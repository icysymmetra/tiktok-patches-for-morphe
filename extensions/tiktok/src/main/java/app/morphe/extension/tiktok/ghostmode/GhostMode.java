/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 * Follows eduardo3677-ai/tiktok-patches-for-morphe.
 */
package app.morphe.extension.tiktok.ghostmode;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.shared.settings.Setting;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.util.Map;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Answers TikTok's own reporting calls so they return before sending anything. This stops
 * the client telling other people what you looked at; it cannot take back what the server
 * already recorded, and a viewer list can still fill up from another device.
 *
 * <h2>Why the answers are reported</h2>
 *
 * <p>An export taken while another person's profile showed no follower counts could not say
 * whether the hooked call had run for that page, what it answered, or whether TikTok's Profile
 * views feature was asking at all: two exports on 2026-09-15, one with the setting on and one
 * with it off, carried no ghost-mode line of any kind. That left a reported defect with two
 * builds' worth of failed reproduction and no way to tell a hooked call from a call that never
 * happened. So each answer is recorded, and the three states a reader needs are separable in
 * the export:
 *
 * <ul>
 *   <li>no ghost mode family at all: the patch is not in this build;
 *   <li>the family with only {@code installed}: the patch is in and no reporter was reached;
 *   <li>a call site's own entry: that reporter was reached, and the log line says what it got.
 * </ul>
 *
 * <p>Nothing here names an account or a handle, in line with the rest of the report.
 */
public final class GhostMode {
    /** How this appears in the export's hook status section. */
    private static final String HOOK_FAMILY = "ghost mode";

    private static final String INSTALLED = "installed";
    private static final String STORY_VIEW = "story view";
    private static final String PROFILE_VIEW = "profile view";
    private static final String TYPING_STATUS = "typing status";
    private static final String STORY_PLAY_STATS = "story play stats";
    private static final String ONLINE_STATUS = "online status";

    /** TikTok's aweme types for a story; getIsTikTokStory covers the rest. */
    private static final int STORY_TYPE = 40;
    private static final int STORY_TYPE_SHARED = 45;

    private static final String BLOCKED = "blocked";
    private static final String SENT = "sent";

    /**
     * The last answer written for each call site.
     *
     * <p>The typing indicator asks on a timer while a message is being written, so a line per
     * call would push everything else out of a buffer a reader is trying to read. A steady
     * state is worth one line; what a reader needs is the change and the fact it happened.
     */
    private static final Map<String, String> reported = new ConcurrentHashMap<>();
    private static volatile boolean storyReportingFailed;
    private static volatile boolean blockedCallObserved;
    private static final List<WeakReference<Runnable>> observers = new ArrayList<>();

    public enum Status { OFF, PAUSED, UNOBSERVED, BLOCKED, PROBLEM }

    /** Local evidence only. A blocked call does not verify TikTok's server-side viewer list. */
    public static Status status() {
        if (!Settings.GHOST_MODE.savedValue()) return Status.OFF;
        if (Setting.isPaused()) return Status.PAUSED;
        if (storyReportingFailed) return Status.PROBLEM;
        return blockedCallObserved ? Status.BLOCKED : Status.UNOBSERVED;
    }

    /** Bound rows keep their own callback alive; the hook never keeps a settings Activity alive. */
    public static synchronized void observe(Runnable observer) {
        for (Iterator<WeakReference<Runnable>> it = observers.iterator(); it.hasNext();) {
            Runnable existing = it.next().get();
            if (existing == observer) return;
            if (existing == null) it.remove();
        }
        observers.add(new WeakReference<>(observer));
    }

    private static void stateChanged() {
        List<Runnable> current = new ArrayList<>();
        synchronized (GhostMode.class) {
            for (Iterator<WeakReference<Runnable>> it = observers.iterator(); it.hasNext();) {
                Runnable observer = it.next().get();
                if (observer == null) it.remove();
                else current.add(observer);
            }
        }
        for (Runnable observer : current) Utils.runOnMainThread(observer);
    }

    private GhostMode() {
    }

    /**
     * Called once where the settings load, so the family is in the export even on a run where
     * no reporter is reached. Without it, a missing family says both "not patched" and "never
     * called" and a reader cannot tell which.
     */
    public static void installed() {
        HookStatus.bound(HOOK_FAMILY, INSTALLED);
    }

    public static boolean shouldBlockStoryView() {
        return answer(STORY_VIEW);
    }

    public static boolean shouldBlockProfileView() {
        return answer(PROFILE_VIEW);
    }

    public static boolean shouldBlockTypingStatus() {
        return answer(TYPING_STATUS);
    }

    /**
     * TikTok's play report (/aweme/v1/aweme/stats/), asked for every video and story that plays.
     * A story's carries its aid, play_delta=1 and story_consumption_type beside the
     * reportStoryViewed call above, and with only that call blocked a second account still saw
     * the viewer (#39). Only a story's report is held back; a feed video's goes as before, and
     * asks nothing of the export.
     */
    public static boolean shouldBlockStoryStats(Aweme aweme) {
        if (!isStory(aweme)) return false;
        return answer(STORY_PLAY_STATS);
    }

    /**
     * TikTok's activity status report, the call that tells the server you're active so a friend
     * sees a green dot or "Active now". Held back only when Ghost mode and its own Hide online
     * status switch are both on, and asked at every report, so flipping the switch takes effect
     * at the next one. A paused process answers false through both settings.
     */
    public static boolean shouldBlockOnlineStatus() {
        return answer(ONLINE_STATUS, Settings.GHOST_MODE.get() && Settings.GHOST_HIDE_ONLINE_STATUS.get());
    }

    static boolean isStory(Aweme aweme) {
        if (aweme == null) return false;
        try {
            if (aweme.getIsTikTokStory()) return true;
            int type = aweme.getAwemeType();
            return type == STORY_TYPE || type == STORY_TYPE_SHARED;
        } catch (Throwable unreadable) {
            // Inside TikTok's own reporter: a renamed getter sends the report rather than crash.
            HookStatus.threw(HOOK_FAMILY, STORY_PLAY_STATS, unreadable);
            // Clearing a diagnostic report or toggling the switch cannot repair that getter.
            // Keep the failure visible for this process, including after another hook succeeds.
            if (!storyReportingFailed) {
                storyReportingFailed = true;
                stateChanged();
            }
            return false;
        }
    }

    /** The setting, recorded against the call site that asked for it. */
    private static boolean answer(String callSite) {
        return answer(callSite, Settings.GHOST_MODE.get());
    }

    private static boolean answer(String callSite, boolean blocked) {
        if (blocked && !blockedCallObserved) {
            blockedCallObserved = true;
            stateChanged();
        }
        String outcome = blocked ? BLOCKED : SENT;
        HookStatus.bound(HOOK_FAMILY, callSite + " " + outcome);
        if (!outcome.equals(reported.put(callSite, outcome))) {
            Logger.diagnosticInfo(
                    DiagnosticCategory.OTHER,
                    "GhostMode",
                    () -> "Ghost mode " + callSite + ": " + outcome);
        }
        return blocked;
    }
}
