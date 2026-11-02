/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.seekbar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class SeekbarPatchTest {
    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.SHOW_SEEKBAR.save(false);
        Settings.SHOW_SEEKBAR_THUMBNAIL.save(false);
    }

    @After
    public void tearDown() {
        Settings.SHOW_SEEKBAR.save(false);
        Settings.SHOW_SEEKBAR_THUMBNAIL.save(false);
    }

    @Test
    public void isEnabledFollowsTheSetting() {
        assertFalse(SeekbarPatch.isEnabled());
        Settings.SHOW_SEEKBAR.save(true);
        assertTrue(SeekbarPatch.isEnabled());
    }

    @Test
    public void hiddenShowTypesAreRewrittenToZeroWhenEnabled() {
        Settings.SHOW_SEEKBAR.save(true);
        assertEquals(0, SeekbarPatch.overrideSeekbarShowType(3));
        assertEquals(0, SeekbarPatch.overrideSeekbarShowType(4));
    }

    @Test
    public void otherShowTypesPassThroughEvenWhenEnabled() {
        Settings.SHOW_SEEKBAR.save(true);
        assertEquals(0, SeekbarPatch.overrideSeekbarShowType(0));
        assertEquals(1, SeekbarPatch.overrideSeekbarShowType(1));
        assertEquals(2, SeekbarPatch.overrideSeekbarShowType(2));
    }

    @Test
    public void showTypesPassThroughWhenDisabled() {
        assertEquals(3, SeekbarPatch.overrideSeekbarShowType(3));
        assertEquals(4, SeekbarPatch.overrideSeekbarShowType(4));
    }

    @Test
    public void refreshDefeatsStaledZeroEqualityWhenEnabled() {
        Settings.SHOW_SEEKBAR.save(true);
        assertEquals(Integer.MIN_VALUE, SeekbarPatch.forceSeekbarRefresh(0, 0));
    }

    @Test
    public void refreshPassesThroughWhenTypesAlreadyDiffer() {
        Settings.SHOW_SEEKBAR.save(true);
        assertEquals(3, SeekbarPatch.forceSeekbarRefresh(3, 0));
    }

    @Test
    public void refreshPassesThroughWhenDisabled() {
        assertEquals(0, SeekbarPatch.forceSeekbarRefresh(0, 0));
    }

    @Test
    public void refreshPassesThroughWhenRequestIsNotZero() {
        Settings.SHOW_SEEKBAR.save(true);
        assertEquals(1, SeekbarPatch.forceSeekbarRefresh(1, 1));
    }

    @Test
    public void theDraggableFlagIsRaisedOnlyWhenEnabledAndOnlyFromNothing() {
        assertEquals(0, SeekbarPatch.overrideDraftProgressBar(0));
        assertEquals(2, SeekbarPatch.overrideDraftProgressBar(2));
        Settings.SHOW_SEEKBAR.save(true);
        assertEquals(1, SeekbarPatch.overrideDraftProgressBar(0));
        // A server value the gate already accepts is handed on as it came.
        assertEquals(2, SeekbarPatch.overrideDraftProgressBar(2));
    }

    @Test
    public void thumbnailGateIsOverriddenWhenEnabled() {
        Settings.SHOW_SEEKBAR_THUMBNAIL.save(true);
        assertEquals(1, SeekbarPatch.overrideThumbnailGate("seekbar_show_thumbnail_when_drag", 0));
    }

    @Test
    public void thumbnailGatePassesThroughForOtherKeys() {
        Settings.SHOW_SEEKBAR_THUMBNAIL.save(true);
        assertEquals(0, SeekbarPatch.overrideThumbnailGate("other_key", 0));
    }

    @Test
    public void thumbnailGatePassesThroughWhenDisabled() {
        assertEquals(0, SeekbarPatch.overrideThumbnailGate("seekbar_show_thumbnail_when_drag", 0));
    }

    /** TikTok's model shapes, by the names the line reads them through. */
    public static final class FakeSheet {
        public List<String> imgUrls;
        public List<String> imgUris;

        FakeSheet(List<String> urls, List<String> uris) {
            imgUrls = urls;
            imgUris = uris;
        }
    }

    public static final class FakeVideo {
        public List<FakeSheet> bigThumbs;
    }

    public static final class FakeAweme {
        private final FakeVideo video;

        FakeAweme(FakeVideo video) {
            this.video = video;
        }

        public FakeVideo getVideo() {
            return video;
        }
    }

    private static FakeAweme videoWith(FakeSheet... sheets) {
        FakeVideo video = new FakeVideo();
        video.bigThumbs = sheets == null ? null : Arrays.asList(sheets);
        return new FakeAweme(video);
    }

    @Test
    public void thePreviewLineCountsSheetsAndTheOnesTikTokWouldUse() {
        FakeSheet whole = new FakeSheet(List.of("https://p16/a.jpg"), List.of("tos/a"));
        FakeSheet noUrls = new FakeSheet(Collections.emptyList(), List.of("tos/b"));
        FakeSheet noUris = new FakeSheet(List.of("https://p16/c.jpg"), null);
        assertEquals("sheets=3 complete=1", SeekbarPatch.describePreviewSheets(videoWith(whole, noUrls, noUris)));
        assertEquals("sheets=2 complete=2", SeekbarPatch.describePreviewSheets(videoWith(whole, whole)));
    }

    @Test
    public void aVideoWithoutSheetsSaysSo() {
        assertEquals("sheets=0 complete=0", SeekbarPatch.describePreviewSheets(videoWith((FakeSheet[]) null)));
        assertEquals("sheets=0 complete=0", SeekbarPatch.describePreviewSheets(videoWith()));
        assertEquals("sheets=none (no video)", SeekbarPatch.describePreviewSheets(new FakeAweme(null)));
    }

    @Test
    public void eachVideoIsLookedAtOnceEvenWhenTikTokAlternates() {
        FakeAweme first = videoWith(new FakeSheet(List.of("u"), List.of("i")));
        FakeAweme second = videoWith();
        assertFalse(SeekbarPatch.firstPreviewLook(null));
        assertTrue(SeekbarPatch.firstPreviewLook(first));
        assertTrue(SeekbarPatch.firstPreviewLook(second));
        // The bar's layout asks about the current video and its neighbour in turn.
        assertFalse(SeekbarPatch.firstPreviewLook(first));
        assertFalse(SeekbarPatch.firstPreviewLook(second));
        assertFalse(SeekbarPatch.firstPreviewLook(first));
    }

    @Test
    public void thePreviewCheckLeavesVideosUnseenWhileLogDiagnosticsIsOff() {
        BaseSettings.DEBUG.save(false);
        ShadowLog.clear();
        FakeAweme aweme = videoWith(new FakeSheet(List.of("u"), List.of("i")));
        SeekbarPatch.onPreviewCheck(null);
        SeekbarPatch.onPreviewCheck(aweme);
        assertEquals(0, previewLines());
        assertTrue(SeekbarPatch.firstPreviewLook(aweme));

        BaseSettings.DEBUG.save(true);
        try {
            FakeAweme logged = videoWith(new FakeSheet(List.of("u"), List.of("i")));
            SeekbarPatch.onPreviewCheck(logged);
            SeekbarPatch.onPreviewCheck(logged);
            assertEquals("one preview line per video", 1, previewLines());
            assertFalse(SeekbarPatch.firstPreviewLook(logged));
        } finally {
            BaseSettings.DEBUG.save(false);
        }
    }

    private static long previewLines() {
        return ShadowLog.getLogs().stream()
                .filter(item -> item.msg != null && item.msg.contains("[Morphe TikTok Seekbar] preview ")
                        && item.msg.contains(" switch="))
                .count();
    }
}
