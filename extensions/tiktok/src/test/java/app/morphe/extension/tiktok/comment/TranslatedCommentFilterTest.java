package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Arrays;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The keyword filter's reach over comments TikTok shows translated.
 *
 * <p>The page filter judges the original text when the page loads. A translation lands later,
 * in the same member, so a blocked phrase the original lacked appeared on screen. The filter
 * now judges the translation when the batch completes and the shown text at every bind.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class TranslatedCommentFilterTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private static final String FAMILY = "comment keyword filter";

    /** Stands in for the comment model: the page filter's members plus the translation ones. */
    public static class Comment {
        public final String cid;
        public String text;
        public String translatedText;
        public boolean isTranslated;

        Comment(String cid, String text) {
            this.cid = cid;
            this.text = text;
        }

        public String getCid() { return cid; }
        public String getText() { return text; }
        public String getTranslatedText() { return translatedText; }
        public boolean isTranslated() { return isTranslated; }
    }

    /** A host build whose comment no longer says whether it is showing a translation. */
    public static class CommentWithoutFlag {
        public final String cid = "cid-flagless";
        public String getCid() { return cid; }
        public String getText() { return "the cat"; }
    }

    /** Stands in for TikTok's TranslationResult, the shape the completion runner carries. */
    public static class Result {
        public final String contentId;
        public final String translatedContent;

        Result(String contentId, String translatedContent) {
            this.contentId = contentId;
            this.translatedContent = translatedContent;
        }
    }

    /** A host build whose result lost the member the filter reads. */
    public static class ResultWithoutContent {
        public final String contentId = "cid-1";
    }

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.COMMENT_KEYWORD_FILTER.save(true);
        Settings.COMMENT_BLOCKED_KEYWORDS.save("the");
        HookStatus.clear();
        TranslatedCommentFilter.resetForTests();
    }

    @After public void tearDown() {
        Settings.COMMENT_KEYWORD_FILTER.save(false);
        Settings.COMMENT_BLOCKED_KEYWORDS.save("");
        Settings.BLOCK_FROM_COMMENT.resetToDefault();
        Settings.COMMENT_LINKS.resetToDefault();
        Settings.COMMENT_BATCH_TRANSLATION.resetToDefault();
        HookStatus.clear();
        TranslatedCommentFilter.resetForTests();
    }

    @Test public void aTranslatedCommentShowingABlockedPhraseCollapsesItsCell() {
        Comment comment = new Comment("cid-1", "the cat");
        comment.isTranslated = true;
        View cell = cell();

        TranslatedCommentFilter.onCellBound(cell, comment);

        assertCollapsed(cell);
    }

    @Test public void anUntranslatedCommentIsLeftToThePageFilter() {
        // The page filter judged this text when the page loaded; if it is still here, the list
        // could not be changed, and hiding it at bind would be a second feature.
        View cell = cell();

        TranslatedCommentFilter.onCellBound(cell, new Comment("cid-1", "the cat"));

        assertShowing(cell);
    }

    @Test public void aRecycledCellGetsItsSizeBackForACleanComment() {
        Comment blocked = new Comment("cid-1", "the cat");
        blocked.isTranslated = true;
        Comment clean = new Comment("cid-2", "a dog");
        clean.isTranslated = true;
        View cell = cell();

        TranslatedCommentFilter.onCellBound(cell, blocked);
        assertCollapsed(cell);
        TranslatedCommentFilter.onCellBound(cell, clean);

        assertShowing(cell);
    }

    @Test public void aTranslationThatAloneHoldsThePhraseCollapsesTheBoundCellWhenTheBatchLands() {
        // The original passed the page filter. The translation is judged before TikTok writes
        // it into the comment, and the cell showing that comment goes with it, while the cell
        // of a comment whose translation is clean stays.
        Comment first = new Comment("cid-1", "el gato");
        Comment second = new Comment("cid-2", "un perro");
        View firstCell = cell();
        View secondCell = cell();
        TranslatedCommentFilter.onCellBound(firstCell, first);
        TranslatedCommentFilter.onCellBound(secondCell, second);

        TranslatedCommentFilter.onTranslationsReady(Arrays.asList(first, second),
                Arrays.asList(new Result("cid-1", "the cat"), new Result("cid-2", "a dog")));

        assertCollapsed(firstCell);
        assertShowing(secondCell);
    }

    @Test public void resultsWithoutIdsPairByPositionAsTikTokDoes() {
        Comment first = new Comment("cid-1", "el gato");
        Comment second = new Comment("cid-2", "un perro");
        View firstCell = cell();
        View secondCell = cell();
        TranslatedCommentFilter.onCellBound(firstCell, first);
        TranslatedCommentFilter.onCellBound(secondCell, second);

        TranslatedCommentFilter.onTranslationsReady(Arrays.asList(first, second),
                Arrays.asList(new Result(null, "a dog"), new Result(null, "the cat")));

        assertShowing(firstCell);
        assertCollapsed(secondCell);
    }

    @Test public void resultsWithoutIdsThatDoNotMatchTheCountAreNotGuessedAt() {
        // TikTok's own completion skips a batch whose result count differs from the request,
        // so pairing by position there would judge the wrong comment.
        Comment first = new Comment("cid-1", "el gato");
        Comment second = new Comment("cid-2", "un perro");
        View firstCell = cell();
        TranslatedCommentFilter.onCellBound(firstCell, first);

        TranslatedCommentFilter.onTranslationsReady(Arrays.asList(first, second),
                Arrays.asList(new Result(null, "the cat")));

        assertShowing(firstCell);
    }

    @Test public void aCellRecycledToAnotherCommentIsNotCollapsedForItsOldOne() {
        Comment first = new Comment("cid-1", "el gato");
        Comment second = new Comment("cid-2", "un perro");
        View cell = cell();
        TranslatedCommentFilter.onCellBound(cell, first);
        TranslatedCommentFilter.onCellBound(cell, second);

        TranslatedCommentFilter.onTranslationsReady(Arrays.asList(first),
                Arrays.asList(new Result("cid-1", "the cat")));

        assertShowing(cell);
    }

    @Test public void theSupplementTranslationIsJudgedByWhatTheCellShows() {
        // The supplement path keeps the original in text and puts the translation beside it.
        Comment comment = new Comment("cid-1", "el gato");
        comment.translatedText = "the cat";
        comment.isTranslated = true;
        View cell = cell();

        TranslatedCommentFilter.onCellBound(cell, comment);

        assertCollapsed(cell);
    }

    @Test public void theSwitchGoingOffGivesACollapsedCellBack() {
        Comment comment = new Comment("cid-1", "the cat");
        comment.isTranslated = true;
        View cell = cell();
        TranslatedCommentFilter.onCellBound(cell, comment);
        assertCollapsed(cell);

        Settings.COMMENT_KEYWORD_FILTER.save(false);
        TranslatedCommentFilter.onCellBound(cell, comment);

        assertShowing(cell);
        assertFalse(TranslatedCommentFilter.active());
    }

    @Test public void anEmptyWordListJudgesNothing() {
        Settings.COMMENT_BLOCKED_KEYWORDS.save("");
        Comment comment = new Comment("cid-1", "the cat");
        comment.isTranslated = true;
        View cell = cell();

        TranslatedCommentFilter.onCellBound(cell, comment);
        TranslatedCommentFilter.onTranslationsReady(Arrays.asList(comment),
                Arrays.asList(new Result("cid-1", "the cat")));

        assertShowing(cell);
        assertFalse(TranslatedCommentFilter.active());
    }

    @Test public void aHostThatLostTheTranslatedContentMemberIsNamedOnce() {
        Comment comment = new Comment("cid-1", "el gato");
        View cell = cell();
        TranslatedCommentFilter.onCellBound(cell, comment);

        List<Object> results = Arrays.asList(new ResultWithoutContent());
        TranslatedCommentFilter.onTranslationsReady(Arrays.asList(comment), results);
        TranslatedCommentFilter.onTranslationsReady(Arrays.asList(comment), results);

        assertShowing(cell);
        List<String> missing = HookStatus.missing(FAMILY);
        assertEquals("the lost member is not named exactly once: " + missing, 1, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains("translatedContent"));
    }

    @Test public void aCommentThatCannotSayWhetherItIsTranslatedIsNamed() {
        View cell = cell();

        TranslatedCommentFilter.onCellBound(cell, new CommentWithoutFlag());

        assertShowing(cell);
        List<String> missing = HookStatus.missing(FAMILY);
        assertEquals(missing.toString(), 1, missing.size());
        assertTrue(missing.toString(), missing.get(0).contains("isTranslated"));
    }

    /**
     * TikTok's completion collapses a cell, and the LiveData rebind that follows collapses it
     * again. The second collapse must not save the collapsed size as the original, or the cell
     * would stay hidden once it is given back.
     */
    @Test public void aCellCollapsedTwiceGetsItsOwnSizeBack() {
        Comment comment = new Comment("cid-1", "the cat");
        comment.isTranslated = true;
        View cell = cell();
        TranslatedCommentFilter.onCellBound(cell, comment);
        TranslatedCommentFilter.onTranslationsReady(Arrays.asList(comment),
                Arrays.asList(new Result("cid-1", "the cat")));
        TranslatedCommentFilter.onCellBound(cell, comment);
        assertCollapsed(cell);

        Settings.COMMENT_KEYWORD_FILTER.save(false);
        TranslatedCommentFilter.onCellBound(cell, comment);

        assertShowing(cell);
    }

    /**
     * TikTok's text completion applies results by position whatever ids they carry, so the
     * translation judged for a comment is the one that will land in it.
     */
    @Test public void resultsPairByPositionEvenWhenTheyCarryIds() {
        Comment first = new Comment("cid-1", "el gato");
        Comment second = new Comment("cid-2", "un perro");
        View firstCell = cell();
        View secondCell = cell();
        TranslatedCommentFilter.onCellBound(firstCell, first);
        TranslatedCommentFilter.onCellBound(secondCell, second);

        TranslatedCommentFilter.onTranslationsReady(Arrays.asList(first, second),
                Arrays.asList(new Result("cid-2", "the dog"), new Result("cid-1", "a cat")));

        assertCollapsed(firstCell);
        assertShowing(secondCell);
    }

    /** A batch whose count differs is skipped by TikTok whatever its ids say, and so here. */
    @Test public void resultsWithIdsThatDoNotMatchTheCountAreNotApplied() {
        Comment first = new Comment("cid-1", "el gato");
        Comment second = new Comment("cid-2", "un perro");
        View firstCell = cell();
        TranslatedCommentFilter.onCellBound(firstCell, first);

        TranslatedCommentFilter.onTranslationsReady(Arrays.asList(first, second),
                Arrays.asList(new Result("cid-1", "the cat")));

        assertShowing(firstCell);
    }

    @Test public void aMissingResultKeepsItsPlace() {
        Comment first = new Comment("cid-1", "el gato");
        Comment second = new Comment("cid-2", "un perro");
        View firstCell = cell();
        View secondCell = cell();
        TranslatedCommentFilter.onCellBound(firstCell, first);
        TranslatedCommentFilter.onCellBound(secondCell, second);

        TranslatedCommentFilter.onTranslationsReady(Arrays.asList(first, second),
                Arrays.asList(null, new Result("cid-2", "the dog")));

        assertShowing(firstCell);
        assertCollapsed(secondCell);
    }

    /**
     * A batch TikTok translated on its own, finishing through the text completion Comment tools
     * hooks: the runner is read the way the translator reads it and the cell showing the blocked
     * translation goes before TikTok writes it in, with the translation switch off.
     */
    @Test public void aFinishedTextBatchIsJudgedThroughTikTokRunner() {
        Settings.COMMENT_BATCH_TRANSLATION.save(false);
        Comment first = new Comment("cid-1", "el gato");
        Comment second = new Comment("cid-2", "un perro");
        View firstCell = cell();
        View secondCell = cell();
        TranslatedCommentFilter.onCellBound(firstCell, first);
        TranslatedCommentFilter.onCellBound(secondCell, second);

        TranslatedCommentFilter.onTextBatchComplete(new Runner(
                Arrays.asList(new Result("cid-1", "the cat"), new Result("cid-2", "a dog")),
                Arrays.asList(first, second)));

        assertCollapsed(firstCell);
        assertShowing(secondCell);

        TranslatedCommentFilter.onTextBatchComplete(new Object());
        TranslatedCommentFilter.onTextBatchComplete(null);
    }

    /**
     * The production bind path is Comment tools', and it has to give a collapsed cell back once
     * the filter goes off, however the other comment tools are set, since a pooled cell keeps
     * its zero size until something restores it.
     */
    @Test public void theCommentToolsBindGivesACollapsedCellBackOnceTheFilterIsOff() {
        Settings.BLOCK_FROM_COMMENT.save(false);
        Settings.COMMENT_LINKS.save(false);
        CellComment comment = new CellComment("cid-1", "the cat");
        comment.isTranslated = true;
        View cell = cell();

        CommentTools.registerCommentCell(cell, new CellManager(comment));
        assertCollapsed(cell);

        Settings.COMMENT_KEYWORD_FILTER.save(false);
        CommentTools.registerCommentCell(cell, new CellManager(new CellComment("cid-2", "un perro")));

        assertShowing(cell);
        assertFalse(TranslatedCommentFilter.anyCollapsed());
    }

    /** The comment a bound cell's state holder carries, with the members Comment tools looks for. */
    public static class CellComment extends Comment {
        CellComment(String cid, String text) {
            super(cid, text);
        }

        public Object getUser() { return null; }
    }

    /** A bound cell's state holder, which is where Comment tools finds the comment. */
    public static class CellManager {
        public final Object comment;

        CellManager(Object comment) {
            this.comment = comment;
        }
    }

    /** TikTok's batch task: the comments it asked about. */
    public static class Task {
        public final List<Object> LIZ;

        Task(List<?> requested) {
            LIZ = new java.util.ArrayList<>(requested);
        }
    }

    /** TikTok's batch runner, as the completion hook is handed it: results, then the task. */
    public static class Runner {
        public final Object l0;
        public final Task l1;

        Runner(Object results, List<?> requested) {
            l0 = results;
            l1 = new Task(requested);
        }
    }

    private static View cell() {
        View cell = new View(RuntimeEnvironment.getApplication());
        cell.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return cell;
    }

    private static void assertCollapsed(View cell) {
        assertEquals("the cell is still shown", View.GONE, cell.getVisibility());
        assertEquals("the cell still takes its height", 0, cell.getLayoutParams().height);
        assertEquals("the cell still takes its width", 0, cell.getLayoutParams().width);
    }

    private static void assertShowing(View cell) {
        assertEquals("the cell is hidden", View.VISIBLE, cell.getVisibility());
        assertEquals("the cell lost its height", ViewGroup.LayoutParams.WRAP_CONTENT,
                cell.getLayoutParams().height);
        assertEquals("the cell lost its width", ViewGroup.LayoutParams.MATCH_PARENT,
                cell.getLayoutParams().width);
    }
}
