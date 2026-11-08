package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.*;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

/**
 * Replays the native ownership boundary proven in 47.4.3: w33 reuses a processor, LIZIZ
 * cancels coroutine jobs, and an already posted 0Wwy retains its response's original aid.
 * The UI projection here is a fixture, not TikTok's adapter. DEX proof verifies the guard
 * is injected before the actual processor's own state writes and response hooks.
 */
public class CommentResponseGuardTest {
    @Test public void aPostedPreviousVideoResponseCannotReplaceTheNewVideosComments() {
        Processor processor = new Processor("a");
        processor.post("a", List.of("a-comment"), 7, false);
        processor.switchVideo("b");
        processor.deliver("b", List.of("b-comment"), 12, true);
        processor.drainPosted();
        assertEquals(List.of("b-comment"), processor.rows);
        assertEquals(12, processor.cursor);
        assertTrue(processor.hasMore);
        assertEquals(1, processor.extensionCalls);
    }

    @Test public void aPreviousVideosEmptyResponseCannotClearTheNewVideo() {
        Processor processor = new Processor("a");
        processor.post("a", List.of(), 0, false);
        processor.switchVideo("b");
        processor.deliver("b", List.of("b-comment"), 12, true);
        processor.drainPosted();
        assertEquals(List.of("b-comment"), processor.rows);
        assertEquals(12, processor.cursor);
    }

    @Test public void rejectingAnOldResponseDoesNotFinishTheCurrentRequest() {
        Processor processor = new Processor("a");
        processor.post("a", List.of("a-comment"), 7, false);
        processor.switchVideo("b");
        processor.loading = true;
        processor.drainPosted();
        assertTrue(processor.loading);
        assertEquals(0, processor.extensionCalls);
        processor.deliver("b", List.of("b-comment"), 12, true);
        assertFalse(processor.loading);
        assertEquals(List.of("b-comment"), processor.rows);
    }

    @Test public void twoCompletedRequestsAreSafeInEitherDeliveryOrder() {
        for (boolean oldFirst : new boolean[]{true, false}) {
            Processor processor = new Processor("b");
            Runnable old = () -> processor.deliver("a", List.of("a-comment"), 7, false);
            Runnable current = () -> processor.deliver("b", List.of("b-comment"), 12, true);
            if (oldFirst) { old.run(); current.run(); }
            else { current.run(); old.run(); }
            assertEquals(List.of("b-comment"), processor.rows);
            assertEquals(12, processor.cursor);
            assertEquals(1, processor.extensionCalls);
        }
    }

    @Test public void matchingIdsUseValueEqualityAcrossDifferentModelsAndStrings() {
        assertFalse(CommentResponseGuard.shouldDiscard(new Video(new String("same")), new String("same")));
        Processor processor = new Processor("same");
        processor.deliver("same", List.of(), 0, false);
        assertEquals(List.of(), processor.rows);
        assertEquals(1, processor.extensionCalls);
    }

    @Test public void unknownOwnershipKeepsNativeHandling() {
        assertFalse(CommentResponseGuard.shouldDiscard(null, "a"));
        assertFalse(CommentResponseGuard.shouldDiscard(new Video(null), "a"));
        assertFalse(CommentResponseGuard.shouldDiscard(new Video(""), "a"));
        assertFalse(CommentResponseGuard.shouldDiscard(new Video("a"), null));
        assertFalse(CommentResponseGuard.shouldDiscard(new Video("a"), ""));
    }

    @Test public void aNewerVideoWithTheSameOwnerIdRemainsValid() {
        Processor processor = new Processor("a");
        processor.post("a", List.of("same-video-comment"), 9, true);
        processor.switchVideo("a");
        processor.drainPosted();
        assertEquals(List.of("same-video-comment"), processor.rows);
        assertEquals(1, processor.extensionCalls);
    }

    @Test public void aResponseAcceptedBeforeTheSwitchCannotPublishAfterTheSwitch() {
        Processor processor = new Processor("a");
        processor.deferPublish = true;
        processor.deliver("a", List.of("a-comment"), 7, false);
        processor.switchVideo("b");
        processor.deferPublish = false;
        processor.deliver("b", List.of("b-comment"), 12, true);
        processor.drainPublishers();
        assertEquals(List.of("b-comment"), processor.rows);
        assertEquals(12, processor.cursor);
    }

    @Test public void aLateFailureCannotFinishTheNewVideosLoadingState() {
        Processor processor = new Processor("b");
        processor.loading = true;
        processor.fail("a");
        assertTrue(processor.loading);
        assertFalse(processor.error);
    }

    @Test public void theCurrentVideosFailureStillReachesNativeHandling() {
        Processor processor = new Processor("b");
        processor.loading = true;
        processor.fail("b");
        assertFalse(processor.loading);
        assertTrue(processor.error);
    }

    private static final class Video extends Aweme {
        private final String aid;
        Video(String aid) { this.aid = aid; }
        @Override public String getAid() { return aid; }
    }

    private static final class Processor {
        Video current;
        final ArrayDeque<Runnable> handler = new ArrayDeque<>();
        final ArrayDeque<Runnable> publishers = new ArrayDeque<>();
        List<String> rows = new ArrayList<>();
        int cursor, extensionCalls;
        boolean hasMore, loading, deferPublish, error;
        Processor(String aid) { current = new Video(aid); }
        void switchVideo(String aid) {
            current = new Video(aid);
            // Native reset cancels jobs, but does not remove already posted handler messages.
        }
        void post(String aid, List<String> rows, int cursor, boolean more) {
            handler.add(() -> deliver(aid, rows, cursor, more));
        }
        void drainPosted() { while (!handler.isEmpty()) handler.remove().run(); }
        void drainPublishers() { while (!publishers.isEmpty()) publishers.remove().run(); }
        void fail(String aid) {
            if (CommentResponseGuard.shouldDiscard(current, aid)) return;
            loading = false;
            error = true;
        }
        void deliver(String aid, List<String> incoming, int nextCursor, boolean more) {
            if (CommentResponseGuard.shouldDiscard(current, aid)) return;
            extensionCalls++;
            if (deferPublish) publishers.add(() -> publish(aid, incoming, nextCursor, more));
            else publish(aid, incoming, nextCursor, more);
        }
        void publish(String aid, List<String> incoming, int nextCursor, boolean more) {
            if (CommentResponseGuard.shouldDiscard(current, aid)) return;
            rows = new ArrayList<>(incoming);
            cursor = nextCursor;
            hasMore = more;
            loading = false;
        }
    }
}
