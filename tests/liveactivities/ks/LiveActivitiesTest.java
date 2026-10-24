// SPDX-License-Identifier: Apache-2.0
package liveactivities.ks;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import me.jxl.kiosk.plugins.OverlayFactory;
import me.jxl.kiosk.plugins.OverlaySpec;
import me.jxl.kiosk.plugins.PluginHost;

/**
 * Settings, value mapping, visibility, actions and the subscription lifecycle against a fake KS host. Drawing the cards
 * needs a device. tools/test.py runs this once in UTC and once in America/Denver.
 */
public final class LiveActivitiesTest {
    static final ZoneId DENVER = ZoneId.of("America/Denver");
    static final Instant T0 = Instant.parse("2026-10-23T18:00:00Z"); // 12:00 in Denver
    static final ZonedDateTime NOW = T0.atZone(DENVER);

    /** KS's host as the plugin sees it, with KS's limits where the plugin must respect them. */
    static final class Host implements PluginHost {
        final Set<String> subscriptions = new LinkedHashSet<>();
        /** "+ha.entity.x", "-ha.entity.x", "show", "hide" in call order. */
        final List<String> log = new ArrayList<>();
        OverlaySpec spec;
        OverlayFactory factory;
        CommandCallback saverRead;
        final List<Map<String, Object>> sent = new ArrayList<>();
        final List<CommandCallback> replies = new ArrayList<>();
        boolean busy, subscribeRefused;
        String status;
        boolean statusError;
        final List<String> results = new ArrayList<>();

        public void executeCommand(String command, Map<String, Object> arguments, CommandCallback callback) {
            assert command.equals("isScreensaverActive") : command;
            saverRead = callback;
        }
        public void subscribe(String event) {
            if (event.startsWith("ha.entity.")) {
                if (subscribeRefused) throw new IllegalStateException("too_many_requests");
                long entities = subscriptions.stream().filter(e -> e.startsWith("ha.entity.")).count();
                // KS silently ignores a 17th entity, so the plugin must never ask for one.
                assert entities < 16 || subscriptions.contains(event) : "a 17th entity subscription";
                assert !subscriptions.contains(event) : "subscribed twice to " + event;
                log.add("+" + event);
            }
            subscriptions.add(event);
        }
        public void unsubscribe(String event) {
            assert subscriptions.contains(event) : "unsubscribed from " + event + " without a subscription";
            subscriptions.remove(event);
            log.add("-" + event);
        }
        public void haCall(Map<String, Object> message, CommandCallback callback) {
            if (busy) throw new IllegalStateException("too_many_pending");
            sent.add(message);
            replies.add(callback);
        }
        public void showOverlay(String key, OverlaySpec spec, OverlayFactory factory) {
            assert key.equals(LiveActivitiesPlugin.OVERLAY) && factory != null;
            this.spec = spec;
            this.factory = factory;
            log.add("show");
        }
        public void hideOverlay(String key) { log.add("hide"); }
        public boolean setupResult(String run, String state, String message) { results.add(run + " " + state + ": " + message); return true; }
        public void showWindow(String title, String message, String button) {}
        public void hideWindow() {}
        public void log(String message) {}
        public void status(String message, boolean error) { status = message; statusError = error; }

        List<String> entityLog() {
            List<String> out = new ArrayList<>();
            for (String line : log) if (line.startsWith("+") || line.startsWith("-")) out.add(line);
            return out;
        }
        int count(String line) { return Collections.frequency(log, line); }
        Set<String> entities() {
            Set<String> out = new TreeSet<>();
            for (String s : subscriptions) if (s.startsWith("ha.entity.")) out.add(s.substring("ha.entity.".length()));
            return out;
        }
    }

