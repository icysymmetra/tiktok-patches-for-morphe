package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * The GIF the sticker save writes has to be readable by something that is not the writer.
 * Android's own decoder reads the first frame here, which is Skia doing the work rather than
 * anything in this repository, and a reader written from the specification walks the rest.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class GifEncoderTest {

    @Test
    public void androidReadsTheFirstFrameBackPixelForPixel() {
        // Skia is the check here: it has no idea how this file was written.
        int width = 9, height = 6;
        int[] argb = new int[width * height];
        int[] colors = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF, 0xFF123456};
        for (int at = 0; at < argb.length; at++) argb[at] = colors[at % colors.length];

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            GifEncoder.write(out, width, height, List.of(new GifEncoder.Frame(argb, 40)));
        } catch (IOException exception) {
            throw new AssertionError(exception);
        }
        byte[] gif = out.toByteArray();

        Bitmap decoded = BitmapFactory.decodeByteArray(gif, 0, gif.length);
        assertNotNull("Android could not read the GIF back", decoded);
        assertEquals(width, decoded.getWidth());
        assertEquals(height, decoded.getHeight());
        int[] pixels = new int[argb.length];
        decoded.getPixels(pixels, 0, width, 0, 0, width, height);
        assertArrayEquals(argb, pixels);
    }

    @Test
    public void everyPixelSurvivesTheRoundTripWhileTheColoursFit() throws Exception {
        // Four colours over three frames, with a transparent corner on each.
        int width = 7, height = 5;
        List<GifEncoder.Frame> frames = new ArrayList<>();
        int[] colors = {0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF};
        for (int index = 0; index < 3; index++) {
            int[] argb = new int[width * height];
            for (int at = 0; at < argb.length; at++) {
                argb[at] = colors[(at + index) % colors.length];
            }
            argb[0] = 0x00000000;
            argb[argb.length - 1] = 0x40FF00FF; // a quarter alpha reads as transparent too
            frames.add(new GifEncoder.Frame(argb, 40 + index * 10));
        }

        GifReader gif = encodeAndRead(width, height, frames);
        assertEquals(3, gif.frames.size());
        assertEquals(List.of(4, 5, 6), gif.delays);
        assertTrue("loop block", gif.looping);

        for (int index = 0; index < frames.size(); index++) {
            int[] wanted = frames.get(index).argb;
            int[] decoded = gif.frames.get(index);
            for (int at = 0; at < wanted.length; at++) {
                if ((wanted[at] >>> 24) < 128) {
                    assertEquals("transparent at " + at + " of frame " + index,
                            gif.transparentIndex, decoded[at]);
                } else {
                    assertEquals("colour at " + at + " of frame " + index,
                            wanted[at] & 0xFFFFFF, gif.palette[decoded[at]]);
                }
            }
        }
    }

    @Test
    public void aSingleFrameIsWrittenWithoutTheLoopBlock() throws Exception {
        int[] argb = {0xFF102030, 0xFF102030, 0xFF405060, 0x00000000};
        GifReader gif = encodeAndRead(2, 2, List.of(new GifEncoder.Frame(argb, 0)));
        assertEquals(1, gif.frames.size());
        // A zero delay still gets one tick, or a viewer runs the frame as fast as it likes.
        assertEquals(List.of(1), gif.delays);
        assertTrue("no loop block on a still", !gif.looping);
        assertEquals(0x102030, gif.palette[gif.frames.get(0)[0]]);
        assertEquals(gif.transparentIndex, gif.frames.get(0)[3]);
    }

    @Test
    public void tooManyColoursAreQuantisedRatherThanRefused() throws Exception {
        // 4096 distinct colours in one frame, well past what GIF can carry.
        int width = 64, height = 64;
        int[] argb = new int[width * height];
        for (int at = 0; at < argb.length; at++) {
            argb[at] = 0xFF000000 | ((at & 0xF) << 20) | (((at >> 4) & 0xF) << 12) | (((at >> 8) & 0xF) << 4);
        }
        GifReader gif = encodeAndRead(width, height, List.of(new GifEncoder.Frame(argb, 100)));
        int[] decoded = gif.frames.get(0);

        Set<Integer> used = new HashSet<>();
        long worst = 0;
        for (int at = 0; at < argb.length; at++) {
            assertTrue("nothing here is transparent", decoded[at] != gif.transparentIndex);
            used.add(decoded[at]);
            int wanted = argb[at] & 0xFFFFFF, got = gif.palette[decoded[at]];
            long distance = 0;
            for (int shift = 0; shift <= 16; shift += 8) {
                long difference = ((wanted >> shift) & 0xFF) - ((got >> shift) & 0xFF);
                distance += difference * difference;
            }
            worst = Math.max(worst, distance);
        }
        assertTrue("colours used " + used.size(), used.size() <= 255);
        // Median cut over an even spread stays close; a broken palette lands far away.
        assertTrue("worst squared distance " + worst, worst < 4000);
    }

    @Test
    public void longRunsCrossTheCodeWidthBoundariesAndStillReadBack() throws Exception {
        // Noise over two colours fills the dictionary, so the writer has to widen its codes
        // several times and reset once it runs out. Getting that boundary wrong loses pixels.
        int width = 200, height = 200;
        Random random = new Random(20260906L);
        int[] argb = new int[width * height];
        for (int at = 0; at < argb.length; at++) {
            argb[at] = random.nextBoolean() ? 0xFF000000 : 0xFFFFFFFF;
        }
        GifReader gif = encodeAndRead(width, height, List.of(new GifEncoder.Frame(argb, 30)));
        int[] decoded = gif.frames.get(0);
        int[] read = new int[argb.length];
        for (int at = 0; at < argb.length; at++) read[at] = 0xFF000000 | gif.palette[decoded[at]];
        assertArrayEquals(argb, read);
    }

    @Test
    public void aFrameThatDoesNotMatchTheSizeIsRefused() {
        assertThrows(IOException.class, () -> GifEncoder.write(new ByteArrayOutputStream(), 2, 2,
                List.of(new GifEncoder.Frame(new int[3], 40))));
        assertThrows(IOException.class, () -> GifEncoder.write(new ByteArrayOutputStream(), 2, 2, List.of()));
        assertThrows(IOException.class, () -> GifEncoder.write(new ByteArrayOutputStream(), 0, 2,
                List.of(new GifEncoder.Frame(new int[0], 40))));
    }

    @Test
    public void precisionPassStopsBeforeTheSecondFrame() {
        assertStopsBeforeSecondFrame(GifEncoder.FramePass.PRECISION_MASK);
    }

    @Test
    public void palettePassStopsBeforeTheSecondFrame() {
        assertStopsBeforeSecondFrame(GifEncoder.FramePass.PALETTE);
    }

    @Test
    public void encodePassStopsBeforeTheSecondFrame() {
        assertStopsBeforeSecondFrame(GifEncoder.FramePass.ENCODE);
    }

    private static void assertStopsBeforeSecondFrame(GifEncoder.FramePass stoppedPass) {
        List<GifEncoder.Frame> frames = new ArrayList<>();
        for (int index = 0; index < 3; index++) {
            frames.add(new GifEncoder.Frame(
                    new int[]{0xFF000000, 0xFFFFFFFF, 0xFF000000, 0xFFFFFFFF},
                    40
            ));
        }

        IOException failure = assertThrows(IOException.class, () -> GifEncoder.write(
                new ByteArrayOutputStream(),
                2,
                2,
                frames,
                (pass, frameIndex) -> {
                    if (pass == stoppedPass && frameIndex == 1) {
                        throw new IOException("stopped during " + stoppedPass);
                    }
                }
        ));
        assertEquals("stopped during " + stoppedPass, failure.getMessage());
    }

    // ------------------------------------------------------------------ a reader of our own

    private static GifReader encodeAndRead(int width, int height, List<GifEncoder.Frame> frames) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GifEncoder.write(out, width, height, frames);
        return new GifReader(out.toByteArray(), width, height);
    }
}
