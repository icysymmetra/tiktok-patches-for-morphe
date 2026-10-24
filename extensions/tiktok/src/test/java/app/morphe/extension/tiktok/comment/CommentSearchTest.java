package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import static org.robolectric.Shadows.shadowOf;

import android.os.Looper;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.UiCapture;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.CommentsPreferenceCategory;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

/** Which comments a search leaves on screen, and what happens when it is cleared. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentSearchTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    public static final class TestActivity extends PreferenceActivity {
        @Override public void onCreate(android.os.Bundle state) {
            setTheme(android.R.style.Theme_Material_NoActionBar);
            super.onCreate(state);
        }
    }

    public static final class Author {
        public final String uniqueId, nickname;
        Author(String handle, String name) { uniqueId = handle; nickname = name; }
    }

    /** Stands in for TikTok's Comment, whose text and user accessors kept their names. */
    public static final class Comment {
        public final String text;
        public final Author user;
        Comment(String said, String handle, String name) {
            text = said;
            user = handle == null && name == null ? null : new Author(handle, name);
        }
    }

    @Test public void aCommentMatchesOnWhatItSaysOrWhoSaidIt() {
        Comment comment = new Comment("Where is this Filmed?", "traveller99", "Jo Traveller");

        // What it says, whatever case either side is typed in.
        assertTrue(CommentSearch.matches(comment, "filmed"));
        assertTrue(CommentSearch.matches(comment, "where is"));
        // The handle, and the name they show.
        assertTrue(CommentSearch.matches(comment, "traveller99"));
        assertTrue(CommentSearch.matches(comment, "jo trav"));
        // Something in none of the three.
        assertFalse(CommentSearch.matches(comment, "recipe"));

        // An empty box is not a search, so everything stays.
        assertTrue(CommentSearch.matches(comment, ""));
        assertTrue(CommentSearch.matches(new Comment(null, null, null), ""));
        // A comment we cannot read is not claimed to match.
        assertFalse(CommentSearch.matches(null, "filmed"));
        assertFalse(CommentSearch.matches(new Object(), "filmed"));
        assertFalse(CommentSearch.matches(new Comment(null, null, null), "filmed"));
        // A comment with no author still matches on its text.
        assertTrue(CommentSearch.matches(new Comment("filmed here", null, null), "filmed"));
    }

    @Test public void theBoxIsTrimmedAndFoldedOnce() {
        try {
            CommentSearch.setQuery("  Filmed  ");
            assertEquals("filmed", CommentSearch.query());
            CommentSearch.setQuery(null);
            assertEquals("", CommentSearch.query());
        } finally {
            CommentSearch.setQuery("");
        }
    }

    @Test public void rowsCollapseAndComeBackAtTheHeightTheyHad() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            CommentSearch.setQuery("");

            // A column holding the list, which is the shape the box needs to be added.
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout listView = new LinearLayout(activity);
            listView.setOrientation(LinearLayout.VERTICAL);
            column.addView(listView);
            activity.setContentView(column);

            View first = new View(activity), second = new View(activity);
            for (View row : new View[]{first, second}) {
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 120));
                listView.addView(row);
            }
            CommentSearch.onCellBound(first, new Comment("Where is this filmed?", "traveller99", "Jo"));
            CommentSearch.onCellBound(second, new Comment("Great recipe", "cook", "Sam"));
            shadowOf(Looper.getMainLooper()).idle();

            // Nothing typed: both rows stand at the height the list gave them.
            assertEquals(View.VISIBLE, first.getVisibility());
            assertEquals(View.VISIBLE, second.getVisibility());
            assertEquals(120, first.getLayoutParams().height);

            // The box went in above the list rather than into it.
            assertEquals(3, column.getChildCount());
            EditText box = (EditText) column.getChildAt(0);
            assertEquals(listView, column.getChildAt(2));

            box.setText("filmed");
            assertEquals(View.VISIBLE, first.getVisibility());
            // A list measures its children itself, so hiding one has to take its height too.
            assertEquals(View.GONE, second.getVisibility());
            assertEquals(0, second.getLayoutParams().height);

            // The author counts as well as the words.
            box.setText("cook");
            assertEquals(View.GONE, first.getVisibility());
            assertEquals(View.VISIBLE, second.getVisibility());
            assertEquals(120, second.getLayoutParams().height);

            // Cleared: everything comes back exactly as it was.
            box.setText("");
            assertEquals(View.VISIBLE, first.getVisibility());
            assertEquals(View.VISIBLE, second.getVisibility());
            assertEquals(120, first.getLayoutParams().height);
            assertEquals(120, second.getLayoutParams().height);

            // A row bound while a search is running arrives already narrowed.
            box.setText("recipe");
            View third = new View(activity);
            third.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            listView.addView(third);
            CommentSearch.onCellBound(third, new Comment("Nothing alike", "someone", "Someone"));
            assertEquals(View.GONE, third.getVisibility());
            // And the same row recycled onto a comment that does match is put right again.
            CommentSearch.onCellBound(third, new Comment("Best recipe here", "someone", "Someone"));
            assertEquals(View.VISIBLE, third.getVisibility());
            assertEquals(120, third.getLayoutParams().height);
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
        }
    }

    @Test
    @Config(qualifiers = "w480dp-h160dp-night-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void resultStatusShowsManyOneZeroAndClearedWithoutTakingFocus() throws Exception {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            CommentSearch.setQuery("");

            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setBackgroundColor(0xFF0B0B0F);
            LinearLayout listView = new LinearLayout(activity);
            listView.setOrientation(LinearLayout.VERTICAL);
            column.addView(listView);
            activity.setContentView(column);

            View first = new View(activity), second = new View(activity), third = new View(activity);
            for (View row : new View[]{first, second, third}) {
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 120));
                listView.addView(row);
            }
            CommentSearch.onCellBound(first,
                    new Comment("Great recipe", "cook", "Sam"));
            CommentSearch.onCellBound(second,
                    new Comment("Another recipe", "baker", "Lee"));
            CommentSearch.onCellBound(third,
                    new Comment("Where was this filmed?", "traveller99", "Jo"));
            shadowOf(Looper.getMainLooper()).idle();

            EditText box = (EditText) column.getChildAt(0);
            TextView status = column.findViewWithTag("comment_search_status");
            assertNotNull("the search field has no result status", status);
            assertEquals("the status is not directly below the field", status, column.getChildAt(1));
            assertEquals(listView, column.getChildAt(2));
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                    status.getAccessibilityLiveRegion());
            assertFalse("the result status entered focus order", status.isFocusable());
            assertEquals(View.GONE, status.getVisibility());
            assertEquals("", status.getText().toString());

            assertTrue(box.requestFocus());
            box.setText("recipe");
            assertEquals("2 results so far", status.getText().toString());
            assertEquals(View.VISIBLE, status.getVisibility());
            assertTrue("the many-result announcement stole search focus", box.hasFocus());

            box.setText("traveller");
            assertEquals("1 result so far", status.getText().toString());
            assertTrue("the one-result announcement stole search focus", box.hasFocus());

            box.setText("nothing-could-match");
            assertEquals("No matching comments. Try a different word, or clear the search "
                    + "with the X in the box.", status.getText().toString());
            assertTrue("the empty explanation stole search focus", box.hasFocus());
            UiCapture.save(column, "comment-search-no-results.png", 480, 160);

            box.setText("");
            assertEquals("", status.getText().toString());
            assertEquals(View.GONE, status.getVisibility());
            assertTrue("clearing the result announcement stole search focus", box.hasFocus());
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
        }
    }

    @Test public void theCountHoldsStillWhileTheReaderScrolls() {
        // It used to be a count of the rows the list had attached, so scrolling turned "1
        // result" into "2 results" into "no matching comments" while nothing was being
        // searched, and the status line is a live region, so a screen reader read out each of
        // those. It counts the comments the sheet has loaded instead: one per comment, added
        // when it loads, and scrolling past it again neither doubles it nor takes it away.
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            CommentSearch.setQuery("");

            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout listView = new LinearLayout(activity);
            listView.setOrientation(LinearLayout.VERTICAL);
            column.addView(listView);
            activity.setContentView(column);

            View first = new View(activity), recycled = new View(activity), replies = new View(activity);
            for (View row : new View[]{first, recycled, replies}) {
                row.setLayoutParams(new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, 120));
                listView.addView(row);
            }
            Comment matching = new Comment("Great recipe", "cook", "Sam");
            Comment other = new Comment("Where was this filmed?", "traveller99", "Jo");
            CommentSearch.onCellBound(first, matching);
            CommentSearch.onCellBound(recycled, other);
            CommentSearch.onReplyControlBound(replies, matching, 0);
            shadowOf(Looper.getMainLooper()).idle();

            EditText box = (EditText) column.getChildAt(0);
            TextView status = column.findViewWithTag("comment_search_status");
            assertNotNull("the search field has no result status", status);
            box.setText("recipe");
            assertEquals("a reply control counted as another matching comment",
                    "1 result so far", status.getText().toString());

            // A second comment loads. That is a real new match and the count moves.
            Comment second = new Comment("Best recipe here", "reader", "Ari");
            CommentSearch.onCellBound(recycled, second);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("2 results so far", status.getText().toString());

            // Now nothing new loads: rows are recycled onto comments already counted and one
            // scrolls off the list entirely. None of that is a change to what matched.
            CommentSearch.onCellBound(first, other);
            CommentSearch.onReplyControlBound(replies, second, 0);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("a recycled row changed the count", "2 results so far",
                    status.getText().toString());

            listView.removeView(recycled);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("scrolling a matching comment off screen uncounted it",
                    "2 results so far", status.getText().toString());

            listView.addView(recycled);
            CommentSearch.onCellBound(recycled, matching);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("scrolling back counted a comment twice", "2 results so far",
                    status.getText().toString());

            box.setText("nothing-could-match");
            assertEquals("No matching comments. Try a different word, or clear the search "
                    + "with the X in the box.", status.getText().toString());
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
        }
    }

    @Test public void theXInTheBoxClearsTheSearchForAFingerAndForAScreenReader() {
        // The empty state told the reader to clear the search and there was nothing on screen
        // that could. A compound drawable is not a view, so the action on the box itself is
        // what a screen reader gets, and it only exists while there is something to clear.
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            CommentSearch.setQuery("");

            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout listView = new LinearLayout(activity);
            listView.setOrientation(LinearLayout.VERTICAL);
            column.addView(listView);
            activity.setContentView(column);

            View row = new View(activity);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            listView.addView(row);
            CommentSearch.onCellBound(row, new Comment("Great recipe", "cook", "Sam"));
            shadowOf(Looper.getMainLooper()).idle();

            EditText box = column.findViewWithTag(CommentSearch.FIELD_TAG);
            assertNotNull(box);
            assertNull("an empty box offered something to clear",
                    box.getCompoundDrawablesRelative()[2]);
            assertFalse("an empty box offered a clear action", hasClearAction(box));

            box.setText("recipe");
            assertNotNull("nothing in the box could clear it",
                    box.getCompoundDrawablesRelative()[2]);
            assertTrue("a screen reader was offered no way to clear the search",
                    hasClearAction(box));

            assertTrue(box.performAccessibilityAction(0x0F0B0001, null));
            assertEquals("", box.getText().toString());
            assertEquals("", CommentSearch.query());
            assertNull("the cleared box kept its clear control",
                    box.getCompoundDrawablesRelative()[2]);
            assertEquals("the row stayed collapsed after the search was cleared",
                    View.VISIBLE, row.getVisibility());
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
        }
    }

    private static boolean hasClearAction(View view) {
        android.view.accessibility.AccessibilityNodeInfo info =
                android.view.accessibility.AccessibilityNodeInfo.obtain();
        view.onInitializeAccessibilityNodeInfo(info);
        for (android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction action
                : info.getActionList()) {
            if (action.getId() == 0x0F0B0001) return true;
        }
        return false;
    }

    @Test @Config(qualifiers = "de")
    public void germanOwnsTheNoMatchExplanation() {
        assertLocalizedNoMatch("Keine passenden Kommentare. Versuche es mit einem anderen Wort "
                + "oder lösche die Suche mit dem X im Feld.");
    }

    @Test @Config(qualifiers = "in-rID")
    public void indonesianOwnsTheNoMatchExplanation() {
        assertLocalizedNoMatch("Tidak ada komentar yang cocok. Coba kata lain atau hapus "
                + "pencarian dengan X di kotak.");
    }

    @Test @Config(qualifiers = "es")
    public void spanishOwnsTheNoMatchExplanation() {
        assertLocalizedNoMatch("No hay comentarios que coincidan. Prueba otra palabra o borra "
                + "la búsqueda con la X del campo.");
    }

    @Test @Config(qualifiers = "pt-rBR")
    public void brazilianPortugueseOwnsTheNoMatchExplanation() {
        assertLocalizedNoMatch("Nenhum comentário encontrado. Tente outra palavra ou limpe a "
                + "pesquisa com o X no campo.");
    }

    private static void assertLocalizedNoMatch(String expected) {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            CommentSearch.setQuery("");

            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout listView = new LinearLayout(activity);
            listView.setOrientation(LinearLayout.VERTICAL);
            column.addView(listView);
            activity.setContentView(column);

            View row = new View(activity);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            listView.addView(row);
            CommentSearch.onCellBound(row,
                    new Comment("Great recipe", "cook", "Sam"));
            shadowOf(Looper.getMainLooper()).idle();

            EditText box = column.findViewWithTag(CommentSearch.FIELD_TAG);
            TextView status = column.findViewWithTag(CommentSearch.STATUS_TAG);
            assertNotNull(box);
            assertNotNull(status);
            box.setText("nothing-could-match");
            assertEquals(expected, status.getText().toString());
            assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                    status.getAccessibilityLiveRegion());
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
        }
    }

    @Test public void theBoxWaitsUntilTheRowIsInTheList() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            CommentSearch.setQuery("");

            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout listView = new LinearLayout(activity);
            listView.setOrientation(LinearLayout.VERTICAL);
            column.addView(listView);
            activity.setContentView(column);

            // A list binds a row before it holds it, so this is the state every real bind runs
            // in: there is nothing above the row to find yet.
            View row = new View(activity);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            CommentSearch.onCellBound(row, new Comment("Great recipe", "cook", "Sam"));
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, column.getChildCount());

            // Once the list holds it, the box goes in above the list.
            listView.addView(row);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(3, column.getChildCount());
            assertTrue(column.getChildAt(0) instanceof EditText);
            assertEquals(listView, column.getChildAt(2));
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
        }
    }

    @Test public void theBoxLooksPastAWrapperForItsColumn() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            CommentSearch.setQuery("");

            // The list is not always a direct child of the column that stacks the sheet.
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            View heading = new View(activity);
            column.addView(heading);
            FrameLayout wrapper = new FrameLayout(activity);
            LinearLayout listView = new LinearLayout(activity);
            listView.setOrientation(LinearLayout.VERTICAL);
            wrapper.addView(listView);
            column.addView(wrapper);
            activity.setContentView(column);

            View row = new View(activity);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            listView.addView(row);
            CommentSearch.onCellBound(row, new Comment("Great recipe", "cook", "Sam"));
            shadowOf(Looper.getMainLooper()).idle();

            // Above the wrapper the list sits in, and below whatever was already there.
            assertEquals(4, column.getChildCount());
            assertEquals(heading, column.getChildAt(0));
            assertTrue(column.getChildAt(1) instanceof EditText);
            assertEquals(wrapper, column.getChildAt(3));
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
        }
    }

    @Test public void aSearchDoesNotFollowTheReaderToTheNextVideo() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            CommentSearch.setQuery("");

            LinearLayout root = new LinearLayout(activity);
            root.setOrientation(LinearLayout.VERTICAL);
            activity.setContentView(root);

            LinearLayout firstColumn = new LinearLayout(activity);
            firstColumn.setOrientation(LinearLayout.VERTICAL);
            LinearLayout firstList = new LinearLayout(activity);
            firstList.setOrientation(LinearLayout.VERTICAL);
            firstColumn.addView(firstList);
            root.addView(firstColumn);

            View row = new View(activity);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            firstList.addView(row);
            CommentSearch.onCellBound(row, new Comment("Great recipe", "cook", "Sam"));
            shadowOf(Looper.getMainLooper()).idle();
            ((EditText) firstColumn.getChildAt(0)).setText("recipe");
            assertEquals("recipe", CommentSearch.query());

            // The next video opens its comments with a list of its own.
            LinearLayout secondColumn = new LinearLayout(activity);
            secondColumn.setOrientation(LinearLayout.VERTICAL);
            LinearLayout secondList = new LinearLayout(activity);
            secondList.setOrientation(LinearLayout.VERTICAL);
            secondColumn.addView(secondList);
            root.addView(secondColumn);

            View other = new View(activity);
            other.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            secondList.addView(other);
            CommentSearch.onCellBound(other, new Comment("Nothing alike", "someone", "Someone"));
            shadowOf(Looper.getMainLooper()).idle();

            // The box starts empty, and the row the old search hid is back at its own height.
            assertEquals("", CommentSearch.query());
            assertEquals("", ((EditText) secondColumn.getChildAt(0)).getText().toString());
            assertEquals(View.VISIBLE, other.getVisibility());
            assertEquals(120, other.getLayoutParams().height);
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
        }
    }

    @Test public void theBoxLeavesWithTheSheetItBelongsTo() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(true);
            CommentSearch.setQuery("");

            // The column the box lands in can sit above the sheet and outlive it.
            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            activity.setContentView(column);
            LinearLayout listView = new LinearLayout(activity);
            listView.setOrientation(LinearLayout.VERTICAL);
            column.addView(listView);

            View row = new View(activity);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            listView.addView(row);
            CommentSearch.onCellBound(row, new Comment("Great recipe", "cook", "Sam"));
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(3, column.getChildCount());
            ((EditText) column.getChildAt(0)).setText("recipe");

            // The reader closes the comments.
            column.removeView(listView);
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("no box left over the feed", 0, column.getChildCount());
            assertEquals("and no search left running", "", CommentSearch.query());

            // The next sheet under the same column still gets one of its own.
            LinearLayout second = new LinearLayout(activity);
            second.setOrientation(LinearLayout.VERTICAL);
            column.addView(second);
            View other = new View(activity);
            other.setLayoutParams(new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 120));
            second.addView(other);
            CommentSearch.onCellBound(other, new Comment("Nothing alike", "someone", "Someone"));
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(3, column.getChildCount());
            assertTrue(column.getChildAt(0) instanceof EditText);
        } finally {
            CommentSearch.setQuery("");
            Settings.COMMENT_SEARCH.save(false);
        }
    }

    @Test public void nothingIsTouchedWhileTheSwitchIsOff() {
        try (var controller = Robolectric.buildActivity(android.app.Activity.class).setup().visible()) {
            var activity = controller.get();
            Utils.setContext(activity);
            Settings.COMMENT_SEARCH.save(false);
            CommentSearch.setQuery("filmed");

            LinearLayout column = new LinearLayout(activity);
            column.setOrientation(LinearLayout.VERTICAL);
            LinearLayout listView = new LinearLayout(activity);
            column.addView(listView);
            View row = new View(activity);
            row.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 120));
            listView.addView(row);

            CommentSearch.onCellBound(row, new Comment("Great recipe", "cook", "Sam"));
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(View.VISIBLE, row.getVisibility());
            assertEquals(120, row.getLayoutParams().height);
            // No box either, so the sheet looks exactly as TikTok drew it.
            assertEquals(1, column.getChildCount());
        } finally {
            CommentSearch.setQuery("");
        }
    }

    @Test public void theSwitchIsReachable() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            var activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.commentToolsEnabled = true;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("comment_search"));
        } finally {
            SettingsStatus.commentToolsEnabled = false;
        }
    }
}
