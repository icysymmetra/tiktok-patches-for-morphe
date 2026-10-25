package app.morphe.extension.tiktok.interaction;

import static org.junit.Assert.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Looper;
import android.preference.PreferenceActivity;
import android.preference.PreferenceScreen;
import android.view.Gravity;
import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.preference.categories.InterfacePreferenceCategory;
import java.io.File;
import java.io.FileOutputStream;
import com.ss.android.ugc.aweme.comment.model.Comment;
import java.time.Duration;
import org.junit.Rule;
import org.junit.Test;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TapConfirmationTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    @After public void tearDown() {
        SettingsStatus.confirmInteractionsEnabled = false;
        // A Setting keeps the value it last loaded in memory, whichever test's store it came from.
        Settings.CONFIRM_COMMENT_LIKE.resetToDefault();
        Settings.CONFIRM_STORY_LIKE.resetToDefault();
        Settings.CONFIRM_QUICK_REPOST.resetToDefault();
    }
    public static final class TestActivity extends PreferenceActivity {}
    public static final class Params {
        public final Clip aweme;
        Params(String id) { aweme = new Clip(id); }
    }
    public static final class Clip {
        public final String aid;
        public final Author author = new Author();
        Clip(String id) { aid = id; }
    }
    public static final class Author { public final String uid = "same_creator"; }

    /** The comment list's like view: a view of its own that keeps the comment it shows. */
    public static final class LikeView extends FrameLayout {
        final Comment comment;
        LikeView(Context context, Comment comment) { super(context); this.comment = comment; }
    }
    /** A photo post's comment like: no view in hand, the comment in a field. */
    public static final class PhotoLike {
        final Comment comment;
        PhotoLike(Comment comment) { this.comment = comment; }
    }
    public static final class TwoComments { Comment first = new Comment("a", false), second = new Comment("b", false); }
    public static final class Story {
        final String aid;
        final boolean liked;
        Story(String aid, boolean liked) { this.aid = aid; this.liked = liked; }
        public String getAid() { return aid; }
        public boolean isLike() { return liked; }
    }

    @Test public void leavingAndReturningToSameVideoRequiresFreshConfirmation() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            View view = new View(controller.get());
            var tracker = app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor.class;
            var update = tracker.getDeclaredMethod("update", Object.class);
            update.setAccessible(true);
            var playing = tracker.getDeclaredMethod("onPlaying", String.class);
            playing.setAccessible(true);

            // A bind alone no longer means the video changed: the feed binds the items
            // either side of the current one before the user reaches them. The player
            // naming a video is what makes it current, so drive both here.
            update.invoke(null, new Params("one"));
            playing.invoke(null, "one");
            assertFalse(TapConfirmation.allow(view, "follow", "one", true));
            update.invoke(null, new Params("two"));
            playing.invoke(null, "two");
            assertNull(view.getForeground());
            update.invoke(null, new Params("one"));
            playing.invoke(null, "one");
            assertFalse(TapConfirmation.allow(view, "follow", "one", true));
        }
    }
    @Test public void firstTapArmsSecondTapRunsButNewVideoAndExpiredTapsRearm() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            PreferenceActivity activity = controller.get();
            Utils.setContext(activity);
            TextView button = new TextView(activity);
            button.setText("+");
            button.setTextSize(28);
            button.setTextColor(Color.WHITE);
            button.setGravity(Gravity.CENTER);
            button.setBackgroundColor(0xFF252525);
            assertFalse(TapConfirmation.allow(button, "follow", "video1", true));
            assertNotNull(button.getForeground());
            int size = View.MeasureSpec.makeMeasureSpec(56, View.MeasureSpec.EXACTLY);
            button.measure(size, size);
            button.layout(0, 0, 56, 56);
            Bitmap bitmap = Bitmap.createBitmap(80, 80, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);
            canvas.drawColor(Color.BLACK);
            canvas.translate(12, 12);
            button.draw(canvas);
            String directory = System.getProperty("morphe.screenshotDir");
            if (directory != null) {
                File output = new File(directory, "confirmation-control.png");
                assertTrue(output.getParentFile().isDirectory() || output.getParentFile().mkdirs());
                try (FileOutputStream stream = new FileOutputStream(output)) {
                    assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
                }
            }
            assertTrue(TapConfirmation.allow(button, "follow", "video1", true));
            assertNull(button.getForeground());
            assertFalse(TapConfirmation.allow(button, "follow", "video1", true));
            assertFalse(TapConfirmation.allow(button, "follow", "video2", true));
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(4));
            assertNull(button.getForeground());
            assertFalse(TapConfirmation.allow(button, "follow", "video2", true));
            assertTrue(TapConfirmation.allow(button, "follow", "video2", false));
            assertNull(button.getForeground());
        }
    }

    @Test public void standalonePatchExposesEverySetting() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            PreferenceActivity activity = controller.get();
            Utils.setContext(activity);
            SettingsStatus.confirmInteractionsEnabled = true;
            PreferenceScreen screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new InterfacePreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("confirm_follow"));
            assertNotNull(screen.findPreference("confirm_like"));
            assertNotNull(screen.findPreference("confirm_comment_like"));
            assertNotNull(screen.findPreference("confirm_story_like"));
            assertNotNull(screen.findPreference("confirm_quick_repost"));
        }
    }

    @Test public void aCommentLikeAsksOnceUnlessTheCommentIsLikedAlready() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            Settings.CONFIRM_COMMENT_LIKE.save(true);
            LikeView view = new LikeView(controller.get(), new Comment("c1", false));
            assertFalse(TapConfirmation.commentLike(view));
            assertNotNull("the like view carries the ring", view.getForeground());
            assertTrue(TapConfirmation.commentLike(view));
            assertNull(view.getForeground());

            assertTrue("removing a like stays immediate",
                    TapConfirmation.commentLike(new LikeView(controller.get(), new Comment("c2", true))));
            Settings.CONFIRM_COMMENT_LIKE.save(false);
            assertTrue(TapConfirmation.commentLike(new LikeView(controller.get(), new Comment("c3", false))));
        }
    }

    @Test public void aPhotoPostsCommentLikeArmsWithoutARingAndEachControlArmsItself() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            Settings.CONFIRM_COMMENT_LIKE.save(true);
            org.robolectric.shadows.ShadowToast.reset();
            // The photo page's like controls are assems: no view to ring, and the patch hands the
            // comment over itself, since one of them keeps no Comment field at all.
            Comment comment = new Comment("c1", false);
            Object first = new Object();
            assertFalse(TapConfirmation.commentLike(first, comment));
            assertEquals("Tap again to like", String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()));
            Object other = new Object();
            assertFalse("another control's tap is a first tap", TapConfirmation.commentLike(other, comment));
            assertTrue(TapConfirmation.commentLike(other, comment));
        }
    }

    @Test public void aPhotoPostsLikeAsksAboutTheCommentItHandsOver() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            Settings.CONFIRM_COMMENT_LIKE.save(true);
            Object control = new Object();
            assertFalse(TapConfirmation.commentLike(control, new Comment("c7", false)));
            assertFalse("another comment on the same control is a first tap",
                    TapConfirmation.commentLike(control, new Comment("c8", false)));
            assertTrue(TapConfirmation.commentLike(control, new Comment("c8", false)));
            assertTrue("removing a like stays immediate", TapConfirmation.commentLike(control, new Comment("c9", true)));
            assertTrue("no comment to name", TapConfirmation.commentLike(control, null));
        }
    }

    @Test public void aCommentLikeThatCantBePinnedToOneCommentGoesThrough() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            Settings.CONFIRM_COMMENT_LIKE.save(true);
            assertTrue("no comment field", TapConfirmation.commentLike(new Object()));
            assertTrue("two comment fields", TapConfirmation.commentLike(new TwoComments()));
            assertTrue("a comment with no id", TapConfirmation.commentLike(new PhotoLike(new Comment(null, false))));
            assertTrue("no control", TapConfirmation.commentLike(null));
        }
    }

    @Test public void aStoryLikeAsksAboutTheStoryItWasHanded() {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            Settings.CONFIRM_STORY_LIKE.save(true);
            View button = new View(controller.get());
            assertFalse(TapConfirmation.storyLike(button, new Story("s1", false)));
            assertNotNull(button.getForeground());
            assertFalse("another story is a first tap", TapConfirmation.storyLike(button, new Story("s2", false)));
            assertTrue(TapConfirmation.storyLike(button, new Story("s2", false)));
            assertTrue("removing a like stays immediate", TapConfirmation.storyLike(button, new Story("s3", true)));
            Settings.CONFIRM_STORY_LIKE.save(false);
            assertTrue(TapConfirmation.storyLike(button, new Story("s4", false)));
        }
    }

    @Test public void aQuickRepostAsksAboutThePlayingVideo() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            Settings.CONFIRM_QUICK_REPOST.save(true);
            var tracker = app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor.class;
            var reset = tracker.getDeclaredMethod("resetForTests");
            reset.setAccessible(true);
            reset.invoke(null);
            Object bar = new Object();
            assertTrue("with no video to name, the repost goes through", TapConfirmation.quickRepost(bar));

            var update = tracker.getDeclaredMethod("update", Object.class);
            update.setAccessible(true);
            var playing = tracker.getDeclaredMethod("onPlaying", String.class);
            playing.setAccessible(true);
            update.invoke(null, new Params("v1"));
            playing.invoke(null, "v1");
            org.robolectric.shadows.ShadowToast.reset();
            assertFalse(TapConfirmation.quickRepost(bar));
            assertEquals("Tap again to repost", String.valueOf(org.robolectric.shadows.ShadowToast.getTextOfLatestToast()));
            assertTrue(TapConfirmation.quickRepost(bar));
            Settings.CONFIRM_QUICK_REPOST.save(false);
            assertTrue(TapConfirmation.quickRepost(bar));
            reset.invoke(null);
        }
    }

    /**
     * With no playing video to name, a second tap had nothing to match and the like or follow
     * could never go through. It goes through at once, as a repost does.
     */
    @Test public void aLikeOrFollowWithNoVideoToNameGoesThrough() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            Settings.CONFIRM_LIKE.save(true);
            Settings.CONFIRM_FOLLOW.save(true);
            var reset = app.morphe.extension.tiktok.blockauthor.CurrentVideoAuthor.class
                    .getDeclaredMethod("resetForTests");
            reset.setAccessible(true);
            reset.invoke(null);
            android.view.View button = new android.view.View(controller.get());
            assertTrue("a like with no video to name was held", TapConfirmation.like(button));
            assertTrue("a follow with no video to name was held", TapConfirmation.follow(button));
        } finally {
            Settings.CONFIRM_LIKE.resetToDefault();
            Settings.CONFIRM_FOLLOW.resetToDefault();
        }
    }

    @Test public void theRingContrastsOnAnyFillAndArmPerformsAHaptic() throws Exception {
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            View view = new View(controller.get());
            assertFalse(TapConfirmation.allow(view, "follow", "video1", true));
            Drawable foreground = view.getForeground();
            assertTrue("the ring is not a LayerDrawable", foreground instanceof LayerDrawable);
            LayerDrawable layers = (LayerDrawable) foreground;
            assertEquals("the ring needs two layers (outer white, inner dark)", 2, layers.getNumberOfLayers());
            GradientDrawable outer = (GradientDrawable) layers.getDrawable(0);
            assertNotNull(outer);
            assertNotEquals("the outer stroke must not be OVERLAY_ACCENT (red)",
                    SettingsUi.OVERLAY_ACCENT,
                    outer.getColor() == null ? 0 : outer.getColor().getDefaultColor());
            GradientDrawable inner = (GradientDrawable) layers.getDrawable(1);
            assertNotNull(inner);
            assertTrue("arm must perform a haptic",
                    Shadows.shadowOf(view).lastHapticFeedbackPerformed() >= 0);
        }
    }

    @Test @org.robolectric.annotation.Config(qualifiers = "de")
    public void theConfirmationIsWholeInEveryLanguage() throws Exception {
        // The verb used to be an English literal spliced into a translated sentence, so a German
        // phone read "Noch einmal tippen zum follow".
        try (var controller = Robolectric.buildActivity(TestActivity.class).setup()) {
            Utils.setContext(controller.get());
            for (String action : new String[]{"follow", "like", "repost"}) {
                org.robolectric.shadows.ShadowToast.reset();
                View view = new View(controller.get());
                assertFalse(TapConfirmation.allow(view, action, "video-" + action, true));

                String shown = String.valueOf(
                        org.robolectric.shadows.ShadowToast.getTextOfLatestToast());
                assertFalse("the toast still carries the English verb: " + shown,
                        shown.contains(action));
                assertTrue("the toast is not the German sentence: " + shown,
                        shown.startsWith("Noch einmal tippen"));
            }
        }
    }
}
