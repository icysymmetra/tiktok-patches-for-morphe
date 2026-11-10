/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

/**
 * Whether TikTok's view report for a watched video goes out.
 *
 * <p>Asked at the entry of AwemeStatsApi's two senders: the report for one video, which sends
 * at once or queues for a batch, and the batch send. That report (/aweme/v1/aweme/stats/) is
 * how TikTok counts a view, and Watch history is built from it on TikTok's side. It also carries
 * how long the video before played, so For You learns less while it's held back. Read on every
 * call, so a change applies to the next video, and while Hushfeed is paused the switch answers
 * off and TikTok reports as it ships. Off by default even with the patch picked: people use
 * Watch history to find a video again.
 */
@SuppressWarnings("unused")
public final class WatchHistoryRecording {
    private WatchHistoryRecording() {
    }

    public static boolean shouldSkip() {
        // The batch send can run from a feed pause early in a start, before the settings load.
        boolean skip = SettingsStatus.watchHistoryEnabled && Utils.getContext() != null
                && Settings.STOP_WATCH_HISTORY.get();
        if (skip) Logger.printDebug(() -> "Watch history: a view report was not sent");
        return skip;
    }
}
