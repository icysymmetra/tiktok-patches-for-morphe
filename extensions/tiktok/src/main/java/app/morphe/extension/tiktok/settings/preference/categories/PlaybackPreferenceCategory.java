/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.ChoicePreference;
import app.morphe.extension.tiktok.settings.preference.SectionHeadingPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.settings.preference.InputTextPreference;
import app.morphe.extension.tiktok.settings.preference.NumberInputPreference;
import app.morphe.extension.tiktok.speed.PlaybackSpeedPatch;


@SuppressWarnings("deprecation")
public final class PlaybackPreferenceCategory extends ConditionalPreferenceCategory {
    public PlaybackPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Playback");
    }

    /** Whether this page has anything on it. The row into it asks the same question. */
    public static boolean isAvailable() {
        return SettingsStatus.playbackQualityEnabled || SettingsStatus.sdrPlaybackEnabled
                || SettingsStatus.h264PlaybackEnabled || SettingsStatus.playbackSpeedEnabled
                || SettingsStatus.autoAdvanceEnabled || SettingsStatus.forceShowAutoScrollEnabled || SettingsStatus.videoFitEnabled
                || SettingsStatus.fullScreenHoldEnabled || SettingsStatus.storyControlsEnabled
                || SettingsStatus.liveControlsEnabled
                || SettingsStatus.feedMuteEnabled || SettingsStatus.keepPulledSoundsEnabled
                || SettingsStatus.backgroundPlayEnabled || SettingsStatus.pictureInPictureEnabled
                || SettingsStatus.showSeekbarEnabled || SettingsStatus.seekbarThumbnailEnabled
                || SettingsStatus.stopVideoLoopingEnabled || SettingsStatus.resumeVideoAfterScrollEnabled
                // The comment sheet switch is a playback switch, and on a bundle with the
                // comment tools and none of the players it is the only thing on this page.
                || SettingsStatus.commentToolsEnabled;
    }

    @Override
    public boolean getSettingsStatus() {
        return isAvailable();
    }

    @Override public void addPreferences(Context context) {
        if (SettingsStatus.forceShowAutoScrollEnabled) {
            addPreference(new TogglePreference(context, "Show TikTok's Auto scroll action",
                    "Make the native action available. It starts only when you choose it.", Settings.FORCE_SHOW_AUTO_SCROLL));
        }
        if (SettingsStatus.autoAdvanceEnabled) {
            addPreference(new SectionHeadingPreference(context, "Auto-advance"));
            addPreference(new TogglePreference(context, "Auto-advance videos",
                    "Move to the next video when the current one ends. Pauses, open dialogs and "
                            + "screen-time holds stop it. Restart TikTok to apply this.",
                    Settings.AUTO_ADVANCE));
            addPreference(new NumberInputPreference(context, "Auto-advance session limit",
                    "Zero means no limit. Counts the videos Metra advanced past for you, "
                            + "not the ones you swiped yourself, and starts again when the feed "
                            + "is rebuilt or you change this number.",
                    Settings.AUTO_ADVANCE_LIMIT, "%1$s video", "%1$s videos").zeroMeansOff());
            addPreference(new TogglePreference(context, "Hide TikTok's Auto scroll button",
                    "Takes TikTok's own Auto scroll action out of the video panel. "
                            + "Auto-advance keeps working.",
                    Settings.AUTO_ADVANCE_HIDE_PANEL_ACTION));
            addPreference(new TogglePreference(context, "Auto-advance in search results",
                    "Also turns on TikTok's own Auto scroll for videos opened from search. "
                            + "Restart TikTok to apply this.",
                    Settings.AUTO_ADVANCE_SEARCH));
        }
        // None of these is auto-advance, and under its heading they read as parts of it: each keeps
        // you on the video you're on, paused behind the comments, held or stopped at its end, or
        // picked up where you left it.
        if (SettingsStatus.commentToolsEnabled || SettingsStatus.fullScreenHoldEnabled
                || SettingsStatus.storyControlsEnabled || SettingsStatus.liveControlsEnabled
                || SettingsStatus.stopVideoLoopingEnabled || SettingsStatus.resumeVideoAfterScrollEnabled) {
            addPreference(new SectionHeadingPreference(context, "Staying on a video"));
        }
        if (SettingsStatus.commentToolsEnabled) {
            addPreference(new TogglePreference(context, "Pause the video while comments are open",
                    "Pauses the video behind the comment sheet while you read. "
                            + "It plays on from the same spot when the sheet closes.",
                    Settings.PAUSE_ON_COMMENTS));
        }
        if (SettingsStatus.fullScreenHoldEnabled) {
            addPreference(new TogglePreference(context, "Stay on the video in full screen",
                    "When a video ends in full screen, stay on it instead of moving to the next "
                            + "one. Swiping still moves on.",
                    Settings.FULL_SCREEN_HOLD));
        }
        if (SettingsStatus.storyControlsEnabled) {
            addPreference(new TogglePreference(context, "Loop a story",
                    "Replay a story from the start when it ends instead of moving to the next one. "
                            + "Tap or swipe to move on.",
                    Settings.STORY_LOOP));
            addPreference(new TogglePreference(context, "Hold a photo story",
                    "Keep a photo story on screen until you tap or swipe, instead of moving on "
                            + "after a few seconds.",
                    Settings.STORY_PHOTO_HOLD));
        }
        if (SettingsStatus.liveControlsEnabled) {
            addPreference(new TogglePreference(context, "Stop LIVE previews opening by themselves",
                    "A LIVE in the feed can count down and take you into the room on its own. "
                            + "This keeps you in the feed until you tap it.",
                    Settings.STOP_LIVE_AUTO_ENTER));
            addPreference(new TogglePreference(context, "Show exact LIVE viewer counts",
                    "A LIVE room shows how many people are watching as a rounded number like 1.2K. "
                            + "This shows the exact count instead.",
                    Settings.SHOW_EXACT_LIVE_VIEWERS));
            addPreference(new TogglePreference(context, "Offer LIVE quality in more rooms",
                    "TikTok lets you pick a LIVE's quality only in some rooms. This adds Video "
                            + "quality to the menu in most of the others, and the next room opens at the quality "
                            + "you picked last.",
                    Settings.OFFER_LIVE_QUALITY));
            addPreference(new TogglePreference(context, "Keep the LIVE quality after TikTok restarts",
                    "TikTok forgets the LIVE quality you picked when it closes. This starts rooms at "
                            + "that quality again after a restart. A room whose stream doesn't have it "
                            + "opens at TikTok's choice.",
                    Settings.REMEMBER_LIVE_QUALITY));
            addPreference(new TogglePreference(context, "Offer clear screen in every LIVE room",
                    "Clear screen hides a LIVE's chat, gifts and buttons so only the stream shows. "
                            + "TikTok puts it in the menu of only some rooms. This adds it to the others.",
                    Settings.OFFER_LIVE_CLEAR_SCREEN));
            // Dark LIVE panels in dark mode (Settings.DARK_LIVE_PANELS) has no row until a LIVE
            // panel is seen dark with it on (#159).
            addPreference(new TogglePreference(context, "Record LIVEs you watch",
                    "Adds a Record button to LIVE rooms. A recording starts when you tap it and ends when you tap Stop or leave the room. Nothing before that is saved.",
                    Settings.RECORD_LIVE));
        }
        if (SettingsStatus.stopVideoLoopingEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Stop video looping",
                    "Stop videos at the end instead of replaying them.",
                    Settings.STOP_VIDEO_LOOPING
            ));
        }
        if (SettingsStatus.resumeVideoAfterScrollEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Resume videos after scrolling",
                    "Continue supported videos from where you stopped when you scroll back to them.",
                    Settings.RESUME_VIDEO_AFTER_SCROLL
            ));
        }
        // How the player itself behaves: the bar, the frame, the sound and what happens when you
        // leave. The bar rows were on App and the frame rows under Quality, which they aren't.
        if (SettingsStatus.showSeekbarEnabled || SettingsStatus.seekbarThumbnailEnabled
                || SettingsStatus.videoFitEnabled || SettingsStatus.feedMuteEnabled
                || SettingsStatus.keepPulledSoundsEnabled || SettingsStatus.backgroundPlayEnabled
                || SettingsStatus.pictureInPictureEnabled) {
            addPreference(new SectionHeadingPreference(context, "Player"));
        }
        if (SettingsStatus.showSeekbarEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Show the progress bar",
                    "Show TikTok's own progress bar on videos where it's normally hidden.",
                    Settings.SHOW_SEEKBAR
            ));
        }
        if (SettingsStatus.showSeekbarEnabled && SettingsStatus.seekbarExtrasEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Show the time beside the progress bar",
                    "Show the time played and the video's length at the end of the progress bar "
                            + "while it's on screen.",
                    Settings.SEEKBAR_TIME
            ));
            // Where the time goes (Settings.SEEKBAR_TIME_PLACEMENT) has no row until the time is seen
            // above the bar: on 47.1.4 the caption or the rail always sits over both ends (#173).
            addPreference(new TogglePreference(
                    context,
                    "Full-width progress bar",
                    "Run the progress bar from one screen edge to the other, without the gap "
                            + "TikTok leaves at each end.",
                    Settings.SEEKBAR_FULL_WIDTH
            ));
            addPreference(new TogglePreference(
                    context,
                    "Bigger progress bar handle",
                    "Draw a larger handle and a slightly thicker line on the progress bar, so it's "
                            + "easier to see where you are.",
                    Settings.SEEKBAR_BIG_HANDLE
            ));
        }
        if (SettingsStatus.showSeekbarEnabled && SettingsStatus.storySeekbarEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Seek bar on video stories",
                    "Add a thin line along the bottom of a video story that you can drag to go "
                            + "back or skip ahead. The time shows while you drag, and a tap on it "
                            + "still moves between stories.",
                    Settings.STORY_SEEKBAR
            ));
        }
        if (SettingsStatus.seekbarThumbnailEnabled) {
            addPreference(new TogglePreference(
                    context,
                    "Show the progress bar thumbnail",
                    "Show a video preview thumbnail while dragging the progress bar.",
                    Settings.SHOW_SEEKBAR_THUMBNAIL
            ));
        }
        if (SettingsStatus.videoFitEnabled) {
            TogglePreference fit = new TogglePreference(context, "Fit the video to the screen",
                    "Show the whole video instead of cropping it to the window. Nothing changes "
                            + "on a tall phone, where it already fits. On a folding phone opened "
                            + "up, a squarer screen or a split view the sides or the ends stop "
                            + "being cut off.",
                    Settings.FIT_VIDEO_TO_SCREEN);
            TogglePreference fill = new TogglePreference(context, "Fill the screen with the video",
                    "Crop the video until it covers the whole window. On a tall phone the black "
                            + "strip TikTok leaves under a video goes and so does a little of each side.",
                    Settings.FILL_VIDEO_TO_SCREEN);
            // One or the other: the whole video inside the window and the window covered can't
            // both hold, so turning either on turns the other off.
            fit.setOnPreferenceChangeListener((preference, value) -> {
                if (Boolean.TRUE.equals(value)) {
                    Settings.FILL_VIDEO_TO_SCREEN.save(false);
                    fill.setChecked(false);
                }
                return true;
            });
            fill.setOnPreferenceChangeListener((preference, value) -> {
                if (Boolean.TRUE.equals(value)) {
                    Settings.FIT_VIDEO_TO_SCREEN.save(false);
                    fit.setChecked(false);
                }
                return true;
            });
            addPreference(fit);
            addPreference(fill);
        }
        if (SettingsStatus.feedMuteEnabled) {
            addPreference(new TogglePreference(context, "Mute feed videos",
                    "Play feed videos without sound and leave the phone's volume alone. Music "
                            + "from another app keeps playing while it's on. DMs, stories and LIVE "
                            + "keep their sound.",
                    Settings.FEED_MUTED));
        }
        if (SettingsStatus.keepPulledSoundsEnabled) {
            addPreference(new TogglePreference(context, "Play sounds TikTok pulled",
                    "Play the audio on videos TikTok silenced because their sound was pulled for "
                            + "copyright or in your region. TikTok may still say the sound isn't "
                            + "available.",
                    Settings.KEEP_PULLED_SOUNDS));
        }
        if (SettingsStatus.backgroundPlayEnabled) {
            addPreference(new TogglePreference(context, "Keep playing in the background",
                    "The video you're watching keeps playing to its end after you leave "
                            + "TikTok or turn the screen off, with TikTok's own media notification "
                            + "to pause it. TikTok's background play switch stays on while this is "
                            + "on. Restart TikTok to apply this.",
                    Settings.BACKGROUND_PLAY));
            addPreference(new TogglePreference(context, "Replay in the background",
                    "When a video ends while TikTok plays in the background, it starts over "
                            + "instead of waiting for you to press Play in the notification. A "
                            + "call or another app's sound still pauses it.",
                    Settings.BACKGROUND_REPLAY));
        }
        if (SettingsStatus.pictureInPictureEnabled) {
            addPreference(new TogglePreference(context, "Keep watching in a small window",
                    "When you leave TikTok while a video plays, it keeps playing in a small window "
                            + "over your other apps, with a button to pause it. Works in the feed and "
                            + "on videos you open from a profile, search or a sound. Needs Android 8 "
                            + "or later.",
                    Settings.PICTURE_IN_PICTURE));
        }

        if (SettingsStatus.playbackSpeedEnabled) {
            addPreference(new SectionHeadingPreference(context, "Speed"));
            addPreference(new TogglePreference(context, "Remember the last speed",
                    "Keep the speed you chose for the next video. Off, each new video "
                            + "starts at 1x and a manual choice lasts for that video only.",
                    Settings.REMEMBER_SPEED));
            addPreference(new TogglePreference(context, "Use a default playback speed",
                    "Start each new video at your default. A manual choice lasts until the video changes.",
                    Settings.DEFAULT_SPEED_ENABLED));
            addPreference(new ChoicePreference(context, "Default playback speed", Settings.DEFAULT_SPEED,
                    new String[]{"0.5x", "0.75x", "1x", "1.25x", "1.5x", "1.75x", "2x", "2.5x", "3x"},
                    new String[]{"0.5", "0.75", "1", "1.25", "1.5", "1.75", "2", "2.5", "3"}));
            String slowest = PlaybackSpeedPatch.speedLabel(PlaybackSpeedPatch.MIN_SPEED);
            String fastest = PlaybackSpeedPatch.speedLabel(PlaybackSpeedPatch.MAX_SPEED);
            InputTextPreference speeds = new InputTextPreference(context, "Speed menu choices",
                    L10n.f(context, "Up to %1$d speeds from %2$s to %3$s, separated by commas. Example: 0.5, 1, 1.5, 2, 2.5, 3. Leave empty for TikTok's list. Restart TikTok to apply this.",
                            PlaybackSpeedPatch.MAX_MENU_SPEEDS, slowest, fastest),
                    Settings.CUSTOM_SPEEDS);
            speeds.withNameKeyboard();
            speeds.withCheck(value -> {
                if (value == null || value.isEmpty()) return null;
                try { PlaybackSpeedPatch.parseMenuSpeeds(value); return null; }
                catch (IllegalArgumentException error) {
                    return L10n.f(context, "Enter up to %1$d comma-separated speeds from %2$s to %3$s",
                            PlaybackSpeedPatch.MAX_MENU_SPEEDS, slowest, fastest);
                }
            });
            speeds.setOnPreferenceChangeListener((preference, value) -> {
                if (value == null || value.toString().isEmpty()) return true;
                try { PlaybackSpeedPatch.parseMenuSpeeds(value.toString()); return true; }
                catch (IllegalArgumentException error) { return false; }
            });
            addPreference(speeds);
            addPreference(new ChoicePreference(context, "Speed while you hold the video", Settings.HOLD_SPEED,
                    new String[]{"1.25x", "1.5x", "1.75x", "2x", "2.5x", "3x"},
                    new String[]{"1.25", "1.5", "1.75", "2", "2.5", "3"}));
        }
        if (SettingsStatus.playbackQualityEnabled || SettingsStatus.sdrPlaybackEnabled
                || SettingsStatus.h264PlaybackEnabled) {
            addPreference(new SectionHeadingPreference(context, "Quality"));
        }
        if (SettingsStatus.playbackQualityEnabled) {
            addPreference(new ChoicePreference(context, "Video playback quality", Settings.PLAYBACK_QUALITY,
                    new String[]{"Automatic", "Highest", "Lowest", "1080p", "720p", "540p", "480p", "360p"},
                    new String[]{"auto", "highest", "lowest", "1080", "720", "540", "480", "360"}));
            addPreference(new ChoicePreference(context, "On mobile data", Settings.PLAYBACK_QUALITY_METERED,
                    new String[]{"No limit", "Highest", "Lowest", "1080p", "720p", "540p", "480p", "360p"},
                    new String[]{"off", "highest", "lowest", "1080", "720", "540", "480", "360"}));
        }
        if (SettingsStatus.sdrPlaybackEnabled) {
            addPreference(new TogglePreference(context, "Play SDR instead of HDR",
                    "HDR videos light the screen extra bright. This plays the standard (SDR) "
                            + "version instead, so the screen doesn't jump to full brightness. "
                            + "Videos with no standard version still play in HDR.",
                    Settings.PLAY_SDR));
        }
        if (SettingsStatus.h264PlaybackEnabled) {
            addPreference(new TogglePreference(context, "Prefer H.264 video",
                    "Picks the older H.264 video format when TikTok also offers a newer one "
                            + "(HEVC or ByteVC2). It helps phones that stutter or run hot. "
                            + "Videos without an H.264 version still play.",
                    Settings.PREFER_H264));
        }
    }
}
