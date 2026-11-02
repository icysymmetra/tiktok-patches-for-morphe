/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Calendar;
import java.util.TimeZone;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/**
 * How much of the feed has gone by today, and whether it is time to stop.
 *
 * <p>Two numbers are kept: how many different videos have come up, and how long the player has
 * been running. Either can carry the budget, both can be off, and off is the default. Reaching
 * the budget says so once and, if a hold was set, covers the feed until it runs out. Nothing
 * here touches the feed's own data, so TikTok never learns that anything happened and never
 * refetches a batch it already has.
 *
 * <p>The day ends at an hour of the reader's choosing rather than at midnight, because someone
 * still scrolling at one in the morning is having last night. The day only ever moves forward:
 * changing that hour, or crossing a timezone, must not hand back a budget that has been spent.
 *
 * <p>Everything survives the process being killed. What it must not do is cost anything to keep:
 * the player reports progress several times a second, the settings store commits synchronously,
 * and a record written on every report would be thousands of blocking writes an hour. So nothing
 * is counted at all until a budget is set, the record is written on a background thread, and the
 * watched time is only committed when it has moved by a meaningful amount.
 */
public final class SessionBudget {
    /**
     * The most one player callback may add to the watched time. The callbacks arrive several
     * times a second while a video plays and stop when it does not, so a long gap means the app
     * was away rather than that someone watched for an hour.
     */
    static final long MAX_TICK_MS = 5_000L;

    /** Watched time is committed at this granularity; between writes it is only in memory. */
    static final long WRITE_EVERY_MS = 30_000L;

    /** Reading and writing the record is not atomic on its own, so every path holds this. */
    private static final Object LOCK = new Object();

    /**
     * Serializes a commit with test resets. The executor preserves ordering between queued
     * writes, but a reset can otherwise clear storage while an older queued record is waiting.
     */
    private static final Object WRITE_FENCE = new Object();
    private static volatile long writeGeneration;

