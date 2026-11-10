package app.morphe.extension.shared;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class UtilsSchedulingTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before
    public void setUp() {
        LogBufferManager.clearLogBuffer();
    }

    @After
    public void tearDown() throws Exception {
        Utils.awaitBackgroundTasksForTests();
        LogBufferManager.clearLogBuffer();
    }

    @Test
    public void waitingForBackgroundWorkOutlastsAFullQueue() throws Exception {
        BackgroundPoolSaturation saturation = BackgroundPoolSaturation.fill();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch waited = new java.util.concurrent.CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                Utils.awaitBackgroundTasksForTests();
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                waited.countDown();
            }
        });
        waiter.start();
        try {
            assertFalse("the wait returned while every worker and queue slot was still held",
                    waited.await(200, java.util.concurrent.TimeUnit.MILLISECONDS));
        } finally {
            saturation.release();
        }
        assertTrue("the wait never returned after the queue drained",
                waited.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertNull("a full queue failed the wait instead of being waited out", failure.get());
    }

    @Test
    public void aBackgroundErrorIsLoggedAndThePoolKeepsRunning() throws Exception {
        AtomicBoolean laterTaskRan = new AtomicBoolean();
        Utils.runOnBackgroundThread(() -> { throw new AssertionError("background sentinel"); });
        Utils.runOnBackgroundThread(() -> laterTaskRan.set(true));
        Utils.awaitBackgroundTasksForTests();

        assertTrue(laterTaskRan.get());
        assertTrue(LogBufferManager.buildExportText().contains("background sentinel"));
    }

    @Test
    public void anImmediateMainThreadErrorIsLoggedInsteadOfEscaping() {
        assertTrue(Utils.isCurrentlyOnMainThread());
        Utils.runOnMainThreadNowOrLater(() -> { throw new AssertionError("main sentinel"); });
        assertTrue(LogBufferManager.buildExportText().contains("main sentinel"));
    }

    @Test
    public void aBrokenFailureDescriptionCannotEscapeMessageFallback() {
        RuntimeException[] failures = {
                new RuntimeException() {
                    @Override public String getMessage() {
                        throw new AssertionError("getMessage must stay contained");
                    }
                },
                new RuntimeException() {
                    @Override public String toString() {
                        throw new AssertionError("toString must stay contained");
                    }
                }
        };

        for (RuntimeException failure : failures) {
            Logger.diagnosticError(
                    DiagnosticCategory.PATCH_ERRORS,
                    "LoggerFailureContainmentTest",
                    () -> { throw failure; },
                    null
            );
        }

        String report = LogBufferManager.buildExportText();
        assertEquals(2, occurrences(report, "Could not build the log message."));
    }

    private static int occurrences(String text, String needle) {
        return (text.length() - text.replace(needle, "").length()) / needle.length();
    }
}
