// SPDX-License-Identifier: Apache-2.0
package liveactivities.ks;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns an activity's settings and what Home Assistant last said into the card to draw. Home Assistant owns every value
 * and calculation; this only picks, labels and formats them. Kept free of Android so the JVM tests cover it.
 */
final class Cards {
    static final String LIVE = "Live", CONNECTING = "Connecting", RECONNECTING = "Reconnecting", UNAVAILABLE = "Unavailable";
    private static final String[] CONNECTIONS = {LIVE, CONNECTING, RECONNECTING, UNAVAILABLE};
    /** A value Home Assistant does not have: never zero, done or off. */
    static final String NONE = "—";
    /** Home Assistant's feature bits. */
    static final int VACUUM_PAUSE = 4, VACUUM_STOP = 8, VACUUM_RETURN_HOME = 16, VACUUM_LOCATE = 512, VACUUM_START = 8192,
        CLIMATE_TARGET_TEMPERATURE = 1;

    private static final Map<String, String> VACUUM_LABELS = new HashMap<>(), VACUUM_ICONS = new HashMap<>();
    static {
        String[][] controls = {{"start", "Start", "mdi:play"}, {"pause", "Pause", "mdi:pause"}, {"resume", "Resume", "mdi:play"},
            {"stop", "Stop", "mdi:stop"}, {"dock", "Dock", "mdi:home-import-outline"}, {"locate", "Locate", "mdi:map-marker"}};
        for (String[] control : controls) {
            VACUUM_LABELS.put(control[0], control[1]);
            VACUUM_ICONS.put(control[0], control[2]);
        }
    }

    private Cards() {}

    /** One entity as the plugin last heard it through KS. */
    static final class Entity {
        /** KS's status: available, unknown, unavailable, missing, too_large, connecting, disconnected or not_configured. */
        final String status;
        /** Null when none is known. */
        final String state;
        final Map<String, Object> attributes;
        /** Home Assistant reported it since it was subscribed, so a dropped connection reads as Reconnecting. */
        final boolean known;

        Entity(String status, String state, Map<String, Object> attributes, boolean known) {
            this.status = status;
            this.state = state;
            this.attributes = attributes;
            this.known = known;
        }

        /**
         * The entity after a KS event. Connection events clear KS's copy, but the card keeps the last state it heard so
         * it can say Reconnecting over what it showed, with its values dimmed and its controls off.
         */
        @SuppressWarnings("unchecked")
        static Entity next(Entity previous, Map<String, Object> payload) {
            String status = payload.get("status") instanceof String ? (String) payload.get("status") : "disconnected";
            switch (status) {
                case "available":
                case "unknown":
                case "unavailable":
                    Object state = payload.get("state");
                    Object attributes = payload.get("attributes");
                    return new Entity(status, state instanceof String ? (String) state : null,
                        attributes instanceof Map ? (Map<String, Object>) attributes : Collections.<String, Object>emptyMap(), true);
                case "missing":
                case "too_large":
                    return new Entity(status, null, Collections.<String, Object>emptyMap(), false);
                default: // connecting, disconnected, not_configured: about the connection, not the entity
                    return previous == null ? new Entity(status, null, Collections.<String, Object>emptyMap(), false)
                        : new Entity(status, previous.state, previous.attributes, previous.known);
            }
        }
    }

    /** Text, or a time Home Assistant gave that counts down on the card. */
    static final class Value {
        final String text;
        final Instant until;

        private Value(String text, Instant until) {
            this.text = text;
            this.until = until;
        }

        static Value text(String text) { return new Value(text, null); }
        static Value until(Instant until) { return new Value(null, until); }

        String format(ZonedDateTime now, Locale locale) {
            return until == null ? text : remaining(until, now, locale);
        }

        @Override public boolean equals(Object other) {
            return other instanceof Value && Arrays.asList(text, until).equals(Arrays.asList(((Value) other).text, ((Value) other).until));
        }

        @Override public int hashCode() { return Arrays.asList(text, until).hashCode(); }
        @Override public String toString() { return until == null ? text : "until " + until; }
    }

