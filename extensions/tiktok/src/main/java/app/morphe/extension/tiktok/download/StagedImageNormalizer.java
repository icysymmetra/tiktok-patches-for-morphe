package app.morphe.extension.tiktok.download;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageDecoder;
import android.graphics.Rect;
import android.os.Build;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Re-encodes HEIF/VVC images that TikTok stages for the gallery.
 *
 * TikTok 47 serves the watermark-free photo-post images as HEIF files that use the VVC codec
 * ("vvic" brand). Android gallery apps cannot open them, but the app bundles a decoder for them
 * (used by its image pipeline), so the staged file is decoded once and written back as JPEG
 * before TikTok copies it into MediaStore.
 */
@SuppressWarnings("unused")
public final class StagedImageNormalizer {
    private static final String HEIF_FACTORY_CLASS = "com.bytedance.fresco.heif.HeifBitmapFactoryImpl";
    private static final int JPEG_QUALITY = 95;
    private static final String[] ISO_BMFF_IMAGE_BRANDS = {
            "heic", "heix", "hevc", "hevx", "heim", "heis", "hevm", "hevs",
            "mif1", "msf1", "avif", "avis", "vvic", "vvis",
    };

    /** Staged files that were re-encoded as JPEG and still await their MediaStore insert. */
    private static final Set<String> CONVERTED_PATHS = new HashSet<>();

    private StagedImageNormalizer() {
    }

    /**
     * Returns the MIME type to insert for the staged file: {@code image/jpeg} once
     * {@link #normalize} re-encoded it, otherwise the MIME type TikTok derived.
     */
    public static String mimeType(String path, String mimeType) {
        if (path == null) {
            return mimeType;
        }
        synchronized (CONVERTED_PATHS) {
            if (CONVERTED_PATHS.remove(path) || CONVERTED_PATHS.remove(new File(path).getPath())) {
                return "image/jpeg";
            }
        }
        return mimeType;
    }

    /**
     * Converts the staged file in place when it is a HEIF-family image.
     *
     * @param path  the private staging file TikTok is about to copy into the gallery.
     * @param name  the public display name TikTok will use for the copy.
     * @param video whether the staged file is a video.
     * @return the display name to use; its extension is switched to {@code jpg} when the
     *         file was re-encoded.
     */
    public static String normalize(String path, String name, boolean video) {
        if (video || path == null || path.isEmpty()) {
            return name;
        }

        try {
            File file = new File(path);
            if (!file.isFile() || !isIsoBmffImage(file)) {
                return name;
            }

            Bitmap bitmap = decode(file);
            if (bitmap == null) {
                Logger.printInfo(() -> "[Morphe Downloads] could not decode HEIF image file=" + file.getName());
                return name;
            }

            File temp = new File(file.getParentFile(), file.getName() + ".morphe-jpg");
            try (OutputStream out = new FileOutputStream(temp)) {
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                    throw new IllegalStateException("JPEG compression failed");
                }
            } finally {
                bitmap.recycle();
            }

            if (!temp.renameTo(file)) {
                boolean copied = copyFile(temp, file);
                //noinspection ResultOfMethodCallIgnored
                temp.delete();
                if (!copied) {
                    throw new IllegalStateException("could not replace staged file");
                }
            }

            synchronized (CONVERTED_PATHS) {
                CONVERTED_PATHS.add(file.getPath());
            }
            String newName = replaceExtension(name, "jpg");
            if (BaseSettings.DEBUG.get()) {
                Logger.printInfo(() -> "[Morphe Downloads] re-encoded HEIF image file=" + file.getName()
                        + " size=" + file.length() + " name=" + newName);
            }
            return newName;
        } catch (Throwable ex) {
            Logger.printException(() -> "[Morphe Downloads] HEIF image conversion failed path=" + path, ex);
            return name;
        }
    }

    private static boolean isIsoBmffImage(File file) throws Exception {
        byte[] header = new byte[12];
        int read;
        try (InputStream in = new FileInputStream(file)) {
            read = in.read(header);
        }
        if (read < 12 || header[4] != 'f' || header[5] != 't' || header[6] != 'y' || header[7] != 'p') {
            return false;
        }
        String brand = new String(header, 8, 4, "ISO-8859-1").toLowerCase(Locale.ROOT);
        for (String candidate : ISO_BMFF_IMAGE_BRANDS) {
            if (candidate.equals(brand)) {
                return true;
            }
        }
        return false;
    }

    private static Bitmap decode(File file) {
        Bitmap bitmap = decodeWithTikTokHeifFactory(file);
        if (bitmap == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            bitmap = decodeWithImageDecoder(file);
        }
        if (bitmap == null) {
            bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        }
        return bitmap;
    }

    private static Bitmap decodeWithTikTokHeifFactory(File file) {
        try {
            Class<?> factoryClass = Class.forName(HEIF_FACTORY_CLASS);
            Object factory = factoryClass.getConstructor().newInstance();
            Method decodeStream = factoryClass.getMethod(
                    "decodeStream", InputStream.class, Rect.class, BitmapFactory.Options.class);
            try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
                return (Bitmap) decodeStream.invoke(factory, in, null, null);
            }
        } catch (Throwable ex) {
            Logger.printInfo(() -> "[Morphe Downloads] TikTok HEIF decoder unavailable: " + ex);
            return null;
        }
    }

    private static Bitmap decodeWithImageDecoder(File file) {
        try {
            return ImageDecoder.decodeBitmap(
                    ImageDecoder.createSource(file),
                    (decoder, info, source) -> decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE));
        } catch (Throwable ex) {
            return null;
        }
    }

    private static boolean copyFile(File source, File target) {
        try (InputStream in = new FileInputStream(source); OutputStream out = new FileOutputStream(target)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return true;
        } catch (Throwable ex) {
            return false;
        }
    }

    private static String replaceExtension(String name, String extension) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return base + "." + extension;
    }
}
