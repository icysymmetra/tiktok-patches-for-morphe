/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.preference.Preference;
import android.view.View;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.seen.SeenHistoryFile;
import app.morphe.extension.tiktok.seen.SeenVideoHistory;
import app.morphe.extension.tiktok.settings.L10n;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Save seen history to a file and Restore seen history from a file: the signed-in account's
 * seen videos written out in {@link SeenHistoryFile}'s format and merged back in later, on this
 * phone or another.
 *
 * <p>Both rows capture the account when they open the picker, keep it through a rebuilt
 * settings page, and act only while it's still signed in. A save reads the history on the
 * history worker, so the file is one moment of it. A restore reads and checks the whole file
 * before its gate, and the merge rechecks the account and the history's generation inside its
 * transaction: a stop before the gate, or an account switch before the commit, changes no
 * account's record. A file app that stalls is handled the way Back up and Restore handle one,
 * through {@link DocumentOperation}: a save the file app already has stays open, and the row
 * says so, until the write returns.
 *
 * <p>The file is the only place the ids go. Nothing here logs one, and settings backups never
 * carry the history.
 */
@SuppressWarnings("deprecation")
public final class SeenHistoryFilePreference extends Preference {
    static final int REQUEST_SAVE = 7331;
    static final int REQUEST_RESTORE = 7332;
    static final String SAVE_KEY = "action_save_seen_history_file";
    static final String RESTORE_KEY = "action_restore_seen_history_file";
    private static final String REQUEST_STATE = "hushfeed_seen_file_request";
    private static final String ACCOUNT_STATE = "hushfeed_seen_file_account";
    private static final DocumentOperation.Kind KIND = DocumentOperation.Kind.SEEN_HISTORY_FILE;
    private static final String SIGN_IN = "Sign in to TikTok before saving or restoring seen history.";
    private static final String ACCOUNT_CHANGED =
            "Your TikTok account changed. Choose the file again for this account.";
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    /**
     * Which run holds BUSY. A stop gives the rows back before its worker returns, and that
     * worker's late outcome must not give them back again under a later run.
     */
    private static final AtomicInteger RUN = new AtomicInteger();
    private static final CopyOnWriteArrayList<WeakReference<SeenHistoryFilePreference>> ROWS =
            new CopyOnWriteArrayList<>();
    private static volatile PendingPick pending;
    /** The row the run belongs to, its line, and the file read or write it waits on. Main thread only. */
    private static int busyRequest;
    private static String busyLine;
    private static DocumentOperation operation;

    private static final class PendingPick {
        final int request;
        final SeenVideoHistory.ImportTarget target;
        final String account;

        PendingPick(int request, SeenVideoHistory.ImportTarget target, String account) {
            this.request = request;
            this.target = target;
            this.account = account;
        }
    }

    private final int request;

    private SeenHistoryFilePreference(Context context, int request) {
        super(context);
        this.request = request;
        setKey(request == REQUEST_SAVE ? SAVE_KEY : RESTORE_KEY);
        setTitle(L10n.t(context, request == REQUEST_SAVE
                ? "Save seen history to a file" : "Restore seen history from a file"));
        ROWS.add(new WeakReference<>(this));
        refreshState();
        setOnPreferenceClickListener(preference -> {
            // The acting row comes back once the file app has kept it waiting, as the way to stop.
            if (offersStop(request)) stopWaiting(getContext());
            else if (!BUSY.get() && pending == null && !heldAfterStop()) {
                TikTokPreferenceFragment.openSeenHistoryFilePicker(request);
            }
            return true;
        });
    }

    public static SeenHistoryFilePreference save(Context context) {
        return new SeenHistoryFilePreference(context, REQUEST_SAVE);
    }

    public static SeenHistoryFilePreference restore(Context context) {
        return new SeenHistoryFilePreference(context, REQUEST_RESTORE);
    }

    /** The stamp the other exports take, and nothing that names the account. */
    static String suggestedName() {
        return "metra-seen-history-"
                + app.morphe.extension.shared.settings.preference.LogBufferManager.fileTimestamp()
                + ".json";
    }

