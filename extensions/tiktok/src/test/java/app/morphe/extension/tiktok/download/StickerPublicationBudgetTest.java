package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.content.ContentProvider;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLUtils;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.shared.settings.BaseSettings;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowEGL14;
import org.robolectric.shadows.ShadowMediaCodec;
import org.robolectric.shadows.ShadowMediaMuxer;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.util.ReflectionHelpers;

/** Real sticker saves against a provider and volume whose capacity can change during publication. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = {28, 30}, shadows = {StickerPublicationBudgetTest.Volume.class,
        StickerPublicationBudgetTest.DeadlineClock.class,
        StickerPublicationBudgetTest.Encoder.class, StickerPublicationBudgetTest.Muxer.class,
        StickerPublicationBudgetTest.Egl.class, StickerPublicationBudgetTest.TextureUpload.class})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class StickerPublicationBudgetTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final byte[] EXISTING = {91, 92, 93, 94};
    private static final long FLOOR = MediaBudget.MIN_FREE_BYTES + MediaBudget.PUBLISH_OVERHEAD_BYTES;
    private enum Kind { BYTES, PNG, GIF, MP4 }
    private enum Capacity { ROOMY, BEFORE_PUBLICATION, AFTER_OPEN, AFTER_BYTES }
    private Context context;
    private File directory, existing;
    private String oldPath, oldFormat;
    private boolean oldDebug;
    private Set<String> initialTemps;

    @Before public void prepare() throws Exception {
        context = RuntimeEnvironment.getApplication();
        oldPath = Settings.DOWNLOAD_STICKER_PATH.get();
        oldFormat = Settings.DOWNLOAD_STICKER_FORMAT.get();
        oldDebug = BaseSettings.DEBUG.get();
        BaseSettings.DEBUG.save(true);
        ShadowLog.clear();
        String path = "DCIM/sticker-budget-" + System.nanoTime();
        Settings.DOWNLOAD_STICKER_PATH.save(path);
        directory = new File(Environment.getExternalStorageDirectory(), path);
        assertTrue(directory.mkdirs());
        existing = new File(directory, "keep.png");
        Files.write(existing.toPath(), EXISTING);
        Provider.reset(directory, existing, path);
        Robolectric.setupContentProvider(Provider.class, "media");
        Volume.directory = directory;
        Volume.capacity = Capacity.ROOMY;
        Volume.reads = 0;
        Volume.observedWrittenBytes = 0;
        Encoder.frames = 0;
        Muxer.writes = 0;
        initialTemps = tempNames();
    }

    @After public void cleanUp() throws Exception {
        Volume.capacity = Capacity.ROOMY;
        Settings.DOWNLOAD_STICKER_PATH.save(oldPath);
        Settings.DOWNLOAD_STICKER_FORMAT.save(oldFormat);
        BaseSettings.DEBUG.save(oldDebug);
        for (File file : outputs()) assertTrue("fixture output remained open: " + file, file.delete());
        assertTrue(existing.delete());
        assertTrue(directory.delete());
        Volume.directory = null;
    }

    /** A broken shadow must fail here, not masquerade as a publisher regression. */
    @Test public void capacityFixtureExercisesTheRealReservationGuard() throws Exception {
        MediaBudget.Deadline deadline = MediaBudget.deadline();
        assertFalse("fixture clocks disagree on a fresh deadline", deadline.expired());
        MediaBudget.check(deadline);
        Volume.capacity = Capacity.BEFORE_PUBLICATION;
        MediaBudget.StopException refused = assertThrows(MediaBudget.StopException.class,
                () -> MediaBudget.checkDiskSpace(directory, 1));
        assertTrue(refused.space);
        assertTrue(Volume.reads > 0);
        Volume.capacity = Capacity.ROOMY;
        MediaBudget.checkDiskSpace(directory, 1);
    }

    @Test public void passthroughRespectsDestinationPreflightAndRecovers() throws Exception { exercise(Kind.BYTES, Capacity.BEFORE_PUBLICATION); }
    @Test public void pngRespectsDestinationPreflightAndRecovers() throws Exception { exercise(Kind.PNG, Capacity.BEFORE_PUBLICATION); }
    @Test public void gifRespectsDestinationPreflightAndRecovers() throws Exception { exercise(Kind.GIF, Capacity.BEFORE_PUBLICATION); }
    @Test public void mp4RespectsDestinationPreflightAndRecovers() throws Exception { exercise(Kind.MP4, Capacity.BEFORE_PUBLICATION); }
    @Test public void passthroughCleansAnOutputWhenSpaceDropsAfterOpening() throws Exception { exercise(Kind.BYTES, Capacity.AFTER_OPEN); }
    @Test public void pngCleansAnOutputWhenSpaceDropsAfterOpening() throws Exception { exercise(Kind.PNG, Capacity.AFTER_OPEN); }
    @Test public void gifCleansAnOutputWhenSpaceDropsAfterOpening() throws Exception { exercise(Kind.GIF, Capacity.AFTER_OPEN); }
    @Test public void mp4CleansAnOutputWhenSpaceDropsAfterOpening() throws Exception { exercise(Kind.MP4, Capacity.AFTER_OPEN); }
    @Test public void passthroughRechecksSpaceAfterWritingItsFirstWindow() throws Exception { exercise(Kind.BYTES, Capacity.AFTER_BYTES); }
    @Test public void mp4RechecksSpaceBetweenEncodedSamples() throws Exception { exercise(Kind.MP4, Capacity.AFTER_BYTES); }

    private void exercise(Kind kind, Capacity capacity) throws Exception {
        Volume.capacity = capacity;
        Object refused = save(kind);
        boolean reportedSuccess = ReflectionHelpers.getField(refused, "success");
        List<File> leftovers = outputs();
        int rowsAfterRefusal = Provider.rows.size();
        long observedBytes = Volume.observedWrittenBytes;
        int samplesAfterRefusal = Muxer.writes;
        boolean sourceCleaned = initialTemps.equals(tempNames());
        // The control runs before the negative assertions, so even an unfixed publisher must
        // prove that this fixture can save this exact format through its real output route.
        Volume.capacity = Capacity.ROOMY;
        Object recovered = save(kind);
        assertTrue("fixture could not save " + kind + " with room available: " + failureLog(),
                ReflectionHelpers.<Boolean>getField(recovered, "success"));
        String landed = ReflectionHelpers.getField(recovered, "path");
        if (kind == Kind.MP4) {
            assertTrue("codec control fell back to WebP: " + landed, landed.contains(".mp4"));
            assertTrue("the converter never submitted a frame", Encoder.frames > 0);
            assertTrue("the converter never wrote through MediaMuxer", Muxer.writes > 0);
        }
        if (kind == Kind.GIF) assertTrue("GIF control fell back to WebP: " + landed, landed.contains(".gif"));
        assertTrue(outputs().stream().anyMatch(file -> file.length() > 0));
        assertArrayEquals("an older gallery file was changed", EXISTING, Files.readAllBytes(existing.toPath()));
        assertFalse("publication ignored the destination reserve for " + kind, reportedSuccess);
        assertTrue("failed publication left a claimed/pending file: " + leftovers, leftovers.isEmpty());
        if (Build.VERSION.SDK_INT >= 29) assertEquals("failed publication left a MediaStore row", 1, rowsAfterRefusal);
        assertTrue("the worker retained its downloaded source", sourceCleaned);
        assertEquals("successful recovery retained its downloaded source", initialTemps, tempNames());
        if (capacity == Capacity.AFTER_BYTES) {
            assertTrue("the refusal did not reach a partial output", observedBytes > 0);
            assertTrue("the write exceeded its first disk grant", observedBytes <= MediaBudget.STREAM_SPACE_CHECK_BYTES);
            if (kind == Kind.MP4) assertEquals("a second encoded sample passed the reserve", 1, samplesAfterRefusal);
        }
    }

    private static String failureLog() {
        java.io.StringWriter text = new java.io.StringWriter();
        java.io.PrintWriter output = new java.io.PrintWriter(text);
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            output.println(item.msg);
            if (item.throwable != null) item.throwable.printStackTrace(output);
        }
        return text.toString();
    }

    private Object save(Kind kind) throws Exception {
        byte[] source;
        String contentType;
        if (kind == Kind.PNG) {
            Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            bitmap.eraseColor(0xffff0000);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            try { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, encoded)); }
            finally { bitmap.recycle(); }
            source = encoded.toByteArray();
            contentType = "image/png";
        } else if (kind == Kind.BYTES) {
            byte[] gif = Base64.getDecoder().decode("R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7");
            source = Arrays.copyOf(gif, 2 * 1024 * 1024);
            contentType = "image/gif";
        } else {
            source = new AnimatedWebpGifConverterTest.Webp(8, 8)
                    .frame(0, 0, 8, 8, 40, false, false, 0xffff0000)
                    .frame(0, 0, 8, 8, 40, false, false, 0xff00ff00).build();
            contentType = "image/webp";
        }
        Settings.DOWNLOAD_STICKER_FORMAT.save(kind == Kind.GIF ? "gif" : "mp4");
        String url = "https://8.8.8.8/sticker-" + kind;
        StickerGallerySaver.StickerAsset asset = new StickerGallerySaver.StickerAsset(url,
                kind == Kind.GIF || kind == Kind.MP4);
        Method worker = StickerGallerySaver.class.getDeclaredMethod("saveSticker", Context.class,
                StickerGallerySaver.StickerAsset.class);
        worker.setAccessible(true);
        try (HttpsBody ignored = new HttpsBody(source, contentType)) {
            return worker.invoke(null, context, asset);
        }
    }

    private List<File> outputs() {
        File[] files = directory.listFiles();
        List<File> result = new ArrayList<>();
        if (files != null) for (File file : files) if (!file.equals(existing)) result.add(file);
        return result;
    }

    private Set<String> tempNames() {
        File[] files = new File(context.getCacheDir(), MediaCache.DIRECTORY_NAME).listFiles();
        Set<String> names = new HashSet<>();
        if (files != null) for (File file : files) if (file.getName().startsWith("sticker-source-")) names.add(file.getName());
        return names;
    }

    /** Shadows only the filesystem observation. All production budget decisions still execute. */
    @Implements(value = MediaBudget.class, isInAndroidSdk = false)
    public static class Volume {
        static File directory;
        static Capacity capacity;
        static int reads;
        static long observedWrittenBytes;

        @Implementation protected static long usableSpace(File target) {
            if (target == null) return 0;
            String root = Environment.getExternalStorageDirectory().getAbsolutePath();
            if (!target.getAbsolutePath().startsWith(root)) return 1024L * 1024 * 1024;
            reads++;
            File[] files = directory == null ? null : directory.listFiles();
            int created = 0;
            long written = 0;
            if (files != null) for (File file : files) if (!file.getName().equals("keep.png")) {
                created++;
                written += file.length();
            }
            observedWrittenBytes = Math.max(observedWrittenBytes, written);
            boolean low = capacity == Capacity.BEFORE_PUBLICATION
                    || capacity == Capacity.AFTER_OPEN && created > 0
                    || capacity == Capacity.AFTER_BYTES && written > 0;
            return low ? FLOOR - 1 : 1024L * 1024 * 1024;
        }
    }

    /** Keep the nested reader on the same Robolectric clock as its instrumented factory. */
    @Implements(value = MediaBudget.Deadline.class, isInAndroidSdk = false)
    public static class DeadlineClock { }

    /** Owns real output files; row deletion also deletes only that row's bytes. */
    public static class Provider extends ContentProvider {
        static final Map<Long, ContentValues> rows = new LinkedHashMap<>();
        static final Map<Long, File> files = new LinkedHashMap<>();
        static File directory;
        static long next;

        static void reset(File folder, File existing, String path) {
            directory = folder;
            rows.clear(); files.clear(); next = 2;
            ContentValues row = new ContentValues();
            row.put(MediaStore.MediaColumns.DISPLAY_NAME, existing.getName());
            row.put(MediaStore.MediaColumns.RELATIVE_PATH, path);
            row.put(MediaStore.MediaColumns.IS_PENDING, 0);
            rows.put(1L, row); files.put(1L, existing);
        }
        @Override public boolean onCreate() { return true; }
        @Override public Uri insert(Uri uri, ContentValues values) {
            long id = next++;
            rows.put(id, new ContentValues(values));
            files.put(id, new File(directory, "provider-" + id));
            return ContentUris.withAppendedId(uri, id);
        }
        @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws java.io.FileNotFoundException {
            return ParcelFileDescriptor.open(files.get(ContentUris.parseId(uri)),
                    ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_READ_WRITE);
        }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) {
            ContentValues row = rows.get(ContentUris.parseId(uri));
            if (row == null) return 0;
            row.putAll(values);
            return 1;
        }
        @Override public int delete(Uri uri, String selection, String[] args) {
            long id = ContentUris.parseId(uri);
            File file = files.remove(id);
            if (file != null && file.exists() && !file.delete()) throw new IllegalStateException("Open output " + file);
            return rows.remove(id) == null ? 0 : 1;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
            ContentValues row = rows.get(ContentUris.parseId(uri));
            MatrixCursor cursor = new MatrixCursor(projection);
            if (row != null) {
                Object[] values = new Object[projection.length];
                for (int i = 0; i < values.length; i++) values[i] = row.get(projection[i]);
                cursor.addRow(values);
            }
            return cursor;
        }
        @Override public String getType(Uri uri) { return null; }
    }

    /** Deterministic platform codec output, leaving frame composition and muxing in production. */
    @Implements(MediaCodec.class)
    public static class Encoder extends ShadowMediaCodec {
        static Encoder running;
        static int frames;
        private int queued;
        private boolean formatSent, ended;
        private SurfaceTexture texture;
        @Implementation protected void configure(MediaFormat format, Surface surface, MediaCrypto crypto, int flags) { }
        @Implementation protected Surface createInputSurface() {
            texture = new SurfaceTexture(0);
            return new Surface(texture);
        }
        @Implementation protected void start() { running = this; }
        @Implementation protected MediaFormat getOutputFormat() { return MediaFormat.createVideoFormat("video/avc", 8, 8); }
        @Implementation protected int dequeueOutputBuffer(MediaCodec.BufferInfo info, long timeout) {
            if (!formatSent) { formatSent = true; return MediaCodec.INFO_OUTPUT_FORMAT_CHANGED; }
            if (queued > 0) { queued--; info.set(0, 4, frames * 40_000L, 0); return 0; }
            if (ended) { info.set(0, 0, frames * 40_000L, MediaCodec.BUFFER_FLAG_END_OF_STREAM); return 0; }
            return MediaCodec.INFO_TRY_AGAIN_LATER;
        }
        @Implementation protected ByteBuffer getOutputBuffer(int index) { return ByteBuffer.wrap(new byte[]{1, 2, 3, 4}); }
        @Implementation protected void releaseOutputBuffer(int index, boolean render) { }
        @Implementation protected void signalEndOfInputStream() { ended = true; }
        @Implementation protected void stop() { }
        @Implementation protected void release() { running = null; if (texture != null) texture.release(); }
    }

    @Implements(EGL14.class)
    public static class Egl extends ShadowEGL14 {
        @Implementation protected static boolean eglSwapBuffers(EGLDisplay display, EGLSurface surface) {
            if (Encoder.running != null) { Encoder.running.queued++; Encoder.frames++; }
            return true;
        }
    }

    @Implements(GLUtils.class)
    public static class TextureUpload {
        @Implementation protected static void texImage2D(int target, int level, Bitmap bitmap, int border) { }
    }

    /** The stock shadow closes only on stop; native release also closes a failed conversion. */
    @Implements(MediaMuxer.class)
    public static class Muxer extends ShadowMediaMuxer {
        static int writes;
        private static final Set<Long> open = new HashSet<>();
        @Implementation protected static long nativeSetup(FileDescriptor descriptor, int format) throws IOException {
            long handle = ShadowMediaMuxer.nativeSetup(descriptor, format);
            open.add(handle);
            return handle;
        }
        @Implementation protected static void nativeStart(long handle) { }
        @Implementation protected static void nativeWriteSampleData(long handle, int track, ByteBuffer bytes,
                int offset, int size, long time, int flags) {
            writes++;
            ShadowMediaMuxer.nativeWriteSampleData(handle, track, bytes, offset, size, time, flags);
        }
        @Implementation protected static void nativeStop(long handle) {
            if (open.remove(handle)) ShadowMediaMuxer.nativeStop(handle);
        }
        @Implementation protected static void nativeRelease(long handle) { nativeStop(handle); }
    }

    /** Same HTTPS handler seam as MediaTransportSecurityTest, with no network request. */
    private static final class HttpsBody implements AutoCloseable {
        private final Hashtable<String, URLStreamHandler> handlers;
        private final URLStreamHandler previous;
        HttpsBody(byte[] body, String contentType) throws Exception {
            new URL("https://8.8.8.8/");
            handlers = ReflectionHelpers.getStaticField(URL.class, "handlers");
            previous = handlers.put("https", new URLStreamHandler() {
                // The pinned media connection opens with Proxy.NO_PROXY; the base overload throws.
                @Override protected URLConnection openConnection(URL url, java.net.Proxy proxy) throws java.io.IOException { return openConnection(url); }
                @Override protected URLConnection openConnection(URL url) {
                    return new FakeHttpsConnection(url) {
                        @Override public int getResponseCode() { return HTTP_OK; }
                        @Override public String getHeaderField(String name) {
                            return "Content-Length".equalsIgnoreCase(name) ? String.valueOf(body.length) : null;
                        }
                        @Override public String getContentType() { return contentType; }
                        @Override public java.io.InputStream getInputStream() { return new ByteArrayInputStream(body); }
                        @Override public void connect() { }
                        @Override public void disconnect() { }
                        @Override public boolean usingProxy() { return false; }
                    };
                }
            });
        }
        @Override public void close() {
            if (previous == null) handlers.remove("https");
            else handlers.put("https", previous);
        }
    }
}
