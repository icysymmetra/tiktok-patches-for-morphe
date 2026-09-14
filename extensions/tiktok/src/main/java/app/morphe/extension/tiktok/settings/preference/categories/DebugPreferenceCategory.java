package app.morphe.extension.tiktok.settings.preference.categories;

import android.app.AlertDialog;
import android.content.Context;
import android.preference.PreferenceScreen;
import android.view.View;

import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.preference.ClearLogBufferPreference;
import app.morphe.extension.shared.settings.preference.ExportDiagnosticReportPreference;
import app.morphe.extension.shared.settings.preference.LogExportFilterPreference;
import app.morphe.extension.tiktok.Utils;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;

@SuppressWarnings("deprecation")
public class DebugPreferenceCategory extends ConditionalPreferenceCategory {
    public DebugPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Diagnostics");
    }

    @Override
    public boolean getSettingsStatus() {
        return SettingsStatus.diagnosticsEnabled;
    }

    @Override
    public void addPreferences(Context context) {
        addPreference(group(context, "Logging and crash capture"));
        addPreference(new TogglePreference(
                context,
                "Enable diagnostic logging",
                "Only enable when recording logs to report an issue. Leaving it on for too long can make TikTok feel laggy and may lead to crashes.",
                BaseSettings.DEBUG
        ));

        addPreference(new TogglePreference(
                context,
                "Capture crash reports locally",
                "Save the latest available TikTok crash report for diagnostic export.",
                BaseSettings.CAPTURE_JAVA_CRASHES
        ));

        addPreference(group(context, "Reports and stored data"));
        var logFilter = new TintedLogExportFilterPreference(context);
        logFilter.setTitle("Included diagnostics");
        addPreference(logFilter);

        var exportLogs = new TintedExportDiagnosticReportPreference(context);
        exportLogs.setTitle("Export diagnostic report");
        exportLogs.setSummary("Copy a quick report or save the full report as a file.");
        addPreference(exportLogs);

        var clearLogs = new TintedClearLogBufferPreference(context);
        clearLogs.setTitle("Clear diagnostic data");
        clearLogs.setSummary("Clear buffered events and saved crash reports.");
        addPreference(clearLogs);
    }

    private static class TintedExportDiagnosticReportPreference extends ExportDiagnosticReportPreference {
        TintedExportDiagnosticReportPreference(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            Utils.setTitleAndSummaryColor(view);
        }

        @Override
        protected void onDialogShown(AlertDialog dialog) {
            SettingsUi.styleStandardAlertDialog(dialog);
        }
    }

    private static class TintedLogExportFilterPreference extends LogExportFilterPreference {
        TintedLogExportFilterPreference(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            Utils.setTitleAndSummaryColor(view);
        }

        @Override
        protected void onDialogShown(AlertDialog dialog) {
            SettingsUi.styleStandardAlertDialog(dialog);
        }
    }

    private static class TintedClearLogBufferPreference extends ClearLogBufferPreference {
        TintedClearLogBufferPreference(Context context) {
            super(context);
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            Utils.setTitleAndSummaryColor(view);
        }
    }
}
