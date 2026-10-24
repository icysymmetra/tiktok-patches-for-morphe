/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings;

import android.content.Context;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.wellbeing.BudgetChanges;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The three one-tap choices at the top of the settings: Quiet, Privacy and Back to defaults.
 *
 * <p>Every switch a choice may touch is listed here and nowhere else. Quiet is the Calm feed
 * preset, applied through it, so the Restore on the Calm feed card still puts back the setup
 * from before. Privacy turns on the switches that keep what you do to yourself and cost nothing
 * you'd notice; anything with a price, like Watch history, location, motion sensors or a VPN,
 * stays a choice for the Privacy page. Back to defaults puts every switch on Hushfeed's pages
 * back as it shipped. Text, numbers and lists are left alone, and a locked day's budget holds
 * its switches still, the way a restore does.
 */
public final class QuickSetups {
    public enum Choice {
        QUIET,
        PRIVACY,
        DEFAULTS
    }

    /** Views and typing (Ghost mode), tracking, the AI engine, the advertising ID, the clipboard and link tags. */
    private static final BooleanSetting[] PRIVACY = {
            Settings.GHOST_MODE,
            Settings.DISABLE_ANALYTICS,
            Settings.STOP_AI_PROFILING,
            Settings.BLOCK_ADVERTISING_ID,
            Settings.BLOCK_CLIPBOARD_READS,
            BaseSettings.SANITIZE_SHARING_LINKS,
    };

    /** What a choice changed, for the restart row, the banner and Undo. */
    public static final class Result {
        /**
         * Every setting the choice changed, with the value it had before. A budget setting whose
         * change waits for the next day is in here too, still holding that value.
         */
        public final Map<Setting<?>, Object> previousValues;
        public final int changedCount;
        public final int restartChangedCount;
        /** Whether Undo goes through the Calm feed preset's own restore. */
        final boolean calmFeedSnapshot;
        /**
         * The settings in {@link #previousValues} whose saved value this left alone: a change that
         * waits for the next day or, on Undo's result, a wait it cancelled.
         */
        final Set<Setting<?>> waiting;

        Result(Map<Setting<?>, Object> previousValues, boolean calmFeedSnapshot) {
            this(previousValues, Collections.emptySet(), calmFeedSnapshot);
        }

        Result(Map<Setting<?>, Object> previousValues, Set<Setting<?>> waiting, boolean calmFeedSnapshot) {
            this.waiting = Collections.unmodifiableSet(new LinkedHashSet<>(waiting));
            this.previousValues = Collections.unmodifiableMap(new LinkedHashMap<>(previousValues));
            int restart = 0;
            for (Setting<?> setting : previousValues.keySet()) {
                // A waiting change takes hold when the day starts over, not at a restart now.
                if (setting.rebootApp && !this.waiting.contains(setting)) restart++;
            }
            this.changedCount = previousValues.size();
            this.restartChangedCount = restart;
            this.calmFeedSnapshot = calmFeedSnapshot;
        }
    }

    private QuickSetups() {
    }

    /** The switches Quiet and Privacy turn on. Back to defaults has no list: it is every switch. */
    public static List<BooleanSetting> switchesFor(Choice choice) {
        switch (choice) {
            case QUIET:
                return Arrays.asList(CalmFeedPreset.settings());
            case PRIVACY:
                return Arrays.asList(PRIVACY.clone());
            default:
                return Collections.emptyList();
        }
    }

    /**
     * What a choice writes, before the budget has its say. Privacy and Back to defaults keep to
     * the switches on a page this bundle shows, so a switch from a patch left out is never changed
     * out of sight. Quiet writes the preset's six, as the Calm feed card does.
     */
    public static Map<Setting<?>, Object> targets(Choice choice, Collection<Setting<?>> onPages) {
        Map<Setting<?>, Object> targets = new LinkedHashMap<>();
        if (choice == Choice.DEFAULTS) {
            for (Setting<?> setting : onPages) {
                // Hiding this card is about the page, not a setting, so Back to defaults on the
                // card itself mustn't bring it back.
                if (setting == BaseSettings.HIDE_QUICK_SETUPS) continue;
                if (setting instanceof BooleanSetting) targets.put(setting, setting.defaultValue);
            }
            return targets;
        }
        for (BooleanSetting setting : switchesFor(choice)) {
            if (choice == Choice.QUIET || onPages.contains(setting)) targets.put(setting, Boolean.TRUE);
        }
        return targets;
    }

    /** The part of {@link #targets} that differs from what's saved now. */
    static Map<Setting<?>, Object> changes(Choice choice, Collection<Setting<?>> onPages) {
        return differing(targets(choice, onPages));
    }

