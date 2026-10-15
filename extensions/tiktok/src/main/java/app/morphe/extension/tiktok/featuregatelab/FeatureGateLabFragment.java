/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.featuregatelab;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceFragment;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

@SuppressWarnings({"deprecation", "SetTextI18n"})
public final class FeatureGateLabFragment extends Fragment {
    private static final String[] VIEW_LABELS = {"Loaded", "All actionable", "Overrides"};
    private static final String[] FILTER_LABELS = {"All", "Boolean", "Enabled", "Disabled", "Unloaded"};
    private static final String[] SOURCE_LABELS = {
            "All", "App AB", "Config", "Player", "Live", "Media", "Activity"
    };
    private static final String[] SOURCE_MANAGERS = {
            null,
            FeatureGateLabStore.MANAGER_ABMOCK,
            FeatureGateLabStore.MANAGER_SETTINGS_MANAGER,
            FeatureGateLabStore.MANAGER_PLAYER_CONFIG,
            FeatureGateLabStore.MANAGER_LIVE,
            FeatureGateLabStore.MANAGER_VE_CONFIG,
            FeatureGateLabStore.MANAGER_PIA_ACTIVITY_CENTER
    };
    private static final long SEARCH_DELAY_MS = 160;
    private static final int REQUEST_EXPORT_LOADED = 0x6f10;
    private static final int REQUEST_IMPORT_LOADED = 0x6f11;
    private static final int MAX_COMPRESSED_IMPORT_BYTES = 32 * 1024 * 1024;
    private static final int MAX_JSON_IMPORT_BYTES = 64 * 1024 * 1024;
    private static final int FILTER_ALL = 0;
    private static final int FILTER_BOOLEAN = 1;
    private static final int FILTER_ENABLED = 2;
    private static final int FILTER_DISABLED = 3;
    private static final int FILTER_UNLOADED = 4;

    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final FeatureGateLabUi.SystemBackHandler systemBack = new FeatureGateLabUi.SystemBackHandler();
    private final List<FeatureGateCatalog.Entry> visible = new ArrayList<>();

    private FeatureGateCatalog.Snapshot snapshot;
    private GateAdapter adapter;
    private TextView count;
    private TextView loading;
    private TextView empty;
    private EditText search;
    private View clearSearch;
    private LinearLayout viewTabs;
    private final TextView[] viewTabLabels = new TextView[VIEW_LABELS.length];
    private final TextView[] sourceTabLabels = new TextView[SOURCE_LABELS.length];
    private final View[] sourceTabIndicators = new View[SOURCE_LABELS.length];
    private TextView filterButton;
    private Switch master;
    private ListView list;
    private boolean exitPromptScheduled;
    private String searchQuery = "";
    private int selectedView;
    private int selectedFilter;
    private int selectedSource;
    private int listPosition;
    private int listOffset;
    private boolean restoreListPosition;
    private int originalSoftInputMode;
    private boolean softInputModeChanged;

    private final Runnable delayedSearch = this::rebuild;

    public static void open(Activity activity) {
        int containerId = findFragmentContainer(activity);
        if (containerId == View.NO_ID) {
            Utils.showToastLong("Feature Gate Lab could not find the settings container");
            return;
        }
        FeatureGateLabSession.begin();
        activity.getFragmentManager()
                .beginTransaction()
                .replace(containerId, new FeatureGateLabFragment())
                .addToBackStack("feature_gate_lab")
                .commit();
    }