    static void pickFile(TikTokPreferenceFragment fragment, int request) {
        if (BUSY.get() || pending != null) return;
        SeenVideoHistory.ImportTarget target = SeenVideoHistory.captureImportTarget();
        if (target == null) {
            // Signed out since the row was drawn: the row says so now.
            refreshRows();
            return;
        }
        boolean saving = request == REQUEST_SAVE;
        Intent intent = new Intent(saving ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json");
        if (saving) intent.putExtra(Intent.EXTRA_TITLE, suggestedName());
        else intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        pending = new PendingPick(request, target, target.accountKey());
        refreshRows();
        try {
            fragment.startActivityForResult(intent, request);
        } catch (RuntimeException failure) {
            pending = null;
            refreshRows();
            Logger.printException(() -> "Could not open the seen-history file picker", failure);
            SettingsActionBanner.showNotice(fragment.getActivity(), L10n.t(
                    "This phone has no file picker, so there's no way to choose a file here"));
        }
    }

    static void savePickerState(Bundle state) {
        PendingPick pick = pending;
        if (pick != null) {
            state.putInt(REQUEST_STATE, pick.request);
            state.putString(ACCOUNT_STATE, pick.account);
        }
    }

    static void restorePickerState(Bundle state) {
        if (pending != null || state == null) return;
        int request = state.getInt(REQUEST_STATE, 0);
        String account = state.getString(ACCOUNT_STATE);
        if (request != REQUEST_SAVE && request != REQUEST_RESTORE || account == null) return;
        SeenVideoHistory.ImportTarget target = SeenVideoHistory.captureImportTarget();
        if (target != null && !account.equals(target.accountKey())) target = null;
        pending = new PendingPick(request, target, account);
        refreshRows();
    }

    static boolean onResult(TikTokPreferenceFragment fragment, int request, int result, Intent data) {
        if (request != REQUEST_SAVE && request != REQUEST_RESTORE) return false;
        PendingPick pick = pending;
        pending = null;
        Activity activity = fragment.getActivity();
        Uri uri = result == Activity.RESULT_OK && data != null ? data.getData() : null;
        if (uri == null) {
            refreshRows();
            return true;
        }
        boolean saving = request == REQUEST_SAVE;
        boolean ours = pick != null && pick.request == request;
        // On process recreation TikTok may not have supplied its account yet. The saved request
        // is resolved only once its own account is known again, before the file is touched.
        SeenVideoHistory.ImportTarget recovered = ours ? pick.target : null;
        if (ours && recovered == null && pick.account.equals(SignedInUser.id())) {
            recovered = SeenVideoHistory.captureImportTarget();
        }
        final SeenVideoHistory.ImportTarget target = recovered;
        if (!ours || target == null || !pick.account.equals(target.accountKey())
                || !target.isCurrentAccount()) {
            if (saving) discardUnwritten(activity, uri);
            refreshRows();
            SettingsActionBanner.showNotice(activity, L10n.t(ACCOUNT_CHANGED));
            return true;
        }
        if (activity == null || !BUSY.compareAndSet(false, true)) {
            if (saving) discardUnwritten(activity, uri);
            refreshRows();
            return true;
        }
        int run = RUN.incrementAndGet();
        Context context = activity.getApplicationContext();
        WeakReference<Activity> window = new WeakReference<>(activity);
        busyRequest = request;
        busyLine = saving ? "Saving seen history" : "Reading the seen-history file";
        refreshRows();
        SettingsActionBanner.showNotice(activity, L10n.t(context, busyLine));
        // A file app can keep a read or write waiting as long as it likes, so it gets a worker
        // the row can stop waiting on rather than one of the shared pool's.
        DocumentOperation started = DocumentOperation.start(KIND, file -> {
            if (saving) save(context, window, uri, target, file, run);
            else restore(context, window, uri, target, file, run);
        }, SeenHistoryFilePreference::refreshRows);
        if (started == null) {
            // A file app still holding a stopped read or write keeps the next one from starting,
            // and only it letting go, or TikTok restarting, frees it.
            boolean held = DocumentOperation.busy(KIND);
            if (saving) discardUnwritten(activity, uri);
            finishRun(run);
            SettingsActionBanner.showNotice(activity, held
                    ? L10n.t(context, "The file app still has the last file. Try again once it lets go, or restart TikTok.")
                    : saving ? L10n.t(context, "Couldn't start the export. Try again in a moment.")
                    : L10n.t(context, "Couldn't start the import. Try again in a moment."));
            return true;
        }
        operation = started;
        return true;
    }

    /**
     * Reads the account's history at one moment and writes it out. The file app is handed the
     * bytes only past {@link DocumentOperation#publish}; a stop before that leaves the file the
     * picker made empty and removes it, and one after it leaves the outcome open until the
     * write returns, which is when this reports it.
     */
    private static void save(Context context, WeakReference<Activity> window, Uri uri,
            SeenVideoHistory.ImportTarget target, DocumentOperation file, int run) {
        ContentResolver resolver = context.getContentResolver();
        // Whether the write has the file. Before that, a file the user chose to replace still
        // holds what it held, so a save that doesn't happen leaves it alone.
        boolean opened = false;
        try {
            SeenVideoHistory.Snapshot snapshot = SeenVideoHistory.snapshot(target);
            SeenHistoryFile.Encoded encoded = SeenHistoryFile.encode(snapshot.videos, snapshot.takenAt);
            // Nothing is written for an account that's no longer the one signed in.
            if (!target.isCurrentAccount()) throw new SeenVideoHistory.AccountChanged();
            if (!file.publish()) {
                DocumentOperation.removeUnsaved(resolver, uri, false);
                return;
            }
            try (OutputStream output = file.openForWrite(resolver, uri, "wt")) {
                opened = true;
                output.write(encoded.bytes);
            }
            // Saved, and a stop from here on can't say otherwise.
            file.finish();
            report(window, context, run, L10n.quantity(context, encoded.rows,
                    "Saved 1 seen video to the file", "Saved %1$s seen videos to the file",
                    NumberFormat.getInstance().format(encoded.rows)));
        } catch (Exception | OutOfMemoryError failure) {
            // Stopped before the file app had anything: the stop has said what happened.
            if (file.stage() == DocumentOperation.Stage.STOPPED) {
                DocumentOperation.removeUnsaved(resolver, uri, false);
                return;
            }
            DocumentOperation.logFailure(file, () -> "Could not save the seen-history file", failure);
            String message = failure instanceof SeenVideoHistory.AccountChanged
                    ? L10n.t(context, ACCOUNT_CHANGED)
                    : file.stage() == DocumentOperation.Stage.STOPPED_WHILE_PUBLISHING
                    ? L10n.t(context, "The export wasn't saved. Export again when the file app is ready.")
                    : L10n.t(context, "Couldn't save the seen-history file. Try again.");
            if (!DocumentOperation.removeUnsaved(resolver, uri, opened)) {
                message += " " + L10n.t(context,
                        "The partial file couldn't be removed. Delete it from the folder you chose.");
            }
            report(window, context, run, message);
        }
    }

    /**
     * Reads and checks the whole file, then merges it into the captured account. Nothing is
     * merged until the file has been read and passed {@link DocumentOperation#commit}, so a
     * stop before then dispatches no write at all.
     */
    private static void restore(Context context, WeakReference<Activity> window, Uri uri,
            SeenVideoHistory.ImportTarget target, DocumentOperation file, int run) {
        try {
            SeenHistoryFile.Contents contents =
                    SeenHistoryFile.read(file.openForRead(context.getContentResolver(), uri));
            // Stopped while the file app had it: the stop gave the rows back and said so.
            if (!file.commit()) return;
            Utils.runOnMainThread(() -> {
                if (RUN.get() != run) return;
                busyLine = "Restoring seen history";
                refreshRows();
            });
            SeenVideoHistory.importHistory(target, contents.records(),
                    outcome -> report(window, context, run, outcomeMessage(context, contents, outcome)));
        } catch (Exception | OutOfMemoryError failure) {
            if (file.isStopped()) return;
            Logger.printException(() -> "Could not read the seen-history file", failure);
            report(window, context, run, failureMessage(context, failure));
        }
    }

    static String outcomeMessage(Context context, SeenHistoryFile.Contents contents,
            SeenVideoHistory.ImportResult outcome) {
        if (outcome.status == SeenVideoHistory.ImportStatus.ACCOUNT_CHANGED) return L10n.t(context, ACCOUNT_CHANGED);
        if (outcome.status == SeenVideoHistory.ImportStatus.SUPERSEDED) return L10n.t(context,
                "Your seen history changed before the import finished. Choose the file again.");
        if (outcome.status == SeenVideoHistory.ImportStatus.FAILED) return L10n.t(context,
                "Couldn't restore seen history. Your saved history is unchanged.");
        NumberFormat numbers = NumberFormat.getInstance();
        String newest = numbers.format(SeenVideoHistory.MAX_RECORDS);
        // A video whose date the file moved later was already there, so it isn't one added. A
        // repeat in the file isn't a video at all, so it's left out of both.
        int already = outcome.alreadyRecorded + outcome.refreshed;
        int added = outcome.imported - outcome.refreshed;
        StringBuilder notes = new StringBuilder();
        if (already > 0) notes.append(' ').append(L10n.quantity(context, already,
                "%1$s video was already recorded.", "%1$s videos were already recorded.",
                numbers.format(already)));
        if (outcome.expired > 0) notes.append(' ').append(L10n.quantity(context, outcome.expired,
                "%1$s video was older than Forget seen videos after allows.",
                "%1$s videos were older than Forget seen videos after allows.",
                numbers.format(outcome.expired)));
        if (outcome.future > 0) notes.append(' ').append(L10n.quantity(context, outcome.future,
                "%1$s video had a date later than this phone's clock.",
                "%1$s videos had dates later than this phone's clock.",
                numbers.format(outcome.future)));
        if (outcome.capped > 0) notes.append(' ').append(L10n.quantity(context, outcome.capped,
                "%1$s video didn't fit in the newest %2$s.", "%1$s videos didn't fit in the newest %2$s.",
                numbers.format(outcome.capped), newest));
        if (outcome.displaced > 0) notes.append(' ').append(L10n.quantity(context, outcome.displaced,
                "%1$s older seen video was dropped to keep the newest %2$s.",
                "%1$s older seen videos were dropped to keep the newest %2$s.",
                numbers.format(outcome.displaced), newest));
        if (outcome.undoRetired) notes.append(' ').append(L10n.t(context,
                "Importing new history ended Undo for the earlier clear. You can clear the imported history."));
        String addedText = numbers.format(added);
        // One sentence on its own takes no full stop; it takes one with others after it.
        if (notes.length() == 0) return L10n.quantity(context, added,
                "Added 1 seen video", "Added %1$s seen videos", addedText);
        return L10n.quantity(context, added,
                "Added 1 seen video.", "Added %1$s seen videos.", addedText) + notes;
    }

    static String failureMessage(Context context, Throwable failure) {
        if (!(failure instanceof SeenHistoryFile.Rejected)) return L10n.t(context,
                "Couldn't read that file. Choose it again.");
        NumberFormat numbers = NumberFormat.getInstance();
        switch (((SeenHistoryFile.Rejected) failure).reason) {
            case TOO_LARGE: return L10n.f(context,
                    "That file is larger than %1$s MB. Choose a seen-history file saved by Metra.",
                    numbers.format(SeenHistoryFile.MAX_BYTES / (1024 * 1024)));
            case TOO_MANY: return L10n.f(context,
                    "That file lists more than %1$s videos. Nothing was restored.",
                    numbers.format(SeenHistoryFile.MAX_ROWS));
            case UNSUPPORTED: return L10n.t(context,
                    "That isn't a seen-history file saved by Metra. For TikTok's own data export, use Import watch history.");
            case NEWER: return L10n.t(context,
                    "That file was saved by a newer Metra. Update Metra, then try again.");
            case DAMAGED: return L10n.t(context,
                    "That seen-history file is damaged or was edited. Nothing was restored.");
            default: return L10n.t(context, "Couldn't read that file. Choose it again.");
        }
    }

    /** Ends the run on the main thread and says how it went in the window open now. */
    private static void report(WeakReference<Activity> window, Context context, int run, String message) {
        Utils.runOnMainThread(() -> {
            finishRun(run);
            SettingsActionBanner.showNotice(TikTokPreferenceFragment.reportWindow(window, context), message);
        });
    }

    /** Gives the rows back, unless a stop already did and they may be a later run's now. */
    private static void finishRun(int run) {
        if (RUN.get() == run) release();
        else refreshRows();
        ClearSeenVideoHistoryPreference.refreshRows();
    }

    private static void release() {
        BUSY.set(false);
        busyRequest = 0;
        busyLine = null;
        operation = null;
        refreshRows();
    }

    /** Only the picker's new document belongs here; a restore reads a file the user already had. */
    private static void discardUnwritten(Context window, Uri uri) {
        Context context = window == null ? Utils.getContext() : window.getApplicationContext();
        ContentResolver resolver = context == null ? null : context.getContentResolver();
        Utils.runOnOwnThread("Metra-SeenHistoryCleanup",
                () -> DocumentOperation.removeUnsaved(resolver, uri, false));
    }

    /** Whether this row's run has kept it waiting long enough to offer a stop, and stopping still can. */
    private static boolean offersStop(int request) {
        DocumentOperation acting = operation;
        return BUSY.get() && busyRequest == request && acting != null && acting.offersStop();
    }

    private static boolean heldAfterStop() {
        return DocumentOperation.heldAfterStop(KIND);
    }

    /**
     * Stops waiting on a file app that has kept the run waiting. A restore stopped before its
     * gate changes nothing and a save stopped before the handover writes nothing. Once the file
     * app has the save, nothing here can say whether it kept it, so the row says that much and
     * waits for the file app to let go, and the worker reports the outcome when it does.
     */
    private static void stopWaiting(Context window) {
        DocumentOperation acting = operation;
        if (acting == null) {
            refreshRows();
            return;
        }
        boolean saving = busyRequest == REQUEST_SAVE;
        DocumentOperation.Stage left = acting.stop();
        // Past the gate, the merge is being made or the save's outcome is on its way.
        if (left != DocumentOperation.Stage.STOPPED && left != DocumentOperation.Stage.STOPPED_WHILE_PUBLISHING) {
            refreshRows();
            return;
        }
        RUN.incrementAndGet();
        release();
        SettingsActionBanner.showNotice(window, !saving
                ? L10n.t(window, "Stopped waiting for the file app. Nothing was changed.")
                : left == DocumentOperation.Stage.STOPPED
                ? L10n.t(window, "Stopped waiting for the file app. Nothing was exported.")
                : L10n.t(window, "Stopped waiting for the file app. It hasn't said yet whether the export was saved."));
    }

    static void refreshRows() {
        for (WeakReference<SeenHistoryFilePreference> held : ROWS) {
            SeenHistoryFilePreference row = held.get();
            if (row == null) ROWS.remove(held);
            else row.refreshState();
        }
    }

    private void refreshState() {
        Context context = getContext();
        boolean signedIn = SignedInUser.id() != null;
        boolean busy = BUSY.get();
        boolean acting = busy && busyRequest == request;
        boolean stoppable = offersStop(request);
        boolean held = !busy && heldAfterStop();
        setEnabled(stoppable || signedIn && !busy && pending == null && !held);
        setSummary(stoppable ? L10n.t(context, "Still waiting for the file app. Tap to stop waiting.")
                : acting && busyLine != null ? L10n.t(context, busyLine)
                : held ? L10n.t(context,
                        "Waiting for the file app to let go of the last file. Restart TikTok if it doesn't.")
                : signedIn ? restingSummary(context)
                : L10n.t(context, SIGN_IN));
    }

    /** Says whose record the row acts on, and for Save, that the file is viewing history. */
    private String restingSummary(Context context) {
        String handle = SignedInUser.handle();
        if (request == REQUEST_SAVE) {
            return handle != null
                    ? L10n.f(context, "Save the videos @%1$s has seen, and when, to a JSON file. "
                            + "Anyone who opens the file can see that viewing history.", handle)
                    : L10n.t(context, "Save this account's seen videos, and when they were watched, to a "
                            + "JSON file. Anyone who opens the file can see that viewing history.");
        }
        return handle != null
                ? L10n.f(context, "Add a seen-history file saved by Metra to the videos @%1$s has seen. "
                        + "A video in both keeps the newer date.", handle)
                : L10n.t(context, "Add a seen-history file saved by Metra to this account's seen videos. "
                        + "A video in both keeps the newer date.");
    }

    @Override protected void onBindView(View view) {
        refreshState();
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }
}