    /** Applies a choice and answers what it changed. Runs off the main thread: it writes to disk. */
    public static synchronized Result apply(Context context, Choice choice,
            Collection<Setting<?>> onPages) throws IOException {
        Map<Setting<?>, Object> changes = changes(choice, onPages);
        if (changes.isEmpty()) return new Result(Collections.emptyMap(), false);
        if (choice == Choice.QUIET && !CalmFeedPreset.hasSnapshot(context)) {
            CalmFeedPreset.Result calm = CalmFeedPreset.apply(context);
            return new Result(changedSince(calm.previousValues), true);
        }
        return save(changes, false);
    }

    /** Puts back what {@code result} changed. */
    public static synchronized Result undo(Context context, Result result) throws IOException {
        if (result.calmFeedSnapshot && CalmFeedPreset.hasSnapshot(context)) {
            return new Result(changedSince(CalmFeedPreset.restore(context).previousValues), false);
        }
        Map<Setting<?>, Object> changes = differing(result.previousValues);
        // A change still waiting for the next day is taken back by writing the value the setting
        // holds anyway: the budget reads that as a fresh choice and drops what was waiting.
        for (Setting<?> setting : result.waiting) changes.put(setting, result.previousValues.get(setting));
        Result undone = save(changes, true);
        if (result.waiting.isEmpty()) return undone;
        // Those rows show the change that was waiting, so they're redrawn with the rest.
        Map<Setting<?>, Object> touched = new LinkedHashMap<>(undone.previousValues);
        for (Setting<?> setting : result.waiting) {
            if (!touched.containsKey(setting)) touched.put(setting, result.previousValues.get(setting));
        }
        // Their saved values never moved, so cancelling the wait asks for no restart either.
        return new Result(touched, result.waiting, false);
    }

    /**
     * Saves {@code changes} in one transaction, less what the budget keeps: a locked day holds its
     * switches, and with Wait a day to loosen a looser one waits for the next day. Answers what
     * changed with the values it had before, the waiting changes included, so the banner counts
     * them and Undo can take them back.
     */
    private static Result save(Map<Setting<?>, Object> changes, boolean deviceState) throws IOException {
        if (changes.isEmpty()) return new Result(Collections.emptyMap(), false);
        try {
            BudgetChanges.Split split = BudgetChanges.forRestore(changes, SessionBudget.now());
            Map<Setting<?>, Object> before = new LinkedHashMap<>();
            for (Setting<?> setting : split.apply.keySet()) before.put(setting, setting.savedValue());
            Map<Setting<?>, Object> waiting = new LinkedHashMap<>();
            for (Setting<?> setting : changes.keySet()) {
                if (split.waits(setting)) waiting.put(setting, setting.savedValue());
            }
            Setting.saveAll(split.withWaiting(), deviceState);
            Map<Setting<?>, Object> changed = changedSince(before);
            changed.putAll(waiting);
            return new Result(changed, waiting.keySet(), false);
        } catch (IOException error) {
            throw error;
        } catch (Exception error) {
            throw new IOException("Could not apply the quick setup", error);
        }
    }

    /** The targets a setting doesn't hold yet, and isn't already waiting for the next day to take. */
    private static Map<Setting<?>, Object> differing(Map<Setting<?>, Object> targets) {
        Map<Setting<?>, Object> changes = new LinkedHashMap<>();
        for (Map.Entry<Setting<?>, Object> target : targets.entrySet()) {
            if (!Objects.equals(target.getKey().savedValue(), target.getValue())
                    && !Objects.equals(BudgetChanges.waiting(target.getKey()), target.getValue())) {
                changes.put(target.getKey(), target.getValue());
            }
        }
        return changes;
    }

    /** The entries of {@code before} whose setting now holds something else. */
    private static Map<Setting<?>, Object> changedSince(Map<Setting<?>, Object> before) {
        Map<Setting<?>, Object> changed = new LinkedHashMap<>();
        for (Map.Entry<Setting<?>, Object> entry : before.entrySet()) {
            if (!Objects.equals(entry.getKey().savedValue(), entry.getValue())) {
                changed.put(entry.getKey(), entry.getValue());
            }
        }
        return changed;
    }

    /** Every switch Quiet and Privacy name, for the test that holds the lists to real settings. */
    static List<BooleanSetting> listed() {
        List<BooleanSetting> listed = new ArrayList<>(switchesFor(Choice.QUIET));
        listed.addAll(switchesFor(Choice.PRIVACY));
        return listed;
    }
}
