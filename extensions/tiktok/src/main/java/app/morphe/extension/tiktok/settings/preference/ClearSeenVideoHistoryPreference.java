/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 * Follows BlueDragon4251/tiktok-patches-for-morphe.
 */
package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.settings.L10n;
import android.content.Context;
import android.preference.Preference;
import android.view.View;

import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.seen.SeenVideoHistory;

import java.text.NumberFormat;

@SuppressWarnings("deprecation")
public final class ClearSeenVideoHistoryPreference extends Preference
        implements app.morphe.extension.shared.settings.preference.ImmediateAction {
    @Override public boolean actsOnTap() { return true; }

    static final String CLEAR_TITLE = "Clear seen videos";
    static final String UNDO_TITLE = "Undo clearing seen videos";
    static final String NOT_READY = "Still reading the record. Tap again in a moment.";
    static final String FAILED = "Couldn't undo the clear. Try again.";
    private static final java.util.List<java.lang.ref.WeakReference<ClearSeenVideoHistoryPreference>> ROWS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    public ClearSeenVideoHistoryPreference(Context context) {
        super(context);
        setKey("action_clear_seen_video_history");
        applyState(SeenVideoHistory.canUndo());
        ROWS.add(new java.lang.ref.WeakReference<>(this));

        setOnPreferenceClickListener(preference -> {
            if (SeenVideoHistory.isClearing() || SeenVideoHistory.isRestoring()) return true;
            if (SeenVideoHistory.canUndo()) {
                undoClear(context);
                return true;
            }

            SeenVideoHistory.clear(result -> {
                refreshRows();
                if (result == SeenVideoHistory.ClearResult.CLEARED) {
                    SettingsActionBanner.showUndo(context, L10n.t(context,
                                    "Seen videos cleared. You can undo until TikTok closes."),
                            () -> undoClear(context));
                } else if (result == SeenVideoHistory.ClearResult.FAILED) {
                    SettingsActionBanner.showNotice(context, L10n.t(context,
                            "Couldn't clear seen videos. Try again."));
                }
            });
            refreshRows();
            return true;
        });
    }

    static void refreshRows() {
        for (java.lang.ref.WeakReference<ClearSeenVideoHistoryPreference> held : ROWS) {
            ClearSeenVideoHistoryPreference row = held.get();
            if (row == null) ROWS.remove(held);
            else row.applyState(SeenVideoHistory.canUndo());
        }
    }

    private void undoClear(Context context) {
        SeenVideoHistory.undoClear((result, retained, requested) -> {
            if (result == SeenVideoHistory.UndoResult.SUPERSEDED) {
                refreshRows();
                return;
            }
            String message;
            if (result == SeenVideoHistory.UndoResult.RESTORED) {
                message = "Seen videos put back";
            } else if (result == SeenVideoHistory.UndoResult.PARTIAL) {
                message = L10n.f(context, "Seen videos put back: %1$s of %2$s",
                        NumberFormat.getInstance().format(retained),
                        NumberFormat.getInstance().format(requested));
            } else if (result == SeenVideoHistory.UndoResult.NONE_RETAINED) {
                message = "No cleared videos fit the current history limits.";
            } else if (result == SeenVideoHistory.UndoResult.FAILED) {
                message = FAILED;
            } else if (result == SeenVideoHistory.UndoResult.EMPTY) {
                message = "There was nothing to put back";
            } else {
                message = NOT_READY;
            }
            SettingsActionBanner.showNotice(context, L10n.t(context, message));
            refreshRows();
        });
        refreshRows();
    }

    private void applyState(boolean canUndo) {
        boolean clearing = SeenVideoHistory.isClearing();
        boolean restoring = SeenVideoHistory.isRestoring();
        setEnabled(!clearing && !restoring);
        if (restoring) {
            setTitle(UNDO_TITLE);
            setSummary("Putting seen videos back");
            return;
        }
        if (clearing) {
            setTitle(CLEAR_TITLE);
            setSummary("Clearing seen videos");
            return;
        }
        if (canUndo) {
            setTitle(UNDO_TITLE);
            setSummary("Cleared. Tap to undo before TikTok closes.");
        } else {
            setTitle(CLEAR_TITLE);
            int count = SeenVideoHistory.size();
            if (count > 0) {
                // The record is the signed-in account's alone, so the row says whose it forgets.
                String handle = SignedInUser.handle();
                String number = NumberFormat.getInstance().format(count);
                setSummary(handle != null
                        ? L10n.quantity(getContext(), count, "Forget the video @%2$s has seen.",
                                "Forget the %1$s videos @%2$s has seen.", number, handle)
                        : L10n.quantity(getContext(), count, "Forget the seen video.",
                                "Forget the %1$s seen videos.", number));
            } else {
                setSummary("No seen videos recorded yet.");
            }
        }
    }

    @Override
    protected void onBindView(View view) {
        applyState(SeenVideoHistory.canUndo());
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
}
