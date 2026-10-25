/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.ImmediateAction;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Shows or hides the Quick setups card on the home page, which a long press on the card hides.
 * One tap flips it and the summary says which way it is now; the home page follows when it is
 * next on screen. It stores nothing of its own, the flag is {@link BaseSettings#HIDE_QUICK_SETUPS}.
 */
@SuppressWarnings("deprecation")
public final class QuickSetupsHomePreference extends Preference implements ImmediateAction {
    public static final String KEY = "action_quick_setups_home";

    @Override public boolean actsOnTap() {
        return true;
    }

    public QuickSetupsHomePreference(Context context) {
        super(context);
        // A key so the settings search can index this row. A key with no Setting behind it is
        // skipped by the settings framework, so nothing tries to persist it.
        setKey(KEY);
        setPersistent(false);
        setTitle(L10n.t(context, "Quick setups on the home page"));
        showState(context);
        setOnPreferenceClickListener(preference -> {
            BaseSettings.HIDE_QUICK_SETUPS.save(!BaseSettings.HIDE_QUICK_SETUPS.get());
            showState(context);
            return true;
        });
    }

    private void showState(Context context) {
        setSummary(L10n.t(context, BaseSettings.HIDE_QUICK_SETUPS.get()
                ? "Hidden. Tap to show it again." : "Shown. Tap to hide it."));
    }
}
