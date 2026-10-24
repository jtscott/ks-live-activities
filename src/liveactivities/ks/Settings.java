// SPDX-License-Identifier: Apache-2.0
package liveactivities.ks;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import me.jxl.kiosk.plugins.OverlaySpec;

/**
 * The plugin's settings, clamped to the manifest ranges. Immutable. Position, X, Y and Size and the overlay spec they
 * build come from Screensaver Agenda (Apache-2.0, jtscott), so both plugins place their cards the same way.
 */
final class Settings {
    /** KS follows at most 16 entities per plugin session and silently drops the 17th, so the plugin counts them itself. */
    static final int MAX_ENTITIES = 16;

    static final String APPLIANCE = "Appliance", VACUUM = "Vacuum", CLIMATE = "Climate", CUSTOM = "Custom";
    static final String AUTOMATIC = "Automatic", STATUS = "Status", PROGRESS = "Progress", CURRENT_TARGET = "Current/target";
    /** What a Values row shows. */
    static final String SHOW_STATUS = "Status", PRIMARY = "Primary value", SECONDARY = "Secondary value",
        SHOW_PROGRESS = "Progress", CURRENT = "Current", TARGET = "Target";
    static final String ALWAYS = "Always", STATE_IS = "When state is", STATE_IS_NOT = "When state is not";
    static final String HOME_ASSISTANT = "Home Assistant", INTEGRATION = "Integration", ENTITY_NAME = "Entity name";
    static final String THEME = "Theme", SOLID = "Solid", GRADIENT = "Gradient";
    /** The vacuum controls a person can pick, in the order they list them. */
    static final String START = "Start", PAUSE = "Pause", STOP = "Stop", DOCK = "Dock", LOCATE = "Locate";
    static final String ICON_ONLY = "Icon only";

    /**
     * Colors the person chose. A null color follows the KS theme's role, so light and dark still apply. The Live,
     * Reconnecting and Unavailable colors are never here: they always follow the theme, since they carry meaning.
     */
    static final class Style {
        static final Style THEMED = new Style(null, null, null, null, null, null, 100, THEME, null, null, false);
        final Integer background, outline, text, secondaryText, accent, controls;
        /** 20 to 100, for the card and "+N more" backgrounds. */
        final int opacity;
        /** THEME (the accent), SOLID or GRADIENT. */
        final String bar;
        final Integer barColor, barEnd;
        /** Card buttons show only their icon; the label stays as the screen reader's name. */
        final boolean iconOnly;

        Style(Integer background, Integer outline, Integer text, Integer secondaryText, Integer accent, Integer controls,
                int opacity, String bar, Integer barColor, Integer barEnd, boolean iconOnly) {
            this.background = background;
            this.outline = outline;
            this.text = text;
            this.secondaryText = secondaryText;
            this.accent = accent;
            this.controls = controls;
            this.opacity = opacity;
            this.bar = bar;
            this.barColor = barColor;
            this.barEnd = barEnd;
            this.iconOnly = iconOnly;
        }

        private List<Object> parts() {
            return java.util.Arrays.<Object>asList(background, outline, text, secondaryText, accent, controls, opacity, bar, barColor, barEnd, iconOnly);
        }

        @Override public boolean equals(Object other) { return other instanceof Style && parts().equals(((Style) other).parts()); }
        @Override public int hashCode() { return parts().hashCode(); }
    }

    /** One value on a card: an entity's state, or one of its attributes. */
    static final class Binding {
        final String entity;
        /** Empty reads the state. */
        final String attribute;
        final String label;
        /** Empty uses the entity's unit_of_measurement, which only the state has. */
        final String unit;
        final double min, max;

        Binding(String entity, String attribute, String label, String unit, double min, double max) {
            this.entity = entity;
            this.attribute = attribute;
            this.label = label;
            this.unit = unit;
            this.min = min;
            this.max = max;
        }
    }

