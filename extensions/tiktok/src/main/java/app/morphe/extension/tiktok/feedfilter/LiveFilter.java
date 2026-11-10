/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/feedfilter/LiveFilter.java
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import com.ss.android.ugc.aweme.feed.model.Aweme;

public class LiveFilter implements IFilter {
    private static final int AWEME_TYPE_LIVE = 101;

    @Override
    public boolean getEnabled() {
        return Settings.HIDE_LIVE.get();
    }

    @Override
    public boolean getFiltered(Aweme item) {
        return isLive(item);
    }

    static boolean isLive(Aweme item) {
        return item.getLiveId() > 0
            || item.isLiveReplay()
            || hasText(item.getLiveType())
            || getIntField(item, "awemeType") == AWEME_TYPE_LIVE
            || hasValidLiveRoom(item);
    }

    static String getLiveEvidence(Aweme item) {
        StringBuilder evidence = new StringBuilder();
        int awemeType = getIntField(item, "awemeType");
        appendEvidence(evidence, "awemeType", awemeType == AWEME_TYPE_LIVE ? awemeType : null);
        appendEvidence(evidence, "liveId", item.getLiveId() > 0 ? item.getLiveId() : null);
        appendEvidence(evidence, "liveReplay", item.isLiveReplay() ? true : null);
        appendEvidence(evidence, "liveType", hasText(item.getLiveType()) ? item.getLiveType() : null);
        appendEvidence(evidence, "newLiveRoomData", getValidRoomEvidence(getFieldValue(item, "newLiveRoomData"), "id"));
        appendEvidence(evidence, "cachedLiveRoomStruct", getValidRoomEvidence(getFieldValue(item, "cachedLiveRoomStruct"), "id"));
        appendEvidence(evidence, "roomFeedCellStruct", getRoomFeedCellEvidence(item));
        appendEvidence(evidence, "room", getValidRoomEvidence(getFieldValue(item, "room"), "roomId"));
        appendEvidence(evidence, "selling", ShopFilter.sellsInLive(item) ? true : null);
        return evidence.length() == 0 ? "none" : evidence.toString();
    }

    private static boolean hasValidLiveRoom(Aweme item) {
        return getValidRoomEvidence(getFieldValue(item, "newLiveRoomData"), "id") != null
            || getValidRoomEvidence(getFieldValue(item, "cachedLiveRoomStruct"), "id") != null
            || getRoomFeedCellEvidence(item) != null
            || getValidRoomEvidence(getFieldValue(item, "room"), "roomId") != null;
    }

    /** Every room object a LIVE item can carry, the same ones read for its evidence. */
    static java.util.List<Object> rooms(Aweme item) {
        java.util.List<Object> rooms = new java.util.ArrayList<>(6);
        addRoom(rooms, getFieldValue(item, "newLiveRoomData"));
        addRoom(rooms, getFieldValue(item, "cachedLiveRoomStruct"));
        addRoom(rooms, getFieldValue(item, "room"));
        Object cell = invokeNoArg(item, "getRoomFeedCellStruct");
        if (cell == null) cell = getFieldValue(item, "mRoomFeedCellStruct");
        if (cell != null) {
            addRoom(rooms, invokeNoArg(cell, "getNewLiveRoomData"));
            addRoom(rooms, getFieldValue(cell, "room"));
            addRoom(rooms, getFieldValue(cell, "newLiveRoomData"));
        }
        return rooms;
    }

    private static void addRoom(java.util.List<Object> rooms, Object room) {
        if (room != null && !rooms.contains(room)) rooms.add(room);
    }

    private static String getRoomFeedCellEvidence(Aweme item) {
        Object cell = invokeNoArg(item, "getRoomFeedCellStruct");
        if (cell == null) {
            cell = getFieldValue(item, "mRoomFeedCellStruct");
        }
        if (cell == null) return null;

        Object room = invokeNoArg(cell, "getNewLiveRoomData");
        String roomEvidence = getValidRoomEvidence(room, "id");
        if (roomEvidence != null) return roomEvidence;

        roomEvidence = getValidRoomEvidence(getFieldValue(cell, "room"), "id");
        if (roomEvidence != null) return roomEvidence;

        return getValidRoomEvidence(getFieldValue(cell, "newLiveRoomData"), "id");
    }

    private static String getValidRoomEvidence(Object room, String idFieldName) {
        if (room == null) return null;

        long id = getLongField(room, idFieldName);
        if (id <= 0) return null;

        Object owner = getFieldValue(room, "owner");
        if (owner == null && !"roomId".equals(idFieldName)) return null;

        return room.getClass().getName() + "#" + id;
    }

    private static long getLongField(Object instance, String name) {
        Object value = getFieldValue(instance, name);
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        return 0;
    }

    private static int getIntField(Object instance, String name) {
        Object value = getFieldValue(instance, name);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private static Object getFieldValue(Object instance, String name) {
        return Reflect.readField(instance, name);
    }

    private static Object invokeNoArg(Object instance, String name) {
        return Reflect.invoke(instance, name);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isEmpty();
    }

    private static void appendEvidence(StringBuilder builder, String name, Object value) {
        if (value == null) return;
        if (builder.length() > 0) builder.append(',');
        builder.append(name).append('=').append(value);
    }
}
