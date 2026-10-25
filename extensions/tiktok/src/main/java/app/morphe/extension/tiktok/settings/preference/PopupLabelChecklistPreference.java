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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.popups.PopupLabelCatalog;
import app.morphe.extension.tiktok.popups.PopupLabels;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Picks the popups to block from the ones TikTok has tried to show on this phone. A pick the
 * list no longer holds is kept. Prompts the account has to answer never reach the list.
 */
@SuppressWarnings("deprecation")
public final class PopupLabelChecklistPreference extends DialogPreference {
    private final List<String> catalog = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private LinearLayout rows;
    private String originalPicks = "";
    private boolean selectionSaved;
    /** The unsaved picks a recreated screen hands back, while it reopens the dialog. */
    private Bundle restoring;

    public PopupLabelChecklistPreference(Context context) {
        super(context);
        setKey("popup_label_checklist");
        setTitle("Block popups");
        setSummary("Pick the popups to stop. The list holds the ones TikTok has tried to show on this phone, so a popup appears here after its first showing. CAPTCHA, verification, sign-in, age, ban and legal consent screens are never listed or blocked.");
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
        catalog.clear();
        catalog.addAll(PopupLabelCatalog.labels());
        // The saved value, not get(): paused, get() answers the default, and a save would wipe it.
        originalPicks = Settings.POPUP_LABEL_PICKS.savedValue();
        selected.clear();
        selected.addAll(selectedKeys(originalPicks));

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

        ScrollView scroll = new ScrollView(context);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(rows, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, SettingsUi.dialogListHeight(context, 300));
        scrollParams.topMargin = SettingsUi.dp(context, 10);
        root.addView(scroll, scrollParams);
        renderRows();
        return root;
    }

    private void renderRows() {
        rows.removeAllViews();
        if (catalog.isEmpty()) {
            addState("No popups have been seen yet",
                    "Use TikTok for a while. A popup shows up here after TikTok first tries to show it.");
            return;
        }
        for (String label : catalog) {
            String key = PopupLabels.key(label);
            CheckBox check = new CheckBox(getContext());
            check.setText(label);
            SettingsUi.styleCheckBoxRow(check);
            check.setTag("popup_label_" + key);
            check.setChecked(selected.contains(key));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(key);
                else selected.remove(key);
            });
            rows.addView(check, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    private void addState(String title, String summary) {
        TextView state = SettingsUi.text(getContext(), L10n.t(getContext(), title), SettingsUi.TEXT_BODY_SMALL,
                SettingsUi.textSecondary(), Typeface.NORMAL);
        state.setPadding(SettingsUi.dp(getContext(), 8), SettingsUi.dp(getContext(), 8),
                SettingsUi.dp(getContext(), 8), SettingsUi.dp(getContext(), 8));
        rows.addView(state, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
        super.onDialogClosed(positiveResult);
    }

    @Override
    protected void showDialog(Bundle state) {
        selectionSaved = false;
        super.showDialog(state);
        Bundle draft = restoring;
        if (draft != null) {
            selected.clear();
            selected.addAll(DialogDraft.picks(draft));
            renderRows();
        }
        SettingsUi.styleFramedDialog(getDialog());
        AlertDialog dialog = (AlertDialog) getDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!view.isEnabled()) return;
            String picks = buildPicks();
            setChoicesEnabled(dialog, false);
            boolean accepted = Utils.runOnBackgroundThread(() -> {
                boolean saved = Settings.POPUP_LABEL_PICKS.save(picks);
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
        if (!DialogDraft.open(getDialog()) || rows == null) return superState;
        return new DialogDraft(superState, DialogDraft.ofPicks(selected, null));
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
        for (int index = 0; index < rows.getChildCount(); index++) {
            rows.getChildAt(index).setEnabled(enabled);
        }
    }

    private void reportSaveFailure() {
        Utils.showToastShort(L10n.t(getContext(), "Couldn't save these choices. Try again."));
    }

    private void saveSelection() {
        if (Settings.POPUP_LABEL_PICKS.save(buildPicks())) {
            notifyChanged();
        } else {
            reportSaveFailure();
        }
    }

    private Set<String> selectedKeys(String stored) {
        Set<String> picked = new HashSet<>();
        for (String token : tokens(stored)) picked.add(PopupLabels.key(token));
        Set<String> keys = new HashSet<>();
        for (String label : catalog) {
            String key = PopupLabels.key(label);
            if (picked.contains(key)) keys.add(key);
        }
        return keys;
    }

    String buildPicks() {
        Set<String> known = new HashSet<>();
        for (String label : catalog) known.add(PopupLabels.key(label));
        List<String> output = new ArrayList<>();
        for (String token : tokens(originalPicks)) {
            if (!known.contains(PopupLabels.key(token))) output.add(token);
        }
        for (String label : catalog) {
            String key = PopupLabels.key(label);
            if (selected.contains(key)) output.add(key);
        }
        return String.join(", ", output);
    }

    private static List<String> tokens(String stored) {
        List<String> result = new ArrayList<>();
        if (stored == null) return result;
        for (String token : stored.split("[,\\n]")) {
            String value = token.trim();
            if (!value.isEmpty()) result.add(value);
        }
        return result;
    }
}
