/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */

package app.morphe.extension.tiktok.feedfilter;

import android.app.Activity;
import android.view.ViewGroup;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.TikTokActivityHook;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Explains repeated batches that filtering removed completely without exposing item data. */
final class FeedFilterFeedback {
    private static final int NOTICE_AFTER_BATCHES = 3;
    private static final int MAX_REASON_LINES = 4;
    private static final long WINDOW_MS = 30_000L;
    private static final long NOTICE_COOLDOWN_MS = 60_000L;
    private static final String OTHER_REASON = "Other feed filters";
    private static final long NO_WINDOW = Long.MIN_VALUE;
    /**
     * Lists in a row that one marker switch has to wipe on one route before the notice names
     * it. Issue #20 was 29 profile pages of 10 reduced to 0 by SeriesFilter, and #24's reporter
     * read the generic notice and still wrote that no filter was on: a predicate that has
     * started matching the default shape looks like TikTok breaking until something says which
     * switch did it. Nothing here turns the switch off; the reader does, or does not.
     */
    static final int SUSPECT_AFTER_LISTS = 3;
    /**
     * A wiped list smaller than this says nothing about a predicate (a page of two series
     * episodes really is all series), so it neither counts towards a run nor breaks one.
     */
    static final int SUSPECT_MIN_LIST_SIZE = 5;
    private static final int MAX_SUSPECT_SOURCES = 64;

    /** A switch the notice can name: its settings page, its row key and the row's title. */
    private static final class MarkerSwitch {
        final String section;
        final String key;
        final String title;

        MarkerSwitch(String section, String key, String title) {
            this.section = section;
            this.key = key;
            this.title = title;
        }
    }

    /**
     * The filters a wiped run can be pinned on. Each reads one marker TikTok attaches to a
     * video, so three lists of five in a row with nothing else left in them is a predicate
     * matching the default shape rather than a feed that is all one thing. Left out on purpose:
     * ads and LIVE rooms, which a page can legitimately be all of; the reader's own lists and
     * ranges (keywords, creators, sounds, counts, countries, age, quality), which do what they
     * were told; and seen videos, which a re-served batch really can be all of.
     */
    private static final Map<String, MarkerSwitch> MARKER_SWITCHES = new HashMap<>();

    static {
        String feedFilter = "FEED_FILTER";
        MARKER_SWITCHES.put("StoryFilter",
                new MarkerSwitch(feedFilter, Settings.HIDE_STORY.key, "Hide stories"));
        MARKER_SWITCHES.put("ImageVideoFilter",
                new MarkerSwitch(feedFilter, Settings.HIDE_IMAGE.key, "Hide photo posts"));
        MARKER_SWITCHES.put("ShopFilter",
                new MarkerSwitch(feedFilter, Settings.HIDE_SHOP.key, "Hide TikTok Shop"));
        MARKER_SWITCHES.put("PaidPartnershipFilter",
                new MarkerSwitch(feedFilter, Settings.HIDE_PAID_PARTNERSHIP.key, "Hide paid partnerships"));
        MARKER_SWITCHES.put("LocationBadgeFilter",
                new MarkerSwitch(feedFilter, Settings.FILTER_LOCATION_VIDEOS.key, "Filter location-tagged videos"));
        MARKER_SWITCHES.put("AiGeneratedFilter",
                new MarkerSwitch(feedFilter, Settings.HIDE_AI_GENERATED.key, "Hide AI-generated videos"));
        MARKER_SWITCHES.put("VerifiedFilter",
                new MarkerSwitch(feedFilter, Settings.HIDE_VERIFIED.key, "Hide verified accounts"));
        MARKER_SWITCHES.put("SeriesFilter",
                new MarkerSwitch(feedFilter, Settings.HIDE_SERIES.key, "Hide Series"));
        MARKER_SWITCHES.put("DramaFilter",
                new MarkerSwitch(feedFilter, Settings.HIDE_MINI_DRAMAS.key, "Hide mini dramas"));
        MARKER_SWITCHES.put("PlaylistFilter",
                new MarkerSwitch(feedFilter, Settings.HIDE_PLAYLIST_VIDEOS.key, "Hide playlist videos"));
        MARKER_SWITCHES.put("PromotionalMusicFilter",
                new MarkerSwitch(feedFilter, Settings.HIDE_PROMOTIONAL_MUSIC.key, "Hide promotional music"));
        MARKER_SWITCHES.put("LiveReplayFilter",
                new MarkerSwitch(feedFilter, Settings.HIDE_LIVE_REPLAYS.key, "Hide LIVE replays"));
        MARKER_SWITCHES.put("InsertedCardFilter",
                new MarkerSwitch("INTERFACE", Settings.HIDE_INSERTED_CARDS.key, "Hide inserted cards"));
    }

