/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

/** Reads category identity from the verified model fields, independently of visible titles. */
public final class InboxModelFilter {
    private InboxModelFilter() {}

    static BooleanSetting settingFor(Object row) {
        if ("archive_entrance".equals(Reflect.invoke(row, "itemUniqueId"))) return Settings.HIDE_INBOX_ARCHIVE;
        // ActivityPod.dataType is FOLLOWER, ACTIVITY or SHOP, including cached pods.
        Object dataType = Reflect.readField(row, "dataType");
        if (dataType instanceof Enum<?>) {
            switch (((Enum<?>) dataType).name()) {
                case "FOLLOWER": return Settings.HIDE_INBOX_NEW_FOLLOWERS;
                case "ACTIVITY": return Settings.HIDE_INBOX_ACTIVITY;
                case "SHOP": return Settings.HIDE_INBOX_SHOP;
                default: break;
            }
        }
        // InboxEntrancePod wraps InboxEntranceCell. The cell's named predicates use
        // stable server ids: Activity=1, Follower=2, Tako=9. Conversations aren't cells.
        Object cell = Reflect.readField(row, "entranceCell");
        if (Boolean.TRUE.equals(Reflect.invoke(cell, "isFollower"))) return Settings.HIDE_INBOX_NEW_FOLLOWERS;
        if (Boolean.TRUE.equals(Reflect.invoke(cell, "isActivity"))) return Settings.HIDE_INBOX_ACTIVITY;
        if (Boolean.TRUE.equals(Reflect.invoke(cell, "isTako"))) return Settings.HIDE_INBOX_TAKO;
        if (isBulletinBoard(cell)) return Settings.HIDE_INBOX_BULLETIN_BOARDS;
        return null;
    }

    /**
     * The server id of a Bulletin board cell. InboxEntrancePod hands the archive screen its
     * target only for this id and returns nothing for every other cell, on 47.0.3, 47.1.3 and
     * 47.1.4 alike, and the cell has no named predicate the way the others do.
     */
    static final int BULLETIN_BOARD_CELL_ID = 15;

    static boolean isBulletinBoard(Object cell) {
        Object id = Reflect.readField(cell, "cellId");
        return id instanceof Integer && (Integer) id == BULLETIN_BOARD_CELL_ID;
    }
}
