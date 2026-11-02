/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import java.lang.reflect.Method;

import app.morphe.extension.tiktok.blockauthor.Reflect;

import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.AwemeBizExtKt;

/**
 * Show only mutual friends on the Friends tab.
 *
 * <p>The Friends tab mixes in posts from accounts you follow that don't follow you back,
 * "People you may know" suggestions and reposts by strangers. Every entry names whose it is,
 * and that account's {@code User} carries TikTok's follow status toward it: 0 none, 1 you
 * follow them, 2 you follow each other. Only 2 is a friend, which is the test kveld9's strict
 * mode makes. A repost belongs to whoever reposted it, so a friend's repost of anyone's video
 * stays and a stranger's repost of a friend's video goes. A LIVE card belongs to the room's
 * owner. The signed-in account's own posts and reposts always stay.
 *
 * <p>Since 47.4.3 a repost is an ordinary card whose video names the reposter itself
 * ({@code AwemeBizExtKt.getRepostUser}, filled in as TikTok reads the JSON). The Reposts list
 * holds nothing but reposts, so there an entry that doesn't name its reposter is kept rather
 * than judged by the video's author.
 *
 * <p>An entry whose account or status can't be read is kept: a renamed member must leave the
 * tab as TikTok built it, not empty it.
 */
final class FriendsMutuals {
    /** The reason the Friends tab's report and debug log give for an entry this drops. */
    static final String REASON = "FriendsMutualsFilter";
    /** TikTok's FollowStatus for two accounts that follow each other. */
    static final int MUTUAL = 2;
    /** The Hook status family for the reposter lookup. */
    static final String REPOSTER_FAMILY = "friends tab reposts";

    private static volatile Method repostUser;
    private static volatile boolean repostUserLooked;

    private FriendsMutuals() {
    }

    /** Whether a Friends tab or Reposts entry comes from a friend. */
    static boolean fromMutual(Object entry, String ownId, boolean repostList) {
        Object user = ownerOf(entry, repostList);
        if (user == null) return true;
        String uid = Reflect.string(user, "getUid", "uid");
        if (ownId != null && ownId.equals(uid)) return true;
        Object status = Reflect.property(user, "getFollowStatus", "followStatus");
        if (!(status instanceof Number)) return true;
        return ((Number) status).intValue() == MUTUAL;
    }

    /** The account an entry is shown for: the reposter, the author, or the LIVE's host. */
    static Object ownerOf(Object entry, boolean repostList) {
        if (entry == null) return null;
        Aweme aweme = FeedItemsFilter.friendsEntryAweme(entry);
        if (aweme != null) {
            Object reposter = reposterOf(aweme);
            if (reposter != null) return reposter;
            return repostList ? null : Reflect.property(aweme, "getAuthor", "author");
        }
        Object room = Reflect.readField(entry, "roomStruct");
        if (room == null) return null;
        Object live = Reflect.property(room, "getNewLiveRoomData", "newLiveRoomData");
        return Reflect.property(live, "getOwner", "owner");
    }

    /** The account that reposted {@code aweme}, or null for an original post. */
    static Object reposterOf(Aweme aweme) {
        if (!repostUserLooked) {
            repostUser = Reflect.requiredMethod(
                    AwemeBizExtKt.class, "getRepostUser", REPOSTER_FAMILY, Aweme.class);
            repostUserLooked = true;
        }
        return Reflect.invokeRequired(repostUser, null, REPOSTER_FAMILY, aweme);
    }
}
