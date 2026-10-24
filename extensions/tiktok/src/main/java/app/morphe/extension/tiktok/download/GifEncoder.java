/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Writes an animated GIF89a, so a sticker can be saved as a GIF with nothing but the platform.
 *
 * GIF carries at most 256 colours and one fully transparent index, which is a real loss next to
 * an animated WebP. Frames are quantised together against one palette so the animation does not
 * shimmer between frames, and every frame clears itself afterwards so a transparent pixel never
 * shows what was underneath.
 */
final class GifEncoder {
    /** One frame: pixels in ARGB_8888 order, row by row, and how long it stays up. */
    static final class Frame {
        final int[] argb;
        final int delayMs;

        Frame(int[] argb, int delayMs) {
            this.argb = argb;
            this.delayMs = delayMs;
        }
    }

    /** Below this the pixel is written as the transparent index; GIF has no partial alpha. */
    private static final int ALPHA_FLOOR = 128;
    private static final int MAX_COLORS = 255;

    /**
     * Colours are counted at full precision until there are this many. Past it the picture is
     * going to be quantised to 255 anyway, so it is snapped to five bits a channel first: that
     * caps the work at 32,768 colours instead of the millions a photographic sticker carries,
     * and the error it adds is a fraction of what median cut adds next.
     */
    private static final int EXACT_COLOR_LIMIT = 4096;
    private static final int COARSE = 0xF8F8F8;

    enum FramePass {
        PRECISION_MASK,
        PALETTE,
        ENCODE
    }

    @FunctionalInterface
    interface BudgetCheck {
        void check(FramePass pass, int frameIndex) throws IOException;
    }

    private static final BudgetCheck MEDIA_BUDGET =
            (pass, frameIndex) -> MediaBudget.check(null);

    private GifEncoder() {
    }

    static void write(OutputStream out, int width, int height, List<Frame> frames) throws IOException {
        write(out, width, height, frames, MEDIA_BUDGET);
    }

    static void write(
            OutputStream out,
            int width,
            int height,
            List<Frame> frames,
            BudgetCheck budget
    ) throws IOException {
        if (width <= 0 || height <= 0) throw new IOException("GIF needs a positive size");
        if (frames == null || frames.isEmpty()) throw new IOException("GIF needs at least one frame");
        int pixels = width * height;
        for (Frame frame : frames) {
            if (frame.argb == null || frame.argb.length != pixels) {
                throw new IOException("A GIF frame does not match " + width + "x" + height);
            }
        }

        int mask = precisionMask(frames, budget);
        int[] palette = palette(frames, mask, budget);
        int transparent = palette.length;
        int tableSize = tableSize(palette.length + 1);
        int bits = Integer.numberOfTrailingZeros(tableSize);

        // Header and logical screen, with the global table the frames were quantised against.
        out.write(new byte[]{'G', 'I', 'F', '8', '9', 'a'});
        writeShort(out, width);
        writeShort(out, height);
        out.write(0xF0 | (bits - 1));
        out.write(transparent);
        out.write(0);
        for (int index = 0; index < tableSize; index++) {
            int color = index < palette.length ? palette[index] : 0;
            out.write((color >> 16) & 0xFF);
            out.write((color >> 8) & 0xFF);
            out.write(color & 0xFF);
        }

        if (frames.size() > 1) {
            // NETSCAPE2.0: loop for ever, which is what an animated sticker does.
            out.write(0x21);
            out.write(0xFF);
            out.write(11);
            out.write(new byte[]{'N', 'E', 'T', 'S', 'C', 'A', 'P', 'E', '2', '.', '0'});
            out.write(3);
            out.write(1);
            writeShort(out, 0);
            out.write(0);
        }

        Map<Integer, Integer> nearest = new HashMap<>();
        int frameIndex = 0;
        for (Frame frame : frames) {
            // Every frame is another full pass of mapping and compression, so this is where a
            // job past its deadline stops, as the decode loop before it does.
            budget.check(FramePass.ENCODE, frameIndex++);
            // Delay is in hundredths of a second, and a zero delay runs as fast as the viewer
            // feels like, so the shortest frame still gets one tick.
            int delay = Math.max(1, Math.round(frame.delayMs / 10f));
            out.write(0x21);
            out.write(0xF9);
            out.write(4);
            out.write(0x09); // dispose by restoring the background, transparent index present
            writeShort(out, delay);
            out.write(transparent);
            out.write(0);

            out.write(0x2C);
            writeShort(out, 0);
            writeShort(out, 0);
            writeShort(out, width);
            writeShort(out, height);
            out.write(0);

            byte[] indexes = new byte[pixels];
            for (int at = 0; at < pixels; at++) {
                int color = frame.argb[at];
                indexes[at] = (byte) (((color >>> 24) < ALPHA_FLOOR)
                        ? transparent
                        : nearest(nearest, palette, color & mask));
            }
            writeLzw(out, indexes, Math.max(2, bits));
        }

        out.write(0x3B);
    }

