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
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.feedfilter.AdvancedFeedRules;
import app.morphe.extension.tiktok.feedfilter.FeedRuleLimits;
import app.morphe.extension.tiktok.settings.L10n;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A searchable, one-row-per-entry editor for locally hidden creators. */
@SuppressWarnings("deprecation")
public class CreatorListPreference extends DialogPreference {
    private String value;
    private boolean valueSet;
    private List<String> pendingEntries = new ArrayList<>();
    private LinearLayout entriesContainer;
    private EditText searchEditText;
    private EditText addEditText;
    private TextView emptyState;
    private TextView resultCount;
    private final List<TextView> removeButtons = new ArrayList<>();
    /** The unsaved list, filter and typed handle a recreated screen hands back, while it reopens the dialog. */
    private Bundle restoring;
    private static final String TYPED = "typed";

    public CreatorListPreference(Context context, String title, String summary, StringSetting setting) {
        super(context);
        setTitle(title);
        setSummary(summary);
        setKey(setting.key);
        setValue(setting.savedValue());
    }

    public void setValue(String nextValue) {
        String normalized = nextValue == null ? "" : nextValue;
        boolean changed = !TextUtils.equals(value, normalized);
        if (changed || !valueSet) {
            value = normalized;
            valueSet = true;
            persistString(normalized);
            if (changed) {
                notifyDependencyChange(shouldDisableDependents());
                notifyChanged();
            }
        }
    }

    public String getValue() {
        return value;
    }

