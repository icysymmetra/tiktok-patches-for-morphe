package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import org.junit.Test;

public class LiveFilterTest {
    @Test public void directGettersAndLiveTypeAreEvidence() {
        GetterAweme item = new GetterAweme();
        item.liveId = 42;
        item.liveType = "room";

        LiveFilter filter = new LiveFilter();
        assertTrue(filter.getFiltered(item));
        assertTrue(LiveFilter.getLiveEvidence(item).contains("liveId=42"));
        assertTrue(LiveFilter.getLiveEvidence(item).contains("liveType=room"));
    }

    @Test public void roomFieldsRemainASecondBoundaryWhenDirectGettersAreEmpty() {
        FieldAweme item = new FieldAweme();
        item.newLiveRoomData = new Room(99, new Object());

        LiveFilter filter = new LiveFilter();
        assertTrue(filter.getFiltered(item));
        assertTrue(LiveFilter.getLiveEvidence(item).contains("newLiveRoomData="));
        assertFalse(LiveFilter.getLiveEvidence(item).contains("liveId="));
    }

    @Test public void numericAndNestedRoomShapesRemainIndependentEvidenceSources() {
        FieldAweme item = new FieldAweme();
        LiveFilter filter = new LiveFilter();

        item.awemeType = 101;
        assertTrue(filter.getFiltered(item));
        assertTrue(LiveFilter.getLiveEvidence(item).contains("awemeType=101"));

        item.awemeType = 0;
        item.room = new RoomWithId(7);
        assertTrue(filter.getFiltered(item));
        assertTrue(LiveFilter.getLiveEvidence(item).contains("room="));

        item.room = new Room(7, null);
        item.roomFeedCell = new RoomCell(new Room(8, new Object()));
        assertTrue(filter.getFiltered(item));
        assertTrue(LiveFilter.getLiveEvidence(item).contains("roomFeedCellStruct="));

        item.room = new Room(9, null);
        item.roomFeedCell = null;
        item.newLiveRoomData = new Room(10, null);
        assertFalse(filter.getFiltered(item));
        assertEquals("none", LiveFilter.getLiveEvidence(item));
    }

    /** A LIVE selling while it streams is TikTok Shop content (#46); one with nothing on sale is not. */
    @Test public void aShoppingLiveIsHiddenByTheShopSwitchAlone() {
        ShopFilter shop = new ShopFilter();

        FieldAweme flagged = new FieldAweme();
        flagged.newLiveRoomData = new GoodsRoom(11, new Object(), true, 0);
        assertTrue(shop.getFiltered(flagged));
        assertTrue(LiveFilter.getLiveEvidence(flagged).contains("selling=true"));

        FieldAweme counted = new FieldAweme();
        counted.roomFeedCell = new RoomCell(new GoodsRoom(12, new Object(), false, 3));
        assertTrue(shop.getFiltered(counted));

        FieldAweme quiet = new FieldAweme();
        quiet.newLiveRoomData = new GoodsRoom(13, new Object(), false, 0);
        assertFalse("a LIVE with nothing on sale was taken for Shop", shop.getFiltered(quiet));
        assertFalse(LiveFilter.getLiveEvidence(quiet).contains("selling"));

        // Goods flags on something that is not a LIVE are not this switch's to read.
        FieldAweme notLive = new FieldAweme();
        notLive.newLiveRoomData = new GoodsRoom(0, null, true, 5);
        assertFalse(shop.getFiltered(notLive));
    }

    public static class GetterAweme extends Aweme {
        long liveId;
        boolean replay;
        String liveType;

        @Override public long getLiveId() { return liveId; }
        @Override public boolean isLiveReplay() { return replay; }
        @Override public String getLiveType() { return liveType; }
    }

    public static class FieldAweme extends Aweme {
        public Room newLiveRoomData;
        public int awemeType;
        public Object room;
        public RoomCell roomFeedCell;

        @Override public long getLiveId() { return 0; }
        @Override public boolean isLiveReplay() { return false; }
        @Override public String getLiveType() { return null; }
        @Override public String getShareUrl() { return null; }
        public RoomCell getRoomFeedCellStruct() { return roomFeedCell; }
    }

    public static class Room {
        public final long id;
        public final Object owner;

        Room(long id, Object owner) {
            this.id = id;
            this.owner = owner;
        }
    }

    /** A room as TikTok sends a selling one: its goods flag and the product count under it. */
    public static class GoodsRoom extends Room {
        public final boolean hasCommerceGoods;
        public final Commerce fypCommerceStruct;

        GoodsRoom(long id, Object owner, boolean goods, int products) {
            super(id, owner);
            hasCommerceGoods = goods;
            fypCommerceStruct = new Commerce(products);
        }
    }

    public static class Commerce {
        public final int productNum;

        Commerce(int productNum) {
            this.productNum = productNum;
        }
    }

    public static class RoomWithId {
        public final long roomId;

        RoomWithId(long roomId) {
            this.roomId = roomId;
        }
    }

    public static class RoomCell {
        private final Room room;

        RoomCell(Room room) {
            this.room = room;
        }

        public Room getNewLiveRoomData() { return room; }
    }
}
