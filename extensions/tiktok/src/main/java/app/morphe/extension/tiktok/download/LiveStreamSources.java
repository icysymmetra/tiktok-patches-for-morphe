/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/** The two ways a LIVE's video reaches the recorder: one long FLV download, or HLS pieces polled. */
final class LiveStreamSources {
    private LiveStreamSources() {
    }

    /** Opens an address for reading. Closing the stream ends the request. */
    interface Http {
        InputStream open(String url) throws IOException;
    }

    /** The real thing: HTTPS only, public addresses only, like every other media download. */
    static final Http NETWORK = url -> {
        MediaBudget.Deadline deadline = MediaBudget.deadline();
        // A recording runs for as long as the viewer watches; the grant slides forward with it.
        deadline.allowAtLeast(24L * 60 * 60 * 1000);
        MediaTransport.Response response = MediaTransport.open(url, deadline, 10_000, 20_000, null, true);
        boolean handedOver = false;
        try {
            if (response.statusCode != 200) throw new IOException("The LIVE server answered " + response.statusCode);
            InputStream body = response.inputStream();
            handedOver = true;
            return new java.io.FilterInputStream(body) {
                @Override public void close() throws IOException {
                    try {
                        super.close();
                    } finally {
                        response.close();
                    }
                }
            };
        } finally {
            if (!handedOver) response.close();
        }
    };

    /** MediaMuxer writes HEVC into an MP4 from Android 7.0 on; before that such a LIVE can't be saved. */
    static final boolean HEVC_WRITABLE = android.os.Build.VERSION.SDK_INT >= 24;

    private static final int BUFFER_BYTES = 32 * 1024;
    /** How much of an FLV stream is looked at to learn early that it can't be saved. */
    private static final int HEAD_BYTES = 48 * 1024;
    private static final long PLAYLIST_LIMIT_BYTES = 512 * 1024;
    private static final long SEGMENT_LIMIT_BYTES = 64L * 1024 * 1024;
    private static final int MAX_FAILURES = 5;
    private static final long STALL_NANOS = 45_000_000_000L;

    /** A LIVE's flv address: the stream is one never-ending download, written to the spool as it arrives. */
    static final class Flv implements LiveRecorder.Source {
        private final Http http;
        private final String url;

        Flv(Http http, String url) {
            this.http = http;
            this.url = url;
        }

        @Override public void pump(LiveRecorder.Output out, LiveRecorder.Control control) throws IOException {
            InputStream in = http.open(url);
            control.onStop(in);
            try {
                byte[] buffer = new byte[BUFFER_BYTES];
                ByteArrayOutputStream head = new ByteArrayOutputStream();
                boolean checked = false;
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    if (control.stopRequested()) return;
                    out.write(buffer, 0, count);
                    if (!checked) {
                        head.write(buffer, 0, Math.min(count, HEAD_BYTES - head.size()));
                        if (head.size() >= HEAD_BYTES) {
                            checked = true;
                            checkHead(head.toByteArray(), HEVC_WRITABLE);
                        }
                    }
                }
                if (!checked && head.size() > 0) checkHead(head.toByteArray(), HEVC_WRITABLE);
            } finally {
                control.release(in);
                in.close();
            }
        }

