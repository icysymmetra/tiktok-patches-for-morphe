package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Set;

/**
 * A commenter blocked from one TikTok account isn't shown as blocked to the next account signed
 * in, where the first tap would have sent that account an unblock.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentBlockAccountsTest {
    /** Stands in for a comment: the tools read its user's uid by name. */
    public static final class Comment {
        public final User user;

        Comment(String uid) {
            user = new User(uid);
        }
    }

    public static final class User {
        public final String uid;

        User(String uid) {
            this.uid = uid;
        }
    }

    @After public void tearDown() throws Exception {
        blocked().clear();
        inFlight().clear();
        CommentTools.signedInUserIdForTests = null;
    }

    @Test public void aBlockBelongsToTheAccountThatMadeIt() throws Exception {
        CommentTools.signedInUserIdForTests = "account_a";
        blocked().add(blockKey("commenter"));
        assertTrue(isBlocked(new Comment("commenter")));

        CommentTools.signedInUserIdForTests = "account_b";
        assertFalse("another account saw the first account's block", isBlocked(new Comment("commenter")));

        CommentTools.signedInUserIdForTests = "account_a";
        assertTrue("switching back lost the block", isBlocked(new Comment("commenter")));
    }

    /**
     * One request per commenter at a time, and no more than that: a single flag for everyone
     * dropped the banner's Undo for one commenter while another commenter's request was pending.
     */
    @Test public void aRequestForOneCommenterHoldsOnlyThatCommenter() throws Exception {
        app.morphe.extension.shared.Utils.setContext(org.robolectric.RuntimeEnvironment.getApplication());
        CommentTools.signedInUserIdForTests = "account_a";
        android.view.View first = cellFor("commenter_x");
        android.view.View second = cellFor("commenter_y");

        toggle(first);
        toggle(first);
        assertTrue("the first tap sent nothing", inFlight().contains(blockKey("commenter_x")));
        assertTrue("a second tap on the same row sent a second request", inFlight().size() == 1);
        toggle(second);
        assertTrue("another commenter waited on the first one", inFlight().contains(blockKey("commenter_y")));

        // Without TikTok the requests fail fast and report back on the main thread.
        long deadline = System.currentTimeMillis() + 10_000;
        while (!inFlight().isEmpty() && System.currentTimeMillis() < deadline) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            Thread.sleep(20);
        }
        assertTrue("a finished request stayed in flight: " + inFlight(), inFlight().isEmpty());
        assertFalse("a failed block was recorded", isBlocked(new Comment("commenter_x")));
    }

    @SuppressWarnings("unchecked")
    private static android.view.View cellFor(String uid) throws Exception {
        android.view.View cell = new android.view.View(org.robolectric.RuntimeEnvironment.getApplication());
        Field field = CommentTools.class.getDeclaredField("CELL_COMMENTS");
        field.setAccessible(true);
        java.util.Map<android.view.View, Object> cells = (java.util.Map<android.view.View, Object>) field.get(null);
        synchronized (cells) {
            cells.put(cell, new Comment(uid));
        }
        return cell;
    }

    private static void toggle(android.view.View cell) throws Exception {
        Method method = CommentTools.class.getDeclaredMethod("toggleBlock", android.view.View.class);
        method.setAccessible(true);
        method.invoke(null, cell);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> inFlight() throws Exception {
        Field field = CommentTools.class.getDeclaredField("IN_FLIGHT");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    @SuppressWarnings("unchecked")
    private static Set<String> blocked() throws Exception {
        Field field = CommentTools.class.getDeclaredField("BLOCKED_UIDS");
        field.setAccessible(true);
        return (Set<String>) field.get(null);
    }

    private static String blockKey(String uid) throws Exception {
        Method method = CommentTools.class.getDeclaredMethod("blockKey", String.class);
        method.setAccessible(true);
        return (String) method.invoke(null, uid);
    }

    private static boolean isBlocked(Object comment) throws Exception {
        Method method = CommentTools.class.getDeclaredMethod("isBlocked", Object.class);
        method.setAccessible(true);
        return (Boolean) method.invoke(null, comment);
    }
}
