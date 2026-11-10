/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.view.View;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.ImmediateAction;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.feedfilter.FeedCapture;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * Starts and stops a {@link FeedCapture}. One tap starts it, the reader scrolls until the feed
 * problem shows, and a tap back here stops it and saves the file beside the other reports. A
 * capture whose file couldn't be written is kept, and the row offers to save it again rather
 * than start over and lose what it caught.
 */
@SuppressWarnings("deprecation")
public final class FeedCapturePreference extends Preference implements ImmediateAction {
    static final String KEY = "action_feed_capture";

    /** One save at a time, on a thread of its own, the way the gate report saves. */
    private static final AtomicBoolean SAVING = new AtomicBoolean();

    /** A stopped capture whose file isn't written yet. It goes once a save succeeds. */
    private static volatile FeedCapture.Recording unsaved;

    public FeedCapturePreference(Context context) {
        super(context);
        setKey(KEY);
        applyState();
        setOnPreferenceClickListener(preference -> {
            if (FeedCapture.isRecording()) {
                unsaved = FeedCapture.stop();
                save(context, this);
            } else if (unsaved != null) {
                save(context, this);
            } else {
                FeedCapture.start();
                Utils.showToastLong(L10n.t(context,
                        "Feed capture started. Scroll until the problem shows up, then come back here to stop."));
            }
            refresh();
            return true;
        });
    }

    /** Every tap acts at once: it starts, stops and saves, or saves again. */
    @Override public boolean actsOnTap() {
        return true;
    }

    /** The name a saved capture gets, dated the way the other reports are. */
    static String fileName() {
        return "metra-feed-capture-" + LogBufferManager.fileTimestamp() + ".txt";
    }

    private static void save(Context context, FeedCapturePreference row) {
        FeedCapture.Recording recording = unsaved;
        if (recording == null) return;
        if (!SAVING.compareAndSet(false, true)) {
            Utils.showToastShort(L10n.t(context, "The feed capture is still being saved"));
            return;
        }
        Context application = context.getApplicationContext();
        Context app = application == null ? context : application;
        boolean started = Utils.runOnOwnThread("Metra-feed-capture", () -> {
            try {
                String saved = GateReportExport.write(app, recording.text(), fileName(), "text/plain");
                if (unsaved == recording) unsaved = null;
                Utils.showToastLong(L10n.f(app,
                        "Feed capture saved to %1$s. Read it before you share it.", saved));
            } catch (IOException | RuntimeException error) {
                Logger.printException(() -> "Could not save the feed capture", error);
                Utils.showToastLong(L10n.t(app,
                        "The feed capture couldn't be saved. Tap the row to try again."));
            } finally {
                SAVING.set(false);
                Utils.runOnMainThread(row::refresh);
            }
        });
        if (!started) {
            SAVING.set(false);
            Utils.showToastShort(L10n.t(context,
                    "Couldn't start saving the feed capture. Try again in a moment."));
        }
    }

    private void refresh() {
        applyState();
        notifyChanged();
    }

    private void applyState() {
        Context context = getContext();
        if (FeedCapture.isRecording()) {
            setTitle(L10n.t(context, "Stop and save the feed capture"));
            setSummary(L10n.f(context,
                    "Recording what the feed filters keep and hide. Tap to stop and save it in %1$s.",
                    GateReportExport.FOLDER));
        } else if (unsaved != null && !SAVING.get()) {
            setTitle(L10n.t(context, "Save the feed capture"));
            setSummary(L10n.t(context, "The last feed capture wasn't saved. Tap to try again."));
        } else {
            setTitle(L10n.t(context, "Capture the feed"));
            setSummary(L10n.t(context,
                    "Tap to start, then scroll until the feed problem shows up and come back here to save it. The file has no captions or names, and videos and creators appear only as short codes. Read it before you share it."));
        }
    }

    /** Forgets a capture left unsaved, so one test's failed save doesn't change the next test's row. */
    static void resetForTests() {
        unsaved = null;
        SAVING.set(false);
        FeedCapture.stop();
    }

    @Override
    protected void onBindView(View view) {
        applyState();
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }
}