    /**
     * The settings store commits synchronously, and the caller here is the player's own progress
     * callback. One thread, so writes stay ordered, and never the caller's.
     */
    private static final ExecutorService WRITER =
            Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "hushfeed-session-budget");
                thread.setDaemon(true);
                return thread;
            });

    /** Volatile so {@link #now()} can read it without the lock a write to the record holds. */
    private static volatile Clock clock = System::currentTimeMillis;

    /**
     * Read without the monitor by {@link #isLocked()}, which runs from the player's progress
     * callback several times a second. Volatile rather than guarded because the fast path only
     * ever answers "no hold", and it answers that from the same field every writer publishes.
     */
    private static volatile boolean loaded;
    private static long day;
    private static int videos;
    private static long watchedMs;
    private static long writtenWatchedMs;
    private static volatile long lockUntilMs;
    /**
     * True once today's budget ran out with the lock switched on. Read without the monitor by
     * {@link #lockedToday()} callers on the settings screen, published under it like everything
     * else. Cleared only by the day turning over.
     */
    private static volatile boolean lockedToday;

    /**
     * How many times the hold has been opened today. Only ever compared against
     * {@link Settings#SESSION_BUDGET_PASSES_PER_DAY}, so it costs nothing while that is zero,
     * but it is counted either way: a reader who sets a cap part way through a day should not
     * get the day's passes back for having set it.
     */
    private static int passesUsed;

    /**
     * The watched time when the last quiet reminder went out, and how many have ever gone.
     *
     * <p>Measured against {@link #watchedMs} rather than the clock, so the interval is minutes
     * of feed rather than minutes of being awake, and time on messages or a profile does not
     * bring one on. The count is what picks the wording, so the three take turns; it is not a
     * count of the day and does not roll over with it.
     */
    private static long noticeMarkMs;
    private static long noticesShown;

    /**
     * What "start today over" cleared, so the next tap can put it back. Held in memory rather
     * than in the record: it is the way back from a tap a moment ago, not a second day's worth
     * of state, and writing it would double what every clear costs.
     */
    private static boolean undoAvailable;
    private static long undoDay;
    private static int undoVideos;
    private static long undoWatchedMs;
    private static long undoLockUntilMs;
    private static boolean undoNoticeShown;
    private static int undoPassesUsed;
    private static long undoNoticeMarkMs;
    private static long lastTickMs;
    private static String lastCountedId;
    private static boolean noticeShown;

    /**
     * The day {@link #dayOf(long)} last worked out and the span of time that answer holds for.
     * Building a Calendar and a TimeZone clone on every player callback was the whole cost of
     * the budget once one was set. Guarded by {@link #LOCK} like everything else here.
     */
    private static long cachedDay;
    private static long cachedWindowStart;
    private static long cachedWindowEnd;
    private static int cachedResetHour = -1;
    /**
     * Registered once, and the reason the memo does not have to read the zone.
     *
     * <p>TimeZone.getDefault() hands back a clone on Android, so asking it on every player
     * callback was an allocation and a string compare several times a second for the whole
     * time a budget was set. There is no allocation-free way to read it: getDefaultRef is
     * package private in java.util and ZoneId.systemDefault goes through the same clone. So
     * the memo is told instead.
     *
     * <p>The one thing this cannot see is the host calling TimeZone.setDefault itself, which
     * sends no broadcast. The memo then answers with the old day until its window rolls, which
     * is the trade the alternative was not worth: reading the zone caught that case and cost a
     * clone on every frame the player reported.
     */
    private static android.content.Context watchedContext;
    private static int dayComputations;
    private static int lockChecksUnderTheMonitor;

    /** So a test can move time without waiting for it. */
    interface Clock {
        long now();
    }

    private SessionBudget() {
    }

    /**
     * Whether anything is being counted at all. With no budget set and no hold running there is
     * nothing to count and nothing to write, and this is checked before any lock is taken so the
     * off case costs one settings read.
     */
    /** Whether any budget or reminder is set, so the player's per-second report has anything to ask. */
    public static boolean isCounting() {
        return counting();
    }

    private static boolean counting() {
        return Settings.SESSION_BUDGET_VIDEOS.get() > 0
                || Settings.SESSION_BUDGET_MINUTES.get() > 0
                // A reader who wants the reminders and no budget at all still has to be counted.
                || Settings.SESSION_BUDGET_NOTICE_MINUTES.get() > 0;
    }

    // ---------------------------------------------------------------- what the feed reports

    /**
     * A different video is on screen. Called from the point that decides which of the bound
     * items is the current one, so a prefetched neighbour does not count and a video revisited
     * without anything in between does not count twice.
     *
     * <p>Nothing is counted while a hold is running, for the same reason {@link #noteWatching()}
     * adds no time: the feed is behind the panel.
     */
    public static void noteVideo(String awemeId) {
        if (awemeId == null || awemeId.isEmpty() || !counting()) return;
        synchronized (LOCK) {
            load();
            long now = clock.now();
            rollOver(now);
            // Nothing counts while a hold is running, for the same reason noteWatching does not:
            // the feed is behind the panel. Videos that go by underneath it were spending
            // tomorrow's budget as well as today's.
            if (lockUntilMs > now) return;
            if (awemeId.equals(lastCountedId)) return;
            lastCountedId = awemeId;
            videos++;
            save();
        }
    }

    /**
     * The player reported progress. Adds the time since the previous report, which is the only
     * measure of watching available without a clock of our own running in the background.
     *
     * <p>Nothing is added while a hold is running: the feed is behind the overlay, and charging
     * someone for a video they cannot see would empty tomorrow's budget as well as today's.
     */
    public static void noteWatching() {
        if (!counting()) return;
        synchronized (LOCK) {
            load();
            long now = clock.now();
            rollOver(now);
            long since = lastTickMs == 0 ? 0 : now - lastTickMs;
            lastTickMs = now;
            if (since <= 0 || since > MAX_TICK_MS) return;
            if (lockUntilMs > now) return;
            watchedMs += since;
            // Committed in steps: a record on every callback is a blocking write several times
            // a second, and losing at most half a minute to a kill is a fair trade for that.
            if (watchedMs - writtenWatchedMs >= WRITE_EVERY_MS) save();
        }
    }

    // ---------------------------------------------------------------------- what it adds up to

    public static int videosSeen() {
        synchronized (LOCK) {
            load();
            rollOver(clock.now());
            return videos;
        }
    }

    public static long watchedMs() {
        synchronized (LOCK) {
            load();
            rollOver(clock.now());
            return watchedMs;
        }
    }

    /**
     * How much of the day's watching budget is left, in milliseconds.
     *
     * <p>Negative when there is nothing counted in time to measure against, which is a
     * different answer from zero: a budget counted only in videos never runs down in time, so
     * anything drawing from the clock has nothing to draw.
     */
    public static long budgetRemainingMs() {
        synchronized (LOCK) {
            load();
            rollOver(clock.now());
            int minuteBudget = Settings.SESSION_BUDGET_MINUTES.get();
            // Asked first, and before spent(). A budget counted only in videos runs out
            // without any time having run down, and answering zero there told anything
            // drawing from the clock that it had reached the end of a clock nobody set.
            if (minuteBudget <= 0) return -1;
            if (spent()) return 0;
            return Math.max(0, minuteBudget * 60_000L - watchedMs);
        }
    }

    /** True once either budget that is switched on has been used up. */
    public static boolean reachedLimit() {
        synchronized (LOCK) {
            load();
            rollOver(clock.now());
            return spent();
        }
    }

    private static boolean spent() {
        return spent(Settings.SESSION_BUDGET_VIDEOS.get(), Settings.SESSION_BUDGET_MINUTES.get());
    }

    private static boolean spent(int videoBudget, int minuteBudget) {
        if (videoBudget > 0 && videos >= videoBudget) return true;
        return minuteBudget > 0 && watchedMs >= minuteBudget * 60_000L;
    }

    /**
     * True the first time the budget runs out, so the notice is shown once rather than on every
     * video after it. A new day, or a raised budget that puts the reader back under it, arms it
     * again and lifts any hold that was running: a hold for a budget nobody is over any more is
     * a screen covered for no reason anyone can see.
     */
    public static boolean claimNotice() {
        // Asked on every video change. Paused, both budgets read zero through get(), which looked
        // like a raised budget and wiped a running hold out of the record. The budget is off with
        // everything else, and the record waits for Hushfeed to come back, as lockRemainingMs has it.
        if (Setting.isPaused()) return false;
        synchronized (LOCK) {
            load();
            rollOver(clock.now());
            if (!spent()) {
                // A locked day is not given back by raising the budget. The settings screen
                // refuses that edit while the lock holds; this is the same answer at the source.
                if (lockedToday) return false;
                if (noticeShown || lockUntilMs != 0) {
                    noticeShown = false;
                    lockUntilMs = 0;
                    save();
                }
                return false;
            }
            if (noticeShown) return false;
            noticeShown = true;
            startLock();
            save();
            return true;
        }
    }

    private static void startLock() {
        long now = clock.now();
        if (Settings.SESSION_BUDGET_LOCK.get()) {
            // A commitment rather than a timer: it runs to the hour the day turns over, and the
            // switch that made it cannot be reached again until then.
            lockedToday = true;
            long untilReset = dayEndAfter(now);
            if (untilReset > lockUntilMs) lockUntilMs = untilReset;
            return;
        }
        int lockMinutes = Settings.SESSION_BUDGET_LOCK_MINUTES.get();
        if (lockMinutes <= 0) return;
        long until = now + lockMinutes * 60_000L;
        if (until > lockUntilMs) lockUntilMs = until;
    }

    /**
     * Whether today's budget was locked when it ran out.
     *
     * <p>Everything the reader could otherwise use to undo the day hangs off this: the way out
     * of the hold, "Start today over", the budget rows and the lock switch itself.
     */
    public static boolean lockedToday() {
        synchronized (LOCK) {
            load();
            rollOver(clock.now());
            return lockedToday;
        }
    }

    /** When the locked day ends, or zero when no day is locked. */
    public static long lockedUntilMs() {
        synchronized (LOCK) {
            load();
            rollOver(clock.now());
            if (!lockedToday) return 0L;
            // The instant the lock committed to, not one worked out again now: the reset hour
            // and the timezone can both have moved since, and neither may bring the day forward.
            return lockUntilMs > 0 ? lockUntilMs : dayEndAfter(clock.now());
        }
    }

    // ------------------------------------------------------------------------------- the hold

    public static boolean isLocked() {
        // SessionLockOverlay.ensureRunning reaches this from the player's progress callback, so
        // the answer almost everyone gets, that no hold has ever been set, must not take the
        // monitor or build a Calendar. Only "no hold" is answered here, and it is answered from
        // the field every writer publishes, so a hold is never missed.
        if (loaded && lockUntilMs == 0) return false;
        return lockRemainingMs() > 0;
    }

    /**
     * How much of the hold is left, or zero when there is none. Paused, the budget is off with
     * everything else: the hold stays in the record for when Hushfeed comes back, and answers
     * zero meanwhile, which is also what {@link #isLocked()} goes by.
     */
    public static long lockRemainingMs() {
        if (Setting.isPaused()) return 0;
        synchronized (LOCK) {
            lockChecksUnderTheMonitor++;
            load();
            long now = clock.now();
            rollOver(now);
            if (lockUntilMs <= now) {
                if (lockUntilMs != 0) {
                    lockUntilMs = 0;
                    save();
                }
                return 0;
            }
            return lockUntilMs - now;
        }
    }

    /**
     * Lifts a running hold without touching the counts. The budget stays reached, so the notice
     * does not come back until a new day or a raised budget puts the reader under it again.
     *
     * @return false when nothing was lifted, which is a locked day, no hold running, or a day
     *         whose passes are spent.
     */
    public static boolean releaseLock() {
        synchronized (LOCK) {
            load();
            rollOver(clock.now());
            // The one exit the hold has, and the whole point of the lock is that today has none.
            if (lockedToday) return false;
            if (lockUntilMs == 0) return false;
            int cap = Settings.SESSION_BUDGET_PASSES_PER_DAY.get();
            if (cap > 0 && passesUsed >= cap) return false;
            lockUntilMs = 0;
            passesUsed++;
            save();
            return true;
        }
    }

    /**
     * How many more times the hold may be opened today.
     *
     * @return {@link Integer#MAX_VALUE} when no cap is set, which is the default and what the
     *         hold has always done.
     */
    /**
     * Whether it is time for a quiet reminder, and which of the three wordings to use.
     *
     * <p>Never while a hold is up: the panel is the message then, and a toast underneath it
     * would be one more thing to read on a screen that is already saying stop.
     *
     * @return 0, 1 or 2 for the wording, or -1 for nothing to say.
     */
    public static int claimIntervalNotice() {
        int minutes = Settings.SESSION_BUDGET_NOTICE_MINUTES.get();
        if (minutes <= 0) return -1;
        synchronized (LOCK) {
            load();
            long now = clock.now();
            rollOver(now);
            if (lockUntilMs > now) return -1;
            long interval = minutes * 60_000L;
            if (watchedMs - noticeMarkMs < interval) return -1;
            // Moved to now. Rounding down to a whole number of intervals, which this did first,
            // left the mark behind the moment the reminder went out by however far the watched
            // time had overshot, so the next one arrived that much early: on a five minute row a
            // reader who reached nine minutes fifty-nine before the video changed got the second
            // one a second after the first. Time away cannot bring one forward either way, since
            // watchedMs only moves while the feed is playing.
            noticeMarkMs = watchedMs;
            // Not reset by the day, because it is not a count of anything: it is which of the
            // three wordings comes next. Rolled over, a reader who gets one reminder a day read
            // the same sentence every day, which is the thing having three is for.
            int wording = (int) Math.floorMod(noticesShown, 3L);
            noticesShown++;
            save();
            return wording;
        }
    }

    public static int passesLeftToday() {
        int cap = Settings.SESSION_BUDGET_PASSES_PER_DAY.get();
        if (cap <= 0) return Integer.MAX_VALUE;
        synchronized (LOCK) {
            load();
            rollOver(clock.now());
            return Math.max(0, cap - passesUsed);
        }
    }

    /**
     * Ends the hold and clears both counts.
     *
     * <p>One tap does it, with no dialog asking permission first, because the next tap puts it
     * back. That is the same bargain the seen video history row makes, and it is the one this
     * row was not keeping: it used to leave nothing behind at all.
     *
     * @return false when today is locked and nothing was cleared.
     */
    public static boolean clear() {
        synchronized (LOCK) {
            load();
            rollOver(clock.now());
            if (lockedToday) return false;
            undoDay = day;
            undoVideos = videos;
            undoWatchedMs = watchedMs;
            undoLockUntilMs = lockUntilMs;
            undoNoticeShown = noticeShown;
            undoPassesUsed = passesUsed;
            undoNoticeMarkMs = noticeMarkMs;
            undoAvailable = true;
            day = dayOf(clock.now());
            videos = 0;
            watchedMs = 0;
            writtenWatchedMs = 0;
            lockUntilMs = 0;
            lastCountedId = null;
            noticeShown = false;
            // A pass is one of today's counts, and this row's own wording is that today is
            // forgotten. Leaving it spent gave back the videos and the minutes and kept the way
            // out of the hold gone, on a day the screen was showing as untouched. The reminders
            // are counts of today for the same reason: the watched time they measure is going
            // back to zero, so the mark they measure from has to as well.
            passesUsed = 0;
            noticeMarkMs = 0;
            save();
            return true;
        }
    }

    /**
     * Locks the rest of today when the budget has already run out, and says whether it did.
     *
     * <p>Called as the switch is turned on. Deriving the answer from the switch and the counts
     * instead was a trap: {@code spent()} reads the budget live, so lowering the budget under
     * the count you already had locked the day on the spot, for someone who never reached it.
     * A locked day is one fact, written once, and only here or by the budget running out.
     */
    public static boolean lockIfSpent() {
        synchronized (LOCK) {
            load();
            long now = clock.now();
            rollOver(now);
            // The settings screen's answer, so the reader's budgets: paused, get() reads both as
            // zero, and a lock turned on then would never take a day that was already spent.
            boolean spent = spent(Settings.SESSION_BUDGET_VIDEOS.savedValue(), Settings.SESSION_BUDGET_MINUTES.savedValue());
            if (lockedToday || !spent) return false;
            lockedToday = true;
            long untilReset = dayEndAfter(now);
            if (untilReset > lockUntilMs) lockUntilMs = untilReset;
            save();
            return true;
        }
    }

    /** Whether the last "start today over" is still there to be taken back. */
    public static boolean canUndoClear() {
        synchronized (LOCK) {
            load();
            rollOver(clock.now());
            return undoAvailable && undoDay == day;
        }
    }

    /**
     * Puts back the counts and any hold that "start today over" cleared.
     *
     * @return false when there is nothing to put back, which includes a day that has since
     *         turned over: the counts it saved belong to a day that is finished.
     */
    public static boolean undoClear() {
        synchronized (LOCK) {
            load();
            rollOver(clock.now());
            if (!undoAvailable || undoDay != day) return false;
            videos = undoVideos;
            watchedMs = undoWatchedMs;
            writtenWatchedMs = undoWatchedMs;
            lockUntilMs = undoLockUntilMs;
            noticeShown = undoNoticeShown;
            passesUsed = undoPassesUsed;
            noticeMarkMs = undoNoticeMarkMs;
            lastCountedId = null;
            undoAvailable = false;
            save();
            return true;
        }
    }

    // -------------------------------------------------------------------------------- the day

    /**
     * Which day a moment belongs to, counting the day as starting at the chosen hour. The
     * device's own zone is what the reader lives in, so that is the one used.
     */
    /**
     * Listens for the device's zone changing, once, and drops the memo when it does.
     *
     * <p>Registered lazily rather than at startup: the budget costs nothing at all until
     * somebody sets one, and this is on the path that only runs once one is set. A receiver
     * that cannot be registered leaves the memo as it is, which is the behaviour a device that
     * never changes zone has anyway.
     */
    private static void watchTheZone() {
        synchronized (LOCK) {
            android.content.Context given = Utils.getContext();
            if (given == null) return;
            // The application, not whatever was handed over. Utils.getContext() can be the main
            // activity, and it is wrapped again on every configuration change when an app
            // language is set, so keying on that identity registered a receiver per wrapper and
            // held a destroyed activity in a static field. The application is one object for
            // the life of the process, which is also how often this should register.
            android.content.Context context = given.getApplicationContext() == null
                    ? given
                    : given.getApplicationContext();
            if (context == watchedContext) return;
            try {
                android.content.BroadcastReceiver receiver =
                        new android.content.BroadcastReceiver() {
                            @Override
                            public void onReceive(
                                    android.content.Context ignored, android.content.Intent sent) {
                                forgetTheDay();
                            }
                        };
                android.content.IntentFilter filter = new android.content.IntentFilter(
                        android.content.Intent.ACTION_TIMEZONE_CHANGED);
                // A protected system broadcast is delivered to a receiver nobody else can
                // reach, and from API 33 a receiver has to say which it is.
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter,
                            android.content.Context.RECEIVER_NOT_EXPORTED);
                } else {
                    context.registerReceiver(receiver, filter);
                }
                // Only once it is registered. Assigning first meant a refusal was never
                // tried again for that context.
                watchedContext = context;
            } catch (Throwable refused) {
                Logger.printDebug(() -> "The budget could not follow the device timezone");
            }
        }
    }

    /** Drops the memo, so the next question is worked out from the zone the device is in now. */
    static void forgetTheDay() {
        synchronized (LOCK) {
            cachedResetHour = -1;
        }
    }

    static long dayOf(long now) {
        int resetHour = Settings.SESSION_BUDGET_RESET_HOUR.get();
        watchTheZone();
        synchronized (LOCK) {
            if (resetHour == cachedResetHour
                    && now >= cachedWindowStart && now < cachedWindowEnd) {
                return cachedDay;
            }
            dayComputations++;
            // The one place the zone is read, which is what makes this a miss rather than a
            // question asked several times a second.
            Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
            calendar.setTimeInMillis(now);
            if (calendar.get(Calendar.HOUR_OF_DAY) < resetHour) {
                calendar.add(Calendar.DAY_OF_YEAR, -1);
            }
            long value = calendar.get(Calendar.YEAR) * 1000L + calendar.get(Calendar.DAY_OF_YEAR);

            // How long that answer holds, so the next few thousand callbacks in the same day are
            // two comparisons. The end is a day added rather than 24 hours, because the day the
            // clocks change is 23 or 25 hours long.
            calendar.set(Calendar.HOUR_OF_DAY, resetHour);
            calendar.set(Calendar.MINUTE, 0);
            calendar.set(Calendar.SECOND, 0);
            calendar.set(Calendar.MILLISECOND, 0);
            long start = calendar.getTimeInMillis();
            calendar.add(Calendar.DAY_OF_YEAR, 1);
            long end = calendar.getTimeInMillis();
            if (now >= start && now < end) {
                cachedDay = value;
                cachedWindowStart = start;
                cachedWindowEnd = end;
                cachedResetHour = resetHour;
            } else {
                // An hour that does not exist on the day the clocks go forward lands outside its
                // own window. Better to work it out again than to answer from a window that does
                // not contain the moment it was built for.
                cachedResetHour = -1;
            }
            return value;
        }
    }

    /**
     * The next moment the reset hour comes round, which is when the day containing {@code now}
     * ends. Worked out from scratch rather than from the window cache, because a locked day has
     * to name its end correctly on the day the clocks change too.
     */
    static long dayEndAfter(long now) {
        int resetHour = Settings.SESSION_BUDGET_RESET_HOUR.get();
        Calendar calendar = Calendar.getInstance(TimeZone.getDefault());
        calendar.setTimeInMillis(now);
        calendar.set(Calendar.HOUR_OF_DAY, resetHour);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        if (calendar.getTimeInMillis() <= now) calendar.add(Calendar.DAY_OF_YEAR, 1);
        return calendar.getTimeInMillis();
    }

    /**
     * Starts a new day when one has arrived, and only then.
     *
     * <p>The day is computed from the reset hour, which the reader can change, and from the
     * device's timezone, which travel changes. Either can make the same moment fall on an
     * earlier day than the one already recorded. Treating that as a new day would hand back a
     * budget that had been spent and lift a hold that was running, which is a one-tap way out of
     * the whole feature. So the day only ever moves forward.
     */
    private static void rollOver(long now) {
        // Changes that loosened the budget wait for the day to start over, so they land here,
        // before the new day's budget is read.
        BudgetChanges.applyDue(now);
        long today = dayOf(now);
        if (today <= day) return;
        // The day only ever moving forward is not enough on its own for a locked day. Moving
        // the device's timezone forward makes the day counter jump, which cleared the lock,
        // the hold and the counts together: two taps in the device settings and sixteen hours
        // of hold were gone. A locked day ends at the moment it committed to, which is an
        // instant rather than a day number and so is the same in every timezone.
        if (lockedToday && lockUntilMs > now) return;
        day = today;
        videos = 0;
        watchedMs = 0;
        writtenWatchedMs = 0;
        lockUntilMs = 0;
        lockedToday = false;
        passesUsed = 0;
        noticeMarkMs = 0;
        undoAvailable = false;
        lastCountedId = null;
        noticeShown = false;
        lastTickMs = 0;
        save();
    }

    // ------------------------------------------------------------------------- staying around

    private static void load() {
        if (loaded) return;
        String stored = Settings.SESSION_BUDGET_STATE.get();
        long currentDay = dayOf(clock.now());
        long nextDay = currentDay;
        int nextVideos = 0;
        long nextWatchedMs = 0;
        long nextLockUntilMs = 0;
        boolean nextNoticeShown = false;
        boolean nextLockedToday = false;
        int nextPassesUsed = 0;
        long nextNoticeMarkMs = 0;
        long nextNoticesShown = 0;
        boolean rewrite = false;

        if (!stored.isEmpty()) {
            try {
                String[] parts = stored.split("\\|", -1);
                if (parts.length < 5 || parts.length > 9) {
                    throw new IllegalArgumentException("unexpected field count");
                }

                long storedDay = Long.parseLong(parts[0]);
                int storedVideos = Integer.parseInt(parts[1]);
                long storedWatchedMs = Long.parseLong(parts[2]);
                long storedLockUntilMs = Long.parseLong(parts[3]);
                boolean storedNoticeShown = parseFlag(parts[4]);
                // Each later field was added at the end, so records with five through nine
                // fields are historical formats rather than truncated current records.
                boolean storedLockedToday = parts.length >= 6 && parseFlag(parts[5]);
                int storedPassesUsed = parts.length >= 7
                        ? Integer.parseInt(parts[6]) : 0;
                long storedNoticeMarkMs = parts.length >= 8
                        ? Long.parseLong(parts[7]) : 0;
                long storedNoticesShown = parts.length >= 9
                        ? Long.parseLong(parts[8]) : 0;

                int cleanVideos = Math.max(0, storedVideos);
                long cleanWatchedMs = Math.max(0, storedWatchedMs);
                long cleanLockUntilMs = Math.max(0, storedLockUntilMs);
                int cleanPassesUsed = Math.max(0, storedPassesUsed);
                long cleanNoticeMarkMs = Math.min(cleanWatchedMs,
                        Math.max(0, storedNoticeMarkMs));
                long cleanNoticesShown = Math.max(0, storedNoticesShown);
                rewrite = cleanVideos != storedVideos
                        || cleanWatchedMs != storedWatchedMs
                        || cleanLockUntilMs != storedLockUntilMs
                        || cleanPassesUsed != storedPassesUsed
                        || cleanNoticeMarkMs != storedNoticeMarkMs
                        || cleanNoticesShown != storedNoticesShown;

                // A record from a day that has not arrived yet belongs to a clock that has since
                // gone backwards, and it is still the reader's own day. A locked day whose hold
                // has not run out is kept as well, the way rollOver keeps it: a timezone moved
                // forward makes the day number jump without the committed instant arriving, and
                // a restart after the move handed the rest of the day back.
                if (storedDay >= currentDay
                        || (storedLockedToday && cleanLockUntilMs > clock.now())) {
                    nextDay = storedDay;
                    nextVideos = cleanVideos;
                    nextWatchedMs = cleanWatchedMs;
                    nextLockUntilMs = cleanLockUntilMs;
                    nextNoticeShown = storedNoticeShown;
                    nextLockedToday = storedLockedToday;
                    nextPassesUsed = cleanPassesUsed;
                    nextNoticeMarkMs = cleanNoticeMarkMs;
                }
                // Read whether or not the record is today's, because it is not a count of a
                // day: it is which of the three wordings comes next, and a reader who gets one
                // reminder a day would otherwise read the same sentence every day.
                nextNoticesShown = cleanNoticesShown;
            } catch (RuntimeException malformed) {
                Logger.printDebug(() -> "Discarded an unreadable session budget record");
                rewrite = true;
            }
        }

        // Parse and validate the whole record before touching shared state. The volatile loaded
        // write below publishes this complete snapshot to the lock-free isLocked() fast path.
        day = nextDay;
        videos = nextVideos;
        watchedMs = nextWatchedMs;
        writtenWatchedMs = nextWatchedMs;
        lockUntilMs = nextLockUntilMs;
        noticeShown = nextNoticeShown;
        lockedToday = nextLockedToday;
        passesUsed = nextPassesUsed;
        noticeMarkMs = nextNoticeMarkMs;
        noticesShown = nextNoticesShown;
        if (rewrite) save();
        // Published last, on purpose. isLocked() reads this without the monitor, so setting it
        // first left a window where another thread saw "loaded, no hold" while the hold it was
        // about to read was still in the record, and auto advance started during a hold.
        loaded = true;
    }

    /** Reads an exact persisted boolean instead of silently treating damaged data as false. */
    private static boolean parseFlag(String value) {
        if ("0".equals(value)) return false;
        if ("1".equals(value)) return true;
        throw new IllegalArgumentException("invalid flag");
    }

    /** Hands the record to the writer thread, because the settings store commits synchronously. */
    private static void save() {
        writtenWatchedMs = watchedMs;
        final long generation = writeGeneration;
        final String record = day + "|" + videos + "|" + watchedMs + "|"
                + lockUntilMs + "|" + (noticeShown ? "1" : "0")
                + "|" + (lockedToday ? "1" : "0")
                + "|" + passesUsed
                + "|" + noticeMarkMs + "|" + noticesShown;
        try {
            WRITER.execute(() -> persistRecord(record, generation));
        } catch (RejectedExecutionException stopped) {
            persistRecord(record, generation);
        }
    }

    private static void persistRecord(String record, long generation) {
        synchronized (WRITE_FENCE) {
            if (generation != writeGeneration) return;
            Settings.SESSION_BUDGET_STATE.save(record);
        }
    }

    /** Now, on the budget's own clock, which a test can replace. */
    public static long now() {
        return clock.now();
    }

    // ------------------------------------------------------------------------------- for tests

    static void setClockForTests(Clock replacement) {
        synchronized (LOCK) {
            clock = replacement == null ? System::currentTimeMillis : replacement;
        }
    }

    /** Waits for the writer to catch up, so a test can read the record back. */
    static void awaitWritesForTests() throws Exception {
        WRITER.submit(() -> null).get();
    }

    /** How many times a Calendar was actually built, so a test can prove the memo holds. */
    static int dayComputationsForTests() {
        synchronized (LOCK) {
            return dayComputations;
        }
    }

    /** How many hold checks got past the fast path and took the monitor. */
    static int lockChecksUnderTheMonitorForTests() {
        synchronized (LOCK) {
            return lockChecksUnderTheMonitor;
        }
    }

    static void resetForTests() {
        synchronized (LOCK) {
            writeGeneration++;
            cachedResetHour = -1;
            cachedWindowStart = 0;
            cachedWindowEnd = 0;
            cachedDay = 0;
            dayComputations = 0;
            lockChecksUnderTheMonitor = 0;
            loaded = false;
            day = 0;
            videos = 0;
            watchedMs = 0;
            writtenWatchedMs = 0;
            lockUntilMs = 0;
            lockedToday = false;
            passesUsed = 0;
            noticeMarkMs = 0;
            noticesShown = 0;
            undoAvailable = false;
            lastTickMs = 0;
            lastCountedId = null;
            noticeShown = false;
        }
        // If a write had already crossed the generation check, wait until it has finished before
        // the test clears preferences. Anything still queued carries the old generation and exits.
        synchronized (WRITE_FENCE) {
            // Fence only.
        }
    }
}
