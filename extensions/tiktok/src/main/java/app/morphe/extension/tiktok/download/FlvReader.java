/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads an FLV stream (what a LIVE's flv address serves) and hands out its H.264 or HEVC video and
 * AAC audio as the pieces an MP4 needs: the decoder settings once, then each sample with its time.
 * Video is converted from FLV's length-prefixed NAL units to start-code form, which is what
 * MediaMuxer wants. It stops at the end of the input, and a tag cut off by the end (a recording
 * that ended mid-write) is dropped rather than reported as an error.
 */
final class FlvReader {
    static final String MIME_AVC = "video/avc";
    static final String MIME_HEVC = "video/hevc";

    interface Listener {
        /**
         * The decoder settings, once, with start codes. For H.264 {@code csd0} is the SPS and
         * {@code csd1} the PPS. For HEVC {@code csd0} holds the VPS, SPS and PPS and {@code csd1} is null.
         */
        void onVideoConfig(String mime, byte[] csd0, byte[] csd1, int width, int height) throws IOException;

        /** The AAC AudioSpecificConfig, once. */
        void onAudioConfig(byte[] specific, int sampleRate, int channels) throws IOException;

        void onVideo(byte[] annexB, long presentationUs, boolean keyFrame) throws IOException;

        void onAudio(byte[] aac, long presentationUs) throws IOException;
    }

    /** Raised for a stream this reader can't turn into an MP4, such as enhanced FLV video. */
    static final class UnsupportedStream extends IOException {
        UnsupportedStream(String message) {
            super(message);
        }
    }

    private static final byte[] START_CODE = {0, 0, 0, 1};
    private static final int TAG_AUDIO = 8;
    private static final int TAG_VIDEO = 9;
    private static final int CODEC_AVC = 7;
    /**
     * HEVC the way Chinese CDNs (TikTok's among them) put it in FLV: codec id 12, with the same
     * packet layout as H.264. Enhanced FLV's HEVC (the 0x80 bit and a "hvc1" tag) is another layout.
     */
    private static final int CODEC_HEVC = 12;
    /** A tag larger than this is garbage, not a frame. */
    private static final int MAX_TAG_BYTES = 8 * 1024 * 1024;

    private final boolean hevc;
    private int codec = -1;
    private int nalLengthSize = 4;
    private long firstMillis = Long.MIN_VALUE;

    private FlvReader(boolean hevc) {
        this.hevc = hevc;
    }

    /** Reads all of {@code input}. */
    static void read(InputStream input, Listener listener) throws IOException {
        read(input, listener, true);
    }

    /** Reads all of {@code input}; HEVC video is refused when {@code hevc} is false. */
    static void read(InputStream input, Listener listener, boolean hevc) throws IOException {
        new FlvReader(hevc).run(new DataInputStream(input), listener);
    }

    private void run(DataInputStream in, Listener listener) throws IOException {
        byte[] header = new byte[9];
        if (!readFully(in, header, 9)) return;
        if (header[0] != 'F' || header[1] != 'L' || header[2] != 'V') {
            throw new UnsupportedStream("Not an FLV stream");
        }
        int headerSize = ((header[5] & 0xFF) << 24) | ((header[6] & 0xFF) << 16)
                | ((header[7] & 0xFF) << 8) | (header[8] & 0xFF);
        if (headerSize > 9) skipFully(in, headerSize - 9);
        byte[] tagHeader = new byte[15];
        while (true) {
            // PreviousTagSize (4) then the 11 byte tag header, together.
            if (!readFully(in, tagHeader, 15)) return;
            int type = tagHeader[4] & 0x1F;
            int size = ((tagHeader[5] & 0xFF) << 16) | ((tagHeader[6] & 0xFF) << 8) | (tagHeader[7] & 0xFF);
            long timestamp = ((tagHeader[8] & 0xFF) << 16) | ((tagHeader[9] & 0xFF) << 8) | (tagHeader[10] & 0xFF)
                    | ((long) (tagHeader[11] & 0xFF) << 24);
            if (size < 0 || size > MAX_TAG_BYTES) throw new IOException("FLV tag size " + size + " is not believable");
            byte[] body = new byte[size];
            if (!readFully(in, body, size)) return;
            if (type == TAG_VIDEO) video(body, timestamp, listener);
            else if (type == TAG_AUDIO) audio(body, timestamp, listener);
        }
    }

    private long micros(long millis) {
        if (firstMillis == Long.MIN_VALUE) firstMillis = millis;
        return Math.max(0, millis - firstMillis) * 1000L;
    }

    private void video(byte[] body, long timestamp, Listener listener) throws IOException {
        if (body.length < 5) return;
        int first = body[0] & 0xFF;
        if ((first & 0x80) != 0) throw new UnsupportedStream("This LIVE uses a video format that can't be saved");
        int frameType = first >> 4;
        int tagCodec = first & 0x0F;
        if (tagCodec != CODEC_AVC && (tagCodec != CODEC_HEVC || !hevc)) {
            throw new UnsupportedStream("This LIVE uses a video format that can't be saved");
        }
        if (codec == -1) codec = tagCodec;
        else if (codec != tagCodec) throw new UnsupportedStream("This LIVE switched video formats midway");
        int packet = body[1] & 0xFF;
        if (packet == 0) {
            if (codec == CODEC_HEVC) hevcConfig(body, listener);
            else config(body, listener);
        } else if (packet == 1) {
            int composition = ((body[2] & 0xFF) << 16) | ((body[3] & 0xFF) << 8) | (body[4] & 0xFF);
            if ((composition & 0x800000) != 0) composition |= 0xFF000000;
            byte[] annexB = toAnnexB(body, 5);
            if (annexB.length == 0) return;
            long pts = Math.max(0, timestamp + composition);
            listener.onVideo(annexB, micros(pts), frameType == 1);
        }
    }

    private void config(byte[] body, Listener listener) throws IOException {
        // AVCDecoderConfigurationRecord after the 5 byte tag prefix.
        int at = 5;
        if (body.length < at + 7) throw new IOException("Cut-off video settings");
        nalLengthSize = (body[at + 4] & 0x03) + 1;
        int spsCount = body[at + 5] & 0x1F;
        at += 6;
        byte[] sps = null;
        for (int i = 0; i < spsCount; i++) {
            if (at + 2 > body.length) throw new IOException("Cut-off video settings");
            int length = ((body[at] & 0xFF) << 8) | (body[at + 1] & 0xFF);
            at += 2;
            if (at + length > body.length) throw new IOException("Cut-off video settings");
            if (sps == null) sps = copy(body, at, length);
            at += length;
        }
        if (at >= body.length) throw new IOException("Cut-off video settings");
        int ppsCount = body[at++] & 0xFF;
        byte[] pps = null;
        for (int i = 0; i < ppsCount; i++) {
            if (at + 2 > body.length) throw new IOException("Cut-off video settings");
            int length = ((body[at] & 0xFF) << 8) | (body[at + 1] & 0xFF);
            at += 2;
            if (at + length > body.length) throw new IOException("Cut-off video settings");
            if (pps == null) pps = copy(body, at, length);
            at += length;
        }
        if (sps == null || pps == null) throw new IOException("The video settings have no SPS or PPS");
        int[] size = SpsSize.of(sps);
        if (size == null) throw new IOException("Can't read the video size");
        listener.onVideoConfig(MIME_AVC, withStartCode(sps), withStartCode(pps), size[0], size[1]);
    }

    private void hevcConfig(byte[] body, Listener listener) throws IOException {
        // HEVCDecoderConfigurationRecord after the 5 byte tag prefix: 22 bytes of settings, the
        // array count, then arrays of NAL units by type. The muxer takes the VPS, SPS and PPS
        // together as one start-coded csd-0.
        int at = 5;
        if (body.length < at + 23) throw new IOException("Cut-off video settings");
        nalLengthSize = (body[at + 21] & 0x03) + 1;
        int arrays = body[at + 22] & 0xFF;
        at += 23;
        java.io.ByteArrayOutputStream csd = new java.io.ByteArrayOutputStream();
        byte[] sps = null;
        boolean vps = false;
        boolean pps = false;
        for (int i = 0; i < arrays; i++) {
            if (at + 3 > body.length) throw new IOException("Cut-off video settings");
            int type = body[at] & 0x3F;
            int count = ((body[at + 1] & 0xFF) << 8) | (body[at + 2] & 0xFF);
            at += 3;
            for (int n = 0; n < count; n++) {
                if (at + 2 > body.length) throw new IOException("Cut-off video settings");
                int length = ((body[at] & 0xFF) << 8) | (body[at + 1] & 0xFF);
                at += 2;
                if (at + length > body.length) throw new IOException("Cut-off video settings");
                if (type == 32 || type == 33 || type == 34) {
                    csd.write(START_CODE, 0, 4);
                    csd.write(body, at, length);
                    if (type == 33 && sps == null) sps = copy(body, at, length);
                    vps |= type == 32;
                    pps |= type == 34;
                }
                at += length;
            }
        }
        if (!vps || sps == null || !pps) throw new IOException("The video settings have no VPS, SPS or PPS");
        int[] size = SpsSize.ofHevc(sps);
        if (size == null) throw new IOException("Can't read the video size");
        listener.onVideoConfig(MIME_HEVC, csd.toByteArray(), null, size[0], size[1]);
    }

    private void audio(byte[] body, long timestamp, Listener listener) throws IOException {
        if (body.length < 2) return;
        int format = (body[0] & 0xFF) >> 4;
        if (format != 10) return;
        int packet = body[1] & 0xFF;
        if (packet == 0) {
            byte[] specific = copy(body, 2, body.length - 2);
            int[] parsed = AacConfig.parse(specific);
            if (parsed == null) throw new IOException("Can't read the audio settings");
            listener.onAudioConfig(specific, parsed[0], parsed[1]);
        } else if (packet == 1 && body.length > 2) {
            listener.onAudio(copy(body, 2, body.length - 2), micros(timestamp));
        }
    }

    private byte[] toAnnexB(byte[] body, int from) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(body.length - from + 16);
        int at = from;
        while (at + nalLengthSize <= body.length) {
            int length = 0;
            for (int i = 0; i < nalLengthSize; i++) length = (length << 8) | (body[at + i] & 0xFF);
            at += nalLengthSize;
            if (length <= 0 || at + length > body.length) break;
            out.write(START_CODE, 0, 4);
            out.write(body, at, length);
            at += length;
        }
        return out.toByteArray();
    }

    private static byte[] withStartCode(byte[] nal) {
        byte[] out = new byte[nal.length + 4];
        System.arraycopy(START_CODE, 0, out, 0, 4);
        System.arraycopy(nal, 0, out, 4, nal.length);
        return out;
    }

    private static byte[] copy(byte[] source, int from, int length) {
        byte[] out = new byte[length];
        System.arraycopy(source, from, out, 0, length);
        return out;
    }

    /** Fills {@code buffer}; false when the input ended first (at a clean edge or mid-tag). */
    private static boolean readFully(DataInputStream in, byte[] buffer, int length) throws IOException {
        try {
            in.readFully(buffer, 0, length);
            return true;
        } catch (EOFException ended) {
            return false;
        }
    }

    private static void skipFully(DataInputStream in, int count) throws IOException {
        int left = count;
        while (left > 0) {
            long skipped = in.skip(left);
            if (skipped <= 0) {
                if (in.read() < 0) return;
                skipped = 1;
            }
            left -= (int) skipped;
        }
    }

    /** The width and height an H.264 SPS describes. */
    static final class SpsSize {
        private SpsSize() {
        }

        /** {width, height} from a NAL unit with its header byte and no start code, or null when unreadable. */
        static int[] of(byte[] nal) {
            if (nal == null || nal.length < 5) return null;
            try {
                Bits bits = new Bits(unescape(nal, 1));
                int profile = bits.bits(8);
                bits.bits(8);
                bits.bits(8);
                bits.ue();
                int chroma = 1;
                if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                        || profile == 83 || profile == 86 || profile == 118 || profile == 128 || profile == 138
                        || profile == 139 || profile == 134 || profile == 135) {
                    chroma = bits.ue();
                    if (chroma == 3) bits.bits(1);
                    bits.ue();
                    bits.ue();
                    bits.bits(1);
                    if (bits.bits(1) == 1) {
                        int lists = chroma != 3 ? 8 : 12;
                        for (int i = 0; i < lists; i++) {
                            if (bits.bits(1) == 1) skipScalingList(bits, i < 6 ? 16 : 64);
                        }
                    }
                }
                bits.ue();
                int pocType = bits.ue();
                if (pocType == 0) {
                    bits.ue();
                } else if (pocType == 1) {
                    bits.bits(1);
                    bits.se();
                    bits.se();
                    int cycle = bits.ue();
                    for (int i = 0; i < cycle; i++) bits.se();
                }
                bits.ue();
                bits.bits(1);
                int widthMbs = bits.ue() + 1;
                int heightUnits = bits.ue() + 1;
                int frameMbsOnly = bits.bits(1);
                if (frameMbsOnly == 0) bits.bits(1);
                bits.bits(1);
                int cropLeft = 0, cropRight = 0, cropTop = 0, cropBottom = 0;
                if (bits.bits(1) == 1) {
                    cropLeft = bits.ue();
                    cropRight = bits.ue();
                    cropTop = bits.ue();
                    cropBottom = bits.ue();
                }
                int unitX = chroma == 1 || chroma == 2 ? 2 : 1;
                int unitY = (chroma == 1 ? 2 : 1) * (2 - frameMbsOnly);
                int width = widthMbs * 16 - unitX * (cropLeft + cropRight);
                int height = (2 - frameMbsOnly) * heightUnits * 16 - unitY * (cropTop + cropBottom);
                return width > 0 && height > 0 ? new int[]{width, height} : null;
            } catch (RuntimeException unreadable) {
                return null;
            }
        }

        /** {width, height} from an HEVC SPS NAL unit with its two header bytes and no start code, or null. */
        static int[] ofHevc(byte[] nal) {
            if (nal == null || nal.length < 6) return null;
            try {
                Bits bits = new Bits(unescape(nal, 2));
                bits.bits(4);
                int subLayers = bits.bits(3);
                bits.bits(1);
                // profile_tier_level: 96 bits for the general profile and level.
                bits.bits(32);
                bits.bits(32);
                bits.bits(32);
                boolean[] profilePresent = new boolean[subLayers];
                boolean[] levelPresent = new boolean[subLayers];
                for (int i = 0; i < subLayers; i++) {
                    profilePresent[i] = bits.bits(1) == 1;
                    levelPresent[i] = bits.bits(1) == 1;
                }
                if (subLayers > 0) {
                    for (int i = subLayers; i < 8; i++) bits.bits(2);
                }
                for (int i = 0; i < subLayers; i++) {
                    if (profilePresent[i]) {
                        bits.bits(32);
                        bits.bits(32);
                        bits.bits(24);
                    }
                    if (levelPresent[i]) bits.bits(8);
                }
                bits.ue();
                int chroma = bits.ue();
                boolean separatePlanes = chroma == 3 && bits.bits(1) == 1;
                int width = bits.ue();
                int height = bits.ue();
                int left = 0, right = 0, top = 0, bottom = 0;
                if (bits.bits(1) == 1) {
                    left = bits.ue();
                    right = bits.ue();
                    top = bits.ue();
                    bottom = bits.ue();
                }
                int unitX = !separatePlanes && (chroma == 1 || chroma == 2) ? 2 : 1;
                int unitY = !separatePlanes && chroma == 1 ? 2 : 1;
                width -= unitX * (left + right);
                height -= unitY * (top + bottom);
                return width > 0 && height > 0 ? new int[]{width, height} : null;
            } catch (RuntimeException unreadable) {
                return null;
            }
        }

        private static void skipScalingList(Bits bits, int size) {
            int last = 8;
            int next = 8;
            for (int i = 0; i < size; i++) {
                if (next != 0) next = (last + bits.se() + 256) % 256;
                last = next == 0 ? last : next;
            }
        }

        /** Removes the emulation prevention bytes (00 00 03) from a NAL unit's payload. */
        private static byte[] unescape(byte[] nal, int from) {
            byte[] out = new byte[nal.length - from];
            int length = 0;
            int zeros = 0;
            for (int i = from; i < nal.length; i++) {
                int value = nal[i] & 0xFF;
                if (zeros >= 2 && value == 3) {
                    zeros = 0;
                    continue;
                }
                zeros = value == 0 ? zeros + 1 : 0;
                out[length++] = (byte) value;
            }
            byte[] trimmed = new byte[length];
            System.arraycopy(out, 0, trimmed, 0, length);
            return trimmed;
        }

        private static final class Bits {
            private final byte[] data;
            private int position;

            Bits(byte[] data) {
                this.data = data;
            }

            int bits(int count) {
                int value = 0;
                for (int i = 0; i < count; i++) {
                    int byteIndex = position >> 3;
                    if (byteIndex >= data.length) throw new IllegalStateException("past the end");
                    value = (value << 1) | ((data[byteIndex] >> (7 - (position & 7))) & 1);
                    position++;
                }
                return value;
            }

            int ue() {
                int zeros = 0;
                while (bits(1) == 0) {
                    zeros++;
                    if (zeros > 31) throw new IllegalStateException("bad exp-golomb");
                }
                return zeros == 0 ? 0 : (int) ((1L << zeros) - 1 + bits(zeros));
            }

            int se() {
                int value = ue();
                return (value & 1) == 1 ? (value + 1) / 2 : -(value / 2);
            }
        }
    }

    /** The sample rate and channel count an AAC AudioSpecificConfig describes. */
    static final class AacConfig {
        private static final int[] RATES = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000,
                12000, 11025, 8000, 7350};

        private AacConfig() {
        }

        /** {sampleRate, channels}, or null when unreadable. */
        static int[] parse(byte[] specific) {
            if (specific == null || specific.length < 2) return null;
            int bitsRead = 0;
            long window = 0;
            int available = Math.min(specific.length, 8);
            for (int i = 0; i < available; i++) window = (window << 8) | (specific[i] & 0xFF);
            int total = available * 8;
            int object = (int) ((window >> (total - 5)) & 0x1F);
            bitsRead = 5;
            if (object == 31) {
                bitsRead += 6;
            }
            if (total < bitsRead + 4) return null;
            int index = (int) ((window >> (total - bitsRead - 4)) & 0x0F);
            bitsRead += 4;
            int rate;
            if (index == 15) {
                if (total < bitsRead + 24) return null;
                rate = (int) ((window >> (total - bitsRead - 24)) & 0xFFFFFF);
                bitsRead += 24;
            } else if (index < RATES.length) {
                rate = RATES[index];
            } else {
                return null;
            }
            if (total < bitsRead + 4) return null;
            int channels = (int) ((window >> (total - bitsRead - 4)) & 0x0F);
            if (channels == 0 || channels > 7) return null;
            if (channels == 7) channels = 8;
            return new int[]{rate, channels};
        }
    }
}
