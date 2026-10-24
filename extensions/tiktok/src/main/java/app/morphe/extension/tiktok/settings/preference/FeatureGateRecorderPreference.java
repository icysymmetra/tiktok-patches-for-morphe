/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.settings.L10n;
import android.app.AlertDialog;
import android.content.Context;
import android.preference.Preference;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import app.morphe.extension.shared.settings.preference.ImmediateAction;
import app.morphe.extension.tiktok.Utils;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLearnMode;

@SuppressWarnings("deprecation")
public final class FeatureGateRecorderPreference extends Preference implements ImmediateAction {
    private static final String KEY = "feature_gate_recorder";

    public FeatureGateRecorderPreference(Context context) {
        super(context);
        setKey(KEY);
        refresh();
        setOnPreferenceClickListener(preference -> {
            if (SettingsUi.dialogOpen(KEY, context)) return true;
            if (FeatureGateLearnMode.isRecording()) {
                showReport(context, FeatureGateLearnMode.stopAndBuildReport());
            } else if (hasLastReport()) {
                offerStartOrLastReport(context);
            } else {
                begin();
            }
            refresh();
            notifyChanged();
            return true;
        });
    }

    /** A tap starts a recording on the spot only when there is nothing else it could mean. */
    @Override public boolean actsOnTap() {
        return !FeatureGateLearnMode.isRecording() && !hasLastReport();
    }

    private static boolean hasLastReport() {
        return !FeatureGateLearnMode.lastReport().isEmpty();
    }

    private static void begin() {
        FeatureGateLearnMode.begin();
        app.morphe.extension.shared.Utils.showToastShort(
                app.morphe.extension.tiktok.settings.L10n.t(
                        "Recording gate reads. Use a feature, then return here to stop."));
    }

    /**
     * The report from the last recording outlives its dialog. The recorder keeps it, and a row
     * that only ever started the next recording gave a reader who closed the dialog too soon no
     * way back to it.
     */
    private void offerStartOrLastReport(Context context) {
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(L10n.t(context, "Start feature gate recording"))
                .setMessage(L10n.t(context, "Compare gate reads with their previous values.")
                        + "\n" + lastRecordingLine(context))
                .setPositiveButton(L10n.t(context, "Start recording"), (ignored, which) -> {
                    begin();
                    refresh();
                    notifyChanged();
                })
                .setNeutralButton(L10n.t(context, "Show last report"),
                        (ignored, which) -> showReport(context, FeatureGateLearnMode.lastReport()))
                .setNegativeButton(L10n.t(context, "Cancel"), null)
                .create();
        dialog.show();
        SettingsUi.dialogOpened(KEY, context, dialog);
        SettingsUi.styleStandardAlertDialog(dialog);
        SettingsUi.styleActionButton(dialog.getButton(AlertDialog.BUTTON_POSITIVE), true);
        SettingsUi.styleActionButton(dialog.getButton(AlertDialog.BUTTON_NEUTRAL), false);
        SettingsUi.styleActionButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), false);
    }

    public static void showReport(Context context, String report) {
        boolean empty = FeatureGateLearnMode.lastCandidateCount() == 0;
        ScrollView scroll = new ScrollView(context);
        TextView text = new TextView(context);
        String shown = report.length() <= GateReportExport.MAX_CLIPBOARD_CHARS ? report
                : report.substring(0, GateReportExport.MAX_CLIPBOARD_CHARS) + "\n\n"
                        + L10n.t(context,
                                "Preview shortened. Save report keeps the full report.");
        // Nothing read is a sentence, not a JSON object with an empty array to puzzle over.
        text.setText(empty
                ? L10n.t(context, "No gate reads were recorded. Use a TikTok feature while recording.")
                        + "\n\n" + shown
                : shown);
        text.setTextIsSelectable(true);
        text.setTextColor(SettingsUi.textPrimary());
        text.setTextSize(SettingsUi.TEXT_LABEL);
        text.setTypeface(android.graphics.Typeface.MONOSPACE);
        text.setLineSpacing(SettingsUi.dp(context, 3), 1f);
        text.setBackground(SettingsUi.borderedSurface(context, SettingsUi.RADIUS_FIELD, false));
        int padding = SettingsUi.dp(context, 16);
        text.setPadding(padding, padding, padding, padding);
        scroll.setPadding(padding, 0, padding, 0);
        scroll.addView(text);
        scroll.setFillViewport(true);
        scroll.setMinimumHeight(SettingsUi.dp(context, Math.min(540,
                context.getResources().getConfiguration().screenHeightDp * 3 / 5)));
        TextView title = SettingsUi.text(context,
                L10n.f(context, "Recorded gate reads (%1$d)",
                        FeatureGateLearnMode.lastCandidateCount()),
                SettingsUi.TEXT_HEADLINE, SettingsUi.textPrimary(), 1);
        SettingsUi.markDialogHeading(title);
        title.setPadding(padding, padding, padding, SettingsUi.dp(context, 12));
        // Save JSON is the accented action and sits last, like every other dialog's. Copy and
        // Save act without closing: the listeners are set after show() so the dialog's own
        // dismissing ones are replaced, and the preview stays on screen once it is copied. An
        // empty recording has nothing to copy or save.
        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setCustomTitle(title)
                .setView(scroll)
                .setNegativeButton(L10n.t(context, "Close"), null);
        if (!empty) {
            builder.setPositiveButton(L10n.t(context, "Save report"), null);
            if (report.length() <= GateReportExport.MAX_CLIPBOARD_CHARS) {
                builder.setNeutralButton(L10n.t(context, "Copy report"), null);
            }
        }
        AlertDialog dialog = builder.create();
        dialog.show();
        SettingsUi.dialogOpened(KEY, context, dialog);
        SettingsUi.styleStandardAlertDialog(dialog);
        Button save = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (save != null && !empty) {
            save.setOnClickListener(ignored -> GateReportExport.save(context, report));
            SettingsUi.styleActionButton(save, true);
        }
        Button copy = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        if (copy != null && !empty) {
            copy.setOnClickListener(ignored -> GateReportExport.copy(context, report));
        }
        SettingsUi.styleActionButton(dialog.getButton(AlertDialog.BUTTON_NEGATIVE), false);
    }

    private void refresh() {
        setTitle(FeatureGateLearnMode.isRecording() ? "Stop feature gate recording" : "Start feature gate recording");
        if (FeatureGateLearnMode.isRecording()) {
            setSummary("Return after using a TikTok feature to see every gate read during the recording.");
        } else if (hasLastReport()) {
            // Two sentences on two lines, each a translation entry of its own. Joined into one
            // sentence they were one key with a number in it, which no table can carry once the
            // number is written in.
            setSummary(L10n.t(getContext(), "Compare gate reads with their previous values.")
                    + "\n" + lastRecordingLine(getContext()));
        } else {
            // Nothing has run yet, so the row must not report a recording of nought gates.
            setSummary("No recording yet. Start one, use a feature, then return here.");
        }
    }

    /** "Last recording: 1 gate." or "Last recording: 12 gates.", in the reader's language. */
    private static String lastRecordingLine(Context context) {
        int count = FeatureGateLearnMode.lastCandidateCount();
        return L10n.quantity(context, count, "Last recording: 1 gate.", "Last recording: %1$d gates.");
    }

    @Override protected void onBindView(View view) {
        refresh();
        super.onBindView(view);
        Utils.setTitleAndSummaryColor(view);
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
