/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.shared.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

/** Offers a compact choice between a quick clipboard report and a full text file. */
@SuppressWarnings({"deprecation", "unused"})
public class ExportDiagnosticReportPreference extends Preference {
    {
        // A key so the settings search can index this row. Nothing in the settings
        // framework treats it as a setting: a key with no Setting behind it is skipped.
        setKey("action_export_diagnostic_report");
        setOnPreferenceClickListener(pref -> {
            AlertDialog shownDialog = new AlertDialog.Builder(getContext())
                    .setTitle(dialogTitle())
                    .setItems(
                            labels(),
                            (dialog, which) -> {
                                if (which == 0) LogBufferManager.exportToClipboard();
                                if (which == 1) LogBufferManager.exportToFile();
                            }
                    )
                    .setNegativeButton(negativeText(), null)
                    .show();
            onDialogShown(shownDialog);
            return true;
        });
    }

    protected void onDialogShown(AlertDialog dialog) {
    }

    /** The dialog's title. A bundle with a translation table overrides these three. */
    protected CharSequence dialogTitle() {
        return "Export diagnostic report";
    }

    /** The two choices, quick copy first, full file second. */
    protected CharSequence[] labels() {
        return new CharSequence[]{"Copy quick report", "Save full report"};
    }

    protected CharSequence negativeText() {
        return "Cancel";
    }

    public ExportDiagnosticReportPreference(
            Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes
    ) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public ExportDiagnosticReportPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public ExportDiagnosticReportPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public ExportDiagnosticReportPreference(Context context) {
        super(context);
    }
}
