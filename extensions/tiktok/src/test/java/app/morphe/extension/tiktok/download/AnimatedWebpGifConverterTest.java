package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.facebook.animated.webp.WebPImage;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * Saving an animated sticker as a GIF runs every frame through this converter, and a wrong frame
 * count or a dropped delay would only ever show up as a broken file sitting in someone's gallery.
 *
 * <p>WebP frames are patches that blend over what came before, while a GIF frame is the whole
 * picture, so the interesting work is the running canvas: what gets cleared, what gets painted
 * over what, and which delay travels with which frame. These drive that from a real WebP
 * container, whose ANMF headers carry the offsets, durations and blend and dispose bits, and read
 * the result back with a GIF reader written from the specification.
 *
 * <p>The pixels inside each frame are the one thing standing in for the real decoder. Expanding
 * them is a native codec that lives in TikTok, not here, so {@code WebPImage} on the test
 * classpath paints each frame a flat colour instead. Nothing the converter itself decides is
 * faked.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AnimatedWebpGifConverterTest {
    private static final int RED = 0xFFFF0000;
    private static final int GREEN = 0xFF00FF00;
    private static final int BLUE = 0xFF0000FF;
    private static final int TRANSPARENT = 0x00000000;

    @Test
    public void everyFrameAndItsDelayReachTheGif() throws Exception {
        byte[] webp = new Webp(8, 6)
                .frame(0, 0, 8, 6, 40, false, false, RED)
                .frame(2, 2, 4, 4, 70, true, false, GREEN)
                .frame(0, 0, 2, 2, 120, false, true, BLUE)
                .build();

        GifReader gif = convert(webp, 8, 6);

        assertEquals("frames", 3, gif.frames.size());
        // GIF counts in hundredths of a second, so 40, 70 and 120 milliseconds land here.
        assertEquals("delays", List.of(4, 7, 12), gif.delays);
    }

    @Test
    public void aFrameThatBlendsKeepsWhatWasUnderIt() throws Exception {
        byte[] webp = new Webp(8, 6)
                .frame(0, 0, 8, 6, 40, false, false, RED)
                .frame(2, 2, 4, 4, 70, true, false, GREEN)
                .build();

        GifReader gif = convert(webp, 8, 6);

        // The second frame covers a 4x4 patch and the first frame's red has to survive around it.
        assertColor(gif, 1, 8, 0, 0, RED);
        assertColor(gif, 1, 8, 7, 5, RED);
        assertColor(gif, 1, 8, 2, 2, GREEN);
        assertColor(gif, 1, 8, 5, 5, GREEN);
        assertColor(gif, 1, 8, 6, 2, RED);
    }

    @Test
    public void aFrameThatDoesNotBlendReplacesWhatWasUnderIt() throws Exception {
        byte[] webp = new Webp(8, 6)
                .frame(0, 0, 8, 6, 40, false, false, RED)
                .frame(2, 2, 4, 4, 70, true, false, GREEN)
                .frame(2, 2, 2, 2, 120, false, false, BLUE)
                .build();

        GifReader gif = convert(webp, 8, 6);

        assertColor(gif, 2, 8, 2, 2, BLUE);
        assertColor(gif, 2, 8, 3, 3, BLUE);
        // The rest of the green patch is untouched, and so is the red around it.
        assertColor(gif, 2, 8, 4, 4, GREEN);
        assertColor(gif, 2, 8, 0, 0, RED);
    }

    @Test
    public void aFrameThatDoesNotBlendClearsWhatWasUnderIt() throws Exception {
        // The interesting half of "does not blend" is the transparent pixels. An opaque patch
        // looks the same whether or not its area was cleared first; a see-through one does not,
        // and without the clear the earlier frame shows through a hole that should be empty.
        byte[] webp = new Webp(8, 6)
                .frame(0, 0, 8, 6, 40, false, false, RED)
                .frame(2, 2, 4, 4, 70, false, false, TRANSPARENT)
                .build();

        GifReader gif = convert(webp, 8, 6);

        assertEquals("the cleared area still shows the frame under it",
                gif.transparentIndex, gif.frames.get(1)[2 + 2 * 8]);
        assertEquals(gif.transparentIndex, gif.frames.get(1)[5 + 5 * 8]);
        assertColor(gif, 1, 8, 0, 0, RED);
    }

    @Test
    public void aFrameThatBlendsLeavesASeeThroughPatchAlone() throws Exception {
        // The same two frames with the blend bit the other way round: nothing is cleared, so
        // painting a fully transparent patch changes nothing at all.
        byte[] webp = new Webp(8, 6)
                .frame(0, 0, 8, 6, 40, false, false, RED)
                .frame(2, 2, 4, 4, 70, true, false, TRANSPARENT)
                .build();

        GifReader gif = convert(webp, 8, 6);

        assertColor(gif, 1, 8, 2, 2, RED);
        assertColor(gif, 1, 8, 5, 5, RED);
    }

    @Test
    public void aFrameThatDisposesToBackgroundLeavesAHoleForTheNextOne() throws Exception {
        byte[] webp = new Webp(8, 6)
                .frame(0, 0, 8, 6, 40, false, false, RED)
                .frame(2, 2, 2, 2, 70, true, true, GREEN)
                .frame(6, 4, 2, 2, 120, true, false, BLUE)
                .build();

        GifReader gif = convert(webp, 8, 6);

        // The green patch is there while its own frame shows.
        assertColor(gif, 1, 8, 2, 2, GREEN);
        // By the next frame it has been cleared, so that corner is transparent rather than red.
        assertEquals("cleared after dispose",
                gif.transparentIndex, gif.frames.get(2)[2 + 2 * 8]);
        assertColor(gif, 2, 8, 6, 4, BLUE);
    }

    @Test
    public void aStillWebpIsRefusedRatherThanWrittenAsAOneFrameGif() {
        // No ANIM and no ANMF. The decoder does not report this as nothing: libwebp stores the
        // lone picture as a frame and says the count is one, so "it decoded" and "it is an
        // animation" are different questions, and only the second one may open the encoder.
        byte[] still = new Webp(8, 6).still().build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        // The decoder does not report a still as nothing: libwebp stores the lone picture as
        // a frame and says the count is one, so the count cannot be what decides this.
        assertEquals("the decoder no longer behaves the way libwebp does for a still",
                1, WebPImage.create(still).getFrameCount());
        assertThrows(IllegalStateException.class,
                () -> AnimatedWebpGifConverter.convert(still, out));
        assertEquals("a GIF was written for a still", 0, out.size());
    }

    @Test
    public void aSingleFrameAnimationIsConverted() throws Exception {
        // One frame is a short animation, not a still. Refusing it on the count would also
        // refuse this, and the container is what tells the two apart.
        byte[] webp = new Webp(8, 6).frame(0, 0, 8, 6, 40, false, false, RED).build();

        GifReader gif = convert(webp, 8, 6);

        assertEquals(1, gif.frames.size());
        assertEquals(List.of(4), gif.delays);
        assertColor(gif, 0, 8, 0, 0, RED);
    }

    @Test
    public void aPictureWithAnExtendedHeaderButNoAnimationIsRefused() {
        // A still gains a VP8X header as soon as it has alpha or an EXIF block, and the
        // animation flag in it is the only thing that says whether it moves.
        byte[] webp = new Webp(8, 6).still().build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertEquals("the fixture is not the shape this is about",
                0x00, webp[20] & 0x02);
        assertThrows(IllegalStateException.class,
                () -> AnimatedWebpGifConverter.convert(webp, out));
        assertEquals(0, out.size());
    }

    @Test
    public void aStickerTooLargeToHoldIsRefusedBeforeAnythingIsAllocated() {
        // Three stored frames plus the canvas and decoded piece need 80 MiB of ARGB pixels.
        Webp builder = new Webp(2048, 2048);
        for (int frame = 0; frame < 3; frame++) {
            builder.frame(0, 0, 2048, 2048, 40, false, false, RED);
        }
        byte[] webp = builder.build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThrows(IllegalStateException.class,
                () -> AnimatedWebpGifConverter.convert(webp, out));
        assertEquals(0, out.size());
    }

    @Test
    public void memoryBudgetCountsTheCanvasAndDecodedPieceAsWellAsStoredFrames() {
        // The old width * height * frames check accepted this one-frame shape at exactly its
        // eight-million-pixel cap, even though its three full surfaces require 96 MiB.
        assertThrows(IllegalStateException.class,
                () -> AnimatedWebpGifConverter.validateWorkingMemory(4096, 2048, 1));

        // Thirty 512-square frames plus the two live bitmaps are exactly 32 MiB. One more frame
        // must cross the bound, which also makes the arithmetic itself part of the contract.
        AnimatedWebpGifConverter.validateWorkingMemory(512, 512, 30);
        assertThrows(IllegalStateException.class,
                () -> AnimatedWebpGifConverter.validateWorkingMemory(512, 512, 31));
    }

    @Test
    public void aFrameReachingOutsideTheCanvasIsRefused() {
        // Two frames, because a single-frame file is refused as a still before the loop that
        // checks where a frame sits ever runs.
        byte[] webp = new Webp(8, 6)
                .frame(0, 0, 8, 6, 40, false, false, RED)
                .frame(6, 4, 4, 4, 70, false, false, GREEN)
                .build();
        ByteArrayOutputStream out = new ByteArrayOutputStream();

        assertThrows(IllegalStateException.class,
                () -> AnimatedWebpGifConverter.convert(webp, out));
    }

    @Test
    public void everyFrameAndTheImageAreReleased() throws Exception {
        byte[] webp = new Webp(8, 6)
                .frame(0, 0, 8, 6, 40, false, false, RED)
                .frame(2, 2, 4, 4, 70, true, false, GREEN)
                .build();

        convert(webp, 8, 6);

        // These hold native memory in the app, and the converter is the only thing that frees it.
        WebPImage image = WebPImage.lastCreated();
        assertTrue("the image was not released", image.isDisposed());
        for (int index = 0; index < image.getFrameCount(); index++) {
            assertTrue("frame " + index + " was not released", image.getFrame(index).isDisposed());
        }
    }

    // ------------------------------------------------------------------------- helpers

    private static GifReader convert(byte[] webp, int width, int height) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        AnimatedWebpGifConverter.convert(webp, (OutputStream) out);
        return new GifReader(out.toByteArray(), width, height);
    }

    private static void assertColor(GifReader gif, int frame, int stride, int x, int y, int argb) {
        int index = gif.frames.get(frame)[x + y * stride];
        assertEquals("colour at " + x + "," + y + " of frame " + frame,
                argb & 0xFFFFFF, gif.palette[index]);
    }

    /**
     * Writes the RIFF container an animated WebP is made of: a VP8X header carrying the canvas
     * size, an ANIM chunk, and one ANMF chunk per frame holding its offset, size, duration and
     * the two bits that say whether it blends and whether its area is cleared afterwards.
     */
    /** Package-private so AnimatedWebpMp4ConverterTest can build the same files. */
    static final class Webp {
        private final int width;
        private final int height;
        private final List<byte[]> chunks = new ArrayList<>();
        private boolean animated = true;

        Webp(int width, int height) {
            this.width = width;
            this.height = height;
        }

        /** Drops the animation chunks, leaving the shape of an ordinary single-picture WebP. */
        Webp still() {
            animated = false;
            return this;
        }

        Webp frame(int x, int y, int frameWidth, int frameHeight, int durationMs,
                   boolean blend, boolean disposeToBackground, int color) {
            byte[] payload = new byte[16 + 8 + 4];
            put24(payload, 0, x / 2);
            put24(payload, 3, y / 2);
            put24(payload, 6, frameWidth - 1);
            put24(payload, 9, frameHeight - 1);
            put24(payload, 12, durationMs);
            payload[15] = (byte) ((blend ? 0 : 0x02) | (disposeToBackground ? 0x01 : 0));
            System.arraycopy("VP8L".getBytes(StandardCharsets.US_ASCII), 0, payload, 16, 4);
            put32(payload, 20, 4);
            put32(payload, 24, color);
            chunks.add(chunk("ANMF", payload));
            return this;
        }

        byte[] build() {
            List<byte[]> body = new ArrayList<>();
            if (animated) {
                byte[] vp8x = new byte[10];
                vp8x[0] = 0x02; // the animation flag
                put24(vp8x, 4, width - 1);
                put24(vp8x, 7, height - 1);
                body.add(chunk("VP8X", vp8x));
                byte[] anim = new byte[6];
                put32(anim, 0, 0x00000000);
                anim[4] = 0; // loop forever
                body.add(chunk("ANIM", anim));
                body.addAll(chunks);
            } else {
                byte[] vp8x = new byte[10];
                put24(vp8x, 4, width - 1);
                put24(vp8x, 7, height - 1);
                body.add(chunk("VP8X", vp8x));
                body.add(chunk("VP8 ", new byte[]{0, 0, 0, 0}));
            }

            int size = 4;
            for (byte[] part : body) size += part.length;
            byte[] file = new byte[8 + size];
            System.arraycopy("RIFF".getBytes(StandardCharsets.US_ASCII), 0, file, 0, 4);
            put32(file, 4, size);
            System.arraycopy("WEBP".getBytes(StandardCharsets.US_ASCII), 0, file, 8, 4);
            int at = 12;
            for (byte[] part : body) {
                System.arraycopy(part, 0, file, at, part.length);
                at += part.length;
            }
            return file;
        }

        private static byte[] chunk(String tag, byte[] payload) {
            int padded = payload.length + (payload.length & 1);
            byte[] out = new byte[8 + padded];
            System.arraycopy(tag.getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
            put32(out, 4, payload.length);
            System.arraycopy(payload, 0, out, 8, payload.length);
            return out;
        }

        private static void put24(byte[] target, int at, int value) {
            target[at] = (byte) value;
            target[at + 1] = (byte) (value >> 8);
            target[at + 2] = (byte) (value >> 16);
        }

        private static void put32(byte[] target, int at, int value) {
            target[at] = (byte) value;
            target[at + 1] = (byte) (value >> 8);
            target[at + 2] = (byte) (value >> 16);
            target[at + 3] = (byte) (value >> 24);
        }
    }
}
