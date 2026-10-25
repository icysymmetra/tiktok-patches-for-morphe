package app.morphe.extension.tiktok.follow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import java.util.ArrayList;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FollowDiagnosticCorrelationTest {
    private boolean previousDebug;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        previousDebug = BaseSettings.DEBUG.get();
        BaseSettings.DEBUG.save(true);
        FollowDiagnostics.resetForTests();
        ShadowLog.clear();
    }

    @After public void tearDown() {
        BaseSettings.DEBUG.save(previousDebug);
        FollowDiagnostics.resetForTests();
        ShadowLog.clear();
    }

    @Test public void returnedStreamsStopAfterTheLastAdmittedRequest() {
        // The patch pairs each Jedi request with its returned stream on the same thread.
        for (int index = 0; index < 160; index++) {
            request(3);
            complete(3);
        }
        List<String> admitted = completions();
        assertEquals(160, admitted.size());
        assertTrue(admitted.get(159), admitted.get(159).contains("stream id=160 "));

        for (int index = 0; index < 20; index++) {
            request(3);
            complete(3);
        }
        assertEquals("a skipped stream reused the last admitted request ID",
                admitted, completions());
        assertEquals(160, FollowDiagnostics.eventCountForTests());
    }

    @Test public void unadmittedDirectResultsDoNotWriteCompletionEvents() {
        for (int index = 0; index < 160; index++) {
            request(2);
            complete(2);
        }
        List<String> admitted = completions();
        assertEquals(160, admitted.size());
        assertTrue(admitted.get(159), admitted.get(159).contains("result id=160 "));

        request(2);
        complete(2);
        assertEquals("a skipped request logged a result", admitted, completions());
    }

    @Test public void debugChangesDoNotReuseAnEarlierRequestId() {
        for (int route : new int[]{2, 3}) {
            FollowDiagnostics.resetForTests();
            ShadowLog.clear();
            BaseSettings.DEBUG.save(true);
            request(route);
            // A setting change can happen while the native method is returning.
            BaseSettings.DEBUG.save(false);
            complete(route);
            request(route);
            BaseSettings.DEBUG.save(true);
            complete(route);
            assertTrue("route " + route + " reused an ID across the debug change: " + completions(),
                    completions().isEmpty());

            request(route);
            complete(route);
            List<String> resumed = completions();
            assertEquals(1, resumed.size());
            assertTrue(resumed.get(0), resumed.get(0).contains(" id=2 "));
        }
    }

    @Test public void anUnloggedRequestCannotCompleteAnEarlierFailedCall() {
        // Common's native Future.get can throw before the patched return hook runs.
        request(2);
        BaseSettings.DEBUG.save(false);
        request(2);
        BaseSettings.DEBUG.save(true);
        complete(2);
        assertTrue("the skipped request inherited a failed call: " + completions(),
                completions().isEmpty());

        request(2);
        complete(2);
        assertEquals(1, completions().size());
        assertTrue(completions().get(0), completions().get(0).contains("result id=2 "));
    }

    private static void request(int route) {
        switch (route) {
            case 2:
                FollowDiagnostics.logCommonFollowRequest(
                        1, 2, 3, 4, "user", "sec", "item", "city", "profile", null);
                break;
            case 3:
                FollowDiagnostics.logJediFollowRequest(
                        "user", "sec", 1, 2, 3, "profile", 0, "profile", "feed", "profile", 0);
                break;
            default:
                throw new AssertionError("Unknown native route " + route);
        }
    }

    private static void complete(int route) {
        if (route == 3) FollowDiagnostics.logFollowStream(new Object());
        else FollowDiagnostics.logFollowResult(null);
    }

    private static List<String> completions() {
        List<String> messages = new ArrayList<>();
        for (ShadowLog.LogItem log : ShadowLog.getLogs()) {
            if (log.msg.startsWith("[Morphe TikTok FollowProbe] stream ")
                    || log.msg.startsWith("[Morphe TikTok FollowProbe] result ")) {
                messages.add(log.msg);
            }
        }
        return messages;
    }
}
