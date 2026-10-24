package app.morphe.extension.tiktok.download;

import java.io.ByteArrayOutputStream;

/** Builds the pieces of an FLV stream, for the live recording tests. */
final class FlvFixtures {
    private FlvFixtures() {
    }

    /** Writes bits the way an H.264 SPS packs them. */
    static final class BitWriter {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int current;
        private int used;

        BitWriter bits(int value, int count) {
            for (int i = count - 1; i >= 0; i--) {
                current = (current << 1) | ((value >> i) & 1);
                if (++used == 8) {
                    out.write(current);
                    current = 0;
                    used = 0;
                }
            }
            return this;
        }

        BitWriter ue(int value) {
            int v = value + 1;
            int length = 32 - Integer.numberOfLeadingZeros(v);
            bits(0, length - 1);
            bits(v, length);
            return this;
        }

        byte[] finish() {
            bits(1, 1);
            while (used != 0) bits(0, 1);
            return out.toByteArray();
        }
    }

    private static byte[] withNalHeader(byte[] rbsp) {
        byte[] nal = new byte[rbsp.length + 1];
        nal[0] = 0x67;
        System.arraycopy(rbsp, 0, nal, 1, rbsp.length);
        return nal;
    }

    /** A baseline SPS NAL unit (with its header byte) for the given size in macroblocks. */
    static byte[] baselineSps(int widthMbs, int heightMbs, int cropBottom) {
        BitWriter w = new BitWriter();
        w.bits(66, 8).bits(0, 8).bits(30, 8);
        w.ue(0);          // sps id
        w.ue(0);          // log2_max_frame_num_minus4
        w.ue(2);          // pic_order_cnt_type 2
        w.ue(1);          // max_num_ref_frames
        w.bits(0, 1);     // gaps
        w.ue(widthMbs - 1);
        w.ue(heightMbs - 1);
        w.bits(1, 1);     // frame_mbs_only
        w.bits(1, 1);     // direct_8x8
        if (cropBottom > 0) {
            w.bits(1, 1).ue(0).ue(0).ue(0).ue(cropBottom);
        } else {
            w.bits(0, 1);
        }
        w.bits(0, 1);     // vui
        return withNalHeader(w.finish());
    }

    /** A High profile SPS with 4:2:0 chroma and no scaling matrix. */
    static byte[] highSps(int widthMbs, int heightMbs) {
        BitWriter w = new BitWriter();
        w.bits(100, 8).bits(0, 8).bits(40, 8);
        w.ue(0);
        w.ue(1);          // chroma_format_idc
        w.ue(0).ue(0);    // bit depths
        w.bits(0, 1);     // qpprime
        w.bits(0, 1);     // no scaling matrix
        w.ue(0);
        w.ue(2);
        w.ue(1);
        w.bits(0, 1);
        w.ue(widthMbs - 1);
        w.ue(heightMbs - 1);
        w.bits(1, 1);
        w.bits(1, 1);
        w.bits(0, 1);
        w.bits(0, 1);
        return withNalHeader(w.finish());
    }

    /** One tag with its trailing size. */
    static byte[] tag(int type, long timestampMs, byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(type);
        out.write((data.length >> 16) & 0xFF);
        out.write((data.length >> 8) & 0xFF);
        out.write(data.length & 0xFF);
        out.write((int) ((timestampMs >> 16) & 0xFF));
        out.write((int) ((timestampMs >> 8) & 0xFF));
        out.write((int) (timestampMs & 0xFF));
        out.write((int) ((timestampMs >> 24) & 0xFF));
        out.write(0);
        out.write(0);
        out.write(0);
        out.write(data, 0, data.length);
        int total = data.length + 11;
        out.write((total >> 24) & 0xFF);
        out.write((total >> 16) & 0xFF);
        out.write((total >> 8) & 0xFF);
        out.write(total & 0xFF);
        return out.toByteArray();
    }

