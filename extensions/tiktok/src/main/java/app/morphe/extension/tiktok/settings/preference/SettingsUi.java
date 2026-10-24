/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference;

import static app.morphe.extension.shared.Utils.isDarkModeEnabled;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityManager;
import android.widget.AbsListView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CheckedTextView;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Switch;

import androidx.annotation.ColorInt;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

public final class SettingsUi {
    public static final @ColorInt int ACCENT = Color.argb(255, 240, 45, 99);
    public static final @ColorInt int DARK_BACKGROUND = Color.argb(255, 13, 13, 16);
    public static final @ColorInt int DARK_SURFACE = Color.argb(255, 21, 21, 26);
    public static final @ColorInt int DARK_SURFACE_LIFTED = Color.argb(255, 32, 32, 39);
    public static final @ColorInt int DARK_BORDER = Color.argb(255, 55, 55, 63);
    public static final @ColorInt int DARK_DIVIDER = Color.argb(255, 29, 29, 34);
    public static final @ColorInt int DARK_TEXT_PRIMARY = Color.argb(255, 242, 242, 245);
    public static final @ColorInt int DARK_TEXT_SECONDARY = Color.argb(255, 151, 151, 159);
    public static final @ColorInt int DARK_TEXT_DISABLED = Color.argb(255, 109, 109, 118);
    public static final @ColorInt int DARK_ERROR = Color.rgb(255, 138, 147);

    public static final @ColorInt int LIGHT_BACKGROUND = Color.WHITE;
    public static final @ColorInt int LIGHT_SURFACE = Color.WHITE;
    public static final @ColorInt int LIGHT_SURFACE_LIFTED = Color.argb(255, 250, 250, 250);
    public static final @ColorInt int LIGHT_BORDER = Color.argb(255, 210, 210, 210);
    public static final @ColorInt int LIGHT_DIVIDER = Color.argb(255, 224, 224, 224);
    public static final @ColorInt int LIGHT_TEXT_PRIMARY = Color.BLACK;
    public static final @ColorInt int LIGHT_TEXT_SECONDARY = Color.argb(255, 80, 80, 80);
    public static final @ColorInt int LIGHT_TEXT_DISABLED = Color.argb(255, 140, 140, 140);
    public static final @ColorInt int LIGHT_ERROR = Color.rgb(180, 35, 61);

    public static final int LIGHT_ACCENT = Color.rgb(184, 22, 77);

    static final @ColorInt int DARK_SWITCH_TRACK_OFF = Color.rgb(5, 5, 7);
    static final @ColorInt int LIGHT_SWITCH_TRACK_OFF = Color.rgb(180, 180, 186);
    static final @ColorInt int SWITCH_THUMB = Color.WHITE;
    static final @ColorInt int DARK_SWITCH_THUMB_STROKE = Color.rgb(225, 225, 230);
    static final @ColorInt int LIGHT_SWITCH_THUMB_STROKE = Color.rgb(116, 116, 127);

    public static @ColorInt int rippleTint() {
        return (accent() & 0x00FFFFFF) | 0x26000000;
    }

    /**
     * The corner radii this bundle draws with. Every rounded surface picks one of these rather
     * than a number of its own, so a card, a field and a chip on the same screen agree.
     *
     * <p>They were not agreeing. The Lab's view tabs were 5, which is not a step at all, and the
     * feed controls, the budget cue and the hold's release control were 24, a full circle and 12
     * while sitting on the same video.
     */
    /**
     * The type scale, in sp. Settings section labels use TEXT_CAPTION and row titles use
     * TEXT_TITLE, with compact descriptions at TEXT_LABEL.
     */
    public static final float TEXT_CAPTION = 12;
    public static final float TEXT_LABEL = 13;
    public static final float TEXT_BODY_SMALL = 14;
    public static final float TEXT_BODY = 15;
    public static final float TEXT_TITLE = 16;
    public static final float TEXT_TITLE_LARGE = 17;
    public static final float TEXT_HEADLINE_SMALL = 18;
    public static final float TEXT_HEADLINE = 20;
    public static final float TEXT_DISPLAY_SMALL = 30;
    public static final float TEXT_DISPLAY = 34;

    public static final int RADIUS_SQUARE = 0;
    public static final int RADIUS_BADGE = 4;
    public static final int RADIUS_CONTROL = 6;
    public static final int RADIUS_FIELD = 8;
    public static final int RADIUS_CARD = 10;

    /** Settings geometry stays independent of the controls drawn over a playing video. */
    public static final int RADIUS_SETTINGS_CARD = 6;
    public static final int RADIUS_SETTINGS_FIELD = 6;
    public static final int RADIUS_PILL = 24;
    /** Taller than any bar TikTok draws, so a pill's ends stay round at whatever height it's given. */
    public static final int RADIUS_ROUND_ENDS = 100;

    /** The focus ring's stroke, thick enough to read at arm's length on a phone. */
    private static final int FOCUS_RING_DP = 2;
    public static final int RADIUS_OVERLAY = 12;

    /**
     * The scrim behind anything this bundle draws on top of a video, and the hairline around it.
     *
     * <p>Five places built this same pair by hand with the same two argb literals, and then
     * rounded it three different ways, so the four feed controls, the budget cue and the hold's
     * release control read as three separate add-ons rather than one set. Fixed rather than
     * themed on purpose: a video is dark whatever the phone's theme says, and away from the
     * settings screen the shared theme flag answers for the system rather than for the feed.
     */
    public static final @ColorInt int OVERLAY_SCRIM = Color.argb(140, 0, 0, 0);
    public static final @ColorInt int OVERLAY_HAIRLINE = Color.argb(90, 255, 255, 255);
    /**
     * The scrim raised to carry a sentence. A chip holds one glyph and reads through the video
     * at 55 percent; a banner holds a line of 14sp text and an action, which need the video
     * mostly gone behind them. 232 rather than 220: over a white frame or TikTok's light comment
     * sheet, the red action text on 220 was 4.3:1, under the 4.5:1 that 14sp text needs. On 232
     * it is 4.9:1 there and 5.7:1 over black.
     */
    public static final @ColorInt int OVERLAY_BANNER_SCRIM = Color.argb(232, 0, 0, 0);
    /**
     * The camera and microphone marks. Fixed like the other overlay colours, since they sit on
     * video. They differ in shape as well as colour, so neither depends on telling the two apart.
     */
    public static final @ColorInt int INDICATOR_CAMERA = 0xFF34C759;
    public static final @ColorInt int INDICATOR_MICROPHONE = 0xFFFF9500;
    public static final @ColorInt int INDICATOR_RING = Color.argb(153, 0, 0, 0);
    /** Text and glyphs on {@link #OVERLAY_SCRIM}: white on it is 12.6:1. */
    public static final @ColorInt int OVERLAY_TEXT = Color.WHITE;
    public static final @ColorInt int OVERLAY_TEXT_MUTED = Color.argb(200, 255, 255, 255);
    /**
     * The scrim a panel that replaces the feed is painted with, rather than one that sits over it.
     * Its alpha is the value {@code HoldRamp.FULL_ALPHA} ramps to, so the ramp's last frame and
     * this panel's first frame are the same shade; {@code HoldRampTest} pins the pair.
     */
    public static final @ColorInt int OVERLAY_SCRIM_SOLID = Color.argb(238, 0, 0, 0);

    private SettingsUi() {
    }

    /** The phone's reading/action timeout may lengthen feedback, but never shorten its default. */
    public static long feedbackTimeout(Context context, int defaultMillis, boolean hasAction) {
        if (Build.VERSION.SDK_INT < 29 || context == null) return defaultMillis;
        try {
            AccessibilityManager manager = (AccessibilityManager)
                    context.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (manager == null) return defaultMillis;
            int flags = AccessibilityManager.FLAG_CONTENT_TEXT;
            if (hasAction) flags |= AccessibilityManager.FLAG_CONTENT_CONTROLS;
            return Math.max(defaultMillis, manager.getRecommendedTimeoutMillis(defaultMillis, flags));
        } catch (RuntimeException failure) {
            app.morphe.extension.shared.Logger.printException(
                    () -> "Could not read the accessibility feedback timeout", failure);
            return defaultMillis;
        }
    }

    /** Sync before painting any surface, since TikTok's theme can differ from the system's. */
    public static void syncDarkMode(Context context) {
        int nightMode = context.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK;
        Utils.setIsDarkModeEnabled(nightMode == Configuration.UI_MODE_NIGHT_YES);
    }

    public static boolean isDarkMode() {
        return isDarkModeEnabled();
    }

    public static boolean isDarkContext(android.content.Context context) {
        int night = context.getResources().getConfiguration().uiMode
                & android.content.res.Configuration.UI_MODE_NIGHT_MASK;
        return night == android.content.res.Configuration.UI_MODE_NIGHT_YES;
    }

    public static @ColorInt int accent() { return isDarkMode() ? ACCENT : LIGHT_ACCENT; }

    /**
     * What a filled chip is painted with. The plain accent is a light pink, and white on it is
     * 3.1:1, under what small text needs. The darker accent carries white in either theme, so
     * a badge and a selected pill use it and stay readable.
     */
    public static @ColorInt int badgeFill() { return LIGHT_ACCENT; }

    /** Text on {@link #badgeFill()}: 6.4:1, the same in both themes. */
    public static @ColorInt int badgeText() { return Color.WHITE; }

    /**
     * TikTok's own red, for the controls this patch draws over the app itself rather than in
     * the settings screen. Those sit on the app's surfaces, not on ours.
     */
    public static final @ColorInt int OVERLAY_ACCENT = Color.rgb(254, 44, 85);

    /** The same red, dark enough to read as text on a white surface (6.4:1 rather than 3.7:1). */
    public static @ColorInt int overlayAccentOn(boolean darkSurface) {
        return darkSurface ? OVERLAY_ACCENT : LIGHT_ACCENT;
    }

