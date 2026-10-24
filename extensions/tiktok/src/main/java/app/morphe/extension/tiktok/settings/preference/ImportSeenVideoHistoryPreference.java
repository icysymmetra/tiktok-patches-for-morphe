/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.view.View;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.seen.WatchHistoryImport;
import app.morphe.extension.tiktok.settings.L10n;
import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** The picker and worker keep the account that asked, including when settings is recreated. */
@SuppressWarnings("deprecation")
public final class ImportSeenVideoHistoryPreference extends Preference {
    static final int REQUEST_IMPORT = 7321;
    private static final String ACCOUNT_STATE = "hushfeed_watch_import_account";
    private static final String ZONE_STATE = "hushfeed_watch_import_zone";
    private static final String SUMMARY =
            "Use this account's TikTok JSON export, up to %1$s MB and %2$s entries. Dates without a time zone use this phone's time zone.";
    private static final String SIGN_IN = "Sign in to TikTok before importing watch history.";
    private static final String ACCOUNT_CHANGED =
            "Your TikTok account changed. Choose the file again for this account.";
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final CopyOnWriteArrayList<WeakReference<ImportSeenVideoHistoryPreference>> ROWS =
            new CopyOnWriteArrayList<>();
    private static volatile PendingPick pending;
    private static String busyLine;
    /** The file read the row waits on, until the import itself starts. Main thread only. */
    private static DocumentOperation reading;

    private static final class PendingPick {
        final SeenVideoHistory.ImportTarget target;
        final String account;
        final TimeZone zone;

        PendingPick(SeenVideoHistory.ImportTarget target, String account, TimeZone zone) {
            this.target = target;
            this.account = account;
            this.zone = (TimeZone) zone.clone();
        }
    }

    public ImportSeenVideoHistoryPreference(Context context) {
        super(context);
        setKey("action_import_seen_video_history");
        setTitle(L10n.t(context, "Import watch history"));
        ROWS.add(new WeakReference<>(this));
        refreshState();
        setOnPreferenceClickListener(preference -> {
            // The row comes back during a read once the file app has kept it waiting, as the
            // way to stop.
            if (offersStop()) stopWaiting(getContext());
            else if (!BUSY.get() && pending == null && !heldAfterStop()) {
                TikTokPreferenceFragment.openSeenVideoHistoryPicker();
            }
            return true;
        });
    }

    static void pickFile(TikTokPreferenceFragment fragment) {
        if (BUSY.get() || pending != null) return;
        SeenVideoHistory.ImportTarget target = SeenVideoHistory.captureImportTarget();
        if (target == null) {
            SettingsActionBanner.showNotice(fragment.getActivity(), L10n.t(SIGN_IN));
            return;
        }
        pending = new PendingPick(target, target.accountKey(), TimeZone.getDefault());
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .setType("application/json");
        refreshRows();
        try {
            fragment.startActivityForResult(intent, REQUEST_IMPORT);
        } catch (RuntimeException failure) {
            pending = null;
            refreshRows();
            Logger.printException(() -> "Could not open watch-history file picker", failure);
            SettingsActionBanner.showNotice(fragment.getActivity(), L10n.t(
                    "This phone has no file picker, so there's no way to choose a file here"));
        }
    }

    static void savePickerState(Bundle state) {
        PendingPick pick = pending;
        if (pick != null) {
            state.putString(ACCOUNT_STATE, pick.account);
            state.putString(ZONE_STATE, pick.zone.getID());
        }
    }

    static void restorePickerState(Bundle state) {
        if (pending != null || state == null) return;
        String account = state.getString(ACCOUNT_STATE);
        String zone = state.getString(ZONE_STATE);
        if (account == null || zone == null) return;
        SeenVideoHistory.ImportTarget target = SeenVideoHistory.captureImportTarget();
        if (target != null && !account.equals(target.accountKey())) target = null;
        pending = new PendingPick(target, account, TimeZone.getTimeZone(zone));
        refreshRows();
    }

