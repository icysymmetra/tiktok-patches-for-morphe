/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings;

import android.content.Context;
import android.content.SharedPreferences;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** A conservative, reversible starting point for a less commercial feed. */
public final class CalmFeedPreset {
    static final String PREFERENCES = "hushfeed-calm-feed-preset";
    private static final String HAS_SNAPSHOT = "has_snapshot";
    private static final String SNAPSHOT_MASK = "snapshot_mask";
    private static final String SNAPSHOT_SCHEMA = "snapshot_schema";
    private static final int SCHEMA = 1;

    private static final BooleanSetting[] SETTINGS = {
            Settings.REMOVE_ADS,
            Settings.HIDE_SHOP,
            Settings.HIDE_LIVE,
            Settings.HIDE_LIVE_REPLAYS,
            Settings.HIDE_PROMOTIONAL_MUSIC,
            Settings.HIDE_PAID_PARTNERSHIP,
    };

    private CalmFeedPreset() {
    }

    public enum State {
        AVAILABLE,
        ACTIVE,
        ADJUSTED,
        ALREADY_ACTIVE
    }

    /** The values before an apply or restore, used by the settings page's restart tracker. */
    public static final class Result {
        public final Map<Setting<?>, Object> previousValues;
        public final int changedCount;
        public final int restartChangedCount;

        private Result(
                Map<Setting<?>, Object> previousValues,
                int changedCount,
                int restartChangedCount
        ) {
            this.previousValues = Collections.unmodifiableMap(previousValues);
            this.changedCount = changedCount;
            this.restartChangedCount = restartChangedCount;
        }
    }

    public static State state(Context context) {
        boolean applied = isApplied();
        if (!hasSnapshot(context)) return applied ? State.ALREADY_ACTIVE : State.AVAILABLE;
        return applied ? State.ACTIVE : State.ADJUSTED;
    }

    /** The six switches the preset turns on, which Quick setups' Quiet turns on as well. */
    static BooleanSetting[] settings() {
        return SETTINGS.clone();
    }

    public static boolean hasSnapshot(Context context) {
        return preferences(context).getBoolean(HAS_SNAPSHOT, false);
    }

    public static boolean isApplied() {
        for (BooleanSetting setting : SETTINGS) {
            if (!setting.savedValue()) return false;
        }
        return true;
    }

    /** Saves the current six values before changing them in one preference transaction. */
    public static synchronized Result apply(Context context) throws IOException {
        if (hasSnapshot(context)) {
            throw new IOException("Restore the previous Calm feed setup before applying it again");
        }
        LinkedHashMap<Setting<?>, Object> before = currentValues();
        int mask = encode(before);
        SharedPreferences preferences = preferences(context);
        boolean snapshotSaved = preferences.edit()
                .putInt(SNAPSHOT_SCHEMA, SCHEMA)
                .putInt(SNAPSHOT_MASK, mask)
                .putBoolean(HAS_SNAPSHOT, true)
                .commit();
        if (!snapshotSaved) throw new IOException("Could not save the previous feed setup");

        LinkedHashMap<Setting<?>, Object> target = targetValues();
        int changed = different(before, target);
        try {
            Setting.saveAll(target);
        } catch (Exception error) {
            if (!clearSnapshot(preferences)) {
                error.addSuppressed(new IOException("Could not clear an unused Calm feed snapshot"));
            }
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("Could not apply Calm feed", error);
        }
        return new Result(before, changed, restartDifferences(before, target));
    }

    /** Restores exactly the six values captured before the most recent apply. */
    public static synchronized Result restore(Context context) throws IOException {
        SharedPreferences preferences = preferences(context);
        if (!preferences.getBoolean(HAS_SNAPSHOT, false)) {
            throw new IOException("There is no previous Calm feed setup to restore");
        }
        int schema = preferences.getInt(SNAPSHOT_SCHEMA, -1);
        int mask = preferences.getInt(SNAPSHOT_MASK, -1);
        int allowedMask = (1 << SETTINGS.length) - 1;
        if (schema != SCHEMA || mask < 0 || (mask & ~allowedMask) != 0) {
            throw new IOException("The previous Calm feed setup could not be read");
        }

        LinkedHashMap<Setting<?>, Object> before = currentValues();
        LinkedHashMap<Setting<?>, Object> target = decode(mask);
        int changed = different(before, target);
        Setting.saveAll(target);
        // The useful state has already been restored. A stale record is harmless and remains a
        // second route back to the same values if the phone refuses this cleanup write.
        clearSnapshot(preferences);
        return new Result(before, changed, restartDifferences(before, target));
    }

