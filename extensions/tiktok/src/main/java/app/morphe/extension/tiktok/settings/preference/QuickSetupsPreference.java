/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.graphics.Typeface;
import android.preference.Preference;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.QuickSetups;

import java.util.Collection;

/**
 * Quick setups, under the search field on the home page: Quiet, Privacy and Back to defaults,
 * one tap each, with Undo in the banner and the restart row raised when a changed switch needs
 * it. The switches each one touches are listed in {@link QuickSetups}.
 */
@SuppressWarnings("deprecation")
public final class QuickSetupsPreference extends Preference {
    public static final String KEY = "quick_setups";
    public static final String ROW_TAG = "quick_setups_card";
    static final String QUIET_TAG = "quick_setups_quiet";
    static final String PRIVACY_TAG = "quick_setups_privacy";
    static final String DEFAULTS_TAG = "quick_setups_defaults";
    static final String BUTTONS_TAG = "quick_setups_buttons";

    /** The settings on Hushfeed's pages, read on the main thread just before a choice applies. */
    public interface Pages {
        Collection<Setting<?>> settings();
    }

    private final Pages pages;
    /** What a long press on the card does: hides it. Null leaves the long press alone. */
    private final Runnable hide;
    private QuickSetups.Choice running;

    public QuickSetupsPreference(Context context, Pages pages) {
        this(context, pages, null);
    }

    public QuickSetupsPreference(Context context, Pages pages, Runnable hide) {
        super(context);
        this.pages = pages;
        this.hide = hide;
        setKey(KEY);
        setPersistent(false);
        setSelectable(false);
        setTitle(L10n.t(context, "Quick setups"));
        setSummary(L10n.t(context, "Quiet hides ads, Shop and LIVE. Privacy hides your views and "
                + "typing and stops tracking. Back to defaults resets every switch. "
                + "Press and hold to hide this row."));
    }

