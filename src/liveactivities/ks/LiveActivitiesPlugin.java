// SPDX-License-Identifier: Apache-2.0
package liveactivities.ks;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.jxl.kiosk.plugins.KioskPlugin;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Shows Home Assistant activities as native cards over the screensaver. Each kiosk follows the entities its activities
 * read through KS's own Home Assistant connection while the screensaver or the preview shows, and lets go of them
 * otherwise. Home Assistant owns the state; the plugin only presents it and sends the actions a person presses.
 *
 * <p>KS runs the callbacks on its plugin worker. Card buttons call press on the main thread, so every entry point
 * holds this object's lock. None of them blocks: host calls only queue their work.
 */
public final class LiveActivitiesPlugin implements KioskPlugin {
    static final String OVERLAY = "activities";
    static final String SAVER_EVENT = "screensaver.state";
    static final String ENTITY_EVENT = "ha.entity.";
    static final long SENT_MS = 4_000, FAILED_MS = 12_000;

    private PluginHost host;
    private boolean alive;
    private Settings settings;
    private int saverRevision;
    private boolean saverActive;
    private boolean previewing;
    /** Entities subscribed now. An event for any other is late and ignored. */
    private final Set<String> subscribed = new LinkedHashSet<>();
    private final Map<String, Cards.Entity> heard = new HashMap<>();
    /** Per activity key: at most one action out, and what the last one said. */
    private final Map<String, Cards.Pending> pending = new HashMap<>();
    private final Map<String, Cards.Feedback> feedback = new HashMap<>();
    /** Home Assistant's temperature unit, for climate entities without their own step. Asked once per showing. */
    private String temperatureUnit;
    private boolean unitAsked;
    private String subscribeError;
    /** Per card entity, its integration's domain; "" where Home Assistant could not say. For the Integration heading. */
    private final Map<String, String> platforms = new HashMap<>();
    /** Per integration domain, its name, such as LG ThinQ; "" where Home Assistant could not say. */
    private final Map<String, String> integrations = new HashMap<>();
    /** One lookup out at a time. */
    private boolean lookingUp;
    final ActivityOverlay overlay = new ActivityOverlay(this::press);
    private boolean shown;
    private String shownSpec;
    private Cards.Board posted;
    /** Null for the device's clock and current zone. Tests fix the time and the zone. */
    Clock clock;

    @Override public synchronized void start(PluginHost host, Map<String, Object> values) {
        this.host = host;
        alive = true;
        settings = Settings.parse(values);
        host.subscribe(SAVER_EVENT);
        final int revision = saverRevision;
        host.executeCommand("isScreensaverActive", Collections.<String, Object>emptyMap(), (ok, data, error) -> {
            synchronized (this) {
                // An event that arrived after the read was sent is newer, so it wins.
                if (!alive || revision != saverRevision || !ok) return;
                saverActive = Boolean.TRUE.equals(data);
                refresh();
            }
        });
        refresh();
    }

    @Override public synchronized void configure(Map<String, Object> values) {
        settings = Settings.parse(values);
        // A removed activity's action may still answer. Its reply finds no pending entry and is dropped.
        pending.keySet().retainAll(keysOf(settings.followed));
        feedback.keySet().retainAll(keysOf(settings.followed));
        // Only a different set of entities touches Home Assistant; looks alone just redraw.
        refresh();
    }

    @Override public synchronized void execute(String command, Map<String, Object> arguments) {
        if ("preview".equals(command)) previewing = true;
        else if ("endPreview".equals(command)) previewing = false;
        else throw new IllegalArgumentException("Unknown command " + command);
        refresh();
    }

    @Override public synchronized void onEvent(String event, Map<String, Object> payload) {
        if (event.equals("ks." + SAVER_EVENT)) {
            saverRevision++;
            saverActive = Boolean.TRUE.equals(payload.get("active"));
            refresh();
        } else if (event.startsWith("ks." + ENTITY_EVENT)) {
            String id = event.substring(("ks." + ENTITY_EVENT).length());
            if (!subscribed.contains(id)) return; // a late event for an entity no card reads now
            Cards.Entity entity = Cards.Entity.next(heard.get(id), payload);
            heard.put(id, entity);
            // A temperature the card showed while waiting gives way once the entity has spoken since.
            for (Iterator<Map.Entry<String, Cards.Pending>> it = pending.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, Cards.Pending> entry = it.next();
                Settings.Activity activity = settings.followed(entry.getKey());
                if (entry.getValue().done && activity != null && activity.entity.equals(id)) it.remove();
            }
            refresh();
        } else if (event.equals("settings.button")) {
            Object id = payload.get("id");
            if (!"showPreview".equals(id) && !"hidePreview".equals(id)) return;
            previewing = "showPreview".equals(id);
            refresh();
            Object run = payload.get("run");
            if (run instanceof String) host.setupResult((String) run, "success", previewing
                ? "Showing every enabled activity, conditions aside. Their buttons control the real devices. Hide preview takes them away."
                : "Preview hidden.");
        }
    }

    @Override public synchronized void stop() {
        // KS revokes the host, its subscriptions and the overlay before calling stop.
        alive = false;
        host = null;
    }

