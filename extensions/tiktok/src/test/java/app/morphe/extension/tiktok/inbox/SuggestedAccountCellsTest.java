package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** The one bind every suggestion cell passes through, fed a cell shaped like a ViewHolder. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SuggestedAccountCellsTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    /** What a RecyclerView ViewHolder gives the bind: a real-named, public, final itemView. */
    public static final class Cell {
        public final View itemView;

        Cell(View itemView) {
            this.itemView = itemView;
        }
    }

    @After public void restoreSwitch() {
        Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(false);
    }

    /**
     * A cell bound with the switch on is collapsed to nothing, and one bound with it off is
     * put back at its own size. A GONE child of a horizontal list keeps its width in some
     * layouts, which is why the size goes too, and why it has to come back.
     */
    @Test public void aCellIsCollapsedWhileTheSwitchIsOnAndComesBackAtItsOwnSize() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            LinearLayout list = new LinearLayout(activity);
            View item = new View(activity);
            list.addView(item, new LinearLayout.LayoutParams(300, 420));
            Cell cell = new Cell(item);

            Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(true);
            SuggestedAccountCells.onBind(cell);
            assertEquals("the cell was left visible", View.GONE, item.getVisibility());
            assertEquals("the cell kept its width", 0, item.getLayoutParams().width);
            assertEquals("the cell kept its height", 0, item.getLayoutParams().height);

            // Bound again while still on: still collapsed, and the remembered size is the real
            // one rather than the zeros it now carries.
            SuggestedAccountCells.onBind(cell);
            assertEquals(View.GONE, item.getVisibility());

            Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(false);
            SuggestedAccountCells.onBind(cell);
            assertEquals("the cell did not come back", View.VISIBLE, item.getVisibility());
            assertEquals("the cell came back at the wrong width", 300, item.getLayoutParams().width);
            assertEquals("the cell came back at the wrong height", 420, item.getLayoutParams().height);
        }
    }

    /**
     * A cell TikTok had out of sight, with margins, comes back exactly that way. Forcing it
     * VISIBLE would show what TikTok had hidden, and margins left standing hold space in
     * every layout.
     */
    @Test public void aCellComesBackWithTheVisibilityAndMarginsItHad() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            LinearLayout list = new LinearLayout(activity);
            View item = new View(activity);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(300, 420);
            params.setMargins(12, 8, 12, 8);
            list.addView(item, params);
            item.setVisibility(View.INVISIBLE);

            Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(true);
            SuggestedAccountCells.onBind(new Cell(item));
            LinearLayout.LayoutParams collapsed = (LinearLayout.LayoutParams) item.getLayoutParams();
            assertEquals(View.GONE, item.getVisibility());
            assertEquals("the collapsed cell kept a margin", 0, collapsed.topMargin);
            assertEquals(0, collapsed.leftMargin + collapsed.rightMargin + collapsed.bottomMargin);

            Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(false);
            SuggestedAccountCells.onBind(new Cell(item));
            LinearLayout.LayoutParams restored = (LinearLayout.LayoutParams) item.getLayoutParams();
            assertEquals("a cell TikTok had hidden was made visible", View.INVISIBLE, item.getVisibility());
            assertEquals(12, restored.leftMargin);
            assertEquals(8, restored.topMargin);
            assertEquals(12, restored.rightMargin);
            assertEquals(8, restored.bottomMargin);
            assertEquals(300, restored.width);
            assertEquals(420, restored.height);
        }
    }

    /** Negative margins are real layout values, including -1, and survive repeated binds. */
    @Test public void aCellComesBackWithNegativeMarginsAfterRepeatedBinds() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            LinearLayout list = new LinearLayout(activity);
            for (int left : new int[]{-1, -12}) {
                View item = new View(activity);
                LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(300, 420);
                params.setMargins(left, -8, 12, -4);
                list.addView(item, params);
                Cell cell = new Cell(item);

                Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(true);
                SuggestedAccountCells.onBind(cell);
                SuggestedAccountCells.onBind(cell);
                assertEquals(0, params.leftMargin);
                assertEquals(0, params.topMargin);

                Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(false);
                SuggestedAccountCells.onBind(cell);
                assertEquals(left, params.leftMargin);
                assertEquals(-8, params.topMargin);
                assertEquals(12, params.rightMargin);
                assertEquals(-4, params.bottomMargin);
                assertEquals(300, params.width);
                assertEquals(420, params.height);
                assertEquals(View.VISIBLE, item.getVisibility());
            }
        }
    }

    /** A cell that was never collapsed is left exactly as TikTok bound it. */
    @Test public void aCellNeverCollapsedIsNotTouchedWhenTheSwitchIsOff() {
        try (var owner = Robolectric.buildActivity(Activity.class).setup()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            FrameLayout list = new FrameLayout(activity);
            View item = new View(activity);
            list.addView(item, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 200));
            item.setVisibility(View.INVISIBLE);

            Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(false);
            assertFalse(SuggestedAccountCells.apply(item, false));
            assertEquals("a cell nobody collapsed was made visible", View.INVISIBLE, item.getVisibility());
            assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, item.getLayoutParams().width);
            assertEquals(200, item.getLayoutParams().height);
        }
    }

    /** An object that is not a ViewHolder, or a cell with no view yet, is left alone. */
    @Test public void aCellWithoutAViewIsSkipped() {
        Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.save(true);
        SuggestedAccountCells.onBind(new Object());
        SuggestedAccountCells.onBind(new Cell(null));
        assertTrue(true);
    }
}
