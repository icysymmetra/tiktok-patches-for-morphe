/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.InputTextPreference;
import app.morphe.extension.tiktok.settings.preference.SectionHeadingPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;

/**
 * Reading comments, hiding some of them, and blocking who wrote them. The page was one card
 * of thirteen rows in the order the patches arrived.
 */
@SuppressWarnings("deprecation")
public class CommentsPreferenceCategory extends ConditionalPreferenceCategory {
    public CommentsPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Comments");
    }

    /** Whether this page has anything on it. The row into it asks the same question. */
    public static boolean isAvailable() {
        return SettingsStatus.commentToolsEnabled
                || SettingsStatus.commentTranslationEnabled
                || SettingsStatus.doNotAutoTranslateEnabled
                || SettingsStatus.translateIntoEnabled
                || SettingsStatus.commentTranslateButtonEnabled
                || SettingsStatus.languagePageTranslationsEnabled
                || SettingsStatus.feedTranslationsEnabled
                || SettingsStatus.hideCommentQuickReactionsEnabled
                || SettingsStatus.copyCommentsWithoutUsernameEnabled
                || SettingsStatus.hideCommentEggsEnabled
                || SettingsStatus.commentSortControlsEnabled
                || SettingsStatus.lengthLimitsEnabled
                || SettingsStatus.voiceCommentsEnabled
                || SettingsStatus.commenterDetailsEnabled;
    }

    @Override
    public boolean getSettingsStatus() {
        return isAvailable();
    }

    @Override
    public void addPreferences(Context context) {
        boolean reading = SettingsStatus.commentTranslationEnabled
                || SettingsStatus.doNotAutoTranslateEnabled
                || SettingsStatus.translateIntoEnabled
                || SettingsStatus.commentTranslateButtonEnabled
                || SettingsStatus.languagePageTranslationsEnabled
                || SettingsStatus.feedTranslationsEnabled
                || SettingsStatus.commentSortControlsEnabled
                || SettingsStatus.commentToolsEnabled
                || SettingsStatus.copyCommentsWithoutUsernameEnabled
                || SettingsStatus.commenterDetailsEnabled;
        if (reading) {
            addPreference(new SectionHeadingPreference(context, "Reading"));
        }
        if (SettingsStatus.commentTranslationEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Auto translate comments",
                    "Translate comments as they load, using TikTok's own translator.",
                    Settings.COMMENT_BATCH_TRANSLATION
            ));
        }
        if (SettingsStatus.doNotAutoTranslateEnabled) {
            addPreference(new InputTextPreference(
                    context,
                    "Don't auto translate these languages",
                    "Separate language codes with commas, like es, de. When TikTok translates for you, "
                            + "captions and comments in these languages stay as they were written. "
                            + "See translation still works when you tap it. TikTok's own Don't translate "
                            + "list stays the way it is.",
                    Settings.DONT_AUTO_TRANSLATE_LANGUAGES)
                    .withCheck(app.morphe.extension.tiktok.translation.DoNotAutoTranslate::languageProblem)
                    .withNameKeyboard());
        }
        if (SettingsStatus.translateIntoEnabled) {
            addPreference(new InputTextPreference(
                    context,
                    "Translate into",
                    "One language code, like en or es. Comments and captions TikTok translates come out in "
                            + "this language instead of the app's. Leave it empty to keep TikTok's choice.",
                    Settings.TRANSLATE_INTO)
                    .withCheck(app.morphe.extension.tiktok.translation.TranslateInto::languageProblem)
                    .withNameKeyboard());
        }
        if (SettingsStatus.commentTranslateButtonEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Translate button in comments",
                    "Adds TikTok's translate button next to the comment count. One tap translates "
                            + "every comment, and another shows the originals again.",
                    Settings.COMMENT_TRANSLATE_BUTTON
            ));
        }
        if (SettingsStatus.languagePageTranslationsEnabled || SettingsStatus.feedTranslationsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Bring back TikTok's translation",
                    translationSummary(),
                    Settings.SHOW_TRANSLATION_SETTINGS
            ));
        }
        if (SettingsStatus.commentSortControlsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Full comment sort options",
                    "Shows TikTok's full sort menu on every post, with hot, newest, media and "
                            + "creator options, instead of the shorter one your account got. "
                            + "Restart TikTok to see the change.",
                    Settings.COMMENT_SORT_CONTROLS
            ));
        }
        if (SettingsStatus.commentToolsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Easier comment likes",
                    "Makes the heart easier to tap by using the empty space around it. The icon "
                            + "and spacing look the same, and Reply and other buttons keep "
                            + "their own space.",
                    Settings.LARGER_COMMENT_LIKE_TARGET
            ));
            addPreference(new TogglePreference(
                    context,
                    "Search within comments",
                    "Adds a search box that finds comments already loaded on this video, by "
                            + "words or username. It doesn't search all of TikTok.",
                    Settings.COMMENT_SEARCH
            ));
            addPreference(new TogglePreference(
                    context,
                    "Export comments",
                    "Adds Export CSV and Export JSON buttons under the search box, so Search "
                            + "within comments must be on. They save the comments and replies "
                            + "loaded so far to a file you pick. Open the reply threads you "
                            + "want first.",
                    Settings.COMMENT_EXPORT
            ));
            addPreference(new TogglePreference(
                    context,
                    "Links in comments open",
                    "A web address someone left in a comment can be tapped instead of copied "
                            + "out by hand. Tapping anywhere else in the comment still does what "
                            + "it did.",
                    Settings.COMMENT_LINKS
            ));
            addPreference(new TogglePreference(
                    context,
                    "More opens the comments",
                    "Tapping more under a video opens its comments with the whole caption at the top instead of "
                            + "spreading the caption over the video. A tap on the caption itself does the same. "
                            + "Photo posts keep TikTok's own page for this.",
                    Settings.CAPTION_OPENS_COMMENTS
            ));
            addPreference(new TogglePreference(
                    context,
                    "Caption above comments",
                    "The comment button also opens a video's comments with its caption at the top. The caption over the video goes away while this is on.",
                    Settings.CAPTION_ABOVE_COMMENTS
            ));
        }
        if (SettingsStatus.copyCommentsWithoutUsernameEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Copy comments without username",
                    "Copy only the comment text when using TikTok's copy comment action.",
                    Settings.COPY_COMMENTS_WITHOUT_USERNAME
            ));
        }
        if (SettingsStatus.commenterDetailsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Show commenters' handles",
                    "Show each commenter's @username instead of the display name they chose. A reply "
                            + "to a reply names the person it answers the same way once their own "
                            + "comment has loaded.",
                    Settings.SHOW_COMMENTER_HANDLE
            ));
            addPreference(new TogglePreference(
                    context,
                    "Show where commenters are from",
                    "Show the country a commenter's account is set to after the comment's time. "
                            + "Comments from accounts TikTok sends no country for stay as they are.",
                    Settings.SHOW_COMMENTER_REGION
            ));
            addPreference(new TogglePreference(
                    context,
                    "Color commenter names",
                    "Give each commenter's name a color of its own, the same one every time you see "
                            + "that account, so a long thread is easier to follow.",
                    Settings.COLOR_COMMENTER_NAMES
            ));
        }

        boolean hiding = SettingsStatus.hideCommentQuickReactionsEnabled
                || SettingsStatus.hideCommentEggsEnabled
                || SettingsStatus.commentToolsEnabled;
        if (hiding) {
            addPreference(new SectionHeadingPreference(context, "Hiding"));
        }
        if (SettingsStatus.hideCommentQuickReactionsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide comment typing suggestions",
                    "Hide the quick emoji row and automatic sticker suggestions that appear while you type. The emoji and sticker buttons still work.",
                    Settings.HIDE_COMMENT_QUICK_REACTIONS
            ));
        }
        if (SettingsStatus.hideCommentEggsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide comment popup ads",
                    "Stop the brand animation that plays over the comments when what someone typed matches an advertiser's trigger.",
                    Settings.HIDE_COMMENT_EGGS
            ));
        }
        if (SettingsStatus.commentToolsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide search suggestions above comments",
                    "Hide TikTok's \"Search: ...\" banner above the comment count. "
                            + "This doesn't hide the comments or Metra's comment-search box.",
                    Settings.HIDE_COMMENT_SEARCH_SUGGESTIONS
            ));
            addPreference(new TogglePreference(
                    context,
                    "Compact comment header",
                    "Hides the comment count, the sort and close buttons and the suggestions "
                            + "above them. Use Back or swipe down to close comments.",
                    Settings.COMPACT_COMMENT_HEADER
            ));
            addPreference(new TogglePreference(
                    context,
                    "Filter comments by keyword",
                    "Hide comments that contain any of the words below, or that come from the accounts below.",
                    Settings.COMMENT_KEYWORD_FILTER
            ));
            addPreference(new InputTextPreference(
                    context,
                    "Blocked comment words",
                    "Separate words with commas. A comment is hidden if it contains any of "
                            + "them. Capital letters don't matter. Write \"a\" & \"b\" to need "
                            + "both phrases, or \"a\" !& \"b\" to need the first without the "
                            + "second.",
                    Settings.COMMENT_BLOCKED_KEYWORDS
            ).withCheck(app.morphe.extension.tiktok.feedfilter.KeywordRules::problem));
            addPreference(new InputTextPreference(
                    context,
                    "Hidden commenters",
                    "Separate usernames or display names with commas. Their comments are hidden.",
                    Settings.COMMENT_BLOCKED_USERS
            ).withNameKeyboard());
            addPreference(new TogglePreference(
                    context,
                    "Hide picture comments",
                    "Hide comments made of an image or a sticker instead of words.",
                    Settings.HIDE_COMMENT_MEDIA
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide comment polls",
                    "Hide voting cards and poll results above the comments, including polls that have ended.",
                    Settings.HIDE_COMMENT_POLLS
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide reply previews",
                    "Don't open a reply under each comment. View replies still shows all of them.",
                    Settings.HIDE_COMMENT_REPLY_PREVIEWS
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide comment surveys",
                    "Hide the question cards TikTok sometimes puts in a comment list to ask what you think.",
                    Settings.HIDE_COMMENT_SURVEYS
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide comment box buttons",
                    "Hide the photo, @ and gift buttons in the box where you write a comment. "
                            + "Emoji and sending work as before.",
                    Settings.HIDE_COMMENT_BOX_BUTTONS
            ));
            addPreference(new TogglePreference(
                    context,
                    "Show poll results before voting",
                    "A comment poll shows how many picked each answer before you vote. Tapping an answer still votes.",
                    Settings.SHOW_POLL_RESULTS
            ));

            addPreference(new SectionHeadingPreference(context, "Blocking"));
            addPreference(new TogglePreference(
                    context,
                    "Thumbs down blocks the commenter",
                    "The thumbs down on each comment blocks the commenter instead of "
                            + "disliking. The comment dims, an undo banner follows, and a second tap "
                            + "unblocks.",
                    Settings.BLOCK_FROM_COMMENT
            ));
            addPreference(new TogglePreference(
                    context,
                    "Block from a comment's menu",
                    "Press and hold a comment to find Block this commenter in its menu. Their "
                            + "comments leave the list right away and an undo banner follows.",
                    Settings.BLOCK_FROM_COMMENT_MENU
            ));
        }
        if (SettingsStatus.lengthLimitsEnabled || SettingsStatus.voiceCommentsEnabled) {
            addPreference(new SectionHeadingPreference(context, "Writing"));
        }
        if (SettingsStatus.lengthLimitsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Lift text length limits",
                    "Let comments, repost notes and your bio run past the length TikTok's app stops at. "
                            + "TikTok's servers can still turn down a long one.",
                    Settings.LIFT_LENGTH_LIMITS
            ));
        }
        if (SettingsStatus.voiceCommentsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Allow voice comments",
                    "Show TikTok's button for recording a spoken comment, on accounts that don't "
                            + "have it yet. It hasn't been tried on a real account, so it may not work.",
                    Settings.ENABLE_VOICE_COMMENTS
            ));
        }
    }

    /** What the translation switch brings back, by which of its two hooks this build has. */
    static String translationSummary() {
        // Each one whole, so the translation tables' check finds it in the source as written.
        if (!SettingsStatus.feedTranslationsEnabled) {
            return "Brings back the translation rows on TikTok's Language page, under Settings and privacy, "
                    + "for accounts that only see App language there.";
        }
        if (!SettingsStatus.languagePageTranslationsEnabled) {
            return "For accounts TikTok took translation away from. Brings back See translation under video "
                    + "descriptions and in captions. Restart TikTok to see the change.";
        }
        return "For accounts TikTok took translation away from. Brings back See translation under video "
                + "descriptions and in captions, and the translation rows on the Language page in Settings "
                + "and privacy. Restart TikTok to see it on videos.";
    }
}
