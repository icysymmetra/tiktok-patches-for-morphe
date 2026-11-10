/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A feed that stays shut: For You, Following and the other feed tabs sit behind a calm panel and
 * their pager doesn't swipe, while Inbox, profiles and search work as they always do.
 *
 * <p>It is not a second hold. The daily budget's panel, its stopped playback and its pager block
 * already do all of this, so the lock is a second reason for them to be there: {@link
 * SessionLockOverlay} puts its panel up when either the budget is spent or this is on, the
 * pager's {@code onInterceptTouchEvent} and {@code onTouchEvent} turn swipes down through {@link
 * FinishLastVideo#holdsSwipe}, and {@link SessionPlaybackHold} stops the video under the panel.
 * A spent budget keeps its own panel, with its countdown, when both apply.
 *
 * <p>A link to one video still opens that video. TikTok starts the app on it with a link
 * (cold, through the main activity's creation, and warm, through its new intent) and plays it at
 * the top of the feed, where nothing tells it from a feed video but the intent that brought it.
 * So a link entry lets the first video that comes up stay uncovered, and the next video, by any
 * route, ends the exception. A video opened from a message, a profile or search is not on the
 * recommendation feed at all, so the panel never covers it.
 *
 * <p>The same link entry serves Open shared videos alone, a switch of its own that needs no
 * lock: the video a link opened plays by itself, with the feed's swipe turned down while it is
 * the one playing ({@link #linkVideoAlone}), so there is no next video to fall into. Another
 * video coming up by any route ends it, and so does TikTok coming back after {@link
 * #AWAY_ENDS_ALONE_MS} or more away, when the reader is back for TikTok itself and the link's
 * video may still be sitting at the top of For You. Only the pager the video came up in is held:
 * the other top tabs and the bottom Friends tab each have a pager of their own, and one of them
 * where nothing new starts playing, like a LIVE or a photo post, would otherwise not swipe at all.
 */
public final class FeedLock {
    /** How long a link entry waits for its video to come up before it is forgotten. */
    static final long LINK_WINDOW_MS = 20_000L;
    /** How long TikTok is away before a return gives a shared video's feed its swipe back. */
    public static final long AWAY_ENDS_ALONE_MS = 10 * 60_000L;

    /** Set on a link entry and cleared when its video binds or the window passes. */
    private static volatile boolean linkPending;
    private static volatile long linkAt;
    /** The video the player had on screen when a warm link arrived, which is not the link's. */
    private static volatile String baseline;
    /** The one video a link opened, uncovered while it is the one on screen. */
    private static volatile String permitted;
    /** Whether that video plays alone: the switch was on at the link entry, and nothing ended it. */
    private static volatile boolean linkAlone;
    /** When TikTok last left the screen, or zero while it is on screen. */
    private static volatile long leftAt;
    /** The main feed's pager that video came up in, the one whose swipe is held. */
    private static volatile WeakReference<View> alonePager = new WeakReference<>(null);

    private static final String MAIN_ACTIVITY = "com.ss.android.ugc.aweme.main.MainActivity";
    private static final String PAGER = "com.ss.android.ugc.aweme.common.widget.VerticalViewPager";

    private static volatile Clock clock = SystemClock::elapsedRealtime;

    interface Clock {
        long now();
    }

    private FeedLock() {
    }

    /**
     * A video's own page, as a link or a share opens it. A photo post's page is one post too, and
     * the mobile site's older links end in .html (m.tiktok.com/v/{id}.html).
     */
    private static final Pattern VIDEO_PATH = Pattern.compile(
            "/(?:share/video/|v/|@[^/]*/(?:video|photo)/)[0-9]{1,20}(?:\\.html)?/?");
    /** The short links a share sheet hands out, which TikTok resolves to one video. */
    private static final Pattern SHORT_PATH = Pattern.compile("/(?:t/)?[A-Za-z0-9]{4,20}/?");
    /** The app's own scheme for a video: aweme://aweme/detail/{id}, also under snssdk{n}. */
    private static final Pattern DETAIL_PATH = Pattern.compile("/detail/[0-9]{1,20}/?");

    /**
     * Whether the switch is on. Paused, the setting answers its unpatched value, off. It also
     * needs the hooks its row's page stands on: without them a switch left on by a restored
     * backup or a repatch would cover the feed with nothing in the app to turn it off.
     */
    public static boolean isOn() {
        return SettingsStatus.blockAuthorEnabled && Settings.FEED_LOCK.get();
    }

    /** Whether the lock wants the feed covered right now: on, and no link video to let through. */
    public static boolean covers() {
        return isOn() && !linkVideoOnScreen();
    }

    private static boolean linkVideoOnScreen() {
        if (linkPending) {
            if (clock.now() - linkAt > LINK_WINDOW_MS) {
                linkPending = false;
            }
            // Until the link's video is known the feed stays covered: a link start has no
            // earlier video to tell it from, so the first one reported is the link's.
        }
        String video = permitted;
        return video != null && video.equals(SessionPlaybackHold.currentAwemeId());
    }

    /**
     * Whether Open shared videos alone is on. Paused, it answers off. It needs the hooks its row
     * stands on too: the pager's touch methods and the player's progress come with the block
     * author patch, and the link's arrival, cold and warm, with Feed tab navigation.
     */
    public static boolean aloneIsOn() {
        return SettingsStatus.blockAuthorEnabled && SettingsStatus.feedNavigationEnabled
                && Settings.SHARED_VIDEO_ALONE.get();
    }

    /** A link started or reached the main activity. The next new video is the link's. */
    public static void noteLinkEntry() {
        boolean alone = aloneIsOn();
        if (!isOn() && !alone) return;
        baseline = SessionPlaybackHold.currentAwemeId();
        permitted = null;
        alonePager = new WeakReference<>(null);
        linkAlone = alone;
        // The link is what brought TikTok back, so the time it was away says nothing about it.
        leftAt = 0;
        linkAt = clock.now();
        linkPending = true;
    }

    /**
     * Whether the video a link opened is the one playing and plays alone, so nothing moves the
     * feed past it: the pager's swipe and Auto-advance both ask. Read on every touch of the
     * feed's pager, so the idle case is one volatile read.
     */
    public static boolean linkVideoAlone() {
        if (!linkAlone || !aloneIsOn()) return false;
        String video = permitted;
        return video != null && video.equals(SessionPlaybackHold.currentAwemeId());
    }

    /** The last of TikTok's screens stopped: the app left the screen. */
    public static void onAppLeft() {
        long now = clock.now();
        leftAt = now == 0 ? 1 : now;
    }

    /**
     * One of TikTok's screens came to the front. Back after a long time away, the reader is
     * here for TikTok rather than the video they were sent, which may still be the one at the
     * top of For You, so the feed swipes again. A short time away (the phone locked, a reply
     * written in another app) keeps the video alone.
     */
    public static void onAppBack() {
        long left = leftAt;
        if (left == 0) return;
        leftAt = 0;
        if (clock.now() - left >= AWAY_ENDS_ALONE_MS) linkAlone = false;
    }

    /** Called with the intent TikTok's main activity is handed while it is already running. */
    public static void onNewIntent(Intent intent) {
        try {
            if (isVideoLink(intent)) noteLinkEntry();
        } catch (Throwable failure) {
            Logger.printException(() -> "The feed lock could not read a new intent", failure);
        }
    }

    /**
     * Whether an intent is a link to one video: a VIEW of a TikTok video or photo post page, a
     * share's short link, or the app's own detail address. Any other intent with an address (a profile, a
     * hashtag, a sound, a search, a web page) opens something that isn't a single video, so it
     * stays covered like the rest of the feed.
     */
    public static boolean isVideoLink(Intent intent) {
        try {
            if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction())) return false;
            Uri data = intent.getData();
            if (data == null || data.getScheme() == null) return false;
            String scheme = data.getScheme().toLowerCase(Locale.ROOT);
            String path = data.getPath();
            if (path == null) return false;
            if (scheme.equals("aweme") || scheme.startsWith("snssdk")) {
                return "aweme".equalsIgnoreCase(data.getHost()) && DETAIL_PATH.matcher(path).matches();
            }
            String host = data.getHost() == null ? "" : data.getHost().toLowerCase(Locale.ROOT);
            if (!(scheme.equals("https") || scheme.equals("http"))
                    || !(host.equals("tiktok.com") || host.endsWith(".tiktok.com"))) {
                return false;
            }
            if (VIDEO_PATH.matcher(path).matches()) return true;
            boolean shortHost = host.startsWith("vm.") || host.startsWith("vt.") || host.startsWith("vn.");
            return (shortHost || path.startsWith("/t/")) && SHORT_PATH.matcher(path).matches();
        } catch (Throwable unreadable) {
            return false;
        }
    }

    /** Called with each video the player reports, after it has become the current one. */
    static void onVideo(String awemeId) {
        if (awemeId == null) return;
        if (linkPending) {
            if (clock.now() - linkAt > LINK_WINDOW_MS) {
                linkPending = false;
                linkAlone = false;
            } else if (!awemeId.equals(baseline)) {
                permitted = awemeId;
                linkPending = false;
                alonePager = new WeakReference<>(null);
                if (linkAlone) Utils.runOnMainThread(FeedLock::pinAlonePager);
                // The panel may be up over the video the link is replacing.
                Utils.runOnMainThread(SessionLockOverlay::sync);
                return;
            }
        }
        String video = permitted;
        if (video != null && !video.equals(awemeId)) {
            permitted = null;
            // Another video by any route (a refresh, Following, a profile, search) is the reader
            // moving on, so a shared video's feed swipes again.
            linkAlone = false;
            Utils.runOnMainThread(SessionLockOverlay::sync);
        }
    }

    /**
     * Whether the panel belongs over the main activity now: the lock is on, no link video is
     * being let through, the main activity is the one in front and the recommendation feed is
     * certainly what it shows. Stricter than the budget's check on purpose: a video opened from
     * a message, a profile or search is a page of the same activity, and the budget's panel
     * covers it, where this one must not.
     */
    static boolean coversFeedOn(Activity main) {
        if (!covers() || main == null) return false;
        Activity front = Utils.getVisibleActivity();
        if (front != null && front != main) return false;
        return FeedVisibility.onFeedTab(main);
    }

    /** Called at the pager's touch methods, so the idle case is a couple of volatile reads. */
    static boolean holdsSwipe(Activity activity) {
        // The swipe stays off through a link's video: it opens that video, and nothing past it.
        return isOn() && activity != null && FeedVisibility.onFeedTab(activity);
    }

    /**
     * The link's video has just come up, so the pager on screen in the main activity is the one
     * it plays in, and the one {@link #holdsAloneSwipe} holds.
     */
    static void pinAlonePager() {
        if (!linkVideoAlone()) return;
        Activity activity = Utils.getVisibleActivity();
        if (activity == null || !MAIN_ACTIVITY.equals(activity.getClass().getName())) return;
        View pager = onScreenPager(activity.findViewById(android.R.id.content));
        if (pager != null) alonePager = new WeakReference<>(pager);
    }

    /**
     * Whether a main feed pager holds its swipe for the shared video playing alone: the one it
     * came up in. With none found then, the first one touched while it plays, which is the one
     * it's on unless a tab was changed first.
     */
    static boolean holdsAloneSwipe(View pager) {
        View pinned = alonePager.get();
        if (pinned == null) {
            alonePager = new WeakReference<>(pager);
            return true;
        }
        return pinned == pager;
    }

    private static View onScreenPager(View view) {
        if (view == null || !view.isShown() || !view.getGlobalVisibleRect(new Rect())) return null;
        if (PAGER.equals(view.getClass().getName())) return view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = onScreenPager(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    static void resetForTests() {
        linkPending = false;
        linkAt = 0;
        baseline = null;
        permitted = null;
        linkAlone = false;
        leftAt = 0;
        alonePager = new WeakReference<>(null);
        clock = SystemClock::elapsedRealtime;
    }

    static void setClockForTests(Clock replacement) {
        clock = replacement == null ? SystemClock::elapsedRealtime : replacement;
    }
}
