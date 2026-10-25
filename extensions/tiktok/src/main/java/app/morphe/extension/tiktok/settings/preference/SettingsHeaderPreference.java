/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.settings.L10n;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.preference.Preference;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

@SuppressWarnings("deprecation")
public final class SettingsHeaderPreference extends Preference {
    /** The product name, which reads the same in every language and is not translated. */
    private static final String BRAND_MARK = "METRA PATCHES";

    private enum Kind {
        MASTER,
        SECTION,
        CAPTION
    }

    private final Kind kind;
    private final String heading;
    private final String detail;
    private final Runnable backAction;
    private Runnable statusAction;
    private TextView statusView;

    public static SettingsHeaderPreference master(Context context, Runnable backAction) {
        return master(context, backAction, null);
    }

    public static SettingsHeaderPreference master(Context context, Runnable backAction, Runnable statusAction) {
        SettingsHeaderPreference preference = new SettingsHeaderPreference(
                context,
                Kind.MASTER,
                "Settings",
                null,
                backAction
        );
        preference.statusAction = statusAction;
        return preference;
    }

    public static SettingsHeaderPreference section(Context context, String title, Runnable backAction) {
        return new SettingsHeaderPreference(context, Kind.SECTION, title, null, backAction);
    }

    public static SettingsHeaderPreference caption(Context context, String caption) {
        return new SettingsHeaderPreference(context, Kind.CAPTION, null, caption, null);
    }

    private SettingsHeaderPreference(
            Context context,
            Kind kind,
            String heading,
            String detail,
            Runnable backAction
    ) {
        super(context);
        this.kind = kind;
        this.heading = L10n.t(context, heading);
        this.detail = L10n.t(context, detail);
        this.backAction = backAction;
        setSelectable(false);
        setOrder(kind == Kind.CAPTION ? -900 : -1000);
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        if (kind == Kind.MASTER) {
            return createMasterHeader();
        }
        if (kind == Kind.CAPTION) {
            return createCaption();
        }
        return createSectionHeader();
    }

    private View createMasterHeader() {
        Context context = getContext();
        LinearLayout header = createHeader(context, heading, backAction, true);
        LinearLayout titleRow = header.findViewWithTag("metra_home_title_row");
        statusView = SettingsUi.text(context, "", SettingsUi.TEXT_CAPTION,
                SettingsUi.textSecondary(), 0);
        statusView.setTag("hushfeed_compact_status");
        statusView.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        statusView.setMinimumHeight(SettingsUi.dp(context, 48));
        statusView.setMinimumWidth(SettingsUi.dp(context, 48));
        statusView.setPaddingRelative(SettingsUi.dp(context, 8), 0, SettingsUi.dp(context, 8), 0);
        if (statusAction != null) {
            SettingsUi.markAsButton(statusView);
            statusView.setFocusable(true);
            statusView.setBackground(SettingsUi.pressAndFocusOver(context,
                    SettingsUi.RADIUS_CONTROL, new android.graphics.drawable.ColorDrawable(
                            SettingsUi.background())));
            statusView.setOnClickListener(view -> statusAction.run());
        }
        titleRow.addView(statusView, new LinearLayout.LayoutParams(-2, -2));
        TextView subtitle = SettingsUi.text(context, L10n.t(context, "MENUS"),
                12.5f, SettingsUi.textDisabled(), 1);
        subtitle.setTag("hushfeed_home_subtitle");
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(-1, -2);
        subtitleParams.setMarginStart(SettingsUi.dp(context, 14));
        subtitleParams.topMargin = SettingsUi.dp(context, 18);
        header.addView(subtitle, subtitleParams);
        refreshStatus();
        return header;
    }

