package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentReplySearchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @Test public void replyControlsFollowTheirParentsWhenTheQueryChangesAndClears() {
        try (Sheet sheet = new Sheet()) {
            Comment laika = new Comment("one", "Why send Laika into space?", "koum");
            Comment recipe = new Comment("two", "Great recipe", "cook");
            View first = sheet.comment(laika);
            View firstReplies = sheet.reply(laika, 0);
            View second = sheet.comment(recipe);
            View secondReplies = sheet.reply(recipe, 0);
            EditText box = sheet.box();

            box.setText("laika");
            sheet.visible(first, 120);
            sheet.visible(firstReplies, 48);
            sheet.hidden(second);
            sheet.hidden(secondReplies);

            box.setText("cook");
            sheet.hidden(first);
            sheet.hidden(firstReplies);
            sheet.visible(second, 120);
            sheet.visible(secondReplies, 48);

            box.setText("");
            sheet.visible(first, 120);
            sheet.visible(firstReplies, 48);
            sheet.visible(second, 120);
            sheet.visible(secondReplies, 48);
        }
    }

    @Test public void aRecycledReplyControlUsesItsNewParentDuringTheSameSearch() {
        try (Sheet sheet = new Sheet()) {
            Comment laika = new Comment("one", "Laika in space", "koum");
            Comment recipe = new Comment("two", "Great recipe", "cook");
            sheet.comment(laika);
            sheet.comment(recipe);
            View reply = sheet.reply(laika, 0);
            EditText box = sheet.box();
            box.setText("laika");
            sheet.visible(reply, 48);

            // The same holder now belongs to another comment, without changing its neighbours.
            sheet.bindReply(reply, recipe, 0);
            sheet.hidden(reply);
            box.setText("recipe");
            sheet.visible(reply, 48);

            sheet.bindReply(reply, laika, 0);
            sheet.hidden(reply);
            box.setText("");
            sheet.visible(reply, 48);
        }
    }

    @Test public void clearingSearchDoesNotExpandAReplyControlCollapsedByTikTok() {
        try (Sheet sheet = new Sheet()) {
            Comment parent = new Comment("one", "Laika in space", "koum");
            sheet.comment(parent);
            View reply = sheet.reply(parent, 4);
            EditText box = sheet.box();
            assertEquals(0, reply.getLayoutParams().height);
            box.setText("unrelated");
            assertEquals(0, reply.getLayoutParams().height);
            box.setText("laika");
            assertEquals(0, reply.getLayoutParams().height);
            box.setText("");
            assertEquals("clearing search revealed TikTok's collapsed control", 0,
                    reply.getLayoutParams().height);

            // A later native bind can make this same control available again.
            sheet.bindReply(reply, parent, 0);
            sheet.visible(reply, 48);
        }
    }

    @Test public void disablingSearchReleasesAReboundReplyWithoutChangingNativeOrUntouchedRows() {
        try (Sheet sheet = new Sheet()) {
            Comment parent = new Comment("one", "Laika in space", "koum");
            View reused = sheet.reply(parent, 0);
            View stillCollapsed = sheet.reply(parent, 4);
            sheet.box();
            sheet.bindReply(reused, parent, 4);
            sheet.hidden(reused);

            Settings.COMMENT_SEARCH.save(false);

            sheet.bindReply(stillCollapsed, parent, 4);
            assertEquals("disabling search expanded a control TikTok still owns", 0,
                    stillCollapsed.getLayoutParams().height);

            for (int visibility : new int[]{View.INVISIBLE, View.GONE}) {
                View untouched = sheet.row(48);
                untouched.setVisibility(visibility);
                sheet.bindReply(untouched, parent, 0);
                assertEquals("the disabled hook changed a row it never hid", visibility,
                        untouched.getVisibility());
                assertEquals(48, untouched.getLayoutParams().height);
            }

            // Q5 restores WRAP_CONTENT when the native control becomes available again.
            // It does not restore the itemView visibility changed by search.
            reused.getLayoutParams().height = ViewGroup.LayoutParams.WRAP_CONTENT;
            reused.setLayoutParams(reused.getLayoutParams());
            CommentSearch.onReplyControlBound(reused, parent, 0);
            assertEquals("disabled search kept the reused reply hidden", View.VISIBLE,
                    reused.getVisibility());
            assertEquals("the current native height was replaced by a cached search height",
                    ViewGroup.LayoutParams.WRAP_CONTENT, reused.getLayoutParams().height);
        }
    }

    @Test public void disablingSearchRestoresReboundCommentBodiesWithEitherBlockSetting() {
        int[] originalHeights = {120, 196};
        int[] restoredHeights = new int[2];
        int[] restoredVisibility = new int[2];
        for (int index = 0; index < originalHeights.length; index++) {
            try (Sheet sheet = new Sheet()) {
                View body = sheet.row(originalHeights[index]);
                CommentTools.registerCommentCell(body,
                        new CellState(new Comment("one", "Laika in space", "koum")));
                sheet.box().setText("unrelated");
                sheet.hidden(body);

                Settings.COMMENT_SEARCH.save(false);
                Settings.BLOCK_FROM_COMMENT.save(index == 1);
                CellState replacement = new CellState(new Comment("two", "Great recipe", "cook"));
                // The native body bind changes its model and children, not the root row height.
                CommentTools.registerCommentCell(body, replacement);

                View nativeCollapsed = sheet.row(0);
                nativeCollapsed.setVisibility(View.GONE);
                CommentTools.registerCommentCell(nativeCollapsed, replacement);
                View nativeInvisible = sheet.row(73);
                nativeInvisible.setVisibility(View.INVISIBLE);
                CommentTools.registerCommentCell(nativeInvisible, replacement);
                shadowOf(Looper.getMainLooper()).idle();

                assertEquals("an untouched native collapse was expanded", 0,
                        nativeCollapsed.getLayoutParams().height);
                assertEquals(View.GONE, nativeCollapsed.getVisibility());
                assertEquals(73, nativeInvisible.getLayoutParams().height);
                assertEquals(View.INVISIBLE, nativeInvisible.getVisibility());
                restoredHeights[index] = body.getLayoutParams().height;
                restoredVisibility[index] = body.getVisibility();
            }
        }
        // Collect both caller routes before asserting, so blocking-on is exercised even on baseline.
        assertArrayEquals("disabled search kept body heights collapsed (blocking off, then on)",
                originalHeights, restoredHeights);
        assertArrayEquals("disabled search kept body visibility hidden (blocking off, then on)",
                new int[]{View.VISIBLE, View.VISIBLE}, restoredVisibility);
    }

    private static final class Sheet implements AutoCloseable {
        final ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class)
                .setup().visible();
        final Activity activity = controller.get();
        final LinearLayout column = new LinearLayout(activity);
        final LinearLayout list = new LinearLayout(activity);

        Sheet() {
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            Settings.BLOCK_FROM_COMMENT.save(false);
            CommentSearch.setQuery("");
            column.setOrientation(LinearLayout.VERTICAL);
            list.setOrientation(LinearLayout.VERTICAL);
            column.addView(list);
            activity.setContentView(column);
        }

        View row(int height) {
            View row = new View(activity);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, height));
            list.addView(row);
            return row;
        }

        View comment(Comment comment) {
            View row = row(120);
            CommentTools.registerCommentCell(row, new CellState(comment));
            return row;
        }

        View reply(Comment parent, int nativeState) {
            View row = row(nativeState == 4 ? 0 : 48);
            bindReply(row, parent, nativeState);
            return row;
        }

        void bindReply(View row, Comment parent, int nativeState) {
            // Q5 runs before the injected return hook and writes zero for render state4.
            // It does not restore the visibility search owns when a holder is reused.
            row.getLayoutParams().height = nativeState == 4 ? 0 : 48;
            row.setLayoutParams(row.getLayoutParams());
            CommentSearch.onReplyControlBound(row, parent, nativeState);
        }

        EditText box() {
            shadowOf(Looper.getMainLooper()).idle();
            assertTrue(column.getChildAt(0) instanceof EditText);
            return (EditText) column.getChildAt(0);
        }

        void visible(View row, int height) {
            assertEquals(View.VISIBLE, row.getVisibility());
            assertEquals(height, row.getLayoutParams().height);
        }

        void hidden(View row) {
            assertEquals(View.GONE, row.getVisibility());
            assertEquals("hidden rows must not leave space", 0, row.getLayoutParams().height);
        }

        @Override public void close() {
            controller.close();
            CommentSearch.setQuery("");
        }
    }

    public static final class CellState {
        public final Comment comment;
        CellState(Comment comment) { this.comment = comment; }
    }

    public static final class Comment {
        private final String cid;
        private final String text;
        private final Author user;
        Comment(String cid, String text, String handle) {
            this.cid = cid;
            this.text = text;
            this.user = new Author(handle);
        }
        public String getCid() { return cid; }
        public String getText() { return text; }
        public Author getUser() { return user; }
    }

    public static final class Author {
        private final String uniqueId;
        Author(String uniqueId) { this.uniqueId = uniqueId; }
        public String getUniqueId() { return uniqueId; }
    }
}
