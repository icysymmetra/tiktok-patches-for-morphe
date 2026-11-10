/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.comment.CommentCopySanitizer;
import app.morphe.extension.tiktok.interaction.StopVideoLoopingPatch;
import app.morphe.extension.tiktok.publishdate.AlwaysShowPublishDatePatch;
import app.morphe.extension.tiktok.search.SearchSuggestions;
import app.morphe.extension.tiktok.seekbar.SeekbarPatch;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The one-method bridges a patch calls into, each of which answers a host question from a
 * setting. None had a test naming it. Each one's whole job is a polarity, and a polarity is
 * the easiest thing to get backwards and the hardest to see on a phone, because the host
 * usually looks the same either way until the one screen where it does not.
 *
 * <p>Every case pins both halves: what the bridge answers with the switch on, and that with
 * it off the host's own value goes through untouched.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SettingBridgesTest {
    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG.save(false);
    }

    @After
    public void tearDown() {
        Settings.COPY_COMMENTS_WITHOUT_USERNAME.resetToDefault();
        Settings.STOP_VIDEO_LOOPING.resetToDefault();
        Settings.ALWAYS_SHOW_PUBLISH_DATE.resetToDefault();
        Settings.HIDE_SEARCH_SUGGESTIONS.resetToDefault();
        Settings.SHOW_SEEKBAR.resetToDefault();
        Settings.SHOW_SEEKBAR_THUMBNAIL.resetToDefault();
        SettingsStatus.hideSearchSuggestionsEnabled = false;
    }

    @Test
    public void copyingACommentDropsTheUsernameOnlyWhenAsked() {
        String copied = "@someone: the comment";
        String comment = "the comment";

        Settings.COPY_COMMENTS_WITHOUT_USERNAME.save(false);
        assertSame(copied, CommentCopySanitizer.sanitizeCopiedCommentText(copied, comment));

        Settings.COPY_COMMENTS_WITHOUT_USERNAME.save(true);
        assertSame(comment, CommentCopySanitizer.sanitizeCopiedCommentText(copied, comment));
        // With nothing to put in its place, what TikTok built is kept rather than dropped.
        assertSame(copied, CommentCopySanitizer.sanitizeCopiedCommentText(copied, null));
    }

    @Test
    public void loopingIsRefusedOnlyWhenAsked() {
        Settings.STOP_VIDEO_LOOPING.save(false);
        assertTrue(StopVideoLoopingPatch.overrideLooping(true));
        assertFalse(StopVideoLoopingPatch.overrideLooping(false));

        Settings.STOP_VIDEO_LOOPING.save(true);
        assertFalse(StopVideoLoopingPatch.overrideLooping(true));
        assertFalse(StopVideoLoopingPatch.overrideLooping(false));
    }

    @Test
    public void thePublishDateGatesAnswerNoOnlyWhenAsked() {
        // The host asks five gates whether to hide the post time on the main feeds, and the
        // bridge answers each. No means it is not hidden, which is what "always show" means.
        Settings.ALWAYS_SHOW_PUBLISH_DATE.save(false);
        assertTrue(AlwaysShowPublishDatePatch.showPostTimeForMainFeeds(true));
        assertFalse(AlwaysShowPublishDatePatch.showPostTimeForMainFeeds(false));

        Settings.ALWAYS_SHOW_PUBLISH_DATE.save(true);
        assertFalse(AlwaysShowPublishDatePatch.showPostTimeForMainFeeds(true));
        assertFalse(AlwaysShowPublishDatePatch.showPostTimeForMainFeeds(false));
    }

    @Test
    public void searchSuggestionsHideOnlyWithThePatchAppliedAndTheSwitchOn() {
        // The status flag is set by the patch at load time. Without the patch the setting
        // row is not even shown, and the bridge must not act on a stale saved value.
        Settings.HIDE_SEARCH_SUGGESTIONS.save(true);
        SettingsStatus.hideSearchSuggestionsEnabled = false;
        assertFalse(SearchSuggestions.shouldHide());

        SettingsStatus.hideSearchSuggestionsEnabled = true;
        assertTrue(SearchSuggestions.shouldHide());

        Settings.HIDE_SEARCH_SUGGESTIONS.save(false);
        assertFalse(SearchSuggestions.shouldHide());
    }

    @Test
    public void theSeekbarShowTypeIsForcedVisibleOnlyForTheTwoHiddenTypes() {
        // Types 3 and 4 are the host's "hidden" and "hidden until drag"; 0 is always shown.
        // Any other type is a state the host owns and is passed through.
        Settings.SHOW_SEEKBAR.save(true);
        assertTrue(SeekbarPatch.isEnabled());
        assertEquals(0, SeekbarPatch.overrideSeekbarShowType(3));
        assertEquals(0, SeekbarPatch.overrideSeekbarShowType(4));
        assertEquals(0, SeekbarPatch.overrideSeekbarShowType(0));
        assertEquals(1, SeekbarPatch.overrideSeekbarShowType(1));
        assertEquals(2, SeekbarPatch.overrideSeekbarShowType(2));
        assertEquals(5, SeekbarPatch.overrideSeekbarShowType(5));

        Settings.SHOW_SEEKBAR.save(false);
        assertFalse(SeekbarPatch.isEnabled());
        assertEquals(3, SeekbarPatch.overrideSeekbarShowType(3));
        assertEquals(4, SeekbarPatch.overrideSeekbarShowType(4));
    }

    @Test
    public void theSeekbarVisibleBranchStillRunsWhenTheStoredTypeAlreadySaysVisible() {
        Settings.SHOW_SEEKBAR.save(true);
        assertEquals(Integer.MIN_VALUE, SeekbarPatch.forceSeekbarRefresh(0, 0));
        assertEquals("a real type change already runs the host branch",
                3, SeekbarPatch.forceSeekbarRefresh(3, 0));
        assertEquals("other requested modes remain the host's decision",
                1, SeekbarPatch.forceSeekbarRefresh(1, 1));

        Settings.SHOW_SEEKBAR.save(false);
        assertEquals("the native equality shortcut stays intact while disabled",
                0, SeekbarPatch.forceSeekbarRefresh(0, 0));
    }

    @Test
    public void theThumbnailGateAnswersOnlyForItsOwnKey() {
        Settings.SHOW_SEEKBAR_THUMBNAIL.save(true);
        assertEquals(1, SeekbarPatch.overrideThumbnailGate("seekbar_show_thumbnail_when_drag", 0));
        // Every other key the same gate reader is asked about is the host's business.
        assertEquals(0, SeekbarPatch.overrideThumbnailGate("seekbar_some_other_key", 0));
        assertEquals(7, SeekbarPatch.overrideThumbnailGate("seekbar_some_other_key", 7));
        assertEquals(0, SeekbarPatch.overrideThumbnailGate(null, 0));

        Settings.SHOW_SEEKBAR_THUMBNAIL.save(false);
        assertEquals(0, SeekbarPatch.overrideThumbnailGate("seekbar_show_thumbnail_when_drag", 0));
        assertEquals(1, SeekbarPatch.overrideThumbnailGate("seekbar_show_thumbnail_when_drag", 1));
    }
}
