/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;
import android.os.SystemClock;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.L10n;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class OriginalPhotos {
    private static final Set<String> ACTIVE = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    private OriginalPhotos() {}

    public static boolean start(Object aweme, Context context) {
        // Handing the link to another app replaces the save, so it comes before all of it.
        if (ExternalDownloader.handOff(aweme, context)) return true;
        // With Ask for the quality on each save on, the choice comes before any of the save, the
        // cover included, so Cancel leaves nothing behind.
        if (QualityChoice.offer(aweme, context)) return true;
        // Its own small save, beside whichever save the video gets. A photo post has none. With
        // the already-saved check on, the video's save makes it once it knows the video is new
        // or the choice says Save again, so Open doesn't leave a second cover behind.
        if (!VideoDownloads.savesTheCover()) CoverSaver.beside(context, aweme);
        if (VideoDownloads.start(aweme, context)) return true;
        // Nothing here is handling the video, so the sound has to fetch its own bytes. When
        // the quality download above took it, it saved the sound from what it already had.
        AudioDownloads.start(aweme, context);
        return savePhotos(aweme, context, null);
    }

    /**
     * TikTok's own photo save job, which every photo save on 47.0.3 runs and the start above
     * never sees: "Download image" in the sheet a single photo asks with, a single photo saved
     * without asking, the photos picked in TikTok's selection sheet, and a TikTok Now save.
     * {@code indices} are the photos it was asked for, counted from 0.
     *
     * <p>{@code video} is not "this is a video save": inside the job it picks the live-photo
     * video over the still for the items that have one ({@code livePhotoStruct.videoModel}),
     * and the picker's Download sets it for every save, plain stills included, which is how
     * two picked stills came out as TikTok's own files with the switch on (S22, 2026-09-23).
     * So it only stands aside where it matters: a chosen photo that really is a live photo
     * would come down as a video, and that save stays TikTok's whole.
     *
     * @return true when Hushfeed took the save, so TikTok's own must not run.
     */
    public static boolean startPhotos(Object aweme, Set<?> indices, boolean video) {
        if (video && anyChosenIsLive(aweme, indices)) return false;
        // With Save photo posts as a video on, a post with several photos asks first.
        if (SlideshowVideo.offer(aweme, indices)) return true;
        return savePhotos(aweme, Utils.getContext(), indices);
    }

    /**
     * The separate "Download video" choice that compiles a still photo and its sound into an
     * MP4. This runs before TikTok starts that job. Its callback expects a video path and opens
     * video sharing, so the image job never calls it, including on failure or cancellation.
     * Live Photo video choices stay native. This entry carries no selected-photo indices.
     *
     * <p>With Save photo posts as a video on, Hushfeed makes that video itself, without the
     * logo and end card TikTok's render adds. Otherwise Download original photos saves the image.
     */
    public static boolean startImageAsVideo(Object aweme) {
        boolean asVideo = SlideshowVideo.enabled();
        if (!asVideo && !Settings.DOWNLOAD_ORIGINAL_PHOTOS.get()) return false;
        Object info = Reflect.property(aweme, "getPhotoModeImageInfo", "photoModeImageInfo");
        // Keep the null-info video path outside this Photo Mode conversion hook.
        if (info == null) return false;
        Object raw = Reflect.property(info, "getImageList", "imageList");
        if (raw instanceof List<?> && anyChosenIsLive(aweme, null)) return false;
        if (asVideo) return SlideshowVideo.start(aweme, null);
        return savePhotos(aweme, Utils.getContext(), null, true);
    }

    /**
     * "Save photos" in the photos-or-video question. The question already took the save from
     * TikTok, so the picked photos go out as originals whatever Download original photos says.
     */
    static void savePicked(Object aweme, Set<?> indices) {
        savePhotos(aweme, Utils.getContext(), indices, false, true);
    }

    /**
     * Whether any photo the save was asked for carries a live-photo video. An unreadable post
     * answers yes: with the flag set, guessing "all stills" could swallow a video save.
     */
    static boolean anyChosenIsLive(Object aweme, Set<?> indices) {
        Object info = Reflect.property(aweme, "getPhotoModeImageInfo", "photoModeImageInfo");
        Object raw = Reflect.property(info, "getImageList", "imageList");
        if (!(raw instanceof List<?>)) return true;
        List<?> photos = (List<?>) raw;
        for (int position : positions(indices, photos.size())) {
            Object live = Reflect.property(photos.get(position), "getLivePhotoStruct", "livePhotoStruct");
            if (live != null && Reflect.property(live, "getVideoModel", "videoModel") != null) return true;
        }
        return false;
    }

    /** The positions to save, in the post's order: all of them for null, else the ones asked for. */
    static List<Integer> positions(Set<?> indices, int count) {
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            if (indices == null || asked(indices, i)) result.add(i);
        }
        return result;
    }

    private static boolean asked(Set<?> indices, int position) {
        for (Object index : indices) {
            if (index instanceof Number && ((Number) index).longValue() == position) return true;
        }
        return false;
    }

    private static boolean savePhotos(Object aweme, Context context, Set<?> indices) {
        return savePhotos(aweme, context, indices, false);
    }

    private static boolean savePhotos(Object aweme, Context context, Set<?> indices, boolean conversion) {
        return savePhotos(aweme, context, indices, conversion, false);
    }

    /**
     * {@code asked} is a save the photos-or-video question already took from TikTok: it runs
     * whatever the switch says, and a failure says so, since there's no native save behind it.
     */
    private static boolean savePhotos(Object aweme, Context context, Set<?> indices, boolean conversion,
            boolean asked) {
        if (!asked && !Settings.DOWNLOAD_ORIGINAL_PHOTOS.get()) return false;
        boolean owned = conversion || asked;
        if (context == null) return failedConversion(owned);
        if (Reflect.property(aweme, "getPhotoModeImageInfo", "photoModeImageInfo") == null) return failedConversion(asked);
        if (android.os.Build.VERSION.SDK_INT < 29
                && context.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) return failedConversion(owned);
        List<List<String>> photos = sources(aweme);
        if (photos.isEmpty()) {
            if (conversion) {
                Utils.showToastShort(L10n.t("The original photos aren't available. No video was saved."));
            } else if (asked) {
                Utils.showToastLong(L10n.t("None of the photos could be saved. Try again."));
            } else {
                Utils.showToastShort(L10n.t(
                        "The original photos aren't available, so TikTok's own save runs instead"));
            }
            return owned;
        }
        List<Integer> chosen = positions(indices, photos.size());
        // Asked for photos this post doesn't have: TikTok's own save says what it makes of that.
        if (chosen.isEmpty()) return failedConversion(owned);
        String id = Reflect.string(aweme, "getAid", "aid");
        if (id == null) return failedConversion(owned);
        List<List<String>> photoSnapshot = snapshot(photos);
        String path = DownloadFilenameFormatter.destinationPath(aweme, true);
        List<String> names = new ArrayList<>();
        for (int i = 0; i < photos.size(); i++) {
            names.add(DownloadFilenameFormatter.formatOriginalPhotoName(aweme, i + 1, "tmp"));
        }
        Retry run = new Retry(id, SignedInUser.id(), photoSnapshot, List.copyOf(names), path, chosen,
                SystemClock.elapsedRealtime());
        // The scheduler already reports a refusal. A requested image save must not become a
        // native converted video merely because the image queue is full.
        return queue(context.getApplicationContext(), run, true) || owned;
    }

    /**
     * Queues the save of {@code run}'s photos, in the post's order. False only when the media
     * queue refused it, which the queue has already said; a save of the same post still running
     * says so and counts as taken. With Check for already-saved videos on, a post saved here
     * before is offered Open or Save again first ({@code check}, false for Save again and Retry),
     * and the first photo that lands is recorded under the post's id.
     */
    private static boolean queue(Context app, Retry run, boolean check) {
        String key = jobKey(run.id);
        if (!ACTIVE.add(run.id)) {
            Utils.showToastShort(MediaJobScheduler.busyMessage(key));
            return true;
        }
        List<Integer> chosen = run.positions;
        // Every original-photo save has a file count and Cancel, including one chosen still.
        SaveProgress progress = SaveProgress.queuedFiles(chosen.size());
        boolean checkSaved = Settings.CHECK_SAVED_VIDEOS.get();
        // Read as the save is accepted: a forget in settings while it runs leaves it unrecorded.
        long archiveGeneration = SavedVideoArchive.generation();
        AtomicBoolean asking = new AtomicBoolean();
        MediaJobScheduler.Job job = progress.submit("original photos", key, () -> {
            // The banner's Open lands on the newest photo, which is where the gallery puts the rest.
            MediaFileWriter.Saved[] last = {null};
            // The record's Open goes to the first photo that landed.
            MediaFileWriter.Saved[] first = {null};
            try {
                if (checkSaved && check) {
                    Runnable saveAgain = () -> {
                        ACTIVE.remove(run.id);
                        if (!queue(app, run, false)) Utils.showToastLong(L10n.t("None of the photos could be saved. Try again."));
                    };
                    asking.set(true);
                    SavedVideoArchive.Check found = SavedVideoArchive.check(app, run.id, saveAgain,
                            () -> ACTIVE.remove(run.id));
                    if (found == SavedVideoArchive.Check.OFFERED) {
                        progress.dismiss();
                        return;
                    }
                    asking.set(false);
                    if (found == SavedVideoArchive.Check.FAILED) return;
                }
                // A photo that fails is skipped and the rest still land, and the result says what did.
                SaveProgress.Outcome outcome = progress.run(index -> {
                    MediaBudget.checkDiskSpace(app.getCacheDir(), -1L);
                    last[0] = savePhoto(app, run, chosen.get(index));
                    if (first[0] == null) first[0] = last[0];
                });
                if (checkSaved && first[0] != null) {
                    SavedVideoArchive.rememberQuietly(app, run.id, first[0], archiveGeneration);
                }
                if (outcome.saved == 0 && outcome.cancelled == 0) {
                    Utils.showToastLong(L10n.t("None of the photos could be saved. Try again."));
                } else {
                    Retry failed = run.failed(outcome);
                    SaveNotice.saved(SaveProgress.message(outcome, L10n.quantity(Utils.getContext(), outcome.saved,
                            "Saved one original photo", "Saved %1$s original photos")), last[0],
                            failed == null ? null : () -> retry(failed));
                }
            } catch (RuntimeException exception) {
                Logger.printException(() -> "Original photo download failed", exception);
                Utils.showToastLong(L10n.t("None of the photos could be saved. Try again."));
            }
        }, () -> {
            if (!asking.get()) ACTIVE.remove(run.id);
        });
        if (job == null) return false;
        String saying = L10n.quantity(app, chosen.size(), "Saving one original photo", "Saving %1$s original photos");
        progress.acknowledge(saying, saying);
        return true;
    }

    /** Fetches, converts and publishes the photo at {@code position} in the post, by its own name. */
    private static MediaFileWriter.Saved savePhoto(Context app, Retry run, int position) throws java.io.IOException {
        PhotoStep stand = photoStepForTests;
        if (stand != null) return stand.save(position, run.names.get(position));
        File temp = MediaCache.createTempFile(app, "original-photo-", ".tmp");
        try {
            String extension = PhotoToJpeg.convert(app, temp,
                    RemoteMedia.fetch(run.photos.get(position), temp, RemoteMedia.Kind.IMAGE));
            String mime = "jpg".equals(extension) ? "image/jpeg" : "image/" + extension;
            // Numbered by the photo's place in the post, also when only some are saved.
            String named = run.names.get(position);
            String name = named.substring(0, named.lastIndexOf('.') + 1) + extension;
            return MediaFileWriter.publishForResult(app, temp, name, mime, run.path, false);
        } finally {
            if (!MediaCache.delete(temp)) Logger.printInfo(() -> "Could not remove original photo temporary file");
        }
    }

    /**
     * Retry on a save's result: queues the photos that didn't land, and nothing else. Refused
     * with a word when the account changed since the save, the save is too old for TikTok's
     * addresses to still be good, or this Retry already ran. A tap that queues nothing, because
     * the same post is still saving or the media queue said no, leaves the Retry for later.
     */
    static void retry(Retry failed) {
        if (ACTIVE.contains(failed.id)) {
            String key = jobKey(failed.id);
            Utils.showToastShort(MediaJobScheduler.busyMessage(key));
            return;
        }
        if (!failed.stillUsable()) {
            Utils.showToastLong(L10n.t("Those photos can't be tried again now. Save the post again to get the rest."));
            return;
        }
        Context context = Utils.getContext();
        if (context == null || !queue(context.getApplicationContext(), failed, false)) failed.giveBack();
    }

    /** The media queue's key for a post's photo save. A key, never text the reader sees. */
    private static String jobKey(String id) {
        return "photos " + id;
    }

    /** For the tests: stands in for fetching, converting and publishing one photo. */
    interface PhotoStep {
        MediaFileWriter.Saved save(int position, String name) throws java.io.IOException;
    }

    static volatile PhotoStep photoStepForTests;

    /**
     * How long a save's failed photos can be tried again. TikTok's photo addresses are signed and
     * stop working after a while, and the Retry that holds them is only up for seconds anyway.
     */
    static final long RETRY_WINDOW_MS = 10 * 60_000L;

    /**
     * One run of a post's original photos, kept in memory only, for as long as its result's Retry
     * is: the post, the account it was saved under, every photo's addresses and name as the save
     * first read them, and the photos this run saves, in the post's order. Nothing here is written
     * anywhere.
     */
    static final class Retry {
        final String id;
        final String account;
        final List<List<String>> photos;
        final List<String> names;
        final String path;
        final List<Integer> positions;
        final long madeAt;
        private final AtomicBoolean used = new AtomicBoolean();

        Retry(String id, String account, List<List<String>> photos, List<String> names, String path,
                List<Integer> positions, long madeAt) {
            this.id = id;
            this.account = account;
            this.photos = photos;
            this.names = names;
            this.path = path;
            this.positions = Collections.unmodifiableList(new ArrayList<>(positions));
            this.madeAt = madeAt;
        }

        /**
         * The run of the photos {@code outcome} didn't land, or null when every one it tried did
         * or the rest were cancelled. Unselected photos never come into it, and the time the
         * addresses were read stays the first save's.
         */
        Retry failed(SaveProgress.Outcome outcome) {
            if (outcome.unsaved.isEmpty()) return null;
            List<Integer> left = new ArrayList<>();
            for (int index : outcome.unsaved) left.add(positions.get(index));
            return new Retry(id, account, photos, names, path, left, madeAt);
        }

        /** Whether it may run now, which it can do once: same account, inside the window. */
        boolean stillUsable() {
            if (SystemClock.elapsedRealtime() - madeAt > RETRY_WINDOW_MS) return false;
            if (!Objects.equals(account, SignedInUser.id())) return false;
            return used.compareAndSet(false, true);
        }

        /** Undoes {@link #stillUsable} when the run it allowed never started. */
        void giveBack() {
            used.set(false);
        }
    }

    private static boolean failedConversion(boolean conversion) {
        if (conversion) Utils.showToastLong(L10n.t("None of the photos could be saved. Try again."));
        return conversion;
    }

    private static List<List<String>> snapshot(List<List<String>> photos) {
        List<List<String>> copy = new ArrayList<>();
        for (List<String> photo : photos) copy.add(List.copyOf(photo));
        return List.copyOf(copy);
    }

    static List<List<String>> sources(Object aweme) {
        Object info = Reflect.property(aweme, "getPhotoModeImageInfo", "photoModeImageInfo");
        Object raw = Reflect.property(info, "getImageList", "imageList");
        if (!(raw instanceof List<?>)) return Collections.emptyList();
        List<List<String>> result = new ArrayList<>();
        for (Object photo : (List<?>) raw) {
            Object model = Reflect.property(photo, "getDisplayImageNoWatermark", "displayImageNoWatermark");
            Object urls = Reflect.property(model, "getUrlList", "urlList");
            List<String> candidates = new ArrayList<>();
            if (urls instanceof List<?>) {
                for (Object url : (List<?>) urls) {
                    if (url instanceof String && MediaTransport.hasAllowedShape((String) url)) {
                        candidates.add((String) url);
                    }
                }
            }
            // Never silently save just part of a post whose original sources are missing.
            if (candidates.isEmpty()) return Collections.emptyList();
            int position = result.size() + 1;
            Logger.printDebug(() -> listing(position, candidates));
            result.add(saveOrder(candidates));
        }
        return result;
    }

    /**
     * The order a photo's addresses are tried in: JPEG copies, then the rest, then HEIF and AVIF
     * copies, each group in TikTok's order.
     *
     * <p>TikTok leads each photo's list with a HEIF copy, and that was what got saved: a .heif that
     * plenty of galleries can't open (#105). A JPEG copy, where TikTok's web lists one, is saved as
     * it is. Next comes the WebP copy TikTok lists beside the HEIF, which {@link PhotoToJpeg} turns
     * into a JPEG on any phone. It couldn't do that with the HEIF on a Samsung, whose decoder turns
     * TikTok's HEIF down, so the HEIF only stays behind the others in case they fail.
     */
    static List<String> saveOrder(List<String> urls) {
        List<String> ordered = new ArrayList<>(urls.size());
        for (String url : urls) if (isJpeg(url)) ordered.add(url);
        for (String url : urls) if (!isJpeg(url) && !isHeifOrAvif(url)) ordered.add(url);
        for (String url : urls) if (isHeifOrAvif(url)) ordered.add(url);
        return ordered;
    }

    /** Whether the address names a HEIF or AVIF copy, the encodings plenty of phones can't decode. */
    private static boolean isHeifOrAvif(String url) {
        String path = path(url);
        return path.endsWith(".heic") || path.endsWith(".heif") || path.endsWith(".avif");
    }

    /** Whether the address names a JPEG, read from its path so a query can't pass for one. */
    private static boolean isJpeg(String url) {
        String path = path(url);
        return path.endsWith(".jpeg") || path.endsWith(".jpg");
    }

    /**
     * The debug line naming one photo's encodings. Worded without a "name: value" pair, since the
     * log's redactor reads "encodings:" as a field holding an ID ("odin" is in the name) and blanked
     * the first encoding, the one that mattered.
     */
    static String listing(int position, List<String> urls) {
        return "Original photo " + position + " is listed as " + String.join(", ", encodings(urls));
    }

    /** What each address's path ends in, which says the encoding without its signed query. */
    static List<String> encodings(List<String> urls) {
        List<String> encodings = new ArrayList<>(urls.size());
        for (String url : urls) {
            String path = path(url);
            int dot = path.lastIndexOf('.');
            encodings.add(dot < 0 || dot < path.lastIndexOf('/') ? "?" : path.substring(dot + 1));
        }
        return encodings;
    }

    private static String path(String url) {
        int end = url.length();
        int query = url.indexOf('?');
        if (query >= 0) end = query;
        int fragment = url.indexOf('#');
        if (fragment >= 0 && fragment < end) end = fragment;
        return url.substring(0, end).toLowerCase(java.util.Locale.ROOT);
    }

}
