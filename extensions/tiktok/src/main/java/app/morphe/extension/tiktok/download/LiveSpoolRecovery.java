/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Saves the LIVE recording TikTok was closed in the middle of (#37). A swipe out of recents or a
 * force stop runs none of the recorder's code, so its spool sat in the media cache until the
 * day-old sweep threw it away. The next start rewrites it as an MP4 and says so the way a
 * recording cut short by TikTok closing is said.
 *
 * <p>A spool's name carries what the saved file is named after, {@code live-<start ms>-<handle>-}
 * ahead of the cache's own random part. A spool from before that naming has neither, so it's
 * saved under the time it was last written to.
 */
final class LiveSpoolRecovery {
    /** How long a start gets to bring the feed up before the banner picks a screen. */
    static final long SETTLE_MS = 3000;

    interface Publisher {
        /** Rewrites {@code spool} as an MP4 and saves it; the spool is gone once the MP4 is whole. */
        LiveRecorder.Published spool(File spool, boolean flv, String creator, long startedAtMs) throws IOException;

        /** Saves an MP4 whose rewrite finished before TikTok closed. */
        LiveRecorder.Published mp4(File mp4, String creator, long startedAtMs) throws IOException;
    }

    private interface Save {
        LiveRecorder.Published run() throws IOException;
    }

    private static final Pattern NAMED = Pattern.compile("live-(\\d{12,})-([A-Za-z0-9._]*)-(-?\\d+)\\.(flv|ts|mp4)");
    private static final Pattern UNNAMED = Pattern.compile("live--?\\d+\\.(flv|ts)");
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private LiveSpoolRecovery() {
    }

    /** The start of a spool's name, with the handle cut to what a file name can hold. */
    static String spoolPrefix(String creator, long startedAtMs) {
        String handle = creator == null ? "" : creator.replaceAll("[^A-Za-z0-9._]", "");
        if (handle.length() > 40) handle = handle.substring(0, 40);
        return "live-" + startedAtMs + "-" + handle + "-";
    }

    /** The same start for the MP4 a spool is rewritten into, so a cut-off rewrite finds its spool. */
    static String prefixOf(File spool) {
        Matcher named = NAMED.matcher(spool.getName());
        return named.matches() ? "live-" + named.group(1) + "-" + named.group(2) + "-" : "live-";
    }

    /** Once per process: saves what an earlier TikTok left and says so once the feed is up. */
    static void atStart(Context context) {
        if (context == null || !STARTED.compareAndSet(false, true)) return;
        // The folder a recording is saved to is a setting, and settings aren't read this early in
        // the start. The main thread reaches this once the application is up.
        Utils.runOnMainThread(() -> Utils.runOnOwnThread("hushfeed-live-recovery", () -> {
            Context app = Utils.getContext() != null ? Utils.getContext() : context;
            File directory;
            try {
                directory = MediaCache.cacheDirectory(app);
            } catch (IOException unavailable) {
                return;
            }
            List<LiveRecorder.Result> results = recover(directory, new Publisher() {
                @Override
                public LiveRecorder.Published spool(File spool, boolean flv, String creator, long startedAtMs)
                        throws IOException {
                    return LiveRecordingSaver.publish(app, spool, flv, creator, startedAtMs);
                }

                @Override
                public LiveRecorder.Published mp4(File mp4, String creator, long startedAtMs) throws IOException {
                    return LiveRecordingSaver.publishMp4(app, mp4, creator, startedAtMs);
                }
            });
            if (results.isEmpty()) return;
            Utils.runOnMainThreadDelayed(() -> {
                for (LiveRecorder.Result result : results) announce(result);
            }, SETTLE_MS);
        }));
    }

    /**
     * Saves every spool, and every MP4 whose rewrite finished, that {@code directory} holds and no
     * job of this process owns. Nothing is left behind: a spool with nothing playable in it goes
     * without a word, and one that can't be saved goes too, with a message, since every later
     * start would fail on it the same way.
     */
    static List<LiveRecorder.Result> recover(File directory, Publisher publisher) {
        File[] files = directory.listFiles();
        if (files == null) return Collections.emptyList();
        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        Set<String> spools = new HashSet<>();
        for (File file : files) {
            Matcher named = NAMED.matcher(file.getName());
            if (named.matches() && !"mp4".equals(named.group(4))) spools.add(prefixOf(file));
        }
        List<LiveRecorder.Result> results = new ArrayList<>();
        for (File file : files) {
            if (!file.isFile() || MediaCache.isActive(file)) continue;
            Matcher named = NAMED.matcher(file.getName());
            Matcher unnamed = UNNAMED.matcher(file.getName());
            String creator;
            long startedAtMs;
            String kind;
            if (named.matches()) {
                creator = named.group(2);
                startedAtMs = Long.parseLong(named.group(1));
                kind = named.group(4);
            } else if (unnamed.matches()) {
                creator = "";
                startedAtMs = file.lastModified();
                kind = unnamed.group(1);
            } else {
                continue;
            }
            LiveRecorder.Result result;
            if ("mp4".equals(kind)) {
                // Its spool is still here, so the rewrite was cut off; the spool is done again below.
                if (spools.contains(prefixOf(file))) {
                    MediaCache.delete(file);
                    continue;
                }
                result = save(file, () -> publisher.mp4(file, creator, startedAtMs));
            } else if (file.length() < LiveRecorder.MIN_USEFUL_BYTES) {
                MediaCache.delete(file);
                continue;
            } else {
                boolean flv = "flv".equals(kind);
                result = save(file, () -> publisher.spool(file, flv, creator, startedAtMs));
            }
            if (result != null) results.add(result);
        }
        return results;
    }

    private static LiveRecorder.Result save(File file, Save save) {
        try {
            LiveRecorder.Published published = save.run();
            return new LiveRecorder.Result(LiveRecorder.Outcome.SAVED_PARTIAL, LiveRecorder.End.APP_EXIT, 0,
                    published.name, published.saved, null);
        } catch (LiveRemux.NothingRecorded | FlvReader.UnsupportedStream nothing) {
            return null;
        } catch (IOException | RuntimeException failure) {
            Logger.printException(() -> "Could not save the LIVE recording TikTok closed on", failure);
            return new LiveRecorder.Result(LiveRecorder.Outcome.FAILED, LiveRecorder.End.APP_EXIT, 0,
                    null, null, failure.getMessage());
        } finally {
            MediaCache.delete(file);
        }
    }

    static void announce(LiveRecorder.Result result) {
        if (result.outcome == LiveRecorder.Outcome.FAILED) {
            Utils.showToastLong(L10n.t("The LIVE recording TikTok closed on couldn't be saved. Its unfinished file was cleared."));
        } else {
            LiveRecordingSaver.announce(result);
        }
    }

    static void resetForTests() {
        STARTED.set(false);
    }
}