    @Override
    protected View onCreateDialogView() {
        Context context = getContext();
        pendingEntries = new ArrayList<>(AdvancedFeedRules.creatorEntries(getValue()));

        LinearLayout dialogView = new LinearLayout(context);
        dialogView.setOrientation(LinearLayout.VERTICAL);
        int padding = SettingsUi.dp(context, 22);
        dialogView.setPadding(padding, padding, padding, SettingsUi.dp(context, 8));

        TextView title = SettingsUi.text(
                context,
                getTitle() == null ? "" : getTitle().toString(),
                SettingsUi.TEXT_HEADLINE,
                SettingsUi.textPrimary(),
                Typeface.BOLD
        );
        SettingsUi.markDialogHeading(title);
        dialogView.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        if (getSummary() != null && getSummary().length() > 0) {
            TextView summary = SettingsUi.text(
                    context,
                    getSummary().toString(),
                    SettingsUi.TEXT_BODY_SMALL,
                    SettingsUi.textSecondary(),
                    Typeface.NORMAL
            );
            LinearLayout.LayoutParams summaryParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            summaryParams.setMargins(0, SettingsUi.dp(context, 14), 0, SettingsUi.dp(context, 10));
            dialogView.addView(summary, summaryParams);
        }

        TextView searchLabel = SettingsUi.text(
                context,
                L10n.t(context, "Search hidden creators"),
                SettingsUi.TEXT_BODY_SMALL,
                SettingsUi.textSecondary(),
                Typeface.BOLD
        );
        dialogView.addView(searchLabel);

        searchEditText = editor(context, L10n.t(context, "Filter the list"));
        searchEditText.setTag("creator_list_search");
        SettingsUi.labelEditor(searchLabel, searchEditText);
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                refreshEntryRows();
            }
            @Override public void afterTextChanged(Editable text) { }
        });
        LinearLayout.LayoutParams searchParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        searchParams.setMargins(0, SettingsUi.dp(context, 4), 0, SettingsUi.dp(context, 10));
        dialogView.addView(searchEditText, searchParams);

        TextView addLabel = SettingsUi.text(
                context,
                L10n.t(context, "Add a creator"),
                SettingsUi.TEXT_BODY_SMALL,
                SettingsUi.textSecondary(),
                Typeface.BOLD
        );
        LinearLayout.LayoutParams addLabelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        addLabelParams.setMargins(0, SettingsUi.dp(context, 10), 0, 0);
        dialogView.addView(addLabel, addLabelParams);

        LinearLayout addRow = new LinearLayout(context);
        addRow.setGravity(Gravity.CENTER_VERTICAL);
        addEditText = editor(context, L10n.t(context, "Handle or id"));
        addEditText.setTag("creator_list_add");
        SettingsUi.labelEditor(addLabel, addEditText);
        addRow.addView(addEditText, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // Flat, like the Cancel and Save two rows below it. styleActionButton sets the colour
        // and the weight and leaves the platform background, so this was the only raised button
        // in any Hushfeed dialog.
        TextView addButton = new TextView(context);
        addButton.setTag("creator_list_add_button");
        addButton.setText(L10n.t(context, "Add"));
        addButton.setContentDescription(L10n.t(context, "Add hidden creator"));
        addButton.setTextSize(SettingsUi.TEXT_TITLE);
        SettingsUi.styleTextAction(addButton, true);
        addButton.setOnClickListener(view -> addEntry());
        LinearLayout.LayoutParams addButtonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        addButtonParams.setMarginStart(SettingsUi.dp(context, 8));
        addRow.addView(addButton, addButtonParams);
        dialogView.addView(addRow);

        resultCount = SettingsUi.resultCount(context, "creator_list_result_count");
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        resultParams.setMargins(0, SettingsUi.dp(context, 10), 0, 0);
        dialogView.addView(resultCount, resultParams);

        int maxHeight = SettingsUi.dialogListHeight(context, 230);
        ScrollView scroll = new ScrollView(context) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                int capped = android.view.View.MeasureSpec.makeMeasureSpec(
                        maxHeight, android.view.View.MeasureSpec.AT_MOST);
                super.onMeasure(widthSpec, capped);
            }
        };
        entriesContainer = new LinearLayout(context);
        entriesContainer.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(entriesContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        scrollParams.setMargins(0, SettingsUi.dp(context, 12), 0, 0);
        dialogView.addView(scroll, scrollParams);

        // Inside the list rather than under it. Added after the scroll view, an empty list read
        // as a 230dp blank band with "No creators are hidden yet" below it, which is a list that
        // has failed to load rather than one with nothing in it. ShareActionChecklistPreference
        // puts its own state rows in the container for the same reason.
        emptyState = SettingsUi.text(context, "", SettingsUi.TEXT_BODY_SMALL, SettingsUi.textSecondary(), Typeface.NORMAL);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setPadding(SettingsUi.dp(context, 12), SettingsUi.dp(context, 18),
                SettingsUi.dp(context, 12), SettingsUi.dp(context, 18));


        refreshEntryRows();
        return dialogView;
    }

    private static EditText editor(Context context, String hint) {
        EditText editor = new EditText(context);
        editor.setInputType(InputType.TYPE_CLASS_TEXT);
        editor.setSingleLine(true);
        editor.setHint(hint);
        SettingsUi.styleEditText(editor);
        editor.setBackgroundTintList(null);
        editor.setBackground(SettingsUi.focusableSurface(context, SettingsUi.RADIUS_SETTINGS_FIELD, false));
        editor.setPaddingRelative(SettingsUi.dp(context, 12), SettingsUi.dp(context, 10),
                SettingsUi.dp(context, 12), SettingsUi.dp(context, 10));
        editor.setMinHeight(SettingsUi.dp(context, 48));
        return editor;
    }

    /**
     * What is wrong with what is in the add box, or null when it can be added.
     *
     * <p>Separate from adding it so the Save button can ask the same question before the dialog
     * closes. An empty box is not a problem here: Save with nothing typed saves the list as it
     * stands, which is the ordinary way to leave this editor.
     */
    private String typedEntryProblem() {
        String candidate = addEditText == null ? "" : addEditText.getText().toString().trim();
        if (candidate.isEmpty()) return null;

        String problem = AdvancedFeedRules.creatorEntryProblem(candidate);
        if (problem != null) return problem;
        if (AdvancedFeedRules.hasCreatorEntry(
                AdvancedFeedRules.joinCreatorEntries(pendingEntries), candidate)) {
            return L10n.t("That creator is already in the list");
        }
        return FeedRuleLimits.creatorProblem(AdvancedFeedRules.addCreatorEntry(
                AdvancedFeedRules.joinCreatorEntries(pendingEntries), candidate));
    }

    /** The reason, under the box it is about, where every other editor in the bundle puts it. */
    private void reportProblem(String problem) {
        SettingsUi.reportFieldError(addEditText, problem);
    }

    private void addEntry() {
        String candidate = addEditText == null ? "" : addEditText.getText().toString().trim();
        if (candidate.isEmpty()) {
            reportProblem(L10n.t("Enter a creator handle or id"));
            return;
        }
        String problem = typedEntryProblem();
        if (problem != null) {
            reportProblem(problem);
            return;
        }
        pendingEntries.add(candidate);
        SettingsUi.clearFieldError(addEditText);
        addEditText.setText("");
        refreshEntryRows();
        Utils.showToastShort(L10n.f(getContext(), "Added %1$s", L10n.isolate(candidate)));
    }

    private void refreshEntryRows() {
        if (entriesContainer == null) return;
        entriesContainer.removeAllViews();
        removeButtons.clear();
        String query = searchEditText == null ? "" : searchEditText.getText().toString()
                .trim().toLowerCase(Locale.ROOT);
        int visible = 0;
        for (String entry : pendingEntries) {
            if (!query.isEmpty() && !entry.toLowerCase(Locale.ROOT).contains(query)) continue;
            addEntryRow(entry);
            visible++;
        }
        SettingsUi.setResultCount(resultCount, visible);
        if (visible == 0) {
            emptyState.setText(pendingEntries.isEmpty()
                    ? L10n.t(getContext(), "No creators are hidden yet. Hide one from a video, or add a handle above.")
                    : L10n.t(getContext(), "No hidden creators match this search. Clear the search to see them all."));
            emptyState.setVisibility(View.VISIBLE);
            entriesContainer.addView(emptyState, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            ));
        } else {
            emptyState.setVisibility(View.GONE);
        }
    }

    private void addEntryRow(String entry) {
        Context context = getContext();
        LinearLayout row = new LinearLayout(context);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPaddingRelative(SettingsUi.dp(context, 12), SettingsUi.dp(context, 4),
                SettingsUi.dp(context, 4), SettingsUi.dp(context, 4));
        row.setBackground(SettingsUi.borderedSurface(context, SettingsUi.RADIUS_SETTINGS_FIELD, true));
        TextView label = SettingsUi.text(context, entry, SettingsUi.TEXT_BODY, SettingsUi.textPrimary(), Typeface.NORMAL);
        label.setTextIsSelectable(true);
        label.setContentDescription(entry);
        row.addView(label, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView remove = new TextView(context);
        remove.setText(L10n.t(context, "Remove"));
        remove.setContentDescription(L10n.f(context, "Remove %1$s", L10n.isolate(entry)));
        remove.setGravity(Gravity.CENTER);
        remove.setMinHeight(SettingsUi.dp(context, 48));
        remove.setMinWidth(SettingsUi.dp(context, 72));
        remove.setPadding(SettingsUi.dp(context, 8), 0, SettingsUi.dp(context, 8), 0);
        SettingsUi.styleTextAction(remove, false);
        remove.setTag("creator_remove_" + entry);
        remove.setFocusable(true);
        remove.setFocusableInTouchMode(true);
        remove.setOnClickListener(view -> removeEntry(entry, remove));
        removeButtons.add(remove);
        row.addView(remove, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowParams.setMargins(0, SettingsUi.dp(context, 4), 0, 0);
        entriesContainer.addView(row, rowParams);
    }

    private void removeEntry(String entry, TextView pressed) {
        int position = Math.max(0, removeButtons.indexOf(pressed));
        pendingEntries.remove(entry);
        refreshEntryRows();
        View next = removeButtons.isEmpty()
                ? searchEditText
                : removeButtons.get(Math.min(position, removeButtons.size() - 1));
        if (next != null) {
            Runnable moveFocus = () -> {
                next.requestFocus();
                next.performAccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS,
                        null
                );
            };
            if (next.isAttachedToWindow()) next.post(moveFocus);
            else moveFocus.run();
        }
        Utils.showToastShort(L10n.f(getContext(), "Removed %1$s", L10n.isolate(entry)));
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        app.morphe.extension.tiktok.Utils.setTitleAndSummaryColor(view);
    }

    @Override
    protected void onPrepareDialogBuilder(AlertDialog.Builder builder) {
        builder.setPositiveButton(L10n.t(getContext(), "Save"), (dialog, which)
                -> this.onClick(dialog, DialogInterface.BUTTON_POSITIVE));
        builder.setNegativeButton(L10n.t(getContext(), "Cancel"), null);
    }

    @Override
    protected void onDialogClosed(boolean positiveResult) {
        if (!positiveResult) return;
        // The Save button is intercepted above and never lets a refused list get this far, but
        // the platform can still close a dialog on its own, and a refusal that reaches here
        // has to land under the box rather than nowhere.
        String problem = saveTypedAndList();
        if (problem != null) reportProblem(problem);
    }

    /**
     * Takes the box and the list as they stand, or says what stops it.
     *
     * <p>A handle typed into the box and never added with the button is still what the reader
     * meant to save, so it goes in the way Add would have put it.
     *
     * @return the reason nothing was saved, or null when the list was written.
     */
    private String saveTypedAndList() {
        String typed = addEditText == null ? "" : addEditText.getText().toString().trim();
        if (!typed.isEmpty()) {
            String problem = typedEntryProblem();
            if (problem != null) return problem;
            addEntry();
        }
        String next = AdvancedFeedRules.joinCreatorEntries(pendingEntries);
        String problem = FeedRuleLimits.creatorProblem(next);
        if (problem != null) return problem;
        setValue(next);
        return null;
    }

    @Override
    protected void showDialog(Bundle state) {
        super.showDialog(state);
        Bundle draft = restoring;
        if (draft != null) {
            pendingEntries = new ArrayList<>(DialogDraft.picks(draft));
            searchEditText.setText(DialogDraft.query(draft));
            addEditText.setText(draft.getString(TYPED, ""));
            refreshEntryRows();
        }
        SettingsUi.styleFramedDialog(getDialog());
        // Save used to let the dialog close and then say what was wrong over whatever was
        // behind it, with the handle the reader had typed already gone. The same shape the
        // download path editor was given: the reason goes under the box, the box keeps the
        // text, and nothing is written until it can be.
        SettingsUi.keepOpenOnInvalidInput(getDialog(), new SettingsUi.DialogCheck() {
            @Override public String problem() {
                String typed = addEditText == null ? "" : addEditText.getText().toString().trim();
                if (!typed.isEmpty()) {
                    String problem = typedEntryProblem();
                    if (problem != null) return problem;
                }
                return FeedRuleLimits.creatorProblem(typed.isEmpty()
                        ? AdvancedFeedRules.joinCreatorEntries(pendingEntries)
                        : AdvancedFeedRules.addCreatorEntry(
                                AdvancedFeedRules.joinCreatorEntries(pendingEntries), typed));
            }

            @Override public void report(String problem) {
                reportProblem(problem);
            }

            @Override public boolean accept() {
                return saveTypedAndList() == null;
            }
        });
    }

    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        if (!DialogDraft.open(getDialog()) || searchEditText == null) return superState;
        Bundle draft = DialogDraft.ofPicks(pendingEntries, searchEditText.getText());
        draft.putString(TYPED, addEditText.getText().toString());
        return new DialogDraft(superState, draft);
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

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(L10n.t(getContext(), title));
    }

    @Override
    public void setSummary(CharSequence summary) {
        super.setSummary(L10n.t(getContext(), summary));
    }
}