    /**
     * A card button, on the main thread, with the call it was drawn for. Each press sends one call; nothing is ever
     * sent again on its own. A tap held while settings changed what the button calls is dropped, not redirected.
     */
    synchronized void press(String key, String actionId, String target) {
        Cards.Pending waiting = pending.get(key);
        if (!alive || host == null || (waiting != null && !waiting.done)) return;
        Settings.Activity activity = settings.followed(key);
        Cards.Card card = activity == null ? null : card(activity, ZonedDateTime.now(clock()));
        Cards.Action action = card == null ? null : card.action(actionId);
        if (action == null || !action.enabled || !action.target.equals(target)) return;
        Double temperature = null;
        String label = action.label;
        if ("cooler".equals(actionId) || "warmer".equals(actionId)) {
            Cards.Entity source = heard.get(activity.entity);
            // A second step builds on the target still waiting to be reported.
            Double from = waiting != null && waiting.target != null ? waiting.target : Cards.number(source, "temperature");
            temperature = Cards.adjusted(source, from, Cards.step(source, temperatureUnit), "warmer".equals(actionId) ? 1 : -1);
            if (temperature == null) return;
            label = "target " + Cards.temperature(temperature, Cards.step(source, temperatureUnit), Locale.getDefault());
        }
        final Cards.Pending sent = new Cards.Pending(actionId, label, temperature);
        try {
            host.haCall(Cards.call(activity, actionId, temperature), (ok, data, error) -> answered(key, sent, ok, error));
            pending.put(key, sent);
            feedback.remove(key);
        } catch (IllegalStateException busy) {
            // Over KS's shared limits, or the session just ended. Nothing went out, so the person may press again.
            feedback.put(key, new Cards.Feedback("Kiosk Satellite is busy. Try again.", true, clock().instant().plusMillis(FAILED_MS)));
        }
        refresh();
    }

    private synchronized void answered(String key, Cards.Pending sent, boolean ok, String error) {
        // Stopped, or the activity was removed or changed while the call was out.
        if (!alive || pending.get(key) != sent) return;
        Settings.Activity activity = settings.followed(key);
        Double reported = activity == null ? null : Cards.number(heard.get(activity.entity), "temperature");
        if (ok && sent.target != null && (reported == null || Math.abs(reported - sent.target) > 1e-9)) sent.done = true;
        else pending.remove(key);
        long ms = ok ? SENT_MS : FAILED_MS;
        feedback.put(key, new Cards.Feedback(ok ? capitalize(sent.label) + " sent" : failure(sent.label, error), !ok, clock().instant().plusMillis(ms)));
        refresh();
    }

