/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Says once that the day's budget has gone, and gets out of the way.
 *
 * <p>With no hold set there is nothing to argue with, so this is a toast and that is the end of
 * it. With a hold, the overlay is what carries the message and the way out of it: a banner would
 * be drawn under the panel, which covers the whole content root and swallows the touch, so the
 * Undo has to live on the panel itself.
 */
public final class SessionBudgetNotice {
    private SessionBudgetNotice() {
    }

    /** @param awemeId the video on screen as the budget ran out */
    public static void show(String awemeId) {
        // The hold waits for this video, so its panel can't carry the news yet. A banner can:
        // nothing covers the feed until the video ends.
        if (FinishLastVideo.begin(awemeId)) {
            SessionLockOverlay.ensureRunning();
            String message = spentMessage() + ". " + L10n.t("The feed stops when this video ends.");
            Utils.runOnMainThread(() -> {
                android.app.Activity activity = Utils.getActivity();
                android.view.ViewGroup root = activity == null
                        ? null : activity.findViewById(android.R.id.content);
                app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay.showNoticeBanner(
                        root, message);
            });
            return;
        }
        if (Settings.SESSION_BUDGET_LOCK_MINUTES.get() > 0) {
            SessionLockOverlay.ensureRunning();
            return;
        }
        Utils.showToastShort(spentMessage());
    }

    /**
     * The quiet reminder partway through, if one is due.
     *
     * <p>The same banner the block button's undo uses, without anything to press: it takes no
     * focus, takes itself away after a few seconds, and announces itself once as a polite live
     * region, which is the part a toast cannot do. It falls back to a toast where there is no
     * view to draw in. It is never due while a hold is up, so it cannot end up under the panel.
     *
     * <p>Three wordings in rotation, because a fixed friction stops being read. None of them
     * names the reader or the count: an interrupt that does reads as being watched, and the one
     * lab study to measure it found that made people watch more, not less.
     */
    public static void showIntervalNoticeIfDue() {
        int wording = SessionBudget.claimIntervalNotice();
        if (wording < 0) return;
        Utils.runOnMainThread(() -> {
            android.app.Activity activity = Utils.getActivity();
            android.view.ViewGroup root = activity == null
                    ? null : activity.findViewById(android.R.id.content);
            app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay.showNoticeBanner(
                    root, intervalMessage(wording));
        });
    }

    static String intervalMessage(int wording) {
        switch (wording) {
            case 0: return L10n.t("Still here. Nothing is waiting.");
            case 1: return L10n.t("A good place to stop, if you want one.");
            default: return L10n.t("The feed doesn't end. This is a fine time to leave it.");
        }
    }

    /** What the day came to, in whichever budget ran out. */
    static String spentMessage() {
        int minuteBudget = Settings.SESSION_BUDGET_MINUTES.get();
        long watchedMinutes = SessionBudget.watchedMs() / 60_000L;
        // Both can be set; whichever is over is the one worth naming, and the videos come first
        // because that is the number a reader recognises.
        int videoBudget = Settings.SESSION_BUDGET_VIDEOS.get();
        if (videoBudget > 0 && SessionBudget.videosSeen() >= videoBudget) {
            return videosToday(SessionBudget.videosSeen());
        }
        if (minuteBudget > 0 && watchedMinutes >= minuteBudget) {
            return L10n.quantity(Utils.getContext(), watchedMinutes,
                    "That is one minute today", "That is %1$d minutes today");
        }
        return videosToday(SessionBudget.videosSeen());
    }

    /** A budget of one produced "That is 1 videos today", which no phrasebook forgives. */
    private static String videosToday(int videos) {
        return L10n.quantity(Utils.getContext(), videos,
                "That is one video today", "That is %1$d videos today");
    }
}
