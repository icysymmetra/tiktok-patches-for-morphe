/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings.preference;

import app.morphe.extension.tiktok.settings.L10n;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.preference.Preference;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.morphe.extension.shared.settings.StringSetting;
import app.morphe.extension.tiktok.navigation.BottomNavigationTabOptions;
import app.morphe.extension.tiktok.navigation.NavigationTabOptions;
import app.morphe.extension.tiktok.navigation.TabOption;
import app.morphe.extension.tiktok.settings.Settings;

@SuppressWarnings("deprecation")
public class TabSelectionPreference extends Preference {
    private final StringSetting setting;
    private final boolean bottomTabs;
    private String value;
    private boolean valueSet;

    public TabSelectionPreference(Context context, StringSetting setting) {
        this(context, setting, false);
    }

    public TabSelectionPreference(Context context, StringSetting setting, boolean bottomTabs) {
        super(context);
        this.setting = setting;
        this.bottomTabs = bottomTabs;
        setTitle(bottomTabs ? "Bottom tabs to keep" : "Feed tabs to keep");
        setKey(setting.key);
        // In memory only. setValue writes, and writing the tidied form of what is already
        // stored, at the moment the row is built, made building the page a change to the
        // reader's settings. Nothing needs it written: every reader of this setting parses it,
        // and the tidied form is saved the moment the reader actually chooses something.
        this.value = serializeEnabledKeys(parseEnabledKeys(setting.savedValue()));
        this.valueSet = true;
        refreshSummary();
    }

    public String getValue() {
        return value;
    }

    public boolean setValue(String value) {
        String sanitizedValue = serializeEnabledKeys(parseEnabledKeys(value));
        boolean changed = !TextUtils.equals(this.value, sanitizedValue);
        if (changed || !valueSet) {
            this.value = sanitizedValue;
            valueSet = true;
            // Persist through Preference so the fragment sees the old running value before it
            // updates the Setting. Saving the Setting directly made this custom editor skip the
            // restart tracker even though it still showed a restart toast.
            persistString(sanitizedValue);
            refreshSummary();
            if (changed) {
                notifyDependencyChange(shouldDisableDependents());
                notifyChanged();
            }
        }
        return changed;
    }

    @Override
    protected void onClick() {
        showSelectionDialog();
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        app.morphe.extension.tiktok.Utils.setTitleAndSummaryColor(view);
    }

    private static final String ROW_TAG = "tab_option_";

    /** The rows and the set they show, so "Select every tab" can tick them where they are. */
    private LinearLayout optionsView;
    private Set<String> selectedKeys;

    private void refreshSummary() {
        Set<String> selected = parseEnabledKeys(value);
        List<OptionRow> observedOptions = getObservedOptions();
        if (nothingSeenYet()) {
            setSummary(bottomTabs ? "Open the feed once so Metra can see which bottom tabs TikTok loaded." : "Open the feed once so Metra can see which tabs TikTok loaded.");
            return;
        }

        int selectedObserved = 0;
        StringBuilder builder = new StringBuilder();
        for (OptionRow option : observedOptions) {
            if (!selected.contains(option.key)) {
                continue;
            }

            selectedObserved++;
            if (builder.length() > 0) {
                builder.append(", ");
            }
            builder.append(L10n.t(getContext(), option.label));
        }

        if (selectedObserved == observedOptions.size()) {
            setSummary("All loaded tabs");
            return;
        }

        if (builder.length() == 0) {
            setSummary(bottomTabs ? "Home" : "For You");
            return;
        }
        setSummary(builder.toString());
    }

