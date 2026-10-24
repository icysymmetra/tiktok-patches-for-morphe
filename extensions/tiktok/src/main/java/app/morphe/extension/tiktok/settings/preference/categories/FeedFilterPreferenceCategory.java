/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/categories/FeedFilterPreferenceCategory.java
 * Mirror, since GitHub blocks the original: https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/categories/FeedFilterPreferenceCategory.java
 */

package app.morphe.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import java.text.NumberFormat;

import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.RangeValuePreference;
import app.morphe.extension.tiktok.settings.preference.InputTextPreference;
import app.morphe.extension.tiktok.settings.preference.AdoptSeenVideoHistoryPreference;
import app.morphe.extension.tiktok.settings.preference.ClearSeenVideoHistoryPreference;
import app.morphe.extension.tiktok.settings.preference.ImportSeenVideoHistoryPreference;
import app.morphe.extension.tiktok.settings.preference.SeenHistoryFilePreference;
import app.morphe.extension.tiktok.settings.preference.NumberInputPreference;
import app.morphe.extension.tiktok.settings.preference.CreatorListPreference;
import app.morphe.extension.tiktok.settings.preference.CalmFeedPresetPreference;
import app.morphe.extension.tiktok.settings.preference.SectionHeadingPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.feedfilter.AdvancedFeedRules;
import app.morphe.extension.tiktok.feedfilter.CreatorExceptions;

/**
 * What reaches the feed, in the order a reader asks: which kinds of post, how much of them,
 * from whom and with which sounds, which words and countries, what was already seen, and the
 * one advanced switch. The buttons that block or hide from a video are on Feed screen with every
 * other control drawn on the video, so one page answers what shows up there.
 */
