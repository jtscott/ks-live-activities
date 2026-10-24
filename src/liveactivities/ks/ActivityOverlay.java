// SPDX-License-Identifier: Apache-2.0
package liveactivities.ks;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Executor;
import me.jxl.kiosk.plugins.KsTheme;
import me.jxl.kiosk.plugins.OverlayFactory;

/**
 * The activity stack. KS builds an overlay's view slowly on low-power panels, so this stays one long-lived overlay
 * whose cards are redrawn in place when the plugin sends a new board. Collapsed, it shows the cards that fit and a
 * "+N more" button; expanded, it scrolls through every card within the height KS allows. It uses the KS theme: its card
 * surface, typeface, Material Design Icons and pill buttons, in the person's own colors where they chose them. The text
 * helpers and the width cap follow Screensaver Agenda's card.
 */
final class ActivityOverlay implements OverlayFactory {
    interface Controls {
        /** A button press, with the call the button was drawn for. */
        void press(String activity, String action, String target);
    }

    /** Card width. Less when the screen has no room for it. */
    private static final int CARD_DP = 360;
    /** The most of a card's main row the value column takes, and of the current/target row the chip takes. */
    static final float VALUE_SHARE = 0.45f, CHIP_SHARE = 0.4f;
    /** Space between cards, a little under KS's card gap so more fit. */
    private static final float GAP_DP = 12;
    private final Controls controls;
    private volatile Cards.Board board = Cards.Board.EMPTY;
    private volatile Stack view;

    ActivityOverlay(Controls controls) {
        this.controls = controls;
    }

    /** Any thread. The newest board wins, drawn on the main thread. */
    void show(Cards.Board next) {
        board = next;
        Stack current = view;
        if (current != null) current.post(current::render);
    }

    Cards.Board board() {
        return board;
    }

    @Override public View create(Context context, KsTheme theme) {
        Root root = new Root(context, theme);
        view = root.stack;
        return root;
    }

    @Override public void onThemeChanged(View view, KsTheme theme) {
        if (view instanceof Root) ((Root) view).stack.restyle(theme);
    }

    @Override public void onDestroy(View view) {
        if (!(view instanceof Root)) return;
        Stack stack = ((Root) view).stack;
        stack.halt();
        // A replacement may be built already, so only the view that went is forgotten.
        if (this.view == stack) this.view = null;
    }

    /**
     * How many buttons go in each row of [width] px: the most for which every button's natural width fits its share,
     * then spread evenly over the rows that takes, so four become two and two rather than three and one. One per
     * row when even that is too narrow; the label then ends with an ellipsis.
     */
    static int columns(int[] natural, int width, int gap) {
        int n = natural.length;
        for (int k = n; k > 1; k--) {
            int cell = (width - gap * (k - 1)) / k;
            boolean fits = true;
            for (int w : natural) fits &= w <= cell;
            if (fits) {
                int rows = (n + k - 1) / k;
                return (n + rows - 1) / rows;
            }
        }
        return Math.min(1, n);
    }

    /** The widest a capped column may be when its row offers [offered] px, after [reserve] px of fixed parts beside it. */
    static int cap(int offered, int reserve, float share) {
        return Math.max(0, Math.min(offered, Math.round((offered - reserve) * share)));
    }

    /**
     * Holds back redraws from finger down until the tap's click has run. A redraw replaces every button, and Android
     * cancels a press, or drops a posted click, whose button leaves the screen, so a value changing mid-tap would
     * swallow the tap. Main thread only; no Android types, so the JVM tests drive it with their own queue.
     */
    static final class Gate {
        /** Counts gestures, so the release of one that ended cannot open the gate on the next. */
        private int gesture;
        private boolean held;
        private boolean owed;

        void press() {
            gesture++;
            held = true;
        }

        /** Whether to redraw now. While held, the redraw is owed instead. */
        boolean ask() {
            if (held) owed = true;
            return !held;
        }

        /**
         * Finger up or cancel. Android posts the tap's click while handling it, so the release is posted after: every
         * redraw queued before it, early ones included, still waits, the click runs on the button it was meant for,
         * then the newest board is drawn once if any redraw was held back.
         */
        void up(Executor post, Runnable redraw) {
            final int ended = gesture;
            post.execute(() -> {
                if (release(ended)) redraw.run();
            });
        }

