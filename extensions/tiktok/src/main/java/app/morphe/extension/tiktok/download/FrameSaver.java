/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.Window;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.playback.PictureInPicture;
import app.morphe.extension.tiktok.settings.L10n;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * Saves the frame on screen as a JPEG, for the Long press action.
 *
 * <p>Read from the view TikTok's player draws into, the largest TextureView or SurfaceView showing,
 * the same one picture-in-picture keeps. Not a screenshot: the caption, the buttons and Hushfeed's
 * own overlays are other views and stay out of it. A TextureView hands its last frame back on the
 * main thread. A SurfaceView's frame sits in a buffer only the compositor draws, and PixelCopy,
 * Android 7 and newer, is the way to read it back. TikTok's feed player on the S22 draws into a
 * SurfaceView, so that's the path most presses take.
 *
 * <p>The frame is read at the video's own size, which the post carries, not at the size it's
 * drawn: both readers scale the decoded picture to the bitmap they're handed, and the crop TikTok
 * makes to fill the screen isn't part of what they read. A post with no size of its own (a LIVE,
 * or a press TikTok gave no post for) takes the size the view is drawn at.
 */
public final class FrameSaver {
    /** A 4K frame. A bigger one is scaled down, keeping its shape, rather than risk the memory. */
    static final long MAX_PIXELS = 3840L * 2160L;
    /** The longest side a frame may have, for a size whose shape is far from any video's. */
    static final long MAX_SIDE = 4096L;
    /** What the queue and the unfinished saves notice call this save. */
    static final String LABEL = "video frame";

    /** Copies what a SurfaceView shows into a bitmap, then answers on the main thread. */
    interface SurfaceReader {
        void read(SurfaceView view, Bitmap into, Copied copied);
    }

    // java.util.function requires API 24; the payload also runs on API 23.
    interface Copied {
        void copied(boolean copied);
    }

    /** PixelCopy on a phone; a test stands in its own, since Robolectric draws no surface. */
    static SurfaceReader surfaceReader = new PixelCopyReader();

    private FrameSaver() {
    }

    /**
     * Saves the frame on screen in {@code activity}, named after {@code aweme} and the position.
     * Read at once on the main thread, so the frame saved is the one showing at the press. Says
     * why when there's nothing to save, because a long press that does nothing reads as broken.
     *
     * @param positionMs where the video was, or a negative number when that isn't known
     */
    public static void save(Activity activity, Object aweme, long positionMs) {
        Utils.runOnMainThreadNowOrLater(() -> capture(activity, aweme, positionMs));
    }

    private static void capture(Activity activity, Object aweme, long positionMs) {
        Window window = activity == null ? null : activity.getWindow();
        View root = window == null ? null : window.peekDecorView();
        View video = root == null ? null : PictureInPicture.videoView(root);
        if (video == null) {
            Utils.showToastShort(L10n.t("There's no video on screen to save a frame from"));
            return;
        }
        if (video instanceof SurfaceView && Build.VERSION.SDK_INT < 24) {
            Utils.showToastShort(L10n.t("Saving a frame from this video needs Android 7 or newer"));
            return;
        }
        // Android 6 to 9 write a real file, so without the permission the save would fail after
        // the frame was already read. Asked first, the way every other saver here asks it.
        if (Build.VERSION.SDK_INT < 29
                && activity.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            Utils.showToastLong(L10n.t("Allow storage for TikTok in Android settings to save frames"));
            return;
        }
        int[] size = frameSize(aweme, video,
                video instanceof SurfaceView ? surfaceBuffer((SurfaceView) video) : null);
        if (size == null) {
            couldNotRead();
            return;
        }
        Context app = activity.getApplicationContext();
        String name = DownloadFilenameFormatter.formatFrameName(aweme, positionMs);
        if (video instanceof TextureView) {
            Bitmap frame = textureFrame((TextureView) video, size);
            if (frame == null) {
                couldNotRead();
            } else {
                publish(app, frame, name);
            }
            return;
        }
        readSurface((SurfaceView) video, size, app, name);
    }

    /**
     * The size to read the frame at: the post's own video size, else the size the view is drawn
     * at, held under {@link #MAX_PIXELS} and {@link #MAX_SIDE} with its shape kept. Null when
     * neither has a size.
     */
    static int[] frameSize(Object aweme, View video) {
        return frameSize(aweme, video, null);
    }

    /**
     * The same, given the size of the surface the frame sits in when that's known. A player that
     * hands decoded frames straight to a SurfaceView leaves its buffer the video's own shape, and
     * the post's size reads it best. One that draws through a renderer of its own leaves the buffer
     * the view's shape with the video already fitted in, and reading that at the post's shape would
     * stretch it, so a buffer of another shape is read at its own size.
     */
    static int[] frameSize(Object aweme, View video, int[] buffer) {
        Object model = aweme == null ? null : Reflect.property(aweme, "getVideo", "video");
        long width = dimension(model, "getWidth", "width");
        long height = dimension(model, "getHeight", "height");
        if (width <= 0 || height <= 0) {
            width = video.getWidth();
            height = video.getHeight();
        }
        if (buffer != null && buffer[0] > 0 && buffer[1] > 0
                && (width <= 0 || height <= 0 || !sameShape(width, height, buffer[0], buffer[1]))) {
            width = buffer[0];
            height = buffer[1];
        }
        if (width <= 0 || height <= 0) return null;
        double scale = Math.min(1d, Math.min(
                Math.sqrt((double) MAX_PIXELS / ((double) width * height)),
                (double) MAX_SIDE / Math.max(width, height)));
        if (scale < 1d) {
            width = Math.max(1L, (long) (width * scale));
            height = Math.max(1L, (long) (height * scale));
        }
        return new int[]{(int) width, (int) height};
    }

