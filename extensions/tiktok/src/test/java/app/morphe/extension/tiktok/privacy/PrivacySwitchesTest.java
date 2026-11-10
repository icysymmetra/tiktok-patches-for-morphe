package app.morphe.extension.tiktok.privacy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AppOpsManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.ContentProvider;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.view.ViewGroup;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.EarlyApplication;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSensor;

import java.util.List;

/**
 * The answers TikTok gets back from the privacy switches. Every intercept blocks while its
 * switch is on and hands the call through while it is off; the dot follows the counts and the
 * switch; and the Privacy page is where the switches live.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class PrivacySwitchesTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
    }

    @After public void tearDown() {
        for (var setting : new app.morphe.extension.shared.settings.BooleanSetting[]{
                Settings.BLOCK_CONTACT_LIST, Settings.BLOCK_INSTALLED_APPS, Settings.BLOCK_LOCATION,
                Settings.BLOCK_CLIPBOARD_READS, Settings.HIDE_VPN, Settings.BLOCK_ADVERTISING_ID,
                Settings.BLOCK_MOTION_SENSORS, Settings.STOP_BENCHMARK_RUNS,
                Settings.BLOCK_WEBVIEW_JS_INTERFACES, Settings.CAMERA_MIC_INDICATOR, Settings.STOP_SEARCH_HISTORY,
                Settings.STOP_WATCH_HISTORY}) {
            setting.save(setting.defaultValue);
        }
        CameraMicIndicator.resetForTests();
        EarlyApplication.reset();
        SettingsStatus.contactListBlockerEnabled = false;
        SettingsStatus.searchHistoryEnabled = false;
        SettingsStatus.watchHistoryEnabled = false;
        SettingsStatus.installedAppsBlockerEnabled = false;
        SettingsStatus.locationGovernorEnabled = false;
        SettingsStatus.devicePrivacyGuardEnabled = false;
        SettingsStatus.resourceGovernorEnabled = false;
        SettingsStatus.browserPrivacyGuardEnabled = false;
        SettingsStatus.cameraMicIndicatorEnabled = false;
        SettingsStatus.ghostModeEnabled = false;
        SettingsStatus.disableTelemetryEnabled = false;
        SettingsStatus.foldableSplitViewEnabled = false;
    }

    /** One row from whichever provider is asked, so a pass-through is visible. */
    public static class OneRowProvider extends ContentProvider {
        @Override public boolean onCreate() { return true; }

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            MatrixCursor cursor = new MatrixCursor(projection != null ? projection : new String[]{"_id"});
            cursor.addRow(new Object[]{1L});
            return cursor;
        }

        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }
    }

    @Test public void contactsComeBackEmptyWhileTheSwitchIsOnAndRealWhenItIsOff() {
        Robolectric.setupContentProvider(OneRowProvider.class, ContactsContract.AUTHORITY);
        Robolectric.setupContentProvider(OneRowProvider.class, MediaStore.AUTHORITY);
        ContentResolver resolver = context.getContentResolver();
        String[] projection = {"_id"};

        Settings.BLOCK_CONTACT_LIST.save(true);
        Cursor blocked = ContactListBlocker.interceptQuery(resolver,
                ContactsContract.Contacts.CONTENT_URI, projection, null, null, null);
        assertNotNull("a blocked read is an empty cursor, not a null the caller trips on", blocked);
        assertEquals(0, blocked.getCount());
        assertEquals("the projection is honoured so the caller's column lookups still work",
                1, blocked.getColumnCount());
        Cursor media = ContactListBlocker.interceptQuery(resolver,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null);
        assertEquals("only the contacts provider is refused", 1, media.getCount());

        Settings.BLOCK_CONTACT_LIST.save(false);
        Cursor allowed = ContactListBlocker.interceptQuery(resolver,
                ContactsContract.Contacts.CONTENT_URI, projection, null, null, null);
        assertEquals("with the switch off the real provider answers", 1, allowed.getCount());
        Cursor cancellable = ContactListBlocker.interceptQuery(resolver,
                ContactsContract.Contacts.CONTENT_URI, projection, null, null, null, null);
        assertEquals(1, cancellable.getCount());
    }

    /** Counts the reads that reach it, so a blocked read can be shown never to have asked. */
    public static class CountingProvider extends OneRowProvider {
        static int reads;

        @Override public Cursor query(Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) {
            reads++;
            return super.query(uri, projection, selection, selectionArgs, sortOrder);
        }
    }

    /**
     * A null projection asks for every column. TikTok's file-name and path helpers ask that way
     * and look _display_name up before they check for a row, so a blocked read keeps the contacts
     * provider's columns in every overload, before Hushfeed has a context too, and the provider
     * is never asked.
     */
    @Test public void aBlockedReadWithNoProjectionStillHasTheContactsColumns() {
        Robolectric.setupContentProvider(CountingProvider.class, ContactsContract.AUTHORITY);
        CountingProvider.reads = 0;
        ContentResolver resolver = context.getContentResolver();
        Uri[] uris = {
                ContactsContract.Contacts.CONTENT_URI,
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                ContactsContract.RawContacts.CONTENT_URI,
                Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode("5551234")),
                Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_VCARD_URI, "0r1-2"),
        };
        String[] wanted = {"_id", "lookup", "display_name", "has_phone_number", "contact_id",
                "raw_contact_id", "mimetype", "data1", "number", "_display_name", "_size"};
        Settings.BLOCK_CONTACT_LIST.save(true);
        saveInTheFile(ContactListBlocker.SWITCH_KEY, true);
        EarlyApplication.set(context);
        for (boolean early : new boolean[]{false, true}) {
            if (early) Utils.setContext(null);
            try {
                for (Uri uri : uris) {
                    Cursor[] answers = {
                            ContactListBlocker.interceptQuery(resolver, uri, null, null, null, null),
                            ContactListBlocker.interceptQuery(resolver, uri, null, null, null, null, null),
                            ContactListBlocker.interceptQuery(resolver, uri, null, (Bundle) null, null),
                    };
                    for (Cursor cursor : answers) {
                        assertEquals(uri + " has no rows", 0, cursor.getCount());
                        for (String column : wanted) cursor.getColumnIndexOrThrow(column);
                        assertEquals(-1, cursor.getColumnIndex("relative_path"));
                    }
                }
            } finally {
                Utils.setContext(context);
            }
        }
        assertEquals("the real provider is never asked while the switch is on", 0, CountingProvider.reads);

        Cursor named = ContactListBlocker.interceptQuery(resolver,
                ContactsContract.Contacts.CONTENT_URI, new String[]{"display_name"}, null, null, null);
        assertEquals("a named projection keeps its own shape", 1, named.getColumnCount());
        Cursor first = ContactListBlocker.interceptQuery(resolver,
                ContactsContract.Contacts.CONTENT_URI, null, null, null, null);
        first.getColumnNames()[0] = "changed";
        Cursor second = ContactListBlocker.interceptQuery(resolver,
                ContactsContract.Contacts.CONTENT_URI, null, null, null, null);
        assertEquals("one caller's edit to its column names doesn't reach the next", 0,
                second.getColumnIndexOrThrow("_id"));
        assertEquals("no column is listed twice", ContactListBlocker.ALL_COLUMNS.length,
                new java.util.HashSet<>(java.util.Arrays.asList(ContactListBlocker.ALL_COLUMNS)).size());

        Settings.BLOCK_CONTACT_LIST.save(false);
        saveInTheFile(ContactListBlocker.SWITCH_KEY, false);
        assertEquals("with the switch off the real provider answers", 1, ContactListBlocker.interceptQuery(
                resolver, ContactsContract.Contacts.CONTENT_URI, null, null, null, null).getCount());
        assertEquals(1, CountingProvider.reads);
    }

    @Test public void theLauncherEnumerationIsTheOnlyPackageQueryRefused() {
        Intent inventory = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        Intent share = new Intent(Intent.ACTION_SEND).setType("text/plain");
        Intent oneApp = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .setPackage("com.example.other");
        assertTrue(InstalledAppsBlocker.isAppInventoryScan(inventory));
        assertFalse("a share target query is not an inventory scan", InstalledAppsBlocker.isAppInventoryScan(share));
        assertFalse("a check for one named app is left alone", InstalledAppsBlocker.isAppInventoryScan(oneApp));
        assertFalse(InstalledAppsBlocker.isAppInventoryScan(null));

        PackageManager pm = context.getPackageManager();
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = "com.example.other";
        info.activityInfo.name = "com.example.other.Main";
        Shadows.shadowOf(pm).addResolveInfoForIntent(inventory, info);
        Shadows.shadowOf(pm).addResolveInfoForIntent(share, info);

        Settings.BLOCK_INSTALLED_APPS.save(true);
        assertEquals(0, InstalledAppsBlocker.interceptQueryIntentActivities(pm, inventory, 0).size());
        assertEquals("the share sheet still learns which apps can take a share",
                1, InstalledAppsBlocker.interceptQueryIntentActivities(pm, share, 0).size());
        assertEquals(0, InstalledAppsBlocker.interceptGetInstalledPackages(pm, 0).size());

        Settings.BLOCK_INSTALLED_APPS.save(false);
        assertEquals(1, InstalledAppsBlocker.interceptQueryIntentActivities(pm, inventory, 0).size());
    }

    @Test public void locationIsNothingWhileTheSwitchIsOn() {
        LocationManager manager = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        Location here = new Location(LocationManager.GPS_PROVIDER);
        here.setLatitude(51.5);
        here.setLongitude(-0.12);
        Shadows.shadowOf(manager).setLastKnownLocation(LocationManager.GPS_PROVIDER, here);

        Settings.BLOCK_LOCATION.save(true);
        assertNull(LocationGovernor.interceptGetLastKnownLocation(manager, LocationManager.GPS_PROVIDER));

        Settings.BLOCK_LOCATION.save(false);
        Location answered = LocationGovernor.interceptGetLastKnownLocation(manager, LocationManager.GPS_PROVIDER);
        assertNotNull(answered);
        assertEquals(51.5, answered.getLatitude(), 0.0001);
    }

    @Test public void clipboardReadsAreEmptyWhileTheSwitchIsOn() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("label", "hello"));

        Settings.BLOCK_CLIPBOARD_READS.save(true);
        assertNull(DevicePrivacyGuard.interceptPrimaryClip(clipboard));
        assertEquals("", DevicePrivacyGuard.interceptClipboardText(clipboard).toString());
        assertFalse(DevicePrivacyGuard.interceptHasPrimaryClip(clipboard));

        Settings.BLOCK_CLIPBOARD_READS.save(false);
        assertNotNull(DevicePrivacyGuard.interceptPrimaryClip(clipboard));
        assertEquals("hello", DevicePrivacyGuard.interceptClipboardText(clipboard).toString());
        assertTrue(DevicePrivacyGuard.interceptHasPrimaryClip(clipboard));
    }

    @Test public void onlyTheVpnTransportIsHiddenAndOnlyWhileTheSwitchIsOn() {
        NetworkCapabilities capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance();
        Shadows.shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_WIFI);
        Shadows.shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN);

        Settings.HIDE_VPN.save(true);
        assertFalse("a VPN transport reads as absent while the switch is on",
                DevicePrivacyGuard.interceptHasTransport(capabilities, NetworkCapabilities.TRANSPORT_VPN));
        assertTrue("every other transport is answered as it really is",
                DevicePrivacyGuard.interceptHasTransport(capabilities, NetworkCapabilities.TRANSPORT_WIFI));

        Settings.HIDE_VPN.save(false);
        assertTrue("with the switch off the real VPN transport comes through",
                DevicePrivacyGuard.interceptHasTransport(capabilities, NetworkCapabilities.TRANSPORT_VPN));
    }

    @Test public void vpnTunnelInterfaceNamesAreTheOnesHidden() {
        assertTrue(DevicePrivacyGuard.isVpnInterfaceName("tun0"));
        assertTrue(DevicePrivacyGuard.isVpnInterfaceName("ppp0"));
        assertTrue(DevicePrivacyGuard.isVpnInterfaceName("ipsec1"));
        assertTrue(DevicePrivacyGuard.isVpnInterfaceName("wg0"));
        assertFalse(DevicePrivacyGuard.isVpnInterfaceName("wlan0"));
        assertFalse(DevicePrivacyGuard.isVpnInterfaceName("eth0"));
        assertFalse(DevicePrivacyGuard.isVpnInterfaceName("rmnet0"));
        assertFalse(DevicePrivacyGuard.isVpnInterfaceName("lo"));
        assertFalse(DevicePrivacyGuard.isVpnInterfaceName(null));
        assertFalse(DevicePrivacyGuard.isVpnInterfaceName(""));

        try {
            Settings.HIDE_VPN.save(true);
            assertNotNull("the filtered interface list is still a list, never null for a thrown read",
                    DevicePrivacyGuard.interceptNetworkInterfaces());
            Settings.HIDE_VPN.save(false);
            assertNotNull(DevicePrivacyGuard.interceptNetworkInterfaces());
        } catch (java.net.SocketException ignored) {
            // The test host may refuse to enumerate interfaces; the name predicate above is the check.
        }
    }

    /** A stand-in for Play Services' AdvertisingIdClient.Info, read by reflection like the real one. */
    public static final class FakeAdInfo {
        public String getId() {
            return "a1b2c3d4-0000-1111-2222-aabbccddeeff";
        }
    }

    @Test public void theAdvertisingIdIsBlankOnlyWhileTheSwitchIsOn() {
        FakeAdInfo info = new FakeAdInfo();

        Settings.BLOCK_ADVERTISING_ID.save(true);
        assertEquals("00000000-0000-0000-0000-000000000000",
                DevicePrivacyGuard.interceptAdvertisingId(info));

        Settings.BLOCK_ADVERTISING_ID.save(false);
        assertEquals("a1b2c3d4-0000-1111-2222-aabbccddeeff",
                DevicePrivacyGuard.interceptAdvertisingId(info));
        assertNull("a null Info with the switch off is answered with no id, not a crash",
                DevicePrivacyGuard.interceptAdvertisingId(null));
    }

    private static final String REAL_AD_ID = "a1b2c3d4-0000-1111-2222-aabbccddeeff";
    private static final String BLANK_AD_ID = "00000000-0000-0000-0000-000000000000";

    /**
     * The reads that skip Info.getId: two SDKs ask Google's service for the id (a String) and the
     * limit flag (an int, nonzero for limited) themselves, and TikTok reads Info's limit field
     * where isLimitAdTrackingEnabled was inlined. On, they answer as Android does once the id is
     * deleted: blank and limited.
     */
    @Test public void directAdvertisingIdReadsAreBlankAndLimitedOnlyWhileTheSwitchIsOn() {
        Settings.BLOCK_ADVERTISING_ID.save(true);
        assertEquals(BLANK_AD_ID, DevicePrivacyGuard.interceptAdvertisingIdRead(REAL_AD_ID));
        assertEquals("a missing id still reads as the blank one", BLANK_AD_ID,
                DevicePrivacyGuard.interceptAdvertisingIdRead(null));
        assertEquals("the service's reply reads as limited", 1, DevicePrivacyGuard.interceptLimitAdTrackingReply(0));
        assertTrue("Info's flag reads as limited", DevicePrivacyGuard.interceptLimitAdTracking(false));
        assertTrue(DevicePrivacyGuard.interceptLimitAdTracking(true));

        Settings.BLOCK_ADVERTISING_ID.save(false);
        assertEquals("off, the id the read produced comes through", REAL_AD_ID,
                DevicePrivacyGuard.interceptAdvertisingIdRead(REAL_AD_ID));
        assertNull(DevicePrivacyGuard.interceptAdvertisingIdRead(null));
        assertEquals(0, DevicePrivacyGuard.interceptLimitAdTrackingReply(0));
        assertEquals("a limited reply stays as the service sent it", 5,
                DevicePrivacyGuard.interceptLimitAdTrackingReply(5));
        assertFalse(DevicePrivacyGuard.interceptLimitAdTracking(false));
        assertTrue(DevicePrivacyGuard.interceptLimitAdTracking(true));
    }

    @Test public void pauseHandsTheRealAdvertisingIdAndLimitFlagBack() {
        Settings.BLOCK_ADVERTISING_ID.save(true);
        PausedProcess.set(true);
        try {
            assertEquals(REAL_AD_ID, DevicePrivacyGuard.interceptAdvertisingId(new FakeAdInfo()));
            assertEquals(REAL_AD_ID, DevicePrivacyGuard.interceptAdvertisingIdRead(REAL_AD_ID));
            assertEquals(0, DevicePrivacyGuard.interceptLimitAdTrackingReply(0));
            assertFalse(DevicePrivacyGuard.interceptLimitAdTracking(false));
            assertEquals("the saved choice is kept for after the pause",
                    Boolean.TRUE, Settings.BLOCK_ADVERTISING_ID.savedValue());
        } finally {
            PausedProcess.set(false);
        }
        assertEquals("the next read after the pause is blank again", BLANK_AD_ID,
                DevicePrivacyGuard.interceptAdvertisingIdRead(REAL_AD_ID));
        assertTrue(DevicePrivacyGuard.interceptLimitAdTracking(false));
    }

    /**
     * One of each device read the blocks answer, set up so a read that goes through gets a real
     * answer back and a refused one is plain to see.
     */
    private final class DeviceReads {
        final ContentResolver resolver = context.getContentResolver();
        final PackageManager pm = context.getPackageManager();
        final Intent inventory = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        final LocationManager locations = (LocationManager) context.getSystemService(Context.LOCATION_SERVICE);
        final ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        final SensorManager sensors = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        final Sensor accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER);
        final SensorEventListener listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {}
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        final NetworkCapabilities capabilities = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance();

        DeviceReads() {
            Robolectric.setupContentProvider(OneRowProvider.class, ContactsContract.AUTHORITY);
            ResolveInfo info = new ResolveInfo();
            info.activityInfo = new ActivityInfo();
            info.activityInfo.packageName = "com.example.other";
            info.activityInfo.name = "com.example.other.Main";
            Shadows.shadowOf(pm).addResolveInfoForIntent(inventory, info);
            Shadows.shadowOf(locations).setLastKnownLocation(LocationManager.GPS_PROVIDER,
                    new Location(LocationManager.GPS_PROVIDER));
            clipboard.setPrimaryClip(ClipData.newPlainText("label", "hello"));
            Shadows.shadowOf(capabilities).addTransportType(NetworkCapabilities.TRANSPORT_VPN);
        }

        void allGoThrough() {
            assertEquals(1, ContactListBlocker.interceptQuery(resolver,
                    ContactsContract.Contacts.CONTENT_URI, new String[]{"_id"}, null, null, null).getCount());
            assertEquals(1, InstalledAppsBlocker.interceptQueryIntentActivities(pm, inventory, 0).size());
            assertNotNull(LocationGovernor.interceptGetLastKnownLocation(locations, LocationManager.GPS_PROVIDER));
            assertEquals("hello", DevicePrivacyGuard.interceptClipboardText(clipboard).toString());
            assertTrue(ResourceBatteryGovernor.interceptSensorRegistration(sensors, listener, accelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL));
            assertTrue(DevicePrivacyGuard.interceptHasTransport(capabilities, NetworkCapabilities.TRANSPORT_VPN));
            assertEquals(REAL_AD_ID, DevicePrivacyGuard.interceptAdvertisingId(new FakeAdInfo()));
            assertEquals(REAL_AD_ID, DevicePrivacyGuard.interceptAdvertisingIdRead(REAL_AD_ID));
            assertEquals(0, DevicePrivacyGuard.interceptLimitAdTrackingReply(0));
            assertFalse(DevicePrivacyGuard.interceptLimitAdTracking(false));
        }

        void allRefused() {
            assertEquals(0, ContactListBlocker.interceptQuery(resolver,
                    ContactsContract.Contacts.CONTENT_URI, new String[]{"_id"}, null, null, null).getCount());
            assertEquals(0, InstalledAppsBlocker.interceptQueryIntentActivities(pm, inventory, 0).size());
            assertNull(LocationGovernor.interceptGetLastKnownLocation(locations, LocationManager.GPS_PROVIDER));
            assertEquals("", DevicePrivacyGuard.interceptClipboardText(clipboard).toString());
            assertFalse(ResourceBatteryGovernor.interceptSensorRegistration(sensors, listener, accelerometer,
                    SensorManager.SENSOR_DELAY_NORMAL));
            assertFalse(DevicePrivacyGuard.interceptHasTransport(capabilities, NetworkCapabilities.TRANSPORT_VPN));
            assertEquals(BLANK_AD_ID, DevicePrivacyGuard.interceptAdvertisingId(new FakeAdInfo()));
            assertEquals(BLANK_AD_ID, DevicePrivacyGuard.interceptAdvertisingIdRead(REAL_AD_ID));
            assertEquals(1, DevicePrivacyGuard.interceptLimitAdTrackingReply(0));
            assertTrue(DevicePrivacyGuard.interceptLimitAdTracking(false));
        }
    }

    /** Built per test: a static field would load Settings before setUp has given it a context. */
    private static app.morphe.extension.shared.settings.BooleanSetting[] deviceAccess() {
        return new app.morphe.extension.shared.settings.BooleanSetting[]{
                Settings.BLOCK_CONTACT_LIST, Settings.BLOCK_INSTALLED_APPS, Settings.BLOCK_LOCATION,
                Settings.BLOCK_CLIPBOARD_READS, Settings.BLOCK_MOTION_SENSORS, Settings.HIDE_VPN,
                Settings.BLOCK_ADVERTISING_ID};
    }

    /** The read before the settings context names each switch by a key of its own. */
    @Test public void theEarlyKeysAreTheOnesTheSwitchesSaveUnder() {
        assertEquals(Settings.BLOCK_CONTACT_LIST.key, ContactListBlocker.SWITCH_KEY);
        assertEquals(Settings.BLOCK_INSTALLED_APPS.key, InstalledAppsBlocker.SWITCH_KEY);
        assertEquals(Settings.BLOCK_LOCATION.key, LocationGovernor.SWITCH_KEY);
        assertEquals(Settings.BLOCK_MOTION_SENSORS.key, ResourceBatteryGovernor.SWITCH_KEY);
        assertEquals(Settings.BLOCK_CLIPBOARD_READS.key, DevicePrivacyGuard.CLIPBOARD_KEY);
        assertEquals(Settings.HIDE_VPN.key, DevicePrivacyGuard.VPN_KEY);
        assertEquals(Settings.BLOCK_ADVERTISING_ID.key, DevicePrivacyGuard.ADVERTISING_ID_KEY);
    }

    /** The device-access blocks start off, so a read before Hushfeed has a context goes through too. */
    @Test public void beforeTheExtensionHasAContextNoDeviceReadIsBlockedWhileTheSwitchesAreOff() {
        for (var setting : deviceAccess()) {
            assertEquals("the patch is in the default selection, so " + setting.key + " starts off",
                    Boolean.FALSE, setting.defaultValue);
        }
        assertEquals(Boolean.FALSE, Settings.CAMERA_MIC_INDICATOR.defaultValue);
        DeviceReads reads = new DeviceReads();
        EarlyApplication.set(context);
        Utils.setContext(null);
        try {
            reads.allGoThrough();
        } finally {
            Utils.setContext(context);
        }
    }

    /**
     * TikTok checks its connection and reads the advertising id early in startup, before
     * Hushfeed has a context to read settings with. A switch that's on still covers those reads,
     * since the saved value is read straight from the file.
     */
    @Test public void beforeTheExtensionHasAContextASwitchThatsOnStillBlocks() {
        DeviceReads reads = new DeviceReads();
        for (var setting : deviceAccess()) {
            setting.save(true);
            saveInTheFile(setting.key, true);
        }
        EarlyApplication.set(context);
        Utils.setContext(null);
        try {
            reads.allRefused();
        } finally {
            Utils.setContext(context);
        }
    }

    /** Paused, the early read answers off the same way the switch does once the context is up. */
    @Test public void beforeTheExtensionHasAContextAPausedStartHandsEveryReadThrough() {
        DeviceReads reads = new DeviceReads();
        for (var setting : deviceAccess()) {
            setting.save(true);
            saveInTheFile(setting.key, true);
        }
        BaseSettings.PAUSED.save(true);
        saveInTheFile(BaseSettings.PAUSED.key, true);
        EarlyApplication.set(context);
        Utils.setContext(null);
        try {
            reads.allGoThrough();
        } finally {
            Utils.setContext(context);
            BaseSettings.PAUSED.save(false);
        }
    }

    /**
     * The early read opens the application's own preferences file. Settings keep the handle from
     * the first application this test JVM made, and Robolectric gives each test a fresh one, so a
     * save through a setting alone doesn't reach the file this test's application reads. On a
     * phone both are the same file.
     */
    private void saveInTheFile(String key, boolean value) {
        context.getSharedPreferences(app.morphe.extension.shared.settings.Setting.PREFERENCES_NAME,
                android.content.Context.MODE_PRIVATE).edit().putBoolean(key, value).commit();
    }

    /** No application to read from yet: TikTok's read goes through as it would unpatched. */
    @Test public void beforeTikTokHasAnApplicationEveryReadGoesThrough() {
        DeviceReads reads = new DeviceReads();
        for (var setting : deviceAccess()) setting.save(true);
        EarlyApplication.set(null);
        Utils.setContext(null);
        try {
            reads.allGoThrough();
        } finally {
            Utils.setContext(context);
        }
    }

    @Test public void motionSensorsAreRefusedAndTheRestRegistered() {
        SensorManager manager = (SensorManager) context.getSystemService(Context.SENSOR_SERVICE);
        Sensor accelerometer = ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER);
        Sensor light = ShadowSensor.newInstance(Sensor.TYPE_LIGHT);
        SensorEventListener listener = new SensorEventListener() {
            @Override public void onSensorChanged(SensorEvent event) {}
            @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };

        Settings.BLOCK_MOTION_SENSORS.save(true);
        assertFalse(ResourceBatteryGovernor.interceptSensorRegistration(manager, listener, accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL));
        assertFalse(ResourceBatteryGovernor.interceptSensorRegistration(manager, listener, accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL, (android.os.Handler) null));
        assertTrue("a light sensor is not a motion sensor and goes through",
                ResourceBatteryGovernor.interceptSensorRegistration(manager, listener, light,
                        SensorManager.SENSOR_DELAY_NORMAL));

        Settings.BLOCK_MOTION_SENSORS.save(false);
        assertTrue(ResourceBatteryGovernor.interceptSensorRegistration(manager, listener, accelerometer,
                SensorManager.SENSOR_DELAY_NORMAL));
    }

    @Test public void theBenchmarkSwitchDisablesItsServiceAndOffOrPausePutsItBack() throws Exception {
        SettingsStatus.resourceGovernorEnabled = true;
        PackageManager packages = context.getPackageManager();
        ComponentName service = new ComponentName(context.getPackageName(), BenchmarkRuns.SERVICE);
        Shadows.shadowOf(packages).addServiceIfNotPresent(service);

        BenchmarkRuns.settingsChanged(context, true);
        Utils.awaitBackgroundTasksForTests();
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, packages.getComponentEnabledSetting(service));
        BenchmarkRuns.settingsChanged(context, false);
        Utils.awaitBackgroundTasksForTests();
        assertEquals("off hands the service back to the manifest",
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, packages.getComponentEnabledSetting(service));

        for (int flip = 0; flip < 20; flip++) {
            BenchmarkRuns.settingsChanged(context, true);
            BenchmarkRuns.settingsChanged(context, false);
        }
        Utils.awaitBackgroundTasksForTests();
        assertEquals("quick flips end where the switch was left",
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, packages.getComponentEnabledSetting(service));

        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Settings.STOP_BENCHMARK_RUNS.save(true);
            BenchmarkRuns.onAppOpened(owner.get());
            Utils.awaitBackgroundTasksForTests();
            assertEquals("a start puts the saved choice into effect",
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED, packages.getComponentEnabledSetting(service));

            PausedProcess.set(true);
            try {
                BenchmarkRuns.onAppOpened(owner.get());
                Utils.awaitBackgroundTasksForTests();
                assertEquals("a paused start leaves the benchmark to TikTok",
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, packages.getComponentEnabledSetting(service));
                BenchmarkRuns.settingsChanged(context, true);
                Utils.awaitBackgroundTasksForTests();
                assertEquals("turning it on while paused waits for the pause to end",
                        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, packages.getComponentEnabledSetting(service));
            } finally {
                PausedProcess.set(false);
            }
        }
    }

    @Test public void searchHistoryIsSkippedOnlyWhileThePatchedSwitchIsOnAndHushfeedRuns() {
        assertEquals("the patch is in the default selection, so its switch starts off",
                Boolean.FALSE, Settings.STOP_SEARCH_HISTORY.defaultValue);
        Settings.STOP_SEARCH_HISTORY.save(true);
        assertFalse("a build without the patch never skips a write", SearchHistoryRecording.shouldSkip());

        SettingsStatus.searchHistoryEnabled = true;
        assertTrue("on: both writers leave before saving", SearchHistoryRecording.shouldSkip());
        Settings.STOP_SEARCH_HISTORY.save(false);
        assertFalse("off: TikTok records the search", SearchHistoryRecording.shouldSkip());

        Settings.STOP_SEARCH_HISTORY.save(true);
        PausedProcess.set(true);
        try {
            assertFalse("paused: TikTok records as it ships", SearchHistoryRecording.shouldSkip());
            assertEquals("the saved choice is kept for after the pause",
                    Boolean.TRUE, Settings.STOP_SEARCH_HISTORY.savedValue());
        } finally {
            PausedProcess.set(false);
        }
        assertTrue("the next search after the pause is skipped again", SearchHistoryRecording.shouldSkip());
    }

    @Test public void viewReportsAreHeldBackOnlyWhileTheSwitchIsOnAndHushfeedRuns() {
        assertEquals("picking the patch isn't enough, since people use Watch history",
                Boolean.FALSE, Settings.STOP_WATCH_HISTORY.defaultValue);
        SettingsStatus.watchHistoryEnabled = true;
        assertFalse("off by default: TikTok reports the view", WatchHistoryRecording.shouldSkip());

        Settings.STOP_WATCH_HISTORY.save(true);
        assertTrue("on: both senders leave before building a report", WatchHistoryRecording.shouldSkip());
        SettingsStatus.watchHistoryEnabled = false;
        assertFalse("a switch saved on by a build with the patch does nothing without it",
                WatchHistoryRecording.shouldSkip());

        SettingsStatus.watchHistoryEnabled = true;
        PausedProcess.set(true);
        try {
            assertFalse("paused: TikTok reports as it ships", WatchHistoryRecording.shouldSkip());
            assertEquals("the saved choice is kept for after the pause",
                    Boolean.TRUE, Settings.STOP_WATCH_HISTORY.savedValue());
        } finally {
            PausedProcess.set(false);
        }
        assertTrue("the next video after the pause is held back again", WatchHistoryRecording.shouldSkip());
    }

    @Test public void theWatchHistorySwitchAloneOpensThePrivacyPageUnderTracking() {
        SettingsStatus.watchHistoryEnabled = true;
        assertTrue(app.morphe.extension.tiktok.settings.preference.categories.PrivacyPreferenceCategory.isAvailable());
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            List<String> keys = keysOn(open(activity, "PRIVACY"));
            assertEquals(List.of(Settings.STOP_WATCH_HISTORY.key), keys.stream()
                    .filter(key -> key.startsWith("block_") || key.startsWith("stop_") || key.equals(Settings.GHOST_MODE.key))
                    .toList());
        }
    }

    @Test public void theSearchHistorySwitchAloneOpensThePrivacyPageUnderTracking() {
        SettingsStatus.searchHistoryEnabled = true;
        assertTrue(app.morphe.extension.tiktok.settings.preference.categories.PrivacyPreferenceCategory.isAvailable());
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            List<String> keys = keysOn(open(activity, "PRIVACY"));
            assertEquals(List.of(Settings.STOP_SEARCH_HISTORY.key), keys.stream()
                    .filter(key -> key.startsWith("block_") || key.startsWith("stop_") || key.equals(Settings.GHOST_MODE.key))
                    .toList());
        }
    }

    @Test public void theDotFollowsTheCountsAndTheSwitch() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            int childrenBefore = decor.getChildCount();

            Settings.CAMERA_MIC_INDICATOR.save(true);
            CameraMicIndicator.onMicStart();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            CameraMicIndicator.DotView dot = CameraMicIndicator.shownDot();
            assertNotNull("a live microphone shows the dot", dot);
            assertSame("the dot sits on the top activity's decor view", decor, dot.getParent());
            assertTrue(dot.microphone());
            assertFalse(dot.camera());
            assertEquals("Microphone in use", String.valueOf(dot.getContentDescription()));
            assertFalse("the dot must never take a touch", dot.isClickable());

            CameraMicIndicator.onCameraOpened(null);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertFalse("a camera that failed to open is not an access", CameraMicIndicator.shownDot().camera());
            CameraMicIndicator.onCameraStart();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue(CameraMicIndicator.shownDot().camera());
            assertEquals("Camera and microphone in use",
                    String.valueOf(CameraMicIndicator.shownDot().getContentDescription()));

            CameraMicIndicator.onMicStop();
            CameraMicIndicator.onMicStop();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals("a stop reported twice does not go below zero", 0, CameraMicIndicator.microphones());
            assertTrue("the camera is still open, so the dot stays", CameraMicIndicator.shownDot().camera());
            assertFalse(CameraMicIndicator.shownDot().microphone());

            CameraMicIndicator.onCameraStop();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNull("nothing live, nothing drawn", CameraMicIndicator.shownDot());
            assertEquals(childrenBefore, decor.getChildCount());

            Settings.CAMERA_MIC_INDICATOR.save(false);
            CameraMicIndicator.onCameraStart();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNull("with the switch off the access is counted and nothing is drawn", CameraMicIndicator.shownDot());
            assertEquals(1, CameraMicIndicator.cameras());
        }
    }

    /**
     * TikTok's camera is a scene inside an activity that has already resumed when the camera
     * opens. With the tracking installed from the main activity, the mark goes onto that screen,
     * not onto the main activity underneath, where the S25 showed nothing (2026-09-26).
     */
    @Test public void theDotGoesOnTheScreenInFrontWhenTheCameraOpensAfterItResumed() {
        try (var main = Robolectric.buildActivity(Activity.class).create()) {
            CameraMicIndicator.install(main.get());
            main.start().resume().visible();
            Utils.setActivity(main.get());
            try (var camera = Robolectric.buildActivity(Activity.class).setup().visible()) {
                Settings.CAMERA_MIC_INDICATOR.save(true);
                CameraMicIndicator.onCameraStart();
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                CameraMicIndicator.DotView dot = CameraMicIndicator.shownDot();
                assertNotNull("an open camera shows the mark", dot);
                assertSame("the mark is on the camera's screen, not the main one behind it",
                        camera.get().getWindow().getDecorView(), dot.getParent());
            }
        }
    }

    /**
     * A screen that draws under the status bar and reports no top inset still gets the mark below
     * the bar, not on its icons: the S25's camera screen put it over the battery (2026-09-26).
     */
    @Test public void theMarkSitsBelowTheStatusBarWhenTheWindowReportsNoInset() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            Settings.CAMERA_MIC_INDICATOR.save(true);
            CameraMicIndicator.onCameraStart();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            CameraMicIndicator.DotView dot = CameraMicIndicator.shownDot();
            assertNotNull(dot);
            int id = activity.getResources().getIdentifier("status_bar_height", "dimen", "android");
            int bar = activity.getResources().getDimensionPixelSize(id);
            assertTrue("the status bar has a height to clear", bar > 0);
            int top = ((android.widget.FrameLayout.LayoutParams) dot.getLayoutParams()).topMargin;
            assertTrue("the mark starts below the status bar (" + top + " px, bar " + bar + " px)", top > bar);
        }
    }

    /**
     * TikTok records a story's sound natively, so the recorder calls the patch counts only flash
     * at its start (the S25, 2026-09-26: 120 ms of a four-second recording). The system's own op
     * report holds the mark for as long as the recording runs.
     */
    @Config(sdk = 35)
    @Test public void theMarkStaysForAsLongAsTheSystemSaysTheAppIsRecording() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            CameraMicIndicator.install(activity);
            Settings.CAMERA_MIC_INDICATOR.save(true);
            int uid = android.os.Process.myUid();
            String own = activity.getPackageName();

            CameraMicIndicator.onMicStart();
            CameraMicIndicator.onOpActiveChanged(AppOpsManager.OPSTR_RECORD_AUDIO, uid, own, true);
            CameraMicIndicator.onMicStop();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertEquals(0, CameraMicIndicator.microphones());
            assertNotNull("the system still reports the recording, so the mark stays", CameraMicIndicator.shownDot());
            assertTrue(CameraMicIndicator.shownDot().microphone());
            assertFalse(CameraMicIndicator.shownDot().camera());

            CameraMicIndicator.onOpActiveChanged(AppOpsManager.OPSTR_CAMERA, uid, own, true);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertTrue("a camera the system reports shows the square", CameraMicIndicator.shownDot().camera());

            CameraMicIndicator.onOpActiveChanged(AppOpsManager.OPSTR_RECORD_AUDIO, uid, own, false);
            CameraMicIndicator.onOpActiveChanged(AppOpsManager.OPSTR_CAMERA, uid, own, false);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNull("the recording ended, so the mark goes", CameraMicIndicator.shownDot());
        }
    }

    /** Another app's op, or another uid's, never lights this app's mark. */
    @Config(sdk = 35)
    @Test public void onlyThisAppsOwnOpsCount() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            CameraMicIndicator.install(activity);
            Settings.CAMERA_MIC_INDICATOR.save(true);
            int uid = android.os.Process.myUid();

            CameraMicIndicator.onOpActiveChanged(AppOpsManager.OPSTR_RECORD_AUDIO, uid + 1, activity.getPackageName(), true);
            CameraMicIndicator.onOpActiveChanged(AppOpsManager.OPSTR_CAMERA, uid, "com.example.other", true);
            CameraMicIndicator.onOpActiveChanged(AppOpsManager.OPSTR_FINE_LOCATION, uid, activity.getPackageName(), true);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            assertNull("nothing of this app's is live", CameraMicIndicator.shownDot());
        }
    }

    /** A recording already running when TikTok's screen is created shows at once. */
    @Config(sdk = 35)
    @Test public void aRecordingUnderwayWhenTheWatchStartsShowsAtOnce() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setActivity(activity);
            Settings.CAMERA_MIC_INDICATOR.save(true);
            AppOpsManager ops = activity.getSystemService(AppOpsManager.class);
            ops.startOpNoThrow(AppOpsManager.OPSTR_RECORD_AUDIO, android.os.Process.myUid(), activity.getPackageName());
            try {
                CameraMicIndicator.install(activity);
                Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
                assertNotNull("the recording was running before the watch began", CameraMicIndicator.shownDot());
                assertTrue(CameraMicIndicator.shownDot().microphone());
            } finally {
                ops.finishOp(AppOpsManager.OPSTR_RECORD_AUDIO, android.os.Process.myUid(), activity.getPackageName());
            }
        }
    }

    @Test public void theLegacyPrivacyRouteAndAppBehaviorCarryTheSamePrivacySwitches() {
        SettingsStatus.contactListBlockerEnabled = true;
        SettingsStatus.installedAppsBlockerEnabled = true;
        SettingsStatus.locationGovernorEnabled = true;
        SettingsStatus.devicePrivacyGuardEnabled = true;
        SettingsStatus.resourceGovernorEnabled = true;
        SettingsStatus.browserPrivacyGuardEnabled = true;
        SettingsStatus.cameraMicIndicatorEnabled = true;
        SettingsStatus.ghostModeEnabled = true;
        SettingsStatus.disableTelemetryEnabled = true;
        SettingsStatus.foldableSplitViewEnabled = true;
        SettingsStatus.searchHistoryEnabled = true;
        try (var owner = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            TikTokPreferenceFragment privacy = open(activity, "PRIVACY");
            List<String> keys = keysOn(privacy);
            List<String> expected = List.of(Settings.DISABLE_ANALYTICS.key, Settings.GHOST_MODE.key,
                    Settings.STOP_SEARCH_HISTORY.key, Settings.BLOCK_CONTACT_LIST.key, Settings.BLOCK_INSTALLED_APPS.key,
                    Settings.BLOCK_LOCATION.key, Settings.BLOCK_CLIPBOARD_READS.key, Settings.HIDE_VPN.key,
                    Settings.BLOCK_ADVERTISING_ID.key, Settings.BLOCK_MOTION_SENSORS.key, Settings.STOP_BENCHMARK_RUNS.key,
                    Settings.CAMERA_MIC_INDICATOR.key, Settings.BLOCK_WEBVIEW_JS_INTERFACES.key);
            assertEquals("the Privacy page lists tracking, then device access, then links",
                    expected, keys.stream().filter(expected::contains).toList());
            assertNotNull("Tracking heading", privacy.findPreference(Settings.GHOST_MODE.key));

            TikTokPreferenceFragment behavior = open(activity, "BEHAVIOR");
            assertEquals("Metra merges the same privacy controls into App behavior",
                    expected, keysOn(behavior).stream().filter(expected::contains).toList());
            assertNotNull(behavior.findPreference(Settings.GHOST_MODE.key));
            assertNotNull(behavior.findPreference(Settings.DISABLE_ANALYTICS.key));
            assertNotNull("the layout rows are still App behavior's",
                    behavior.findPreference(Settings.FOLDABLE_SPLIT_VIEW.key));
        }
    }

    private static TikTokPreferenceFragment open(Activity activity, String section) {
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", section);
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
        return fragment;
    }

    private static List<String> keysOn(TikTokPreferenceFragment fragment) {
        var screen = fragment.getPreferenceScreen();
        java.util.ArrayList<String> keys = new java.util.ArrayList<>();
        for (int index = 0; index < screen.getPreferenceCount(); index++) {
            String key = screen.getPreference(index).getKey();
            if (key != null) keys.add(key);
        }
        return keys;
    }
}
