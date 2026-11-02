/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;
import android.view.View;
import android.view.ViewParent;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Saves a story from a press and hold on it, and from the save button {@link StoryShareSave}
 * puts in its share sheet.
 *
 * A story is an Aweme like anything else in the feed, so the saves beside this one do the work;
 * what stories lack is any way to ask. The registered play area identifies a story viewer;
 * its native child handles the hold and owns the currently selected story. The outer play
 * area can carry a collection, and neighboring pages bind ahead, so the child supplies the
 * model when its timer fires.
 *
 * The gesture is only taken when the switch is on, because holding a story is how TikTok pauses
 * it.
 */
@SuppressWarnings("unused")
public final class StoryDownloads {
    static final String HOOK_FAMILY = "story saves";
    private static final Set<String> ACTIVE = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    /** The model each play area binds, and the view each play area put on screen. */
    private static final Map<Object, Object> STORIES = new WeakHashMap<>();
    private static final Map<View, WeakReference<Object>> OWNERS = new WeakHashMap<>();

    private StoryDownloads() {
    }

    /** Called as a play area binds a story, with the component doing the binding. */
    public static void recordStory(Object component, int position, Object aweme) {
        if (component == null || aweme == null) return;
        synchronized (STORIES) {
            STORIES.put(component, aweme);
        }
    }

    /**
     * Called with a play area and the view it created. A view holds one long click listener, so
     * this only takes it when the feature is on; turning the switch on takes effect the next time
     * the story viewer opens.
     */
    /** The views whose long press is ours, so only those are handed back. */
    private static final java.util.Map<View, Boolean> TAKEN =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    public static void attachPlayArea(Object component, View view) {
        if (component == null || view == null) return;
        try {
            if (!enabled()) {
                // Ours goes back only if it was ours. Holding is how TikTok pauses a story,
                // and clearing a listener we never set would take that away.
                if (TAKEN.remove(view) != null) view.setOnLongClickListener(null);
                // The component holds the view, so an entry left here keeps its own key
                // reachable and the weak map never lets go of it.
                synchronized (OWNERS) {
                    OWNERS.remove(view);
                }
                return;
            }
            synchronized (OWNERS) {
                // The native component owns its view. A strong value would keep this map's
                // weak key alive through that component after the story viewer is closed.
                OWNERS.put(view, new WeakReference<>(component));
            }
            TAKEN.put(view, Boolean.TRUE);
            view.setOnLongClickListener(anchor -> {
                if (!enabled()) return false;
                return save(anchor, storyFor(anchor));
            });
        } catch (RuntimeException exception) {
            Logger.printException(() -> "Could not attach the story save", exception);
        }
    }

    static boolean enabled() {
        return SettingsStatus.advancedDownloadsEnabled && Settings.SAVE_STORY.get();
    }

