package app.morphe.extension.shared.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** The diagnostic clear row keeps a complete, one-tap way back. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class ClearLogBufferPreferenceTest {
    private Context context;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        BaseSettings.DEBUG_LOG_FILTERS.save("all");
        emptyEverythingAndDropUndo();
        LogBufferManager.clearedMessage = "Diagnostic data cleared. Tap again to put it back.";
        LogBufferManager.nothingToClearMessage = "There is no diagnostic data to clear";
        LogBufferManager.restoredMessage = "Diagnostic data put back";
        LogBufferManager.nothingToRestoreMessage = "There is no diagnostic data to put back";
        LogBufferManager.restoreFailedMessage =
                "Couldn't put back the diagnostic data. Try again.";
        ShadowToast.reset();
    }

    @After public void tearDown() {
        emptyEverythingAndDropUndo();
        BaseSettings.DEBUG_LOG_FILTERS.resetToDefault();
        ShadowToast.reset();
    }

    @Test public void theNextTapRestoresEveryDiagnosticSurfaceAndKeepsNewFindings()
            throws Exception {
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER,
                "BeforeClear", "INFO", "old buffered event");
        HookStatus.bound("comments", "like_button");
        HookStatus.missingViewId("comments", "missing_button");
        LogBufferManager.persistCrashReport(context, "old java crash");
        LogBufferManager.persistNpthCrashReport(context, "old native crash");

        ClearLogBufferPreference first = row();
        assertTrue(first.getOnPreferenceClickListener().onPreferenceClick(first));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(LogBufferManager.canUndoClear());
        assertEquals("Cleared summary", first.getSummary());
        assertEquals("", LogBufferManager.buildExportText());
        assertFalse(HookStatus.anyMissing());
        assertEquals("", LogBufferManager.readCrashReport(context));
        assertEquals("", LogBufferManager.readNpthCrashReport(context));
        assertEquals("Diagnostic data cleared. Tap again to put it back.",
                ShadowToast.getTextOfLatestToast());

        // The row can be rebuilt while its offer survives. New evidence arriving meanwhile is
        // retained after the older data is put back in front of it.
        ClearLogBufferPreference rebuilt = row();
        assertEquals("Cleared summary", rebuilt.getSummary());
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER,
                "AfterClear", "INFO", "new buffered event");
        HookStatus.bound("inbox", "item_view");
        assertTrue(rebuilt.getOnPreferenceClickListener().onPreferenceClick(rebuilt));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertFalse(LogBufferManager.canUndoClear());
        assertEquals("Clear summary", rebuilt.getSummary());
        assertEquals("old java crash", LogBufferManager.readCrashReport(context));
        assertEquals("old native crash", LogBufferManager.readNpthCrashReport(context));
        String report = LogBufferManager.buildExportText();
        assertTrue(report, report.contains("old buffered event"));
        assertTrue(report, report.contains("new buffered event"));
        assertTrue(report, report.contains("comments: 1 found, 1 missing"));
        assertTrue(report, report.contains("inbox: 1 found, 0 missing"));

        // Restoring a raw miss key, rather than only its displayed sentence, keeps the normal
        // repeat guard working when the same hook runs again.
        HookStatus.missingViewId("comments", "missing_button");
        assertEquals(1, HookStatus.missing("comments").size());
        assertEquals("Diagnostic data put back", ShadowToast.getTextOfLatestToast());
    }

    @Test public void clearingNothingDoesNotPromiseAnUndoThatDoesNotExist() {
        ClearLogBufferPreference preference = row();

        assertTrue(preference.getOnPreferenceClickListener().onPreferenceClick(preference));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertFalse(LogBufferManager.canUndoClear());
        assertEquals("Clear summary", preference.getSummary());
        assertEquals("There is no diagnostic data to clear",
                ShadowToast.getTextOfLatestToast());
    }

    @Test public void anEventArrivingDuringClearStartsTheNewGeneration() throws Exception {
        LogBufferManager.appendEvent(DiagnosticCategory.OTHER,
                "BeforeClear", "INFO", "old event");
        BlockingFilesContext blocked = new BlockingFilesContext(context, 1);
        Utils.setContext(blocked);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread clear = thread("clear diagnostics", failure, LogBufferManager::clearLogBuffer);
        clear.start();
        assertTrue("clear never reached its snapshot boundary",
                blocked.blocked.await(2, TimeUnit.SECONDS));

        Thread producer = thread("record during clear", failure, () ->
                LogBufferManager.appendEvent(DiagnosticCategory.OTHER,
                        "DuringClear", "INFO", "new event"));
        producer.start();
        blocked.release.countDown();
        join(clear);
        join(producer);
        if (failure.get() != null) throw new AssertionError(failure.get());

        String afterClear = LogBufferManager.buildExportText();
        assertFalse(afterClear, afterClear.contains("old event"));
        assertTrue(afterClear, afterClear.contains("new event"));
        assertEquals(LogBufferManager.UndoResult.RESTORED, LogBufferManager.undoClear());
        String restored = LogBufferManager.buildExportText();
        assertTrue(restored, restored.contains("old event"));
        assertTrue(restored, restored.contains("new event"));
    }

    @Test public void undoDoesNotOverwriteACrashSavedWhileItRestores() throws Exception {
        LogBufferManager.persistCrashReport(context, "cleared crash");
        LogBufferManager.clearLogBuffer();
        assertTrue(LogBufferManager.canUndoClear());

        BlockingFilesContext blocked = new BlockingFilesContext(context, 2);
        Utils.setContext(blocked);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread undo = thread("restore diagnostics", failure, LogBufferManager::undoClear);
        undo.start();
        assertTrue("undo never reached its crash write boundary",
                blocked.blocked.await(2, TimeUnit.SECONDS));

        Thread producer = thread("save newer crash", failure,
                () -> LogBufferManager.persistCrashReport(blocked, "newer crash"));
        producer.start();
        // In the broken implementation the producer reaches the file while Undo is paused
        // between its check and write. Wait for that path before releasing the boundary so the
        // final value proves which write won. In the fixed implementation it waits on the
        // diagnostic generation lock and reaches the file only after Undo.
        if (blocked.laterCall.await(300, TimeUnit.MILLISECONDS)) join(producer);
        blocked.release.countDown();
        join(undo);
        join(producer);
        if (failure.get() != null) throw new AssertionError(failure.get());

        assertEquals("newer crash", LogBufferManager.readCrashReport(context));
    }

    private ClearLogBufferPreference row() {
        ClearLogBufferPreference preference = new ClearLogBufferPreference(context);
        preference.setClearAndUndoSummaries("Clear summary", "Cleared summary");
        return preference;
    }

    private static void emptyEverythingAndDropUndo() {
        // The second empty clear replaces the first clear's snapshot with nothing.
        LogBufferManager.clearLogBuffer();
        LogBufferManager.clearLogBuffer();
        HookStatus.clear();
    }

    private static Thread thread(
            String name,
            AtomicReference<Throwable> failure,
            ThrowingRunnable action
    ) {
        return new Thread(() -> {
            try {
                action.run();
            } catch (Throwable error) {
                failure.compareAndSet(null, error);
            }
        }, name);
    }

    private static void join(Thread thread) throws Exception {
        thread.join(TimeUnit.SECONDS.toMillis(5));
        assertFalse(thread.getName() + " did not finish", thread.isAlive());
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** Pauses one exact file lookup so a producer can be placed on the other side of Clear. */
    private static final class BlockingFilesContext extends ContextWrapper {
        final CountDownLatch blocked = new CountDownLatch(1);
        final CountDownLatch laterCall = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final int blockAt;
        private final AtomicInteger calls = new AtomicInteger();

        BlockingFilesContext(Context base, int blockAt) {
            super(base);
            this.blockAt = blockAt;
        }

        @Override public File getFilesDir() {
            int call = calls.incrementAndGet();
            if (call > blockAt) laterCall.countDown();
            if (call == blockAt) {
                blocked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new AssertionError("file boundary was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(interrupted);
                }
            }
            return super.getFilesDir();
        }
    }
}
