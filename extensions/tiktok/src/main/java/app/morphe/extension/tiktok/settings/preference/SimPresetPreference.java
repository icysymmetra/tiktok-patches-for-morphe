/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.settings.L10n;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.spoof.sim.SimPreset;
import app.morphe.extension.tiktok.spoof.sim.SimPresets;

@SuppressWarnings("deprecation")
public class SimPresetPreference extends Preference {
    private final List<SimPreset> visiblePresets = new ArrayList<>();

    /**
     * The status line and the explanation under an empty list, held for the length of one open
     * dialog. A no-match used to be a disabled row inside the list itself, which left the list
     * reporting one item and made the explanation look like a result that could not be picked.
     */
    private TextView resultCount;
    private TextView emptyState;
    private ListView presetList;
    private final InputTextPreference countryIsoPreference;
    private final InputTextPreference mccMncPreference;
    private final InputTextPreference operatorNamePreference;

    public SimPresetPreference(Context context,
                               InputTextPreference countryIsoPreference,
                               InputTextPreference mccMncPreference,
                               InputTextPreference operatorNamePreference) {
        super(context);
        this.countryIsoPreference = countryIsoPreference;
        this.mccMncPreference = mccMncPreference;
        this.operatorNamePreference = operatorNamePreference;
        setTitle("SIM country preset");
        refreshSummary();
    }

    public void refreshSummary() {
        refreshSummary(
                Settings.SIM_SPOOF_ISO.savedValue(),
                Settings.SIMSPOOF_MCCMNC.savedValue(),
                Settings.SIMSPOOF_OP_NAME.savedValue()
        );
    }

    public void refreshSummary(String iso, String mccMnc, String operatorName) {
        SimPreset selectedPreset = SimPresets.findSelected(iso, mccMnc, operatorName);
        if (selectedPreset != null) {
            setSummary(selectedPreset.country + ", " + selectedPreset.operatorName + " ("
                    + selectedPreset.mccMnc + ")");
        } else if (SimPresets.hasEmptyCurrentValues(iso, mccMnc, operatorName)) {
            setSummary("No preset selected. Choose a preset above.");
        } else {
            setSummary("Custom SIM details");
        }
    }

    @Override
    protected void onClick() {
        showPresetDialog();
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);