        /** True when a redraw is due. A release from an earlier gesture does nothing while a newer one holds. */
        boolean release(int ended) {
            if (ended != gesture) return false;
            boolean due = held && owed;
            held = false;
            owed = false;
            return due;
        }

        /** The view went: no release will come, and nothing needs drawing. */
        void reset() {
            release(gesture);
        }
    }

    /** The overlay's view: scrolls the expanded stack, and fits the collapsed one to the height KS measures it against. */
    private final class Root extends ScrollView {
        final Stack stack;

        Root(Context context, KsTheme theme) {
            super(context);
            stack = new Stack(context, theme);
            addView(stack, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT));
        }

        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            // A scroll view measures its content without a height limit, so the collapsed stack learns it here.
            if (MeasureSpec.getMode(heightSpec) != MeasureSpec.UNSPECIFIED) {
                stack.fit(getChildMeasureSpec(widthSpec, getPaddingLeft() + getPaddingRight(), FrameLayout.LayoutParams.WRAP_CONTENT),
                    MeasureSpec.getSize(heightSpec) - getPaddingTop() - getPaddingBottom());
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) stack.gate.press();
            boolean handled = super.dispatchTouchEvent(event);
            // After super, so the release queues behind the click the button just posted. A scroll ends the same way.
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) stack.gate.up(stack::post, stack::render);
            return handled;
        }
    }

    private final class Stack extends LinearLayout {
        private KsTheme theme;
        private float k = 1;
        private Cards.Board drawn = Cards.Board.EMPTY;
        private final List<View> cards = new ArrayList<>();
        private TextView more;
        /** Every card shows and the root scrolls. A new view, such as after a theme change, starts collapsed. */
        private boolean expanded;
        private boolean running;
        final Gate gate = new Gate();
        private final Runnable tick = this::render;

        Stack(Context context, KsTheme theme) {
            super(context);
            this.theme = theme;
            setOrientation(VERTICAL);
            render();
        }

        void restyle(KsTheme next) {
            theme = next;
            render();
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            running = true;
            schedule();
        }

        @Override protected void onDetachedFromWindow() {
            halt();
            super.onDetachedFromWindow();
        }

        void halt() {
            running = false;
            removeCallbacks(tick);
            gate.reset(); // a gesture cut short by removal never sends ACTION_UP here
        }

        /** Countdowns move each minute and notes leave on time, between the boards the plugin sends. */
        private void schedule() {
            removeCallbacks(tick);
            if (!running) return;
            long now = System.currentTimeMillis();
            long next = now - now % 60_000 + 60_250;
            for (Cards.Card card : drawn.cards) if (card.noteUntil > now) next = Math.min(next, card.noteUntil + 50);
            postDelayed(tick, next - now);
        }

        /**
         * Main thread, outside the callbacks KS guards: an exception here would end the app, so none escapes. New boards,
         * the minute tick and theme changes all wait here for a finger to lift; the newest board is drawn then.
         */
        void render() {
            if (!gate.ask()) return;
            try {
                drawn = board;
                k = drawn.scale / 100f;
                removeAllViews();
                cards.clear();
                setGravity("right".equals(drawn.align) ? Gravity.RIGHT : "center".equals(drawn.align) ? Gravity.CENTER_HORIZONTAL : Gravity.LEFT);
                ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
                for (Cards.Card card : drawn.cards) {
                    View view = card(card, now, Locale.getDefault());
                    // Collapsed, fit narrows this further to the cards the height allows.
                    if (!expanded && cards.size() >= drawn.limit) view.setVisibility(GONE);
                    addView(view, stacked(cards.isEmpty() ? 0 : GAP_DP));
                    cards.add(view);
                }
                more = label(expanded ? "Show fewer" : "+" + drawn.overflow() + " more", 13, 600, "onSurface");
                more.setGravity(Gravity.CENTER);
                more.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(color("onSurface"), 31)), surface(), null));
                more.setPadding(dp(16), 0, dp(16), 0);
                int tall = Math.max(dp(44), theme.px(44));
                more.setMinHeight(tall);
                more.setMinimumHeight(tall);
                more.setOnClickListener(view -> toggle());
                more.setVisibility(expanded || drawn.overflow() > 0 ? VISIBLE : GONE);
                addView(more, stacked(GAP_DP));
                setVisibility(drawn.cards.isEmpty() ? GONE : VISIBLE);
            } catch (RuntimeException failed) {
                setVisibility(GONE);
            }
            schedule();
        }

        /** "+N more" opens every card in a scrolling list; "Show fewer" closes it at the top. */
        private void toggle() {
            expanded = !expanded;
            render();
            if (!expanded && getParent() instanceof ScrollView) ((ScrollView) getParent()).scrollTo(0, 0);
        }

        /**
         * Collapsed: the first Maximum cards that fit whole in [available] px show, and "+N more" counts the rest. When
         * not even one fits, that button is all that shows, so nothing is ever cut off. Expanded: nothing to fit.
         */
        void fit(int widthSpec, int available) {
            if (expanded || more == null || cards.isEmpty()) return;
            try {
                int free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                int limit = Math.min(drawn.limit, cards.size());
                int[] heights = new int[limit];
                for (int i = 0; i < limit; i++) {
                    cards.get(i).measure(widthSpec, free);
                    heights[i] = cards.get(i).getMeasuredHeight();
                }
                more.measure(widthSpec, free);
                int fits = Cards.fit(heights, dp(GAP_DP), more.getMeasuredHeight(), available, cards.size() - limit);
                for (int i = 0; i < cards.size(); i++) shown(cards.get(i), i < fits);
                int hidden = cards.size() - fits;
                String text = "+" + hidden + " more";
                // Setting the same text would ask for another layout pass, and another, so only a change is set.
                if (!text.contentEquals(more.getText())) more.setText(text);
                // No gap above the button with no card above it. Changed in place, so no extra layout pass is asked for.
                ((LayoutParams) more.getLayoutParams()).topMargin = fits > 0 ? dp(GAP_DP) : 0;
                shown(more, hidden > 0);
            } catch (RuntimeException failed) {
                // Measure what is there.
            }
        }

        private void shown(View view, boolean shown) {
            int visibility = shown ? VISIBLE : GONE;
            if (view.getVisibility() != visibility) view.setVisibility(visibility);
        }

        private View card(Cards.Card c, ZonedDateTime now, Locale locale) {
            LinearLayout card = new Sized();
            card.setBackground(surface());
            card.setPadding(dp(16), dp(14), dp(16), dp(16));
            boolean live = c.live();

            // Header: where the data comes from, and whether it is live.
            LinearLayout header = row();
            header.addView(icon("mdi:home-assistant", "onSurfaceVariant"), square(15, 6));
            header.addView(single(c.heading, 12, 500, "onSurfaceVariant"), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            String role = live ? "success" : Cards.UNAVAILABLE.equals(c.connection) ? "error" : "tertiary";
            View dot = new View(getContext());
            GradientDrawable round = new GradientDrawable();
            round.setShape(GradientDrawable.OVAL);
            round.setColor(theme.color(role));
            dot.setBackground(round);
            header.addView(dot, square(8, 6));
            TextView connection = label(c.connection, 12, 600, live ? "onSurface" : role);
            // Like its dot, straight from the theme: the palette must not recolor whether the values can be trusted.
            connection.setTextColor(theme.color(live ? "onSurface" : role));
            header.addView(connection);
            card.addView(header, below(0));

            // Main: icon, title and status on the left, the value on the right.
            LinearLayout main = row();
            main.addView(icon(c.icon, "primary"), square(32, 12));
            LinearLayout left = column();
            TextView title = label(c.title, 18, 600, "onSurface");
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            left.addView(title);
            TextView status = single(c.status.format(now, locale), 14, 400, "onSurfaceVariant");
            left.addView(status, below(2));
            main.addView(left, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            List<View> values = new ArrayList<>();
            values.add(status);
            if (c.primary != null || c.secondary != null) {
                // At most 45% of what the icon and gaps leave, measured on the card as drawn, so the title keeps the rest.
                LinearLayout right = new Capped(VERTICAL, dp(32 + 12), VALUE_SHARE);
                right.setGravity(Gravity.END);
                if (c.primary != null) {
                    TextView primary = single(c.primary.format(now, locale), 22, 600, "onSurface");
                    primary.setGravity(Gravity.END);
                    right.addView(primary);
                }
                if (c.secondary != null) {
                    String value = c.secondary.format(now, locale);
                    TextView secondary = single(c.secondaryLabel.isEmpty() ? value : c.secondaryLabel + " " + value, 13, 400, "onSurfaceVariant");
                    secondary.setGravity(Gravity.END);
                    right.addView(secondary, below(2));
                }
                LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
                params.leftMargin = dp(12);
                main.addView(right, params);
                values.add(right);
            }
            card.addView(main, below(12));

            if (Settings.PROGRESS.equals(c.layout) && c.progress != null) {
                Bar bar = new Bar(c.progress.floatValue());
                LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, dp(8));
                params.topMargin = dp(14);
                card.addView(bar, params);
                values.add(bar);
            }
            if (Settings.CURRENT_TARGET.equals(c.layout)) {
                View targets = targets(c, now, locale);
                card.addView(targets, below(14));
                values.add(targets);
            }
            if (!c.actions.isEmpty()) card.addView(actions(c), below(14));
            if (c.note != null && (c.noteUntil == 0 || c.noteUntil > System.currentTimeMillis())) {
                TextView note = label(c.note, 13, 500, c.noteError ? "error" : "onSurfaceVariant");
                note.setMaxLines(2);
                note.setEllipsize(TextUtils.TruncateAt.END);
                card.addView(note, below(10));
            }
            // Values last heard before the connection dropped stay, dimmed, under the Reconnecting label.
            if (!live) for (View value : values) value.setAlpha(0.55f);
            return card;
        }

        /** Current and target, large, with what the device is doing between them. */
        private View targets(Cards.Card c, ZonedDateTime now, Locale locale) {
            LinearLayout row = row();
            row.addView(reading(c.current.format(now, locale), c.currentLabel, Gravity.START), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            // The same row shape as the title and value: the readings keep at least 60% of it.
            LinearLayout chip = new Capped(HORIZONTAL, 0, CHIP_SHARE);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setPadding(dp(12), dp(8), dp(12), dp(8));
            chip.setBackground(pill("surfaceContainerHighest"));
            chip.addView(icon(operatingIcon(c.operating), "primary"), square(20, c.operating == null ? 0 : 6));
            if (c.operating != null) {
                TextView operating = single(c.operating, 14, 600, "primary");
                operating.setMaxWidth(dp(120));
                chip.addView(operating);
            }
            LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            params.leftMargin = dp(8);
            params.rightMargin = dp(8);
            row.addView(chip, params);
            row.addView(reading(c.target.format(now, locale), c.targetLabel, Gravity.END), new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f));
            return row;
        }

        private LinearLayout reading(String value, String label, int gravity) {
            LinearLayout block = column();
            TextView big = single(value, 30, 600, "onSurface");
            TextView small = single(label, 13, 400, "onSurfaceVariant");
            big.setGravity(gravity);
            small.setGravity(gravity);
            block.addView(big);
            block.addView(small, below(2));
            return block;
        }

        /** Equal pill buttons in a row. A press while one is out, or while not live, does nothing: they are off. */
        private View actions(Cards.Card c) {
            Buttons buttons = new Buttons();
            for (Cards.Action action : c.actions) buttons.addView(button(c.key, action));
            return buttons;
        }

        /**
         * The card's buttons in order, as many to a row as fit whole, each row's buttons sharing its width equally. A
         * weighted row would squeeze four labelled buttons until the labels vanished.
         */
        private final class Buttons extends ViewGroup {
            private final int gap = dp(8);
            private int columns = 1;

            Buttons() {
                super(Stack.this.getContext());
            }

            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = MeasureSpec.getSize(widthSpec);
                int n = getChildCount();
                int free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
                int[] natural = new int[n];
                for (int i = 0; i < n; i++) {
                    getChildAt(i).measure(free, free);
                    natural[i] = getChildAt(i).getMeasuredWidth();
                }
                columns = ActivityOverlay.columns(natural, width, gap);
                int height = 0;
                for (int start = 0; start < n; start += columns) {
                    int count = Math.min(columns, n - start);
                    int cell = MeasureSpec.makeMeasureSpec(Math.max(0, (width - gap * (count - 1)) / count), MeasureSpec.EXACTLY);
                    int tallest = 0;
                    for (int i = start; i < start + count; i++) {
                        getChildAt(i).measure(cell, free);
                        tallest = Math.max(tallest, getChildAt(i).getMeasuredHeight());
                    }
                    height += (start > 0 ? gap : 0) + tallest;
                }
                setMeasuredDimension(width, height);
            }

            @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                int n = getChildCount();
                int y = 0;
                for (int start = 0; start < n; start += columns) {
                    int count = Math.min(columns, n - start);
                    int x = 0;
                    int tallest = 0;
                    for (int i = start; i < start + count; i++) {
                        View child = getChildAt(i);
                        child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
                        x += child.getMeasuredWidth() + gap;
                        tallest = Math.max(tallest, child.getMeasuredHeight());
                    }
                    y += tallest + gap;
                }
            }
        }

        /** A KS pill button: its icon and label, or the icon alone with the label as its spoken name. */
        private View button(final String key, final Cards.Action action) {
            LinearLayout button = row();
            button.setGravity(Gravity.CENTER);
            button.setBackground(new RippleDrawable(ColorStateList.valueOf(withAlpha(color("primary"), 31)), pill("surfaceContainerHighest"), null));
            button.setPadding(dp(12), dp(10), dp(12), dp(10));
            // A touch target of at least 44 dp, whatever Size says.
            button.setMinimumHeight(Math.max(dp(44), theme.px(44)));
            button.setContentDescription(action.label);
            boolean iconOnly = drawn.style.iconOnly;
            Drawable mark = tinted(theme.icon(action.icon, "primary"), "primary");
            if (mark != null) {
                ImageView image = new ImageView(getContext());
                image.setImageDrawable(mark);
                button.addView(image, square(iconOnly ? 22 : 18, iconOnly ? 0 : 6));
            }
            if (!iconOnly || mark == null) button.addView(single(action.label, 14.5f, 600, "primary"));
            button.setClickable(true);
            button.setEnabled(action.enabled);
            button.setAlpha(action.enabled ? 1f : 0.38f);
            button.setOnClickListener(view -> {
                try {
                    controls.press(key, action.id, action.target);
                } catch (RuntimeException sessionEnded) {
                    // KS turned the plugin off: nothing to do.
                }
            });
            return button;
        }

        /**
         * A rounded bar: the track, and the part done, in the accent, one color or a gradient. Colors are read here, not
         * in onDraw, where an error ends the app.
         */
        private final class Bar extends View {
            private final float fraction;
            private final Settings.Style style = drawn.style;
            private final int track = color("surfaceContainerHighest");
            private final int fill = style.barColor != null ? style.barColor : color("primary");
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF rect = new RectF();
            /** Spans the whole track, so the fill shows the part of it reached. Null for one color. */
            private LinearGradient gradient;

            Bar(float fraction) {
                super(Stack.this.getContext());
                this.fraction = fraction;
            }

            @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
                gradient = Settings.GRADIENT.equals(style.bar) && style.barColor != null && style.barEnd != null && width > 0
                    ? new LinearGradient(0, 0, width, 0, style.barColor, style.barEnd, Shader.TileMode.CLAMP) : null;
            }

            @Override protected void onDraw(Canvas canvas) {
                float radius = getHeight() / 2f;
                paint.setShader(null);
                paint.setColor(track);
                rect.set(0, 0, getWidth(), getHeight());
                canvas.drawRoundRect(rect, radius, radius, paint);
                if (fraction <= 0) return;
                paint.setColor(fill);
                paint.setShader(gradient);
                rect.set(0, 0, Math.max(getHeight(), getWidth() * fraction), getHeight());
                canvas.drawRoundRect(rect, radius, radius, paint);
            }
        }

        /**
         * A column or chip beside a weighted one. Android measures it against the whole row before the weighted side
         * gets what is left, so on its own a long value could leave the title no width at all.
         */
        private final class Capped extends LinearLayout {
            private final int reserve;
            private final float share;

            Capped(int orientation, int reserve, float share) {
                super(Stack.this.getContext());
                setOrientation(orientation);
                this.reserve = reserve;
                this.share = share;
            }

            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                // Only the first, open-ended pass: an exact width is what the row already settled on.
                if (MeasureSpec.getMode(widthSpec) == MeasureSpec.AT_MOST) {
                    widthSpec = MeasureSpec.makeMeasureSpec(cap(MeasureSpec.getSize(widthSpec), reserve, share), MeasureSpec.AT_MOST);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
        }

        /** A card CARD_DP wide, or what is left when the screen is narrower, so text wraps instead of running off. */
        private final class Sized extends LinearLayout {
            Sized() {
                super(Stack.this.getContext());
                setOrientation(VERTICAL);
            }

            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int width = dp(CARD_DP);
                if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) width = Math.min(width, MeasureSpec.getSize(widthSpec));
                super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), heightSpec);
            }
        }

        private String operatingIcon(String operating) {
            switch (operating == null ? "" : operating.toLowerCase(Locale.ROOT)) {
                case "heating":
                case "preheating": return "mdi:fire";
                case "cooling": return "mdi:snowflake";
                case "defrosting": return "mdi:snowflake-melt";
                case "drying": return "mdi:water-percent";
                case "fan": return "mdi:fan";
                case "idle": return "mdi:pause-circle-outline";
                case "off": return "mdi:power";
                default: return "mdi:arrow-right";
            }
        }

        private ImageView icon(String name, String role) {
            ImageView image = new ImageView(getContext());
            Drawable drawable = theme.icon(name, role);
            image.setImageDrawable(tinted(drawable != null ? drawable : theme.icon("mdi:information-outline", role), role));
            return image;
        }

        private Drawable tinted(Drawable drawable, String role) {
            if (drawable != null) drawable.setTint(color(role));
            return drawable;
        }

        /** A KS theme role, or the person's own color for it. The connection and error colors always follow the theme. */
        private int color(String role) {
            Settings.Style style = drawn.style;
            Integer own;
            switch (role) {
                case "surfaceContainerHigh": own = style.background; break;
                case "outlineVariant": own = style.outline; break;
                case "onSurface": own = style.text; break;
                case "onSurfaceVariant": own = style.secondaryText; break;
                case "primary": own = style.accent; break;
                case "surfaceContainerHighest": own = style.controls; break;
                default: own = null;
            }
            return own != null ? own : theme.color(role);
        }

        /** KS's card surface as KsTheme.card draws it, in the chosen colors, with Background opacity. */
        private GradientDrawable surface() {
            int background = color("surfaceContainerHigh");
            GradientDrawable card = new GradientDrawable();
            card.setColor(withAlpha(background, Math.round(Color.alpha(background) * drawn.style.opacity / 100f)));
            card.setCornerRadius(theme.px(theme.radiusCard()));
            card.setStroke(Math.max(1, theme.px(1)), color("outlineVariant"));
            return card;
        }

        private TextView label(String text, float sp, int weight, String role) {
            TextView view = new TextView(getContext());
            view.setText(text);
            theme.styleText(view, sp * k, weight, role);
            view.setTextColor(color(role));
            // Without these, Android pads each line for font padding and fallback fonts, which spread the card out.
            view.setIncludeFontPadding(false);
            if (Build.VERSION.SDK_INT >= 28) {
                view.setFallbackLineSpacing(false);
                view.setLineHeight(Math.round(view.getTextSize() * 1.2f));
            }
            return view;
        }

        private TextView single(String text, float sp, int weight, String role) {
            TextView view = label(text, sp, weight, role);
            view.setSingleLine(true);
            view.setEllipsize(TextUtils.TruncateAt.END);
            return view;
        }

        private GradientDrawable pill(String role) {
            GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(dp(100));
            shape.setColor(color(role));
            return shape;
        }

        private LinearLayout row() {
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            return row;
        }

        private LinearLayout column() {
            LinearLayout column = new LinearLayout(getContext());
            column.setOrientation(VERTICAL);
            return column;
        }

        private LayoutParams below(float topDp) {
            LayoutParams params = new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(topDp);
            return params;
        }

        /** A card or the "+N more" line: its own width, so the stack's gravity lines it up left, center or right. */
        private LayoutParams stacked(float topDp) {
            LayoutParams params = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT);
            params.topMargin = dp(topDp);
            return params;
        }

        private LayoutParams square(float sizeDp, float gapDp) {
            LayoutParams params = new LayoutParams(dp(sizeDp), dp(sizeDp));
            params.rightMargin = dp(gapDp);
            return params;
        }

        private int dp(float value) {
            return theme.px(value * k);
        }

        private int withAlpha(int color, int alpha) {
            return Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color));
        }
    }
}
