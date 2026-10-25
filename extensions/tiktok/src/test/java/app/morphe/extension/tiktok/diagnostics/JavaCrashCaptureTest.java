package app.morphe.extension.tiktok.diagnostics;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.io.IOException;

import app.morphe.extension.shared.settings.preference.LogBufferManager;
import java.lang.reflect.Method;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** What a crash report is allowed to carry once it leaves the device. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class JavaCrashCaptureTest {
    /** A request that failed mid-flight, which is how a URL gets into an exception message. */
    private static final String URL =
            "https://api16-normal-c-useast1a.tiktokv.com/aweme/v1/feed/?device_id=6812&sessionid=abc123";

    @Test public void reportsKeepAddressesAndCredentialsOutOfTheText() {
        String redacted = JavaCrashCapture.redact("unexpected end of stream on " + URL);
        assertFalse(redacted.contains("tiktokv.com"));
        assertFalse(redacted.contains("abc123"));
        assertFalse(redacted.contains("6812"));
        assertTrue(redacted.contains("[url omitted]"));

        // A bare credential, with no address around it, still goes.
        String bare = JavaCrashCapture.redact("Auth failed sessionid=abc123 sid_tt=deadbeef");
        assertFalse(bare.contains("abc123"));
        assertFalse(bare.contains("deadbeef"));
        assertTrue(bare.contains("sessionid=[omitted]"));

        assertEquals("", JavaCrashCapture.redact(null));
        assertEquals("nothing to hide", JavaCrashCapture.redact("nothing to hide"));
    }

    @Test public void theMessagesAnAndroidNetworkFailureActuallyProducesAreRedacted() {
        // Neither of the two commonest ones carries a scheme, which is what a pattern anchored
        // on https:// misses.
        String unknownHost = JavaCrashCapture.redact(
                "java.net.UnknownHostException: Unable to resolve host "
                        + "\"api16-normal-c-useast1a.tiktokv.com\": No address associated with hostname");
        assertFalse(unknownHost, unknownHost.contains("tiktokv.com"));

        String refused = JavaCrashCapture.redact(
                "ConnectException: failed to connect to api16-normal.tiktokv.com/13.32.1.5 "
                        + "(port 443) after 15000ms");
        assertFalse(refused, refused.contains("tiktokv.com"));
    }

    @Test public void aCredentialDoesNotShieldTheOnesAfterIt() {
        // Taking everything up to the next space swallows the delimiter, and then every later
        // value on the line rides out inside the match.
        String cookies = JavaCrashCapture.redact(
                "Cookie: sessionid=abc123; msToken=SECRETVALUE; ttwid=1%7Cxyz");
        assertFalse(cookies, cookies.contains("abc123"));
        assertFalse(cookies, cookies.contains("SECRETVALUE"));
        assertFalse(cookies, cookies.contains("1%7Cxyz"));

        // A name the first version did not list, and a header rather than a query parameter.
        String others = JavaCrashCapture.redact("sessionid_ss=abc123 odin_tt=deadbeef");
        assertFalse(others, others.contains("abc123"));
        assertFalse(others, others.contains("deadbeef"));
        assertFalse(JavaCrashCapture.redact("X-Tt-Token: 03deadbeefcafe").contains("03deadbeefcafe"));

        // Ordinary text is left readable, or the report stops being worth reading.
        assertEquals("Attempt to invoke virtual method on a null object reference",
                JavaCrashCapture.redact(
                        "Attempt to invoke virtual method on a null object reference"));
    }

    @Test public void aJavaCrashReportRedactsItsMessageAndItsStack() throws Exception {
        Throwable thrown = new IOException("unexpected end of stream on " + URL);
        Method build = JavaCrashCapture.class.getDeclaredMethod(
                "buildReport", android.content.Context.class, Thread.class, Throwable.class,
                Thread.UncaughtExceptionHandler.class);
        build.setAccessible(true);
        String report = (String) build.invoke(null,
                org.robolectric.RuntimeEnvironment.getApplication(),
                Thread.currentThread(), thrown, null);

        // The message line and the stack trace are both in there, and neither carries the URL.
        assertTrue(report.contains("[STACK TRACE]"));
        assertTrue(report.contains("JavaCrashCaptureTest"));
        assertFalse("the message must not carry the address", report.contains("tiktokv.com"));
        assertFalse("nor the session", report.contains("abc123"));
        assertTrue(report.contains("[url omitted]"));
    }

    @Test public void crashPersistenceRedactsThreadMetadataAndDelegatesTheOriginalFailure() throws Exception {
        var context = org.robolectric.RuntimeEnvironment.getApplication();
        app.morphe.extension.shared.Utils.setContext(context);
        Throwable original = new IOException("{\"sessionid\":\"CRASH_SENTINEL\"}");
        java.util.concurrent.atomic.AtomicReference<Throwable> delegated =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread.UncaughtExceptionHandler delegate = (thread, failure) -> delegated.set(failure);
        Class<?> handlerClass = Class.forName(JavaCrashCapture.class.getName() + "$MorpheCrashHandler");
        java.lang.reflect.Constructor<?> constructor = handlerClass.getDeclaredConstructor(
                android.content.Context.class, Thread.UncaughtExceptionHandler.class);
        constructor.setAccessible(true);
        Thread.UncaughtExceptionHandler handler = (Thread.UncaughtExceptionHandler)
                constructor.newInstance(context, delegate);

        handler.uncaughtException(new Thread("sessionid=CRASH_THREAD_SENTINEL"), original);

        assertSame("TikTok's crash handler must receive the original throwable", original, delegated.get());
        String raw = new String(java.nio.file.Files.readAllBytes(new java.io.File(
                context.getFilesDir(), "morphe_java_crash_report_v1.txt").toPath()),
                java.nio.charset.StandardCharsets.UTF_8);
        assertFalse(raw.contains("CRASH_SENTINEL"));
        assertFalse(raw.contains("CRASH_THREAD_SENTINEL"));
        assertTrue(raw.contains("JavaCrashCaptureTest"));
    }

    @Test public void aWriteCutShortLeavesTheLastWholeReportReadable() throws Exception {
        var context = org.robolectric.RuntimeEnvironment.getApplication();
        String whole = "schema: 1\ncomplete: true\nthe last whole report\n";
        LogBufferManager.persistCrashReport(context, whole);
        // What a death in the middle of the next write leaves behind: AtomicFile moved the good
        // copy aside to .bak before it began, and the base file holds the start of the new one.
        java.io.File base = new java.io.File(context.getFilesDir(), "morphe_java_crash_report_v1.txt");
        java.io.File backup = new java.io.File(base.getPath() + ".bak");
        assertTrue(base.renameTo(backup));
        java.nio.file.Files.write(base.toPath(), "schema: 1\ncomp".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        assertEquals(whole, LogBufferManager.readCrashReport(context));
        assertFalse("the half written copy is gone once the whole one is back", backup.exists());
    }

    @Test public void aReportPastTheCeilingSaysSoAndIsCutOnACharacter() throws Exception {
        var context = org.robolectric.RuntimeEnvironment.getApplication();
        // Well past 64,000 bytes, and every character two bytes wide, so a cut at the byte
        // ceiling lands inside one unless the writer steps back to a boundary.
        StringBuilder wide = new StringBuilder("schema: 1\ncomplete: true\n");
        while (wide.length() < 50_000) wide.append("\u00e9");
        LogBufferManager.persistCrashReport(context, wide.toString());
        String saved = LogBufferManager.readCrashReport(context);

        assertTrue("the end says the report was cut", saved.endsWith("[report truncated]\n"));
        assertTrue("and so does the header", saved.startsWith("schema: 1\ncomplete: false\n"));
        assertFalse("which no longer claims the report is whole", saved.contains("complete: true"));
        assertFalse("nothing read back as a replacement character", saved.contains("\ufffd"));
        assertTrue(saved.length() < wide.length());
        assertTrue(saved.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 64_000);

        // A report that fits is written whole, with no marker.
        String small = "schema: 1\ncomplete: true\nshort\n";
        LogBufferManager.persistCrashReport(context, small);
        assertEquals(small, LogBufferManager.readCrashReport(context));
    }
}
