/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.preference.Preference;
import android.view.View;
import android.widget.TextView;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.BuildDetails;
import app.morphe.extension.shared.settings.preference.ImmediateAction;
import app.morphe.extension.shared.settings.preference.LogBufferManager;
import app.morphe.extension.tiktok.settings.L10n;

/** Available even when Diagnostic tools was not selected. */
@SuppressWarnings("deprecation")
public final class BuildDetailsPreference extends Preference implements ImmediateAction {
    @Override public boolean actsOnTap() { return true; }

    /** The row's key and summary, shared with the settings search's entry for it. */
    static final String KEY = "action_build_details";
    static final String SUMMARY = "Copy or save which patches and options this Metra was built with.";

    public BuildDetailsPreference(Context context) {
        super(context);
        setKey(KEY);
        setTitle(L10n.t(context, "Build details"));
        setSummary(L10n.t(context, SUMMARY));
        setOnPreferenceClickListener(preference -> {
            if (SettingsUi.dialogOpen(KEY, context)) return true;
            String report = BuildDetails.report();
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(L10n.t(context, "Build details"))
                    .setMessage(report)
                    .setPositiveButton(L10n.t(context, "Copy build details"),
                            (shown, which) -> copy(context, report))
                    .setNeutralButton(L10n.t(context, "Save build details"),
                            (shown, which) -> LogBufferManager.exportBuildDetailsToFile())
                    .setNegativeButton(L10n.t(context, "Close"), null)
                    .show();
            SettingsUi.dialogOpened(KEY, context, dialog);
            SettingsUi.styleStandardAlertDialog(dialog);
            TextView details = dialog.findViewById(android.R.id.message);
            if (details != null) {
                details.setTypeface(Typeface.MONOSPACE);
                details.setTextIsSelectable(true);
            }
            return true;
        });
    }

    private static void copy(Context context, String report) {
        try {
            Utils.setClipboard(context, L10n.t(context, "Build details"), report);
            Utils.showToastShort(L10n.t(context, "Build details copied to the clipboard"));
        } catch (Exception failure) {
            Logger.printException(() -> "Failed to copy build details", failure);
            Utils.showToastLong(L10n.t(context,
                    "Couldn't copy the build details. Use Save build details instead."));
        }
    }

    @Override protected void onBindView(View view) {
        super.onBindView(view);
        SettingsUi.styleTitleAndSummary(view);
    }
}
