/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.view.View;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.ImmediateAction;
import app.morphe.extension.tiktok.inbox.AutoStreak;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Sends today's streak message at once, which also shows the setup works without waiting for
 * the time to come round. The daily one then waits for tomorrow.
 */
@SuppressWarnings("deprecation")
public final class SendStreakNowPreference extends Preference implements ImmediateAction {
    @Override public boolean actsOnTap() {
        return true;
    }

    public SendStreakNowPreference(Context context, Runnable afterSend) {
        super(context);
        // A key so the settings search can index this row; no Setting has it.
        setKey("action_send_streak_now");
        setTitle(L10n.t(context, "Send it now"));
        setSummary(L10n.t(context, "Sends today's message right away. The daily one then waits for tomorrow."));
        setOnPreferenceClickListener(preference -> {
            if (!Settings.AUTO_STREAK.get()) {
                Utils.showToastShort(L10n.t(context, "Turn on Keep a streak going first"));
                return true;
            }
            Utils.showToastShort(L10n.t(context, "Sending the message"));
            AutoStreak.sendNow(context, toast -> {
                Utils.showToastLong(toast);
                if (afterSend != null) afterSend.run();
            });
            return true;
        });
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        app.morphe.extension.tiktok.Utils.setTitleAndSummaryColor(view);
    }
}