    static Map<String, Object> map(Object... pairs) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) m.put((String) pairs[i], pairs[i + 1]);
        return m;
    }

    static Map<String, Object> settings(Object... rowsThenPairs) {
        return map(rowsThenPairs);
    }

    static List<Object> rows(Object... rows) { return new ArrayList<>(Arrays.asList(rows)); }

    static Map<String, Object> value(String show, String entity, String attribute, Object... more) {
        Map<String, Object> row = map("show", show, "entity", entity, "attribute", attribute, "label", "", "unit", "", "min", 0, "max", 100);
        row.putAll(map(more));
        return row;
    }

    /** A complete activity row, as KS delivers it with every field filled in. */
    static Map<String, Object> activity(String type, String entity, Object... more) {
        Map<String, Object> row = map("type", type, "entity", entity, "enabled", true, "title", "", "icon", "", "layout", "Automatic",
            "values", new ArrayList<Object>(), "showWhen", "Always", "whenEntity", "", "whenStates", "", "controls", false,
            "action", "", "actionLabel", "");
        row.putAll(map(more));
        return row;
    }

    /** A vacuum's Controls rows, as KS saves them. */
    static List<Object> controls(String... picked) {
        List<Object> out = new ArrayList<>();
        for (String control : picked) out.add(map("control", control));
        return out;
    }

    static Map<String, Object> payload(String status, String state, Object... attributes) {
        return map("entityId", "x.y", "status", status, "state", state, "attributes", map(attributes), "time", "2026-10-23T18:00:00Z");
    }

    static Cards.Entity entity(String status, String state, Object... attributes) {
        return Cards.Entity.next(null, payload(status, state, attributes));
    }

    static void hear(LiveActivitiesPlugin plugin, String id, String status, String state, Object... attributes) {
        plugin.onEvent("ks.ha.entity." + id, payload(status, state, attributes));
    }

    static Settings.Activity first(Map<String, Object> row) {
        return Settings.parse(map("activities", rows(row))).activities.get(0);
    }

    static Cards.Card card(Map<String, Object> row, Map<String, Cards.Entity> heard) {
        return Cards.card(first(row), heard, null, null, NOW, Locale.US, false, null, "Home Assistant");
    }

    static Map<String, Cards.Entity> heard(Object... idsThenEntities) {
        Map<String, Cards.Entity> m = new HashMap<>();
        for (int i = 0; i < idsThenEntities.length; i += 2) m.put((String) idsThenEntities[i], (Cards.Entity) idsThenEntities[i + 1]);
        return m;
    }

    static String text(Cards.Value value) {
        return value == null ? null : value.format(NOW, Locale.US).replace(' ', ' '); // JDK 20+ puts a narrow space before PM
    }

    static Cards.Board board(LiveActivitiesPlugin plugin) { return plugin.overlay.board(); }

    /** A tap on the button as the card draws it now, which hands back the call it was drawn for. */
    static void tap(LiveActivitiesPlugin plugin, String key, String id) {
        String target = null;
        for (Cards.Card card : board(plugin).cards) if (card.key.equals(key) && card.action(id) != null) target = card.action(id).target;
        plugin.press(key, id, target);
    }
    static Cards.Card only(LiveActivitiesPlugin plugin) {
        Cards.Board b = board(plugin);
        assert b.cards.size() == 1 : b.cards.size();
        return b.cards.get(0);
    }

    public static void main(String[] args) {
        Locale.setDefault(Locale.US);
        settingsAndPositioning();
        values();
        visibility();
        progressAndTargets();
        actionsOnCards();
        lifecycle();
        actions();
        climateControls();
        touchGate();
        headingAndColors();
        integrationHeading();
        System.out.println("LiveActivitiesTest passed in " + TimeZone.getDefault().getID());
    }

    static void settingsAndPositioning() {
        Settings empty = Settings.parse(new HashMap<String, Object>());
        assert empty.activities.isEmpty() && empty.followed.isEmpty() && empty.entities.isEmpty();
        assert empty.maxCards == 3 && empty.scale == 100 && empty.anchor.equals(OverlaySpec.TOP_LEFT);

        // Identity: type and entity, numbered among repeats, so a reorder or a new title keeps it and every kiosk agrees.
        Settings two = Settings.parse(map("activities", rows(activity("Vacuum", "vacuum.rex"), activity("Climate", "climate.hall"),
            activity("Vacuum", "vacuum.rex", "title", "Again"), activity("Custom", ""), "not a row")));
        List<String> keys = new ArrayList<>();
        for (Settings.Activity a : two.activities) keys.add(a.key);
        assert keys.equals(Arrays.asList("Vacuum:vacuum.rex", "Climate:climate.hall", "Vacuum:vacuum.rex#1")) : keys;
        Settings swapped = Settings.parse(map("activities", rows(activity("Climate", "climate.hall", "title", "Hall"), activity("Vacuum", "vacuum.rex"))));
        assert swapped.activities.get(0).key.equals("Climate:climate.hall") && swapped.activities.get(1).key.equals("Vacuum:vacuum.rex");

        // Values: an empty entity reads the activity's, the first row of each kind wins.
        Settings.Activity washer = first(activity("Appliance", "sensor.washer_state", "values", rows(
            value("Progress", "sensor.washer_progress", "", "min", 0, "max", 100),
            value("Primary value", "", "remaining"),
            value("Primary value", "sensor.ignored", ""),
            value("Secondary value", "binary_sensor.washer_door", "", "label", "Door"))));
        assert washer.values.get(Settings.PRIMARY).entity.equals("sensor.washer_state") && washer.values.get(Settings.PRIMARY).attribute.equals("remaining");
        assert washer.values.get(Settings.SECONDARY).label.equals("Door");
        assert washer.entities().equals(new LinkedHashSet<>(Arrays.asList("sensor.washer_state", "sensor.washer_progress", "binary_sensor.washer_door")));

        // Conditions: states are trimmed and lowercased, the condition entity defaults to the activity's.
        Settings.Activity conditional = first(activity("Appliance", "sensor.dw", "showWhen", "When state is", "whenStates", " Run, pause ,, "));
        assert conditional.whenStates.equals(new LinkedHashSet<>(Arrays.asList("run", "pause"))) && conditional.whenEntity.equals("sensor.dw");
        assert first(activity("Appliance", "sensor.dw", "showWhen", "When state is", "whenEntity", "binary_sensor.dw_on")).entities().contains("binary_sensor.dw_on");
        assert !first(activity("Appliance", "sensor.dw", "whenEntity", "binary_sensor.dw_on")).entities().contains("binary_sensor.dw_on") : "Always reads no condition entity";

        // Controls apply to their own types only: hidden fields keep their saved values.
        assert !first(activity("Custom", "sensor.x", "controls", true)).controls;
        assert !first(activity("Vacuum", "vacuum.rex", "controls", true)).controls : "the Cooler/Warmer switch is Climate's";
        assert first(activity("Vacuum", "vacuum.rex", "controls", true)).vacuumControls.isEmpty() : "the old switch picks no vacuum controls";
        assert first(activity("Climate", "climate.hall", "controls", true)).controls;
        Settings.Activity picked = first(activity("Vacuum", "vacuum.rex", "vacuumControls", controls("Locate", "Pause", "Bogus", "Locate", "Dock")));
        assert picked.vacuumControls.equals(Arrays.asList("Locate", "Pause", "Dock")) : "in order, each once, unknown ones dropped";
        assert first(activity("Custom", "sensor.x", "vacuumControls", controls("Pause"))).vacuumControls.isEmpty() : "only a vacuum has them";
        assert first(activity("Vacuum", "vacuum.rex", "action", "script.go")).action.isEmpty();
        assert first(activity("Custom", "sensor.x", "action", "script.go")).action.equals("script.go");

        // KS follows 16 entities. An activity that would pass it is left out whole and named; disabled ones cost nothing.
        List<Object> many = rows(activity("Custom", "sensor.off", "enabled", false));
        for (int i = 0; i < 5; i++) {
            many.add(activity("Custom", "sensor.a" + i, "values", rows(value("Primary value", "sensor.b" + i, ""), value("Secondary value", "sensor.c" + i, ""))));
        }
        Settings crowded = Settings.parse(map("activities", many));
        assert crowded.followed.size() == 5 && crowded.entities.size() == 15 && !crowded.entities.contains("sensor.off");
        many.add(activity("Custom", "sensor.a0")); // shares an entity, so it fits
        many.add(activity("Custom", "sensor.d", "values", rows(value("Primary value", "sensor.e", ""))));
        many.add(activity("Custom", "sensor.f"));
        Settings full = Settings.parse(map("activities", many));
        assert full.entities.size() == 16 && full.followed.size() == 7 : full.entities.size();
        assert full.problems.size() == 1 && full.problems.get(0).startsWith("sensor.d is left out") : full.problems;

        // Setup mistakes reach the status.
        Settings wrong = Settings.parse(map("activities", rows(activity("Vacuum", "sensor.rex", "title", "Rex"), activity("Climate", "sensor.t"),
            activity("Custom", "sensor.x", "action", "light.kitchen"), activity("Custom", "sensor.y", "showWhen", "When state is"),
            activity("Custom", "sensor.z", "values", rows(value("Progress", "", "", "min", 50, "max", 50))))));
        assert wrong.problems.equals(Arrays.asList("Rex: Vacuum needs a vacuum entity.", "sensor.t: Climate needs a climate entity.",
            "sensor.x: the action must be a script or a button.", "sensor.y: list the states it shows for.",
            "sensor.z: the progress maximum must be above its minimum.")) : wrong.problems;

        // Screensaver Agenda's positions: every one maps to its anchor, an unknown one to the top left.
        String[][] positions = {
            {"Top left", OverlaySpec.TOP_LEFT, "left"}, {"Top middle", OverlaySpec.TOP, "center"}, {"Top right", OverlaySpec.TOP_RIGHT, "right"},
            {"Left middle", OverlaySpec.LEFT, "left"}, {"Center", OverlaySpec.CENTER, "center"}, {"Right middle", OverlaySpec.RIGHT, "right"},
            {"Bottom left", OverlaySpec.BOTTOM_LEFT, "left"}, {"Bottom middle", OverlaySpec.BOTTOM, "center"}, {"Bottom right", OverlaySpec.BOTTOM_RIGHT, "right"},
            {"Custom", OverlaySpec.TOP_LEFT, "left"}, {"Sideways", OverlaySpec.TOP_LEFT, "left"}};
        for (String[] p : positions) {
            Settings s = Settings.parse(map("position", p[0]));
            assert s.anchor.equals(p[1]) && s.align().equals(p[2]) : p[0] + " -> " + s.anchor + " " + s.align();
            OverlaySpec spec = s.spec();
            // Always touchable: "+N more" can appear whenever a short screen leaves a card out.
            assert spec.onTop() && !spec.closeOnBack() && spec.touchable() && spec.anchor().equals(p[1]);
        }
        // Custom: x and y from the top left with no inset. Presets: an inset that grows with Size, and x and y ignored.
        OverlaySpec custom = Settings.parse(map("position", "Custom", "x", 40.0, "y", 180.0)).spec();
        assert custom.anchor().equals(OverlaySpec.TOP_LEFT) && custom.offsetX() == 40 && custom.offsetY() == 180 && custom.inset() == 0;
        OverlaySpec clamped = Settings.parse(map("position", "Custom", "x", -5, "y", 99999)).spec();
        assert clamped.offsetX() == 0 && clamped.offsetY() == 4096;
        OverlaySpec corner = Settings.parse(map("position", "Bottom right", "x", 40.0, "y", 180.0, "scale", 150)).spec();
        assert corner.offsetX() == 0 && corner.offsetY() == 0 && corner.insetLeft() == 36 && corner.insetBottom() == 36;
        assert Settings.parse(map("position", "Top left")).spec().inset() == 24;
        assert Settings.key(Settings.parse(map("position", "Center", "x", 40.0)).spec()).equals(Settings.key(Settings.parse(map("position", "Center", "x", 41.0)).spec()))
            : "hidden x does not count";
        assert !Settings.key(Settings.parse(map("position", "Custom", "x", 40.0)).spec()).equals(Settings.key(Settings.parse(map("position", "Custom", "x", 41.0)).spec()));
        Settings sized = Settings.parse(map("maxCards", 9, "scale", 500));
        assert sized.maxCards == 6 && sized.scale == 200 && Settings.parse(map("maxCards", 0)).maxCards == 1;
    }

    static void values() {
        // States with units, numbers by size, and the kiosk's decimal separator.
        assert text(Cards.read(entity("available", "80", "unit_of_measurement", "%"), "", "", Locale.US)).equals("80%");
        assert text(Cards.read(entity("available", "1234.6", "unit_of_measurement", "W"), "", "", Locale.US)).equals("1235 W");
        assert text(Cards.read(entity("available", "21.53", "unit_of_measurement", "°C"), "", "", Locale.US)).equals("21.5°C");
        assert text(Cards.read(entity("available", "0.123", "unit_of_measurement", "kWh"), "", "", Locale.US)).equals("0.12 kWh");
        assert text(Cards.read(entity("available", "3.5", "unit_of_measurement", "kW"), "", "MW", Locale.US)).equals("3.5 MW") : "the row's unit wins";
        assert Cards.number(21.5, Locale.GERMANY).equals("21,5") && Cards.number(-0.001, Locale.US).equals("0") && Cards.number(-3.25, Locale.US).equals("-3.25");
        assert Cards.temperature(22, 0.5, Locale.US).equals("22.0°") && Cards.temperature(72, 1, Locale.US).equals("72°");
        assert Cards.temperature(21.5, Double.NaN, Locale.US).equals("21.5°");

        // Unknown, unavailable and missing are each named, never zero, off or done.
        assert text(Cards.read(entity("unknown", "unknown"), "", "", Locale.US)).equals("Unknown");
        assert text(Cards.read(entity("unavailable", "unavailable"), "", "", Locale.US)).equals("Unavailable");
        assert text(Cards.read(entity("missing", null), "", "", Locale.US)).equals("Not found");
        assert text(Cards.read(entity("too_large", null), "", "", Locale.US)).equals("Too large");
        assert text(Cards.read(null, "", "", Locale.US)).equals(Cards.NONE);
        assert text(Cards.read(entity("connecting", null), "", "", Locale.US)).equals(Cards.NONE);

        // Attributes: numbers, yes/no, words, and nothing for missing, null or structured values.
        Cards.Entity vacuum = entity("available", "cleaning", "fan_speed", "turbo_max", "battery", 80, "charging", false, "rooms", Arrays.asList("a", "b"), "error", null);
        assert text(Cards.read(vacuum, "battery", "%", Locale.US)).equals("80%");
        assert text(Cards.read(vacuum, "charging", "", Locale.US)).equals("No");
        assert text(Cards.read(vacuum, "fan_speed", "", Locale.US)).equals("Turbo max");
        assert text(Cards.read(vacuum, "rooms", "", Locale.US)).equals(Cards.NONE);
        assert text(Cards.read(vacuum, "error", "", Locale.US)).equals(Cards.NONE);
        assert text(Cards.read(vacuum, "nonexistent", "", Locale.US)).equals(Cards.NONE);
        assert text(Cards.read(vacuum, "", "", Locale.US)).equals("Cleaning");

        // Malformed numbers stay words: Java would read NaN, Infinity, hex and 1d as numbers.
        for (String bad : new String[] {"NaN", "Infinity", "0x10", "1d", "12abc", "", " ", "1e309", "-1e309"}) assert Cards.parse(bad) == null : bad;
        assert Cards.parse("-3.5") == -3.5 && Cards.parse("1e3") == 1000 && Cards.parse(" 42 ") == 42;
        assert text(Cards.read(entity("available", "NaN"), "", "", Locale.US)).equals("NaN");
        assert Cards.number(entity("available", "NaN"), "") == null && Cards.number(entity("unknown", "unknown"), "") == null;
        assert Cards.number(entity("available", "x", "t", Double.NaN), "t") == null;

        // Finish times count down in whole minutes, rounded up. A past one shows its time, never "done".
        Cards.Value finish = Cards.read(entity("available", "2026-10-23T19:04:00+00:00", "device_class", "timestamp"), "", "", Locale.US);
        assert finish.until != null && text(finish).equals("1h 4m");
        assert Cards.remaining(T0.plusSeconds(30), NOW, Locale.US).equals("1m");
        assert Cards.remaining(T0.plusSeconds(86400 + 3 * 3600 + 60), NOW, Locale.US).equals("1d 3h");
        String past = Cards.remaining(T0.minusSeconds(3600), NOW, Locale.US).replace(' ', ' ');
        assert past.equals("11:00 AM") : past;
        assert Cards.remaining(T0.minusSeconds(3600), T0.atZone(ZoneOffset.UTC), Locale.US).replace(' ', ' ').equals("5:00 PM") : "in UTC";
        String yesterday = Cards.remaining(T0.minusSeconds(86400), NOW, Locale.US).replace(' ', ' ');
        assert yesterday.equals("10/22/26, 12:00 PM") : yesterday;
        assert Cards.instant("2026-10-23T19:04:00") == null && Cards.instant("2026-10-23") == null && Cards.instant("not a time at all, really") == null;
        assert Cards.instant("2026-10-23T19:04:00.123456+00:00") != null;
        // Durations: Home Assistant's duration sensors in any unit.
        assert text(Cards.read(entity("available", "64", "device_class", "duration", "unit_of_measurement", "min"), "", "", Locale.US)).equals("1h 4m");
        assert text(Cards.read(entity("available", "3600", "device_class", "duration", "unit_of_measurement", "s"), "", "", Locale.US)).equals("1h");
        assert text(Cards.read(entity("available", "1.5", "device_class", "duration", "unit_of_measurement", "h"), "", "", Locale.US)).equals("1h 30m");
        assert text(Cards.read(entity("available", "2", "device_class", "duration", "unit_of_measurement", "d"), "", "", Locale.US)).equals("2d");
        assert text(Cards.read(entity("available", "45", "unit_of_measurement", "min"), "", "", Locale.US)).equals("45 min") : "only duration sensors become 1h 4m";
        assert Cards.humanize("heat_cool").equals("Heat cool") && Cards.humanize(" ").equals(Cards.NONE) && Cards.humanize("Door Locked").equals("Door Locked");

        // A lost connection keeps what was last heard, marked as such; missing and too large drop it.
        Cards.Entity live = entity("available", "cleaning", "supported_features", 8212);
        Cards.Entity lost = Cards.Entity.next(live, payload("disconnected", null));
        assert lost.status.equals("disconnected") && "cleaning".equals(lost.state) && lost.known;
        assert Cards.Entity.next(lost, payload("connecting", null)).known;
        assert !Cards.Entity.next(live, payload("missing", null)).known && Cards.Entity.next(live, payload("missing", null)).state == null;
        assert Cards.connection(Arrays.asList(live, live)).equals(Cards.LIVE);
        assert Cards.connection(Arrays.asList(live, null)).equals(Cards.CONNECTING) : "an entity not heard yet";
        assert Cards.connection(Arrays.asList(live, entity("connecting", null))).equals(Cards.CONNECTING);
        assert Cards.connection(Arrays.asList(live, lost)).equals(Cards.RECONNECTING);
        assert Cards.connection(Arrays.asList(entity("disconnected", null))).equals(Cards.UNAVAILABLE) : "never connected";
        assert Cards.connection(Arrays.asList(lost, entity("not_configured", null))).equals(Cards.UNAVAILABLE);
        assert Cards.connection(Arrays.asList(entity("missing", null), entity("unavailable", "unavailable"))).equals(Cards.LIVE)
            : "a missing or unavailable entity is Home Assistant speaking, not a lost connection";
    }

    static void visibility() {
        Map<String, Object> always = activity("Appliance", "sensor.dw", "title", "Dishwasher");
        assert card(always, heard()) == null : "nothing heard yet";
        assert card(always, heard("sensor.dw", entity("available", "Eco 50°C"))).status.text.equals("Eco 50°C");
        assert card(activity("Appliance", "sensor.dw", "enabled", false), heard("sensor.dw", entity("available", "run"))) == null;

        Map<String, Object> running = activity("Appliance", "sensor.dw", "showWhen", "When state is", "whenStates", "run, pause");
        assert card(running, heard("sensor.dw", entity("available", "run"))) != null;
        assert card(running, heard("sensor.dw", entity("available", "Run"))) != null : "states match regardless of case";
        assert card(running, heard("sensor.dw", entity("available", "ready"))) == null;
        assert card(running, heard("sensor.dw", entity("unknown", "unknown"))) == null : "unknown is not run";
        assert card(running, heard("sensor.dw", entity("missing", null))) == null;
        assert card(activity("Appliance", "sensor.dw", "showWhen", "When state is", "whenStates", "unknown"), heard("sensor.dw", entity("unknown", "unknown"))) != null
            : "unknown matches when listed";
        // A paused activity is still shown, and still live.
        Cards.Card paused = card(running, heard("sensor.dw", entity("available", "pause")));
        assert paused.live() && paused.status.text.equals("Pause");

        Map<String, Object> notOff = activity("Custom", "sensor.ev_power", "showWhen", "When state is not", "whenEntity", "binary_sensor.ev_charging", "whenStates", "off");
        Cards.Entity power = entity("available", "7.2", "unit_of_measurement", "kW");
        assert card(notOff, heard("sensor.ev_power", power, "binary_sensor.ev_charging", entity("available", "on"))) != null;
        assert card(notOff, heard("sensor.ev_power", power, "binary_sensor.ev_charging", entity("available", "off"))) == null;
        assert card(notOff, heard("sensor.ev_power", power)) == null : "an unheard condition never shows";
        // unknown and unavailable never pass as "not off": only When state is shows them, and only when listed.
        assert card(notOff, heard("sensor.ev_power", power, "binary_sensor.ev_charging", entity("unknown", "unknown"))) == null;
        assert card(notOff, heard("sensor.ev_power", power, "binary_sensor.ev_charging", entity("unavailable", "unavailable"))) == null;
        assert card(notOff, heard("sensor.ev_power", power, "binary_sensor.ev_charging", entity("available", "Unavailable"))) == null : "in any case";
        Map<String, Object> notOffListed = activity("Custom", "sensor.ev_power", "showWhen", "When state is not", "whenEntity", "binary_sensor.ev_charging", "whenStates", "off, unknown");
        assert card(notOffListed, heard("sensor.ev_power", power, "binary_sensor.ev_charging", entity("unknown", "unknown"))) == null;
        assert card(notOffListed, heard("sensor.ev_power", power, "binary_sensor.ev_charging", entity("available", "on"))) != null;
        Map<String, Object> isListed = activity("Custom", "sensor.ev_power", "showWhen", "When state is", "whenEntity", "binary_sensor.ev_charging", "whenStates", "on, unavailable");
        assert card(isListed, heard("sensor.ev_power", power, "binary_sensor.ev_charging", entity("unavailable", "unavailable"))) != null;
        assert card(isListed, heard("sensor.ev_power", power, "binary_sensor.ev_charging", entity("unknown", "unknown"))) == null : "unknown is not listed";
        Cards.Card previewed = Cards.card(first(notOff), heard("sensor.ev_power", power, "binary_sensor.ev_charging", entity("unknown", "unknown")), null, null, NOW, Locale.US, true, null, "Home Assistant");
        assert previewed != null && previewed.note.startsWith("Hidden outside the preview") : "the preview still shows it, with the reason";

        // A card shown when the connection drops stays, saying Reconnecting over its last values.
        Cards.Entity run = entity("available", "run");
        Cards.Card dropped = card(running, heard("sensor.dw", Cards.Entity.next(run, payload("disconnected", null))));
        assert dropped != null && dropped.connection.equals(Cards.RECONNECTING) && !dropped.live() && dropped.status.text.equals("Run");

        // The preview shows a hidden card too, and says why it would be hidden.
        Cards.Card preview = Cards.card(first(running), heard("sensor.dw", entity("available", "ready")), null, null, NOW, Locale.US, true, null, "Home Assistant");
        assert preview != null && preview.note.startsWith("Hidden outside the preview") && !preview.noteError;

        // Title and icon: the setting, else the entity's own, else the type's.
        assert card(always, heard("sensor.dw", entity("available", "run", "friendly_name", "Bosch", "icon", "mdi:dishwasher"))).title.equals("Dishwasher");
        Cards.Card named = card(activity("Appliance", "sensor.dw"), heard("sensor.dw", entity("available", "run", "friendly_name", "Bosch", "icon", "mdi:dishwasher")));
        assert named.title.equals("Bosch") && named.icon.equals("mdi:dishwasher");
        Cards.Card plain = card(activity("Vacuum", "vacuum.rex"), heard("vacuum.rex", entity("available", "docked")));
        assert plain.title.equals("vacuum.rex") && plain.icon.equals("mdi:robot-vacuum") && plain.layout.equals(Settings.STATUS);

        // Order follows the settings, never the values. The board keeps every card, so "+N more" can open them all.
        List<Cards.Card> eight = new ArrayList<>();
        for (String id : new String[] {"sensor.h", "sensor.a", "sensor.g", "sensor.b", "sensor.f", "sensor.c", "sensor.e", "sensor.d"}) {
            eight.add(card(activity("Custom", id, "action", "script.go"), heard(id, entity("available", "1"))));
        }
        Cards.Board stack = Cards.board(eight, Settings.parse(map("maxCards", 3, "position", "Bottom right", "scale", 120)));
        assert stack.cards.size() == 8 && stack.limit == 3 && stack.overflow() == 5 && stack.align.equals("right") && stack.scale == 120;
        assert stack.cards.get(0).key.equals("Custom:sensor.h") && stack.cards.get(7).key.equals("Custom:sensor.d");
        assert stack.cards.get(7).actions.toString().equals("[run]") : "an overflow card keeps its controls for the expanded list";
        assert Cards.board(eight.subList(0, 2), Settings.parse(map("maxCards", 3))).overflow() == 0;

        // Narrow cards: the value column takes at most 45% of what the icon and gaps leave, measured on the card as
        // drawn, so the title and status never lose their width. 240 dp card at 100%: 208 inside, 196 offered.
        int value = ActivityOverlay.cap(196, 44, ActivityOverlay.VALUE_SHARE);
        assert value == 68 && 208 - 44 - 12 - value == 84 : value;
        for (int inside = 56; inside <= 2000; inside++) {
            int column = ActivityOverlay.cap(inside - 12, 44, ActivityOverlay.VALUE_SHARE);
            int title = inside - 44 - 12 - column;
            assert title >= 0 && title >= column : inside + " dp leaves the title " + title;
        }
        assert ActivityOverlay.cap(30, 44, ActivityOverlay.VALUE_SHARE) == 0 : "no room, no column";
        int chip = ActivityOverlay.cap(192, 0, ActivityOverlay.CHIP_SHARE);
        assert chip == 77 && (208 - 16 - chip) / 2 >= 57 : "each reading keeps 30% of the row";

        // Buttons wrap rather than squeeze their labels, in order, in balanced rows. Natural widths at 100%: padding 24,
        // icon 18 and its gap 6, plus the label; Stop, Pause, Dock and Locate come to about 82, 88, 84 and 94 dp.
        int[] fourLabelled = {88, 84, 94, 82};
        assert ActivityOverlay.columns(fourLabelled, 208, 8) == 2 : "240 dp card: two rows of two, each 100 dp";
        assert ActivityOverlay.columns(fourLabelled, 328, 8) == 2 : "360 dp card: three would fit, but two and two beats three and one";
        assert ActivityOverlay.columns(fourLabelled, 400, 8) == 4 : "wide enough for all four";
        int[] threeLabelled = {88, 84, 94};
        assert ActivityOverlay.columns(threeLabelled, 328, 8) == 3 : "the usual card keeps three in a row";
        assert ActivityOverlay.columns(threeLabelled, 208, 8) == 2 : "a narrow card wraps the third, which then takes the row";
        assert ActivityOverlay.columns(new int[] {46, 46, 46, 46}, 208, 8) == 4 : "icon-only buttons (24 padding, 22 icon) stay in one row";
        assert ActivityOverlay.columns(new int[] {300, 80}, 208, 8) == 1 && ActivityOverlay.columns(new int[0], 208, 8) == 0;
        for (int width = 60; width <= 1200; width++) {
            int k = ActivityOverlay.columns(fourLabelled, width, 8);
            int cell = (width - 8 * (k - 1)) / k;
            for (int natural : fourLabelled) assert k == 1 || natural <= cell : width + " dp squeezes a label";
        }

        // Fitting the screen: whole cards leave from the end, down to none, and "+N more" always has room when needed.
        int[] heights = {100, 100, 100};
        assert Cards.fit(heights, 10, 30, 400, 0) == 3 : "320 fits";
        assert Cards.fit(heights, 10, 30, 320, 0) == 3;
        assert Cards.fit(heights, 10, 30, 250, 0) == 2 : "two cards and the more button";
        assert Cards.fit(heights, 10, 30, 249, 0) == 1;
        assert Cards.fit(heights, 10, 30, 140, 0) == 1 : "a card, a gap and the button";
        assert Cards.fit(heights, 10, 30, 139, 0) == 0 : "a card that cannot fit whole is left out, not cut off";
        assert Cards.fit(heights, 10, 30, 50, 0) == 0 : "only +3 more shows";
        assert Cards.fit(new int[] {100}, 10, 30, 100, 0) == 1 : "a lone card that fits needs no button";
        assert Cards.fit(new int[] {100}, 10, 30, 99, 0) == 0;
        assert Cards.fit(heights, 10, 30, 320, 2) == 2 : "Maximum cards already left some out, so the button needs room";
        assert Cards.fit(new int[0], 10, 30, 320, 0) == 0 && Cards.fit(new int[0], 10, 30, 320, 4) == 0;
    }

    static void progressAndTargets() {
        Map<String, Object> washer = activity("Appliance", "sensor.washer_state", "values", rows(value("Progress", "sensor.washer_progress", "")));
        Cards.Card half = card(washer, heard("sensor.washer_state", entity("available", "run"), "sensor.washer_progress", entity("available", "47", "unit_of_measurement", "%")));
        assert half.layout.equals(Settings.PROGRESS) && Math.abs(half.progress - 0.47) < 1e-9 && text(half.primary).equals("47%");
        assert card(washer, heard("sensor.washer_state", entity("available", "run"), "sensor.washer_progress", entity("available", "120"))).progress == 1.0;
        Cards.Card below = card(washer, heard("sensor.washer_state", entity("available", "run"), "sensor.washer_progress", entity("available", "-5")));
        assert below.progress == 0.0 && text(below.primary).equals("0%");
        Cards.Card unknown = card(washer, heard("sensor.washer_state", entity("available", "run"), "sensor.washer_progress", entity("unknown", "unknown")));
        assert unknown.progress == null && text(unknown.primary).equals("Unknown") : "no bar, and never 0%";
        assert card(washer, heard("sensor.washer_state", entity("available", "run"))).progress == null : "a progress entity not heard yet";
        Cards.Card overflowing = card(washer, heard("sensor.washer_state", entity("available", "run"), "sensor.washer_progress", entity("available", "1e309")));
        assert overflowing.progress == null && text(overflowing.primary).equals("1e309") : "infinity is not a full bar";
        // A range: an EV charging from 20 to 80 kWh.
        Map<String, Object> ev = activity("Custom", "sensor.ev", "values", rows(value("Progress", "", "", "min", 20, "max", 80)));
        assert Math.abs(card(ev, heard("sensor.ev", entity("available", "50"))).progress - 0.5) < 1e-9;
        Map<String, Object> empty = activity("Custom", "sensor.ev", "values", rows(value("Progress", "", "", "min", 80, "max", 20)));
        Cards.Card bad = card(empty, heard("sensor.ev", entity("available", "50")));
        assert bad.progress == null && text(bad.primary).equals(Cards.NONE);
        // Time left on the right when bound, the bar below; the dishwasher layout.
        Map<String, Object> dishwasher = activity("Appliance", "sensor.dw_state", "title", "Dishwasher", "values", rows(
            value("Progress", "sensor.dw_progress", ""), value("Primary value", "sensor.dw_finish", ""),
            value("Status", "sensor.dw_program", ""), value("Secondary value", "binary_sensor.dw_door", "", "label", "Door")));
        Cards.Card dw = card(dishwasher, heard("sensor.dw_state", entity("available", "run"), "sensor.dw_progress", entity("available", "40"),
            "sensor.dw_finish", entity("available", "2026-10-23T19:04:00+00:00", "device_class", "timestamp"), "sensor.dw_program", entity("available", "eco_50"),
            "binary_sensor.dw_door", entity("available", "off")));
        assert text(dw.primary).equals("1h 4m") && text(dw.status).equals("Eco 50") && dw.secondaryLabel.equals("Door") && text(dw.secondary).equals("Off");
        assert Math.abs(dw.progress - 0.4) < 1e-9 && dw.live();

        // Climate: current and target, the mode under the title and what it is doing between them.
        Map<String, Object> thermostat = activity("Climate", "climate.hall", "title", "Hallway");
        Cards.Entity heating = entity("available", "heat", "current_temperature", 21.5, "temperature", 22, "hvac_action", "heating", "target_temp_step", 0.5);
        Cards.Card hall = card(thermostat, heard("climate.hall", heating));
        assert hall.layout.equals(Settings.CURRENT_TARGET) && text(hall.current).equals("21.5°") && text(hall.target).equals("22.0°");
        assert hall.operating.equals("Heating") && text(hall.status).equals("Heat mode") && hall.currentLabel.equals("Current") && hall.targetLabel.equals("Target");
        Cards.Card off = card(thermostat, heard("climate.hall", entity("available", "off", "current_temperature", 19, "temperature", null)));
        assert text(off.status).equals("Off") && text(off.target).equals(Cards.NONE) && off.operating == null;
        Cards.Card range = card(thermostat, heard("climate.hall", entity("available", "heat_cool", "current_temperature", 21, "target_temp_low", 20, "target_temp_high", 24, "target_temp_step", 0.5)));
        assert text(range.target).equals("20.0° – 24.0°") && text(range.status).equals("Heat cool mode");
        assert text(card(thermostat, heard("climate.hall", entity("available", "heat"))).current).equals(Cards.NONE);
        // Any entity can fill the current/target layout, with its own labels.
        Map<String, Object> pool = activity("Custom", "sensor.pool", "values", rows(value("Current", "", "", "label", "Water"), value("Target", "input_number.pool_target", "", "label", "Goal")));
        Cards.Card water = card(pool, heard("sensor.pool", entity("available", "27.3", "unit_of_measurement", "°C"), "input_number.pool_target", entity("available", "28.0")));
        assert water.layout.equals(Settings.CURRENT_TARGET) && text(water.current).equals("27.3°C") && text(water.target).equals("28") && water.currentLabel.equals("Water");
        // An explicit layout wins over Automatic.
        assert card(activity("Climate", "climate.hall", "layout", "Status"), heard("climate.hall", heating)).layout.equals(Settings.STATUS);
    }

    static void actionsOnCards() {
        int all = Cards.VACUUM_PAUSE | Cards.VACUUM_RETURN_HOME | Cards.VACUUM_START;
        Map<String, Object> rex = activity("Vacuum", "vacuum.rex", "vacuumControls", controls("Pause", "Dock"));
        assert card(rex, heard("vacuum.rex", entity("available", "cleaning", "supported_features", all))).actions.toString().equals("[pause, dock]");
        assert card(rex, heard("vacuum.rex", entity("available", "paused", "supported_features", all))).actions.toString().equals("[resume, dock]");
        assert card(rex, heard("vacuum.rex", entity("available", "docked", "supported_features", all))).actions.isEmpty() : "no row without actions";
        assert card(rex, heard("vacuum.rex", entity("available", "returning", "supported_features", all))).actions.isEmpty();
        assert card(rex, heard("vacuum.rex", entity("unavailable", "unavailable", "supported_features", all))).actions.isEmpty();
        assert card(rex, heard("vacuum.rex", entity("available", "cleaning", "supported_features", Cards.VACUUM_RETURN_HOME))).actions.toString().equals("[dock]")
            : "no Pause for a vacuum that cannot pause";
        assert card(rex, heard("vacuum.rex", entity("available", "paused", "supported_features", Cards.VACUUM_PAUSE))).actions.isEmpty() : "no Resume without START";
        assert card(activity("Vacuum", "vacuum.rex"), heard("vacuum.rex", entity("available", "cleaning", "supported_features", all))).actions.isEmpty() : "controls off";
        Cards.Entity cleaning = entity("available", "cleaning", "supported_features", all);
        Cards.Card reconnecting = card(rex, heard("vacuum.rex", Cards.Entity.next(cleaning, payload("connecting", null))));
        assert reconnecting.actions.toString().equals("[pause (off), dock (off)]") : "kept in place but off while not live";

        // Picked controls: only those, in their order, each when the vacuum supports it and it applies now.
        int every = all | Cards.VACUUM_STOP | Cards.VACUUM_LOCATE;
        Map<String, Object> mine = activity("Vacuum", "vacuum.rex", "vacuumControls", controls("Pause", "Dock", "Locate"));
        assert card(mine, heard("vacuum.rex", entity("available", "cleaning", "supported_features", every))).actions.toString().equals("[pause, dock, locate]");
        assert card(mine, heard("vacuum.rex", entity("available", "paused", "supported_features", every))).actions.toString().equals("[resume, dock, locate]");
        assert card(mine, heard("vacuum.rex", entity("available", "docked", "supported_features", every))).actions.toString().equals("[locate]")
            : "Locate works on the dock too";
        assert card(mine, heard("vacuum.rex", entity("available", "returning", "supported_features", every))).actions.toString().equals("[locate]");
        assert card(mine, heard("vacuum.rex", entity("unavailable", "unavailable", "supported_features", every))).actions.isEmpty();
        assert card(mine, heard("vacuum.rex", entity("available", "cleaning", "supported_features", all))).actions.toString().equals("[pause, dock]")
            : "no Locate for a vacuum that cannot locate";
        Map<String, Object> others = activity("Vacuum", "vacuum.rex", "vacuumControls", controls("Locate", "Stop", "Start"));
        assert card(others, heard("vacuum.rex", entity("available", "cleaning", "supported_features", every))).actions.toString().equals("[locate, stop]")
            : "in the order picked";
        assert card(others, heard("vacuum.rex", entity("available", "docked", "supported_features", every))).actions.toString().equals("[locate, start]");
        assert card(others, heard("vacuum.rex", entity("available", "returning", "supported_features", every))).actions.toString().equals("[locate, stop]");
        assert card(activity("Vacuum", "vacuum.rex"), heard("vacuum.rex", entity("available", "cleaning", "supported_features", every))).actions.isEmpty()
            : "none picked, none shown";
        Cards.Action locate = card(mine, heard("vacuum.rex", entity("available", "docked", "supported_features", every))).action("locate");
        assert locate.label.equals("Locate") && locate.icon.equals("mdi:map-marker") && locate.target.equals("vacuum.locate vacuum.rex");
        assert Cards.call(first(mine), "locate", null).get("service").equals("locate");
        assert Cards.call(first(others), "start", null).get("service").equals("start") && Cards.call(first(others), "stop", null).get("service").equals("stop");

        // Scripts and buttons, labelled from their ID unless named.
        Cards.Card script = card(activity("Appliance", "sensor.dw", "action", "script.pause_dishwasher"), heard("sensor.dw", entity("available", "run")));
        assert script.actions.size() == 1 && script.actions.get(0).label.equals("Pause dishwasher") && script.actions.get(0).icon.equals("mdi:play");
        Cards.Card button = card(activity("Custom", "sensor.dw", "action", "button.dw_start", "actionLabel", "Start"), heard("sensor.dw", entity("available", "ready")));
        assert button.actions.get(0).label.equals("Start") && button.actions.get(0).icon.equals("mdi:gesture-tap-button");
        assert card(activity("Custom", "sensor.dw", "action", "light.kitchen"), heard("sensor.dw", entity("available", "on"))).actions.isEmpty();

        // Climate steps stay on the grid and inside the limits.
        Cards.Entity limits = entity("available", "heat", "min_temp", 7, "max_temp", 22.2);
        assert Cards.adjusted(limits, 21.3, 0.5, 1) == 22.0 && Cards.adjusted(limits, 22.0, 0.5, -1) == 21.5;
        assert Cards.adjusted(limits, 22.0, 0.5, 1) == 22.2 : "up to the limit";
        assert Cards.adjusted(limits, 22.2, 0.5, 1) == null && Cards.adjusted(limits, 7, 0.5, -1) == null;
        assert Cards.adjusted(limits, 20, Double.NaN, 1) == null : "no step known yet";
        assert Cards.step(entity("available", "heat", "target_temp_step", 0.1), null) == 0.1;
        assert Double.isNaN(Cards.step(entity("available", "heat"), null)) && Cards.step(entity("available", "heat"), "°F") == 1 && Cards.step(entity("available", "heat"), "°C") == 0.5;
        Map<String, Object> climate = activity("Climate", "climate.hall", "controls", true);
        Cards.Card atMax = card(climate, heard("climate.hall", entity("available", "heat", "temperature", 22, "max_temp", 22, "target_temp_step", 0.5, "supported_features", 1)));
        assert atMax.actions.toString().equals("[cooler, warmer (off)]") : atMax.actions;
        assert card(climate, heard("climate.hall", entity("available", "off", "temperature", 22, "supported_features", 1))).actions.isEmpty() : "nothing to adjust while off";
        assert card(climate, heard("climate.hall", entity("available", "heat", "temperature", 22, "target_temp_step", 0.5, "supported_features", 0))).actions.isEmpty()
            : "no target temperature feature";
        assert card(climate, heard("climate.hall", entity("available", "heat", "temperature", 22, "supported_features", 1))).actions.toString().equals("[cooler (off), warmer (off)]")
            : "off until the unit system gives a step";

        // Each action names its call, which its button hands back with a press.
        assert Cards.target(first(rex), "pause").equals("vacuum.pause vacuum.rex") && Cards.target(first(rex), "dock").equals("vacuum.return_to_base vacuum.rex");
        assert Cards.target(first(climate), "warmer").equals("climate.set_temperature climate.hall");
        assert Cards.target(first(activity("Custom", "sensor.x", "action", "button.go")), "run").equals("button.press button.go");
        assert card(rex, heard("vacuum.rex", entity("available", "cleaning", "supported_features", all))).action("pause").target.equals("vacuum.pause vacuum.rex");

        // The calls each action sends.
        assert Cards.call(first(rex), "pause", null).toString().equals("{type=call_service, domain=vacuum, service=pause, target={entity_id=vacuum.rex}}");
        assert Cards.call(first(rex), "resume", null).get("service").equals("start");
        assert Cards.call(first(rex), "dock", null).get("service").equals("return_to_base");
        assert Cards.call(first(climate), "warmer", 22.5).toString().equals("{type=call_service, domain=climate, service=set_temperature, target={entity_id=climate.hall}, service_data={temperature=22.5}}");
        assert Cards.call(first(activity("Custom", "sensor.x", "action", "script.go")), "run", null).toString().equals("{type=call_service, domain=script, service=turn_on, target={entity_id=script.go}}")
            : "script.turn_on returns at once";
        assert Cards.call(first(activity("Custom", "sensor.x", "action", "input_button.go")), "run", null).get("domain").equals("input_button");
    }

    static void lifecycle() {
        // Nothing to show: no entities, no overlay, and the status says what to do.
        Host bare = new Host();
        LiveActivitiesPlugin none = new LiveActivitiesPlugin();
        none.start(bare, new HashMap<String, Object>());
        bare.saverRead.onResult(true, true, null);
        assert bare.entities().isEmpty() && bare.log.isEmpty() && bare.statusError && bare.status.equals("Add an activity in Settings.");

        Host host = new Host();
        LiveActivitiesPlugin plugin = new LiveActivitiesPlugin();
        plugin.clock = Clock.fixed(T0, DENVER);
        Map<String, Object> rex = activity("Vacuum", "vacuum.rex", "title", "Rex", "vacuumControls", controls("Pause", "Dock"));
        plugin.start(host, map("activities", rows(rex)));
        assert host.subscriptions.equals(Collections.singleton("screensaver.state")) && host.log.isEmpty() : "nothing followed until it can show";
        assert !host.statusError && host.status.equals("1 activity. They show with the screensaver.") : host.status;

        // The screensaver: one overlay, the entity followed, and a card once Home Assistant speaks.
        host.saverRead.onResult(true, true, null);
        assert host.log.equals(Arrays.asList("+ha.entity.vacuum.rex", "show")) : host.log;
        assert host.spec.onTop() && host.spec.touchable() && !host.spec.closeOnBack() && host.spec.anchor().equals(OverlaySpec.TOP_LEFT);
        assert board(plugin).cards.isEmpty() : "no card before the first state";
        hear(plugin, "vacuum.rex", "connecting", null);
        assert only(plugin).connection.equals(Cards.CONNECTING) && text(only(plugin).status).equals(Cards.NONE);
        hear(plugin, "vacuum.rex", "available", "cleaning", "supported_features", 8212, "friendly_name", "Roborock");
        Cards.Card cleaning = only(plugin);
        assert cleaning.live() && cleaning.title.equals("Rex") && text(cleaning.status).equals("Cleaning") && cleaning.actions.toString().equals("[pause, dock]");
        assert host.count("show") == 1 : "state changes update the view in place";
        assert host.status.equals("1 activity. 1 showing now.") : host.status;

        // Looks only: no Home Assistant traffic. A new place replaces the overlay; a new title only redraws.
        int entityLines = host.entityLog().size();
        Map<String, Object> renamed = activity("Vacuum", "vacuum.rex", "title", "Rex II", "vacuumControls", controls("Pause", "Dock"), "icon", "mdi:robot");
        plugin.configure(map("activities", rows(renamed), "scale", 120));
        assert host.entityLog().size() == entityLines && host.count("show") == 2 : "Size moves the inset, so the overlay is replaced";
        assert only(plugin).title.equals("Rex II") && board(plugin).scale == 120;
        plugin.configure(map("activities", rows(renamed), "scale", 120, "maxCards", 4));
        assert host.count("show") == 2 && host.entityLog().size() == entityLines;
        Cards.Board before = board(plugin);
        plugin.configure(map("activities", rows(renamed), "scale", 120, "maxCards", 4, "x", 300, "y", 500));
        assert host.count("show") == 2 && board(plugin) == before : "hidden x and y change nothing";
        plugin.configure(map("activities", rows(renamed), "scale", 120, "maxCards", 4, "x", 300, "y", 500, "position", "Custom"));
        assert host.count("show") == 3 && host.spec.offsetX() == 300 && host.spec.offsetY() == 500 && host.spec.inset() == 0;
        plugin.configure(map("activities", rows(renamed), "scale", 120, "maxCards", 4, "x", 301, "y", 500, "position", "Custom"));
        assert host.count("show") == 4 && host.spec.offsetX() == 301;

        // A new value adds just its entity; KS's shared connection restarts once for it, the rest stay.
        Map<String, Object> withBattery = activity("Vacuum", "vacuum.rex", "title", "Rex II", "vacuumControls", controls("Pause", "Dock"),
            "values", rows(value("Secondary value", "sensor.rex_battery", "", "label", "Battery")));
        plugin.configure(map("activities", rows(withBattery)));
        assert host.entityLog().subList(entityLines, host.entityLog().size()).equals(Collections.singletonList("+ha.entity.sensor.rex_battery")) : host.entityLog();
        assert only(plugin).connection.equals(Cards.CONNECTING) : "the battery has not spoken yet";
        hear(plugin, "sensor.rex_battery", "available", "80", "unit_of_measurement", "%");
        assert only(plugin).live() && only(plugin).secondaryLabel.equals("Battery") && text(only(plugin).secondary).equals("80%");

        // The connection drops: the card stays with its last values, says Reconnecting, and its buttons go off.
        hear(plugin, "vacuum.rex", "disconnected", null);
        hear(plugin, "sensor.rex_battery", "disconnected", null);
        Cards.Card lost = only(plugin);
        assert lost.connection.equals(Cards.RECONNECTING) && text(lost.status).equals("Cleaning") && text(lost.secondary).equals("80%");
        assert lost.actions.toString().equals("[pause (off), dock (off)]");
        hear(plugin, "vacuum.rex", "connecting", null);
        hear(plugin, "sensor.rex_battery", "connecting", null);
        assert only(plugin).connection.equals(Cards.RECONNECTING);
        hear(plugin, "vacuum.rex", "available", "cleaning", "supported_features", 8212);
        hear(plugin, "sensor.rex_battery", "available", "79", "unit_of_measurement", "%");
        assert only(plugin).live() && text(only(plugin).secondary).equals("79%");
        // KS without Home Assistant: Unavailable, never Live.
        hear(plugin, "sensor.rex_battery", "not_configured", null);
        assert only(plugin).connection.equals(Cards.UNAVAILABLE);
        hear(plugin, "sensor.rex_battery", "available", "79", "unit_of_measurement", "%");

        // The screensaver ends: every entity let go, the overlay hidden, and late events ignored.
        plugin.onEvent("ks.screensaver.state", map("active", false));
        assert host.entities().isEmpty() && host.log.get(host.log.size() - 1).equals("hide");
        hear(plugin, "vacuum.rex", "available", "docked");
        assert host.entities().isEmpty();
        // The start-up read answering late cannot turn it back on: the event is newer.
        host.saverRead.onResult(true, true, null);
        assert host.entities().isEmpty() && host.log.get(host.log.size() - 1).equals("hide");
        // The next showing starts fresh: old values never come back as live.
        plugin.onEvent("ks.screensaver.state", map("active", true));
        assert host.entities().equals(new TreeSet<>(Arrays.asList("vacuum.rex", "sensor.rex_battery")));
        assert board(plugin).cards.isEmpty();
        hear(plugin, "vacuum.rex", "connecting", null);
        assert only(plugin).connection.equals(Cards.CONNECTING) && text(only(plugin).status).equals(Cards.NONE) : "Connecting, not Reconnecting over stale values";

        // Removing an activity lets go of its entities.
        plugin.configure(map("activities", rows(activity("Custom", "sensor.solar_power"))));
        assert host.entities().equals(Collections.singleton("sensor.solar_power")) : host.entities();
        assert board(plugin).cards.isEmpty();
        plugin.configure(map("activities", new ArrayList<Object>()));
        assert host.entities().isEmpty() && host.log.get(host.log.size() - 1).equals("hide") && host.status.equals("Add an activity in Settings.");

        // Screensaver and preview overlap: the overlay and subscriptions stay until both end, with no second subscribe.
        Host both = new Host();
        LiveActivitiesPlugin overlap = new LiveActivitiesPlugin();
        overlap.start(both, map("activities", rows(activity("Custom", "sensor.solar_power"))));
        overlap.execute("preview", new HashMap<String, Object>());
        assert both.log.equals(Arrays.asList("+ha.entity.sensor.solar_power", "show"));
        overlap.onEvent("ks.screensaver.state", map("active", true));
        overlap.execute("endPreview", new HashMap<String, Object>());
        assert both.log.equals(Arrays.asList("+ha.entity.sensor.solar_power", "show")) : both.log;
        overlap.execute("preview", new HashMap<String, Object>());
        overlap.onEvent("ks.screensaver.state", map("active", false));
        assert both.log.equals(Arrays.asList("+ha.entity.sensor.solar_power", "show"));
        overlap.execute("endPreview", new HashMap<String, Object>());
        assert both.log.equals(Arrays.asList("+ha.entity.sensor.solar_power", "show", "-ha.entity.sensor.solar_power", "hide")) : both.log;
        // The settings page buttons run the same preview and report on their run.
        overlap.onEvent("settings.button", map("id", "showPreview", "run", "r1"));
        assert both.log.get(both.log.size() - 1).equals("show") && both.results.get(0).startsWith("r1 success: Showing every enabled activity");
        overlap.onEvent("settings.button", map("id", "hidePreview", "run", "r2"));
        assert both.log.get(both.log.size() - 1).equals("hide") && both.results.get(1).equals("r2 success: Preview hidden.");
        overlap.onEvent("settings.button", map("id", "somethingElse", "run", "r3"));
        assert both.results.size() == 2;
        boolean rejected = false;
        try { overlap.execute("nope", new HashMap<String, Object>()); } catch (IllegalArgumentException expected) { rejected = true; }
        assert rejected;

        // Eight visible activities with Maximum cards 3: all eight reach the overlay, which shows three and opens the rest.
        Host eightHost = new Host();
        LiveActivitiesPlugin eight = new LiveActivitiesPlugin();
        List<Object> eightRows = new ArrayList<>();
        for (int i = 0; i < 8; i++) eightRows.add(activity("Custom", "sensor.e" + i));
        eight.start(eightHost, map("activities", eightRows, "maxCards", 3));
        eight.execute("preview", new HashMap<String, Object>());
        for (int i = 0; i < 8; i++) hear(eight, "sensor.e" + i, "available", String.valueOf(i));
        assert board(eight).cards.size() == 8 && board(eight).limit == 3 && board(eight).overflow() == 5;
        assert eightHost.status.equals("8 activities. 8 showing now.") : eightHost.status;

        // Too many entities: the activity is left out and named in the status; KS never sees a 17th.
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 4; i++) many.add(activity("Custom", "sensor.a" + i, "values", rows(value("Primary value", "sensor.b" + i, ""),
            value("Secondary value", "sensor.c" + i, ""), value("Progress", "sensor.d" + i, ""))));
        many.add(activity("Custom", "sensor.extra", "title", "Extra"));
        Host busyHost = new Host();
        LiveActivitiesPlugin crowded = new LiveActivitiesPlugin();
        crowded.start(busyHost, map("activities", many));
        crowded.execute("preview", new HashMap<String, Object>());
        assert busyHost.entities().size() == 16 && busyHost.statusError && busyHost.status.startsWith("Extra is left out") : busyHost.status;

        // A refused subscribe shows in the status and is tried again with the next change.
        Host refusing = new Host();
        refusing.subscribeRefused = true;
        LiveActivitiesPlugin retry = new LiveActivitiesPlugin();
        retry.start(refusing, map("activities", rows(activity("Custom", "sensor.solar_power"))));
        retry.execute("preview", new HashMap<String, Object>());
        assert refusing.entities().isEmpty() && refusing.statusError && refusing.status.contains("did not follow sensor.solar_power") : refusing.status;
        refusing.subscribeRefused = false;
        retry.onEvent("ks.screensaver.state", map("active", true));
        assert refusing.entities().equals(Collections.singleton("sensor.solar_power")) && !refusing.statusError;

        // After stop, late replies and presses do nothing.
        retry.stop();
        retry.onEvent("ks.ha.entity.sensor.solar_power", payload("available", "3"));
        refusing.saverRead.onResult(true, false, null);
        tap(retry, "Custom:sensor.solar_power", "run");
        assert refusing.sent.isEmpty();
    }

    static void actions() {
        Host host = new Host();
        LiveActivitiesPlugin plugin = new LiveActivitiesPlugin();
        Instant[] now = {T0};
        plugin.clock = new Clock() {
            public ZoneId getZone() { return DENVER; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now[0]; }
        };
        String rex = "Vacuum:vacuum.rex";
        Map<String, Object> vacuum = activity("Vacuum", "vacuum.rex", "vacuumControls", controls("Pause", "Dock"));
        Map<String, Object> dishwasher = activity("Appliance", "sensor.dw", "action", "script.pause_dishwasher");
        plugin.start(host, map("activities", rows(vacuum, dishwasher)));
        plugin.execute("preview", new HashMap<String, Object>());
        hear(plugin, "vacuum.rex", "available", "cleaning", "supported_features", 8212);
        hear(plugin, "sensor.dw", "available", "run");

        // One press, one call. A second press while it is out does nothing.
        tap(plugin, rex, "pause");
        tap(plugin, rex, "pause");
        tap(plugin, rex, "dock");
        assert host.sent.size() == 1 && host.sent.get(0).get("service").equals("pause");
        Cards.Card waiting = board(plugin).cards.get(0);
        assert waiting.note.equals("Sending Pause…") && waiting.actions.toString().equals("[pause (off), dock (off)]");
        assert board(plugin).cards.get(1).actions.toString().equals("[run]") : "other cards keep working";
        host.replies.get(0).onResult(true, map("context", map()), null);
        Cards.Card sent = board(plugin).cards.get(0);
        assert sent.note.equals("Pause sent") && !sent.noteError && sent.noteUntil == T0.toEpochMilli() + LiveActivitiesPlugin.SENT_MS;
        assert sent.actions.toString().equals("[pause, dock]") : "the state still says cleaning until Home Assistant reports";
        hear(plugin, "vacuum.rex", "available", "paused", "supported_features", 8212);
        assert board(plugin).cards.get(0).actions.toString().equals("[resume, dock]");

        // A failure says so and goes after a while. Nothing is sent again on its own.
        tap(plugin, rex, "resume");
        host.replies.get(1).onResult(false, map("code", "home_assistant_error", "message", "Vacuum is offline"), "home_assistant_error: Vacuum is offline");
        Cards.Card failed = board(plugin).cards.get(0);
        assert failed.note.equals("Resume failed: Vacuum is offline") && failed.noteError && host.sent.size() == 2;
        now[0] = T0.plusMillis(LiveActivitiesPlugin.FAILED_MS + 1);
        hear(plugin, "vacuum.rex", "available", "paused", "supported_features", 8212);
        assert board(plugin).cards.get(0).note == null : "the note expired";

        // A timeout may still have worked: it says to check, and sends nothing more.
        tap(plugin, rex, "dock");
        host.replies.get(2).onResult(false, map("code", "timeout"), "timeout: No answer within 25 seconds");
        assert board(plugin).cards.get(0).note.startsWith("No answer from Home Assistant. Check the device") && host.sent.size() == 3;
        assert board(plugin).cards.get(0).actions.toString().equals("[resume, dock]") : "the person may try again";

        // KS busy: nothing went out, so the card says to try again and takes another press.
        host.busy = true;
        tap(plugin, "Appliance:sensor.dw", "run");
        assert host.sent.size() == 3 && board(plugin).cards.get(1).note.equals("Kiosk Satellite is busy. Try again.") && board(plugin).cards.get(1).noteError;
        host.busy = false;
        tap(plugin, "Appliance:sensor.dw", "run");
        assert host.sent.size() == 4 && host.sent.get(3).get("domain").equals("script");

        // Removed while its call is out: the late reply is dropped.
        plugin.configure(map("activities", rows(vacuum)));
        host.replies.get(3).onResult(false, null, "service_not_found: Script not found");
        assert board(plugin).cards.size() == 1 && board(plugin).cards.get(0).key.equals(rex);
        plugin.configure(map("activities", rows(vacuum, dishwasher)));
        hear(plugin, "sensor.dw", "available", "run");
        assert board(plugin).cards.get(1).note == null : "added back, it carries no word from the old call";
        plugin.configure(map("activities", rows(vacuum)));

        // Not live: buttons off, presses ignored.
        hear(plugin, "vacuum.rex", "disconnected", null);
        tap(plugin, rex, "dock");
        assert host.sent.size() == 4;
        // A tap held back while the state changed lands on the button that was under the finger. Pause is no longer
        // offered once the vacuum is paused, so the plugin's own check drops it. Unknown cards are ignored too.
        hear(plugin, "vacuum.rex", "available", "paused", "supported_features", 8212);
        tap(plugin, rex, "pause");
        tap(plugin, "Vacuum:vacuum.other", "dock");
        assert host.sent.size() == 4;

        // A finger held on Pause while settings make the same button run another script: the old tap is dropped.
        Host held = new Host();
        LiveActivitiesPlugin washer = new LiveActivitiesPlugin();
        String key = "Appliance:sensor.washer_current_status";
        Map<String, Object> pausing = activity("Appliance", "sensor.washer_current_status", "action", "script.pause_washer", "actionLabel", "Pause");
        washer.start(held, map("activities", rows(pausing)));
        washer.execute("preview", new HashMap<String, Object>());
        hear(washer, "sensor.washer_current_status", "available", "running");
        String drawnFor = only(washer).action("run").target;
        assert drawnFor.equals("script.turn_on script.pause_washer") : drawnFor;
        washer.configure(map("activities", rows(activity("Appliance", "sensor.washer_current_status", "action", "script.start_washer", "actionLabel", "Pause"))));
        assert only(washer).action("run").label.equals("Pause") : "same key, same button, same label";
        washer.press(key, "run", drawnFor);
        assert held.sent.isEmpty() : "the tap was for pause_washer, so start_washer is not sent";
        tap(washer, key, "run");
        assert held.sent.size() == 1 && held.sent.get(0).get("target").equals(Collections.singletonMap("entity_id", "script.start_washer"))
            : "the button as drawn now runs what it now says";
        washer.press(key, "run", null);
        washer.press(key, "run", "script.turn_on script.start_washer");
        assert held.sent.size() == 1 : "a call is out, and a tap without its call is never sent";

        assert LiveActivitiesPlugin.failure("pause", "unknown_error").equals("Pause failed: unknown_error");
        assert LiveActivitiesPlugin.failure("pause", null).equals("Pause failed.");
        assert LiveActivitiesPlugin.failure("pause", "home_assistant_error: " + new String(new char[200]).replace('\0', 'x')).length() < 140;
    }

    /**
     * Redraws wait from finger down until the tap's click has run, so a value changing mid-tap cannot cancel it. The
     * queue stands in for the main looper; draw mirrors render, which asks the gate first.
     */
    static void touchGate() {
        ActivityOverlay.Gate gate = new ActivityOverlay.Gate();
        Deque<Runnable> looper = new ArrayDeque<>();
        int[] drawn = {0}, clicks = {0};
        Runnable draw = () -> { if (gate.ask()) drawn[0]++; };
        Runnable click = () -> {
            assert drawn[0] == 0 : "the click runs before any redraw replaces its button";
            clicks[0]++;
        };
        assert gate.ask() : "no finger down: draw now";

        // A Home Assistant update queued its redraw before Android posted the click: redraw, click, release.
        gate.press();
        looper.add(draw);
        looper.add(click);
        gate.up(looper::add, draw);
        while (!looper.isEmpty()) looper.poll().run();
        assert clicks[0] == 1 && drawn[0] == 1 : clicks[0] + " clicks, " + drawn[0] + " redraws";

        // A redraw queued between the click and the release waits too: click, redraw, release.
        drawn[0] = 0;
        clicks[0] = 0;
        gate.press();
        looper.add(click);
        looper.add(draw);
        gate.up(looper::add, draw);
        while (!looper.isEmpty()) looper.poll().run();
        assert clicks[0] == 1 && drawn[0] == 1;

        // A tap with no update draws nothing extra.
        drawn[0] = 0;
        gate.press();
        gate.up(looper::add, draw);
        while (!looper.isEmpty()) looper.poll().run();
        assert drawn[0] == 0 && gate.ask();

        // The release of a tap that ended cannot open the gate on the next one already down.
        gate.press();
        gate.up(looper::add, draw);
        gate.press();
        looper.add(draw);
        while (!looper.isEmpty()) looper.poll().run();
        assert drawn[0] == 0 && !gate.ask() : "still held for the second finger";
        gate.up(looper::add, draw);
        while (!looper.isEmpty()) looper.poll().run();
        assert drawn[0] == 1 && gate.ask();

        // A view removed mid-gesture never hears finger up, so reset opens the gate.
        gate.press();
        gate.ask();
        gate.reset();
        assert gate.ask();
    }

    static void headingAndColors() {
        // Defaults: Home Assistant without a dash, and the KS theme's colors throughout.
        Settings plain = Settings.parse(new HashMap<String, Object>());
        assert plain.heading.equals("Home Assistant") && plain.headingText.isEmpty() && plain.style.equals(Settings.Style.THEMED);
        Map<String, Object> washer = activity("Appliance", "sensor.washer_current_status", "title", "Washer");
        Settings.Activity a = first(washer);
        Cards.Entity source = entity("available", "running", "friendly_name", "Washer Current status");
        assert Cards.heading(plain, a, source, "LG ThinQ").equals("Home Assistant") : "only Integration names the integration";
        Settings integration = Settings.parse(map("heading", "Integration"));
        assert Cards.heading(integration, a, source, "LG ThinQ").equals("LG ThinQ");
        assert Cards.heading(integration, a, source, null).equals("Home Assistant") : "until Home Assistant names it";
        assert Cards.heading(integration, a, source, "").equals("Home Assistant") : "or when it cannot";
        Settings named = Settings.parse(map("heading", "Entity name"));
        assert Cards.heading(named, a, source, null).equals("Washer Current status");
        assert Cards.heading(named, a, entity("available", "running"), null).equals("sensor.washer_current_status");
        assert Cards.heading(named, a, null, null).equals("sensor.washer_current_status");
        assert Cards.heading(Settings.parse(map("heading", "Custom", "headingText", " Laundry ")), a, source, null).equals("Laundry");
        assert Cards.heading(Settings.parse(map("heading", "Custom")), a, source, null).isEmpty() : "empty shows the icon alone";
        assert Cards.heading(Settings.parse(map("heading", "Sideways")), a, source, "LG ThinQ").equals("Home Assistant");

        // Colors: Custom applies the palette; Theme ignores saved custom colors, which KS keeps while hidden.
        Map<String, Object> palette = map("background", "#112233", "outline", "#445566", "text", "#FFFFFF", "secondaryText", "#AABBCC",
            "accent", "#ff8800", "controls", "#000000", "opacity", 62.0, "bar", "Gradient", "barColor", "#488284", "barEnd", "#749C6F");
        Settings themed = Settings.parse(palette);
        assert themed.style.background == null && themed.style.accent == null && themed.style.controls == null : "Theme keeps the theme's roles";
        assert themed.style.opacity == 62 : "opacity applies in both modes";
        palette.put("palette", "Custom");
        Settings.Style own = Settings.parse(palette).style;
        assert own.background == 0xFF112233 && own.outline == 0xFF445566 && own.text == 0xFFFFFFFF && own.secondaryText == 0xFFAABBCC;
        assert own.accent == 0xFFFF8800 && own.controls == 0xFF000000 : "black is a color, not the theme";
        assert own.bar.equals("Gradient") && own.barColor == 0xFF488284 && own.barEnd == 0xFF749C6F;
        assert Settings.color("#12345") == null && Settings.color("red") == null && Settings.color(null) == null && Settings.color("#GG0000") == null;
        assert Settings.parse(map("opacity", 5)).style.opacity == 20 && Settings.parse(map("opacity", 500)).style.opacity == 100;
        // The bar: Theme uses the accent, Solid one color, Gradient two. Colors a mode hides do not count.
        Settings.Style solid = Settings.parse(map("bar", "Solid", "barColor", "#FF0000", "barEnd", "#00FF00")).style;
        assert solid.bar.equals("Solid") && solid.barColor == 0xFFFF0000 && solid.barEnd == null;
        Settings.Style theme = Settings.parse(map("bar", "Theme", "barColor", "#FF0000", "barEnd", "#00FF00")).style;
        assert theme.barColor == null && theme.barEnd == null && theme.equals(Settings.Style.THEMED);
        assert Settings.parse(map("bar", "Rainbow")).style.bar.equals("Theme");
        assert !plain.style.iconOnly && Settings.parse(map("buttonStyle", "Icon only")).style.iconOnly;
        assert !Settings.parse(map("buttonStyle", "Icon only")).style.equals(Settings.Style.THEMED) : "a looks change, so the cards redraw";
        assert Settings.parse(map("palette", "Custom")).style.background == null : "a missing color follows the theme";

        // Heading and colors are looks only: they redraw the cards with no Home Assistant traffic.
        Host host = new Host();
        LiveActivitiesPlugin plugin = new LiveActivitiesPlugin();
        plugin.start(host, map("activities", rows(washer)));
        plugin.execute("preview", new HashMap<String, Object>());
        hear(plugin, "sensor.washer_current_status", "available", "running", "friendly_name", "Washer Current status");
        assert only(plugin).heading.equals("Home Assistant");
        int lines = host.log.size();
        plugin.configure(map("activities", rows(washer), "heading", "Entity name", "palette", "Custom", "accent", "#FF8800", "bar", "Gradient",
            "barColor", "#488284", "barEnd", "#749C6F", "opacity", 80));
        assert host.log.size() == lines && host.sent.isEmpty() : "no subscribe, no overlay rebuild, no call";
        assert only(plugin).heading.equals("Washer Current status") && board(plugin).style.accent == 0xFFFF8800 && board(plugin).style.opacity == 80;
        plugin.configure(map("activities", rows(washer), "heading", "Custom", "headingText", "Laundry"));
        assert only(plugin).heading.equals("Laundry") && board(plugin).style.equals(Settings.Style.THEMED) && host.sent.isEmpty();
    }

    /** The Integration heading reads each card entity's integration from Home Assistant once, a call at a time. */
    static void integrationHeading() {
        Host host = new Host();
        LiveActivitiesPlugin plugin = new LiveActivitiesPlugin();
        Map<String, Object> washer = activity("Appliance", "sensor.washer_current_status", "title", "Washer",
            "values", rows(value("Primary value", "sensor.washer_remaining_time", "")));
        Map<String, Object> dryer = activity("Appliance", "sensor.dryer_current_status", "title", "Dryer");
        Map<String, Object> solar = activity("Custom", "sensor.solar_power", "title", "Solar");
        plugin.start(host, map("activities", rows(washer, dryer, solar), "heading", "Integration"));
        assert host.sent.isEmpty() : "nothing is read until the cards can show";
        plugin.execute("preview", new HashMap<String, Object>());
        hear(plugin, "sensor.washer_current_status", "available", "running");
        hear(plugin, "sensor.dryer_current_status", "available", "power_off");
        hear(plugin, "sensor.solar_power", "available", "1200", "unit_of_measurement", "W");
        // One registry read for the cards' own entities; their values' entities are not needed.
        assert host.sent.size() == 1 : host.sent;
        assert host.sent.get(0).equals(map("type", "config/entity_registry/get_entries",
            "entity_ids", Arrays.asList("sensor.washer_current_status", "sensor.dryer_current_status", "sensor.solar_power"))) : host.sent.get(0);
        assert board(plugin).cards.get(0).heading.equals("Home Assistant") : "until Home Assistant names it";
        Map<String, Object> entries = map("sensor.washer_current_status", map("platform", "lg_thinq", "entity_id", "sensor.washer_current_status"),
            "sensor.dryer_current_status", map("platform", "lg_thinq"), "sensor.solar_power", null);
        host.replies.get(0).onResult(true, entries, null);
        // Then each integration's name, once per integration, never manifest/list.
        assert host.sent.size() == 2 && host.sent.get(1).equals(map("type", "manifest/get", "integration", "lg_thinq")) : host.sent;
        host.replies.get(1).onResult(true, map("domain", "lg_thinq", "name", "LG ThinQ"), null);
        assert host.sent.size() == 2 : "solar's entity has no registry entry, so nothing more to ask";
        assert board(plugin).cards.get(0).heading.equals("LG ThinQ") && board(plugin).cards.get(1).heading.equals("LG ThinQ");
        assert board(plugin).cards.get(2).heading.equals("Home Assistant") : "an entity without an integration falls back";
        for (Map<String, Object> m : host.sent) assert !"manifest/list".equals(m.get("type"));

        // Cached: switching the heading away and back reads nothing again.
        plugin.configure(map("activities", rows(washer, dryer, solar)));
        assert board(plugin).cards.get(0).heading.equals("Home Assistant");
        plugin.configure(map("activities", rows(washer, dryer, solar), "heading", "Integration"));
        assert host.sent.size() == 2 && board(plugin).cards.get(0).heading.equals("LG ThinQ");

        // A failed read falls back now, is not repeated during this showing, and is asked again at the next one.
        Map<String, Object> vacuum = activity("Vacuum", "vacuum.rex", "title", "Rex");
        plugin.configure(map("activities", rows(washer, vacuum), "heading", "Integration"));
        assert host.sent.size() == 3 && host.sent.get(2).get("entity_ids").equals(Collections.singletonList("vacuum.rex")) : host.sent;
        host.replies.get(2).onResult(true, map("vacuum.rex", map("platform", "roborock")), null);
        assert host.sent.size() == 4 && host.sent.get(3).get("integration").equals("roborock");
        host.replies.get(3).onResult(false, map("code", "not_found"), "not_found: Integration not found");
        hear(plugin, "vacuum.rex", "available", "cleaning");
        assert host.sent.size() == 4 && board(plugin).cards.get(1).heading.equals("Home Assistant");
        plugin.execute("endPreview", new HashMap<String, Object>());
        plugin.execute("preview", new HashMap<String, Object>());
        assert host.sent.size() == 5 && host.sent.get(4).get("integration").equals("roborock") : "asked again at the next showing";
        // One lookup out at a time: events while it waits send nothing more.
        hear(plugin, "vacuum.rex", "available", "cleaning");
        hear(plugin, "sensor.washer_current_status", "available", "rinsing");
        assert host.sent.size() == 5;
        host.replies.get(4).onResult(true, map("name", "Roborock"), null);
        assert board(plugin).cards.get(1).heading.equals("Roborock");

        // KS busy: nothing is out, so the next refresh tries again.
        Host crowded = new Host();
        LiveActivitiesPlugin busy = new LiveActivitiesPlugin();
        crowded.busy = true;
        busy.start(crowded, map("activities", rows(washer), "heading", "Integration"));
        busy.execute("preview", new HashMap<String, Object>());
        assert crowded.sent.isEmpty();
        crowded.busy = false;
        hear(busy, "sensor.washer_current_status", "available", "running");
        assert crowded.sent.size() == 1 && crowded.sent.get(0).get("type").equals("config/entity_registry/get_entries");
        // A reply after stop is dropped.
        busy.stop();
        crowded.replies.get(0).onResult(true, map("sensor.washer_current_status", map("platform", "lg_thinq")), null);
        assert crowded.sent.size() == 1;
        // The default heading never reads the registry.
        Host quiet = new Host();
        LiveActivitiesPlugin plainPlugin = new LiveActivitiesPlugin();
        plainPlugin.start(quiet, map("activities", rows(washer)));
        plainPlugin.execute("preview", new HashMap<String, Object>());
        hear(plainPlugin, "sensor.washer_current_status", "available", "running");
        assert quiet.sent.isEmpty();
    }

    static void climateControls() {
        Host host = new Host();
        LiveActivitiesPlugin plugin = new LiveActivitiesPlugin();
        plugin.clock = Clock.fixed(T0, ZoneOffset.UTC);
        String hall = "Climate:climate.hall";
        plugin.start(host, map("activities", rows(activity("Climate", "climate.hall", "controls", true))));
        host.saverRead.onResult(true, true, null);
        hear(plugin, "climate.hall", "available", "heat", "current_temperature", 21.5, "temperature", 22, "target_temp_step", 0.5,
            "min_temp", 7, "max_temp", 30, "supported_features", 385, "hvac_action", "heating");
        assert host.sent.isEmpty() : "an entity with its own step needs no unit system";
        tap(plugin, hall, "warmer");
        assert host.sent.get(0).toString().contains("service_data={temperature=22.5}") : host.sent;
        assert text(only(plugin).target).equals("22.5°") && only(plugin).note.equals("Sending target 22.5°…");
        host.replies.get(0).onResult(true, map(), null);
        assert text(only(plugin).target).equals("22.5°") && only(plugin).note.equals("Target 22.5° sent") : "the asked-for target shows until the entity reports";
        // Another step builds on it while the entity catches up.
        tap(plugin, hall, "warmer");
        assert host.sent.get(1).toString().contains("temperature=23.0") : host.sent.get(1);
        host.replies.get(1).onResult(true, map(), null);
        hear(plugin, "climate.hall", "available", "heat", "current_temperature", 21.5, "temperature", 23, "target_temp_step", 0.5,
            "min_temp", 7, "max_temp", 30, "supported_features", 385, "hvac_action", "heating");
        assert text(only(plugin).target).equals("23.0°") && only(plugin).actions.toString().equals("[cooler, warmer]");
        // Whatever the entity reports next wins, even when it is not what was asked for, such as a schedule taking over.
        tap(plugin, hall, "warmer");
        host.replies.get(2).onResult(true, map(), null);
        assert text(only(plugin).target).equals("23.5°");
        hear(plugin, "climate.hall", "available", "heat", "current_temperature", 21.5, "temperature", 21, "target_temp_step", 0.5,
            "min_temp", 7, "max_temp", 30, "supported_features", 385, "hvac_action", "idle");
        assert text(only(plugin).target).equals("21.0°") && only(plugin).operating.equals("Idle");

        // Without its own step, the unit system decides: asked once, controls off until it answers.
        Host imperial = new Host();
        LiveActivitiesPlugin us = new LiveActivitiesPlugin();
        us.start(imperial, map("activities", rows(activity("Climate", "climate.den", "controls", true))));
        us.execute("preview", new HashMap<String, Object>());
        hear(us, "climate.den", "available", "cool", "current_temperature", 75, "temperature", 72, "min_temp", 45, "max_temp", 95, "supported_features", 1);
        hear(us, "climate.den", "available", "cool", "current_temperature", 74, "temperature", 72, "min_temp", 45, "max_temp", 95, "supported_features", 1);
        assert imperial.sent.size() == 1 && imperial.sent.get(0).equals(map("type", "get_config")) : imperial.sent;
        assert only(us).actions.toString().equals("[cooler (off), warmer (off)]");
        imperial.replies.get(0).onResult(true, map("unit_system", map("temperature", "°F")), null);
        assert only(us).actions.toString().equals("[cooler, warmer]") && text(only(us).target).equals("72°");
        tap(us, "Climate:climate.den", "cooler");
        assert imperial.sent.get(1).toString().contains("temperature=71.0") : imperial.sent.get(1);
        // A failed unit read is tried again at the next showing, not in a loop.
        Host flaky = new Host();
        LiveActivitiesPlugin again = new LiveActivitiesPlugin();
        again.start(flaky, map("activities", rows(activity("Climate", "climate.den", "controls", true))));
        again.execute("preview", new HashMap<String, Object>());
        hear(again, "climate.den", "available", "cool", "temperature", 72, "supported_features", 1);
        flaky.replies.get(0).onResult(false, null, "unknown_error: boom");
        hear(again, "climate.den", "available", "cool", "temperature", 72, "supported_features", 1);
        assert flaky.sent.size() == 1;
        again.execute("endPreview", new HashMap<String, Object>());
        again.execute("preview", new HashMap<String, Object>());
        hear(again, "climate.den", "available", "cool", "temperature", 72, "supported_features", 1);
        assert flaky.sent.size() == 2;
        // A reply after stop is dropped.
        again.stop();
        flaky.replies.get(1).onResult(true, map("unit_system", map("temperature", "°C")), null);
    }
}