    /** What went wrong, without "code:". A timeout may still have worked, so it says to look rather than retrying. */
    static String failure(String label, String error) {
        String text = error == null ? "" : error;
        if (text.startsWith("timeout")) return "No answer from Home Assistant. Check the device before trying " + label + " again.";
        if (text.startsWith("busy") || text.startsWith("too_many")) return "Kiosk Satellite is busy. Try again.";
        int colon = text.indexOf(": ");
        String reason = colon >= 0 ? text.substring(colon + 2) : text;
        if (reason.length() > 120) reason = reason.substring(0, 119) + "…";
        return capitalize(label) + " failed" + (reason.isEmpty() ? "." : ": " + reason);
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static Set<String> keysOf(List<Settings.Activity> activities) {
        Set<String> keys = new LinkedHashSet<>();
        for (Settings.Activity activity : activities) keys.add(activity.key);
        return keys;
    }

    /** The overlay and its entities follow the screensaver and the preview: on while either shows, off once both end. */
    private void refresh() {
        if (!alive || host == null) return;
        boolean wanted = (saverActive || previewing) && !settings.followed.isEmpty();
        follow(wanted ? settings.entities : Collections.<String>emptySet());
        if (wanted) {
            lookUp();
        } else {
            // What Home Assistant could not answer is asked again at the next showing.
            unitAsked = false;
            platforms.values().removeIf(String::isEmpty);
            integrations.values().removeIf(String::isEmpty);
        }
        Cards.Board board = board();
        if (!board.equals(posted)) {
            overlay.show(board);
            posted = board;
        }
        try {
            if (wanted) {
                OverlaySpec spec = settings.spec();
                String key = Settings.key(spec);
                if (!shown || !key.equals(shownSpec)) {
                    host.showOverlay(OVERLAY, spec, overlay);
                    shown = true;
                    shownSpec = key;
                }
            } else if (shown) {
                host.hideOverlay(OVERLAY);
                shown = false;
            }
        } catch (IllegalStateException rateLimited) {
            // Over eight shows a second. The next event or settings change tries again.
        }
        report(board);
    }

    /**
     * Subscribes to the entities the cards need and drops the rest, as one batch: each change restarts KS's shared
     * entity connection, so unchanged entities are never touched. What a dropped entity said is forgotten with it, so
     * the next showing waits for fresh state instead of presenting old values as live.
     */
    private void follow(Set<String> needed) {
        for (Iterator<String> it = subscribed.iterator(); it.hasNext();) {
            String id = it.next();
            if (needed.contains(id)) continue;
            host.unsubscribe(ENTITY_EVENT + id);
            it.remove();
            heard.remove(id);
        }
        subscribeError = null;
        for (String id : needed) {
            if (subscribed.contains(id)) continue;
            try {
                host.subscribe(ENTITY_EVENT + id);
                subscribed.add(id);
            } catch (IllegalStateException | IllegalArgumentException refused) {
                // Tried again with the next event or settings change.
                subscribeError = "Kiosk Satellite did not follow " + id + ": " + refused.getMessage();
            }
        }
    }

    private Cards.Board board() {
        ZonedDateTime now = ZonedDateTime.now(clock());
        feedback.values().removeIf(f -> !f.until.isAfter(now.toInstant()));
        List<Cards.Card> cards = new ArrayList<>();
        for (Settings.Activity activity : settings.followed) {
            if (temperatureUnit == null && !unitAsked && subscribed.contains(activity.entity) && Cards.needsUnit(activity, heard.get(activity.entity))) askUnit();
            Cards.Card card = card(activity, now);
            if (card != null) cards.add(card);
        }
        return Cards.board(cards, settings);
    }

    private Cards.Card card(Settings.Activity activity, ZonedDateTime now) {
        String platform = platforms.get(activity.entity);
        String heading = Cards.heading(settings, activity, heard.get(activity.entity), platform == null ? null : integrations.get(platform));
        return Cards.card(activity, heard, pending.get(activity.key), feedback.get(activity.key), now, Locale.getDefault(), previewing,
            temperatureUnit, heading);
    }

    /**
     * Names the integration behind each card for the Integration heading: one registry read for every card entity
     * not known yet, then each integration's manifest, one call at a time, so it never crowds KS's 8 pending calls.
     * manifest/list is avoided: an empty list there answers with every integration loaded.
     */
    private void lookUp() {
        if (lookingUp || !Settings.INTEGRATION.equals(settings.heading)) return;
        List<String> unknown = new ArrayList<>();
        for (String id : settings.sources()) if (!platforms.containsKey(id)) unknown.add(id);
        String domain = null;
        if (unknown.isEmpty()) {
            for (String id : settings.sources()) {
                String platform = platforms.get(id);
                if (!platform.isEmpty() && !integrations.containsKey(platform)) {
                    domain = platform;
                    break;
                }
            }
            if (domain == null) return;
        }
        Map<String, Object> message = new HashMap<>();
        if (domain == null) {
            message.put("type", "config/entity_registry/get_entries");
            message.put("entity_ids", unknown);
        } else {
            message.put("type", "manifest/get");
            message.put("integration", domain);
        }
        final String asked = domain;
        try {
            host.haCall(message, (ok, data, error) -> named(unknown, asked, ok, data));
            lookingUp = true;
        } catch (IllegalStateException busy) {
            // Tried again with the next refresh.
        }
    }

    private synchronized void named(List<String> entities, String domain, boolean ok, Object data) {
        if (!alive) return;
        lookingUp = false;
        Map<?, ?> answer = ok && data instanceof Map ? (Map<?, ?>) data : Collections.emptyMap();
        if (domain == null) {
            for (String id : entities) {
                Object entry = answer.get(id);
                Object platform = entry instanceof Map ? ((Map<?, ?>) entry).get("platform") : null;
                platforms.put(id, platform instanceof String ? (String) platform : "");
            }
        } else {
            Object name = answer.get("name");
            integrations.put(domain, name instanceof String ? ((String) name).trim() : "");
        }
        refresh();
    }

    /** One get_config call: Home Assistant's unit system sets the default temperature step. */
    private void askUnit() {
        unitAsked = true;
        try {
            host.haCall(Collections.<String, Object>singletonMap("type", "get_config"), (ok, data, error) -> {
                synchronized (this) {
                    if (!alive || !ok || !(data instanceof Map)) return;
                    Object system = ((Map<?, ?>) data).get("unit_system");
                    Object unit = system instanceof Map ? ((Map<?, ?>) system).get("temperature") : null;
                    if (unit instanceof String) {
                        temperatureUnit = (String) unit;
                        refresh();
                    }
                }
            });
        } catch (IllegalStateException busy) {
            unitAsked = false; // the next refresh asks again
        }
    }

    /** Looked up on each use, so a time zone change takes effect at once. */
    private Clock clock() {
        return clock != null ? clock : Clock.systemDefaultZone();
    }

    private void report(Cards.Board board) {
        if (settings.activities.isEmpty()) {
            host.status("Add an activity in Settings.", true);
            return;
        }
        List<String> problems = new ArrayList<>(settings.problems);
        if (subscribeError != null) problems.add(subscribeError);
        if (!problems.isEmpty()) {
            host.status(String.join(" ", problems), true);
        } else if (settings.followed.isEmpty()) {
            host.status("Every activity is turned off.", false);
        } else {
            int n = settings.followed.size();
            int showing = board.cards.size();
            host.status(n + (n == 1 ? " activity. " : " activities. ")
                + (shown ? showing + " showing now." : "They show with the screensaver."), false);
        }
    }
}