    private void showSelectionDialog() {
        Context context = getContext();
        Set<String> selected = new LinkedHashSet<>(parseEnabledKeys(value));
        List<OptionRow> observedOptions = getObservedOptions();

        LinearLayout dialogView = new LinearLayout(context);
        dialogView.setOrientation(LinearLayout.VERTICAL);
        // No background of its own any more. The card is the window's, so it covers the
        // platform's button panel as well, which is where the actions now live.
        int padding = SettingsUi.dp(getContext(), 22);
        dialogView.setPadding(padding, padding, padding, SettingsUi.dp(getContext(), 8));

        TextView title = new TextView(context);
        title.setText(L10n.t(getContext(),
                bottomTabs ? "Bottom tabs to keep" : "Feed tabs to keep"));
        title.setTextColor(getTitleTextColor());
        title.setTextSize(SettingsUi.TEXT_HEADLINE);
        title.setTypeface(title.getTypeface(), Typeface.BOLD);
        SettingsUi.markDialogHeading(title);
        dialogView.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView helper = new TextView(context);
        helper.setText(bottomTabs
                // One literal each, and written out where the call is. The table is keyed
                // on the whole sentence, and the gate that pairs a key with its translation
                // reads what is inside the call rather than what the compiler joins together.
                ? L10n.t(getContext(), "Only bottom tabs TikTok has loaded on this device are shown here. This doesn't force unavailable tabs to appear.")
                : L10n.t(getContext(), "Only tabs TikTok has loaded on this device are shown here. This doesn't force unavailable tabs to appear."));
        helper.setTextColor(getSummaryTextColor());
        LinearLayout.LayoutParams helperParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        helperParams.setMargins(0, SettingsUi.dp(getContext(), 14), 0, SettingsUi.dp(getContext(), 10));
        dialogView.addView(helper, helperParams);

        LinearLayout optionsContainer = new LinearLayout(context);
        optionsContainer.setOrientation(LinearLayout.VERTICAL);
        optionsContainer.setBackground(createListBackground());
        int optionInset = Math.max(1, SettingsUi.dp(getContext(), 1));
        optionsContainer.setPadding(optionInset, optionInset, optionInset, optionInset);

        boolean empty = nothingSeenYet();
        if (empty) {
            TextView emptyState = new TextView(context);
            emptyState.setText(L10n.t(context, bottomTabs
                    ? "Open the feed once so Metra can see which bottom tabs TikTok loaded."
                    : "Open the feed once so Metra can see which tabs TikTok loaded."));
            emptyState.setTextColor(SettingsUi.textSecondary());
            emptyState.setTextSize(SettingsUi.TEXT_BODY_SMALL);
            emptyState.setGravity(Gravity.CENTER);
            int emptyPad = SettingsUi.dp(context, 24);
            emptyState.setPadding(emptyPad, emptyPad, emptyPad, emptyPad);
            optionsContainer.addView(emptyState);
        }
        for (OptionRow option : observedOptions) {
            optionsContainer.addView(createOptionRow(context, selected, option));
        }
        optionsView = optionsContainer;
        selectedKeys = selected;

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.addView(optionsContainer, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                SettingsUi.dialogListHeight(context, 380)
        );
        scrollParams.setMargins(0, 0, 0, SettingsUi.dp(getContext(), 16));
        dialogView.addView(scrollView, scrollParams);

        // The actions used to be the last row of this column, under a list that is never
        // shorter than 220dp. At a large font scale the title, the two-sentence helper and the
        // list came to more than the screen, and the platform's custom panel clips rather than
        // scrolls, so Save was the thing that got cut off and there was no way to reach it.
        // The platform keeps its own button panel on screen and shrinks the custom panel
        // instead, which is where the creator and share editors already put theirs.
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .setPositiveButton(L10n.t(context, "Save"), null)
                .setNegativeButton(L10n.t(context, "Cancel"), null)
                .setNeutralButton(L10n.t(context, "Select every tab"), null)
                .create();

        dialog.show();
        SettingsUi.styleFramedDialog(dialog);

        // Wired after show, because a button handed to the builder dismisses the dialog on its
        // own. Ticking every row is a way of filling the list in rather than a second Save, and
        // it used to save and dismiss: a reader who pressed it to see what it did lost the
        // selection they came in with, with no undo.
        View showAllButton = dialog.getButton(DialogInterface.BUTTON_NEUTRAL);
        if (showAllButton != null) {
            if (empty) {
                showAllButton.setEnabled(false);
            }
            showAllButton.setOnClickListener(view -> {
                selected.clear();
                for (OptionRow option : observedOptions) {
                    selected.add(option.key);
                }
                refreshRowChecks();
            });
        }
        View saveButton = dialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (saveButton != null) {
            saveButton.setOnClickListener(view -> {
                setValue(serializeEnabledKeys(selected));
                dialog.dismiss();
            });
        }
    }

    private List<OptionRow> getObservedOptions() {
        LinkedHashSet<OptionRow> rows = new LinkedHashSet<>();
        if (bottomTabs) {
            Set<String> observed = BottomNavigationTabOptions.parseObservedKeys(Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.savedValue());
            observed.add(BottomNavigationTabOptions.HOME);
            observed.add(BottomNavigationTabOptions.PROFILE);
            for (TabOption option : BottomNavigationTabOptions.optionsForKeys(observed)) {
                rows.add(new OptionRow(option.key, option.label));
            }
        } else {
            Set<String> observed = NavigationTabOptions.parseObservedKeys(Settings.FEED_NAVIGATION_OBSERVED_TABS.savedValue());
            observed.add(NavigationTabOptions.HOT);
            for (TabOption option : NavigationTabOptions.optionsForKeys(observed)) {
                rows.add(new OptionRow(option.key, option.label));
            }
        }
        return new java.util.ArrayList<>(rows);
    }

    /**
     * True until TikTok has been seen loading its tabs.
     *
     * <p>Read from the observed setting, not the row count. The setting starts as the one tab
     * every install has (Home, or For You), and the observer writes the whole bar it saw, Home
     * and Profile included, so a stored value with nothing beyond that first tab is one TikTok
     * has not been watched loading yet, and a bar seen to hold only Home and Profile is not.
     * The bottom list used to be held to one row, which its two always-present rows never met,
     * so its "open the feed once" state could not show and the row said All loaded tabs before
     * anything had loaded.
     */
    private boolean nothingSeenYet() {
        Set<String> observed = new LinkedHashSet<>(bottomTabs
                ? BottomNavigationTabOptions.parseObservedKeys(Settings.BOTTOM_NAVIGATION_OBSERVED_TABS.savedValue())
                : NavigationTabOptions.parseObservedKeys(Settings.FEED_NAVIGATION_OBSERVED_TABS.savedValue()));
        observed.remove(bottomTabs ? BottomNavigationTabOptions.HOME : NavigationTabOptions.HOT);
        return observed.isEmpty();
    }