    static final class Activity {
        /**
         * Its identity: type, entity and how many rows before it share both. Every kiosk in a fleet derives the same key
         * from the same synced settings, with nothing written back, and a reorder or a new title keeps it.
         */
        final String key;
        final String type;
        final String entity;
        final boolean enabled;
        final String title;
        final String icon;
        final String layout;
        /** The first Values row for each kind of value. */
        final Map<String, Binding> values;
        final String showWhen;
        final String whenEntity;
        /** Lowercase, so "Run" in the setting matches "run" from Home Assistant. */
        final Set<String> whenStates;
        /** Climate: Cooler and Warmer. */
        final boolean controls;
        /** Vacuum: the controls picked, in their order, each once. */
        final List<String> vacuumControls;
        /** Appliance and Custom: a script or button the card can run. Empty for none. */
        final String action;
        final String actionLabel;

        Activity(String key, Map<?, ?> row) {
            this.key = key;
            type = choice(row.get("type"), CUSTOM, APPLIANCE, VACUUM, CLIMATE, CUSTOM);
            entity = string(row.get("entity"), "");
            enabled = flag(row.get("enabled"), true);
            title = string(row.get("title"), "");
            icon = string(row.get("icon"), "");
            layout = choice(row.get("layout"), AUTOMATIC, AUTOMATIC, STATUS, PROGRESS, CURRENT_TARGET);
            values = bindings(row.get("values"), entity);
            showWhen = choice(row.get("showWhen"), ALWAYS, ALWAYS, STATE_IS, STATE_IS_NOT);
            whenEntity = string(row.get("whenEntity"), "").isEmpty() ? entity : string(row.get("whenEntity"), "");
            Set<String> states = new LinkedHashSet<>();
            for (String state : string(row.get("whenStates"), "").split(",")) {
                if (!state.trim().isEmpty()) states.add(state.trim().toLowerCase(Locale.ROOT));
            }
            whenStates = Collections.unmodifiableSet(states);
            boolean ownControls = VACUUM.equals(type) || CLIMATE.equals(type);
            controls = CLIMATE.equals(type) && flag(row.get("controls"), false);
            vacuumControls = VACUUM.equals(type) ? vacuumControls(row.get("vacuumControls")) : Collections.<String>emptyList();
            action = ownControls ? "" : string(row.get("action"), "");
            actionLabel = string(row.get("actionLabel"), "");
        }

        /** Every entity the card reads, the source first. The action entity is only called, never read. */
        Set<String> entities() {
            Set<String> out = new LinkedHashSet<>();
            out.add(entity);
            for (Binding binding : values.values()) out.add(binding.entity);
            if (!ALWAYS.equals(showWhen)) out.add(whenEntity);
            return out;
        }

        String domain() {
            return entity.substring(0, Math.max(0, entity.indexOf('.')));
        }
    }

    final List<Activity> activities;
    /** The enabled activities whose entities fit in MAX_ENTITIES, in list order. */
    final List<Activity> followed;
    /** The entities the followed activities read, in first-use order. */
    final Set<String> entities;
    /** Setup mistakes for the plugin's status, such as a vacuum card on a sensor. */
    final List<String> problems;
    final int maxCards;
    final String anchor;
    /** Position "Custom": the stack's exact place, x and y dp from the top left of the kiosk area. */
    final boolean custom;
    final int x, y;
    final int scale;
    /** HOME_ASSISTANT, INTEGRATION, ENTITY_NAME or CUSTOM: what each card's top-left label says. */
    final String heading;
    /** Custom's text. Empty shows the icon alone. */
    final String headingText;
    final Style style;

