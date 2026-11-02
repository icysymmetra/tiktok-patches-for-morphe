/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.settings.L10n;

final class GateReportExport {
    static final int MAX_CLIPBOARD_CHARS = 60000;

    static boolean copy(Context context, String report) {
        if (report.length() > MAX_CLIPBOARD_CHARS) {
            Utils.showToastShort(L10n.t("Use Save report for this large report"));
            return false;
        }
        try {
            Utils.setClipboard(context, "Feature gate recording", report);
            Utils.showToastShort(L10n.t("Feature gate report copied"));
            return true;
        } catch (RuntimeException error) {
            Logger.printException(() -> "Could not copy gate report", error);
            Utils.showToastShort(L10n.t("Couldn't copy the report. Use Save report instead."));
            return false;
        }
    }

    /**
     * One save at a time, on a thread of its own. A MediaStore write that stalls used to hold one
     * of the few shared background threads, and every further tap took another.
     */
    private static final AtomicBoolean SAVING = new AtomicBoolean();

    static void save(Context context, String report) {
        Context app = context.getApplicationContext();
        if (!SAVING.compareAndSet(false, true)) {
            Utils.showToastShort(L10n.t("The last report is still being saved"));
            return;
        }
        boolean started = Utils.runOnOwnThread("Metra-gate-report", () -> {
            try {
                Utils.showToastLong(L10n.f("Report saved to %1$s", write(app, report)));
            } catch (IOException | RuntimeException error) {
                Logger.printException(() -> "Could not save gate report", error);
                Utils.showToastLong(L10n.t("The report couldn't be saved. Try again."));
            } finally {
                SAVING.set(false);
            }
        });
        // Nothing else would say so: the reader tapped Save JSON and got neither the saved path
        // nor the failure sentence.
        if (!started) {
            SAVING.set(false);
            Utils.showToastShort(L10n.t("Couldn't start the report export. Try again in a moment."));
        }
    }

    /** The folder under Download the report lands in, spelt the way a file manager shows it. */
    static final String FOLDER = "Download/Metra";

    /**
     * A name two reports a second apart can be told apart by, the way the settings backup and the
     * diagnostics export are named. Epoch milliseconds put a number in the list that nobody could
     * read a date off.
     */
    static String fileName() {
        return "metra-gate-report-" + LogBufferManager.fileTimestamp() + ".json";
    }

    static String write(Context context, String report) throws IOException {
        return write(context, report, fileName(), "application/json");
    }

    /**
     * Writes {@code report} as {@code name} in the Hushfeed folder under Download, or in the app's
     * own Documents folder before Android 10, and says where it went. The feed capture saves
     * through here too, so both files land in the same place.
     */
    static String write(Context context, String report, String name, String mimeType) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            var resolver = context.getContentResolver();
            ContentValues values = new ContentValues();
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("Could not create report");
            try {
                writeText(resolver.openOutputStream(uri, "w"), report);
                values.clear();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                if (resolver.update(uri, values, null, null) != 1) throw new IOException("Could not publish report");
            } catch (IOException | RuntimeException error) {
                try { resolver.delete(uri, null, null); } catch (RuntimeException cleanup) { error.addSuppressed(cleanup); }
                throw error;
            }
            return FOLDER + "/" + name;
        }
        File directory = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS);
        if (directory == null || (!directory.isDirectory() && !directory.mkdirs())) throw new IOException("Documents unavailable");
        File file = new File(directory, name);
        int dot = name.lastIndexOf('.');
        String stem = dot < 0 ? name : name.substring(0, dot);
        String extension = dot < 0 ? "" : name.substring(dot);
        for (int copy = 2; file.exists(); copy++) {
            file = new File(directory, stem + "-" + copy + extension);
        }
        try { writeText(new FileOutputStream(file), report); }
        catch (IOException | RuntimeException error) {
            if (!file.delete()) error.addSuppressed(new IOException("Could not remove incomplete report"));
            throw error;
        }
        return file.getAbsolutePath();
    }

    private static void writeText(OutputStream output, String report) throws IOException {
        if (output == null) throw new IOException("Could not open report");
        try (var writer = new OutputStreamWriter(output, StandardCharsets.UTF_8)) { writer.write(report); }
    }
}