    /** One palette for every frame, so the colours do not swim as the animation runs. */
    private static int[] palette(List<Frame> frames, int mask, BudgetCheck budget) throws IOException {
        List<Integer> colors = new ArrayList<>();
        Map<Integer, Boolean> seen = new HashMap<>();
        int frameIndex = 0;
        for (Frame frame : frames) {
            budget.check(FramePass.PALETTE, frameIndex++);
            for (int color : frame.argb) {
                if ((color >>> 24) < ALPHA_FLOOR) continue;
                Integer rgb = color & mask;
                if (seen.put(rgb, Boolean.TRUE) == null) colors.add(rgb);
            }
        }
        if (colors.isEmpty()) return new int[]{0};
        if (colors.size() <= MAX_COLORS) {
            int[] exact = new int[colors.size()];
            for (int index = 0; index < exact.length; index++) exact[index] = colors.get(index);
            return exact;
        }
        return medianCut(colors);
    }

    /**
     * Full precision while the picture has few enough colours to count them all, coarser once
     * it does not. Counting stops as soon as the answer is known, so a photographic frame does
     * not build a map of every colour in it just to find that out.
     */
    private static int precisionMask(List<Frame> frames, BudgetCheck budget) throws IOException {
        Map<Integer, Boolean> seen = new HashMap<>();
        int frameIndex = 0;
        for (Frame frame : frames) {
            budget.check(FramePass.PRECISION_MASK, frameIndex++);
            for (int color : frame.argb) {
                if ((color >>> 24) < ALPHA_FLOOR) continue;
                seen.put(color & 0xFFFFFF, Boolean.TRUE);
                if (seen.size() > EXACT_COLOR_LIMIT) return COARSE;
            }
        }
        return 0xFFFFFF;
    }

    /**
     * Median cut: split the colours along their widest channel until there are enough boxes,
     * then take each box's average. It keeps the colours a sticker actually uses rather than
     * snapping everything to a fixed grid.
     */
    private static int[] medianCut(List<Integer> colors) {
        List<List<Integer>> boxes = new ArrayList<>();
        boxes.add(colors);
        while (boxes.size() < MAX_COLORS) {
            List<Integer> widest = null;
            int widestSpread = -1, widestChannel = 0;
            for (List<Integer> box : boxes) {
                if (box.size() < 2) continue;
                for (int channel = 0; channel < 3; channel++) {
                    int low = 255, high = 0;
                    for (int color : box) {
                        int value = (color >> (channel * 8)) & 0xFF;
                        if (value < low) low = value;
                        if (value > high) high = value;
                    }
                    if (high - low > widestSpread) {
                        widestSpread = high - low;
                        widest = box;
                        widestChannel = channel;
                    }
                }
            }
            if (widest == null || widestSpread <= 0) break;
            final int channel = widestChannel;
            Collections.sort(widest, (left, right) ->
                    Integer.compare((left >> (channel * 8)) & 0xFF, (right >> (channel * 8)) & 0xFF));
            int middle = widest.size() / 2;
            List<Integer> tail = new ArrayList<>(widest.subList(middle, widest.size()));
            List<Integer> head = new ArrayList<>(widest.subList(0, middle));
            boxes.remove(widest);
            boxes.add(head);
            boxes.add(tail);
        }

        int[] palette = new int[boxes.size()];
        for (int index = 0; index < boxes.size(); index++) {
            List<Integer> box = boxes.get(index);
            long red = 0, green = 0, blue = 0;
            for (int color : box) {
                red += (color >> 16) & 0xFF;
                green += (color >> 8) & 0xFF;
                blue += color & 0xFF;
            }
            int count = box.size();
            palette[index] = (int) ((red / count) << 16 | (green / count) << 8 | (blue / count));
        }
        return palette;
    }