    /** Called by the native story child's completed long-press timer. */
    public static boolean onNativeLongPress(View view) {
        if (view == null || !enabled()) return false;
        for (View ancestor = view; ancestor != null; ) {
            boolean owned;
            synchronized (OWNERS) {
                WeakReference<Object> owner = OWNERS.get(ancestor);
                owned = owner != null && owner.get() != null;
            }
            if (owned) return save(view, heldStory(view));
            ViewParent parent = ancestor.getParent();
            ancestor = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    /**
     * TikTok's own names, which R8 leaves alone on 46.2.3, 47.0.3 and 47.1.3: the story hold's
     * monitor is the cell component implementing this ability, and the cell it pauses is bound
     * to a VideoItemParams. The fields between them are renamed with every build (46.2.3's
     * view.LLJIJIL, then .LLJIJIL and .LL, exist on neither 47.x build), so those are found by
     * what they hold.
     */
    static final String MONITOR_ABILITY =
            "com.ss.android.ugc.aweme.feed.collection.sub.ability.LongPressMonitorAbility";
    static final String CELL_PARAMS = "com.ss.android.ugc.aweme.feed.model.VideoItemParams";
    /** Two fields down from the monitor on every declared build: its Assem state, then the bound item. */
    static final int PARAMS_DEPTH = 2;

    /** The story the pressed view's monitor is about to pause, or null when it can't be told. */
    static Object heldStory(View view) {
        Object monitor = null;
        for (Object value : fieldValues(view)) {
            if (implementsNamed(value.getClass(), MONITOR_ABILITY)) {
                monitor = value;
                break;
            }
        }
        if (monitor == null) {
            HookStatus.missingMember(HOOK_FAMILY, "field", view.getClass().getName(), "a LongPressMonitorAbility");
            return null;
        }
        Object params = boundParams(monitor);
        if (params == null) {
            HookStatus.missingMember(HOOK_FAMILY, "field", monitor.getClass().getName(),
                    "one VideoItemParams within " + PARAMS_DEPTH + " fields");
            return null;
        }
        HookStatus.bound(HOOK_FAMILY, "story hold");
        return Reflect.required(params, "getAweme", HOOK_FAMILY);
    }

    /**
     * The VideoItemParams the monitor's cell is bound to, searched level by level through TikTok's
     * own objects. Two at the shallowest level naming different stories is an answer nobody can
     * trust, so it is none.
     */
    public static Object boundParams(Object monitor) {
        Set<Object> seen = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        seen.add(monitor);
        List<Object> level = Collections.singletonList(monitor);
        for (int depth = 0; depth < PARAMS_DEPTH && !level.isEmpty(); depth++) {
            List<Object> next = new ArrayList<>();
            Object found = null;
            Object story = null;
            for (Object holder : level) {
                for (Object value : fieldValues(holder)) {
                    if (!seen.add(value)) continue;
                    if (CELL_PARAMS.equals(value.getClass().getName())) {
                        Object aweme = Reflect.invoke(value, "getAweme");
                        if (found != null && aweme != story) return null;
                        found = value;
                        story = aweme;
                    } else if (tiktokObject(value)) {
                        next.add(value);
                    }
                }
            }
            if (found != null) return found;
            level = next;
        }
        return null;
    }

    /** Whether a value is one of TikTok's own objects rather than a platform, JDK or view type. */
    private static boolean tiktokObject(Object value) {
        Class<?> type = value.getClass();
        if (type.isArray() || value instanceof View) return false;
        String name = type.getName();
        return name.startsWith("X.") || name.startsWith("com.ss.") || name.startsWith("com.bytedance.");
    }

    /**
     * Every non-null instance field value of the object, its class first and then each superclass,
     * stopping at the first platform class: a View's own fields are Android's, and hidden ones.
     */
    private static List<Object> fieldValues(Object target) {
        List<Object> values = new ArrayList<>();
        for (Class<?> type = target.getClass(); type != null && !platformClass(type); type = type.getSuperclass()) {
            for (java.lang.reflect.Field field : type.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(target);
                    if (value != null) values.add(value);
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // A field the runtime won't open is one this search can do without.
                }
            }
        }
        return values;
    }

    private static boolean platformClass(Class<?> type) {
        String name = type.getName();
        return name.startsWith("android.") || name.startsWith("androidx.") || name.startsWith("java.")
                || name.startsWith("kotlin.");
    }

    private static boolean implementsNamed(Class<?> type, String interfaceName) {
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            for (Class<?> implemented : at.getInterfaces()) {
                if (interfaceName.equals(implemented.getName())) return true;
            }
        }
        return false;
    }

    /** The story the pressed view's own play area last bound. */
    static Object storyFor(View view) {
        Object component;
        synchronized (OWNERS) {
            WeakReference<Object> owner = OWNERS.get(view);
            component = owner == null ? null : owner.get();
        }
        if (component == null) return null;
        synchronized (STORIES) {
            return STORIES.get(component);
        }
    }

    /** @return true when the press was used, so TikTok's own hold keeps working when it was not. */
    static boolean save(View anchor, Object aweme) {
        if (anchor == null) return false;
        return saveStory(anchor.getContext(), aweme);
    }

