package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.feedfilter.FeedCapture;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.DebugPreferenceCategory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

/**
 * The Diagnostics row that runs a feed capture: a tap starts it, the next stops it and saves the
 * file beside the other reports, and a save that fails keeps the capture for another try.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FeedCapturePreferenceTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String SAVED = "Feed capture saved to ";
    private static final String READ_IT = ". Read it before you share it.";

    @After public void tearDown() {
        FeedCapturePreference.resetForTests();
    }

    private static void tap(FeedCapturePreference row) {
        row.getOnPreferenceClickListener().onPreferenceClick(row);
    }

    @Test public void aTapStartsTheCaptureAndTheNextStopsAndSavesIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        FeedCapturePreference row = new FeedCapturePreference(context);
        assertEquals(FeedCapturePreference.KEY, row.getKey());
        assertTrue("every tap acts at once", row.actsOnTap());
        assertEquals("Capture the feed", String.valueOf(row.getTitle()));
        assertFalse(FeedCapture.isRecording());

        ShadowToast.reset();
        tap(row);
        assertTrue(FeedCapture.isRecording());
        assertEquals("Stop and save the feed capture", String.valueOf(row.getTitle()));
        assertTrue(String.valueOf(row.getSummary()), String.valueOf(row.getSummary())
                .endsWith("Tap to stop and save it in " + GateReportExport.FOLDER + "."));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Feed capture started. Scroll until the problem shows up, then come back here to stop.",
                String.valueOf(ShadowToast.getTextOfLatestToast()));

        tap(row);
        assertFalse("the second tap left the capture running", FeedCapture.isRecording());
        String said = awaitToast(SAVED);
        assertTrue(said, said.endsWith(READ_IT));
        File saved = new File(said.substring(SAVED.length(), said.length() - READ_IT.length()));
        try {
            assertTrue(saved.getName(), saved.getName().matches("metra-feed-capture-\\d{8}-\\d{6}(-\\d+)?\\.txt"));
            String text = new String(Files.readAllBytes(saved.toPath()), StandardCharsets.UTF_8);
            assertTrue(text, text.startsWith("Metra feed capture\nRead this file before you share it."));
        } finally {
            assertTrue(saved.delete());
        }
        settle();
        assertEquals("a saved capture still offered to save again",
                "Capture the feed", String.valueOf(row.getTitle()));
    }

    @Test public void aFailedSaveKeepsTheCaptureAndTheRowOffersToSaveItAgain() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        // Nowhere to write before Android 10: no app Documents folder.
        Context nowhere = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getExternalFilesDir(String type) { return null; }
        };
        FeedCapturePreference row = new FeedCapturePreference(nowhere);
        tap(row);
        assertTrue(FeedCapture.isRecording());

        ShadowToast.reset();
        tap(row);
        assertFalse(FeedCapture.isRecording());
        assertEquals("The feed capture couldn't be saved. Tap the row to try again.",
                awaitToast("The feed capture couldn't"));
        settle();
        assertEquals("Save the feed capture", String.valueOf(row.getTitle()));
        assertEquals("The last feed capture wasn't saved. Tap to try again.", String.valueOf(row.getSummary()));

        // A row with somewhere to write saves the same capture rather than starting a new one.
        FeedCapturePreference retry = new FeedCapturePreference(context);
        assertEquals("Save the feed capture", String.valueOf(retry.getTitle()));
        ShadowToast.reset();
        tap(retry);
        assertFalse("a retry started a new capture", FeedCapture.isRecording());
        String said = awaitToast(SAVED);
        File saved = new File(said.substring(SAVED.length(), said.length() - READ_IT.length()));
        try {
            assertTrue(saved.isFile());
        } finally {
            assertTrue(saved.delete());
        }
        settle();
        assertEquals("Capture the feed", String.valueOf(retry.getTitle()));
    }

    public static final class TestActivity extends PreferenceActivity {}

    /**
     * The row comes with the Diagnostic tools patch. A build with only the Feature Gate Lab still
     * has the Diagnostics page, and no Capture the feed row on it.
     */
    @Test public void theRowIsOnlyOnABuildWithDiagnosticTools() {
        boolean diagnostics = SettingsStatus.diagnosticsEnabled;
        boolean recorder = SettingsStatus.featureGateRecorderEnabled;
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            SettingsStatus.featureGateRecorderEnabled = true;
            SettingsStatus.diagnosticsEnabled = false;
            PreferenceScreen withoutTools = debugPage(controller.get());
            assertNotNull("the Feature Gate Lab case built no page, so it proves nothing",
                    withoutTools.findPreference("feature_gate_recorder"));
            assertNull("a build without Diagnostic tools has a Capture the feed row",
                    withoutTools.findPreference(FeedCapturePreference.KEY));
            SettingsStatus.diagnosticsEnabled = true;
            assertNotNull(debugPage(controller.get()).findPreference(FeedCapturePreference.KEY));
        } finally {
            SettingsStatus.diagnosticsEnabled = diagnostics;
            SettingsStatus.featureGateRecorderEnabled = recorder;
        }
    }

    private static PreferenceScreen debugPage(PreferenceActivity activity) {
        PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
        new DebugPreferenceCategory(activity, screen);
        return screen;
    }

    /** The save thread finished and the row refresh it posts has run. */
    private static void settle() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
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
}
