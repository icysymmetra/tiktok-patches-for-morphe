/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.follow;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.os.Looper;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowToast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A follow TikTok turns down comes back looking like a success, so the only thing that tells
 * the user is this notice. It has to fire on a real refusal and stay quiet otherwise.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class FollowDiagnosticsTest {
    private boolean debug;

    public static final class ParsedResponse {
        public final Object LIZIZ;
        ParsedResponse(Object body) { this.LIZIZ = body; }
        public String LIZJ() { return "true"; }
        public int LIZ() { return 0; }
    }

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        debug = BaseSettings.DEBUG.get();
        BaseSettings.DEBUG.save(false);
        FollowDiagnostics.resetForTests();
    }

    @org.junit.After
    public void tearDown() {
        BaseSettings.DEBUG.save(debug);
        FollowDiagnostics.resetForTests();
    }

    private static FollowDiagnostics.FollowRequestContext context() {
        return new FollowDiagnostics.FollowRequestContext(1, "/aweme/v1/commit/follow/user/");
    }

    @Test
    public void accountPseudonymsStayStableWithinOneInstallAndDifferBetweenInstalls() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG.save(true);
        java.lang.reflect.Field cached = FollowDiagnostics.class.getDeclaredField("salt");
        cached.setAccessible(true);

        try {
            Settings.DIAGNOSTIC_REPORT_SALT.save("");
            cached.set(null, null);
            String first = requestPseudonym("6812345678901234567");
            assertNotEquals("the salt must be made and kept", "",
                    Settings.DIAGNOSTIC_REPORT_SALT.get());

            // Same install, same id: two reports from one phone still line up.
            assertEquals(first, requestPseudonym("6812345678901234567"));

            // Another install uses a different private key for the same account.
            Settings.DIAGNOSTIC_REPORT_SALT.save("");
            cached.set(null, null);
            assertNotEquals(first, requestPseudonym("6812345678901234567"));

            assertEquals("empty", requestPseudonym(null));
        } finally {
            Settings.DIAGNOSTIC_REPORT_SALT.save("");
            cached.set(null, null);
        }
    }

    private static String requestPseudonym(String uid) throws ReflectiveOperationException {
        FollowDiagnostics.logCommonFollowRequest(1, 0, 0, 0, uid, null, null, null, null, null);
        java.lang.reflect.Field recent = FollowDiagnostics.class.getDeclaredField("recentDirectContext");
        recent.setAccessible(true);
        FollowDiagnostics.FollowRequestContext context =
                (FollowDiagnostics.FollowRequestContext) recent.get(null);
        assertNotNull("the public request hook did not retain its context", context);
        assertEquals("the request reused an earlier context",
                FollowDiagnostics.eventCountForTests(), context.id);
        return context.uidHash;
    }

    @Test
    public void aStatusCodeOtherThanZeroIsARefusal() {
        FollowDiagnostics.FollowRequestContext refused = context();
        refused.statusCode = "2098";

        assertTrue(FollowDiagnostics.followWasRefused(refused));
    }

    @Test
    public void anAcceptedFollowIsNotARefusal() {
        FollowDiagnostics.FollowRequestContext accepted = context();
        accepted.statusCode = "0";
        assertFalse(FollowDiagnostics.followWasRefused(accepted));

        // The model may hold the code as any numeric type.
        accepted.statusCode = "0.0";
        assertFalse(FollowDiagnostics.followWasRefused(accepted));

        // A response that says nothing is not a refusal either: most follows land silently.
        assertFalse(FollowDiagnostics.followWasRefused(context()));
    }

    @Test
    public void anExplicitFollowSuccessFalseIsARefusal() {
        FollowDiagnostics.FollowRequestContext refused = context();
        refused.bodyIsFollowSuccess = "false";

        assertTrue(FollowDiagnostics.followWasRefused(refused));
    }

    @Test
    public void theRefusalIsNamedOnScreenOnceAndTheReasonIsTheServersOwn() {
        ShadowToast.reset();

        FollowDiagnostics.FollowRequestContext refused = context();
        refused.statusCode = "2098";
        refused.statusMsg = "You are following too fast.";
        refused.riskCheck = "risk slide_captcha";

        FollowDiagnostics.warnAboutRefusedFollowOnce(refused);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        String message = ShadowToast.getTextOfLatestToast();
        assertTrue(String.valueOf(message), message.contains("You are following too fast."));
        // A hidden CAPTCHA is the first thing to check when a follow does not land, and the
        // message calls it what the switch calls it.
        assertTrue(String.valueOf(message), message.contains("A hidden CAPTCHA may be the cause."));

        int shown = ShadowToast.shownToastCount();
        FollowDiagnostics.warnAboutRefusedFollowOnce(refused);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(shown, ShadowToast.shownToastCount());
    }

    @Test
    public void aFollowThatLandedSaysNothing() {
        ShadowToast.reset();

        FollowDiagnostics.FollowRequestContext accepted = context();
        accepted.statusCode = "0";
        FollowDiagnostics.warnAboutRefusedFollowOnce(accepted);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test
    public void diagnosticsStopAtTheirSessionLimit() {
        BaseSettings.DEBUG.save(true);
        for (int index = 0; index < 240; index++) {
            FollowDiagnostics.logCommonFollowRequest(
                    1, 2, 3, 4, "user-" + index, "sec-" + index, "item", "city", "profile", null);
        }
        assertEquals(160, FollowDiagnostics.eventCountForTests());
    }

    @Test
    public void concurrentDirectRequestsCannotPassTheSessionLimit() throws Exception {
        BaseSettings.DEBUG.save(true);
        ExecutorService executor = Executors.newFixedThreadPool(8);
        List<java.util.concurrent.Future<?>> tasks = new ArrayList<>();
        try {
            for (int worker = 0; worker < 8; worker++) {
                final int offset = worker * 80;
                tasks.add(executor.submit(() -> {
                    for (int index = 0; index < 80; index++) {
                        FollowDiagnostics.logCommonFollowRequest(1, 2, 3, 4, "user-" + (offset + index),
                                "sec-" + (offset + index), "item", "city", "profile", null);
                    }
                }));
            }
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
        assertEquals(160, FollowDiagnostics.eventCountForTests());
    }

    @Test
    public void aNullContextCannotBecomeARefusalOrToast() {
        assertFalse(FollowDiagnostics.followWasRefused(null));
        FollowDiagnostics.warnAboutRefusedFollowOnce(null);
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test
    public void aParsedServerRefusalReachesTheNoticeWithoutTrustingNestedStatus() {
        ShadowToast.reset();
        String body = "{\"data\":{\"status_code\":0},"
                + "\"status_code\":2098,\"status_msg\":\"Try again later.\"}";

        FollowDiagnostics.logParsedResponse(
                new CaptchaGateRequest("/aweme/v1/commit/follow/user/"),
                new ParsedResponse(body));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(String.valueOf(ShadowToast.getTextOfLatestToast()),
                ShadowToast.getTextOfLatestToast().contains("Try again later."));
    }

    @Test
    public void malformedServerStatusDoesNotInventARefusalNotice() {
        ShadowToast.reset();
        FollowDiagnostics.logParsedResponse(
                new CaptchaGateRequest("/aweme/v1/commit/follow/user/"),
                new ParsedResponse("{\"data\":{\"status_code\":2098},\"status_code\":\"NaN\"}"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, ShadowToast.shownToastCount());
    }

    /**
     * The path is the gate. Every request TikTok makes goes through the same hook, and a
     * refusal code on a feed or profile request is that request's business: it must not be
     * read as a follow being refused. Without this the path test could match everything and
     * the refusal notice would fire on any failed request in the app.
     */
    @Test
    public void aRefusalOnARequestThatIsNotAFollowSaysNothing() {
        ShadowToast.reset();
        String refusal = "{\"status_code\":2098,\"status_msg\":\"Try again later.\"}";
        for (String path : new String[]{
                "/aweme/v1/feed/",
                "/aweme/v1/user/profile/other/",
                "/aweme/v1/user/",
                "/aweme/v1/commit/item/digg/",
                "/tiktok/v1/relation/list/",
                "/aweme/v1/following/list/"}) {
            FollowDiagnostics.logParsedResponse(
                    new CaptchaGateRequest(path), new ParsedResponse(refusal));
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, ShadowToast.shownToastCount());
    }

    /** The write routes in the 46.2.3 fixture all reach the refusal notice. */
    @Test
    public void everyVendorFollowWriteReachesTheRefusalNotice() {
        String refusal = "{\"status_code\":2098,\"status_msg\":\"Try again later.\"}";
        for (String path : new String[]{
                "/aweme/v1/commit/follow/",
                "/aweme/v1/commit/follow/user/",
                "/aweme/v1/commit/follow/batchuser/",
                "/aweme/v1/commit/follow/request/approve/",
                "/aweme/v1/commit/follow/request/reject/",
                "/aweme/v3/f2f/follow/",
                "/aweme/v1/remove/follower/"}) {
            ShadowToast.reset();
            FollowDiagnostics.resetForTests();
            FollowDiagnostics.logParsedResponse(new CaptchaGateRequest(path), new ParsedResponse(refusal));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals(path, 1, ShadowToast.shownToastCount());
            assertTrue(path, ShadowToast.getTextOfLatestToast().contains("Try again later."));
        }

        ShadowToast.reset();
        FollowDiagnostics.resetForTests();
        FollowDiagnostics.logParsedResponse(new Object(), new ParsedResponse(refusal));
        FollowDiagnostics.logParsedResponse(null, new ParsedResponse(refusal));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test
    public void aMissingRequestPathNamesTheFollowHookInsteadOfStayingUnknown() throws Exception {
        HookStatus.clear();
        try {
            java.lang.reflect.Method requestPath = FollowDiagnostics.class.getDeclaredMethod(
                    "requestPath", Object.class);
            requestPath.setAccessible(true);
            assertEquals(null, requestPath.invoke(null, new Object()));

            List<String> missing = HookStatus.missing("follow diagnostics");
            assertEquals("Expected one missing request path, got " + missing, 1, missing.size());
            assertTrue(missing.get(0), missing.get(0).contains("java.lang.Object#getPath"));
        } finally {
            HookStatus.clear();
        }
    }

    /**
     * A response the hook cannot read is not a refusal. The envelope is reached by
     * reflection over obfuscated names, and the day TikTok renames them the read must fail
     * quietly rather than throw into the network stack or invent a verdict.
     */
    @Test
    public void anUnreadableResponseIsNeitherARefusalNorAnException() throws Exception {
        ShadowToast.reset();
        java.lang.reflect.Method read = FollowDiagnostics.class.getDeclaredMethod(
                "readServerVerdict", Object.class, FollowDiagnostics.FollowRequestContext.class);
        read.setAccessible(true);
        for (Object response : new Object[]{new Object(), null,
                new ParsedResponse(null), new ParsedResponse("not json")}) {
            // Invoke below the network boundary's catch as well: a swallowed NPE is a
            // broken reader even when the hook correctly protects TikTok from the exception.
            FollowDiagnostics.FollowRequestContext verdict = context();
            read.invoke(null, response, verdict);
            assertEquals(FollowVerdict.UNKNOWN, verdict.statusCode);
            assertFalse(FollowDiagnostics.followWasRefused(verdict));
            FollowDiagnostics.logParsedResponse(
                    new CaptchaGateRequest("/aweme/v1/commit/follow/user/"), response);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertEquals(0, ShadowToast.shownToastCount());
    }

    @Test
    public void parsedResponsesStopRetainingRequestsAtTheLimitButStillReportRefusals() throws Exception {
        BaseSettings.DEBUG.save(true);
        ShadowToast.reset();
        for (int index = 0; index < 240; index++) {
            FollowDiagnostics.logParsedResponse(
                    new CaptchaGateRequest("/aweme/v1/commit/follow/user/"),
                    new ParsedResponse("{\"status_code\":0}"));
        }
        assertEquals(160, FollowDiagnostics.eventCountForTests());
        // A parsed response ends the request's life, so none of the 240 is held: the 160 that
        // used to stay here kept their bodies and buffers reachable until TikTok was killed.
        assertEquals(0, FollowDiagnostics.heldRequestsForTests());
        assertEquals(0, ShadowToast.shownToastCount());

        FollowDiagnostics.logParsedResponse(
                new CaptchaGateRequest("/aweme/v1/commit/follow/user/"),
                new ParsedResponse("{\"status_code\":2098,\"status_msg\":\"Try again later.\"}"));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(ShadowToast.getTextOfLatestToast().contains("Try again later."));
        assertEquals(160, FollowDiagnostics.eventCountForTests());
        assertEquals(0, FollowDiagnostics.heldRequestsForTests());
    }

    @Test
    public void everyWayARequestEndsLetsGoOfIt() throws Exception {
        BaseSettings.DEBUG.save(true);
        Object parsed = new CaptchaGateRequest("/aweme/v1/commit/follow/user/");
        Object parseFailed = new CaptchaGateRequest("/aweme/v1/commit/follow/user/");
        Object transportFailed = new CaptchaGateRequest("/aweme/v1/commit/follow/user/");
        Object abandoned = new CaptchaGateRequest("/aweme/v1/commit/follow/user/");
        FollowDiagnostics.logNetworkRequest(parsed);
        FollowDiagnostics.logNetworkRequest(parseFailed);
        FollowDiagnostics.logNetworkRequest(transportFailed);
        FollowDiagnostics.logNetworkRequest(abandoned);
        assertEquals(4, FollowDiagnostics.heldRequestsForTests());

        FollowDiagnostics.logNetworkResponse(parsed, new Object());
        assertEquals("a response is not the end of a request", 4, FollowDiagnostics.heldRequestsForTests());
        FollowDiagnostics.logParsedResponse(parsed, new ParsedResponse("{\"status_code\":0}"));
        FollowDiagnostics.logParseThrowable(parseFailed, new RuntimeException("bad json"));
        FollowDiagnostics.logNetworkThrowable(transportFailed, new java.io.IOException("reset"));
        assertEquals("only the request that never ended is still held", 1, FollowDiagnostics.heldRequestsForTests());

        // Two requests in flight keep their own ids and are told apart by identity, not equality.
        Object first = new CaptchaGateRequest("/aweme/v1/commit/follow/user/");
        Object second = new CaptchaGateRequest("/aweme/v1/commit/follow/user/");
        FollowDiagnostics.logNetworkRequest(first);
        FollowDiagnostics.logNetworkRequest(second);
        assertEquals(3, FollowDiagnostics.heldRequestsForTests());
        FollowDiagnostics.logParsedResponse(first, new ParsedResponse("{\"status_code\":0}"));
        assertEquals(2, FollowDiagnostics.heldRequestsForTests());
        FollowDiagnostics.logParsedResponse(second, new ParsedResponse("{\"status_code\":0}"));
        assertEquals(1, FollowDiagnostics.heldRequestsForTests());
    }

    public static final class ChatBody {
        public int status_code = 4003;
        public String status_msg = "Slow down";
        public String comment_text = "a private sentence";
        public long user_id = 7123456789012345678L;
    }

    private static List<String> liveCommentLines() {
        List<String> lines = new ArrayList<>();
        for (org.robolectric.shadows.ShadowLog.LogItem log : org.robolectric.shadows.ShadowLog.getLogs()) {
            if (log.msg.startsWith("[Morphe TikTok FollowProbe] live comment response ")) lines.add(log.msg);
        }
        return lines;
    }

    @Test
    public void aLiveCommentAnswerIsLoggedWithoutTheTextOrIds() {
        BaseSettings.DEBUG.save(true);
        org.robolectric.shadows.ShadowLog.clear();
        ShadowToast.reset();

        FollowDiagnostics.logParsedResponse(
                new CaptchaGateRequest("/webcast/room/chat/?room_id=7400000000000000001"),
                new ParsedResponse(new ChatBody()));

        List<String> lines = liveCommentLines();
        assertEquals(lines.toString(), 1, lines.size());
        String line = lines.get(0);
        assertTrue(line, line.contains("path=/webcast/room/chat/ "));
        assertFalse("the room id stays out of the report", line.contains("7400000000000000001"));
        assertTrue(line, line.contains("status_code=4003"));
        assertTrue(line, line.contains("status_msg=Slow down"));
        assertTrue(line, line.contains("outcome=refused"));
        assertTrue(line, line.contains("bodyFieldNames=status_code,status_msg,comment_text,user_id"));
        assertFalse(line, line.contains("a private sentence"));
        assertFalse(line, line.contains("7123456789012345678"));
        assertEquals("a LIVE comment answer is not a follow refusal", 0, ShadowToast.shownToastCount());
    }

    @Test
    public void aLiveCommentAnswerStaysQuietWithLoggingOffAndForOtherPaths() {
        org.robolectric.shadows.ShadowLog.clear();
        FollowDiagnostics.logParsedResponse(
                new CaptchaGateRequest("/webcast/room/chat/"), new ParsedResponse(new ChatBody()));
        assertEquals(0, liveCommentLines().size());

        BaseSettings.DEBUG.save(true);
        FollowDiagnostics.logParsedResponse(
                new CaptchaGateRequest("/webcast/room/enter/"), new ParsedResponse(new ChatBody()));
        assertEquals(0, liveCommentLines().size());

        // An unreadable response still produces a line instead of throwing into the network stack.
        FollowDiagnostics.logParsedResponse(new CaptchaGateRequest("/webcast/room/chat/"), null);
        assertEquals(1, liveCommentLines().size());
        assertTrue(liveCommentLines().get(0), liveCommentLines().get(0).contains("status_code=unknown"));
    }

    public static final class EnterBody {
        public int status_code = 0;
        public String status_msg = "ok";
    }

    private static List<String> linesStarting(String prefix) {
        List<String> lines = new ArrayList<>();
        for (org.robolectric.shadows.ShadowLog.LogItem log : org.robolectric.shadows.ShadowLog.getLogs()) {
            if (log.msg.startsWith(prefix)) lines.add(log.msg);
        }
        return lines;
    }

    @Test
    public void aLiveCommentSaysWhetherItWentToTheRoomEnteredLast() throws Exception {
        for (String name : new String[]{"lastEnteredRoom", "lastEnterAtMs"}) {
            java.lang.reflect.Field field = FollowDiagnostics.class.getDeclaredField(name);
            field.setAccessible(true);
            if (field.getType() == int.class) field.setInt(null, 0); else field.setLong(null, 0L);
        }
        BaseSettings.DEBUG.save(true);
        org.robolectric.shadows.ShadowLog.clear();

        FollowDiagnostics.logParsedResponse(new CaptchaGateRequest("/webcast/room/chat/?room_id=7400000000000000001"),
                new ParsedResponse(new ChatBody()));
        FollowDiagnostics.logParsedResponse(new CaptchaGateRequest("/webcast/room/enter/?room_id=7400000000000000001"
                        + "&enter_from_merge=homepage_hot&enter_method=live_cell&enter_source=20261016123456"),
                new ParsedResponse(new EnterBody()));
        FollowDiagnostics.logParsedResponse(new CaptchaGateRequest("/webcast/room/chat/?room_id=7400000000000000001"),
                new ParsedResponse(new ChatBody()));
        FollowDiagnostics.logParsedResponse(new CaptchaGateRequest("/webcast/room/chat/?room_id=7400000000000000002"),
                new ParsedResponse(new ChatBody()));

        List<String> enters = linesStarting("[Morphe TikTok FollowProbe] live room enter response ");
        assertEquals(enters.toString(), 1, enters.size());
        String enter = enters.get(0);
        assertTrue(enter, enter.contains("status_code=0"));
        assertTrue(enter, enter.contains("route=enter_from_merge:homepage_hot,enter_method:live_cell,enter_source:set"));
        assertTrue(enter, enter.contains("queryKeys=room_id,enter_from_merge,enter_method,enter_source"));
        assertFalse("the room id stays out of the report", enter.contains("7400000000000000001"));
        assertFalse("a number in a route value stays out too", enter.contains("20261016123456"));

        List<String> comments = liveCommentLines();
        assertEquals(comments.toString(), 3, comments.size());
        assertTrue(comments.get(0), comments.get(0).contains("room=no_enter_seen "));
        assertTrue(comments.get(1), comments.get(1).contains("room=last_entered,enteredSecondsAgo="));
        assertTrue(comments.get(2), comments.get(2).contains("room=other_than_last_entered,enteredSecondsAgo="));
        for (String comment : comments) {
            assertFalse(comment, comment.contains("740000000000000000"));
        }
    }

    public static final class CaptchaGateRequest {
        private final String path;
        CaptchaGateRequest(String path) { this.path = path; }
        public String getPath() { return path; }
    }
}