    private static int nearest(Map<Integer, Integer> cache, int[] palette, int rgb) {
        Integer known = cache.get(rgb);
        if (known != null) return known;
        int red = (rgb >> 16) & 0xFF, green = (rgb >> 8) & 0xFF, blue = rgb & 0xFF;
        int best = 0, bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < palette.length; index++) {
            int candidate = palette[index];
            int dr = red - ((candidate >> 16) & 0xFF);
            int dg = green - ((candidate >> 8) & 0xFF);
            int db = blue - (candidate & 0xFF);
            int distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = index;
                if (distance == 0) break;
            }
        }
        cache.put(rgb, best);
        return best;
    }

    /** The colour table has to be a power of two, at least four entries. */
    private static int tableSize(int wanted) {
        int size = 2;
        while (size < wanted) size <<= 1;
        return Math.max(4, size);
    }

    private static void writeShort(OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    /** GIF's variable width LZW, written into sub-blocks of at most 255 bytes. */
    private static void writeLzw(OutputStream out, byte[] indexes, int minCodeSize) throws IOException {
        out.write(minCodeSize);
        BlockWriter blocks = new BlockWriter(out);
        int clearCode = 1 << minCodeSize, endCode = clearCode + 1;
        Map<Long, Integer> dictionary = new HashMap<>();
        int next = endCode + 1, codeSize = minCodeSize + 1;

        blocks.writeBits(clearCode, codeSize);
        int prefix = indexes[0] & 0xFF;
        for (int at = 1; at < indexes.length; at++) {
            int value = indexes[at] & 0xFF;
            long key = ((long) prefix << 8) | value;
            Integer found = dictionary.get(key);
            if (found != null) {
                prefix = found;
                continue;
            }
            blocks.writeBits(prefix, codeSize);
            if (next < 4096) {
                dictionary.put(key, next++);
                // One past the last code that fits, not the last that fits: a reader only
                // learns an entry when the code after it arrives, so it is always one behind,
                // and widening a code early loses everything past the first boundary.
                if (next > (1 << codeSize) && codeSize < 12) codeSize++;
            } else {
                blocks.writeBits(clearCode, codeSize);
                dictionary.clear();
                next = endCode + 1;
                codeSize = minCodeSize + 1;
            }
            prefix = value;
        }
        blocks.writeBits(prefix, codeSize);
        blocks.writeBits(endCode, codeSize);
        blocks.finish();
    }

    /** Bits go out least significant first, packed into 255 byte sub-blocks. */
    private static final class BlockWriter {
        private final OutputStream out;
        private final byte[] block = new byte[255];
        private int used;
        private int bitBuffer;
        private int bitCount;

        BlockWriter(OutputStream out) {
            this.out = out;
        }

        void writeBits(int code, int width) throws IOException {
            bitBuffer |= code << bitCount;
            bitCount += width;
            while (bitCount >= 8) {
                block[used++] = (byte) (bitBuffer & 0xFF);
                bitBuffer >>>= 8;
                bitCount -= 8;
                if (used == block.length) flush();
            }
        }

        void finish() throws IOException {
            if (bitCount > 0) {
                block[used++] = (byte) (bitBuffer & 0xFF);
                bitBuffer = 0;
                bitCount = 0;
                if (used == block.length) flush();
            }
            flush();
            out.write(0);
        }

        private void flush() throws IOException {
            if (used == 0) return;
            out.write(used);
            out.write(block, 0, used);
            used = 0;
        }
    }
}