    /** The lists in a row one marker filter has wiped on one route. */
    private static final class Streak {
        final String filter;
        int lists;
        /** Named once per run: the export keeps counting, the banner does not repeat. */
        boolean announced;

        Streak(String filter) {
            this.filter = filter;
        }
    }

    /** What a batch decided to show: the words, and the row the action lands on when it names one. */
    static final class Notice {
        final String message;
        /** Null for the generic notice, whose action opens the Feed filter page. */
        final MarkerSwitch suspect;

        Notice(String message, MarkerSwitch suspect) {
            this.message = message;
            this.suspect = suspect;
        }

        /** The setting key the action scrolls to, or null when it opens the page alone. */
        String settingKey() {
            return suspect == null ? null : suspect.key;
        }
    }

    private static final Object LOCK = new Object();
    private static final Map<String, Integer> reasons = new LinkedHashMap<>();
    private static final Map<String, Streak> streaks = new HashMap<>();
    private static int filteredBatches;
    private static long windowStartedAt = NO_WINDOW;
    private static long lastNoticeAt = Long.MIN_VALUE;
    private static boolean noticePending;
    /** Whether the last notice named a switch, so a second named one waits out the cooldown. */
    private static boolean lastNoticeNamed;

    private FeedFilterFeedback() {
    }

    /** Records one completed feed-list filtering pass and schedules a notice when needed. */
    static void onBatchResult(String source, int before, int after, Map<String, Integer> batchReasons, long nowMs) {
        Notice notice = recordBatch(source, before, after, batchReasons, nowMs);
        if (notice == null) return;

        Utils.runOnMainThread(() -> showNotice(notice));
    }

    /** Returns the next notice, or null when this pass should stay quiet. */
    static Notice recordBatch(String source, int before, int after, Map<String, Integer> batchReasons, long nowMs) {
        synchronized (LOCK) {
            Streak suspect = recordSuspectLocked(source, before, after, batchReasons);
            boolean allFiltered = before > 0 && after == 0;
            if (!allFiltered) {
                resetWindowLocked();
                return null;
            }

            if (windowStartedAt == NO_WINDOW || nowMs < windowStartedAt
                    || nowMs - windowStartedAt > WINDOW_MS) {
                resetWindowLocked();
                windowStartedAt = nowMs;
            }
            filteredBatches++;
            addReasonsLocked(batchReasons);
            boolean coolingDown = lastNoticeAt != Long.MIN_VALUE
                    && (nowMs < lastNoticeAt || nowMs - lastNoticeAt < NOTICE_COOLDOWN_MS);

            // A named switch beats the generic count: the reader who gets "Hide Series did
            // this" has no use for "your filters did this" a moment later. A run is named once,
            // and the first named notice also beats a generic one still pending or inside its
            // cooldown, since a banner replaces the one before it and the generic notice a few
            // seconds earlier said less. A second run of the same kind waits the cooldown out.
            if (suspect != null && suspect.lists >= SUSPECT_AFTER_LISTS && !suspect.announced
                    && !(coolingDown && lastNoticeNamed)) {
                suspect.announced = true;
                MarkerSwitch named = MARKER_SWITCHES.get(suspect.filter);
                String message = L10n.f(
                        "%1$s hid everything TikTok sent, %2$d times in a row. Turn it off if that's not what you wanted.",
                        L10n.t(named.title),
                        suspect.lists
                );
                lastNoticeAt = nowMs;
                lastNoticeNamed = true;
                noticePending = true;
                resetWindowLocked();
                return new Notice(message, named);
            }

            if (noticePending || coolingDown || filteredBatches < NOTICE_AFTER_BATCHES) return null;

            String message = formatMessageLocked();
            lastNoticeAt = nowMs;
            lastNoticeNamed = false;
            noticePending = true;
            resetWindowLocked();
            return new Notice(message, null);
        }
    }

