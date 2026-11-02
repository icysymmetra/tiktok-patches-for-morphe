package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.*;

import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import java.util.List;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The surprise TikTok's comment surprise struct is built with, which every popup ad path reads. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentPopupAdsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.HIDE_COMMENT_EGGS.resetToDefault();
        CommentTools.resetSurpriseOriginForTests();
        HookStatus.clear();
    }

    /**
     * A surprise sent with a comment you posted is TikTok's own only when it is the
     * first-comment kind. The keyword said nothing: a campaign's surprise with none written on
     * it played, and TikTok's own code never reads the keyword before playing.
     */
    @Test public void aPublishedSurpriseKeepsOnlyTheFirstCommentKind() {
        Settings.HIDE_COMMENT_EGGS.save(true);
        Surprise campaignWithoutAWord = new Surprise(3, null);
        CommentTools.surpriseFromPublish();
        assertNull("a published surprise that names no word is still a campaign's",
                CommentTools.commentSurprise(campaignWithoutAWord));
        Surprise firstComment = new Surprise(CommentTools.FIRST_COMMENT_SURPRISE, "#word");
        CommentTools.surpriseFromPublish();
        assertSame(firstComment, CommentTools.commentSurprise(firstComment));
    }

    /**
     * A surprise sent with a comment page is judged by the scene the page was fetched for: the
     * default scene is a campaign's whatever the server wrote on the surprise, and the one other
     * scene a page is fetched for, the author's own first comment (6), is TikTok's own
     * celebration whatever the keyword says.
     */
    @Test public void aCommentPageSurpriseIsJudgedByTheSceneThePageWasFetchedFor() {
        Settings.HIDE_COMMENT_EGGS.save(true);
        Surprise firstCommentShaped = new Surprise(CommentTools.FIRST_COMMENT_SURPRISE, null);
        CommentTools.surpriseFromPage(CommentTools.PAGE_SCENE_DEFAULT);
        assertNull("the default scene is a campaign's, whatever the type says",
                CommentTools.commentSurprise(firstCommentShaped));
        Surprise campaignShaped = new Surprise(3, "#summerdrop");
        CommentTools.surpriseFromPage(6);
        assertSame("the author's first comment scene is TikTok's own, whatever the keyword says",
                campaignShaped, CommentTools.commentSurprise(campaignShaped));
    }

    /**
     * An unmarked construction, which 46.2.3's image-comment publish is, is named on the
     * Diagnostics row only while the switch is on: with it off nothing is decided, so nothing
     * is missing.
     */
    @Test public void anUnmarkedSiteIsNamedOnlyWhileTheSwitchIsOn() {
        Settings.HIDE_COMMENT_EGGS.save(false);
        Surprise surprise = new Surprise(3, "#summerdrop");
        assertSame(surprise, CommentTools.commentSurprise(surprise));
        assertTrue("an unmarked site was named with the switch off: " + HookStatus.missing("comment popup ads"),
                HookStatus.missing("comment popup ads").isEmpty());

        Settings.HIDE_COMMENT_EGGS.save(true);
        CommentTools.commentSurprise(surprise);
        assertEquals(1, HookStatus.missing("comment popup ads").size());
    }

    @Test public void theMilestoneBuilderKeepsWhatItReplays() {
        Settings.HIDE_COMMENT_EGGS.save(true);
        Surprise replayed = new Surprise(3, "#summerdrop");
        CommentTools.surpriseFromMilestone();
        assertSame(replayed, CommentTools.commentSurprise(replayed));
    }

    @Test public void aMarkIsSpentByTheConstructionItWasSetFor() {
        Settings.HIDE_COMMENT_EGGS.save(true);
        Surprise campaign = new Surprise(3, "#summerdrop");
        CommentTools.surpriseFromMilestone();
        assertSame(campaign, CommentTools.commentSurprise(campaign));
        assertNull("the mark outlived the construction it was set for",
                CommentTools.commentSurprise(campaign));
    }

    /** A site the patch did not mark, which a host update could add, is judged by content and named on the Diagnostics row. */
    @Test public void anUnmarkedConstructionFallsBackToTheContentAndIsNamedOnce() {
        Settings.HIDE_COMMENT_EGGS.save(true);
        HookStatus.clear();
        Surprise firstComment = new Surprise(CommentTools.FIRST_COMMENT_SURPRISE, null);
        assertNull(CommentTools.commentSurprise(new Surprise(3, "#summerdrop")));
        assertSame(firstComment, CommentTools.commentSurprise(firstComment));
        List<String> missing = HookStatus.missing("comment popup ads");
        assertEquals("an unmarked site is not named exactly once: " + missing, 1, missing.size());
        assertTrue(missing.get(0), missing.get(0).contains("CommentSurpriseStruct"));
        assertTrue("the family is missing from the row: " + HookStatus.report(),
                HookStatus.report().stream().anyMatch(line -> line.startsWith("comment popup ads:")));
    }

    @Test public void theSwitchOffKeepsEveryPath() {
        Settings.HIDE_COMMENT_EGGS.save(false);
        Surprise campaign = new Surprise(3, "#summerdrop");
        CommentTools.surpriseFromPage(CommentTools.PAGE_SCENE_DEFAULT);
        assertSame(campaign, CommentTools.commentSurprise(campaign));
        CommentTools.surpriseFromPublish();
        assertSame(campaign, CommentTools.commentSurprise(campaign));
    }

    @Test public void theSwitchOnDropsAKeywordSurpriseAndOffKeepsIt() {
        Surprise brand = new Surprise(3, "#summerdrop");
        Settings.HIDE_COMMENT_EGGS.save(true);
        assertNull(CommentTools.commentSurprise(brand));
        Settings.HIDE_COMMENT_EGGS.save(false);
        assertSame(brand, CommentTools.commentSurprise(brand));
    }

    /** The review of 6346661c: nulling every surprise took TikTok's own first-comment celebration too. */
    @Test public void tiktoksFirstCommentCelebrationStaysWithTheSwitchOn() {
        Settings.HIDE_COMMENT_EGGS.save(true);
        Surprise firstComment = new Surprise(CommentTools.FIRST_COMMENT_SURPRISE, null);
        assertSame(firstComment, CommentTools.commentSurprise(firstComment));
        Surprise firstCommentNamingAWord = new Surprise(CommentTools.FIRST_COMMENT_SURPRISE, "first");
        assertSame("the first-comment type wins over a keyword",
                firstCommentNamingAWord, CommentTools.commentSurprise(firstCommentNamingAWord));
        Surprise noWord = new Surprise(2, "  ");
        assertSame("nothing a comment typed set it off", noWord, CommentTools.commentSurprise(noWord));
        Surprise noType = new Surprise(null, "#summerdrop");
        assertNull("a keyword with no type is still set off by words", CommentTools.commentSurprise(noType));
    }

    @Test public void noSurpriseStaysNone() {
        Settings.HIDE_COMMENT_EGGS.save(false);
        assertNull(CommentTools.commentSurprise(null));
        Settings.HIDE_COMMENT_EGGS.save(true);
        assertNull(CommentTools.commentSurprise(null));
    }

    @Test public void pausedTheSurpriseIsTikToks() {
        Surprise brand = new Surprise(3, "#summerdrop");
        Settings.HIDE_COMMENT_EGGS.save(true);
        PausedProcess.set(true);
        assertSame(brand, CommentTools.commentSurprise(brand));
    }

    /** TikTok's CommentSurprise, by the two fields the switch reads. */
    private static final class Surprise {
        public final Integer surpriseType;
        public final String keyword;

        Surprise(Integer surpriseType, String keyword) {
            this.surpriseType = surpriseType;
            this.keyword = keyword;
        }
    }
}
