package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.interaction.GestureActions;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.feed.assem.ability.IVideoCommentAbility;
import com.ss.android.ugc.aweme.feed.assem.desc.VideoDescVM;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.PhotoModeImageInfo;
import com.ss.android.ugc.aweme.feed.model.PhotoModeTextInfo;
import java.util.concurrent.TimeUnit;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public class CaptionCommentsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** TikTok's ways into the caption, by the names the hook reads. */
    enum Mechanism { SEE_MORE_BTN, TAP_TEXT, TAP_PHOTO }

    /** A video as the checks read it: its id, whether it's an ad and whether it's a photo post. */
    static final class Video extends Aweme {
        final String id;
        boolean ad;
        PhotoModeImageInfo photos;

        Video(String id) {
            this.id = id;
        }

        @Override public String getAid() { return id; }
        @Override public boolean isAd() { return ad; }
        @Override public PhotoModeImageInfo getPhotoModeImageInfo() { return photos; }
        @Override public PhotoModeTextInfo getPhotoModeTextInfo() { return null; }
    }

    /** The feed item a VideoDescVM holds. */
    public static final class Item {
        public final Object aweme;

        Item(Object aweme) {
            this.aweme = aweme;
        }
    }

    /** The caption handler, obfuscated on TikTok, holding its VideoDescVM under some field name. */
    static final class Caption {
        final VideoDescVM LLJLLL;

        Caption(Object aweme) {
            LLJLLL = new VideoDescVM(new Item(aweme));
        }
    }

    /** The comment assem whose icon press opens the sheet. */
    public static final class CommentAssem implements IVideoCommentAbility {
        int presses;

        @Override public void Id0() { presses++; }
        @Override public void XZ1(String enterMethod) { }
        @Override public boolean c00(float x, float y) { return false; }
        @Override public void jo2(int jumpType) { }
        @Override public Rect nw2() { return new Rect(); }
        @Override public void qg1(CharSequence text, String source) { }
    }

    public static final class Params {
        public final Object aweme;

        Params(Object aweme) {
            this.aweme = aweme;
        }
    }

    private FrameLayout root;

    @Before public void setUp() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        Utils.setContext(activity);
        root = new FrameLayout(activity);
        activity.setContentView(root);
        reset();
    }

    @After public void tearDown() {
        reset();
    }

    private static void reset() {
        Settings.CAPTION_OPENS_COMMENTS.resetToDefault();
        Settings.CAPTION_ABOVE_COMMENTS.resetToDefault();
        CaptionComments.resetForTests();
        HookStatus.clear();
    }

    /** The comment button of {@code video} on screen, as Double-tap controls registers it. */
    private CommentAssem button(Video video) {
        CommentAssem assem = new CommentAssem();
        View view = new View(root.getContext());
        root.addView(view, new FrameLayout.LayoutParams(40, 40));
        GestureActions.registerCommentView(assem, view);
        GestureActions.bindCommentView(assem, new Params(video));
        return assem;
    }

    private static boolean rowFor(Object video) {
        CaptionComments.listFor(video);
        return CaptionComments.showCaption(false);
    }

    @Test public void tikTokKeepsTheCaptionByDefault() {
        Video video = new Video("tikTokKeepsTheCaptionByDefault-one");
        CommentAssem assem = button(video);
        assertFalse(CaptionComments.opensComments(new Caption(video), true, "", Mechanism.SEE_MORE_BTN));
        assertEquals("the switch is off, so TikTok spreads the caption", 0, assem.presses);
        assertFalse("and no list starts with it", rowFor(video));
        CaptionComments.listFor(video);
        assertTrue("an opener that asked for the caption still gets it", CaptionComments.showCaption(true));
    }

    @Test public void moreOpensTheVideosCommentsWithItsCaptionOnTop() {
        Settings.CAPTION_OPENS_COMMENTS.save(true);
        Video video = new Video("moreOpensTheVideosCommentsWithItsCaptionOnTop-one");
        CommentAssem assem = button(video);
        assertTrue(CaptionComments.opensComments(new Caption(video), true, "", Mechanism.SEE_MORE_BTN));
        assertEquals("the comment button was pressed", 1, assem.presses);
        assertTrue("the list that opened starts with the caption", rowFor(video));
        assertFalse("the next list of the same video is TikTok's again", rowFor(video));
    }

    @Test public void aTapOnTheCaptionTextCountsButCollapsingAndPhotosDoNot() {
        Settings.CAPTION_OPENS_COMMENTS.save(true);
        Video video = new Video("aTapOnTheCaptionTextCountsButCollapsingAndPhotosDoNot-one");
        CommentAssem assem = button(video);
        Caption caption = new Caption(video);
        assertTrue(CaptionComments.opensComments(caption, true, "click_descr", Mechanism.TAP_TEXT));
        assertFalse("collapsing is TikTok's", CaptionComments.opensComments(caption, false, "click_hide", Mechanism.SEE_MORE_BTN));
        assertFalse("a tap on a photo is TikTok's", CaptionComments.opensComments(caption, true, "", Mechanism.TAP_PHOTO));
        assertFalse("a way this build doesn't know is TikTok's", CaptionComments.opensComments(caption, true, "", "SEE_MORE_BTN"));
        assertEquals(1, assem.presses);
    }

    @Test public void photoPostsAndAdsStayWithTikTok() {
        Settings.CAPTION_OPENS_COMMENTS.save(true);
        Settings.CAPTION_ABOVE_COMMENTS.save(true);
        Video photo = new Video("photoPostsAndAdsStayWithTikTok-photo");
        photo.photos = new PhotoModeImageInfo();
        Video ad = new Video("photoPostsAndAdsStayWithTikTok-ad");
        ad.ad = true;
        CommentAssem photoButton = button(photo);
        CommentAssem adButton = button(ad);
        assertFalse(CaptionComments.opensComments(new Caption(photo), true, "", Mechanism.SEE_MORE_BTN));
        assertFalse(CaptionComments.opensComments(new Caption(ad), true, "", Mechanism.SEE_MORE_BTN));
        assertEquals(0, photoButton.presses + adButton.presses);
        assertFalse("a photo post's list is TikTok's", rowFor(photo));
        assertFalse("and so is an ad's", rowFor(ad));
        assertFalse("and a list whose video wasn't handed over", CaptionComments.showCaption(false));
    }

    @Test public void withNoCommentButtonTikTokSpreadsTheCaptionAsBefore() {
        Settings.CAPTION_OPENS_COMMENTS.save(true);
        Video video = new Video("withNoCommentButtonTikTokSpreadsTheCaptionAsBefore-one");
        assertFalse(CaptionComments.opensComments(new Caption(video), true, "", Mechanism.SEE_MORE_BTN));
        assertFalse("nothing opened, so no list is owed the caption", rowFor(video));
    }

    @Test public void theRowGoesToTheOpenedVideoAndOnlyForAWhile() {
        Settings.CAPTION_OPENS_COMMENTS.save(true);
        Video one = new Video("theRowGoesToTheOpenedVideoAndOnlyForAWhile-one");
        Video two = new Video("theRowGoesToTheOpenedVideoAndOnlyForAWhile-two");
        button(one);
        assertTrue(CaptionComments.opensComments(new Caption(one), true, "", Mechanism.SEE_MORE_BTN));
        assertFalse("another video's list", rowFor(two));
        assertTrue("the opened video's list still gets it", rowFor(one));

        assertTrue(CaptionComments.opensComments(new Caption(one), true, "", Mechanism.SEE_MORE_BTN));
        ShadowSystemClock.advanceBy(CaptionComments.PENDING_MS, TimeUnit.MILLISECONDS);
        assertTrue("the last moment the list may still ask", rowFor(one));
        assertTrue(CaptionComments.opensComments(new Caption(one), true, "", Mechanism.SEE_MORE_BTN));
        ShadowSystemClock.advanceBy(CaptionComments.PENDING_MS + 1, TimeUnit.MILLISECONDS);
        assertFalse("a list asking later is someone else's", rowFor(one));
    }

    @Test public void theSecondSwitchPutsTheCaptionAtopEveryVideosComments() {
        Settings.CAPTION_ABOVE_COMMENTS.save(true);
        Video video = new Video("theSecondSwitchPutsTheCaptionAtopEveryVideosComments-one");
        assertTrue(rowFor(video));
        assertTrue("every time", rowFor(video));
        Settings.CAPTION_ABOVE_COMMENTS.save(false);
        assertFalse(rowFor(video));
    }

    @Test public void theCaptionsVideoIsReadThroughItsViewModel() {
        Video video = new Video("theCaptionsVideoIsReadThroughItsViewModel-one");
        assertSame(video, CaptionComments.awemeOf(new Caption(video)));
        assertNull("a handler without the view model", CaptionComments.awemeOf(new Object()));
        assertNull(CaptionComments.awemeOf(null));
    }

    @Test public void theExportCountsTheTapsAndTheRows() {
        int[] found = new int[1];
        HookStatus.setLineWriter((family, count, missing, truncated, firstMiss) -> {
            if (CaptionComments.FAMILY.equals(family)) found[0] = count;
            return family;
        });
        try {
            Video video = new Video("theExportCountsTheTapsAndTheRows-one");
            button(video);
            CaptionComments.opensComments(new Caption(video), true, "", Mechanism.SEE_MORE_BTN);
            rowFor(video);
            HookStatus.report();
            assertEquals("the tap and the row check", 2, found[0]);
            Settings.CAPTION_OPENS_COMMENTS.save(true);
            CaptionComments.opensComments(new Caption(video), true, "", Mechanism.SEE_MORE_BTN);
            rowFor(video);
            HookStatus.report();
            assertEquals("and the comments opened and the row shown", 4, found[0]);
        } finally {
            HookStatus.setLineWriter(null);
        }
    }
}