    /**
     * Advances or breaks this route's run and returns it once it is long enough to name.
     *
     * <p>A list counts when it was five or more, nothing was kept, and one marker filter took
     * out more than half of it: an ad or a LIVE room in the same batch is not a reason to doubt
     * the run. A list that kept anything breaks the run, because the predicate plainly does not
     * match everything. An empty response or a small wiped list says nothing either way.
     */
    private static Streak recordSuspectLocked(String source, int before, int after, Map<String, Integer> batchReasons) {
        if (source == null || before <= 0) return null;
        if (after > 0) {
            streaks.remove(source);
            return null;
        }
        if (before < SUSPECT_MIN_LIST_SIZE) return null;

        String dominant = dominantMarkerFilter(batchReasons, before);
        if (dominant == null) return null;

        Streak streak = streaks.get(source);
        if (streak == null || !streak.filter.equals(dominant)) {
            if (streak == null && streaks.size() >= MAX_SUSPECT_SOURCES) return null;
            streak = new Streak(dominant);
            streaks.put(source, streak);
        }
        streak.lists++;
        if (streak.lists >= SUSPECT_AFTER_LISTS) {
            FeedFilterCounters.suspect(source, streak.filter, streak.lists);
            return streak;
        }
        return null;
    }

    /** The marker filter that took out more than half of a wiped list, or null when none did. */
    private static String dominantMarkerFilter(Map<String, Integer> batchReasons, int size) {
        if (batchReasons == null) return null;
        String best = null;
        int bestCount = 0;
        for (Map.Entry<String, Integer> entry : batchReasons.entrySet()) {
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (count > bestCount && MARKER_SWITCHES.containsKey(entry.getKey())) {
                best = entry.getKey();
                bestCount = count;
            }
        }
        return bestCount * 2 > size ? best : null;
    }

    private static void addReasonsLocked(Map<String, Integer> batchReasons) {
        if (batchReasons == null || batchReasons.isEmpty()) {
            addReasonLocked(OTHER_REASON, 1);
            return;
        }
        for (Map.Entry<String, Integer> entry : batchReasons.entrySet()) {
            int count = entry.getValue() == null ? 0 : entry.getValue();
            if (count > 0) addReasonLocked(reasonLabel(entry.getKey()), count);
        }
    }

    private static void addReasonLocked(String label, int count) {
        Integer existing = reasons.get(label);
        if (existing != null) {
            reasons.put(label, existing + count);
            return;
        }
        if (reasons.size() >= MAX_REASON_LINES - 1) {
            Integer other = reasons.get(OTHER_REASON);
            reasons.put(OTHER_REASON, other == null ? count : other + count);
            return;
        }
        reasons.put(label, count);
    }

    private static String formatMessageLocked() {
        List<Map.Entry<String, Integer>> ordered = new ArrayList<>(reasons.entrySet());
        // Most matches first, then by label. List.sort, Comparator.comparingInt, reversed and
        // thenComparing are all API 24 and D8 leaves them as stubs that throw, which would
        // reach TikTok's own frame from here on Android 6.
        Collections.sort(ordered, (left, right) -> {
            int byCount = right.getValue().compareTo(left.getValue());
            return byCount != 0 ? byCount : left.getKey().compareTo(right.getKey());
        });
        StringBuilder summary = new StringBuilder();
        for (Map.Entry<String, Integer> entry : ordered) {
            if (summary.length() > 0) summary.append(", ");
            summary.append(L10n.t(entry.getKey())).append(" (").append(entry.getValue()).append(")");
        }
        if (summary.length() == 0) summary.append(L10n.t(OTHER_REASON));
        // One batch is the common case the first time this shows, and "to 1 batches" is not a
        // sentence.
        // "Batch" and "matches" are words from inside the code. What a reader saw was a feed
        // that stopped giving them anything, and what they need to know is that their own
        // settings did it and roughly which one.
        if (filteredBatches == 1) {
            return L10n.f(
                    "Your filters hid everything TikTok just sent. Most were %1$s.",
                    summary.toString()
            );
        }
        return L10n.f(
                "Your filters hid everything TikTok sent, %1$d times in a row. Most were %2$s.",
                filteredBatches,
                summary.toString()
        );
    }