    private Settings(Map<String, Object> values) {
        List<Activity> all = new ArrayList<>();
        Map<String, Integer> seen = new HashMap<>();
        Object rows = values.get("activities");
        if (rows instanceof List) {
            for (Object row : (List<?>) rows) {
                if (!(row instanceof Map)) continue;
                Map<?, ?> r = (Map<?, ?>) row;
                String entity = string(r.get("entity"), "");
                if (entity.indexOf('.') <= 0) continue; // KS requires one; nothing to read without it
                String base = choice(r.get("type"), CUSTOM, APPLIANCE, VACUUM, CLIMATE, CUSTOM) + ":" + entity;
                int n = seen.containsKey(base) ? seen.get(base) + 1 : 0;
                seen.put(base, n);
                all.add(new Activity(n == 0 ? base : base + "#" + n, r));
            }
        }
        activities = Collections.unmodifiableList(all);
        List<Activity> fit = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        List<String> issues = new ArrayList<>();
        for (Activity activity : all) {
            if (!activity.enabled) continue;
            Set<String> next = new LinkedHashSet<>(used);
            next.addAll(activity.entities());
            if (next.size() > MAX_ENTITIES) {
                issues.add(name(activity) + " is left out: the activities above it already read " + used.size()
                    + " of the " + MAX_ENTITIES + " entities Kiosk Satellite follows.");
                continue;
            }
            used = next;
            fit.add(activity);
            issues.addAll(problems(activity));
        }
        followed = Collections.unmodifiableList(fit);
        entities = Collections.unmodifiableSet(used);
        problems = Collections.unmodifiableList(issues);
        maxCards = number(values.get("maxCards"), 3, 1, 6);
        anchor = anchor(values.get("position"));
        custom = "Custom".equals(values.get("position"));
        x = number(values.get("x"), 24, 0, 4096);
        y = number(values.get("y"), 24, 0, 4096);
        scale = number(values.get("scale"), 100, 60, 200);
        heading = choice(values.get("heading"), HOME_ASSISTANT, HOME_ASSISTANT, INTEGRATION, ENTITY_NAME, CUSTOM);
        headingText = string(values.get("headingText"), "");
        boolean own = CUSTOM.equals(values.get("palette"));
        String bar = choice(values.get("bar"), THEME, THEME, SOLID, GRADIENT);
        style = new Style(own ? color(values.get("background")) : null, own ? color(values.get("outline")) : null,
            own ? color(values.get("text")) : null, own ? color(values.get("secondaryText")) : null,
            own ? color(values.get("accent")) : null, own ? color(values.get("controls")) : null,
            number(values.get("opacity"), 100, 20, 100), bar,
            THEME.equals(bar) ? null : color(values.get("barColor")), GRADIENT.equals(bar) ? color(values.get("barEnd")) : null,
            ICON_ONLY.equals(values.get("buttonStyle")));
    }

    /** Each followed card's own entity: the ones whose integration the Integration heading names. */
    Set<String> sources() {
        Set<String> out = new LinkedHashSet<>();
        for (Activity activity : followed) out.add(activity.entity);
        return out;
    }

    static Settings parse(Map<String, Object> values) {
        return new Settings(values);
    }

    private static List<String> problems(Activity a) {
        List<String> out = new ArrayList<>();
        String name = name(a);
        if (VACUUM.equals(a.type) && !"vacuum".equals(a.domain())) out.add(name + ": Vacuum needs a vacuum entity.");
        if (CLIMATE.equals(a.type) && !"climate".equals(a.domain())) out.add(name + ": Climate needs a climate entity.");
        if (!a.action.isEmpty() && service(a.action) == null) out.add(name + ": the action must be a script or a button.");
        if (!ALWAYS.equals(a.showWhen) && a.whenStates.isEmpty()) out.add(name + ": list the states it shows for.");
        Binding progress = a.values.get(SHOW_PROGRESS);
        if (progress != null && progress.max <= progress.min) out.add(name + ": the progress maximum must be above its minimum.");
        return out;
    }

    /** The domain and service that run a script or press a button, or null for any other entity. */
    static String[] service(String entity) {
        if (entity.startsWith("script.")) return new String[] {"script", "turn_on"}; // turn_on returns at once; a direct call waits for the script
        if (entity.startsWith("button.")) return new String[] {"button", "press"};
        if (entity.startsWith("input_button.")) return new String[] {"input_button", "press"};
        return null;
    }

    static String name(Activity a) {
        return a.title.isEmpty() ? a.entity : a.title;
    }

    Activity followed(String key) {
        for (Activity activity : followed) if (activity.key.equals(key)) return activity;
        return null;
    }

    /** The overlay spec, as Screensaver Agenda builds it. */
    OverlaySpec spec() {
        // Over the screensaver like the voice overlay, and Back leaves it alone. It takes touches, unlike Agenda's
        // card: its buttons and "+N more" must work, and a stack that a short screen cuts down can always need one.
        OverlaySpec spec = OverlaySpec.at(anchor).onTop(true).touchable(true).closeOnBack(false);
        // From the top left with no inset, the offset is the stack's position.
        if (custom) return spec.offset(x, y);
        return spec.inset(Math.round(24 * scale / 100f));
    }