    /** Width to height within 2 percent of each other, the slack of an odd row or column. */
    static boolean sameShape(long width, long height, long otherWidth, long otherHeight) {
        double one = (double) width / height;
        double other = (double) otherWidth / otherHeight;
        return Math.abs(one - other) <= 0.02 * Math.max(one, other);
    }

    /** The size of the SurfaceView's current buffer, or null before it has one. */
    static int[] surfaceBuffer(SurfaceView view) {
        try {
            Rect frame = view.getHolder().getSurfaceFrame();
            if (frame != null && frame.width() > 0 && frame.height() > 0) {
                return new int[]{frame.width(), frame.height()};
            }
        } catch (RuntimeException unreadable) {
            Logger.printInfo(() -> "Could not read the video surface's size: " + unreadable);
        }
        return null;
    }

    private static long dimension(Object model, String getter, String field) {
        Object value = Reflect.property(model, getter, field);
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    /**
     * The TextureView's last frame at {@code size}, or null while it has none: before the video's
     * first frame, or once its surface has gone away.
     */
    static Bitmap textureFrame(TextureView view, int[] size) {
        try {
            return view.getBitmap(size[0], size[1]);
        } catch (RuntimeException | OutOfMemoryError error) {
            Logger.printInfo(() -> "Could not read the video's frame: " + error);
            return null;
        }
    }

    private static void readSurface(SurfaceView view, int[] size, Context app, String name) {
        final Bitmap frame;
        try {
            frame = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888);
        } catch (RuntimeException | OutOfMemoryError error) {
            Logger.printInfo(() -> "No room for a frame of " + size[0] + "x" + size[1] + ": " + error);
            couldNotRead();
            return;
        }
        try {
            surfaceReader.read(view, frame, copied -> {
                if (copied) {
                    publish(app, frame, name);
                } else {
                    frame.recycle();
                    couldNotRead();
                }
            });
        } catch (RuntimeException refused) {
            // PixelCopy turns down a surface that's already gone, a swipe at the same moment.
            Logger.printInfo(() -> "The video's surface couldn't be read: " + refused);
            frame.recycle();
            couldNotRead();
        }
    }

    private static void couldNotRead() {
        Utils.showToastShort(L10n.t("The frame couldn't be read. Try again."));
    }

    /**
     * Encodes the frame and publishes it to the photo folder, off the main thread. The frame is
     * recycled once the job is over, whether it saved, failed or was never let into the queue.
     */
    static void publish(Context app, Bitmap frame, String name) {
        String path = DownloadsPatch.getPhotoDownloadPath();
        MediaJobScheduler.Job job = MediaJobScheduler.submit(LABEL, null, () -> {
            File temp = null;
            try {
                MediaBudget.checkDiskSpace(app.getCacheDir(), -1L);
                temp = MediaCache.createTempFile(app, "frame-", ".tmp");
                // A video frame has nothing see-through in it, and a JPEG couldn't keep it if it did.
                frame.setHasAlpha(false);
                try (FileOutputStream output = new FileOutputStream(temp)) {
                    if (!frame.compress(Bitmap.CompressFormat.JPEG, PhotoToJpeg.QUALITY, output)) {
                        throw new IOException("The JPEG encoder refused the frame");
                    }
                }
                MediaFileWriter.Saved landed = MediaFileWriter.publishForResult(app, temp, name, "image/jpeg", path, false);
                SaveNotice.saved(L10n.f("Frame saved to %1$s", path), landed);
            } catch (IOException | RuntimeException | OutOfMemoryError exception) {
                // A 4K frame's JPEG needs room of its own on top of the frame, and this runs on a
                // worker thread, where an error nobody catches takes TikTok down with it.
                Logger.printException(() -> "Frame save failed", exception);
                Utils.showToastLong(L10n.t("The frame couldn't be saved. Try again."));
            } finally {
                if (temp != null && !MediaCache.delete(temp)) {
                    Logger.printInfo(() -> "Could not remove the frame's temporary file");
                }
            }
        }, frame::recycle);
        String saying = L10n.t("Saving the frame");
        MediaJobScheduler.acknowledge(job, saying, saying);
    }

    /** PixelCopy, which {@link #capture} never reaches below Android 7. */
    private static final class PixelCopyReader implements SurfaceReader {
        @Override public void read(SurfaceView view, Bitmap into, Copied copied) {
            if (Build.VERSION.SDK_INT >= 24) {
                PixelCopy.request(view, into, result -> {
                    if (result != PixelCopy.SUCCESS) {
                        Logger.printInfo(() -> "PixelCopy couldn't read the video's frame: " + result);
                    }
                    copied.copied(result == PixelCopy.SUCCESS);
                }, new Handler(Looper.getMainLooper()));
            } else {
                copied.copied(false);
            }
        }
    }
}
