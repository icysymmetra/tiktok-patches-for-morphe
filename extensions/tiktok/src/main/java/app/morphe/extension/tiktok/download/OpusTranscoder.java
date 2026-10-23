/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;

/**
 * Turns a sound into Opus in an Ogg file: decoded by whatever decoder its track asks for, moved
 * to a rate the Opus encoder takes, and encoded again. Android 10 and later only, which is where
 * both the Opus encoder and the muxer's Ogg output arrived.
 *
 * <p>Robolectric has no real codecs, so the arithmetic lives where tests reach it: the rate
 * change in {@link PcmResampler}, and the rate and bit rate picks here. What's left is the order
 * the codec calls go in, the same loop {@link SlideshowEncoder#soundTrack} runs for AAC.
 */
final class OpusTranscoder {
    static final String MIME = "audio/opus";
    /** The only rates Android's Opus encoder takes. */
    private static final int[] RATES = {8000, 12000, 16000, 24000, 48000};
    private static final long TIMEOUT_US = 10_000L;
    /** Turns in a row with nothing moving, each with a short wait, before the sound counts as stuck. */
    private static final int MAX_IDLE_TURNS = 500;
    /** android.media.AudioFormat.ENCODING_PCM_16BIT, the only sample shape this hands the encoder. */
    private static final int PCM_16BIT = 2;

    private OpusTranscoder() {}

    /** The rate a sound at {@code inRate} is encoded at: its own when Opus takes it, else 48 kHz. */
    static int encoderRate(int inRate) {
        for (int rate : RATES) if (rate == inRate) return inRate;
        return 48000;
    }

    /**
     * Opus at 96 kbps for stereo is about where listening tests stop telling it from the source,
     * and it's under the 128 kbps AAC TikTok serves most sounds at, so the file comes out smaller.
     */
    static int bitRate(int channels) {
        return channels == 1 ? 48_000 : 96_000;
    }

