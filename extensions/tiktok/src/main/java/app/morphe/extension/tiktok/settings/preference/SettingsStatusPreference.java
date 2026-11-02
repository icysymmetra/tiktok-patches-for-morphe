/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.tiktok.settings.L10n;

/** A compact, readable proof that the injected settings runtime is alive. */
@SuppressWarnings("deprecation")
public final class SettingsStatusPreference extends Preference {
    public static final String KEY = "hushfeed_status";
    public static final String ROW_TAG = "hushfeed_status_card";
    public static final String ACTION_TAG = "hushfeed_status_diagnostics";
    public static final String TURN_BACK_ON_TAG = "hushfeed_status_turn_back_on";
    private static final String TITLE_TAG = "hushfeed_status_title";
    private static final String SUMMARY_TAG = "hushfeed_status_summary";

    private final Runnable diagnosticsAction;
    private Runnable turnBackOnAction;
    /** Paused now, but nothing pauses the next start: the way back has been taken. */
    private boolean backOnAtRestart;

    public SettingsStatusPreference(Context context, Runnable diagnosticsAction) {
        this(context, Utils.getPatchesReleaseVersion(), Utils.getAppVersionName(), diagnosticsAction);
    }

    SettingsStatusPreference(
            Context context,
            String bundleVersion,
            String appVersion,
            Runnable diagnosticsAction
    ) {
        super(context);
        this.diagnosticsAction = diagnosticsAction;
        setKey(KEY);
        setPersistent(false);
        setSelectable(false);
        // No fixed order: the home page lists the card where it adds it, under the Hushfeed
        // heading, while the restart row keeps its -950 just under the page header.
        if (HushfeedPause.isPaused()) {
            setTitle(L10n.t(context, "Metra is paused"));
            showPause(context);
        } else {
            setTitle(L10n.t(context, "Metra is active"));
            setSummary(MorpheTikTokAboutPreference.versionSummary(
                    context, bundleVersion, appVersion));
        }
    }

    /** What the card offers while Hushfeed is paused, in place of Diagnostics. */
    public void setTurnBackOnAction(Runnable action) {
        turnBackOnAction = action;
    }

    /**
     * Reads the pause again, after the way back was taken or the Pause switch was flipped. The
     * two summaries differ, so setSummary rebinds the card and the way back hides or returns.
     */
    public void refresh() {
        if (!HushfeedPause.isPaused()) return;
        showPause(getContext());
    }

    private void showPause(Context context) {
        backOnAtRestart = !HushfeedPause.pausesNextStart(context);
        setSummary(backOnAtRestart
                ? L10n.t(context, "Metra turns back on when TikTok restarts.")
                : pausedSummary(context, HushfeedPause.reason()));
    }

