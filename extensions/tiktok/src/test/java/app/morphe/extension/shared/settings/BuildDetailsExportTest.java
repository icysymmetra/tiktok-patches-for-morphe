package app.morphe.extension.shared.settings;

import static org.junit.Assert.*;
import android.content.ClipboardManager;
import android.content.Context;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.BuildDetails;
import app.morphe.extension.shared.diagnostics.BuildDetailsTest;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.BuildDetailsFixture;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.SettingsBackup;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Field;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = {23, 35})
public class BuildDetailsExportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private String oldSettings;

    @Before public void start() throws Exception {
        oldSettings = SettingsBackup.create(false);
        HushfeedPause.resetForTests();
        // A failed preference commit rolls the filter back to "all" and logs an error the clear
        // below removes, and every later assertion then reads unrelated categories. Say so here.
        boolean interruptedBefore = Thread.currentThread().isInterrupted();
        boolean saved = saveDownloadsFilter();
        assertTrue(saved ? "" : failedFilterSaveDetails(interruptedBefore), saved);
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    @After public void finish() throws Exception {
        try {
            HushfeedPause.resetForTests();
            SettingsBackup.restore(RuntimeEnvironment.getApplication(), oldSettings, false);
        } finally {
            LogBufferManager.clearLogBuffer();
            HookStatus.clear();
        }
    }

    @Test public void metadataDoesNotMakeAnAutomaticReportWorthExporting() throws Exception {
        try (BuildDetailsFixture asset = asset(BuildDetailsTest.metadata())) {
            Utils.setContext(asset.context);
            assertEquals(BuildDetailsTest.metadata(), Utils.getBuildMetadata());
            assertEquals("", LogBufferManager.buildExportText());
            LogBufferManager.appendEvent(DiagnosticCategory.FEED_AND_NAVIGATION, "Fixture", "INFO", "not in the selected category");
            assertEquals("", LogBufferManager.buildExportText());
            LogBufferManager.appendEvent(DiagnosticCategory.DOWNLOADS, "Fixture", "INFO", "phase=ready sessionid=EVENT_SENTINEL");
            String report = LogBufferManager.buildExportText();
            assertTrue(report.contains(BuildDetails.section(BuildDetailsTest.metadata())));
            assertTrue(report.contains("phase=ready"));
            assertFalse(report.contains("EVENT_SENTINEL"));
            assertTrue(report.contains("source_start: A1234567890123456789B"));
            LogBufferManager.clearLogBuffer();
            assertTrue(LogBufferManager.canUndoClear());
            assertEquals(BuildDetailsTest.metadata(), Utils.getBuildMetadata());
            assertEquals("", LogBufferManager.buildExportText());
            assertEquals(LogBufferManager.UndoResult.RESTORED, LogBufferManager.undoClear());
            assertTrue(LogBufferManager.buildExportText().contains(BuildDetails.section(BuildDetailsTest.metadata())));
        }
    }

    @Test public void pauseImportAndResetCannotRewritePatchTimeFacts() throws Exception {
        String metadata = BuildDetailsTest.metadata(), expected = BuildDetails.report(metadata);
        try (BuildDetailsFixture asset = asset(metadata)) {
            Utils.setContext(asset.context);
            for (HushfeedPause.Reason reason : HushfeedPause.Reason.values()) {
                HushfeedPause.pauseForTests(reason);
                assertEquals(expected, BuildDetails.report());
            }
            HushfeedPause.resetForTests();
            String backup = SettingsBackup.create(true);
            assertFalse(backup.contains("patch_time"));
            assertFalse(backup.contains("source_commit"));
            SettingsBackup.restore(asset.context, backup, false);
            assertEquals(expected, BuildDetails.report());
            SettingsBackup.reset(asset.context);
            assertEquals(expected, BuildDetails.report());
            assertEquals(metadata, Utils.getBuildMetadata());
        }
    }

    @Test public void metadataSurvivesTheRealQuickCopyTailWhenEventsExceedItsLimit() throws Exception {
        try (BuildDetailsFixture asset = asset(BuildDetailsTest.metadata())) {
            Utils.setContext(asset.context);
            for (int index = 0; index < 180; index++) {
                LogBufferManager.appendEvent(DiagnosticCategory.DOWNLOADS, "Fixture", "INFO", "phase=ready " + "x".repeat(500));
            }
            assertTrue(LogBufferManager.buildExportText().length() > 60_000);
            LogBufferManager.exportToClipboard();
            ClipboardManager clipboard = (ClipboardManager) asset.context.getSystemService(Context.CLIPBOARD_SERVICE);
            String copied = clipboard.getPrimaryClip().getItemAt(0).getText().toString();
            assertTrue(copied.contains("clipboard_note:"));
            assertTrue(copied.contains(BuildDetails.section(BuildDetailsTest.metadata())));
        }
    }

    @Test public void legacyReadsDoNotCacheUnknownAndOversizedAssetsNeverRenderTheirContents() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        assertEquals("", Utils.getBuildMetadata());
        assertTrue(BuildDetails.report().contains("build_metadata: unknown"));
        try (BuildDetailsFixture asset = asset(BuildDetailsTest.metadata())) {
            Utils.setContext(asset.context);
            assertTrue(BuildDetails.report().contains("build_metadata: available"));
        }
        try (BuildDetailsFixture asset = asset("SIZE_SENTINEL".repeat(400))) {
            Utils.setContext(asset.context);
            assertEquals("", Utils.getBuildMetadata());
            assertFalse(BuildDetails.report().contains("SIZE_SENTINEL"));
        }
    }

    /**
     * Robolectric's SDK 23 SharedPreferencesImpl opens the prefs file, and when that fails tries
     * mkdir on its folder and gives up with "Couldn't create ...". On Windows the open can fail
     * for a moment (twice in full gates, never alone), and the commit then returns false and the
     * save rolls back. Up to two more tries after a short wait, only when the failed try logged
     * that line itself, tell the file hiccup apart from a save that can't land. A retry says so on
     * stderr, so the test report shows it.
     */
    private static boolean saveDownloadsFilter() throws InterruptedException {
        for (int attempt = 0; ; attempt++) {
            long hiccupsBefore = fileHiccups();
            if (BaseSettings.DEBUG_LOG_FILTERS.save("downloads")) return true;
            if (fileHiccups() == hiccupsBefore || attempt >= 2) return false;
            System.err.println("prefs file hiccup on save attempt " + (attempt + 1) + ", trying again");
            Thread.sleep(250);
        }
    }

    private static long fileHiccups() {
        return ShadowLog.getLogsForTag("SharedPreferencesImpl").stream()
                .filter(item -> item.msg != null && item.msg.startsWith("Couldn't create"))
                .count();
    }

    private static String failedFilterSaveDetails(boolean interruptedBefore) {
        StringWriter text = new StringWriter();
        PrintWriter out = new PrintWriter(text);
        out.println("the downloads-only log filter was not saved");
        out.println("thread=" + Thread.currentThread().getName()
                + " interruptedBefore=" + interruptedBefore
                + " interruptedAfter=" + Thread.currentThread().isInterrupted());
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            out.println(item.type + " " + item.tag + ": " + item.msg);
            if (item.throwable != null) item.throwable.printStackTrace(out);
        }
        try {
            Context context = Utils.getContext();
            out.println("mainProcess=" + Utils.isMainProcess()
                    + " context=" + (context == null ? "null" : context.getClass().getName()));
            var store = Setting.preferences.preferences;
            out.println("store=" + store.getClass().getName()
                    + " applicationStore=" + (store == RuntimeEnvironment.getApplication()
                            .getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE)));
            if (context != null) {
                out.println("package=" + context.getPackageName()
                        + " declaredProcess=" + context.getApplicationInfo().processName
                        + " currentStore=" + (store == context.getSharedPreferences(
                                Setting.PREFERENCES_NAME, Context.MODE_PRIVATE)));
                File expected = new File(context.getApplicationInfo().dataDir,
                        "shared_prefs/" + Setting.PREFERENCES_NAME + ".xml");
                fileState(out, "expectedParent", expected.getParentFile());
                fileState(out, "expectedFile", expected);
            }
            Field field = store.getClass().getDeclaredField("mFile");
            field.setAccessible(true);
            File actual = (File) field.get(store);
            fileState(out, "storeParent", actual.getParentFile());
            fileState(out, "storeFile", actual);
            fileState(out, "storeBackup", new File(actual.getPath() + ".bak"));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            out.println("Could not collect all preference state:");
            failure.printStackTrace(out);
        }
        out.flush();
        return text.toString();
    }

    private static void fileState(PrintWriter out, String label, File file) {
        if (file == null) {
            out.println(label + "=null");
            return;
        }
        out.println(label + "=" + file + " exists=" + file.exists()
                + " directory=" + file.isDirectory() + " writable=" + file.canWrite()
                + " bytes=" + file.length());
    }

    private BuildDetailsFixture asset(String metadata) throws Exception {
        return new BuildDetailsFixture(RuntimeEnvironment.getApplication(), temporary.newFile(), metadata);
    }
}
