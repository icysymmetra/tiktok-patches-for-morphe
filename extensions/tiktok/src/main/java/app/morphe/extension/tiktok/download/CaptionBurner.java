/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.download;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.graphics.Typeface;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * Writes the creator's @username and the first line of the caption into a saved video, in its
 * bottom left corner for its whole length. Each frame is decoded onto a texture and drawn with
 * the words over it into an H.264 encoder at the decoder's own time stamp, and the sound is
 * copied across untouched, so the two stay where they were against each other.
 *
 * <p>Robolectric has no codecs or GL, so what a test would want to check lives in plain
 * methods: which lines go on, where one is cut and how big the text is ({@link #lines},
 * {@link #textSize}, {@link #margin}), the bit rate and the time allowed. What's left is the
 * order the codec and GL calls go in, the decode, edit and encode loop Android's own media tests
 * use, and letting every one of them go again.
 */
final class CaptionBurner {
    static final String ELLIPSIS = "…";
    /** Text never gets smaller than this many pixels, however narrow the video. */
    static final float MIN_TEXT_PX = 12f;
    /** Each line takes this much of its own height again before the next one starts. */
    private static final float LINE_SPACING = 1.15f;
    /** Black at about 70% behind the words, so they still read over a white frame. */
    private static final int SHADOW = 0xB3000000;
    /** What the encoder is told when the track doesn't say. The frames carry their own stamps. */
    private static final int DEFAULT_FPS = 30;
    private static final int MIN_BIT_RATE = 1_000_000;
    private static final int MAX_BIT_RATE = 20_000_000;
    private static final long TIMEOUT_US = 10_000L;
    /** How long one decoded frame may take to reach the texture before the decoder counts as stuck. */
    private static final long FRAME_WAIT_MS = 2_500L;
    /** Turns in a row with nothing moving, each with a short wait, before the video counts as stuck. */
    private static final int MAX_IDLE_TURNS = 500;

    /** The encode itself. A test swaps it, since Robolectric has no codecs to run the real one. */
    interface Burn {
        void run(File source, File output, String creator, String caption, SaveProgress progress) throws IOException;
    }

    static volatile Burn burner = CaptionBurner::burn;

    /** A line's width in pixels, as the paint that draws it measures it. */
    interface Measure {
        float width(String text);
    }

    private CaptionBurner() {}

    /**
     * {@link #burner}, or false when it failed, so the caller saves the video as it came rather
     * than losing the save to a codec this phone gets wrong. The reader is told it's going out
     * without. False too, and quietly, for a post with neither a creator nor a caption. Cancel or
     * no room still throws, since that's the save's answer. Running out of time doesn't: the video
     * itself is already here, and a track that doesn't say how long it plays gets only the time
     * any save gets, so it's saved without and given the time the rest of the save needs.
     */
    static boolean burnOrKeep(File source, File output, String creator, String caption, SaveProgress progress)
            throws MediaBudget.StopException {
        if (!hasText(creator, caption)) {
            Logger.printInfo(() -> "Nothing to write on the video: the post has no creator or caption");
            return false;
        }
        try {
            burner.run(source, output, creator, caption, progress);
            return true;
        } catch (MediaBudget.StopException stop) {
            if (stop.reason != MediaBudget.StopException.Reason.TIME) throw stop;
            MediaBudget.deadline().allowAtLeast(MediaBudget.JOB_DEADLINE_MS);
            return keep(stop);
        } catch (IOException | RuntimeException | OutOfMemoryError failure) {
            return keep(failure);
        }
    }

    private static boolean keep(Throwable failure) {
        Logger.printException(() -> "Could not write the caption on the video, so it's saved without", failure);
        Utils.showToastLong(L10n.t("Couldn't write the caption on this video. Saving it without."));
        return false;
    }

    /**
     * Decodes {@code source}'s picture, draws each frame with the words over it into an encoder
     * and writes that, with the source's sound copied as it was, to {@code output}.
     */
    static void burn(File source, File output, String creator, String caption, SaveProgress progress) throws IOException {
        MediaBudget.check(null);
        File directory = output.getAbsoluteFile().getParentFile();
        MediaBudget.checkDiskSpace(directory, source.length());
        MediaExtractor picture = new MediaExtractor();
        MediaExtractor sound = null;
        MediaCodec decoder = null, encoder = null;
        AnimatedWebpMp4Converter.CodecSurface surface = null;
        DecodedFrames frames = null;
        MediaMuxer muxer = null;
        AnimatedWebpMp4Converter.EncoderState state = new AnimatedWebpMp4Converter.EncoderState();
        try {
            picture.setDataSource(source.getAbsolutePath());
            MediaFormat input = TrackMuxer.select(picture, "video/");
            int width = number(input, MediaFormat.KEY_WIDTH, 0);
            int height = number(input, MediaFormat.KEY_HEIGHT, 0);
            if (width <= 0 || height <= 0) throw new IOException("The video doesn't say how big it is");
            int rotation = rotation(input);
            long durationUs = duration(input);
            int fps = number(input, MediaFormat.KEY_FRAME_RATE, DEFAULT_FPS);
            if (fps <= 0 || fps > 240) fps = DEFAULT_FPS;
            int bitRate = bitRate(number(input, MediaFormat.KEY_BIT_RATE, 0), source.length(), durationUs, width, height, fps);
            // The encoder takes even sides only. An odd one is stretched by the one pixel.
            int outWidth = width + (width & 1);
            int outHeight = height + (height & 1);

            int soundTrack = trackOf(picture, "audio/");
            MediaFormat audio = null;
            if (soundTrack >= 0) {
                sound = new MediaExtractor();
                sound.setDataSource(source.getAbsolutePath());
                sound.selectTrack(soundTrack);
                audio = sound.getTrackFormat(soundTrack);
            }
            // One shift for both tracks, as TrackMuxer.combine takes it, so the sound stays
            // where it was against the picture.
            long shift = Math.min(TrackMuxer.leadIn(picture), sound == null ? 0L : TrackMuxer.leadIn(sound));
            // Encoding again runs about as long as the video plays, so a long one gets the time
            // for it rather than running out partway.
            MediaBudget.deadline().allowAtLeast(timeAllowedMs(durationUs));

            muxer = new MediaMuxer(output.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            // Stored the way the source stores it and turned by the player the same way, so the
            // picture plays upright. The words are drawn turned back to match.
            if (rotation != 0) muxer.setOrientationHint(rotation);
            state.companion = audio;

            // Each size goes first to the encoders that say they take it, then to the default
            // one, as SlideshowEncoder.open does, since a phone's list is sometimes too strict.
            IOException refused = new IOException("No H.264 encoder took " + outWidth + " by " + outHeight);
            List<String> names = SlideshowEncoder.encodersFor(outWidth, outHeight);
            names.add(null);
            for (String name : names) {
                MediaCodec candidate = null;
                AnimatedWebpMp4Converter.CodecSurface candidateSurface = null;
                try {
                    candidate = name == null ? MediaCodec.createEncoderByType(SlideshowEncoder.VIDEO_MIME)
                            : MediaCodec.createByCodecName(name);
                    candidate.configure(SlideshowEncoder.format(outWidth, outHeight, fps, bitRate), null, null,
                            MediaCodec.CONFIGURE_FLAG_ENCODE);
                    candidateSurface = new AnimatedWebpMp4Converter.CodecSurface(candidate.createInputSurface(), outWidth, outHeight);
                    candidate.start();
                    encoder = candidate;
                    surface = candidateSurface;
                    break;
                } catch (IOException | RuntimeException failure) {
                    refused.addSuppressed(failure);
                    SlideshowEncoder.release(candidate, candidateSurface);
                }
            }
            if (encoder == null) throw refused;

            // The encoder's GL context is current on this thread from here, which the overlay's
            // texture and the decoder's both need.
            Bitmap overlay = overlay(outWidth, outHeight, rotation, creator, caption);
            try {
                surface.upload(overlay);
            } finally {
                overlay.recycle();
            }
            frames = new DecodedFrames();
            decoder = MediaCodec.createDecoderByType(input.getString(MediaFormat.KEY_MIME));
            // On a surface the decoder would turn the picture itself, and the player turns it again.
            input.setInteger("rotation-degrees", 0);
            decoder.configure(input, frames.surface, null, 0);
            decoder.start();
            if (progress != null) progress.captioning(0, durationUs);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            long firstUs = -1, drawn = 0;
            int idle = 0;
            boolean fed = false, decoded = false;
            while (!decoded) {
                MediaBudget.check(null);
                if (progress != null && progress.isCancelled()) {
                    throw new MediaBudget.StopException("Writing the caption was cancelled",
                            MediaBudget.StopException.Reason.CANCELLED);
                }
                boolean moved = false;

                // Compressed frames into the decoder.
                if (!fed) {
                    int index = decoder.dequeueInputBuffer(0);
                    if (index >= 0) {
                        ByteBuffer buffer = decoder.getInputBuffer(index);
                        int size = buffer == null ? -1 : picture.readSampleData(buffer, 0);
                        if (size < 0) {
                            decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            fed = true;
                        } else {
                            decoder.queueInputBuffer(index, 0, size, picture.getSampleTime(), 0);
                            picture.advance();
                        }
                        moved = true;
                    }
                }

                // Each decoded frame onto the texture, then drawn with the words into the encoder,
                // one at a time: the next isn't released until this one has been drawn.
                int frame = decoder.dequeueOutputBuffer(info, moved ? 0 : TIMEOUT_US);
                if (frame >= 0) {
                    moved = true;
                    boolean last = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    boolean render = info.size > 0;
                    decoder.releaseOutputBuffer(frame, render);
                    if (render) {
                        frames.await();
                        frames.draw(outWidth, outHeight);
                        // Premultiplied, as Android's bitmaps are: the clear pixels leave the
                        // frame as it was.
                        GLES20.glEnable(GLES20.GL_BLEND);
                        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA);
                        surface.drawUploaded();
                        GLES20.glDisable(GLES20.GL_BLEND);
                        surface.submit(Math.max(0L, info.presentationTimeUs - shift) * 1000L);
                        drawn++;
                        if (firstUs < 0) firstUs = info.presentationTimeUs;
                        if (progress != null) progress.captioning(info.presentationTimeUs - firstUs, durationUs);
                    }
                    if (last) {
                        encoder.signalEndOfInputStream();
                        decoded = true;
                    }
                } else if (frame == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    moved = true;
                }

                // Encoded frames out to the file, all that's left once the decoder is done. Wait
                // only when the encoder falls behind, as SlideshowEncoder.add does, so its output
                // can't fill up while the next frame waits for room.
                long written = state.samples;
                AnimatedWebpMp4Converter.drainEncoder(encoder, muxer, decoded, state, directory,
                        drawn - state.samples > 2 ? AnimatedWebpMp4Converter.CODEC_TIMEOUT_US : 0);
                if (state.samples != written) moved = true;

                if (moved) idle = 0;
                else if (++idle > MAX_IDLE_TURNS) throw new IOException("The video stopped moving partway through");
            }
            if (drawn == 0) throw new IOException("The video gave no frames to draw");
            if (!state.muxerStarted) throw new IOException("The encoder gave no video");
            if (sound != null) TrackMuxer.copy(sound, muxer, state.companionTrack, shift);
            muxer.stop();
            state.muxerStarted = false;
            MediaBudget.check(null);
        } finally {
            stopAndRelease(decoder);
            if (frames != null) frames.release();
            SlideshowEncoder.release(encoder, surface);
            picture.release();
            if (sound != null) sound.release();
            SlideshowEncoder.releaseMuxer(muxer);
        }
        if (output.length() == 0) throw new IOException("The caption muxer wrote an empty file");
    }

    /** Whether the post gives anything to write: a creator, or a caption with words in it. */
    static boolean hasText(String creator, String caption) {
        return !clean(creator).isEmpty() || !firstLine(caption).isEmpty();
    }

    /**
     * Text height in pixels for a picture {@code width} across: a 24th of it, about what TikTok's
     * own name line comes to on a phone.
     */
    static float textSize(int width) {
        return Math.max(MIN_TEXT_PX, width / 24f);
    }

    /** The gap between the words and the picture's left and bottom edges. */
    static int margin(int width) {
        return Math.round(width / 20f);
    }

    /**
     * What goes on the picture, top line first: the @username, then the caption's first line,
     * each cut with an ellipsis to fit {@code room} pixels. A part the post doesn't have is left
     * out rather than drawn empty, so a post without a caption gets the name alone.
     */
    static List<String> lines(String creator, String caption, float room, Measure measure) {
        List<String> lines = new ArrayList<>(2);
        String name = clean(creator);
        if (!name.isEmpty()) lines.add(fit(name, room, measure));
        String first = firstLine(caption);
        if (!first.isEmpty()) lines.add(fit(first, room, measure));
        return lines;
    }

    /**
     * The caption's first line, with the blank ones before it skipped, the same line players
     * show as the title of a tagged save. A tab would draw as a box, so it's a space here.
     */
    static String firstLine(String caption) {
        String text = clean(caption);
        int end = text.indexOf('\n');
        if (end >= 0) text = text.substring(0, end);
        return text.replace('\t', ' ').trim();
    }

    /**
     * {@code text} as it is when it fits {@code room}, else the longest start of it that fits
     * with an ellipsis after. Cut between whole characters as a reader sees them, so an emoji
     * or an accented letter is never split in half.
     */
    static String fit(String text, float room, Measure measure) {
        if (measure.width(text) <= room) return text;
        BreakIterator characters = BreakIterator.getCharacterInstance();
        characters.setText(text);
        List<Integer> ends = new ArrayList<>();
        for (int end = characters.next(); end != BreakIterator.DONE && end < text.length(); end = characters.next()) {
            ends.add(end);
        }
        // Found by halving rather than one character at a time: a caption's first line can run
        // to thousands of characters, and a longer start never measures narrower.
        String best = ELLIPSIS;
        int low = 0, high = ends.size() - 1;
        while (low <= high) {
            int middle = (low + high) >>> 1;
            String cut = stripEnd(text.substring(0, ends.get(middle))) + ELLIPSIS;
            if (measure.width(cut) <= room) {
                best = cut;
                low = middle + 1;
            } else {
                high = middle - 1;
            }
        }
        return best;
    }

    /**
     * The picture laid over every frame: {@code width} by {@code height}, clear but for the
     * lines in its bottom left corner. The player turns a stored frame {@code rotation} degrees
     * clockwise, so the lines are laid out on the picture as it's shown and drawn turned back.
     */
    static Bitmap overlay(int width, int height, int rotation, String creator, String caption) {
        boolean sideways = rotation == 90 || rotation == 270;
        int shownWidth = sideways ? height : width;
        int shownHeight = sideways ? width : height;
        float size = textSize(shownWidth);
        int margin = margin(shownWidth);
        Paint name = textPaint(size, Typeface.DEFAULT_BOLD);
        Paint words = textPaint(size, Typeface.DEFAULT);
        // Measured in bold, so the caption in regular weight fits the same room with a little over.
        List<String> lines = lines(creator, caption, shownWidth - 2f * margin, name::measureText);
        boolean named = !clean(creator).isEmpty();
        Bitmap overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(overlay);
        canvas.concat(shownToStored(rotation, shownWidth, shownHeight));
        Paint.FontMetrics metrics = name.getFontMetrics();
        float step = (metrics.descent - metrics.ascent) * LINE_SPACING;
        float baseline = shownHeight - margin - metrics.descent;
        for (int index = lines.size() - 1; index >= 0; index--) {
            canvas.drawText(lines.get(index), margin, baseline, index == 0 && named ? name : words);
            baseline -= step;
        }
        return overlay;
    }

    /**
     * Maps the picture as it's shown onto the frame as it's stored: turned back by
     * {@code rotation}, then moved so its corner sits on the frame's again.
     */
    static Matrix shownToStored(int rotation, int shownWidth, int shownHeight) {
        Matrix matrix = new Matrix();
        if (rotation == 0) return matrix;
        matrix.setRotate(-rotation);
        RectF bounds = new RectF(0, 0, shownWidth, shownHeight);
        matrix.mapRect(bounds);
        matrix.postTranslate(-bounds.left, -bounds.top);
        return matrix;
    }

    /**
     * The rate the new picture is encoded at: a quarter over the source's, since a second encode
     * at the same rate comes out softer than the first. The source's is what its track says,
     * else its file size over its length (the sound is in that, but it's small beside the
     * picture), else a guess from the frame size. Kept between 1 and 20 Mbps.
     */
    static int bitRate(int declared, long fileBytes, long durationUs, int width, int height, int fps) {
        long source;
        if (declared > 0) source = declared;
        else if (fileBytes > 0 && durationUs > 0) source = fileBytes * 8L * 1_000_000L / durationUs;
        else source = (long) width * height * Math.max(1, fps) / 10;
        return (int) Math.max(MIN_BIT_RATE, Math.min(MAX_BIT_RATE, source + source / 4));
    }

    /**
     * How long the encode may run from its start: the two minutes any save gets, and three times
     * the video's length on top.
     */
    static long timeAllowedMs(long durationUs) {
        return MediaBudget.JOB_DEADLINE_MS + Math.max(0L, durationUs) / 1000L * 3L;
    }

    /** The clockwise turn the player gives the picture, or 0 for one the muxer wouldn't take. */
    private static int rotation(MediaFormat format) {
        int degrees = number(format, "rotation-degrees", 0);
        degrees = ((degrees % 360) + 360) % 360;
        return degrees % 90 == 0 ? degrees : 0;
    }

    private static long duration(MediaFormat format) {
        try {
            return format.containsKey(MediaFormat.KEY_DURATION) ? Math.max(0L, format.getLong(MediaFormat.KEY_DURATION)) : 0L;
        } catch (ClassCastException unreadable) {
            return 0L;
        }
    }

    /** A whole number the format may hold as a float, or not hold at all. */
    private static int number(MediaFormat format, String key, int fallback) {
        if (!format.containsKey(key)) return fallback;
        try {
            return format.getInteger(key);
        } catch (ClassCastException notWhole) {
            try {
                return Math.round(format.getFloat(key));
            } catch (ClassCastException neither) {
                return fallback;
            }
        }
    }

    /** The first track whose type starts with {@code prefix}, or -1 for a video saved without one. */
    private static int trackOf(MediaExtractor extractor, String prefix) {
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            String mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith(prefix)) return i;
        }
        return -1;
    }

    private static String clean(String text) {
        return text == null ? "" : text.trim();
    }

    private static String stripEnd(String text) {
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private static Paint textPaint(float size, Typeface typeface) {
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setColor(Color.WHITE);
        paint.setTextSize(size);
        paint.setTypeface(typeface);
        paint.setShadowLayer(size / 6f, 0f, size / 16f, SHADOW);
        return paint;
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
            Logger.printInfo(() -> "Could not release the video decoder: " + failure);
        }
    }

    /**
     * Where the decoder draws: a SurfaceTexture on an external texture in the encoder's GL
     * context, and the program that draws it. Its frame callback comes on a thread of its own,
     * since a media job's thread has no Looper and the main one may be busy, and each frame is
     * waited for a bounded time, so a decoder that stops can't hold the save.
     *
     * <p>The program and texture go with the encoder's context when that's released, as
     * {@link AnimatedWebpMp4Converter.CodecSurface}'s own do.
     */
    private static final class DecodedFrames implements SurfaceTexture.OnFrameAvailableListener {
        /** Not flipped, unlike CodecSurface's: the texture's own matrix turns the frame upright. */
        private static final float[] VERTICES = {
                -1f, -1f, 0f, 0f,
                1f, -1f, 1f, 0f,
                -1f, 1f, 0f, 1f,
                1f, 1f, 1f, 1f
        };
        private static final String VERTEX_SHADER =
                "uniform mat4 uTexMatrix;attribute vec2 aPosition;attribute vec4 aTexCoord;varying vec2 vTexCoord;" +
                        "void main(){gl_Position=vec4(aPosition,0.0,1.0);vTexCoord=(uTexMatrix*aTexCoord).xy;}";
        // The extension has to be asked for on a line of its own, before anything else.
        private static final String FRAGMENT_SHADER =
                "#extension GL_OES_EGL_image_external : require\n" +
                        "precision mediump float;uniform samplerExternalOES uTexture;varying vec2 vTexCoord;" +
                        "void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}";
        /** The frame's texture unit. Unit 0 keeps the overlay CodecSurface draws. */
        private static final int UNIT = 1;

        private final FloatBuffer vertices;
        private final float[] matrix = new float[16];
        private final Object lock = new Object();
        private boolean available;
        private HandlerThread thread;
        private SurfaceTexture texture;
        Surface surface;
        private int program;
        private int textureName;
        private int positionLocation;
        private int textureLocation;
        private int matrixLocation;
        private int samplerLocation;

        DecodedFrames() {
            vertices = ByteBuffer.allocateDirect(VERTICES.length * 4)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer();
            vertices.put(VERTICES).position(0);
            try {
                program = AnimatedWebpMp4Converter.CodecSurface.createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
                positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
                textureLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
                matrixLocation = GLES20.glGetUniformLocation(program, "uTexMatrix");
                samplerLocation = GLES20.glGetUniformLocation(program, "uTexture");
                int[] names = new int[1];
                GLES20.glGenTextures(1, names, 0);
                textureName = names[0];
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT);
                GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                thread = new HandlerThread("Metra-CaptionFrames");
                thread.start();
                texture = new SurfaceTexture(textureName);
                texture.setOnFrameAvailableListener(this, new Handler(thread.getLooper()));
                surface = new Surface(texture);
            } catch (RuntimeException | Error failure) {
                // The caller only owns this object after construction has completed.
                try {
                    release();
                } catch (RuntimeException | Error cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
                throw failure;
            }
        }

        @Override public void onFrameAvailable(SurfaceTexture ignored) {
            synchronized (lock) {
                available = true;
                lock.notifyAll();
            }
        }

        /** Waits for the frame just released to the surface to land on the texture. */
        void await() throws IOException {
            long end = System.nanoTime() + FRAME_WAIT_MS * 1_000_000L;
            synchronized (lock) {
                try {
                    while (!available) {
                        long left = (end - System.nanoTime()) / 1_000_000L;
                        if (left <= 0) throw new IOException("The decoder didn't hand over a frame in time");
                        lock.wait(left);
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new MediaBudget.StopException("Writing the caption was interrupted",
                            MediaBudget.StopException.Reason.CANCELLED);
                }
                available = false;
            }
        }

        /** Takes the newest frame onto the texture and draws it across the whole frame. */
        void draw(int width, int height) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + UNIT);
            texture.updateTexImage();
            texture.getTransformMatrix(matrix);
            GLES20.glViewport(0, 0, width, height);
            GLES20.glDisable(GLES20.GL_BLEND);
            GLES20.glUseProgram(program);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureName);
            GLES20.glUniform1i(samplerLocation, UNIT);
            GLES20.glUniformMatrix4fv(matrixLocation, 1, false, matrix, 0);
            vertices.position(0);
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices);
            GLES20.glEnableVertexAttribArray(positionLocation);
            vertices.position(2);
            GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false, 16, vertices);
            GLES20.glEnableVertexAttribArray(textureLocation);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            // Back to the unit CodecSurface draws the overlay from.
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        }

        void release() {
            try {
                if (surface != null) surface.release();
                if (texture != null) texture.release();
            } finally {
                if (thread != null) thread.quitSafely();
            }
        }
    }
}
