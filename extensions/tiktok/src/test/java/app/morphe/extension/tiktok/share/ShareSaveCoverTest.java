/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import static org.junit.Assert.*;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.widget.ImageView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.share.improve.pkg.AwemeSharePackage;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Save cover beside Download in a video's share sheet (#146). */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28, qualifiers = "en")
public class ShareSaveCoverTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    public static final class UrlModel {
        public final List<String> urlList;
        UrlModel(String... urls) { urlList = Arrays.asList(urls); }
        public List<String> getUrlList() { return urlList; }
        public int getWidth() { return 720; }
        public int getHeight() { return 1280; }
    }

    public static final class Video {
        public UrlModel cover;
        public UrlModel getCover() { return cover; }
    }

    public static final class Post extends Aweme {
        public final Video video = new Video();
        private final String id;

        Post(String id, boolean withCover) {
            this.id = id;
            if (withCover) video.cover = new UrlModel("https://p16.example/" + id + ".jpeg");
        }
        public Video getVideo() { return video; }
        @Override public String getAid() { return id; }
    }

    public static final class Action {
        private final String originalKey;
        Action(String key) { originalKey = key; }
        public String key() {
            String owned = OwnedShareActions.key(this);
            return owned == null ? originalKey : owned;
        }
    }

    private final List<Object> saved = new ArrayList<>();
    private Activity activity;
    private int constructed;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        Utils.setActivity(activity);
        SettingsStatus.advancedDownloadsEnabled = true;
        Settings.SAVE_COVER_BUTTON.save(true);
        ShareSaveCover.nativeForTests = (from, post) -> {
            assertSame(activity, from);
            assertTrue(post instanceof Post);
            constructed++;
            return new Action("report");
        };
        ShareSaveCover.saverForTests = (Context context, Object post) -> saved.add(post);
    }

    @After public void tearDown() {
        ShareSaveCover.nativeForTests = null;
        ShareSaveCover.saverForTests = null;
        ((List<?>) ReflectionHelpers.getStaticField(ShareSaveCover.class, "BUILT")).clear();
        Settings.SAVE_COVER_BUTTON.resetToDefault();
        Settings.DOWNLOAD_COVER.resetToDefault();
        Settings.HIDE_SHARE_ACTIONS.save(false);
        Settings.SHARE_HIDDEN_ITEMS.save("");
        SettingsStatus.advancedDownloadsEnabled = false;
        SettingsStatus.shareSheetEnabled = false;
        PausedProcess.set(false);
        activity.finish();
    }

    private static List<Object> row() {
        return new ArrayList<>(Arrays.asList(new Action("report"), new Action("save"), new Action("duet")));
    }

    private static AwemeSharePackage sheet(String id) { return new AwemeSharePackage(new Post(id, true)); }

    @Test public void goesRightAfterDownloadOnceAndKeepsTheNativeRowAsItWas() {
        assertFalse(Settings.SAVE_COVER_BUTTON.defaultValue);
        AwemeSharePackage sheet = sheet("1");
        List<Object> nativeRow = row();
        List<?> finished = ShareSaveCover.actions(sheet, nativeRow);
        assertEquals(3, nativeRow.size());
        assertEquals(4, finished.size());
        assertSame(nativeRow.get(1), finished.get(1));
        assertEquals(ShareSaveCover.KEY, ((Action) finished.get(2)).key());
        assertSame(nativeRow.get(2), finished.get(3));
        assertSame(finished, ShareSaveCover.actions(sheet, finished));
        assertEquals(1, constructed);
        assertEquals("Save cover", OwnedShareActions.label(finished.get(2)));
        assertTrue(ShareActionCatalog.entries(ShareSurface.VIDEO).stream().anyMatch(entry ->
                entry.key.equals("hushfeed save cover") && entry.label.equals("Save cover")));
    }

    @Test public void withoutDownloadInTheRowItLeadsTheRow() {
        List<Object> nativeRow = new ArrayList<>(Arrays.asList(new Action("report"), new Action("duet")));
        List<?> finished = ShareSaveCover.actions(sheet("2"), nativeRow);
        assertEquals(ShareSaveCover.KEY, ((Action) finished.get(0)).key());
        assertEquals(3, finished.size());
    }

    @Test public void offPausedHiddenCoverlessAndOtherSheetsGetNoButton() {
        List<Object> nativeRow = row();
        Settings.SAVE_COVER_BUTTON.save(false);
        assertSame(nativeRow, ShareSaveCover.actions(sheet("3"), nativeRow));
        Settings.SAVE_COVER_BUTTON.save(true);
        SettingsStatus.advancedDownloadsEnabled = false;
        assertSame("needs Advanced downloads", nativeRow, ShareSaveCover.actions(sheet("3"), nativeRow));
        SettingsStatus.advancedDownloadsEnabled = true;
        PausedProcess.set(true);
        assertSame(nativeRow, ShareSaveCover.actions(sheet("3"), nativeRow));
        PausedProcess.set(false);

        assertSame("a photo post or a video without a cover", nativeRow,
                ShareSaveCover.actions(new AwemeSharePackage(new Post("4", false)), nativeRow));
        AwemeSharePackage profile = sheet("5");
        profile.itemType = "user";
        assertSame(nativeRow, ShareSaveCover.actions(profile, nativeRow));
        assertSame(nativeRow, ShareSaveCover.actions(new AwemeSharePackage(new Object()), nativeRow));
        assertSame(nativeRow, ShareSaveCover.actions(null, nativeRow));
        assertNull(ShareSaveCover.actions(sheet("5"), null));

        SettingsStatus.shareSheetEnabled = true;
        Settings.SHARE_HIDDEN_ITEMS.save("Save cover");
        assertSame("hidden with Share sheet tools", nativeRow, ShareSaveCover.actions(sheet("6"), nativeRow));
        Settings.SHARE_HIDDEN_ITEMS.save("");
        Settings.HIDE_SHARE_ACTIONS.save(true);
        assertSame(nativeRow, ShareSaveCover.actions(sheet("6"), nativeRow));
        assertEquals(0, constructed);
    }

    @Test public void turningItOffTakesABuiltButtonBackOut() {
        AwemeSharePackage sheet = sheet("7");
        List<?> finished = ShareSaveCover.actions(sheet, row());
        Object button = finished.get(2);
        Settings.SAVE_COVER_BUTTON.save(false);
        List<?> after = ShareSaveCover.actions(sheet, finished);
        assertEquals(3, after.size());
        assertFalse(after.contains(button));
        assertTrue("retired, not forgotten, until the sheet lets go", OwnedShareActions.owns(button));
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertTrue(saved.isEmpty());
    }

    @Test public void aTapSavesThatSheetsCoverAndOnlyOwnedCarriersAreTaken() {
        AwemeSharePackage sheet = sheet("8");
        Object button = ShareSaveCover.actions(sheet, row()).get(2);
        assertTrue(OwnedShareActions.click(button, activity, sheet));
        assertEquals(List.of(sheet.aweme), saved);

        assertFalse("TikTok's own Report keeps its click", OwnedShareActions.click(new Action("report"), activity, sheet));
        assertTrue("another sheet's tap is taken but saves nothing", OwnedShareActions.click(button, activity, sheet("9")));
        assertEquals(1, saved.size());
    }

    @Test public void theIconIsDrawnOnlyOnTheButton() {
        Object button = ShareSaveCover.actions(sheet("10"), row()).get(2);
        ImageView image = new ImageView(activity);
        ColorDrawable nativeIcon = new ColorDrawable();
        image.setImageDrawable(nativeIcon);
        OwnedShareActions.bindIcon(new Action("report"), image);
        assertSame(nativeIcon, image.getDrawable());
        OwnedShareActions.bindIcon(button, image);
        assertTrue(image.getDrawable() instanceof CoverGlyphDrawable);
    }

    /** The binder's framed-icon test sees Report's key on the button, so the button gets TikTok's frame. */
    @Test public void theButtonAsksForTikToksIconFrameAndNothingElseDoes() {
        Object button = ShareSaveCover.actions(sheet("11"), row()).get(2);
        assertEquals("report", OwnedShareActions.framedKey(button, "save_cover"));
        assertEquals("share", OwnedShareActions.framedKey(new Action("share"), "share"));
        assertNull(OwnedShareActions.framedKey(new Object(), null));
    }
}