    static final class Action {
        final String id;
        final String label;
        final String icon;
        final boolean enabled;
        /** The call it was drawn for, such as "script.turn_on script.pause_washer". Its button hands it back. */
        final String target;

        Action(String id, String label, String icon, boolean enabled, String target) {
            this.id = id;
            this.label = label;
            this.icon = icon;
            this.enabled = enabled;
            this.target = target;
        }

        private List<Object> parts() { return Arrays.<Object>asList(id, label, icon, enabled, target); }
        @Override public boolean equals(Object other) { return other instanceof Action && parts().equals(((Action) other).parts()); }
        @Override public int hashCode() { return parts().hashCode(); }
        @Override public String toString() { return id + (enabled ? "" : " (off)"); }
    }

    /** One action on its way. Its card shows it and takes no other action until Home Assistant answers. */
    static final class Pending {
        final String action;
        final String label;
        /** The temperature asked for, shown as the target until Home Assistant reports it. */
        final Double target;
        /** Home Assistant accepted it; only the target is still waiting for the entity to report it. */
        boolean done;

        Pending(String action, String label, Double target) {
            this.action = action;
            this.label = label;
            this.target = target;
        }
    }

    static final class Feedback {
        final String text;
        final boolean error;
        final Instant until;

        Feedback(String text, boolean error, Instant until) {
            this.text = text;
            this.error = error;
            this.until = until;
        }
    }

    static final class Card {
        final String key;
        /** The top-left label, such as Home Assistant or LG ThinQ. Empty shows the icon alone. */
        final String heading;
        final String title;
        final String icon;
        /** Live, Connecting, Reconnecting or Unavailable. */
        final String connection;
        final Value status;
        /** Settings.STATUS, PROGRESS or CURRENT_TARGET. */
        final String layout;
        /** Null for none. */
        final Value primary;
        final String secondaryLabel;
        final Value secondary;
        /** 0 to 1, or null when there is no known progress, so no bar shows. */
        final Double progress;
        final Value current, target;
        final String currentLabel, targetLabel;
        /** What a climate entity is doing, such as Heating, or null. */
        final String operating;
        final List<Action> actions;
        final String note;
        final boolean noteError;
        /** When the note goes, in epoch ms, or 0 to keep it. */
        final long noteUntil;

        Card(String key, String heading, String title, String icon, String connection, Value status, String layout, Value primary,
                String secondaryLabel, Value secondary, Double progress, Value current, Value target, String currentLabel,
                String targetLabel, String operating, List<Action> actions, String note, boolean noteError, long noteUntil) {
            this.key = key;
            this.heading = heading;
            this.title = title;
            this.icon = icon;
            this.connection = connection;
            this.status = status;
            this.layout = layout;
            this.primary = primary;
            this.secondaryLabel = secondaryLabel;
            this.secondary = secondary;
            this.progress = progress;
            this.current = current;
            this.target = target;
            this.currentLabel = currentLabel;
            this.targetLabel = targetLabel;
            this.operating = operating;
            this.actions = Collections.unmodifiableList(actions);
            this.note = note;
            this.noteError = noteError;
            this.noteUntil = noteUntil;
        }

        boolean live() { return LIVE.equals(connection); }

        Action action(String id) {
            for (Action action : actions) if (action.id.equals(id)) return action;
            return null;
        }

        private List<Object> parts() {
            return Arrays.<Object>asList(key, heading, title, icon, connection, status, layout, primary, secondaryLabel, secondary, progress,
                current, target, currentLabel, targetLabel, operating, actions, note, noteError, noteUntil);
        }

        @Override public boolean equals(Object other) { return other instanceof Card && parts().equals(((Card) other).parts()); }
        @Override public int hashCode() { return parts().hashCode(); }
    }

    /**
     * The whole stack: every card that shows, in settings order, and the layout settings it is drawn with. Collapsed,
     * the stack draws the first [limit] that fit and counts the rest as "+N more"; expanded, it scrolls through them all.
     */
    static final class Board {
        static final Board EMPTY = new Board(Collections.<Card>emptyList(), 1, 100, "left", Settings.Style.THEMED);
        final List<Card> cards;
        final int limit;
        final int scale;
        final String align;
        final Settings.Style style;

