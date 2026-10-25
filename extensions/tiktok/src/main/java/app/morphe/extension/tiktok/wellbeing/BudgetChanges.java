/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.wellbeing;

import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * A change that loosens the daily budget waits for the next day.
 *
 * <p>The budget's lock only binds once the budget is spent, so the moment before that is open:
 * two taps raise the budget and the evening goes on. With {@link Settings#SESSION_BUDGET_WAIT_TO_LOOSEN}
 * on, a change that loosens the budget is kept aside and applies when the day starts over, at the
 * reset hour, and a change that tightens it applies at once. Loosening is a higher budget or none,
 * a shorter hold, more times to open the feed anyway or no cap on them, the lock, the feed lock
 * or this switch turned off, and any move of the hour the day starts, since a move can start a new day, and a
 * new budget, straight away. A restore, a reset and an undo follow the same rule, and on a locked
 * day a restore leaves every budget setting as it is.
 */
public final class BudgetChanges {
    static final String FAMILY = "budget changes";

    private static final String AT = "at";
    private static final String VALUES = "values";

    /** When the waiting changes apply: zero when nothing waits, -1 until read from the setting. */
    private static volatile long appliesAt = -1;
    private static volatile String cachedPending;

    private BudgetChanges() {
    }

    /** The settings a loosening of waits for the next day, in the order the page lists them. */
    static Setting<?>[] watched() {
        return new Setting<?>[]{Settings.FEED_LOCK, Settings.SESSION_BUDGET_VIDEOS, Settings.SESSION_BUDGET_MINUTES,
                Settings.SESSION_BUDGET_LOCK_MINUTES, Settings.SESSION_BUDGET_RESET_HOUR,
                Settings.SESSION_BUDGET_LOCK, Settings.SESSION_BUDGET_PASSES_PER_DAY,
                Settings.SESSION_BUDGET_WAIT_TO_LOOSEN};
    }

    /** The settings a locked day holds still, the reminders included, as the page's rows do. */
    static Setting<?>[] heldByALockedDay() {
        Setting<?>[] watched = watched();
        Setting<?>[] held = new Setting<?>[watched.length + 1];
        System.arraycopy(watched, 0, held, 0, watched.length);
        held[watched.length] = Settings.SESSION_BUDGET_NOTICE_MINUTES;
        return held;
    }

    /** Whether moving {@code setting} from {@code from} to {@code to} loosens the budget. */
    public static boolean loosens(Setting<?> setting, Object from, Object to) {
        if (Objects.equals(from, to)) return false;
        if (setting == Settings.SESSION_BUDGET_VIDEOS || setting == Settings.SESSION_BUDGET_MINUTES
                || setting == Settings.SESSION_BUDGET_PASSES_PER_DAY) {
            // Counts where zero means none: none at all, or more of it.
            int before = number(from);
            int after = number(to);
            return before != 0 && (after == 0 || after > before);
        }
        if (setting == Settings.SESSION_BUDGET_LOCK_MINUTES) return number(to) < number(from);
        if (setting == Settings.SESSION_BUDGET_LOCK || setting == Settings.SESSION_BUDGET_WAIT_TO_LOOSEN
                || setting == Settings.FEED_LOCK) {
            return Boolean.TRUE.equals(from) && !Boolean.TRUE.equals(to);
        }
        return setting == Settings.SESSION_BUDGET_RESET_HOUR;
    }

    /** Whether changing {@code setting} to {@code to} waits for the next day. */
    public static boolean waits(Setting<?> setting, Object to) {
        return Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.savedValue() && isWatched(setting)
                && loosens(setting, setting.savedValue(), bounded(setting, to));
    }

    /**
     * Keeps {@code to} aside for {@code setting} until the day starts over, and answers when that
     * is. Changes already due are applied first, so a new one never pushes theirs back.
     */
    public static long keep(Setting<?> setting, Object to, long now) {
        applyDue(now);
        long at = SessionBudget.dayEndAfter(now);
        synchronized (Setting.class) {
            long due = appliesAt();
            if (due > 0 && due <= now) return 0;
            Map<String, Object> values = waitingValues();
            values.put(setting.key, bounded(setting, to));
            if (!write(values, at)) return 0;
            HookStatus.bound(FAMILY, "kept for the next day");
            return at;
        }
    }

    /** Drops what was waiting for {@code setting}: the reader has chosen again. */
    public static boolean forget(Setting<?> setting) {
        synchronized (Setting.class) {
            Map<String, Object> values = waitingValues();
            if (values.remove(setting.key) == null) return true;
            return write(values, values.isEmpty() ? 0 : readAt());
        }
    }

    /** Saves a fresh choice and cancels its waiting value in the same transaction. */
    public static boolean saveNow(Setting<?> setting, Object to) {
        synchronized (Setting.class) {
            Map<String, Object> values = waitingValues();
            values.remove(setting.key);
            Map<Setting<?>, Object> updates = new LinkedHashMap<>();
            updates.put(setting, bounded(setting, to));
            updates.put(Settings.SESSION_BUDGET_PENDING,
                    encode(values, values.isEmpty() ? 0 : readAt()));
            try {
                Setting.saveAll(updates);
                readAt();
                return true;
            } catch (Exception failure) {
                Logger.printException(() -> "The budget's new choice could not be saved", failure);
                return false;
            }
        }
    }

    /** The value waiting for {@code setting}, or null when nothing waits for it. */
    public static Object waiting(Setting<?> setting) {
        synchronized (Setting.class) {
            return waitingValues().get(setting.key);
        }
    }

    /** The next time the day starts over. */
    public static long nextDayAt() {
        return SessionBudget.dayEndAfter(SessionBudget.now());
    }

    /** When the waiting changes apply, or zero when nothing waits. */
    public static long appliesAt() {
        long at = appliesAt;
        return at < 0 || !Objects.equals(cachedPending, Settings.SESSION_BUDGET_PENDING.savedValue())
                ? readAt() : at;
    }

    /**
     * Applies the waiting changes once the day they waited for has started. Cached reads are
     * enough when nothing waits, since the budget calls it on every count.
     */
    public static void applyDue(long now) {
        long at = appliesAt();
        if (at == 0 || now < at) return;
        // Use the preference transaction's own lock. A separate budget monitor around a save
        // would invert the lock order when a preference listener reads this state back.
        synchronized (Setting.class) {
            at = readAt();
            if (at == 0 || now < at) return;
            Map<Setting<?>, Object> updates = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : waitingValues().entrySet()) {
                Setting<?> setting = Setting.getSettingFromPath(entry.getKey());
                if (setting != null && isWatched(setting)
                        && setting.defaultValue.getClass().isInstance(entry.getValue())) {
                    updates.put(setting, bounded(setting, entry.getValue()));
                }
            }
            // Discard invalid stored values, but retain valid ones if the transaction fails.
            // Clearing the record and applying its values must survive or fail together.
            updates.put(Settings.SESSION_BUDGET_PENDING, "");
            try {
                Setting.saveAll(updates);
                readAt();
                HookStatus.bound(FAMILY, "applied as the day started");
            } catch (Exception failure) {
                Logger.printException(() -> "The budget's waiting changes could not be applied", failure);
            }
        }
    }

    /**
     * A restore's settings, split into what applies now and what waits for the next day. On a
     * locked day the budget settings are left out altogether, so they stay as they are.
     *
     * <p>A budget setting the restore does not loosen is a fresh choice for it, as a tighter
     * number typed on the page is, so whatever was waiting for that setting is dropped. That is
     * also what lets Undo take back a restore that left changes waiting: it writes the values
     * the restore found, which loosen nothing.
     */
    public static Split forRestore(Map<Setting<?>, Object> incoming, long now) {
        applyDue(now);
        long due = appliesAt();
        if (due > 0 && due <= now) {
            throw new IllegalStateException("Could not apply the budget changes already due");
        }
        Map<Setting<?>, Object> apply = new LinkedHashMap<>(incoming);
        Map<String, Object> waiting = new LinkedHashMap<>();
        Set<String> settled = new HashSet<>();
        if (SessionBudget.lockedToday()) {
            for (Setting<?> setting : heldByALockedDay()) apply.remove(setting);
            HookStatus.bound(FAMILY, "restore held by a locked day");
            return new Split(apply, waiting, settled, 0, apply.size() != incoming.size());
        }
        boolean waitToLoosen = Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.savedValue();
        for (Setting<?> setting : watched()) {
            if (!apply.containsKey(setting)) continue;
            Object to = bounded(setting, apply.get(setting));
            if (waitToLoosen && loosens(setting, setting.savedValue(), to)) {
                apply.remove(setting);
                waiting.put(setting.key, to);
            } else {
                settled.add(setting.key);
            }
        }
        return new Split(apply, waiting, settled,
                waiting.isEmpty() ? 0 : SessionBudget.dayEndAfter(now), apply.size() != incoming.size());
    }

    /** What a restore writes now, and what it leaves for the next day. */
    public static final class Split {
        public final Map<Setting<?>, Object> apply;
        final Map<String, Object> waiting;
        final Set<String> settled;
        final long at;
        private final boolean heldBack;

        Split(Map<Setting<?>, Object> apply, Map<String, Object> waiting, Set<String> settled, long at,
                boolean heldBack) {
            this.apply = apply;
            this.waiting = waiting;
            this.settled = settled;
            this.at = at;
            this.heldBack = heldBack;
        }

        /** Whether the restore writes less than its file carries, because the budget kept some of it. */
        public boolean heldBack() {
            return heldBack;
        }

        /** Whether the change to {@code setting} waits for the next day instead of applying now. */
        public boolean waits(Setting<?> setting) {
            return waiting.containsKey(setting.key);
        }

        /** Includes the delayed record in the same transaction as the restored settings. */
        public Map<Setting<?>, Object> withWaiting() {
            Map<Setting<?>, Object> updates = new LinkedHashMap<>(apply);
            synchronized (Setting.class) {
                Map<String, Object> values = waitingValues();
                values.keySet().removeAll(settled);
                values.putAll(waiting);
                updates.put(Settings.SESSION_BUDGET_PENDING,
                        encode(values, values.isEmpty() ? 0 : waiting.isEmpty() ? readAt() : at));
            }
            return updates;
        }

        /** Persists only the delayed part when the caller is not restoring other settings. */
        public boolean keepWaiting() {
            synchronized (Setting.class) {
                return Settings.SESSION_BUDGET_PENDING.save(
                        (String) withWaiting().get(Settings.SESSION_BUDGET_PENDING));
            }
        }
    }

    private static boolean isWatched(Setting<?> setting) {
        for (Setting<?> watched : watched()) {
            if (watched == setting) return true;
        }
        return false;
    }

    /**
     * What {@code setting} holds once {@code value} is saved. A number outside a setting's range
     * is brought inside it by the save, so it is judged that way here too: a restored -1 videos is
     * saved as 0, which is no budget at all, and judged as -1 it looked like a tighter budget than
     * 50 and applied at once.
     */
    static Object bounded(Setting<?> setting, Object value) {
        if (!(setting instanceof IntegerSetting) || !(value instanceof Number)) return value;
        IntegerSetting number = (IntegerSetting) setting;
        long raw = ((Number) value).longValue();
        return (int) Math.max(number.minimum(), Math.min(number.maximum(), raw));
    }

    private static int number(Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    private static long readAt() {
        String text = Settings.SESSION_BUDGET_PENDING.savedValue();
        long at = parse(text).optLong(AT, 0);
        appliesAt = at;
        cachedPending = text;
        return at;
    }

    private static Map<String, Object> waitingValues() {
        Map<String, Object> values = new LinkedHashMap<>();
        JSONObject stored = parse(Settings.SESSION_BUDGET_PENDING.savedValue()).optJSONObject(VALUES);
        if (stored == null) return values;
        for (Iterator<String> keys = stored.keys(); keys.hasNext(); ) {
            String key = keys.next();
            values.put(key, stored.opt(key));
        }
        return values;
    }

    private static JSONObject parse(String text) {
        if (text == null || text.isEmpty()) return new JSONObject();
        try {
            return new JSONObject(text);
        } catch (Exception unreadable) {
            return new JSONObject();
        }
    }

    private static boolean write(Map<String, Object> values, long at) {
        try {
            if (!Settings.SESSION_BUDGET_PENDING.save(encode(values, at))) return false;
            readAt();
            return true;
        } catch (Exception failure) {
            Logger.printException(() -> "The budget's waiting changes could not be written", failure);
            return false;
        }
    }

    private static String encode(Map<String, Object> values, long at) {
        if (values.isEmpty()) return "";
        try {
            JSONObject stored = new JSONObject();
            for (Map.Entry<String, Object> entry : values.entrySet()) stored.put(entry.getKey(), entry.getValue());
            return new JSONObject().put(AT, at).put(VALUES, stored).toString();
        } catch (org.json.JSONException invalid) {
            throw new IllegalArgumentException("Invalid waiting budget values", invalid);
        }
    }

    static void resetForTests() {
        appliesAt = -1;
        cachedPending = null;
    }
}
