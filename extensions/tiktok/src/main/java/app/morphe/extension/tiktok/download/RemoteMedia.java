/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

final class RemoteMedia {
    private RemoteMedia() {}

    /**
     * What the caller is expecting back, which decides the signatures a body may carry.
     *
     * <p>{@link #VIDEO} is anything that has to arrive in an MP4 container, the separate DASH
     * audio stream included: {@code TrackMuxer} reads those with a {@code MediaExtractor} and
     * a bare MP3 would be useless to it. {@link #AUDIO} is a sound saved as its own file, which
     * TikTok serves in more than one container.
     */
    enum Kind { IMAGE, VIDEO, AUDIO }

    interface Progress {
        void copied(long bytes, long expected);
    }

    static String fetch(List<String> urls, File target, Kind kind, Progress progress) throws IOException {
        return fetch(urls, target, kind, MediaTransport.DEFAULT, progress);
    }

    static String fetch(List<String> urls, File target, Kind kind) throws IOException {
        return fetch(urls, target, kind, MediaTransport.DEFAULT);
    }

    static String fetch(
            List<String> urls,
            File target,
            Kind kind,
            MediaTransport.Client transport
    ) throws IOException {
        return fetch(urls, target, kind, transport, null);
    }

    static String fetch(List<String> urls, File target, Kind kind,
            MediaTransport.Client transport, Progress progress) throws IOException {
        IOException failure = new IOException("No media URL succeeded");
        MediaBudget.Deadline deadline = MediaBudget.deadline();
        for (String url : urls == null ? Collections.<String>emptyList() : urls) {
            for (int attempt = 0; attempt < MediaBudget.MAX_ATTEMPTS_PER_MIRROR; attempt++) {
                try (MediaTransport.Response response = transport.open(
                        url, deadline, 15000, 30000, null, false)) {
                    MediaBudget.check(deadline);
                    int responseCode = response.statusCode;
                    if (MediaBudget.isTransientStatus(responseCode)
                            || (responseCode >= 300 && response.header("Retry-After") != null)) {
                        String retryAfter = response.header("Retry-After");
                        response.close();
                        MediaBudget.waitBeforeRetry(retryAfter, attempt, deadline);
                        if (MediaBudget.isTransientStatus(responseCode)
                                && attempt + 1 < MediaBudget.MAX_ATTEMPTS_PER_MIRROR) continue;
                    }
                    if (responseCode != 200) throw new IOException("Media server returned " + responseCode);
                    long expected = contentLength(response.header("Content-Length"));
                    MediaBudget.checkTransferLength(expected);
                    File targetDirectory = target == null ? null : target.getParentFile();
                    MediaBudget.checkDiskSpace(targetDirectory, expected, deadline);
                    try (BufferedInputStream input = new BufferedInputStream(response.inputStream())) {
                        input.mark(32);
                        byte[] header = new byte[16];
                        int offset = 0, read;
                        while (offset < header.length && (read = input.read(header, offset, header.length - offset)) != -1) offset += read;
                        if (offset != header.length) throw new IOException("Media response is too short");
                        input.reset();
                        String extension = extensionFor(kind, header);
                        if (extension == null) throw new IOException("Media server returned an unsupported format");
                        long count;
                        if (progress != null) progress.copied(0, expected);
                        try (FileOutputStream output = new FileOutputStream(target)) {
                            count = MediaFileWriter.copy(input, output,
                                    MediaBudget.MAX_TRANSFER_BYTES, deadline, targetDirectory,
                                    progress == null ? null : countSoFar -> progress.copied(countSoFar, expected));
                        }
                        if (expected >= 0 && count != expected) throw new IOException("Media download is incomplete");
                        return extension;
                }
                } catch (IOException | RuntimeException exception) {
                    boolean cleaned = target == null || MediaCache.deletePartial(target);
                    if (exception instanceof MediaBudget.StopException) {
                        if (!cleaned) exception.addSuppressed(
                                new IOException("Could not remove partial media output"));
                        throw (MediaBudget.StopException) exception;
                    }
                    boolean retryable = MediaBudget.isRetryableTransport(exception);
                    if (cleaned && retryable && attempt + 1 < MediaBudget.MAX_ATTEMPTS_PER_MIRROR) {
                        MediaBudget.waitBeforeRetry(null, attempt, deadline);
                        continue;
                    }
                    // URL parsing and transport failures stay with this mirror. The aggregate
                    // message contains only a query-free, bounded address summary.
                    failure.addSuppressed(new IOException(
                            "Media mirror failed (" + exception.getClass().getSimpleName() + "): "
                                     + summarizeUrl(url), exception));
                    if (!cleaned) failure.addSuppressed(
                            new IOException("Could not remove partial media output"));
                    break;
                }
            }
        }
        if (target != null) MediaCache.deletePartial(target);
        throw failure;
    }

