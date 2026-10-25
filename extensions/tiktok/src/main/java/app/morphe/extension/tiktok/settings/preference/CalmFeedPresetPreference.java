/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.preference.ImmediateAction;
import app.morphe.extension.tiktok.settings.CalmFeedPreset;
import app.morphe.extension.tiktok.settings.L10n;

/** A one-tap feed cleanup with an exact, persistent route back. */
@SuppressWarnings("deprecation")
public final class CalmFeedPresetPreference extends Preference implements ImmediateAction {
    public static final String KEY = "calm_feed_preset";
    public static final String ROW_TAG = "calm_feed_preset_card";
    public static final String ACTION_TAG = "calm_feed_preset_action";
    public static final String STATUS_TAG = "calm_feed_preset_status";
    public static final String HEADING_TAG = "calm_feed_preset_heading";
    private static final String TITLE_TAG = "calm_feed_preset_title";
    private static final String SUMMARY_TAG = "calm_feed_preset_summary";

    private boolean busy;
    private boolean restoring;

    public CalmFeedPresetPreference(Context context) {
        super(context);
        setKey(KEY);
        setPersistent(false);
        setSelectable(false);
        setTitle(L10n.t(context, "Calm feed"));
        refreshSummary();
    }

    @Override public boolean actsOnTap() {
        return true;
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setTag(ROW_TAG);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(SettingsUi.dp(context, 16), SettingsUi.dp(context, 15),
                SettingsUi.dp(context, 16), SettingsUi.dp(context, 14));
        card.setBackground(SettingsUi.borderedSurface(context, SettingsUi.RADIUS_SETTINGS_CARD, true));

        boolean stack = context.getResources().getConfiguration().fontScale > 1.3f
                || context.getResources().getConfiguration().screenWidthDp < 360;
        LinearLayout heading = new LinearLayout(context);
        heading.setTag(HEADING_TAG);
        heading.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        heading.setGravity(stack ? Gravity.NO_GRAVITY : Gravity.CENTER_VERTICAL);

        TextView title = SettingsUi.text(context, String.valueOf(getTitle()), SettingsUi.TEXT_HEADLINE_SMALL,
                SettingsUi.textPrimary(), Typeface.BOLD);
        title.setTag(TITLE_TAG);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(title, stack ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -2, 1));