    @Override
    protected View onCreateView(ViewGroup parent) {
        Context context = getContext();
        LinearLayout card = new LinearLayout(context);
        card.setTag(ROW_TAG);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(SettingsUi.dp(context, 22), SettingsUi.dp(context, 12),
                SettingsUi.dp(context, 22), SettingsUi.dp(context, 12));
        card.setBackgroundColor(SettingsUi.background());

        TextView title = SettingsUi.text(context, String.valueOf(getTitle()), SettingsUi.TEXT_TITLE,
                SettingsUi.textPrimary(), Typeface.BOLD);
        title.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        card.addView(title, new LinearLayout.LayoutParams(-1, -2));

        TextView summary = SettingsUi.text(context, String.valueOf(getSummary()), SettingsUi.TEXT_BODY_SMALL,
                SettingsUi.textSecondary(), Typeface.NORMAL);
        summary.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(-1, -2);
        summaryParams.topMargin = SettingsUi.dp(context, 6);
        card.addView(summary, summaryParams);

        // Three across on a phone, one under another at large text or on a narrow screen, where
        // "Back to defaults" would otherwise be cut short.
        boolean stack = context.getResources().getConfiguration().fontScale > 1.3f
                || context.getResources().getConfiguration().screenWidthDp < 360;
        LinearLayout buttons = new LinearLayout(context);
        buttons.setTag(BUTTONS_TAG);
        buttons.setOrientation(stack ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        // A row lines its children up on their first baseline by default, so when "Back to
        // defaults" wrapped onto a second line it was pushed down and its bottom edge was cut off
        // (S22, 2026-10-13). Lined up on their tops, and all three as tall as the tallest.
        buttons.setBaselineAligned(false);
        buttons.addView(button(context, QUIET_TAG, QuickSetups.Choice.QUIET), params(context, stack, 0));
        buttons.addView(button(context, PRIVACY_TAG, QuickSetups.Choice.PRIVACY), params(context, stack, 1));
        buttons.addView(button(context, DEFAULTS_TAG, QuickSetups.Choice.DEFAULTS), params(context, stack, 2));
        LinearLayout.LayoutParams buttonsParams = new LinearLayout.LayoutParams(-1, -2);
        buttonsParams.topMargin = SettingsUi.dp(context, 12);
        card.addView(buttons, buttonsParams);
        card.setContentDescription(String.valueOf(getTitle()) + '.' + ' ' + getSummary());
        update(card);
        if (hide != null) {
            // The card and the three buttons on it: a press held anywhere on the row hides it.
            card.setOnLongClickListener(held -> {
                hide.run();
                return true;
            });
            for (int index = 0; index < buttons.getChildCount(); index++) {
                buttons.getChildAt(index).setOnLongClickListener(held -> {
                    hide.run();
                    return true;
                });
            }
        }

        FrameLayout holder = new FrameLayout(context);
        holder.setBackgroundColor(SettingsUi.background());
        holder.setPadding(0, SettingsUi.dp(context, 4), 0, SettingsUi.dp(context, 8));
        holder.addView(card, new FrameLayout.LayoutParams(-1, -2));
        return holder;
    }

    @Override protected void onBindView(View view) {
        super.onBindView(view);
        update(view);
    }

    private TextView button(Context context, String tag, QuickSetups.Choice choice) {
        TextView button = SettingsUi.text(context, "", SettingsUi.TEXT_BODY_SMALL, SettingsUi.accent(), Typeface.BOLD);
        button.setTag(tag);
        button.setGravity(Gravity.CENTER);
        button.setMinimumWidth(SettingsUi.dp(context, 48));
        button.setMinimumHeight(SettingsUi.dp(context, 48));
        button.setPadding(SettingsUi.dp(context, 8), 0, SettingsUi.dp(context, 8), 0);
        button.setFocusable(true);
        button.setClickable(true);
        SettingsUi.markAsButton(button);
        button.setBackground(SettingsUi.pressAndFocusOver(context, SettingsUi.RADIUS_SETTINGS_FIELD,
                SettingsUi.borderedSurface(context, SettingsUi.RADIUS_SETTINGS_FIELD, false)));
        button.setOnClickListener(view -> run(choice));
        return button;
    }

    private static LinearLayout.LayoutParams params(Context context, boolean stack, int index) {
        LinearLayout.LayoutParams params = stack
                ? new LinearLayout.LayoutParams(-1, -2)
                : new LinearLayout.LayoutParams(0, -1, 1);
        if (index > 0) {
            if (stack) params.topMargin = SettingsUi.dp(context, 8);
            else params.setMarginStart(SettingsUi.dp(context, 8));
        }
        return params;
    }

    private void update(View root) {
        label(root, QUIET_TAG, QuickSetups.Choice.QUIET, "Quiet");
        label(root, PRIVACY_TAG, QuickSetups.Choice.PRIVACY, "Privacy");
        label(root, DEFAULTS_TAG, QuickSetups.Choice.DEFAULTS, "Back to defaults");
    }

    private void label(View root, String tag, QuickSetups.Choice choice, String english) {
        View found = root.findViewWithTag(tag);
        if (!(found instanceof TextView)) return;
        TextView button = (TextView) found;
        String text = L10n.t(getContext(), running == choice ? "Working" : english);
        button.setText(text);
        button.setContentDescription(text);
        boolean enabled = running == null;
        button.setEnabled(enabled);
        button.setClickable(enabled);
    }

    void run(QuickSetups.Choice choice) {
        if (running != null) return;
        Context context = getContext();
        Collection<Setting<?>> onPages = pages.settings();
        running = choice;
        notifyChanged();
        AbstractPreferenceFragment.settingImportInProgress = true;
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            try {
                QuickSetups.Result result = QuickSetups.apply(context, choice, onPages);
                Utils.runOnMainThread(() -> finish(result, null));
            } catch (Exception error) {
                Utils.runOnMainThread(() -> finish(null, error));
            }
        });
        if (!accepted) {
            AbstractPreferenceFragment.settingImportInProgress = false;
            running = null;
            notifyChanged();
            SettingsActionBanner.showNotice(context, L10n.t(context,
                    "Quick setups couldn't start. Try again in a moment."));
        }
    }

    private void finish(QuickSetups.Result result, Exception error) {
        AbstractPreferenceFragment.settingImportInProgress = false;
        running = null;
        Context context = getContext();
        if (result == null) {
            Logger.printInfo(() -> "Quick setup failed", error);
            SettingsActionBanner.showNotice(context, L10n.t(context,
                    "That setup couldn't be applied. Nothing was changed."));
        } else if (result.changedCount == 0) {
            SettingsActionBanner.showNotice(context, L10n.t(context, "Your settings already match"));
        } else {
            // The rows and the restart row catch up first, so a switch that needs a restart has
            // its button on the page before the banner offers Undo.
            TikTokPreferenceFragment.onSettingsBatchChanged(result.previousValues);
            SettingsActionBanner.showUndo(context, L10n.quantity(context, result.changedCount,
                    "Changed 1 setting", "Changed %1$d settings"), () -> undo(context, result));
        }
        notifyChanged();
    }

    private static void undo(Context context, QuickSetups.Result result) {
        AbstractPreferenceFragment.settingImportInProgress = true;
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            try {
                QuickSetups.Result undone = QuickSetups.undo(context, result);
                Utils.runOnMainThread(() -> {
                    AbstractPreferenceFragment.settingImportInProgress = false;
                    TikTokPreferenceFragment.onSettingsBatchChanged(undone.previousValues);
                    SettingsActionBanner.showNotice(context, L10n.t(context, "Your earlier settings are back"));
                });
            } catch (Exception error) {
                Utils.runOnMainThread(() -> {
                    AbstractPreferenceFragment.settingImportInProgress = false;
                    Logger.printInfo(() -> "Quick setup undo failed", error);
                    SettingsActionBanner.showNotice(context, L10n.t(context, "Couldn't undo that. Try again."));
                });
            }
        });
        if (!accepted) {
            AbstractPreferenceFragment.settingImportInProgress = false;
            SettingsActionBanner.showNotice(context, L10n.t(context, "Couldn't undo that. Try again."));
        }
    }
}
