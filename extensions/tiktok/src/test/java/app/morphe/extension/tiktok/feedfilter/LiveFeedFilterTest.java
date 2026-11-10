/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The LIVE feed filter (issue #57) against stand-ins that carry the field names of TikTok's
 * FeedItem, Room and User, read by name the way the filter reads the real ones. The values are
 * the shapes seen on a phone: no verified flag but a custom_verify text, a shop flag with a
 * product count of 0, a sponsor label in bc_toggle_info.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class LiveFeedFilterTest {
    private static final String ANY = "0-" + Long.MAX_VALUE;

    static final class Item {
        final int type;
        final Room room;
        Item(int type, Room room) {
            this.type = type;
            this.room = room;
        }
        public Room getRoom() { return room; }
    }

    static final class Room {
        long id = 1;
        String title;
        Long userCount = 100L;
        final User owner = new User();
        Hashtag hashtag;
        Taxonomy taxonomyTagInfo;
        GameCategory gameCategoryInfo;
        List<GameTag> gameTags;
        Boolean hasCommerceGoods;
        Bc bcToggleInfo;
        Partnership partnershipInfo;
    }

    static final class User {
        long id = 42;
        String username = "host";
        String nickName = "Host";
        boolean isVerified;
        Auth authenticationInfo;
        Follow followInfo = new Follow();
    }

    static final class Follow { Long followerCount = 1000L; }
    static final class Auth { String customVerify; }
    static final class Hashtag { Long id; String title; }
    static final class Taxonomy { List<String> level1Tag; }
    static final class GameCategory { String title; }
    static final class GameTag { String showName; }
    static final class Bc { String toggleText; }
    static final class Partnership { boolean promotingRoom; }

    private static Room room(String handle) {
        Room room = new Room();
        room.owner.username = handle;
        room.owner.nickName = handle;
        return room;
    }

    private static Room gaming(String handle) {
        Room room = room(handle);
        room.gameCategoryInfo = new GameCategory();
        room.gameCategoryInfo.title = "Gaming";
        GameTag tag = new GameTag();
        tag.showName = "Fortnite";
        room.gameTags = new ArrayList<>(Arrays.asList(tag));
        return room;
    }

    private static List<Object> page(Room... rooms) {
        List<Object> items = new ArrayList<>();
        for (Room room : rooms) items.add(new Item(1, room));
        return items;
    }

    private static List<String> handles(List<?> items) {
        List<String> handles = new ArrayList<>();
        for (Object item : items) handles.add(((Item) item).room.owner.username);
        return handles;
    }

    @Before
    public void reset() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        LiveFeedFilter.resetForTests();
        Settings.LIVE_FEED_FILTER.save(true);
        Settings.LIVE_HIDE_GAMING.save(false);
        Settings.LIVE_HIDE_SHOPPING.save(false);
        Settings.LIVE_HIDE_SPONSORED.save(false);
        Settings.LIVE_HIDE_VERIFIED.save(false);
        Settings.LIVE_HIDDEN_CATEGORIES.save("");
        Settings.LIVE_MIN_MAX_VIEWERS.save(ANY);
        Settings.LIVE_MIN_MAX_FOLLOWERS.save(ANY);
        Settings.BLOCKED_CREATORS.save("");
        Settings.LOCAL_HIDDEN_CREATORS.save("");
        Settings.BLOCKED_CAPTION_WORDS.save("");
        Settings.CREATOR_FILTER_EXCEPTIONS.save("");
    }

    @Test
    public void aPageComesBackAsItWasWhenNothingIsHidden() {
        List<Object> items = page(gaming("a"), room("b"));
        assertSame(items, LiveFeedFilter.filter(items));

        Settings.LIVE_HIDE_GAMING.save(true);
        Settings.LIVE_FEED_FILTER.save(false);
        assertSame("the switch off leaves the page alone", items, LiveFeedFilter.filter(items));
    }

    @Test
    public void eachKindRuleHidesItsOwnRooms() {
        Room game = gaming("game");
        Room shop = room("shop");
        shop.hasCommerceGoods = true;
        Room sponsor = room("sponsor");
        sponsor.bcToggleInfo = new Bc();
        sponsor.bcToggleInfo.toggleText = "Paid partnership";
        Room checked = room("checked");
        checked.owner.authenticationInfo = new Auth();
        checked.owner.authenticationInfo.customVerify = "verified account";
        Room plain = room("plain");
        List<Object> items = page(game, shop, sponsor, checked, plain);

        Settings.LIVE_HIDE_GAMING.save(true);
        assertEquals(Arrays.asList("shop", "sponsor", "checked", "plain"), handles(LiveFeedFilter.filter(items)));
        Settings.LIVE_HIDE_GAMING.save(false);
        Settings.LIVE_HIDE_SHOPPING.save(true);
        assertEquals(Arrays.asList("game", "sponsor", "checked", "plain"), handles(LiveFeedFilter.filter(items)));
        Settings.LIVE_HIDE_SHOPPING.save(false);
        Settings.LIVE_HIDE_SPONSORED.save(true);
        assertEquals(Arrays.asList("game", "shop", "checked", "plain"), handles(LiveFeedFilter.filter(items)));
        Settings.LIVE_HIDE_SPONSORED.save(false);
        Settings.LIVE_HIDE_VERIFIED.save(true);
        assertEquals(Arrays.asList("game", "shop", "sponsor", "plain"), handles(LiveFeedFilter.filter(items)));
    }

    @Test
    public void theVerifiedFlagAndTheBadgeTextBothCount() {
        Room flagged = room("flagged");
        flagged.owner.isVerified = true;
        assertEquals("verified", LiveFeedFilter.LiveRoom.of(flagged).verifiedBy);
        Room badged = room("badged");
        badged.owner.authenticationInfo = new Auth();
        badged.owner.authenticationInfo.customVerify = "verified account";
        assertEquals("custom_verify", LiveFeedFilter.LiveRoom.of(badged).verifiedBy);
        assertNull(LiveFeedFilter.LiveRoom.of(room("plain")).verifiedBy);
    }

    @Test
    public void aGamePartnershipCountsAsSponsored() {
        Room partner = room("partner");
        partner.partnershipInfo = new Partnership();
        partner.partnershipInfo.promotingRoom = true;
        Settings.LIVE_HIDE_SPONSORED.save(true);
        assertEquals(Arrays.asList("plain"), handles(LiveFeedFilter.filter(page(partner, room("plain")))));
    }

    @Test
    public void theGamingCategoryCountsWithoutAGameTag() {
        // A stream filed under Gaming with no game picked comes with the category alone.
        Room byId = room("byId");
        byId.hashtag = new Hashtag();
        byId.hashtag.id = LiveFeedFilter.GAMING_HASHTAG_ID;
        byId.hashtag.title = "Juegos";
        Room byTitle = room("byTitle");
        byTitle.hashtag = new Hashtag();
        byTitle.hashtag.title = "Gaming";
        Room music = room("music");
        music.hashtag = new Hashtag();
        music.hashtag.id = 7L;
        music.hashtag.title = "Music";
        Settings.LIVE_HIDE_GAMING.save(true);
        assertEquals(Arrays.asList("music"), handles(LiveFeedFilter.filter(page(byId, byTitle, music))));
    }

    @Test
    public void categoriesMatchTheTopicTheTagsAndTheGame() {
        Room music = room("music");
        music.hashtag = new Hashtag();
        music.hashtag.title = "Music";
        Room chat = room("chat");
        chat.taxonomyTagInfo = new Taxonomy();
        chat.taxonomyTagInfo.level1Tag = Arrays.asList("chatting", "creative");
        Room game = gaming("game");
        Settings.LIVE_HIDDEN_CATEGORIES.save("music, chat, fortnite");
        assertEquals(Arrays.asList("plain"),
                handles(LiveFeedFilter.filter(page(music, chat, game, room("plain")))));
    }

    @Test
    public void rangesAreInclusiveAndAnUnknownCountKeepsTheRoom() {
        Room few = room("few");
        few.userCount = 9L;
        Room low = room("low");
        low.userCount = 10L;
        Room high = room("high");
        high.userCount = 500L;
        Room over = room("over");
        over.userCount = 501L;
        Room unknown = room("unknown");
        unknown.userCount = null;
        Settings.LIVE_MIN_MAX_VIEWERS.save("10-500");
        assertEquals(Arrays.asList("low", "high", "unknown"),
                handles(LiveFeedFilter.filter(page(few, low, high, over, unknown))));

        Settings.LIVE_MIN_MAX_VIEWERS.save(ANY);
        Room small = room("small");
        small.owner.followInfo.followerCount = 50L;
        Room big = room("big");
        big.owner.followInfo.followerCount = 5_000_000L;
        Room noFollow = room("noFollow");
        noFollow.owner.followInfo = null;
        Settings.LIVE_MIN_MAX_FOLLOWERS.save("100-100000");
        assertEquals(Arrays.asList("plain", "noFollow"),
                handles(LiveFeedFilter.filter(page(small, room("plain"), big, noFollow))));
    }

    @Test
    public void blockedCreatorsAndTitleWordsComeAlongFromTheVideoFilter() {
        Room blocked = room("blocked");
        Room worded = room("worded");
        worded.title = "GIVEAWAY tonight";
        Settings.BLOCKED_CREATORS.save("@blocked");
        Settings.BLOCKED_CAPTION_WORDS.save("giveaway");
        assertEquals(Arrays.asList("plain"),
                handles(LiveFeedFilter.filter(page(blocked, worded, room("plain")))));
    }

    @Test
    public void anExceptionGetsPastTheKindRulesButNeverPastABlockOrShopping() {
        Settings.CREATOR_FILTER_EXCEPTIONS.save("@friend");
        Settings.LIVE_HIDE_GAMING.save(true);
        Settings.LIVE_MIN_MAX_VIEWERS.save("1000-" + Long.MAX_VALUE);
        assertEquals(Arrays.asList("friend"),
                handles(LiveFeedFilter.filter(page(gaming("friend"), gaming("stranger")))));

        Room shop = room("friend");
        shop.hasCommerceGoods = true;
        Settings.LIVE_HIDE_SHOPPING.save(true);
        assertEquals("shopping is a hard rule", 0, LiveFeedFilter.filter(page(shop)).size());

        Settings.LIVE_HIDE_SHOPPING.save(false);
        Settings.BLOCKED_CREATORS.save("@friend");
        assertEquals("so is a block", 0, LiveFeedFilter.filter(page(gaming("friend"))).size());
    }

    @Test
    public void itemsThatAreNotRoomsPassUntouched() {
        Settings.LIVE_HIDE_GAMING.save(true);
        Item banner = new Item(3, gaming("banner"));
        List<Object> items = new ArrayList<>(Arrays.asList(banner, new Item(1, gaming("game"))));
        List<?> kept = LiveFeedFilter.filter(items);
        assertEquals(1, kept.size());
        assertSame(banner, kept.get(0));
    }

    @Test
    public void aRunOfWholeHiddenPagesLetsOneRoomThroughSoTheFeedKeepsLoading() {
        Settings.LIVE_HIDE_GAMING.save(true);
        Settings.BLOCKED_CREATORS.save("@blocked");
        for (int run = 1; run <= LiveFeedFilter.HIDDEN_PAGES_BEFORE_ONE_SHOWS; run++) {
            assertEquals("page " + run + " stays hidden", 0,
                    LiveFeedFilter.filter(page(gaming("a" + run), gaming("b" + run))).size());
        }
        // The next whole page gives up one room, and never a blocked creator's.
        assertEquals(Arrays.asList("next"),
                handles(LiveFeedFilter.filter(page(room("blocked"), gaming("next"), gaming("after")))));
        // The run starts over after that.
        assertEquals(0, LiveFeedFilter.filter(page(gaming("again"))).size());

        // A page that keeps a room of its own resets the run too.
        LiveFeedFilter.resetForTests();
        for (int run = 1; run <= LiveFeedFilter.HIDDEN_PAGES_BEFORE_ONE_SHOWS; run++) {
            LiveFeedFilter.filter(page(gaming("c" + run)));
        }
        assertEquals(Arrays.asList("plain"), handles(LiveFeedFilter.filter(page(gaming("d"), room("plain")))));
        assertEquals(0, LiveFeedFilter.filter(page(gaming("e"))).size());
    }

    @Test
    public void aPageOfOnlyBlockedCreatorsNeverStandsIn() {
        Settings.BLOCKED_CREATORS.save("@blocked");
        for (int run = 0; run <= LiveFeedFilter.HIDDEN_PAGES_BEFORE_ONE_SHOWS + 2; run++) {
            assertEquals(0, LiveFeedFilter.filter(page(room("blocked"))).size());
        }
    }

    @Test
    public void theReportCountsPagesRoomsAndRules() {
        Settings.LIVE_HIDE_GAMING.save(true);
        Settings.LIVE_HIDE_SHOPPING.save(true);
        Room shop = room("shop");
        shop.hasCommerceGoods = true;
        LiveFeedFilter.filter(page(gaming("a"), shop, room("plain")));
        String report = String.join("\n", LiveFeedFilter.Report.INSTANCE.lines());
        assertTrue(report, report.contains("Filter the LIVE feed: on"));
        assertTrue(report, report.contains("LIVE feed pages since TikTok started: 1"));
        assertTrue(report, report.contains("Rooms checked: 3, hidden: 2"));
        assertTrue(report, report.contains("Hidden by rule: gaming 1, shopping 1"));
    }
}