        Board(List<Card> cards, int limit, int scale, String align, Settings.Style style) {
            this.cards = Collections.unmodifiableList(cards);
            this.limit = limit;
            this.scale = scale;
            this.align = align;
            this.style = style;
        }

        /** Cards past Maximum cards, before the screen height leaves out any more. */
        int overflow() { return Math.max(0, cards.size() - limit); }

        private List<Object> parts() { return Arrays.<Object>asList(cards, limit, scale, align, style); }
        @Override public boolean equals(Object other) { return other instanceof Board && parts().equals(((Board) other).parts()); }
        @Override public int hashCode() { return parts().hashCode(); }
    }

    /** Every card that shows, in settings order, never by value, with Maximum cards as the collapsed limit. */
    static Board board(List<Card> shown, Settings settings) {
        return new Board(new ArrayList<>(shown), settings.maxCards, settings.scale, settings.align(), settings.style);
    }

    /**
     * The card's top-left label. Integration falls back to Home Assistant until Home Assistant has named it, or when
     * it cannot; Entity name falls back to the entity ID.
     */
    static String heading(Settings settings, Settings.Activity a, Entity source, String integration) {
        switch (settings.heading) {
            case Settings.INTEGRATION:
                return integration == null || integration.isEmpty() ? Settings.HOME_ASSISTANT : integration;
            case Settings.ENTITY_NAME:
                return source == null ? a.entity : string(source.attributes.get("friendly_name"), a.entity);
            case Settings.CUSTOM:
                return settings.headingText;
            default:
                return Settings.HOME_ASSISTANT;
        }
    }

    /**
     * How many of the first cards fit whole in [available] px, from all of them down to none. Whenever any are left
     * out, the "+N more" line of [more] px takes a place too, so it always fits and always tells the truth. With none,
     * that line is all the stack shows, and it opens the scrolling list.
     */
    static int fit(int[] heights, int gap, int more, int available, int hiddenAlready) {
        for (int count = heights.length; count > 0; count--) {
            int total = 0;
            for (int i = 0; i < count; i++) total += heights[i] + (i > 0 ? gap : 0);
            if (count < heights.length || hiddenAlready > 0) total += gap + more;
            if (total <= available) return count;
        }
        return 0;
    }