        app.morphe.extension.tiktok.Utils.setTitleAndSummaryColor(view);
    }

    private void showPresetDialog() {
        Context context = getContext();
        LinearLayout dialogView = new LinearLayout(context);
        dialogView.setOrientation(LinearLayout.VERTICAL);
        // No background of its own. The card is the window's, so it covers the platform's
        // button panel as well, which is where the actions now live.
        int padding = SettingsUi.dp(getContext(), 22);
        dialogView.setPadding(padding, padding, padding, SettingsUi.dp(getContext(), 8));

        TextView title = new TextView(context);
        title.setText(L10n.t(getContext(), "SIM country preset"));
        title.setTextColor(getTitleTextColor());
        title.setTextSize(SettingsUi.TEXT_HEADLINE);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        SettingsUi.markDialogHeading(title);
        dialogView.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView helper = new TextView(context);
        helper.setText(L10n.t(getContext(), "Choose a preset to fill the SIM details."));
        helper.setTextColor(getSummaryTextColor());
        LinearLayout.LayoutParams helperParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        helperParams.setMargins(0, SettingsUi.dp(getContext(), 14), 0, SettingsUi.dp(getContext(), 10));
        dialogView.addView(helper, helperParams);

        EditText search = new EditText(context);
        search.setSingleLine(true);
        search.setHint(L10n.t(getContext(), "Search countries or operators"));
        search.setTextColor(getTitleTextColor());
        search.setHintTextColor(getSummaryTextColor());
        SettingsUi.styleEditText(search);
        SettingsUi.labelEditor(search, L10n.t(getContext(), "Search countries or operators"));
        dialogView.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        resultCount = SettingsUi.resultCount(context, "sim_preset_result_count");
        LinearLayout.LayoutParams resultParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        resultParams.setMargins(0, SettingsUi.dp(getContext(), 10), 0, 0);
        dialogView.addView(resultCount, resultParams);

        ListView listView = new ListView(context);
        SettingsUi.styleScrollableList(listView);
        listView.setBackgroundColor(Color.TRANSPARENT);
        int listPadding = Math.max(1, SettingsUi.dp(getContext(), 1));
        listView.setPadding(listPadding, listPadding, listPadding, listPadding);
        listView.setClipToPadding(false);
        listView.setDivider(new ColorDrawable(getDialogDividerColor()));
        listView.setDividerHeight(Math.max(1, SettingsUi.dp(getContext(), 1)));
        listView.setFooterDividersEnabled(true);
        PresetAdapter adapter = new PresetAdapter(context, visiblePresets);
        listView.setAdapter(adapter);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        presetList = listView;

        FrameLayout listContainer = new FrameLayout(context);
        listContainer.setBackground(createListBackground());
        int containerInset = Math.max(1, SettingsUi.dp(getContext(), 1));
        listContainer.setPadding(containerInset, containerInset, containerInset, containerInset);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                SettingsUi.dialogListHeight(context, 320)
        );
        listParams.setMargins(0, SettingsUi.dp(getContext(), 12), 0, SettingsUi.dp(getContext(), 14));
        listContainer.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));

        // Over the empty list rather than inside it. Nothing here is selectable, so it takes no
        // focus and is not a second live region: the count above already announces the change,
        // and two of them would say the same thing twice.
        emptyState = SettingsUi.text(context, "", SettingsUi.TEXT_BODY_SMALL, getSummaryTextColor(),
                android.graphics.Typeface.NORMAL);
        emptyState.setGravity(Gravity.CENTER);
        emptyState.setTag("sim_preset_empty_state");
        emptyState.setFocusable(false);
        emptyState.setVisibility(View.GONE);
        int emptyPadding = SettingsUi.dp(getContext(), 16);
        emptyState.setPadding(emptyPadding, emptyPadding, emptyPadding, emptyPadding);
        listContainer.addView(emptyState, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
        ));
        dialogView.addView(listContainer, listParams);

        // The actions are the platform's own. They were the last row of this column, under a
        // list that is never shorter than 220dp, and the custom panel clips rather than scrolls,
        // so at a large text size they were what got cut. The platform keeps its button panel on
        // screen and shrinks the custom panel instead.
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                // The way back to no preset. Picking a row applied it and closed the dialog, and
                // nothing undid that short of retyping three fields.
                .setNeutralButton(L10n.t(context, "Clear preset"), (ignored, which) -> clearPreset())
                .setNegativeButton(L10n.t(context, "Cancel"), null)
                .create();

        listView.setOnItemClickListener((parent, view, position, id) -> {
            SimPreset preset = visiblePresets.get(position);
            if (preset != null && savePreset(preset)) {
                dialog.dismiss();
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterPresets(s.toString(), adapter);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        filterPresets("", adapter);
        dialog.show();
        SettingsUi.styleFramedDialog(dialog);
        View clearButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (clearButton != null) clearButton.setTag("sim_preset_clear");
    }

    private boolean savePreset(SimPreset preset) {
        if (!preset.isValid()) {
            Logger.printException(() -> "That preset is not recognized. Choose another. refused: "
                    + preset.country + " / " + preset.operatorName + " / "
                    + preset.mccMnc + " / " + preset.iso);
            app.morphe.extension.shared.Utils.showToastLong(
                app.morphe.extension.tiktok.settings.L10n.t("That preset isn't recognized. Choose another."));
            return false;
        }

        countryIsoPreference.setText(preset.iso);
        mccMncPreference.setText(preset.mccMnc);
        operatorNamePreference.setText(preset.operatorName);
        refreshSummary(preset.iso, preset.mccMnc, preset.operatorName);

        Logger.printDebug(() -> "SIM preset selected: " + preset.country + " / "
                + preset.operatorName + " / " + preset.mccMnc + " / " + preset.iso);

        if (Settings.SIM_SPOOF.savedValue()) {
            app.morphe.extension.shared.Utils.showToastLong(
                    app.morphe.extension.tiktok.settings.L10n.t("Restart TikTok to apply this"));
        }

        return true;
    }

    private void filterPresets(String query, PresetAdapter adapter) {
        visiblePresets.clear();
        for (SimPreset preset : SimPresets.PRESETS) {
            if (preset.matches(query)) {
                visiblePresets.add(preset);
            }
        }

        markSelected(adapter);

        // The count is the one thing that speaks, and only when it changes. The explanation
        // under it is there to be read, not announced a second time.
        SettingsUi.setResultCount(resultCount, visiblePresets.size());
        if (emptyState == null) return;
        if (visiblePresets.isEmpty()) {
            emptyState.setText(L10n.t(getContext(), "No matching countries. Try a country name or a two-letter code."));
            emptyState.setVisibility(View.VISIBLE);
        } else {
            emptyState.setVisibility(View.GONE);
        }
    }

    /**
     * Marks the preset the three fields already hold and brings it into view.
     *
     * <p>The list used to be sixty identical rows with no sign of which one was on, and the only
     * way to tell was to close the dialog and read the row summary.
     */
    private void markSelected(PresetAdapter adapter) {
        SimPreset selected = SimPresets.findSelected(Settings.SIM_SPOOF_ISO.savedValue(),
                Settings.SIMSPOOF_MCCMNC.savedValue(), Settings.SIMSPOOF_OP_NAME.savedValue());
        adapter.selected = selected;
        adapter.notifyDataSetChanged();
        if (presetList == null) return;
        int index = selected == null ? -1 : visiblePresets.indexOf(selected);
        if (index < 0) {
            presetList.clearChoices();
            return;
        }
        presetList.setItemChecked(index, true);
        presetList.setSelection(index);
    }

    /**
     * Back to no preset: the three settings blank, the three rows showing that, and the row
     * summary saying so. The settings are written here as well as through the rows, because
     * a row persists through its preference manager and a row on its own has none.
     */
    private void clearPreset() {
        Settings.SIM_SPOOF_ISO.save("");
        Settings.SIMSPOOF_MCCMNC.save("");
        Settings.SIMSPOOF_OP_NAME.save("");
        countryIsoPreference.setText("");
        mccMncPreference.setText("");
        operatorNamePreference.setText("");
        refreshSummary("", "", "");
    }

    private GradientDrawable createListBackground() {
        return SettingsUi.borderedSurface(getContext(), SettingsUi.RADIUS_SETTINGS_FIELD, false);
    }

    private static int getDialogBackgroundColor() {
        return SettingsUi.surface();
    }

    private static int getDialogDividerColor() {
        return SettingsUi.divider();
    }

    private static int getTitleTextColor() {
        return SettingsUi.textPrimary();
    }

    private static int getSummaryTextColor() {
        return SettingsUi.textSecondary();
    }

    private static class PresetAdapter extends ArrayAdapter<SimPreset> {
        /** The preset the fields hold right now, marked on its row; null when they hold none. */
        SimPreset selected;

        PresetAdapter(Context context, List<SimPreset> presets) {
            super(context, android.R.layout.simple_list_item_2, android.R.id.text1, presets);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            TextView title = view.findViewById(android.R.id.text1);
            TextView summary = view.findViewById(android.R.id.text2);
            // Every row is a real preset now. A no-match used to put a null in here and render
            // it as a disabled row, which is a sentence pretending to be a country.
            SimPreset preset = getItem(position);

            title.setText(preset.country);
            summary.setText(preset.getSummary());
            view.setEnabled(true);

            // The chosen row wears the radio the standard dialogs draw. TalkBack hears it from the
            // list, which reports its checked row (markSelected) through the item delegate it
            // gives every row that has none. A delegate of the row's own replaced that one, and
            // with it the click action Switch Access and Voice Access pick a preset with. Nor is
            // the row marked selected: the list uses that for the row a d-pad is on, and the
            // row's drawable washes a selected row, so the chosen preset looked focused.
            boolean chosen = selected != null && preset == selected;
            Context context = parent.getContext();
            int gap = SettingsUi.dp(context, 8);
            Drawable ring = SettingsUi.radioMark(context);
            int column = ring.getIntrinsicWidth() + gap;
            title.setCompoundDrawablePadding(gap);
            Drawable mark = chosen ? ring : null;
            if (mark != null) {
                // Its own 32dp height is taller than a line of the title, so the chosen row's
                // title grew and its operator line sat about 10dp below every other row's. One
                // line high, the 18dp ring still fits.
                mark.setBounds(0, 0, mark.getIntrinsicWidth(),
                        Math.min(mark.getIntrinsicHeight(), title.getLineHeight()));
            }
            title.setCompoundDrawablesRelative(mark, null, null, null);
            // Every row keeps the mark's column, as the platform's single-choice lists keep their
            // radio column. Only the chosen title gave the mark its width, so at a large font a
            // long country name wrapped only when chosen and its operator line dropped a line.
            title.setPaddingRelative(chosen ? 0 : column, 0, 0, 0);
            summary.setPaddingRelative(column, 0, 0, 0);

            // The fill under the press and the focus. Set flat, it covered the ListView's
            // own selector, so pressing a preset looked like nothing at all.
            view.setBackground(SettingsUi.listRow(parent.getContext(), getDialogBackgroundColor()));
            title.setTextColor(getTitleTextColor());
            summary.setTextColor(getSummaryTextColor());
            return view;
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