    static String summarizeUrl(String url) {
        if (url == null) return "null";
        int query = url.indexOf('?');
        String withoutQuery = query < 0 ? url : url.substring(0, query);
        int schemeEnd = withoutQuery.indexOf("://");
        if (schemeEnd >= 0) {
            int userInfoEnd = withoutQuery.indexOf('@', schemeEnd + 3);
            if (userInfoEnd >= 0) {
                withoutQuery = withoutQuery.substring(0, schemeEnd + 3)
                        + withoutQuery.substring(userInfoEnd + 1);
            }
        }
        return withoutQuery.length() <= 96 ? withoutQuery : withoutQuery.substring(0, 96) + "...";
    }

    private static long contentLength(String header) {
        if (header == null) return -1L;
        try {
            return Long.parseLong(header.trim());
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }

    private static String extensionFor(Kind kind, byte[] header) {
        switch (kind) {
            case IMAGE:
                return imageExtension(header);
            case AUDIO:
                return audioExtension(header);
            default:
                return header.length >= 8
                        && new String(header, 4, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("ftyp")
                        ? "mp4" : null;
        }
    }

    /**
     * What the sound in this body actually is, read from its own header.
     *
     * <p>A sound entry is not one container. TikTok hands back an MP3 for some addresses and an
     * MP4 audio track for others, which is why the extension and the type are settled here
     * rather than assumed by the caller: a file named .m4a holding MPEG frames is one a gallery
     * refuses to play.
     */
    private static String audioExtension(byte[] header) {
        if (header.length < 12) return null;
        if (header[0] == 'I' && header[1] == 'D' && header[2] == '3') return "mp3";
        // An MPEG frame sync is eleven set bits, so the second byte keeps its top three. ADTS
        // AAC shares that sync, and the two layer bits are what separate them: MPEG audio never
        // leaves them at zero and ADTS always does. Without this an .aac body was saved as an
        // .mp3 that nothing would play.
        if ((header[0] & 255) == 255 && (header[1] & 0xE0) == 0xE0) {
            return (header[1] & 0x06) == 0 ? "aac" : "mp3";
        }
        String signature = new String(header, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (signature.substring(4, 8).equals("ftyp")) return "m4a";
        if (signature.startsWith("OggS")) return "ogg";
        if (signature.startsWith("fLaC")) return "flac";
        if (signature.startsWith("RIFF") && signature.substring(8, 12).equals("WAVE")) return "wav";
        return null;
    }

    private static String imageExtension(byte[] header) {
        if (header.length < 12) return null;
        if ((header[0] & 255) == 255 && (header[1] & 255) == 216) return "jpg";
        if (header[0] == (byte) 137 && header[1] == 80 && header[2] == 78 && header[3] == 71) return "png";
        String signature = new String(header, java.nio.charset.StandardCharsets.ISO_8859_1);
        if (signature.startsWith("GIF8")) return "gif";
        if (signature.startsWith("RIFF") && signature.substring(8, 12).equals("WEBP")) return "webp";
        if (signature.substring(4, 8).equals("ftyp")) {
            String brand = signature.substring(8, 12);
            if (brand.equals("avif") || brand.equals("avis")) return "avif";
            if (brand.equals("heic") || brand.equals("heix")) return "heic";
            if (brand.equals("mif1") || brand.equals("msf1")) return "heif";
        }
        return null;
    }
}
