/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Parcelable;
import android.preference.DialogPreference;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.share.ShareTargets;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Picks the apps added to the share sheet's Share via row. The list is every installed app that
 * takes shared text, read off the main thread when the dialog opens. A picked app that has since
 * been uninstalled stays picked, and comes back if it's installed again.
 */
@SuppressWarnings("deprecation")
public final class ShareAppPickerPreference extends DialogPreference {
    private final List<ShareTargets.App> apps = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private LinearLayout rows;
    private EditText search;
    private TextView resultCount;
    private boolean loaded;
    /** The lookup threw or never started, which is not the same as finding no apps. */
    private boolean lookupFailed;
    private boolean selectionSaved;
    /** The unsaved picks and search a recreated screen hands back, while it reopens the dialog. */
    private Bundle restoring;

    public ShareAppPickerPreference(Context context) {
        super(context);
        setKey("share_added_apps_picker");
        setTitle("Add apps to Share via");
        setSummary("Pick apps to add to the Share via row, before More. Each gets the same link the other apps get.");
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
    protected View onCreateDialogView() {
        Context context = getContext();
        apps.clear();
        loaded = false;
        selected.clear();
        selected.addAll(ShareTargets.picked(Settings.SHARE_ADDED_APPS.savedValue()));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        int padding = SettingsUi.dp(context, 22);
        root.setPadding(padding, padding, padding, SettingsUi.dp(context, 8));

        TextView title = SettingsUi.text(context, getTitle().toString(), SettingsUi.TEXT_HEADLINE,
                SettingsUi.textPrimary(), Typeface.BOLD);
        SettingsUi.markDialogHeading(title);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView summary = SettingsUi.text(context, getSummary().toString(), SettingsUi.TEXT_BODY_SMALL,
                SettingsUi.textSecondary(), Typeface.NORMAL);
        LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        summaryParams.setMargins(0, SettingsUi.dp(context, 14), 0, SettingsUi.dp(context, 10));
        root.addView(summary, summaryParams);

        search = new EditText(context);
        search.setSingleLine(true);
        search.setHint(L10n.t(context, "Search apps"));
        search.setTag("share_app_search");
        SettingsUi.styleEditText(search);
        SettingsUi.labelEditor(search, L10n.t(context, "Search apps"));
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        resultCount = SettingsUi.resultCount(context, "share_app_result_count");
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resultParams.topMargin = SettingsUi.dp(context, 10);
        root.addView(resultCount, resultParams);

        ScrollView scroll = new ScrollView(context);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUi.dialogListHeight(context, 300));
        scrollParams.topMargin = SettingsUi.dp(context, 10);
        root.addView(scroll, scrollParams);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderRows(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        lookUpApps();
        return root;
    }

    /** Looks for the apps that take shared text, on opening and again from the retry. */
    private void lookUpApps() {
        Context context = getContext();
        LinearLayout list = rows;
        loaded = false;
        lookupFailed = false;
        renderRows(query());
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            List<ShareTargets.App> found = null;
            try {
                found = ShareTargets.installed(context);
            } catch (Throwable failure) {
                Logger.printException(() -> "Could not list the apps that take shared text", failure);
            }
            List<ShareTargets.App> result = found;
            Utils.runOnMainThread(() -> {
                if (rows != list) return; // The dialog was closed and opened again meanwhile.
                showApps(result);
            });
        });
        // A full background queue never ran the lookup; left alone, the list said "Looking for
        // apps" for as long as the dialog stayed open.
        if (!accepted) showApps(null);
    }

    /** The apps found, or null when the lookup failed or never started. */
    private void showApps(List<ShareTargets.App> found) {
        apps.clear();
        if (found != null) apps.addAll(found);
        loaded = true;
        lookupFailed = found == null;
        renderRows(query());
    }

    private String query() {
        return search == null || search.getText() == null ? "" : search.getText().toString();
    }

    private void renderRows(String query) {
        rows.removeAllViews();
        if (!loaded) {
            addState("Looking for apps", "");
            return;
        }
        if (lookupFailed) {
            addState("Couldn't list your apps. Tap here to try again.", "");
            View retry = rows.getChildAt(0);
            retry.setTag("share_app_retry");
            retry.setOnClickListener(view -> lookUpApps());
            return;
        }
        if (apps.isEmpty()) {
            SettingsUi.setResultCount(resultCount, 0);
            addState("No other app takes shared text", "Install one, then come back here.");
            return;
        }
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        int shown = 0;
        for (ShareTargets.App app : apps) {
            if (!normalized.isEmpty()
                    && !((app.label + " " + app.packageName).toLowerCase(Locale.ROOT).contains(normalized))) {
                continue;
            }
            CheckBox check = new CheckBox(getContext());
            check.setText(app.label);
            SettingsUi.styleCheckBoxRow(check);
            check.setTag("share_app_" + app.packageName);
            check.setChecked(selected.contains(app.packageName));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(app.packageName);
                else selected.remove(app.packageName);
            });
            rows.addView(check, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView identifier = SettingsUi.text(getContext(), app.packageName, SettingsUi.TEXT_CAPTION,
                    SettingsUi.textSecondary(), Typeface.NORMAL);
            // The 48 lines this up under the checkbox label; relative, so RTL puts it on the right.
            identifier.setPaddingRelative(SettingsUi.dp(getContext(), 48), 0,
                    SettingsUi.dp(getContext(), 8), SettingsUi.dp(getContext(), 8));
            rows.addView(identifier, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            shown++;
        }
        SettingsUi.setResultCount(resultCount, shown);
        if (shown == 0) addState("No apps match this search", "Try a different word or clear the search.");
    }

    private void addState(String title, String summary) {
        TextView state = SettingsUi.text(getContext(), L10n.t(getContext(), title), SettingsUi.TEXT_BODY_SMALL,
                SettingsUi.textSecondary(), Typeface.NORMAL);
        state.setPadding(SettingsUi.dp(getContext(), 8), SettingsUi.dp(getContext(), 8),
                SettingsUi.dp(getContext(), 8), SettingsUi.dp(getContext(), 8));
        rows.addView(state, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (summary.isEmpty()) return;
        TextView details = SettingsUi.text(getContext(), L10n.t(getContext(), summary), SettingsUi.TEXT_CAPTION,
                SettingsUi.textSecondary(), Typeface.NORMAL);
        details.setPadding(SettingsUi.dp(getContext(), 8), 0, SettingsUi.dp(getContext(), 8),
                SettingsUi.dp(getContext(), 8));
        rows.addView(details, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        builder.setPositiveButton(L10n.t(getContext(), "Save"), (dialog, which)
                -> onClick(dialog, DialogInterface.BUTTON_POSITIVE));
        builder.setNegativeButton(L10n.t(getContext(), "Cancel"), null);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        if (positiveResult && !selectionSaved) saveSelection();
        rows = null;
        super.onDialogClosed(positiveResult);
    }

    @Override
    protected void showDialog(Bundle state) {
        selectionSaved = false;
        super.showDialog(state);
        Bundle draft = restoring;
        if (draft != null) {
            // The app lookup may still be running; when it lands it draws these picks.
            selected.clear();
            selected.addAll(DialogDraft.picks(draft));
            search.setText(DialogDraft.query(draft));
            renderRows(DialogDraft.query(draft));
        }
        SettingsUi.styleFramedDialog(getDialog());
        AlertDialog dialog = (AlertDialog) getDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!view.isEnabled()) return;
            String picked = String.join(",", selected);
            setChoicesEnabled(dialog, false);
            boolean accepted = Utils.runOnBackgroundThread(() -> {
                boolean saved = Settings.SHARE_ADDED_APPS.save(picked);
                Utils.runOnMainThread(() -> {
                    if (saved) notifyChanged();
                    if (getDialog() != dialog || !dialog.isShowing()) {
                        if (!saved) reportSaveFailure();
                        return;
                    }
                    setChoicesEnabled(dialog, true);
                    if (!saved) {
                        reportSaveFailure();
                        return;
                    }
                    selectionSaved = true;
                    onClick(dialog, DialogInterface.BUTTON_POSITIVE);
                    dialog.dismiss();
                });
            });
            if (!accepted) {
                setChoicesEnabled(dialog, true);
                reportSaveFailure();
            }
        });
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        if (!DialogDraft.open(getDialog()) || search == null) return superState;
        return new DialogDraft(superState, DialogDraft.ofPicks(selected, search.getText()));
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof DialogDraft)) {
            super.onRestoreInstanceState(state);
            return;
        }
        // DialogPreference reopens the dialog from inside this call; showDialog puts the draft back.
        restoring = ((DialogDraft) state).values;
        try {
            super.onRestoreInstanceState(((DialogDraft) state).getSuperState());
        } finally {
            restoring = null;
        }
    }

    private void setChoicesEnabled(AlertDialog dialog, boolean enabled) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(enabled);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(enabled);
        search.setEnabled(enabled);
        for (int index = 0; index < rows.getChildCount(); index++) {
            rows.getChildAt(index).setEnabled(enabled);
        }
    }

    private void reportSaveFailure() {
        Utils.showToastShort(L10n.t(getContext(), "Couldn't save these choices. Try again."));
    }

    private void saveSelection() {
        if (Settings.SHARE_ADDED_APPS.save(String.join(",", selected))) {
            notifyChanged();
        } else {
            reportSaveFailure();
        }
    }
}
