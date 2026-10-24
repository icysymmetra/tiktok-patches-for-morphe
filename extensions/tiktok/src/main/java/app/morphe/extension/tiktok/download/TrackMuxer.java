/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import app.morphe.extension.shared.Logger;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Copies compressed samples and original timestamps; it does not re-encode either track. */
final class TrackMuxer {
    private TrackMuxer() {}

    static void combine(File video, File audio, File output) throws IOException {
        MediaBudget.check(null);
        MediaBudget.checkDiskSpace(output.getParentFile(), video.length() + audio.length());
        MediaExtractor picture = new MediaExtractor(), sound = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            picture.setDataSource(video.getAbsolutePath());
            sound.setDataSource(audio.getAbsolutePath());
            MediaFormat videoFormat = select(picture, "video/"), audioFormat = select(sound, "audio/");
            // One shift for both tracks, so the sound stays where it was against the picture.
            long shift = Math.min(leadIn(picture), leadIn(sound));
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack = muxer.addTrack(videoFormat), audioTrack = muxer.addTrack(audioFormat);
            if (videoFormat.containsKey("rotation-degrees")) muxer.setOrientationHint(videoFormat.getInteger("rotation-degrees"));
            muxer.start();
            copy(picture, muxer, videoTrack, shift);
            copy(sound, muxer, audioTrack, shift);
            muxer.stop();
            MediaBudget.check(null);
        } finally {
            picture.release();
            sound.release();
            release(muxer);
        }
        if (output.length() == 0) throw new IOException("Video muxer wrote an empty file");
    }

    /**
     * Lets the muxer go without letting its complaint replace the one that got here. A muxer
     * that was started and never stopped, because a copy threw before the track had a sample,
     * stops itself inside release() and throws over an empty track; out of a finally block
     * that throw discarded the failure that emptied it.
     */
    private static void release(MediaMuxer muxer) {
        if (muxer == null) return;
        try {
            muxer.release();
        } catch (RuntimeException exception) {
            Logger.printInfo(() -> "Could not release the muxer cleanly: " + exception);
        }
    }

    /** Copies just the sound into its own MP4 container, which is what an .m4a is. */
    static void audioOnly(File source, File output) throws IOException {
        MediaBudget.check(null);
        MediaBudget.checkDiskSpace(output.getParentFile(), source.length());
        MediaExtractor sound = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            sound.setDataSource(source.getAbsolutePath());
            MediaFormat audioFormat = select(sound, "audio/");
            long shift = leadIn(sound);
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int audioTrack = muxer.addTrack(audioFormat);
            muxer.start();
            copy(sound, muxer, audioTrack, shift);
            muxer.stop();
            MediaBudget.check(null);
        } finally {
            sound.release();
            release(muxer);
        }
        if (output.length() == 0) throw new IOException("Audio muxer wrote an empty file");
    }

    /** Copies just the picture, which is a download of a video with the sound left out. */
    static void videoOnly(File source, File output) throws IOException {
        MediaBudget.check(null);
        MediaBudget.checkDiskSpace(output.getParentFile(), source.length());
        MediaExtractor picture = new MediaExtractor();
        MediaMuxer muxer = null;
        try {
            picture.setDataSource(source.getAbsolutePath());
            MediaFormat videoFormat = select(picture, "video/");
            long shift = leadIn(picture);
            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            int videoTrack = muxer.addTrack(videoFormat);
            if (videoFormat.containsKey("rotation-degrees")) {
                muxer.setOrientationHint(videoFormat.getInteger("rotation-degrees"));
            }
            muxer.start();
            copy(picture, muxer, videoTrack, shift);
            muxer.stop();
            MediaBudget.check(null);
        } finally {
            picture.release();
            release(muxer);
        }
        if (output.length() == 0) throw new IOException("Video muxer wrote an empty file");
    }

    /**
     * Package-private, with {@link #leadIn} and {@link #copy}, for {@link CaptionBurner}, which
     * copies a video's sound across the same way.
     */
    static MediaFormat select(MediaExtractor extractor, String prefix) throws IOException {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) {
                extractor.selectTrack(i);
                return format;
            }
        }
        throw new IOException("Download is missing its " + prefix + " track");
    }

    /** Past this a sample is not a frame any more, and a direct buffer that size is its own problem. */
    private static final int MAX_SAMPLE_BYTES = 64 * 1024 * 1024;

    /**
     * How far before zero a selected track starts, as a number to take off every stamp.
     *
     * <p>TikTok's AAC tracks carry an edit list that puts the encoder's priming frames ahead
     * of the picture, and the extractor reports those first samples at a negative time (the
     * S22's downloads start at -161 ms). The muxer refuses a negative stamp, and the copy loop
     * used to read the first negative stamp as the end of the track, so "Save the sound as
     * well" wrote nothing and said "Download contains no media samples". A track that starts
     * at or after zero is left exactly as it is.
     */
    static long leadIn(MediaExtractor extractor) {
        long first = extractor.getSampleTime();
        return first < 0 ? first : 0L;
    }

    static void copy(MediaExtractor extractor, MediaMuxer muxer, int track, long shift) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocateDirect(8 * 1024 * 1024);
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int samples = 0;
        // The end of the track is the read that hands back nothing. A negative stamp is not
        // the end: it is the lead-in, which the shift moves to zero.
        while (true) {
            MediaBudget.check(null);
            if ((extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_ENCRYPTED) != 0) throw new IOException("Encrypted media cannot be saved");
            // From API 28 the extractor will say how big the sample is, so the buffer is sized
            // once. Below that it will not, and readSampleData rejects a buffer the sample does
            // not fit in, which threw IllegalArgumentException out of a method declaring
            // IOException. The retry covers those releases and grows one step at a time so a
            // single oversized sample cannot reserve every size on the way up.
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                long size = extractor.getSampleSize();
                if (size > MAX_SAMPLE_BYTES) throw new IOException("A media sample is too large to copy");
                if (size > buffer.capacity()) buffer = ByteBuffer.allocateDirect((int) size);
            }

            int count;
            while (true) {
                buffer.clear();
                try {
                    count = extractor.readSampleData(buffer, 0);
                    break;
                } catch (IllegalArgumentException tooSmall) {
                    int grown = buffer.capacity() * 2;
                    if (grown > MAX_SAMPLE_BYTES) {
                        throw new IOException("A media sample is too large to copy", tooSmall);
                    }
                    buffer = ByteBuffer.allocateDirect(grown);
                }
            }
            if (count < 0) break;
            info.set(0, count, extractor.getSampleTime() - shift,
                    (extractor.getSampleFlags() & MediaExtractor.SAMPLE_FLAG_SYNC) != 0 ? MediaCodec.BUFFER_FLAG_KEY_FRAME : 0);
            muxer.writeSampleData(track, buffer, info);
            samples++;
            if (!extractor.advance()) break;
        }
        if (samples == 0) throw new IOException("Download contains no media samples");
    }
}
