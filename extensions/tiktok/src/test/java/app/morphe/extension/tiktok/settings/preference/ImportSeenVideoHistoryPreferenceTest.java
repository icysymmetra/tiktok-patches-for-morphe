package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.tiktok.settings.SettingsPagesTest;
import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.lang.reflect.Field;
import java.util.TimeZone;
import java.text.SimpleDateFormat;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.android.controller.ActivityController;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class ImportSeenVideoHistoryPreferenceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    private ActivityController<SettingsPagesTest.PageActivity> owner;
    private Activity activity;
    private TikTokPreferenceFragment fragment;
    private TimeZone originalZone;
    private app.morphe.extension.tiktok.DocumentExportProvider provider;

    @Before public void setUp() throws Exception {
        originalZone = TimeZone.getDefault();
        resetPicker();
        SignedInUser.idForTests = "import-picker-" + System.nanoTime();
        SettingsStatus.seenVideoFilterEnabled = true;
        owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible();
        activity = owner.get();
        Utils.setContext(activity);
        SettingsUi.syncDarkMode(activity);
        FrameLayout content = new FrameLayout(activity);
        content.setId(View.generateViewId());
        content.setTag(SettingsActionBanner.CONTENT_ROOT_TAG);
        activity.setContentView(content);
        fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "FEED_FILTER");
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction()
                .replace(content.getId(), fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
    }

    @After public void tearDown() throws Exception {
        SettingsActionBanner.dismissForTests();
        resetPicker();
        SignedInUser.idForTests = null;
        SignedInUser.handleForTests = null;
        SettingsStatus.seenVideoFilterEnabled = false;
        TimeZone.setDefault(originalZone);
        owner.close();
    }

    @Test public void theMountedRowOpensAnAsyncJsonDocumentPickerAndCancellationEnablesItAgain() {
        ImportSeenVideoHistoryPreference row = row();
        assertTrue(row.isEnabled());
        assertTrue(row.getSummary().toString().contains("this phone's time zone"));
        row.getOnPreferenceClickListener().onPreferenceClick(row);
        var request = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(request);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.getAction());
        assertEquals("application/json", request.intent.getType());
        assertTrue(request.intent.getCategories().contains(Intent.CATEGORY_OPENABLE));
        assertEquals(ImportSeenVideoHistoryPreference.REQUEST_IMPORT, request.requestCode & 0xffff);
        assertFalse(row.isEnabled());
        fragment.onActivityResult(ImportSeenVideoHistoryPreference.REQUEST_IMPORT, Activity.RESULT_CANCELED, null);
        assertTrue(row.isEnabled());
    }

    @Test public void pickerRecreationKeepsItsAccountAndPhoneZoneAndRejectsAnotherAccountBeforeReading() throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        ImportSeenVideoHistoryPreference.pickFile(fragment);
        Bundle saved = new Bundle();
        fragment.onSaveInstanceState(saved);
        field("pending").set(null, null);
        SignedInUser.idForTests = "other-account";
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        ImportSeenVideoHistoryPreference.restorePickerState(saved);
        Object pick = field("pending").get(null);
        Field zone = pick.getClass().getDeclaredField("zone");
        zone.setAccessible(true);
        assertEquals("America/New_York", ((TimeZone) zone.get(pick)).getID());
        Intent selected = new Intent().setData(Uri.parse("content://unregistered-provider/not-readable"));
        fragment.onActivityResult(ImportSeenVideoHistoryPreference.REQUEST_IMPORT, Activity.RESULT_OK, selected);
        assertFalse(((AtomicBoolean) field("BUSY").get(null)).get());
        assertTrue(row().isEnabled());
        assertNull(field("pending").get(null));
        View root = activity.findViewById(android.R.id.content);
        TextView message = root.findViewWithTag(SettingsActionBanner.MESSAGE_TAG);
        assertNotNull(message);
        assertEquals("Your TikTok account changed. Choose the file again for this account.",
                message.getText().toString());
    }

    @Test public void aRecreatedPickerRecoversTheSavedAccountWhenTikTokSuppliesItBeforeTheResult() throws Exception {
        Settings.HIDE_SEEN_VIDEOS.save(true);
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(0);
        String originalAccount = SignedInUser.idForTests;
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"));
        ImportSeenVideoHistoryPreference.pickFile(fragment);
        Bundle saved = new Bundle();
        fragment.onSaveInstanceState(saved);
        field("pending").set(null, null);
        SignedInUser.idForTests = null;
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"));
        ImportSeenVideoHistoryPreference.restorePickerState(saved);
        SignedInUser.idForTests = originalAccount;
        Uri uri = reviewedFile();
        fragment.onActivityResult(ImportSeenVideoHistoryPreference.REQUEST_IMPORT, Activity.RESULT_OK,
                new Intent().setData(uri));
        finishWorkers();
        assertTrue(bannerMessage().startsWith("15 watched videos added. 3 entries skipped."));
        assertTrue(SeenVideoHistory.shouldHide("7420104946231577888"));
        Field database = SeenVideoHistory.class.getDeclaredField("database");
        database.setAccessible(true);
        android.database.sqlite.SQLiteDatabase db =
                ((android.database.sqlite.SQLiteOpenHelper) database.get(null)).getReadableDatabase();
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("America/New_York"));
        try (android.database.Cursor cursor = db.rawQuery(
                "SELECT account, last_seen_ms FROM seen_videos WHERE aid = ?",
                new String[]{"7420104946231577888"})) {
            assertTrue(cursor.moveToFirst());
            assertEquals(originalAccount, cursor.getString(0));
            assertEquals(format.parse("2024-12-15 19:07:30").getTime(), cursor.getLong(1));
        }
    }

    @Test public void recoveryAfterAnUnknownAccountStillRejectsADifferentAccountBeforeReading() throws Exception {
        ImportSeenVideoHistoryPreference.pickFile(fragment);
        Bundle saved = new Bundle();
        fragment.onSaveInstanceState(saved);
        field("pending").set(null, null);
        SignedInUser.idForTests = null;
        ImportSeenVideoHistoryPreference.restorePickerState(saved);
        SignedInUser.idForTests = "different-account";
        fragment.onActivityResult(ImportSeenVideoHistoryPreference.REQUEST_IMPORT, Activity.RESULT_OK,
                new Intent().setData(Uri.parse("content://unregistered-provider/not-readable")));
        assertFalse(((AtomicBoolean) field("BUSY").get(null)).get());
        assertEquals("Your TikTok account changed. Choose the file again for this account.", bannerMessage());
    }

    @Test public void importingIsUnavailableWhileSignedOut() {
        SignedInUser.idForTests = null;
        ImportSeenVideoHistoryPreference row = row();
        row.getView(null, null);
        assertFalse(row.isEnabled());
        assertEquals("Sign in to TikTok before importing watch history.", row.getSummary().toString());
        ImportSeenVideoHistoryPreference.pickFile(fragment);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test public void choosingTheReviewedFileShowsAccurateCountsAndRepeatingItAddsNothing() throws Exception {
        Settings.HIDE_SEEN_VIDEOS.save(true);
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(0);
        Uri uri = reviewedFile();
        ImportSeenVideoHistoryPreference.pickFile(fragment);
        fragment.onActivityResult(ImportSeenVideoHistoryPreference.REQUEST_IMPORT, Activity.RESULT_OK,
                new Intent().setData(uri));
        finishWorkers();
        assertTrue(row().isEnabled());
        assertTrue(SeenVideoHistory.shouldHide("7420104946231577888"));
        assertTrue(bannerMessage().startsWith("15 watched videos added. 3 entries skipped."));
        // The same document again: the file app hands it over as often as it's asked.
        ImportSeenVideoHistoryPreference.pickFile(fragment);
        fragment.onActivityResult(ImportSeenVideoHistoryPreference.REQUEST_IMPORT, Activity.RESULT_OK,
                new Intent().setData(uri));
        finishWorkers();
        assertTrue(row().isEnabled());
        assertTrue(bannerMessage().startsWith("0 watched videos added. 18 entries skipped."));
    }

    @Test @Config(qualifiers = "night") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theImportRowAndItsCompletedNoticeRenderInDarkSettings() throws Exception {
        captureCompletedImport("dark");
    }

    @Test @Config(qualifiers = "notnight") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void theImportRowAndItsCompletedNoticeRenderInLightSettings() throws Exception {
        captureCompletedImport("light");
    }

    private void captureCompletedImport(String theme) throws Exception {
        Settings.HIDE_SEEN_VIDEOS.save(true);
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(0);
        // Scroll to Seen videos so the complete import row appears in the image.
        android.widget.ListView list = fragment.getView().findViewById(android.R.id.list);
        assertNotNull("the mounted preference list is missing", list);
        int rowIndex = -1;
        for (int index = 0; index < list.getAdapter().getCount(); index++) {
            if (list.getAdapter().getItem(index) == row()) rowIndex = index;
        }
        assertTrue("the importer is missing from the mounted list", rowIndex >= 0);
        list.setSelection(Math.max(0, rowIndex - 1));
        UiCapture.save(activity.findViewById(android.R.id.content),
                "watch-history/import-settings-" + theme + ".png", 480, 960);
        SeenVideoHistory.onPlayProgressChange("previously-cleared", 5000, 10000);
        finishWorkers();
        ClearSeenVideoHistoryPreference clear = (ClearSeenVideoHistoryPreference)
                fragment.findPreference("action_clear_seen_video_history");
        clear.getOnPreferenceClickListener().onPreferenceClick(clear);
        finishWorkers();
        assertTrue(SeenVideoHistory.canUndo());
        ImportSeenVideoHistoryPreference.pickFile(fragment);
        fragment.onActivityResult(ImportSeenVideoHistoryPreference.REQUEST_IMPORT, Activity.RESULT_OK,
                new Intent().setData(reviewedFile()));
        finishWorkers();
        assertTrue(bannerMessage().startsWith("15 watched videos added. 3 entries skipped."));
        assertTrue(bannerMessage().contains("Importing new history ended Undo for the earlier clear."));
        assertFalse(SeenVideoHistory.canUndo());
        assertEquals("Clear seen videos", clear.getTitle().toString());
        UiCapture.save(activity.findViewById(android.R.id.content),
                "watch-history/import-completed-" + theme + ".png", 480, 960);
    }

    /**
     * The reviewed export as a file app's document. The read goes through the descriptor the file
     * app hands over, so a stream registered with the resolver would never be read.
     */
    private Uri reviewedFile() throws Exception {
        try (java.io.InputStream export = getClass().getResourceAsStream("/seen/reviewed-watch-history.json")) {
            provider = app.morphe.extension.tiktok.DocumentExportProvider.register(activity)
                    .contents(export.readAllBytes());
            return provider.uri;
        }
    }

    /**
     * A file app that keeps the read waiting can be stopped waiting on once it stalls, and the
     * import that never got its file adds nothing.
     */
    @Test public void aReadStoppedWhileTheFileAppHoldsItImportsNothing() throws Exception {
        Settings.HIDE_SEEN_VIDEOS.save(true);
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(0);
        Uri uri = reviewedFile();
        provider.honorCancel = true;
        java.util.concurrent.CountDownLatch release = provider.holdOpens();
        try {
            ImportSeenVideoHistoryPreference.pickFile(fragment);
            fragment.onActivityResult(ImportSeenVideoHistoryPreference.REQUEST_IMPORT, Activity.RESULT_OK,
                    new Intent().setData(uri));
            assertTrue(provider.awaitOpening());
            ImportSeenVideoHistoryPreference row = row();
            assertFalse(row.isEnabled());
            assertEquals("Reading watch history", row.getSummary().toString());
            Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(DocumentOperation.stallMillis));
            assertTrue("the row didn't come back as the way to stop", row.isEnabled());
            assertEquals("Still waiting for the file app. Tap to stop waiting.", row.getSummary().toString());

            row.getOnPreferenceClickListener().onPreferenceClick(row);
            assertEquals("Stopped waiting for the file app. Nothing was imported.", bannerMessage());
            finishWorkers();
            assertEquals("the cancellation never reached the file app", 1, provider.cancels.get());
            assertTrue(provider.handedOut.isEmpty());
            assertFalse("a stopped import added history", SeenVideoHistory.shouldHide("7420104946231577888"));
            assertTrue(row().isEnabled());
            assertTrue(row().getSummary().toString().contains("this phone's time zone"));
            assertFalse(DocumentOperation.busy(DocumentOperation.Kind.WATCH_HISTORY_FILE));
        } finally {
            release.countDown();
            finishWorkers();
        }
    }

    /**
     * A cloud file app hands the file over at once and trickles the data. Stopping closes the
     * descriptor under the read, which wakes it, and the import adds nothing.
     */
    @Test public void aReadStoppedWhileTheFileAppTricklesItImportsNothing() throws Exception {
        Settings.HIDE_SEEN_VIDEOS.save(true);
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(0);
        Uri uri = reviewedFile();
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        SlowTransfers transfers = SlowTransfers.install(provider, release);
        try {
            ImportSeenVideoHistoryPreference.pickFile(fragment);
            fragment.onActivityResult(ImportSeenVideoHistoryPreference.REQUEST_IMPORT, Activity.RESULT_OK,
                    new Intent().setData(uri));
            assertTrue("the read never started", transfers.awaitStarted());
            Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(java.time.Duration.ofMillis(DocumentOperation.stallMillis));
            ImportSeenVideoHistoryPreference row = row();
            assertEquals("Still waiting for the file app. Tap to stop waiting.", row.getSummary().toString());

            row.getOnPreferenceClickListener().onPreferenceClick(row);
            assertEquals("Stopped waiting for the file app. Nothing was imported.", bannerMessage());
            assertFalse("the stop left the read's descriptor open",
                    provider.handedOut.get(0).getFileDescriptor().valid());
            finishWorkers();
            assertEquals("the read's end said something after the stop",
                    "Stopped waiting for the file app. Nothing was imported.", bannerMessage());
            assertFalse("a stopped import added history", SeenVideoHistory.shouldHide("7420104946231577888"));
            assertTrue(row().isEnabled());
            assertTrue(row().getSummary().toString().contains("this phone's time zone"));
            assertFalse(DocumentOperation.busy(DocumentOperation.Kind.WATCH_HISTORY_FILE));
        } finally {
            release.countDown();
            finishWorkers();
            DocumentOperation.streams = null;
        }
    }

    /** An account switch while the file app is slow to hand the file over adds nothing anywhere. */
    @Test public void anAccountSwitchDuringASlowReadImportsNothing() throws Exception {
        Settings.HIDE_SEEN_VIDEOS.save(true);
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(0);
        Uri uri = reviewedFile();
        String chooser = SignedInUser.idForTests;
        String switched = "switched-account-" + System.nanoTime();
        java.util.concurrent.CountDownLatch release = provider.holdOpens();
        try {
            ImportSeenVideoHistoryPreference.pickFile(fragment);
            fragment.onActivityResult(ImportSeenVideoHistoryPreference.REQUEST_IMPORT, Activity.RESULT_OK,
                    new Intent().setData(uri));
            assertTrue(provider.awaitOpening());
            SignedInUser.idForTests = switched;
        } finally {
            release.countDown();
            finishWorkers();
        }
        assertEquals("Your TikTok account changed. Choose the file again for this account.", bannerMessage());
        assertFalse(SeenVideoHistory.shouldHide("7420104946231577888"));
        Field database = SeenVideoHistory.class.getDeclaredField("database");
        database.setAccessible(true);
        Object helper = database.get(null);
        // No database yet means nothing was ever written to one.
        if (helper != null) {
            android.database.sqlite.SQLiteDatabase db =
                    ((android.database.sqlite.SQLiteOpenHelper) helper).getReadableDatabase();
            try (android.database.Cursor cursor = db.rawQuery(
                    "SELECT COUNT(*) FROM seen_videos WHERE account IN (?, ?)", new String[]{chooser, switched})) {
                assertTrue(cursor.moveToFirst());
                assertEquals("the import landed after the account changed", 0, cursor.getInt(0));
            }
        }
        assertTrue(row().isEnabled());
    }

    private String bannerMessage() {
        View root = activity.getWindow().getDecorView();
        TextView message = root.findViewWithTag(SettingsActionBanner.MESSAGE_TAG);
        assertNotNull("the import outcome didn't reach the action banner", message);
        return message.getText().toString();
    }

    private static void finishWorkers() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Field worker = SeenVideoHistory.class.getDeclaredField("IO");
        worker.setAccessible(true);
        ((ExecutorService) worker.get(null)).submit(() -> {}).get(15, TimeUnit.SECONDS);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
    }

    private ImportSeenVideoHistoryPreference row() {
        ImportSeenVideoHistoryPreference row = (ImportSeenVideoHistoryPreference)
                fragment.findPreference("action_import_seen_video_history");
        assertNotNull("the Seen videos section has no import action", row);
        return row;
    }

    private static Field field(String name) throws Exception {
        Field field = ImportSeenVideoHistoryPreference.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void resetPicker() throws Exception {
        field("pending").set(null, null);
        field("busyLine").set(null, null);
        field("reading").set(null, null);
        ((AtomicBoolean) field("BUSY").get(null)).set(false);
    }
}
