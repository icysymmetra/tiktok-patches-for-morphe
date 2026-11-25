/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.download;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A feed video's sound as mono 16-bit PCM, for the ad skipper.
 *
 * <p>It fetches the post's smallest file that carries the sound: the separate DASH audio track
 * when TikTok sends one, a few hundred kilobytes, otherwise the lowest quality video file. The
 * fetch goes through the same pinned transport and size limits as Downloads, then the platform
 * decoder turns it to PCM at the rate the caller asks for. The file is deleted at once.
 */
public final class AdAudioSource {
    private AdAudioSource() {}

    /** Mono PCM and the rate it is at. */
    public static final class Pcm {
        public final short[] samples;
        public final int length;
        public final int sampleRate;
        /** The size of the sound file it was decoded from. */
        public long downloadedBytes;

        Pcm(short[] samples, int length, int sampleRate) {
            this.samples = samples;
            this.length = length;
            this.sampleRate = sampleRate;
        }

        public long durationMs() {
            return sampleRate <= 0 ? 0 : length * 1000L / sampleRate;
        }
    }

    /** The addresses worth trying, smallest first: DASH audio, the lowest gear, the play address. */
    public static List<String> soundUrls(Object aweme) {
        Object video = Reflect.property(aweme, "getVideo", "video");
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (video == null) return new ArrayList<>(urls);
        try {
            if (Boolean.TRUE.equals(Reflect.invoke(video, "hasDashBitrate"))) {
                urls.addAll(lowestAudio(video));
            }
        } catch (Throwable ignored) {
            // Fall through to the video file.
        }
        try {
            Object raw = QualitySelector.rawGears(video);
            if (raw instanceof List<?> && !Boolean.TRUE.equals(Reflect.invoke(video, "hasDashBitrate"))) {
                Object lowest = null;
                long lowestRate = Long.MAX_VALUE;
                for (Object gear : (List<?>) raw) {
                    Object rate = Reflect.property(gear, "getBitRate", "bitRate");
                    long value = rate instanceof Number ? ((Number) rate).longValue() : Long.MAX_VALUE - 1;
                    if (!VideoDownloads.urls(Reflect.property(gear, "getPlayAddr", "playAddr")).isEmpty()
                            && value < lowestRate) {
                        lowest = gear;
                        lowestRate = value;
                    }
                }
                if (lowest != null) urls.addAll(VideoDownloads.urls(Reflect.property(lowest, "getPlayAddr", "playAddr")));
            }
        } catch (Throwable ignored) {
            // The play address below still works.
        }
        urls.addAll(VideoDownloads.urls(Reflect.property(video, "getPlayAddr", "playAddr")));
        urls.addAll(VideoDownloads.sourceUrls(video));
        return new ArrayList<>(urls);
    }

    private static List<String> lowestAudio(Object video) {
        Object raw = Reflect.readField(video, "bitRateAudio");
        List<String> selected = new ArrayList<>();
        if (!(raw instanceof List<?>)) return selected;
        long bestRate = Long.MAX_VALUE;
        for (Object track : (List<?>) raw) {
            Object meta = Reflect.property(track, "getAudioMeta", "audioMeta");
            Object addresses = Reflect.property(meta, "getUrlList", "urlList");
            List<String> candidates = new ArrayList<>();
            for (String[] field : new String[][]{{"getMainUrl", "mainUrl"}, {"getBackupUrl", "backupUrl"}, {"getFallbackUrl", "fallbackUrl"}}) {
                String url = Reflect.string(addresses, field[0], field[1]);
                if (MediaTransport.hasAllowedShape(url)) candidates.add(url);
            }
            Object rate = Reflect.property(meta, "getBitrate", "bitrate");
            long value = rate instanceof Number ? ((Number) rate).longValue() : Long.MAX_VALUE - 1;
            if (!candidates.isEmpty() && value < bestRate) {
                selected = candidates;
                bestRate = value;
            }
        }
        return selected;
    }

    /** The picture for the editor's preview: the play address first, then the download ones. */
    public static List<String> videoUrls(Object aweme) {
        Object video = Reflect.property(aweme, "getVideo", "video");
        LinkedHashSet<String> urls = new LinkedHashSet<>();
        if (video == null) return new ArrayList<>(urls);
        urls.addAll(VideoDownloads.urls(Reflect.property(video, "getPlayAddr", "playAddr")));
        urls.addAll(VideoDownloads.sourceUrls(video));
        return new ArrayList<>(urls);
    }

    /** Whether the post's picture file carries no sound of its own (TikTok's DASH posts). */
    public static boolean separateSound(Object aweme) {
        Object video = Reflect.property(aweme, "getVideo", "video");
        return Boolean.TRUE.equals(Reflect.invoke(video, "hasDashBitrate"));
    }

