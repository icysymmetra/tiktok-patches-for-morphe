package com.facebook.animated.webp;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.List;

/**
 * Stands in for the WebP decoder TikTok ships, which this repository does not have.
 *
 * <p>{@code AnimatedWebpGifConverter} reaches Fresco's decoder by name because the real class
 * is inside the host app, and {@code Class.forName} finds whatever is on the classpath. Under
 * test that is this: it walks the RIFF container and the ANMF frame headers of a real WebP file
 * exactly as the format defines them, so the frame count, the per-frame durations, the offsets
 * and the blend and dispose bits the converter reads are the ones in the bytes.
 *
 * <p>What it does not do is expand the compressed image inside each frame. That is a codec, it
 * is native, and it belongs to the decoder rather than to the converter under test, so each
 * frame here paints one flat colour written into the frame's payload. Everything the converter
 * decides, which is what to clear, what to paint over what, and which delay goes with which
 * frame, is still driven by the file.
 */
@SuppressWarnings("unused")
/*
 * The LIZ and LIZIZ names below are not a guess. In the 46.2.3 host,
 * Lcom/facebook/animated/webp/WebPFrame;->LIZ()Z is a single invoke-direct of
 * nativeIsBlendWithPreviousFrame()Z and ->LIZIZ()Z is a single invoke-direct of
 * nativeShouldDisposeToBackgroundColor()Z, read out of classes35.dex on 2026-09-08. Because this
 * file defines the same mapping the converters read, a swap here would pass every test, so the
 * host is the authority and this comment is the record of it.
 */
public final class WebPImage {
    private final int width;
    private final int height;
    private final List<WebPFrame> frames;
    private boolean disposed;

    /** The last image the converter asked for, so a test can check it was released. */
    private static WebPImage lastCreated;

    private WebPImage(int width, int height, List<WebPFrame> frames) {
        this.width = width;
        this.height = height;
        this.frames = frames;
    }

    /**
      * The entry point the converter calls.
      *
      * <p>A file with no ANMF chunks still decodes to one frame, because that is what libwebp's
      * demuxer does: {@code ParseSingleImage} stores the lone picture as a frame and reports a
      * frame count of 1. Returning zero here would have let the converter look as though it
      * refused a still when what it actually does is take the loop once.
      */
    public static WebPImage create(byte[] data) {
        Reader reader = new Reader(data);
        reader.expect("RIFF");
        reader.u32();
        reader.expect("WEBP");

        int width = 0;
        int height = 0;
        List<WebPFrame> frames = new ArrayList<>();
        while (reader.remaining() >= 8) {
            String chunk = reader.fourCc();
            int size = reader.u32();
            int payload = reader.at();
            if ("VP8X".equals(chunk)) {
                width = reader.u24At(payload + 4) + 1;
                height = reader.u24At(payload + 7) + 1;
            } else if ("ANMF".equals(chunk)) {
                frames.add(WebPFrame.parse(reader, payload, size));
            }
            reader.skipTo(payload + size + (size & 1));
        }
        if (frames.isEmpty() && width > 0 && height > 0) {
            frames.add(WebPFrame.still(width, height));
        }
        lastCreated = new WebPImage(width, height, frames);
        return lastCreated;
    }

