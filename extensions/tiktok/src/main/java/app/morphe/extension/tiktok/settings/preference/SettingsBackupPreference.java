/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.settings.L10n;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.View;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.tiktok.settings.SettingsBackup;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@SuppressWarnings("deprecation")
public final class SettingsBackupPreference extends Preference
        implements app.morphe.extension.shared.settings.preference.ImmediateAction {
    private static final int EXPORT = 7311, IMPORT = 7312, RESET = 7313, UNDO = 7314;
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    /**
     * Which run holds BUSY. Stopping a run gives the rows back before its worker has returned,
     * and that worker's return must not then give them back again under a later run.
     */
    private static final AtomicInteger RUN = new AtomicInteger();
    // Cleanup must still run when the export itself never started.
    private static final java.util.concurrent.ExecutorService EXPORT_CLEANUP =
            java.util.concurrent.Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "Metra-BackupCleanup");
                thread.setDaemon(true);
                return thread;
            });
    private static int busyAction;
    private static String runningLine;
    /** The file read or write the acting row waits on, null for Reset and Undo. Main thread only. */
    private static DocumentOperation operation;
    /** The document that read or write is for, which a backup stopped before the handover removes if it's empty. */
    private static Uri operationUri;
    /** The four rows on the page right now, so a run can take them all out of reach. */
    private static final java.util.List<java.lang.ref.WeakReference<SettingsBackupPreference>> ROWS =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Reset and Undo act on the tap. Back up and Restore open a file picker first. */
    @Override public boolean actsOnTap() {
        return rowAction == RESET || rowAction == UNDO;
    }

    private final int rowAction;
    /** What this row says when nothing is running, so the run can hand it back. */
    private final String restingSummary;
    /** The line this row shows while it is the one running, else null. */
    private String busyLine;

    static final String UNDO_SUMMARY = "Put back the settings saved before the last restore or reset.";

    private SettingsBackupPreference(TikTokPreferenceFragment fragment, int action, String title, String summary) {
        super(fragment.getActivity());
        this.rowAction = action;
        this.restingSummary = summary;
        setKey("settings_backup_" + action);
        setTitle(title);
        setSummary(summary);
        applyState();
        setOnPreferenceClickListener(preference -> {
            // The rows are disabled while a run is going, so this is the race between a tap and
            // the disable rather than the ordinary second tap it used to refuse out loud. The
            // acting row comes back once a file app has kept it waiting, as the way to stop.
            if (BUSY.get()) {
                if (offersStop()) stopWaiting(fragment.getActivity());
                return true;
            }
            if (action != RESET && action != UNDO
                    && DocumentOperation.heldAfterStop(DocumentOperation.Kind.SETTINGS_FILE)) return true;
            if (action == RESET || action == UNDO) run(fragment, action, null);
            else pickFile(fragment, action);
            return true;
        });
    }

    /** Whether this is the acting row and the file app has kept it waiting long enough to offer a stop. */
    private boolean offersStop() {
        DocumentOperation acting = operation;
        return rowAction == busyAction && acting != null && acting.offersStop();
    }

    /**
     * The four backup actions, kept together at the end of the page. They used to be added
     * without an order and so landed among the logging rows, which put Back up and Restore
     * four rows apart with unrelated switches between them.
     */
    public static void addTo(TikTokPreferenceFragment fragment, PreferenceScreen screen) {
        int order = screen.getPreferenceCount();
        for (Object[] row : new Object[][]{
                {EXPORT, "Back up settings",
                        "Saves your Metra settings and Feature Gate Lab rules to a file."},
                {IMPORT, "Restore settings",
                        "Choose a backup file. Your current settings are kept for Undo."},
                {RESET, "Reset settings",
                        "Put every setting back to its default straight away. Your current settings are kept for Undo."},
                {UNDO, "Undo last restore or reset", UNDO_SUMMARY},
        }) {
            SettingsBackupPreference preference = new SettingsBackupPreference(
                    fragment, (Integer) row[0], (String) row[1], (String) row[2]);
            preference.setOrder(order++);
            screen.addPreference(preference);
            ROWS.add(new java.lang.ref.WeakReference<>(preference));
        }
        // Where the home page's Quick setups card comes back after a long press hid it.
        QuickSetupsHomePreference quickSetups = new QuickSetupsHomePreference(fragment.getActivity());
        quickSetups.setOrder(order);
        screen.addPreference(quickSetups);
    }

    /**
     * Takes the four rows out of reach while one of them runs, and puts the running line on the
     * row that is acting.
     *
     * <p>Called with a zero action and a null line when the run ends, which re-enables them and
     * gives every row its own summary back. A screen reader hears the row as disabled and reads
     * the line as its state, which is the treatment Inbox Clear all already had.
     */
    static void setRowsBusy(int action, String running) {
        busyAction = action;
        runningLine = running;
        refreshRows();
    }

    private static void refreshRows() {
        for (java.lang.ref.WeakReference<SettingsBackupPreference> held : ROWS) {
            SettingsBackupPreference row = held.get();
            if (row == null) ROWS.remove(held);
            else row.applyState();
        }
    }

    /**
     * The same readable stamp the diagnostics export and the Lab export use. Epoch milliseconds
     * put a name in the picker that nobody could tell two backups apart by.
     */
    public static String suggestedExportName() {
        // The diagnostics export stamps in UTC, and a backup made in the same second used to get
        // a name hours away from it because this one used local time.
        return "metra-settings-"
                + app.morphe.extension.shared.settings.preference.LogBufferManager.fileTimestamp()
                + ".json";
    }

    private static void pickFile(TikTokPreferenceFragment fragment, int action) {
        Intent intent = new Intent(action == EXPORT ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("application/json");
        if (action == EXPORT) intent.putExtra(Intent.EXTRA_TITLE, suggestedExportName());
        try { fragment.startActivityForResult(intent, action); }
        catch (RuntimeException error) {
            Logger.printException(() -> "Could not open settings file picker", error);
            SettingsActionBanner.showNotice(fragment.getActivity(), L10n.t(
                    "This phone has no file picker, so there's no way to choose a file here"));
        }
    }

    public static boolean onResult(TikTokPreferenceFragment fragment, int request, int result, Intent data) {
        if (request != EXPORT && request != IMPORT) return false;
        if (result == Activity.RESULT_OK && data != null && data.getData() != null) run(fragment, request, data.getData());
        return true;
    }

    private static void run(TikTokPreferenceFragment fragment, int action, Uri uri) {
        Activity activity = fragment.getActivity();
        if (activity == null || !BUSY.compareAndSet(false, true)) {
            if (action == EXPORT) discardUnstartedExport(activity, uri);
            return;
        }
        int run = RUN.incrementAndGet();
        Context context = activity.getApplicationContext();
        WeakReference<TikTokPreferenceFragment> owner = new WeakReference<>(fragment);
        // The settings window the result is shown in, as a banner that stays until it is read.
        // The flow told its outcome in toasts, two in a row when a file left settings out.
        WeakReference<Activity> window = new WeakReference<>(activity);
        if (action != EXPORT) AbstractPreferenceFragment.settingImportInProgress = true;
        // One line per action. "Updating settings" was said for a restore, a reset and an
        // undo alike, so the one thing on screen did not say which of the three was running.
        String running = L10n.t(action == EXPORT ? "Saving settings backup"
                : action == IMPORT ? "Restoring your settings"
                : action == RESET ? "Putting the settings back to their defaults"
                : "Undoing the last change");
        SettingsActionBanner.showNotice(activity, running);
        // A restore of a large file or a reset takes long enough to notice, and the rows used
        // to look exactly as they did before, with a second tap earning a refusal. The acting
        // row now says what is happening and all four are out of reach until it is done.
        setRowsBusy(action, running);
        boolean accepted;
        boolean fileStillHeld = false;
        if (action == EXPORT || action == IMPORT) {
            // A file app can keep a read or write waiting as long as it likes, so these get a
            // worker the row can stop waiting on rather than one of the shared pool's.
            DocumentOperation started = DocumentOperation.start(DocumentOperation.Kind.SETTINGS_FILE,
                    work -> perform(context, window, owner, action, uri, work, run),
                    SettingsBackupPreference::refreshRows);
            fileStillHeld = started == null && DocumentOperation.busy(DocumentOperation.Kind.SETTINGS_FILE);
            operation = started;
            operationUri = uri;
            accepted = started != null;
        } else {
            accepted = Utils.runOnBackgroundThread(() -> perform(context, window, owner, action, null, null, run));
        }
        if (!accepted) {
            if (action == EXPORT) discardUnstartedExport(activity, uri);
            if (action != EXPORT) AbstractPreferenceFragment.settingImportInProgress = false;
            operation = null;
            operationUri = null;
            BUSY.set(false);
            setRowsBusy(0, null);
            // A file app still holding a stopped read or write keeps the next one from starting,
            // and only it letting go, or TikTok restarting, frees it.
            SettingsActionBanner.showNotice(activity, fileStillHeld
                    ? L10n.t("The file app still has the last file. Try again once it lets go, or restart TikTok.")
                    : L10n.t("Couldn't start the settings change. Try again in a moment."));
            TikTokPreferenceFragment current = owner.get();
            if (current != null && current.isAdded()) current.refreshBackupSettings();
        }
    }

    /**
     * The action itself, on its worker. A backup is handed to the file app only once it is past
     * the point of stopping, and a restore changes settings only once it is past it: stopped
     * before then, the run gave the rows back and said so, and the worker leaves without a word.
     */
    private static void perform(Context context, WeakReference<Activity> window,
            WeakReference<TikTokPreferenceFragment> owner, int action, Uri uri,
            DocumentOperation file, int run) {
        boolean labRulesSkipped = false;
        int keptAsTheyWere = 0;
        int settingsSkipped = 0;
        SettingsBackup.ProxyHold proxyHold = SettingsBackup.ProxyHold.NONE;
        java.util.List<app.morphe.extension.tiktok.download.DownloadDestination.Kind> foldersKept =
                java.util.Collections.emptyList();
        // Whether the write has the file. Before that, a file the user chose to replace still
        // holds what it held, so a backup that doesn't happen must leave it alone.
        boolean opened = false;
        try {
            if (action == EXPORT) {
                byte[] bytes = SettingsBackup.export().getBytes(StandardCharsets.UTF_8);
                if (!file.publish()) return;
                try (OutputStream output = file.openForWrite(context.getContentResolver(), uri, "wt")) {
                    opened = true;
                    output.write(bytes);
                }
                // Saved, and a stop from here on can't say otherwise.
                file.finish();
            } else if (action == IMPORT) {
                // Read with the reasons a restore gives, so an unreadable or oversized file
                // is refused with one. Read on its own, those two came out as a bare
                // IOException and reached the user as the generic rejection.
                String text = SettingsBackup.readForRestore(
                        file.openForRead(context.getContentResolver(), uri));
                if (!file.commit()) return;
                // Asked first: afterwards the phone already holds what the restore wrote.
                proxyHold = SettingsBackup.proxyHold(text);
                SettingsBackup.restore(context, text, true);
                labRulesSkipped = SettingsBackup.labRulesWereSkipped(text);
                keptAsTheyWere = SettingsBackup.settingsNotInFile(text);
                settingsSkipped = SettingsBackup.settingsSkipped(text);
                foldersKept = SettingsBackup.foldersKept(text);
            } else if (action == RESET) SettingsBackup.reset(context);
            else {
                // An undo copy written before a retarget holds Lab rules for the older build,
                // and dropping them silently is the same surprise as on an import. An undo
                // copy older than a setting is the same case as a backup older than one, so
                // it is counted the same way.
                String undone = SettingsBackup.undo(context);
                labRulesSkipped = SettingsBackup.labRulesWereSkipped(undone);
                keptAsTheyWere = SettingsBackup.settingsNotInFile(undone);
                settingsSkipped = SettingsBackup.settingsSkipped(undone);
            }
            // Anything the file did not carry stayed as the device had it, which is worth
            // saying: an older backup used to put every setting added since back to its
            // default, download folders included, without a word. Said first, in the same
            // banner as the outcome, since the banner shows one message at a time.
            String kept = keptAsTheyWere == 1
                    ? L10n.f("%1$d setting wasn't in that file and was left as it is", keptAsTheyWere)
                    : keptAsTheyWere > 1
                    ? L10n.f("%1$d settings weren't in that file and were left as they are", keptAsTheyWere)
                    : null;
            // Each of these is one literal, because the translation gate reads the literal
            // handed to L10n and a string built from two of them is two entries it cannot find.
            // The action decides the sentence, and only then does it matter whether Lab
            // rules were dropped. An undo copy written before a retarget carries rules for
            // the older build too, so testing that first made every such undo say a restore
            // had happened. The chain stays flat: a bracketed group around a nested ternary
            // puts the literal inside brackets that are not L10n's, which is the one thing
            // the translation gate reads.
            String outcome = L10n.t(
                    action == EXPORT ? "Settings backup saved"
                            : action == IMPORT && labRulesSkipped
                            ? "Settings restored. The Feature Gate Lab rules were for another TikTok version and were left out. Restart TikTok to apply all changes."
                            : action == IMPORT
                            ? "Settings restored. Restart TikTok to apply all changes."
                            : action == RESET
                            ? "Settings are back to their defaults. Restart TikTok to apply all changes."
                            : labRulesSkipped
                            ? "Last change put back. The Feature Gate Lab rules were for another TikTok version and were left out. Restart TikTok to apply all changes."
                            : "Last change put back. Restart TikTok to apply all changes.");
            // A folder the file named that can't hold its kind was kept, which is said next:
            // downloads would otherwise have gone to DCIM/TikTok while the row showed the file's.
            StringBuilder notes = new StringBuilder();
            if (kept != null) notes.append(kept).append(". ");
            if (settingsSkipped > 0) {
                notes.append(L10n.quantity(context, settingsSkipped,
                        "%1$d setting in that file can't be restored here and was skipped",
                        "%1$d settings in that file can't be restored here and were skipped",
                        settingsSkipped)).append(". ");
            }
            for (app.morphe.extension.tiktok.download.DownloadDestination.Kind kind : foldersKept) {
                notes.append(L10n.t(
                        kind == app.morphe.extension.tiktok.download.DownloadDestination.Kind.VIDEO
                                ? "Videos can't be saved to the folder in that file, so your video folder was kept"
                                : kind == app.morphe.extension.tiktok.download.DownloadDestination.Kind.PHOTO
                                ? "Photos can't be saved to the folder in that file, so your photo folder was kept"
                                : "Stickers can't be saved to the folder in that file, so your sticker folder was kept"))
                        .append(". ");
            }
            // A file can't turn the proxy on or move this phone's, and the row alone wouldn't
            // say why the switch is off after a move, or why the file's address isn't there.
            if (proxyHold == SettingsBackup.ProxyHold.LEFT_OFF) {
                notes.append(L10n.t("The network proxy from that file is off. Check its address before you turn it on")).append(". ");
            } else if (proxyHold == SettingsBackup.ProxyHold.KEPT_THIS_PHONES) {
                notes.append(L10n.t("Your network proxy was kept, since a backup can't change it")).append(". ");
            }
            String message = notes + outcome;
            if (action == EXPORT) SettingsActionBanner.showNotice(TikTokPreferenceFragment.reportWindow(window, context), message);
            else SettingsActionBanner.showRestart(TikTokPreferenceFragment.reportWindow(window, context), message);
        } catch (SettingsBackup.RuleListTooLarge tooLarge) {
            if (file != null && file.stage() == DocumentOperation.Stage.STOPPED) return;
            // Named, since a backup with it in would be refused by every restore.
            String message = L10n.f(
                    "%1$s is too long for a settings backup. Shorten it, then save the backup again.",
                    L10n.t(tooLarge.listTitle));
            SettingsActionBanner.showNotice(TikTokPreferenceFragment.reportWindow(window, context),
                    exportFailure(context, action, uri, opened, message));
        } catch (Exception error) {
            // Stopped before the gate: nothing changed and the stop already said so.
            if (file != null && file.stage() == DocumentOperation.Stage.STOPPED) return;
            DocumentOperation.logFailure(file, () -> "Settings backup operation failed", error);
            // Stopped while the file app had the backup, and this is how that ended.
            String failure = file != null && file.stage() == DocumentOperation.Stage.STOPPED_WHILE_PUBLISHING
                    ? L10n.t("The backup wasn't saved. Back up again when the file app is ready.")
                    : L10n.t(failureMessage(action, error));
            SettingsActionBanner.showNotice(TikTokPreferenceFragment.reportWindow(window, context), exportFailure(
                    context, action, uri, opened, failure));
        } finally {
            Utils.runOnMainThread(() -> finishRun(run, action, owner));
        }
    }

    private static void finishRun(int run, int action, WeakReference<TikTokPreferenceFragment> owner) {
        // A run that was stopped gave the rows back already, and they may be a later run's now.
        if (RUN.get() != run) {
            refreshRows();
            return;
        }
        if (action != EXPORT) AbstractPreferenceFragment.settingImportInProgress = false;
        operation = null;
        operationUri = null;
        BUSY.set(false);
        setRowsBusy(0, null);
        TikTokPreferenceFragment started = owner.get();
        if (started != null && started.isAdded()) started.refreshBackupSettings();
        // The page open now, when settings was rebuilt while the file app had the file.
        TikTokPreferenceFragment open = TikTokPreferenceFragment.active();
        if (open != null && open != started) open.refreshBackupSettings();
    }

    /**
     * Stops waiting on a file app that has kept the acting row waiting. A restore stopped before
     * its gate changes nothing and a backup stopped before its handover removes the empty file
     * the picker made, or leaves a file the user chose to replace as it was. Once the file app
     * has the backup, nothing here can say whether it kept it, so the row says so and the worker
     * reports the outcome when the file app answers.
     */
    private static void stopWaiting(Activity activity) {
        DocumentOperation acting = operation;
        if (acting == null) return;
        int action = busyAction;
        Uri uri = operationUri;
        DocumentOperation.Stage left = acting.stop();
        // Past the gate, the change is being made or the backup's outcome is on its way.
        if (left != DocumentOperation.Stage.STOPPED && left != DocumentOperation.Stage.STOPPED_WHILE_PUBLISHING) {
            refreshRows();
            return;
        }
        RUN.incrementAndGet();
        if (action != EXPORT) AbstractPreferenceFragment.settingImportInProgress = false;
        operation = null;
        operationUri = null;
        BUSY.set(false);
        setRowsBusy(0, null);
        Context feedback = activity != null ? activity : Utils.getContext();
        if (action != EXPORT) {
            SettingsActionBanner.showNotice(feedback, L10n.t(
                    "Stopped waiting for the file app. Nothing was changed."));
        } else if (left == DocumentOperation.Stage.STOPPED) {
            discardUnstartedExport(activity, uri);
            SettingsActionBanner.showNotice(feedback, L10n.t(
                    "The backup wasn't saved. Back up again when the file app is ready."));
        } else {
            SettingsActionBanner.showNotice(feedback, L10n.t(
                    "Stopped waiting for the file app. It hasn't said yet whether the backup was saved."));
        }
    }

    private static String exportFailure(Context context, int action, Uri uri, boolean opened, String failure) {
        if (action != EXPORT || DocumentOperation.removeUnsaved(context.getContentResolver(), uri, opened)) return failure;
        return failure + " " + L10n.t(context,
                "The partial file couldn't be removed. Delete it from the folder you chose.");
    }

    /** Only ACTION_CREATE_DOCUMENT results belong here; imports are existing user files. */
    private static void discardUnstartedExport(Context window, Uri uri) {
        Context context = window == null ? Utils.getContext() : window.getApplicationContext();
        WeakReference<Context> feedback = new WeakReference<>(window);
        EXPORT_CLEANUP.execute(() -> {
            ContentResolver resolver = context == null ? null : context.getContentResolver();
            if (!DocumentOperation.removeUnsaved(resolver, uri, false)) {
                Context current = feedback.get();
                SettingsActionBanner.showNotice(current == null ? context : current, L10n.t(context,
                        "The backup didn't start and its partial file couldn't be removed. Delete it from the folder you chose."));
            }
        });
    }

    static String failureMessage(int action, Exception error) {
        if (action == EXPORT) return "Couldn't save the settings backup. Try again.";
        if (error instanceof SettingsBackup.RestoreException) {
            SettingsBackup.RestoreException restore = (SettingsBackup.RestoreException) error;
            switch (restore.getFailure()) {
                case REJECTED_INPUT:
                    // One sentence per reason. All of these read as the same rejection before,
                    // so a truncated download and a backup from a newer Hushfeed were
                    // indistinguishable to the person holding the file.
                    switch (restore.getReason()) {
                        case SIZE:
                            return "That file is too large to be a settings backup. Nothing was altered.";
                        case DAMAGED:
                            return "That settings backup is damaged or only partly downloaded. "
                                    + "Nothing was altered.";
                        case ENCODING:
                            return "That file isn't readable text, so it may have been damaged in "
                                    + "transit. Nothing was altered.";
                        case FORMAT:
                            return "That isn't a Metra settings backup. Nothing was altered.";
                        case SCHEMA:
                            return "That backup was written by a newer Metra than this one. "
                                    + "Nothing was altered.";
                        case INCOMPLETE:
                            return "That settings backup is incomplete, so it may have been cut "
                                    + "short. Nothing was altered.";
                        case VALUE:
                            return "That settings backup holds a value Metra can't read. "
                                    + "Nothing was altered.";
                        case RULE_LIST:
                            return "That settings backup contains a feed rule list larger than "
                                    + "Metra accepts. Nothing was altered.";
                        case LAB_RULES:
                            return "That settings backup holds more Feature Gate Lab rules than "
                                    + "the Lab takes. Nothing was altered.";
                        default:
                            return "The settings backup was rejected. Nothing was altered.";
                    }
                case ROLLED_BACK:
                    return "That settings change didn't go through. Nothing was altered.";
                case RECOVERY_REQUIRED:
                    // Each action names itself: a Reset or an Undo that stopped halfway used to
                    // report a restore nobody had asked for.
                    if (action == RESET) {
                        return restore.isRecoveryAvailable()
                                ? "Reset didn't finish. Some settings may still be changed. Use Undo to put them back."
                                : "Reset didn't finish. Some settings may still be changed.";
                    }
                    if (action == UNDO) {
                        return restore.isRecoveryAvailable()
                                ? "Undo didn't finish. Some settings may still be changed. Try Undo again."
                                : "Undo didn't finish. Some settings may still be changed.";
                    }
                    return restore.isRecoveryAvailable()
                            ? "Restore failed. Some settings may still be changed. Use Undo to put them back."
                            : "Restore failed. Some settings may still be changed.";
                default:
                    break;
            }
        }
        if (action == UNDO && hasCause(error, java.io.FileNotFoundException.class)) {
            return "Nothing to undo yet.";
        }
        if (action == RESET) return "Couldn't reset the settings. Try again.";
        if (action == UNDO) return "Couldn't undo the last change. Try again.";
        return "Couldn't restore the settings. Try again.";
    }

    /** Whether the throwable, or anything it wraps, is of the given kind. */
    private static boolean hasCause(Throwable error, Class<? extends Throwable> kind) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (kind.isInstance(current)) return true;
            if (current.getCause() == current) break;
        }
        return false;
    }

    /**
     * Puts the row in the state the backup actions are in. While one runs, all four are out of
     * reach and the acting row says what it is doing, or offers to stop once a file app has kept
     * it waiting. While a file app still holds a stopped backup or restore's file, Back up and
     * Restore wait for it to let go. Undo is greyed when there is nothing to undo.
     *
     * <p>Undo used to be offered on a clean install, and tapping it reported that the settings
     * could not be restored, which reads as something having gone wrong rather than as there
     * being nothing there. Checked again on every bind, so a reset or a restore enables it
     * without the page being rebuilt.
     */
    private void applyState() {
        // Nothing overrides a run in progress: the row is disabled and saying what it is doing.
        if (runningLine != null) {
            boolean stoppable = offersStop();
            busyLine = rowAction != busyAction ? null : stoppable
                    ? L10n.t(getContext(), "Still waiting for the file app. Tap to stop waiting.")
                    : runningLine;
            if (isEnabled() != stoppable) setEnabled(stoppable);
            if (busyLine != null) setSummaryDirect(busyLine);
            return;
        }
        busyLine = null;
        if (rowAction == UNDO) {
            boolean available = SettingsBackup.hasUndo(getContext());
            if (isEnabled() != available) setEnabled(available);
            // The one greyed row on the page, and its summary went on offering a recovery. A
            // screen reader announces "disabled" with no reason; this is the reason.
            setSummary(available ? UNDO_SUMMARY : "Nothing to undo yet.");
            return;
        }
        boolean held = rowAction != RESET && DocumentOperation.heldAfterStop(DocumentOperation.Kind.SETTINGS_FILE);
        if (isEnabled() == held) setEnabled(!held);
        if (held) {
            setSummaryDirect(L10n.t(getContext(),
                    "Waiting for the file app to let go of the last file. Restart TikTok if it doesn't."));
        } else {
            setSummary(restingSummary);
        }
    }

    @Override protected void onBindView(View view) {
        applyState();
        if (android.os.Build.VERSION.SDK_INT >= 30 && view != null) {
            view.setStateDescription(busyLine);
        }
        super.onBindView(view);
        app.morphe.extension.tiktok.Utils.setTitleAndSummaryColor(view);
    }

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(L10n.t(getContext(), title));
    }

    @Override
    public void setSummary(CharSequence summary) {
        super.setSummary(L10n.t(getContext(), summary));
    }

    /** Text that has already been translated, so the lookup above would find nothing. */
    private void setSummaryDirect(CharSequence summary) {
        super.setSummary(summary);
    }
}
