package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.text.style.URLSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * A web address in a comment, which TikTok draws as plain text, made tappable without taking
 * the cell's own tap away from it.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class CommentLinksTest {
    private Context context;

    /** Stands in for TikTok's Comment, whose text accessor keeps its name. */
    public static final class Comment {
        public final String text;

        Comment(String said) {
            text = said;
        }
    }

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        Settings.COMMENT_LINKS.save(true);
    }

    @After public void tearDown() {
        Settings.COMMENT_LINKS.resetToDefault();
    }

    private LinearLayout cell(String username, String body) {
        LinearLayout root = new LinearLayout(context);
        TextView name = new TextView(context);
        name.setText(username);
        TextView text = new TextView(context);
        text.setText(body);
        root.addView(name);
        root.addView(text);
        return root;
    }

    private static TextView body(LinearLayout cell) {
        return (TextView) cell.getChildAt(1);
    }

    private static URLSpan[] linksIn(TextView view) {
        CharSequence text = view.getText();
        return text instanceof Spanned
                ? ((Spanned) text).getSpans(0, text.length(), URLSpan.class)
                : new URLSpan[0];
    }

    @Test public void anAddressInACommentBecomesSomethingToTap() {
        LinearLayout cell = cell("baker", "the recipe is at https://example.com/buns if you want it");

        CommentLinks.apply(cell, new Comment("the recipe is at https://example.com/buns if you want it"));

        URLSpan[] links = linksIn(body(cell));
        assertEquals("the address in the comment was not made tappable", 1, links.length);
        assertEquals("https://example.com/buns", links[0].getURL());
    }

    @Test public void theUsernameIsNotMistakenForTheComment() {
        // Both views are text and both belong to the same cell. What separates them is that
        // the comment's own words and the address are in the same one.
        LinearLayout cell = cell("https://example.com", "no link here at all");

        CommentLinks.apply(cell, new Comment("no link here at all"));

        assertEquals("the username was linkified instead of the comment",
                0, linksIn((TextView) cell.getChildAt(0)).length);
        assertEquals(0, linksIn(body(cell)).length);
    }

    @Test public void aCommentWithNoAddressIsLeftExactlyAsItWas() {
        LinearLayout cell = cell("baker", "best buns I have ever had");
        CharSequence before = body(cell).getText();
        MovementMethod movement = body(cell).getMovementMethod();

        CommentLinks.apply(cell, new Comment("best buns I have ever had"));

        assertSame("an ordinary comment had its text rebuilt", before, body(cell).getText());
        assertSame("an ordinary comment was given a movement method", movement,
                body(cell).getMovementMethod());
    }

    @Test public void theSwitchOffLeavesTheCommentPlain() {
        Settings.COMMENT_LINKS.save(false);
        LinearLayout cell = cell("baker", "see https://example.com/buns");

        CommentLinks.apply(cell, new Comment("see https://example.com/buns"));

        assertEquals(0, linksIn(body(cell)).length);
    }

    @Test public void aTapThatMissedTheLinkIsLeftToTikTok() {
        // The whole reason for a movement method of our own. A plain LinkMovementMethod
        // consumes a release anywhere in the view, so TikTok's own tap to reply would go
        // missing on exactly the comments this is meant to improve.
        LinearLayout cell = cell("baker", "see https://example.com/buns");
        CommentLinks.apply(cell, new Comment("see https://example.com/buns"));
        TextView view = body(cell);
        assertNotNull(view.getMovementMethod());
        assertTrue(view.getMovementMethod() instanceof LinkMovementMethod);

        assertFalse("the comment text became clickable, so it consumes every touch",
                view.isClickable());
        assertFalse("the comment text became long clickable", view.isLongClickable());

        // No layout, so nothing can be under the touch. The answer has to be "not mine".
        assertNull("the test needs an unlaid-out view to stand for a touch off any link",
                view.getLayout());
        assertFalse("a touch that landed on no link was swallowed",
                dispatch(view, MotionEvent.ACTION_DOWN));
        assertFalse("a release that landed on no link was swallowed",
                dispatch(view, MotionEvent.ACTION_UP));
    }

    @Test public void aCellThatAlreadyHandlesItsOwnSpansIsNotChanged() {
        // TikTok's hashtags and mentions are clickable spans, so a cell that has them already
        // has a movement method. Replacing it would be changing how those behave.
        LinearLayout cell = cell("baker", "see https://example.com/buns");
        MovementMethod tikToksOwn = LinkMovementMethod.getInstance();
        body(cell).setMovementMethod(tikToksOwn);

        CommentLinks.apply(cell, new Comment("see https://example.com/buns"));

        assertEquals(1, linksIn(body(cell)).length);
        assertSame("TikTok's own movement method was replaced", tikToksOwn,
                body(cell).getMovementMethod());
    }

    private static boolean dispatch(View view, int action) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, 5, 5, 0);
        try {
            return view.dispatchTouchEvent(event);
        } finally {
            event.recycle();
        }
    }
}
