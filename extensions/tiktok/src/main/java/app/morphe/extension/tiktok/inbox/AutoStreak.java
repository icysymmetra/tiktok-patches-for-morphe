/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.inbox;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.SystemClock;
import android.text.TextUtils;
import android.text.format.DateFormat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Keeps message streaks going: one message per selected chat every day at a set time.
 *
 * <p>An alarm wakes {@link AutoStreakReceiver}, which the patch adds to the manifest switched
 * off and which is only switched on while this is. The receiver starts TikTok's messaging when
 * the process was not running and hands the message to TikTok's notification quick reply, the
 * same path a reply typed into a notification takes (see {@link StreakMessenger}). Opening
 * TikTok sends a message the alarm missed, and puts the alarm back after a reboot, an update or
 * a force stop that cleared it.
 *
 * <p>Nothing goes out because a setting changed. Turning the switch on, or moving the time, after
 * today's time has passed makes tomorrow the first day; Send it now is there for today.
 */
public final class AutoStreak {
    static final String ACTION_SEND = "app.morphe.extension.tiktok.inbox.AUTO_STREAK";
    /** Between one failed try and the next. */
    static final long RETRY_MILLIS = 15 * 60_000L;
    /** Tries an alarm makes in one day. Opening TikTok tries again past it. */
    static final int TRIES_PER_DAY = 4;
    /** An alarm's whole send, inside the minute Android gives a receiver that holds its broadcast. */
    static final long ALARM_BUDGET_MILLIS = 45_000L;
    /** A send from an open app or the settings row, which nothing is timing. */
    static final long OPEN_BUDGET_MILLIS = 60_000L;
    /** How early an alarm may land and still count as on time. */
    static final long EARLY_MILLIS = 60_000L;
    /**
     * How long after TikTok opens a message waits. The launch starts messaging only once its
     * first screen has settled, and the quick reply drops a message handed over before then.
     */
    static final long LAUNCH_SETTLE_MILLIS = 30_000L;
    private static final int REQUEST_CODE = 0x5154;

    // SENT is read only from the old journal, which used it for a queued dispatch.
    enum Result { NONE, SENT, DISPATCHING, DISPATCHED, UNCONFIRMED, PARTIAL,
        NO_RECIPIENT, SIGNED_OUT, NOT_FOUND, OTHER_ACCOUNT, NOT_READY, FAILED }

    private static final AtomicBoolean sending = new AtomicBoolean();

    /** So a test can fix the clock the day and the alarm are worked out from. */
    static volatile Long nowForTests;

    /**
     * Whether TikTok's main screen has been created in this process, whose launch starts
     * messaging. A process an alarm started has had no launch and starts it here.
     */
    static volatile boolean openedHere;
    /** When that was, in {@link SystemClock#elapsedRealtime}. */
    private static volatile long openedAt;
    /** Whether this process has set messaging up and started it itself. */
    private static boolean startedHere;

    private AutoStreak() {}

    /** What {@link Settings#AUTO_STREAK_STATE} holds, one field per line of a bar-separated row. */
    static final class State {
        /** Legacy field name: the last dispatch day, not proof of delivery. */
        String sentDay = "";
        /** The day {@link #tries} counts. */
        String tryDay = "";
        int tries;
        Result result = Result.NONE;
        long resultAt;
        /** When the switch went on or the time moved. A time before it waits for tomorrow. */
        long activeSince;
        /** The uid of the account the recipient was entered on, or empty until one is seen. */
        String owner = "";
        /**
         * The handles {@link #sentDay}'s dispatches were reserved for, lowercase, comma separated. Empty on a
         * row written before this was kept, which reads as whoever is entered now.
         */
        String sentTo = "";
        /** Account and handle to its own attempts and resolved conversation. */
        final Map<String, RecipientState> recipients = new LinkedHashMap<>();

        static State parse(String text) {
            State state = new State();
            if (text == null || text.isEmpty()) return state;
            String[] parts = text.split("\\|", 10);
            if (parts.length < 8 || !("1".equals(parts[0]) || "2".equals(parts[0]))) return state;
            try {
                state.sentDay = parts[1];
                state.tryDay = parts[2];
                state.tries = Integer.parseInt(parts[3]);
                state.result = readResult(parts[4]);
                state.resultAt = Long.parseLong(parts[5]);
                state.activeSince = Long.parseLong(parts[6]);
                state.owner = parts[7];
                if (parts.length > 8) state.sentTo = parts[8];
                if ("2".equals(parts[0])) {
                    if (parts.length != 10) return new State();
                    JSONObject records = new JSONObject(parts[9]);
                    for (java.util.Iterator<String> keys = records.keys(); keys.hasNext();) {
                        String key = keys.next();
                        state.recipients.put(key, RecipientState.parse(records.getJSONArray(key)));
                    }
                }
            } catch (RuntimeException | JSONException unreadable) {
                // A row this build cannot read starts over rather than guessing at a field.
                return new State();
            }
            return state;
        }

