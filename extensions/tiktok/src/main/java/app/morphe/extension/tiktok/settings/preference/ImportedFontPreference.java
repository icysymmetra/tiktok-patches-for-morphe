/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.preference.Preference;
import android.provider.OpenableColumns;
import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.font.ImportedFont;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.text.NumberFormat;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The two rows under Use an imported font: one picks a font file and copies it into TikTok's
 * files, the other removes it.
 *
 * <p>The picker answers the settings page, the way the watch-history import does, and the copy
 * runs on a {@link DocumentOperation} worker so a file app that keeps it waiting can't hold the
 * row. Nothing replaces the imported font until the file has been read whole and Android has
 * made a face of it; a stop before then leaves the old one in place.
 */
@SuppressWarnings("deprecation")
public final class ImportedFontPreference extends Preference {
    static final int REQUEST_PICK = 7341;
    static final String PICK_KEY = "action_import_font";
    static final String REMOVE_KEY = "action_remove_imported_font";
    /** What the restart row counts for a font file changed while the switch is on. */
    static final String RESTART_KEY = "hushfeed_imported_font_file";

    private static final AtomicBoolean BUSY = new AtomicBoolean();
    private static final CopyOnWriteArrayList<WeakReference<ImportedFontPreference>> ROWS =
            new CopyOnWriteArrayList<>();
    /** The picker is open. Main thread only. */
    private static boolean picking;
    /** The read the row waits on, until the font is installed. Main thread only. */
    private static DocumentOperation reading;

    /** Installing the checked font failed, as opposed to reading the picked file. */
    private static final class NotSaved extends IOException {
        NotSaved(IOException cause) {
            super("Could not keep the imported font", cause);
        }
    }

    private final boolean removes;

    private ImportedFontPreference(Context context, boolean removes) {
        super(context);
        this.removes = removes;
        setPersistent(false);
        setKey(removes ? REMOVE_KEY : PICK_KEY);
        setTitle(removes ? L10n.t(context, "Remove the imported font") : L10n.t(context, "Import a font file"));
        ROWS.add(new WeakReference<>(this));
        refreshState();
        setOnPreferenceClickListener(preference -> {
            if (removes) removeFont(getContext());
            else if (offersStop()) stopWaiting(getContext());
            else if (!BUSY.get() && !picking && !heldAfterStop()) {
                TikTokPreferenceFragment.openImportedFontPicker();
            }
            return true;
        });
    }

    /** The row that picks a font file. */
    public static ImportedFontPreference pickRow(Context context) {
        return new ImportedFontPreference(context, false);
    }

    /** The row that removes the imported font. */
    public static ImportedFontPreference removeRow(Context context) {
        return new ImportedFontPreference(context, true);
    }

    static void pickFile(TikTokPreferenceFragment fragment) {
        if (BUSY.get() || picking) return;
        // Font files come with no MIME type a file app agrees on (font/ttf, application/x-font-ttf,
        // application/octet-stream), so every file is offered and the import checks the bytes.
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                .setType("*/*");
        picking = true;
        refreshRows();
        try {
            fragment.startActivityForResult(intent, REQUEST_PICK);
        } catch (RuntimeException failure) {
            picking = false;
            refreshRows();
            Logger.printException(() -> "Could not open the font file picker", failure);
            SettingsActionBanner.showNotice(fragment.getActivity(), L10n.t(
                    "This phone has no file picker, so there's no way to choose a file here"));
        }
    }

    static boolean onResult(TikTokPreferenceFragment fragment, int request, int result, Intent data) {
        if (request != REQUEST_PICK) return false;
        picking = false;
        Activity activity = fragment.getActivity();
        if (result != Activity.RESULT_OK || data == null || data.getData() == null
                || activity == null || !BUSY.compareAndSet(false, true)) {
            refreshRows();
            return true;
        }
        Context context = activity.getApplicationContext();
        WeakReference<Activity> window = new WeakReference<>(activity);
        Uri uri = data.getData();
        refreshRows();
        SettingsActionBanner.showNotice(activity, L10n.t(context, "Reading the font file"));
        reading = DocumentOperation.start(DocumentOperation.Kind.FONT_FILE, file -> {
            try {
                ContentResolver resolver = context.getContentResolver();
                ImportedFont.Incoming incoming = ImportedFont.receive(
                        context, file.openForRead(resolver, uri), displayName(resolver, uri));
                // Stopped while the file app had it: the stop gave the row back and said so.
                if (!file.commit()) {
                    incoming.discard();
                    return;
                }
                String name;
                try {
                    name = incoming.install();
                } catch (IOException failure) {
                    incoming.discard();
                    throw new NotSaved(failure);
                }
                Utils.runOnMainThread(() -> {
                    finish();
                    imported(TikTokPreferenceFragment.reportWindow(window, context), name);
                });
            } catch (Exception failure) {
                if (file.isStopped()) return;
                if (failure instanceof ImportedFont.Rejected) {
                    Logger.printInfo(() -> "Font import refused: " + failure.getMessage());
                } else {
                    Logger.printException(() -> "Could not import a font file", failure);
                }
                Utils.runOnMainThread(() -> {
                    finish();
                    SettingsActionBanner.showNotice(TikTokPreferenceFragment.reportWindow(window, context),
                            failureMessage(context, failure));
                });
            }
        }, ImportedFontPreference::refreshRows);
        if (reading == null) {
            // A file app still holding a stopped read keeps the next one from starting, and only
            // it letting go, or TikTok restarting, frees it.
            boolean held = DocumentOperation.busy(DocumentOperation.Kind.FONT_FILE);
            finish();
            SettingsActionBanner.showNotice(activity, held
                    ? L10n.t(context, "The file app still has the last file. Try again once it lets go, or restart TikTok.")
                    : L10n.t(context, "Couldn't start the import. Try again in a moment."));
        }
        return true;
    }

