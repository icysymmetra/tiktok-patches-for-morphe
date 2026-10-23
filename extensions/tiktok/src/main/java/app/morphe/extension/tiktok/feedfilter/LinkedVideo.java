/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;

import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.wellbeing.FeedLock;

import com.ss.android.ugc.aweme.feed.model.Aweme;

/**
 * The video a link opened. TikTok answers a video link with a For You response that holds that
 * one video, so a feed rule that took it out (a video already seen, a blocked word, a photo post)
 * left TikTok an empty page and its error screen instead of the video (#117). The video is the
 * reader's own pick, so the feed rules let it through, as they do the reader's own posts.
 *
 * <p>A full link names its video and only that one is let through. A share's short link names
 * none until TikTok resolves it, so the lone video of the first one-video response after it is
 * taken as the link's (a page of the feed always carries several), unless it's an ad.
 */
public final class LinkedVideo {
    /** How long after a link its video is still let through, covering a slow cold start. */
    static final long WINDOW_MS = 60_000L;

    /** The list a link's video arrives in. Cached and offline lists replay older pages. */
    static final String RESPONSE_SOURCE = "FeedItemList:response";

    /** A launch ad can arrive on its own too, so a short link never vouches for one. */
    private static final String ADS_REASON = "AdsFilter";

    /** The post id in a TikTok video or photo page, or in the app's own detail address. */
    private static final Pattern VIDEO_ID = Pattern.compile(
            "/(?:share/video/|v/|@[^/]*/(?:video|photo)/|detail/)([0-9]{1,20})(?:\\.html)?/?");

    /** The last link, replaced whole so a reader never sees half of one. */
    private static final AtomicReference<Link> last = new AtomicReference<>();

    private static volatile Clock clock = SystemClock::elapsedRealtime;

    interface Clock {
        long now();
    }

    private static final class Link {
        final long at;
        /** The video the link named, or null for a short link until its video turns up. */
        final String id;

        Link(long at, String id) {
            this.at = at;
            this.id = id;
        }
    }

    private LinkedVideo() {
    }

    /**
     * Called first thing in the main activity's onCreate. A start Android restores, or one
     * relaunched from the recent apps, carries the old link again, which the reader isn't
     * opening now.
     */
    public static void onCreate(Activity activity, Bundle savedState) {
        try {
            if (activity == null || savedState != null) return;
            Intent intent = activity.getIntent();
            if (intent != null && (intent.getFlags() & Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) != 0) return;
            note(intent);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not read the link TikTok started with", failure);
        }
    }

    /** Called first thing in the main activity's onNewIntent, a link reaching the running app. */
    public static void onNewIntent(Intent intent) {
        try {
            note(intent);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not read the link TikTok was handed", failure);
        }
    }

    static void note(Intent intent) {
        if (!FeedLock.isVideoLink(intent)) return;
        Uri data = intent.getData();
        String path = data == null ? null : data.getPath();
        Matcher id = path == null ? null : VIDEO_ID.matcher(path);
        String named = id != null && id.matches() ? id.group(1) : null;
        last.set(new Link(clock.now(), named));
        Logger.printDebug(() -> "Linked video: a link opened " + (named == null ? "a short link" : "video " + named));
    }

    /**
     * Whether {@code item}, one of {@code size} videos in a list from {@code source}, is the one a
     * recent link opened, so {@code reason} leaves it in.
     */
    static boolean spares(String source, Aweme item, int size, String reason) {
        if (item == null || !RESPONSE_SOURCE.equals(source)) return false;
        String aid = item.getAid();
        if (aid == null || aid.isEmpty()) return false;
        while (true) {
            Link link = last.get();
            if (link == null || clock.now() - link.at > WINDOW_MS) return false;
            if (link.id != null) {
                if (!link.id.equals(aid)) return false;
            } else {
                if (size != 1 || ADS_REASON.equals(reason)) return false;
                // Kept as the link's video from now on, so a later one-video response isn't.
                if (!last.compareAndSet(link, new Link(link.at, aid))) continue;
            }
            Logger.printDebug(() -> "Linked video: kept " + aid + ", which " + reason + " would have hidden");
            return true;
        }
    }

    static void setClockForTests(Clock testClock) {
        clock = testClock;
    }

    static void forgetForTests() {
        last.set(null);
        clock = SystemClock::elapsedRealtime;
    }
}
