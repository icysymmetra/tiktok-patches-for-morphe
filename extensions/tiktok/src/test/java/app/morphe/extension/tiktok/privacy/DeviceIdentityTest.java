/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.provider.Settings.Secure;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.SecureRandom;
import java.util.UUID;

/**
 * A new device identity: the wipe that runs at the start after it's armed, what it keeps, and
 * the Android ID and advertising id TikTok gets from it afterwards.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class DeviceIdentityTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    private static final String REAL_AD_ID = "a1b2c3d4-0000-1111-2222-aabbccddeeff";
    private static final String IDENTITY_AD_ID = "0f1e2d3c-4b5a-4968-8776-655443322110";
    private static final String IDENTITY_ANDROID_ID = "fedcba9876543210";

    private Context context;
    private RecordingProcesses processes;

    /** Ends nothing, and notes what was saved and marked when the wipe ended the other processes. */
    private static final class RecordingProcesses implements DeviceIdentity.Processes {
        int endedOthers;
        int endedSelf;
        String androidIdWhenEnding;
        boolean markedWhenEnding;

        @Override public void endOthers(Context base) {
            endedOthers++;
            androidIdWhenEnding = base.getSharedPreferences(DeviceIdentity.PREFERENCES, Context.MODE_PRIVATE)
                    .getString(DeviceIdentity.ANDROID_ID_KEY, null);
            markedWhenEnding = new File(base.getApplicationInfo().dataDir, DeviceIdentity.WIPING_MARKER).exists();
        }

        @Override public void endSelf() {
            endedSelf++;
        }
    }

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        DeviceIdentity.resetForTests();
        processes = new RecordingProcesses();
        DeviceIdentity.processes = processes;
        saved().edit().clear().commit();
    }

    @After public void tearDown() {
        saved().edit().clear().commit();
        DeviceIdentity.resetForTests();
        Settings.BLOCK_ADVERTISING_ID.save(Settings.BLOCK_ADVERTISING_ID.defaultValue);
    }

    private SharedPreferences saved() {
        return context.getSharedPreferences(DeviceIdentity.PREFERENCES, Context.MODE_PRIVATE);
    }

    private static File file(File parent, String path) throws IOException {
        File file = new File(parent, path);
        assertTrue(file.getParentFile().isDirectory() || file.getParentFile().mkdirs());
        Files.write(file.toPath(), new byte[]{1});
        return file;
    }

    /** The app context with its data, device-protected data and shared storage in test folders. */
    private Context dataIn(File data, File deviceData, File external) {
        return new ContextWrapper(context) {
            @Override public ApplicationInfo getApplicationInfo() {
                ApplicationInfo info = new ApplicationInfo(super.getApplicationInfo());
                info.dataDir = data.getPath();
                return info;
            }

            @Override public Context createDeviceProtectedStorageContext() {
                return new ContextWrapper(context) {
                    @Override public File getDataDir() {
                        return deviceData;
                    }
                };
            }

            @Override public File getExternalFilesDir(String type) {
                return new File(external, "files");
            }
        };
    }

    @Test public void theWipeKeepsHushfeedsFilesAndTakesTikToksOwn() throws IOException {
        File data = temporary.newFolder("data");
        File[] kept = {
                file(data, "shared_prefs/morphe_prefs.xml"),
                file(data, "shared_prefs/morphe_feature_gate_lab.xml"),
                file(data, "shared_prefs/hushfeed_device_identity.xml"),
                file(data, "shared_prefs/hushfeed-calm-feed-preset.xml"),
                file(data, "files/hushfeed-save-records.json"),
                file(data, "files/hushfeed_fonts/imported.ttf"),
                file(data, "files/metra-settings-undo.json"),
                file(data, "files/feature-gate-lab-undo.json"),
                file(data, "files/morphe_java_crash_report_v1.txt"),
                file(data, "databases/seen_videos.db"),
                file(data, "databases/seen_videos.db-journal"),
                file(data, "databases/hushfeed-saved-videos.db"),
                file(data, "cache/hushfeed-media/clip.mp4"),
                file(data, "no_backup/hushfeed-media/pending-uris.tsv"),
                file(data, "lib/libtiktok.so"),
                file(data, "code_cache/compiled.dex"),
                file(data, DeviceIdentity.WIPING_MARKER),
        };
        File[] taken = {
                file(data, "shared_prefs/applog_stats.xml"),
                file(data, "files/keva/repo/device_id.keva"),
                file(data, "databases/ss_app_log.db"),
                file(data, "cache/okhttp/journal"),
                file(data, "no_backup/com.google.android.gms.appid-no-backup"),
                file(data, "app_webview/Default/Cookies"),
                file(data, "device_register.txt"),
        };

        int removed = DeviceIdentity.wipe(data);

        for (File file : kept) assertTrue("the wipe took Metra's " + file, file.isFile());
        for (File file : taken) assertFalse("the wipe left TikTok's " + file, file.exists());
        assertFalse("TikTok's directories go whole", new File(data, "files/keva").exists());
        assertFalse(new File(data, "app_webview").exists());
        assertTrue("a shared directory itself stays", new File(data, "databases").isDirectory());
        // The seven files, the keva and keva/repo directories, okhttp, app_webview and its Default.
        assertEquals(12, removed);
    }

    @Test public void theWipeRemovesALinkWithoutFollowingIt() throws IOException {
        File data = temporary.newFolder("data");
        File elsewhere = temporary.newFolder("elsewhere");
        File outside = file(elsewhere, "photo.jpg");
        File link = new File(data, "app_link");
        File sharedLink = new File(data, "files");
        try {
            Files.createSymbolicLink(link.toPath(), elsewhere.toPath());
            Files.createSymbolicLink(sharedLink.toPath(), elsewhere.toPath());
        } catch (IOException | UnsupportedOperationException noLinks) {
            Assume.assumeNoException("this host can't make symbolic links", noLinks);
        }
        assertTrue(DeviceIdentity.isLink(link));
        assertFalse(DeviceIdentity.isLink(elsewhere));

        DeviceIdentity.wipe(data);

        assertFalse("the link went", Files.exists(link.toPath(), LinkOption.NOFOLLOW_LINKS));
        assertFalse("a link in a shared directory's place went too",
                Files.exists(sharedLink.toPath(), LinkOption.NOFOLLOW_LINKS));
        assertTrue("what the links pointed at is untouched", outside.isFile());
    }

    @Test public void aNewAndroidIdIsSixteenLowercaseHexDigitsAndNeverZero() {
        SecureRandom sequence = new SecureRandom() {
            private final long[] values = {0L, 0x1fL, -1L};
            private int next;

            @Override public long nextLong() {
                return values[next++];
            }
        };
        assertEquals("a zero is drawn again, and short values are padded",
                "000000000000001f", DeviceIdentity.newAndroidId(sequence));
        assertEquals("ffffffffffffffff", DeviceIdentity.newAndroidId(sequence));
        String drawn = DeviceIdentity.newAndroidId(new SecureRandom());
        assertTrue(drawn, drawn.matches("[0-9a-f]{16}"));
    }

    @Test public void anArmedIdentityWipesOnlyInTheMainProcessAndOnlyOnce() throws IOException {
        File data = temporary.newFolder("data");
        File deviceData = temporary.newFolder("device-data");
        File external = temporary.newFolder("external");
        File tiktok = file(data, "shared_prefs/applog_stats.xml");
        File deviceTikTok = file(deviceData, "files/push_device_id");
        File externalTikTok = file(external, "files/logs/device.log");
        File hushfeed = file(data, "shared_prefs/morphe_prefs.xml");
        Context app = dataIn(data, deviceData, external);
        assertTrue(DeviceIdentity.setArmed(app, true));

        DeviceIdentity.processName = () -> context.getPackageName() + ":push";
        DeviceIdentity.start(app);
        assertTrue("another process never wipes", tiktok.isFile());
        assertTrue("and leaves the wipe armed for the main one", DeviceIdentity.isArmed(app));
        assertEquals("with no wipe running it carries on", 0, processes.endedSelf);
        assertNull("nothing armed is in force yet", DeviceIdentity.androidId());
        assertNull(DeviceIdentity.advertisingId());

        DeviceIdentity.processName = context::getPackageName;
        DeviceIdentity.start(app);
        assertEquals("the main process ends the others once", 1, processes.endedOthers);
        assertNotNull("with the new ids already saved", processes.androidIdWhenEnding);
        assertEquals(processes.androidIdWhenEnding, DeviceIdentity.androidId());
        assertTrue("and the wipe marked", processes.markedWhenEnding);
        assertFalse("the marker goes when the wipe is done",
                new File(data, DeviceIdentity.WIPING_MARKER).exists());
        assertFalse(tiktok.exists());
        assertFalse("the device-protected copy goes too", deviceTikTok.exists());
        assertFalse("and TikTok's files on shared storage", externalTikTok.exists());
        assertTrue(hushfeed.isFile());
        assertFalse("one wipe per arming", DeviceIdentity.isArmed(app));
        String androidId = DeviceIdentity.androidId();
        String advertisingId = DeviceIdentity.advertisingId();
        assertNotNull(androidId);
        assertTrue(androidId, androidId.matches("[0-9a-f]{16}"));
        assertNotNull(advertisingId);
        assertEquals(advertisingId, UUID.fromString(advertisingId).toString());

        File written = file(data, "shared_prefs/applog_stats.xml");
        DeviceIdentity.start(app);
        assertTrue("an identity in force wipes nothing at later starts", written.isFile());
        assertEquals("and stays the same", androidId, DeviceIdentity.androidId());
        assertEquals(advertisingId, DeviceIdentity.advertisingId());
        assertEquals(1, processes.endedOthers);
    }

    @Test public void aProcessThatStartsDuringTheWipeEndsBeforeTikTokRuns() throws IOException {
        File data = temporary.newFolder("data");
        Context app = dataIn(data, temporary.newFolder("device-data"), temporary.newFolder("external"));
        file(data, DeviceIdentity.WIPING_MARKER);
        saved().edit()
                .putString(DeviceIdentity.ANDROID_ID_KEY, IDENTITY_ANDROID_ID)
                .putString(DeviceIdentity.ADVERTISING_ID_KEY, IDENTITY_AD_ID)
                .commit();
        DeviceIdentity.processName = () -> context.getPackageName() + ":push";

        assertTrue(DeviceIdentity.setArmed(app, true));
        DeviceIdentity.start(app);
        assertEquals("a wipe is running, so it ends", 1, processes.endedSelf);
        assertEquals("and ends nothing else", 0, processes.endedOthers);
        assertNull("before taking up any identity", DeviceIdentity.androidId());

        assertTrue(DeviceIdentity.setArmed(app, false));
        DeviceIdentity.start(app);
        assertEquals("a marker left behind once the wipe is done changes nothing", 1, processes.endedSelf);
        assertEquals(IDENTITY_ANDROID_ID, DeviceIdentity.androidId());
    }

    @Test public void theAndroidIdAndAdvertisingIdComeFromTheIdentityWhileThereIsOne() {
        ContentResolver resolver = context.getContentResolver();
        Secure.putString(resolver, Secure.ANDROID_ID, "0123456789abcdef");
        Secure.putString(resolver, "bluetooth_name", "Pixel");
        android.provider.Settings.System.putString(resolver, "screen_off_timeout", "60000");
        assertEquals("without an identity the phone's own comes through", "0123456789abcdef",
                DevicePrivacyGuard.interceptSecureString(resolver, Secure.ANDROID_ID));
        assertEquals(REAL_AD_ID, DevicePrivacyGuard.interceptAdvertisingIdRead(REAL_AD_ID));
        assertEquals(REAL_AD_ID, DevicePrivacyGuard.interceptAdvertisingId(new PrivacySwitchesTest.FakeAdInfo()));

        saved().edit()
                .putString(DeviceIdentity.ANDROID_ID_KEY, IDENTITY_ANDROID_ID)
                .putString(DeviceIdentity.ADVERTISING_ID_KEY, IDENTITY_AD_ID)
                .commit();
        DeviceIdentity.processName = () -> context.getPackageName() + ":push";
        DeviceIdentity.start(context);

        assertEquals("every process answers with the identity", IDENTITY_ANDROID_ID,
                DevicePrivacyGuard.interceptSecureString(resolver, Secure.ANDROID_ID));
        assertEquals(IDENTITY_ANDROID_ID, DevicePrivacyGuard.interceptSystemString(resolver, Secure.ANDROID_ID));
        assertEquals("other names still come from the system", "Pixel",
                DevicePrivacyGuard.interceptSecureString(resolver, "bluetooth_name"));
        assertEquals("60000", DevicePrivacyGuard.interceptSystemString(resolver, "screen_off_timeout"));
        assertEquals(IDENTITY_AD_ID, DevicePrivacyGuard.interceptAdvertisingIdRead(REAL_AD_ID));
        assertEquals(IDENTITY_AD_ID, DevicePrivacyGuard.interceptAdvertisingId(new PrivacySwitchesTest.FakeAdInfo()));

        Settings.BLOCK_ADVERTISING_ID.save(true);
        assertEquals("Block the advertising ID still wins", "00000000-0000-0000-0000-000000000000",
                DevicePrivacyGuard.interceptAdvertisingIdRead(REAL_AD_ID));
        Settings.BLOCK_ADVERTISING_ID.save(false);

        PausedProcess.set(true);
        try {
            assertEquals("a pause doesn't hand the phone's own ids over", IDENTITY_ANDROID_ID,
                    DevicePrivacyGuard.interceptSecureString(resolver, Secure.ANDROID_ID));
            assertEquals(IDENTITY_AD_ID, DevicePrivacyGuard.interceptAdvertisingIdRead(REAL_AD_ID));
        } finally {
            PausedProcess.set(false);
        }
    }

    @Test public void aDamagedSavedIdentityIsIgnored() {
        saved().edit()
                .putString(DeviceIdentity.ANDROID_ID_KEY, "not hex at all!!")
                .putString(DeviceIdentity.ADVERTISING_ID_KEY, "1234")
                .commit();
        DeviceIdentity.processName = () -> null;
        DeviceIdentity.start(context);
        assertNull(DeviceIdentity.androidId());
        assertNull(DeviceIdentity.advertisingId());
    }
}