    @Override
    public View onCreateView(android.view.LayoutInflater inflater, ViewGroup container, Bundle state) {
        Context context = getActivity();
        FeatureGateLabUi.syncTheme(context);

        Activity activity = getActivity();
        if (activity != null && !softInputModeChanged) {
            originalSoftInputMode = activity.getWindow().getAttributes().softInputMode;
            activity.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
            softInputModeChanged = true;
        }

        FeatureGateLabUi.BackInterceptLayout root =
                new FeatureGateLabUi.BackInterceptLayout(context, this::handleSystemBack);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(SettingsUi.background());
        root.setFocusableInTouchMode(true);
        root.requestFocus();

        FrameLayout header = FeatureGateLabUi.header(
                context,
                "Feature Gate Lab",
                this::leaveLab,
                this::showOverflow
        );
        root.addView(header, FeatureGateLabUi.matchWrap());

        LinearLayout controls = new LinearLayout(context);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(
                FeatureGateLabUi.dp(context, 16),
                0,
                FeatureGateLabUi.dp(context, 16),
                FeatureGateLabUi.dp(context, 8)
        );
        root.addView(controls, FeatureGateLabUi.matchWrap());

        LinearLayout masterRow = new LinearLayout(context);
        masterRow.setOrientation(LinearLayout.HORIZONTAL);
        masterRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout masterText = new LinearLayout(context);
        masterText.setOrientation(LinearLayout.VERTICAL);
        masterText.addView(FeatureGateLabUi.body(context, "Enable overrides"), FeatureGateLabUi.matchWrap());
        masterText.addView(FeatureGateLabUi.label(context, "Applies saved rules at supported getters"), FeatureGateLabUi.matchWrap());
        masterRow.addView(masterText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        master = new Switch(context);
        master.setChecked(FeatureGateLabStore.masterEnabled());
        masterRow.addView(master, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                FeatureGateLabUi.dp(context, 48)
        ));
        controls.addView(masterRow, FeatureGateLabUi.matchWrap());

        TextView warning = FeatureGateLabUi.label(
                context,
                "Account warning: forced client values apply to every account in this app data and cannot bypass server controls."
        );
        warning.setTextColor(FeatureGateLabUi.warningColor(context));
        LinearLayout.LayoutParams warningParams = FeatureGateLabUi.matchWrap();
        warningParams.setMargins(0, 0, 0, FeatureGateLabUi.dp(context, 8));
        controls.addView(warning, warningParams);

        LinearLayout searchRow = new LinearLayout(context);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPadding(FeatureGateLabUi.dp(context, 8), 0, 0, 0);
        searchRow.setBackground(SettingsUi.borderedSurface(context, 6, false));
        search = new EditText(context);
        search.setSingleLine(true);
        search.setHint("Search words or key");
        search.setBackgroundColor(Color.TRANSPARENT);
        search.setTextColor(SettingsUi.textPrimary());
        search.setHintTextColor(SettingsUi.textSecondary());
        search.setText(searchQuery);
        search.setSelection(search.length());
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((view, actionId, event) -> {
            boolean submitted = actionId == EditorInfo.IME_ACTION_SEARCH
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_UP);
            if (!submitted) return false;
            searchHandler.removeCallbacks(delayedSearch);
            searchQuery = search.getText().toString();
            rebuild();
            FeatureGateLabUi.hideKeyboard(search);
            View rootView = getView();
            if (rootView != null) rootView.requestFocus();
            return true;
        });
        searchRow.addView(search, new LinearLayout.LayoutParams(0, FeatureGateLabUi.dp(context, 48), 1f));
        clearSearch = FeatureGateLabUi.clearSearchButton(context, () -> search.setText(""));
        clearSearch.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
        searchRow.addView(clearSearch, new LinearLayout.LayoutParams(
                FeatureGateLabUi.dp(context, 48),
                FeatureGateLabUi.dp(context, 48)
        ));
        LinearLayout.LayoutParams searchParams = FeatureGateLabUi.matchWrap();
        searchParams.setMargins(0, 0, 0, FeatureGateLabUi.dp(context, 10));
        controls.addView(searchRow, searchParams);

