/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns TikTok's animated WebP sticker frames into a GIF.
 *
 * WebP frames can be a patch of the picture that blends over what came before, while a GIF
 * frame here is the whole picture, so the frames are painted onto a running canvas and each
 * complete state is handed to {@link GifEncoder}. The reflection into Fresco's decoder mirrors
 * the MP4 and APNG converters beside this one; TikTok ships that decoder, this repository does
 * not, so it can only be reached by name.
 */
final class AnimatedWebpGifConverter {
    /**
     * Pixel storage held before the encoder starts: one canvas, one worst-case decoded piece,
     * and one full-canvas int array for every frame. The encoder needs working memory too, so
     * this deliberately leaves most of TikTok's heap free.
     */
    private static final long MAX_PIXEL_STORAGE_BYTES = 32L * 1024 * 1024;
    private static final long ARGB_BYTES_PER_PIXEL = 4L;

    private AnimatedWebpGifConverter() {
    }

    static void convert(byte[] webpData, OutputStream outputStream) throws Exception {
        // The file itself says whether it is an animation, and it is the only thing that knows.
        // Counting frames cannot tell a still from a one-frame animation, because libwebp
        // reports both as one: it stores a lone picture as a frame like any other.
        if (!isAnimated(webpData)) {
            throw new IllegalStateException("WebP is a still picture, not an animation");
        }

        Object image = null;
        Bitmap canvas = null;
        try {
            Class<?> imageClass = Class.forName("com.facebook.animated.webp.WebPImage");
            Method create = imageClass.getDeclaredMethod("create", byte[].class);
            create.setAccessible(true);
            image = create.invoke(null, (Object) webpData);
            if (image == null) throw new IllegalStateException("WebP decoder returned null");

            int width = invokeInt(image, "getWidth");
            int height = invokeInt(image, "getHeight");
            int frameCount = invokeInt(image, "getFrameCount");
            int[] durations = (int[]) invoke(image, "getFrameDurations");
            validateWorkingMemory(width, height, frameCount);

            canvas = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            Canvas painter = new Canvas(canvas);
            Paint clear = new Paint();
            clear.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

            List<GifEncoder.Frame> frames = new ArrayList<>(frameCount);
            for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                // The one place in the loop that can notice the job's deadline.
                // The memory cap does not bound time: a small canvas with tens of
                // thousands of frames passes it and then holds one of the three media worker
                // threads for as long as the decoding takes. The MP4 path checks the same way.
                MediaBudget.check(null);
                Object frame = null;
                Bitmap piece = null;
                try {
                    frame = invokeFrame(image, frameIndex);
                    int frameWidth = invokeInt(frame, "getWidth");
                    int frameHeight = invokeInt(frame, "getHeight");
                    int xOffset = invokeInt(frame, "getXOffset");
                    int yOffset = invokeInt(frame, "getYOffset");
                    validateFrame(width, height, frameWidth, frameHeight, xOffset, yOffset);
                    // LIZ and LIZIZ read out of Fresco's WebPFrame, checked against the
                    // 46.2.3 host on 2026-09-08 rather than assumed: in
                    // Lcom/facebook/animated/webp/WebPFrame; the body of LIZ()Z is a single
                    // invoke-direct of nativeIsBlendWithPreviousFrame()Z, and LIZIZ()Z is a
                    // single invoke-direct of nativeShouldDisposeToBackgroundColor()Z. The
                    // native names survive obfuscation because JNI binds by name, so they are
                    // the thing to read the mapping off. Swapping these two composites every
                    // non-blending frame wrongly and no test could tell, because the stand-in
                    // decoder in the test tree defines the same mapping.
                    boolean blend = invokeBoolean(frame, "LIZ");
                    boolean disposeToBackground = invokeBoolean(frame, "LIZIZ");

                    piece = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888);
                    invoke(frame, "renderFrame", new Class<?>[]{
                            int.class, int.class, Bitmap.class
                    }, frameWidth, frameHeight, piece);

                    // A frame that does not blend replaces what is under it, transparency and all.
                    if (!blend) {
                        painter.drawRect(xOffset, yOffset, xOffset + frameWidth, yOffset + frameHeight, clear);
                    }
                    painter.drawBitmap(piece, xOffset, yOffset, null);

                    int[] pixels = new int[width * height];
                    canvas.getPixels(pixels, 0, width, 0, 0, width, height);
                    int duration = durations != null && frameIndex < durations.length
                            ? durations[frameIndex]
                            : 100;
                    frames.add(new GifEncoder.Frame(pixels, duration));

                    if (disposeToBackground) {
                        painter.drawRect(xOffset, yOffset, xOffset + frameWidth, yOffset + frameHeight, clear);
                    }
                } finally {
                    if (piece != null) piece.recycle();
                    dispose(frame);
                }
            }

