package app.morphe.extension.tiktok.download;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/**
 * TikTok lists a post's photos as HEIF and WebP copies, and the saved .heif is what #105 couldn't
 * open. Robolectric's Skia reads no HEIF, so a PNG stands in for a HEIF TikTok sent: the decision
 * is made from the extension the header gave, and the decoding, the JPEG and the swap into the
 * saved file are what's under test. The WebP is a real one.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 30)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PhotoToJpegTest {
    @Rule public final TemporaryFolder folder = new TemporaryFolder();
    private final Context context = RuntimeEnvironment.getApplication();

    @After
    public void restoreTheExtractor() {
        PhotoToJpeg.primaryImageReader = PhotoToJpeg::readPrimaryImage;
    }

    @Test
    public void aHeifPhotoIsSavedAgainAsAJpegOfTheSameSize() throws IOException {
        for (String extension : List.of("heif", "heic")) {
            File photo = png(48, 32, 0xFF3366CC);
            assertEquals(extension + " comes back a JPEG", "jpg", PhotoToJpeg.convert(context, photo, extension));
            byte[] saved = Files.readAllBytes(photo.toPath());
            assertEquals("JPEG start of image", 0xFF, saved[0] & 0xFF);
            assertEquals("JPEG start of image", 0xD8, saved[1] & 0xFF);
            Bitmap decoded = BitmapFactory.decodeByteArray(saved, 0, saved.length);
            assertNotNull("the JPEG reads back", decoded);
            assertEquals(48, decoded.getWidth());
            assertEquals(32, decoded.getHeight());
        }
        assertNoConversionFilesLeft();
    }

    /** The copy fetched in place of a HEIF a Samsung can't decode (S22, 47.0.3). */
    @Test
    public void aWebpPhotoIsSavedAgainAsAJpegOfTheSameSize() throws IOException {
        File photo = encoded(48, 32, 0xFF3366CC, Bitmap.CompressFormat.WEBP_LOSSY);
        byte[] sent = Files.readAllBytes(photo.toPath());
        assertEquals("RIFF", new String(sent, 0, 4, StandardCharsets.US_ASCII));
        assertEquals("jpg", PhotoToJpeg.convert(context, photo, "webp"));
        byte[] saved = Files.readAllBytes(photo.toPath());
        assertEquals("JPEG start of image", 0xFF, saved[0] & 0xFF);
        assertEquals("JPEG start of image", 0xD8, saved[1] & 0xFF);
        Bitmap decoded = BitmapFactory.decodeByteArray(saved, 0, saved.length);
        assertNotNull("the JPEG reads back", decoded);
        assertEquals(48, decoded.getWidth());
        assertEquals(32, decoded.getHeight());
        assertNoConversionFilesLeft();
    }

    @Test
    public void everythingElseKeepsWhatTikTokSent() throws IOException {
        for (String extension : List.of("jpg", "png", "gif", "avif")) {
            File photo = png(8, 8, 0xFF00AA00);
            byte[] before = Files.readAllBytes(photo.toPath());
            assertEquals(extension, PhotoToJpeg.convert(context, photo, extension));
            assertArrayEquals(extension + " untouched", before, Files.readAllBytes(photo.toPath()));
        }
    }

    @Test
    public void aPhotoThatWontDecodeKeepsTheHeif() throws IOException {
        File photo = folder.newFile("broken.tmp");
        byte[] noise = new byte[512];
        new Random(105).nextBytes(noise);
        Files.write(photo.toPath(), noise);
        assertEquals("heif", PhotoToJpeg.convert(context, photo, "heif"));
        assertArrayEquals("the HEIF TikTok sent is still there", noise, Files.readAllBytes(photo.toPath()));
        assertNoConversionFilesLeft();
    }

    /** Samsung's decoder said "invalid input" to a HEIF its media extractor read (S22). */
    @Test
    public void aHeifTheDecoderRefusesIsReadThroughTheMediaExtractor() throws IOException {
        File photo = noise("refused.tmp");
        java.util.List<File> asked = new java.util.ArrayList<>();
        PhotoToJpeg.primaryImageReader = file -> {
            asked.add(file);
            Bitmap image = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888);
            image.eraseColor(0xFF3366CC);
            return image;
        };
        assertEquals("jpg", PhotoToJpeg.convert(context, photo, "heif"));
        assertEquals(List.of(photo), asked);
        byte[] saved = Files.readAllBytes(photo.toPath());
        assertEquals("JPEG start of image", 0xFF, saved[0] & 0xFF);
        assertEquals("JPEG start of image", 0xD8, saved[1] & 0xFF);
        Bitmap decoded = BitmapFactory.decodeByteArray(saved, 0, saved.length);
        assertNotNull("the JPEG reads back", decoded);
        assertEquals(48, decoded.getWidth());
        assertEquals(32, decoded.getHeight());
        assertNoConversionFilesLeft();
    }

    @Test
    public void aHeifNeitherPathReadsKeepsWhatTikTokSent() throws IOException {
        File photo = noise("unreadable.tmp");
        byte[] sent = Files.readAllBytes(photo.toPath());
        PhotoToJpeg.primaryImageReader = file -> null;
        assertEquals("heic", PhotoToJpeg.convert(context, photo, "heic"));
        assertArrayEquals(sent, Files.readAllBytes(photo.toPath()));
        assertNoConversionFilesLeft();
    }

    @Test
    public void aWebpTheDecoderRefusesNeverReachesTheExtractor() throws IOException {
        File photo = noise("refused-webp.tmp");
        PhotoToJpeg.primaryImageReader = file -> {
            throw new AssertionError("a WebP went to the HEIF extractor");
        };
        assertEquals("webp", PhotoToJpeg.convert(context, photo, "webp"));
        assertNoConversionFilesLeft();
    }

    /** The real extractor on a file it can't read answers null rather than throwing. */
    @Test
    public void theRealExtractorTurnsDownAFileItCantRead() throws IOException {
        assertEquals(null, PhotoToJpeg.readPrimaryImage(noise("extractor.tmp")));
    }

    @Test public void onlyOneStillImageGoesThroughTheExtractor() {
        assertTrue(PhotoToJpeg.oneStillImage("1080", "1440", "1", null));
        assertTrue(PhotoToJpeg.oneStillImage("1080", "1440", null, "no"));
        // An animated HEIF: one image item, its frames in a video track.
        assertFalse(PhotoToJpeg.oneStillImage("1080", "1440", "1", "yes"));
        assertFalse(PhotoToJpeg.oneStillImage("1080", "1440", "3", null));
        assertFalse(PhotoToJpeg.oneStillImage("10000", "10000", "1", null));
        assertFalse(PhotoToJpeg.oneStillImage(null, "1440", "1", null));
    }

    @Test
    public void aSeeThroughPhotoStaysHeifBecauseJpegWouldBlackenIt() throws IOException {
        File photo = png(8, 8, 0x00000000);
        byte[] before = Files.readAllBytes(photo.toPath());
        assertEquals("heif", PhotoToJpeg.convert(context, photo, "heif"));
        assertArrayEquals(before, Files.readAllBytes(photo.toPath()));
        assertNoConversionFilesLeft();
    }

    @Test
    @Config(sdk = 27)
    public void beforeAndroid9TheHeifStays() throws IOException {
        File photo = png(8, 8, 0xFF3366CC);
        byte[] before = Files.readAllBytes(photo.toPath());
        assertEquals("heif", PhotoToJpeg.convert(context, photo, "heif"));
        assertArrayEquals(before, Files.readAllBytes(photo.toPath()));
    }

    private File noise(String name) throws IOException {
        File photo = folder.newFile(name);
        byte[] bytes = new byte[512];
        new Random(105).nextBytes(bytes);
        Files.write(photo.toPath(), bytes);
        return photo;
    }

    private File png(int width, int height, int color) throws IOException {
        return encoded(width, height, color, Bitmap.CompressFormat.PNG);
    }

    private File encoded(int width, int height, int color, Bitmap.CompressFormat format) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        // Android reads every HEIF as opaque, and the PNG standing in says so only when its
        // bitmap does; otherwise it's written with an alpha channel and decodes see-through.
        bitmap.setHasAlpha(Color.alpha(color) != 0xFF);
        File file = folder.newFile();
        try (FileOutputStream output = new FileOutputStream(file)) {
            if (!bitmap.compress(format, 100, output)) throw new IOException(format + " encode failed");
        } finally {
            bitmap.recycle();
        }
        return file;
    }

    private void assertNoConversionFilesLeft() {
        File[] left = new File(context.getCacheDir(), MediaCache.DIRECTORY_NAME)
                .listFiles((directory, name) -> name.startsWith("photo-jpeg-"));
        assertEquals("conversion files left behind", 0, left == null ? 0 : left.length);
    }
}
