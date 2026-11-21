/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.text.format.Formatter;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Download lists the playable video renditions and offers the audio or cover on their own when
 * the post provides a source. The choice applies to this save. Sizes are estimates from the
 * video model, so showing the menu needs no network request.
 */
final class QualityChoice {
    /** The videos whose choice is on screen. */
    private static final Set<String> CHOOSING = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private static final Object AUDIO_ONLY = new Object();
    private static final Object COVER_ONLY = new Object();

    private QualityChoice() {}

    /** One line of the choice: a rendition, or {@link VideoDownloads#AUTOMATIC}. */
    static final class Option {
        final Object pick;
        final String label;

        Option(Object pick, String label) {
            this.pick = pick;
            this.label = label;
        }
    }

    /**
     * Download on a video, before anything is fetched. True when the choice is up (or already
     * was), or a save of this video is under way, so Download goes no further; false leaves it to
     * the usual save. That's a post with no usable source, a photo post, a phone that
     * still needs the storage permission (TikTok asks for it) or no screen to ask on.
     */
    static boolean offer(Object aweme, Context context) {
        if (!SettingsStatus.advancedDownloadsEnabled || !Settings.DOWNLOAD_ASK_QUALITY.get() || context == null) return false;
        if (android.os.Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return false;
        if (Reflect.property(aweme, "getPhotoModeImageInfo", "photoModeImageInfo") != null) return false;
        String id = Reflect.string(aweme, "getAid", "aid");
        if (id == null) return false;
        if (VideoDownloads.saving(id)) {
            // A picked save is ours whatever else is on, and the usual path can hand an Automatic
            // Download straight to TikTok, which would make a second copy beside it.
            Utils.showToastShort(VideoDownloads.busyMessage(id));
            return true;
        }
        Object video = Reflect.property(aweme, "getVideo", "video");
        if (video == null) return false;
        List<Option> options = options(video, context);
        if (!AudioDownloads.sourceUrls(video).isEmpty()) options.add(new Option(AUDIO_ONLY, L10n.t("Audio only")));
        if (CoverSaver.hasCover(aweme)) options.add(new Option(COVER_ONLY, L10n.t("Cover image only")));
        if (options.isEmpty()) return false;
        Activity activity = Utils.getVisibleActivity();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return false;
        if (!CHOOSING.add(id)) {
            // Said rather than swallowed, so a second tap doesn't read as a broken button.
            Utils.showToastShort(L10n.t("The download menu is still open"));
            return true;
        }
        Utils.runOnMainThread(() -> ask(aweme, id, options));
        return true;
    }

    /**
     * Automatic first, then the playable renditions tallest first, H.264 before HEVC at a height,
     * the larger before the smaller. Two that would read the same are offered once.
     */
    static List<Option> options(Object video, Context context) {
        List<Option> options = new ArrayList<>();
        Object automatic = VideoDownloads.automaticSource(video);
        if (!VideoDownloads.urls(automatic).isEmpty()) {
            options.add(new Option(VideoDownloads.AUTOMATIC, sized(L10n.t("Automatic (TikTok's file)"),
                    QualitySelector.addressSize(automatic), context)));
        }
        Object raw = QualitySelector.rawGears(video);
        if (!(raw instanceof List<?>)) return options;
        boolean needsSound = Boolean.TRUE.equals(Reflect.invoke(video, "hasDashBitrate"))
                && !Settings.DOWNLOAD_WITHOUT_SOUND.get();
        List<Object> gears = new ArrayList<>();
        for (Object gear : (List<?>) raw) {
            if (!QualitySelector.hasUsableAddress(gear) || !QualitySelector.playable(gear)) continue;
            // A picture on its own track is saved only with a sound to put beside it.
            if (needsSound && VideoDownloads.audioUrls(video, gear).isEmpty()) continue;
            gears.add(gear);
        }
        // Not Comparator.comparingInt, which arrived at API 24.
        Collections.sort(gears, (a, b) -> {
            int compare = Integer.compare(QualitySelector.heightOf(b), QualitySelector.heightOf(a));
            if (compare == 0) compare = Integer.compare(QualitySelector.codec(a), QualitySelector.codec(b));
            return compare != 0 ? compare : Long.compare(QualitySelector.size(b), QualitySelector.size(a));
        });
        Set<String> seen = new HashSet<>();
        for (Object gear : gears) {
            int height = QualitySelector.heightOf(gear);
            String shape = (height > 0 ? height + "p" : L10n.t("Unknown resolution"))
                    + (QualitySelector.codec(gear) == 1 ? " HEVC" : " H.264");
            String label = sized(shape, QualitySelector.size(gear), context);
            if (seen.add(label)) options.add(new Option(gear, label));
        }
        return options;
    }

    private static String sized(String what, long bytes, Context context) {
        return bytes > 0 ? L10n.f("%1$s, about %2$s", what, Formatter.formatShortFileSize(context, bytes))
                : L10n.f("%1$s, size unknown", what);
    }

    private static void ask(Object aweme, String id, List<Option> options) {
        try {
            Activity activity = Utils.getVisibleActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
                throw new IllegalStateException("No screen to ask on");
            }
            SettingsUi.syncDarkMode(activity);
            String[] labels = new String[options.size()];
            for (int i = 0; i < labels.length; i++) labels[i] = options.get(i).label;
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(L10n.t("What do you want to download?"))
                    .setItems(labels, (ignored, which) -> {
                        CHOOSING.remove(id);
                        save(aweme, activity, options.get(which).pick);
                    })
                    .setNegativeButton(L10n.t("Cancel"), null)
                    .create();
            // A screen torn down under the dialog, by a theme change say, takes it without a
            // dismiss, which would leave this video's choice open until TikTok restarts.
            Application application = activity.getApplication();
            Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
                @Override public void onActivityDestroyed(Activity destroyed) {
                    if (destroyed == activity) dialog.dismiss();
                }
                @Override public void onActivityCreated(Activity item, Bundle state) { }
                @Override public void onActivityStarted(Activity item) { }
                @Override public void onActivityResumed(Activity item) { }
                @Override public void onActivityPaused(Activity item) { }
                @Override public void onActivityStopped(Activity item) { }
                @Override public void onActivitySaveInstanceState(Activity item, Bundle state) { }
            };
            // Cancel, Back or a tap outside: nothing was fetched, and the next Download asks again.
            dialog.setOnDismissListener(ignored -> {
                application.unregisterActivityLifecycleCallbacks(lifecycle);
                CHOOSING.remove(id);
            });
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            application.registerActivityLifecycleCallbacks(lifecycle);
            try {
                dialog.show();
            } catch (RuntimeException failure) {
                application.unregisterActivityLifecycleCallbacks(lifecycle);
                throw failure;
            }
        } catch (RuntimeException failure) {
            CHOOSING.remove(id);
            Logger.printException(() -> "Could not open the download menu", failure);
            Utils.showToastShort(L10n.t("The download menu couldn't open. Try again."));
        }
    }

    /** The save Download would have started, at the picked rendition. */
    private static void save(Object aweme, Context context, Object pick) {
        if (pick == AUDIO_ONLY) {
            AudioDownloads.startOnly(aweme, context);
            return;
        }
        if (pick == COVER_ONLY) {
            CoverSaver.save(context, aweme);
            return;
        }
        if (!VideoDownloads.savesTheCover()) CoverSaver.beside(context, aweme);
        if (!VideoDownloads.startPicked(aweme, context, pick)) {
            Utils.showToastLong(L10n.t("The video couldn't be saved. Try again, or choose Automatic."));
        }
    }

    static void resetForTests() {
        CHOOSING.clear();
    }
}