        String format() {
            JSONObject records = new JSONObject();
            try {
                for (Map.Entry<String, RecipientState> entry : recipients.entrySet()) {
                    records.put(entry.getKey(), entry.getValue().format());
                }
            } catch (JSONException impossible) {
                throw new IllegalStateException("Could not encode streak state", impossible);
            }
            return "2|" + sentDay + "|" + tryDay + "|" + tries + "|" + result.name() + "|"
                    + resultAt + "|" + activeSince + "|" + owner + "|" + sentTo + "|" + records;
        }

        int triesOn(String day) {
            return day.equals(tryDay) ? tries : 0;
        }

        /** Whether a dispatch was reserved for {@code handle} on {@code day}. */
        boolean sentOn(String day, String handle) {
            if (!day.equals(sentDay)) return false;
            if (sentTo.isEmpty()) return true;
            return Arrays.asList(sentTo.split(",")).contains(handle.toLowerCase(Locale.ROOT));
        }

        /** Keeps the legacy handle ledger alongside the per-conversation journal. */
        void markSent(String day, String handle) {
            if (!day.equals(sentDay)) {
                sentDay = day;
                sentTo = "";
            }
            String key = handle.toLowerCase(Locale.ROOT);
            if (!Arrays.asList(sentTo.split(",")).contains(key)) {
                sentTo = sentTo.isEmpty() ? key : sentTo + "," + key;
            }
        }

        RecipientState recipient(String handle) {
            return recipients.get(owner + ":" + handle.toLowerCase(Locale.ROOT));
        }

        RecipientState record(String handle) {
            return record(owner, handle);
        }

        RecipientState record(String account, String handle) {
            String key = account + ":" + handle.toLowerCase(Locale.ROOT);
            RecipientState record = recipients.get(key);
            if (record == null) {
                record = new RecipientState();
                record.activeSince = activeSince;
                if (account.equals(owner) && sentOn(day(now()), handle)) {
                    record.dispatchDay = sentDay;
                    record.result = Result.DISPATCHED;
                    record.resultAt = resultAt;
                }
                recipients.put(key, record);
            }
            return record;
        }

        RecipientState dispatched(String day, String conversation) {
            for (Map.Entry<String, RecipientState> entry : recipients.entrySet()) {
                RecipientState record = entry.getValue();
                if (entry.getKey().startsWith(owner + ":") && day.equals(record.dispatchDay)
                        && conversation.equals(record.conversation)) return record;
            }
            return null;
        }
    }

    static final class RecipientState {
        long activeSince;
        String conversation = "";
        /** A durable reservation, not proof the message arrived. */
        String dispatchDay = "";
        String tryDay = "";
        int tries;
        Result result = Result.NONE;
        long resultAt;

        static RecipientState parse(JSONArray row) throws JSONException {
            RecipientState record = new RecipientState();
            record.activeSince = row.getLong(0);
            record.conversation = row.getString(1);
            record.dispatchDay = row.getString(2);
            record.tryDay = row.getString(3);
            record.tries = row.getInt(4);
            record.result = readResult(row.getString(5));
            record.resultAt = row.getLong(6);
            return record;
        }

        JSONArray format() {
            return new JSONArray().put(activeSince).put(conversation).put(dispatchDay).put(tryDay)
                    .put(tries).put(result.name()).put(resultAt);
        }

        int triesOn(String day) {
            return day.equals(tryDay) ? tries : 0;
        }

        void note(String day, Result outcome, boolean count) {
            if (count) {
                if (!day.equals(tryDay)) {
                    tryDay = day;
                    tries = 0;
                }
                tries++;
            }
            result = outcome;
            resultAt = now();
        }
    }

    static long now() {
        Long forTests = nowForTests;
        return forTests != null ? forTests : System.currentTimeMillis();
    }

    private static Result readResult(String text) {
        Result result = Result.valueOf(text);
        return result == Result.SENT ? Result.DISPATCHED : result;
    }

    static synchronized State state() {
        return State.parse(Settings.AUTO_STREAK_STATE.get());
    }

    private static synchronized boolean save(State state) {
        return Settings.AUTO_STREAK_STATE.save(state.format());
    }

