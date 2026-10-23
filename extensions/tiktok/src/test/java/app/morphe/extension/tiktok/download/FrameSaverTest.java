package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Environment;
import android.os.Looper;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowToast;

/**
 * The Long press frame save, through the real media job and gallery writer. Robolectric draws no
 * video, so a TextureView here hands back a frame of its own and the SurfaceView reader is stood in
 * for; which view is read, at what size, and what lands in the photo folder are production code.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class FrameSaverTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String DIRECTORY = "DCIM/FrameSaverTest";
    private static final int FRAME_COLOR = Color.rgb(200, 40, 90);

    private ActivityController<Activity> owner;
    private Activity activity;
    private FrameLayout content;
    private File directory;
    private String oldPath;
    private FrameSaver.SurfaceReader oldReader;

    @Before public void setUp() {
        oldPath = Settings.DOWNLOAD_PHOTO_PATH.get();
        oldReader = FrameSaver.surfaceReader;
        Settings.DOWNLOAD_PHOTO_PATH.save(DIRECTORY);
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        assertTrue("an earlier save was still running", MediaJobScheduler.idle());
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        activity = owner.get();
        content = new FrameLayout(activity);
        activity.setContentView(content);
        directory = new File(Environment.getExternalStorageDirectory(), DIRECTORY);
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        try {
            awaitSaves();
            if (owner != null) owner.pause().stop().destroy();
            if (directory != null && directory.isDirectory()) {
                File[] saved = directory.listFiles();
                assertNotNull(saved);
                for (File file : saved) assertTrue(file.delete());
                assertTrue(directory.delete());
            }
        } finally {
            FrameSaver.surfaceReader = oldReader;
            Settings.DOWNLOAD_PHOTO_PATH.save(oldPath);
        }
    }

    /** The video's view at 400x700 with the caption over it, as the feed lays a cell out. */
    private void show(View video) {
        content.addView(video, new FrameLayout.LayoutParams(400, 700));
        TextView caption = new TextView(activity);
        caption.setText("a caption that isn't part of the frame");
        content.addView(caption, new FrameLayout.LayoutParams(400, 100));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, 400, 800);
    }

    private static void awaitSaves() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!MediaJobScheduler.idle()) {
            assertTrue("the frame save did not finish", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** Nothing reached the photo folder and nothing waits to. */
    private void assertNothingSaved() throws InterruptedException {
        awaitSaves();
        String[] files = directory.list();
        assertTrue("a frame was saved", files == null || files.length == 0);
    }

    private void assertSavedJpeg(File saved, int width, int height) throws Exception {
        assertTrue("the frame was not published as " + saved.getName(), saved.isFile());
        byte[] bytes = Files.readAllBytes(saved.toPath());
        assertTrue(bytes.length > 2);
        assertEquals("a JPEG opens with its start-of-image marker", (byte) 0xFF, bytes[0]);
        assertEquals((byte) 0xD8, bytes[1]);
        Bitmap image = BitmapFactory.decodeFile(saved.getAbsolutePath());
        assertNotNull(image);
        try {
            assertEquals(width, image.getWidth());
            assertEquals(height, image.getHeight());
            int pixel = image.getPixel(width / 2, height / 2);
            assertEquals(Color.red(FRAME_COLOR), Color.red(pixel), 8d);
            assertEquals(Color.green(FRAME_COLOR), Color.green(pixel), 8d);
            assertEquals(Color.blue(FRAME_COLOR), Color.blue(pixel), 8d);
        } finally {
            image.recycle();
        }
    }

    @Test public void aTextureViewFrameIsSavedAsAJpegAtTheVideosOwnSize() throws Exception {
        ShowingTexture video = new ShowingTexture(activity);
        show(video);
        Post post = new Post("7350000000000000001", 1080, 1920);

        FrameSaver.save(activity, post, 12_345);
        assertEquals("Saving the frame", ShadowToast.getTextOfLatestToast());
        awaitSaves();

        assertEquals("read once, at the press", 1, video.asked.size());
        assertArrayEquals("the video's own size, not the 400x700 it's drawn at",
                new int[]{1080, 1920}, video.asked.get(0));
        String name = DownloadFilenameFormatter.formatFrameName(post, 12_345);
        assertTrue(name, name.startsWith("dancer_") && name.contains("7350000000000000001")
                && name.endsWith("_frame_0m12s.jpg"));
        assertSavedJpeg(new File(directory, name), 1080, 1920);
        assertTrue("the frame's bitmap is let go once the save is over", video.handedOut.get(0).isRecycled());
        assertEquals("Frame saved to " + DIRECTORY, ShadowToast.getTextOfLatestToast());
    }

    @Test public void aSurfaceViewFrameIsCopiedAtTheVideosOwnSizeAndSaved() throws Exception {
        SurfaceView video = new SurfaceView(activity);
        show(video);
        List<int[]> asked = new ArrayList<>();
        FrameSaver.surfaceReader = (view, into, copied) -> {
            assertSame("the video's own surface is read", video, view);
            asked.add(new int[]{into.getWidth(), into.getHeight()});
            into.eraseColor(FRAME_COLOR);
            copied.copied(true);
        };
        Post post = new Post("7350000000000000002", 720, 1280);

        FrameSaver.save(activity, post, 65_400);
        awaitSaves();

        assertEquals(1, asked.size());
        assertArrayEquals(new int[]{720, 1280}, asked.get(0));
        String name = DownloadFilenameFormatter.formatFrameName(post, 65_400);
        assertTrue(name, name.endsWith("_frame_1m05s.jpg"));
        assertSavedJpeg(new File(directory, name), 720, 1280);
        assertEquals("Frame saved to " + DIRECTORY, ShadowToast.getTextOfLatestToast());
    }

    @Test public void aSurfaceThatCantBeReadSavesNothingAndSaysSo() throws Exception {
        show(new SurfaceView(activity));
        List<Bitmap> handed = new ArrayList<>();
        FrameSaver.surfaceReader = (view, into, copied) -> {
            handed.add(into);
            copied.copied(false);
        };

        FrameSaver.save(activity, new Post("7350000000000000003", 720, 1280), 0);

        assertNothingSaved();
        assertEquals("The frame couldn't be read. Try again.", ShadowToast.getTextOfLatestToast());
        assertEquals(1, handed.size());
        assertTrue("the unused bitmap is let go at once", handed.get(0).isRecycled());

        // PixelCopy throws for a surface that's already gone, a swipe at the same moment.
        handed.clear();
        ShadowToast.reset();
        FrameSaver.surfaceReader = (view, into, copied) -> {
            handed.add(into);
            throw new IllegalArgumentException("Surface isn't valid");
        };
        FrameSaver.save(activity, new Post("7350000000000000003", 720, 1280), 0);
        assertNothingSaved();
        assertEquals("The frame couldn't be read. Try again.", ShadowToast.getTextOfLatestToast());
        assertTrue(handed.get(0).isRecycled());
    }

    @Test public void aTextureWithNoFrameYetSavesNothingAndSaysSo() throws Exception {
        // Before a video's first frame, or once its surface has gone, there's nothing to hand back.
        show(new TextureView(activity) {
            @Override public Bitmap getBitmap(int width, int height) {
                return null;
            }
        });

        FrameSaver.save(activity, new Post("7350000000000000004", 1080, 1920), 0);

        assertNothingSaved();
        assertEquals("The frame couldn't be read. Try again.", ShadowToast.getTextOfLatestToast());
    }

    @Test public void noVideoOnScreenSavesNothingAndSaysSo() throws Exception {
        FrameSaver.surfaceReader = (view, into, copied) -> fail("there's no surface to read");
        // A photo post: the caption and the picture, no surface.
        show(new TextView(activity));
        // A video view TikTok has hidden isn't the video on screen either.
        ShowingTexture hidden = new ShowingTexture(activity);
        hidden.setVisibility(View.GONE);
        content.addView(hidden, new FrameLayout.LayoutParams(400, 700));

        FrameSaver.save(activity, new Post("7350000000000000005", 1080, 1920), 0);

        assertNothingSaved();
        assertTrue(hidden.asked.isEmpty());
        assertEquals("There's no video on screen to save a frame from", ShadowToast.getTextOfLatestToast());

        ShadowToast.reset();
        FrameSaver.save(null, null, -1);
        assertNothingSaved();
        assertEquals("with no screen at all", "There's no video on screen to save a frame from",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void withoutStorageBeforeAndroid10NothingIsReadOrSaved() throws Exception {
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .denyPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        ShowingTexture video = new ShowingTexture(activity);
        show(video);

        FrameSaver.save(activity, new Post("7350000000000000006", 1080, 1920), 0);

        assertNothingSaved();
        assertTrue("asked before the frame is read", video.asked.isEmpty());
        assertEquals("Allow storage for TikTok in Android settings to save frames",
                ShadowToast.getTextOfLatestToast());
    }

    /** PixelCopy arrived in Android 7, so below it a SurfaceView isn't read at all. */
    @Test @Config(sdk = 23)
    public void belowAndroid7ASurfaceViewIsNotRead() throws Exception {
        show(new SurfaceView(activity));
        FrameSaver.surfaceReader = (view, into, copied) -> fail("PixelCopy doesn't exist below Android 7");

        FrameSaver.save(activity, new Post("7350000000000000007", 1080, 1920), 0);

        assertNothingSaved();
        assertEquals("Saving a frame from this video needs Android 7 or newer",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void theFrameKeepsTheVideosShapeWithinTheLimits() {
        View drawn = new View(activity);
        drawn.layout(0, 0, 400, 700);
        assertArrayEquals(new int[]{1080, 1920}, FrameSaver.frameSize(new Post("1", 1080, 1920), drawn));
        assertArrayEquals("no post: the size it's drawn at",
                new int[]{400, 700}, FrameSaver.frameSize(null, drawn));
        assertArrayEquals("a post with no size of its own",
                new int[]{400, 700}, FrameSaver.frameSize(new Post("1", 0, 0), drawn));

        int[] eightK = FrameSaver.frameSize(new Post("1", 4320, 7680), drawn);
        assertArrayEquals("halved, the shape kept", new int[]{2160, 3840}, eightK);
        assertTrue((long) eightK[0] * eightK[1] <= FrameSaver.MAX_PIXELS);

        int[] strip = FrameSaver.frameSize(new Post("1", 1, 1_000_000), drawn);
        assertTrue(strip[0] >= 1 && strip[1] <= FrameSaver.MAX_SIDE);

        assertNull("nothing has a size", FrameSaver.frameSize(null, new View(activity)));
    }

    @Test public void aSurfaceOfAnotherShapeIsReadAtItsOwnSoNothingStretches() {
        View drawn = new View(activity);
        drawn.layout(0, 0, 400, 700);
        assertArrayEquals("a buffer the video's shape keeps the post's size",
                new int[]{1080, 1920}, FrameSaver.frameSize(new Post("1", 1080, 1920), drawn, new int[]{720, 1280}));
        assertArrayEquals("an odd row is still the same shape",
                new int[]{1080, 1920}, FrameSaver.frameSize(new Post("1", 1080, 1920), drawn, new int[]{720, 1281}));
        assertArrayEquals("a screen-shaped buffer is read as it is",
                new int[]{1080, 2340}, FrameSaver.frameSize(new Post("1", 1080, 1920), drawn, new int[]{1080, 2340}));
        assertArrayEquals("no post size: the buffer, not the view",
                new int[]{1080, 2340}, FrameSaver.frameSize(null, drawn, new int[]{1080, 2340}));
        assertArrayEquals("only a buffer",
                new int[]{1080, 2340}, FrameSaver.frameSize(null, new View(activity), new int[]{1080, 2340}));
        assertArrayEquals("an empty buffer changes nothing",
                new int[]{1080, 1920}, FrameSaver.frameSize(new Post("1", 1080, 1920), drawn, new int[]{0, 0}));
        int[] eightK = FrameSaver.frameSize(null, drawn, new int[]{4320, 9360});
        assertTrue("the limits still hold", (long) eightK[0] * eightK[1] <= FrameSaver.MAX_PIXELS);
    }

    @Test public void aFrameIsNamedAfterItsVideoAndWhereInItItWas() {
        assertEquals("0m00s", DownloadFilenameFormatter.frameTime(0));
        assertEquals("1m05s", DownloadFilenameFormatter.frameTime(65_400));
        assertEquals("61m01s", DownloadFilenameFormatter.frameTime(3_661_000));

        Post post = new Post("7350000000000000008", 1080, 1920);
        String video = DownloadFilenameFormatter.formatSelectedVideoName(post);
        String stem = video.substring(0, video.lastIndexOf('.'));
        assertEquals(stem + "_frame_1m05s.jpg", DownloadFilenameFormatter.formatFrameName(post, 65_400));
        assertEquals("no position: the video's name alone",
                stem + "_frame.jpg", DownloadFilenameFormatter.formatFrameName(post, -1));
        assertTrue("no post: named for when it was saved",
                DownloadFilenameFormatter.formatFrameName(null, -1)
                        .matches("frame_\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2}\\.jpg"));
    }

    /** A TextureView with a frame to hand back, which Robolectric's own never has. */
    private static final class ShowingTexture extends TextureView {
        final List<int[]> asked = new ArrayList<>();
        final List<Bitmap> handedOut = new ArrayList<>();

        ShowingTexture(Context context) {
            super(context);
        }

        @Override public Bitmap getBitmap(int width, int height) {
            asked.add(new int[]{width, height});
            Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            frame.eraseColor(FRAME_COLOR);
            handedOut.add(frame);
            return frame;
        }
    }

    /** The parts of TikTok's Aweme the save reads: its id, author, date and video size. */
    public static final class Post {
        private final String aid;
        private final Size video;
        private final Author author = new Author();

        Post(String aid, int width, int height) {
            this.aid = aid;
            this.video = new Size(width, height);
        }

        public String getAid() {
            return aid;
        }

        public Author getAuthor() {
            return author;
        }

        public long getCreateTime() {
            return 1_760_000_000L;
        }

        public Size getVideo() {
            return video;
        }
    }

    public static final class Size {
        private final int width;
        private final int height;

        Size(int width, int height) {
            this.width = width;
            this.height = height;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }
    }

    public static final class Author {
        public String getUniqueId() {
            return "dancer";
        }
    }
}
