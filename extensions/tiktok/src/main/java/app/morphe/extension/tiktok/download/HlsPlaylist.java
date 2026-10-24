/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * One HLS playlist, read just far enough to record a LIVE: a master playlist names the variants to
 * choose between, a media playlist lists segments. Anything the recorder can't turn into a playable
 * file (encrypted segments, fragmented MP4 pieces with an init map) is flagged rather than
 * guessed at, so the recorder can say so instead of saving something that won't play.
 */
final class HlsPlaylist {
    /** One piece of the stream. {@code sequence} is its media sequence number. */
    static final class Segment {
        final String uri;
        final double seconds;
        final long sequence;
        /** True when the stream restarts its timestamps before this piece. */
        final boolean discontinuity;

        Segment(String uri, double seconds, long sequence, boolean discontinuity) {
            this.uri = uri;
            this.seconds = seconds;
            this.sequence = sequence;
            this.discontinuity = discontinuity;
        }
    }

    /** A master playlist's variant, with the bandwidth it advertises (0 when it says none). */
    static final class Variant {
        final String uri;
        final long bandwidth;

        Variant(String uri, long bandwidth) {
            this.uri = uri;
            this.bandwidth = bandwidth;
        }
    }

    final boolean master;
    final List<Variant> variants;
    final List<Segment> segments;
    final long mediaSequence;
    final double targetSeconds;
    /** True once the stream says it has finished (#EXT-X-ENDLIST). */
    final boolean ended;
    /** True when the segments are fragmented MP4 pieces that need an init section. */
    final boolean needsInitSection;
    /** True when the segments are encrypted. */
    final boolean encrypted;

    private HlsPlaylist(boolean master, List<Variant> variants, List<Segment> segments, long mediaSequence,
            double targetSeconds, boolean ended, boolean needsInitSection, boolean encrypted) {
        this.master = master;
        this.variants = variants;
        this.segments = segments;
        this.mediaSequence = mediaSequence;
        this.targetSeconds = targetSeconds;
        this.ended = ended;
        this.needsInitSection = needsInitSection;
        this.encrypted = encrypted;
    }

    /** The master playlist's variant with the most bandwidth, or null when there are none. */
    Variant best() {
        Variant best = null;
        for (Variant variant : variants) {
            if (best == null || variant.bandwidth > best.bandwidth) best = variant;
        }
        return best;
    }