        /**
         * Throws when the start of the stream shows it can't be turned into an MP4: a video format
         * the reader doesn't know, or HEVC on a phone whose muxer can't write it ({@code hevc} false).
         */
        static void checkHead(byte[] head, boolean hevc) throws IOException {
            try {
                FlvReader.read(new ByteArrayInputStream(head), new NoOpListener(), hevc);
            } catch (FlvReader.UnsupportedStream unsupported) {
                throw unsupported;
            } catch (IOException cutOff) {
                // The head ends mid-tag, which is only the slice looked at.
            }
        }
    }

    private static final class NoOpListener implements FlvReader.Listener {
        @Override public void onVideoConfig(String mime, byte[] csd0, byte[] csd1, int width, int height) { }

        @Override public void onAudioConfig(byte[] specific, int sampleRate, int channels) { }

        @Override public void onVideo(byte[] annexB, long presentationUs, boolean keyFrame) { }

        @Override public void onAudio(byte[] aac, long presentationUs) { }
    }

    /**
     * A LIVE's HLS address: the playlist is read again every few seconds and each new MPEG-TS piece
     * is fetched once and appended. What the playlist already listed when the recording began is
     * skipped, because a recording is of what the viewer watches from the moment they started it.
     */
    static final class Hls implements LiveRecorder.Source {
        private final Http http;
        private final String url;

        Hls(Http http, String url) {
            this.http = http;
            this.url = url;
        }

        @Override public void pump(LiveRecorder.Output out, LiveRecorder.Control control) throws IOException {
            String playlistUrl = url;
            HlsPlaylist.Follower follower = new HlsPlaylist.Follower();
            long lastFresh = System.nanoTime();
            int failures = 0;
            while (!control.stopRequested()) {
                try {
                    HlsPlaylist playlist = HlsPlaylist.parse(readText(control, playlistUrl), playlistUrl);
                    if (playlist.master) {
                        HlsPlaylist.Variant best = playlist.best();
                        if (best == null) throw new IOException("The LIVE's playlist lists no streams");
                        playlistUrl = best.uri;
                        continue;
                    }
                    if (playlist.encrypted || playlist.needsInitSection) {
                        throw new FlvReader.UnsupportedStream("This LIVE's stream can't be saved");
                    }
                    List<HlsPlaylist.Segment> fresh = follower.fresh(playlist);
                    boolean allFetched = true;
                    for (HlsPlaylist.Segment segment : fresh) {
                        if (control.stopRequested()) return;
                        try {
                            copy(control, segment.uri, out);
                            lastFresh = System.nanoTime();
                        } catch (FlvReader.UnsupportedStream | LiveRecorder.SpoolFull fatal) {
                            throw fatal;
                        } catch (IOException lost) {
                            // One piece missing is a short skip in the video; the rest still count.
                            if (control.stopRequested()) return;
                            allFetched = false;
                            if (++failures >= MAX_FAILURES) throw lost;
                        }
                    }
                    if (allFetched) failures = 0;
                    if (playlist.ended) return;
                    if (System.nanoTime() - lastFresh > STALL_NANOS) {
                        throw new IOException("The stream stopped sending video");
                    }
                } catch (FlvReader.UnsupportedStream unsupported) {
                    throw unsupported;
                } catch (LiveRecorder.SpoolFull full) {
                    throw full;
                } catch (IOException failure) {
                    if (control.stopRequested()) return;
                    if (++failures >= MAX_FAILURES) throw failure;
                }
                if (!control.pause(1500)) return;
            }
        }

        private String readText(LiveRecorder.Control control, String address) throws IOException {
            InputStream in = http.open(address);
            control.onStop(in);
            try {
                ByteArrayOutputStream text = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    text.write(buffer, 0, count);
                    if (text.size() > PLAYLIST_LIMIT_BYTES) throw new IOException("The playlist is too large");
                }
                return text.toString("UTF-8");
            } finally {
                control.release(in);
                in.close();
            }
        }

        private void copy(LiveRecorder.Control control, String address, LiveRecorder.Output out) throws IOException {
            InputStream in = http.open(address);
            control.onStop(in);
            try {
                byte[] buffer = new byte[BUFFER_BYTES];
                long total = 0;
                int count;
                while ((count = in.read(buffer)) >= 0) {
                    total += count;
                    if (total > SEGMENT_LIMIT_BYTES) throw new IOException("A piece of the stream is too large");
                    out.write(buffer, 0, count);
                }
            } finally {
                control.release(in);
                in.close();
            }
        }
    }
}
