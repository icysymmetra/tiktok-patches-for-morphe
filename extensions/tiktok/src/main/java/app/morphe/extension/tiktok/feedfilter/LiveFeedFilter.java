/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The LIVE feed you swipe through, filtered a page at a time (issue #57).
 *
 * <p>TikTok's draw list, the vertical pager of LIVE rooms, asks /webcast/feed/ for its next page
 * and hands the items to one handler, which turns them into rooms and adds them to the pager.
 * The patch puts {@link #filter} at the top of that handler, so a room a rule hides never gets
 * there. An item is a FeedItem whose JSON becomes a Room the first time getRoom() is called,
 * which the handler does next anyway. Anything that isn't a room passes untouched.
 *
 * <p>Two of the feed filter's lists come along: Blocked creators with Creators hidden on this
 * phone, and Blocked caption words, matched against the LIVE title. Creator exceptions comes
 * along too, and the line runs where it runs for videos: a listed creator gets past the rules
 * about a LIVE's kind, labels and numbers, never past a block, a word, shopping or a sponsor.
 *
 * <p>Two things sit outside the filter. The room the LIVE button opens first comes with the
 * button, not from a page, so the rules start with the room after it. And a page with every room
 * hidden makes TikTok load the next at once, so after a run of those one room goes through (see
 * {@link #HIDDEN_PAGES_BEFORE_ONE_SHOWS}); a blocked creator or a blocked word never does.
 */
public final class LiveFeedFilter {
    private LiveFeedFilter() {
    }

    // Why a room was hidden, as the report and the debug log name it.
    static final String BLOCKED_CREATOR = "blocked creator";
    static final String TITLE_WORD = "blocked word in the title";
    static final String SHOPPING = "shopping";
    static final String SPONSORED = "sponsored";
    static final String VERIFIED = "verified creator";
    static final String GAMING = "gaming";
    static final String CATEGORY = "hidden category";
    static final String VIEWERS = "viewers";
    static final String FOLLOWERS = "followers";

    /** The id of TikTok's Gaming LIVE category, the room's hashtag. */
    static final long GAMING_HASHTAG_ID = 5;

    private static final AtomicLong PAGES = new AtomicLong();
    private static final AtomicLong ROOMS = new AtomicLong();
    private static final AtomicLong HIDDEN = new AtomicLong();
    /** A ConcurrentMap, not a Map: its putIfAbsent is there on API 23, Map's default isn't. */
    private static final ConcurrentMap<String, AtomicLong> HIDDEN_BY = new ConcurrentHashMap<>();
    private static final AtomicInteger FAILURES = new AtomicInteger();
    /** Kept rooms whose fields go to the debug log in full, so a report shows what TikTok sends. */
    private static final AtomicInteger ROOMS_TO_DESCRIBE = new AtomicInteger(12);
    /** Hidden rooms logged the same way. Strict rules hide hundreds, which would crowd out the rest of the log. */
    private static final AtomicInteger HIDDEN_TO_DESCRIBE = new AtomicInteger(50);

    /**
     * Whole pages hidden before one room goes through. A page that adds no room makes TikTok ask
     * for the next one at once, about once a second, so rules that pass nothing would keep it
     * asking for as long as the LIVE screen stays open, with the viewer stuck on the room they
     * came in on. One room after five such pages ends that, and costs one unwanted room in 30.
     */
    static final int HIDDEN_PAGES_BEFORE_ONE_SHOWS = 4;
    private static final AtomicInteger HIDDEN_PAGES_IN_A_ROW = new AtomicInteger();
    private static final AtomicLong SHOWN_TO_KEEP_LOADING = new AtomicLong();

    /** Called once at start-up by the patch, which only does so when it found the handler. */
    public static void installed() {
        SettingsStatus.enableLiveFeedFilter();
        LogBufferManager.registerReportSection(Report.INSTANCE);
    }

    /**
     * The page TikTok is about to add to the LIVE feed, without the rooms a rule hides. The same
     * list comes back when nothing is hidden, with the filter off, and on any failure.
     */
    public static List<?> filter(List<?> items) {
        if (items == null || items.isEmpty()) return items;
        PAGES.incrementAndGet();
        if (!Settings.LIVE_FEED_FILTER.get()) return items;
        try {
            Rules rules = Rules.fromSettings();
            String[] reasons = new String[items.size()];
            int rooms = 0;
            int roomsKept = 0;
            int standIn = -1;
            for (int index = 0; index < items.size(); index++) {
                Object room = roomOf(items.get(index));
                if (room == null) continue;
                rooms++;
                ROOMS.incrementAndGet();
                LiveRoom live = LiveRoom.of(room);
                String reason = reason(live, rules);
                describe(live, reason);
                reasons[index] = reason;
                if (reason == null) roomsKept++;
                else if (standIn < 0 && mayStandIn(reason)) standIn = index;
            }
            if (rooms > 0 && roomsKept == 0) {
                if (HIDDEN_PAGES_IN_A_ROW.incrementAndGet() > HIDDEN_PAGES_BEFORE_ONE_SHOWS && standIn >= 0) {
                    HIDDEN_PAGES_IN_A_ROW.set(0);
                    reasons[standIn] = null;
                    SHOWN_TO_KEEP_LOADING.incrementAndGet();
                    Logger.printDebug(() -> "LIVE feed: the rules hid every room on "
                            + (HIDDEN_PAGES_BEFORE_ONE_SHOWS + 1) + " pages in a row, one shows so the feed keeps loading");
                }
            } else if (rooms > 0) {
                HIDDEN_PAGES_IN_A_ROW.set(0);
            }

            List<Object> kept = null;
            for (int index = 0; index < items.size(); index++) {
                String reason = reasons[index];
                if (reason == null) {
                    if (kept != null) kept.add(items.get(index));
                    continue;
                }
                if (kept == null) kept = new ArrayList<>(items.subList(0, index));
                HIDDEN.incrementAndGet();
                countHidden(reason);
            }
            if (kept == null) return items;
            int hidden = items.size() - kept.size();
            Logger.printDebug(() -> "LIVE feed page: kept " + (items.size() - hidden) + " of " + items.size());
            return kept;
        } catch (RuntimeException failure) {
            if (FAILURES.getAndIncrement() < 3) {
                Logger.printException(() -> "Could not filter a LIVE feed page; it was left as TikTok sent it", failure);
            }
            return items;
        }
    }

    private static void countHidden(String reason) {
        AtomicLong count = HIDDEN_BY.get(reason);
        if (count == null) {
            AtomicLong fresh = new AtomicLong();
            count = HIDDEN_BY.putIfAbsent(reason, fresh);
            if (count == null) count = fresh;
        }
        count.incrementAndGet();
    }

    /** The room a feed item carries, or null for an item of another kind. */
    @Nullable
    private static Object roomOf(Object item) {
        if (item == null) return null;
        // TikTok's handler makes rooms of these three item types and passes the rest by.
        long type = number(item, "type");
        if (type != 1 && type != 2 && type != 19) return null;
        return Reflect.invoke(item, "getRoom");
    }

    /** Whether a room hidden for {@code reason} may be the one that keeps the feed loading. */
    static boolean mayStandIn(String reason) {
        return !BLOCKED_CREATOR.equals(reason) && !TITLE_WORD.equals(reason);
    }

    /** Why the rules hide {@code room}, or null to keep it. Hard rules come first. */
    @Nullable
    static String reason(LiveRoom room, Rules rules) {
        CreatorIdentity who = CreatorIdentity.of(room.uid, room.secUid, room.handle, room.nickname);
        if (rules.creators && AdvancedFeedRules.blocks(who)) return BLOCKED_CREATOR;
        if (!rules.titleWords.isEmpty() && KeywordRules.anyMatches(rules.titleWords, room.title)) return TITLE_WORD;
        if (rules.shopping && room.shopping) return SHOPPING;
        if (rules.sponsored && room.sponsored) return SPONSORED;
        // Everything below is a preference about LIVEs in general, which an exception gets past.
        if (rules.anySubjective() && CreatorExceptions.excepted(who)) return null;
        if (rules.verified && room.verified()) return VERIFIED;
        if (rules.gaming && room.gaming) return GAMING;
        if (!rules.categories.isEmpty() && KeywordRules.anyMatches(rules.categories, room.categoryText())) return CATEGORY;
        // A count TikTok didn't send keeps the room: an unknown is not a small number.
        if (room.viewers >= 0 && (room.viewers < rules.minViewers || room.viewers > rules.maxViewers)) return VIEWERS;
        if (room.followers >= 0 && (room.followers < rules.minFollowers || room.followers > rules.maxFollowers)) return FOLLOWERS;
        return null;
    }

    private static void describe(LiveRoom room, @Nullable String reason) {
        if ((reason == null ? ROOMS_TO_DESCRIBE : HIDDEN_TO_DESCRIBE).getAndDecrement() <= 0) return;
        Logger.printDebug(() -> "LIVE room " + room.id + " @" + room.handle
                + (reason == null ? " kept" : " hidden: " + reason)
                + "; viewers " + room.viewers + ", followers " + room.followers
                + ", verified " + (room.verifiedBy == null ? "no" : "by " + room.verifiedBy)
                + ", gaming " + room.gaming + ", shopping " + room.shopping + ", sponsored " + room.sponsored
                + ", categories " + room.categories + ", title " + (room.title == null ? "none" : '"' + room.title + '"'));
    }

    /** The settings as they stand for one page. */
    static final class Rules {
        boolean creators;
        List<KeywordRules.Rule> titleWords = new ArrayList<>();
        boolean shopping;
        boolean sponsored;
        boolean verified;
        boolean gaming;
        List<KeywordRules.Rule> categories = new ArrayList<>();
        long minViewers;
        long maxViewers = Long.MAX_VALUE;
        long minFollowers;
        long maxFollowers = Long.MAX_VALUE;

        static Rules fromSettings() {
            Rules rules = new Rules();
            rules.creators = !Settings.BLOCKED_CREATORS.get().trim().isEmpty()
                    || !Settings.LOCAL_HIDDEN_CREATORS.get().trim().isEmpty();
            String words = Settings.BLOCKED_CAPTION_WORDS.get();
            // The feed filter reads the same list through this one-entry cache, so they share it.
            if (!words.trim().isEmpty()) rules.titleWords = KeywordRules.cached(words);
            rules.shopping = Settings.LIVE_HIDE_SHOPPING.get();
            rules.sponsored = Settings.LIVE_HIDE_SPONSORED.get();
            rules.verified = Settings.LIVE_HIDE_VERIFIED.get();
            rules.gaming = Settings.LIVE_HIDE_GAMING.get();
            // Parsed here rather than cached: the cache holds one list, the caption words above.
            String categories = Settings.LIVE_HIDDEN_CATEGORIES.get();
            if (!categories.trim().isEmpty()) rules.categories = KeywordRules.parse(categories);
            long[] viewers = Utils.parseMinMax(Settings.LIVE_MIN_MAX_VIEWERS);
            rules.minViewers = viewers[0];
            rules.maxViewers = viewers[1];
            long[] followers = Utils.parseMinMax(Settings.LIVE_MIN_MAX_FOLLOWERS);
            rules.minFollowers = followers[0];
            rules.maxFollowers = followers[1];
            return rules;
        }

        boolean anySubjective() {
            return verified || gaming || !categories.isEmpty()
                    || minViewers > 0 || maxViewers != Long.MAX_VALUE
                    || minFollowers > 0 || maxFollowers != Long.MAX_VALUE;
        }
    }

    /**
     * What one room says about itself, read once. A count TikTok didn't send is -1. A feed room's
     * start_time and create_time, and its owner's create_time, all read 0 on 47.0.3, so neither a
     * running time nor an account age rule would have anything to go on.
     */
    static final class LiveRoom {
        long id;
        @Nullable String uid;
        @Nullable String secUid;
        @Nullable String handle;
        @Nullable String nickname;
        /** The owner field that says the account is verified, null when none does. */
        @Nullable String verifiedBy;
        long followers = -1;
        long viewers = -1;
        @Nullable String title;
        final List<String> categories = new ArrayList<>();
        boolean gaming;
        boolean shopping;
        boolean sponsored;

        String categoryText() {
            return String.join("\n", categories);
        }

        static LiveRoom of(Object room) {
            LiveRoom live = new LiveRoom();
            live.id = number(room, "id");
            live.title = text(room, "title");
            live.viewers = number(room, "userCount");

            Object owner = Reflect.readField(room, "owner");
            if (owner != null) {
                long id = number(owner, "id");
                live.uid = Reflect.firstNonBlank(text(owner, "idStr"), id > 0 ? Long.toString(id) : null);
                live.secUid = text(owner, "secUid");
                live.handle = text(owner, "username");
                live.nickname = text(owner, "nickName");
                live.verifiedBy = verifiedBy(owner);
                Object follow = Reflect.readField(owner, "followInfo");
                if (follow != null) live.followers = number(follow, "followerCount");
            }

            // The category a LIVE is listed under, its taxonomy tags and the game it plays.
            Object hashtag = Reflect.readField(room, "hashtag");
            String hashtagTitle = text(hashtag, "title");
            live.addCategory(hashtagTitle);
            Object taxonomy = Reflect.readField(room, "taxonomyTagInfo");
            live.addCategories(Reflect.readField(taxonomy, "level1Tag"));
            live.addCategory(text(taxonomy, "level2Tag"));
            Object gameCategory = Reflect.readField(room, "gameCategoryInfo");
            String gameCategoryTitle = text(gameCategory, "title");
            live.addCategory(gameCategoryTitle);
            Object detail = Reflect.readField(room, "gameTagDetail");
            live.addCategory(text(detail, "displayName"));
            live.addCategory(text(detail, "gameTagName"));
            Object gameTags = Reflect.readField(room, "gameTags");
            boolean tagged = false;
            if (gameTags instanceof List) {
                for (Object tag : (List<?>) gameTags) {
                    if (tag == null) continue;
                    tagged = true;
                    live.addCategory(text(tag, "showName"));
                    live.addCategory(text(tag, "fullName"));
                    live.addCategories(Reflect.readField(tag, "gameCategory"));
                }
            }
            // A stream filed under the Gaming category without a game picked carries no game tag
            // at all ("Gta V" on the S22, 2026-10-01). TikTok's own log names that category
            // hashtag_id=5, hashtag_name=Gaming, hashtag_is_game=1; the id holds in any language.
            live.gaming = number(room, "isGame") > 0 || tagged || gameCategoryTitle != null
                    || number(detail, "gameTagId") > 0
                    || number(hashtag, "id") == GAMING_HASHTAG_ID || "Gaming".equalsIgnoreCase(hashtagTitle);

            Object commerce = Reflect.readField(room, "commerceStruct");
            Object shopTags = Reflect.readField(room, "ecommerceRoomTags");
            // has_commerce_goods is the flag a feed room carries for a TikTok Shop showcase. The
            // product count reads 0 there even on a room selling, so it only adds to the flags.
            live.shopping = Boolean.TRUE.equals(Reflect.readField(room, "hasCommerceGoods"))
                    || Boolean.TRUE.equals(Reflect.readField(room, "existedCommerceGoods"))
                    || number(commerce, "productNum") > 0
                    || (shopTags instanceof List && !((List<?>) shopTags).isEmpty())
                    || text(Reflect.readField(room, "ecommerceRoomHeadTag"), "text") != null;

            // The label TikTok puts on a branded LIVE ("Paid partnership", "Promotional content")
            // comes in bc_toggle_info, absent on other rooms. A game partnership counts too.
            Object partnership = Reflect.readField(room, "partnershipInfo");
            live.sponsored = text(Reflect.readField(room, "bcToggleInfo"), "toggleText") != null
                    || Boolean.TRUE.equals(Reflect.readField(partnership, "promotingRoom"))
                    || Boolean.TRUE.equals(Reflect.readField(partnership, "partnershipRoom"));
            return live;
        }

        /**
         * The owner field that marks the account verified, or null. A feed room's owner can come
         * without the plain flag and still carry the reason or the badge text behind the check.
         */
        @Nullable
        private static String verifiedBy(Object owner) {
            if (Boolean.TRUE.equals(Reflect.readField(owner, "isVerified"))) return "verified";
            if (text(owner, "verifiedReason") != null) return "verified_reason";
            if (text(owner, "verifiedContent") != null) return "verified_content";
            Object authentication = Reflect.readField(owner, "authenticationInfo");
            if (text(authentication, "customVerify") != null) return "custom_verify";
            if (text(authentication, "enterpriseVerifyReason") != null) return "enterprise_verify_reason";
            return null;
        }

        boolean verified() {
            return verifiedBy != null;
        }

        private void addCategory(@Nullable String category) {
            if (category != null && !categories.contains(category)) categories.add(category);
        }

        private void addCategories(@Nullable Object values) {
            if (!(values instanceof List)) return;
            for (Object value : (List<?>) values) {
                if (value instanceof String) addCategory(((String) value).trim().isEmpty() ? null : (String) value);
            }
        }
    }

    /** A whole-number field, or -1 when it is missing or not a number. */
    private static long number(@Nullable Object target, String field) {
        Object value = Reflect.readField(target, field);
        return value instanceof Number ? ((Number) value).longValue() : -1;
    }

    @Nullable
    private static String text(@Nullable Object target, String field) {
        Object value = Reflect.readField(target, field);
        if (!(value instanceof String)) return null;
        String text = ((String) value).trim();
        return text.isEmpty() ? null : text;
    }

    static final class Report implements LogBufferManager.ReportSection {
        static final Report INSTANCE = new Report();

        @Override public String title() {
            return "LIVE FEED";
        }

        @Override public List<String> lines() {
            List<String> lines = new ArrayList<>();
            // The saved choice: Pause reads every switch as off, which would report a filter the
            // reader never turned off.
            boolean saved = Settings.LIVE_FEED_FILTER.savedValue();
            lines.add("Filter the LIVE feed: " + (saved ? "on" : "off"));
            if (saved && !Settings.LIVE_FEED_FILTER.get()) lines.add("Metra is paused, so the LIVE feed is as TikTok sends it");
            lines.add("LIVE feed pages since TikTok started: " + PAGES.get());
            lines.add("Rooms checked: " + ROOMS.get() + ", hidden: " + HIDDEN.get());
            if (!HIDDEN_BY.isEmpty()) {
                StringBuilder by = new StringBuilder("Hidden by rule:");
                for (Map.Entry<String, AtomicLong> entry : new TreeMap<>(HIDDEN_BY).entrySet()) {
                    by.append(' ').append(entry.getKey()).append(' ').append(entry.getValue().get()).append(',');
                }
                by.setLength(by.length() - 1);
                lines.add(by.toString());
            }
            long shown = SHOWN_TO_KEEP_LOADING.get();
            if (shown > 0) lines.add("Shown after " + (HIDDEN_PAGES_BEFORE_ONE_SHOWS + 1)
                    + " whole pages were hidden, so the feed kept loading: " + shown);
            return lines;
        }
    }

    /** Clears the counters between tests. */
    static void resetForTests() {
        PAGES.set(0);
        ROOMS.set(0);
        HIDDEN.set(0);
        HIDDEN_BY.clear();
        FAILURES.set(0);
        ROOMS_TO_DESCRIBE.set(12);
        HIDDEN_TO_DESCRIBE.set(50);
        HIDDEN_PAGES_IN_A_ROW.set(0);
        SHOWN_TO_KEEP_LOADING.set(0);
    }
}
