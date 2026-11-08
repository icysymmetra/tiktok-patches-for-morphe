package app.morphe.extension.tiktok.translation;

import static org.junit.Assert.*;

import android.app.Activity;
import android.os.Looper;
import android.text.Spanned;
import android.text.style.URLSpan;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.tiktok.comment.CommentLinks;
import app.morphe.extension.tiktok.comment.CommentTools;
import app.morphe.extension.tiktok.settings.Settings;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

/**
 * Runs real extension hooks against controlled cross-video inputs, without TikTok or a network.
 * Native scaffolding models only verified 47.4.3 boundaries: initialized manager fields before
 * the cell hook, response items, text binding, and translation notifications keyed by CID.
 * It does not emulate TikTok's adapter, response acceptance, pane lifecycle, or feed cache.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
public class CommentOwnershipReplayTest {
    private final CommentBatchTranslatorTest state = new CommentBatchTranslatorTest();

    @Before public void setUp() throws Exception {
        state.setUp();
        resetOptions();
        NativeManager.requests.clear();
    }

    @After public void tearDown() throws Exception {
        resetOptions();
        state.tearDown();
        NativeManager.requests.clear();
    }

    private static void resetOptions() {
        Settings.COMMENT_LINKS.save(false);
        Settings.COMMENT_SEARCH.save(false);
        Settings.BLOCK_FROM_COMMENT.save(false);
        Settings.COMMENT_KEYWORD_FILTER.save(false);
        Settings.HIDE_COMMENT_MEDIA.save(false);
        Settings.HIDE_COMMENT_POLLS.save(false);
        Settings.HIDE_COMMENT_REPLY_PREVIEWS.save(false);
        Settings.COMMENT_BLOCKED_KEYWORDS.save("");
        Settings.COMMENT_BLOCKED_USERS.save("");
        Settings.COMMENT_BLOCKED_NAME_KEYWORDS.save("");
    }

    private static void drain() {
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(400));
    }

    @Test public void latePreviousVideoEventsKeepTheRecycledRowOnItsNewVideo() throws Exception {
        int[][] orders = {{0,1,2}, {0,2,1}, {1,0,2}, {1,2,0}, {2,0,1}, {2,1,0}};
        for (boolean tools : new boolean[]{false, true}) {
            for (int[] order : orders) {
                state.tearDown();
                state.setUp();
                NativeManager.requests.clear();
                Settings.COMMENT_LINKS.save(tools);
                Settings.COMMENT_SEARCH.save(tools);
                Settings.COMMENT_KEYWORD_FILTER.save(tools);
                Settings.COMMENT_BLOCKED_KEYWORDS.save("remove-this-fixture");
                try (var activity = Robolectric.buildActivity(Activity.class).setup().visible()) {
                    Cell cell = new Cell(activity.get());
                    ReplayComment a = new ReplayComment("video-a", "comment-a");
                    ReplayComment b = new ReplayComment("video-b", "comment-b");
                    Manager manager = new Manager(a);
                    page(a);
                    cell.bind(manager);
                    page(b);
                    manager.bind(b);
                    cell.bind(manager);
                    assertEquals("translation positive control", 2, NativeManager.requests.size());

                    Runnable[] events = {
                        () -> page(a), // Old response delivered after B's cell is bound.
                        () -> complete(a, cell), // Old native translation finishes after B.
                        CommentOwnershipReplayTest::drain // Includes posted A and B cell hooks.
                    };
                    for (int event : order) {
                        events[event].run();
                        cell.assertShowing(b);
                        assertRequestsHaveMatchingOwners();
                    }
                    drain();
                    cell.assertShowing(b);
                    assertEquals("a late A event must not redispatch an already pending batch",
                            2, NativeManager.requests.size());
                    assertEquals(tools ? 1 : 0, cell.links().length);
                    if (tools) assertEquals("https://example.org/video-b", cell.links()[0].getURL());

                    complete(b, cell); // A current-CID completion really does update this view.
                    assertEquals("translated " + b.original, cell.text.getText().toString());
                }
            }
        }
    }

    @Test public void delayedVisibleFallbackReadsTheReusedManagersCurrentFields() {
        try (var activity = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Cell cell = new Cell(activity.get());
            Manager manager = new Manager(new ReplayComment("video-a", "comment-a"));
            cell.bind(manager);
            manager.bind(new ReplayComment("video-b", "comment-b"));
            cell.bind(manager);
            ReplayComment c = new ReplayComment("video-c", "comment-c");
            manager.bind(c);
            cell.bind(manager);
            assertTrue(NativeManager.requests.isEmpty());
            drain();
            cell.assertShowing(c);
            assertEquals(1, NativeManager.requests.size());
            assertEquals(List.of("comment-c"), NativeManager.requests.get(0).cids);
            assertRequestsHaveMatchingOwners();
        }
    }

    @Test public void returningToAnOlderVideoSelectsItsBatchRatherThanTheLatestResponse() {
        ReplayComment a = new ReplayComment("video-a", "comment-a");
        ReplayComment b = new ReplayComment("video-b", "comment-b");
        page(a);
        page(b);
        CommentBatchTranslator.registerCommentCell(new TextView(
                org.robolectric.RuntimeEnvironment.getApplication()), new Manager(a));
        assertEquals(1, NativeManager.requests.size());
        assertEquals(List.of("comment-a"), NativeManager.requests.get(0).cids);
        assertRequestsHaveMatchingOwners();
    }

    @Test public void responseFilteringRemovesOnlyFromTheSuppliedVideoPage() {
        Settings.COMMENT_KEYWORD_FILTER.save(true);
        Settings.COMMENT_BLOCKED_KEYWORDS.save("remove-this-fixture");
        ReplayComment a = new ReplayComment("video-a", "comment-a");
        ReplayComment b = new ReplayComment("video-b", "comment-b");
        ReplayComment excluded = new ReplayComment("video-b", "comment-excluded");
        excluded.text = "remove-this-fixture";
        Page first = new Page(a);
        Page second = new Page(b, excluded);
        CommentTools.onCommentListLoaded(first);
        CommentTools.onCommentListLoaded(second);
        CommentBatchTranslator.onCommentListLoaded(second);
        CommentTools.onCommentListLoaded(first); // Late A response, independently filtered.
        assertEquals(List.of(a), first.items);
        assertEquals(List.of(b), second.items);
        assertSame(b, second.items.get(0));
    }

    @Test public void mixedOwnerInputProbeShowsWhyContextReuseStillNeedsNativeEvidence() {
        // Deliberately inconsistent input, not a claim that TikTok creates it. The native
        // 0HFr context has mutable String fields, but the real binding/lifetime is not modeled.
        // This characterizes a blind spot instead of claiming the stale-panel bug is reproduced.
        ReplayComment a = new ReplayComment("video-a", "comment-a");
        Manager manager = new Manager(a);
        page(a);
        manager.context.LIZIZ = "video-b";
        CommentBatchTranslator.registerCommentCell(new TextView(
                org.robolectric.RuntimeEnvironment.getApplication()), manager);
        assertEquals(1, NativeManager.requests.size());
        Request request = NativeManager.requests.get(0);
        assertEquals("video-b", request.contextAid);
        assertEquals(List.of("video-a"), request.commentAids);
    }

    @Test public void ownershipOracleRejectsAnIntentionallyStaleNativeBind() {
        try (var activity = Robolectric.buildActivity(Activity.class).setup().visible()) {
            Cell cell = new Cell(activity.get());
            ReplayComment a = new ReplayComment("video-a", "comment-a");
            ReplayComment b = new ReplayComment("video-b", "comment-b");
            cell.bind(new Manager(b));
            cell.bind(new Manager(a)); // Fault injection stands in for a native stale adapter bind.
            AssertionError detected = assertThrows(AssertionError.class, () -> cell.assertShowing(b));
            assertNotNull(detected);
        }
    }

    private static void page(ReplayComment comment) {
        Page page = new Page(comment);
        CommentTools.onCommentListLoaded(page);
        CommentBatchTranslator.onCommentListLoaded(page);
        assertEquals(List.of(comment), page.items);
    }

    private static void complete(ReplayComment comment, Cell cell) {
        CommentBatchTranslator.onNativeBatchComplete(new Completion(comment));
        // Native 17RD mutates requested models and publishes by CID, not by current video.
        comment.text = "translated " + comment.original;
        comment.translated = true;
        if (cell.bound.getCid().equals(comment.getCid())) cell.render(comment);
    }

    private static void assertRequestsHaveMatchingOwners() {
        for (Request request : NativeManager.requests) {
            for (String aid : request.commentAids) assertEquals(request.contextAid, aid);
        }
    }

    private static final class Cell {
        final LinearLayout root;
        final TextView text;
        ReplayComment bound;
        Cell(Activity activity) {
            root = new LinearLayout(activity);
            text = new TextView(activity);
            root.addView(text);
            activity.setContentView(root);
        }
        void bind(Manager manager) {
            bound = manager.comment;
            CommentTools.registerCommentCell(root, manager);
            CommentBatchTranslator.registerCommentCell(root, manager);
            render(bound);
        }
        void render(ReplayComment comment) {
            text.setText(comment.text);
            CommentLinks.onTextBound(text, comment);
        }
        void assertShowing(ReplayComment expected) {
            assertSame("a previous video's model was rebound", expected, bound);
            assertEquals("a previous video's text replaced the row", expected.text, text.getText().toString());
            assertEquals(android.view.View.VISIBLE, root.getVisibility());
        }
        URLSpan[] links() {
            CharSequence shown = text.getText();
            return shown instanceof Spanned
                    ? ((Spanned) shown).getSpans(0, shown.length(), URLSpan.class) : new URLSpan[0];
        }
    }

    public static final class ReplayComment {
        final String aid, cid, original;
        String text;
        boolean translated;
        ReplayComment(String aid, String cid) {
            this.aid = aid;
            this.cid = cid;
            text = original = "comment on " + aid + " https://example.org/" + aid;
        }
        public String getAwemeId() { return aid; }
        public String getCid() { return cid; }
        public String getText() { return text; }
        public Object getUser() { return null; }
        public boolean isTranslated() { return translated; }
        public String getCommentLanguage() { return "zh"; }
    }
    public static final class Page {
        public final List<ReplayComment> items;
        Page(ReplayComment... comments) { items = new ArrayList<>(Arrays.asList(comments)); }
    }
    public static final class Context {
        public String LIZIZ;
        Context(String aid) { LIZIZ = aid; }
    }
    public static final class Manager {
        public ReplayComment comment;
        public Context context;
        public final NativeManager nativeManager = new NativeManager();
        Manager(ReplayComment comment) { bind(comment); }
        void bind(ReplayComment comment) {
            this.comment = comment;
            context = new Context(comment.aid);
        }
    }
    private static final class Request {
        final String contextAid;
        final List<String> cids = new ArrayList<>(), commentAids = new ArrayList<>();
        Request(List<ReplayComment> comments, Context context) {
            contextAid = context.LIZIZ;
            for (ReplayComment comment : comments) {
                cids.add(comment.cid);
                commentAids.add(comment.aid);
            }
        }
    }
    public static final class NativeManager {
        static final List<Request> requests = new ArrayList<>();
        public static void translate(List<ReplayComment> comments, Context context, boolean force) {
            requests.add(new Request(comments, context));
        }
    }
    public static final class Completion {
        public final List<CommentBatchTranslatorTest.Translation> l0;
        public final Task l1;
        Completion(ReplayComment comment) {
            l0 = List.of(new CommentBatchTranslatorTest.Translation(comment.cid, "translated " + comment.original));
            l1 = new Task(comment);
        }
    }
    public static final class Task {
        public final List<ReplayComment> LIZ;
        Task(ReplayComment comment) { LIZ = List.of(comment); }
    }
}
