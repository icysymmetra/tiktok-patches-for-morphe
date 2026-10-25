/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import app.morphe.extension.tiktok.captions.CaptionStyle;
import app.morphe.extension.tiktok.feed.BurnInGuard;
import app.morphe.extension.tiktok.feed.FeedTextSize;
import app.morphe.extension.tiktok.interaction.TapConfirmation;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.ChoicePreference;
import app.morphe.extension.tiktok.settings.preference.NumberInputPreference;
import app.morphe.extension.tiktok.settings.preference.PopupLabelChecklistPreference;
import app.morphe.extension.tiktok.settings.preference.SectionHeadingPreference;
import app.morphe.extension.tiktok.settings.preference.SwitchListPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;

/**
 * Everything drawn on the feed itself, grouped by where it sits: the right column, the buttons
 * Hushfeed adds, the text beside the video, what is around it, what pops over it, then
 * captions, clear display and gestures. The status bar and screenshot switches cover the whole
 * app, so they're on App. The page used to be one 27-row "Feed controls" card after the
 * captions, in the order the patches were written.
 */
@SuppressWarnings("deprecation")
public final class InterfacePreferenceCategory extends ConditionalPreferenceCategory {
    public InterfacePreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Feed screen");
    }

    /** Whether this page has anything on it. The row into it asks the same question. */
    public static boolean isAvailable() {
        return SettingsStatus.videoOverlaysEnabled
                || SettingsStatus.musicDiscSpinEnabled
                || SettingsStatus.feedTextSizeEnabled
                || SettingsStatus.hideFeedFollowButtonEnabled
                || SettingsStatus.hideFeedSaveButtonEnabled
                || SettingsStatus.exactCountsEnabled
                || SettingsStatus.avatarRingsEnabled
                || SettingsStatus.alwaysShowPublishDateEnabled
                || SettingsStatus.authorRegionEnabled
                || SettingsStatus.engagementRateEnabled
                || SettingsStatus.hideFeedLiveButtonEnabled
                || SettingsStatus.hideFeedSidebarButtonEnabled
                || SettingsStatus.hideFeedSearchButtonEnabled
                || SettingsStatus.feedFilterEnabled
                || SettingsStatus.promotionalBannersEnabled
                || SettingsStatus.captchaPopupSuppressionEnabled
                || SettingsStatus.sensitiveWarningsEnabled
                || SettingsStatus.popupLabelsEnabled
                || SettingsStatus.subtitleToolsEnabled
                || SettingsStatus.blockAuthorEnabled
                || SettingsStatus.notInterestedEnabled
                || SettingsStatus.feedMuteEnabled
                || SettingsStatus.automaticClearDisplayEnabled
                || SettingsStatus.doubleTapEnabled
                || SettingsStatus.swipeLeftEnabled
                || SettingsStatus.longPressEnabled
                || SettingsStatus.longPressSpeedLockEnabled
                || SettingsStatus.disableLongPressQuickShareEnabled
                || SettingsStatus.disableLongPressRepostEnabled
                || SettingsStatus.confirmInteractionsEnabled;
    }

    @Override
    public boolean getSettingsStatus() {
        return isAvailable();
    }

    @Override
    public void addPreferences(Context context) {
        addRightColumn(context);
        addButtonsOnVideos(context);
        addVideoInfo(context);
        addAroundTheVideo(context);
        addPopups(context);
        addCaptions(context);
        addClearDisplay(context);
        addGestures(context);
    }

    private void addRightColumn(Context context) {
        boolean any = SettingsStatus.videoOverlaysEnabled
                || SettingsStatus.musicDiscSpinEnabled
                || SettingsStatus.hideFeedFollowButtonEnabled
                || SettingsStatus.hideFeedSaveButtonEnabled
                || SettingsStatus.exactCountsEnabled
                || SettingsStatus.avatarRingsEnabled;
        if (!any) return;
        addPreference(new SectionHeadingPreference(context, "Right column"));
        if (SettingsStatus.videoOverlaysEnabled) {
            // One row over the six hides and the counts. Six rows that each said "Hide the ...
            // button" read the same choice seven times over; the row's second line says what
            // is hidden at a glance.
            List<SwitchListPreference.Item> hides = new ArrayList<>();
            hides.add(new SwitchListPreference.Item(L10n.t(context, "Avatar and follow button"), Settings.HIDE_RAIL_FOLLOW));
            hides.add(new SwitchListPreference.Item(L10n.t(context, "Like"), Settings.HIDE_RAIL_LIKE));
            hides.add(new SwitchListPreference.Item(L10n.t(context, "Comments"), Settings.HIDE_RAIL_COMMENTS));
            hides.add(new SwitchListPreference.Item(L10n.t(context, "Save"), Settings.HIDE_RAIL_FAVOURITE));
            hides.add(new SwitchListPreference.Item(L10n.t(context, "Share"), Settings.HIDE_RAIL_SHARE));
            hides.add(new SwitchListPreference.Item(L10n.t(context, "Music disc"), Settings.HIDE_RAIL_MUSIC));
            hides.add(new SwitchListPreference.Item(L10n.t(context, "Counts under the buttons"), Settings.HIDE_RAIL_COUNTS));
            addPreference(new SwitchListPreference(context, "feed_right_column_hides",
                    "Hide buttons in the right column",
                    "Pick the buttons to take off the column. The rest stay where they are.",
                    hides));
            addPreference(new TogglePreference(
                    context,
                    "Hide the right column",
                    "Hide the whole column on the right, from the avatar down to the music disc.",
                    Settings.HIDE_FEED_ACTION_BAR
            ));
            // Two choices, not four. The right column has no room between its slots, so a
            // glyph grown by more than a quarter lands on the count under it or the button
            // above it (S22, 2026-09-17); 1.5x and 2x were offered before that was measured.
            addPreference(new ChoicePreference(context, "Feed button size", Settings.TOUCH_TARGET_SCALE,
                    new String[]{"Normal", "Larger (1.25x)"},
                    new String[]{"1", "1.25"}));
        }
        // TikTok 47.1.4 holds the disc still by default (#68); these answer the setting that does.
        if (SettingsStatus.musicDiscSpinEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Spin the music disc",
                    "Turn the music disc at the bottom of the right column while a video plays, the way TikTok used to.",
                    Settings.SPIN_MUSIC_DISC
            ));
            addPreference(new TogglePreference(
                    context,
                    "Keep the music disc still",
                    "Hold the music disc at the bottom of the right column still, even where TikTok turns it. This overrides Spin the music disc.",
                    Settings.STOP_MUSIC_DISC_SPIN
            ));
        }
        if (SettingsStatus.hideFeedFollowButtonEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide the follow button on the feed",
                    "Hide the plus button under the creator's picture in the right column. Ticking Avatar and follow button above hides the picture with it.",
                    Settings.HIDE_FEED_FOLLOW_BUTTON
            ));
        }
        if (SettingsStatus.hideFeedSaveButtonEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide the save button on the feed",
                    "Hide the save button in the right column. Ticking Save in the list above hides it too.",
                    Settings.HIDE_FEED_SAVE_BUTTON
            ));
        }
        if (SettingsStatus.exactCountsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Show exact counts",
                    "Show counts as full numbers, like 1,234,567 instead of 1.2M.",
                    Settings.SHOW_EXACT_COUNTS
            ));
        }
        if (SettingsStatus.avatarRingsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Remove story rings",
                    "Take the story ring off profile pictures on the feed, in comments and on profiles. Tapping a picture opens the profile instead of the story.",
                    Settings.HIDE_STORY_RINGS
            ));
            addPreference(new TogglePreference(
                    context,
                    "Remove the LIVE ring",
                    "Take the pulsing LIVE ring off profile pictures on the feed and in comments. Tapping the picture of a creator who's live opens their profile instead of the LIVE.",
                    Settings.HIDE_LIVE_RING
            ));
        }
    }

    /**
     * Every control Hushfeed draws on the video, in one card. They were split between Feed filter
     * and Playback, so nobody could see in one place which of them would show up. Each starts off.
     */
    private void addButtonsOnVideos(Context context) {
        boolean any = SettingsStatus.blockAuthorEnabled || SettingsStatus.notInterestedEnabled
                || SettingsStatus.feedMuteEnabled;
        if (!any) return;
        addPreference(new SectionHeadingPreference(context, "Buttons on videos"));
        if (SettingsStatus.blockAuthorEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Show the block button on videos",
                    "Block the creator in one tap. Once TikTok confirms, skip to the next video. "
                            + "A small Unblock button appears at the top left for two seconds. "
                            + "You can also unblock later in TikTok's Privacy > Blocked accounts.",
                    Settings.BLOCK_AUTHOR_BUTTON
            ));
        }
        // These two act through the feed filter's lists, and without it they would draw nothing.
        if (SettingsStatus.blockAuthorEnabled && SettingsStatus.feedFilterEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Show the hide button on videos",
                    "Add a button that hides the current creator on this phone without blocking them.",
                    Settings.LOCAL_HIDE_BUTTON
            ));
            addPreference(new TogglePreference(
                    context,
                    "Show the block sound button",
                    "Add a separate button that skips videos using the current sound.",
                    Settings.BLOCK_SOUND_BUTTON
            ));
        }
        if (SettingsStatus.notInterestedEnabled) {
            addPreference(new TogglePreference(context, "Show the Not interested button",
                    "Add a button beside the block control to send feedback about the current video.",
                    Settings.NOT_INTERESTED_BUTTON));
        }
        if (SettingsStatus.feedMuteEnabled) {
            addPreference(new TogglePreference(context, "Show the mute button on videos",
                    "Add a button beside the block control that turns the feed's sound off and on.",
                    Settings.FEED_MUTE_BUTTON));
        }
    }

    private void addVideoInfo(Context context) {
        boolean any = SettingsStatus.alwaysShowPublishDateEnabled
                || SettingsStatus.authorRegionEnabled
                || SettingsStatus.engagementRateEnabled
                || SettingsStatus.feedTextSizeEnabled
                || SettingsStatus.videoOverlaysEnabled;
        if (!any) return;
        addPreference(new SectionHeadingPreference(context, "Video info"));
        if (SettingsStatus.feedTextSizeEnabled) {
            NumberInputPreference descriptionSize = new NumberInputPreference(context, "Description text size",
                    L10n.f(context, "Use 0 for TikTok's size, or %1$d to %2$d. Sizes the description below the author's name and keeps Android's font scaling.",
                            FeedTextSize.MIN_TEXT_SIZE, FeedTextSize.MAX_TEXT_SIZE),
                    Settings.FEED_DESCRIPTION_TEXT_SIZE, "%1$s point", "%1$s points") {
                @Override protected int clamp(int value) { return FeedTextSize.clampSize(value); }
            };
            descriptionSize.zeroMeans("TikTok's size");
            addPreference(descriptionSize);
            NumberInputPreference authorSize = new NumberInputPreference(context, "Author text size",
                    L10n.f(context, "Use 0 for TikTok's size, or %1$d to %2$d. Sizes the author's name and the post date next to it, and keeps Android's font scaling.",
                            FeedTextSize.MIN_TEXT_SIZE, FeedTextSize.MAX_TEXT_SIZE),
                    Settings.FEED_AUTHOR_TEXT_SIZE, "%1$s point", "%1$s points") {
                @Override protected int clamp(int value) { return FeedTextSize.clampSize(value); }
            };
            authorSize.zeroMeans("TikTok's size");
            addPreference(authorSize);
        }
        if (SettingsStatus.alwaysShowPublishDateEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Always show publish date",
                    "Always show the publish date in video author information. Restart TikTok to apply this.",
                    Settings.ALWAYS_SHOW_PUBLISH_DATE
            ));
            addPreference(new TogglePreference(
                    context,
                    "Show the time to the second",
                    "Show when a video was posted down to the second, with your time zone, in place of TikTok's short date by the creator's name.",
                    Settings.PUBLISH_DATE_EXACT_TIME
            ));
            addPreference(new TogglePreference(
                    context,
                    "Show the time zone",
                    "Puts your time zone after the time to the second, like EDT.",
                    Settings.PUBLISH_DATE_TIME_ZONE
            ));
            addPreference(new TogglePreference(
                    context,
                    "Short date",
                    "Writes the date before the time to the second the short way your language does, like 10/7/25.",
                    Settings.PUBLISH_DATE_SHORT_DATE
            ));
            addPreference(new TogglePreference(
                    context,
                    "Show dates on profile grids",
                    "Show the day each video was posted under its view count on profile grids.",
                    Settings.PUBLISH_DATE_ON_GRID
            ));
            addPreference(new TogglePreference(
                    context,
                    "Show video length on profile grids",
                    "Show how long each video runs under its view count on profile grids. Photo posts and videos TikTok sent no length for show none.",
                    Settings.VIDEO_LENGTH_ON_GRID
            ));
        }
        if (SettingsStatus.authorRegionEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Show where a video was posted",
                    "Show the country a video was posted from next to the creator's name.",
                    Settings.SHOW_AUTHOR_REGION
            ));
            addPreference(new TogglePreference(
                    context,
                    "Show the country as a flag",
                    "Shows the country's flag in place of its two letters, by the creator's name and by a commenter's time.",
                    Settings.REGION_AS_FLAG
            ));
            addPreference(new TogglePreference(
                    context,
                    "Show the handle instead of the name",
                    "Show @username beside a video instead of the display name the creator chose.",
                    Settings.SHOW_AUTHOR_HANDLE
            ));
        }
        if (SettingsStatus.engagementRateEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Show engagement rate",
                    "Show likes, comments, shares and saves as a percentage of views, next to the creator's name and after the view count on profile grids.",
                    Settings.SHOW_ENGAGEMENT_RATE
            ));
        }
        if (SettingsStatus.videoOverlaysEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide the caption",
                    "Hide the description under the creator's name on the feed.",
                    Settings.HIDE_FEED_CAPTION
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide the music line",
                    "Hide the track name beside the caption.",
                    Settings.HIDE_FEED_MUSIC
            ));
        }
    }

    private void addAroundTheVideo(Context context) {
        boolean any = SettingsStatus.videoOverlaysEnabled
                || SettingsStatus.hideFeedLiveButtonEnabled
                || SettingsStatus.hideFeedSidebarButtonEnabled
                || SettingsStatus.hideFeedSearchButtonEnabled
                || SettingsStatus.feedFilterEnabled;
        if (!any) return;
        addPreference(new SectionHeadingPreference(context, "Around the video"));
        if (SettingsStatus.videoOverlaysEnabled || SettingsStatus.hideFeedLiveButtonEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide the LIVE button",
                    "Hide the LIVE button in the top left corner of the feed.",
                    Settings.HIDE_LIVE_ENTRANCE
            ));
        }
        // TikTok builds the side menu button in code with no view id, so only the LIVE patch's
        // toolbar check can take it away; the video overlay hider has nothing to find it by.
        // A build the patch doesn't declare can leave that check out, so the row has its flag.
        if (SettingsStatus.hideFeedSidebarButtonEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide the side menu button",
                    "Hide the button at the top left of the feed that opens TikTok's side menu, with Your orders, TikTok Minis and more.",
                    Settings.HIDE_FEED_SIDEBAR_BUTTON
            ));
        }
        if (SettingsStatus.hideFeedSearchButtonEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide the search button on the feed",
                    "Hide the magnifying glass at the top right of the feed. Search in Inbox and suggestions above comments stay unchanged.",
                    Settings.HIDE_FEED_SEARCH_BUTTON
            ));
            // The overlay hider's layout pass takes the box away, so the row needs that patch too.
            if (SettingsStatus.videoOverlaysEnabled) {
                addPreference(new TogglePreference(
                        context,
                        "Also on opened videos",
                        "Hide the search bar at the top of videos you open from search, a profile or your watch history. The Back button stays.",
                        Settings.HIDE_OPENED_VIDEO_SEARCH
                ));
            }
        }
        if (SettingsStatus.videoOverlaysEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide the Full screen button",
                    "Hide the Full screen button over videos. Playback and automatic rotation keep working as before.",
                    Settings.HIDE_FULLSCREEN_BUTTON
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide the Report button on videos",
                    "Hide the flag button TikTok puts above the creator's picture in some regions. You can still report a video from the share menu.",
                    Settings.HIDE_FEED_REPORT_BUTTON
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide location labels",
                    "Hide place badges on videos, including cards with multiple places. Videos and your location permissions stay unchanged.",
                    Settings.HIDE_LOCATION_LABELS
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide effect and template tags",
                    "Hide the tags above a video's description that invite you to try an effect, a template, CapCut or an AI style. Location labels and the music line have their own switches.",
                    Settings.HIDE_CREATION_TAGS
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide surveys",
                    "Hide the question cards TikTok slides over a video near its end.",
                    Settings.HIDE_FEED_SURVEYS
            ));
            if (SettingsStatus.footnotesEnabled) {
                addPreference(new TogglePreference(
                        context,
                        "Hide Footnotes",
                        "Hide the Footnotes banner TikTok shows on a video that has a note attached. The video and its comments stay as they are.",
                        Settings.HIDE_FOOTNOTES
                ));
            }
            addPreference(new TogglePreference(
                    context,
                    "Hide Search this image prompts",
                    "Hide the \"Search this image\" prompt shown over videos. The feed search button and suggestions above comments are separate settings.",
                    Settings.HIDE_VISUAL_SEARCH
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide the comment bar on opened videos",
                    "Take the Add comment bar off videos you open from a profile, a hashtag, a sound or search, so the video fills the screen down to the bottom. The comment button on the right still opens comments.",
                    Settings.HIDE_DETAIL_COMMENT_BAR
            ));
        }
        // Feed filter rows that hide things drawn around a video rather than videos.
        // They were the tail of the Feed filter page under "Feed elements", a page about
        // which videos arrive.
        if (SettingsStatus.feedFilterEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide the search bar below videos",
                    "Hide the suggested-search strip above the bottom tabs and let video details and side controls use its space. The top search button and comments stay unchanged.",
                    Settings.HIDE_BOTTOM_SEARCH_BAR
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide the playlist bar",
                    "Hide the playlist bar along the bottom of videos that belong to a series.",
                    Settings.HIDE_PLAYLIST_BAR
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide the event badge",
                    "Hide the floating promotional badge over the feed.",
                    Settings.HIDE_EVENT_BADGE
            ));
            addPreference(new TogglePreference(
                    context,
                    "Hide inserted cards",
                    "Hide the friend recommendation card and the other cards TikTok slots between videos.",
                    Settings.HIDE_INSERTED_CARDS
            ));
        }
    }

    private void addPopups(Context context) {
        boolean any = SettingsStatus.promotionalBannersEnabled
                || SettingsStatus.captchaPopupSuppressionEnabled
                || SettingsStatus.sensitiveWarningsEnabled
                || SettingsStatus.popupLabelsEnabled
                || SettingsStatus.videoOverlaysEnabled;
        if (!any) return;
        addPreference(new SectionHeadingPreference(context, "Popups"));
        if (SettingsStatus.promotionalBannersEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide floating promotions",
                    "Hide floating promotion badges, coins, and timer banners on the feed.",
                    Settings.HIDE_HOMEPAGE_COIN
            ));
        }
        if (SettingsStatus.captchaPopupSuppressionEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide CAPTCHA popups",
                    "Skip the puzzle TikTok shows while you're just browsing. A puzzle right after "
                            + "you like, follow, comment or repost still shows, and so do sign-in, "
                            + "SMS and two-step checks. If something stops working, turn this off.",
                    Settings.HIDE_CAPTCHA_POPUPS
            ));
        }
        if (SettingsStatus.popupLabelsEnabled) {
            addPreference(new PopupLabelChecklistPreference(context));
            addPreference(new TogglePreference(
                    context,
                    "Hide the LIVE bubble",
                    "Stop the bubble TikTok floats at the top of the feed to point you to a LIVE.",
                    Settings.HIDE_LIVE_BUBBLE
            ));
            // Left out with its hook on a build where the triggers' check wasn't found.
            if (SettingsStatus.windDownScreensEnabled) {
                addPreference(new TogglePreference(
                        context,
                        "Hide TikTok's wind-down screens",
                        "Stops the bedtime wind-down, the breathing exercise and the daily "
                                + "limit screen TikTok puts over the feed. Works only on "
                                + "accounts TikTok knows belong to adults. Teen accounts, and "
                                + "ones a parent links with Family Pairing, keep them. With "
                                + "Leave when TikTok says time is up on, the daily limit screen "
                                + "still shows so that switch can work.",
                        Settings.HIDE_WIND_DOWN_SCREENS
                ));
            }
        }
        if (SettingsStatus.sensitiveWarningsEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Skip content warnings",
                    "Plays videos that TikTok covers with a content warning without asking you "
                            + "to tap through first.",
                    Settings.HIDE_SENSITIVE_WARNINGS
            ));
            // Left out with its hook on a build where the risk model getter wasn't found.
            if (SettingsStatus.unverifiedNoticesEnabled) {
                addPreference(new TogglePreference(
                        context,
                        "Hide unverified content notices",
                        "Hide the Check sources banner TikTok puts on videos it marks as unverified, "
                                + "and the warnings it shows when you share one.",
                        Settings.HIDE_UNVERIFIED_NOTICES
                ));
            }
            // Left out with its hooks on a build where the Know the facts gate or prompt wasn't found.
            if (SettingsStatus.checkYourSourcesEnabled) {
                addPreference(new TogglePreference(
                        context,
                        "Hide Check your sources warnings",
                        "Hide the Check your sources banner TikTok puts across the top of some videos, "
                                + "and the prompt it shows when you share one.",
                        Settings.HIDE_CHECK_YOUR_SOURCES
                ));
            }
        }
        if (SettingsStatus.videoOverlaysEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide the share prompt after a like",
                    "Stop the bubble that asks you to share a video with friends after you like it.",
                    Settings.HIDE_SHARE_GUIDE
            ));
        }
    }

    private void addCaptions(Context context) {
        if (SettingsStatus.subtitleToolsEnabled) {
            addPreference(new SectionHeadingPreference(context, "Captions"));
            NumberInputPreference captionSize = new NumberInputPreference(context, "Caption text size",
                    L10n.f(context, "Use 0 for TikTok's size, or %1$d to %2$d. Applies to the next caption.",
                            CaptionStyle.MIN_TEXT_SIZE, CaptionStyle.MAX_TEXT_SIZE),
                    Settings.CAPTION_TEXT_SIZE, "%1$s point", "%1$s points") {
                @Override protected int clamp(int value) { return CaptionStyle.clampSize(value); }
            };
            captionSize.zeroMeans("TikTok's size");
            addPreference(captionSize);
            addPreference(new TogglePreference(context, "Normal-weight captions",
                    "Draw spoken captions at regular weight instead of TikTok's bold. Applies to the next caption.",
                    Settings.CAPTION_REGULAR_WEIGHT));
            addPreference(new ChoicePreference(context, "Caption background", Settings.CAPTION_BACKGROUND,
                    new String[]{"TikTok default", "Transparent", "Dark", "Black"},
                    new String[]{"default", "transparent", "dark", "black"}));
            addPreference(new TogglePreference(context, "Keep captions in Clear display",
                    "Show the current spoken caption while the other controls are hidden.", Settings.KEEP_CAPTIONS_CLEAR_DISPLAY));
        }
    }

    private void addClearDisplay(Context context) {
        if (SettingsStatus.automaticClearDisplayEnabled || SettingsStatus.videoOverlaysEnabled) {
            addPreference(new SectionHeadingPreference(context, "Clear display"));
        }
        if (SettingsStatus.automaticClearDisplayEnabled) {
            addPreference(new TogglePreference(context, "Automatic Clear display",
                    "Hide controls after each video starts. Tap to restore them.", Settings.AUTOMATIC_CLEAR_DISPLAY));
            NumberInputPreference delay = new NumberInputPreference(context, "Clear display delay",
                    "Wait before hiding the controls.", Settings.AUTOMATIC_CLEAR_DISPLAY_DELAY,
                    "%1$s millisecond", "%1$s milliseconds");
            delay.zeroMeans("No delay");
            addPreference(delay);
        }
        // Beside the automatic switch rather than at the end of Around the video, where the #84
        // reporter couldn't find it. It belongs to the overlay patch, so it shows without the
        // automatic one too.
        if (SettingsStatus.videoOverlaysEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Hide the Clear display controls",
                    "While Clear display is on, also hide the progress bar, the close button and the pause and speed buttons at the bottom. You can still drag along the bottom edge to move through the video. To leave Clear display, press and hold the video and tap Restore display, or pinch the screen.",
                    Settings.HIDE_CLEAR_DISPLAY_CONTROLS
            ));
            addPreference(new NumberInputPreference(
                    context,
                    "Fade the video controls",
                    "Fade the buttons, caption and tabs over the video to this level instead of hiding them. 100 leaves them as TikTok draws them. Faded controls still take taps. At 0 the buttons and caption are hidden, and the tabs stay at 10.",
                    Settings.FADE_CONTROLS_OPACITY, "%1$s%%"
            ));
            addPreference(new TogglePreference(
                    context,
                    "Keep faded controls in Clear display",
                    "With the fade below 100, the buttons and caption stay on screen at that level in Clear display, and taps go through them to the video. Off, Clear display hides them the way it does at 100.",
                    Settings.FADE_IN_CLEAR_DISPLAY
            ));
            addPreference(new TogglePreference(
                    context,
                    "See-through bottom bar",
                    "Let the video reach the bottom of the screen on the main feed and show the tabs over it with no bar behind them.",
                    Settings.SEE_THROUGH_BOTTOM_BAR
            ));
            addPreference(new TogglePreference(
                    context,
                    "Floating bottom bar",
                    "Show the main feed's tabs on a rounded, see-through dark pill that floats over the video. Needs See-through bottom bar.",
                    Settings.FLOATING_BOTTOM_BAR
            ));
            addPreference(new TogglePreference(
                    context,
                    "See-through status bar",
                    "Let the video reach the top of the screen on the main feed, with the clock and status icons over it. The tabs at the top stay where they are.",
                    Settings.SEE_THROUGH_STATUS_BAR
            ));
            addPreference(new ChoicePreference(context, "Burn-in guard", Settings.BURN_IN_GUARD,
                    new String[]{"Off", "Dim when idle", "Dim when idle and shift"},
                    new String[]{BurnInGuard.OFF, BurnInGuard.DIM, BurnInGuard.DIM_AND_SHIFT})
                    .withNote("For OLED screens. After 5 seconds without a touch, the buttons, caption and tabs over the video dim to a quarter, and your next touch brings them back. Shift also moves everything on the feed a few pixels every 2 minutes."));
        }
    }

    private void addGestures(Context context) {
        boolean any = SettingsStatus.doubleTapEnabled || SettingsStatus.longPressEnabled
                || SettingsStatus.swipeLeftEnabled
                || SettingsStatus.longPressSpeedLockEnabled
                || SettingsStatus.disableLongPressQuickShareEnabled
                || SettingsStatus.disableLongPressRepostEnabled
                || SettingsStatus.confirmInteractionsEnabled
                || SettingsStatus.videoOverlaysEnabled;
        if (!any) return;
        addPreference(new SectionHeadingPreference(context, "Gestures"));
        if (SettingsStatus.videoOverlaysEnabled) {
            addPreference(new TogglePreference(context, "Swipe for brightness and volume",
                    "Drag up or down along the left or right edge of a video to change what that edge strip is set "
                            + "to below. By default that's the screen brightness on the left and the volume on the "
                            + "right. Swiping anywhere else still scrolls the feed.",
                    Settings.SWIPE_LEVELS));
            // Speed is the playing video's, so it is only offered where the Playback speed patch
            // is in the bundle and found the player on screen. Brightness and volume stay where
            // they were on each side.
            String[] stripLabels = SettingsStatus.liveSpeedEnabled
                    ? new String[]{"Brightness", "Volume", "Speed"}
                    : new String[]{"Brightness", "Volume"};
            String[] stripValues = SettingsStatus.liveSpeedEnabled
                    ? new String[]{"brightness", "volume", "speed"}
                    : new String[]{"brightness", "volume"};
            addPreference(new ChoicePreference(context, "Left edge strip", Settings.SWIPE_LEVELS_LEFT,
                    stripLabels, stripValues));
            addPreference(new ChoicePreference(context, "Right edge strip", Settings.SWIPE_LEVELS_RIGHT,
                    stripLabels, stripValues));
            addPreference(new NumberInputPreference(context, "Edge strip width",
                    "How wide each edge strip is, as a percent of the screen width.",
                    Settings.SWIPE_LEVELS_STRIP_PERCENT, "%1$s%%"));
        }
        if (SettingsStatus.doubleTapEnabled) {
            // Long press's actions, which GestureActions runs the same way for both gestures. The
            // speed toggle changes the playing video's speed, so it needs the Playback speed patch's
            // player bridge (#136).
            List<String> tapLabels = new ArrayList<>(Arrays.asList("TikTok default", "Do nothing", "Open comments",
                    "Save the original sound", "Copy the video link", "Copy the sound link",
                    "Find the sound on YouTube Music", "Set a sleep timer that closes TikTok",
                    "Save the frame on screen as a photo", "Save the video's cover"));
            List<String> tapValues = new ArrayList<>(Arrays.asList("default", "nothing", "comments", "original_sound",
                    "copy_link", "copy_sound_link", "youtube_music", "sleep_timer", "save_frame", "save_cover"));
            if (SettingsStatus.liveSpeedEnabled) {
                tapLabels.add("Speed up this video, double tap again to undo");
                tapValues.add("speed_toggle");
            }
            addPreference(new ChoicePreference(context, "Double tap", Settings.DOUBLE_TAP_ACTION,
                    tapLabels.toArray(new String[0]), tapValues.toArray(new String[0])));
        }
        if (SettingsStatus.swipeLeftEnabled) {
            addPreference(new ChoicePreference(context, "Swipe left", Settings.SWIPE_LEFT_ACTION,
                    new String[]{"TikTok default (open the creator's profile)", "Do nothing", "Open comments"},
                    new String[]{"default", "nothing", "comments"}));
        }
        if (SettingsStatus.longPressEnabled) {
            // Double tap's actions and a like, which TikTok's own double tap already does.
            addPreference(new ChoicePreference(context, "Long press", Settings.LONG_PRESS_ACTION,
                    new String[]{"TikTok default (hold to speed up, quick share)", "Do nothing",
                            "Like the video", "Open comments", "Save the original sound",
                            "Copy the video link", "Copy the sound link", "Find the sound on YouTube Music",
                            "Set a sleep timer that closes TikTok", "Save the frame on screen as a photo",
                            "Save the video's cover"},
                    new String[]{"default", "nothing", "like", "comments", "original_sound", "copy_link",
                            "copy_sound_link", "youtube_music", "sleep_timer", "save_frame", "save_cover"}));
            addPreference(new TogglePreference(context, "Only on the sides of the video",
                    "Your Long press choice runs only on the left and right thirds of the video. "
                            + "A press in the middle still opens TikTok's own menu. "
                            + "It needs a Long press choice other than TikTok default.",
                    Settings.LONG_PRESS_SIDES_ONLY));
            addPreference(new TogglePreference(context, "Full long-press menu on a creator's videos",
                    "A long press on a video you opened from someone's profile opens the same menu as on For You, "
                            + "not the small share sheet. It needs Long press on TikTok default.",
                    Settings.LONG_PRESS_FULL_MENU_ON_PROFILES));
            addPreference(new TogglePreference(context, "Full long-press menu in search results",
                    "A long press on a video you opened from search opens the same menu as on For You. "
                            + "It needs Long press on TikTok default.",
                    Settings.LONG_PRESS_FULL_MENU_IN_SEARCH));
            addPreference(new TogglePreference(context, "Seek from the edges",
                    "Press and hold the left or right third of the screen to jump back or forward. "
                            + "The middle third keeps the Long press action.", Settings.EDGE_SEEK));
            addPreference(new NumberInputPreference(context, "Seek by",
                    "How far each edge press moves the video.", Settings.EDGE_SEEK_SECONDS, "%1$s s"));
            addPreference(new TogglePreference(context, "Hold speed on Comment",
                    "A long press on Comment plays the video at the hold speed instead of opening the emoji row. "
                            + "It needs Long press on TikTok default and Seek from the edges off.",
                    Settings.RAIL_HOLD_COMMENT));
            addPreference(new TogglePreference(context, "Hold speed on Share",
                    "A long press on Share plays the video at the hold speed instead of opening the quick share row. "
                            + "It needs Long press on TikTok default and Seek from the edges off.",
                    Settings.RAIL_HOLD_SHARE));
            addPreference(new TogglePreference(context, "Hold speed on Favorites",
                    "A long press on Favorites plays the video at the hold speed instead of offering a new collection. "
                            + "It needs Long press on TikTok default and Seek from the edges off.",
                    Settings.RAIL_HOLD_FAVORITES));
        }
        // Three more long presses, each its own patch. They sat under Player on the App page,
        // two pages away from the Long press row they belong beside.
        if (SettingsStatus.longPressSpeedLockEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Lock the hold speed with a pull down",
                    "Use TikTok's own hold, pull down and release gesture to keep playing at the hold speed, "
                            + "2x unless Playback sets another.",
                    Settings.ENABLE_LONG_PRESS_SPEED_LOCK
            ));
        }
        if (SettingsStatus.disableLongPressQuickShareEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Keep long press from quick sharing",
                    "Stop a long press on Share from opening TikTok's quick share action.",
                    Settings.DISABLE_LONG_PRESS_QUICK_SHARE
            ));
        }
        if (SettingsStatus.disableLongPressRepostEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Keep long press from reposting",
                    "Stop a long press on Like from opening TikTok's repost action.",
                    Settings.DISABLE_LONG_PRESS_REPOST
            ));
        }
        if (SettingsStatus.confirmInteractionsEnabled) {
            addPreference(new TogglePreference(context, "Confirm before following",
                    L10n.f(context, "Tap the feed Follow button twice within %1$d seconds.",
                            TapConfirmation.CONFIRM_WINDOW_SECONDS),
                    Settings.CONFIRM_FOLLOW));
            addPreference(new TogglePreference(context, "Confirm before liking",
                    L10n.f(context, "Tap the like heart twice within %1$d seconds. Removing a like stays immediate.",
                            TapConfirmation.CONFIRM_WINDOW_SECONDS),
                    Settings.CONFIRM_LIKE));
            addPreference(new TogglePreference(context, "Confirm before liking a comment",
                    L10n.f(context, "Tap a comment's heart twice within %1$d seconds. A double tap on the comment needs a second one too. Removing a like stays immediate.",
                            TapConfirmation.CONFIRM_WINDOW_SECONDS),
                    Settings.CONFIRM_COMMENT_LIKE));
            addPreference(new TogglePreference(context, "Confirm before liking a story",
                    L10n.f(context, "Tap a story's heart twice within %1$d seconds. Removing a like stays immediate.",
                            TapConfirmation.CONFIRM_WINDOW_SECONDS),
                    Settings.CONFIRM_STORY_LIKE));
            addPreference(new TogglePreference(context, "Confirm before a quick repost",
                    L10n.f(context, "Tap Repost on the bar under a video twice within %1$d seconds. A repost with a note is left alone.",
                            TapConfirmation.CONFIRM_WINDOW_SECONDS),
                    Settings.CONFIRM_QUICK_REPOST));
        }
    }
}
