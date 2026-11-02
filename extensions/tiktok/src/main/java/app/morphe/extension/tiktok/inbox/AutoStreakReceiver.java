/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * The streak's alarm, and the reboot, update and clock changes that clear or move it. The patch
 * declares it switched off, so it starts nothing while the streak is off. See {@link AutoStreak}.
 */
public final class AutoStreakReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        // Held open so the send can wait for TikTok's messaging to start.
        AutoStreak.onReceive(context, intent, goAsync());
    }
}
