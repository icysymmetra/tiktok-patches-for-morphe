package app.morphe.extension.tiktok.download;

import static org.junit.Assert.*;

import android.Manifest;
import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Environment;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Files;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;

/** Runs the current-owner gesture through the real media job, fetch and gallery-file writer. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class ProfileAvatarSizeTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private static final String DIRECTORY = "DCIM/ProfileAvatarSizeTest";
    private final Map<String, byte[]> pictures = new ConcurrentHashMap<>();
    private final List<String> requested = new CopyOnWriteArrayList<>();
    private ActivityController<Activity> activity;
    private View avatar;
    private File directory;
    private boolean oldEnabled, oldStatus;
    private String oldPath;
    private Object oldProfile;
    private Hashtable<String, URLStreamHandler> handlers;
    private URLStreamHandler oldHttp, oldHttps;
    private int nativeActions;

    @Before public void setUp() throws Exception {
        oldEnabled = Settings.SAVE_PROFILE_PICTURE.get();
        oldStatus = SettingsStatus.advancedDownloadsEnabled;
        oldPath = Settings.DOWNLOAD_PHOTO_PATH.get();
        oldProfile = ProfileAvatarSaver.recordedProfileUser();
        Settings.SAVE_PROFILE_PICTURE.save(true);
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.DOWNLOAD_PHOTO_PATH.save(DIRECTORY);
        Shadows.shadowOf(RuntimeEnvironment.getApplication())
                .grantPermissions(Manifest.permission.WRITE_EXTERNAL_STORAGE);
        assertEquals(0, MediaJobScheduler.runningJobs());
        assertEquals(0, MediaJobScheduler.queuedJobs());

        // Replace only the connection boundary. These handlers return local image bytes;
        // URL selection, HTTPS filtering, scheduling and publication remain production code.
        new URL("http://8.8.8.8/");
        new URL("https://8.8.8.8/");
        handlers = ReflectionHelpers.getStaticField(URL.class, "handlers");
        URLStreamHandler transport = new URLStreamHandler() {
            // The pinned media connection opens with Proxy.NO_PROXY; the base overload throws.
            @Override protected URLConnection openConnection(URL url, java.net.Proxy proxy) throws java.io.IOException { return openConnection(url); }
            @Override protected URLConnection openConnection(URL url) throws IOException {
                requested.add(url.toString());
                byte[] body = pictures.get(url.getPath());
                if (body == null) throw new IOException("Unexpected fixture URL: " + url);
                return new FakeHttpsConnection(url) {
                    @Override public int getResponseCode() { return HTTP_OK; }
                    @Override public String getHeaderField(String name) {
                        return "Content-Length".equals(name) ? String.valueOf(body.length) : null;
                    }
                    @Override public InputStream getInputStream() { return new ByteArrayInputStream(body); }
                    @Override public void connect() { }
                    @Override public void disconnect() { }
                    @Override public boolean usingProxy() { return false; }
                };
            }
        };
        oldHttp = handlers.put("http", transport);
        oldHttps = handlers.put("https", transport);
        assertNotNull(oldHttp);
        assertNotNull(oldHttps);

        activity = Robolectric.buildActivity(Activity.class).setup();
        LinearLayout header = new LinearLayout(activity.get());
        TextView handle = new TextView(activity.get());
        handle.setText("@nasa");
        header.addView(handle);
        avatar = new View(activity.get());
        header.addView(avatar);
        activity.get().setContentView(header);
        directory = new File(Environment.getExternalStorageDirectory(), DIRECTORY);
        ProfileAvatarSaver.recordProfileResponse(new LegacyResponse(new User("previous-profile")));
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        try {
            awaitSave();
            if (activity != null) activity.close();
            if (directory != null && directory.isDirectory()) {
                File[] saved = directory.listFiles();
                assertNotNull(saved);
                for (File file : saved) assertTrue(file.delete());
                assertTrue(directory.delete());
            }
        } finally {
            if (handlers != null) {
                if (oldHttp != null) handlers.put("http", oldHttp);
                if (oldHttps != null) handlers.put("https", oldHttps);
            }
            Settings.SAVE_PROFILE_PICTURE.save(oldEnabled);
            SettingsStatus.advancedDownloadsEnabled = oldStatus;
            Settings.DOWNLOAD_PHOTO_PATH.save(oldPath);
            ReflectionHelpers.setStaticField(ProfileAvatarSaver.class, "profileUser", oldProfile);
        }
    }

    @Test public void currentOwnerHoldSavesTheLargestUsableSourceAndKeepsSmallerTlsFallbacks() throws Exception {
        // The S22 User carries 300, Larger=1080 and Medium=720 together. PNG fixtures let
        // the assertion read the published dimensions without fetching a real account's CDN.
        User user = new User("nasa");
        String larger = picture(1080);
        String medium = picture(720);
        String small = picture(300);
        String tiny = picture(168);
        String thumb = picture(100);
        user.larger = new Address(larger.replace("https://", "http://"), larger);
        user.medium = new Address(medium);
        user.small = new Address(small);
        user.tiny = new Address(tiny);
        user.thumb = new Address(thumb);
        ProfileOwner owner = new ProfileOwner(user);
        // Native slot 3 stores an OnClickListener and dispatches it when the avatar is held.
        View.OnClickListener registered = view -> {
            if (!ProfileAvatarSaver.onAvatarLongPress(owner, view)) nativeActions++;
        };
        avatar.setOnLongClickListener(view -> { registered.onClick(view); return true; });

        assertPublished(larger, 1080);
        assertEquals(1, owner.reads);
        assertEquals("previous-profile", ((User) ProfileAvatarSaver.recordedProfileUser()).getUniqueId());

        // Missing or non-TLS large sources must step down by image size, not field order.
        user.larger = null;
        assertPublished(medium, 720);
        user.larger = new Address(larger.replace("https://", "http://"));
        assertPublished(medium, 720);
        user.medium = new Address();
        assertPublished(small, 300);
        user.small = null;
        assertPublished(tiny, 168);
        user.tiny = new Address(tiny.replace("https://", "http://"));
        assertPublished(thumb, 100);

        user.thumb = new Address(thumb.replace("https://", "http://"));
        requested.clear();
        ShadowToast.reset();
        assertTrue(avatar.performLongClick());
        awaitSave();
        assertTrue("an HTTP-only avatar started a fetch", requested.isEmpty());
        assertEquals("This profile picture isn't available to save", ShadowToast.getTextOfLatestToast());
        assertEquals(7, owner.reads);
        assertEquals(0, nativeActions);
    }

    private void assertPublished(String expectedUrl, int side) throws Exception {
        requested.clear();
        assertTrue("the registered avatar hold did not run", avatar.performLongClick());
        awaitSave();
        File saved = new File(directory, "nasa_profile.png");
        assertTrue("the current owner's avatar was not published", saved.isFile());
        Bitmap image = BitmapFactory.decodeFile(saved.getAbsolutePath());
        assertNotNull(image);
        try {
            assertEquals("the saved avatar used a smaller display crop", side, image.getWidth());
            assertEquals(side, image.getHeight());
        } finally {
            image.recycle();
        }
        assertEquals(List.of(expectedUrl), requested);
        assertArrayEquals("saving must preserve the selected source without upscaling",
                pictures.get(new URL(expectedUrl).getPath()), Files.readAllBytes(saved.toPath()));
        assertEquals(0, nativeActions);
        assertTrue(saved.delete());
    }

    private String picture(int side) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Bitmap image = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888);
        try {
            assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, output));
        } finally {
            image.recycle();
        }
        String path = "/nasa/cropcenter:" + side + ":" + side + ".png";
        pictures.put(path, output.toByteArray());
        return "https://8.8.8.8" + path;
    }

    private static void awaitSave() throws InterruptedException {
        AtomicBoolean saving = ReflectionHelpers.getStaticField(ProfileAvatarSaver.class, "RUNNING");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (saving.get() || MediaJobScheduler.runningJobs() != 0 || MediaJobScheduler.queuedJobs() != 0) {
            assertTrue("the local avatar media job did not finish", System.nanoTime() < deadline);
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** The avatar component: its one profile getter is found by its return type (#160). */
    public static final class ProfileOwner {
        private final User user;
        int reads;
        ProfileOwner(User user) { this.user = user; }
        public com.ss.android.ugc.aweme.profile.model.User Qr() { reads++; return user; }
    }

    public static final class LegacyResponse {
        public final User user;
        LegacyResponse(User user) { this.user = user; }
    }

    public static final class User extends com.ss.android.ugc.aweme.profile.model.User {
        private final String handle;
        Address larger, medium, small, tiny, thumb;
        User(String handle) { this.handle = handle; }
        public String getUniqueId() { return handle; }
        public Address getAvatarLarger() { return larger; }
        public Address getAvatarMedium() { return medium; }
        public Address getAvatar300() { return small; }
        public Address getAvatar168() { return tiny; }
        public Address getAvatarThumb() { return thumb; }
    }

    public static final class Address {
        private final List<String> urls;
        Address(String... urls) { this.urls = List.of(urls); }
        public List<String> getUrlList() { return urls; }
    }
}