    /** Why this start runs paused, and that nothing the reader saved has changed. */
    static String pausedSummary(Context context, HushfeedPause.Reason reason) {
        switch (reason) {
            case CRASH_LOOP:
                return L10n.t(context, "TikTok closed three times within a minute of starting, so Metra paused itself. Your settings stay as they are.");
            case MARKER_FILE:
                return L10n.t(context, "A file named hushfeed-safe-mode in TikTok's folder under Android/data paused Metra. Your settings stay as they are.");
            default:
                return L10n.t(context, "Metra's switches are paused. Your settings stay saved. Changes made when you patched, like the app's name or icon, stay.");
        }
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        // The paused card carries a sentence and a longer action, so the action goes underneath.
        boolean stack = context.getResources().getConfiguration().fontScale > 1.3f
                || context.getResources().getConfiguration().screenWidthDp < 360
                || HushfeedPause.isPaused();
        LinearLayout card = new LinearLayout(context);
        card.setTag(ROW_TAG);
        card.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        card.setGravity(stack ? Gravity.NO_GRAVITY : Gravity.CENTER_VERTICAL);
        card.setMinimumHeight(SettingsUi.dp(context, 76));
        card.setPadding(
                SettingsUi.dp(context, 16),
                SettingsUi.dp(context, 12),
                SettingsUi.dp(context, 12),
                SettingsUi.dp(context, 12));
        card.setBackgroundColor(SettingsUi.background());

        LinearLayout information = new LinearLayout(context);
        information.setOrientation(LinearLayout.HORIZONTAL);
        information.setGravity(Gravity.CENTER_VERTICAL);

        ImageView mark = new ImageView(context);
        mark.setImageDrawable(HushfeedPause.isPaused() ? new PausedDrawable(context) : new ActiveDrawable(context));
        mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        information.addView(mark, new LinearLayout.LayoutParams(
                SettingsUi.dp(context, 44), SettingsUi.dp(context, 44)));

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.setGravity(Gravity.CENTER_VERTICAL);
        // Grouped so a screen reader says the title and status together. Keyboard focus is
        // another matter: the group does nothing when chosen and drew nothing when focused, so
        // from API 28 it is a screen reader stop only.
        if (android.os.Build.VERSION.SDK_INT >= 28) {
            labels.setScreenReaderFocusable(true);
        } else {
            labels.setFocusable(true);
        }

        TextView title = SettingsUi.text(
                context, String.valueOf(getTitle()), SettingsUi.TEXT_TITLE, SettingsUi.textPrimary(),
                android.graphics.Typeface.NORMAL);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        title.setTag(TITLE_TAG);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        labels.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView summary = SettingsUi.text(
                context, String.valueOf(getSummary()), SettingsUi.TEXT_BODY_SMALL, SettingsUi.textSecondary(), 0);
        summary.setTag(SUMMARY_TAG);
        summary.setSingleLine(false);
        summary.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        labels.addView(summary, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(0, -2, 1);
        labelParams.setMarginStart(SettingsUi.dp(context, 12));
        labelParams.setMarginEnd(SettingsUi.dp(context, 8));
        information.addView(labels, labelParams);

        LinearLayout.LayoutParams informationParams = stack
                ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -2, 1);
        card.addView(information, informationParams);

        // Paused, the one thing the card offers is the way back; Diagnostics is on the home page.
        boolean offerTurnBackOn = HushfeedPause.isPaused() && turnBackOnAction != null;
        Runnable cardAction = offerTurnBackOn ? turnBackOnAction : diagnosticsAction;
        if (cardAction != null) {
            String label = L10n.t(context, offerTurnBackOn ? "Turn Metra back on" : "Diagnostics");
            TextView action = SettingsUi.text(
                    context, label, SettingsUi.TEXT_BODY_SMALL, SettingsUi.accent(),
                    android.graphics.Typeface.BOLD);
            action.setTag(offerTurnBackOn ? TURN_BACK_ON_TAG : ACTION_TAG);
            action.setContentDescription(label);
            action.setPadding(SettingsUi.dp(context, 10), 0, SettingsUi.dp(context, 10), 0);
            SettingsUi.styleTextAction(action, true);
            GradientDrawable actionBackground = SettingsUi.borderedSurface(
                    context, SettingsUi.RADIUS_SETTINGS_FIELD, false);
            if (!HushfeedPause.isPaused() && SettingsUi.isDarkMode()) {
                actionBackground.setColor(SettingsUi.DARK_STATUS_SURFACE);
                actionBackground.setStroke(SettingsUi.dp(context, 1), SettingsUi.accent());
            }
            action.setBackground(SettingsUi.pressAndFocusOver(
                    context, SettingsUi.RADIUS_SETTINGS_FIELD, actionBackground));
            action.setOnClickListener(view -> cardAction.run());
            LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(
                    stack ? -1 : -2,
                    SettingsUi.dp(context, 48));
            if (stack) actionParams.topMargin = SettingsUi.dp(context, 8);
            card.addView(action, actionParams);
        }

        updateLabels(card);

        FrameLayout holder = new FrameLayout(context);
        holder.setBackgroundColor(SettingsUi.background());
        holder.setPadding(0, SettingsUi.dp(context, 4), 0, SettingsUi.dp(context, 8));
        holder.addView(card, new FrameLayout.LayoutParams(-1, -2));
        return holder;
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        updateLabels(view);
    }