    /** Field and action failure text, measured to at least 4.5:1 on the current surface. */
    public static @ColorInt int error() {
        return isDarkMode() ? DARK_ERROR : LIGHT_ERROR;
    }

    /** Amber on a dark surface, 9.6:1, and burnt orange on a light one, 5.0:1. */
    public static final @ColorInt int DARK_ATTENTION = 0xFFFFA45B;
    public static final @ColorInt int LIGHT_ATTENTION = 0xFFB45309;
    public static final @ColorInt int DARK_OK = 0xFF6BCB77;
    public static final @ColorInt int LIGHT_OK = 0xFF1D7A37;
    /**
     * The home status card while Hushfeed is running, in the dark theme: the surface warmed
     * toward the accent, with a border between the two. The light theme keeps the plain card.
     */
    public static final @ColorInt int DARK_STATUS_SURFACE = 0xFF1D1119;
    public static final @ColorInt int DARK_STATUS_BORDER = 0xFF573244;

    public static @ColorInt int attentionColor() {
        return isDarkMode() ? DARK_ATTENTION : LIGHT_ATTENTION;
    }

    public static @ColorInt int okColor() {
        return isDarkMode() ? DARK_OK : LIGHT_OK;
    }

    /** The glyph every toned notice and status row leads with, so it is written once. */
    public static final String ATTENTION_GLYPH = "⚠";

    /**
     * A toned summary for a preference row: glyph in bold, both glyph and body in the tone
     * colour. The same glyph, gap and tone the {@link #inlineNotice} component uses, so the
     * two surfaces stay in step.
     */
    public static CharSequence tonedSummary(String glyph, @ColorInt int color, String body) {
        android.text.SpannableString result = new android.text.SpannableString(glyph + body);
        result.setSpan(new android.text.style.ForegroundColorSpan(color), 0, result.length(),
                android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        result.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),
                0, glyph.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return result;
    }

    /** The margins a notice keeps from whatever it sits between, so two of them match. */
    public static final int NOTICE_MARGIN = 14;

    public static android.widget.LinearLayout inlineNotice(
            android.content.Context context, String text, @ColorInt int toneColor) {
        android.widget.LinearLayout row = new android.widget.LinearLayout(context);
        row.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        // Top, not centre. A three-line paragraph centred its glyph against the middle of the
        // block instead of against the line it belongs to.
        row.setGravity(android.view.Gravity.TOP);
        int pad = dp(context, 12);
        row.setPadding(pad, pad, pad, pad);
        row.setBackground(borderedSurface(context, RADIUS_CARD, false));
        android.widget.TextView glyph = new android.widget.TextView(context);
        glyph.setText(ATTENTION_GLYPH);
        glyph.setTextColor(toneColor);
        glyph.setTextSize(TEXT_BODY_SMALL);
        glyph.setPadding(0, 0, dp(context, 8), 0);
        glyph.setImportantForAccessibility(android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(glyph, new android.widget.LinearLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
        android.widget.TextView body = new android.widget.TextView(context);
        body.setText(text);
        // The tone carries the notice, so it carries the sentence as well as the glyph. Both of
        // these pairs clear 4.5:1 on surface() in either theme.
        body.setTextColor(toneColor);
        body.setTextSize(TEXT_BODY_SMALL);
        row.addView(body, new android.widget.LinearLayout.LayoutParams(
                0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    /**
     * The recovery page's message, drawn as that page's title.
     *
     * <p>It was the first card on an otherwise empty page, flush with the top edge, so the one
     * screen that opens when settings fail looked like a list that had lost its header. Every
     * other page opens with a large title and a line under it, and so does this one now.
     */
    public static void styleErrorHeading(View row) {
        Context context = row.getContext();
        row.setBackground(null);
        // The list already sits 16dp in, so 8dp more puts the title where every page title starts.
        row.setPaddingRelative(dp(context, 8), dp(context, 56), dp(context, 8), dp(context, 20));
        View icon = row.findViewById(android.R.id.icon_frame);
        if (icon != null) icon.setVisibility(View.GONE);
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            if (title.getParent() instanceof View && title.getParent() != row) {
                ((View) title.getParent()).setPadding(0, 0, 0, 0);
            }
            title.setTextSize(TEXT_DISPLAY_SMALL);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            title.setTextColor(textPrimary());
            title.setSingleLine(false);
            title.setMaxLines(Integer.MAX_VALUE);
            title.setEllipsize(null);
            markHeading(title);
        }
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextSize(TEXT_BODY);
            summary.setTextColor(textSecondary());
            summary.setSingleLine(false);
            summary.setMaxLines(Integer.MAX_VALUE);
            summary.setEllipsize(null);
            summary.setPadding(0, dp(context, 12), 0, 0);
        }
    }

    /** The recovery page's actions: Retry in the accent as the way forward, Back plain as the way out. */
    public static void styleErrorAction(View row, boolean primary) {
        TextView title = row.findViewById(android.R.id.title);
        if (title == null) return;
        title.setTextColor(enabledTextColors(primary ? accent() : textPrimary()));
        title.setTypeface(Typeface.create("sans-serif-medium", primary ? Typeface.BOLD : Typeface.NORMAL));
    }

    public static void stylePreferenceRow(View view) {
        Context context = view.getContext();
        view.setPaddingRelative(dp(context, 22), dp(context, 16), dp(context, 22), dp(context, 16));
        view.setMinimumHeight(dp(context, 72));
        TextView title = view.findViewById(android.R.id.title);
        if (title != null) {
            if (title.getParent() instanceof View && title.getParent() != view) {
                ((View) title.getParent()).setPadding(0, 0, 0, 0);
            }
            title.setTextSize(TEXT_TITLE);
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            title.setTextColor(title.isEnabled() ? textPrimary() : textDisabled());
            title.setSingleLine(false);
            title.setMaxLines(Integer.MAX_VALUE);
            title.setEllipsize(null);
        }
        TextView summary = view.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextSize(14);
            summary.setTextColor(summary.isEnabled() ? textSecondary() : textDisabled());
            summary.setSingleLine(false);
            summary.setMaxLines(Integer.MAX_VALUE);
            summary.setEllipsize(null);
            summary.setLineSpacing(dp(context, 2), 1f);
            summary.setPadding(0, dp(context, 4), 0, 0);
        }
        styleSwitches(view);
    }

    /** Metra's flat navigation rows, with wrapping retained for larger text and translations. */
    public static void styleCompactMenuRow(View row, boolean last) {
        styleCompactMenuRow(row, last, false);
    }

    static void styleCompactMenuRow(View row, boolean last, boolean footer) {
        Context context = row.getContext();
        row.setPaddingRelative(dp(context, 20), dp(context, 8), dp(context, 20), dp(context, 8));
        row.setMinimumHeight(dp(context, 68));
        TextView title = row.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextSize(15.5f);
            title.setTextColor(enabledTextColors(textPrimary()));
            title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextSize(12.8f);
            summary.setTextColor(enabledTextColors(textSecondary()));
            summary.setLineSpacing(0, 1f);
            summary.setPadding(0, dp(context, 3), 0, 0);
        }
    }

    public static void styleSwitches(View view) {
        if (view instanceof Switch) styleSwitch((Switch) view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) styleSwitches(group.getChildAt(i));
        }
    }

    /** Keyed tags in the app's id space, which a plain tag and the chevron's string tag leave alone. */
    private static final int TAG_ROW_PAINT = 0x7f7f4001;
    private static final int TAG_SWITCH_PAINT = 0x7f7f4002;
    /** The row params the widget frame had before it moved in beside the title, to go back to. */
    private static final int TAG_WIDGET_ROW_PARAMS = 0x7f7f4003;

    /** What a row was last painted as, kept on the row so a rebind of the same view costs nothing. */
    private static final class RowPaint {
        final boolean first, last, dark;
        RowPaint(boolean first, boolean last, boolean dark) {
            this.first = first; this.last = last; this.dark = dark;
        }
    }

    /**
     * Paints the row as a member of its group, unless it already is. A list recycles its rows on
     * every frame of a scroll, and the ripple, the card, its mask and the switch drawables were
     * rebuilt for each one as it came into view. A row that comes back with the same edges in the
     * same theme keeps what it has; only a changed edge or a changed theme paints again.
     */
    public static void applyGroupedRow(View row, boolean first, boolean last) {
        boolean dark = isDarkMode();
        Object painted = row.getTag(TAG_ROW_PAINT);
        if (painted instanceof RowPaint && row.getBackground() != null) {
            RowPaint paint = (RowPaint) painted;
            if (paint.first == first && paint.last == last && paint.dark == dark) return;
        }
        row.setBackground(groupedRow(row.getContext(), first, last));
        row.setTag(TAG_ROW_PAINT, new RowPaint(first, last, dark));
    }