    private static LinkedHashMap<Setting<?>, Object> currentValues() {
        LinkedHashMap<Setting<?>, Object> values = new LinkedHashMap<>();
        for (BooleanSetting setting : SETTINGS) values.put(setting, setting.savedValue());
        return values;
    }

    private static LinkedHashMap<Setting<?>, Object> targetValues() {
        LinkedHashMap<Setting<?>, Object> values = new LinkedHashMap<>();
        for (BooleanSetting setting : SETTINGS) values.put(setting, true);
        return values;
    }

    private static LinkedHashMap<Setting<?>, Object> decode(int mask) {
        LinkedHashMap<Setting<?>, Object> values = new LinkedHashMap<>();
        for (int index = 0; index < SETTINGS.length; index++) {
            values.put(SETTINGS[index], (mask & (1 << index)) != 0);
        }
        return values;
    }

    private static int encode(Map<Setting<?>, Object> values) {
        int mask = 0;
        for (int index = 0; index < SETTINGS.length; index++) {
            if (Boolean.TRUE.equals(values.get(SETTINGS[index]))) mask |= 1 << index;
        }
        return mask;
    }

    private static int different(Map<Setting<?>, Object> left, Map<Setting<?>, Object> right) {
        int count = 0;
        for (BooleanSetting setting : SETTINGS) {
            if (!left.get(setting).equals(right.get(setting))) count++;
        }
        return count;
    }

    private static int restartDifferences(
            Map<Setting<?>, Object> left,
            Map<Setting<?>, Object> right
    ) {
        int count = 0;
        for (BooleanSetting setting : SETTINGS) {
            if (setting.rebootApp && !left.get(setting).equals(right.get(setting))) count++;
        }
        return count;
    }

    /**
     * Keeps a copy of the saved setup beside the settings' undo copy, written with it, so the two
     * always belong to the same moment. Held only once the restore had worked, a restore that
     * failed left the undo copy new and this copy old, and the Undo its banner asked for brought
     * back a setup from an earlier reset, or took the current one away.
     */
    public static synchronized void holdForUndo(Context context) {
        SharedPreferences preferences = preferences(context);
        SharedPreferences.Editor edit = preferences.edit();
        move(preferences, edit, "", HELD);
        if (!edit.commit()) {
            app.morphe.extension.shared.Logger.printInfo(() -> "Could not keep the Calm feed setup for Undo");
        }
    }

    /**
     * Once a reset or an import has replaced every setting. Offered as it was, "Restore setup"
     * would have put back values the reset had just cleared; the copy {@link #holdForUndo} kept
     * is what the settings' Undo brings back.
     */
    public static synchronized void clearAfterRestore(Context context) {
        SharedPreferences.Editor edit = preferences(context).edit();
        move(null, edit, HELD, "");
        if (!edit.commit()) {
            app.morphe.extension.shared.Logger.printInfo(() -> "Could not set the Calm feed setup aside");
        }
    }

    /**
     * After the settings' Undo: the setup set aside comes back with the settings it belongs to,
     * and the current one waits beside the new undo copy, so a second Undo swaps them again.
     */
    public static synchronized void swapWithUndo(Context context) {
        SharedPreferences preferences = preferences(context);
        SharedPreferences.Editor edit = preferences.edit();
        move(preferences, edit, "", HELD);
        move(preferences, edit, HELD, "");
        if (!edit.commit()) {
            app.morphe.extension.shared.Logger.printInfo(() -> "Could not bring the Calm feed setup back");
        }
    }

    private static final String HELD = "held_";

    /** Writes the snapshot under {@code from} into {@code to}, or clears {@code to} when there is none. */
    private static void move(SharedPreferences source, SharedPreferences.Editor edit, String from, String to) {
        if (source == null || !source.getBoolean(from + HAS_SNAPSHOT, false)) {
            edit.remove(to + HAS_SNAPSHOT).remove(to + SNAPSHOT_MASK).remove(to + SNAPSHOT_SCHEMA);
            return;
        }
        edit.putBoolean(to + HAS_SNAPSHOT, true)
                .putInt(to + SNAPSHOT_MASK, source.getInt(from + SNAPSHOT_MASK, 0))
                .putInt(to + SNAPSHOT_SCHEMA, source.getInt(from + SNAPSHOT_SCHEMA, 0));
    }

    private static SharedPreferences preferences(Context context) {
        Context app = context.getApplicationContext();
        return (app == null ? context : app).getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static boolean clearSnapshot(SharedPreferences preferences) {
        return preferences.edit()
                .remove(HAS_SNAPSHOT)
                .remove(SNAPSHOT_MASK)
                .remove(SNAPSHOT_SCHEMA)
                .commit();
    }

    static void clearForTests(Context context) {
        preferences(context).edit().clear().commit();
    }
}
