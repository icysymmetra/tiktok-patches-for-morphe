package app.morphe.extension.tiktok.inbox;

import static org.junit.Assert.*;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class InboxModelFilterTest {
    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.HIDE_INBOX_NEW_FOLLOWERS.save(false);
        Settings.HIDE_INBOX_ACTIVITY.save(false);
        Settings.HIDE_INBOX_TAKO.save(false);
        Settings.HIDE_INBOX_SHOP.save(false);
    }
    @After public void restore() {
        Settings.HIDE_INBOX_BULLETIN_BOARDS.resetToDefault();
    }
    @Test public void defaultsKeepRecognizedCategoriesVisible() {
        List<?> rows = List.of(new Pod(Kind.FOLLOWER), new Entrance(9));
        for (Object row : rows) assertFalse(InboxModelFilter.settingFor(row).get());
        assertNull(InboxModelFilter.settingFor(null));
    }
    @Test public void categoryIdentityTracksSwitchesWithoutTouchingConversationsOrInputData() {
        Object conversation = new Object();
        List<?> rows = List.of(new Pod(Kind.FOLLOWER), new Entrance(2), new Entrance(1),
                new Entrance(9), conversation);
        Settings.HIDE_INBOX_NEW_FOLLOWERS.save(true);
        Settings.HIDE_INBOX_ACTIVITY.save(true);
        Settings.HIDE_INBOX_TAKO.save(true);
        assertEquals(List.of(true, true, true, true, false), hidden(rows));
        assertEquals(5, rows.size());
        Settings.HIDE_INBOX_NEW_FOLLOWERS.save(false);
        assertEquals(List.of(false, false, true, true, false), hidden(rows));
        assertNull(InboxModelFilter.settingFor(conversation));
    }
    /**
     * InboxEntrancePod hands the archive screen its target only for the cell whose server id is
     * 15, on 47.0.3, 47.1.3 and 47.1.4 alike. That id, read from the cell's own field, is what
     * makes a row a Bulletin board, and no other cell or shape is taken for one.
     */
    @Test public void aBulletinBoardCellFollowsItsOwnSwitchAndNothingElseIsOne() {
        Object bulletin = new Entrance(15);
        assertFalse(Settings.HIDE_INBOX_BULLETIN_BOARDS.defaultValue);
        assertSame(Settings.HIDE_INBOX_BULLETIN_BOARDS, InboxModelFilter.settingFor(bulletin));
        assertFalse(InboxModelFilter.settingFor(bulletin).get());
        Settings.HIDE_INBOX_BULLETIN_BOARDS.save(true);
        assertTrue(InboxModelFilter.settingFor(bulletin).get());

        // The named cells keep their own switches, and an unknown id belongs to none.
        assertSame(Settings.HIDE_INBOX_TAKO, InboxModelFilter.settingFor(new Entrance(9)));
        assertSame(Settings.HIDE_INBOX_ACTIVITY, InboxModelFilter.settingFor(new Entrance(1)));
        assertSame(Settings.HIDE_INBOX_NEW_FOLLOWERS, InboxModelFilter.settingFor(new Entrance(2)));
        assertNull(InboxModelFilter.settingFor(new Entrance(14)));
        assertNull(InboxModelFilter.settingFor(new Entrance(16)));

        // A row with no cell, a cell with no id field, an id that isn't a number and no cell.
        assertNull(InboxModelFilter.settingFor(new Object()));
        assertSame(Settings.HIDE_INBOX_SHOP, InboxModelFilter.settingFor(new Pod(Kind.SHOP)));
        assertFalse(InboxModelFilter.isBulletinBoard(new Object()));
        assertFalse(InboxModelFilter.isBulletinBoard(new TextCell()));
        assertFalse(InboxModelFilter.isBulletinBoard(null));
        assertTrue(InboxModelFilter.isBulletinBoard(new Cell(InboxModelFilter.BULLETIN_BOARD_CELL_ID)));
    }
    private List<Boolean> hidden(List<?> rows) {
        return rows.stream().map(row -> {
            var setting = InboxModelFilter.settingFor(row);
            return setting != null && setting.get();
        }).collect(java.util.stream.Collectors.toList());
    }
    private static final class TextCell {
        public final String cellId = "15";
    }
    private enum Kind { FOLLOWER, ACTIVITY, SHOP }
    private static final class Pod {
        public final Kind dataType;
        Pod(Kind kind) { dataType = kind; }
    }
    private static final class Entrance {
        public final Cell entranceCell;
        Entrance(int id) { entranceCell = new Cell(id); }
    }
    /** InboxEntranceCell keeps its server id in an int field named cellId. */
    private static final class Cell {
        private final int id;
        public final int cellId;
        Cell(int id) { this.id = id; this.cellId = id; }
        public boolean isFollower() { return id == 2; }
        public boolean isActivity() { return id == 1; }
        public boolean isTako() { return id == 9; }
    }
}
