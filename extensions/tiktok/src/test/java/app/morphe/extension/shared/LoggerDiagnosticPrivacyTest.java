package app.morphe.extension.shared;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.diagnostics.DiagnosticCategory;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SettingsContextRule;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class LoggerDiagnosticPrivacyTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Before public void setUp() {
        BaseSettings.DEBUG.save(false);
        BaseSettings.DEBUG_STACKTRACE.save(false);
        BaseSettings.DEBUG_TOAST_ON_ERROR.save(false);
        LogBufferManager.clearLogBuffer();
        ShadowLog.clear();
    }

    @After public void tearDown() {
        BaseSettings.DEBUG.resetToDefault();
        BaseSettings.DEBUG_STACKTRACE.resetToDefault();
        BaseSettings.DEBUG_TOAST_ON_ERROR.resetToDefault();
        LogBufferManager.clearLogBuffer();
        ShadowLog.clear();
    }

    @Test public void infoMessagesAndTagsAreSanitizedBeforeReachingSystemLogs() {
        Logger.diagnosticInfo(DiagnosticCategory.DOWNLOADS, "sessionid=TAG_SENTINEL",
                () -> "phase=fetch sessionid=INFO_SENTINEL");

        String logs = capturedText();
        assertTrue("The diagnostic itself was dropped", logs.contains("phase=fetch"));
        assertFalse(logs.contains("TAG_SENTINEL"));
        assertFalse(logs.contains("INFO_SENTINEL"));
    }

    @Test public void exceptionCausesAndSuppressedMessagesAreSanitizedAtTheSystemSink() {
        Exception cause = new java.io.IOException("{\"access_token\":\"CAUSE_SENTINEL\"}");
        Exception failure = new IllegalStateException("Authorization: Bearer ROOT_SENTINEL", cause);
        failure.addSuppressed(new IllegalArgumentException("{\"password\":\"SUPPRESSED_SENTINEL\"}"));

        Logger.printException(() -> "phase=decode {\"sessionid\":\"MESSAGE_SENTINEL\"}", failure);

        String logs = capturedText();
        for (String secret : new String[]{"CAUSE_SENTINEL", "ROOT_SENTINEL",
                "SUPPRESSED_SENTINEL", "MESSAGE_SENTINEL"}) {
            assertFalse("The system sink retained " + secret, logs.contains(secret));
        }
        assertTrue(logs.contains("phase=decode"));
        assertTrue("Useful exception identity was lost", logs.contains("IllegalStateException"));
        assertTrue("Useful stack frames were lost", logs.contains("LoggerDiagnosticPrivacyTest"));
    }

    @Test public void aBrokenThrowableCannotExposeTheOriginalMessageThroughTheFallback() {
        Throwable failure = new RuntimeException() {
            @Override public String getMessage() {
                throw new IllegalStateException("Authorization: Bearer FALLBACK_SENTINEL");
            }
        };

        Logger.printException(() -> "sessionid=ORIGINAL_SENTINEL", failure);

        String logs = capturedText();
        assertFalse(logs.contains("FALLBACK_SENTINEL"));
        assertFalse(logs.contains("ORIGINAL_SENTINEL"));
        assertTrue("The logger silently discarded its failure", !logs.isEmpty());
    }

    @Test(timeout = 5000) public void aGeneratingCauseChainIsBoundedWithoutPassingTheOriginalThrowable() {
        AtomicInteger causeReads = new AtomicInteger();
        Throwable failure = new GeneratingCause(causeReads);
        Logger.printException(() -> "phase=decode", failure);

        StringBuilder messages = new StringBuilder();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (item.tag == null || !item.tag.startsWith("morphe:")) continue;
            assertNotSame("The raw throwable still reached Android's unbounded renderer",
                    failure, item.throwable);
            messages.append(item.msg);
        }
        assertTrue("The logger followed an unbounded host cause chain", causeReads.get() <= 128);
        assertTrue(messages.toString().contains("phase=decode"));
        assertFalse(messages.toString().contains("CHAIN_SENTINEL"));
        assertTrue("The omitted cause chain was not reported",
                messages.toString().toLowerCase(java.util.Locale.ROOT).contains("truncated"));
        assertTrue(messages.length() <= 250_000);
    }

    private static final class GeneratingCause extends RuntimeException {
        private final AtomicInteger causeReads;

        GeneratingCause(AtomicInteger causeReads) {
            super("sessionid=CHAIN_SENTINEL", null, false, false);
            this.causeReads = causeReads;
        }

        @Override public synchronized Throwable getCause() {
            causeReads.incrementAndGet();
            return new GeneratingCause(causeReads);
        }
    }

    private static String capturedText() {
        StringBuilder result = new StringBuilder();
        for (ShadowLog.LogItem item : ShadowLog.getLogs()) {
            if (item.tag == null || !item.tag.startsWith("morphe:")) continue;
            result.append(item.tag).append('\n').append(item.msg).append('\n');
            if (item.throwable != null) {
                StringWriter stack = new StringWriter();
                item.throwable.printStackTrace(new PrintWriter(stack));
                result.append(stack);
            }
        }
        return result.toString();
    }
}
