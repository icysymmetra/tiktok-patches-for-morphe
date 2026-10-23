package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.DocumentExportProvider;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.seen.SeenHistoryFile;
import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsPagesTest;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

/**
 * Save seen history to a file and Restore seen history from a file, mounted on the Feed filter
 * page: each acts on the account that chose the file, a stop before the gate changes nothing,
 * and a save the file app already has stays open until the write returns.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class SeenHistoryFilePreferenceTest {
    private static final String STOP_OFFER = "Still waiting for the file app. Tap to stop waiting.";
    private static final String ACCOUNT_CHANGED = "Your TikTok account changed. Choose the file again for this account.";
    private static final String HEADER =
            "{\"format\":\"hushfeed-seen-history\",\"schema\":1,\"exported_at\":1700000001000,\"videos\":[";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    /** Every hold a test makes, let go afterwards so a failed test can't keep the slot for the next. */
    private final List<CountDownLatch> holds = new ArrayList<>();
    private ActivityController<SettingsPagesTest.PageActivity> owner;
    private Activity activity;
    private TikTokPreferenceFragment fragment;
    private String account;

    @Before public void setUp() throws Exception {
        resetRows();
        account = "seen-file-" + System.nanoTime();
        SignedInUser.idForTests = account;
        SettingsStatus.seenVideoFilterEnabled = true;
        Settings.HIDE_SEEN_VIDEOS.save(true);
        Settings.SEEN_VIDEO_RETENTION_DAYS.save(0);
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
        for (CountDownLatch hold : holds) hold.countDown();
        finishWorkers();
        DocumentOperation.streams = null;
        assertFalse("a test left a seen-history file worker out",
                DocumentOperation.busy(DocumentOperation.Kind.SEEN_HISTORY_FILE));
        SettingsActionBanner.dismissForTests();
        resetRows();
        SignedInUser.idForTests = null;
        SignedInUser.handleForTests = null;
        SettingsStatus.seenVideoFilterEnabled = false;
        owner.close();
    }

    @Test public void bothRowsSitBetweenImportAndClearAndOpenTheirOwnPickers() {
        ListView list = fragment.getView().findViewById(android.R.id.list);
        assertNotNull("the mounted preference list is missing", list);
        int imported = position(list, "action_import_seen_video_history");
        int save = position(list, SeenHistoryFilePreference.SAVE_KEY);
        int restore = position(list, SeenHistoryFilePreference.RESTORE_KEY);
        int clear = position(list, "action_clear_seen_video_history");
        assertTrue(imported >= 0 && imported < save && save < restore && restore < clear);

        SeenHistoryFilePreference saveRow = row(SeenHistoryFilePreference.SAVE_KEY);
        assertTrue(saveRow.isEnabled());
        assertTrue(saveRow.getSummary().toString().endsWith(
                "Anyone who opens the file can see that viewing history."));
        saveRow.getOnPreferenceClickListener().onPreferenceClick(saveRow);
        var request = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(request);
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, request.intent.getAction());
        assertEquals("application/json", request.intent.getType());
        assertTrue(request.intent.getCategories().contains(Intent.CATEGORY_OPENABLE));
        String name = request.intent.getStringExtra(Intent.EXTRA_TITLE);
        assertTrue(name, name.startsWith("metra-seen-history-") && name.endsWith(".json"));
        assertFalse("the suggested name gives the account away", name.contains(account));
        assertEquals(SeenHistoryFilePreference.REQUEST_SAVE, request.requestCode & 0xffff);
        assertFalse("a second picker could open over the first", saveRow.isEnabled());
        assertFalse(row(SeenHistoryFilePreference.RESTORE_KEY).isEnabled());
        fragment.onActivityResult(SeenHistoryFilePreference.REQUEST_SAVE, Activity.RESULT_CANCELED, null);
        assertTrue(saveRow.isEnabled());

        SeenHistoryFilePreference restoreRow = row(SeenHistoryFilePreference.RESTORE_KEY);
        assertTrue(restoreRow.getSummary().toString().endsWith("A video in both keeps the newer date."));
        restoreRow.getOnPreferenceClickListener().onPreferenceClick(restoreRow);
        request = Shadows.shadowOf(activity).getNextStartedActivityForResult();
        assertNotNull(request);
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, request.intent.getAction());
        assertEquals("application/json", request.intent.getType());
        assertTrue((request.intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertEquals(SeenHistoryFilePreference.REQUEST_RESTORE, request.requestCode & 0xffff);
        fragment.onActivityResult(SeenHistoryFilePreference.REQUEST_RESTORE, Activity.RESULT_CANCELED, null);
        assertResting();
    }

    @Test public void bothRowsWaitForASignedInAccount() {
        SignedInUser.idForTests = null;
        for (String key : new String[]{SeenHistoryFilePreference.SAVE_KEY, SeenHistoryFilePreference.RESTORE_KEY}) {
            SeenHistoryFilePreference row = row(key);
            row.getView(null, null);
            assertFalse(row.isEnabled());
            assertEquals("Sign in to TikTok before saving or restoring seen history.", row.getSummary().toString());
        }
        SeenHistoryFilePreference.pickFile(fragment, SeenHistoryFilePreference.REQUEST_SAVE);
        SeenHistoryFilePreference.pickFile(fragment, SeenHistoryFilePreference.REQUEST_RESTORE);
        assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
    }

    @Test public void savingWritesTheAccountsSeenVideosAndNothingThatNamesIt() throws Exception {
        SignedInUser.handleForTests = "saver_handle";
        seed("{\"id\":\"7555000000000000101\",\"seen\":1700000000300}",
                "{\"id\":\"7555000000000000102\",\"seen\":1700000000200}",
                "{\"id\":\"7555000000000000103\",\"seen\":1700000000100}");
        DocumentExportProvider provider = DocumentExportProvider.register(activity);
        SeenHistoryFilePreference.pickFile(fragment, SeenHistoryFilePreference.REQUEST_SAVE);
        fragment.onActivityResult(SeenHistoryFilePreference.REQUEST_SAVE, Activity.RESULT_OK,
                new Intent().setData(provider.uri));
        finishWorkers();

        assertEquals("Saved 3 seen videos to the file", bannerMessage());
        byte[] written = Files.readAllBytes(provider.file.toPath());
        String text = new String(written, StandardCharsets.UTF_8);
        assertFalse("the file names the account", text.contains(account));
        assertFalse("the file names the handle", text.contains("saver_handle"));
        assertEquals(Map.of("7555000000000000101", 1700000000300L,
                        "7555000000000000102", 1700000000200L,
                        "7555000000000000103", 1700000000100L),
                SeenHistoryFile.read(new ByteArrayInputStream(written)).videos);
        assertEquals(0, provider.deleteCalls);
        assertResting();
    }

    @Test public void restoringAddsTheFilesVideosAndSaysWhatWasAlreadyThere() throws Exception {
        seed("{\"id\":\"7555000000000000201\",\"seen\":1700000000500}");
        DocumentExportProvider provider = fileWith(
                "{\"id\":\"7555000000000000201\",\"seen\":1700000000400}",
                "{\"id\":\"7555000000000000202\",\"seen\":1700000000300}",
                "{\"id\":\"7555000000000000202\",\"seen\":1700000000200}",
                "{\"id\":\"7555000000000000203\",\"seen\":1700000000100}");
        SeenHistoryFilePreference.pickFile(fragment, SeenHistoryFilePreference.REQUEST_RESTORE);
        fragment.onActivityResult(SeenHistoryFilePreference.REQUEST_RESTORE, Activity.RESULT_OK,
                new Intent().setData(provider.uri));
        finishWorkers();

        assertEquals("the repeated 202 is one video, not one already recorded",
                "Added 2 seen videos. 1 video was already recorded.", bannerMessage());
        assertTrue(SeenVideoHistory.shouldHide("7555000000000000202"));
        assertTrue(SeenVideoHistory.shouldHide("7555000000000000203"));
        assertEquals(1700000000500L, lastSeen(account, "7555000000000000201"));
        assertEquals(1700000000300L, lastSeen(account, "7555000000000000202"));
        assertResting();
    }

    @Test public void aFileThatIsntOursChangesNothingAndSaysWhereTikToksExportGoes() throws Exception {
        DocumentExportProvider provider = DocumentExportProvider.register(activity)
                .contents("{\"Activity\":{\"Video Browsing History\":{\"VideoList\":[]}}}"
                        .getBytes(StandardCharsets.UTF_8));
        SeenHistoryFilePreference.pickFile(fragment, SeenHistoryFilePreference.REQUEST_RESTORE);
        fragment.onActivityResult(SeenHistoryFilePreference.REQUEST_RESTORE, Activity.RESULT_OK,
                new Intent().setData(provider.uri));
        finishWorkers();
        assertEquals("That isn't a seen-history file saved by Metra. "
                + "For TikTok's own data export, use Import watch history.", bannerMessage());
        assertEquals(0, rowsFor(account));
        assertResting();
    }

    /**
     * A cloud file app hands the file over and trickles the data. Stopping closes the descriptor
     * under the read, and the restore that never passed its gate merges nothing.
     */
    @Test public void aRestoreStoppedWhileTheFileAppTricklesItChangesNoAccount() throws Exception {
        DocumentExportProvider provider = fileWith("{\"id\":\"7555000000000000301\",\"seen\":1700000000100}");
        SlowTransfers transfers = SlowTransfers.install(provider, hold());
        SeenHistoryFilePreference.pickFile(fragment, SeenHistoryFilePreference.REQUEST_RESTORE);
        fragment.onActivityResult(SeenHistoryFilePreference.REQUEST_RESTORE, Activity.RESULT_OK,
                new Intent().setData(provider.uri));
        assertTrue("the read never started", transfers.awaitStarted());
        SeenHistoryFilePreference restore = row(SeenHistoryFilePreference.RESTORE_KEY);
        assertFalse(restore.isEnabled());
        assertEquals("Reading the seen-history file", restore.getSummary().toString());
        stall();
        assertTrue("the row didn't come back as the way to stop", restore.isEnabled());
        assertEquals(STOP_OFFER, restore.getSummary().toString());
        assertFalse(row(SeenHistoryFilePreference.SAVE_KEY).isEnabled());

        restore.getOnPreferenceClickListener().onPreferenceClick(restore);
        assertEquals("Stopped waiting for the file app. Nothing was changed.", bannerMessage());
        finishWorkers();
        assertEquals("the read's end said something after the stop",
                "Stopped waiting for the file app. Nothing was changed.", bannerMessage());
        assertFalse(SeenVideoHistory.shouldHide("7555000000000000301"));
        assertEquals(0, rowsFor(account));
        assertResting();
    }

    /** An account switch while the file app is slow to hand the file over restores into no account. */
    @Test public void anAccountSwitchDuringASlowReadRestoresIntoNoAccount() throws Exception {
        DocumentExportProvider provider = fileWith("{\"id\":\"7555000000000000401\",\"seen\":1700000000100}");
        String switched = "switched-" + System.nanoTime();
        CountDownLatch release = provider.holdOpens();
        holds.add(release);
        SeenHistoryFilePreference.pickFile(fragment, SeenHistoryFilePreference.REQUEST_RESTORE);
        fragment.onActivityResult(SeenHistoryFilePreference.REQUEST_RESTORE, Activity.RESULT_OK,
                new Intent().setData(provider.uri));
        assertTrue(provider.awaitOpening());
        SignedInUser.idForTests = switched;
        release.countDown();
        finishWorkers();

        assertEquals(ACCOUNT_CHANGED, bannerMessage());
        assertEquals("the restore landed in the account that chose the file", 0, rowsFor(account));
        assertEquals("the restore landed in the account signed in now", 0, rowsFor(switched));
        assertResting();
    }

    /** A picker that comes back to a different account touches no file and drops the one it made. */
    @Test public void aPickerThatReturnsToAnotherAccountDropsTheEmptyFileItMade() throws Exception {
        DocumentExportProvider provider = DocumentExportProvider.register(activity);
        SeenHistoryFilePreference.pickFile(fragment, SeenHistoryFilePreference.REQUEST_SAVE);
        Bundle saved = new Bundle();
        fragment.onSaveInstanceState(saved);
        field("pending").set(null, null);
        SignedInUser.idForTests = "other-" + System.nanoTime();
        SeenHistoryFilePreference.restorePickerState(saved);
        fragment.onActivityResult(SeenHistoryFilePreference.REQUEST_SAVE, Activity.RESULT_OK,
                new Intent().setData(provider.uri));
        assertEquals(ACCOUNT_CHANGED, bannerMessage());
        assertTrue("the picker's empty file was left behind", provider.awaitDeletion());
        assertEquals(0, provider.openCalls.get());
        assertFalse(((AtomicBoolean) field("BUSY").get(null)).get());
        assertResting();
    }

    /**
     * Stopped while the history is still being read: the file app never gets anything, the stop
     * says so, and the empty file the picker made goes.
     */
    @Test public void aSaveStoppedBeforeTheFileAppHasItExportsNothing() throws Exception {
        DocumentExportProvider provider = DocumentExportProvider.register(activity);
        CountDownLatch history = hold();
        historyWorker().execute(() -> {
            try {
                history.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        SeenHistoryFilePreference.pickFile(fragment, SeenHistoryFilePreference.REQUEST_SAVE);
        fragment.onActivityResult(SeenHistoryFilePreference.REQUEST_SAVE, Activity.RESULT_OK,
                new Intent().setData(provider.uri));
        stall();
        SeenHistoryFilePreference save = row(SeenHistoryFilePreference.SAVE_KEY);
        assertEquals(STOP_OFFER, save.getSummary().toString());

        save.getOnPreferenceClickListener().onPreferenceClick(save);
        assertEquals("Stopped waiting for the file app. Nothing was exported.", bannerMessage());
        history.countDown();
        finishWorkers();
        assertEquals("Stopped waiting for the file app. Nothing was exported.", bannerMessage());
        assertEquals("the file app was handed the save after the stop", 0, provider.openCalls.get());
        assertEquals("the picker's empty file was left behind", 1, provider.deleteCalls);
        assertResting();
    }

    /**
     * The file app has the save's file and takes the bytes slowly. Stopping can't say whether it
     * kept them, and when the write fails on the closed descriptor the save is called unsaved and
     * its partly written file goes.
     */
    @Test public void aSaveStoppedAfterTheFileAppHasItStaysOpenUntilTheWriteReturns() throws Exception {
        seed("{\"id\":\"7555000000000000501\",\"seen\":1700000000100}");
        DocumentExportProvider provider = DocumentExportProvider.register(activity);
        SlowTransfers transfers = SlowTransfers.install(provider, hold());
        LogBufferManager.clearLogBuffer();
        SeenHistoryFilePreference.pickFile(fragment, SeenHistoryFilePreference.REQUEST_SAVE);
        fragment.onActivityResult(SeenHistoryFilePreference.REQUEST_SAVE, Activity.RESULT_OK,
                new Intent().setData(provider.uri));
        assertTrue("the write never started", transfers.awaitStarted());
        stall();
        SeenHistoryFilePreference save = row(SeenHistoryFilePreference.SAVE_KEY);
        assertEquals(STOP_OFFER, save.getSummary().toString());

        save.getOnPreferenceClickListener().onPreferenceClick(save);
        assertEquals("Stopped waiting for the file app. It hasn't said yet whether the export was saved.",
                bannerMessage());
        finishWorkers();
        assertEquals("The export wasn't saved. Export again when the file app is ready.", bannerMessage());
        DocumentOperationTest.assertStopLoggedAsInfo("Could not save the seen-history file");
        assertEquals("the partly written file was left behind", 1, provider.deleteCalls);
        assertFalse(provider.exists);
        assertResting();
    }

    private void assertResting() {
        SeenHistoryFilePreference save = row(SeenHistoryFilePreference.SAVE_KEY);
        SeenHistoryFilePreference restore = row(SeenHistoryFilePreference.RESTORE_KEY);
        assertTrue(save.isEnabled());
        assertTrue(restore.isEnabled());
        String handle = SignedInUser.handle();
        assertEquals(handle != null
                        ? "Save the videos @" + handle + " has seen, and when, to a JSON file. "
                        + "Anyone who opens the file can see that viewing history."
                        : "Save this account's seen videos, and when they were watched, to a JSON file. "
                        + "Anyone who opens the file can see that viewing history.",
                save.getSummary().toString());
        assertEquals(handle != null
                        ? "Add a seen-history file saved by Metra to the videos @" + handle + " has seen. "
                        + "A video in both keeps the newer date."
                        : "Add a seen-history file saved by Metra to this account's seen videos. "
                        + "A video in both keeps the newer date.",
                restore.getSummary().toString());
        assertFalse(DocumentOperation.busy(DocumentOperation.Kind.SEEN_HISTORY_FILE));
    }

    /** Adds rows to the signed-in account through the merge Restore uses. */
    private static void seed(String... rows) throws Exception {
        SeenHistoryFile.Contents contents = SeenHistoryFile.read(new ByteArrayInputStream(
                (HEADER + String.join(",", rows) + "]}").getBytes(StandardCharsets.UTF_8)));
        AtomicReference<SeenVideoHistory.ImportResult> result = new AtomicReference<>();
        SeenVideoHistory.importHistory(SeenVideoHistory.captureImportTarget(), contents.records(), result::set);
        finishWorkers();
        assertNotNull("the seed never answered", result.get());
        assertEquals(SeenVideoHistory.ImportStatus.IMPORTED, result.get().status);
    }

    /** A seen-history file as a file app's document, read through the descriptor it hands over. */
    private DocumentExportProvider fileWith(String... rows) throws Exception {
        return DocumentExportProvider.register(activity)
                .contents((HEADER + String.join(",", rows) + "]}").getBytes(StandardCharsets.UTF_8));
    }

    private CountDownLatch hold() {
        CountDownLatch release = new CountDownLatch(1);
        holds.add(release);
        return release;
    }

    private static void stall() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DocumentOperation.stallMillis));
    }

    private String bannerMessage() {
        View root = activity.getWindow().getDecorView();
        TextView message = root.findViewWithTag(SettingsActionBanner.MESSAGE_TAG);
        assertNotNull("nothing reached the action banner", message);
        return message.getText().toString();
    }

    private static void finishWorkers() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        historyWorker().submit(() -> { }).get(15, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    private static ExecutorService historyWorker() throws Exception {
        Field worker = SeenVideoHistory.class.getDeclaredField("IO");
        worker.setAccessible(true);
        return (ExecutorService) worker.get(null);
    }

    private static android.database.sqlite.SQLiteDatabase database() throws Exception {
        Field database = SeenVideoHistory.class.getDeclaredField("database");
        database.setAccessible(true);
        Object helper = database.get(null);
        // No database yet means nothing was ever written to one.
        return helper == null ? null
                : ((android.database.sqlite.SQLiteOpenHelper) helper).getReadableDatabase();
    }

    private static int rowsFor(String account) throws Exception {
        android.database.sqlite.SQLiteDatabase db = database();
        if (db == null) return 0;
        try (android.database.Cursor cursor = db.rawQuery(
                "SELECT COUNT(*) FROM seen_videos WHERE account = ?", new String[]{account})) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }

    private static long lastSeen(String account, String aid) throws Exception {
        try (android.database.Cursor cursor = database().rawQuery(
                "SELECT last_seen_ms FROM seen_videos WHERE account = ? AND aid = ?", new String[]{account, aid})) {
            assertTrue("no row for " + aid, cursor.moveToFirst());
            return cursor.getLong(0);
        }
    }

    private SeenHistoryFilePreference row(String key) {
        SeenHistoryFilePreference row = (SeenHistoryFilePreference) fragment.findPreference(key);
        assertNotNull("the Seen videos section has no " + key + " row", row);
        return row;
    }

    private int position(ListView list, String key) {
        Object wanted = fragment.findPreference(key);
        assertNotNull("the Seen videos section has no " + key + " row", wanted);
        for (int index = 0; index < list.getAdapter().getCount(); index++) {
            if (list.getAdapter().getItem(index) == wanted) return index;
        }
        return -1;
    }

    private static Field field(String name) throws Exception {
        Field field = SeenHistoryFilePreference.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static void resetRows() throws Exception {
        field("pending").set(null, null);
        field("busyLine").set(null, null);
        field("operation").set(null, null);
        field("busyRequest").setInt(null, 0);
        ((AtomicBoolean) field("BUSY").get(null)).set(false);
    }
}