    /** Puts every row's tick in step with the selection, without rebuilding the dialog. */
    private void refreshRowChecks() {
        if (optionsView == null || selectedKeys == null) return;
        // Walked rather than indexed: each row is a wrapper holding the row itself and the
        // divider under it, so the box is a grandchild.
        refreshRowChecks(optionsView);
    }

    private void refreshRowChecks(View view) {
        Object tag = view.getTag();
        if (view instanceof CheckBox && tag instanceof String
                && ((String) tag).startsWith(ROW_TAG)) {
            ((CheckBox) view).setChecked(
                    selectedKeys.contains(((String) tag).substring(ROW_TAG.length())));
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            refreshRowChecks(group.getChildAt(index));
        }
    }

    private View createOptionRow(Context context, Set<String> selected, OptionRow option) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(SettingsUi.listRow(context, getDialogBackgroundColor()));
        row.setPadding(SettingsUi.dp(getContext(), 10), SettingsUi.dp(getContext(), 10), SettingsUi.dp(getContext(), 10), SettingsUi.dp(getContext(), 10));
        // A clickable view is only focusable by default from API 26; below that the d-pad
        // would skip every row and the focus wash above would never show.
        row.setFocusable(true);

        CheckBox checkBox = new CheckBox(context);
        // Tagged so "Select every tab" can tick them where they are, rather than closing the
        // dialog and saving to show what it did.
        checkBox.setTag(ROW_TAG + option.key);
        checkBox.setChecked(selected.contains(option.key));
        checkBox.setEnabled(!isRequiredOption(option.key));
        checkBox.setClickable(false);
        SettingsUi.styleCheckBox(checkBox);
        row.addView(checkBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout textContainer = new LinearLayout(context);
        textContainer.setOrientation(LinearLayout.VERTICAL);

        TextView label = new TextView(context);
        label.setText(L10n.t(getContext(), option.label));
        label.setTextColor(getTitleTextColor());
        label.setTextSize(SettingsUi.TEXT_TITLE);
        textContainer.addView(label);

        if (isRequiredOption(option.key)) {
            TextView summary = new TextView(context);
            summary.setText(L10n.t(getContext(), "Required"));
            summary.setTextColor(getSummaryTextColor());
            summary.setTextSize(SettingsUi.TEXT_BODY_SMALL);
            textContainer.addView(summary);
        }

        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1
        );
        row.addView(textContainer, textParams);

        // The box is not clickable and the row does the toggling, so without this a screen
        // reader read the tab name and "double tap to activate" with no on or off in it, and
        // said nothing at all after the tap.
        row.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                    View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.setClassName(CheckBox.class.getName());
                info.setCheckable(!isRequiredOption(option.key));
                info.setChecked(checkBox.isChecked());
                info.setEnabled(!isRequiredOption(option.key));
            }
        });

        row.setOnClickListener(view -> {
            if (isRequiredOption(option.key)) {
                return;
            }

            if (selected.contains(option.key)) {
                selected.remove(option.key);
                checkBox.setChecked(false);
            } else {
                selected.add(option.key);
                checkBox.setChecked(true);
            }
            // What was just done, rather than leaving the reader to go back and check.
            row.sendAccessibilityEvent(
                    android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED);
        });

        View divider = new View(context);
        divider.setBackgroundColor(getDialogDividerColor());

        LinearLayout wrapper = new LinearLayout(context);
        wrapper.setOrientation(LinearLayout.VERTICAL);
        wrapper.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        wrapper.addView(divider, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(1, SettingsUi.dp(getContext(), 1))
        ));
        return wrapper;
    }

    private Set<String> parseEnabledKeys(String value) {
        return bottomTabs
                ? BottomNavigationTabOptions.parseEnabledKeys(value)
                : NavigationTabOptions.parseEnabledKeys(value);
    }

    private String serializeEnabledKeys(Set<String> selected) {
        return bottomTabs
                ? BottomNavigationTabOptions.serializeEnabledKeys(selected)
                : NavigationTabOptions.serializeEnabledKeys(selected);
    }

    private boolean isRequiredOption(String key) {
        return bottomTabs
                ? BottomNavigationTabOptions.isRequiredKey(key)
                : NavigationTabOptions.HOT.equals(key);
    }

    private static final class OptionRow {
        final String key;
        final String label;

        OptionRow(String key, String label) {
            this.key = key;
            this.label = label;
        }
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

    @Override
    public void setTitle(CharSequence title) {
        super.setTitle(L10n.t(getContext(), title));
    }

    /**
     * The state line, "Home" or "All loaded tabs", with the restart note after it. Both tab
     * settings are read at startup, and the row said nothing about that while every switch did.
     */
    @Override
    public void setSummary(CharSequence summary) {
        String text = summary == null ? null : summary.toString();
        super.setSummary(TogglePreference.withRestartNote(getContext(), text, setting));
    }
}
