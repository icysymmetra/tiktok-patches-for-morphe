/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.playback;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.feedfilter.LiveFilter;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.wellbeing.SessionPlaybackHold;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Mute feed videos: the feed plays silent until the reader turns the sound back on, without
 * touching the phone's volume, and other apps keep the audio while it is on.
 *
 * <p>A one-off {@code setVolume(0, 0)} doesn't stick, since every new video sets its own volume as
 * it prepares. What sticks is the feed player engine's own mute flag: TikTok never sets it on
 * these engines, and a native player created later is handed the stored flag. A new video can
 * come on a new engine, though, so the flag is set each time an engine starts playing a feed
 * video, at the top of its {@code play()}.
 *
 * <p>DMs, stories and LIVE replays play on the same engines. What tells a feed video apart is the
 * PlayerController that asked for it: this hears each controller play first, notes whether its
 * host is the feed activity and whether the video is a story or LIVE, and the engine's source id
 * (the video's id) is looked up in those notes when it plays. A video no feed controller asked
 * for, like one in a DM chat, has no note and keeps its sound.
 *
 * <p>TikTok takes the audio focus twice for a feed video: a lasting grant per player session and
 * a transient one each time a page resumes. While muted with a feed video in front both requests
 * are turned down, and the ones already held are given up when the mute is switched on.
 */
public final class FeedMute {
    private static final String HOOK_FAMILY = "Feed mute";
    /** Controller plays remembered, newest last; the feed keeps a few videos prepared ahead. */
    private static final int MAX_PLAYS = 64;

    /** Whether each video a PlayerController asked to play was a feed video, by aweme id. */
    private static final Map<String, Boolean> PLAYS = new LinkedHashMap<String, Boolean>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > MAX_PLAYS;
        }
    };
    /**
     * Engines that played a feed video, with that video's id, so a mute switched on mid-video
     * reaches them and a lost note only counts for the video the engine was muted for.
     */
    private static final WeakHashMap<Object, String> FEED_ENGINES = new WeakHashMap<>();
    /**
     * Every engine seen playing. TikTok prepares the next videos ahead, and an engine's play()
     * runs while it prepares, before the controller has asked for that video; when the video
     * comes on, the engine often starts without play() again. Deciding at play() alone missed
     * every such engine (S22 and S25, 2026-09-29: no feed engine was ever muted, so a feed
     * video on 47.1.3 and a photo post on 47.0.3 kept their sound). The controller's ask settles
     * the engines already prepared for its video.
     */
    private static final WeakHashMap<Object, Boolean> ENGINES = new WeakHashMap<>();
    /** Engines this muted, with the mute TikTok had on them before. */
    private static final WeakHashMap<Object, Boolean> MUTED = new WeakHashMap<>();
    /** Focus helpers seen asking for focus for a feed video. */
    private static final WeakHashMap<Object, Boolean> SESSION_HELPERS = new WeakHashMap<>();
    private static final WeakHashMap<Object, Boolean> PAGE_HELPERS = new WeakHashMap<>();
    private static volatile WeakReference<Object> lastSessionHelper = new WeakReference<>(null);
    private static volatile WeakReference<Object> lastPageHelper = new WeakReference<>(null);

    /** TikTok's screens between their onStart and onStop. None means TikTok is in the background. */
    private static final WeakHashMap<Activity, Boolean> STARTED = new WeakHashMap<>();
    /**
     * Feed windows resumed now. TikTok's launcher entry is an alias of MainActivity, so a theme
     * change recreates a buried copy too, and that copy resumes and pauses for a moment while the
     * feed's stays resumed. Main thread only.
     */
    private static final WeakHashMap<Activity, Boolean> RESUMED_FEED = new WeakHashMap<>();
    /** The feed's activity is the one in front. Main thread writes, any thread reads. */
    private static volatile boolean feedInFront;
    /** The last video a controller asked to play was a feed video. */
    private static volatile boolean lastPlayFeed;
    private static volatile Class<?> feedActivity;
    /**
     * A refresh that found the feed behind another screen, the settings page among them. What the
     * focus should be is settled when the feed is back: a mute switched on from settings left the
     * grant the playing video held in place, and another app's music stayed paused.
     */
    private static volatile boolean refreshOwed;
    /**
     * Sound turned back on with nothing playing. TikTok asks for its session focus once per player
     * session and believes it still holds it, so nothing asked again and the feed played over
     * another app; the next feed video to play asks instead.
     */
    private static volatile boolean focusOwed;
    private static WeakReference<Application> followed = new WeakReference<>(null);
    /** Feed engines this silenced, and focus requests it turned down, since TikTok started. */
    private static final AtomicLong SILENCED = new AtomicLong();
    private static final AtomicLong FOCUS_HELD = new AtomicLong();

    private FeedMute() {
    }

    /** From the feed activity's onCreate, with the other activity hooks. */
    public static void install(Activity activity) {
        if (activity == null) return;
        feedActivity = activity.getClass();
        feedInFront = true;
        LogBufferManager.registerReportSection(Report.INSTANCE);
        Application application = activity.getApplication();
        if (application == null || followed.get() == application) return;
        followed = new WeakReference<>(application);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity resumed) {
                if (!isFeedHost(resumed)) return;
                RESUMED_FEED.put(resumed, Boolean.TRUE);
                feedInFront = true;
                remuteFeedEngines();
                if (refreshOwed) {
                    refreshOwed = false;
                    refresh();
                }
            }

            @Override public void onActivityPaused(Activity paused) {
                if (!isFeedHost(paused)) return;
                RESUMED_FEED.remove(paused);
                // A buried copy pausing leaves the feed in front, still muted.
                feedInFront = !RESUMED_FEED.isEmpty();
            }

            @Override public void onActivityCreated(Activity created, Bundle state) { }
            // A pager's first video can start its engine before the pager resumes.
            @Override public void onActivityStarted(Activity started) {
                synchronized (STARTED) {
                    STARTED.put(started, Boolean.TRUE);
                }
                if (isFeedHost(started)) feedInFront = true;
            }
            @Override public void onActivityStopped(Activity stopped) {
                synchronized (STARTED) {
                    STARTED.remove(stopped);
                }
            }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity destroyed) {
                RESUMED_FEED.remove(destroyed);
            }
        });
    }

    public static boolean isMuted() {
        return SettingsStatus.feedMuteEnabled && Settings.FEED_MUTED.get();
    }

    /** The feed's button and the settings row. Main thread. */
    public static void setMuted(boolean muted) {
        if (!Settings.FEED_MUTED.save(muted)) {
            Utils.showToastLong(L10n.t("This change couldn't be saved. Try again."));
            return;
        }
        refresh();
    }

    /**
     * Brings the feed's engines and audio focus in line with the setting: on, the engines that
     * played feed videos go silent and the focus TikTok holds for them is given up; off, the
     * engines this silenced get TikTok's own state back and, with a feed video playing, the
     * focus it last held back is asked for again. Main thread.
     */
    public static void refresh() {
        try {
            boolean muted = isMuted();
            List<Object> engines = keys(muted ? FEED_ENGINES : MUTED);
            for (Object engine : engines) apply(engine);
            if (!feedInFront) {
                // A DM's or a story's player may hold the focus now, so it isn't touched here.
                refreshOwed = true;
                focusOwed = false;
                return;
            }
            if (!lastPlayFeed) return;
            Context context = Utils.getContext();
            if (muted) {
                focusOwed = false;
                for (Object helper : keys(SESSION_HELPERS)) abandonSessionFocus(helper);
                if (context != null) for (Object helper : keys(PAGE_HELPERS)) abandonPageFocus(helper, context);
                return;
            }
            if (!Boolean.TRUE.equals(SessionPlaybackHold.currentPlaying())) {
                focusOwed = true;
                return;
            }
            requestHeldFocus();
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, "refresh", failure);
            Logger.printException(() -> "Could not apply Mute feed videos", failure);
        }
    }

    /** Asks again for the focus TikTok last held for the feed. Main thread. */
    private static void requestHeldFocus() {
        focusOwed = false;
        Object session = lastSessionHelper.get();
        if (session != null) requestSessionFocus(session);
        Context context = Utils.getContext();
        Object page = lastPageHelper.get();
        if (page != null && context != null) requestPageFocus(page, context);
    }

    /**
     * Whether Mute feed videos governs this video. A story or a LIVE replay in the feed plays with
     * its sound whatever the button says, so the button stays off those. A video with no note yet
     * answers yes, so a missed note costs a moment of a button rather than the button.
     */
    public static boolean appliesTo(String awemeId) {
        if (awemeId == null) return true;
        Boolean feed;
        synchronized (PLAYS) {
            feed = PLAYS.get(awemeId);
        }
        return feed == null || feed;
    }

    /** PlayerController's play, before it starts anything. Main thread. */
    public static void onControllerPlay(Object controller, Object aweme) {
        if (!SettingsStatus.feedMuteEnabled) return;
        try {
            boolean feed = isFeedPlay(controller, aweme);
            lastPlayFeed = feed;
            String id = aweme instanceof Aweme ? ((Aweme) aweme).getAid() : null;
            if (id != null && !id.isEmpty()) {
                record(id, feed, isFeedHost(Reflect.readField(controller, "activity")));
                settle(id, feed && feedInFront);
            }
            HookStatus.bound(HOOK_FAMILY, "controller play");
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, "controller play", failure);
        }
    }

    /**
     * The feed's own activity, or the detail pager a video from a profile, a hashtag or a sound
     * plays in. The mute button is drawn there too, so what it says has to hold there.
     */
    static boolean isFeedHost(Object host) {
        Class<?> feed = feedActivity;
        return host != null && feed != null
                && (host.getClass() == feed || FeedVisibility.isDetailPager(host));
    }

    /**
     * The feed's current video, as the block button's tracking names it on each change. TikTok
     * starts most feed videos through routes other than the PlayerController play heard above
     * (S25, 47.1.3, 2026-09-29: three swipes in four left no note, so their engines kept their
     * sound), and this tracking follows every one of them. Main or player thread.
     */
    public static void onCurrentVideo(Object aweme) {
        note(aweme, true, "current video");
    }

    /**
     * A feed item as the feed binds it, which is ahead of the reader reaching it, as the engine
     * for it is prepared ahead. Noted only from the current video, a swipe let about half a
     * second of the next video's sound through before its change came (S25, 2026-09-29). A
     * bind says nothing about what plays now, so the focus is left to the current video.
     */
    public static void onFeedBind(Object aweme) {
        note(aweme, false, "feed bind");
    }

    private static void note(Object aweme, boolean current, String what) {
        if (!SettingsStatus.feedMuteEnabled || !(aweme instanceof Aweme)) return;
        try {
            String id = ((Aweme) aweme).getAid();
            if (id == null || id.isEmpty()) return;
            boolean feed = feedInFront && isFeedItem((Aweme) aweme);
            if (current) lastPlayFeed = feed;
            record(id, feed, feedInFront);
            settle(id, feed);
            HookStatus.bound(HOOK_FAMILY, what);
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, what, failure);
        }
    }

    /**
     * Notes whether a video is a feed video. A note from outside the feed never takes the feed's
     * own note away: a feed video shared to a DM and opened there plays under the same id, and
     * losing the note left the feed's engine for it with its sound when the reader came back
     * (verifier, 2026-09-29). The other screen's engines still get their sound from settle(),
     * and {@link #remuteFeedEngines} silences the feed's again when the feed is back in front.
     */
    private static void record(String id, boolean feed, boolean fromFeed) {
        synchronized (PLAYS) {
            if (!feed && !fromFeed && Boolean.TRUE.equals(PLAYS.get(id))) return;
            PLAYS.put(id, feed);
        }
    }

    /**
     * The feed is back in front: every engine prepared for a video the feed noted is a feed
     * engine again. Another screen that played the same video gave those engines their sound,
     * and the feed's own resumes without a play() to decide at. Main thread.
     */
    private static void remuteFeedEngines() {
        for (Object engine : keys(ENGINES)) {
            String id = engineSourceId(engine);
            if (id == null) continue;
            Boolean feed;
            synchronized (PLAYS) {
                feed = PLAYS.get(id);
            }
            if (!Boolean.TRUE.equals(feed)) continue;
            synchronized (FEED_ENGINES) {
                FEED_ENGINES.put(engine, id);
            }
            apply(engine);
        }
    }

    /** A video on the feed or a detail pager, not a story and not LIVE. */
    static boolean isFeedPlay(Object controller, Object aweme) {
        if (!(aweme instanceof Aweme)) return false;
        if (!isFeedHost(Reflect.readField(controller, "activity"))) return false;
        return isFeedItem((Aweme) aweme);
    }

    /** Not a story and not LIVE: what the button governs, wherever the item came from. */
    private static boolean isFeedItem(Aweme item) {
        if (item.getIsTikTokStory()) return false;
        int type = item.getAwemeType();
        if (type == STORY_TYPE || type == STORY_TYPE_SHARED) return false;
        return !new LiveFilter().getFiltered(item);
    }

    /** Story aweme types, as Ghost mode reads them. */
    private static final int STORY_TYPE = 40;
    private static final int STORY_TYPE_SHARED = 45;

    /**
     * Engines already prepared for the video a controller just asked for, whose play() came
     * before the ask, take the answer that play() would have got.
     */
    private static void settle(String id, boolean feed) {
        for (Object engine : keys(ENGINES)) {
            if (!id.equals(engineSourceId(engine))) continue;
            synchronized (FEED_ENGINES) {
                if (feed) FEED_ENGINES.put(engine, id);
                else FEED_ENGINES.remove(engine);
            }
            apply(engine);
        }
    }

    /** The top of the feed engine's play(), on its player thread. */
    public static void onEnginePlay(Object engine) {
        if (engine == null || !SettingsStatus.feedMuteEnabled) return;
        try {
            synchronized (ENGINES) {
                ENGINES.put(engine, Boolean.TRUE);
            }
            String id = engineSourceId(engine);
            Boolean feed = null;
            if (id != null) {
                synchronized (PLAYS) {
                    feed = PLAYS.get(id);
                }
            }
            boolean isFeed;
            if (!feedInFront && inBackground()) {
                // With no TikTok screen showing, what plays is the video background play carried
                // on with, and TikTok's media notification resumes it with a play() on the same
                // engine. That is still the feed video it was muted for (2026-09-30: a resume
                // from the notification gave a muted feed video its sound back).
                synchronized (FEED_ENGINES) {
                    isFeed = id != null && id.equals(FEED_ENGINES.get(engine));
                }
            } else if (feed == null && feedInFront) {
                // No note is no evidence against an engine muted for this same video: a profile
                // grid or search results in the feed's own activity bind past MAX_PLAYS items and
                // push the playing and next videos' notes out (verifier, 2026-09-29). An engine
                // reused for another video still gets its sound back.
                synchronized (FEED_ENGINES) {
                    isFeed = id != null && id.equals(FEED_ENGINES.get(engine));
                }
            } else {
                isFeed = feedInFront && Boolean.TRUE.equals(feed);
            }
            if (isFeed) {
                synchronized (FEED_ENGINES) {
                    FEED_ENGINES.put(engine, id);
                }
            } else {
                synchronized (FEED_ENGINES) {
                    FEED_ENGINES.remove(engine);
                }
            }
            apply(engine);
            if (isFeed && focusOwed && !isMuted()) {
                focusOwed = false;
                Utils.runOnMainThread(FeedMute::requestHeldFocus);
            }
            HookStatus.bound(HOOK_FAMILY, "engine play");
        } catch (Throwable failure) {
            HookStatus.threw(HOOK_FAMILY, "engine play", failure);
        }
    }

    private static boolean inBackground() {
        synchronized (STARTED) {
            return STARTED.isEmpty();
        }
    }

    /** Silences the engine, or gives back TikTok's own state on one this silenced. */
    private static void apply(Object engine) {
        synchronized (FEED_ENGINES) {
            synchronized (MUTED) {
                // A swipe and a toggle can arrive on different threads. Decide while holding
                // both maps through the native call so an older decision cannot run last.
                boolean mute = FEED_ENGINES.containsKey(engine) && isMuted();
                Boolean before = MUTED.get(engine);
                if (mute) {
                    if (before == null) {
                        before = engineIsMute(engine);
                        MUTED.put(engine, before);
                        SILENCED.incrementAndGet();
                    }
                    setEngineMute(engine, true);
                } else if (before != null) {
                    setEngineMute(engine, before);
                    MUTED.remove(engine);
                }
            }
        }
    }

    /** SimAudioFocusManager's per-session request. True turns it down. Any thread. */
    public static boolean holdSessionFocus(Object helper) {
        if (helper == null || !SettingsStatus.feedMuteEnabled || !feedInFront || !lastPlayFeed) return false;
        synchronized (SESSION_HELPERS) {
            SESSION_HELPERS.put(helper, Boolean.TRUE);
            lastSessionHelper = new WeakReference<>(helper);
        }
        return holdBack("session focus held back");
    }

    /** The page helper's transient request, made on each resume. True turns it down. Any thread. */
    public static boolean holdPageFocus(Object helper) {
        if (helper == null || !SettingsStatus.feedMuteEnabled || !feedInFront || !lastPlayFeed) return false;
        synchronized (PAGE_HELPERS) {
            PAGE_HELPERS.put(helper, Boolean.TRUE);
            lastPageHelper = new WeakReference<>(helper);
        }
        return holdBack("page focus held back");
    }

    private static boolean holdBack(String what) {
        if (!Settings.FEED_MUTED.get()) return false;
        FOCUS_HELD.incrementAndGet();
        HookStatus.bound(HOOK_FAMILY, what);
        return true;
    }

    /** The daily hold waits for TikTok's focus before resuming; while muted it never comes. */
    public static boolean isHoldingFocus() {
        return feedInFront && lastPlayFeed && isMuted();
    }

    private static List<Object> keys(WeakHashMap<Object, ?> map) {
        synchronized (map) {
            return new ArrayList<>(map.keySet());
        }
    }

    /**
     * The FEED MUTE section of the diagnostic report. A report of a silent feed (#58) couldn't say
     * whether Mute feed videos was on, and with the feed's mute button turned off nothing on the
     * screen says so either.
     */
    static final class Report implements LogBufferManager.ReportSection {
        static final Report INSTANCE = new Report();

        @Override public String title() {
            return "FEED MUTE";
        }

        @Override public List<String> lines() {
            List<String> lines = new ArrayList<>();
            boolean saved = Settings.FEED_MUTED.savedValue();
            lines.add("Mute feed videos: " + (saved ? "on" : "off"));
            if (saved && !Settings.FEED_MUTED.get()) lines.add("Metra is paused, so the feed plays with its sound");
            // The saved choice, like the line above: Pause reads every switch as off, which would
            // report a button the reader never turned off.
            lines.add("Mute button on the feed: " + (Settings.FEED_MUTE_BUTTON.savedValue() ? "shown" : "turned off"));
            // One player plays many videos, so this counts players, not videos.
            lines.add("Feed players muted since TikTok started: " + SILENCED.get());
            lines.add("Audio focus requests turned down: " + FOCUS_HELD.get());
            return lines;
        }
    }

    // TikTok's player members change name with every build, so the patch writes them into the
    // bodies of these bridges. What they do without the patch is for the tests.

    /** TikTok's members as the tests stand them in. */
    interface Native {
        String sourceId(Object engine);
        boolean isMute(Object engine);
        void setMute(Object engine, boolean mute);
        void abandonSession(Object helper);
        void requestSession(Object helper);
        void abandonPage(Object helper, Context context);
        void requestPage(Object helper, Context context);
    }

    static volatile Native nativeForTests;

    static String engineSourceId(Object engine) {
        Native stand = nativeForTests;
        return stand == null ? null : stand.sourceId(engine);
    }

    static boolean engineIsMute(Object engine) {
        Native stand = nativeForTests;
        return stand != null && stand.isMute(engine);
    }

    static void setEngineMute(Object engine, boolean mute) {
        Native stand = nativeForTests;
        if (stand != null) stand.setMute(engine, mute);
    }

    static void abandonSessionFocus(Object helper) {
        Native stand = nativeForTests;
        if (stand != null) stand.abandonSession(helper);
    }

    static void requestSessionFocus(Object helper) {
        Native stand = nativeForTests;
        if (stand != null) stand.requestSession(helper);
    }

    static void abandonPageFocus(Object helper, Context context) {
        Native stand = nativeForTests;
        if (stand != null) stand.abandonPage(helper, context);
    }

    static void requestPageFocus(Object helper, Context context) {
        Native stand = nativeForTests;
        if (stand != null) stand.requestPage(helper, context);
    }

    /** Clears everything this remembers. Tests only. */
    static void resetForTests() {
        synchronized (PLAYS) {
            PLAYS.clear();
        }
        for (WeakHashMap<Object, ?> map : Arrays.<WeakHashMap<Object, ?>>asList(ENGINES, FEED_ENGINES, MUTED, SESSION_HELPERS, PAGE_HELPERS)) {
            synchronized (map) {
                map.clear();
            }
        }
        synchronized (STARTED) {
            STARTED.clear();
        }
        RESUMED_FEED.clear();
        lastSessionHelper = new WeakReference<>(null);
        lastPageHelper = new WeakReference<>(null);
        feedInFront = false;
        lastPlayFeed = false;
        refreshOwed = false;
        focusOwed = false;
        feedActivity = null;
        nativeForTests = null;
        SILENCED.set(0);
        FOCUS_HELD.set(0);
    }
}
