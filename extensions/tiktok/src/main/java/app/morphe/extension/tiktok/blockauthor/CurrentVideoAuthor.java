/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import app.morphe.extension.shared.Logger;

import app.morphe.extension.tiktok.wellbeing.SessionBudget;
import app.morphe.extension.tiktok.wellbeing.SessionBudgetNotice;
import app.morphe.extension.tiktok.wellbeing.SessionLockOverlay;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Holds the author of the video that is on screen.
 *
 * Two signals feed this, and the difference between them matters.
 *
 * {@code VideoAuthorInfoVM.paramSync2StateAccept} runs when TikTok <em>binds</em> an item,
 * which a paging feed does for the neighbours you have not reached yet. Taking the most
 * recent bind as the current video is therefore wrong: by the time you tap, it is usually
 * the video below the one you are watching. That is only a cache here.
 *
 * {@code PlayerController.onPlayProgressChange} reports the id of the video that is
 * actually <em>playing</em>, several times a second. That is what selects the current item
 * out of the cache. When the player names a video that has not been bound yet, nothing is
 * current and the button hides rather than arming the wrong creator.
 */
public final class CurrentVideoAuthor {
    /**
     * How many bound items to remember. The feed binds a couple either side of the current
     * one, so this is generous; it exists to bound the map, not to tune behaviour.
     */
    private static final int RECENT_LIMIT = 16;

