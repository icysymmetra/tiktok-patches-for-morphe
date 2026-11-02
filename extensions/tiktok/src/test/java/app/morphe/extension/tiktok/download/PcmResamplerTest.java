/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Random;

import org.junit.Test;

/** The rate change between the sound's decoder and the Opus encoder, which only takes 48 kHz and its divisors. */
public class PcmResamplerTest {
    private static short[] tone(int frames, int rate, double hertz, int channels, int loudChannel) {
        short[] pcm = new short[frames * channels];
        for (int i = 0; i < frames; i++) {
            pcm[i * channels + loudChannel] = (short) Math.round(20000 * Math.sin(2 * Math.PI * hertz * i / rate));
        }
        return pcm;
    }

    private static short[] all(PcmResampler resampler, short[] input, int channels) {
        return resampler.process(input, input.length / channels, true);
    }

    @Test public void aRateOpusTakesIsCopiedSampleForSample() {
        short[] input = tone(4800, 48000, 440, 2, 0);
        assertArrayEquals(input, all(new PcmResampler(48000, 48000, 2), input, 2));
        assertEquals(48000, OpusTranscoder.encoderRate(48000));
        assertEquals(24000, OpusTranscoder.encoderRate(24000));
        assertEquals("44.1 kHz isn't one Opus takes", 48000, OpusTranscoder.encoderRate(44100));
        assertEquals(48000, OpusTranscoder.encoderRate(22050));
    }

    @Test public void oneSecondInIsOneSecondOut() {
        PcmResampler resampler = new PcmResampler(44100, 48000, 1);
        short[] out = all(resampler, tone(44100, 44100, 1000, 1, 0), 1);
        assertEquals("the sound came out a different length", 48000, out.length);
        assertEquals(48000, resampler.produced());

        PcmResampler down = new PcmResampler(48000, 16000, 2);
        assertEquals(16000 * 2, all(down, tone(48000, 48000, 300, 2, 1), 2).length);
    }

    /**
     * The decoder hands its sound over in buffers of whatever size it likes, so where they're
     * cut can't change a single sample.
     */
    @Test public void howTheInputIsCutChangesNothing() {
        short[] input = tone(20000, 44100, 1234, 2, 0);
        for (int i = 0; i < input.length; i += 2) input[i + 1] = (short) (i * 7);
        short[] whole = all(new PcmResampler(44100, 48000, 2), input, 2);

        Random random = new Random(84);
        PcmResampler pieces = new PcmResampler(44100, 48000, 2);
        short[] joined = new short[0];
        int at = 0;
        while (at < input.length / 2) {
            int frames = Math.min(input.length / 2 - at, random.nextInt(700));
            short[] piece = Arrays.copyOfRange(input, at * 2, (at + frames) * 2);
            joined = join(joined, pieces.process(piece, frames, false));
            at += frames;
        }
        joined = join(joined, pieces.process(new short[0], 0, true));
        assertArrayEquals(whole, joined);
    }

    @Test public void aToneComesOutAsTheSameTone() {
        short[] out = all(new PcmResampler(44100, 48000, 1), tone(44100, 44100, 1000, 1, 0), 1);
        short[] ideal = tone(48000, 48000, 1000, 1, 0);
        int worst = 0;
        // The last two samples have no neighbours past them to curve through.
        for (int i = 0; i < out.length - 2; i++) worst = Math.max(worst, Math.abs(out[i] - ideal[i]));
        assertTrue("a 1 kHz tone was off by " + worst + " of 20000", worst < 100);
    }

    @Test public void eachChannelKeepsToItself() {
        short[] out = all(new PcmResampler(44100, 48000, 2), tone(8820, 44100, 500, 2, 0), 2);
        boolean left = false;
        for (int i = 0; i < out.length; i += 2) {
            assertEquals("the left channel leaked into the right", 0, out[i + 1]);
            left |= out[i] != 0;
        }
        assertTrue(left);
    }

    /**
     * The curve overshoots a sudden jump. At full scale the overshoot has to stop at the
     * loudest sample there is, because a short cast past it turns the loudest sound into the
     * quietest and the jump into a click.
     */
    @Test public void anOvershootPastFullScaleClipsInsteadOfWrapping() {
        short[] input = new short[40];
        Arrays.fill(input, 0, 20, Short.MIN_VALUE);
        Arrays.fill(input, 20, 40, Short.MAX_VALUE);
        short[] out = all(new PcmResampler(44100, 48000, 1), input, 1);
        boolean between = false;
        for (int k = 0; k < out.length; k++) {
            long scaled = (long) k * 44100;
            if (scaled / 48000 < 20) continue;
            between |= scaled % 48000 != 0 && scaled / 48000 == 20;
            assertTrue("sample " + k + " wrapped to " + out[k], out[k] > 30000);
        }
        assertTrue("no output fell inside the overshoot", between);
    }

    private static short[] join(short[] a, short[] b) {
        short[] both = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, both, a.length, b.length);
        return both;
    }
}