@SuppressWarnings("deprecation")
public class FeedFilterPreferenceCategory extends ConditionalPreferenceCategory {
    public FeedFilterPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Feed filter");
    }

    /** Whether this page has anything on it. The row into it asks the same question. */
    public static boolean isAvailable() {
        return SettingsStatus.feedFilterEnabled
                || SettingsStatus.seenVideoFilterEnabled;
    }

    @Override
    public boolean getSettingsStatus() {
        return isAvailable();
    }

    @Override
    public void addPreferences(Context context) {
        // Each patch's rows go behind its own flag. The page is reachable when any of them
        // is in the bundle, and a page reachable because of one of them must not offer the
        // others' settings, which would sit there doing nothing.
        if (SettingsStatus.feedFilterEnabled) addQuickSetup(context);
        if (SettingsStatus.feedFilterEnabled) addAds(context);
        if (SettingsStatus.feedFilterEnabled) addKindsOfPost(context);
        if (SettingsStatus.feedFilterEnabled) addLimits(context);
        if (SettingsStatus.feedFilterEnabled) addCreatorsAndSounds(context);
        if (SettingsStatus.feedFilterEnabled) addWordsAndCountries(context);
        if (SettingsStatus.feedFilterEnabled && SettingsStatus.liveFeedFilterEnabled) addLiveFeed(context);
        if (SettingsStatus.seenVideoFilterEnabled) addSeenVideoRules(context);
        if (SettingsStatus.feedFilterEnabled) addAdvanced(context);
    }

    private void addQuickSetup(Context context) {
        addPreference(new SectionHeadingPreference(context, "Quick setup"));
        addPreference(new CalmFeedPresetPreference(context));
    }

    private void addKindsOfPost(Context context) {
        addPreference(new SectionHeadingPreference(context, "Kinds of post"));
        addPreference(new TogglePreference(
                context,
                "Hide TikTok Shop", "Hide TikTok Shop posts from the feed.",
                Settings.HIDE_SHOP
        ));
        addPreference(new TogglePreference(
                context,
                "Hide TikTok Shop in search", "Hide the Products block and product cards in search results.",
                Settings.HIDE_SEARCH_SHOP
        ));
        addPreference(new TogglePreference(
                context,
                "Hide LIVE videos", "Hide LIVE videos from the feed.",
                Settings.HIDE_LIVE
        ));
        addPreference(new TogglePreference(context, "Hide LIVE replays", "Hide recorded LIVE broadcasts from the feed.", Settings.HIDE_LIVE_REPLAYS));
        addPreference(new TogglePreference(
                context,
                "Hide unpersonalized For You videos",
                "Uses TikTok's unpersonalized fill-in marker. Covers full batches and cached copies.",
                Settings.HIDE_UNPERSONALIZED_FOR_YOU
        ));
        addPreference(new TogglePreference(
                context,
                "Show only mutual friends on the Friends tab",
                "On the Friends tab, hide posts, reposts and LIVEs from accounts that aren't a mutual follow, suggested accounts included. Your own posts stay.",
                Settings.FRIENDS_MUTUALS_ONLY
        ));
        addPreference(new TogglePreference(
                context,
                "Hide stories", "Hide stories from the feed.",
                Settings.HIDE_STORY
        ));
        addPreference(new TogglePreference(
                context,
                "Hide photo posts", "Hide photo posts from the feed.",
                Settings.HIDE_IMAGE
        ));
        addPreference(new TogglePreference(
                context,
                "Hide AI-generated videos",
                "Hide videos with TikTok's AI-generated label, AI content credentials, a TikTok AI effect "
                        + "such as AI Alive, or a tag like #ai or #aigenerated.",
                Settings.HIDE_AI_GENERATED
        ));
        addPreference(new TogglePreference(
                context,
                "Hide verified accounts",
                "Hide videos posted by verified accounts.",
                Settings.HIDE_VERIFIED
        ));
        addPreference(new TogglePreference(
                context,
                "Hide Series",
                "Hide videos that belong to a paid Series.",
                Settings.HIDE_SERIES
        ));
        addPreference(new TogglePreference(
                context,
                "Hide mini dramas",
                "Hide TikTok's short drama episodes and the cards that promote them.",
                Settings.HIDE_MINI_DRAMAS
        ));
        addPreference(new TogglePreference(
                context,
                "Hide playlist videos",
                "Hide videos posted as part of a playlist.",
                Settings.HIDE_PLAYLIST_VIDEOS
        ));
        // A kind of post, not an ad: its summary says it goes even when the badge isn't paid.
        addPreference(new TogglePreference(context, "Filter location-tagged videos",
                "Hide feed videos with place badges, even when they aren't paid ads. To keep the video and hide only its badge, use Hide location labels in Feed screen.",
                Settings.FILTER_LOCATION_VIDEOS));
    }

    private void addAds(Context context) {
        addPreference(new SectionHeadingPreference(context, "Ads"));
        addPreference(new TogglePreference(
                context,
                "Remove feed ads",
                "Remove feed ads, creator commission posts, paid partnerships, and branded content.",
                Settings.REMOVE_ADS
        ));
        addPreference(new TogglePreference(
                context,
                "Hide paid partnerships",
                "Hide videos marked as paid partnership or branded content.",
                Settings.HIDE_PAID_PARTNERSHIP
        ));
        addPreference(new TogglePreference(context, "Hide promotional music",
                "Hide videos marked as using promotional music.", Settings.HIDE_PROMOTIONAL_MUSIC));
    }

    private void addLimits(Context context) {
        addPreference(new SectionHeadingPreference(context, "Limits"));
        addPreference(new NumberInputPreference(context, "Minimum video length", "Seconds. Zero keeps every length. Photo posts and videos without a known length stay. If everything the feed just sent would be filtered out, the video closest to your limit is kept.", Settings.MIN_VIDEO_SECONDS, "%1$s second", "%1$s seconds").zeroMeansOff());
        addPreference(new NumberInputPreference(context, "Maximum video length", "Seconds. Zero keeps every length. If everything the feed just sent would be filtered out, the video closest to your limit is kept so the feed isn't empty.", Settings.MAX_VIDEO_SECONDS, "%1$s second", "%1$s seconds").zeroMeansOff());
        addPreference(new NumberInputPreference(context, "Maximum post age",
                "Days. Zero keeps every age. Posts without a usable timestamp, including future posts, stay visible.",
                Settings.MAX_PUBLICATION_AGE_DAYS, "%1$s day", "%1$s days").zeroMeansOff());
        addPreference(new NumberInputPreference(context, "Maximum views per like", "Hide videos with a lot of views and few likes. Lower numbers are stricter, zero turns the rule off, and one video is kept back if everything the feed just sent would go.", Settings.MAX_VIEWS_PER_LIKE, "%1$s view per like", "%1$s views per like").zeroMeansOff());
        addPreference(new NumberInputPreference(context, "Maximum views per comment", "Hide videos with a lot of views and few comments. Works the same way as views per like. Zero turns it off.", Settings.MAX_VIEWS_PER_COMMENT, "%1$s view per comment", "%1$s views per comment").zeroMeansOff());
        addPreference(new RangeValuePreference(
                context,
                "Views range", "Show only videos with this many views.",
                Settings.MIN_MAX_VIEWS
        ));
        addPreference(new RangeValuePreference(
                context,
                "Likes range", "Show only videos with this many likes.",
                Settings.MIN_MAX_LIKES
        ));
        addPreference(new RangeValuePreference(
                context,
                "Comments range", "Show only videos with this many comments.",
                Settings.MIN_MAX_COMMENTS
        ));
        addPreference(new RangeValuePreference(
                context,
                "Favorites range", "Show only videos with this many favorites.",
                Settings.MIN_MAX_FAVOURITES
        ));
        addPreference(new RangeValuePreference(
                context,
                "Shares range", "Show only videos with this many shares.",
                Settings.MIN_MAX_SHARES
        ));
    }

    private void addCreatorsAndSounds(Context context) {
        addPreference(new SectionHeadingPreference(context, "Creators and sounds"));
        if (SettingsStatus.feedFilterEnabled) {
            addPreference(new InputTextPreference(context, "Blocked creators", "Separate "
                    + "account handles or user IDs with commas. Videos from these accounts are "
                    + "always hidden. An entry between slashes, like /^news_/, is a search "
                    + "pattern checked against the handle and display name.", Settings.BLOCKED_CREATORS)
                    .withCheck(AdvancedFeedRules::creatorEntryProblem).withNameKeyboard());
            addPreference(new CreatorListPreference(context, "Creators hidden on this phone",
                    "Creators you hid from a video. Search the list and remove one at a time.",
                    Settings.LOCAL_HIDDEN_CREATORS));
            addPreference(new InputTextPreference(context, "Creator exceptions",
                    "Separate handles or user IDs with commas. Their videos stay when a filter "
                            + "on post type, labels, age, length or counts would hide them. "
                            + "Ads, blocked creators, words, sounds, countries, caption "
                            + "languages and seen videos still apply.",
                    Settings.CREATOR_FILTER_EXCEPTIONS)
                    .withCheck(CreatorExceptions::entryProblem)
                    .withNote(CreatorExceptions::conflictNote)
                    .withNameKeyboard());
        }
        if (SettingsStatus.feedFilterEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide videos with blocked sounds",
                    "Hide videos that use a sound blocked with the player's sound button, or named below.",
                    Settings.HIDE_BLOCKED_SOUNDS
            ));
            addPreference(new InputTextPreference(
                    context,
                    "Blocked sound names",
                    "Separate words with commas, like saxophone, to match against sound names. "
                            + "Capital letters don't matter.",
                    Settings.BLOCKED_SOUND_NAMES
            ));
            addPreference(new InputTextPreference(
                    context,
                    "Blocked sound ids",
                    "Sound IDs saved by the player's sound button, separated by commas. Remove "
                            + "one to unblock it.",
                    Settings.BLOCKED_SOUND_IDS
            ).withNameKeyboard());
        }
    }

    private void addWordsAndCountries(Context context) {
        addPreference(new SectionHeadingPreference(context, "Words, countries and languages"));
        addPreference(new InputTextPreference(context, "Blocked caption words",
                "Separate words or phrases with commas. Videos whose caption matches are "
                        + "hidden. Capital letters don't matter. Write \"a\" & \"b\" to need "
                        + "both phrases, or \"a\" !& \"b\" to need the first without the "
                        + "second.",
                Settings.BLOCKED_CAPTION_WORDS)
                .withCheck(app.morphe.extension.tiktok.feedfilter.KeywordRules::problem)
                .withSamplePreview(app.morphe.extension.tiktok.settings.L10n.t("Sample caption to test"),
                        app.morphe.extension.tiktok.feedfilter.KeywordRulePreview.MAX_SAMPLE_CHARS,
                        app.morphe.extension.tiktok.feedfilter.KeywordRulePreview::result));
        addPreference(new TogglePreference(context, "Match text stickers too",
                "Also hide videos whose text stickers match a blocked caption word. Text stickers are the words a creator types over the video in TikTok's editor. Text that's part of the picture itself can't be read.",
                Settings.BLOCKED_WORDS_IN_STICKERS));
        addPreference(new InputTextPreference(context, "Only from these countries",
                "Separate country codes with commas, like GB, IE. Videos posted from anywhere "
                        + "else are hidden. Leave empty for all countries.",
                Settings.REGION_ONLY_FROM)
                .withCheck(app.morphe.extension.tiktok.feedfilter.RegionFilter::countryProblem)
                .withNameKeyboard());
        addPreference(new InputTextPreference(context, "Never from these countries",
                "Separate country codes with commas. Videos posted from these are hidden, "
                        + "whatever the list above says.",
                Settings.REGION_NEVER_FROM)
                .withCheck(app.morphe.extension.tiktok.feedfilter.RegionFilter::countryProblem)
                .withNameKeyboard());
        addPreference(new InputTextPreference(context, "Only these caption languages",
                "Separate language codes with commas, like en, es. A video whose original "
                        + "caption is in another language is hidden. Videos with no caption, or "
                        + "only translated ones, always stay.",
                Settings.CAPTION_LANGUAGES)
                .withCheck(app.morphe.extension.tiktok.feedfilter.CaptionLanguageFilter::languageProblem)
                .withNameKeyboard());
    }

    /** The LIVE feed you swipe through, a page of rooms at a time (issue #57). */
    private void addLiveFeed(Context context) {
        addPreference(new SectionHeadingPreference(context, "LIVE feed"));
        addPreference(new TogglePreference(context, "Filter the LIVE feed",
                "Hides rooms in the LIVE feed you swipe through, using the rules below. Blocked "
                        + "creators, Creators hidden on this phone and Creator exceptions apply "
                        + "here too, and Blocked caption words are checked against each LIVE's "
                        + "title. The first room the LIVE button opens isn't filtered. If every "
                        + "room would be hidden, one gets through so the feed keeps loading.",
                Settings.LIVE_FEED_FILTER));
        addPreference(new TogglePreference(context, "Hide gaming LIVEs",
                "Hide LIVEs that TikTok tags with a game or a gaming category.",
                Settings.LIVE_HIDE_GAMING));
        addPreference(new TogglePreference(context, "Hide shopping LIVEs",
                "Hide LIVEs that sell products or carry a TikTok Shop tag.",
                Settings.LIVE_HIDE_SHOPPING));
        addPreference(new TogglePreference(context, "Hide sponsored LIVEs",
                "Hide LIVEs that TikTok labels as a paid partnership or promotional content.",
                Settings.LIVE_HIDE_SPONSORED));
        addPreference(new TogglePreference(context, "Hide verified creators' LIVEs",
                "Hide LIVEs hosted by verified accounts.",
                Settings.LIVE_HIDE_VERIFIED));
        addPreference(new InputTextPreference(context, "Hidden LIVE categories",
                "Separate words with commas, like music, chat. A LIVE is hidden when its "
                        + "category, topic tags or game name contains one. Capital letters "
                        + "don't matter.",
                Settings.LIVE_HIDDEN_CATEGORIES)
                .withCheck(app.morphe.extension.tiktok.feedfilter.KeywordRules::problem)
                .withSamplePreview(app.morphe.extension.tiktok.settings.L10n.t("Sample category to test"),
                        app.morphe.extension.tiktok.feedfilter.KeywordRulePreview.MAX_SAMPLE_CHARS,
                        app.morphe.extension.tiktok.feedfilter.KeywordRulePreview::result));
        addPreference(new RangeValuePreference(context, "LIVE viewers range",
                "Show only LIVEs with this many people watching.",
                Settings.LIVE_MIN_MAX_VIEWERS));
        addPreference(new RangeValuePreference(context, "LIVE followers range",
                "Show only LIVEs from creators with this many followers.",
                Settings.LIVE_MIN_MAX_FOLLOWERS));
    }

    private void addSeenVideoRules(Context context) {
        addPreference(new SectionHeadingPreference(context, "Seen videos"));
        addPreference(new TogglePreference(
                context,
                "Hide videos you have already seen",
                "Keep a local record of what you have watched and hide those videos when "
                        + "the feed sends them again.",
                Settings.HIDE_SEEN_VIDEOS
        ));
        addPreference(new NumberInputPreference(
                context,
                "Forget seen videos after",
                L10n.f(context, "Days to remember a video. Zero removes the age limit. History keeps at most %1$s videos.",
                        NumberFormat.getInstance().format(SeenVideoHistory.MAX_RECORDS)),
                Settings.SEEN_VIDEO_RETENTION_DAYS, "%1$s day", "%1$s days"
        ).zeroMeansOff());
        addPreference(new NumberInputPreference(
                context,
                "Count a video as seen after",
                "How much of a video you watch before it's hidden next time. Zero counts it after a few seconds.",
                Settings.SEEN_VIDEO_MARK_PERCENT, "%1$s%%"
        ).zeroMeans("A few seconds"));
        addPreference(new ImportSeenVideoHistoryPreference(context));
        addPreference(SeenHistoryFilePreference.save(context));
        addPreference(SeenHistoryFilePreference.restore(context));
        addPreference(new ClearSeenVideoHistoryPreference(context));
        int unowned = SeenVideoHistory.unownedCount();
        if (unowned > 0) addPreference(new AdoptSeenVideoHistoryPreference(context, unowned));
    }

    private void addAdvanced(Context context) {
        addPreference(new SectionHeadingPreference(context, "Advanced"));
        addPreference(new TogglePreference(
                context,
                "Keep offline videos out of the feed",
                "TikTok slips the videos it saved for offline viewing back into For You when it can't load "
                        + "enough new ones. This takes them all out. Your offline list stays as it is.",
                Settings.HIDE_OFFLINE_VIDEOS
        ));
        addPreference(new TogglePreference(
                context,
                "Filter TikTok's offline videos",
                "Also apply these filters to downloaded videos TikTok uses when the feed can't load enough new items.",
                Settings.FILTER_OFFLINE_FALLBACK_VIDEOS
        ));
        addPreference(new TogglePreference(
                context,
                "Show how many were filtered",
                "Shows a small count under TikTok's top tabs of what the feed filter has "
                        + "removed since TikTok started. It shows only on the feed, and taps on "
                        + "it go through to the video under it.",
                Settings.FILTERED_COUNT_PILL
        ));
    }
}
