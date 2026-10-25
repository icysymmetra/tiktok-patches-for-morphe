/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
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

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.share.ShareActionCatalog;
import app.morphe.extension.tiktok.share.ShareModelFilter;
import app.morphe.extension.tiktok.share.ShareSurface;
import app.morphe.extension.tiktok.settings.L10n;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Edits the share actions one sheet hides, while preserving manually saved unknown identifiers.
 *
 * <p>Videos, and every sheet that is neither a profile nor a LIVE, share the original list. The
 * profile and LIVE sheets each have their own, which follows the video list until it is first
 * saved here, so the dialog opens on whatever that sheet hides today.
 */
@SuppressWarnings("deprecation")
public final class ShareActionChecklistPreference extends DialogPreference {
    private final ShareSurface surface;
    private final List<ShareActionCatalog.Entry> catalog = new ArrayList<>();
    private final Set<String> selected = new LinkedHashSet<>();
    private LinearLayout rows;
    private EditText search;
    private TextView resultCount;
    private String originalHidden = "";
    private boolean selectionSaved;
    /** The unsaved picks and search a recreated screen hands back, while it reopens the dialog. */
    private Bundle restoring;

    public ShareActionChecklistPreference(Context context) {
        this(context, ShareSurface.VIDEO);
    }

    public ShareActionChecklistPreference(Context context, ShareSurface surface) {
        super(context);
        this.surface = surface;
        switch (surface) {
            case PROFILE:
                setKey("share_action_checklist_profile");
                setTitle("Profile share actions");
                setSummary("Until you choose here, this sheet follows your choices under Hide share actions.");
                break;
            case LIVE:
                setKey("share_action_checklist_live");
                setTitle("LIVE share actions");
                setSummary("Until you choose here, this sheet follows your choices under Hide share actions.");
                break;
            default:
                setKey("share_action_checklist");
                setTitle("Hide share actions");
                setSummary("Pick the actions to remove from the share sheet. Names you typed by hand that TikTok hasn't shown yet stay in the list above.");
                break;
        }
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
        catalog.addAll(ShareActionCatalog.entries(surface));
        originalHidden = ShareModelFilter.savedHiddenItems(surface);
        selected.clear();
        selected.addAll(selectedKeys(originalHidden));

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
        search.setHint(L10n.t(context, "Search share actions"));
        search.setTag("share_action_search");
        SettingsUi.styleEditText(search);
        SettingsUi.labelEditor(search, L10n.t(context, "Search share actions"));
        root.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        resultCount = SettingsUi.resultCount(context, "share_action_result_count");
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
        renderRows("");
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                renderRows(s == null ? "" : s.toString());
            }
            @Override public void afterTextChanged(android.text.Editable s) { }
        });
        return root;
    }

    private void renderRows(String query) {
        rows.removeAllViews();
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (catalog.isEmpty()) {
            SettingsUi.setResultCount(resultCount, 0);
            addState("No share actions have been seen yet",
                    "Open a share sheet once, then return here to choose its actions.");
            return;
        }
        int shown = 0;
        for (ShareActionCatalog.Entry entry : catalog) {
            if (!normalized.isEmpty()
                    && !((entry.label + " " + entry.key).toLowerCase(Locale.ROOT).contains(normalized))) {
                continue;
            }
            CheckBox check = new CheckBox(getContext());
            check.setText(entry.label);
            SettingsUi.styleCheckBoxRow(check);
            check.setTag("share_action_" + entry.key);
            check.setChecked(selected.contains(entry.key));
            check.setOnCheckedChangeListener((button, checked) -> {
                if (checked) selected.add(entry.key);
                else selected.remove(entry.key);
            });
            rows.addView(check, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            TextView identifier = SettingsUi.text(getContext(), entry.key, SettingsUi.TEXT_CAPTION,
                    SettingsUi.textSecondary(), Typeface.NORMAL);
            // Relative padding: the 48 is the indent that lines this up under the checkbox
            // label above it, and in an RTL layout that checkbox is on the right.
            identifier.setPaddingRelative(SettingsUi.dp(getContext(), 48), 0,
                    SettingsUi.dp(getContext(), 8), SettingsUi.dp(getContext(), 8));
            identifier.setTag("share_action_id_" + entry.key);
            rows.addView(identifier, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            shown++;
        }
        SettingsUi.setResultCount(resultCount, shown);
        if (shown == 0) {
            addState("No share actions match this search", "Try a different word or clear the search.");
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
            search.setText(DialogDraft.query(draft));
            renderRows(DialogDraft.query(draft));
        }
        SettingsUi.styleFramedDialog(getDialog());
        AlertDialog dialog = (AlertDialog) getDialog();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!view.isEnabled()) return;
            String hidden = buildHidden();
            setChoicesEnabled(dialog, false);
            boolean accepted = Utils.runOnBackgroundThread(() -> {
                boolean saved = ShareModelFilter.hiddenSetting(surface).save(hidden);
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

    private boolean saveSelection() {
        if (ShareModelFilter.hiddenSetting(surface).save(buildHidden())) {
            notifyChanged();
            return true;
        }
        reportSaveFailure();
        return false;
    }

    private Set<String> selectedKeys(String stored) {
        Set<String> keys = new HashSet<>();
        for (String token : tokens(stored)) {
            String canonical = ShareActionCatalog.canonical(token);
            for (ShareActionCatalog.Entry entry : catalog) {
                if (entry.key.equals(canonical)
                        || ShareActionCatalog.canonical(entry.label).equals(canonical)) {
                    keys.add(entry.key);
                }
            }
        }
        return keys;
    }

    private String buildHidden() {
        Set<String> known = new HashSet<>();
        for (ShareActionCatalog.Entry entry : catalog) {
            known.add(entry.key);
            known.add(ShareActionCatalog.canonical(entry.label));
        }
        List<String> output = new ArrayList<>();
        for (String token : tokens(originalHidden)) {
            if (!known.contains(ShareActionCatalog.canonical(token))) {
                output.add(token);
            }
        }
        for (ShareActionCatalog.Entry entry : catalog) {
            if (selected.contains(entry.key)) output.add(entry.key);
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
