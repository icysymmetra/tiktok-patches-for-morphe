package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.*;

import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.CommentsPreferenceCategory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Exercises the same parsed comment page and nested replies that TikTok hands to the hook. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class CommentNameFilterTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    public static class User {
        public String uniqueId;
        public String nickname;
        User(String uniqueId, String nickname) {
            this.uniqueId = uniqueId;
            this.nickname = nickname;
        }
    }

    public static class Comment {
        public String text = "ordinary comment";
        public User user;
        public List<Comment> replyComments;
        Comment(String uniqueId, String nickname) { user = new User(uniqueId, nickname); }
    }

    public static class Page {
        public List<Comment> items;
        Page(Comment... comments) { items = new ArrayList<>(Arrays.asList(comments)); }
    }

    @Before public void setup() {
        Settings.COMMENT_KEYWORD_FILTER.save(true);
        Settings.COMMENT_BLOCKED_KEYWORDS.save("");
        Settings.COMMENT_BLOCKED_USERS.save("");
        Settings.COMMENT_BLOCKED_NAME_KEYWORDS.save("");
        Settings.HIDE_COMMENT_MEDIA.save(false);
        Settings.HIDE_COMMENT_POLLS.save(false);
        Settings.HIDE_COMMENT_REPLY_PREVIEWS.save(false);
    }

    @Test public void emojiAndCaseInsensitiveUsernamePartsHideOnlyMatchingAuthors() {
        Settings.COMMENT_BLOCKED_NAME_KEYWORDS.save("✝️, SPAM");
        Comment symbol = new Comment("lucas", "Lucas ✝️");
        Comment handle = new Comment("daily_spam_posts", "Ordinary name");
        Comment kept = new Comment("other", "Other name");
        kept.text = "spam ✝️ in the comment, not the name";
        Page page = new Page(symbol, handle, kept);

        CommentTools.onCommentListLoaded(page);

        assertEquals(List.of(kept), page.items);
    }

    @Test public void nameOnlyRulesFilterNestedRepliesAndSeparatelyLoadedReplyPages() {
        Settings.COMMENT_BLOCKED_NAME_KEYWORDS.save("spam");
        Comment parent = new Comment("parent", "Parent");
        Comment kept = new Comment("reply", "Reply");
        parent.replyComments = new ArrayList<>(List.of(new Comment("spam_reply", "Reply"), kept));
        Page thread = new Page(parent);
        Page replyPage = new Page(new Comment("reply2", "Spam replies"), kept);

        CommentTools.onCommentListLoaded(thread);
        CommentTools.onCommentListLoaded(replyPage);

        assertEquals(List.of(parent), thread.items);
        assertEquals(List.of(kept), parent.replyComments);
        assertEquals(List.of(kept), replyPage.items);
    }

    @Test public void existingExactNamesAndHandlesDoNotBecomeSubstringRules() {
        Settings.COMMENT_BLOCKED_USERS.save("@Alice, Lucas");
        Comment partialHandle = new Comment("super_alice", "Other");
        Comment partialName = new Comment("other", "Lucas ✝️");
        Page page = new Page(new Comment("ALICE", "Other"), new Comment("bob", "lucas"),
                partialHandle, partialName);

        CommentTools.onCommentListLoaded(page);

        assertEquals(List.of(partialHandle, partialName), page.items);
    }

    @Test public void compositeRulesMatchEachNameWithoutJoiningDifferentIdentityFields() {
        Settings.COMMENT_BLOCKED_NAME_KEYWORDS.save("\"cat\" & \"dog\"");
        Comment differentFields = new Comment("cat_account", "Dog person");
        Comment kept = new Comment("cat_only", "Cat person");
        Page page = new Page(differentFields, kept, new Comment("other", "Cat and dog"));

        CommentTools.onCommentListLoaded(page);

        assertEquals(List.of(differentFields, kept), page.items);
    }

    @Test public void exclusionsAndMultipleRulesUseTheExistingKeywordSyntax() {
        Settings.COMMENT_BLOCKED_NAME_KEYWORDS.save("\"cat\" !& \"dog\", promo");
        Comment exception = new Comment("other", "Cat and dog");
        Page page = new Page(new Comment("other", "Cat person"), exception,
                new Comment("daily_promo", "Other"));

        CommentTools.onCommentListLoaded(page);

        assertEquals(List.of(exception), page.items);
    }

    @Test public void disablingTheCommentFilterAlsoDisablesNameRules() {
        Settings.COMMENT_BLOCKED_NAME_KEYWORDS.save("spam");
        Settings.COMMENT_KEYWORD_FILTER.save(false);
        Settings.HIDE_COMMENT_MEDIA.save(true);
        Comment kept = new Comment("spam", "Spam");
        Page page = new Page(kept);

        CommentTools.onCommentListLoaded(page);

        assertEquals(List.of(kept), page.items);
    }

    @Test public void missingAuthorsAndUnfinishedRulesDoNotHideOrdinaryComments() {
        Settings.COMMENT_BLOCKED_NAME_KEYWORDS.save("\"cat\" & ");
        Comment noAuthor = new Comment(null, null);
        noAuthor.user = null;
        Comment blankAuthor = new Comment(null, null);
        Comment kept = new Comment("cat", "Cat");
        Page page = new Page(noAuthor, blankAuthor, kept);

        CommentTools.onCommentListLoaded(page);

        assertEquals(List.of(noAuthor, blankAuthor, kept), page.items);
    }

    @Test public void namesAndCommentTextHaveIndependentRuleLists() {
        Settings.COMMENT_BLOCKED_NAME_KEYWORDS.save("promo");
        Settings.COMMENT_BLOCKED_KEYWORDS.save("advert");
        Comment textMatch = new Comment("other", "Other");
        textMatch.text = "an advert";
        Comment nameMatch = new Comment("promo_account", "Other");
        Comment kept = new Comment("advert_account", "Other");
        kept.text = "promo in the comment only";
        Page page = new Page(textMatch, nameMatch, kept);

        CommentTools.onCommentListLoaded(page);

        assertEquals(List.of(kept), page.items);
    }

    @Test public void missingIdentityFieldsDoNotStopOtherAuthorsBeingFiltered() {
        Settings.COMMENT_BLOCKED_NAME_KEYWORDS.save("spam");
        Comment noAuthor = new Comment(null, null);
        noAuthor.user = null;
        Comment noNames = new Comment(null, null);
        Page page = new Page(noAuthor, noNames, new Comment(null, "Spam name"));

        CommentTools.onCommentListLoaded(page);

        assertEquals(List.of(noAuthor, noNames), page.items);
    }

    public static class SettingsHost extends PreferenceActivity { }

    @Test public void nameRulesAreEditableInTheExistingCommentsPageOnlyWithCommentTools() {
        boolean oldStatus = SettingsStatus.commentToolsEnabled;
        try (var host = Robolectric.buildActivity(SettingsHost.class).setup()) {
            SettingsHost activity = host.get();
            SettingsStatus.commentToolsEnabled = true;
            PreferenceScreen enabled = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, enabled);
            assertNotNull(enabled.findPreference(Settings.COMMENT_BLOCKED_NAME_KEYWORDS.key));
            assertNotNull(enabled.findPreference(Settings.COMMENT_BLOCKED_USERS.key));

            SettingsStatus.commentToolsEnabled = false;
            PreferenceScreen disabled = activity.getPreferenceManager().createPreferenceScreen(activity);
            new CommentsPreferenceCategory(activity, disabled);
            assertNull(disabled.findPreference(Settings.COMMENT_BLOCKED_NAME_KEYWORDS.key));
        } finally {
            SettingsStatus.commentToolsEnabled = oldStatus;
        }
    }
}