    /** The name the file app shows for the document, or null when it gives none. */
    private static String displayName(ContentResolver resolver, Uri uri) {
        String[] columns = {OpenableColumns.DISPLAY_NAME};
        // From Android 8 a DocumentsProvider implements only the Bundle form of query.
        try (Cursor cursor = Build.VERSION.SDK_INT >= 26
                ? resolver.query(uri, columns, null, null)
                : resolver.query(uri, columns, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) return null;
            int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
            return column < 0 || cursor.isNull(column) ? null : cursor.getString(column);
        } catch (RuntimeException failure) {
            Logger.printInfo(() -> "Could not read the picked font's name: " + failure);
            return null;
        }
    }

    /** What the reader is told once a font is in. A change the switch draws owes a restart. */
    private static void imported(Context window, String name) {
        if (Settings.IMPORTED_FONT.get()) {
            TikTokPreferenceFragment.noteFileRestartPending(RESTART_KEY);
            SettingsActionBanner.showRestart(window, L10n.f(window,
                    "%1$s is imported. Restart TikTok to see it.", name));
        } else {
            SettingsActionBanner.showNotice(window, L10n.f(window,
                    "%1$s is imported. Turn on Use an imported font to see it.", name));
        }
    }

    private static void removeFont(Context context) {
        String name = ImportedFont.name(context);
        if (name == null || BUSY.get()) {
            refreshRows();
            return;
        }
        boolean gone = ImportedFont.remove(context);
        refreshRows();
        if (!gone) {
            SettingsActionBanner.showNotice(context, L10n.t(context,
                    "Couldn't remove the imported font. Try again."));
        } else if (Settings.IMPORTED_FONT.get()) {
            TikTokPreferenceFragment.noteFileRestartPending(RESTART_KEY);
            SettingsActionBanner.showRestart(context, L10n.f(context,
                    "%1$s is removed. Restart TikTok to see the change.", name));
        } else {
            SettingsActionBanner.showNotice(context, L10n.f(context, "%1$s is removed", name));
        }
    }

    static String failureMessage(Context context, Exception failure) {
        if (failure instanceof NotSaved) return L10n.t(context,
                "Couldn't save the font in TikTok's storage. Free up some space and try again.");
        if (!(failure instanceof ImportedFont.Rejected)) return L10n.t(context,
                "Couldn't read that file. Choose it again.");
        if (((ImportedFont.Rejected) failure).reason == ImportedFont.Reason.TOO_LARGE) {
            return L10n.f(context, "That file is larger than %1$s MB. Choose a smaller font file.",
                    NumberFormat.getInstance().format(ImportedFont.MAX_BYTES / (1024 * 1024)));
        }
        return L10n.t(context, "That file isn't a font Android can use. Choose a .ttf or .otf file.");
    }

    /** Whether the read has kept the row waiting long enough to offer a stop, and stopping still can. */
    private static boolean offersStop() {
        DocumentOperation acting = reading;
        return acting != null && acting.offersStop();
    }

    private static boolean heldAfterStop() {
        return DocumentOperation.heldAfterStop(DocumentOperation.Kind.FONT_FILE);
    }

    /** Stops waiting on a file app that has kept the read waiting. The old font stays. */
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

    private static void finish() {
        BUSY.set(false);
        reading = null;
        refreshRows();
    }

    private static void refreshRows() {
        for (WeakReference<ImportedFontPreference> held : ROWS) {
            ImportedFontPreference row = held.get();
            if (row == null) ROWS.remove(held);
            else row.refreshState();
        }
    }

    private void refreshState() {
        Context context = getContext();
        String name = ImportedFont.name(context);
        if (removes) {
            setEnabled(name != null && !BUSY.get());
            setSummary(name != null
                    ? L10n.f(context, "Deletes %1$s from TikTok's storage.", name)
                    : L10n.t(context, "No font is imported"));
            return;
        }
        boolean stoppable = offersStop();
        boolean held = !BUSY.get() && heldAfterStop();
        setEnabled(stoppable || !BUSY.get() && !picking && !held);
        setSummary(stoppable ? L10n.t(context, "Still waiting for the file app. Tap to stop waiting.")
                : BUSY.get() ? L10n.t(context, "Reading the font file")
                : held ? L10n.t(context,
                        "Waiting for the file app to let go of the last file. Restart TikTok if it doesn't.")
                : name != null ? L10n.f(context, "Imported: %1$s. Pick another file to replace it.", name)
                : L10n.f(context, "Pick a .ttf or .otf file of up to %1$s MB.",
                        NumberFormat.getInstance().format(ImportedFont.MAX_BYTES / (1024 * 1024))));
    }

    @Override protected void onBindView(View view) {
        refreshState();
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }
}
