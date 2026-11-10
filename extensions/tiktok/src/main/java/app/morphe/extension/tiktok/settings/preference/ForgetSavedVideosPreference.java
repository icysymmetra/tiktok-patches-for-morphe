/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.view.View;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.download.SavedVideoArchive;
import app.morphe.extension.tiktok.settings.L10n;

import java.lang.ref.WeakReference;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Forgets the record behind "Check for already-saved videos", with an Undo for as long as the
 * settings banner offers one. The saved files and every setting stay. The row is there with the
 * check off too, because turning the check off stops new entries and erases nothing.
 */
@SuppressWarnings("deprecation")
public final class ForgetSavedVideosPreference extends Preference
        implements app.morphe.extension.shared.settings.preference.ImmediateAction {
    @Override public boolean actsOnTap() { return true; }

    static final String FORGET_TITLE = "Forget saved videos";
    static final String UNDO_TITLE = "Undo forgetting saved videos";
    static final String SUMMARY = "Forget which videos Metra saved here, so the already-saved "
            + "check starts over. The saved files and your settings stay. Turning the check off "
            + "doesn't erase this record.";

    private static final List<WeakReference<ForgetSavedVideosPreference>> ROWS =
            new CopyOnWriteArrayList<>();
    /** The forget whose Undo is on offer, or 0. Main thread. */
    private static long offered;
    /** What the row says while a forget or an Undo runs, or null. Main thread. */
    private static String working;

    public ForgetSavedVideosPreference(Context context) {
        super(context);
        setKey("action_forget_saved_videos");
        ROWS.add(new WeakReference<>(this));
        applyState();
        setOnPreferenceClickListener(preference -> {
            if (working != null) return true;
            if (offered != 0 && SavedVideoArchive.canUndo(offered)) {
                undo(context, offered);
            } else {
                forget(context);
            }
            return true;
        });
    }

    private static void forget(Context context) {
        working = "Forgetting saved videos";
        refreshRows();
        Context app = context.getApplicationContext();
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            long[] generation = new long[1];
            SavedVideoArchive.ForgetResult result = SavedVideoArchive.forget(app, generation);
            Utils.runOnMainThread(() -> {
                working = null;
                if (result == SavedVideoArchive.ForgetResult.FORGOTTEN) {
                    long forgotten = generation[0];
                    offered = forgotten;
                    String notice = L10n.t(context, "Saved videos forgotten. The files are still there.");
                    SettingsActionBanner.showUndo(context, notice,
                            () -> {
                                // The row's Undo may have run already, or be running: a second
                                // one ended on "Too late" after a restore that worked.
                                if (working == null && offered == forgotten) undo(context, forgotten);
                            });
                    // The Undo lives as long as the banner that offers it, a longer
                    // accessibility timeout included, and its rows go with it.
                    Utils.runOnMainThreadDelayed(() -> expire(forgotten), SettingsUi.feedbackTimeout(
                            context, (int) SettingsActionBanner.visibleMs(notice), true));
                } else if (result == SavedVideoArchive.ForgetResult.NOTHING_SAVED) {
                    offered = 0;
                    SettingsActionBanner.showNotice(context, L10n.t(context,
                            "There were no saved videos to forget"));
                } else {
                    SettingsActionBanner.showNotice(context, L10n.t(context,
                            "Couldn't forget the saved videos. Try again."));
                }
                refreshRows();
            });
        });
        if (!accepted) refused(context, "Couldn't forget the saved videos. Try again.");
    }

    private static void undo(Context context, long generation) {
        working = "Putting the saved videos back";
        refreshRows();
        Context app = context.getApplicationContext();
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            SavedVideoArchive.UndoResult result = SavedVideoArchive.undo(app, generation);
            Utils.runOnMainThread(() -> {
                working = null;
                String message;
                if (result == SavedVideoArchive.UndoResult.RESTORED) {
                    if (offered == generation) offered = 0;
                    message = L10n.t(context, "Saved videos put back");
                } else if (result == SavedVideoArchive.UndoResult.EXPIRED) {
                    if (offered == generation) offered = 0;
                    message = L10n.t(context, "Too late to put the saved videos back");
                } else {
                    message = L10n.t(context, "Couldn't put the saved videos back. Try again.");
                }
                SettingsActionBanner.showNotice(context, message);
                refreshRows();
            });
        });
        if (!accepted) refused(context, "Couldn't put the saved videos back. Try again.");
    }

    /**
     * The background queue was full, so the task never started and nothing was changed. The row
     * comes back to life, and an Undo on offer stays on offer.
     */
    private static void refused(Context context, String message) {
        working = null;
        SettingsActionBanner.showNotice(context, L10n.t(context, message));
        refreshRows();
    }

    private static void expire(long generation) {
        SavedVideoArchive.discardUndo(generation);
        if (offered == generation) offered = 0;
        refreshRows();
    }

    private static void refreshRows() {
        for (WeakReference<ForgetSavedVideosPreference> held : ROWS) {
            ForgetSavedVideosPreference row = held.get();
            if (row == null) ROWS.remove(held);
            else row.applyState();
        }
    }

    private void applyState() {
        setEnabled(working == null);
        if (working != null) {
            setTitle(FORGET_TITLE);
            setSummary(working);
        } else if (offered != 0 && SavedVideoArchive.canUndo(offered)) {
            setTitle(UNDO_TITLE);
            setSummary("Forgotten. Tap to put them back.");
        } else {
            setTitle(FORGET_TITLE);
            setSummary(SUMMARY);
        }
    }

    @Override
    protected void onBindView(View view) {
        applyState();
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(L10n.t(getContext(), title));
    }

    @Override
    public void setSummary(CharSequence summary) {
        super.setSummary(L10n.t(getContext(), summary));
    }

    /** Forgets what the row remembers between tests. */
    static void resetForTests() {
        offered = 0;
        working = null;
        ROWS.clear();
    }
}
