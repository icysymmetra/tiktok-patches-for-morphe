/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference.categories;

import android.app.Dialog;
import android.content.Context;
import android.preference.DialogPreference;
import android.preference.EditTextPreference;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.ClockHourPreference;
import app.morphe.extension.tiktok.settings.preference.NumberInputPreference;
import app.morphe.extension.tiktok.settings.preference.SectionHeadingPreference;
import app.morphe.extension.tiktok.settings.preference.SettingsActionBanner;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.preference.StartTodayOverPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.wellbeing.BudgetChanges;
import app.morphe.extension.tiktok.wellbeing.FeedLock;
import app.morphe.extension.tiktok.wellbeing.FinishLastVideo;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;
import app.morphe.extension.tiktok.wellbeing.SessionLockOverlay;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The daily budget with its reminders and hold, and the switch that keeps the feed from starting
 * on its own. These sat at the bottom of Playback behind a "Daily budget" heading, which is not
 * where somebody looking for a limit on their evening would think to scroll.
 */
@SuppressWarnings("deprecation")
public final class ScreenTimePreferenceCategory extends ConditionalPreferenceCategory {
    public ScreenTimePreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Screen time");
    }

    /**
     * Whether this page has anything on it. The row into it asks the same question. Every row
     * here counts through the hook that tracks which video is on screen, which the block author
     * patch installs; without it these would take a number and count nothing.
     */
    public static boolean isAvailable() {
        return SettingsStatus.blockAuthorEnabled;
    }

    @Override
    public boolean getSettingsStatus() {
        return isAvailable();
    }

    @Override public void addPreferences(Context context) {
        // Changes that waited for today land before the rows read their values, so the page
        // never shows yesterday's budget with today's change still listed as waiting.
        BudgetChanges.applyDue(SessionBudget.now());
        addPreference(new SectionHeadingPreference(context, "Focus"));
        addPreference(new TogglePreference(context, "Lock the feed",
                "For You, Following and the other feed tabs stay behind a calm panel and won't "
                        + "swipe. Messages, profiles and search work as usual, a link to one video "
                        + "still opens that video, and the app opens on Inbox instead of the feed.",
                Settings.FEED_LOCK));
        // The link's arrival, cold and warm, is read by Feed tab navigation's hooks, so without
        // that patch there is nothing for the switch to act on. The block author patch carries
        // the rest of what it stands on and this whole page, as FeedLock.aloneIsOn asks of both.
        if (SettingsStatus.blockAuthorEnabled && SettingsStatus.feedNavigationEnabled) {
            // The time away is formatted in from the constant that applies it.
            addPreference(new TogglePreference(context, "Open shared videos alone",
                    L10n.f(context, "A link to one video opens just that video, and the feed won't "
                            + "swipe past it. It swipes again once another video plays, like after a "
                            + "refresh or a tap on Following, or when you come back to TikTok after "
                            + "%1$d minutes or more away. Auto-advance doesn't move on from it either.",
                            FeedLock.AWAY_ENDS_ALONE_MS / 60_000L),
                    Settings.SHARED_VIDEO_ALONE));
        }
        addPreference(new TogglePreference(context, "Don't start the feed on returning",
                "The feed waits for one tap before it starts playing again when you "
                        + "come back to the app. Messages, profiles and search are still "
                        + "one tap away.",
                Settings.NO_RESUME_ON_FOREGROUND));
        addPreference(new TogglePreference(context, "Don't start the first video",
                "When you open TikTok from its icon, the first video in the feed waits for one tap "
                        + "before it plays. A link, a notification or a shortcut opens as usual.",
                Settings.PAUSE_FIRST_VIDEO));
        addPreference(new TogglePreference(context, "Keep a paused video paused",
                "A video you paused stays paused when you come back to the app instead of "
                        + "starting again. One you left playing starts as usual. "
                        + "It works on Android 10 and newer.",
                Settings.KEEP_PAUSED_ON_RETURN));
        addPreference(new TogglePreference(context, "Leave when TikTok says time is up",
                "TikTok's own daily screen-time reminder sends the app to the background "
                        + "instead of waiting to be dismissed. Coming straight back leaves the "
                        + "reminder on screen, so the limit stays reachable. Needs a daily limit "
                        + "set under TikTok's Settings and privacy, under Time and well-being.",
                Settings.LEAVE_ON_REST_REMINDER));

        addPreference(new SectionHeadingPreference(context, "Daily budget"));
        addPreference(new NumberInputPreference(context, "Daily video budget",
                "Zero switches this off. Counts every video that comes up in the feed and tells "
                        + "you when you reach the limit. Separate from the auto-advance session "
                        + "limit under Playback, which counts only videos Metra moved past "
                        + "for you.", Settings.SESSION_BUDGET_VIDEOS, "%1$s video", "%1$s videos") {
            @Override protected String extraSummaryLine() {
                String waiting = waitingLine(getContext(), Settings.SESSION_BUDGET_VIDEOS, this::shown);
                if (Settings.SESSION_BUDGET_VIDEOS.savedValue() <= 0) return waiting;
                int seen = SessionBudget.videosSeen();
                return joined(L10n.quantity(getContext(), seen, "Today: %1$d video", "Today: %1$d videos"),
                        waiting);
            }
        }.zeroMeansOff());
        addPreference(new NumberInputPreference(context, "Daily time budget",
                "Zero switches this off. Count the minutes the player spends running in the feed. "
                        + "Time on messages, a profile or search doesn't count.",
                Settings.SESSION_BUDGET_MINUTES, "%1$s minute", "%1$s minutes") {
            @Override protected String extraSummaryLine() {
                String waiting = waitingLine(getContext(), Settings.SESSION_BUDGET_MINUTES, this::shown);
                if (Settings.SESSION_BUDGET_MINUTES.savedValue() <= 0) return waiting;
                // Whole minutes down, so a budget of 30 never reads "Today: 30 minutes" while
                // there is still time left on it.
                long minutes = SessionBudget.watchedMs() / 60_000L;
                return joined(L10n.quantity(getContext(), minutes, "Today: %1$d minute", "Today: %1$d minutes"),
                        waiting);
            }
        }.zeroMeansOff());
        addPreference(new NumberInputPreference(context, "Remind me every",
                "Zero switches this off. A short reminder after that many minutes of watching, "
                        + "and again after the same again. Time on messages, a profile or search "
                        + "doesn't count, and nothing is shown while the feed is on hold.",
                Settings.SESSION_BUDGET_NOTICE_MINUTES, "%1$s minute", "%1$s minutes").zeroMeansOff());
        addPreference(new NumberInputPreference(context, "Hold the feed after the budget",
                "Zero shows the notice and leaves the feed alone. Anything else covers the feed "
                        + "for that many minutes once a budget is reached. Messages, profiles and "
                        + "search keep working, and nothing in the feed is thrown away.",
                Settings.SESSION_BUDGET_LOCK_MINUTES, "%1$s minute", "%1$s minutes") {
            @Override protected String extraSummaryLine() {
                return waitingLine(getContext(), Settings.SESSION_BUDGET_LOCK_MINUTES, this::shown);
            }
        });
        addPreference(new TogglePreference(context, "Fade the feed out before the hold",
                "The feed slowly dims during the last 45 seconds before the hold, so it doesn't "
                        + "come as a sudden stop. Needs a time budget and a hold.",
                Settings.SESSION_BUDGET_RAMP));
        // The limit is formatted in from the constant that enforces it.
        addPreference(new TogglePreference(context, "Let the last video finish",
                L10n.f(context, "When the budget runs out, the video on screen plays to its end "
                        + "before the hold starts, and you can't swipe to another video "
                        + "meanwhile. If the fade already dimmed the feed, the hold starts "
                        + "right away. Needs a hold. It waits %1$d minutes at most.",
                        FinishLastVideo.LONGEST_MS / 60_000L),
                Settings.SESSION_BUDGET_FINISH_VIDEO));
        addPreference(new TogglePreference(context, "Show what is left of the budget",
                "A small label on the feed shows the minutes or videos left of today's "
                        + "budget, whichever is closer to running out.",
                Settings.SESSION_BUDGET_CUE));
        addPreference(new ClockHourPreference(context, "Start the day at",
                "The hour both budgets reset, on a 24 hour clock. The default is 4 in the "
                        + "morning, so a late night still counts as the same day.",
                Settings.SESSION_BUDGET_RESET_HOUR) {
            @Override protected String extraSummaryLine() {
                return waitingLine(getContext(), Settings.SESSION_BUDGET_RESET_HOUR, this::shown);
            }
        });
        addPreference(new TogglePreference(context, "Lock today's budget",
                "Once today's budget runs out, the hold stays and can't be dismissed. "
                        + "The budget settings are locked until the day starts over. "
                        + "Turn this off any time before the budget runs out.",
                Settings.SESSION_BUDGET_LOCK));
        addPreference(new NumberInputPreference(context, "Times you can open the feed anyway",
                "Zero means the way out of the hold is always there. Any other number is how "
                        + "many times a day it works. Once they're used up, the hold stays "
                        + "until the day starts over. Ignored while the budget is locked, which "
                        + "removes the way out.",
                Settings.SESSION_BUDGET_PASSES_PER_DAY, "%1$s time", "%1$s times") {
            @Override protected String extraSummaryLine() {
                return waitingLine(getContext(), Settings.SESSION_BUDGET_PASSES_PER_DAY, this::shown);
            }
        }.zeroMeansOff());
        addPreference(new TogglePreference(context, "Wait a day to loosen the budget",
                "A change that makes your budget looser waits until the day starts over, and a "
                        + "stricter one applies at once. Looser means a higher or no budget, a "
                        + "shorter hold, more times to open the feed anyway, a new start hour, "
                        + "or turning this off. A restored backup follows the same rule, and "
                        + "Start today over is off while this is on.",
                Settings.SESSION_BUDGET_WAIT_TO_LOOSEN));

        // Everything the budget is made of, refused for the rest of a locked day. A commitment
        // anyone can edit their way out of in two taps is a suggestion.
        Preference.OnPreferenceChangeListener refuseWhileLocked = (preference, value) -> {
            if (!SessionBudget.lockedToday()) return true;
            String reason = L10n.f(context,
                    "Today's budget is locked. This can be changed again at %1$s.",
                    SessionLockOverlay.resetTimeLabel());
            // A number row keeps its dialog open on a refused Save, so what was typed isn't
            // lost, and the settings banner sits behind that dialog: Save looked dead. The
            // reason goes under the field there. Everywhere else it is the banner, which stays
            // inside the window for the reader to finish, as every other refusal on these pages
            // does. A toast was gone before the time was read.
            android.widget.EditText field = openField(preference);
            if (field != null) SettingsUi.reportFieldError(field, reason);
            else SettingsActionBanner.showNotice(context, reason);
            return false;
        };
        // Past the lock, with Wait a day to loosen on, a change that loosens the budget is kept
        // for the next day instead of saved, and the row says what it becomes and when. Anything
        // else is a fresh choice for the row, so whatever was waiting for it goes.
        Preference.OnPreferenceChangeListener keepOrSave = (preference, value) -> {
            Setting<?> setting = Setting.getSettingFromPath(preference.getKey());
            if (setting == null) return true;
            // A number row hands over the text it is about to save.
            Object to = setting instanceof IntegerSetting
                    ? Integer.valueOf(String.valueOf(value).trim()) : value;
            if (!BudgetChanges.waits(setting, to)) {
                if (preference instanceof NumberInputPreference) {
                    NumberInputPreference number = (NumberInputPreference) preference;
                    String typed = number.getEditText().getText().toString();
                    if (!BudgetChanges.saveNow(setting, to)) {
                        showSaveFailure(preference);
                        return false;
                    }
                    number.acceptSavedValue(typed);
                    return false;
                }
                if (!BudgetChanges.forget(setting)) {
                    showSaveFailure(preference);
                    return false;
                }
                showWhatWaits(preference);
                return true;
            }
            long at = BudgetChanges.keep(setting, to, SessionBudget.now());
            if (at == 0) {
                showSaveFailure(preference);
                return false;
            }
            showWhatWaits(preference);
            // Refusing the save is what keeps the value as it is, and a refused Save keeps its
            // dialog open for another try. Nothing here needs another try.
            if (preference instanceof DialogPreference) {
                Dialog dialog = ((DialogPreference) preference).getDialog();
                if (dialog != null) dialog.dismiss();
            }
            // One literal for each, because the translation gate reads the literal handed to L10n.
            SettingsActionBanner.showNotice(context, setting == Settings.FEED_LOCK
                    ? L10n.f(context, "That loosens the lock. It waits until %1$s.",
                            SessionLockOverlay.timeLabel(at))
                    : L10n.f(context, "That loosens the budget. It waits until %1$s.",
                            SessionLockOverlay.timeLabel(at)));
            return false;
        };
        Preference.OnPreferenceChangeListener waitToLoosen = (preference, value) ->
                refuseWhileLocked.onPreferenceChange(preference, value)
                        && keepOrSave.onPreferenceChange(preference, value);
        // The feed lock is a commitment like the budget: turning it off loosens it, so with Wait
        // a day to loosen on it turns off when the day starts over. Turning it on tightens, so a
        // locked day, which holds the budget still, doesn't refuse that.
        Preference.OnPreferenceChangeListener feedLockChange = (preference, value) ->
                (Boolean.TRUE.equals(value) || refuseWhileLocked.onPreferenceChange(preference, value))
                        && keepOrSave.onPreferenceChange(preference, value);
        Preference feedLockRow = findPreference(Settings.FEED_LOCK.key);
        if (feedLockRow != null) feedLockRow.setOnPreferenceChangeListener(feedLockChange);
        for (Setting<?> setting : new Setting<?>[]{Settings.SESSION_BUDGET_VIDEOS,
                Settings.SESSION_BUDGET_MINUTES, Settings.SESSION_BUDGET_LOCK_MINUTES,
                Settings.SESSION_BUDGET_RESET_HOUR, Settings.SESSION_BUDGET_PASSES_PER_DAY,
                Settings.SESSION_BUDGET_WAIT_TO_LOOSEN}) {
            Preference row = findPreference(setting.key);
            if (row != null) row.setOnPreferenceChangeListener(waitToLoosen);
        }
        Preference reminders = findPreference(Settings.SESSION_BUDGET_NOTICE_MINUTES.key);
        if (reminders != null) reminders.setOnPreferenceChangeListener(refuseWhileLocked);

        // The switch itself refuses the same way, and turning it on when the budget has already
        // run out locks the rest of that day. Left to work it out from the switch and the counts
        // together, lowering the budget under the count you already had locked the day for
        // someone who never reached it.
        Preference lockRow = findPreference(Settings.SESSION_BUDGET_LOCK.key);
        if (lockRow != null) {
            lockRow.setOnPreferenceChangeListener((preference, value) -> {
                if (!waitToLoosen.onPreferenceChange(preference, value)) return false;
                if (Boolean.TRUE.equals(value)) SessionBudget.lockIfSpent();
                return true;
            });
            showWhatWaits(lockRow);
        }
        Preference waitRow = findPreference(Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.key);
        if (waitRow != null) showWhatWaits(waitRow);

        // The rows as built. The settings screen moves a category's rows onto itself
        // (flattenCategory), so findPreference on this category finds nothing once the page is up.
        Setting<?>[] budget = budgetRows();
        Preference[] rows = new Preference[budget.length];
        for (int index = 0; index < budget.length; index++) rows[index] = findPreference(budget[index].key);
        StartTodayOverPreference[] startOver = new StartTodayOverPreference[1];
        Runnable redraw = () -> showTheBudgetAsItIs(budget, rows, startOver[0]);
        // The row holds the redraw, so the page keeps it alive and the list only lets it be seen.
        startOver[0] = new StartTodayOverPreference(context, redraw);
        addPreference(startOver[0]);
        OPEN_PAGES.add(new WeakReference<>(redraw));
    }

    /** The redraw of every Screen time page built so far. A page that has gone lapses. Main thread only. */
    private static final List<WeakReference<Runnable>> OPEN_PAGES = new ArrayList<>();

    /**
     * Applies the budget changes that are due and redraws every Screen time page still around.
     * The settings screen calls it as it comes to the front and once a minute while it stays
     * there. A page drew its rows once, as it opened: on the S22 a phone asleep over 4:00 came
     * back to Wait a day to loosen still on and a waiting number still waiting, and a moved reset
     * hour or a changed clock would leave the old day up the same way. The budget applies
     * waiting changes as it counts, and nothing counts while this page is up.
     */
    public static void redrawOpenPages() {
        if (OPEN_PAGES.isEmpty()) return;
        BudgetChanges.applyDue(SessionBudget.now());
        for (Iterator<WeakReference<Runnable>> pages = OPEN_PAGES.iterator(); pages.hasNext(); ) {
            Runnable redraw = pages.next().get();
            if (redraw == null) pages.remove();
            else redraw.run();
        }
    }

    /** Every row a budget change or a new day can move, in the order the page shows them. */
    private static Setting<?>[] budgetRows() {
        return new Setting<?>[]{Settings.FEED_LOCK, Settings.SESSION_BUDGET_VIDEOS, Settings.SESSION_BUDGET_MINUTES,
                Settings.SESSION_BUDGET_LOCK_MINUTES, Settings.SESSION_BUDGET_RESET_HOUR,
                Settings.SESSION_BUDGET_LOCK, Settings.SESSION_BUDGET_PASSES_PER_DAY,
                Settings.SESSION_BUDGET_WAIT_TO_LOOSEN};
    }

    /**
     * Draws the budget rows again from what is stored now, without writing anything. The page
     * drew them once, as it opened: Start today over left "Today: 2 minutes" on the row it had
     * just cleared (S22), until the page was opened again.
     */
    private static void showTheBudgetAsItIs(Setting<?>[] budget, Preference[] rows,
            StartTodayOverPreference startOver) {
        for (int index = 0; index < budget.length; index++) {
            Setting<?> setting = budget[index];
            Preference row = rows[index];
            if (row == null) continue;
            if (row instanceof NumberInputPreference) {
                NumberInputPreference number = (NumberInputPreference) row;
                String stored = String.valueOf(setting.get());
                if (!stored.equals(number.getText())) number.setValueWithoutPersisting(stored);
            } else if (row instanceof TogglePreference && setting.get() instanceof Boolean) {
                TogglePreference toggle = (TogglePreference) row;
                boolean persistent = toggle.isPersistent();
                toggle.setPersistent(false);
                toggle.setChecked((Boolean) setting.get());
                toggle.setPersistent(persistent);
            }
            showWhatWaits(row);
        }
        // A clear can only be taken back the day it was made.
        if (startOver != null) startOver.showWhatATapDoes();
    }

    private void showSaveFailure(Preference preference) {
        String message = L10n.t(getContext(), "Couldn't save the change. Try again.");
        android.widget.EditText field = openField(preference);
        if (field != null) SettingsUi.reportFieldError(field, message);
        else SettingsActionBanner.showNotice(getContext(), message);
    }

    // Not IntFunction: java.util.function arrived at API 24, and a type D8 cannot backport fails
    // to resolve on Android 6, which the payload's own floor of API 23 still allows.
    /** How a number row writes one of its values, unit and all. */
    private interface Shown {
        String of(int value);
    }

    /** "Changes to 45 minutes at 4:00 AM" under a number row whose change is waiting, or null. */
    private static String waitingLine(Context context, Setting<?> setting, Shown shown) {
        Object waiting = BudgetChanges.waiting(setting);
        long at = BudgetChanges.appliesAt();
        if (!(waiting instanceof Number) || at <= 0) return null;
        return L10n.f(context, "Changes to %1$s at %2$s",
                shown.of(((Number) waiting).intValue()), SessionLockOverlay.timeLabel(at));
    }

    private static String joined(String first, String second) {
        if (second == null) return first;
        return first == null ? second : first + "\n" + second;
    }

    /**
     * Draws a budget row's waiting line again. A switch can only loosen by going off, so what
     * waits for one is always that.
     */
    /** The field of a text row whose dialog is open, or null when the row has no dialog up. */
    private static android.widget.EditText openField(Preference preference) {
        if (!(preference instanceof EditTextPreference)) return null;
        Dialog dialog = ((DialogPreference) preference).getDialog();
        if (dialog == null || !dialog.isShowing()) return null;
        return ((EditTextPreference) preference).getEditText();
    }

    private static void showWhatWaits(Preference row) {
        if (row instanceof NumberInputPreference) {
            ((NumberInputPreference) row).refreshSummary();
            return;
        }
        if (!(row instanceof TogglePreference)) return;
        Setting<?> setting = Setting.getSettingFromPath(row.getKey());
        long at = BudgetChanges.appliesAt();
        boolean waits = setting != null && BudgetChanges.waiting(setting) != null && at > 0;
        ((TogglePreference) row).showExtraLine(waits
                ? L10n.f(row.getContext(), "Turns off at %1$s", SessionLockOverlay.timeLabel(at))
                : null);
    }
}
