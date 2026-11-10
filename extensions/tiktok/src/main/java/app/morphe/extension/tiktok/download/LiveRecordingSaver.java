/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Wires a LIVE recording to Hushfeed's own saving: the spool lives in the media cache, the finished
 * MP4 goes where videos are saved with the same publish step, and the end is announced the way a
 * download is, with Open on the banner.
 */
public final class LiveRecordingSaver {
    private LiveRecordingSaver() {
    }

    /** Where recordings are saved, which is where videos are. */
    public static String folder() {
        return DownloadsPatch.getVideoDownloadPath();
    }

    /** Builds a recorder for {@code target}; it records once started. */
    public static LiveRecorder create(Context context, LiveRecorder.Target target, LiveRecorder.Listener listener)
            throws IOException {
        Context app = context.getApplicationContext() == null ? context : context.getApplicationContext();
        boolean flv = target.transport == LiveRecorder.Transport.FLV;
        // Named for the creator and the start, so a spool TikTok closed on can still be saved under
        // the right name at the next start (LiveSpoolRecovery).
        File spool = MediaCache.createTempFile(app,
                LiveSpoolRecovery.spoolPrefix(target.creator, System.currentTimeMillis()), flv ? ".flv" : ".ts");
        LiveRecorder.Source source = flv
                ? new LiveStreamSources.Flv(LiveStreamSources.NETWORK, target.url)
                : new LiveStreamSources.Hls(LiveStreamSources.NETWORK, target.url);
        LiveRecorder.Finisher finisher = (file, wanted, startedAt) -> finish(app, file, wanted, startedAt);
        return new LiveRecorder(target, spool, source, finisher, result -> {
            MediaCache.delete(spool);
            listener.onFinished(result);
        });
    }

    private static LiveRecorder.Published finish(Context app, File spool, LiveRecorder.Target target, long startedAtMs)
            throws IOException {
        return publish(app, spool, target.transport == LiveRecorder.Transport.FLV, target.creator, startedAtMs);
    }

    /** Rewrites {@code spool} as an MP4 and saves it where videos go. */
    static LiveRecorder.Published publish(Context app, File spool, boolean flv, String creator, long startedAtMs)
            throws IOException {
        File mp4 = MediaCache.createTempFile(app, LiveSpoolRecovery.prefixOf(spool), ".mp4");
        try {
            if (flv) {
                LiveRemux.fromFlv(spool, new LiveRemux.MuxerWriter(mp4));
            } else {
                LiveRemux.fromTransportStream(spool, mp4);
            }
            // The rewrite is whole; the raw copy is only in the way of the publish copy now.
            MediaCache.delete(spool);
            return publishMp4(app, mp4, creator, startedAtMs);
        } finally {
            MediaCache.delete(mp4);
        }
    }

    static LiveRecorder.Published publishMp4(Context app, File mp4, String creator, long startedAtMs)
            throws IOException {
        MediaFileWriter.Saved saved = MediaFileWriter.publishForResult(app, mp4, fileName(creator, startedAtMs),
                "video/mp4", folder(), true, MediaBudget.MAX_LIVE_RECORDING_BYTES);
        return new LiveRecorder.Published(saved.name, saved);
    }

    /** {@code live_<handle>_<yyyyMMdd>_<HHmmss>.mp4}, with the handle cleaned for a file name. */
    static String fileName(String creator, long startedAtMs) {
        String handle = creator == null ? "" : creator.replaceAll("[^A-Za-z0-9._-]", "");
        if (handle.length() > 40) handle = handle.substring(0, 40);
        String when = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(startedAtMs));
        return "live_" + (handle.isEmpty() ? "" : handle + "_") + when + ".mp4";
    }

    /** Tells the viewer how a recording ended. Safe to call from any thread. */
    public static void announce(LiveRecorder.Result result) {
        try {
            String folder = folder();
            switch (result.outcome) {
                case SAVED:
                    saved(L10n.f("LIVE recording saved to %1$s", folder), result);
                    break;
                case SAVED_PARTIAL:
                    saved(partialText(result.end, folder), result);
                    break;
                case FAILED:
                    Utils.showToastLong(L10n.t("The recording couldn't be saved. Try again."));
                    break;
                case NOTHING_SAVED:
                default:
                    Utils.showToastLong(nothingMessage(result.end));
                    break;
            }
        } catch (RuntimeException failure) {
            Logger.printException(() -> "Could not announce the end of a LIVE recording", failure);
        }
    }

    private static void saved(String message, LiveRecorder.Result result) {
        if (result.saved instanceof MediaFileWriter.Saved) {
            SaveNotice.saved(message, (MediaFileWriter.Saved) result.saved);
        } else {
            Utils.showToastLong(message);
        }
    }

    /** Why a saved recording stops short, one translatable sentence for each reason. */
    static String partialText(LiveRecorder.End end, String folder) {
        switch (end) {
            case DISCONNECTED:
                return L10n.f("The LIVE ended or the connection dropped. What was recorded is saved to %1$s", folder);
            case STORAGE:
                return L10n.f("The phone is running out of room. What was recorded is saved to %1$s", folder);
            case SIZE_LIMIT:
                return L10n.f("The recording reached its size limit and was saved to %1$s", folder);
            case APP_EXIT:
                return L10n.f("TikTok closed. What was recorded is saved to %1$s", folder);
            default:
                return L10n.f("Recording stopped early. What was recorded is saved to %1$s", folder);
        }
    }

    static String nothingMessage(LiveRecorder.End end) {
        switch (end) {
            case UNSUPPORTED:
                return L10n.t("This LIVE's stream can't be saved");
            case STORAGE:
                return L10n.t("There isn't enough room on the phone to record");
            default:
                return L10n.t("Nothing was recorded");
        }
    }
}