    /** Keeps the compact state truthful after returning from a page with restart-gated settings. */
    public void refreshStatus() {
        if (statusView == null) return;
        boolean pending = !AbstractPreferenceFragment.restartPending.isEmpty();
        boolean paused = HushfeedPause.isPaused();
        String label = L10n.t(getContext(), pending ? "Restart pending" : paused ? "Paused" : "Active");
        statusView.setText(label);
        statusView.setTextColor(pending ? SettingsUi.accent() : SettingsUi.textSecondary());
        android.graphics.drawable.GradientDrawable dot = new android.graphics.drawable.GradientDrawable();
        dot.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        dot.setColor(paused && !pending ? SettingsUi.textSecondary() : SettingsUi.accent());
        int dotSize = SettingsUi.dp(getContext(), 6);
        dot.setBounds(0, 0, dotSize, dotSize);
        statusView.setCompoundDrawablesRelative(dot, null, null, null);
        statusView.setCompoundDrawablePadding(SettingsUi.dp(getContext(), 6));
        String description = pending ? label
                : L10n.t(getContext(), paused ? "Metra is paused" : "Metra is active");
        // The press scrolls this page down to the status card, so there's no other page to name.
        statusView.setContentDescription(description);
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        if (kind == Kind.MASTER) {
            statusView = view.findViewWithTag("hushfeed_compact_status");
            refreshStatus();
        }
    }

    private View createSectionHeader() { return createHeader(getContext(), heading, backAction); }

    /**
     * Metra's section toolbar. The arrow retains a 48 dp touch target and visible keyboard
     * focus, while its resting appearance is the original bare back arrow.
     */
    public static LinearLayout createHeader(Context context, String title, Runnable onBack) {
        return createHeader(context, title, onBack, false);
    }