    private void updateLabels(View root) {
        // Taken once, the way back has nothing left to do; the restart row above applies it.
        View back = root.findViewWithTag(TURN_BACK_ON_TAG);
        if (back != null) back.setVisibility(backOnAtRestart ? View.GONE : View.VISIBLE);
        TextView title = root.findViewWithTag(TITLE_TAG);
        TextView summary = root.findViewWithTag(SUMMARY_TAG);
        if (title != null) title.setText(getTitle());
        if (summary != null) {
            CharSequence detail = getSummary();
            summary.setText(detail);
            summary.setVisibility(detail == null || detail.length() == 0 ? View.GONE : View.VISIBLE);
        }
        View labels = title == null ? null : (View) title.getParent();
        if (labels != null) {
            CharSequence detail = getSummary();
            labels.setContentDescription(detail == null || detail.length() == 0
                    ? getTitle()
                    : String.valueOf(getTitle()) + '.' + ' ' + detail);
        }
    }

    /** The same tile with two bars: Hushfeed is paused. */
    private static final class PausedDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bar = new Paint(Paint.ANTI_ALIAS_FLAG);

        /** The status mark keeps tighter corners inside the larger card. */
        private final float corner;

        PausedDrawable(Context context) {
            corner = SettingsUi.dp(context, SettingsUi.RADIUS_CARD);
            fill.setColor(SettingsUi.badgeFill());
            fill.setStyle(Paint.Style.FILL);
            bar.setColor(SettingsUi.badgeText());
            bar.setStyle(Paint.Style.STROKE);
            bar.setStrokeWidth(SettingsUi.strokePx(context, 3f));
            bar.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            float cx = getBounds().exactCenterX();
            float cy = getBounds().exactCenterY();
            float radius = Math.min(getBounds().width(), getBounds().height()) * 0.38f;
            canvas.drawRoundRect(new RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                    corner, corner, fill);
            float half = radius * 0.4f;
            canvas.drawLine(cx - radius * 0.22f, cy - half, cx - radius * 0.22f, cy + half, bar);
            canvas.drawLine(cx + radius * 0.22f, cy - half, cx + radius * 0.22f, cy + half, bar);
        }

        @Override public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            bar.setAlpha(alpha);
        }

        @Override public void setColorFilter(ColorFilter filter) {
            fill.setColorFilter(filter);
            bar.setColorFilter(filter);
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /** Accent status tile with a high-contrast check. */
    private static final class ActiveDrawable extends Drawable {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint check = new Paint(Paint.ANTI_ALIAS_FLAG);

        private final float corner;

        ActiveDrawable(Context context) {
            corner = SettingsUi.dp(context, SettingsUi.RADIUS_CARD);
            fill.setColor(SettingsUi.badgeFill());
            fill.setStyle(Paint.Style.FILL);
            check.setColor(SettingsUi.badgeText());
            check.setStyle(Paint.Style.STROKE);
            check.setStrokeWidth(SettingsUi.strokePx(context, 2.7f));
            check.setStrokeCap(Paint.Cap.ROUND);
            check.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override public void draw(Canvas canvas) {
            float cx = getBounds().exactCenterX();
            float cy = getBounds().exactCenterY();
            float radius = Math.min(getBounds().width(), getBounds().height()) * 0.38f;
            canvas.drawRoundRect(new RectF(cx - radius, cy - radius, cx + radius, cy + radius),
                    corner, corner, fill);
            canvas.drawLine(cx - radius * 0.48f, cy,
                    cx - radius * 0.12f, cy + radius * 0.34f, check);
            canvas.drawLine(cx - radius * 0.12f, cy + radius * 0.34f,
                    cx + radius * 0.52f, cy - radius * 0.38f, check);
        }

        @Override public void setAlpha(int alpha) {
            fill.setAlpha(alpha);
            check.setAlpha(alpha);
        }

        @Override public void setColorFilter(ColorFilter filter) {
            fill.setColorFilter(filter);
            check.setColorFilter(filter);
        }

        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }
}