    /** The card for [a], or null while it is hidden: turned off, not heard from yet, or its Show condition not met. */
    static Card card(Settings.Activity a, Map<String, Entity> heard, Pending pending, Feedback feedback, ZonedDateTime now,
            Locale locale, boolean preview, String temperatureUnit, String heading) {
        if (!a.enabled) return null;
        Entity source = heard.get(a.entity);
        if (source == null) return null;
        String hint = null;
        if (!Settings.ALWAYS.equals(a.showWhen)) {
            Entity when = heard.get(a.whenEntity);
            String whenState = when == null || when.state == null ? null : when.state.toLowerCase(Locale.ROOT);
            boolean matches = whenState != null && a.whenStates.contains(whenState);
            // unknown and unavailable show a card only when listed under When state is: they never pass as "not off".
            boolean unsure = "unknown".equals(whenState) || "unavailable".equals(whenState);
            boolean shown = whenState != null && (Settings.STATE_IS.equals(a.showWhen) ? matches : !matches && !unsure);
            // The preview is for placing the cards, so it shows them all and says why one would be hidden.
            if (!shown && !preview) return null;
            if (!shown) hint = "Hidden outside the preview: its Show condition is not met.";
        }
        List<Entity> read = new ArrayList<>();
        for (String id : a.entities()) read.add(heard.get(id));
        String connection = connection(read);
        boolean live = LIVE.equals(connection);

        String title = !a.title.isEmpty() ? a.title : string(source.attributes.get("friendly_name"), a.entity);
        String ownIcon = string(source.attributes.get("icon"), "");
        String icon = !a.icon.isEmpty() ? a.icon : ownIcon.startsWith("mdi:") ? ownIcon : typeIcon(a.type);
        boolean climate = Settings.CLIMATE.equals(a.type) && "climate".equals(a.domain());

        Settings.Binding statusBinding = a.values.get(Settings.SHOW_STATUS);
        Value status = statusBinding != null ? bound(statusBinding, heard, locale)
            : climate ? Value.text(climateMode(source)) : read(source, "", "", locale);
        Settings.Binding secondaryBinding = a.values.get(Settings.SECONDARY);
        Value primary = a.values.containsKey(Settings.PRIMARY) ? bound(a.values.get(Settings.PRIMARY), heard, locale) : null;
        Value secondary = secondaryBinding == null ? null : bound(secondaryBinding, heard, locale);

        Settings.Binding progressBinding = a.values.get(Settings.SHOW_PROGRESS);
        Double progress = null;
        if (progressBinding != null && progressBinding.max > progressBinding.min) {
            Double n = number(heard.get(progressBinding.entity), progressBinding.attribute);
            if (n != null) progress = Math.max(0, Math.min(1, (n - progressBinding.min) / (progressBinding.max - progressBinding.min)));
        }
        String layout = layout(a);
        // Progress on the right when nothing else is bound there. Unknown stays unknown, never 0%.
        if (Settings.PROGRESS.equals(layout) && primary == null && progressBinding != null) {
            primary = progress != null ? Value.text(Math.round(progress * 100) + "%")
                : progressBinding.max > progressBinding.min ? bound(progressBinding, heard, locale) : Value.text(NONE);
        }

        double step = climate ? step(source, temperatureUnit) : Double.NaN;
        Double target = climate ? number(source, "temperature") : null;
        Double shownTarget = pending != null && pending.target != null ? pending.target : target;
        Value current = null, targetValue = null;
        String currentLabel = "Current", targetLabel = "Target", operating = null;
        if (Settings.CURRENT_TARGET.equals(layout)) {
            if (climate) {
                Double measured = number(source, "current_temperature");
                current = Value.text(measured == null ? NONE : number(measured, locale) + "°");
                Double low = number(source, "target_temp_low"), high = number(source, "target_temp_high");
                targetValue = Value.text(shownTarget != null ? temperature(shownTarget, step, locale)
                    : low != null && high != null ? temperature(low, step, locale) + " – " + temperature(high, step, locale) : NONE);
                String action = string(source.attributes.get("hvac_action"), "");
                operating = action.isEmpty() ? null : humanize(action);
            }
            Settings.Binding currentBinding = a.values.get(Settings.CURRENT), targetBinding = a.values.get(Settings.TARGET);
            if (currentBinding != null) {
                current = bound(currentBinding, heard, locale);
                if (!currentBinding.label.isEmpty()) currentLabel = currentBinding.label;
            }
            if (targetBinding != null) {
                targetValue = bound(targetBinding, heard, locale);
                if (!targetBinding.label.isEmpty()) targetLabel = targetBinding.label;
            }
            if (current == null) current = Value.text(NONE);
            if (targetValue == null) targetValue = Value.text(NONE);
        }

        // Controls follow the state last heard, so they stay in place while reconnecting, but only work while live.
        List<Action> actions = new ArrayList<>();
        boolean ready = live && (pending == null || pending.done);
        String state = source.state;
        if (Settings.VACUUM.equals(a.type) && "vacuum".equals(a.domain()) && state != null) {
            // The picked controls in their order, each only when the vacuum supports it and it applies now.
            int features = integer(source.attributes.get("supported_features"));
            for (String control : a.vacuumControls) {
                String id = null;
                switch (control) {
                    case Settings.START:
                        if ((features & VACUUM_START) != 0 && Arrays.asList("docked", "idle").contains(state)) id = "start";
                        break;
                    case Settings.PAUSE:
                        if ("cleaning".equals(state) && (features & VACUUM_PAUSE) != 0) id = "pause";
                        else if ("paused".equals(state) && (features & VACUUM_START) != 0) id = "resume";
                        break;
                    case Settings.STOP:
                        if ((features & VACUUM_STOP) != 0 && Arrays.asList("cleaning", "paused", "returning").contains(state)) id = "stop";
                        break;
                    case Settings.DOCK:
                        if ((features & VACUUM_RETURN_HOME) != 0 && Arrays.asList("cleaning", "paused", "idle", "error").contains(state)) id = "dock";
                        break;
                    case Settings.LOCATE:
                        if ((features & VACUUM_LOCATE) != 0 && !Arrays.asList("unknown", "unavailable").contains(state)) id = "locate";
                        break;
                    default:
                        break;
                }
                if (id != null) actions.add(new Action(id, VACUUM_LABELS.get(id), VACUUM_ICONS.get(id), ready, target(a, id)));
            }
        }
        if (a.controls && climate && shownTarget != null && state != null && !Arrays.asList("off", "unknown", "unavailable").contains(state)
                && (integer(source.attributes.get("supported_features")) & CLIMATE_TARGET_TEMPERATURE) != 0) {
            actions.add(new Action("cooler", "Cooler", "mdi:minus", ready && adjusted(source, shownTarget, step, -1) != null, target(a, "cooler")));
            actions.add(new Action("warmer", "Warmer", "mdi:plus", ready && adjusted(source, shownTarget, step, 1) != null, target(a, "warmer")));
        }
        if (!a.action.isEmpty() && Settings.service(a.action) != null) {
            String label = !a.actionLabel.isEmpty() ? a.actionLabel : humanize(a.action.substring(a.action.indexOf('.') + 1));
            actions.add(new Action("run", label, a.action.startsWith("script.") ? "mdi:play" : "mdi:gesture-tap-button", ready, target(a, "run")));
        }

        String note = hint;
        boolean noteError = false;
        long noteUntil = 0;
        if (pending != null && !pending.done) {
            note = "Sending " + pending.label + "…";
        } else if (feedback != null && feedback.until.isAfter(now.toInstant())) {
            note = feedback.text;
            noteError = feedback.error;
            noteUntil = feedback.until.toEpochMilli();
        }
        return new Card(a.key, heading, title, icon, connection, status, layout, primary, secondaryBinding == null ? "" : secondaryBinding.label,
            secondary, progress, current, targetValue, currentLabel, targetLabel, operating, actions, note, noteError, noteUntil);
    }