    private static LinearLayout createHeader(Context context, String title, Runnable onBack, boolean compact) {
        LinearLayout header = new LinearLayout(context);
        header.setTag("hushfeed_page_header");
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPaddingRelative(SettingsUi.dp(context, 8), SettingsUi.dp(context, compact ? 18 : 0),
                SettingsUi.dp(context, compact ? 22 : 16), SettingsUi.dp(context, compact ? 10 : 0));
        header.setBackgroundColor(SettingsUi.background());
        LinearLayout toolbar = new LinearLayout(context);
        toolbar.setTag("hushfeed_toolbar");
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setMinimumHeight(SettingsUi.dp(context, 56));
        ImageView back = new ImageView(context);
        back.setContentDescription(L10n.t(context, "Back"));
        // An ImageView with a click listener is read as an image; this says it is a button.
        SettingsUi.markAsButton(back);
        back.setImageDrawable(new BackDrawable(context));
        back.setOnClickListener(view -> { if (onBack != null) onBack.run(); });
        back.setFocusable(true);
        android.graphics.drawable.LayerDrawable backSurface = new android.graphics.drawable.LayerDrawable(
                new Drawable[]{new android.graphics.drawable.ColorDrawable(SettingsUi.background()),
                        SettingsUi.focusRing(context, SettingsUi.RADIUS_CONTROL)});
        back.setBackground(SettingsUi.pressAndFocusOver(context, SettingsUi.RADIUS_CONTROL, backSurface));
        toolbar.addView(back, new LinearLayout.LayoutParams(SettingsUi.dp(context, 48), SettingsUi.dp(context, 48)));
        TextView heading = SettingsUi.text(context, title,
                compact ? headingSizeSp(context, title) : 17, SettingsUi.textPrimary(), 1);
        heading.setTag("hushfeed_page_title");
        heading.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        SettingsUi.markHeading(heading);
        if (compact) {
            TextView brand = SettingsUi.text(context, BRAND_MARK, 12.5f, SettingsUi.accent(), 1);
            LinearLayout.LayoutParams brandParams = new LinearLayout.LayoutParams(0, -2, 1);
            toolbar.addView(brand, brandParams);
        } else {
            toolbar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        }
        View support = SupportUi.createPill(context);
        boolean stackSupport = context.getResources().getConfiguration().fontScale >= 1.3f
                || context.getResources().getConfiguration().screenWidthDp < 360;
        if (!stackSupport) toolbar.addView(support, new LinearLayout.LayoutParams(-2, -2));
        header.addView(toolbar, new LinearLayout.LayoutParams(-1, -2));
        if (stackSupport) {
            LinearLayout.LayoutParams supportParams = new LinearLayout.LayoutParams(-2, -2);
            supportParams.gravity = Gravity.END;
            header.addView(support, supportParams);
        }
        if (compact) {
            LinearLayout titleRow = new LinearLayout(context);
            titleRow.setTag("metra_home_title_row");
            titleRow.setGravity(Gravity.CENTER_VERTICAL);
            titleRow.setOrientation(context.getResources().getConfiguration().fontScale >= 1.3f
                    ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams titleParams = titleRow.getOrientation() == LinearLayout.VERTICAL
                    ? new LinearLayout.LayoutParams(-1, -2)
                    : new LinearLayout.LayoutParams(0, -2, 1);
            titleRow.addView(heading, titleParams);
            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(-1, -2);
            rowParams.setMarginStart(SettingsUi.dp(context, 14));
            rowParams.topMargin = SettingsUi.dp(context, 2);
            header.addView(titleRow, rowParams);
        }
        return header;
    }

    private View createCaption() {
        Context context = getContext();
        TextView caption = SettingsUi.text(context, detail, 13.5f, SettingsUi.textSecondary(), 0);
        caption.setPadding(SettingsUi.dp(context, 22), SettingsUi.dp(context, 12),
                SettingsUi.dp(context, 22), SettingsUi.dp(context, 8));
        caption.setBackgroundColor(SettingsUi.background());
        return caption;
    }

    /**
     * How large the page title may be at the reader's text scale.
     *
     * <p>Metra's 32sp home title grows through the first large-text preset, then adapts to keep
     * the page below it within reach. The 20sp floor keeps it readable at larger text scales.
     */
    public static int headingSizeSp(Context context) {
        float scale = context.getResources().getConfiguration().fontScale;
        if (scale <= 1.3f) return 32;
        return Math.max(20, Math.round(32 * 1.3f / scale));
    }

    /**
     * Keeps the longest word whole when a translated title meets large text on a narrow phone.
     * Android otherwise breaks an unhyphenated word at an arbitrary character, which made the
     * German "wiederherstellen" end with a two-line fragment even though a modestly smaller
     * display heading could keep the word intact. Body text and the system font scale are left
     * untouched; only this oversized display title adapts, with 20sp as its floor.
     */
    static int headingSizeSp(Context context, String title) {
        int preferred = headingSizeSp(context);
        if (title == null || title.trim().isEmpty()) return preferred;

        android.content.res.Resources resources = context.getResources();
        android.util.DisplayMetrics metrics = resources.getDisplayMetrics();
        int screenWidthDp = resources.getConfiguration().screenWidthDp;
        float windowWidth = screenWidthDp > 0
                ? screenWidthDp * metrics.density
                : metrics.widthPixels;
        // The list contributes a 16dp gutter and this header contributes another 8dp per side.
        float available = Math.max(SettingsUi.dp(context, 160),
                windowWidth - SettingsUi.dp(context, 48));

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, preferred, metrics));
        float longest = 0;
        for (String word : title.trim().split("\\s+")) {
            longest = Math.max(longest, paint.measureText(word));
        }
        if (longest <= available || longest <= 0) return preferred;
        int fitted = (int) Math.floor(preferred * available / longest);
        return Math.max(20, Math.min(preferred, fitted));
    }

    public static final class BackDrawable extends Drawable {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public BackDrawable(Context context) {
            paint.setColor(SettingsUi.textPrimary());
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(SettingsUi.strokePx(context, 2.1f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        /**
         * The header row mirrors, by margin and gravity, and the glyph inside it did not, so an
         * Arabic or Hebrew reader got a left pointing back arrow parked at the right edge.
         */
        @Override
        public boolean isAutoMirrored() {
            return true;
        }

        @Override
        public void draw(Canvas canvas) {
            float centerX = getBounds().exactCenterX();
            float centerY = getBounds().exactCenterY();
            float size = Math.min(getBounds().width(), getBounds().height()) * 0.18f;
            float offset = getLayoutDirection() == View.LAYOUT_DIRECTION_RTL ? -size : size;
            float pointX = centerX - offset;
            canvas.drawLine(pointX, centerY, centerX + offset, centerY, paint);
            canvas.drawLine(pointX, centerY, centerX - offset * 0.1f, centerY - size * 0.9f, paint);
            canvas.drawLine(pointX, centerY, centerX - offset * 0.1f, centerY + size * 0.9f, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