    private static String reasonLabel(String reason) {
        if ("AdsFilter".equals(reason)) return "Ads";
        if ("LiveFilter".equals(reason)) return "LIVE videos";
        if ("StoryFilter".equals(reason)) return "Stories";
        if ("ImageVideoFilter".equals(reason)) return "Photo and image posts";
        if ("ShopFilter".equals(reason)) return "Shop posts";
        if ("SoundFilter".equals(reason)) return "Blocked sounds";
        if ("PaidPartnershipFilter".equals(reason)) return "Paid partnerships";
        if ("LocationBadgeFilter".equals(reason)) return "Location-tagged videos";
        if ("AiGeneratedFilter".equals(reason)) return "AI-generated posts";
        if ("VerifiedFilter".equals(reason)) return "Verified accounts";
        if ("SeriesFilter".equals(reason)) return "Series posts";
        if ("DramaFilter".equals(reason)) return "Mini dramas";
        if ("PlaylistFilter".equals(reason)) return "Playlist posts";
        if ("InsertedCardFilter".equals(reason)) return "Inserted cards";
        if ("MidAdFilter".equals(reason)) return "Mid-roll ads";
        if ("SeenVideoFilter".equals(reason)) return "Seen videos";
        if ("OfflineVideoFilter".equals(reason)) return "Offline videos";
        if ("KeywordFilter".equals(reason)) return "Blocked caption words";
        if ("StickerTextFilter".equals(reason)) return "Text sticker words";
        if ("CreatorFilter".equals(reason)) return "Blocked and hidden creators";
        if ("PromotionalMusicFilter".equals(reason)) return "Promotional music";
        if ("LiveReplayFilter".equals(reason)) return "LIVE replays";
        if ("UnpersonalizedForYouFilter".equals(reason)) return "Unpersonalized For You videos";
        if ("RegionFilter".equals(reason)) return "Country filters";
        if ("CaptionLanguageFilter".equals(reason)) return "Caption languages";
        if ("PublicationAgeFilter".equals(reason)) return "Post age";
        if ("QualityFilter".equals(reason)) return "Length and views per like";
        return OTHER_REASON;
    }

    private static void showNotice(Notice notice) {
        synchronized (LOCK) {
            noticePending = false;
        }
        Activity activity = Utils.getActivity();
        // Posted from a network thread, so it can land between an activity's onDestroy and
        // the next one taking its place, when isFinishing is already false again and only
        // isDestroyed says the window token is gone. A dialog on it throws; a toast does not.
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            Utils.showToastLong(notice.message);
            return;
        }
        // This used to be a modal over the feed. Its only action was opening a settings page,
        // and nothing about it was worth stopping a scroll for: a reader who wants to keep
        // scrolling had to dismiss a dialog to do it. The banner says the same thing, takes no
        // focus, announces itself once and takes itself away.
        ViewGroup root = activity.findViewById(android.R.id.content);
        if (root == null) {
            Utils.showToastLong(notice.message);
            return;
        }
        MarkerSwitch suspect = notice.suspect;
        if (suspect == null) {
            BlockAuthorOverlay.showActionBanner(root, notice.message,
                    L10n.t(activity, "Filter settings"),
                    TikTokActivityHook::openFeedFilterSettings);
            return;
        }
        BlockAuthorOverlay.showActionBanner(root, notice.message,
                L10n.t(activity, "Show the switch"),
                () -> TikTokActivityHook.openSettingsRow(suspect.section, suspect.key));
    }

    private static void resetWindowLocked() {
        filteredBatches = 0;
        windowStartedAt = NO_WINDOW;
        reasons.clear();
    }

    static void resetForTests() {
        synchronized (LOCK) {
            resetWindowLocked();
            streaks.clear();
            lastNoticeAt = Long.MIN_VALUE;
            lastNoticeNamed = false;
            noticePending = false;
        }
    }

    /** What the main thread does with a notice: the tests never post one there. */
    static void noticeShownForTests() {
        synchronized (LOCK) {
            noticePending = false;
        }
    }
}