    /** Downloads one of {@code urls} into the cache. The caller deletes the file. */
    public static File fetchUrls(Context context, List<String> urls, String tag) throws IOException {
        if (urls.isEmpty()) throw new IOException("This post has no media address");
        File directory = new File(context.getCacheDir(), "metra-adskip");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No cache directory");
        File target = new File(directory, tag + "-" + System.nanoTime() + ".media");
        try {
            RemoteMedia.fetch(urls, target, RemoteMedia.Kind.VIDEO);
            return target;
        } catch (IOException failure) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw failure;
        }
    }

    /** Whether the file has an audio track. */
    public static boolean hasSound(File file) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) return true;
            }
            return false;
        } catch (Exception failure) {
            return false;
        } finally {
            extractor.release();
        }
    }

    /** Deletes what earlier runs left in the cache folder. */
    public static void sweep(Context context) {
        File[] files = new File(context.getCacheDir(), "metra-adskip").listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - 10 * 60 * 1000L;
        for (File file : files) {
            //noinspection ResultOfMethodCallIgnored
            if (file.lastModified() < cutoff) file.delete();
        }
    }

    /** Downloads the sound into the cache. The caller deletes the file. */
    public static File fetch(Context context, Object aweme, String tag) throws IOException {
        List<String> urls = soundUrls(aweme);
        if (urls.isEmpty()) throw new IOException("This post has no sound address");
        File directory = new File(context.getCacheDir(), "metra-adskip");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("No cache directory");
        File target = new File(directory, tag + "-" + System.nanoTime() + ".media");
        try {
            RemoteMedia.fetch(urls, target, RemoteMedia.Kind.VIDEO);
            return target;
        } catch (IOException failure) {
            //noinspection ResultOfMethodCallIgnored
            target.delete();
            throw failure;
        }
    }

    /**
     * Decodes the file's first audio track to mono PCM at {@code outRate}, at most
     * {@code maxMs} of it. {@code cancel} stops the decode early.
     */
    public static Pcm decode(File file, int outRate, long maxMs, AtomicBoolean cancel) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        try {
            extractor.setDataSource(file.getAbsolutePath());
            int track = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat candidate = extractor.getTrackFormat(i);
                String mime = candidate.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    track = i;
                    format = candidate;
                    break;
                }
            }
            if (track < 0) throw new IOException("The file has no sound");
            extractor.selectTrack(track);
            String mime = format.getString(MediaFormat.KEY_MIME);
            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();

            int inRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
            int channels = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ? format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 2;
            PcmResampler resampler = null;
            int resamplerIn = -1;

            long maxSamples = outRate * Math.max(1L, maxMs) / 1000L;
            short[] out = new short[(int) Math.min(maxSamples, outRate * 60L)];
            int length = 0;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false, outputDone = false;
            int idle = 0;
            while (!outputDone) {
                if (cancel != null && cancel.get()) throw new IOException("Cancelled");
                if (!inputDone) {
                    int in = codec.dequeueInputBuffer(10_000);
                    if (in >= 0) {
                        ByteBuffer buffer = codec.getInputBuffer(in);
                        int size = buffer == null ? -1 : extractor.readSampleData(buffer, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(in, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            codec.queueInputBuffer(in, 0, size, extractor.getSampleTime(), 0);
                            extractor.advance();
                        }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat changed = codec.getOutputFormat();
                    if (changed.containsKey(MediaFormat.KEY_SAMPLE_RATE)) inRate = changed.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                    if (changed.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) channels = changed.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                    continue;
                }
                if (index < 0) {
                    if (inputDone && ++idle > 300) break;
                    continue;
                }
                idle = 0;
                ByteBuffer output = codec.getOutputBuffer(index);
                if (output != null && info.size > 0) {
                    output.position(info.offset);
                    output.limit(info.offset + info.size);
                    ShortBuffer shorts = output.order(ByteOrder.nativeOrder()).asShortBuffer();
                    int frames = shorts.remaining() / Math.max(1, channels);
                    short[] mono = new short[frames];
                    for (int f = 0; f < frames; f++) {
                        int sum = 0;
                        for (int c = 0; c < channels; c++) sum += shorts.get(f * channels + c);
                        mono[f] = (short) (sum / channels);
                    }
                    if (resampler == null || resamplerIn != inRate) {
                        resampler = new PcmResampler(inRate, outRate, 1);
                        resamplerIn = inRate;
                    }
                    short[] converted = resampler.process(mono, frames, false);
                    int take = Math.min(converted.length, (int) Math.min(Integer.MAX_VALUE, maxSamples - length));
                    if (take > 0) {
                        if (length + take > out.length) {
                            out = java.util.Arrays.copyOf(out, (int) Math.min(maxSamples, Math.max(out.length * 2L, length + take)));
                        }
                        System.arraycopy(converted, 0, out, length, take);
                        length += take;
                    }
                    if (length >= maxSamples) outputDone = true;
                }
                codec.releaseOutputBuffer(index, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true;
            }
            return new Pcm(out, length, outRate);
        } catch (IOException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IOException("Could not decode the sound", failure);
        } finally {
            if (codec != null) {
                try { codec.stop(); } catch (Exception ignored) { }
                try { codec.release(); } catch (Exception ignored) { }
            }
            extractor.release();
        }
    }

    /** Fetch, decode, delete. */
    public static Pcm load(Context context, Object aweme, int outRate, long maxMs, AtomicBoolean cancel)
            throws IOException {
        File file = fetch(context, aweme, "pcm");
        try {
            Pcm pcm = decode(file, outRate, maxMs, cancel);
            pcm.downloadedBytes = file.length();
            return pcm;
        } finally {
            if (!file.delete()) Logger.printDebug(() -> "Ad skip: could not delete " + file.getName());
        }
    }
}
