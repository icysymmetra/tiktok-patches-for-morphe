package app.morphe.extension.tiktok.live;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * #37: the Record button follows the room page that is on screen. On 47.1.4 the pager resumes the
 * next room right after the one being watched, so following the last page to resume put the button
 * on a room nobody could see, and it went away a second after it showed.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "en")
public class LiveRecordChipTest {
    @Test public void aPageFullyOnScreenIsInFront() {
        assertTrue(LiveRecordChip.mostlyShown(2340, 2340));
    }

    @Test public void halfwayThroughASwipeThePageStillCounts() {
        assertTrue(LiveRecordChip.mostlyShown(1170, 2340));
        assertFalse(LiveRecordChip.mostlyShown(1169, 2340));
    }

    @Test public void aRoomBesideTheOneInFrontDoesNot() {
        assertFalse(LiveRecordChip.mostlyShown(0, 2340));
        assertFalse(LiveRecordChip.mostlyShown(0, 0));
    }

    // The chip is 200 wide at the right edge (880 to 1080), 120 tall, wanted at 330.

    @Test public void theChipStaysPutWhenNothingIsUnderIt() {
        List<Rect> taken = List.of(new Rect(0, 330, 600, 500), new Rect(880, 0, 1080, 200));
        assertEquals(330, LiveRecordChip.freeTop(330, 120, 880, 1080, taken, 20, 1500));
    }

    @Test public void theChipMovesBelowTheDiscoverPanelsAvatarsAndBackWhenTheyGo() {
        // The panel's row of avatars, one of them under the chip's spot, then a second row.
        List<Rect> panel = List.of(new Rect(700, 300, 900, 500), new Rect(900, 300, 1080, 500),
                new Rect(860, 600, 1080, 800));
        assertEquals("below both rows", 820, LiveRecordChip.freeTop(330, 120, 880, 1080, panel, 20, 1500));
        assertEquals("back once they're gone", 330,
                LiveRecordChip.freeTop(330, 120, 880, 1080, List.of(), 20, 1500));
    }

    @Test public void aColumnOfControlsDownTheSideLeavesTheChipWhereItWas() {
        List<Rect> side = List.of(new Rect(900, 250, 1080, 1700));
        assertEquals(330, LiveRecordChip.freeTop(330, 120, 880, 1080, side, 20, 1500));
    }

    @Test public void onlyShownTapTargetsSmallerThanTheLimitCount() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout content = new FrameLayout(activity);
        View avatar = new View(activity);
        avatar.setOnClickListener(v -> { });
        View hidden = new View(activity);
        hidden.setOnClickListener(v -> { });
        hidden.setVisibility(View.GONE);
        View surface = new View(activity);
        surface.setOnClickListener(v -> { });
        View chip = new View(activity);
        chip.setOnClickListener(v -> { });
        content.addView(avatar, new FrameLayout.LayoutParams(100, 100));
        content.addView(hidden, new FrameLayout.LayoutParams(100, 100));
        content.addView(surface, new FrameLayout.LayoutParams(1000, 1000));
        content.addView(chip, new FrameLayout.LayoutParams(100, 100));
        activity.setContentView(content, new ViewGroup.LayoutParams(1000, 1000));
        content.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
        content.layout(0, 0, 1000, 1000);
        Rect area = new Rect();
        assertTrue(content.getGlobalVisibleRect(area));

        List<Rect> found = new ArrayList<>();
        LiveRecordChip.tapTargets(content, chip, area, (long) area.width() * area.height() / 4, found);

        assertEquals(List.of(new Rect(0, 0, 100, 100)), found);
    }
}