            GifEncoder.write(outputStream, width, height, frames);
            outputStream.flush();
        } finally {
            if (canvas != null) canvas.recycle();
            dispose(image);
        }
    }

    static void validateWorkingMemory(int width, int height, int frameCount) {
        if (width <= 0 || height <= 0 || frameCount <= 0) {
            throw new IllegalStateException("Invalid animated WebP dimensions or frame count");
        }
        long canvasPixels = (long) width * height;
        long fullCanvasSurfaces = (long) frameCount + 2L;
        long maximumPixels = MAX_PIXEL_STORAGE_BYTES / ARGB_BYTES_PER_PIXEL / fullCanvasSurfaces;
        if (canvasPixels > maximumPixels) {
            throw new IllegalStateException("Animated WebP is too large to hold as a GIF");
        }
    }

    /**
     * Whether the container says it holds an animation.
     *
     * <p>An extended WebP puts its flags in the byte after the VP8X tag and its size, and bit 1
     * of those is the animation flag. A file with no VP8X chunk cannot be animated at all. This
     * is the same test the sticker save makes before choosing a format, made again here so the
     * converter is not relying on having been called correctly.
     */
    private static boolean isAnimated(byte[] data) {
        return data != null && data.length >= 21
                && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P'
                && data[12] == 'V' && data[13] == 'P' && data[14] == '8' && data[15] == 'X'
                && (data[20] & 0x02) != 0;
    }

    private static Object invokeFrame(Object image, int index) throws Exception {
        for (Method method : image.getClass().getMethods()) {
            if (!"getFrame".equals(method.getName()) || method.getParameterTypes().length != 1) continue;
            method.setAccessible(true);
            Object frame = method.invoke(image, index);
            if (frame == null) throw new IllegalStateException("WebP frame " + index + " is missing");
            return frame;
        }
        throw new NoSuchMethodException("getFrame(int)");
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        Method method = target.getClass().getMethod(methodName);
        method.setAccessible(true);
        return method.invoke(target);
    }

    private static Object invoke(
            Object target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        Method method = target.getClass().getMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static int invokeInt(Object target, String methodName) throws Exception {
        return (Integer) invoke(target, methodName);
    }

    private static boolean invokeBoolean(Object target, String methodName) throws Exception {
        return Boolean.TRUE.equals(invoke(target, methodName));
    }

    private static void dispose(Object target) {
        if (target == null) return;
        try {
            invoke(target, "dispose");
        } catch (Throwable ignored) {
            // Native resources are also finalized by Fresco; explicit disposal is best effort.
        }
    }

    private static void validateFrame(
            int canvasWidth,
            int canvasHeight,
            int frameWidth,
            int frameHeight,
            int xOffset,
            int yOffset
    ) {
        if (frameWidth <= 0 || frameHeight <= 0 || xOffset < 0 || yOffset < 0
                || (long) xOffset + frameWidth > canvasWidth
                || (long) yOffset + frameHeight > canvasHeight) {
            throw new IllegalStateException("Animated WebP frame is outside its canvas");
        }
    }
}
