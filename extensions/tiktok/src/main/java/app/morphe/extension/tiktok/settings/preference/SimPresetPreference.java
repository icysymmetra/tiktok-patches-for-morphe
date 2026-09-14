package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
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
import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.spoof.sim.SimPreset;
import app.morphe.extension.tiktok.spoof.sim.SimPresets;

@SuppressWarnings("deprecation")
public class SimPresetPreference extends Preference {
    private final List<SimPreset> visiblePresets = new ArrayList<>();
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
        setTitle("Region preset");
        refreshSummary();
    }

    public void refreshSummary() {
        refreshSummary(
                Settings.SIM_SPOOF_ISO.get(),
                Settings.SIMSPOOF_MCCMNC.get(),
                Settings.SIMSPOOF_OP_NAME.get()
        );
    }

    public void refreshSummary(String iso, String mccMnc, String operatorName) {
        SimPreset selectedPreset = SimPresets.findSelected(iso, mccMnc, operatorName);
        if (selectedPreset != null) {
            setSummary(selectedPreset.country + " - " + selectedPreset.operatorName + " - "
                    + selectedPreset.mccMnc);
        } else if (SimPresets.hasEmptyCurrentValues(iso, mccMnc, operatorName)) {
            setSummary("No preset selected");
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
        dialogView.setBackground(createDialogBackground());
        int padding = dpToPx(20);
        dialogView.setPadding(padding, padding, padding, padding);

        TextView title = new TextView(context);
        title.setText("Region preset");
        title.setTextColor(getTitleTextColor());
        title.setTextSize(20);
        title.setTypeface(title.getTypeface(), android.graphics.Typeface.BOLD);
        dialogView.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView helper = new TextView(context);
        helper.setText("Choose a preset to fill these region details.");
        helper.setTextColor(getSummaryTextColor());
        LinearLayout.LayoutParams helperParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        helperParams.setMargins(0, dpToPx(18), 0, dpToPx(8));
        dialogView.addView(helper, helperParams);

        EditText search = new EditText(context);
        search.setSingleLine(true);
        search.setHint("Search countries or operators");
        search.setTextColor(getTitleTextColor());
        search.setHintTextColor(getSummaryTextColor());
        SettingsUi.styleEditText(search);
        dialogView.addView(search, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        ListView listView = new ListView(context);
        listView.setBackgroundColor(Color.TRANSPARENT);
        int listPadding = Math.max(1, dpToPx(1));
        listView.setPadding(listPadding, listPadding, listPadding, listPadding);
        listView.setClipToPadding(false);
        listView.setDivider(new ColorDrawable(getDialogDividerColor()));
        listView.setDividerHeight(Math.max(1, dpToPx(1)));
        listView.setFooterDividersEnabled(true);
        PresetAdapter adapter = new PresetAdapter(context, visiblePresets);
        listView.setAdapter(adapter);

        FrameLayout listContainer = new FrameLayout(context);
        listContainer.setBackground(createListBackground());
        int containerInset = Math.max(1, dpToPx(1));
        listContainer.setPadding(containerInset, containerInset, containerInset, containerInset);
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                SettingsUi.dialogListHeight(context, 320)
        );
        listParams.setMargins(0, dpToPx(12), 0, dpToPx(14));
        listContainer.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        dialogView.addView(listContainer, listParams);

        LinearLayout actions = new LinearLayout(context);
        actions.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        TextView cancelButton = new TextView(context);
        cancelButton.setText(android.R.string.cancel);
        cancelButton.setTextSize(16);
        SettingsUi.styleTextAction(cancelButton, false);
        int buttonHorizontalPadding = dpToPx(12);
        cancelButton.setPadding(buttonHorizontalPadding, dpToPx(8), buttonHorizontalPadding, dpToPx(6));
        actions.addView(cancelButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        dialogView.addView(actions, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .create();
        cancelButton.setOnClickListener(view -> dialog.dismiss());

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
        SettingsUi.styleDialog(dialog);
    }

    private boolean savePreset(SimPreset preset) {
        if (!preset.isValid()) {
            Logger.printException(() -> "Invalid SIM preset refused: "
                    + preset.country + " / " + preset.operatorName + " / "
                    + preset.mccMnc + " / " + preset.iso);
            app.morphe.extension.shared.Utils.showToastLong("Invalid SIM preset");
            return false;
        }

        countryIsoPreference.setText(preset.iso);
        mccMncPreference.setText(preset.mccMnc);
        operatorNamePreference.setText(preset.operatorName);
        refreshSummary(preset.iso, preset.mccMnc, preset.operatorName);

        Logger.printDebug(() -> "SIM preset selected: " + preset.country + " / "
                + preset.operatorName + " / " + preset.mccMnc + " / " + preset.iso);

        if (Settings.SIM_SPOOF.get()) {
            AbstractPreferenceFragment.showRestartDialog(getContext());
        } else {
            app.morphe.extension.shared.Utils.showToastShort("Region preset saved");
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

        if (visiblePresets.isEmpty()) {
            visiblePresets.add(null);
        }

        adapter.notifyDataSetChanged();
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getContext().getResources().getDisplayMetrics().density);
    }

    private GradientDrawable createDialogBackground() {
        return SettingsUi.borderedSurface(getContext(), 6, true);
    }

    private GradientDrawable createListBackground() {
        return SettingsUi.borderedSurface(getContext(), 4, false);
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
        PresetAdapter(Context context, List<SimPreset> presets) {
            super(context, android.R.layout.simple_list_item_2, android.R.id.text1, presets);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            TextView title = view.findViewById(android.R.id.text1);
            TextView summary = view.findViewById(android.R.id.text2);
            SimPreset preset = getItem(position);

            if (preset == null) {
                title.setText("No matching countries");
                summary.setText("");
                view.setEnabled(false);
            } else {
                title.setText(preset.country);
                summary.setText(preset.getSummary());
                view.setEnabled(true);
            }

            view.setBackgroundColor(getDialogBackgroundColor());
            title.setTextColor(getTitleTextColor());
            summary.setTextColor(getSummaryTextColor());
            return view;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItem(position) != null;
        }
    }
}