    static boolean onResult(TikTokPreferenceFragment fragment, int request, int result, Intent data) {
        if (request != REQUEST_IMPORT) return false;
        PendingPick pick = pending;
        pending = null;
        if (result != Activity.RESULT_OK || data == null || data.getData() == null) {
            refreshRows();
            return true;
        }
        Activity activity = fragment.getActivity();
        // On process recreation TikTok may not have supplied its account yet. Resolve that
        // saved request only when its original account is known again, before opening the file.
        SeenVideoHistory.ImportTarget recovered = pick == null ? null : pick.target;
        if (pick != null && recovered == null && pick.account.equals(SignedInUser.id())) {
            recovered = SeenVideoHistory.captureImportTarget();
        }
        final SeenVideoHistory.ImportTarget target = recovered;
        if (pick == null || target == null || !pick.account.equals(target.accountKey())
                || !target.isCurrentAccount()) {
            refreshRows();
            SettingsActionBanner.showNotice(activity, L10n.t(ACCOUNT_CHANGED));
            return true;
        }
        if (activity == null || !BUSY.compareAndSet(false, true)) {
            refreshRows();
            return true;
        }
        Context context = activity.getApplicationContext();
        WeakReference<Activity> window = new WeakReference<>(activity);
        Uri uri = data.getData();
        busyLine = "Reading watch history";
        refreshRows();
        SettingsActionBanner.showNotice(activity, L10n.t(context, busyLine));
        // A file app can keep the read waiting as long as it likes, so it gets a worker the row
        // can stop waiting on rather than one of the shared pool's.
        reading = DocumentOperation.start(DocumentOperation.Kind.WATCH_HISTORY_FILE, file -> {
            try {
                WatchHistoryImport.Records records = WatchHistoryImport.read(
                        file.openForRead(context.getContentResolver(), uri), pick.zone, System.currentTimeMillis());
                // Stopped while the file app had it: the stop gave the row back and said so.
                if (!file.commit()) return;
                Utils.runOnMainThread(() -> {
                    reading = null;
                    busyLine = "Importing watch history";
                    refreshRows();
                });
                SeenVideoHistory.importHistory(target, records, outcome -> {
                    finish();
                    SettingsActionBanner.showNotice(TikTokPreferenceFragment.reportWindow(window, context),
                            outcomeMessage(context, target, outcome));
                });
            } catch (Exception failure) {
                if (file.isStopped()) return;
                Logger.printException(() -> "Could not read watch-history import", failure);
                Utils.runOnMainThread(() -> {
                    finish();
                    SettingsActionBanner.showNotice(TikTokPreferenceFragment.reportWindow(window, context),
                            failureMessage(context, failure));
                });
            }
        }, ImportSeenVideoHistoryPreference::refreshRows);
        if (reading == null) {
            // A file app still holding a stopped read keeps the next one from starting, and only
            // it letting go, or TikTok restarting, frees it.
            boolean held = DocumentOperation.busy(DocumentOperation.Kind.WATCH_HISTORY_FILE);
            finish();
            SettingsActionBanner.showNotice(activity, held
                    ? L10n.t(context, "The file app still has the last file. Try again once it lets go, or restart TikTok.")
                    : L10n.t(context, "Couldn't start the import. Try again in a moment."));
        }
        return true;
    }

    /** Whether the read has kept the row waiting long enough to offer a stop, and stopping still can. */
    private static boolean offersStop() {
        DocumentOperation acting = reading;
        return acting != null && acting.offersStop();
    }

    private static boolean heldAfterStop() {
        return DocumentOperation.heldAfterStop(DocumentOperation.Kind.WATCH_HISTORY_FILE);
    }

    /**
     * Stops waiting on a file app that has kept the read waiting. Nothing is imported until the
     * file has been read, so the row comes back straight away. Once the read is done the import
     * is under way and the tap does nothing.
     */
    private static void stopWaiting(Context window) {
        DocumentOperation acting = reading;
        if (acting == null || acting.stop() != DocumentOperation.Stage.STOPPED) {
            refreshRows();
            return;
        }
        finish();
        SettingsActionBanner.showNotice(window, L10n.t(window,
                "Stopped waiting for the file app. Nothing was imported."));
    }