    /** The FLV header, the first PreviousTagSize, then the tags. */
    static byte[] stream(byte[]... tags) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] header = {'F', 'L', 'V', 1, 5, 0, 0, 0, 9, 0, 0, 0, 0};
        out.write(header, 0, header.length);
        for (byte[] tag : tags) out.write(tag, 0, tag.length);
        return out.toByteArray();
    }

    static byte[] avcConfigTag(long ts, byte[] sps, byte[] pps) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x17);
        body.write(0);
        body.write(0);
        body.write(0);
        body.write(0);
        body.write(1);
        body.write(sps[1]);
        body.write(sps[2]);
        body.write(sps[3]);
        body.write(0xFF);
        body.write(0xE1);
        body.write(sps.length >> 8);
        body.write(sps.length & 0xFF);
        body.write(sps, 0, sps.length);
        body.write(1);
        body.write(pps.length >> 8);
        body.write(pps.length & 0xFF);
        body.write(pps, 0, pps.length);
        return tag(9, ts, body.toByteArray());
    }

    static byte[] avcFrameTag(long ts, boolean key, int compositionMs, byte[]... nals) {
        return frameTag(key ? 0x17 : 0x27, ts, compositionMs, nals);
    }

    /** A frame in TikTok's HEVC flavor of FLV: codec id 12, laid out like H.264. */
    static byte[] hevcFrameTag(long ts, boolean key, int compositionMs, byte[]... nals) {
        return frameTag(key ? 0x1C : 0x2C, ts, compositionMs, nals);
    }

    /** An HEVC SPS NAL unit (with its two header bytes): Main profile, 4:2:0, no sub-layers. */
    static byte[] hevcSps(int width, int height, int cropRight) {
        BitWriter w = new BitWriter();
        w.bits(0x42, 8).bits(0x01, 8);      // NAL header, type 33
        w.bits(0, 4).bits(0, 3).bits(1, 1); // vps id, max_sub_layers_minus1, temporal_id_nesting
        w.bits(0x01, 8).bits(0x60000000, 32); // Main profile and its compatibility flags
        w.bits(0x9, 4).bits(0, 32).bits(0, 12); // progressive, frame only, reserved
        w.bits(123, 8);                     // level 4.1
        w.ue(0);                            // sps id
        w.ue(1);                            // chroma_format_idc 4:2:0
        w.ue(width).ue(height);
        if (cropRight > 0) {
            w.bits(1, 1).ue(0).ue(cropRight).ue(0).ue(0);
        } else {
            w.bits(0, 1);
        }
        return w.finish();
    }

    static byte[] hevcVps() {
        return new byte[]{0x40, 0x01, 0x0C, 0x01};
    }

    static byte[] hevcPps() {
        return new byte[]{0x44, 0x01, (byte) 0xC1, 0x72};
    }

    /** The HEVCDecoderConfigurationRecord tag, with 4 byte NAL lengths and one array per unit. */
    static byte[] hevcConfigTag(long ts, byte[]... nals) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x1C);
        body.write(0);
        body.write(0);
        body.write(0);
        body.write(0);
        byte[] settings = new byte[22];
        settings[0] = 1;
        settings[21] = 0x0F;
        body.write(settings, 0, settings.length);
        body.write(nals.length);
        for (byte[] nal : nals) {
            body.write(0x80 | ((nal[0] >> 1) & 0x3F));
            body.write(0);
            body.write(1);
            body.write(nal.length >> 8);
            body.write(nal.length & 0xFF);
            body.write(nal, 0, nal.length);
        }
        return tag(9, ts, body.toByteArray());
    }

    private static byte[] frameTag(int first, long ts, int compositionMs, byte[]... nals) {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(first);
        body.write(1);
        body.write((compositionMs >> 16) & 0xFF);
        body.write((compositionMs >> 8) & 0xFF);
        body.write(compositionMs & 0xFF);
        for (byte[] nal : nals) {
            body.write((nal.length >> 24) & 0xFF);
            body.write((nal.length >> 16) & 0xFF);
            body.write((nal.length >> 8) & 0xFF);
            body.write(nal.length & 0xFF);
            body.write(nal, 0, nal.length);
        }
        return tag(9, ts, body.toByteArray());
    }

    /** AAC LC, 44100 Hz, stereo. */
    static byte[] aacConfigTag(long ts) {
        return tag(8, ts, new byte[]{(byte) 0xAF, 0, 0x12, 0x10});
    }

    static byte[] aacFrameTag(long ts, byte... payload) {
        byte[] body = new byte[payload.length + 2];
        body[0] = (byte) 0xAF;
        body[1] = 1;
        System.arraycopy(payload, 0, body, 2, payload.length);
        return tag(8, ts, body);
    }

    static byte[] pps() {
        return new byte[]{0x68, (byte) 0xCE, 0x3C, (byte) 0x80};
    }
}