    /**
     * The save itself, for a hold and for the save button {@link StoryShareSave} puts in a
     * story's share sheet, which has a Context and no view of the story.
     *
     * @return true when the save was taken on or the reader was told why it couldn't be.
     */
    static boolean saveStory(Context context, Object aweme) {
        if (context == null) return false;
        if (aweme == null) {
            Utils.showToastShort(L10n.t("Open the story again and try once more"));
            return true;
        }
        if (android.os.Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Utils.showToastLong(L10n.t("Allow storage for TikTok in Android settings to save stories"));
            return false;
        }

        String id = Reflect.string(aweme, "getAid", "aid");
        if (id == null) {
            Utils.showToastShort(L10n.t("This story isn't available to save"));
            return true;
        }
        String key = "story " + id;
        if (ACTIVE.contains(id)) {
            Utils.showToastShort(MediaJobScheduler.busyMessage(key));
            return true;
        }

        // The quality download handles the video, its sound and its subtitles the way the
        // settings ask for. It declines when there is nothing for it to do differently, and
        // then the story is fetched from whatever address it carries.
        // The same hand-off the save button gets, so one setting covers both.
        if (ExternalDownloader.handOff(aweme, context)) return true;
        if (VideoDownloads.start(aweme, context, false)) return true;

        List<List<String>> photos = OriginalPhotos.sources(aweme);
        Object storyVideo = Reflect.property(aweme, "getVideo", "video");
        // On Automatic the quality download declines, and the addresses below are the copy the
        // player streamed. The tallest rendition comes first when there is one (#155).
        if (photos.isEmpty() && VideoDownloads.startStory(aweme, context, VideoDownloads.storyGear(storyVideo))) {
            return true;
        }
        VideoSource source = photos.isEmpty() ? videoSource(storyVideo) : VideoSource.NONE;
        List<String> video = source.urls;
        if (photos.isEmpty() && video.isEmpty()) {
            Utils.showToastShort(L10n.t("This story isn't available to save"));
            return true;
        }

        List<List<String>> photoSnapshot = snapshot(photos);
        List<String> videoSnapshot = List.copyOf(video);
        String audioName = null;
        if (AudioDownloads.enabled()) {
            try { audioName = DownloadFilenameFormatter.formatSelectedAudioName(aweme); }
            catch (RuntimeException exception) {
                Logger.printException(() -> "Could not work out the story sound name", exception);
            }
        }

        Context app = context.getApplicationContext();
        String path, videoName;
        List<String> photoNames = new ArrayList<>();
        try {
            path = DownloadFilenameFormatter.destinationPath(aweme, !photoSnapshot.isEmpty());
            videoName = photoSnapshot.isEmpty() ? DownloadFilenameFormatter.formatSelectedVideoName(aweme) : null;
            for (int i = 0; i < photoSnapshot.size(); i++) {
                photoNames.add(DownloadFilenameFormatter.formatOriginalPhotoName(aweme, i + 1, "tmp"));
            }
        } catch (RuntimeException exception) {
            Logger.printException(() -> "Could not start the story download", exception);
            Utils.showToastLong(L10n.t("The story couldn't be saved. Try again."));
            return true;
        }
        if (!ACTIVE.add(id)) {
            Utils.showToastShort(MediaJobScheduler.busyMessage(key));
            return true;
        }
        String capturedAudioName = audioName;
        boolean stamped = source.stamped;
        // The photos' row follows the job from the moment it is accepted; a video has none.
        SaveProgress progress = SaveProgress.queued(Math.max(1, photoSnapshot.size()), false);
        // A video story's sound is a second file in the gallery, though the row counts one.
        int files = photoSnapshot.isEmpty() && capturedAudioName != null ? 2 : progress.total();
        MediaJobScheduler.Job job = progress.submit("story", key, files, () -> {
            try {
                if (photoSnapshot.isEmpty()) {
                    saveVideo(app, videoName, path, videoSnapshot, capturedAudioName, stamped);
                } else {
                    savePhotos(app, photoNames, path, photoSnapshot, progress);
                }
            } catch (IOException | RuntimeException exception) {
                Logger.printException(() -> "Story download failed", exception);
                Utils.showToastLong(L10n.t("The story couldn't be saved. Try again."));
            }
        }, () -> ACTIVE.remove(id));
        if (job == null) return false;
        String saying = L10n.t("Saving the story");
        progress.acknowledge(saying, saying);
        return true;
    }

    /**
     * Where a story's video is fetched from, best first, and whether that's TikTok's watermarked
     * copy. The clean address comes first: Remove watermark fills it from the copy the player
     * streams when TikTok sent none. Then that streamed copy, which carries no watermark either.
     * TikTok's download address is the stamped copy, so a story falls back to it only when it has
     * nothing else, and the notice says so. A feed video keeps {@link VideoDownloads#sourceUrls}'
     * order, where the download address is the full file and the streamed one can be smaller.
     */
    static VideoSource videoSource(Object video) {
        List<String> clean = VideoDownloads.urls(
                Reflect.property(video, "getDownloadNoWatermarkAddr", "downloadNoWatermarkAddr"));
        if (!clean.isEmpty()) return new VideoSource(clean, false);
        List<String> streamed = VideoDownloads.urls(Reflect.property(video, "getPlayAddr", "playAddr"));
        if (!streamed.isEmpty()) return new VideoSource(streamed, false);
        List<String> stamped = VideoDownloads.urls(Reflect.property(video, "getDownloadAddr", "downloadAddr"));
        return stamped.isEmpty() ? VideoSource.NONE : new VideoSource(stamped, true);
    }

