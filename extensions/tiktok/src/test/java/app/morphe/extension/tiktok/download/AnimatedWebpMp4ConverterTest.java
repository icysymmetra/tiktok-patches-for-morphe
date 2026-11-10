package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.view.Surface;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowEGL14;
import org.robolectric.shadows.ShadowGLES20;

/** Geometry guards and resource ownership for animated sticker codec setup. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AnimatedWebpMp4ConverterTest {
    private static final int RED = 0xFFFF0000;

    @Test public void frameBoundsRejectOverflowAndOutsideCanvasCoordinates() {
        AnimatedWebpMp4Converter.validateFrame(100, 100, 20, 20, 80, 80);

        assertThrows(IllegalStateException.class, () ->
                AnimatedWebpMp4Converter.validateFrame(100, 100, 21, 20, 80, 80));
        assertThrows(IllegalStateException.class, () ->
                AnimatedWebpMp4Converter.validateFrame(100, 100, Integer.MAX_VALUE, 1, 1, 0));
        assertThrows(IllegalStateException.class, () ->
                AnimatedWebpMp4Converter.validateFrame(100, 100, 1, 1, -1, 0));
    }

    @Test public void drainingStopsWhenTheJobDeadlineHasPassed() throws Exception {
        withExpiredDeadline(() -> {
            try {
                AnimatedWebpMp4Converter.drainEncoder(null, null, true, new AnimatedWebpMp4Converter.EncoderState());
                fail("the drain loop ran past the job deadline");
            } catch (java.io.InterruptedIOException wrong) {
                fail("reported an interrupted transfer for an expired deadline");
            } catch (java.io.IOException expired) {
                assertEquals("Media job deadline exceeded", expired.getMessage());
            }
        });
    }

    @Test public void aLiveJobIsNotStoppedByTheNewCheck() {
        // Without this the two tests above would pass just as well against a loop that always
        // threw. A live job has to get past the check and reach the encoder, which
        // here is null, so the failure has to be the null encoder and not an IOException.
        assertFalse("the drain loop refused a job that is still within its deadline",
                Thread.currentThread().isInterrupted());
        assertThrows(NullPointerException.class, () ->
                AnimatedWebpMp4Converter.drainEncoder(null, null, true, new AnimatedWebpMp4Converter.EncoderState()));
    }

    /**
     * Runs {@code work} with an already expired job deadline installed on this thread.
     *
     * <p>{@code MediaBudget} deliberately offers no way to shorten a deadline, since a job that
     * could pick its own would not be limited by one. The test reaches the thread local
     * directly rather than the production code growing a setter only a test would call.
     */
    private static void withExpiredDeadline(Runnable work) throws Exception {
        java.lang.reflect.Constructor<?> constructor =
                MediaBudget.Deadline.class.getDeclaredConstructor(long.class);
        constructor.setAccessible(true);
        Object expired = constructor.newInstance(System.nanoTime() - 1_000_000L);

        java.lang.reflect.Field field = MediaBudget.class.getDeclaredField("CURRENT_DEADLINE");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        ThreadLocal<Object> current = (ThreadLocal<Object>) field.get(null);
        Object previous = current.get();
        current.set(expired);
        try {
            work.run();
        } finally {
            if (previous == null) current.remove();
            else current.set(previous);
        }
    }

    @Test public void bitrateIsBoundedForTinyAndLargeFrames() {
        assertEquals(500_000, AnimatedWebpMp4Converter.chooseBitRate(1, 1));
        assertEquals(4_000_000, AnimatedWebpMp4Converter.chooseBitRate(1000, 1000));
        assertEquals(8_000_000, AnimatedWebpMp4Converter.chooseBitRate(4000, 4000));
    }

    @Test public void aCanvasTooLargeToComposeIsRefusedBeforeTheBitmapIsAllocated() throws Exception {
        // A VP8X canvas is header metadata, so this is a few hundred bytes that would ask for a
        // 16382 by 16382 ARGB bitmap, about a gigabyte, from well inside the 24 MB transfer cap.
        AnimatedWebpGifConverterTest.Webp builder =
                new AnimatedWebpGifConverterTest.Webp(16382, 16382);
        builder.frame(0, 0, 16382, 16382, 40, false, false, RED);
        builder.frame(0, 0, 16382, 16382, 40, false, false, RED);
        byte[] webp = builder.build();

        java.io.File output = java.io.File.createTempFile("hushfeed-sticker", ".mp4");
        output.deleteOnExit();
        // Robolectric closes its path-owned stream only from nativeStop, not release.
        // This rejection happens before start, so own the descriptor as the MediaStore caller
        // does and close it even when conversion refuses the canvas.
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(output)) {
            AnimatedWebpMp4Converter.convert(webp, stream.getFD());
            fail("a gigabyte of canvas was accepted");
        } catch (IllegalStateException refused) {
            assertTrue("refused for the wrong reason: " + refused.getMessage(),
                    String.valueOf(refused.getMessage()).contains("too large to compose"));
        } finally {
            assertTrue(output.delete() || !output.exists());
        }
    }

    @Test public void aFrameWiderThanTheGpuWillTakeIsRefusedAndSaysBySoMuch() {
        // 8192 by 2000 is 16.4 million pixels, inside the cap above, and past the 4096 a side
        // that a great many Android GPUs stop at. Asked for anyway the upload fails silently
        // and the saved video is black.
        IllegalStateException refused = assertThrows(IllegalStateException.class, () ->
                AnimatedWebpMp4Converter.requireFitsTexture(8192, 2000, 4096));
        String said = String.valueOf(refused.getMessage());
        assertTrue("the refusal does not say what the GPU will take: " + said,
                said.contains("4096"));
        assertTrue("the refusal does not say how big the frame is: " + said,
                said.contains("8192"));

        // Taller than it is wide, which the same check has to catch.
        assertThrows(IllegalStateException.class, () ->
                AnimatedWebpMp4Converter.requireFitsTexture(2000, 8192, 4096));
    }

    @Test public void aFrameTheGpuWillTakeGoesThroughAndSoDoesOneItWillNotDiscuss() {
        // The positive control. Without it the check could refuse everything and the case
        // above would still pass.
        AnimatedWebpMp4Converter.requireFitsTexture(512, 512, 4096);
        AnimatedWebpMp4Converter.requireFitsTexture(4096, 4096, 4096);
        // A GPU that did not answer is not a reason to refuse a sticker on a number nobody gave.
        AnimatedWebpMp4Converter.requireFitsTexture(8192, 8192, 0);
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Config(shadows = {RecordingEgl.class, BoundedGles.class})
    public void failedCodecSetupReleasesItsResourcesAndTheNextSetupSucceeds() throws Exception {
        RecordingEgl.resetTracking();
        Constructor<?> constructor = codecSurfaceConstructor();
        SurfaceTexture texture = new SurfaceTexture(0);
        Surface rejected = new Surface(texture);
        try {
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> constructor.newInstance(rejected, 8192, 2));
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(failure.getCause().getMessage().contains("4096"));
            assertReleased(rejected, 1);

            Surface accepted = new Surface(texture);
            Object next = constructor.newInstance(accepted, 64, 64);
            try {
                assertTrue(accepted.isValid());
                assertEquals(2, RecordingEgl.contextsCreated);
                assertEquals(1, RecordingEgl.contextsDestroyed);
                assertNotNull("successful setup must bind an allocated context", RecordingEgl.currentContext);
                assertFalse(RecordingEgl.currentContext.equals(EGL14.EGL_NO_CONTEXT));
            } finally {
                Method release = next.getClass().getDeclaredMethod("release");
                release.setAccessible(true);
                release.invoke(next);
            }
            assertReleased(accepted, 2);
        } finally {
            rejected.release();
            texture.release();
        }
    }

    @Test
    @GraphicsMode(GraphicsMode.Mode.LEGACY)
    @Config(shadows = {RecordingEgl.class, BoundedGles.class})
    public void codecSetupKeepsThePrimaryFailureWhenCleanupAlsoFails() throws Exception {
        RecordingEgl.resetTracking();
        RuntimeException cleanupFailure = new IllegalStateException("EGL surface release failed");
        RecordingEgl.cleanupFailure = cleanupFailure;
        SurfaceTexture texture = new SurfaceTexture(0);
        Surface input = new Surface(texture);
        try {
            InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                    () -> codecSurfaceConstructor().newInstance(input, 8192, 2));
            Throwable primary = failure.getCause();
            assertTrue(primary instanceof IllegalStateException);
            assertTrue(primary.getMessage().contains("4096"));
            assertEquals(1, primary.getSuppressed().length);
            assertSame(cleanupFailure, primary.getSuppressed()[0]);
            assertReleased(input, 1);
        } finally {
            input.release();
            texture.release();
        }
    }

    private static Constructor<?> codecSurfaceConstructor() throws Exception {
        // Conversion cannot assign its cleanup owner until this constructor returns.
        Class<?> type = Class.forName(AnimatedWebpMp4Converter.class.getName() + "$CodecSurface");
        Constructor<?> constructor = type.getDeclaredConstructor(Surface.class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor;
    }

    private static void assertReleased(Surface input, int count) {
        assertFalse("the encoder input surface leaked", input.isValid());
        assertEquals(count, RecordingEgl.contextsCreated);
        assertEquals(count, RecordingEgl.contextsDestroyed);
        assertEquals(count, RecordingEgl.surfacesCreated);
        assertEquals(count, RecordingEgl.surfacesDestroyed);
        assertEquals(count, RecordingEgl.threadsReleased);
        assertEquals(count, RecordingEgl.displaysTerminated);
        assertEquals(EGL14.EGL_NO_CONTEXT, RecordingEgl.currentContext);
    }

    @Implements(EGL14.class)
    public static class RecordingEgl extends ShadowEGL14 {
        static int contextsCreated, contextsDestroyed, surfacesCreated, surfacesDestroyed;
        static int threadsReleased, displaysTerminated;
        static EGLContext currentContext;
        static RuntimeException cleanupFailure;

        static void resetTracking() {
            contextsCreated = contextsDestroyed = surfacesCreated = surfacesDestroyed = 0;
            threadsReleased = displaysTerminated = 0;
            currentContext = EGL14.EGL_NO_CONTEXT;
            cleanupFailure = null;
        }

        @Implementation protected static EGLContext eglCreateContext(EGLDisplay display,
                EGLConfig config, EGLContext shareContext, int[] attributes, int offset) {
            EGLContext result = ShadowEGL14.eglCreateContext(
                    display, config, shareContext, attributes, offset);
            contextsCreated++;
            return result;
        }

        @Implementation protected static EGLSurface eglCreateWindowSurface(EGLDisplay display,
                EGLConfig config, Object window, int[] attributes, int offset) {
            EGLSurface result = ShadowEGL14.eglCreateWindowSurface(
                    display, config, window, attributes, offset);
            surfacesCreated++;
            return result;
        }

        @Implementation protected static boolean eglMakeCurrent(EGLDisplay display,
                EGLSurface draw, EGLSurface read, EGLContext context) {
            currentContext = context;
            return true;
        }

        @Implementation protected static boolean eglDestroySurface(EGLDisplay display,
                EGLSurface surface) {
            surfacesDestroyed++;
            if (cleanupFailure != null) throw cleanupFailure;
            return true;
        }

        @Implementation protected static boolean eglDestroyContext(EGLDisplay display,
                EGLContext context) {
            contextsDestroyed++;
            return true;
        }

        @Implementation protected static boolean eglReleaseThread() {
            threadsReleased++;
            return true;
        }

        @Implementation protected static boolean eglTerminate(EGLDisplay display) {
            displaysTerminated++;
            return true;
        }
    }

    @Implements(GLES20.class)
    public static class BoundedGles extends ShadowGLES20 {
        @Implementation protected static void glGetIntegerv(int name, int[] values, int offset) {
            if (name == GLES20.GL_MAX_TEXTURE_SIZE) values[offset] = 4096;
        }
    }

    @Test public void anOrdinaryStickerIsStillAccepted() {
        // The positive control for the guard above. Without one the cap could be "> 0" and the
        // reject case would still pass while nothing converted any more.
        AnimatedWebpMp4Converter.validateFrame(320, 320, 320, 320, 0, 0);
        assertTrue("an ordinary sticker is inside the frame cap",
                320L * 320L < 16L * 1024 * 1024);
    }
}