    /** Flat, full-width Metra settings rows. Selection and keyboard focus remain visible. */
    public static void applyMetraRow(View row, boolean last) {
        final int tag = 0x7f7f4004;
        Object previous = row.getTag(tag);
        int state = (isDarkMode() ? 1 : 0) | (last ? 2 : 0);
        if (previous instanceof Integer && (Integer) previous == state && row.getBackground() != null) return;
        Drawable paint = new Drawable() {
            private final Paint brush = new Paint(Paint.ANTI_ALIAS_FLAG);
            private boolean activated, focused;
            @Override public void draw(Canvas canvas) {
                Rect bounds = getBounds();
                brush.setStyle(Paint.Style.FILL);
                brush.setColor(background());
                canvas.drawRect(bounds, brush);
                if (activated) {
                    brush.setColor(activatedFill());
                    canvas.drawRect(bounds, brush);
                }
                if (!last) {
                    brush.setColor(divider());
                    canvas.drawRect(bounds.left, bounds.bottom - 1, bounds.right, bounds.bottom, brush);
                }
                if (focused) {
                    float width = dp(row.getContext(), FOCUS_RING_DP);
                    brush.setStyle(Paint.Style.STROKE);
                    brush.setStrokeWidth(width);
                    brush.setColor(accent());
                    float inset = width / 2f;
                    canvas.drawRect(bounds.left + inset, bounds.top + inset,
                            bounds.right - inset, bounds.bottom - inset, brush);
                }
            }
            @Override public boolean isStateful() { return true; }
            @Override protected boolean onStateChange(int[] states) {
                boolean active = false, focus = false;
                for (int value : states) {
                    active |= value == android.R.attr.state_activated;
                    focus |= value == android.R.attr.state_focused || value == android.R.attr.state_selected;
                }
                if (active == activated && focus == focused) return false;
                activated = active;
                focused = focus;
                invalidateSelf();
                return true;
            }
            @Override public void setAlpha(int alpha) { brush.setAlpha(alpha); }
            @Override public void setColorFilter(ColorFilter filter) { brush.setColorFilter(filter); }
            @Override public int getOpacity() { return PixelFormat.OPAQUE; }
        };
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(rippleTint()), paint,
                new ColorDrawable(Color.WHITE)));
        row.setTag(tag, state);
    }

    /**
     * A switch row whose text column would be narrower than this many ems of its title moves
     * the switch under the text and gives the text the whole row.
     */
    static final int NARROW_TITLE_EMS = 10;

    /**
     * Gives a switch row the shape its width can hold. The 44 dp switch and its 16 dp gap sit
     * beside the text, and at twice the text size on a 320 dp phone that leaves the title about
     * a dozen characters a line: "Kommentare automatisch übersetzen" came out as Kom-, mentare,
     * automatisch, übersetzen, with the summary wrapped in the same narrow column. When the
     * column would be under {@link #NARROW_TITLE_EMS} of the title's size, the switch moves
     * under the text, at the end of the row, and the title and summary take the whole width.
     * A switch beside the title alone still left the title that narrow column. The title's size
     * is read back from the view, so the user's text size and Android 14's curve both count. The
     * width is the row's own, so a wide list at a large size keeps the usual shape, and a row
     * recycled from one shape into the other goes back.
     *
     * @param rowWidth the width the row is laid out at, in pixels; nothing happens for zero
     */
    public static void reflowSwitchRow(View row, int rowWidth) {
        TextView title = row.findViewById(android.R.id.title);
        TextView summary = row.findViewById(android.R.id.summary);
        ViewGroup widget = row.findViewById(android.R.id.widget_frame);
        if (title == null || summary == null || widget == null || rowWidth <= 0) return;
        if (!(title.getParent() instanceof RelativeLayout) || summary.getParent() != title.getParent()) return;
        if (findSwitch(widget) == null) return;
        RelativeLayout text = (RelativeLayout) title.getParent();
        int column = rowWidth - row.getPaddingLeft() - row.getPaddingRight() - dp(row.getContext(), 44 + 16);
        boolean narrow = column < title.getTextSize() * NARROW_TITLE_EMS;
        if (narrow == (widget.getParent() == text)) return;
        if (narrow) {
            widget.setTag(TAG_WIDGET_ROW_PARAMS, widget.getLayoutParams());
            ((ViewGroup) widget.getParent()).removeView(widget);
            // Under the summary, or under the title when the summary is gone: RelativeLayout
            // follows a gone view's own anchor.
            RelativeLayout.LayoutParams under = new RelativeLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            under.addRule(RelativeLayout.BELOW, android.R.id.summary);
            under.addRule(RelativeLayout.ALIGN_PARENT_END);
            text.addView(widget, under);
        } else {
            text.removeView(widget);
            Object kept = widget.getTag(TAG_WIDGET_ROW_PARAMS);
            ((ViewGroup) row).addView(widget, kept instanceof ViewGroup.LayoutParams
                    ? (ViewGroup.LayoutParams) kept
                    : new android.widget.LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private static Switch findSwitch(View view) {
        if (view instanceof Switch) return (Switch) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                Switch found = findSwitch(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    public static void styleSwitch(Switch control) {
        Context context = control.getContext();
        // The track and thumb depend on the theme and nothing else, so a switch that already
        // wears this theme's pair keeps it. Four GradientDrawables and a state list per bind
        // was the cost of a scroll on a forty-row page.
        Object painted = control.getTag(TAG_SWITCH_PAINT);
        if (painted instanceof Boolean && (Boolean) painted == isDarkMode()
                && control.getTrackDrawable() != null && control.getThumbDrawable() != null) {
            return;
        }
        control.setTag(TAG_SWITCH_PAINT, isDarkMode());
        StateListDrawable track = new StateListDrawable();
        // Checked-and-disabled first, or the -state_enabled entry below answers for it and a
        // greyed switch looks the same on as off: a reader cannot see what state it will come
        // back in when the parent is turned on again.
        track.addState(new int[]{-android.R.attr.state_enabled, android.R.attr.state_checked},
                switchShape(context, (accent() & 0x00ffffff) | 0x66000000, 44, 26, 13));
        track.addState(new int[]{-android.R.attr.state_enabled},
                switchShape(context, border(), 44, 26, 13));
        track.addState(new int[]{android.R.attr.state_checked},
                switchShape(context, isDarkMode() ? Color.rgb(77, 27, 43) : Color.rgb(255, 228, 236),
                        44, 26, 13));
        track.addState(new int[]{}, switchShape(context,
                isDarkMode() ? DARK_SWITCH_TRACK_OFF : LIGHT_SWITCH_TRACK_OFF, 44, 26, 13));
        control.setTrackTintList(null);
        control.setThumbTintList(null);
        control.setTrackDrawable(track);
        StateListDrawable thumb = new StateListDrawable();
        thumb.addState(new int[]{-android.R.attr.state_enabled},
                switchShape(context, textDisabled(), 22, 22, 11));
        thumb.addState(new int[]{android.R.attr.state_checked},
                switchShape(context, accent(), 22, 22, 11));
        GradientDrawable offThumb = switchShape(context, SWITCH_THUMB, 22, 22, 11);
        offThumb.setStroke(dp(context, 1), isDarkMode() ? DARK_SWITCH_THUMB_STROKE : LIGHT_SWITCH_THUMB_STROKE);
        thumb.addState(new int[]{}, offThumb);
        control.setThumbDrawable(thumb);
        control.setSwitchMinWidth(dp(context, 44));
        control.setThumbTextPadding(0);
        control.setShowText(false);
        control.setSplitTrack(false);
        control.setMinimumHeight(dp(context, 48));
    }

    private static GradientDrawable switchShape(Context context, int color, int width, int height, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(context, radius));
        shape.setSize(dp(context, width), dp(context, height));
        return shape;
    }

    public static Drawable groupedRow(Context context, boolean first, boolean last) {
        return new RippleDrawable(ColorStateList.valueOf(rippleTint()),
                new GroupRowDrawable(context, first, last),
                groupRowMask(context, first, last));
    }

    /**
     * The shape a press is allowed to fill, which has to be the shape the row draws.
     *
     * <p>A plain rectangle let the ripple fill the transparent notches a card's first and last
     * row leave at the corners, so a press at the corner of a card spilled outside it. The
     * corners are rounded on the same two edges {@link GroupRowDrawable} rounds and square on
     * the others, where the row meets its neighbour.
     */
    public static Drawable groupRowMask(Context context, boolean first, boolean last) {
        float radius = dp(context, RADIUS_SETTINGS_CARD);
        float top = first ? radius : 0f;
        float bottom = last ? radius : 0f;
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(Color.WHITE);
        // Clockwise from the top left, two values per corner.
        mask.setCornerRadii(new float[]{top, top, top, top, bottom, bottom, bottom, bottom});
        return mask;
    }

    /**
     * The ring a control wears while the focus is on it.
     *
     * <p>A ripple is the only thing these surfaces had, and a RippleDrawable paints
     * {@code state_focused} as its own tint at 60% opacity: the accent at 15% alpha came out at
     * about 9% over the surface, near enough 1.3:1, which is nothing to look at with a keyboard,
     * a d-pad or switch access. The ring is the accent at full strength instead.
     *
     * <p>Selected as well as focused, for the same reason {@code pressAndFocus} takes both: a
     * list moves a d-pad by marking a row selected rather than focusing it.
     *
     * <p>It draws its own inset rather than being wrapped in one. An {@code InsetDrawable}
     * reports the inset as padding and a {@code LayerDrawable} nests a layer's inset into its
     * padding, and a View hands its background's padding to itself: wrapping this cost every
     * settings row a pixel on each side, which moved twenty-six tracked captures.
     */
    public static Drawable focusRing(Context context, int radiusDp) {
        return new FocusRingDrawable(dp(context, radiusDp), dp(context, FOCUS_RING_DP));
    }

    /** A rounded stroke in the accent, painted inside its own bounds, while focused. */
    private static final class FocusRingDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final float radius;
        private final float width;
        private boolean lit;

        FocusRingDrawable(float radius, float width) {
            this.radius = radius;
            this.width = width;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(width);
        }

        @Override public void draw(Canvas canvas) {
            if (!lit) return;
            Rect bounds = getBounds();
            float inset = width / 2f;
            if (bounds.width() <= width || bounds.height() <= width) return;
            paint.setColor(accent());
            canvas.drawRoundRect(new RectF(bounds.left + inset, bounds.top + inset,
                            bounds.right - inset, bounds.bottom - inset),
                    Math.max(0f, radius - inset), Math.max(0f, radius - inset), paint);
        }

        @Override public boolean isStateful() { return true; }

        @Override protected boolean onStateChange(int[] stateSet) {
            boolean next = false;
            for (int state : stateSet) {
                if (state == android.R.attr.state_focused || state == android.R.attr.state_selected) {
                    next = true;
                    break;
                }
            }
            if (next == lit) return false;
            lit = next;
            invalidateSelf();
            return true;
        }

        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * The check mark the dialogs draw, for a surface that has to show a choice without a dialog.
     *
     * <p>Drawn rather than typed, like every other glyph on this screen, so it keeps its weight
     * at any font scale. Returned already checked: a caller that has nothing to mark hides it.
     */
    /** The radio the standard single-choice dialogs draw, drawn chosen, for a row with no state of its own. */
    public static Drawable radioMark(Context context) {
        return new DialogCheckMarkDrawable(context, true, true);
    }

    public static Drawable checkMark(Context context) {
        // Pinned, not set through a state: an ImageView hands a stateful drawable its own
        // state the moment it is set, and no ImageView state carries state_checked, so a mark
        // that listened would be unchecked before it was ever drawn.
        return new DialogCheckMarkDrawable(context, false, true);
    }

    /**
     * The fill a row takes while it is one of the chosen ones, over {@link #surface}.
     *
     * <p>The same accent and the same alpha the ripple uses, so a held selection reads as the
     * press that made it rather than as a second idea.
     */
    public static int activatedFill() {
        return rippleTint();
    }

    private static final class GroupRowDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final boolean first, last;
        private final float radius, inset;
        private final Context context;
        /** Selected, as {@code View.setActivated} sets it. Repainted when it changes. */
        private boolean activated;
        /** Focused, or selected the way a list marks the row a d-pad is on. */
        private boolean focused;
        GroupRowDrawable(Context context, boolean first, boolean last) {
            this.context = context;
            this.first = first;
            this.last = last;
            radius = dp(context, RADIUS_SETTINGS_CARD);
            inset = dp(context, 16);
        }
        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            float top = bounds.top, bottom = bounds.bottom;
            RectF frame = new RectF(bounds.left + 0.5f, first ? top + 0.5f : top - radius,
                    bounds.right - 0.5f, last ? bottom - 0.5f : bottom + radius);
            canvas.save();
            canvas.clipRect(bounds);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(surface());
            canvas.drawRoundRect(frame, radius, radius, paint);
            if (activated) {
                // Over the surface rather than instead of it, so the tint is the same one the
                // ripple leaves behind and the row keeps its own background underneath.
                paint.setColor(activatedFill());
                canvas.drawRoundRect(frame, radius, radius, paint);
            }
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(1);
            paint.setColor(activated ? accent() : border());
            canvas.drawRoundRect(frame, radius, radius, paint);
            if (!last) {
                paint.setColor(divider());
                canvas.drawLine(bounds.left + inset, bottom - 0.5f, bounds.right - inset, bottom - 0.5f, paint);
            }
            if (focused) {
                // Last, and with its own stroke width: this Paint is shared, and a ring drawn
                // before the divider left the divider at the ring's width, which on a 3x screen
                // is a six pixel grey bar under every focused row of a card.
                float width = dp(context, FOCUS_RING_DP);
                float inset = width / 2f;
                // Against the row's own box rather than the card frame. A middle row's frame
                // runs a radius past the top and bottom of the clip, so a ring drawn on it kept
                // only its two vertical edges and the reader saw a pair of bars, no ring.
                RectF ring = new RectF(bounds.left + inset, top + inset,
                        bounds.right - inset, bottom - inset);
                float outer = Math.max(0f, radius - inset);
                float head = first ? outer : 0f;
                float foot = last ? outer : 0f;
                // Clockwise from the top left, two values per corner: rounded only where the
                // card is, square where this row meets its neighbour.
                Path path = new Path();
                path.addRoundRect(ring, new float[]{head, head, head, head,
                        foot, foot, foot, foot}, Path.Direction.CW);
                paint.setStrokeWidth(width);
                paint.setColor(accent());
                canvas.drawPath(path, paint);
            }
            canvas.restore();
        }
        /**
         * The row is told it is selected with {@code setActivated}, and a drawable that is not
         * stateful is never asked. The Lab's selection bar said "2 gates selected" over rows that
         * looked exactly like the rest, and Enable, Disable and Reset then acted on them.
         */
        @Override public boolean isStateful() { return true; }
        @Override protected boolean onStateChange(int[] stateSet) {
            boolean nextActivated = false;
            boolean nextFocused = false;
            for (int state : stateSet) {
                if (state == android.R.attr.state_activated) nextActivated = true;
                if (state == android.R.attr.state_focused || state == android.R.attr.state_selected) {
                    nextFocused = true;
                }
            }
            if (nextActivated == activated && nextFocused == focused) return false;
            activated = nextActivated;
            focused = nextFocused;
            invalidateSelf();
            return true;
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * The same in pixels without rounding, for a stroke width.
     *
     * <p>Paint.setStrokeWidth takes canvas pixels. Every hand drawn glyph here was given its
     * width as though it were dp, so on a 420 dpi phone the menu tile's lines came out 0.69dp
     * wide and the back arrow 0.8dp: hairlines beside 40sp type. The screenshots are captured at
     * density 1, which is why they looked right.
     */
    public static float strokePx(Context context, float dpValue) {
        return dpValue * context.getResources().getDisplayMetrics().density;
    }

    /*
     * Two accessors for every colour. The no-argument one reads the shared theme flag and is
     * what the settings screen wants. The one taking a theme is for a surface that worked out
     * its own: the comment sheet is drawn in TikTok's theme, which is not always the system's,
     * so it decides for itself and then asks for the matching value. It used to carry its own
     * copy of eight of these as hex literals, which is the same palette maintained twice.
     */

    public static @ColorInt int background() {
        return backgroundOn(isDarkMode());
    }

    public static @ColorInt int backgroundOn(boolean dark) {
        return dark ? DARK_BACKGROUND : LIGHT_BACKGROUND;
    }

    public static @ColorInt int surface() {
        return surfaceOn(isDarkMode());
    }

    public static @ColorInt int surfaceOn(boolean dark) {
        return dark ? DARK_SURFACE : LIGHT_SURFACE;
    }

    public static @ColorInt int liftedSurface() {
        return liftedSurfaceOn(isDarkMode());
    }

    public static @ColorInt int liftedSurfaceOn(boolean dark) {
        return dark ? DARK_SURFACE_LIFTED : LIGHT_SURFACE_LIFTED;
    }

    public static @ColorInt int border() {
        return borderOn(isDarkMode());
    }

    public static @ColorInt int borderOn(boolean dark) {
        return dark ? DARK_BORDER : LIGHT_BORDER;
    }

    public static @ColorInt int divider() {
        return dividerOn(isDarkMode());
    }

    public static @ColorInt int dividerOn(boolean dark) {
        return dark ? DARK_DIVIDER : LIGHT_DIVIDER;
    }

    public static @ColorInt int textPrimary() {
        return textPrimaryOn(isDarkMode());
    }

    public static @ColorInt int textPrimaryOn(boolean dark) {
        return dark ? DARK_TEXT_PRIMARY : LIGHT_TEXT_PRIMARY;
    }

    public static @ColorInt int textSecondary() {
        return textSecondaryOn(isDarkMode());
    }

    public static @ColorInt int textSecondaryOn(boolean dark) {
        return dark ? DARK_TEXT_SECONDARY : LIGHT_TEXT_SECONDARY;
    }

    public static @ColorInt int textDisabled() {
        return textDisabledOn(isDarkMode());
    }

    public static @ColorInt int textDisabledOn(boolean dark) {
        return dark ? DARK_TEXT_DISABLED : LIGHT_TEXT_DISABLED;
    }

    /** The accent for a surface that worked out its own theme. */
    public static @ColorInt int accentOn(boolean dark) {
        return dark ? ACCENT : LIGHT_ACCENT;
    }

    /** Text that repaints itself when its control is enabled or disabled. */
    public static ColorStateList enabledTextColors(@ColorInt int enabledColor) {
        return new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{}
                },
                new int[]{textDisabled(), enabledColor}
        );
    }

    public static void styleTitleAndSummary(View view) {
        TextView title = view.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(title.isEnabled() ? textPrimary() : textDisabled());
        }

        TextView summary = view.findViewById(android.R.id.summary);
        if (summary != null) {
            summary.setTextColor(summary.isEnabled() ? textSecondary() : textDisabled());
        }
    }

    public static void styleCategory(View view) {
        TextView title = view.findViewById(android.R.id.title);
        if (title != null) {
            title.setTextColor(accent());
            title.setTextSize(13);
            title.setAllCaps(true);
            title.setLetterSpacing(0);
            title.setTypeface(title.getTypeface(), Typeface.BOLD);
        }
    }

    public static TextView sectionTitle(Context context, String text) {
        TextView title = new TextView(context);
        title.setText(text);
        title.setTextColor(accent());
        title.setTextSize(TEXT_LABEL);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        markHeading(title);
        return title;
    }

    public static TextView text(Context context, String value, float sizeSp, int color, int style) {
        TextView textView = new TextView(context);
        textView.setText(value);
        textView.setIncludeFontPadding(true);
        textView.setTextColor(enabledTextColors(color));
        textView.setTextSize(sizeSp);
        textView.setTypeface(textView.getTypeface(), style);
        return textView;
    }

    public static TextView dialogTitle(Context context, CharSequence text) {
        TextView title = text(context, text.toString(), 20, textPrimary(), Typeface.BOLD);
        markDialogHeading(title);
        int padding = dp(context, 22);
        title.setPadding(padding, padding, padding, dp(context, 12));
        return title;
    }

    /** Marks a title in a hand-built dialog as a heading for accessibility services. */
    public static void markDialogHeading(TextView title) {
        markHeading(title);
    }

    /**
     * Marks an app-owned page, section, recovery or dialog title as a heading for accessibility
     * services on every supported API level. Body rows stay ordinary controls.
     */
    public static void markHeading(TextView title) {
        if (Build.VERSION.SDK_INT >= 28) {
            title.setAccessibilityHeading(true);
            return;
        }
        // Before API 28, accessibility services recognize the heading bit carried by a
        // CollectionItemInfo. These titles are views this app creates, or the host's plain preference
        // title, and none carries a delegate to preserve; the delegate only adds the heading bit.
        title.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                    View host,
                    AccessibilityNodeInfo info
            ) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(
                        0, 1, 0, 1, true));
            }
        });
    }

    /** A visible status line that politely announces a filtered list's result count. */
    public static TextView resultCount(Context context, String tag) {
        TextView result = text(context, "", 13, textSecondary(), Typeface.BOLD);
        result.setTag(tag);
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        return result;
    }

    /**
     * Writes text only when it is not already there.
     *
     * <p>{@link TextView#setText} does not compare, so setting the same words again still posts a
     * content-changed event. On a surface that refreshes on a timer that is a screen reader being
     * interrupted, once a timer tick, while nothing on screen has moved: the hold panel repeated
     * its countdown, its release label and its hint once a second for the length of a hold.
     */
    public static void setTextIfChanged(TextView view, CharSequence text) {
        if (view == null || TextUtils.equals(view.getText(), text)) return;
        view.setText(text);
    }

    /** Updates a result status only when it changed, avoiding duplicate announcements. */
    public static void setResultCount(TextView view, int count) {
        if (view == null) return;
        String next = L10n.quantity(view.getContext(), count, "1 result", "%1$d results");
        if (!TextUtils.equals(view.getText(), next)) view.setText(next);
    }

    /**
     * The X that clears a field, drawn rather than typed.
     *
     * <p>It carries its own intrinsic size so a TextView can hang it off the end of a box without
     * being told how big it is, and it sizes from the density rather than the field, so it stays
     * a touch target at a large font scale instead of growing with the letters.
     */
    public static final class ClearGlyphDrawable extends Drawable {
        private static final float ARM_FRACTION = 0.26f;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int size;

        public ClearGlyphDrawable(Context context, @ColorInt int color) {
            paint.setColor(color);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(strokePx(context, 1.8f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            size = dp(context, 22);
        }

        @Override public int getIntrinsicWidth() {
            return size;
        }

        @Override public int getIntrinsicHeight() {
            return size;
        }

        @Override public void draw(Canvas canvas) {
            Rect bounds = getBounds();
            if (bounds.isEmpty()) return;
            float centerX = bounds.exactCenterX();
            float centerY = bounds.exactCenterY();
            float arm = Math.min(bounds.width(), bounds.height()) * ARM_FRACTION;
            canvas.drawLine(centerX - arm, centerY - arm, centerX + arm, centerY + arm, paint);
            canvas.drawLine(centerX - arm, centerY + arm, centerX + arm, centerY - arm, paint);
        }

        @Override public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
            invalidateSelf();
        }

        @Override public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
            invalidateSelf();
        }

        @Override public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }

    public static GradientDrawable roundedSurface(Context context, int radiusDp, boolean lifted) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(lifted ? liftedSurface() : surface());
        drawable.setCornerRadius(dp(context, radiusDp));
        return drawable;
    }

    public static GradientDrawable borderedSurface(Context context, int radiusDp, boolean lifted) {
        GradientDrawable drawable = roundedSurface(context, radiusDp, lifted);
        drawable.setStroke(Math.max(1, dp(context, 1)), border());
        return drawable;
    }

    /**
     * The backdrop for a control this bundle draws over a video: the shared scrim, the shared
     * hairline, and one radius from the scale.
     *
     * <p>Every caller used to build this by hand, which is how four feed controls ended up as
     * circles, the budget cue as a 12dp rectangle and the hold's release as a 24dp pill, all on
     * the same screen and all meant to read as the same thing.
     */
    public static GradientDrawable overlayChip(Context context, int radiusDp) {
        GradientDrawable chip = new GradientDrawable();
        chip.setShape(GradientDrawable.RECTANGLE);
        chip.setCornerRadius(dp(context, radiusDp));
        chip.setColor(OVERLAY_SCRIM);
        chip.setStroke(Math.max(1, dp(context, 1)), OVERLAY_HAIRLINE);
        return chip;
    }

    /**
     * The backdrop of a banner drawn over a video: the chip's radius and hairline on the
     * raised scrim, so the Undo banner reads as one family with the controls beside it.
     */
    public static GradientDrawable overlayBanner(Context context) {
        GradientDrawable banner = overlayChip(context, RADIUS_OVERLAY);
        banner.setColor(OVERLAY_BANNER_SCRIM);
        return banner;
    }

    /**
     * A control drawn over a video, with the press and focus states the settings rows have.
     *
     * <p>Every control this bundle draws inside TikTok looked identical before, during and after
     * a press, and showed nothing at all to a reader moving with a keyboard, a d-pad or switch
     * access. The block button was the only one that changed, and only by fading the whole chip
     * to 40 percent while a request was in flight.
     *
     * <p>The ring is a stroke that is only coloured while the control has focus, so one drawable
     * carries both states and there is no second copy of the chip to keep in step.
     *
     * @param glyph drawn over the backdrop, or null. The block button's ring is drawn rather than
     *              typed, because the font TikTok is using may not carry the character.
     */
    public static Drawable overlayControl(Context context, int radiusDp, Drawable glyph) {
        return overlayControl(context, radiusDp, glyph, overlayChip(context, radiusDp));
    }

    public static Drawable overlayControl(Context context, int radiusDp) {
        return overlayControl(context, radiusDp, null);
    }

    /**
     * The same press and focus states for a control that sits on TikTok's own surface.
     *
     * <p>Undo, Inbox Clear all and Save media are drawn on a sheet the host painted, so they take
     * no scrim of their own: a backdrop would make them look like something dropped onto the
     * page rather than part of it.
     */
    public static Drawable overlayAction(Context context, int radiusDp) {
        return overlayControl(context, radiusDp, null, new ColorDrawable(Color.TRANSPARENT),
                OVERLAY_TEXT, OVERLAY_HAIRLINE);
    }

    /**
     * The same press and focus pair in the colour of the surface it sits on.
     *
     * <p>For an action drawn onto one of TikTok's own surfaces rather than over the video: the
     * inbox header and the sticker sheet follow the app's theme, so a white ring and a white
     * ripple vanish on them in the light theme. The tone is the text colour the host paints
     * there, which is by definition visible against its background.
     */
    public static Drawable overlayAction(Context context, int radiusDp, @ColorInt int tone) {
        return overlayControl(context, radiusDp, null, new ColorDrawable(Color.TRANSPARENT),
                tone, (tone & 0x00ffffff) | 0x40000000);
    }

    private static Drawable overlayControl(Context context, int radiusDp, Drawable glyph,
            Drawable backdrop) {
        return overlayControl(context, radiusDp, glyph, backdrop, OVERLAY_TEXT, OVERLAY_HAIRLINE);
    }

    private static Drawable overlayControl(Context context, int radiusDp, Drawable glyph,
            Drawable backdrop, @ColorInt int ringColor, @ColorInt int rippleColor) {
        GradientDrawable ring = new GradientDrawable();
        ring.setShape(GradientDrawable.RECTANGLE);
        ring.setCornerRadius(dp(context, radiusDp));
        ring.setStroke(Math.max(2, dp(context, 2)), new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[0]},
                new int[]{ringColor, Color.TRANSPARENT}));

        Drawable content = glyph == null
                ? new LayerDrawable(new Drawable[]{backdrop, ring})
                : new LayerDrawable(new Drawable[]{backdrop, glyph, ring});

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(dp(context, radiusDp));
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    public static void styleDialog(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            View decorView = window.getDecorView();
            if (decorView != null) {
                decorView.setBackgroundColor(Color.TRANSPARENT);
            }
            constrainDialogWindow(dialog, window);
        }

        if (dialog instanceof AlertDialog) {
            AlertDialog alertDialog = (AlertDialog) dialog;
            View content = alertDialog.findViewById(android.R.id.content);
            if (content != null) {
                content.setBackgroundColor(Color.TRANSPARENT);
            }
            styleActionButton(alertDialog.getButton(DialogInterface.BUTTON_POSITIVE), true);
            styleActionButton(alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE), false);
            styleActionButton(alertDialog.getButton(DialogInterface.BUTTON_NEUTRAL), false);
        }
    }

    public static void styleFramedDialog(Dialog dialog) {
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(borderedSurface(dialog.getContext(), RADIUS_SETTINGS_CARD, true));
            constrainDialogWindow(dialog, window);
        }

        if (dialog instanceof AlertDialog) {
            AlertDialog alertDialog = (AlertDialog) dialog;
            View content = alertDialog.findViewById(android.R.id.content);
            if (content != null) {
                content.setBackgroundColor(Color.TRANSPARENT);
            }
            styleActionButton(alertDialog.getButton(DialogInterface.BUTTON_POSITIVE), true);
            styleActionButton(alertDialog.getButton(DialogInterface.BUTTON_NEGATIVE), false);
            styleActionButton(alertDialog.getButton(DialogInterface.BUTTON_NEUTRAL), false);
        }
    }

    /** The dialog each settings row opened last, by the row's key, and the screen it opened on. */
    private static final java.util.Map<String, java.lang.ref.WeakReference<Dialog>> OPEN_DIALOGS =
            new java.util.HashMap<>();
    private static final java.util.Map<String, java.lang.ref.WeakReference<Context>> OPENED_ON =
            new java.util.HashMap<>();

    /**
     * Whether the dialog this row opened on this screen is still showing. A second tap can land
     * on the row before the first tap's dialog takes input, and it opened another one on top.
     * Public, because ReleaseNotesTest loads ReleaseNotes in a class loader of its own, which
     * package access doesn't reach.
     */
    public static boolean dialogOpen(String key, Context context) {
        java.lang.ref.WeakReference<Dialog> open = OPEN_DIALOGS.get(key);
        java.lang.ref.WeakReference<Context> on = OPENED_ON.get(key);
        Dialog dialog = open == null ? null : open.get();
        return dialog != null && dialog.isShowing() && on != null && on.get() == context;
    }

    /** Remembers the dialog a row opened on this screen, for {@link #dialogOpen}. */
    public static void dialogOpened(String key, Context context, Dialog dialog) {
        OPEN_DIALOGS.put(key, new java.lang.ref.WeakReference<>(dialog));
        OPENED_ON.put(key, new java.lang.ref.WeakReference<>(context));
    }

    public static void styleStandardAlertDialog(AlertDialog dialog) {
        styleFramedDialog(dialog);

        Window window = dialog.getWindow();
        if (window == null) {
            return;
        }

        ListView list = dialog.getListView();
        boolean radio = list != null && list.getChoiceMode() == ListView.CHOICE_MODE_SINGLE;
        makeDialogPanelsTransparent(window.getDecorView(), dialog.getContext());
        styleDialogText(window.getDecorView(), radio);

        if (list != null) {
            list.setBackgroundColor(Color.TRANSPARENT);
            styleScrollableList(list);
            list.setDivider(new ColorDrawable(divider()));
            list.setDividerHeight(Math.max(1, dp(dialog.getContext(), 1)));
            list.post(() -> {
                styleDialogText(list, radio);
                list.postDelayed(() -> styleDialogText(list, radio), 50);
            });
            // A row only reachable by scrolling does not exist yet when the pass above runs, so
            // it arrived with the platform's end-side check mark and TikTok's text colour. Eight
            // rows at about 48dp is taller than a small screen, and taller than any screen at 2x
            // font scale. onScroll runs on every layout and every scroll position change, which
            // is exactly when a row that was not there before appears.
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                @Override public void onScrollStateChanged(AbsListView view, int scrollState) {
                }

                @Override public void onScroll(
                        AbsListView view, int first, int visibleCount, int total) {
                    styleDialogText(view, radio);
                }
            });
        }

        styleActionButton(dialog.getButton(DialogInterface.BUTTON_POSITIVE), true);
        styleActionButton(dialog.getButton(DialogInterface.BUTTON_NEGATIVE), false);
        styleActionButton(dialog.getButton(DialogInterface.BUTTON_NEUTRAL), false);
    }

    public static int dialogListHeight(Context context, int preferredDp) {
        int preferred = dp(context, preferredDp);
        int screenBased = Math.round(context.getResources().getDisplayMetrics().heightPixels * 0.38f);
        return Math.min(preferred, Math.max(dp(context, 220), screenBased));
    }

    private static void constrainDialogWindow(Dialog dialog, Window window) {
        int screenWidth = dialog.getContext().getResources().getDisplayMetrics().widthPixels;
        int horizontalMargins = dp(dialog.getContext(), 32);
        int maxWidth = dp(dialog.getContext(), 560);
        int width = Math.min(maxWidth, Math.max(dp(dialog.getContext(), 280), screenWidth - horizontalMargins));
        window.setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT);
    }

    private static void makeDialogPanelsTransparent(View root, Context context) {
        String[] panelNames = {
                "parentPanel",
                "topPanel",
                "contentPanel",
                "buttonPanel",
                "customPanel"
        };
        for (String panelName : panelNames) {
            int id = context.getResources().getIdentifier(panelName, "id", "android");
            if (id == 0) {
                continue;
            }
            View panel = root.findViewById(id);
            if (panel != null) {
                panel.setBackgroundColor(Color.TRANSPARENT);
            }
        }
    }

    private static void styleDialogText(View view, boolean radio) {
        if (view instanceof CheckBox) {
            CheckBox checkBox = (CheckBox) view;
            checkBox.setTextColor(textPrimary());
            styleCheckBox(checkBox);
        } else if (view instanceof CheckedTextView) {
            CheckedTextView checkedTextView = (CheckedTextView) view;
            checkedTextView.setTextColor(textPrimary());
            // The platform row starts nearer the edge than the dialog's title, so the marks sat
            // out to the left of every heading above them. The title is inset by the theme's
            // dialog padding, and the mark is centred in its own box, so the row starts that
            // much before the padding to put the mark's edge under the title's first letter.
            // setPaddingRelative only relays out on a change, so the scroll callback that runs
            // this per frame costs nothing.
            Context rowContext = checkedTextView.getContext();
            int titleInset = dialogInset(rowContext);
            int markMargin = dp(rowContext,
                    (DialogCheckMarkDrawable.intrinsicSizeDp - DialogCheckMarkDrawable.boxSizeDp) / 2);
            checkedTextView.setPaddingRelative(Math.max(0, titleInset - markMargin),
                    checkedTextView.getPaddingTop(), titleInset, checkedTextView.getPaddingBottom());
            Drawable[] drawables = checkedTextView.getCompoundDrawablesRelative();
            // Runs on every scroll callback now, so it does its work once per row rather than
            // building a drawable per frame. A rebound row brings the platform check mark back,
            // which is what the first half of this test catches.
            if (checkedTextView.getCheckMarkDrawable() != null
                    || !(drawables[0] instanceof DialogCheckMarkDrawable)) {
                checkedTextView.setCheckMarkDrawable(null);
                // Given no bounds, the mark was drawn centred on the padding edge with half of it
                // out past the row's start, and the platform's 20dp gap put the text after that.
                // With its own box the mark takes its space, and 8dp keeps the text close to it.
                DialogCheckMarkDrawable mark =
                        new DialogCheckMarkDrawable(checkedTextView.getContext(), radio);
                mark.setBounds(0, 0, mark.getIntrinsicWidth(), mark.getIntrinsicHeight());
                checkedTextView.setCompoundDrawablesRelative(
                        mark, drawables[1], drawables[2], drawables[3]);
                checkedTextView.setCompoundDrawablePadding(dp(checkedTextView.getContext(), 8));
            }
        } else if (view instanceof Button) {
            ((Button) view).setTextColor(accent());
        } else if (view instanceof TextView) {
            ((TextView) view).setTextColor(textPrimary());
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                styleDialogText(group.getChildAt(i), radio);
            }
        }
    }

    /** The theme's dialog content inset, which an AlertDialog's title uses. 24dp when unset. */
    static int dialogInset(Context context) {
        android.util.TypedValue value = new android.util.TypedValue();
        if (context.getTheme().resolveAttribute(android.R.attr.dialogPreferredPadding, value, true)
                && value.type == android.util.TypedValue.TYPE_DIMENSION) {
            return android.util.TypedValue.complexToDimensionPixelSize(
                    value.data, context.getResources().getDisplayMetrics());
        }
        return dp(context, 24);
    }

    public static void styleActionButton(Button button, boolean primary) {
        if (button == null) {
            return;
        }
        button.setTextColor(enabledTextColors(primary ? accent() : textSecondary()));
        button.setAllCaps(false);
        button.setTypeface(button.getTypeface(), primary ? Typeface.BOLD : Typeface.NORMAL);
        button.setMinimumHeight(dp(button.getContext(), 48));
        button.setMinimumWidth(dp(button.getContext(), 48));
        button.setBackground(pressAndFocus(button.getContext(), RADIUS_SETTINGS_FIELD,
                new ColorDrawable(Color.TRANSPARENT)));
    }

    /**
     * A dialog's flat action, which is a TextView with a click listener.
     *
     * <p>TalkBack reads one of those as text, so every Save, Cancel and Apply in a hand built
     * dialog here was announced as a label rather than as something to press. The role is set
     * here so every consumer inherits it, and 48dp each way is Android's own guidance for
     * anything a finger has to land on.
     */
    public static void styleTextAction(TextView button, boolean primary) {
        button.setTextColor(enabledTextColors(primary ? accent() : textSecondary()));
        // From the typeface's plain face, not from whatever the button already wears.
        // setTypeface(tf, NORMAL) takes the else branch and keeps the face it is handed, so a
        // button built bold and then styled secondary stayed bold and went on reading as the
        // action to take. The two callers that do want a bold secondary say so afterwards.
        button.setTypeface(Typeface.create(button.getTypeface(), Typeface.NORMAL),
                primary ? Typeface.BOLD : Typeface.NORMAL);
        button.setMinimumHeight(dp(button.getContext(), 48));
        button.setMinimumWidth(dp(button.getContext(), 48));
        button.setGravity(android.view.Gravity.CENTER);
        // Colour, weight, size and the button role, and until now nothing at all to show a press
        // or say where the focus is. Add, Remove, Save, Cancel, Select every tab, the Lab's tabs
        // and its four selection actions all went through here and all of them were flat.
        button.setBackground(pressAndFocus(button.getContext(), RADIUS_CONTROL,
                new ColorDrawable(Color.TRANSPARENT)));
        button.setFocusable(true);
        markAsButton(button);
    }

    /**
     * A row of text actions that wraps onto another line rather than running off the edge.
     *
     * <p>A horizontal {@link android.widget.LinearLayout} hands each child what is left of the
     * width, so once the labels are wider than the row the later ones are measured at nothing
     * and squeezed to their minimum with their words cut. That is not a large-text problem
     * alone: four German labels at ordinary size already overflow a 360dp phone. Lines are laid
     * out towards the end of the row, which is where a row of actions belongs in either
     * direction.
     */
    public static ViewGroup actionRow(Context context) {
        return new ActionFlow(context);
    }

    /**
     * A bordered surface that answers a press and says when it holds focus.
     *
     * <p>For the controls that are a frame rather than a word: the Lab's search row, its filter
     * button and the detail page's value spinner. The border is the one the resting surface has
     * and the accent while the control has focus, so the shape does not move under the reader.
     */
    public static Drawable focusableSurface(Context context, int radiusDp, boolean lifted) {
        GradientDrawable surface = roundedSurface(context, radiusDp, lifted);
        surface.setStroke(Math.max(1, dp(context, 1)), new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused}, new int[0]},
                new int[]{accent(), border()}));
        return pressAndFocus(context, radiusDp, surface);
    }

    /**
     * The shared press and focus pair: a ripple over the content, and an accent wash while the
     * control holds focus. One drawable, so the two states cannot be given to one control and
     * forgotten on the next.
     */
    /** The same pair over a fill the caller built, for a control that repaints itself. */
    public static Drawable pressAndFocusOver(Context context, int radiusDp, Drawable content) {
        return pressAndFocus(context, radiusDp, content);
    }

    /**
     * A row in a hand-built list, with the press and focus the settings rows have.
     *
     * <p>The SIM preset list and the tab checklist painted each row an opaque colour, and a
     * ListView draws its selector underneath the item, so the one press highlight those rows
     * could have had was hidden by the fill covering it.
     *
     * @param fill the row's own colour, painted under the states.
     */
    public static Drawable listRow(Context context, @ColorInt int fill) {
        return pressAndFocus(context, 0, new ColorDrawable(fill));
    }

    private static Drawable pressAndFocus(Context context, int radiusDp, Drawable content) {
        GradientDrawable focus = new GradientDrawable();
        focus.setShape(GradientDrawable.RECTANGLE);
        focus.setCornerRadius(dp(context, radiusDp));
        // Selected as well as focused: a ListView never focuses its rows, it marks the one the
        // d-pad is on as selected and draws its own selector underneath, where an opaque row
        // fill covers it. The wash is the row's own answer to that.
        focus.setColor(new ColorStateList(
                new int[][]{new int[]{android.R.attr.state_focused},
                        new int[]{android.R.attr.state_selected}, new int[0]},
                new int[]{activatedFill(), activatedFill(), Color.TRANSPARENT}));

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setCornerRadius(dp(context, radiusDp));
        mask.setColor(Color.WHITE);
        return new RippleDrawable(ColorStateList.valueOf(activatedFill()),
                new LayerDrawable(new Drawable[]{content, focus}), mask);
    }

    /**
     * Gives a custom clickable view the platform button role, its action and its state.
     *
     * <p>The class name alone was not enough. A screen reader decides whether to offer "double
     * tap to activate" from the node's click action, and whether to say "dimmed" from its
     * enabled flag, and a hand built TextView hands over neither unless the view is actually
     * marked clickable. Every action built this way announced as a button that could not be
     * pressed, and a disabled one announced exactly like an enabled one.
     */
    public static void markAsButton(View button) {
        button.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(
                    View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(android.widget.Button.class.getName());
                info.setEnabled(host.isEnabled());
                if (host.isClickable()) {
                    info.setClickable(true);
                    if (host.isEnabled()) {
                        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CLICK);
                    }
                }
            }
        });
    }

    /**
     * Says a control is working, without fading it.
     *
     * <p>Alpha on the view multiplies its backdrop as well as its glyph, so a chip drawn over a
     * video went from a readable disabled control to a smudge over a bright frame. A caller that
     * wants a visible disabled state mutes its own glyph, which is the one thing on the control
     * that should lose contrast.
     */
    public static void setBusy(View control, boolean busy, String busyLabel) {
        control.setEnabled(!busy);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            control.setStateDescription(busy ? busyLabel : null);
        } else if (busy) {
            if (!DESCRIPTION_BEFORE_BUSY.containsKey(control)) {
                DESCRIPTION_BEFORE_BUSY.put(control, control.getContentDescription());
            }
            control.setContentDescription(busyLabel);
        } else if (DESCRIPTION_BEFORE_BUSY.containsKey(control)) {
            control.setContentDescription(DESCRIPTION_BEFORE_BUSY.remove(control));
        }
    }

    /**
     * What each busy control said before it went busy, on Android 10 and older, where there's no
     * state description to carry the busy word. A view tag can't hold it: a view only takes tag
     * keys from the app's own resource ids, and the framework id this once used threw on every
     * busy press there. A control with no description of its own is put back to none. Weak, so
     * a control that goes away while busy takes its entry with it; main thread only.
     */
    private static final java.util.Map<View, CharSequence> DESCRIPTION_BEFORE_BUSY = new java.util.WeakHashMap<>();

    /** What a dialog's Save has to satisfy before the dialog is allowed to close. */
    public interface DialogCheck {
        /** Null when the value is fine, otherwise what is wrong with it, in the reader's words. */
        String problem();

        /** Puts the reason where the reader is looking, under the field it is about. */
        void report(String problem);

        /**
         * Saves what the dialog holds. Runs only when {@link #problem()} answered null, and
         * answers false when something further down refused the value and has already said
         * why, which keeps the dialog open without a second message on top of the first.
         */
        boolean accept();
    }

    /**
     * Keeps a preference dialog open when Save is pressed on something it will not take.
     *
     * <p>A DialogPreference dismisses on the positive button before it is told what was typed, so
     * every one of these rejected a value by closing the dialog and then toasting the reason, and
     * the reader had to reopen the row and type it again. Replacing the button's own listener
     * after the dialog is showing is the only way in: the dialog closes when the value is
     * accepted and stays put, with the text still in the box, when it is not.
     */
    public static void keepOpenOnInvalidInput(Dialog dialog, DialogCheck check) {
        if (!(dialog instanceof AlertDialog)) return;
        Button save = ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE);
        if (save == null) return;
        save.setOnClickListener(view -> {
            String problem = check.problem();
            if (problem != null) {
                check.report(problem);
                return;
            }
            if (!check.accept()) return;
            dialog.dismiss();
        });
    }

    /**
     * Pressing the keyboard's Done key clicks the positive button so the dialog's own
     * validation runs, rather than just hiding the keyboard and waiting for a tap.
     */
    public static void submitOnDone(EditText field, Dialog dialog) {
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setOnEditorActionListener((view, actionId, event) -> {
            boolean done = actionId == EditorInfo.IME_ACTION_DONE
                    || (actionId == EditorInfo.IME_NULL && event != null
                        && event.getKeyCode() == android.view.KeyEvent.KEYCODE_ENTER
                        && event.getAction() == android.view.KeyEvent.ACTION_DOWN);
            if (!done) return false;
            if (!(dialog instanceof AlertDialog)) return false;
            Button save = ((AlertDialog) dialog).getButton(AlertDialog.BUTTON_POSITIVE);
            if (save != null) save.performClick();
            return true;
        });
    }

    // Keyed View tags require the application resource namespace. This private value is not a
    // real resource and never crosses a process boundary; the uncommon suffix keeps it clear of
    // TikTok's generated ids while satisfying View's namespace check.
    private static final int FIELD_ERROR_TAG = 0x7f0f4846;

    /**
     * Puts a refused value's reason directly under its field and moves focus back to the field.
     *
     * <p>The platform error popup inherits TikTok's theme, can cover the value it is explaining,
     * and disappears on its own schedule. This message is part of the form instead. Its assertive
     * live region speaks the failure once, and the first edit clears stale text without waiting
     * for another Save press.
     */
    public static void reportFieldError(EditText field, String problem) {
        if (field == null) return;
        TextView error = ensureFieldError(field);
        field.setError(null);
        if (error == null) {
            // A field not attached to a form cannot carry an inline sibling. Keep the refusal
            // visible rather than losing it, while every shipped dialog takes the inline path.
            field.setError(problem);
            field.announceForAccessibility(problem);
        } else {
            // Suppress the live-region event while changing the node, then send one explicit
            // announcement. Otherwise some Android builds say the same error once for the text
            // change and once for announceForAccessibility, while others say neither because the
            // view was GONE when the text changed.
            error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_NONE);
            error.setVisibility(View.VISIBLE);
            error.setText(problem == null ? "" : problem);
            error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
            if (!TextUtils.isEmpty(problem)) error.announceForAccessibility(problem);
        }
        field.requestFocus();
    }

    /** Clears both the inline form message and the platform fallback, if either is present. */
    public static void clearFieldError(EditText field) {
        if (field == null) return;
        field.setError(null);
        Object tagged = field.getTag(FIELD_ERROR_TAG);
        if (!(tagged instanceof TextView)) return;
        TextView error = (TextView) tagged;
        error.setVisibility(View.GONE);
        error.setText("");
    }

    private static TextView ensureFieldError(EditText field) {
        Object tagged = field.getTag(FIELD_ERROR_TAG);
        if (tagged instanceof TextView) return (TextView) tagged;
        if (!(field.getParent() instanceof ViewGroup)) return null;

        ViewGroup parent = (ViewGroup) field.getParent();
        int fieldIndex = parent.indexOfChild(field);
        if (fieldIndex < 0) return null;
        TextView error = text(field.getContext(), "", 13, error(), Typeface.NORMAL);
        error.setTag("hushfeed_field_error");
        error.setVisibility(View.GONE);
        error.setPaddingRelative(0, dp(field.getContext(), 4), 0, 0);
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE);
        error.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        ViewGroup.LayoutParams params;
        if (parent instanceof android.widget.LinearLayout) {
            params = new android.widget.LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        } else {
            params = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        parent.addView(error, fieldIndex + 1, params);
        field.setTag(FIELD_ERROR_TAG, error);
        field.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(
                    CharSequence value, int start, int count, int after) {
            }

            @Override public void onTextChanged(
                    CharSequence value, int start, int before, int count) {
            }

            @Override public void afterTextChanged(android.text.Editable value) {
                clearFieldError(field);
            }
        });
        return error;
    }

    /**
     * A text field, with an underline that says whether typing will land in it.
     *
     * <p>The tint had two states, disabled and everything else, so a field at rest wore the same
     * accent underline as the one holding the cursor. In the Min and Max dialog that means two
     * accent underlines and one caret to tell them apart, and the same on the search box and
     * every other editor on this screen. Focused keeps the accent; at rest the line drops to the
     * quieter colour, which is still a line, just not a claim to have the keyboard.
     *
     * <p>The quieter colour is {@link #border()} rather than {@link #textSecondary()}, measured
     * rather than chosen: against the surface the secondary text colour reads 8.0:1 in the dark
     * theme and 7.1:1 in the light one, where the accent reads 6.0:1 and 6.4:1, so a resting
     * field would have been the more prominent of the two, and the pair would differ by 1.33:1
     * and 1.10:1, which in the light theme is no difference at all for a reader who cannot
     * separate the hues. The border colour reads 1.5:1 against the surface and 3.9:1 against the
     * accent in both themes, which is the order this is for: the field with the cursor is the
     * loud one.
     */
    public static void styleEditText(EditText editText) {
        editText.setTextColor(enabledTextColors(textPrimary()));
        editText.setHintTextColor(enabledTextColors(textSecondary()));
        // The main Metra border is just under 3:1 against the pink focused underline.
        // A slightly quieter resting underline keeps focus distinct without changing the palette.
        int restingUnderline = isDarkMode() ? Color.rgb(52, 52, 60) : border();
        editText.setBackgroundTintList(new ColorStateList(
                new int[][]{
                        new int[]{-android.R.attr.state_enabled},
                        new int[]{android.R.attr.state_focused},
                        new int[]{}
                },
                new int[]{border(), accent(), restingUnderline}
        ));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            editText.setTextCursorDrawable(tinted(editText.getTextCursorDrawable(), accent()));
            editText.setTextSelectHandle(tinted(editText.getTextSelectHandle(), accent()));
            editText.setTextSelectHandleLeft(tinted(editText.getTextSelectHandleLeft(), accent()));
            editText.setTextSelectHandleRight(tinted(editText.getTextSelectHandleRight(), accent()));
        }
    }

    private static Drawable tinted(Drawable drawable, @ColorInt int color) {
        if (drawable == null) return null;
        Drawable result = drawable.mutate();
        result.setTint(color);
        return result;
    }

    /** Keeps list overscroll inside the same accent system as focus, selection and switches. */
    public static void styleScrollableList(AbsListView list) {
        if (list != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            list.setEdgeEffectColor(accent());
        }
    }

    /** A dropdown whose closed control, popup and touch target all belong to this settings UI. */
    public static void styleSpinner(android.widget.Spinner spinner) {
        if (spinner == null) return;
        Context context = spinner.getContext();
        spinner.setBackground(focusableSurface(context, RADIUS_CONTROL, false));
        spinner.setPopupBackgroundDrawable(borderedSurface(context, RADIUS_FIELD, true));
        spinner.setMinimumHeight(dp(context, 48));
        spinner.setFocusableInTouchMode(true);
        spinner.setFocusable(true);
    }

    /**
     * Connects a visible field label to its editor and makes the editor the one spoken stop.
     *
     * <p>A content description on an EditText replaces or competes with what was typed. The
     * node hint carries the stable field name instead, while the platform text, input type and
     * enabled state remain separate node properties. The label stays on screen but is removed
     * from accessibility traversal because the editor now says it itself.
     */
    public static void labelEditor(TextView label, EditText editor) {
        labelEditor(label, editor, label.getText());
    }

    /** Hint-only field with no visible label: just the spoken name for screen readers. */
    public static void labelEditor(EditText editor, CharSequence spokenName) {
        labelEditor(null, editor, spokenName);
    }

    /** Same contract with a more precise spoken name for generated fields. */
    public static void labelEditor(TextView label, EditText editor, CharSequence spokenName) {
        if (editor.getId() == View.NO_ID) editor.setId(View.generateViewId());
        if (label != null) {
            label.setLabelFor(editor.getId());
            label.setFocusable(false);
            label.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
        final CharSequence fieldName = spokenName == null ? "" : spokenName.toString();
        editor.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override public void onInitializeAccessibilityNodeInfo(
                    View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                EditText input = (EditText) host;
                // Keep these explicit. Dynamic fields can be disabled after construction, and
                // their current value and required keyboard must survive that state change.
                info.setText(input.getText());
                info.setEditable(true);
                info.setEnabled(input.isEnabled());
                info.setInputType(input.getInputType());
                if (Build.VERSION.SDK_INT >= 26) {
                    info.setHintText(fieldName);
                    info.setShowingHintText(input.length() == 0);
                }
            }
        });
    }

    public static void styleCheckBox(CompoundButton button) {
        int[][] states = new int[][]{
                new int[]{android.R.attr.state_checked},
                new int[]{-android.R.attr.state_enabled},
                new int[]{}
        };
        int[] colors = new int[]{accent(), textDisabled(), textSecondary()};
        button.setButtonTintList(new ColorStateList(states, colors));
    }

    public static void styleCheckBoxRow(CompoundButton button) {
        styleCheckBox(button);
        button.setTextColor(enabledTextColors(textPrimary()));
        button.setTextSize(TEXT_TITLE);
    }

    /** Lays its children out in rows, breaking to a new one when the next child will not fit. */
    private static final class ActionFlow extends ViewGroup {
        ActionFlow(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            int available = Math.max(0, MeasureSpec.getSize(widthSpec)
                    - getPaddingLeft() - getPaddingRight());
            int childWidthSpec = MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST);
            int childHeightSpec = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED);
            int lineWidth = 0;
            int lineHeight = 0;
            int widest = 0;
            int height = 0;
            for (int index = 0; index < getChildCount(); index++) {
                View child = getChildAt(index);
                if (child.getVisibility() == GONE) continue;
                measureChild(child, childWidthSpec, childHeightSpec);
                int width = child.getMeasuredWidth();
                if (lineWidth > 0 && lineWidth + width > available) {
                    widest = Math.max(widest, lineWidth);
                    height += lineHeight;
                    lineWidth = 0;
                    lineHeight = 0;
                }
                lineWidth += width;
                lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
            }
            widest = Math.max(widest, lineWidth);
            height += lineHeight;
            setMeasuredDimension(
                    resolveSize(widest + getPaddingLeft() + getPaddingRight(), widthSpec),
                    resolveSize(height + getPaddingTop() + getPaddingBottom(), heightSpec));
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            int available = Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight());
            boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            int y = getPaddingTop();
            int index = 0;
            while (index < getChildCount()) {
                // Gather the line first: where it starts depends on how wide it turns out.
                int lineWidth = 0;
                int lineHeight = 0;
                int taken = 0;
                for (int i = index; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (child.getVisibility() == GONE) {
                        taken++;
                        continue;
                    }
                    int width = child.getMeasuredWidth();
                    if (lineWidth > 0 && lineWidth + width > available) break;
                    lineWidth += width;
                    lineHeight = Math.max(lineHeight, child.getMeasuredHeight());
                    taken++;
                }
                if (taken == 0) break;
                int x = getPaddingLeft() + (rtl ? 0 : available - lineWidth);
                for (int i = index; i < index + taken; i++) {
                    View child = getChildAt(i);
                    if (child.getVisibility() == GONE) continue;
                    child.layout(x, y, x + child.getMeasuredWidth(),
                            y + child.getMeasuredHeight());
                    x += child.getMeasuredWidth();
                }
                y += lineHeight;
                index += taken;
            }
        }
    }

    private static final class DialogCheckMarkDrawable extends Drawable {
        /** The drawable's box and the mark centred in it, which row alignment measures against. */
        static final int intrinsicSizeDp = 32;
        static final int boxSizeDp = 18;

        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int intrinsicSize;
        private final float boxSize;
        private final float radius;
        private final boolean radio;
        /** Drawn checked whatever state it is handed, for a surface with no state to give it. */
        private final boolean pinned;
        private boolean checked;

        DialogCheckMarkDrawable(Context context, boolean radio) {
            this(context, radio, false);
        }

        DialogCheckMarkDrawable(Context context, boolean radio, boolean pinnedChecked) {
            intrinsicSize = dp(context, intrinsicSizeDp);
            boxSize = dp(context, boxSizeDp);
            radius = dp(context, RADIUS_BADGE);
            this.radio = radio;
            this.pinned = pinnedChecked;
            this.checked = pinnedChecked;
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(Math.max(2, dp(context, 2)));
            stroke.setStrokeCap(Paint.Cap.ROUND);
            stroke.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        public void draw(Canvas canvas) {
            float centerX = getBounds().exactCenterX();
            float centerY = getBounds().exactCenterY();
            if (radio) {
                float ringRadius = boxSize / 2f - stroke.getStrokeWidth() / 2f;
                stroke.setColor(checked ? accent() : textSecondary());
                canvas.drawCircle(centerX, centerY, ringRadius, stroke);
                if (checked) {
                    fill.setColor(accent());
                    canvas.drawCircle(centerX, centerY, boxSize / 4f, fill);
                }
                return;
            }

            float left = centerX - boxSize / 2f;
            float top = centerY - boxSize / 2f;
            RectF box = new RectF(left, top, left + boxSize, top + boxSize);

            if (checked) {
                fill.setColor(accent());
                canvas.drawRoundRect(box, radius, radius, fill);
                stroke.setColor(Color.WHITE);
                float unit = boxSize / 18f;
                canvas.drawLine(left + 4f * unit, top + 9f * unit,
                        left + 8f * unit, top + 13f * unit, stroke);
                canvas.drawLine(left + 8f * unit, top + 13f * unit,
                        left + 15f * unit, top + 5f * unit, stroke);
            } else {
                stroke.setColor(textSecondary());
                canvas.drawRoundRect(box, radius, radius, stroke);
            }
        }

        @Override
        protected boolean onStateChange(int[] stateSet) {
            if (pinned) return false;
            boolean nextChecked = false;
            for (int state : stateSet) {
                if (state == android.R.attr.state_checked) {
                    nextChecked = true;
                    break;
                }
            }
            if (checked == nextChecked) {
                return false;
            }
            checked = nextChecked;
            invalidateSelf();
            return true;
        }

        @Override
        public boolean isStateful() {
            return !pinned;
        }

        @Override
        public int getIntrinsicWidth() {
            return intrinsicSize;
        }

        @Override
        public int getIntrinsicHeight() {
            return intrinsicSize;
        }

        @Override
        public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            stroke.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            fill.setColorFilter(colorFilter);
            stroke.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
