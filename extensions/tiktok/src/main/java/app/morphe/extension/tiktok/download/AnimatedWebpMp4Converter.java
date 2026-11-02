/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.download;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;

import androidx.annotation.RequiresApi;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Converts TikTok animated WebP sticker frames to a Gallery-compatible MP4. */
final class AnimatedWebpMp4Converter {
    /**
     * A cap on one composed frame, in pixels rather than pixels times frames, because only one
     * frame is held at a time here. It is not the same situation as the still sticker path,
     * which decodes one bitmap: at this cap the composed bitmap, the frame bitmap and the
     * texture upload are each around 64 MB. What actually holds the ceiling down on a device is
     * the encoder, which refuses a format far below this, and that runs before the allocation.
     * This is the guard for the case where it does not.
     */
    private static final long MAX_FRAME_PIXELS = 16L * 1024 * 1024;

    /**
     * What this GPU will take as a texture, a side at a time, or zero when it has not said.
     *
     * <p>Only meaningful once an EGL context is current, so it is read there rather than beside
     * the pixel cap, which runs before there is a context to ask.
     */
    static int maxTextureSize() {
        try {
            int[] value = new int[1];
            GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, value, 0);
            return Math.max(0, value[0]);
        } catch (Throwable unavailable) {
            return 0;
        }
    }

    /**
     * Refuses a frame this GPU cannot hold.
     *
     * @param maximum a side, as the GPU reports it. Zero or less means it did not say, and a
     *                frame is let through rather than refused on a number nobody supplied.
     */
    static void requireFitsTexture(int width, int height, int maximum) {
        if (maximum <= 0 || (width <= maximum && height <= maximum)) return;
        throw new IllegalStateException("Animated WebP frame is " + width + " by " + height
                + ", larger than this device's maximum texture size of " + maximum);
    }

    private static final String MIME_TYPE = "video/avc";
    private static final int FRAME_RATE = 30;
    private static final int I_FRAME_INTERVAL_SECONDS = 1;
    static final long CODEC_TIMEOUT_US = 10_000L;

    private AnimatedWebpMp4Converter() {
    }

    // The muxer that writes to a file descriptor arrived in API 26, while the one that takes a
    // path is API 18. Saying so here is what lets the API level check see that the only caller
    // is behind a Q guard, rather than reporting a call it cannot follow.
    @RequiresApi(26)
    static void convert(byte[] webpData, FileDescriptor output) throws Exception {
        convert(webpData, output, null);
    }

    @RequiresApi(26)
    static void convert(byte[] webpData, FileDescriptor output, File directory) throws Exception {
        MediaBudget.checkStreamingDiskSpace(directory, null);
        convert(webpData, new MediaMuxer(output, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), directory);
    }

    static void convert(byte[] webpData, String outputPath) throws Exception {
        File directory = new File(outputPath).getAbsoluteFile().getParentFile();
        MediaBudget.checkStreamingDiskSpace(directory, null);
        convert(webpData, new MediaMuxer(outputPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4), directory);
    }

    private static void convert(byte[] webpData, MediaMuxer muxer, File directory) throws Exception {
        Object image = null;
        MediaCodec encoder = null;
        CodecSurface codecSurface = null;
        boolean muxerStarted = false;
        try {
            Class<?> imageClass = Class.forName("com.facebook.animated.webp.WebPImage");
            Method create = imageClass.getDeclaredMethod("create", byte[].class);
            create.setAccessible(true);
            image = create.invoke(null, (Object) webpData);
            if (image == null) throw new IllegalStateException("WebP decoder returned null");

            int sourceWidth = invokeInt(image, "getWidth");
            int sourceHeight = invokeInt(image, "getHeight");
            int frameCount = invokeInt(image, "getFrameCount");
            int[] durations = (int[]) invoke(image, "getFrameDurations");
            if (sourceWidth <= 0 || sourceHeight <= 0 || frameCount <= 0) {
                throw new IllegalStateException("Invalid animated WebP dimensions or frame count");
            }
            // The canvas size is header metadata, so a few hundred bytes inside the transfer cap
            // can declare 16383 by 16383 and ask for about a gigabyte below. The GIF converter
            // beside this one has always capped its frames; this path had nothing, and MP4 is the
            // sticker format that ships on by default.
            if ((long) sourceWidth * sourceHeight > MAX_FRAME_PIXELS) {
                throw new IllegalStateException("Animated WebP frame is too large to compose");
            }

            int outputWidth = sourceWidth + (sourceWidth & 1);
            int outputHeight = sourceHeight + (sourceHeight & 1);
            MediaFormat format = MediaFormat.createVideoFormat(MIME_TYPE, outputWidth, outputHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, chooseBitRate(outputWidth, outputHeight));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS);

            encoder = MediaCodec.createEncoderByType(MIME_TYPE);
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            Surface inputSurface = encoder.createInputSurface();
            codecSurface = new CodecSurface(inputSurface, outputWidth, outputHeight);
            encoder.start();

            Bitmap composed = Bitmap.createBitmap(sourceWidth, sourceHeight, Bitmap.Config.ARGB_8888);
            Canvas composedCanvas = new Canvas(composed);
            Paint sourcePaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            Paint replacePaint = new Paint(sourcePaint);
            replacePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC));
            Paint clearPaint = new Paint();
            clearPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

            int previousX = 0;
            int previousY = 0;
            int previousWidth = 0;
            int previousHeight = 0;
            boolean clearPrevious = false;
            long presentationTimeNs = 0L;
            EncoderState state = new EncoderState();

            try {
                for (int frameIndex = 0; frameIndex < frameCount; frameIndex++) {
                    if (clearPrevious) {
                        composedCanvas.drawRect(
                                previousX,
                                previousY,
                                previousX + previousWidth,
                                previousY + previousHeight,
                                clearPaint
                        );
                    }

                    Object frame = null;
                    Bitmap frameBitmap = null;
                    try {
                        frame = invokeFrame(image, frameIndex);
                        int frameWidth = invokeInt(frame, "getWidth");
                        int frameHeight = invokeInt(frame, "getHeight");
                        int xOffset = invokeInt(frame, "getXOffset");
                        int yOffset = invokeInt(frame, "getYOffset");
                        validateFrame(sourceWidth, sourceHeight, frameWidth, frameHeight, xOffset, yOffset);

                        // The same two Fresco names the GIF converter reads, and the same
                        // finding: LIZ()Z is nativeIsBlendWithPreviousFrame and LIZIZ()Z is
                        // nativeShouldDisposeToBackgroundColor in the 46.2.3 host.
                        boolean blend = invokeBoolean(frame, "LIZ");
                        clearPrevious = invokeBoolean(frame, "LIZIZ");
                        frameBitmap = Bitmap.createBitmap(frameWidth, frameHeight, Bitmap.Config.ARGB_8888);
                        invoke(frame, "renderFrame", new Class<?>[]{
                                int.class, int.class, Bitmap.class
                        }, frameWidth, frameHeight, frameBitmap);
                        composedCanvas.drawBitmap(
                                frameBitmap,
                                xOffset,
                                yOffset,
                                blend ? sourcePaint : replacePaint
                        );

                        codecSurface.draw(composed, presentationTimeNs);
                        drainEncoder(encoder, muxer, false, state, directory);

                        int durationMs = durations != null && frameIndex < durations.length
                                ? durations[frameIndex]
                                : 100;
                        presentationTimeNs += Math.max(10, durationMs) * 1_000_000L;
                        previousX = xOffset;
                        previousY = yOffset;
                        previousWidth = frameWidth;
                        previousHeight = frameHeight;
                    } finally {
                        if (frameBitmap != null) frameBitmap.recycle();
                        dispose(frame);
                    }
                }

                encoder.signalEndOfInputStream();
                drainEncoder(encoder, muxer, true, state, directory);
                muxerStarted = state.muxerStarted;
            } finally {
                composed.recycle();
                sourcePaint.setXfermode(null);
                replacePaint.setXfermode(null);
                clearPaint.setXfermode(null);
            }
        } finally {
            if (encoder != null) {
                try {
                    encoder.stop();
                } catch (Throwable ignored) {
                    // An encoder that never started, or already failed, refuses to stop. The
                    // release below is what has to happen either way.
                }
                encoder.release();
            }
            if (codecSurface != null) codecSurface.release();
            try {
                if (muxerStarted) muxer.stop();
            } finally {
                muxer.release();
            }
            dispose(image);
        }
    }

    static int chooseBitRate(int width, int height) {
        long proposed = (long) width * height * 4L;
        return (int) Math.max(500_000L, Math.min(8_000_000L, proposed));
    }

    /** Package-private so a test can drive the loop with no encoder behind it. */
    static void drainEncoder(
            MediaCodec encoder,
            MediaMuxer muxer,
            boolean endOfStream,
            EncoderState state
    ) throws IOException {
        drainEncoder(encoder, muxer, endOfStream, state, null);
    }

    /** Package-private for {@link SlideshowEncoder}, which drains its encoder the same way. */
    static void drainEncoder(
            MediaCodec encoder,
            MediaMuxer muxer,
            boolean endOfStream,
            EncoderState state,
            File directory
    ) throws IOException {
        drainEncoder(encoder, muxer, endOfStream, state, directory, CODEC_TIMEOUT_US);
    }

    /**
     * As above, waiting at most {@code timeoutUs} for output that isn't there yet. A caller
     * draining between frames can pass 0 while the encoder keeps up: it's still working on the
     * frame just sent, and waiting for it each time cost a slideshow about a minute across
     * thousands of frames.
     */
    static void drainEncoder(
            MediaCodec encoder,
            MediaMuxer muxer,
            boolean endOfStream,
            EncoderState state,
            File directory,
            long timeoutUs
    ) throws IOException {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (true) {
            // An encoder that stops emitting after signalEndOfInputStream leaves this loop
            // spinning. One of the three media worker threads was then gone for the life of the
            // process. This is the only place in the loop that can notice the job deadline.
            MediaBudget.check(null);
            int outputIndex = encoder.dequeueOutputBuffer(info, endOfStream ? CODEC_TIMEOUT_US : timeoutUs);
            if (outputIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!endOfStream) return;
            } else if (outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                if (state.muxerStarted) throw new IllegalStateException("Encoder format changed twice");
                state.trackIndex = muxer.addTrack(encoder.getOutputFormat());
                // The muxer takes no track once it has started, and it starts here.
                if (state.companion != null) state.companionTrack = muxer.addTrack(state.companion);
                muxer.start();
                state.muxerStarted = true;
            } else if (outputIndex >= 0) {
                ByteBuffer outputBuffer = encoder.getOutputBuffer(outputIndex);
                if (outputBuffer == null) throw new IllegalStateException("Encoder output buffer was null");
                if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
                if (info.size > 0) {
                    if (!state.muxerStarted) throw new IllegalStateException("Muxer has not started");
                    outputBuffer.position(info.offset);
                    outputBuffer.limit(info.offset + info.size);
                    MediaBudget.checkDiskSpace(directory, info.size);
                    muxer.writeSampleData(state.trackIndex, outputBuffer, info);
                    state.samples++;
                }
                encoder.releaseOutputBuffer(outputIndex, false);
                if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
            }
        }
    }

    private static Object invokeFrame(Object image, int index) throws Exception {
        for (Method method : image.getClass().getMethods()) {
            if (!"getFrame".equals(method.getName()) || method.getParameterTypes().length != 1) continue;
            if (!method.getReturnType().getName().endsWith("WebPFrame")) continue;
            method.setAccessible(true);
            return method.invoke(image, index);
        }
        throw new NoSuchMethodException("WebPImage.getFrame(int)");
    }

    private static Object invoke(Object target, String methodName) throws Exception {
        return invoke(target, methodName, new Class<?>[0]);
    }

    private static Object invoke(
            Object target,
            String methodName,
            Class<?>[] parameterTypes,
            Object... arguments
    ) throws Exception {
        Method method = target.getClass().getMethod(methodName, parameterTypes);
        method.setAccessible(true);
        return method.invoke(target, arguments);
    }

    private static int invokeInt(Object target, String methodName) throws Exception {
        return ((Number) invoke(target, methodName)).intValue();
    }

    private static boolean invokeBoolean(Object target, String methodName) throws Exception {
        return (Boolean) invoke(target, methodName);
    }

    private static void dispose(Object target) {
        if (target == null) return;
        try {
            invoke(target, "dispose");
        } catch (Throwable ignored) {
            // Best effort on a decoder this build may not have, or may already have closed.
            // Nothing the caller can do about it, and the conversion is finished either way.
        }
    }

    static void validateFrame(
            int canvasWidth,
            int canvasHeight,
            int frameWidth,
            int frameHeight,
            int xOffset,
            int yOffset
    ) {
        if (frameWidth <= 0 || frameHeight <= 0 || xOffset < 0 || yOffset < 0
                || (long) xOffset + frameWidth > canvasWidth
                || (long) yOffset + frameHeight > canvasHeight) {
            throw new IllegalStateException("Animated WebP frame is outside its canvas");
        }
    }

    /** Package-private with the drain loop it belongs to, so a test can call that loop. */
    static final class EncoderState {
        int trackIndex = -1;
        boolean muxerStarted;
        /** Encoded frames written to the file, so a caller can tell how far the encoder lags. */
        long samples;
        /**
         * A track copied in beside the encoded one, such as a video's own sound, or null. It's
         * added as the encoder's format arrives, just before the muxer starts.
         */
        MediaFormat companion;
        /** Where {@link #companion} went in the muxer, once it's there. */
        int companionTrack = -1;
    }

    /**
     * The encoder's input surface behind EGL. {@link SlideshowEncoder} draws its photos through
     * it too, and {@link CaptionBurner} a saved video's frames with the caption over them.
     */
    static final class CodecSurface {
        private static final float[] VERTICES = {
                -1f, -1f, 0f, 1f,
                1f, -1f, 1f, 1f,
                -1f, 1f, 0f, 0f,
                1f, 1f, 1f, 0f
        };
        private static final String VERTEX_SHADER =
                "attribute vec2 aPosition;attribute vec2 aTexCoord;varying vec2 vTexCoord;" +
                        "void main(){gl_Position=vec4(aPosition,0.0,1.0);vTexCoord=aTexCoord;}";
        private static final String FRAGMENT_SHADER =
                "precision mediump float;uniform sampler2D uTexture;varying vec2 vTexCoord;" +
                        "void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}";

        private final Surface surface;
        private EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        private EGLContext context = EGL14.EGL_NO_CONTEXT;
        private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        private final int program;
        private final int texture;
        private final FloatBuffer vertices;
        private final int width;
        private final int height;

        CodecSurface(Surface surface, int width, int height) {
            this.surface = surface;
            this.width = width;
            this.height = height;
            try {
                display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
                if (display == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("No EGL display");
                int[] version = new int[2];
                if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                    throw new IllegalStateException("Could not initialize EGL");
                }
                int[] configAttributes = {
                        EGL14.EGL_RED_SIZE, 8,
                        EGL14.EGL_GREEN_SIZE, 8,
                        EGL14.EGL_BLUE_SIZE, 8,
                        EGL14.EGL_ALPHA_SIZE, 8,
                        EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                        0x3142, 1,
                        EGL14.EGL_NONE
                };
                EGLConfig[] configs = new EGLConfig[1];
                int[] configCount = new int[1];
                if (!EGL14.eglChooseConfig(display, configAttributes, 0, configs, 0, 1, configCount, 0)
                        || configCount[0] == 0) {
                    throw new IllegalStateException("No recordable EGL config");
                }
                int[] contextAttributes = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
                context = EGL14.eglCreateContext(
                        display, configs[0], EGL14.EGL_NO_CONTEXT, contextAttributes, 0
                );
                int[] surfaceAttributes = {EGL14.EGL_NONE};
                eglSurface = EGL14.eglCreateWindowSurface(
                        display, configs[0], surface, surfaceAttributes, 0
                );
                if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) {
                    throw new IllegalStateException("Could not make codec EGL surface current");
                }
                // The pixel cap above does not know what this GPU will take. A great many Android
                // GPUs stop at 4096 a side, and 8192 by 2000 is well inside sixteen million pixels.
                // Asked for anyway the upload sets GL_INVALID_VALUE, which nothing was reading, and
                // the swap still succeeds, so the sticker saved as a black video.
                requireFitsTexture(width, height, maxTextureSize());

                program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);
                int[] textures = new int[1];
                GLES20.glGenTextures(1, textures, 0);
                texture = textures[0];
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
                GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
                vertices = ByteBuffer.allocateDirect(VERTICES.length * 4)
                        .order(ByteOrder.nativeOrder())
                        .asFloatBuffer();
                vertices.put(VERTICES).position(0);
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

        void draw(Bitmap bitmap, long presentationTimeNs) {
            upload(bitmap);
            present(presentationTimeNs);
        }

        /**
         * Puts {@code bitmap} in the texture every later {@link #present} draws. A slideshow holds
         * one photo for many frames, and uploading eight megabytes for each of them bought nothing.
         */
        void upload(Bitmap bitmap) {
            GLES20.glUseProgram(program);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            // The flag is sticky and nothing else here reads it, so program and texture setup
            // leave whatever they set for the first frame to find. Drained first, the check
            // below reports this upload rather than something that happened before it.
            while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                // Discarding on purpose: these belong to setup, which has already succeeded far
                // enough to get here, and attributing them to a frame would be a lie.
            }
            android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            // Belt and braces for a device whose reported maximum is not the whole story. The
            // caller writes the WebP as it came instead, which beats writing a black MP4.
            int uploadError = GLES20.glGetError();
            if (uploadError != GLES20.GL_NO_ERROR) {
                throw new IllegalStateException("Could not upload a frame to the GPU, "
                        + "OpenGL error 0x" + Integer.toHexString(uploadError));
            }
        }

        /** Draws the uploaded texture as one frame stamped {@code presentationTimeNs}. */
        void present(long presentationTimeNs) {
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0f, 0f, 0f, 0f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            drawUploaded();
            submit(presentationTimeNs);
        }

        /**
         * Draws the uploaded texture across the whole frame, over whatever is there already.
         * {@link CaptionBurner} draws a decoded frame first and blends its overlay on with this.
         */
        void drawUploaded() {
            GLES20.glUseProgram(program);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
            int positionLocation = GLES20.glGetAttribLocation(program, "aPosition");
            int textureLocation = GLES20.glGetAttribLocation(program, "aTexCoord");
            vertices.position(0);
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 16, vertices);
            GLES20.glEnableVertexAttribArray(positionLocation);
            vertices.position(2);
            GLES20.glVertexAttribPointer(textureLocation, 2, GLES20.GL_FLOAT, false, 16, vertices);
            GLES20.glEnableVertexAttribArray(textureLocation);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

        /** Hands what's drawn to the encoder as one frame stamped {@code presentationTimeNs}. */
        void submit(long presentationTimeNs) {
            EGLExt.eglPresentationTimeANDROID(display, eglSurface, presentationTimeNs);
            if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                throw new IllegalStateException("Could not submit MP4 frame");
            }
        }

        void release() {
            try {
                if (display != EGL14.EGL_NO_DISPLAY) {
                    try {
                        try {
                            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE,
                                    EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                        } finally {
                            try {
                                if (eglSurface != EGL14.EGL_NO_SURFACE) {
                                    EGL14.eglDestroySurface(display, eglSurface);
                                }
                            } finally {
                                if (context != EGL14.EGL_NO_CONTEXT) {
                                    EGL14.eglDestroyContext(display, context);
                                }
                            }
                        }
                    } finally {
                        try {
                            EGL14.eglReleaseThread();
                        } finally {
                            EGL14.eglTerminate(display);
                        }
                    }
                }
            } finally {
                surface.release();
            }
        }

        /** Package-private so {@link CaptionBurner} builds its decoded-frame program the same way. */
        static int createProgram(String vertexSource, String fragmentSource) {
            int vertexShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexSource);
            int fragmentShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentSource);
            int result = GLES20.glCreateProgram();
            GLES20.glAttachShader(result, vertexShader);
            GLES20.glAttachShader(result, fragmentShader);
            GLES20.glLinkProgram(result);
            int[] status = new int[1];
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, status, 0);
            GLES20.glDeleteShader(vertexShader);
            GLES20.glDeleteShader(fragmentShader);
            if (status[0] == 0) {
                String log = GLES20.glGetProgramInfoLog(result);
                GLES20.glDeleteProgram(result);
                throw new IllegalStateException("Could not link sticker video shader: " + log);
            }
            return result;
        }

        private static int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] status = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0);
            if (status[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException("Could not compile sticker video shader: " + log);
            }
            return shader;
        }
    }
}