    /** Reads {@code text}, resolving every address against {@code baseUrl}. Never null. */
    static HlsPlaylist parse(String text, String baseUrl) {
        List<Variant> variants = new ArrayList<>();
        List<Segment> segments = new ArrayList<>();
        long mediaSequence = 0;
        double target = 0;
        boolean ended = false;
        boolean map = false;
        boolean encrypted = false;
        boolean master = false;
        boolean discontinuity = false;
        double pendingSeconds = -1;
        long pendingBandwidth = -1;
        long sequence = 0;
        boolean sequenceSet = false;
        String[] lines = text == null ? new String[0] : text.split("\\r?\\n");
        for (String raw : lines) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.startsWith("#")) {
                if (line.startsWith("#EXT-X-STREAM-INF:")) {
                    master = true;
                    pendingBandwidth = number(attribute(line, "BANDWIDTH"));
                } else if (line.startsWith("#EXTINF:")) {
                    String value = line.substring("#EXTINF:".length());
                    int comma = value.indexOf(',');
                    pendingSeconds = decimal(comma < 0 ? value : value.substring(0, comma));
                } else if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:")) {
                    mediaSequence = number(line.substring("#EXT-X-MEDIA-SEQUENCE:".length()));
                    sequence = mediaSequence;
                    sequenceSet = true;
                } else if (line.startsWith("#EXT-X-TARGETDURATION:")) {
                    target = decimal(line.substring("#EXT-X-TARGETDURATION:".length()));
                } else if (line.startsWith("#EXT-X-DISCONTINUITY") && !line.startsWith("#EXT-X-DISCONTINUITY-SEQUENCE")) {
                    discontinuity = true;
                } else if (line.equals("#EXT-X-ENDLIST")) {
                    ended = true;
                } else if (line.startsWith("#EXT-X-MAP:")) {
                    map = true;
                } else if (line.startsWith("#EXT-X-KEY:")) {
                    String method = attribute(line, "METHOD");
                    if (method != null && !method.equalsIgnoreCase("NONE")) encrypted = true;
                }
                continue;
            }
            // An address line: a variant after STREAM-INF, a segment after EXTINF.
            if (pendingBandwidth >= 0 || (master && pendingSeconds < 0)) {
                variants.add(new Variant(resolve(baseUrl, line), Math.max(0, pendingBandwidth)));
                pendingBandwidth = -1;
            } else if (pendingSeconds >= 0) {
                segments.add(new Segment(resolve(baseUrl, line), pendingSeconds, sequence, discontinuity));
                sequence++;
                pendingSeconds = -1;
                discontinuity = false;
            }
        }
        if (!sequenceSet) mediaSequence = 0;
        return new HlsPlaylist(master, Collections.unmodifiableList(variants),
                Collections.unmodifiableList(segments), mediaSequence, target, ended, map, encrypted);
    }

    private static String resolve(String base, String address) {
        try {
            URI uri = new URI(address);
            if (uri.isAbsolute() || base == null) return address;
            return new URI(base).resolve(uri).toString();
        } catch (Exception unresolvable) {
            return address;
        }
    }

    private static String attribute(String line, String name) {
        int colon = line.indexOf(':');
        String rest = colon < 0 ? "" : line.substring(colon + 1);
        String wanted = name + "=";
        int from = 0;
        while (from < rest.length()) {
            int at = rest.indexOf(wanted, from);
            if (at < 0) return null;
            boolean atStart = at == 0 || rest.charAt(at - 1) == ',';
            if (!atStart) {
                from = at + 1;
                continue;
            }
            int start = at + wanted.length();
            if (start < rest.length() && rest.charAt(start) == '"') {
                int end = rest.indexOf('"', start + 1);
                return end < 0 ? rest.substring(start + 1) : rest.substring(start + 1, end);
            }
            int end = rest.indexOf(',', start);
            return (end < 0 ? rest.substring(start) : rest.substring(start, end)).trim();
        }
        return null;
    }

    private static long number(String value) {
        if (value == null) return -1;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return -1;
        }
    }

    private static double decimal(String value) {
        if (value == null) return 0;
        try {
            return Double.parseDouble(value.trim().toLowerCase(Locale.ROOT));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /**
     * Hands out each segment of a refreshed playlist exactly once, and none that were already
     * listed when the recording began: a recording is of what the viewer watches from the moment
     * they started it, so whatever the playlist held at that moment is the past.
     */
    static final class Follower {
        private long next = -1;
        private int missed;

        /** The segments not handed out before, oldest first. */
        List<Segment> fresh(HlsPlaylist playlist) {
            List<Segment> fresh = new ArrayList<>();
            if (playlist.segments.isEmpty()) return fresh;
            Segment last = playlist.segments.get(playlist.segments.size() - 1);
            if (next < 0) {
                next = last.sequence + 1;
                return fresh;
            }
            long first = playlist.segments.get(0).sequence;
            if (first > next) {
                // The server dropped pieces we hadn't fetched. Say so; the file will skip ahead.
                missed += (int) Math.min(Integer.MAX_VALUE, first - next);
                next = first;
            }
            for (Segment segment : playlist.segments) {
                if (segment.sequence >= next) {
                    fresh.add(segment);
                    next = segment.sequence + 1;
                }
            }
            return fresh;
        }

        /** How many segments the server dropped before they could be fetched. */
        int missedSegments() {
            return missed;
        }
    }
}
