/*
 * Copyright (c) 2026 Metra TikTok Patches
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */

package app.morphe.extension.tiktok.download;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.base.model.UrlModel;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("unused")
public final class StickerGallerySaver {
    private static final String ACTION_LABEL = "Save media";
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = 20_000;

    private static final ExecutorService SAVE_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final WeakHashMap<View, Boolean> ATTACHED_SHEETS = new WeakHashMap<>();
    private static final WeakHashMap<Object, Object> STICKER_SOURCES = new WeakHashMap<>();

    private StickerGallerySaver() {
    }

    public static void registerStickerSource(Object previewModel, Object source) {
        if (previewModel == null || source == null) return;
        synchronized (STICKER_SOURCES) {
            STICKER_SOURCES.put(previewModel, source);
        }
    }

    public static void attachSaveImageButton(View sheetView, Object sheetModel) {
        try {
            if (sheetView == null || sheetModel == null) return;

            synchronized (ATTACHED_SHEETS) {
                if (ATTACHED_SHEETS.containsKey(sheetView)) {
                    return;
                }
            }

            StickerAsset asset = findStickerAsset(sheetModel);
            if (asset == null) {
                debugLog("[Morphe Stickers] no usable sticker URL");
                return;
            }

            List<View> actionButtons = findViewsByClassName(sheetView, "X.0GSy", "LX.0GSy", "X.0Daq", "LX.0Daq");
            ViewGroup actionParent = findCommonParent(actionButtons);
            if (actionParent == null || actionButtons.size() < 2 || hasSaveImageButton(actionParent)) {
                debugLog("[Morphe Stickers] action parent unavailable buttons=" + actionButtons.size());
                return;
            }

            View template = actionButtons.get(actionButtons.size() - 1);
            int insertIndex = actionParent.indexOfChild(template) + 1;
            if (insertIndex <= 0) {
                debugLog("[Morphe Stickers] action buttons are not direct children");
                return;
            }

            TextView saveImageButton = createActionButton(template, asset);
            ViewGroup.LayoutParams layoutParams = cloneLayoutParams(template.getLayoutParams());
            actionParent.addView(saveImageButton, insertIndex, layoutParams);

            synchronized (ATTACHED_SHEETS) {
                ATTACHED_SHEETS.put(sheetView, Boolean.TRUE);
            }

            debugLog("[Morphe Stickers] attached Save sticker button animated=" + asset.animated
                    + " url=" + summarizeUrl(asset.url)
                    + " parent=" + actionParent.getClass().getName());
        } catch (Throwable ex) {
            if (BaseSettings.DEBUG.get()) {
                Logger.printException(() -> "[Morphe Stickers] attachSaveImageButton failure", ex);
            }
        }
    }

    private static TextView createActionButton(View template, StickerAsset asset) {
        Context context = template.getContext();
        TextView button = new TextView(context);
        button.setText(ACTION_LABEL);
        button.setGravity(Gravity.CENTER);
        button.setSingleLine(true);
        button.setEllipsize(TextUtils.TruncateAt.END);
        button.setOnClickListener(view -> saveStickerFromButton(view, asset));

        if (template instanceof TextView) {
            TextView textTemplate = (TextView) template;
            button.setTextColor(textTemplate.getTextColors());
            button.setTextSize(0, textTemplate.getTextSize());
            button.setTypeface(textTemplate.getTypeface(), textTemplate.getTypeface() == null ? Typeface.NORMAL : textTemplate.getTypeface().getStyle());
            button.setIncludeFontPadding(textTemplate.getIncludeFontPadding());
            button.setMinHeight(textTemplate.getMinHeight());
            button.setMinWidth(textTemplate.getMinWidth());
            button.setPadding(
                    textTemplate.getPaddingLeft(),
                    textTemplate.getPaddingTop(),
                    textTemplate.getPaddingRight(),
                    textTemplate.getPaddingBottom()
            );
        } else {
            button.setTextColor(Color.WHITE);
            button.setTextSize(16);
            int paddingHorizontal = dp(context, 16);
            int paddingVertical = dp(context, 10);
            button.setPadding(paddingHorizontal, paddingVertical, paddingHorizontal, paddingVertical);
        }

        Drawable background = template.getBackground();
        if (background != null && background.getConstantState() != null) {
            button.setBackground(background.getConstantState().newDrawable().mutate());
        } else {
            button.setBackground(template.getBackground());
        }

        button.setEnabled(template.isEnabled());
        button.setClickable(true);
        button.setFocusable(true);
        button.setAlpha(template.getAlpha());
        return button;
    }