    /** Automatic: current and target for climate, a bar when a progress value is set, otherwise status. */
    static String layout(Settings.Activity a) {
        if (!Settings.AUTOMATIC.equals(a.layout)) return a.layout;
        if (Settings.CLIMATE.equals(a.type) || (a.values.containsKey(Settings.CURRENT) && a.values.containsKey(Settings.TARGET))) {
            return Settings.CURRENT_TARGET;
        }
        return a.values.containsKey(Settings.SHOW_PROGRESS) ? Settings.PROGRESS : Settings.STATUS;
    }

    /** Live, unless an entity the card reads is still connecting, lost its connection or KS has no Home Assistant. */
    static String connection(List<Entity> entities) {
        int worst = 0;
        for (Entity e : entities) {
            int rank;
            if (e == null) rank = 1;
            else if ("not_configured".equals(e.status)) rank = 3;
            else if ("connecting".equals(e.status)) rank = e.known ? 2 : 1;
            else if ("disconnected".equals(e.status)) rank = e.known ? 2 : 3;
            else rank = 0;
            worst = Math.max(worst, rank);
        }
        return CONNECTIONS[worst];
    }

    private static Value bound(Settings.Binding binding, Map<String, Entity> heard, Locale locale) {
        return read(heard.get(binding.entity), binding.attribute, binding.unit, locale);
    }

    /** An entity's state, or one attribute, as the card shows it. Unknown, unavailable and missing each say so. */
    static Value read(Entity e, String attribute, String unit, Locale locale) {
        if (e == null) return Value.text(NONE);
        if ("missing".equals(e.status)) return Value.text("Not found");
        if ("too_large".equals(e.status)) return Value.text("Too large");
        if (attribute.isEmpty()) {
            String s = e.state;
            if (s == null) return Value.text(NONE);
            if ("unknown".equals(s)) return Value.text("Unknown");
            if ("unavailable".equals(s)) return Value.text("Unavailable");
            return scalar(s, unit.isEmpty() ? string(e.attributes.get("unit_of_measurement"), "") : unit,
                string(e.attributes.get("device_class"), ""), locale);
        }
        if (!e.attributes.containsKey(attribute)) return Value.text(NONE);
        Object v = e.attributes.get(attribute);
        if (v instanceof Number) return Value.text(withUnit(number(((Number) v).doubleValue(), locale), unit));
        if (v instanceof Boolean) return Value.text((Boolean) v ? "Yes" : "No");
        if (v instanceof String) return scalar((String) v, unit, "", locale);
        return Value.text(NONE); // null, a list or an object
    }