    /** True when the first sound track in {@code file} is Opus already, so there's nothing to do. */
    static boolean isOpus(File file) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) return MIME.equalsIgnoreCase(mime);
            }
        } catch (IOException | RuntimeException unreadable) {
            Logger.printInfo(() -> "Could not read the sound's format: " + unreadable);
        } finally {
            extractor.release();
        }
        return false;
    }

    /**
     * {@link #transcode}, or false when it failed, so the caller saves the sound as TikTok sent it
     * rather than losing the save to an encoder this phone gets wrong. The reader is told why the
     * file isn't an .ogg. A refusal from the budget (no room, out of time) still throws, since the
     * other format wouldn't get through either.
     */
    static boolean transcodeOrKeep(File source, File output) throws MediaBudget.StopException {
        try {
            transcode(source, output);
            return true;
        } catch (MediaBudget.StopException refusal) {
            throw refusal;
        } catch (IOException | RuntimeException failure) {
            Logger.printException(() -> "Opus encode failed, keeping the sound as TikTok sent it", failure);
            Utils.showToastLong(L10n.t("Opus didn't work on this phone. Saving the sound as TikTok sent it instead."));
            return false;
        }
    }

    static void transcode(File source, File output) throws IOException {
        if (android.os.Build.VERSION.SDK_INT < 29) throw new IOException("Opus needs Android 10 or later");
        MediaBudget.check(null);
        MediaBudget.checkDiskSpace(output.getParentFile(), source.length());
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec decoder = null, encoder = null;
        MediaMuxer muxer = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            MediaFormat input = selectAudio(extractor);
            decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
            decoder.configure(input, null, null, 0);
            decoder.start();
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            PcmResampler resampler = null;
            // Resampled sound the encoder hasn't taken yet, and how far into it it has got.
            short[] waiting = null;
            int waitingAt = 0;
            int track = -1, inRate = 0, rate = 0, channels = 0, samples = 0, idle = 0;
            long queuedFrames = 0, shift = 0;
            byte[] config = null;
            boolean fed = false, decoded = false, closed = false, done = false, muxing = false, moved = true;
            while (!done) {
                MediaBudget.check(null);
                long wait = moved ? 0 : TIMEOUT_US;
                moved = false;

                // Compressed samples into the decoder. A negative stamp is the AAC priming
                // TrackMuxer.leadIn describes, and the encoder side is stamped by count anyway.
                if (!fed) {
                    int index = decoder.dequeueInputBuffer(0);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            fed = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, Math.max(0, extractor.getSampleTime()), 0);
                            extractor.advance();
                        }
                        moved = true;
                    }
                }

                // One decoded buffer at a time, resampled, held until the encoder has all of it.
                if (waiting == null && !decoded) {
                    int index = decoder.dequeueOutputBuffer(info, wait);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED || (index >= 0 && encoder == null)) {
                        MediaFormat raw = decoder.getOutputFormat();
                        if (raw.containsKey("pcm-encoding") && raw.getInteger("pcm-encoding") != PCM_16BIT) {
                            throw new IOException("The sound decodes to samples the encoder can't take");
                        }
                        int nowRate = raw.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        int nowChannels = raw.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (encoder == null) {
                            if (nowRate <= 0 || nowChannels < 1 || nowChannels > 2) {
                                throw new IOException("The sound has " + nowChannels + " channels at " + nowRate + " Hz");
                            }
                            inRate = nowRate;
                            channels = nowChannels;
                            rate = encoderRate(inRate);
                            resampler = new PcmResampler(inRate, rate, channels);
                            encoder = opusEncoder(rate, channels);
                        } else if (nowRate != inRate || nowChannels != channels) {
                            throw new IOException("The sound changed shape partway through");
                        }
                        moved = true;
                    }
                    if (index >= 0) {
                        moved = true;
                        boolean last = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                        ByteBuffer data = info.size > 0 ? decoder.getOutputBuffer(index) : null;
                        short[] pcm = new short[0];
                        int frames = 0;
                        if (data != null) {
                            data.position(info.offset);
                            data.limit(info.offset + info.size);
                            ShortBuffer shorts = data.order(ByteOrder.nativeOrder()).asShortBuffer();
                            // A stray half frame at a buffer's end can't be encoded on its own.
                            frames = shorts.remaining() / channels;
                            pcm = new short[frames * channels];
                            shorts.get(pcm);
                        }
                        decoder.releaseOutputBuffer(index, false);
                        short[] resampled = resampler.process(pcm, frames, last);
                        if (resampled.length > 0) {
                            waiting = resampled;
                            waitingAt = 0;
                        }
                        if (last) decoded = true;
                    }
                }

                // Samples into the encoder, stamped by how many came before.
                if (encoder != null && !closed && (waiting != null || decoded)) {
                    int index = encoder.dequeueInputBuffer(0);
                    if (index >= 0) {
                        moved = true;
                        long time = queuedFrames * 1_000_000L / rate;
                        if (waiting != null) {
                            ByteBuffer target = encoder.getInputBuffer(index);
                            int room = target == null ? 0 : target.capacity() / (2 * channels) * channels;
                            if (room == 0) throw new IOException("The sound encoder has no room for a sample");
                            int count = Math.min(room, waiting.length - waitingAt);
                            target.clear();
                            target.order(ByteOrder.nativeOrder()).asShortBuffer().put(waiting, waitingAt, count);
                            encoder.queueInputBuffer(index, 0, count * 2, time, 0);
                            waitingAt += count;
                            queuedFrames += count / channels;
                            if (waitingAt >= waiting.length) waiting = null;
                        } else {
                            encoder.queueInputBuffer(index, 0, 0, time, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            closed = true;
                        }
                    }
                }

                // Opus out to the file.
                if (encoder != null) {
                    int index = encoder.dequeueOutputBuffer(info, moved ? 0 : wait);
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (muxing) throw new IOException("The sound encoder changed format twice");
                        track = startMuxer(muxer, encoder.getOutputFormat(), config);
                        muxing = true;
                        moved = true;
                    } else if (index >= 0) {
                        moved = true;
                        ByteBuffer data = encoder.getOutputBuffer(index);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            // The Opus header. The output format normally carries it as csd-0,
                            // and this is kept for an encoder whose format doesn't.
                            if (data != null && info.size > 0) {
                                config = new byte[info.size];
                                data.position(info.offset);
                                data.get(config);
                            }
                            info.size = 0;
                        }
                        if (info.size > 0 && data != null) {
                            if (!muxing) throw new IOException("The sound muxer hasn't started");
                            // The muxer refuses a stamp before zero, so a lead-in moves the lot.
                            if (samples == 0 && info.presentationTimeUs < 0) shift = info.presentationTimeUs;
                            info.presentationTimeUs = Math.max(0, info.presentationTimeUs - shift);
                            data.position(info.offset);
                            data.limit(info.offset + info.size);
                            MediaBudget.checkDiskSpace(output.getParentFile(), info.size);
                            muxer.writeSampleData(track, data, info);
                            samples++;
                        }
                        encoder.releaseOutputBuffer(index, false);
                        if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) done = true;
                    }
                } else if (decoded) {
                    break;
                }

                if (moved) idle = 0;
                else if (++idle > MAX_IDLE_TURNS) throw new IOException("The sound conversion stopped moving");
            }
            if (samples == 0) throw new IOException("The sound gave nothing to encode");
            muxer.stop();
            MediaBudget.check(null);
        } finally {
            stopAndRelease(decoder);
            stopAndRelease(encoder);
            extractor.release();
            releaseMuxer(muxer);
        }
        if (output.length() == 0) throw new IOException("The Opus muxer wrote an empty file");
    }

    private static int startMuxer(MediaMuxer muxer, MediaFormat format, byte[] config) {
        if (!format.containsKey("csd-0") && config != null) format.setByteBuffer("csd-0", ByteBuffer.wrap(config));
        int track = muxer.addTrack(format);
        muxer.start();
        return track;
    }

    private static MediaCodec opusEncoder(int rate, int channels) throws IOException {
        MediaFormat format = MediaFormat.createAudioFormat(MIME, rate, channels);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate(channels));
        MediaCodec codec = MediaCodec.createEncoderByType(MIME);
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            return codec;
        } catch (RuntimeException refused) {
            codec.release();
            throw refused;
        }
    }

    private static MediaFormat selectAudio(MediaExtractor extractor) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                extractor.selectTrack(i);
                return format;
            }
        }
        throw new IOException("The sound file has no audio track");
    }

    private static void stopAndRelease(MediaCodec codec) {
        if (codec == null) return;
        try {
            codec.stop();
        } catch (RuntimeException ignored) {
            // Already failed or never started. Release below is what matters.
        }
        try {
            codec.release();
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Could not release a sound codec: " + failure);
        }
    }

    /** A muxer started and never stopped throws from release over its empty track; that says nothing new. */
    private static void releaseMuxer(MediaMuxer muxer) {
        if (muxer == null) return;
        try {
            muxer.release();
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Could not release the Opus muxer cleanly: " + failure);
        }
    }
}
