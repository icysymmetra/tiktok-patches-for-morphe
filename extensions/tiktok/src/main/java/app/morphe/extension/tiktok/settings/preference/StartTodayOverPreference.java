/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;

import app.morphe.extension.shared.settings.preference.ImmediateAction;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.wellbeing.BudgetChanges;
import app.morphe.extension.tiktok.wellbeing.SessionBudget;
import app.morphe.extension.tiktok.wellbeing.SessionLockOverlay;

/**
 * Forgets what has been counted today.
 *
 * <p>One tap does it, with no dialog first, because the next tap puts it back. That is the same
 * bargain the seen video history row makes. This row used to make it without keeping it: a
 * mis-tap lifted a running hold and there was nothing at all to undo it with.
 */
@SuppressWarnings("deprecation")
public final class StartTodayOverPreference extends Preference implements ImmediateAction {
    static final String CLEAR_SUMMARY =
            "Forget what has been counted today and end any hold. The budgets themselves are left alone.";
    static final String UNDO_SUMMARY = "Today started over. Tap again to put the counts back.";

    @Override public boolean actsOnTap() {
        return true;
    }

    /** Redraws what the counts show elsewhere on the page; runs after a clear and after Undo. */
    private final Runnable afterChange;

    public StartTodayOverPreference(Context context) {
        this(context, null);
    }

    public StartTodayOverPreference(Context context, Runnable afterChange) {
        super(context);
        this.afterChange = afterChange;
        // A key so the settings search can index this row. A key with no Setting behind it is
        // skipped by the settings framework, so nothing tries to persist it.
        setKey("action_start_today_over");
        setTitle(L10n.t(context, "Start today over"));
        // Whether a clear is waiting to be taken back outlives this row, so the row asks rather
        // than assuming it is the first one ever built.
        showWhatATapDoes();

        setOnPreferenceClickListener(preference -> {
            if (SessionBudget.canUndoClear()) {
                undoClear(context);
                return true;
            }
            // Starting today over hands back a spent budget, which loosens it, and the day
            // starting over is the very thing a loosening would wait for.
            if (Settings.SESSION_BUDGET_WAIT_TO_LOOSEN.savedValue()) {
                SettingsActionBanner.showNotice(context, L10n.f(context,
                        "Today can't start over while Wait a day to loosen the budget is on. The day starts over at %1$s.",
                        SessionLockOverlay.timeLabel(BudgetChanges.nextDayAt())));
                return true;
            }
            if (!SessionBudget.clear()) {
                SettingsActionBanner.showNotice(context, L10n.f(context,
                        "Today's budget is locked. The day starts over at %1$s.",
                        SessionLockOverlay.resetTimeLabel()));
                return true;
            }
            SessionLockOverlay.sync();
            if (afterChange != null) afterChange.run();
            setSummary(L10n.t(context, UNDO_SUMMARY));
            SettingsActionBanner.showUndo(context, L10n.t(context, "Today starts again"),
                    () -> undoClear(context));
            return true;
        });
    }

    /** Says what a tap does now: start today over, or put back a clear that can still be taken back. */
    public void showWhatATapDoes() {
        setSummary(L10n.t(getContext(), SessionBudget.canUndoClear() ? UNDO_SUMMARY : CLEAR_SUMMARY));
    }

    private void undoClear(Context context) {
        boolean back = SessionBudget.undoClear();
        SessionLockOverlay.sync();
        if (afterChange != null) afterChange.run();
        setSummary(L10n.t(context, CLEAR_SUMMARY));
        SettingsActionBanner.showNotice(context, L10n.t(context, back
                ? "Today is back where it was"
                : "Today has moved on, so there's nothing to put back"));
    }
}