    private static String outcomeMessage(Context context, SeenVideoHistory.ImportTarget target,
                                         SeenVideoHistory.ImportResult outcome) {
        if (outcome.status == SeenVideoHistory.ImportStatus.ACCOUNT_CHANGED) return L10n.t(context, ACCOUNT_CHANGED);
        if (outcome.status == SeenVideoHistory.ImportStatus.SUPERSEDED) return L10n.t(context,
                "Your seen history changed before the import finished. Choose the file again.");
        if (outcome.status == SeenVideoHistory.ImportStatus.FAILED) return L10n.t(context,
                "Couldn't import watch history. Your saved history is unchanged.");
        String added = L10n.quantity(context, outcome.imported,
                "%1$s watched video added.", "%1$s watched videos added.",
                NumberFormat.getInstance().format(outcome.imported));
        String skipped = L10n.quantity(context, outcome.skipped,
                "%1$s entry skipped.", "%1$s entries skipped.",
                NumberFormat.getInstance().format(outcome.skipped));
        String explanation = L10n.f(context,
                "Skipped entries are invalid, expired, already recorded, or older than the newest %1$s videos.",
                NumberFormat.getInstance().format(SeenVideoHistory.MAX_RECORDS));
        String recovery = outcome.undoRetired ? " " + L10n.t(context,
                "Importing new history ended Undo for the earlier clear. You can clear the imported history.") : "";
        String account = target.isCurrentAccount() ? "" : " " + L10n.t(context,
                "Watch history was imported for the account that chose the file. Switch back to see it.");
        return added + " " + skipped + " " + explanation + recovery + account;
    }

    static String failureMessage(Context context, Exception failure) {
        if (!(failure instanceof WatchHistoryImport.Rejected)) return L10n.t(context,
                "Couldn't read that file. Choose it again.");
        switch (((WatchHistoryImport.Rejected) failure).reason) {
            case TOO_LARGE: return L10n.f(context,
                    "That file is larger than %1$s MB. Choose a smaller watch-history export.",
                    NumberFormat.getInstance().format(WatchHistoryImport.MAX_BYTES / (1024 * 1024)));
            case TOO_MANY: return L10n.f(context, "That file has more than %1$s watch-history entries.",
                    NumberFormat.getInstance().format(SeenVideoHistory.MAX_RECORDS));
            case UNSUPPORTED: return L10n.t(context, "That JSON file doesn't contain the supported TikTok watch history.");
            case DAMAGED: return L10n.t(context, "That watch-history file is damaged. Nothing was imported.");
            default: return L10n.t(context, "Couldn't read that file. Choose it again.");
        }
    }

    private static void finish() {
        BUSY.set(false);
        busyLine = null;
        reading = null;
        refreshRows();
        ClearSeenVideoHistoryPreference.refreshRows();
    }

    private static void refreshRows() {
        for (WeakReference<ImportSeenVideoHistoryPreference> held : ROWS) {
            ImportSeenVideoHistoryPreference row = held.get();
            if (row == null) ROWS.remove(held);
            else row.refreshState();
        }
    }

    private void refreshState() {
        boolean signedIn = SignedInUser.id() != null;
        boolean stoppable = offersStop();
        boolean held = !BUSY.get() && heldAfterStop();
        setEnabled(stoppable || signedIn && !BUSY.get() && pending == null && !held);
        setSummary(stoppable ? L10n.t(getContext(), "Still waiting for the file app. Tap to stop waiting.")
                : busyLine != null ? L10n.t(getContext(), busyLine)
                : held ? L10n.t(getContext(),
                        "Waiting for the file app to let go of the last file. Restart TikTok if it doesn't.")
                : signedIn ? L10n.f(getContext(), SUMMARY,
                        NumberFormat.getInstance().format(WatchHistoryImport.MAX_BYTES / (1024 * 1024)),
                        NumberFormat.getInstance().format(SeenVideoHistory.MAX_RECORDS))
                : L10n.t(getContext(), SIGN_IN));
    }

    @Override protected void onBindView(View view) {
        refreshState();
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }
}
