package app.morphe.extension.tiktok.diagnostics;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ClipData;
import android.content.ClipboardManager;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class LogBufferRedactionTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        BaseSettings.DEBUG_LOG_FILTERS.save("downloads");
        LogBufferManager.clearLogBuffer();
        // The export carries a hook table now, and the registry behind it outlives a test class.
        HookStatus.clear();
    }

    @After public void tearDown() {
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
        // The filter lives in a static registry that outlives this class. Left on "downloads",
        // a later class in the same JVM that exported a feed event got an empty report
        // (NetworkRequestsTest, whenever the run order put this class first).
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
    }

    @Test public void selectedEventsAreRedactedAtExport() {
        LogBufferManager.appendEvent(
                DiagnosticCategory.DOWNLOADS,
                "RemoteMedia",
                "ERROR",
                "GET https://api16-normal-c-useast1a.tiktokv.com/feed?sessionid=secret123");

        String report = LogBufferManager.buildExportText();

        assertTrue(report.contains("RemoteMedia"));
        assertTrue(report.contains("[url omitted]"));
        assertFalse(report.contains("tiktokv.com"));
        assertFalse(report.contains("secret123"));
    }

    @Test public void persistedCrashTextIsRedactedAlongsideEvents() throws Exception {
        LogBufferManager.persistCrashReport(
                context,
                "Exception: https://api16.tiktokv.com/feed?token=crash-secret");
        LogBufferManager.appendEvent(
                DiagnosticCategory.DOWNLOADS,
                "RemoteMedia",
                "ERROR",
                "sessionid=event-secret");

        String report = LogBufferManager.buildExportText();

        assertTrue(report.contains("[LATEST JAVA CRASH]"));
        assertTrue(report.contains("[SELECTED EVENTS]"));
        assertFalse(report.contains("crash-secret"));
        assertFalse(report.contains("event-secret"));
        assertFalse(report.contains("tiktokv.com"));
    }

    @Test public void theRawBufferNeverRetainsMessageSourceOrThreadCredentials() {
        Thread thread = Thread.currentThread();
        String originalName = thread.getName();
        try {
            thread.setName("sessionid=THREAD_SENTINEL");
            LogBufferManager.appendEvent(DiagnosticCategory.DOWNLOADS,
                    "sessionid=SOURCE_SENTINEL", "INFO",
                    "phase=fetch {\"sessionid\":\"BUFFER_SENTINEL\"}");
        } finally {
            thread.setName(originalName);
        }

        String raw = LogBufferManager.snapshotForCrash(12_000);
        assertTrue(raw.contains("phase=fetch"));
        assertFalse(raw.contains("THREAD_SENTINEL"));
        assertFalse(raw.contains("SOURCE_SENTINEL"));
        assertFalse(raw.contains("BUFFER_SENTINEL"));
    }

    @Test public void bothCrashFilesAreSanitizedBeforeTheyReachDisk() throws Exception {
        String report = "schema: 1\ncomplete: true\nphase=decode\n"
                + "{\"sessionid\":\"DISK_SENTINEL\"}\n"
                + "Authorization: Bearer HEADER_SENTINEL\n";
        LogBufferManager.persistCrashReport(context, report);
        LogBufferManager.persistNpthCrashReport(context, report);

        for (String name : new String[]{"morphe_java_crash_report_v1.txt",
                "morphe_npth_crash_report_v1.txt"}) {
            String raw = new String(java.nio.file.Files.readAllBytes(
                    new java.io.File(context.getFilesDir(), name).toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(raw.contains("phase=decode"));
            assertFalse(name + " retained a structured credential", raw.contains("DISK_SENTINEL"));
            assertFalse(name + " retained a header credential", raw.contains("HEADER_SENTINEL"));
        }
    }

    /**
     * Each of these resolves to a post somebody can open, so a report shared on the tracker
     * would otherwise carry part of what the reporter had been watching.
     */
    @Test public void videoAndCommentIdsAreRedactedAtExport() {
        LogBufferManager.appendEvent(
                DiagnosticCategory.DOWNLOADS,
                "FeedFilter",
                "INFO",
                // Short values on purpose: these are caught by the name in front of them, not by
                // the shape of the value, so the two rules are pinned apart.
                "aid=aa11 aweme_id=bb22 item_id=cc33 cid=dd44 msg_id=ee55");

        String report = LogBufferManager.buildExportText();

        assertTrue(report.contains("FeedFilter"));
        for (String id : new String[]{"aa11", "bb22", "cc33", "dd44", "ee55"}) {
            assertFalse(report + " still carries " + id, report.contains(id));
        }
    }

    /** The feed probe prints a list of ids with no name in front of them. */
    @Test public void bareVideoIdListsAreRedactedAtExport() {
        LogBufferManager.appendEvent(
                DiagnosticCategory.DOWNLOADS,
                "FeedFilterProbe",
                "INFO",
                "before=\"7412345678901234567,7412345678901234568\" removed=2 elapsedMs=13");

        String report = LogBufferManager.buildExportText();

        assertFalse(report, report.contains("7412345678901234567"));
        assertFalse(report, report.contains("7412345678901234568"));
        // The numbers that describe the call are what makes the report worth reading.
        assertTrue(report, report.contains("removed=2"));
        assertTrue(report, report.contains("elapsedMs=13"));
    }

    /** A list of ids under one name, and names that carry a prefix in front of the id. */
    @Test public void idListsAndPrefixedIdNamesAreRedactedAtExport() {
        LogBufferManager.appendEvent(
                DiagnosticCategory.DOWNLOADS,
                "FeedFilter",
                "INFO",
                "aweme_id=aa11,bb22 first_item_id=cc33 parent_cid=dd44 removed=2");

        String report = LogBufferManager.buildExportText();

        for (String id : new String[]{"aa11", "bb22", "cc33", "dd44"}) {
            assertFalse(report + " still carries " + id, report.contains(id));
        }
        assertTrue(report, report.contains("removed=2"));
    }

    /** A setting whose name happens to contain "aid" keeps the value a reader needs. */
    @Test public void settingNamesThatContainAnIdWordKeepTheirValues() {
        LogBufferManager.appendEvent(
                DiagnosticCategory.DOWNLOADS,
                "Settings",
                "INFO",
                "hide_paid_partnership=true unpaid=false");

        String report = LogBufferManager.buildExportText();

        assertTrue(report, report.contains("hide_paid_partnership=true"));
        assertTrue(report, report.contains("unpaid=false"));
    }

    @Test public void diagnosticClipboardContentIsMarkedSensitive() {
        LogBufferManager.appendEvent(
                DiagnosticCategory.DOWNLOADS,
                "RemoteMedia",
                "INFO",
                "download complete");

        LogBufferManager.exportToClipboard();

        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        assertTrue(clip != null);
        assertTrue(clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE"));
    }
}
