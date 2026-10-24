/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.repost;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.LogBufferManager;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class RepostDiagnosticsTest {
    private boolean previousDebug;

    public static final class Request {
        private final String path;
        Request(String path) { this.path = path; }
        public String getPath() { return path; }
    }

    public static final class Parsed {
        public final Object LIZIZ;
        Parsed(Object body) { LIZIZ = body; }
        public int LIZ() { return 200; }
        public boolean LIZJ() { return true; }
    }

    public static final class Body {
        public final int status_code;
        public final int error_code;
        public final String status_msg;
        public final List<Item> upvoteLists;
        Body(int status, String message, List<Item> items) {
            status_code = status;
            error_code = 0;
            status_msg = message;
            upvoteLists = items;
        }
    }

    public static final class Item {
        public final String itemId;
        Item(String id) { itemId = id; }
    }

    public static final class ServerRefusal extends RuntimeException {
        ServerRefusal() { super("private repost note"); }
        public int getErrorCode() { return 2098; }
    }

    /** The raw response 47.1.4 hands the request stage: the HTTP code is a field, with no LIZ(). */
    public static final class RawResponse {
        public final int LIZIZ;
        RawResponse(int code) { LIZIZ = code; }
    }

    public static final class HttpFailure extends RuntimeException {
        HttpFailure() { super("private repost note"); }
        public int getStatusCode() { return 503; }
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        previousDebug = BaseSettings.DEBUG.get();
        BaseSettings.DEBUG.save(true);
    }

    @After public void tearDown() {
        BaseSettings.DEBUG.save(previousDebug);
    }

    @Test public void onlyRepostWritesAndReadbacksAreObserved() {
        assertEquals("publish", RepostDiagnostics.pathKind(new Request("/tiktok/v1/upvote/publish")));
        assertEquals("publish", RepostDiagnostics.pathKind(new Request("/tiktok/v1/upvote/batch_publish")));
        assertEquals("batch_list", RepostDiagnostics.pathKind(new Request("/tiktok/v1/upvote/batch_list")));
        assertEquals("item/list", RepostDiagnostics.pathKind(new Request("/tiktok/v1/upvote/item/list")));
        assertEquals(null, RepostDiagnostics.pathKind(new Request("/tiktok/v1/upvote/publish_comment")));
        assertEquals(null, RepostDiagnostics.pathKind(new Request("/aweme/v1/comment/list")));
    }

    @Test public void requestRefusalAndReadbackAreLoggedWithoutTheVideoIdOrNote() {
        String itemId = "9876543210123456789";
        String note = "private repost note";
        RepostDiagnostics.onPublishRequested(itemId, note);
        RepostDiagnostics.onNetworkRequest(new Request("/tiktok/v1/upvote/publish"));
        RepostDiagnostics.onParsedResponse(new Request("/tiktok/v1/upvote/publish"),
                new Parsed(new Body(2098, note, null)));
        RepostDiagnostics.onParsedResponse(new Request("/tiktok/v1/upvote/batch_list"),
                new Parsed(new Body(0, "", Arrays.asList(new Item("other"), new Item(itemId)))));

        String report = LogBufferManager.snapshotForCrash(100_000);
        assertTrue(report, report.contains("RepostProbe] request itemHash="));
        assertTrue(report, report.contains("status_code=2098"));
        assertTrue(report, report.contains("outcome=server_refused"));
        assertTrue(report, report.contains("RepostProbe] readback"));
        assertTrue(report, report.contains("target=found"));
        assertFalse(report, report.contains(itemId));
        assertFalse(report, report.contains(note));
    }

    @Test public void statusAndPartialReadbacksStayHonest() {
        assertEquals("transport_refused", RepostDiagnostics.outcome("403", "false", "0", "0"));
        assertEquals("unknown_body", RepostDiagnostics.outcome("200", "true", "null", "null"));
        assertEquals("server_accepted", RepostDiagnostics.outcome("200", "true", "0", "0"));
        assertEquals("server_refused", RepostDiagnostics.outcome("200", "true", "0", "14"));

        String hash = app.morphe.extension.tiktok.follow.FollowDiagnostics.pseudonym("123");
        assertEquals("not_in_page", RepostDiagnostics.readbackTarget(
                new Body(0, "", Collections.emptyList()), hash, 100L, 101L));
        assertEquals("no_recent_request", RepostDiagnostics.readbackTarget(
                new Body(0, "", Collections.emptyList()), hash, 100L, 120_101L));
    }

    @Test public void theRawResponseAndHttpFailureNameTheirCodesAsOn4714() {
        assertEquals("200", RepostDiagnostics.rawHttpCode(new RawResponse(200)));
        assertEquals("a parsed-style response still answers through LIZ()",
                "200", RepostDiagnostics.rawHttpCode(new Parsed(null)));
        assertEquals("unknown", RepostDiagnostics.rawHttpCode(new Object()));

        assertEquals("2098", RepostDiagnostics.errorStatus(new ServerRefusal()));
        assertEquals("503", RepostDiagnostics.errorStatus(new HttpFailure()));
        assertEquals("unknown", RepostDiagnostics.errorStatus(new RuntimeException()));

        RepostDiagnostics.onNetworkResponse(new Request("/tiktok/v1/upvote/publish"), new RawResponse(200));
        RepostDiagnostics.onParseThrowable(new Request("/tiktok/v1/upvote/publish"), new HttpFailure());
        String report = LogBufferManager.snapshotForCrash(100_000);
        assertTrue(report, report.contains("network response id="));
        assertTrue(report, report.contains("http=200"));
        assertTrue(report, report.contains("parse error id="));
        assertTrue(report, report.contains("status_code=503"));
        assertFalse(report, report.contains("private repost note"));
    }

    @Test public void parseErrorNamesTheRefusalCode() {
        RepostDiagnostics.onParseThrowable(
                new Request("/tiktok/v1/upvote/publish"), new ServerRefusal());
        String report = LogBufferManager.snapshotForCrash(100_000);
        assertTrue(report.contains("status_code=2098"));
        assertFalse(report.contains("private repost note"));
    }
}
