package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.*;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.Looper;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.StallingMediaProvider;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class GateReportExportTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void multiMegabyteReportSavesInFullAndNeverReachesClipboard() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        String small = "{\"gates\":[]}";
        ShadowToast.reset();
        assertTrue(GateReportExport.copy(context, small));
        assertEquals("Feature gate report copied", String.valueOf(ShadowToast.getTextOfLatestToast()));
        String large = "{\"value\":\"" + "Caption \uD83C\uDF0D ".repeat(200000) + "\"}";
        ShadowToast.reset();
        assertFalse(GateReportExport.copy(context, large));
        assertEquals("Use Save report for this large report",
                String.valueOf(ShadowToast.getTextOfLatestToast()));
        var clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        assertNotNull(clip);
        assertEquals("Feature gate recording", clip.getDescription().getLabel());
        assertEquals(small, clip.getItemAt(0).getText());
        assertTrue(clip.getDescription().getExtras()
                .getBoolean("android.content.extra.IS_SENSITIVE"));
        File saved = new File(GateReportExport.write(context, large));
        try { assertEquals(large, new String(Files.readAllBytes(saved.toPath()), java.nio.charset.StandardCharsets.UTF_8)); }
        finally { assertTrue(saved.delete()); }
    }

    @Test
    @Config(sdk = {23, 35})
    public void emptyReportUsesSharedSensitiveClipboardHandlingWhereSupported() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        ShadowToast.reset();

        assertTrue(GateReportExport.copy(context, ""));

        ClipboardManager clipboard = (ClipboardManager) context
                .getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = clipboard.getPrimaryClip();
        assertNotNull(clip);
        assertEquals("Feature gate recording", clip.getDescription().getLabel());
        assertEquals("", clip.getItemAt(0).getText());
        if (Build.VERSION.SDK_INT >= 24) {
            assertNotNull(clip.getDescription().getExtras());
            assertTrue(clip.getDescription().getExtras()
                    .getBoolean("android.content.extra.IS_SENSITIVE"));
        }
        assertEquals("Feature gate report copied",
                String.valueOf(ShadowToast.getTextOfLatestToast()));
    }

    @Test @Config(sdk = 30)
    public void aFullSharedPoolNoLongerHoldsUpTheSave() throws Exception {
        // Save JSON ran on the shared pool, so a pool full of other work turned it away. It has a
        // thread of its own now and saves while every shared worker is busy.
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        StallingMediaProvider media = StallingMediaProvider.register(context, temporary.newFile());
        ShadowToast.reset();
        try (var saturation = app.morphe.extension.shared.BackgroundPoolSaturation.fill()) {
            GateReportExport.save(context, "{\"gates\":[]}");
            String said = awaitToast("Report saved to ");
            assertTrue(said, said.startsWith("Report saved to Download/Metra/metra-gate-report-"));
        }
        assertTrue(media.published);
        assertEquals("{\"gates\":[]}", new String(Files.readAllBytes(media.file.toPath()), StandardCharsets.UTF_8));
    }

    @Test @Config(sdk = 30)
    public void aSecondTapWhileTheMediaStoreStallsStartsNoSecondWriter() throws Exception {
        // Each tap used to take another background thread, so a media store that stalled could
        // be handed one writer per tap. One save is out at a time, and a tap while it waits says so.
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        StallingMediaProvider media = StallingMediaProvider.register(context, temporary.newFile()).hold();
        ShadowToast.reset();
        GateReportExport.save(context, "{\"gates\":[]}");
        assertTrue("the save never reached the media store", media.awaitInserting());

        GateReportExport.save(context, "{\"gates\":[1]}");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("The last report is still being saved", String.valueOf(ShadowToast.getTextOfLatestToast()));

        media.release();
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("a second tap started another writer", 1, media.inserts.get());
        assertTrue(String.valueOf(ShadowToast.getTextOfLatestToast()).startsWith("Report saved to "));
        assertEquals("{\"gates\":[]}", new String(Files.readAllBytes(media.file.toPath()), StandardCharsets.UTF_8));

        // Once the save is back, the next tap saves again.
        GateReportExport.save(context, "{\"gates\":[2]}");
        Utils.awaitBackgroundTasksForTests();
        assertEquals(2, media.inserts.get());
        assertEquals("{\"gates\":[2]}", new String(Files.readAllBytes(media.file.toPath()), StandardCharsets.UTF_8));
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

    @Test public void theSavedNameReadsAsADateInAHushfeedFolder() throws Exception {
        // Two reports a second apart get names a reader can tell apart, the way the settings
        // backup is named, and the folder is the one a file manager shows.
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        assertTrue(GateReportExport.fileName(), GateReportExport.fileName()
                .matches("metra-gate-report-\\d{8}-\\d{6}\\.json"));
        assertEquals("Download/Metra", GateReportExport.FOLDER);
        File first = new File(GateReportExport.write(context, "{\"gates\":[]}"));
        File second = new File(GateReportExport.write(context, "{\"gates\":[]}"));
        try {
            assertTrue(first.getName(), first.getName().startsWith("metra-gate-report-"));
            assertNotEquals("two reports in one second overwrote each other", first, second);
        } finally {
            assertTrue(first.delete());
            assertTrue(second.delete());
        }
    }

    @Test public void clipboardFailureIsReportedWithoutCrashing() {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        Context denied = new ContextWrapper(context) {
            @Override public Object getSystemService(String service) { throw new SecurityException("Clipboard denied"); }
        };
        ShadowToast.reset();
        assertFalse(GateReportExport.copy(denied, "{}"));
        assertEquals("Couldn't copy the report. Use Save report instead.",
                String.valueOf(ShadowToast.getTextOfLatestToast()));
    }
}