    private static final Map<String, Item> RECENT = Collections.synchronizedMap(
            new LinkedHashMap<String, Item>(32, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Item> eldest) {
                    return size() > RECENT_LIMIT;
                }
            });

    /**
     * Items that were current, most recent last, so the player naming one again finds it. A
     * creator's grid or a story binds more videos than {@link #RECENT_LIMIT}, and the feed video
     * the reader came back to was no longer bound anywhere the player's lookup could see: the
     * controls went until the next swipe. Only selections land here, so it stays small.
     */
    private static final int SELECTED_LIMIT = 8;

    private static final Map<String, Item> SELECTED = Collections.synchronizedMap(
            new LinkedHashMap<String, Item>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Item> eldest) {
                    return size() > SELECTED_LIMIT;
                }
            });

    private static volatile Item current;
    private static volatile String playingAwemeId;

    /**
     * Held while one of the two signals decides what is current and applies it. They arrive on
     * different threads: a bind on the main thread, the player's id on its own. The player
     * could find nothing bound, the bind could then land and select the video, and the
     * player's "nothing" was written last, leaving no current video until the next one,
     * because every later tick of the same id returns early.
     */
    private static final Object SELECTING = new Object();

    /** Runs between the player's lookup and its select, so a test can land a bind there. */
    private static volatile Runnable betweenLookupAndSelectForTests;
    /** How often the player's report asks whether the budget is spent, on the budget's clock. */
    private static final long CLAIM_EVERY_MS = 1_000L;
    private static volatile long lastClaimAskedAt = Long.MIN_VALUE / 2;

    private CurrentVideoAuthor() {
    }

    /** One bound feed item. {@code author} is null for a card with nobody to block. */
    private static final class Item {
        final String awemeId;
        final VideoAuthor author;
        final Object aweme;

        Item(String awemeId, VideoAuthor author, Object aweme) {
            this.awemeId = awemeId;
            this.author = author;
            this.aweme = aweme;
        }
    }

    /**
     * Called as TikTok binds a feed item, which happens ahead of the item being reached.
     *
     * @param videoItemParams a {@code com.ss.android.ugc.aweme.feed.model.VideoItemParams}
     */
    static void update(Object videoItemParams) {
        Item item = parse(videoItemParams);
        // Ahead of the reader, as TikTok prepares the item's engine ahead: muted then, a feed
        // video starts silent instead of for the moment before it becomes current.
        if (item != null && item.aweme != null) {
            app.morphe.extension.tiktok.playback.FeedMute.onFeedBind(item.aweme);
        }
        synchronized (SELECTING) {
            if (item != null && item.awemeId != null) {
                RECENT.put(item.awemeId, item);
            }

            // A bind may fill an empty selection but never replace one. That covers the first
            // video of a fresh process, where nothing has played yet, and the moment after the
            // player names a video the feed had not bound. Replacing a live selection is the
            // player's job alone, which is what keeps a prefetched neighbour from arming the
            // button.
            if (current == null || (item != null && item.awemeId != null
                    && item.awemeId.equals(playingAwemeId))) {
                select(item);
            }
        }
    }

    /**
     * Called from {@code PlayerController.onPlayProgressChange} for the video that is
     * playing. Fires several times a second, so it returns immediately once the id is
     * already the current one.
     *
     * @param awemeId the playing video's id
     */
    static void onPlaying(String awemeId) {
        if (awemeId == null || awemeId.isEmpty()) {
            return;
        }

        // Before the early return: this is the only signal that arrives while a video plays,
        // so it is the only thing that can measure how long the feed has been running.
        SessionBudget.noteWatching();
        // The budget can run out halfway through a video, or be spent already once the reader
        // lowers it, and the notice and the hold are due then: left to the next video change, a
        // long or looping one played on past the budget. claimNotice answers once per spent
        // budget and re-arms itself when a raised budget puts the reader under it again.
        // Only while a budget counts: with none set there is nothing to run out, and the video
        // change still asks, which is what lets a removed budget's hold go.
        if (SessionBudget.isCounting()) {
            long now = SessionBudget.now();
            if (now - lastClaimAskedAt >= CLAIM_EVERY_MS || now < lastClaimAskedAt) {
                lastClaimAskedAt = now;
                if (SessionBudget.claimNotice()) SessionBudgetNotice.show(awemeId);
            }
        }
        SessionLockOverlay.ensureRunning();
        // Follows the budget rather than a clock of its own, so it is redrawn from the same
        // signal that measures the budget. Switched off, which is the default, it returns on
        // the setting before it looks at anything.
        app.morphe.extension.tiktok.wellbeing.HoldRamp.sync();
        // Same signal, same switched-off-first shape. The label moves at most once a minute, so
        // this is only how often it is asked, not how often it changes.
        app.morphe.extension.tiktok.wellbeing.BudgetCue.sync();

        if (awemeId.equals(playingAwemeId)) {
            return;
        }
        synchronized (SELECTING) {
            if (awemeId.equals(playingAwemeId)) return;
            playingAwemeId = awemeId;

            // A miss means the player is ahead of the bind. Selecting null hides the button
            // for that moment, which is the right answer: better no button than one wired to
            // the previous creator. The bind that follows finds the id playing and selects it.
            Item found = RECENT.get(awemeId);
            if (found == null) found = SELECTED.get(awemeId);
            Runnable hook = betweenLookupAndSelectForTests;
            if (hook != null) hook.run();
            select(found);
        }
    }

    /** Applies a new current item and tells everything that tracks the current video. */
    private static void select(Item item) {
        Item previous = current;
        current = item;
        if (item != null && item.awemeId != null) SELECTED.put(item.awemeId, item);

        String previousId = previous == null ? null : previous.awemeId;
        String newId = item == null ? null : item.awemeId;

        if (!Objects.equals(previousId, newId)) {
            SessionBudget.noteVideo(newId);
            // Before the budget is asked: a last video being finished ends when another comes
            // up, and the one that just came up may be the one that spends the budget.
            app.morphe.extension.tiktok.wellbeing.FinishLastVideo.onVideoChanged(newId);
            if (SessionBudget.claimNotice()) SessionBudgetNotice.show(newId);
            // Checked where the day's own notice is checked, on the video change rather than on
            // the progress callback: the reminder is measured in watched minutes but it should
            // arrive between videos rather than over one.
            SessionBudgetNotice.showIntervalNoticeIfDue();
            app.morphe.extension.tiktok.interaction.TapConfirmation.onVideoChanged();
            app.morphe.extension.tiktok.captions.CaptionTools.onVideoChanged(newId);
            app.morphe.extension.tiktok.feed.AuthorRegion.onVideoChanged();

            if (item != null && item.aweme != null) {
                CurrentVideoSound.update(item.aweme);
                // Mute feed videos hears only one of the routes TikTok starts a feed video by;
                // this change is the one every route passes.
                app.morphe.extension.tiktok.playback.FeedMute.onCurrentVideo(item.aweme);
            } else {
                CurrentVideoSound.clear();
            }
        }

        VideoAuthor author = item == null ? null : item.author;
        VideoAuthor previousAuthor = previous == null ? null : previous.author;
        if (Objects.equals(author, previousAuthor)) {
            return;
        }

        if (author == null) {
            Logger.printDebug(() -> "Current item has no blockable author");
        } else {
            // "Current video:" rather than "author:", which the export's credential rule reads
            // as a name to omit the value of, taking the pseudonym's first word with it.
            Logger.printDebug(() -> "Current video: " + author.reference()
                    + " aweme=" + author.awemeId);
        }
        BlockAuthorOverlay.onAuthorChanged(author);
    }

    /**
     * @return the author of the video that is playing, or null when there is nobody to
     *         block on it. There is deliberately no expiry: a video can run for minutes,
     *         and the player keeps naming the same id throughout. Keeping the button off
     *         other screens is {@link FeedVisibility}'s job, not this one's.
     */
    public static VideoAuthor get() {
        Item item = current;
        return item == null ? null : item.author;
    }

    public static Object getAweme() {
        Item item = current;
        return item == null ? null : item.aweme;
    }

    static void setBetweenLookupAndSelectForTests(Runnable hook) {
        betweenLookupAndSelectForTests = hook;
    }

    static void resetForTests() {
        betweenLookupAndSelectForTests = null;
        lastClaimAskedAt = Long.MIN_VALUE / 2;
        RECENT.clear();
        SELECTED.clear();
        current = null;
        playingAwemeId = null;
        CurrentVideoSound.clear();
    }

    /** Reads the item without touching anything that tracks the current video. */
    private static Item parse(Object videoItemParams) {
        try {
            Object aweme = Reflect.property(videoItemParams, "getAweme", "aweme");
            if (aweme == null) {
                return null;
            }

            String awemeId = Reflect.string(aweme, "getAid", "aid");
            Object user = Reflect.property(aweme, "getAuthor", "author");
            if (user == null) {
                // A LIVE preview, a promo or an end of feed card: real item, nobody to block.
                return new Item(awemeId, null, aweme);
            }

            String uid = Reflect.firstNonBlank(
                    Reflect.string(user, "getUid", "uid"),
                    Reflect.string(aweme, "getAuthorUid", "authorUid")
            );
            String secUid = Reflect.string(user, "getSecUid", "secUid");
            String displayName = Reflect.firstNonBlank(
                    Reflect.string(user, "getUniqueId", "uniqueId"),
                    Reflect.string(user, "getNickname", "nickname")
            );

            VideoAuthor author = new VideoAuthor(uid, secUid, displayName, awemeId);
            return new Item(awemeId, author.isUsable() ? author : null, aweme);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not read the current video author", ex);
            return null;
        }
    }
}