    /** What makes one spec differ from another, so hidden X and Y never replace the overlay. */
    static String key(OverlaySpec spec) {
        return spec.anchor() + " " + spec.insetLeft() + " " + spec.offsetX() + "," + spec.offsetY() + " " + spec.touchable();
    }

    /** How the cards line up within the stack: "left", "center" or "right", from the anchor. Custom lines up left. */
    String align() {
        if (anchor.endsWith("right")) return "right";
        if (anchor.equals(OverlaySpec.TOP) || anchor.equals(OverlaySpec.CENTER) || anchor.equals(OverlaySpec.BOTTOM)) return "center";
        return "left";
    }

    /** The Controls rows of a vacuum, in order, each control once; anything unknown drops out. */
    private static List<String> vacuumControls(Object rows) {
        Set<String> out = new LinkedHashSet<>();
        if (rows instanceof List) {
            for (Object row : (List<?>) rows) {
                if (row instanceof Map) {
                    String control = choice(((Map<?, ?>) row).get("control"), "", START, PAUSE, STOP, DOCK, LOCATE);
                    if (!control.isEmpty()) out.add(control);
                }
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(out));
    }

    /** Each kind of value from its first row. An empty entity reads the activity's own. */
    private static Map<String, Binding> bindings(Object rows, String entity) {
        Map<String, Binding> out = new LinkedHashMap<>();
        if (!(rows instanceof List)) return Collections.unmodifiableMap(out);
        for (Object row : (List<?>) rows) {
            if (!(row instanceof Map)) continue;
            Map<?, ?> r = (Map<?, ?>) row;
            String show = choice(r.get("show"), PRIMARY, SHOW_STATUS, PRIMARY, SECONDARY, SHOW_PROGRESS, CURRENT, TARGET);
            if (out.containsKey(show)) continue;
            String own = string(r.get("entity"), "");
            out.put(show, new Binding(own.isEmpty() ? entity : own, string(r.get("attribute"), ""), string(r.get("label"), ""),
                string(r.get("unit"), ""), decimal(r.get("min"), 0), decimal(r.get("max"), 100)));
        }
        return Collections.unmodifiableMap(out);
    }

    private static String choice(Object value, String fallback, String... options) {
        for (String option : options) if (option.equals(value)) return option;
        return fallback;
    }

    private static String string(Object value, String fallback) {
        return value instanceof String ? ((String) value).trim() : fallback;
    }

    /** An opaque ARGB color from KS's #RRGGBB, or null, which follows the theme. */
    static Integer color(Object value) {
        if (!(value instanceof String) || !((String) value).matches("#[0-9a-fA-F]{6}")) return null;
        return 0xFF000000 | Integer.parseInt(((String) value).substring(1), 16);
    }

    private static boolean flag(Object value, boolean fallback) {
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    private static int number(Object value, int fallback, int min, int max) {
        int n = value instanceof Number ? (int) Math.round(((Number) value).doubleValue()) : fallback;
        return Math.max(min, Math.min(max, n));
    }

    private static double decimal(Object value, double fallback) {
        return value instanceof Number && !Double.isNaN(((Number) value).doubleValue()) ? ((Number) value).doubleValue() : fallback;
    }

    /** The Position setting, with Screensaver Agenda's choices. */
    private static String anchor(Object position) {
        switch (String.valueOf(position)) {
            case "Top middle": return OverlaySpec.TOP;
            case "Top right": return OverlaySpec.TOP_RIGHT;
            case "Left middle": return OverlaySpec.LEFT;
            case "Center": return OverlaySpec.CENTER;
            case "Right middle": return OverlaySpec.RIGHT;
            case "Bottom left": return OverlaySpec.BOTTOM_LEFT;
            case "Bottom middle": return OverlaySpec.BOTTOM;
            case "Bottom right": return OverlaySpec.BOTTOM_RIGHT;
            default: return OverlaySpec.TOP_LEFT; // Custom too, where x and y place the stack from the top left
        }
    }
}