        HorizontalScrollView sourceScroller = new HorizontalScrollView(context);
        sourceScroller.setHorizontalScrollBarEnabled(false);
        sourceScroller.setFillViewport(false);
        LinearLayout sourceTabs = new LinearLayout(context);
        sourceTabs.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < SOURCE_LABELS.length; i++) {
            final int position = i;
            LinearLayout tabContainer = new LinearLayout(context);
            tabContainer.setOrientation(LinearLayout.VERTICAL);
            tabContainer.setGravity(Gravity.CENTER);
            tabContainer.setOnClickListener(view -> onSourceSelected(position));

            TextView tab = FeatureGateLabUi.text(
                    context,
                    SOURCE_LABELS[i],
                    14,
                    SettingsUi.textSecondary(),
                    Typeface.BOLD
            );
            tab.setGravity(Gravity.CENTER);
            tab.setMinWidth(FeatureGateLabUi.dp(context, 72));
            tab.setMinHeight(FeatureGateLabUi.dp(context, 36));
            tab.setPadding(
                    FeatureGateLabUi.dp(context, 14),
                    0,
                    FeatureGateLabUi.dp(context, 14),
                    0
            );
            View indicator = new View(context);
            sourceTabLabels[i] = tab;
            sourceTabIndicators[i] = indicator;
            tabContainer.addView(tab, FeatureGateLabUi.matchWrap());
            tabContainer.addView(indicator, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    FeatureGateLabUi.dp(context, 2)
            ));
            sourceTabs.addView(tabContainer, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    FeatureGateLabUi.dp(context, 38)
            ));
        }
        sourceScroller.addView(sourceTabs, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams sourceParams = FeatureGateLabUi.matchWrap();
        sourceParams.setMargins(0, 0, 0, FeatureGateLabUi.dp(context, 8));
        controls.addView(sourceScroller, sourceParams);

        viewTabs = new LinearLayout(context);
        viewTabs.setOrientation(LinearLayout.HORIZONTAL);
        viewTabs.setPadding(
                FeatureGateLabUi.dp(context, 2),
                FeatureGateLabUi.dp(context, 2),
                FeatureGateLabUi.dp(context, 2),
                FeatureGateLabUi.dp(context, 2)
        );
        viewTabs.setBackground(SettingsUi.borderedSurface(context, 6, false));
        for (int i = 0; i < VIEW_LABELS.length; i++) {
            final int position = i;
            TextView tab = FeatureGateLabUi.text(context, VIEW_LABELS[i], 14, SettingsUi.textSecondary(), Typeface.BOLD);
            tab.setGravity(Gravity.CENTER);
            tab.setMinHeight(FeatureGateLabUi.dp(context, 40));
            tab.setOnClickListener(view -> onViewSelected(position));
            viewTabLabels[i] = tab;
            viewTabs.addView(tab, new LinearLayout.LayoutParams(0, FeatureGateLabUi.dp(context, 40), 1f));
        }
        controls.addView(viewTabs, FeatureGateLabUi.matchWrap());

        LinearLayout resultRow = new LinearLayout(context);
        resultRow.setOrientation(LinearLayout.HORIZONTAL);
        resultRow.setGravity(Gravity.CENTER_VERTICAL);
        count = FeatureGateLabUi.label(context, "Loading gates...");
        resultRow.addView(count, new LinearLayout.LayoutParams(0, FeatureGateLabUi.dp(context, 44), 1f));
        filterButton = FeatureGateLabUi.text(context, "", 14, SettingsUi.textPrimary(), Typeface.BOLD);
        filterButton.setGravity(Gravity.CENTER);
        filterButton.setPadding(
                FeatureGateLabUi.dp(context, 12),
                0,
                FeatureGateLabUi.dp(context, 12),
                0
        );
        filterButton.setBackground(SettingsUi.borderedSurface(context, 6, false));
        filterButton.setOnClickListener(view -> showFilterPicker());
        resultRow.addView(filterButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                FeatureGateLabUi.dp(context, 36)
        ));
        controls.addView(resultRow, FeatureGateLabUi.matchWrap());

        loading = FeatureGateLabUi.label(context, "Loading local catalog and current TikTok cache...");
        controls.addView(loading, FeatureGateLabUi.matchWrap());

        FrameLayout listContainer = new FrameLayout(context);
        list = new ListView(context);
        list.setDividerHeight(FeatureGateLabUi.dp(context, 1));
        list.setDivider(new android.graphics.drawable.ColorDrawable(SettingsUi.divider()));
        adapter = new GateAdapter(context, visible);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> openDetail(visible.get(position)));
        listContainer.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        empty = FeatureGateLabUi.label(context, "No gates match this search and filter.");
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(
                FeatureGateLabUi.dp(context, 24),
                FeatureGateLabUi.dp(context, 24),
                FeatureGateLabUi.dp(context, 24),
                FeatureGateLabUi.dp(context, 24)
        );
        listContainer.addView(empty, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        list.setEmptyView(empty);
        root.addView(listContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        master.setOnCheckedChangeListener((button, checked) -> onMasterChanged(checked));
        search.addTextChangedListener(new SimpleTextWatcher(() -> {
            searchQuery = search.getText().toString();
            clearSearch.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
            searchHandler.removeCallbacks(delayedSearch);
            searchHandler.postDelayed(delayedSearch, SEARCH_DELAY_MS);
        }));

        updateControls();
        load(false);
        root.post(this::showMigrationNoticeIfNeeded);
        return root;
    }

    private void showMigrationNoticeIfNeeded() {
        Activity activity = getActivity();
        if (activity == null || activity.isFinishing() || !FeatureGateLabStore.consumeMigrationNotice()) {
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("Review saved overrides")
                .setMessage("Overrides saved for an older TikTok version were kept but disabled. Review their values before enabling them on TikTok 47.1.4.")
                .setPositiveButton("Review", null)
                .create();
        showStyled(dialog);
    }

    @Override
    public void onResume() {
        super.onResume();
        systemBack.register(getActivity(), this::handleSystemBack);
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (requestCode == REQUEST_EXPORT_LOADED) writeLoadedValuesFile(uri);
        if (requestCode == REQUEST_IMPORT_LOADED) readLoadedValuesFile(uri);
    }

    @Override
    public void onPause() {
        systemBack.unregister();
        Activity activity = getActivity();
        if (activity != null && activity.isFinishing() && FeatureGateLabSession.restartNeeded()) {
            FeatureGateLabSession.showAfterHostExit(activity);
        }
        super.onPause();
    }

    @Override
    public void onDestroyView() {
        searchHandler.removeCallbacks(delayedSearch);
        if (search != null) searchQuery = search.getText().toString();
        if (list != null && list.getChildCount() > 0) {
            listPosition = list.getFirstVisiblePosition();
            listOffset = list.getChildAt(0).getTop();
            restoreListPosition = true;
        }
        adapter = null;
        count = null;
        loading = null;
        empty = null;
        search = null;
        clearSearch = null;
        viewTabs = null;
        filterButton = null;
        master = null;
        list = null;
        for (int i = 0; i < viewTabLabels.length; i++) viewTabLabels[i] = null;
        for (int i = 0; i < sourceTabLabels.length; i++) {
            sourceTabLabels[i] = null;
            sourceTabIndicators[i] = null;
        }
        super.onDestroyView();
    }

    @Override
    public void onDestroy() {
        Activity activity = getActivity();
        if (activity != null && softInputModeChanged) {
            activity.getWindow().setSoftInputMode(originalSoftInputMode);
            softInputModeChanged = false;
        }
        super.onDestroy();
        if (activity == null || activity.isChangingConfigurations() || activity.isFinishing()) return;
        activity.getWindow().getDecorView().post(() -> {
            if (!exitPromptScheduled && FeatureGateLabSession.restartNeeded()) {
                exitPromptScheduled = true;
                FeatureGateLabSession.showRestartDialog(activity);
            }
        });
    }

    private void leaveLab() {
        FeatureGateLabUi.hideKeyboard(search);
        FragmentManager manager = getFragmentManager();
        if (manager != null) manager.popBackStack();
    }

    private void handleSystemBack() {
        if (search != null && search.hasFocus()) {
            FeatureGateLabUi.hideKeyboard(search);
            View root = getView();
            if (root != null) root.requestFocus();
            return;
        }
        leaveLab();
    }

    private void load(boolean refresh) {
        loading.setVisibility(View.VISIBLE);
        loading.setText(refresh ? "Refreshing current TikTok cache..." : "Loading local catalog and current TikTok cache...");
        FeatureGateCatalog.loadAsync(refresh, new FeatureGateCatalog.Callback() {
            @Override
            public void onLoaded(FeatureGateCatalog.Snapshot loaded) {
                if (!isAdded() || getView() == null) return;
                snapshot = loaded;
                if (loaded.catalogComplete) {
                    loading.setVisibility(View.GONE);
                } else {
                    loading.setVisibility(View.VISIBLE);
                    loading.setText("Loaded current values. Loading all known gates...");
                }
                empty.setText("No gates match this search and filter.");
                rebuild();
            }

            @Override
            public void onError(String message) {
                if (!isAdded() || getView() == null) return;
                loading.setText("Current cache unavailable: " + message);
                FeatureGateCatalog.Snapshot cached = FeatureGateCatalog.cachedSnapshot();
                if (cached != null) {
                    snapshot = cached;
                    rebuild();
                } else {
                    count.setText("Gate data unavailable");
                    empty.setText("No gate data is available. Refresh values from the menu to try again.");
                }
            }
        });
    }

    private void rebuild() {
        if (snapshot == null || adapter == null) return;
        String query = normalizeSearchText(searchQuery);
        Map<String, FeatureGateLabStore.Rule> rules = rulesByIdentity();
        Map<FeatureGateCatalog.Entry, Integer> searchRanks = new HashMap<>();

        visible.clear();
        for (FeatureGateCatalog.Entry entry : snapshot.entries) {
            if (!entry.userVisible()) continue;
            String sourceManager = SOURCE_MANAGERS[selectedSource];
            if (sourceManager != null && !sourceManager.equals(entry.manager)) continue;
            FeatureGateLabStore.Rule rule = rules.get(ruleIdentity(entry));
            if (selectedView == 0 && !entry.loaded) continue;
            if (selectedView == 2 && rule == null) continue;
            if (!matchesFilter(entry)) continue;
            if (!query.isEmpty()) {
                int rank = searchRank(entry, query);
                if (rank < 0) continue;
                searchRanks.put(entry, rank);
            }
            visible.add(entry);
        }

        if (selectedView == 2) {
            visible.sort((left, right) -> {
                FeatureGateLabStore.Rule leftRule = rules.get(ruleIdentity(left));
                FeatureGateLabStore.Rule rightRule = rules.get(ruleIdentity(right));
                return Long.compare(
                        rightRule == null ? 0 : rightRule.updatedAtMs,
                        leftRule == null ? 0 : leftRule.updatedAtMs
                );
            });
        } else if (!query.isEmpty()) {
            visible.sort((left, right) -> {
                int rank = Integer.compare(searchRanks.get(left), searchRanks.get(right));
                return rank != 0 ? rank : left.title.compareToIgnoreCase(right.title);
            });
        }

        count.setText(visible.size() + (visible.size() == 1 ? " result" : " results"));
        adapter.notifyDataSetChanged();
        if (restoreListPosition && list != null) {
            int position = listPosition;
            int offset = listOffset;
            restoreListPosition = false;
            list.post(() -> {
                if (list != null) list.setSelectionFromTop(position, offset);
            });
        }
    }

    private Map<String, FeatureGateLabStore.Rule> rulesByIdentity() {
        Map<String, FeatureGateLabStore.Rule> rules = new HashMap<>();
        for (FeatureGateLabStore.Rule rule : FeatureGateLabStore.rules()) {
            rules.put(rule.manager + "\n" + rule.key + "\n" + rule.type, rule);
        }
        return rules;
    }

    private static String ruleIdentity(FeatureGateCatalog.Entry entry) {
        return entry.manager + "\n" + entry.key + "\n" + entry.type;
    }

    private boolean matchesFilter(FeatureGateCatalog.Entry entry) {
        if (selectedFilter == FILTER_ALL) return true;
        if (selectedFilter == FILTER_UNLOADED) return !entry.loaded;
        if (!entry.loaded) return false;

        boolean isBoolean = "BOOLEAN".equalsIgnoreCase(entry.type)
                || "BOOLEAN".equalsIgnoreCase(entry.currentType);
        if (!isBoolean) return false;
        if (selectedFilter == FILTER_BOOLEAN) return true;
        if (selectedFilter == FILTER_ENABLED) return "true".equalsIgnoreCase(entry.currentValue);
        if (selectedFilter == FILTER_DISABLED) return "false".equalsIgnoreCase(entry.currentValue);
        return true;
    }

    private void onViewSelected(int position) {
        selectedView = position;
        if (selectedView == 0 && selectedFilter == FILTER_UNLOADED) selectedFilter = FILTER_ALL;
        if (selectedView != 0 && selectedFilter >= FILTER_BOOLEAN
                && selectedFilter <= FILTER_DISABLED) selectedFilter = FILTER_ALL;
        updateControls();
        rebuild();
    }

    private void onFilterSelected(int position) {
        selectedFilter = position;
        if (selectedFilter >= FILTER_BOOLEAN && selectedFilter <= FILTER_DISABLED) selectedView = 0;
        if (selectedFilter == FILTER_UNLOADED) selectedView = 1;
        updateControls();
        rebuild();
    }

    private void onSourceSelected(int position) {
        selectedSource = position;
        updateControls();
        rebuild();
    }

    private void updateControls() {
        for (int i = 0; i < viewTabLabels.length; i++) {
            TextView tab = viewTabLabels[i];
            if (tab == null) continue;
            boolean selected = i == selectedView;
            tab.setTextColor(selected ? Color.WHITE : SettingsUi.textSecondary());
            if (selected) {
                GradientDrawable background = new GradientDrawable();
                background.setColor(SettingsUi.ACCENT);
                background.setCornerRadius(FeatureGateLabUi.dp(tab.getContext(), 5));
                tab.setBackground(background);
            } else {
                tab.setBackgroundColor(Color.TRANSPARENT);
            }
        }
        for (int i = 0; i < sourceTabLabels.length; i++) {
            TextView tab = sourceTabLabels[i];
            View indicator = sourceTabIndicators[i];
            if (tab == null || indicator == null) continue;
            boolean selected = i == selectedSource;
            tab.setTextColor(selected ? SettingsUi.ACCENT : SettingsUi.textSecondary());
            tab.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            indicator.setBackgroundColor(selected ? SettingsUi.ACCENT : Color.TRANSPARENT);
        }
        if (filterButton != null) filterButton.setText("Filter: " + FILTER_LABELS[selectedFilter]);
    }

    private void showFilterPicker() {
        AlertDialog dialog = new AlertDialog.Builder(getActivity())
                .setTitle("Show gates")
                .setSingleChoiceItems(FILTER_LABELS, selectedFilter, (choiceDialog, which) -> {
                    onFilterSelected(which);
                    choiceDialog.dismiss();
                })
                .setNegativeButton("Cancel", null)
                .create();
        showStyled(dialog);
    }

    private void onMasterChanged(boolean checked) {
        if (checked && !FeatureGateLabStore.warningAcknowledged()) {
            master.setChecked(false);
            AlertDialog dialog = new AlertDialog.Builder(getActivity())
                    .setTitle("Enable Feature Gate Lab overrides?")
                    .setMessage("Client-side override values apply to all accounts in this app data. Some gates can affect account safety, compliance, login, region, or payment behavior. Use only keys you can test and roll back.")
                    .setPositiveButton("Enable", (ignored, which) -> {
                        FeatureGateLabStore.acknowledgeWarning();
                        master.setChecked(true);
                    })
                    .setNegativeButton("Cancel", null)
                    .create();
            showStyled(dialog);
            return;
        }
        if (FeatureGateLabStore.masterEnabled() != checked) {
            FeatureGateLabStore.setMasterEnabled(checked);
            rebuild();
        }
    }

    private void openDetail(FeatureGateCatalog.Entry entry) {
        FeatureGateLabUi.hideKeyboard(search);
        FeatureGateDetailFragment detail = FeatureGateDetailFragment.forEntry(entry.manager, entry.key, entry.type);
        getFragmentManager().beginTransaction()
                .replace(getId(), detail)
                .addToBackStack("feature_gate_detail")
                .commit();
    }

    private void showOverflow() {
        View anchor = getView() == null ? null : getView().findViewWithTag("feature_gate_menu");
        if (anchor == null) return;
        int popupTheme = SettingsUi.isDarkMode()
                ? android.R.style.Theme_Material
                : android.R.style.Theme_Material_Light;
        PopupMenu menu = new PopupMenu(
                new ContextThemeWrapper(getActivity(), popupTheme),
                anchor
        );
        menu.getMenu().add(0, 1, 0, "Refresh values");
        menu.getMenu().add(0, 2, 1, "Export loaded values");
        menu.getMenu().add(0, 3, 2, "Import loaded values");
        menu.getMenu().add(0, 4, 3, "Reset all overrides");
        menu.getMenu().add(0, 5, 4, "Reset all Lab data");
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1:
                    load(true);
                    return true;
                case 2:
                    exportLoadedValues();
                    return true;
                case 3:
                    chooseLoadedValuesFile();
                    return true;
                case 4:
                    confirmReset(false);
                    return true;
                case 5:
                    confirmReset(true);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void exportLoadedValues() {
        try {
            if (getActivity() == null || snapshot == null) return;
            String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/gzip")
                    .putExtra(Intent.EXTRA_TITLE,
                            "tiktok-47.1.4-loaded-feature-gates-" + timestamp + ".json.gz");
            startActivityForResult(intent, REQUEST_EXPORT_LOADED);
        } catch (Throwable throwable) {
            Utils.showToastLong("Could not open the export file picker");
        }
    }

    private void chooseLoadedValuesFile() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES,
                            new String[]{"application/gzip", "application/json", "application/octet-stream"});
            startActivityForResult(intent, REQUEST_IMPORT_LOADED);
        } catch (Throwable throwable) {
            Utils.showToastLong("Could not open the import file picker");
        }
    }

    private void writeLoadedValuesFile(Uri uri) {
        new Thread(() -> {
            try {
                Activity activity = getActivity();
                if (activity == null) return;
                ExportPayload payload = buildExportPayload();
                try (OutputStream output = activity.getContentResolver().openOutputStream(uri, "w")) {
                    if (output == null) throw new IllegalStateException("Document provider returned no output stream");
                    output.write(payload.gzipBytes);
                }
                postToast("Exported " + payload.count + " loaded values");
            } catch (Throwable throwable) {
                postToast("Loaded-value file export failed");
            }
        }, "MorpheGateFileExport").start();
    }

    private void readLoadedValuesFile(Uri uri) {
        new Thread(() -> {
            try {
                Activity activity = getActivity();
                if (activity == null) return;
                byte[] compressed;
                try (InputStream input = activity.getContentResolver().openInputStream(uri)) {
                    if (input == null) throw new IllegalStateException("Document provider returned no input stream");
                    compressed = readLimited(input, MAX_COMPRESSED_IMPORT_BYTES);
                }
                reviewLoadedImport(new JSONObject(readGzipJson(compressed)));
            } catch (Throwable throwable) {
                postToast("Loaded-value file is invalid or too large");
            }
        }, "MorpheGateFileImport").start();
    }

    private void reviewLoadedImport(JSONObject imported) throws Exception {
        Activity activity = getActivity();
        FeatureGateCatalog.Snapshot currentSnapshot = snapshot;
        if (activity == null || currentSnapshot == null) return;
        if (!"loaded_values".equals(imported.optString("payload_kind"))) {
            throw new IllegalArgumentException("Unsupported Feature Gate Lab export type");
        }
        if (!FeatureGateLabStore.TARGET_VERSION.equals(imported.optString("tiktok_version"))) {
            throw new IllegalArgumentException("Loaded values target a different TikTok version");
        }

        Map<String, FeatureGateLabStore.Rule> existingRules = rulesByIdentity();
        JSONArray sourceRules = imported.optJSONArray("rules");
        JSONArray candidates = new JSONArray();
        int same = 0;
        int unavailable = 0;
        if (sourceRules != null) {
            for (int i = 0; i < sourceRules.length(); i++) {
                JSONObject item = sourceRules.optJSONObject(i);
                if (item == null) continue;
                String manager = item.optString("manager");
                String key = item.optString("key");
                String type = item.optString("type", "").toUpperCase(Locale.ROOT);
                FeatureGateCatalog.Entry local = currentSnapshot.byIdentity.get(manager + "\n" + key);
                if (local == null) {
                    unavailable++;
                    continue;
                }

                String importedValue = item.optString("value", "");
                FeatureGateLabStore.Rule existingRule = existingRules.get(
                        manager + "\n" + key + "\n" + type);
                boolean alreadyRestored = existingRule != null
                        ? !existingRule.enabled && importedValue.equals(existingRule.value)
                        : local.loaded && importedValue.equals(local.currentValue);
                if (alreadyRestored) {
                    same++;
                    continue;
                }
                candidates.put(item);
            }
        }

        JSONObject profile = new JSONObject();
        profile.put("schema", 1);
        profile.put("target", "TikTok global");
        profile.put("tiktok_version", FeatureGateLabStore.TARGET_VERSION);
        profile.put("rules", candidates);
        FeatureGateLabStore.ImportReview review = FeatureGateLabStore.reviewProfile(
                profile.toString(), currentSnapshot.byIdentity);

        String message = review.accepted.size()
                + " missing, different, or overridden values can be imported.\n"
                + same + " values and saved rules already match this account.\n"
                + unavailable + " keys are unavailable in this catalog.\n"
                + review.rejected.size() + " values failed type or boundary validation.\n\n"
                + "Imported values remain disabled until you enable individual overrides.";
        activity.runOnUiThread(() -> {
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle("Review loaded values")
                    .setMessage(message)
                    .setPositiveButton("Import disabled", (ignored, which) ->
                            applyImportAsync(review, review.accepted.size()))
                    .setNegativeButton("Cancel", null)
                    .create();
            showStyled(dialog);
        });
    }

    private void applyImportAsync(FeatureGateLabStore.ImportReview review, int acceptedCount) {
        new Thread(() -> {
            try {
                FeatureGateLabStore.applyImport(review);
                new Handler(Looper.getMainLooper()).post(() -> {
                    rebuild();
                    Utils.showToastLong("Imported " + acceptedCount + " disabled values");
                });
            } catch (Throwable throwable) {
                postToast("Could not save imported values");
            }
        }, "MorpheGateImportApply").start();
    }

    private ExportPayload buildExportPayload() throws Exception {
        FeatureGateCatalog.Snapshot currentSnapshot = snapshot;
        if (currentSnapshot == null) throw new IllegalStateException("No loaded Feature Gate Lab snapshot");

        JSONArray rules = new JSONArray();
        for (FeatureGateCatalog.Entry entry : currentSnapshot.entries) {
            if (!entry.loaded || !entry.actionable) continue;
            String type = entry.type.toUpperCase(Locale.ROOT);
            if (!isPrimitiveType(type)) continue;
            String value = entry.currentValue == null ? "" : entry.currentValue;
            if (value.isEmpty() && !"STRING".equals(type)) continue;

            JSONObject item = new JSONObject();
            item.put("manager", entry.manager);
            item.put("key", entry.key);
            item.put("type", type);
            item.put("value", value);
            item.put("force", false);
            rules.put(item);
        }

        JSONObject root = new JSONObject();
        root.put("schema", 1);
        root.put("payload_kind", "loaded_values");
        root.put("target", "TikTok global");
        root.put("tiktok_version", FeatureGateLabStore.TARGET_VERSION);
        root.put("exported_at_ms", System.currentTimeMillis());
        root.put("entry_count", rules.length());
        root.put("rules", rules);
        JSONArray settingsManagerObservations =
                FeatureGateLabRuntime.settingsManagerObservationsJson();
        root.put("settings_manager_observation_count", settingsManagerObservations.length());
        root.put("settings_manager_observations", settingsManagerObservations);

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(root.toString().getBytes(StandardCharsets.UTF_8));
        }
        return new ExportPayload(bytes.toByteArray(), rules.length());
    }

    private static String readGzipJson(byte[] compressed) throws Exception {
        if (compressed.length < 2 || (compressed[0] & 0xff) != 0x1f || (compressed[1] & 0xff) != 0x8b) {
            throw new IllegalArgumentException("Expected a gzip-compressed Feature Gate Lab JSON file");
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return new String(readLimited(gzip, MAX_JSON_IMPORT_BYTES), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            total += read;
            if (total > maxBytes) throw new IllegalArgumentException("File exceeds the import size limit");
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private static boolean isPrimitiveType(String type) {
        return "BOOLEAN".equals(type) || "INT".equals(type) || "LONG".equals(type)
                || "FLOAT".equals(type) || "DOUBLE".equals(type) || "STRING".equals(type);
    }

    private void postToast(String text) {
        new Handler(Looper.getMainLooper()).post(() -> Utils.showToastLong(text));
    }

    private static final class ExportPayload {
        final byte[] gzipBytes;
        final int count;

        ExportPayload(byte[] gzipBytes, int count) {
            this.gzipBytes = gzipBytes;
            this.count = count;
        }
    }

    private void confirmReset(boolean allData) {
        if (!FeatureGateLabStore.masterEnabled()) {
            Utils.showToastLong("Enable overrides before changing saved Lab data");
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(getActivity())
                .setTitle(allData ? "Reset all Lab data?" : "Reset all overrides?")
                .setMessage(allData
                        ? "This removes all rules, the master state, and the warning acknowledgement."
                        : "This removes every saved override rule and selected value.")
                .setPositiveButton("Reset", (ignored, which) -> {
                    if (allData) {
                        FeatureGateLabStore.resetAllLabData();
                        master.setChecked(false);
                    } else {
                        FeatureGateLabStore.resetAllOverrides();
                    }
                    rebuild();
                })
                .setNegativeButton("Cancel", null)
                .create();
        showStyled(dialog);
    }

    private static void showStyled(AlertDialog dialog) {
        dialog.setOnShowListener(ignored -> FeatureGateLabUi.styleDialog(dialog));
        dialog.show();
    }

    private static int searchRank(FeatureGateCatalog.Entry entry, String query) {
        String key = normalizeSearchText(entry.key);
        String title = normalizeSearchText(entry.title);
        if (key.equals(query)) return 0;
        if (title.equals(query)) return 1;
        if (key.startsWith(query)) return 2;
        if (title.startsWith(query)) return 3;
        if (key.contains(query)) return 4;
        if (title.contains(query)) return 5;

        String[] queryTokens = query.split(" ");
        String[] candidateTokens = (key + " " + title).split(" ");
        int score = 10;
        for (String queryToken : queryTokens) {
            int bestTokenScore = Integer.MAX_VALUE;
            for (String candidateToken : candidateTokens) {
                bestTokenScore = Math.min(
                        bestTokenScore,
                        tokenMatchScore(queryToken, candidateToken)
                );
                if (bestTokenScore == 0) break;
            }
            if (bestTokenScore == Integer.MAX_VALUE) return -1;
            score += bestTokenScore;
        }
        return score;
    }

    private static String normalizeSearchText(String text) {
        if (text == null || text.isEmpty()) return "";
        String lower = text.toLowerCase(Locale.ROOT);
        StringBuilder normalized = new StringBuilder(lower.length());
        boolean previousWasSpace = true;
        for (int index = 0; index < lower.length(); index++) {
            char character = lower.charAt(index);
            if (Character.isLetterOrDigit(character)) {
                normalized.append(character);
                previousWasSpace = false;
            } else if (!previousWasSpace) {
                normalized.append(' ');
                previousWasSpace = true;
            }
        }
        int length = normalized.length();
        if (length > 0 && normalized.charAt(length - 1) == ' ') {
            normalized.setLength(length - 1);
        }
        return normalized.toString();
    }

    private static int tokenMatchScore(String queryToken, String candidateToken) {
        if (queryToken.equals(candidateToken)) return 0;
        if (candidateToken.startsWith(queryToken)) return 1;
        if (queryToken.length() >= 3 && queryToken.startsWith(candidateToken)) return 2;
        if (queryToken.length() >= 4 && candidateToken.contains(queryToken)) return 3;

        int allowedDistance;
        if (queryToken.length() >= 7) {
            allowedDistance = 2;
        } else if (queryToken.length() >= 4) {
            allowedDistance = 1;
        } else {
            return Integer.MAX_VALUE;
        }
        int distance = editDistanceWithin(queryToken, candidateToken, allowedDistance);
        return distance < 0 ? Integer.MAX_VALUE : 4 + distance;
    }

    private static int editDistanceWithin(String left, String right, int maximum) {
        if (Math.abs(left.length() - right.length()) > maximum) return -1;

        int[] previousPrevious = null;
        int[] previous = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) previous[column] = column;

        for (int row = 1; row <= left.length(); row++) {
            int[] current = new int[right.length() + 1];
            current[0] = row;
            int rowMinimum = current[0];
            for (int column = 1; column <= right.length(); column++) {
                int substitutionCost = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        previous[column - 1] + substitutionCost
                );
                if (previousPrevious != null
                        && row > 1
                        && column > 1
                        && left.charAt(row - 1) == right.charAt(column - 2)
                        && left.charAt(row - 2) == right.charAt(column - 1)) {
                    current[column] = Math.min(
                            current[column],
                            previousPrevious[column - 2] + 1
                    );
                }
                rowMinimum = Math.min(rowMinimum, current[column]);
            }
            if (rowMinimum > maximum) return -1;
            previousPrevious = previous;
            previous = current;
        }
        return previous[right.length()] <= maximum ? previous[right.length()] : -1;
    }

    private static int findFragmentContainer(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        return findFragmentContainer(activity.getFragmentManager(), content);
    }

    private static int findFragmentContainer(FragmentManager manager, View view) {
        if (view == null) return View.NO_ID;
        if (view.getId() != View.NO_ID && manager.findFragmentById(view.getId()) instanceof PreferenceFragment) {
            return view.getId();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                int found = findFragmentContainer(manager, group.getChildAt(i));
                if (found != View.NO_ID) return found;
            }
        }
        return View.NO_ID;
    }

    private final class GateAdapter extends BaseAdapter {
        private final Context context;
        private final List<FeatureGateCatalog.Entry> entries;

        GateAdapter(Context context, List<FeatureGateCatalog.Entry> entries) {
            this.context = context;
            this.entries = entries;
        }

        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return ruleIdentity(entries.get(position)).hashCode(); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            if (convertView == null) {
                LinearLayout row = new LinearLayout(context);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(FeatureGateLabUi.dp(context, 64));
                row.setPadding(
                        FeatureGateLabUi.dp(context, 16),
                        FeatureGateLabUi.dp(context, 7),
                        FeatureGateLabUi.dp(context, 16),
                        FeatureGateLabUi.dp(context, 7)
                );

                LinearLayout textColumn = new LinearLayout(context);
                textColumn.setOrientation(LinearLayout.VERTICAL);
                TextView title = FeatureGateLabUi.text(context, "", 15, SettingsUi.textPrimary(), Typeface.BOLD);
                title.setSingleLine(true);
                title.setEllipsize(TextUtils.TruncateAt.END);
                TextView key = FeatureGateLabUi.label(context, "");
                key.setSingleLine(true);
                key.setEllipsize(TextUtils.TruncateAt.MIDDLE);
                textColumn.addView(title, FeatureGateLabUi.matchWrap());
                textColumn.addView(key, FeatureGateLabUi.matchWrap());
                row.addView(textColumn, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                LinearLayout stateColumn = new LinearLayout(context);
                stateColumn.setOrientation(LinearLayout.VERTICAL);
                stateColumn.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
                TextView type = FeatureGateLabUi.text(context, "", 12, SettingsUi.textSecondary(), Typeface.BOLD);
                type.setGravity(Gravity.END);
                TextView state = FeatureGateLabUi.text(context, "", 12, SettingsUi.textSecondary(), Typeface.NORMAL);
                state.setGravity(Gravity.END);
                TextView value = FeatureGateLabUi.text(context, "", 12, SettingsUi.textPrimary(), Typeface.NORMAL);
                value.setGravity(Gravity.END);
                value.setSingleLine(true);
                value.setEllipsize(TextUtils.TruncateAt.END);
                stateColumn.addView(type, FeatureGateLabUi.matchWrap());
                stateColumn.addView(value, FeatureGateLabUi.matchWrap());
                stateColumn.addView(state, FeatureGateLabUi.matchWrap());
                row.addView(stateColumn, new LinearLayout.LayoutParams(
                        FeatureGateLabUi.dp(context, 116),
                        ViewGroup.LayoutParams.WRAP_CONTENT
                ));

                holder = new RowHolder(title, key, type, value, state);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (RowHolder) convertView.getTag();
            }

            FeatureGateCatalog.Entry entry = entries.get(position);
            FeatureGateLabStore.Rule rule = FeatureGateLabStore.rule(entry.manager, entry.key, entry.type);
            holder.title.setText(entry.title);
            holder.key.setText(entry.key);
            holder.type.setText(entry.shortSourceName() + " " + entry.type);
            String shownValue;
            if (rule != null && rule.enabled && FeatureGateLabStore.masterEnabled()) {
                shownValue = "Returns " + rule.value;
            } else if (entry.loaded) {
                shownValue = "Current " + entry.currentValue;
            } else if (rule != null) {
                shownValue = "Saved " + rule.value;
            } else {
                shownValue = "No current value";
            }
            holder.value.setText(shownValue);

            String state;
            int stateColor;
            if (rule != null && rule.enabled && FeatureGateLabRuntime.isTriggered(entry.manager, entry.key, entry.type)) {
                state = "Getter used";
                stateColor = SettingsUi.ACCENT;
            } else if (rule != null && rule.enabled) {
                state = "Waiting";
                stateColor = FeatureGateLabUi.warningColor(context);
            } else if (rule != null) {
                state = "Override off";
                stateColor = SettingsUi.textSecondary();
            } else {
                state = entry.loaded ? "Loaded" : "Unloaded";
                stateColor = entry.loaded ? SettingsUi.textSecondary() : SettingsUi.textDisabled();
            }
            holder.state.setText(state);
            holder.state.setTextColor(stateColor);
            convertView.setContentDescription(entry.title + ", " + entry.key + ", " + entry.type + ", " + shownValue + ", " + state);
            return convertView;
        }
    }

    private static final class RowHolder {
        final TextView title;
        final TextView key;
        final TextView type;
        final TextView value;
        final TextView state;

        RowHolder(TextView title, TextView key, TextView type, TextView value, TextView state) {
            this.title = title;
            this.key = key;
            this.type = type;
            this.value = value;
            this.state = state;
        }
    }

    private static final class SimpleTextWatcher implements TextWatcher {
        private final Runnable changed;

        SimpleTextWatcher(Runnable changed) {
            this.changed = changed;
        }

        @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
        @Override public void onTextChanged(CharSequence s, int start, int before, int count) { changed.run(); }
        @Override public void afterTextChanged(Editable s) { }
    }
}