        TextView status = SettingsUi.text(context, "", SettingsUi.TEXT_CAPTION, SettingsUi.badgeText(), Typeface.BOLD);
        status.setTag(STATUS_TAG);
        status.setGravity(Gravity.CENTER);
        status.setPadding(SettingsUi.dp(context, 10), SettingsUi.dp(context, 5),
                SettingsUi.dp(context, 10), SettingsUi.dp(context, 5));
        GradientDrawable statusBackground = new GradientDrawable();
        statusBackground.setColor(SettingsUi.badgeFill());
        statusBackground.setCornerRadius(SettingsUi.dp(context, SettingsUi.RADIUS_PILL));
        status.setBackground(statusBackground);
        status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-2, -2);
        if (stack) statusParams.topMargin = SettingsUi.dp(context, 8);
        else statusParams.setMarginStart(SettingsUi.dp(context, 12));
        heading.addView(status, statusParams);
        card.addView(heading, new LinearLayout.LayoutParams(-1, -2));

        TextView summary = SettingsUi.text(context, String.valueOf(getSummary()), SettingsUi.TEXT_BODY_SMALL,
                SettingsUi.textSecondary(), Typeface.NORMAL);
        summary.setTag(SUMMARY_TAG);
        summary.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
        summaryParams.topMargin = SettingsUi.dp(context, 8);
        card.addView(summary, summaryParams);

        TextView action = SettingsUi.text(context, "", SettingsUi.TEXT_BODY_SMALL, SettingsUi.accent(), Typeface.BOLD);
        action.setTag(ACTION_TAG);
        action.setGravity(Gravity.CENTER);
        action.setMinimumWidth(SettingsUi.dp(context, 48));
        action.setMinimumHeight(SettingsUi.dp(context, 48));
        action.setPadding(SettingsUi.dp(context, 14), 0, SettingsUi.dp(context, 14), 0);
        action.setFocusable(true);
        action.setClickable(true);
        SettingsUi.markAsButton(action);
        action.setBackground(SettingsUi.pressAndFocusOver(context, SettingsUi.RADIUS_SETTINGS_FIELD,
                SettingsUi.borderedSurface(context, SettingsUi.RADIUS_SETTINGS_FIELD, false)));
        action.setOnClickListener(view -> runAction());
        LinearLayout.LayoutParams actionParams = new LinearLayout.LayoutParams(-1, -2);
        actionParams.topMargin = SettingsUi.dp(context, 12);
        card.addView(action, actionParams);

        update(card);

        FrameLayout holder = new FrameLayout(context);
        holder.setBackgroundColor(SettingsUi.background());
        holder.setPadding(0, SettingsUi.dp(context, 4), 0, SettingsUi.dp(context, 8));
        holder.addView(card, new FrameLayout.LayoutParams(-1, -2));
        return holder;
    }

    @Override protected void onBindView(View view) {
        super.onBindView(view);
        refreshSummary();
        update(view);
    }

    private void refreshSummary() {
        Context context = getContext();
        CalmFeedPreset.State state = CalmFeedPreset.state(context);
        String summary;
        switch (state) {
            case ACTIVE:
                summary = "On. Restore puts back the feed setup you had before.";
                break;
            case ADJUSTED:
                summary = "Some Calm feed settings changed. Restore still puts back your earlier setup.";
                break;
            case ALREADY_ACTIVE:
                summary = "Your current settings already match Calm feed.";
                break;
            default:
                summary = "Hide ads, Shop posts, LIVE videos and replays, promotional music, and paid partnerships.";
                break;
        }
        setSummary(L10n.t(context, summary));
    }

    private void update(View root) {
        Context context = getContext();
        CalmFeedPreset.State state = CalmFeedPreset.state(context);
        TextView title = root.findViewWithTag(TITLE_TAG);
        TextView summary = root.findViewWithTag(SUMMARY_TAG);
        TextView status = root.findViewWithTag(STATUS_TAG);
        TextView action = root.findViewWithTag(ACTION_TAG);
        if (title != null) title.setText(getTitle());
        if (summary != null) summary.setText(getSummary());
        if (status != null) status.setText(L10n.t(context, busy ? "Working"
                : state == CalmFeedPreset.State.AVAILABLE ? "Preset"
                : state == CalmFeedPreset.State.ADJUSTED ? "Changed" : "On"));
        if (action != null) {
            String label = busy
                    ? restoring ? "Restoring setup" : "Applying Calm feed"
                    : state == CalmFeedPreset.State.ACTIVE || state == CalmFeedPreset.State.ADJUSTED
                    ? "Restore setup"
                    : state == CalmFeedPreset.State.ALREADY_ACTIVE ? "Already active"
                    : "Use Calm feed";
            action.setText(L10n.t(context, label));
            boolean enabled = !busy && state != CalmFeedPreset.State.ALREADY_ACTIVE;
            action.setEnabled(enabled);
            action.setClickable(enabled);
            action.setContentDescription(L10n.t(context, label));
        }
        View card = root.findViewWithTag(ROW_TAG);
        if (card != null) {
            card.setContentDescription(String.valueOf(getTitle()) + '.' + ' ' + getSummary());
        }
    }

    private void runAction() {
        if (busy) return;
        CalmFeedPreset.State state = CalmFeedPreset.state(getContext());
        if (state == CalmFeedPreset.State.ALREADY_ACTIVE) return;
        restoring = state == CalmFeedPreset.State.ACTIVE || state == CalmFeedPreset.State.ADJUSTED;
        busy = true;
        notifyChanged();
        AbstractPreferenceFragment.settingImportInProgress = true;
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            try {
                CalmFeedPreset.Result result = restoring
                        ? CalmFeedPreset.restore(getContext())
                        : CalmFeedPreset.apply(getContext());
                Utils.runOnMainThread(() -> finish(result, null));
            } catch (Exception error) {
                Utils.runOnMainThread(() -> finish(null, error));
            }
        });
        if (!accepted) {
            AbstractPreferenceFragment.settingImportInProgress = false;
            busy = false;
            notifyChanged();
            SettingsActionBanner.showNotice(getContext(), L10n.t(getContext(),
                    "Calm feed couldn't start. Try again in a moment."));
        }
    }

    private void finish(CalmFeedPreset.Result result, Exception error) {
        AbstractPreferenceFragment.settingImportInProgress = false;
        busy = false;
        if (result != null) {
            TikTokPreferenceFragment.onSettingsBatchChanged(result.previousValues);
            String message;
            if (restoring) {
                message = result.restartChangedCount == 0
                        ? "Your previous feed setup is back."
                        : "Your previous feed setup is back. Restart TikTok to apply all changes.";
            } else {
                message = result.restartChangedCount == 0
                        ? "Calm feed is on."
                        : "Calm feed is on. Restart TikTok to apply all changes.";
            }
            // In the settings window rather than a toast, with the restart one tap away when
            // anything changed needs it.
            if (result.restartChangedCount == 0) SettingsActionBanner.showNotice(getContext(), L10n.t(getContext(), message));
            else SettingsActionBanner.showRestart(getContext(), L10n.t(getContext(), message));
        } else {
            Logger.printInfo(() -> "Calm feed preset operation failed", error);
            SettingsActionBanner.showNotice(getContext(), L10n.t(getContext(), restoring
                    ? "Your previous feed setup couldn't be restored. Nothing was changed."
                    : "Calm feed couldn't be applied. Nothing was changed."));
        }
        refreshSummary();
        notifyChanged();
    }
}