    private static Value scalar(String s, String unit, String deviceClass, Locale locale) {
        Instant at = instant(s);
        if (at != null) return Value.until(at);
        Double n = parse(s);
        if (n == null) return Value.text(humanize(s));
        Double minutes = "duration".equals(deviceClass) ? minutes(n, unit) : null;
        return Value.text(minutes != null ? duration(Math.round(minutes)) : withUnit(number(n, locale), unit));
    }

    /** A number from the state or an attribute, or null for anything else, unknown and unavailable included. */
    static Double number(Entity e, String attribute) {
        if (e == null || "missing".equals(e.status) || "too_large".equals(e.status)) return null;
        Object v = attribute.isEmpty() ? e.state : e.attributes.get(attribute);
        if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? null : d;
        }
        return v instanceof String ? parse((String) v) : null;
    }

    /** Finite plain decimal numbers only: Java would also take NaN, Infinity, hex and a trailing d or f. */
    static Double parse(String s) {
        String t = s.trim();
        if (!t.matches("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?")) return null;
        Double d = Double.valueOf(t);
        return d.isInfinite() ? null : d; // 1e309 overflows to infinity, which would read as a full bar
    }

    /** Home Assistant timestamps carry an offset. Anything without one is left as text. */
    static Instant instant(String s) {
        if (s.length() < 20 || s.charAt(4) != '-' || s.indexOf('T') != 10) return null;
        try {
            return OffsetDateTime.parse(s).toInstant();
        } catch (DateTimeParseException notATime) {
            return null;
        }
    }

    private static Double minutes(double n, String unit) {
        switch (unit) {
            case "d": return n * 1440;
            case "h": return n * 60;
            case "min": return n;
            case "s": return n / 60;
            case "ms": return n / 60000;
            default: return null;
        }
    }

    /** "1h 4m", "45m" or "2d 3h". */
    static String duration(long minutes) {
        long m = Math.max(0, minutes);
        if (m >= 1440) return m / 1440 + "d" + (m % 1440 >= 60 ? " " + m % 1440 / 60 + "h" : "");
        if (m >= 60) return m / 60 + "h" + (m % 60 > 0 ? " " + m % 60 + "m" : "");
        return m + "m";
    }

    /** Time left until a future time, in whole minutes rounded up. A past time shows as a time, never as done. */
    static String remaining(Instant until, ZonedDateTime now, Locale locale) {
        long seconds = until.getEpochSecond() - now.toEpochSecond();
        if (seconds > 0) return duration((seconds + 59) / 60);
        ZonedDateTime at = until.atZone(now.getZone());
        DateTimeFormatter format = at.toLocalDate().equals(now.toLocalDate()) ? DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
            : DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT);
        return format.withLocale(locale).format(at);
    }

    /** At most two decimals under 10, one under 100 and none above, in the kiosk's locale. */
    static String number(double v, Locale locale) {
        double a = Math.abs(v);
        return fixed(v, a >= 100 ? 0 : a >= 10 ? 1 : 2, 0, locale);
    }

    private static String fixed(double v, int maxDecimals, int minDecimals, Locale locale) {
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setGroupingUsed(false);
        format.setMaximumFractionDigits(maxDecimals);
        format.setMinimumFractionDigits(minDecimals);
        String text = format.format(v);
        return text.matches("-0([.,]0*)?") ? text.substring(1) : text;
    }

    /** A target on its step: 21.5° or 22.0° for half-degree steps, 72° for whole ones. */
    static String temperature(double v, double step, Locale locale) {
        return (!Double.isNaN(step) && step < 1 ? fixed(v, 1, 1, locale) : number(v, locale)) + "°";
    }

    static String withUnit(String number, String unit) {
        if (unit.isEmpty()) return number;
        return unit.equals("%") || unit.startsWith("°") ? number + unit : number + " " + unit;
    }

    /** "heat_cool" to "Heat cool". Home Assistant's own words otherwise: no states are invented or translated. */
    static String humanize(String s) {
        String t = s.trim().replace('_', ' ');
        return t.isEmpty() ? NONE : Character.toUpperCase(t.charAt(0)) + t.substring(1);
    }

    private static String climateMode(Entity e) {
        if (e.state == null) return NONE;
        switch (e.state) {
            case "off": return "Off";
            case "unknown": return "Unknown";
            case "unavailable": return "Unavailable";
            default: return humanize(e.state) + " mode";
        }
    }

    /** The entity's own step, else Home Assistant's default for its unit system: 0.5 °C or 1 °F. NaN until that is known. */
    static double step(Entity e, String temperatureUnit) {
        Double own = number(e, "target_temp_step");
        if (own != null && own > 0) return own;
        if (temperatureUnit == null) return Double.NaN;
        return "°F".equals(temperatureUnit) ? 1 : 0.5;
    }

    /** Whether a climate card's controls wait for Home Assistant's unit system to know their step. */
    static boolean needsUnit(Settings.Activity a, Entity e) {
        return a.controls && Settings.CLIMATE.equals(a.type) && e != null && e.known && number(e, "target_temp_step") == null;
    }

    /** One step up or down from [from], on the step grid and within min_temp and max_temp, or null when it cannot move. */
    static Double adjusted(Entity e, double from, double step, int direction) {
        if (Double.isNaN(step) || step <= 0) return null;
        double next = Math.round((from + direction * step) / step) * step;
        next = Math.round(next * 10000) / 10000.0;
        Double min = number(e, "min_temp"), max = number(e, "max_temp");
        if (min != null) next = Math.max(min, next);
        if (max != null) next = Math.min(max, next);
        return Math.abs(next - from) < 1e-9 ? null : next;
    }

    /**
     * What one of a card's actions calls, such as "vacuum.pause vacuum.rex". A button keeps the one it was drawn
     * with, so a press after a settings change made it call something else is dropped instead of sent.
     */
    static String target(Settings.Activity a, String action) {
        Map<String, Object> call = call(a, action, null);
        return call.get("domain") + "." + call.get("service") + " " + ((Map<?, ?>) call.get("target")).get("entity_id");
    }

    /** The Home Assistant call for one of a card's actions. */
    static Map<String, Object> call(Settings.Activity a, String action, Double temperature) {
        String domain, service, entity = a.entity;
        Map<String, Object> data = null;
        switch (action) {
            case "start":
            case "resume": domain = "vacuum"; service = "start"; break;
            case "pause": domain = "vacuum"; service = "pause"; break;
            case "stop": domain = "vacuum"; service = "stop"; break;
            case "dock": domain = "vacuum"; service = "return_to_base"; break;
            case "locate": domain = "vacuum"; service = "locate"; break;
            case "cooler":
            case "warmer":
                domain = "climate";
                service = "set_temperature";
                data = new HashMap<>();
                data.put("temperature", temperature);
                break;
            case "run":
                String[] call = Settings.service(a.action);
                domain = call[0];
                service = call[1];
                entity = a.action;
                break;
            default: throw new IllegalArgumentException("Unknown action " + action);
        }
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("type", "call_service");
        message.put("domain", domain);
        message.put("service", service);
        message.put("target", Collections.singletonMap("entity_id", entity));
        if (data != null) message.put("service_data", data);
        return message;
    }

    private static String typeIcon(String type) {
        switch (type) {
            case Settings.APPLIANCE: return "mdi:washing-machine";
            case Settings.VACUUM: return "mdi:robot-vacuum";
            case Settings.CLIMATE: return "mdi:thermostat";
            default: return "mdi:information-outline";
        }
    }

    private static String string(Object value, String fallback) {
        return value instanceof String && !((String) value).trim().isEmpty() ? ((String) value).trim() : fallback;
    }

    private static int integer(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }
}