    /** A story video's addresses and whether they are TikTok's watermarked copy. */
    static final class VideoSource {
        static final VideoSource NONE = new VideoSource(Collections.<String>emptyList(), false);

        final List<String> urls;
        final boolean stamped;

        VideoSource(List<String> urls, boolean stamped) {
            this.urls = urls;
            this.stamped = stamped;
        }
    }

    private static void saveVideo(Context app, String name, String path, List<String> urls, String audioName,
            boolean stamped) throws IOException {
        MediaBudget.checkDiskSpace(app.getCacheDir(), -1L);
        File temp = MediaCache.createTempFile(app, "story-", ".mp4");
        try {
            RemoteMedia.fetch(urls, temp, RemoteMedia.Kind.VIDEO);
            MediaFileWriter.Saved saved = MediaFileWriter.publishForResult(app, temp,
                    name, "video/mp4", path, true);
            // The sound keeps to a toast: its banner went up first and the story's, a tick
            // later, took it down before anyone saw it (refutation review of 3d5395f2).
            if (audioName != null) saveSound(app, audioName, temp, path);
            SaveNotice.saved(stamped
                    ? L10n.f("TikTok only had its watermarked copy of this story, so that copy was saved to %1$s", path)
                    : L10n.f("Story saved to %1$s", path), saved);
        } finally {
            if (!MediaCache.delete(temp)) Logger.printInfo(() -> "Could not remove story temporary file");
        }
    }

    /**
     * The sound beside a story that is already published. A sound stopped for space or time is
     * the sound's failure alone: letting it reach the job said the story had failed, and a retry
     * made a second copy of it. It goes beside the story, in {@code storyPath}.
     */
    static void saveSound(Context app, String audioName, File source, String storyPath) {
        try {
            AudioDownloads.write(app, audioName, source, storyPath, false);
        } catch (MediaBudget.StopException refusal) {
            Logger.printException(() -> "Story sound save stopped", refusal);
            Utils.showToastLong(L10n.t("The sound couldn't be saved. Try again."));
        }
    }

    private static void savePhotos(Context app, List<String> names, String path, List<List<String>> photos,
            SaveProgress progress) {
        List<File> temporary = new ArrayList<>();
        // The banner's Open lands on the newest photo, which is where the gallery puts the rest.
        MediaFileWriter.Saved[] last = {null};
        try {
            // From three photos up a row counts them and offers Cancel; a photo that fails is
            // skipped and the rest still land. Say what did: a story that stopped part way has
            // files in the gallery already, and "nothing was saved" would send the reader back
            // for duplicates.
            SaveProgress.Outcome outcome = progress.run(index -> {
                MediaBudget.checkDiskSpace(app.getCacheDir(), -1L);
                File temp = MediaCache.createTempFile(app, "story-photo-", ".tmp");
                temporary.add(temp);
                String extension = PhotoToJpeg.convert(app, temp,
                        RemoteMedia.fetch(photos.get(index), temp, RemoteMedia.Kind.IMAGE));
                String mime = "jpg".equals(extension) ? "image/jpeg" : "image/" + extension;
                String named = names.get(index);
                String name = named.substring(0, named.lastIndexOf('.') + 1) + extension;
                last[0] = MediaFileWriter.publishForResult(app, temp, name, mime, path, false);
            });
            if (outcome.saved == 0 && outcome.cancelled == 0) {
                Utils.showToastLong(L10n.f("Saved %1$s of %2$s photos before the story failed",
                        String.valueOf(0), String.valueOf(photos.size())));
            } else {
                SaveNotice.saved(SaveProgress.message(outcome, L10n.f("Story saved to %1$s", path)), last[0]);
            }
        } catch (RuntimeException exception) {
            Logger.printException(() -> "Story photo save failed", exception);
            Utils.showToastLong(L10n.f("Saved %1$s of %2$s photos before the story failed",
                    String.valueOf(0), String.valueOf(photos.size())));
        } finally {
            for (File file : temporary) {
                if (!MediaCache.delete(file)) Logger.printInfo(() -> "Could not remove story temporary file");
            }
        }
    }

    private static List<List<String>> snapshot(List<List<String>> photos) {
        List<List<String>> copy = new ArrayList<>();
        for (List<String> photo : photos) copy.add(List.copyOf(photo));
        return List.copyOf(copy);
    }
}
