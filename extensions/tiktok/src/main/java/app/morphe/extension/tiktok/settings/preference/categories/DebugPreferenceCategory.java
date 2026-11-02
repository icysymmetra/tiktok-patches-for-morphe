/*
 * Adapted from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/categories/ExtensionPreferenceCategory.java
 */
package app.morphe.extension.tiktok.settings.preference.categories;

import app.morphe.extension.tiktok.settings.L10n;
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
import app.morphe.extension.tiktok.settings.preference.FeatureGateRecorderPreference;
import app.morphe.extension.tiktok.settings.preference.FeedCapturePreference;
import app.morphe.extension.tiktok.settings.preference.HookStatusPreference;
import app.morphe.extension.tiktok.settings.preference.ScreenLayoutPreference;
import app.morphe.extension.tiktok.settings.preference.SectionHeadingPreference;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.preference.TimedDiagnosticsPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.storage.StorageReport;

@SuppressWarnings("deprecation")
public class DebugPreferenceCategory extends ConditionalPreferenceCategory {
    public DebugPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Diagnostics");
    }

    /**
     * Whether this page has anything on it. The row into it asks the same question. The gate
     * recorder is a diagnostics tool, so a bundle carrying only that patch still gets the page.
     */
    public static boolean isAvailable() {
        return SettingsStatus.diagnosticsEnabled || SettingsStatus.featureGateRecorderEnabled;
    }

    @Override
    public boolean getSettingsStatus() {
        return isAvailable();
    }

    @Override
    public void addPreferences(Context context) {
        if (SettingsStatus.diagnosticsEnabled) {
            // Measured as the page opens, so the export made from it says where TikTok's
            // storage went (#70).
            StorageReport.prepare(context);

            addPreference(new TogglePreference(
                    context,
                    "Log diagnostics",
                    "Keeps logging until you turn it off, even after a restart. TikTok can feel slower while it's on.",
                    BaseSettings.DEBUG
            ));

            // One bug report's worth of logging that ends by itself, beside the switch rather
            // than through it, so it never turns off logging someone left on.
            addPreference(new TimedDiagnosticsPreference(context));

            // The switch behind the failure messages. It was read, persisted and backed up from
            // the first release and had no row anywhere, so nobody could turn the messages off
            // without turning the logging off with them.
            addPreference(new TogglePreference(
                    context,
                    "Show failures on screen",
                    "While diagnostic logging is on, a message names the part of Metra that failed. Off keeps failures in the report only.",
                    BaseSettings.DEBUG_TOAST_ON_ERROR
            ));

            addPreference(new TogglePreference(
                    context,
                    "Capture crash reports locally",
                    "Saves TikTok's latest crash report on this phone so it can go in the "
                            + "diagnostic report.",
                    BaseSettings.CAPTURE_JAVA_CRASHES
            ));

            addPreference(new HookStatusPreference(context));

            var logFilter = new TintedLogExportFilterPreference(context);
            logFilter.setTitle(L10n.t(context, "Included diagnostics"));
            addPreference(logFilter);

            // Before the export, since what it records goes into the next one.
            addPreference(new ScreenLayoutPreference(context));

            var exportLogs = new TintedExportDiagnosticReportPreference(context);
            exportLogs.setTitle(L10n.t(context, "Export diagnostic report"));
            exportLogs.setSummary(L10n.t(context, "Copy a quick report or save the full report as a file."));
            addPreference(exportLogs);

            var clearLogs = new TintedClearLogBufferPreference(context);
            clearLogs.setTitle(L10n.t(context, "Clear diagnostic data"));
            clearLogs.setClearAndUndoSummaries(
                    L10n.t(context,
                            "Clears the recent events, saved crash reports, a recorded screen "
                                    + "layout and the Hook status results above."),
                    L10n.t(context, "Diagnostic data cleared. Tap again to put it back."));
            addPreference(clearLogs);

            // A file of its own rather than a part of the report, so it sits after the rows
            // that make and clear the report.
            addPreference(new FeedCapturePreference(context));
        }

        // The recorder used to sit on the master menu as the one row without an icon tile. It
        // records what TikTok reads from its gates, which is diagnostics, so it lives here.
        if (SettingsStatus.featureGateRecorderEnabled) {
            addPreference(new SectionHeadingPreference(context, "Feature Gate Lab"));
            addPreference(new FeatureGateRecorderPreference(context));
        }
    }

    private static class TintedExportDiagnosticReportPreference extends ExportDiagnosticReportPreference {
        TintedExportDiagnosticReportPreference(Context context) {
            super(context);
        }

        @Override
        public void setTitle(CharSequence title) {
            super.setTitle(L10n.t(getContext(), title));
        }

        @Override
        public void setSummary(CharSequence summary) {
            super.setSummary(L10n.t(getContext(), summary));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            Utils.setTitleAndSummaryColor(view);
        }

        // The dialog's own words. Without these a translated row opened an English dialog.
        @Override
        protected CharSequence dialogTitle() {
            return L10n.t(getContext(), "Export diagnostic report");
        }

        @Override
        protected CharSequence[] labels() {
            return new CharSequence[]{
                    L10n.t(getContext(), "Copy quick report"),
                    L10n.t(getContext(), "Save full report"),
            };
        }

        @Override
        protected CharSequence negativeText() {
            return L10n.t(getContext(), "Cancel");
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
        public void setTitle(CharSequence title) {
            super.setTitle(L10n.t(getContext(), title));
        }

        @Override
        public void setSummary(CharSequence summary) {
            super.setSummary(L10n.t(getContext(), summary));
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
        // The picker's own words. The shared class cannot reach a translation table, because it
        // is shared with bundles that carry none, so this bundle hands its own back.
        @Override
        protected String[] labels() {
            String[] english = super.labels();
            String[] translated = new String[english.length];
            for (int index = 0; index < english.length; index++) {
                translated[index] = L10n.t(getContext(), english[index]);
            }
            return translated;
        }

        @Override
        protected CharSequence dialogTitle() {
            return L10n.t(getContext(), "Include diagnostic events");
        }

        @Override
        protected CharSequence positiveText() {
            return L10n.t(getContext(), "Apply");
        }

        @Override
        protected CharSequence negativeText() {
            return L10n.t(getContext(), "Cancel");
        }

        @Override
        protected CharSequence allEventsSummary() {
            return L10n.t(getContext(), "Includes all diagnostic events.");
        }

        @Override
        protected CharSequence includesSummary(String kinds) {
            return L10n.f(getContext(), "Includes these events: %1$s", kinds);
        }
    }

    private static class TintedClearLogBufferPreference extends ClearLogBufferPreference {
        TintedClearLogBufferPreference(Context context) {
            super(context);
        }

        @Override
        public void setTitle(CharSequence title) {
            super.setTitle(L10n.t(getContext(), title));
        }

        @Override
        public void setSummary(CharSequence summary) {
            super.setSummary(L10n.t(getContext(), summary));
        }

        @Override
        protected void onBindView(View view) {
            super.onBindView(view);
            Utils.setTitleAndSummaryColor(view);
        }
    }
}
