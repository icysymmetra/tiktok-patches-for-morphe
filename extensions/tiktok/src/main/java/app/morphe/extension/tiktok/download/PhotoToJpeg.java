/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.media.MediaMetadataRetriever;
import android.os.Build;
import app.morphe.extension.shared.Logger;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Saves a HEIF or WebP photo again as a JPEG of the same size.
 *
 * <p>TikTok lists each photo of a post as a HEIF copy and a WebP copy, and its own HEIF rewrite
 * leaves signed addresses alone, so there's no JPEG to ask the server for. A .heif is a file plenty
 * of galleries and computers won't open (#105), and a .webp is one some computers and apps still
 * won't. Samsung's decoder turns TikTok's HEIF down as well ("invalid input" on the S22), so
 * {@link OriginalPhotos} fetches the WebP first and this rewrites whichever one came. A HEIF the
 * image decoder turns down gets a second read through the media extractor, which reads TikTok's
 * HEIF on that same phone. Any other format keeps exactly what TikTok sent, and so does a photo
 * this Android can't decode (before 9, or a HEIF neither path reads), an animated one and a
 * see-through one.
 */
final class PhotoToJpeg {
    /** High enough that the second encoding can't be told from the first at full size. */
    static final int QUALITY = 95;
    /** Far above any TikTok photo; a bigger one stays HEIF rather than risk the memory. */
    static final long MAX_PIXELS = 25_000_000L;

    /** Reads a HEIF's primary image the way the media scanner does, or returns null. */
    interface PrimaryImageReader {
        Bitmap read(File file);
    }

    /** The platform extractor, which Robolectric doesn't run, so a test can stand in for it. */
    static PrimaryImageReader primaryImageReader = PhotoToJpeg::readPrimaryImage;

    /** A photo kept on purpose (animated, too large), which no second decoder should retry. */
    private static final class KeepAsSent extends IllegalArgumentException {
        KeepAsSent(String message) {
            super(message);
        }
    }

    private PhotoToJpeg() {}

    /**
     * Rewrites {@code file} as a JPEG when {@code extension}, read from its header, says HEIF or WebP.
     *
     * @return the extension the file holds afterwards: "jpg" once converted, else {@code extension}.
     */
    static String convert(Context context, File file, String extension) {
        if (!"heif".equals(extension) && !"heic".equals(extension) && !"webp".equals(extension)) {
            return extension;
        }
        if (Build.VERSION.SDK_INT < 28) return extension;
        File jpeg = null;
        Bitmap bitmap = null;
        try {
            try {
                bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file), (decoder, info, source) -> {
                    // A JPEG would keep the first frame and drop the rest.
                    if (info.isAnimated()) throw new KeepAsSent("Animated photo kept as sent");
                    if ((long) info.getSize().getWidth() * info.getSize().getHeight() > MAX_PIXELS) {
                        throw new KeepAsSent("Photo too large to convert: " + info.getSize());
                    }
                    // A hardware bitmap can't be encoded again.
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                });
            } catch (KeepAsSent kept) {
                throw kept;
            } catch (IOException | RuntimeException refused) {
                // Samsung's decoder says "invalid input" to TikTok's HEIF, which its media
                // extractor reads cleanly (S22, 2026-10-06).
                if ("webp".equals(extension)) throw refused;
                bitmap = primaryImageReader.read(file);
                if (bitmap == null) throw refused;
                // The extractor decodes the primary HEVC item alone, which has no alpha plane, so
                // the pixels are opaque even though an ARGB_8888 bitmap says it may have alpha.
                bitmap.setHasAlpha(false);
                Logger.printInfo(() -> "The image decoder refused a HEIF photo; read it through the media extractor");
            }
            // JPEG has no transparency, so a see-through photo would turn black where it shows through.
            if (bitmap.hasAlpha()) return extension;
            jpeg = MediaCache.createTempFile(context, "photo-jpeg-", ".tmp");
            try (FileOutputStream output = new FileOutputStream(jpeg)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, QUALITY, output)) {
                    throw new IOException("The JPEG encoder refused the photo");
                }
            }
            Files.move(jpeg.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return "jpg";
        } catch (IOException | RuntimeException | OutOfMemoryError error) {
            Logger.printInfo(() -> "Kept the " + extension + " photo TikTok sent: " + error);
            return extension;
        } finally {
            if (bitmap != null) bitmap.recycle();
            if (jpeg != null && !MediaCache.delete(jpeg)) {
                Logger.printInfo(() -> "Could not remove a photo conversion temporary file");
            }
        }
    }

    /**
     * The HEIF's primary image through MediaMetadataRetriever, held to the same size limit, or
     * null when it can't be read. The retriever is released on every path.
     */
    static Bitmap readPrimaryImage(File file) {
        if (Build.VERSION.SDK_INT < 28) return null;
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(file.getPath());
            if (!oneStillImage(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_WIDTH),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_HEIGHT),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_IMAGE_COUNT),
                    retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))) {
                return null;
            }
            MediaMetadataRetriever.BitmapParams params = new MediaMetadataRetriever.BitmapParams();
            params.setPreferredConfig(Bitmap.Config.ARGB_8888);
            return retriever.getPrimaryImage(params);
        } catch (RuntimeException error) {
            Logger.printInfo(() -> "The media extractor couldn't read the HEIF photo either: " + error);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (IOException | RuntimeException error) {
                Logger.printInfo(() -> "Could not release the photo media extractor: " + error);
            }
        }
    }

    /** Whether the retriever's metadata describe one still image within the size limit. */
    static boolean oneStillImage(String width, String height, String imageCount, String hasVideo) {
        long wide = parseDimension(width);
        long tall = parseDimension(height);
        if (wide <= 0 || tall <= 0 || wide * tall > MAX_PIXELS) return false;
        // An image sequence would lose every frame but the first, as with the decoder. An
        // animated HEIF keeps its frames in a video track, not as extra images, so it says so there.
        return parseDimension(imageCount) <= 1 && !"yes".equals(hasVideo);
    }

    private static long parseDimension(String value) {
        if (value == null) return -1;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }
}
