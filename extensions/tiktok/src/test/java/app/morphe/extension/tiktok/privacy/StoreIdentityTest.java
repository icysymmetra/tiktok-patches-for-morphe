package app.morphe.extension.tiktok.privacy;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * What TikTok's own signature and installer reads get back once "Look like the store app" is on.
 * The extension answers TikTok's own package with TikTok's certificate and the Play Store, and
 * leaves every other read, and a paused build, untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class StoreIdentityTest {
    /** The digest Morphe Manager holds a picked APK to, so a bad edit to the constant is caught. */
    private static final String TIKTOK_SIGNER_SHA256 =
            "9041803e91bcb814b4b4399fb5c85a91640b755e5e8ba76813814bf4cf2ab5ba";
    /** PineDrama's (CN=TikTok Drama), as apksigner prints it for the APKMirror 47.1.4 bundle. */
    private static final String PINEDRAMA_SIGNER_SHA256 =
            "e97ca0c8835c5a9e4f7702a0382b103ede9c5d51e5194c8db54f4c8833f54e18";

    private Context context;
    private String ownPackage;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        ownPackage = context.getPackageName();
        Settings.STORE_IDENTITY.save(true);
        StoreIdentity.loggedInstaller = null;
        ShadowLog.clear();
    }

    @After public void tearDown() {
        Settings.STORE_IDENTITY.save(Settings.STORE_IDENTITY.defaultValue);
        Settings.STORE_IDENTITY_INSTALLER.save(Settings.STORE_IDENTITY_INSTALLER.defaultValue);
    }

    @Test public void theEmbeddedCertificateIsTikToks() throws Exception {
        assertEquals(TIKTOK_SIGNER_SHA256, sha256(StoreIdentity.signatures()[0].toByteArray()));
        assertEquals(TIKTOK_SIGNER_SHA256, sha256(der(StoreIdentity.certificateFor("com.zhiliaoapp.musically"))));
    }

    /** A patched PineDrama answers with PineDrama's own certificate, not TikTok's (#156). */
    @Test public void pineDramaGetsItsOwnCertificate() throws Exception {
        assertEquals(PINEDRAMA_SIGNER_SHA256, sha256(der(StoreIdentity.certificateFor("com.ss.android.ttmd.video"))));
    }

    private static byte[] der(String certificate) {
        return new Signature(android.util.Base64.decode(certificate, android.util.Base64.DEFAULT)).toByteArray();
    }

    private static String sha256(byte[] der) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(der);
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) hex.append(String.format(Locale.US, "%02x", b));
        return hex.toString();
    }

    @Test public void aSignatureReadOfTikTokGetsTikToksCertificate() {
        PackageManager pm = context.getPackageManager();
        PackageInfo info = StoreIdentity.packageInfo(pm, ownPackage, PackageManager.GET_SIGNATURES);
        assertNotNull("a read of its own certificate should be answered", info);
        assertArrayEquals(StoreIdentity.signatures()[0].toByteArray(), info.signatures[0].toByteArray());
    }

    @Test public void aReadWithoutSignatureFlagsIsLeftAlone() {
        PackageManager pm = context.getPackageManager();
        assertNull(StoreIdentity.packageInfo(pm, ownPackage, 0));
    }

    @Test public void aReadOfAnotherPackageIsLeftAlone() {
        PackageManager pm = context.getPackageManager();
        assertNull(StoreIdentity.packageInfo(pm, "com.example.other", PackageManager.GET_SIGNATURES));
    }

    @Test public void pausedOrOffItAnswersNothing() {
        PackageManager pm = context.getPackageManager();
        Settings.STORE_IDENTITY.save(false);
        assertNull(StoreIdentity.packageInfo(pm, ownPackage, PackageManager.GET_SIGNATURES));
    }

    @Test public void theInstallerOfTikTokReadsAsThePlayStore() {
        PackageManager pm = context.getPackageManager();
        pm.setInstallerPackageName(ownPackage, "com.morphe.manager");
        assertEquals(StoreIdentity.STORE, StoreIdentity.installerFor(pm, ownPackage));
    }

    @Test public void theInstallerReadsAsThePickedStore() {
        PackageManager pm = context.getPackageManager();
        pm.setInstallerPackageName(ownPackage, "com.morphe.manager");
        Settings.STORE_IDENTITY_INSTALLER.save("com.sec.android.app.samsungapps");
        assertEquals("com.sec.android.app.samsungapps", StoreIdentity.installerFor(pm, ownPackage));
    }

    /** A value off the list, from an edited backup, can't make TikTok read an arbitrary package. */
    @Test public void aStoreOffTheListReadsAsThePlayStore() {
        PackageManager pm = context.getPackageManager();
        pm.setInstallerPackageName(ownPackage, "com.morphe.manager");
        Settings.STORE_IDENTITY_INSTALLER.save("com.example.anything");
        assertEquals(StoreIdentity.STORE, StoreIdentity.installerFor(pm, ownPackage));
    }

    @Test public void theSettingsRowOffersEveryStoreThePlayStoreFirst() {
        String[] installers = StoreIdentity.installers();
        assertEquals(4, installers.length);
        assertEquals(StoreIdentity.STORE, installers[0]);
        assertEquals(Settings.STORE_IDENTITY_INSTALLER.defaultValue, installers[0]);
    }

    @Test public void anotherPackageGetsTheRealCallWithWhatItThrows() {
        PackageManager pm = context.getPackageManager();
        // Not TikTok's own package, so it gets exactly what the real call gives, a throw included.
        assertEquals(outcome(() -> pm.getInstallerPackageName("com.example.other")),
                outcome(() -> StoreIdentity.installerFor(pm, "com.example.other")));
        Settings.STORE_IDENTITY.save(false);
        assertEquals(outcome(() -> pm.getInstallerPackageName("com.example.other")),
                outcome(() -> StoreIdentity.installerFor(pm, "com.example.other")));
    }

    /** What a call returned, or the kind of exception it threw. */
    private static Object outcome(java.util.concurrent.Callable<String> call) {
        try {
            return "returned " + call.call();
        } catch (Exception thrown) {
            return thrown.getClass();
        }
    }

    @Test public void offTheInstallerReadsAsItReallyIs() {
        PackageManager pm = context.getPackageManager();
        pm.setInstallerPackageName(ownPackage, "com.morphe.manager");
        Settings.STORE_IDENTITY.save(false);
        assertEquals("com.morphe.manager", StoreIdentity.installerFor(pm, ownPackage));
    }

    /** Log diagnostics names the store TikTok was told, once per store rather than once per read. */
    @Test public void anAnsweredInstallerReadIsLoggedOncePerStore() {
        PackageManager pm = context.getPackageManager();
        pm.setInstallerPackageName(ownPackage, "com.morphe.manager");
        BaseSettings.DEBUG.save(true);
        try {
            StoreIdentity.installerFor(pm, ownPackage);
            StoreIdentity.installerFor(pm, ownPackage);
            Settings.STORE_IDENTITY_INSTALLER.save("com.sec.android.app.samsungapps");
            StoreIdentity.installerFor(pm, ownPackage);
            Settings.STORE_IDENTITY.save(false);
            StoreIdentity.installerFor(pm, ownPackage);
            assertEquals(Arrays.asList(StoreIdentity.STORE, "com.sec.android.app.samsungapps"), answersLogged());
        } finally {
            BaseSettings.DEBUG.save(false);
        }
    }

    /** TikTok reads its installer at a cold start, before anyone turns Log diagnostics on. */
    @Test public void aReadBeforeLoggingIsOnStillLeavesTheStoreToLog() {
        PackageManager pm = context.getPackageManager();
        pm.setInstallerPackageName(ownPackage, "com.morphe.manager");
        BaseSettings.DEBUG.save(false);
        StoreIdentity.installerFor(pm, ownPackage);
        BaseSettings.DEBUG.save(true);
        try {
            StoreIdentity.installerFor(pm, ownPackage);
            assertEquals(Arrays.asList(StoreIdentity.STORE), answersLogged());
        } finally {
            BaseSettings.DEBUG.save(false);
        }
    }

    static List<String> answersLogged() {
        String prefix = "Store identity: TikTok's installer read answered ";
        List<String> answers = new ArrayList<>();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (item.msg == null) continue;
            int at = item.msg.indexOf(prefix);
            if (at >= 0) answers.add(item.msg.substring(at + prefix.length()).trim());
        }
        return answers;
    }
}
