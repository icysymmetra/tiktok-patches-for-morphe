package app.morphe.extension.tiktok.comment;

import static org.junit.Assert.assertEquals;

import android.app.Activity;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class CommentBoxButtonsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private FrameLayout box;

    @Before public void setUp() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.resetToDefault();
        HookStatus.clear();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        box = new FrameLayout(activity);
        activity.setContentView(box);
    }

    @After public void tearDown() {
        PausedProcess.set(false);
        Settings.HIDE_COMMENT_BOX_BUTTONS.resetToDefault();
        HookStatus.clear();
    }

    private View button() {
        View button = new View(box.getContext());
        box.addView(button);
        return button;
    }

    private void layOut() {
        box.requestLayout();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void tikTokKeepsItsButtonsByDefault() {
        View photo = button();
        CommentBoxButtons.photo(photo);
        assertEquals(View.VISIBLE, photo.getVisibility());
    }

    @Test public void theSwitchHidesEachOfTheThree() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        View photo = button();
        View mention = button();
        View gift = button();
        CommentBoxButtons.photo(photo);
        CommentBoxButtons.mention(mention);
        CommentBoxButtons.gift(gift);
        assertEquals(View.GONE, photo.getVisibility());
        assertEquals(View.GONE, mention.getVisibility());
        assertEquals(View.GONE, gift.getVisibility());
    }

    @Test public void aButtonTikTokShowsAgainIsGoneByTheNextLayout() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        View mention = button();
        CommentBoxButtons.mention(mention);
        mention.setVisibility(View.VISIBLE);
        layOut();
        assertEquals(View.GONE, mention.getVisibility());
    }

    @Test public void aButtonHandedOverBeforeItIsAttachedIsHeldOnceItIs() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        View gift = new View(box.getContext());
        CommentBoxButtons.gift(gift);
        box.addView(gift);
        gift.setVisibility(View.VISIBLE);
        layOut();
        assertEquals(View.GONE, gift.getVisibility());
    }

    @Test public void handingTheSameButtonOverTwiceIsHarmless() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        View photo = button();
        CommentBoxButtons.photo(photo);
        CommentBoxButtons.photo(photo);
        photo.setVisibility(View.VISIBLE);
        layOut();
        assertEquals(View.GONE, photo.getVisibility());
    }

    @Test public void pausedTheButtonsAreTikToks() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        PausedProcess.set(true);
        View photo = button();
        CommentBoxButtons.photo(photo);
        assertEquals(View.VISIBLE, photo.getVisibility());
    }

    @Test public void aNullButtonIsIgnored() {
        Settings.HIDE_COMMENT_BOX_BUTTONS.save(true);
        CommentBoxButtons.photo(null);
    }

    @Test public void theExportCountsEachButtonHandedOver() {
        int[] found = new int[1];
        HookStatus.setLineWriter((family, count, missing, truncated, firstMiss) -> {
            if (CommentBoxButtons.FAMILY.equals(family)) found[0] = count;
            return family;
        });
        try {
            CommentBoxButtons.photo(button());
            CommentBoxButtons.mention(button());
            CommentBoxButtons.gift(button());
            HookStatus.report();
            assertEquals(3, found[0]);
        } finally {
            HookStatus.setLineWriter(null);
        }
    }
}
