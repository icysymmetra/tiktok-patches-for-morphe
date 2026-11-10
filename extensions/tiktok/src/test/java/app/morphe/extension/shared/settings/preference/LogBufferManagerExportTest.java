package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.StallingMediaProvider;
import android.content.Context;
import android.os.Environment;
import android.os.Looper;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class LogBufferManagerExportTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    @Test public void anOlderAndroidSaysNothingAboutTheLastExit() {
        // The history arrived in API 30 and this bundle runs from 23, so the section has to be
        // absent rather than empty or guessed at.
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        app.morphe.extension.shared.diagnostics.HookStatus.missingViewId("comments", "jlk");

        String report = LogBufferManager.buildExportText();
        assertNotEquals("nothing was reported at all", "", report);
        assertTrue("an API 28 build claimed to know why the process went away: " + report,
                report.indexOf("[LAST EXIT]") < 0);
    }

    @Test @Config(sdk = 30)
    public void theReportSaysWhyTheProcessWentAwayLastTime() throws Exception {
        // A Java crash handler sees none of the ways the system ends an app. Android 17 kills one
        // that goes over a RAM-proportional limit and leaves only this behind.
        app.morphe.extension.shared.Utils.setContext(RuntimeEnvironment.getApplication());
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();

        android.app.ApplicationExitInfo exit =
                org.robolectric.shadows.ShadowActivityManager.ApplicationExitInfoBuilder.newBuilder()
                .setReason(13)
                .setTimestamp(1757260800000L)
                .setProcessName(RuntimeEnvironment.getApplication().getPackageName())
                .setDescription("MemoryLimiter:AnonSwap")
                .build();

        // TikTok runs several processes, and the most recent record is routinely a background
        // helper the system reaped. Reporting that as why the app went away is worse than saying
        // nothing, so the newer of the two here must be passed over.
        android.app.ApplicationExitInfo helper = org.robolectric.shadows.ShadowActivityManager
                .ApplicationExitInfoBuilder.newBuilder()
                .setReason(10)
                .setTimestamp(1757260900000L)
                .setProcessName(RuntimeEnvironment.getApplication().getPackageName() + ":push")
                .setDescription("a background helper nobody asked about")
                .build();
        android.app.ActivityManager manager = (android.app.ActivityManager) RuntimeEnvironment
                .getApplication().getSystemService(Context.ACTIVITY_SERVICE);
        org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(exit);
        org.robolectric.Shadows.shadowOf(manager).addApplicationExitInfo(helper);

        // The line rides along with a report that was already worth making. On its own it must
        // not make one, because every process has a last exit and most of them are ordinary.
        assertEquals("a last exit alone made a report", "", LogBufferManager.buildExportText());

        app.morphe.extension.shared.diagnostics.HookStatus.missingViewId("comments", "jlk");
        String report = LogBufferManager.buildExportText();
        assertTrue("the report does not say why the process went away: " + report,
                report.contains("[LAST EXIT]"));
        assertTrue("the reason was not named: " + report, report.contains("reason: OTHER"));
        assertTrue("the description the kill carried was dropped: " + report,
                report.contains("MemoryLimiter:AnonSwap"));
        assertTrue("a background helper's exit was reported as the app's: " + report,
                report.indexOf("a background helper nobody asked about") < 0);
        java.text.SimpleDateFormat utc =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", java.util.Locale.US);
        utc.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        String when = utc.format(new java.util.Date(1757260800000L));
        assertTrue("the time it happened was left out or in another format: " + report,
                report.contains(when));
    }

    @Test @Config(sdk = 30)
    public void aFullSharedPoolNoLongerHoldsUpTheDiagnosticSave() throws Exception {
        // The save ran on the shared pool, so other work filling it turned the save away. It has
        // a thread of its own now and saves while every shared worker is busy.
        Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        reportAMissingView();
        StallingMediaProvider media = StallingMediaProvider.register(context, temporary.newFile());
        ShadowToast.reset();
        try (var saturation = app.morphe.extension.shared.BackgroundPoolSaturation.fill()) {
            LogBufferManager.exportToFile();
            awaitToast("Full report saved to ");
        }
        assertTrue(media.published);
        assertTrue(new String(Files.readAllBytes(media.file.toPath()), StandardCharsets.UTF_8)
                .startsWith("MORPHE DIAGNOSTIC REPORT\n"));
    }

    @Test @Config(sdk = 30)
    public void aSecondTapWhileTheMediaStoreStallsStartsNoSecondDiagnosticWriter() throws Exception {
        // One save is out at a time, and the slot comes back when a stalled one finally returns.
        Context context = RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        reportAMissingView();
        StallingMediaProvider media = StallingMediaProvider.register(context, temporary.newFile()).hold();
        ShadowToast.reset();
        LogBufferManager.exportToFile();
        assertTrue("the save never reached the media store", media.awaitInserting());

        LogBufferManager.exportToFile();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(String.valueOf(ShadowToast.getTextOfLatestToast()),
                String.valueOf(ShadowToast.getTextOfLatestToast()).startsWith("A diagnostic report is already being saved"));

        media.release();
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("a second tap started another writer", 1, media.inserts.get());
        assertTrue(String.valueOf(ShadowToast.getTextOfLatestToast()).startsWith("Full report saved to "));

        LogBufferManager.exportToFile();
        app.morphe.extension.shared.Utils.awaitBackgroundTasksForTests();
        assertEquals("the slot stayed taken after the stalled save returned", 2, media.inserts.get());
    }

    private static void reportAMissingView() {
        app.morphe.extension.shared.settings.BaseSettings.DEBUG_LOG_FILTERS.save("all");
        app.morphe.extension.shared.diagnostics.HookStatus.clear();
        LogBufferManager.clearLogBuffer();
        app.morphe.extension.shared.diagnostics.HookStatus.missingViewId("comments", "jlk");
    }

    private static String awaitToast(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            String said = String.valueOf(ShadowToast.getTextOfLatestToast());
            if (said.startsWith(prefix)) return said;
            Thread.sleep(10);
        }
        throw new AssertionError("no toast starting \"" + prefix + "\"; the last said: "
                + ShadowToast.getTextOfLatestToast());
    }

    @Test public void repeatedLegacyExportsUseTheRealUniqueDocumentsPaths() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String report = "MORPHE DIAGNOSTIC REPORT\nschema: 1\n";
        String first = LogBufferManager.writeToFile(context, report);
        String second = LogBufferManager.writeToFile(context, report);
        File firstFile = new File(first);
        File secondFile = new File(second);
        try {
            File documents = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
            assertTrue(documents != null);
            assertTrue(first.startsWith(documents.getAbsolutePath()));
            assertTrue(second.startsWith(documents.getAbsolutePath()));
            assertNotEquals(first, second);
            assertEquals(report, new String(Files.readAllBytes(firstFile.toPath()), StandardCharsets.UTF_8));
            assertEquals(report, new String(Files.readAllBytes(secondFile.toPath()), StandardCharsets.UTF_8));
        } finally {
            firstFile.delete();
            secondFile.delete();
        }
    }
}
