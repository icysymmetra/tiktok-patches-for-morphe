package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.preference.Preference;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.DocumentExportProvider;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsBackup;
import app.morphe.extension.tiktok.settings.SettingsPagesTest;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.robolectric.shadows.ShadowToast;

/**
 * A file app that keeps Backup or Restore waiting can be stopped waiting on, and stopping keeps
 * every promise the rows make: nothing changes before the gate, a backup the file app already has
 * is reported once it answers, and a worker the file app won't let go of holds its kind's one
 * slot instead of piling up more workers and open files behind it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class DocumentOperationTest {
    private static final String BACKUP = "settings_backup_7311";
    private static final String RESTORE = "settings_backup_7312";
    private static final String RESET = "settings_backup_7313";
    private static final String UNDO = "settings_backup_7314";
    private static final String STOP_OFFER = "Still waiting for the file app. Tap to stop waiting.";
    private static final String HELD =
            "Waiting for the file app to let go of the last file. Restart TikTok if it doesn't.";
    private static final String SLOT_REFUSAL =
            "The file app still has the last file. Try again once it lets go, or restart TikTok.";

    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    /** Every hold a test makes, let go afterwards so a failed test can't keep a slot for the next. */
    private final List<CountDownLatch> holds = new ArrayList<>();
    private ActivityController<SettingsPagesTest.PageActivity> owner;
    private Activity activity;

    @Before public void setUp() {
        for (Setting<?> setting : Setting.allLoadedSettings()) setting.resetToDefault();
        owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible();
        activity = owner.get();
        Utils.setContext(activity);
        new File(activity.getApplicationContext().getFilesDir(), "hushfeed-settings-undo.json").delete();
        ShadowToast.reset();
    }

    @After public void tearDown() throws Exception {
        for (CountDownLatch hold : holds) hold.countDown();
        Utils.awaitBackgroundTasksForTests();
        DocumentOperation.streams = null;
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse("a test left a settings file worker out",
                DocumentOperation.busy(DocumentOperation.Kind.SETTINGS_FILE));
        owner.close();
    }

    @Test public void stoppingBeforeTheGateLeavesTheWorkNothingToPass() throws Exception {
        CountDownLatch release = hold();
        AtomicReference<Boolean> passed = new AtomicReference<>();
        DocumentOperation operation = DocumentOperation.start(DocumentOperation.Kind.SETTINGS_FILE, work -> {
            await(release);
            passed.set(work.commit());
        }, null);
        assertNotNull(operation);

        assertEquals(DocumentOperation.Stage.STOPPED, operation.stop());
        assertTrue("a stopped worker gave up its slot before returning",
                DocumentOperation.heldAfterStop(DocumentOperation.Kind.SETTINGS_FILE));
        release.countDown();
        Utils.awaitBackgroundTasksForTests();

        assertEquals("the work passed the gate after the stop", Boolean.FALSE, passed.get());
        assertEquals(DocumentOperation.Stage.STOPPED, operation.stage());
        assertFalse(DocumentOperation.busy(DocumentOperation.Kind.SETTINGS_FILE));
    }

    @Test public void aChangeThatPassedTheGateCannotBeStoppedAndFinishes() throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        CountDownLatch release = hold();
        AtomicBoolean passed = new AtomicBoolean();
        DocumentOperation operation = DocumentOperation.start(DocumentOperation.Kind.SETTINGS_FILE, work -> {
            passed.set(work.commit());
            committed.countDown();
            await(release);
        }, null);
        assertTrue(committed.await(5, TimeUnit.SECONDS));
        assertTrue(passed.get());

        assertEquals("a stop undid a change already being made",
                DocumentOperation.Stage.COMMITTING, operation.stop());
        assertFalse(operation.isStopped());
        assertFalse("the row offered a stop it can't keep", operation.offersStop());
        release.countDown();
        Utils.awaitBackgroundTasksForTests();
        assertEquals(DocumentOperation.Stage.DONE, operation.stage());
    }

    @Test public void stoppingAfterTheHandoverStaysOpenUntilTheWorkerReturns() throws Exception {
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch release = hold();
        DocumentOperation operation = DocumentOperation.start(DocumentOperation.Kind.SETTINGS_FILE, work -> {
            work.publish();
            published.countDown();
            await(release);
            work.finish();
        }, null);
        assertTrue(published.await(5, TimeUnit.SECONDS));

        assertEquals(DocumentOperation.Stage.STOPPED_WHILE_PUBLISHING, operation.stop());
        assertTrue(DocumentOperation.heldAfterStop(DocumentOperation.Kind.SETTINGS_FILE));
        release.countDown();
        Utils.awaitBackgroundTasksForTests();
        assertEquals("the worker's finish or return overwrote how it was stopped",
                DocumentOperation.Stage.STOPPED_WHILE_PUBLISHING, operation.stage());
        assertFalse(DocumentOperation.busy(DocumentOperation.Kind.SETTINGS_FILE));
    }

    /**
     * Once the file app has taken the whole backup, it's saved, and a stop tapped before the
     * worker gets to say so can't call the outcome open: that used to follow "Settings backup
     * saved" with "It hasn't said yet whether the backup was saved".
     */
    @Test public void aBackupTheFileAppHasTakenCanNoLongerBeStopped() throws Exception {
        CountDownLatch finished = new CountDownLatch(1);
        CountDownLatch release = hold();
        AtomicInteger changes = new AtomicInteger();
        DocumentOperation operation = DocumentOperation.start(DocumentOperation.Kind.SETTINGS_FILE, work -> {
            work.publish();
            work.finish();
            finished.countDown();
            await(release);
        }, changes::incrementAndGet);
        assertTrue(finished.await(5, TimeUnit.SECONDS));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("the rows weren't told the backup was taken", changes.get() >= 2);
        stall();

        assertFalse("the row offered a stop after the backup was saved", operation.offersStop());
        assertEquals(DocumentOperation.Stage.DONE, operation.stop());
        assertFalse(operation.isStopped());
        assertFalse(DocumentOperation.heldAfterStop(DocumentOperation.Kind.SETTINGS_FILE));
        release.countDown();
        Utils.awaitBackgroundTasksForTests();
        assertEquals(DocumentOperation.Stage.DONE, operation.stage());
    }

    @Test public void eachKindHasOneWorkerAndTheStopIsOfferedOnlyOnceTheFileAppStalls() throws Exception {
        CountDownLatch release = hold();
        AtomicInteger changes = new AtomicInteger();
        DocumentOperation operation = DocumentOperation.start(DocumentOperation.Kind.SETTINGS_FILE,
                work -> await(release), changes::incrementAndGet);
        assertNotNull(operation);
        assertNull("a second worker started beside the first",
                DocumentOperation.start(DocumentOperation.Kind.SETTINGS_FILE, work -> { }, null));
        DocumentOperation other = DocumentOperation.start(DocumentOperation.Kind.WATCH_HISTORY_FILE, work -> { }, null);
        assertNotNull("one kind's worker held up the other kind", other);

        assertFalse(operation.offersStop());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DocumentOperation.stallMillis - 1));
        assertFalse("the stop was offered before the file app had stalled", operation.offersStop());
        int before = changes.get();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertTrue(operation.offersStop());
        assertTrue("the rows weren't told about the stall", changes.get() > before);

        operation.stop();
        assertFalse("a stopped operation still offered the stop", operation.offersStop());
        release.countDown();
        Utils.awaitBackgroundTasksForTests();
    }

    /** A cooperative file app gives up when it's told to, and the restore changes nothing. */
    @Test public void aRestoreStoppedWhileTheFileAppHoldsItChangesNothing() throws Exception {
        TikTokPreferenceFragment fragment = attachBackupPage();
        DocumentExportProvider provider = restoreSource();
        provider.honorCancel = true;
        CountDownLatch release = holdOpens(provider);
        fragment.onActivityResult(7312, Activity.RESULT_OK, new Intent().setData(provider.uri));
        assertTrue(provider.awaitOpening());

        Preference restore = fragment.findPreference(RESTORE);
        assertFalse(restore.isEnabled());
        assertEquals("Restoring your settings", String.valueOf(restore.getSummary()));
        stall();
        assertTrue("the acting row didn't come back as the way to stop", restore.isEnabled());
        assertEquals(STOP_OFFER, String.valueOf(restore.getSummary()));
        assertFalse(fragment.findPreference(BACKUP).isEnabled());
        assertFalse(fragment.findPreference(RESET).isEnabled());

        restore.getOnPreferenceClickListener().onPreferenceClick(restore);
        assertEquals("Stopped waiting for the file app. Nothing was changed.", ShadowToast.getTextOfLatestToast());
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("the cancellation never reached the file app", 1, provider.cancels.get());
        assertTrue("the file app handed something over after giving up", provider.handedOut.isEmpty());
        assertEquals("a stopped restore changed settings", 73, (int) Settings.MAX_VIDEO_SECONDS.get());
        assertFalse("a stopped restore saved an Undo copy", SettingsBackup.hasUndo(activity));
        assertFalse(AbstractPreferenceFragment.settingImportInProgress);
        assertResting(fragment);
        release.countDown();
    }

    /**
     * A file app that has hung ignores the cancellation. The rows come back at once, but its
     * worker keeps the one slot until it lets go, so trying again can't start a second worker
     * or open a second file, and the file it finally hands over is closed unread.
     */
    @Test public void aFileAppThatIgnoresTheStopKeepsTheSlotAndItsLateFileIsClosedUnread() throws Exception {
        TikTokPreferenceFragment fragment = attachBackupPage();
        DocumentExportProvider provider = restoreSource();
        CountDownLatch release = holdOpens(provider);
        fragment.onActivityResult(7312, Activity.RESULT_OK, new Intent().setData(provider.uri));
        assertTrue(provider.awaitOpening());
        stall();
        Preference restore = fragment.findPreference(RESTORE);
        restore.getOnPreferenceClickListener().onPreferenceClick(restore);
        assertEquals("Stopped waiting for the file app. Nothing was changed.", ShadowToast.getTextOfLatestToast());
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        for (String key : new String[]{BACKUP, RESTORE}) {
            Preference row = fragment.findPreference(key);
            assertFalse(key + " was offered while the file app still had the last file", row.isEnabled());
            assertEquals(HELD, String.valueOf(row.getSummary()));
        }
        assertTrue("Reset needs no file app and was held back", fragment.findPreference(RESET).isEnabled());
        for (int attempt = 0; attempt < 3; attempt++) {
            ShadowToast.reset();
            fragment.onActivityResult(7312, Activity.RESULT_OK, new Intent().setData(provider.uri));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(SLOT_REFUSAL, ShadowToast.getTextOfLatestToast());
        }
        assertEquals("trying again opened the file a second time", 1, provider.openCalls.get());

        release.countDown();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, provider.handedOut.size());
        assertFalse("the file handed over after the stop was left open",
                provider.handedOut.get(0).getFileDescriptor().valid());
        assertEquals("a stopped restore changed settings", 73, (int) Settings.MAX_VIDEO_SECONDS.get());
        assertResting(fragment);

        ShadowToast.reset();
        fragment.onActivityResult(7312, Activity.RESULT_OK, new Intent().setData(provider.uri));
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Settings restored. Restart TikTok to apply all changes.", ShadowToast.getTextOfLatestToast());
        assertEquals(7, (int) Settings.MAX_VIDEO_SECONDS.get());
    }

    /**
     * A backup the slot refuses removes the empty file the picker made for it, but a file the
     * user chose to replace is left as it was: nothing was written to it.
     */
    @Test public void aHeldSlotRefusesABackupAndRemovesOnlyAnEmptyNewDocument() throws Exception {
        TikTokPreferenceFragment fragment = attachBackupPage();
        DocumentExportProvider provider = restoreSource();
        byte[] chosen = Files.readAllBytes(provider.file.toPath());
        CountDownLatch release = holdOpens(provider);
        fragment.onActivityResult(7312, Activity.RESULT_OK, new Intent().setData(provider.uri));
        assertTrue(provider.awaitOpening());
        stall();
        Preference restore = fragment.findPreference(RESTORE);
        restore.getOnPreferenceClickListener().onPreferenceClick(restore);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        ShadowToast.reset();
        fragment.onActivityResult(7311, Activity.RESULT_OK, new Intent().setData(provider.uri));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(SLOT_REFUSAL, ShadowToast.getTextOfLatestToast());
        awaitBackupCleanup();
        assertEquals("a refused backup removed the file the user chose to replace", 0, provider.deleteCalls);
        assertArrayEquals(chosen, Files.readAllBytes(provider.file.toPath()));

        provider.contents(new byte[0]);
        fragment.onActivityResult(7311, Activity.RESULT_OK, new Intent().setData(provider.uri));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(SLOT_REFUSAL, ShadowToast.getTextOfLatestToast());
        assertTrue("the refused backup left the picker's new file behind", provider.awaitDeletion());
        assertFalse(provider.exists);
        assertEquals(1, provider.deleteCalls);

        release.countDown();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(provider.handedOut.isEmpty());
        assertResting(fragment);
    }

    @Test public void aBackupStoppedAfterTheHandoverSaysSoThenHowItEndedWhenTheFileAppGivesUp() throws Exception {
        assertBackupStoppedAfterTheHandover(true);
    }

    @Test public void aBackupStoppedAfterTheHandoverSaysSoThenHowItEndedWhenTheFileAppHangs() throws Exception {
        assertBackupStoppedAfterTheHandover(false);
    }

    /**
     * Stopping can't say whether a file app that already has the backup kept it, so the row says
     * that much and the worker reports the end once the file app answers: here it never got the
     * bytes, so the empty file is removed and the backup is called unsaved.
     */
    private void assertBackupStoppedAfterTheHandover(boolean cooperative) throws Exception {
        TikTokPreferenceFragment fragment = attachBackupPage();
        DocumentExportProvider provider = DocumentExportProvider.register(activity);
        provider.honorCancel = cooperative;
        CountDownLatch release = holdOpens(provider);
        LogBufferManager.clearLogBuffer();
        fragment.onActivityResult(7311, Activity.RESULT_OK, new Intent().setData(provider.uri));
        assertTrue(provider.awaitOpening());
        stall();
        Preference backup = fragment.findPreference(BACKUP);
        assertEquals(STOP_OFFER, String.valueOf(backup.getSummary()));
        backup.getOnPreferenceClickListener().onPreferenceClick(backup);
        assertEquals("Stopped waiting for the file app. It hasn't said yet whether the backup was saved.",
                ShadowToast.getTextOfLatestToast());
        if (cooperative) {
            // A file app that honours the stop gives up by itself, so the worker is back before
            // the open is let go. Letting it go first would race the file app's own check.
            Utils.awaitBackgroundTasksForTests();
            assertEquals("the cancellation never reached the file app", 1, provider.cancels.get());
        } else {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(HELD, String.valueOf(fragment.findPreference(BACKUP).getSummary()));
        }

        release.countDown();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("The backup wasn't saved. Back up again when the file app is ready.",
                ShadowToast.getTextOfLatestToast());
        assertEquals("the backup's own empty file was left behind", 1, provider.deleteCalls);
        assertFalse(provider.exists);
        for (ParcelFileDescriptor late : provider.handedOut) {
            assertFalse("the file handed over after the stop was left open", late.getFileDescriptor().valid());
        }
        assertEquals(cooperative ? 0 : 1, provider.handedOut.size());
        assertResting(fragment);
        assertStopLoggedAsInfo("Settings backup operation failed");
    }

    /**
     * The stop that ended the work is the user's own choice, so its line is info. As a patch error
     * it showed the debug error toast over the row's own answer and counted in the error report.
     * The log is cleared before the run, so the one line is this run's.
     */
    public static void assertStopLoggedAsInfo(String message) {
        int lines = 0;
        for (String line : LogBufferManager.buildExportText().split("\n")) {
            if (!line.contains(message)) continue;
            lines++;
            assertTrue("the stop was logged as a patch error: " + line, line.contains("| INFO | " + message));
        }
        assertEquals("the stop left no line in the log", 1, lines);
    }

    /**
     * Settings rebuilt while a file app has the file, as after a rotation, picks the run up: the
     * new rows are out of reach, offer the stop once it stalls, and hear how it ended.
     */
    @Test public void aRebuiltSettingsPagePicksUpTheRunningRestore() throws Exception {
        TikTokPreferenceFragment fragment = attachBackupPage();
        DocumentExportProvider provider = restoreSource();
        CountDownLatch release = holdOpens(provider);
        fragment.onActivityResult(7312, Activity.RESULT_OK, new Intent().setData(provider.uri));
        assertTrue(provider.awaitOpening());

        activity.getFragmentManager().beginTransaction().remove(fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        TikTokPreferenceFragment rebuilt = attachBackupPage();
        Preference restore = rebuilt.findPreference(RESTORE);
        restore.getView(null, null);
        assertFalse(restore.isEnabled());
        assertEquals("Restoring your settings", String.valueOf(restore.getSummary()));
        stall();
        assertTrue(restore.isEnabled());
        assertEquals(STOP_OFFER, String.valueOf(restore.getSummary()));

        ShadowToast.reset();
        release.countDown();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Settings restored. Restart TikTok to apply all changes.", ShadowToast.getTextOfLatestToast());
        assertEquals(7, (int) Settings.MAX_VIDEO_SECONDS.get());
        assertTrue("the restore left no Undo copy", SettingsBackup.hasUndo(activity));
        assertResting(rebuilt);
    }

    /**
     * The picker hands back a file the user chose to replace, and the file app stalls opening it.
     * Stopping can't say whether the file app will take the backup, but once it gives up without
     * having opened the file, the file still holds the older backup and stays.
     */
    @Test public void aBackupStoppedBeforeTheFileAppOpenedTheFileItReplacesLeavesThatFileAlone() throws Exception {
        TikTokPreferenceFragment fragment = attachBackupPage();
        byte[] older = "{\"format\":\"hushfeed-settings\",\"older\":true}".getBytes(StandardCharsets.UTF_8);
        DocumentExportProvider provider = DocumentExportProvider.register(activity).contents(older);
        provider.honorCancel = true;
        CountDownLatch release = holdOpens(provider);
        fragment.onActivityResult(7311, Activity.RESULT_OK, new Intent().setData(provider.uri));
        assertTrue(provider.awaitOpening());
        stall();
        Preference backup = fragment.findPreference(BACKUP);
        backup.getOnPreferenceClickListener().onPreferenceClick(backup);
        assertEquals("Stopped waiting for the file app. It hasn't said yet whether the backup was saved.",
                ShadowToast.getTextOfLatestToast());
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("The backup wasn't saved. Back up again when the file app is ready.",
                ShadowToast.getTextOfLatestToast());
        assertEquals("the file the user chose to replace was removed", 0, provider.deleteCalls);
        assertTrue(provider.exists);
        assertArrayEquals(older, Files.readAllBytes(provider.file.toPath()));
        assertResting(fragment);
        release.countDown();
    }

    /**
     * A cloud file app hands the restore its file at once and then trickles the data. Stopping
     * closes the descriptor under the read, which wakes it, and the restore changes nothing.
     */
    @Test public void aRestoreStoppedWhileTheFileAppTricklesTheFileChangesNothing() throws Exception {
        TikTokPreferenceFragment fragment = attachBackupPage();
        DocumentExportProvider provider = restoreSource();
        SlowTransfers transfers = SlowTransfers.install(provider, hold());
        fragment.onActivityResult(7312, Activity.RESULT_OK, new Intent().setData(provider.uri));
        assertTrue("the read never started", transfers.awaitStarted());
        stall();
        Preference restore = fragment.findPreference(RESTORE);
        assertEquals(STOP_OFFER, String.valueOf(restore.getSummary()));

        restore.getOnPreferenceClickListener().onPreferenceClick(restore);
        assertEquals("Stopped waiting for the file app. Nothing was changed.", ShadowToast.getTextOfLatestToast());
        assertFalse("the stop left the read's descriptor open",
                provider.handedOut.get(0).getFileDescriptor().valid());
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("the read's end said something after the stop",
                "Stopped waiting for the file app. Nothing was changed.", ShadowToast.getTextOfLatestToast());
        assertEquals("a stopped restore changed settings", 73, (int) Settings.MAX_VIDEO_SECONDS.get());
        assertFalse("a stopped restore saved an Undo copy", SettingsBackup.hasUndo(activity));
        assertFalse(AbstractPreferenceFragment.settingImportInProgress);
        assertResting(fragment);
    }

    /**
     * The same trickle on the way out: the file app has the backup's file and takes the bytes
     * slowly. Stopping can't say whether it kept them, and when the write fails on the closed
     * descriptor the backup is called unsaved and its partly written file goes.
     */
    @Test public void aBackupStoppedWhileTheFileAppTakesItSlowlyIsCalledUnsavedAndRemoved() throws Exception {
        TikTokPreferenceFragment fragment = attachBackupPage();
        DocumentExportProvider provider = DocumentExportProvider.register(activity);
        SlowTransfers transfers = SlowTransfers.install(provider, hold());
        fragment.onActivityResult(7311, Activity.RESULT_OK, new Intent().setData(provider.uri));
        assertTrue("the write never started", transfers.awaitStarted());
        stall();
        Preference backup = fragment.findPreference(BACKUP);
        assertEquals(STOP_OFFER, String.valueOf(backup.getSummary()));

        backup.getOnPreferenceClickListener().onPreferenceClick(backup);
        assertEquals("Stopped waiting for the file app. It hasn't said yet whether the backup was saved.",
                ShadowToast.getTextOfLatestToast());
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals("The backup wasn't saved. Back up again when the file app is ready.",
                ShadowToast.getTextOfLatestToast());
        assertEquals("the partly written backup was left behind", 1, provider.deleteCalls);
        assertFalse(provider.exists);
        assertResting(fragment);
    }

    /**
     * Turning the phone while the file app has the restore's file rebuilds the whole window, not
     * just the page. The restore keeps going, the new window's rows stay out of reach until it
     * ends, and its outcome lands in the new window rather than the destroyed one.
     */
    @Test public void aRestoreKeepsGoingThroughARebuiltWindowAndReportsInTheNewOne() throws Exception {
        TikTokPreferenceFragment started = attachBackupPage();
        activity.findViewById(android.R.id.content).setTag(SettingsActionBanner.CONTENT_ROOT_TAG);
        DocumentExportProvider provider = restoreSource();
        CountDownLatch release = holdOpens(provider);
        started.onActivityResult(7312, Activity.RESULT_OK, new Intent().setData(provider.uri));
        assertTrue(provider.awaitOpening());

        Activity before = activity;
        owner.recreate();
        activity = owner.get();
        assertNotSame("the window wasn't rebuilt", before, activity);
        assertTrue(before.isDestroyed());
        activity.findViewById(android.R.id.content).setTag(SettingsActionBanner.CONTENT_ROOT_TAG);
        TikTokPreferenceFragment rebuilt = (TikTokPreferenceFragment)
                activity.getFragmentManager().findFragmentById(android.R.id.content);
        assertNotNull("the backup page didn't come back with the window", rebuilt);
        assertNotSame(started, rebuilt);
        Preference restore = rebuilt.findPreference(RESTORE);
        restore.getView(null, null);
        assertFalse(restore.isEnabled());
        assertEquals("Restoring your settings", String.valueOf(restore.getSummary()));
        assertFalse(rebuilt.findPreference(BACKUP).isEnabled());

        ShadowToast.reset();
        release.countDown();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(7, (int) Settings.MAX_VIDEO_SECONDS.get());
        android.view.View banner = activity.getWindow().getDecorView()
                .findViewWithTag("hushfeed_settings_action_banner");
        assertNotNull("the outcome didn't reach the rebuilt window", banner);
        android.widget.TextView message = banner.findViewWithTag("hushfeed_settings_action_message");
        assertEquals("Settings restored. Restart TikTok to apply all changes.", String.valueOf(message.getText()));
        assertEquals("the outcome went to a toast instead of the window", 0, ShadowToast.shownToastCount());
        assertResting(rebuilt);
    }

    /** A backup holding 7 seconds, with 73 on the phone. */
    private DocumentExportProvider restoreSource() throws Exception {
        Settings.MAX_VIDEO_SECONDS.save(7);
        byte[] backup = SettingsBackup.create(false).getBytes(StandardCharsets.UTF_8);
        Settings.MAX_VIDEO_SECONDS.save(73);
        return DocumentExportProvider.register(activity).contents(backup);
    }

    private CountDownLatch holdOpens(DocumentExportProvider provider) {
        CountDownLatch release = provider.holdOpens();
        holds.add(release);
        return release;
    }

    private CountDownLatch hold() {
        CountDownLatch release = new CountDownLatch(1);
        holds.add(release);
        return release;
    }

    private static void await(CountDownLatch release) throws InterruptedException {
        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("the test never let the work go");
    }

    private static void stall() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(DocumentOperation.stallMillis));
    }

    /** Waits for the rows' cleanup worker, which removes or keeps a refused backup's file. */
    private static void awaitBackupCleanup() throws Exception {
        Field field = SettingsBackupPreference.class.getDeclaredField("EXPORT_CLEANUP");
        field.setAccessible(true);
        ((ExecutorService) field.get(null)).submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    private void assertResting(TikTokPreferenceFragment fragment) {
        Preference backup = fragment.findPreference(BACKUP);
        Preference restore = fragment.findPreference(RESTORE);
        assertTrue(backup.isEnabled());
        assertTrue(restore.isEnabled());
        assertTrue(fragment.findPreference(RESET).isEnabled());
        assertEquals("Saves your Metra settings and Feature Gate Lab rules to a file.",
                String.valueOf(backup.getSummary()));
        assertEquals("Choose a backup file. Your current settings are kept for Undo.",
                String.valueOf(restore.getSummary()));
        assertEquals(SettingsBackup.hasUndo(activity), fragment.findPreference(UNDO).isEnabled());
    }

    private TikTokPreferenceFragment attachBackupPage() {
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "BACKUP");
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return fragment;
    }
}