    /** The day {@code millis} falls on, in the phone's time zone. */
    static String day(long millis) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(millis);
        return String.format(Locale.US, "%04d-%02d-%02d", calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.DAY_OF_MONTH));
    }

    /** {@code minute} after midnight on the day {@code now} falls on, {@code days} later. */
    static long at(long now, int minute, int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTimeInMillis(now);
        calendar.add(Calendar.DAY_OF_YEAR, days);
        calendar.set(Calendar.HOUR_OF_DAY, minute / 60);
        calendar.set(Calendar.MINUTE, minute % 60);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);
        return calendar.getTimeInMillis();
    }

    /** Whether today's message still has to go and its time has come. */
    static boolean isDue(State state, long now, int minute) {
        long today = at(now, minute, 0);
        return now >= today - EARLY_MILLIS && counts(state, now, today);
    }

    /**
     * Whether today's time is one this setup covers and the person entered now hasn't had
     * today's message.
     */
    private static boolean counts(State state, long now, long today) {
        if (today < state.activeSince) return false;
        List<String> handles = recipients();
        if (handles.isEmpty()) return !state.sentOn(day(now), "");
        for (String handle : handles) {
            RecipientState record = state.recipient(handle);
            if (!wasDispatched(state, day(now), handle)
                    && (record == null || (today >= record.activeSince && !day(now).equals(record.dispatchDay)))) {
                return true;
            }
        }
        return false;
    }

    private static boolean wasDispatched(State state, String day, String handle) {
        if (!state.sentOn(day, handle)) return false;
        // The original journal had one recipient and no handle. It did not message a list.
        return !state.sentTo.isEmpty()
                || handle.equalsIgnoreCase(StreakMessenger.handleOf(Settings.AUTO_STREAK_RECIPIENT.get()));
    }

    /** The handle entered now, or empty. */
    private static List<String> recipients() {
        return StreakMessenger.handlesOf(Settings.AUTO_STREAK_RECIPIENT.get());
    }

    /**
     * When the alarm should next go off: today's time while it is ahead, a retry while today's
     * message is late and tries are left, and otherwise tomorrow's time.
     */
    static long nextAlarm(State state, long now, int minute) {
        long today = at(now, minute, 0);
        if (counts(state, now, today)) {
            if (now < today - EARLY_MILLIS) return today;
            List<String> handles = recipients();
            int runs = state.triesOn(day(now));
            if (handles.isEmpty() && runs < TRIES_PER_DAY) return now + RETRY_MILLIS;
            // A run that runs out of time before its first recipient counts no recipient's try,
            // so the day's runs are capped too. That cap still leaves each recipient its own tries.
            if (runs >= TRIES_PER_DAY * handles.size()) return at(now, minute, 1);
            for (String handle : handles) {
                RecipientState record = state.recipient(handle);
                if (!wasDispatched(state, day(now), handle) && (record == null
                        || (!day(now).equals(record.dispatchDay) && today >= record.activeSince
                        && record.triesOn(day(now)) < TRIES_PER_DAY))) return now + RETRY_MILLIS;
            }
        }
        return at(now, minute, 1);
    }

    // ---------------------------------------------------------------------------------------
    // Entry points.

    /** From TikTok's main activity: puts the alarm back, and sends a message the alarm missed. */
    public static void onAppOpened(Activity activity) {
        if (!openedHere) {
            openedHere = true;
            openedAt = SystemClock.elapsedRealtime();
        }
        final Context context = activity.getApplicationContext();
        LogBufferManager.registerReportSection(Report.INSTANCE);
        onSendThread(() -> {
            try {
                reconcile(context);
                if (Settings.AUTO_STREAK.get()
                        && isDue(state(), now(), Settings.AUTO_STREAK_MINUTE.get())) {
                    run(context, OPEN_BUDGET_MILLIS, false);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Auto streak: app open failed", ex);
            }
        });
    }

    /** From {@link AutoStreakReceiver}: an alarm, a reboot, an update or a clock change. */
    static void onReceive(Context context, Intent intent, BroadcastReceiver.PendingResult pending) {
        final Context app = context.getApplicationContext();
        onSendThread(() -> {
            try {
                Logger.printInfo(() -> "Auto streak: woken by " + intent.getAction());
                if (!Settings.AUTO_STREAK.get()) {
                    reconcile(app);
                    return;
                }
                if (isDue(state(), now(), Settings.AUTO_STREAK_MINUTE.get())) {
                    run(app, ALARM_BUDGET_MILLIS, true);
                } else {
                    reconcile(app);
                }
            } catch (Exception ex) {
                Logger.printException(() -> "Auto streak: alarm failed", ex);
            } finally {
                if (pending != null) pending.finish();
            }
        });
    }

    /**
     * From the settings rows, once a change has been saved. {@code fresh} is true when the
     * switch went on or the time moved, which makes a time already past today wait for tomorrow.
     */
    public static void settingsChanged(Context context, boolean fresh) {
        final Context app = context.getApplicationContext();
        LogBufferManager.registerReportSection(Report.INSTANCE);
        if (fresh) {
            synchronized (AutoStreak.class) {
                State state = state();
                state.activeSince = now();
                save(state);
            }
        }
        Utils.runOnBackgroundThread(() -> reconcile(app));
    }

    /**
     * From the recipient row, once saved, with what it held before: the account signed in now
     * is the one that sends.
     */
    public static void recipientChanged(Context context, String before) {
        synchronized (AutoStreak.class) {
            State state = state();
            migrateLegacyRecipient(state, before);
            String self = SignedInUser.id();
            boolean differentAccount = self != null && !self.equals(state.owner);
            if (differentAccount) {
                // Keep a legacy dispatch under its original account before clearing its ledger.
                for (String handle : state.sentTo.split(",")) {
                    if (!handle.isEmpty()) state.record(handle);
                }
                state.owner = self;
                state.sentDay = "";
                state.sentTo = "";
            }
            List<String> previous = StreakMessenger.handlesOf(before);
            for (String handle : recipients()) {
                RecipientState record = state.record(handle);
                // Adding someone after today's time must not postpone another person's retry.
                if (differentAccount || !previous.contains(handle)) record.activeSince = now();
            }
            save(state);
        }
        settingsChanged(context, false);
    }

    private static void migrateLegacyRecipient(State state, String typed) {
        if (state.sentTo.isEmpty() && !state.sentDay.isEmpty()) {
            state.sentTo = StreakMessenger.handleOf(typed).toLowerCase(Locale.ROOT);
        }
    }

    /** Callback for {@link #sendNow}, on the main thread. */
    public interface Done {
        void sent(String toast);
    }

    /** From the Send it now row: today's message, straight away. */
    public static void sendNow(Context context, Done done) {
        final Context app = context.getApplicationContext();
        if (!sending.compareAndSet(false, true)) {
            done.sent(L10n.t(app, "A message is already on its way"));
            return;
        }
        onSendThread(() -> {
            Result result;
            try {
                result = attempt(app, OPEN_BUDGET_MILLIS, false, true);
                if (result != Result.NONE) finish(result);
                reconcile(app);
            } finally {
                sending.set(false);
            }
            final String toast = attemptLine(app, result);
            Utils.runOnMainThread(() -> done.sent(toast));
        });
    }

    // ---------------------------------------------------------------------------------------

    /**
     * Runs a send on a thread of its own. A send can wait most of a minute for messaging, and the
     * shared background pool's three threads carry every other task Hushfeed has.
     */
    private static void onSendThread(Runnable task) {
        Thread thread = new Thread(task, "hushfeed-auto-streak");
        thread.setDaemon(true);
        thread.start();
    }

    /** Arms or clears the alarm and the receiver to match the switch. */
    static void reconcile(Context context) {
        boolean on = Settings.AUTO_STREAK.get();
        setReceiverEnabled(context, on);
        AlarmManager alarms = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        if (alarms == null) return;
        if (!on) {
            alarms.cancel(alarmIntent(context));
            return;
        }
        State state;
        synchronized (AutoStreak.class) {
            state = state();
            if (state.activeSince == 0) {
                // Turned on by a restore rather than by the switch: the same as turning it on now.
                state.activeSince = now();
                save(state);
            }
        }
        long next = nextAlarm(state, now(), Settings.AUTO_STREAK_MINUTE.get());
        schedule(alarms, context, next);
        Logger.printInfo(() -> "Auto streak: next alarm " + new Date(next));
    }

    private static void run(Context context, long budget, boolean fromAlarm) {
        if (!sending.compareAndSet(false, true)) return;
        try {
            // An alarm counts its tries; opening the app or tapping the row is a try of its own.
            Result result = attempt(context, budget, fromAlarm, false);
            // NONE is a try called off on the way, which neither went nor counts.
            if (result == Result.DISPATCHED || result == Result.PARTIAL || (fromAlarm && result != Result.NONE)) finish(result);
            else if (result != Result.NONE) note(result);
            reconcile(context);
        } finally {
            sending.set(false);
        }
    }

    /** Records how a try ended and counts it towards today's. */
    private static synchronized void finish(Result result) {
        long now = now();
        String today = day(now);
        State state = state();
        if (!today.equals(state.tryDay)) {
            state.tryDay = today;
            state.tries = 0;
        }
        state.tries++;
        state.result = result;
        state.resultAt = now;
        save(state);
    }

    private static synchronized void note(Result result) {
        State state = state();
        state.result = result;
        state.resultAt = now();
        save(state);
    }

    /**
     * One try at today's message. {@code manual} is Send it now, which goes whether or not the
     * switch is on.
     */
    static Result attempt(Context context, long budget, boolean fromAlarm, boolean manual) {
        List<String> handles = recipients();
        if (handles.isEmpty()) return Result.NO_RECIPIENT;
        long deadline = SystemClock.elapsedRealtime() + budget;
        boolean count = fromAlarm || manual;
        try {
            Object service = StreakMessenger.startMessaging();
            boolean cold = !openedHere;
            if (cold && !startedHere) {
                startedHere = true;
                StreakMessenger.initializeMessaging(service);
                StreakMessenger.kickMessaging(context);
            }
            String self = StreakMessenger.awaitSignedIn(deadline);
            if (self == null) return failPending(handles, Result.SIGNED_OUT, count);
            synchronized (AutoStreak.class) {
                State state = state();
                migrateLegacyRecipient(state, Settings.AUTO_STREAK_RECIPIENT.get());
                if (state.owner.isEmpty()) {
                    for (String handle : handles) {
                        RecipientState unbound = state.recipients.remove(":" + handle);
                        if (unbound != null) state.recipients.put(self + ":" + handle, unbound);
                    }
                    state.owner = self;
                } else if (!state.owner.equals(self)) {
                    return failPending(handles, Result.OTHER_ACCOUNT, count);
                }
                for (String handle : handles) state.record(handle);
                if (!save(state)) return Result.FAILED;
            }
            boolean ready = false;
            boolean handed = false;
            boolean uncertainDispatch = false;
            Result failure = Result.NONE;
            String today = day(now());
            for (String handle : handles) {
                State latest = state();
                RecipientState record = latest.record(self, handle);
                if (wasDispatched(latest, today, handle) || today.equals(record.dispatchDay)
                        || (!manual && at(now(), Settings.AUTO_STREAK_MINUTE.get(), 0)
                        < Math.max(latest.activeSince, record.activeSince))
                        || (fromAlarm && record.triesOn(today) >= TRIES_PER_DAY)) continue;
                if (SystemClock.elapsedRealtime() + StreakMessenger.settleMillis >= deadline) {
                    failure = Result.NOT_READY;
                    break; // Unvisited recipients keep their own retry allowance.
                }
                StreakMessenger.Contact peer = StreakMessenger.findContact(context, self, handle);
                if (peer == null) {
                    recordResult(self, handle, Result.NOT_FOUND, count);
                    if (failure == Result.NONE) failure = Result.NOT_FOUND;
                    continue;
                }
                if (!ready) {
                    if (!StreakMessenger.awaitReady(context, deadline)) {
                        recordResult(self, handle, Result.NOT_READY, count);
                        failure = Result.NOT_READY;
                        break;
                    }
                    long wait = cold ? StreakMessenger.warmUpMillis
                            : openedAt + LAUNCH_SETTLE_MILLIS - SystemClock.elapsedRealtime();
                    long left = deadline - SystemClock.elapsedRealtime() - StreakMessenger.settleMillis;
                    if (wait >= left) {
                        recordResult(self, handle, Result.NOT_READY, count);
                        failure = Result.NOT_READY;
                        break;
                    }
                    SystemClock.sleep(Math.max(0, wait));
                    ready = true;
                }
                String conversation = StreakMessenger.conversationId(self, peer.uid);
                try {
                    long left = deadline - SystemClock.elapsedRealtime() - StreakMessenger.settleMillis;
                    StreakMessenger.deliver(context, conversation, message(),
                            () -> beginDispatch(self, handle, conversation, today, count, manual, deadline),
                            Math.min(StreakMessenger.handOffMillis, left));
                    recordResult(self, handle, Result.DISPATCHED, false);
                    handed = true;
                    Logger.printInfo(() -> "Auto streak: handed to TikTok for @" + handle);
                } catch (Cancelled cancelled) {
                    if (cancelled.result != Result.NONE) {
                        recordResult(self, handle, cancelled.result, count && !cancelled.counted);
                        failure = cancelled.result;
                    }
                    if (cancelled.result == Result.OTHER_ACCOUNT || cancelled.result == Result.SIGNED_OUT) break;
                } catch (StreakMessenger.UnconfirmedDispatch uncertain) {
                    uncertainDispatch = true;
                    recordResult(self, handle, Result.UNCONFIRMED, false);
                    failure = Result.UNCONFIRMED;
                    Logger.printException(() -> "Auto streak: native dispatch outcome is unknown", uncertain);
                } catch (Exception rejected) {
                    recordResult(self, handle, Result.FAILED, count);
                    if (failure == Result.NONE) failure = Result.FAILED;
                    Logger.printException(() -> "Auto streak: recipient dispatch failed", rejected);
                }
            }
            if ((handed || uncertainDispatch) && (fromAlarm || cold)) SystemClock.sleep(StreakMessenger.settleMillis);
            if (handed && failure != Result.NONE) return Result.PARTIAL;
            return handed ? Result.DISPATCHED : failure;
        } catch (Exception ex) {
            Logger.printException(() -> "Auto streak: send failed", ex);
            return failPending(handles, Result.FAILED, count);
        }
    }

    private static final class Cancelled extends Exception {
        final Result result;
        final boolean counted;

        Cancelled(Result result) { this(result, false); }
        Cancelled(Result result, boolean counted) {
            this.result = result;
            this.counted = counted;
        }
    }

    /** Runs on the main thread, after any queued account change and immediately before native code. */
    private static synchronized void beginDispatch(String self, String handle, String conversation,
                                                   String today, boolean count, boolean manual,
                                                   long deadline) throws Exception {
        if ((!manual && !Settings.AUTO_STREAK.get()) || !recipients().contains(handle)) {
            throw new Cancelled(Result.NONE);
        }
        String current = SignedInUser.id();
        if (current == null) throw new Cancelled(Result.SIGNED_OUT);
        State state = state();
        if (!self.equals(current) || !self.equals(state.owner)) throw new Cancelled(Result.OTHER_ACCOUNT);
        if (SystemClock.elapsedRealtime() >= deadline) throw new Cancelled(Result.NOT_READY);
        RecipientState record = state.record(handle);
        if (!today.equals(day(now())) || (!manual && at(now(), Settings.AUTO_STREAK_MINUTE.get(), 0)
                < Math.max(state.activeSince, record.activeSince))) throw new Cancelled(Result.NONE);
        RecipientState previous = state.dispatched(today, conversation);
        if (previous != null) {
            record.conversation = conversation;
            record.dispatchDay = previous.dispatchDay;
            record.result = previous.result;
            record.resultAt = previous.resultAt;
            save(state);
            throw new Cancelled(Result.NONE);
        }
        if (wasDispatched(state, today, handle) || today.equals(record.dispatchDay)) throw new Cancelled(Result.NONE);
        record.conversation = conversation;
        record.dispatchDay = today;
        record.note(today, Result.DISPATCHING, count);
        state.markSent(today, handle);
        // Setting.save commits synchronously. If storage fails, nothing reaches the receiver.
        if (!save(state)) throw new IllegalStateException("Could not reserve the streak dispatch");
        // Committing can take time too. A change here is a known cancellation, not a delivery.
        current = SignedInUser.id();
        if (!self.equals(current)) {
            record.dispatchDay = "";
            List<String> reserved = new ArrayList<>(Arrays.asList(state.sentTo.split(",")));
            reserved.remove(handle);
            state.sentTo = TextUtils.join(",", reserved);
            if (state.sentTo.isEmpty()) state.sentDay = "";
            save(state); // Failure retains the durable reservation and still sends nothing.
            throw new Cancelled(current == null ? Result.SIGNED_OUT : Result.OTHER_ACCOUNT, count);
        }
    }

    private static synchronized void recordResult(String account, String handle, Result result, boolean count) {
        State state = state();
        state.record(account, handle).note(day(now()), result, count);
        save(state);
    }

    private static synchronized Result failPending(List<String> handles, Result result, boolean count) {
        State state = state();
        String today = day(now());
        for (String handle : handles) {
            RecipientState record = state.record(handle);
            if (!wasDispatched(state, today, handle) && !today.equals(record.dispatchDay)) {
                record.note(today, result, count);
            }
        }
        save(state);
        return result;
    }

    static String message() {
        String text = Settings.AUTO_STREAK_MESSAGE.get();
        return text == null || text.trim().isEmpty()
                ? Settings.AUTO_STREAK_MESSAGE.defaultValue : text.trim();
    }

    private static PendingIntent alarmIntent(Context context) {
        Intent intent = new Intent(ACTION_SEND).setClass(context, AutoStreakReceiver.class);
        return PendingIntent.getBroadcast(context, REQUEST_CODE, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    private static void schedule(AlarmManager alarms, Context context, long at) {
        PendingIntent pending = alarmIntent(context);
        // The patch asks for exact alarms. Where Android still refuses them the alarm comes a
        // little late rather than not at all.
        if (exactAllowed(alarms)) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        }
    }

    private static boolean exactAllowed(AlarmManager alarms) {
        return Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms();
    }

    private static void setReceiverEnabled(Context context, boolean on) {
        try {
            PackageManager packages = context.getPackageManager();
            ComponentName receiver = new ComponentName(context, AutoStreakReceiver.class);
            int wanted = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
            if (packages.getComponentEnabledSetting(receiver) != wanted) {
                packages.setComponentEnabledSetting(receiver, wanted, PackageManager.DONT_KILL_APP);
            }
        } catch (IllegalArgumentException | SecurityException missing) {
            // A bundle without the patch has no receiver to switch.
            Logger.printInfo(() -> "Auto streak: no receiver in the manifest");
        }
    }

    /**
     * The streak in the diagnostic export: how the last try went and when the next is, which a
     * report of a missed day needs and the settings row only shows on the phone. It never names
     * the recipient.
     */
    static final class Report implements LogBufferManager.ReportSection {
        static final Report INSTANCE = new Report();

        private Report() {}

        @Override
        public String title() {
            return "AUTO STREAK";
        }

        @Override
        public List<String> lines() {
            List<String> lines = new ArrayList<>();
            boolean on = Settings.AUTO_STREAK.get();
            if (!on && Settings.AUTO_STREAK_STATE.get().isEmpty()) return lines;
            State state = state();
            long now = now();
            int minute = Settings.AUTO_STREAK_MINUTE.get();
            lines.add("On: " + (on ? "yes" : "no"));
            lines.add("Recipients entered: " + recipients().size());
            String self = SignedInUser.id();
            lines.add("Account: " + (state.owner.isEmpty() ? "not seen yet"
                    : self == null ? "signed out" : state.owner.equals(self) ? "the one it was set up on"
                    : "a different one"));
            lines.add(String.format(Locale.US, "Time: %02d:%02d", minute / 60, minute % 60));
            lines.add("Last dispatch reserved: " + (state.sentDay.isEmpty() ? "never" : state.sentDay));
            lines.add("Last try: " + state.result.name()
                    + (state.resultAt == 0 ? "" : " at " + stamp(state.resultAt)));
            lines.add("Alarm tries today: " + state.triesOn(day(now)));
            List<String> handles = recipients();
            for (int index = 0; index < handles.size(); index++) {
                RecipientState record = state.recipient(handles.get(index));
                lines.add("Recipient " + (index + 1) + ": " + (record == null ? "not attempted"
                        : record.result + ", attempts today=" + record.triesOn(day(now))
                        + ", dispatch reserved today=" + day(now).equals(record.dispatchDay)));
            }
            lines.add("Delivery: not confirmed by the quick-reply dispatch");
            if (on) lines.add("Next alarm: " + stamp(nextAlarm(state, now, minute)));
            AlarmManager alarms = (AlarmManager) Utils.getContext().getSystemService(Context.ALARM_SERVICE);
            if (alarms != null) {
                lines.add("Exact alarms: " + (exactAllowed(alarms) ? "allowed" : "refused, so an alarm can come late"));
            }
            return lines;
        }

        private static String stamp(long millis) {
            return String.format(Locale.US, "%1$tF %1$tR", millis);
        }
    }

    // ---------------------------------------------------------------------------------------
    // What the rows say.

    static String time(Context context, long millis) {
        return DateFormat.getTimeFormat(context).format(new Date(millis));
    }

    private static String attemptLine(Context context, Result result) {
        if (result == Result.DISPATCHED || result == Result.PARTIAL || result == Result.NONE) {
            String status = statusLine(context);
            if (status != null) return status;
            String summary = dispatchSummary(context, state(), day(now()));
            if (summary != null) return summary;
            return L10n.t(context, "Nothing was sent. Check the streak settings and try again.");
        }
        return problem(context, result);
    }

    private static String dispatchSummary(Context context, State state, String today) {
        int[] dispatches = dispatchCounts(state, today);
        if (dispatches[0] == 0) return null;
        // TikTok took every one of these without an error. Only a hand-off that threw or never
        // wrote its result stays unconfirmed. A clean one used to read as a failure too (#92).
        if (dispatches[2] == 0) {
            return dispatches[0] == dispatches[1]
                    ? L10n.f(context, "Sent to %1$d/%2$d chats. They won't get another message today.",
                    dispatches[0], dispatches[1])
                    : L10n.f(context, "Sent to %1$d/%2$d chats. The rest still need a message.",
                    dispatches[0], dispatches[1]);
        }
        return dispatches[0] == dispatches[1]
                ? L10n.f(context, "Delivery unconfirmed: %1$d/%2$d chats. Those chats won't be retried today.",
                dispatches[0], dispatches[1])
                : L10n.f(context, "Delivery unconfirmed: %1$d/%2$d chats. Remaining chats still need a message.",
                dispatches[0], dispatches[1]);
    }

    /** The line under the switch: what happened last and what happens next. */
    public static String statusLine(Context context) {
        if (!Settings.AUTO_STREAK.get()) return null;
        if (recipients().isEmpty()) {
            return L10n.t(context, "Enter who to message below to start.");
        }
        State state = state();
        long now = now();
        int minute = Settings.AUTO_STREAK_MINUTE.get();
        long next = nextAlarm(state, now, minute);
        String today = day(now);
        boolean triedToday = today.equals(day(state.resultAt)) && state.resultAt > 0;
        String summary = dispatchSummary(context, state, today);
        if (summary != null) {
            return summary + " " + (day(next).equals(today)
                    ? L10n.f(context, "Next message: today at %1$s.", time(context, next))
                    : L10n.f(context, "Next message: tomorrow at %1$s.", time(context, next)));
        }
        if (triedToday && state.result != Result.NONE) {
            return problem(context, state.result, next);
        }
        return day(next).equals(today)
                ? L10n.f(context, "Next message: today at %1$s.", time(context, next))
                : L10n.f(context, "Next message: tomorrow at %1$s.", time(context, next));
    }

    private static int[] dispatchCounts(State state, String today) {
        Map<String, Boolean> chats = new LinkedHashMap<>();
        Set<String> uncertain = new HashSet<>();
        for (String handle : recipients()) {
            RecipientState record = state.recipient(handle);
            String identity = record == null || record.conversation.isEmpty() ? "@" + handle : record.conversation;
            boolean reserved = wasDispatched(state, today, handle)
                    || (record != null && today.equals(record.dispatchDay));
            chats.put(identity, reserved || Boolean.TRUE.equals(chats.get(identity)));
            if (reserved && (record == null || record.result != Result.DISPATCHED)) uncertain.add(identity);
        }
        int reserved = 0;
        for (boolean value : chats.values()) if (value) reserved++;
        return new int[]{reserved, chats.size(), uncertain.size()};
    }

    private static String problem(Context context, Result result) {
        return problem(context, result, nextAlarm(state(), now(), Settings.AUTO_STREAK_MINUTE.get()));
    }

    static String problem(Context context, Result result, long next) {
        String handle = "";
        State state = state();
        for (String entered : recipients()) {
            RecipientState record = state.recipient(entered);
            if (record != null && record.result == result) {
                handle = entered;
                break;
            }
        }
        switch (result) {
            case NO_RECIPIENT:
                return L10n.t(context, "Enter who to message below to start.");
            case NOT_FOUND:
                return L10n.f(context, "No chat with @%1$s turned up on this account. "
                        + "Open your chat with them once, then try again.", handle);
            case SIGNED_OUT:
                return L10n.f(context, "Nobody was signed in to TikTok. Sign in, and it tries again at %1$s.",
                        time(context, next));
            case OTHER_ACCOUNT:
                return L10n.t(context, "A different account is signed in. Switch back to the one "
                        + "you set this up on, or enter who to message again.");
            case NOT_READY:
                return L10n.f(context, "TikTok's messages didn't start in time. It tries again at %1$s.",
                        time(context, next));
            case UNCONFIRMED:
                return L10n.t(context, "TikTok may have taken this message. Delivery isn't confirmed, so it won't be retried today.");
            default:
                return L10n.f(context, "The message didn't go through. It tries again at %1$s.",
                        time(context, next));
        }
    }

    /**
     * The last contact lookup for the recipient row, as {@code self TAB handle}, and what it
     * found. The row is drawn again on every scroll, and the lookup opens a database.
     */
    private static String noteKey;
    private static List<StreakMessenger.Contact> notePeers;
    private static boolean noteMiss;
    private static long noteAt;
    /** How long a lookup that found nobody is kept, so a chat opened since turns up. */
    private static final long NOTE_MISS_MILLIS = 5_000L;

    /** The line under the recipient row: who the handle turned out to be, or that nobody did. */
    public static synchronized String recipientNote(Context context, String typed) {
        List<String> handles = StreakMessenger.handlesOf(typed);
        if (handles.isEmpty()) return null;
        String self = SignedInUser.id();
        if (self == null) return null;
        String key = self + '\t' + TextUtils.join(",", handles);
        long now = SystemClock.elapsedRealtime();
        if (!key.equals(noteKey) || (noteMiss && now - noteAt >= NOTE_MISS_MILLIS)) {
            notePeers = new ArrayList<>();
            noteMiss = false;
            for (String handle : handles) {
                StreakMessenger.Contact peer = StreakMessenger.findContact(context, self, handle);
                notePeers.add(peer);
                noteMiss |= peer == null;
            }
            noteKey = key;
            noteAt = now;
        }
        List<String> notes = new ArrayList<>();
        for (int index = 0; index < handles.size(); index++) {
            String handle = handles.get(index);
            StreakMessenger.Contact peer = notePeers.get(index);
            if (peer == null) {
                notes.add(L10n.f(context, "No chat with @%1$s turned up on this account. "
                        + "Open your chat with them once, then try again.", handle));
            } else {
                String name = peer.name == null || peer.name.isEmpty() ? "@" + handle : peer.name;
                notes.add(L10n.f(context, "Found %1$s in your chats.", name));
            }
        }
        return TextUtils.join("\n", notes);
    }
}