    private static void saveStickerFromButton(View button, StickerAsset asset) {
        Context context = button.getContext().getApplicationContext();
        button.setEnabled(false);
        toast(context, "Saving sticker...");

        SAVE_EXECUTOR.execute(() -> {
            SaveResult result = saveSticker(context, asset);
            MAIN_HANDLER.post(() -> {
                button.setEnabled(true);
                toast(context, result.message);
                if (result.success) {
                    debugLog("[Morphe Stickers] saved sticker path=" + result.path);
                } else if (BaseSettings.DEBUG.get()) {
                    Logger.printInfo(() -> "[Morphe Stickers] save failed reason=" + result.message
                            + " url=" + summarizeUrl(asset.url));
                }
            });
        });
    }

    private static SaveResult saveSticker(Context context, StickerAsset asset) {
        HttpURLConnection connection = null;
        Uri pendingUri = null;

        try {
            connection = (HttpURLConnection) new URL(asset.url).openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent", "TikTok 47.1.4 Morphe");

            int responseCode = connection.getResponseCode();
            if (responseCode < 200 || responseCode >= 300) {
                return SaveResult.failure("Sticker download failed");
            }

            try (BufferedInputStream inputStream = new BufferedInputStream(connection.getInputStream())) {
                MediaFormat format = detectMediaFormat(connection.getContentType(), asset.url, inputStream, asset.animated);
                String mediaId = Integer.toUnsignedString(asset.url.hashCode(), 16);
                String displayName = DownloadFilenameFormatter.formatCommentMediaName(format.extension, mediaId);

                if (!format.convertToPng) {
                    if (format.convertToMp4) {
                        byte[] animatedWebp = readFully(inputStream);
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            pendingUri = saveAnimatedWebpMp4WithMediaStore(context, animatedWebp, displayName);
                            return SaveResult.success(displayPath(displayName, true), pendingUri.toString(), "MP4");
                        }

                        File outputFile = saveAnimatedWebpMp4WithLegacyStorage(context, animatedWebp, displayName);
                        return SaveResult.success(outputFile.getAbsolutePath(), outputFile.getAbsolutePath(), "MP4");
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        pendingUri = saveStreamWithMediaStore(context, inputStream, displayName, format);
                        return SaveResult.success(displayPath(displayName, format.video), pendingUri.toString(), format.label);
                    }

                    File outputFile = saveStreamWithLegacyStorage(context, inputStream, displayName, format.mimeType);
                    return SaveResult.success(outputFile.getAbsolutePath(), outputFile.getAbsolutePath(), format.label);
                }

                Bitmap bitmap = BitmapFactory.decodeStream(inputStream);
                if (bitmap == null) return SaveResult.failure("Sticker image could not be decoded");

                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        pendingUri = saveBitmapWithMediaStore(context, bitmap, displayName);
                        return SaveResult.success(displayPath(displayName, false), pendingUri.toString(), "PNG");
                    }

                    File outputFile = saveBitmapWithLegacyStorage(context, bitmap, displayName);
                    return SaveResult.success(outputFile.getAbsolutePath(), outputFile.getAbsolutePath(), "PNG");
                } finally {
                    bitmap.recycle();
                }
            } catch (Throwable ex) {
                if (pendingUri != null) {
                    try {
                        context.getContentResolver().delete(pendingUri, null, null);
                    } catch (Throwable ignored) {
                        // Best effort cleanup.
                    }
                }
                throw ex;
            }
        } catch (Throwable ex) {
            if (BaseSettings.DEBUG.get()) {
                Logger.printException(() -> "[Morphe Stickers] saveSticker failure", ex);
            }
            return SaveResult.failure("Sticker save failed");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static Uri saveBitmapWithMediaStore(Context context, Bitmap bitmap, String displayName) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        String relativePath = stickerRelativePath(false);
        values.put(MediaStore.Images.Media.RELATIVE_PATH, relativePath);
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(DownloadDestination.collectionUri(relativePath, false), values);
        if (uri == null) {
            throw new IllegalStateException("MediaStore insert returned null");
        }

        try {
            try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                if (outputStream == null) {
                    throw new IllegalStateException("MediaStore output stream returned null");
                }
                writePng(bitmap, outputStream);
            }

            ContentValues complete = new ContentValues();
            complete.put(MediaStore.Images.Media.IS_PENDING, 0);
            resolver.update(uri, complete, null, null);
            return uri;
        } catch (Throwable ex) {
            try {
                resolver.delete(uri, null, null);
            } catch (Throwable ignored) {
                // Best effort cleanup.
            }
            throw ex;
        }
    }

    private static Uri saveStreamWithMediaStore(
            Context context,
            InputStream inputStream,
            String displayName,
            MediaFormat format
    ) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
        values.put(MediaStore.MediaColumns.MIME_TYPE, format.mimeType);
        String relativePath = stickerRelativePath(format.video);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);

        Uri collection = DownloadDestination.collectionUri(relativePath, format.video);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) throw new IllegalStateException("MediaStore insert returned null");

        try {
            try (OutputStream outputStream = resolver.openOutputStream(uri)) {
                if (outputStream == null) throw new IllegalStateException("MediaStore output stream returned null");
                copy(inputStream, outputStream);
            }
            ContentValues complete = new ContentValues();
            complete.put(MediaStore.MediaColumns.IS_PENDING, 0);
            resolver.update(uri, complete, null, null);
            return uri;
        } catch (Throwable ex) {
            try {
                resolver.delete(uri, null, null);
            } catch (Throwable ignored) {
                // Best effort cleanup.
            }
            throw ex;
        }
    }

    private static File saveBitmapWithLegacyStorage(Context context, Bitmap bitmap, String displayName) throws Exception {
        File directory = new File(Environment.getExternalStorageDirectory(), stickerRelativePath(false));
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create " + directory);
        }

        File outputFile = new File(directory, displayName);
        try {
            try (OutputStream outputStream = new FileOutputStream(outputFile)) {
                writePng(bitmap, outputStream);
            }
        } catch (Throwable ex) {
            if (outputFile.exists() && !outputFile.delete()) {
                debugLog("[Morphe Stickers] could not remove partial file=" + outputFile.getAbsolutePath());
            }
            throw ex;
        }
        MediaScannerConnection.scanFile(context, new String[]{outputFile.getAbsolutePath()}, new String[]{"image/png"}, null);
        return outputFile;
    }

    private static File saveStreamWithLegacyStorage(
            Context context,
            InputStream inputStream,
            String displayName,
            String mimeType
    ) throws Exception {
        boolean video = mimeType != null && mimeType.startsWith("video/");
        File directory = new File(Environment.getExternalStorageDirectory(), stickerRelativePath(video));
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create " + directory);
        }

        File outputFile = new File(directory, displayName);
        try {
            try (OutputStream outputStream = new FileOutputStream(outputFile)) {
                copy(inputStream, outputStream);
            }
        } catch (Throwable ex) {
            if (outputFile.exists() && !outputFile.delete()) {
                debugLog("[Morphe Stickers] could not remove partial file=" + outputFile.getAbsolutePath());
            }
            throw ex;
        }
        MediaScannerConnection.scanFile(context, new String[]{outputFile.getAbsolutePath()}, new String[]{mimeType}, null);
        return outputFile;
    }

    private static Uri saveAnimatedWebpMp4WithMediaStore(
            Context context,
            byte[] animatedWebp,
            String displayName
    ) throws Exception {
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        String relativePath = stickerRelativePath(true);
        values.put(MediaStore.Video.Media.RELATIVE_PATH, relativePath);
        values.put(MediaStore.Video.Media.IS_PENDING, 1);

        Uri uri = resolver.insert(DownloadDestination.collectionUri(relativePath, true), values);
        if (uri == null) throw new IllegalStateException("MediaStore insert returned null");

        try {
            try (ParcelFileDescriptor output = resolver.openFileDescriptor(uri, "w")) {
                if (output == null) throw new IllegalStateException("MediaStore file descriptor returned null");
                AnimatedWebpMp4Converter.convert(animatedWebp, output.getFileDescriptor());
            }
            ContentValues complete = new ContentValues();
            complete.put(MediaStore.Video.Media.IS_PENDING, 0);
            resolver.update(uri, complete, null, null);
            return uri;
        } catch (Throwable ex) {
            try {
                resolver.delete(uri, null, null);
            } catch (Throwable ignored) {
                // Best effort cleanup.
            }
            throw ex;
        }
    }

    private static File saveAnimatedWebpMp4WithLegacyStorage(
            Context context,
            byte[] animatedWebp,
            String displayName
    ) throws Exception {
        File directory = new File(Environment.getExternalStorageDirectory(), stickerRelativePath(true));
        if (!directory.exists() && !directory.mkdirs()) {
            throw new IllegalStateException("Could not create " + directory);
        }

        File outputFile = new File(directory, displayName);
        try {
            AnimatedWebpMp4Converter.convert(animatedWebp, outputFile.getAbsolutePath());
        } catch (Throwable ex) {
            if (outputFile.exists() && !outputFile.delete()) {
                debugLog("[Morphe Stickers] could not remove partial file=" + outputFile.getAbsolutePath());
            }
            throw ex;
        }
        MediaScannerConnection.scanFile(
                context,
                new String[]{outputFile.getAbsolutePath()},
                new String[]{"video/mp4"},
                null
        );
        return outputFile;
    }

    private static String stickerRelativePath(boolean video) {
        String path = DownloadDestination.resolve(
                Settings.DOWNLOAD_STICKER_PATH.get(),
                DownloadDestination.Kind.STICKER
        );
        return path;
    }

    private static String displayPath(String displayName, boolean video) {
        return stickerRelativePath(video) + "/" + displayName;
    }

    private static void writePng(Bitmap bitmap, OutputStream outputStream) throws Exception {
        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, outputStream)) {
            throw new IllegalStateException("PNG encoding failed");
        }
        outputStream.flush();
    }

    private static void copy(InputStream inputStream, OutputStream outputStream) throws Exception {
        byte[] buffer = new byte[16 * 1024];
        int read;
        while ((read = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, read);
        }
        outputStream.flush();
    }

    private static byte[] readFully(InputStream inputStream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        copy(inputStream, output);
        return output.toByteArray();
    }

    private static MediaFormat detectMediaFormat(
            String contentType,
            String url,
            BufferedInputStream inputStream,
            boolean animated
    ) throws Exception {
        byte[] header = peekHeader(inputStream);
        String mimeType = normalizedContentType(contentType);
        String extension = extensionFromUrl(url);

        if (isMp4(header) || "video/mp4".equals(mimeType) || "m4v".equals(extension)) {
            return MediaFormat.passthrough("video/mp4", "mp4", true, "MP4");
        }
        if (isWebm(header) || "video/webm".equals(mimeType) || "webm".equals(extension)) {
            return MediaFormat.passthrough("video/webm", "webm", true, "WebM");
        }
        if (mimeType != null && mimeType.startsWith("video/")) {
            String videoExtension = extension == null ? "mp4" : extension;
            return MediaFormat.passthrough(
                    mimeType,
                    videoExtension,
                    true,
                    videoExtension.toUpperCase(java.util.Locale.ROOT)
            );
        }
        if (isGif(header) || "image/gif".equals(mimeType) || "gif".equals(extension)) {
            return MediaFormat.passthrough("image/gif", "gif", false, "GIF");
        }
        if (isWebp(header) || "image/webp".equals(mimeType) || "webp".equals(extension)) {
            if (animated || isAnimatedWebp(header)) {
                return MediaFormat.mp4();
            }
            return MediaFormat.png();
        }
        if (isPng(header) || isJpeg(header) || (mimeType != null && mimeType.startsWith("image/"))) {
            return MediaFormat.png();
        }
        if (animated && mimeType != null) {
            String safeExtension = extension == null ? "bin" : extension;
            return MediaFormat.passthrough(
                    mimeType,
                    safeExtension,
                    false,
                    safeExtension.toUpperCase(java.util.Locale.ROOT)
            );
        }
        throw new IllegalStateException("Unsupported sticker media type");
    }

    private static byte[] peekHeader(BufferedInputStream inputStream) throws Exception {
        inputStream.mark(32);
        byte[] buffer = new byte[32];
        int count = inputStream.read(buffer);
        inputStream.reset();
        if (count == buffer.length) return buffer;
        byte[] result = new byte[Math.max(count, 0)];
        if (count > 0) System.arraycopy(buffer, 0, result, 0, count);
        return result;
    }

    private static String normalizedContentType(String contentType) {
        if (contentType == null) return null;
        int separator = contentType.indexOf(';');
        String value = (separator >= 0 ? contentType.substring(0, separator) : contentType).trim().toLowerCase();
        return value.isEmpty() || "application/octet-stream".equals(value) ? null : value;
    }

    private static boolean isPng(byte[] header) {
        return header.length >= 8 && (header[0] & 0xff) == 0x89 && header[1] == 'P'
                && header[2] == 'N' && header[3] == 'G';
    }

    private static boolean isJpeg(byte[] header) {
        return header.length >= 3 && (header[0] & 0xff) == 0xff && (header[1] & 0xff) == 0xd8
                && (header[2] & 0xff) == 0xff;
    }

    private static boolean isGif(byte[] header) {
        return header.length >= 6 && header[0] == 'G' && header[1] == 'I' && header[2] == 'F'
                && header[3] == '8' && (header[4] == '7' || header[4] == '9') && header[5] == 'a';
    }

    private static boolean isWebp(byte[] header) {
        return header.length >= 12 && header[0] == 'R' && header[1] == 'I' && header[2] == 'F'
                && header[3] == 'F' && header[8] == 'W' && header[9] == 'E' && header[10] == 'B'
                && header[11] == 'P';
    }

    private static boolean isAnimatedWebp(byte[] header) {
        return header.length >= 21 && isWebp(header) && header[12] == 'V' && header[13] == 'P'
                && header[14] == '8' && header[15] == 'X' && (header[20] & 0x02) != 0;
    }

    private static boolean isMp4(byte[] header) {
        return header.length >= 8 && header[4] == 'f' && header[5] == 't' && header[6] == 'y' && header[7] == 'p';
    }

    private static boolean isWebm(byte[] header) {
        return header.length >= 4 && (header[0] & 0xff) == 0x1a && (header[1] & 0xff) == 0x45
                && (header[2] & 0xff) == 0xdf && (header[3] & 0xff) == 0xa3;
    }

    private static StickerAsset findStickerAsset(Object model) {
        StickerAsset sourceAsset = findSourceStickerAsset(model);
        if (sourceAsset != null) return sourceAsset;

        UrlModel urlModel = findUrlModel(model);
        String url = firstUsableUrl(urlModel);
        if (url == null) return null;
        return new StickerAsset(url, isAnimatedStickerModel(model));
    }

    private static StickerAsset findSourceStickerAsset(Object previewModel) {
        Object source;
        synchronized (STICKER_SOURCES) {
            source = STICKER_SOURCES.get(previewModel);
        }
        if (source == null) return null;

        Object sticker = resolveSourceSticker(source);
        if (sticker != null) {
            for (String methodName : new String[]{"getAnimateUrl", "getAnimatedUrl"}) {
                UrlModel animated = bestResolutionUrl(invokeNoArg(sticker, methodName));
                String animatedUrl = firstUsableUrl(animated);
                if (animatedUrl != null) {
                    debugLog("[Morphe Stickers] selected source animated URL " + summarizeUrl(animatedUrl));
                    return new StickerAsset(animatedUrl, true);
                }
            }

            UrlModel staticModel = bestResolutionUrl(invokeNoArg(sticker, "getStaticUrl"));
            String staticUrl = firstUsableUrl(staticModel);
            if (staticUrl != null) {
                debugLog("[Morphe Stickers] selected source static URL " + summarizeUrl(staticUrl));
                return new StickerAsset(staticUrl, false);
            }

            Object directValue = invokeNoArg(sticker, "getUrl");
            if (directValue instanceof String) {
                String directUrl = ((String) directValue).trim();
                if (!directUrl.isEmpty() && !"null".equalsIgnoreCase(directUrl)) {
                    Object typeValue = invokeNoArg(sticker, "getType");
                    String type = typeValue == null
                            ? ""
                            : typeValue.toString().toLowerCase(java.util.Locale.ROOT);
                    boolean animated = !type.contains("static")
                            && !type.contains("png")
                            && !type.contains("jpeg")
                            && !type.contains("jpg");
                    debugLog("[Morphe Stickers] selected source direct URL "
                            + summarizeUrl(directUrl) + " type=" + type);
                    return new StickerAsset(directUrl, animated);
                }
            }
        }

        Object image = invokeNoArg(source, "currentImage");
        String directUrl = firstUsableUrlList(readNamedField(image, "urlList"));
        if (directUrl == null) return null;

        Object imageTypeValue = readNamedField(image, "imageType");
        String imageType = imageTypeValue == null ? "" : imageTypeValue.toString().toLowerCase(java.util.Locale.ROOT);
        boolean animated = imageType.contains("anim") || imageType.contains("webp") || imageType.contains("gif");
        debugLog("[Morphe Stickers] selected StickerItem image URL " + summarizeUrl(directUrl)
                + " type=" + imageType);
        return new StickerAsset(directUrl, animated);
    }

    private static Object resolveSourceSticker(Object source) {
        Object legacySticker = readNamedField(source, "LLILLIZIL");
        if (legacySticker != null) return legacySticker;

        if (invokeNoArg(source, "getStaticUrl") != null
                || invokeNoArg(source, "getAnimateUrl") != null
                || invokeNoArg(source, "getAnimatedUrl") != null) {
            return source;
        }

        try {
            Class<?> helperClass = Class.forName("X.0UD5");
            for (java.lang.reflect.Method method : helperClass.getDeclaredMethods()) {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (!java.lang.reflect.Modifier.isStatic(method.getModifiers())
                        || parameterTypes.length != 1
                        || !parameterTypes[0].isAssignableFrom(source.getClass())
                        || !isRichStickerType(method.getReturnType())) {
                    continue;
                }
                method.setAccessible(true);
                Object value = method.invoke(null, source);
                if (value != null) return value;
            }
        } catch (Throwable ignored) {
            // Fall through to StickerItem.currentImage().
        }
        return null;
    }

    private static boolean isRichStickerType(Class<?> type) {
        String name = type.getName();
        return name.endsWith(".SetSticker")
                || name.endsWith(".VideoSticker")
                || name.endsWith(".IMGiphyInfo");
    }

    private static String firstUsableUrlList(Object value) {
        if (!(value instanceof List<?>)) return null;
        for (Object item : (List<?>) value) {
            if (item == null) continue;
            String url = item.toString().trim();
            if (!url.isEmpty() && !"null".equalsIgnoreCase(url)) return url;
        }
        return null;
    }

    private static UrlModel bestResolutionUrl(Object stickerUrlStruct) {
        if (stickerUrlStruct instanceof UrlModel) return (UrlModel) stickerUrlStruct;
        if (stickerUrlStruct == null) return null;
        for (String methodName : new String[]{
                "getHighResolutionUrl", "getMidResolutionUrl", "getLowResolutionUrl"
        }) {
            Object value = invokeNoArg(stickerUrlStruct, methodName);
            if (value instanceof UrlModel && firstUsableUrl((UrlModel) value) != null) {
                return (UrlModel) value;
            }
        }
        return null;
    }

    private static Object invokeNoArg(Object instance, String methodName) {
        if (instance == null) return null;
        try {
            java.lang.reflect.Method method = instance.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(instance);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object readNamedField(Object instance, String fieldName) {
        if (instance == null) return null;
        Class<?> current = instance.getClass();
        while (current != null) {
            try {
                java.lang.reflect.Field field = current.getDeclaredField(fieldName);
                field.setAccessible(true);
                return field.get(instance);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    private static boolean isAnimatedStickerModel(Object model) {
        if (model == null) return false;
        Class<?> current = model.getClass();
        while (current != null) {
            try {
                java.lang.reflect.Field staticFlag = current.getDeclaredField("LIZIZ");
                if (staticFlag.getType() == Boolean.TYPE || staticFlag.getType() == Boolean.class) {
                    staticFlag.setAccessible(true);
                    Object value = staticFlag.get(model);
                    if (value instanceof Boolean) return !((Boolean) value);
                }
            } catch (Throwable ignored) {
                // Continue with the media signature when the target model changes.
            }
            current = current.getSuperclass();
        }
        return false;
    }

    private static UrlModel findUrlModel(Object model) {
        if (model == null) return null;

        Class<?> current = model.getClass();
        while (current != null) {
            java.lang.reflect.Field[] fields = current.getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(model);
                    if (value instanceof UrlModel) {
                        return (UrlModel) value;
                    }
                } catch (Throwable ignored) {
                    // Keep probing other fields.
                }
            }
            current = current.getSuperclass();
        }

        return null;
    }

    private static String extensionFromUrl(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null) return null;
            int dotIndex = path.lastIndexOf('.');
            if (dotIndex < 0 || dotIndex == path.length() - 1) return null;
            String extension = path.substring(dotIndex + 1).toLowerCase(java.util.Locale.ROOT);
            return extension.length() <= 5 ? extension : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String firstUsableUrl(UrlModel model) {
        if (model == null) return null;

        try {
            List<String> urls = model.getUrlList();
            if (urls == null || urls.isEmpty()) return null;

            for (String url : urls) {
                if (url != null && !url.trim().isEmpty() && !"null".equalsIgnoreCase(url.trim())) {
                    return url;
                }
            }
        } catch (Throwable ignored) {
            return null;
        }

        return null;
    }

    private static List<View> findViewsByClassName(View root, String... classNames) {
        List<View> matches = new ArrayList<>();
        collectViewsByClassName(root, matches, classNames);
        return matches;
    }

    private static void collectViewsByClassName(View view, List<View> matches, String... classNames) {
        if (view == null) return;

        String viewClassName = view.getClass().getName();
        for (String className : classNames) {
            if (viewClassName.equals(className)) {
                matches.add(view);
                break;
            }
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectViewsByClassName(group.getChildAt(i), matches, classNames);
            }
        }
    }

    private static ViewGroup findCommonParent(List<View> views) {
        if (views.size() < 2) return null;

        View first = views.get(0);
        View second = views.get(1);
        ViewParent parent = first.getParent();
        while (parent != null) {
            if (isAncestor(parent, second)) {
                return parent instanceof ViewGroup ? (ViewGroup) parent : null;
            }
            parent = parent.getParent();
        }

        return null;
    }

    private static boolean isAncestor(ViewParent candidate, View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent == candidate) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private static boolean hasSaveImageButton(ViewGroup parent) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (child instanceof TextView && ACTION_LABEL.contentEquals(((TextView) child).getText())) {
                return true;
            }
        }
        return false;
    }

    private static ViewGroup.LayoutParams cloneLayoutParams(ViewGroup.LayoutParams params) {
        if (params instanceof LinearLayout.LayoutParams) {
            return new LinearLayout.LayoutParams((LinearLayout.LayoutParams) params);
        }
        if (params instanceof ViewGroup.MarginLayoutParams) {
            return new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) params);
        }
        if (params != null) {
            return new ViewGroup.LayoutParams(params);
        }
        return new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private static String summarizeUrl(String url) {
        if (url == null || url.trim().isEmpty()) return "null";

        try {
            URI uri = URI.create(url);
            String path = uri.getPath();
            if (path != null && path.length() > 96) {
                path = path.substring(0, 96) + "...";
            }
            return uri.getHost() + path;
        } catch (Throwable ignored) {
            int queryIndex = url.indexOf('?');
            String withoutQuery = queryIndex >= 0 ? url.substring(0, queryIndex) : url;
            return withoutQuery.length() <= 96 ? withoutQuery : withoutQuery.substring(0, 96) + "...";
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static void toast(Context context, String message) {
        MAIN_HANDLER.post(() -> Toast.makeText(context, message, Toast.LENGTH_SHORT).show());
    }

    private static void debugLog(String message) {
        if (BaseSettings.DEBUG.get()) {
            Logger.printInfo(() -> message);
        }
    }

    private static final class StickerAsset {
        final String url;
        final boolean animated;

        StickerAsset(String url, boolean animated) {
            this.url = url;
            this.animated = animated;
        }
    }

    private static final class MediaFormat {
        final String mimeType;
        final String extension;
        final boolean video;
        final boolean convertToPng;
        final boolean convertToMp4;
        final String label;

        private MediaFormat(
                String mimeType,
                String extension,
                boolean video,
                boolean convertToPng,
                boolean convertToMp4,
                String label
        ) {
            this.mimeType = mimeType;
            this.extension = extension;
            this.video = video;
            this.convertToPng = convertToPng;
            this.convertToMp4 = convertToMp4;
            this.label = label;
        }

        static MediaFormat png() {
            return new MediaFormat("image/png", "png", false, true, false, "PNG");
        }

        static MediaFormat mp4() {
            return new MediaFormat("video/mp4", "mp4", true, false, true, "MP4");
        }

        static MediaFormat passthrough(String mimeType, String extension, boolean video, String label) {
            return new MediaFormat(mimeType, extension, video, false, false, label);
        }
    }

    private static final class SaveResult {
        final boolean success;
        final String message;
        final String path;

        private SaveResult(boolean success, String message, String path) {
            this.success = success;
            this.message = message;
            this.path = path;
        }

        static SaveResult success(String path, String uri, String format) {
            return new SaveResult(true, "Sticker saved as " + format, path + " (" + uri + ")");
        }

        static SaveResult failure(String message) {
            return new SaveResult(false, message, null);
        }
    }
}