    public static WebPImage lastCreated() {
        return lastCreated;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getFrameCount() {
        return frames.size();
    }

    public int[] getFrameDurations() {
        int[] durations = new int[frames.size()];
        for (int index = 0; index < durations.length; index++) {
            durations[index] = frames.get(index).duration;
        }
        return durations;
    }

    public WebPFrame getFrame(int index) {
        return frames.get(index);
    }

    public void dispose() {
        disposed = true;
    }

    /** Whether the converter released the image, which it promises to do on every path. */
    public boolean isDisposed() {
        return disposed;
    }

    /** One ANMF chunk: where it goes, how long it stays, and what to do around it. */
    public static final class WebPFrame {
        private final int xOffset;
        private final int yOffset;
        private final int width;
        private final int height;
        final int duration;
        private final boolean blend;
        private final boolean disposeToBackground;
        private final int color;
        private boolean disposed;

        private WebPFrame(int xOffset, int yOffset, int width, int height, int duration,
                          boolean blend, boolean disposeToBackground, int color) {
            this.xOffset = xOffset;
            this.yOffset = yOffset;
            this.width = width;
            this.height = height;
            this.duration = duration;
            this.blend = blend;
            this.disposeToBackground = disposeToBackground;
            this.color = color;
        }

        /** The single frame libwebp reports for a picture with no animation chunks. */
        static WebPFrame still(int width, int height) {
            return new WebPFrame(0, 0, width, height, 0, false, false, 0xFF000000);
        }

        static WebPFrame parse(Reader reader, int payload, int size) {
            int x = reader.u24At(payload) * 2;
            int y = reader.u24At(payload + 3) * 2;
            int frameWidth = reader.u24At(payload + 6) + 1;
            int frameHeight = reader.u24At(payload + 9) + 1;
            int duration = reader.u24At(payload + 12);
            int flags = reader.u8At(payload + 15);
            // The specification puts disposal in the low bit and blending in the one above it,
            // and reads a set blending bit as "do not blend".
            boolean disposeToBackground = (flags & 0x01) != 0;
            boolean blend = (flags & 0x02) == 0;
            // The sub-chunk header is 8 bytes; the flat colour this stands in for follows it.
            int color = size >= 16 + 8 + 4 ? reader.u32At(payload + 16 + 8) : 0xFF000000;
            return new WebPFrame(x, y, frameWidth, frameHeight, duration,
                    blend, disposeToBackground, color);
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public int getXOffset() {
            return xOffset;
        }

        public int getYOffset() {
            return yOffset;
        }

        /** Fresco's obfuscated name for "this frame blends over what is already there". */
        public boolean LIZ() {
            return blend;
        }

        /** Fresco's obfuscated name for "clear this frame's area once it has been shown". */
        public boolean LIZIZ() {
            return disposeToBackground;
        }

        public void renderFrame(int renderWidth, int renderHeight, Bitmap into) {
            int[] pixels = new int[renderWidth * renderHeight];
            for (int at = 0; at < pixels.length; at++) pixels[at] = color;
            into.setPixels(pixels, 0, renderWidth, 0, 0, renderWidth, renderHeight);
        }

        public void dispose() {
            disposed = true;
        }

        public boolean isDisposed() {
            return disposed;
        }
    }

    /** A cursor over the container, big-endian for the tags and little-endian for the numbers. */
    private static final class Reader {
        private final byte[] data;
        private int at;

        Reader(byte[] data) {
            this.data = data;
        }

        int at() {
            return at;
        }

        int remaining() {
            return data.length - at;
        }

        void expect(String tag) {
            String found = fourCc();
            if (!tag.equals(found)) {
                throw new IllegalStateException("expected " + tag + " but found " + found);
            }
        }

        String fourCc() {
            String tag = new String(data, at, 4, java.nio.charset.StandardCharsets.US_ASCII);
            at += 4;
            return tag;
        }

        int u32() {
            int value = u32At(at);
            at += 4;
            return value;
        }

        int u32At(int index) {
            return (data[index] & 0xFF) | ((data[index + 1] & 0xFF) << 8)
                    | ((data[index + 2] & 0xFF) << 16) | ((data[index + 3] & 0xFF) << 24);
        }

        int u24At(int index) {
            return (data[index] & 0xFF) | ((data[index + 1] & 0xFF) << 8)
                    | ((data[index + 2] & 0xFF) << 16);
        }

        int u8At(int index) {
            return data[index] & 0xFF;
        }

        void skipTo(int index) {
            at = index;
        }
    }
}
