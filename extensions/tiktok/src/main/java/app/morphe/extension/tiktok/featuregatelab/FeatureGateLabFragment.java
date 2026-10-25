/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.featuregatelab;

import app.morphe.extension.tiktok.settings.L10n;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Fragment;
import android.app.FragmentManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;

import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceFragment;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
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
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.SettingsJson;
import app.morphe.extension.tiktok.settings.SettingsOperationJournal;
import app.morphe.extension.tiktok.settings.preference.DocumentOperation;
import app.morphe.extension.tiktok.settings.preference.SettingsActionBanner;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.preference.SettingsMenuPreference;

@SuppressWarnings({"deprecation", "SetTextI18n"})
public final class FeatureGateLabFragment extends Fragment {
    private static final String[] VIEW_LABELS = {"Seen", "All", "Overridden"};
    private static final String[] FILTER_LABELS = {"All", "On or off", "Enabled", "Disabled", "Not seen"};

    /**
     * The five filter choices in the reader's language.
     *
     * <p>The button and the dialog it opens show the same five words, so they are translated
     * from one place. Translating one without the other would read worse than leaving both.
     */
    private static String[] filterLabels(android.content.Context context) {
        String[] translated = new String[FILTER_LABELS.length];
        for (int index = 0; index < FILTER_LABELS.length; index++) {
            translated[index] = L10n.t(context, FILTER_LABELS[index]);
        }
        return translated;
    }
    private static final String[] SOURCE_LABELS = {
            "All", "App AB", "Config", "Player", "LIVE", "Media", "Activity"
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
    private static final java.util.concurrent.atomic.AtomicBoolean CHANGING = new java.util.concurrent.atomic.AtomicBoolean();
    /**
     * The Lab's loaded-value import or export, null when none is out. A file app can keep a read
     * or write waiting as long as it likes, and the one file thread these used to share queued
     * every later import and export behind a stalled one, with nothing on screen, to run whenever
     * the stall ended. Main thread only.
     */
    private static DocumentOperation fileOperation;
    private static boolean fileOperationExports;
    /** Whether the stop has been offered for the operation's stall, so it's offered once. */
    private static boolean stopOffered;
    /** The Lab on screen, for an import or export that outlives the Lab it started from. */
    private static volatile java.lang.ref.WeakReference<FeatureGateLabFragment> shown =
            new java.lang.ref.WeakReference<>(null);
    private static final ExecutorService SEARCH_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MorpheGateSearch");
        thread.setDaemon(true);
        return thread;
    });
    private static volatile String lastSearchThreadForTests;
    private static volatile Runnable searchWorkHookForTests;
    private static final int REQUEST_EXPORT_LOADED = 0x6f10;
    private static final int REQUEST_IMPORT_LOADED = 0x6f11;
    /*
     * A loaded-values export holds every value TikTok loaded (14,747 on 47.4.3, 5.7 MB of JSON
     * and 177,709 values counted the parser's way) plus up to 2,048 settings-manager
     * observations. The import turns only the values that differ into rules, and the rule store
     * refuses more than it keeps, so these bounds are for the file alone. Sized at the Lab's own
     * rule cap, they refused every real export.
     */
    private static final int MAX_JSON_IMPORT_BYTES = 16 * 1024 * 1024;
    private static final SettingsJson.Limits IMPORT_JSON_LIMITS = new SettingsJson.Limits(
            24, 1 << 20, 64 * 1024, 1 << 16, MAX_JSON_IMPORT_BYTES);
    private static final int FILTER_ALL = 0;
    private static final int FILTER_BOOLEAN = 1;
    private static final int FILTER_ENABLED = 2;
    private static final int FILTER_DISABLED = 3;
    private static final int FILTER_UNLOADED = 4;

    private final Handler searchHandler = new Handler(Looper.getMainLooper());
    private final AtomicInteger rebuildGeneration = new AtomicInteger();
    private final app.morphe.extension.tiktok.settings.SystemBackHandler systemBack =
            new app.morphe.extension.tiktok.settings.SystemBackHandler("FeatureGateLabBackCallback");
    private final List<FeatureGateCatalog.Entry> visible = new ArrayList<>();
    /**
     * The gates a long press has gathered, by identity, in the order they were chosen.
     *
     * <p>Empty means the list is behaving as it always did: a tap opens the gate. Held by
     * identity rather than by index because the search and the filters rebuild {@link #visible}
     * underneath a selection.
     */
    private final java.util.LinkedHashMap<String, FeatureGateCatalog.Entry> selection =
            new java.util.LinkedHashMap<>();

    /** Written on the main thread, and read by the worker that reviews an import against it. */
    private volatile FeatureGateCatalog.Snapshot snapshot;
    private GateAdapter adapter;
    private TextView count;
    private TextView empty;
    private TextView emptyAction;
    private EditText search;
    private View clearSearch;
    private LinearLayout viewTabs;
    private final TextView[] viewTabLabels = new TextView[VIEW_LABELS.length];
    private final View[] viewTabContainers = new View[VIEW_LABELS.length];
    private final View[] viewTabIndicators = new View[VIEW_LABELS.length];
    private final View[] sourceTabContainers = new View[SOURCE_LABELS.length];
    private final TextView[] sourceTabLabels = new TextView[SOURCE_LABELS.length];
    private final View[] sourceTabIndicators = new View[SOURCE_LABELS.length];
    private TextView filterButton;
    private Switch master;
    private ListView list;
    private LinearLayout selectionBar;
    private TextView selectionCount;
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
            Utils.showToastLong(L10n.t(Utils.getContext(),
                    "The Lab couldn't open. Go back and open it again."));
            return;
        }
        FeatureGateLabSession.begin();
        activity.getFragmentManager()
                .beginTransaction()
                .replace(containerId, new FeatureGateLabFragment())
                .addToBackStack("feature_gate_lab")
                .commit();
    }

    static void awaitFileIoForTests() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (DocumentOperation.busy(DocumentOperation.Kind.LAB_FILE)) {
            if (System.nanoTime() > deadline) throw new TimeoutException("The Lab's file worker is still out");
            Thread.sleep(5);
        }
    }

    static void awaitSearchForTests() throws Exception {
        SEARCH_EXECUTOR.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    static String lastSearchThreadForTests() {
        return lastSearchThreadForTests;
    }

    static void setSearchWorkHookForTests(Runnable hook) {
        searchWorkHookForTests = hook;
    }

    static void resetForTests() {
        CHANGING.set(false);
        fileOperation = null;
        stopOffered = false;
        lastSearchThreadForTests = null;
        searchWorkHookForTests = null;
    }

    @Override
    public View onCreateView(android.view.LayoutInflater inflater, ViewGroup container, Bundle state) {
        shown = new java.lang.ref.WeakReference<>(this);
        Context context = getActivity();
        SettingsOperationJournal.initialize(context == null ? null : context.getApplicationContext());
        SettingsOperationJournal.showRecoveryNotice(context);
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
                L10n.t(context, "Feature Gate Lab"),
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

        master = new Switch(context);
        master.setChecked(FeatureGateLabStore.masterEnabled());
        SettingsUi.styleSwitch(master);
        // One row, one screen-reader stop: the row is the switch. It used to be two stops that
        // both read "Apply overrides", and only the 44dp switch answered a tap.
        LinearLayout masterRow = FeatureGateLabUi.switchRow(context,
                L10n.t(context, "Apply overrides"), null, master);
        masterRow.setPadding(FeatureGateLabUi.dp(context, 16), FeatureGateLabUi.dp(context, 4),
                FeatureGateLabUi.dp(context, 16), FeatureGateLabUi.dp(context, 4));
        LinearLayout masterControls = new LinearLayout(context);
        masterControls.setGravity(Gravity.CENTER_VERTICAL);
        masterControls.addView(masterRow, new LinearLayout.LayoutParams(0, -2, 1f));
        TextView help = FeatureGateLabUi.text(context, SettingsUi.ATTENTION_GLYPH, 20,
                SettingsUi.attentionColor(), Typeface.NORMAL);
        SettingsUi.styleTextAction(help, false);
        help.setTextColor(SettingsUi.attentionColor());
        help.setTag("feature_gate_help");
        help.setContentDescription(L10n.t(context, "About overrides"));
        help.setOnClickListener(view -> {
            if (getActivity() == null) return;
            AlertDialog notice = new AlertDialog.Builder(getActivity())
                    .setTitle(L10n.t(context, "About overrides"))
                    .setMessage(L10n.t(context, "Replace values when TikTok asks for them") + "\n\n"
                            + L10n.t(context, "A forced value applies to this copy of TikTok whichever account is signed in. It can't get past a check the server makes."))
                    .setPositiveButton(L10n.t(context, "Close"), null).create();
            showStyled(notice);
        });
        masterControls.addView(help, new LinearLayout.LayoutParams(FeatureGateLabUi.dp(context, 48), -2));
        LinearLayout.LayoutParams masterParams = FeatureGateLabUi.matchWrap();
        masterParams.bottomMargin = FeatureGateLabUi.dp(context, 8);
        controls.addView(masterControls, masterParams);

        LinearLayout searchRow = new LinearLayout(context);
        searchRow.setOrientation(LinearLayout.HORIZONTAL);
        searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.setPaddingRelative(FeatureGateLabUi.dp(context, 8), 0, 0, 0);
        searchRow.setTag("feature_gate_search_row");
        searchRow.setBackground(SettingsUi.focusableSurface(context, SettingsUi.RADIUS_SETTINGS_FIELD, false));
        // The focus lands on the field inside, never on the row, and a group only carries its
        // children's states when told to. Without this the accent border above never showed.
        searchRow.setAddStatesFromChildren(true);
        search = new EditText(context);
        search.setSingleLine(true);
        search.setTextSize(SettingsUi.TEXT_TITLE);
        // No content description on a search box. On an editable view it replaces what was
        // typed in the announcement, so "cats" came back as the label. The hint names it.
        search.setHint(L10n.t(context, "Search by name or gate key"));
        android.graphics.drawable.Drawable searchIcon = SettingsMenuPreference.lineIconDrawable(
                context, SettingsMenuPreference.Icon.SEARCH);
        int searchIconSize = FeatureGateLabUi.dp(context, 24);
        searchIcon.setBounds(0, 0, searchIconSize, searchIconSize);
        search.setCompoundDrawablesRelative(searchIcon, null, null, null);
        search.setCompoundDrawablePadding(FeatureGateLabUi.dp(context, 12));
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
        // A fixed 48dp box clipped 32sp glyphs at 2x text. CommentSearch documents the
        // same trap and answers it the same way: 48dp is the floor, not the height.
        search.setMinimumHeight(FeatureGateLabUi.dp(context, 48));
        searchRow.addView(search, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        clearSearch = FeatureGateLabUi.clearSearchButton(context, () -> search.setText(""));
        clearSearch.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
        searchRow.addView(clearSearch, new LinearLayout.LayoutParams(
                FeatureGateLabUi.dp(context, 48),
                FeatureGateLabUi.dp(context, 48)
        ));
        LinearLayout.LayoutParams searchParams = FeatureGateLabUi.matchWrap();
        searchParams.setMargins(0, 0, 0, FeatureGateLabUi.dp(context, 8));
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
            tabContainer.setFocusable(true);
            tabContainer.setTag("feature_gate_source_" + i);
            tabContainer.setContentDescription(L10n.t(context, SOURCE_LABELS[i]));
            // The whole tab is the target, so the press and the focus belong on the container
            // rather than on the label inside it. It had neither.
            FeatureGateLabUi.styleTab(tabContainer, false);
            SettingsUi.markAsButton(tabContainer);
            tabContainer.setOnClickListener(view -> onSourceSelected(position));

            TextView tab = FeatureGateLabUi.text(
                    context,
                    L10n.t(context, SOURCE_LABELS[i]),
                    14,
                    SettingsUi.textSecondary(),
                    Typeface.BOLD
            );
            tab.setGravity(Gravity.CENTER);
            tab.setSingleLine(true);
            tab.setMinWidth(FeatureGateLabUi.dp(context, 72));
            tab.setMinHeight(FeatureGateLabUi.dp(context, 48));
            tab.setPadding(
                    FeatureGateLabUi.dp(context, 14),
                    0,
                    FeatureGateLabUi.dp(context, 14),
                    0
            );
            View indicator = new View(context);
            indicator.setVisibility(View.GONE);
            sourceTabContainers[i] = tabContainer;
            sourceTabLabels[i] = tab;
            sourceTabIndicators[i] = indicator;
            tabContainer.addView(tab, FeatureGateLabUi.matchWrap());
            tabContainer.addView(indicator, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    FeatureGateLabUi.dp(context, 2)
            ));
            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            if (i + 1 < SOURCE_LABELS.length) tabParams.setMarginEnd(FeatureGateLabUi.dp(context, 6));
            sourceTabs.addView(tabContainer, tabParams);
        }
        sourceScroller.addView(sourceTabs, new HorizontalScrollView.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        LinearLayout.LayoutParams sourceParams = FeatureGateLabUi.matchWrap();
        sourceParams.setMargins(0, 0, 0, FeatureGateLabUi.dp(context, 6));
        controls.addView(sourceScroller, sourceParams);

        viewTabs = new LinearLayout(context);
        viewTabs.setOrientation(LinearLayout.HORIZONTAL);
        for (int i = 0; i < VIEW_LABELS.length; i++) {
            final int position = i;
            LinearLayout tabContainer = new LinearLayout(context);
            tabContainer.setOrientation(LinearLayout.VERTICAL);
            tabContainer.setGravity(Gravity.CENTER);
            tabContainer.setFocusable(true);
            tabContainer.setTag("feature_gate_view_" + i);
            tabContainer.setContentDescription(L10n.t(context, VIEW_LABELS[i]));
            FeatureGateLabUi.styleTab(tabContainer, false);
            SettingsUi.markAsButton(tabContainer);
            tabContainer.setOnClickListener(view -> onViewSelected(position));
            TextView tab = FeatureGateLabUi.text(context, L10n.t(context, VIEW_LABELS[i]), 14,
                    SettingsUi.textSecondary(), Typeface.BOLD);
            tab.setGravity(Gravity.CENTER);
            tab.setSingleLine(true);
            tab.setMinHeight(FeatureGateLabUi.dp(context, 48));
            tab.setMinWidth(FeatureGateLabUi.dp(context, 72));
            tab.setPadding(
                    FeatureGateLabUi.dp(context, 14), 0,
                    FeatureGateLabUi.dp(context, 14), 0);
            View indicator = new View(context);
            indicator.setVisibility(View.GONE);
            viewTabLabels[i] = tab;
            viewTabContainers[i] = tabContainer;
            viewTabIndicators[i] = indicator;
            tabContainer.addView(tab, FeatureGateLabUi.matchWrap());
            tabContainer.addView(indicator, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    FeatureGateLabUi.dp(context, 2)));
            LinearLayout.LayoutParams tabParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i + 1 < VIEW_LABELS.length) tabParams.setMarginEnd(FeatureGateLabUi.dp(context, 6));
            viewTabs.addView(tabContainer, tabParams);
        }
        HorizontalScrollView viewScroller = new HorizontalScrollView(context);
        viewScroller.setFillViewport(false);
        viewScroller.setHorizontalScrollBarEnabled(false);
        viewScroller.addView(viewTabs, new HorizontalScrollView.LayoutParams(-2, -2));
        controls.addView(viewScroller, FeatureGateLabUi.matchWrap());

        LinearLayout resultRow = new LinearLayout(context);
        resultRow.setOrientation(LinearLayout.HORIZONTAL);
        resultRow.setGravity(Gravity.CENTER_VERTICAL);
        count = SettingsUi.resultCount(context, "feature_gate_result_count");
        count.setText(L10n.t(context, "Loading gates…"));
        count.setGravity(Gravity.CENTER_VERTICAL);
        count.setMinHeight(FeatureGateLabUi.dp(context, 48));
        resultRow.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        filterButton = FeatureGateLabUi.text(context, "", 14, SettingsUi.textPrimary(), Typeface.BOLD);
        filterButton.setTag("feature_gate_filter");
        SettingsUi.styleTextAction(filterButton, false);
        filterButton.setTypeface(filterButton.getTypeface(), Typeface.BOLD);
        filterButton.setGravity(Gravity.CENTER);
        filterButton.setPadding(
                FeatureGateLabUi.dp(context, 12),
                0,
                FeatureGateLabUi.dp(context, 12),
                0
        );
        filterButton.setBackground(SettingsUi.focusableSurface(context, SettingsUi.RADIUS_PILL, false));
        filterButton.setFocusable(true);
        filterButton.setOnClickListener(view -> showFilterPicker());
        resultRow.addView(filterButton, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        controls.addView(resultRow, FeatureGateLabUi.matchWrap());

        FrameLayout listContainer = new FrameLayout(context);
        list = new ListView(context);
        SettingsUi.styleScrollableList(list);
        list.setDivider(null);
        list.setDividerHeight(0);
        list.setPadding(FeatureGateLabUi.dp(context, 16), 0, FeatureGateLabUi.dp(context, 16), FeatureGateLabUi.dp(context, 24));
        list.setClipToPadding(false);
        list.setScrollBarStyle(View.SCROLLBARS_OUTSIDE_OVERLAY);
        adapter = new GateAdapter(context, visible);
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            FeatureGateCatalog.Entry gate = visible.get(position);
            // While a selection is up a tap adds and removes rather than opening, which is what
            // every list that does this does: entering the mode changes what a tap means.
            if (selection.isEmpty()) {
                openDetail(gate);
                return;
            }
            String identity = gate.identity();
            if (selection.remove(identity) == null) selection.put(identity, gate);
            onSelectionChanged();
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            FeatureGateCatalog.Entry gate = visible.get(position);
            selection.put(gate.identity(), gate);
            onSelectionChanged();
            return true;
        });
        listContainer.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        // Blank until the first load answers: a ListView shows its empty view while the adapter
        // has nothing, so "No gates match" sat under "Loading..." before anything was looked at.
        empty = FeatureGateLabUi.label(context, "");
        empty.setGravity(Gravity.CENTER);

        // The way out of an empty list, rather than a sentence on its own in the middle of a
        // blank screen. It only appears when a search is what emptied it, because it is the only
        // case this control can do anything about.
        emptyAction = FeatureGateLabUi.label(context, L10n.t(context, "Clear search"));
        emptyAction.setGravity(Gravity.CENTER);
        emptyAction.setPadding(
                FeatureGateLabUi.dp(context, 16),
                FeatureGateLabUi.dp(context, 12),
                FeatureGateLabUi.dp(context, 16),
                FeatureGateLabUi.dp(context, 12)
        );
        SettingsUi.styleTextAction(emptyAction, true);
        emptyAction.setVisibility(View.GONE);
        emptyAction.setOnClickListener(view -> {
            if (search != null) search.setText("");
        });

        LinearLayout emptyColumn = new LinearLayout(context);
        emptyColumn.setOrientation(LinearLayout.VERTICAL);
        emptyColumn.setGravity(Gravity.CENTER);
        emptyColumn.setPadding(
                FeatureGateLabUi.dp(context, 24),
                FeatureGateLabUi.dp(context, 24),
                FeatureGateLabUi.dp(context, 24),
                FeatureGateLabUi.dp(context, 24)
        );
        emptyColumn.addView(empty, FeatureGateLabUi.wrapWrap());
        emptyColumn.addView(emptyAction, FeatureGateLabUi.wrapWrap());
        listContainer.addView(emptyColumn, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
        ));
        list.setEmptyView(emptyColumn);
        LinearLayout selectionOverlay = buildSelectionBar(context);
        FrameLayout.LayoutParams barParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        barParams.gravity = Gravity.BOTTOM;
        listContainer.addView(selectionOverlay, barParams);
        root.addView(listContainer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        master.setOnCheckedChangeListener((button, checked) -> onMasterChanged(checked));
        search.addTextChangedListener(new SimpleTextWatcher(() -> {
            searchQuery = search.getText().toString();
            clearSearch.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
            // An older ranking result must not flash after the reader has already typed more.
            rebuildGeneration.incrementAndGet();
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
        if (activity == null || activity.isFinishing()) {
            return;
        }
        int turnedOff = FeatureGateLabStore.consumeMigrationNotice();
        if (turnedOff <= 0) {
            return;
        }
        // Both forms take (count, build); the one form leaves the count unused.
        Utils.showToastLong(L10n.quantity(Utils.getContext(), turnedOff,
                "1 override was turned off because the Lab couldn't confirm its gate is unchanged in TikTok %2$s. Review it before turning it back on.",
                "%1$d overrides were turned off because the Lab couldn't confirm their gates are unchanged in TikTok %2$s. Review them before turning them back on.",
                turnedOff, FeatureGateLabStore.targetVersion()));
    }

    @Override
    public void onResume() {
        super.onResume();
        systemBack.register(getActivity(), this::handleSystemBack);
        if (adapter != null) adapter.notifyDataSetChanged();
        // A stall that began in a window since rebuilt, or while the Lab was out of sight.
        DocumentOperation acting = fileOperation;
        if (acting != null && acting.offersStop()) offerStop(getActivity());
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
        if (shown.get() == this) shown = new java.lang.ref.WeakReference<>(null);
        rebuildGeneration.incrementAndGet();
        searchHandler.removeCallbacks(delayedSearch);
        if (search != null) searchQuery = search.getText().toString();
        if (list != null && list.getChildCount() > 0) {
            listPosition = list.getFirstVisiblePosition();
            listOffset = list.getChildAt(0).getTop();
            restoreListPosition = true;
        }
        adapter = null;
        count = null;
        empty = null;
        emptyAction = null;
        search = null;
        clearSearch = null;
        viewTabs = null;
        filterButton = null;
        master = null;
        list = null;
        selectionBar = null;
        selectionCount = null;
        for (int i = 0; i < viewTabLabels.length; i++) {
            viewTabContainers[i] = null;
            viewTabLabels[i] = null;
            viewTabIndicators[i] = null;
        }
        for (int i = 0; i < sourceTabLabels.length; i++) {
            sourceTabContainers[i] = null;
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
        // Entering selection changed what a tap means, so Back is what takes that back. Without
        // this the only way out was the Cancel action, and Back left the Lab entirely.
        if (!selection.isEmpty()) {
            selection.clear();
            onSelectionChanged();
            return;
        }
        if (search != null && search.hasFocus()) {
            FeatureGateLabUi.hideKeyboard(search);
            View root = getView();
            if (root != null) root.requestFocus();
            return;
        }
        leaveLab();
    }

    private void load(boolean refresh) {
        count.setText(L10n.t(getContext(), refresh
                ? "Refreshing TikTok's current values…"
                : "Loading the gate list and TikTok's current values…"));
        FeatureGateCatalog.loadAsync(refresh, new FeatureGateCatalog.Callback() {
            @Override
            public void onLoaded(FeatureGateCatalog.Snapshot loaded) {
                if (!isAdded() || getView() == null) return;
                snapshot = loaded;
                if (!loaded.catalogComplete) {
                    count.setText(L10n.t(getContext(),
                            "Loaded current values. Loading all known gates…"));
                }
                // The message is chosen in rebuild now, from whether a search or only the
                // filter emptied the list. Setting it here as well meant a list narrowed by the
                // view tabs still blamed a search box the reader had not typed in.
                rebuild();
            }

            @Override
            public void onError(String message) {
                if (!isAdded() || getView() == null) return;
                count.setText(
                        L10n.f(getContext(), "Couldn't read TikTok's current values (%1$s). Refresh values from the menu to try again.", message));
                FeatureGateCatalog.Snapshot cached = FeatureGateCatalog.cachedSnapshot();
                if (cached != null) {
                    snapshot = cached;
                    rebuild();
                } else {
                    count.setText(L10n.t(getContext(), "Gate data unavailable"));
                    empty.setText(L10n.t(getContext(), "No gate data is available. Refresh values from the menu to try again."));
                }
            }
        });
    }

    private void rebuild() {
        if (snapshot == null || adapter == null) return;
        String query = normalizeSearchText(searchQuery);
        Map<String, FeatureGateLabStore.Rule> rules = rulesByIdentity();
        int generation = rebuildGeneration.incrementAndGet();
        RebuildRequest request = new RebuildRequest(
                snapshot.entries,
                rules,
                query,
                selectedView,
                selectedFilter,
                SOURCE_MANAGERS[selectedSource]
        );

        // Plain tab and filter changes are cheap. Fuzzy search is not: the complete 47.1.4
        // catalogue has more than 20,000 entries, so it is ranked on a serial worker and only the newest
        // answer is allowed back onto the view hierarchy.
        if (query.isEmpty()) {
            applyRebuild(generation, buildRebuild(request, generation, false));
            return;
        }
        SEARCH_EXECUTOR.execute(() -> {
            lastSearchThreadForTests = Thread.currentThread().getName();
            Runnable hook = searchWorkHookForTests;
            if (hook != null) hook.run();
            RebuildResult result;
            try {
                result = buildRebuild(request, generation, true);
            } catch (Throwable error) {
                Logger.printException(() -> "Could not search the Feature Gate Lab", error);
                try {
                    // Exact, prefix and substring matching remain useful if fuzzy ranking hits
                    // an unexpected malformed value or allocation limit.
                    result = buildRebuild(request, generation, false);
                } catch (Throwable fallbackError) {
                    Logger.printException(() -> "Could not run the Lab search fallback",
                            fallbackError);
                    return;
                }
            }
            if (result == null) return;
            RebuildResult delivered = result;
            searchHandler.post(() -> applyRebuild(generation, delivered));
        });
    }

    private RebuildResult buildRebuild(
            RebuildRequest request,
            int generation,
            boolean fuzzy
    ) {
        List<FeatureGateCatalog.Entry> matches = new ArrayList<>();
        Map<FeatureGateCatalog.Entry, Integer> searchRanks = new HashMap<>();
        String[] queryTokens = request.query.isEmpty()
                ? new String[0]
                : request.query.split(" ");
        int visited = 0;
        for (FeatureGateCatalog.Entry entry : request.entries) {
            if ((visited++ & 127) == 0 && generation != rebuildGeneration.get()) return null;
            if (!entry.userVisible()) continue;
            if (request.sourceManager != null
                    && !request.sourceManager.equals(entry.manager)) continue;
            FeatureGateLabStore.Rule rule = request.rules.get(ruleIdentity(entry));
            if (request.selectedView == 0 && !entry.loaded) continue;
            if (request.selectedView == 2 && rule == null) continue;
            if (!matchesFilter(entry, request.selectedFilter)) continue;
            if (!request.query.isEmpty()) {
                int rank = searchRank(entry, request.query, queryTokens, fuzzy);
                if (rank < 0) continue;
                searchRanks.put(entry, rank);
            }
            matches.add(entry);
        }

        if (generation != rebuildGeneration.get()) return null;
        if (request.selectedView == 2) {
            Collections.sort(matches, (left, right) -> {
                FeatureGateLabStore.Rule leftRule = request.rules.get(ruleIdentity(left));
                FeatureGateLabStore.Rule rightRule = request.rules.get(ruleIdentity(right));
                return Long.compare(
                        rightRule == null ? 0 : rightRule.updatedAtMs,
                        leftRule == null ? 0 : leftRule.updatedAtMs
                );
            });
        } else if (!request.query.isEmpty()) {
            Collections.sort(matches, (left, right) -> {
                int rank = Integer.compare(searchRanks.get(left), searchRanks.get(right));
                return rank != 0 ? rank : left.title.compareToIgnoreCase(right.title);
            });
        }
        return generation == rebuildGeneration.get()
                ? new RebuildResult(matches, request.rules, request.query, request.selectedFilter)
                : null;
    }

    private void applyRebuild(int generation, RebuildResult result) {
        if (result == null || generation != rebuildGeneration.get() || adapter == null) return;
        visible.clear();
        visible.addAll(result.entries);

        // Which filter produced this count. The picker's own label is off screen while the
        // list is being read, and a count on its own gives no way of telling a short list from
        // a narrow filter.
        if (result.selectedFilter == FILTER_ALL) {
            SettingsUi.setResultCount(count, visible.size());
        } else {
            SettingsUi.setTextIfChanged(count, L10n.f(getContext(),
                    "%1$d results, filtered to %2$s",
                    visible.size(), filterLabels(getContext())[result.selectedFilter]));
        }
        updateEmptyState(result.query);
        adapter.setRules(result.rules);
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

    private static final class RebuildRequest {
        final List<FeatureGateCatalog.Entry> entries;
        final Map<String, FeatureGateLabStore.Rule> rules;
        final String query;
        final int selectedView;
        final int selectedFilter;
        final String sourceManager;

        RebuildRequest(
                List<FeatureGateCatalog.Entry> entries,
                Map<String, FeatureGateLabStore.Rule> rules,
                String query,
                int selectedView,
                int selectedFilter,
                String sourceManager
        ) {
            this.entries = entries;
            this.rules = rules;
            this.query = query;
            this.selectedView = selectedView;
            this.selectedFilter = selectedFilter;
            this.sourceManager = sourceManager;
        }
    }

    private static final class RebuildResult {
        final List<FeatureGateCatalog.Entry> entries;
        final Map<String, FeatureGateLabStore.Rule> rules;
        final String query;
        final int selectedFilter;

        RebuildResult(
                List<FeatureGateCatalog.Entry> entries,
                Map<String, FeatureGateLabStore.Rule> rules,
                String query,
                int selectedFilter
        ) {
            this.entries = entries;
            this.rules = rules;
            this.query = query;
            this.selectedFilter = selectedFilter;
        }
    }

    /**
     * Says why the list is empty and, when a search is the reason, offers to undo it.
     *
     * <p>The message used to be set once when the catalogue loaded, so a list emptied by the
     * view tabs or the filter still blamed the search, and a reader looking at an empty screen
     * was told what had happened but given nothing to do about it.
     */
    private void updateEmptyState(String query) {
        if (empty == null || emptyAction == null || getContext() == null) return;
        boolean searching = !query.isEmpty();
        empty.setText(searching
                ? L10n.t(getContext(), "No gates match this search and filter.")
                : L10n.t(getContext(), "No gates match this filter."));
        emptyAction.setVisibility(searching ? View.VISIBLE : View.GONE);
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

    private static boolean matchesFilter(FeatureGateCatalog.Entry entry, int filter) {
        if (filter == FILTER_ALL) return true;
        if (filter == FILTER_UNLOADED) return !entry.loaded;
        if (!entry.loaded) return false;

        boolean isBoolean = "BOOLEAN".equalsIgnoreCase(entry.type)
                || "BOOLEAN".equalsIgnoreCase(entry.currentType);
        if (!isBoolean) return false;
        if (filter == FILTER_BOOLEAN) return true;
        if (filter == FILTER_ENABLED) return "true".equalsIgnoreCase(entry.currentValue);
        if (filter == FILTER_DISABLED) return "false".equalsIgnoreCase(entry.currentValue);
        return true;
    }

    private void onViewSelected(int position) {
        selectedView = position;
        // The tab used to rewrite the filter and the filter used to rewrite the tab, so
        // choosing "On or off" moved the reader to a different tab and tapping "Overrides" put
        // the filter quietly back to All, with nothing on screen saying why either happened.
        // Only one pairing is really a contradiction, and this is the one place it is settled.
        if (!filterAppliesToView(selectedFilter, selectedView)) selectedFilter = FILTER_ALL;
        updateControls();
        rebuild();
    }

    private void onFilterSelected(int position) {
        selectedFilter = position;
        updateControls();
        rebuild();
    }

    /**
     * Whether a filter can mean anything on a tab.
     *
     * <p>Only one pairing cannot: the Loaded tab has already dropped every gate TikTok has not
     * read, so asking it for the unread ones can only come back empty. Everything else is a
     * real question, including a boolean filter on the Overrides tab, which asks which of your
     * overrides are on a boolean gate TikTok has read.
     */
    private static boolean filterAppliesToView(int filter, int view) {
        return !(view == 0 && filter == FILTER_UNLOADED);
    }

    private void onSourceSelected(int position) {
        selectedSource = position;
        updateControls();
        rebuild();
    }

    private void updateControls() {
        for (int i = 0; i < viewTabLabels.length; i++) {
            View container = viewTabContainers[i];
            TextView tab = viewTabLabels[i];
            View indicator = viewTabIndicators[i];
            if (container == null || tab == null || indicator == null) continue;
            boolean selected = i == selectedView;
            container.setSelected(selected);
            FeatureGateLabUi.styleTab(container, selected);
            tab.setTextColor(selected ? SettingsUi.accent() : SettingsUi.textSecondary());
            tab.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            indicator.setBackgroundColor(selected ? SettingsUi.accent() : Color.TRANSPARENT);
        }
        for (int i = 0; i < sourceTabLabels.length; i++) {
            View container = sourceTabContainers[i];
            TextView tab = sourceTabLabels[i];
            View indicator = sourceTabIndicators[i];
            if (container == null || tab == null || indicator == null) continue;
            boolean selected = i == selectedSource;
            container.setSelected(selected);
            FeatureGateLabUi.styleTab(container, selected);
            tab.setTextColor(selected ? SettingsUi.accent() : SettingsUi.textSecondary());
            tab.setTypeface(Typeface.DEFAULT, selected ? Typeface.BOLD : Typeface.NORMAL);
            indicator.setBackgroundColor(selected ? SettingsUi.accent() : Color.TRANSPARENT);
        }
        if (filterButton != null) {
            filterButton.setText(L10n.f(getContext(), "Filter: %1$s",
                    filterLabels(getContext())[selectedFilter]));
        }
    }

    private void showFilterPicker() {
        // Only the filters this tab can answer, so nothing on the list silently moves the tab
        // out from under the reader when it is chosen.
        String[] allLabels = filterLabels(getContext());
        List<String> offeredLabels = new ArrayList<>();
        final int[] offeredFilters = new int[allLabels.length];
        int checked = 0;
        for (int filter = 0; filter < allLabels.length; filter++) {
            if (!filterAppliesToView(filter, selectedView)) continue;
            offeredFilters[offeredLabels.size()] = filter;
            if (filter == selectedFilter) checked = offeredLabels.size();
            offeredLabels.add(allLabels[filter]);
        }
        AlertDialog dialog = new AlertDialog.Builder(getActivity())
                .setTitle(L10n.t(getContext(), "Show gates"))
                .setSingleChoiceItems(offeredLabels.toArray(new String[0]), checked,
                        (choiceDialog, which) -> {
                    onFilterSelected(offeredFilters[which]);
                    choiceDialog.dismiss();
                })
                .setNegativeButton(L10n.t(getContext(), "Cancel"), null)
                .create();
        // This is a platform single-choice list, so use the same radio indicator, text colours
        // and scroll-time restyling as every other platform picker in settings.
        dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
        dialog.show();
    }

    /**
     * Puts the switch back where the store is, when the screen is still up.
     *
     * <p>The null check is the point: a Lab change finishes on the main thread after work that
     * takes long enough to leave the screen during, and {@link #onDestroyView} has cleared the
     * switch by then.
     */
    private void syncMasterSwitch() {
        if (master == null) return;
        master.setChecked(FeatureGateLabStore.masterEnabled());
        master.setContentDescription(L10n.t(getContext(), "Apply overrides"));
        SettingsUi.styleSwitch(master);
    }

    private void onMasterChanged(boolean checked) {
        if (FeatureGateLabStore.masterEnabled() == checked) return;
        if (CHANGING.get()) {
            syncMasterSwitch();
            Utils.showToastLong(L10n.t(Utils.getContext(),
                    "A Lab change is already running"));
            return;
        }
        // This is storage, not a flag: the journal lock, two write-and-verify cycles and a
        // blocking commit. The Lab's other three mutations already go this way; this one held
        // the main thread, and a settings restore holding the journal lock froze the screen
        // until it finished. runLabChange puts the switch back for us either way.
        runLabChange(() -> FeatureGateLabUndo.setMasterEnabled(checked),
                checked ? L10n.t(getContext(), "Overrides enabled. Restart TikTok to apply this.")
                        : L10n.t(getContext(), "Overrides disabled. Restart TikTok to apply this."));
    }

    /**
     * The row of actions that appears once a long press has gathered a selection.
     *
     * <p>Built with the screen and hidden, rather than added and removed, so nothing about the
     * layout moves when a selection starts.
     */
    private LinearLayout buildSelectionBar(Context context) {
        selectionBar = new LinearLayout(context);
        selectionBar.setOrientation(LinearLayout.VERTICAL);
        selectionBar.setVisibility(View.GONE);
        int padding = FeatureGateLabUi.dp(context, 16);
        selectionBar.setPadding(padding, FeatureGateLabUi.dp(context, 12), padding,
                FeatureGateLabUi.dp(context, 12));
        selectionBar.setBackground(SettingsUi.borderedSurface(context, SettingsUi.RADIUS_SETTINGS_CARD, true));

        selectionCount = FeatureGateLabUi.label(context, "");
        selectionCount.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        selectionBar.addView(selectionCount, FeatureGateLabUi.matchWrap());

        // Four labels do not fit one line at large text, and four German labels do not fit one
        // at any size, so the row wraps rather than squeezing the last of them to nothing.
        ViewGroup actions = SettingsUi.actionRow(context);
        actions.addView(selectionAction(context, L10n.t(context, "Reset"),
                this::resetSelection, true));
        actions.addView(selectionAction(context, L10n.t(context, "Disable"),
                () -> forceSelection(false), true));
        actions.addView(selectionAction(context, L10n.t(context, "Enable"),
                () -> forceSelection(true), true));
        // Cancel only puts the selection down. It is not one of the three that write.
        actions.addView(selectionAction(context, L10n.t(context, "Cancel"), () -> {
            selection.clear();
            onSelectionChanged();
        }, false));
        selectionBar.addView(actions, FeatureGateLabUi.matchWrap());

        TextView selectionHint = FeatureGateLabUi.label(context,
                L10n.t(context, "Hold a gate to choose several"));
        selectionHint.setTextColor(SettingsUi.textSecondary());
        // Said to someone who has just held a gate, so it is the line to give up at large text,
        // where the bar grew taller than the list it sits over on a small phone.
        if (context.getResources().getConfiguration().fontScale >= 1.3f) {
            selectionHint.setVisibility(View.GONE);
        }
        selectionBar.addView(selectionHint, FeatureGateLabUi.matchWrap());
        // The list ends above the bar at whatever height the bar came out, so the last gates can
        // scroll clear of it. A fixed 80 dp left the bottom row under a bar of about 110 dp.
        selectionBar.addOnLayoutChangeListener((bar, left, top, right, bottom, oldLeft, oldTop,
                oldRight, oldBottom) -> {
            if (bar.getVisibility() == View.VISIBLE && list != null) padListAbove(bar.getHeight());
        });
        return selectionBar;
    }

    /** Room under the last gate for a bar this tall, or the plain end margin with none (-1). */
    private void padListAbove(int barHeight) {
        int bottomPad = barHeight < 0 ? FeatureGateLabUi.dp(list.getContext(), 24)
                : Math.max(FeatureGateLabUi.dp(list.getContext(), 80),
                        barHeight + FeatureGateLabUi.dp(list.getContext(), 8));
        if (list.getPaddingBottom() == bottomPad) return;
        list.setPadding(list.getPaddingLeft(), list.getPaddingTop(), list.getPaddingRight(), bottomPad);
    }

    private TextView selectionAction(Context context, String label, Runnable action,
            boolean primary) {
        TextView button = FeatureGateLabUi.text(context, label, 14,
                primary ? SettingsUi.accent() : SettingsUi.textSecondary(),
                primary ? Typeface.BOLD : Typeface.NORMAL);
        SettingsUi.styleTextAction(button, primary);
        button.setMinimumHeight(FeatureGateLabUi.dp(context, 48));
        button.setMinimumWidth(FeatureGateLabUi.dp(context, 48));
        button.setGravity(Gravity.CENTER);
        int side = FeatureGateLabUi.dp(context, 12);
        button.setPadding(side, 0, side, 0);
        button.setContentDescription(label);
        button.setOnClickListener(view -> action.run());
        return button;
    }

    /** Shows or hides the bar and repaints the rows, which draw their own chosen state. */
    private void onSelectionChanged() {
        boolean barVisible = !selection.isEmpty();
        if (selectionBar != null) {
            selectionBar.setVisibility(barVisible ? View.VISIBLE : View.GONE);
        }
        if (list != null) {
            padListAbove(barVisible && selectionBar != null ? selectionBar.getHeight() : -1);
        }
        if (selectionCount != null) {
            selectionCount.setText(L10n.quantity(getContext(), selection.size(),
                    "1 gate selected", "%1$d gates selected"));
        }
        if (adapter != null) adapter.notifyDataSetChanged();
    }

    private void forceSelection(boolean value) {
        List<FeatureGateCatalog.Entry> gates = new ArrayList<>(selection.values());
        int total = gates.size();
        boolean started = runLabChange(() -> {
            int written = FeatureGateLabUndo.forceBoolean(gates, value);
            if (written == 0) {
                throw new Refused(L10n.t(Utils.getContext(),
                        "None of these gates takes a true or false value."));
            }
            // The message says what happened rather than what was asked for. Reported as a
            // failure, which is what throwing here did, it said nothing went through on a run
            // where most of it had.
            //
            // Eight whole sentences rather than a verb, a count and two tails glued together.
            // A table row holds a sentence: assembled here, the plural rule of the language
            // never got a say and neither did the order the pieces go in.
            android.content.Context context = Utils.getContext();
            if (written == total) {
                if (value) {
                    return L10n.quantity(context, written,
                            "Forced 1 gate. Restart TikTok to apply this.",
                            "Forced %1$d gates. Restart TikTok to apply this.");
                }
                return L10n.quantity(context, written,
                        "Turned off 1 gate. Restart TikTok to apply this.",
                        "Turned off %1$d gates. Restart TikTok to apply this.");
            }
            // Both forms take (written, total); the one form leaves the first unused.
            if (value) {
                return L10n.quantity(context, written,
                        "Forced 1 gate of %2$d. The rest don't take a true or false value. Restart TikTok to apply this.",
                        "Forced %1$d gates of %2$d. The rest don't take a true or false value. Restart TikTok to apply this.",
                        written, total);
            }
            return L10n.quantity(context, written,
                    "Turned off 1 gate of %2$d. The rest don't take a true or false value. Restart TikTok to apply this.",
                    "Turned off %1$d gates of %2$d. The rest don't take a true or false value. Restart TikTok to apply this.",
                    written, total);
        });
        if (started) {
            selection.clear();
            onSelectionChanged();
        }
    }

    private void resetSelection() {
        List<FeatureGateCatalog.Entry> gates = new ArrayList<>(selection.values());
        int total = gates.size();
        boolean started = runLabChange(() -> {
            int dropped = FeatureGateLabUndo.resetAll(gates);
            if (dropped == 0) {
                throw new Refused(L10n.t(Utils.getContext(),
                        "None of these gates had an override to reset."));
            }
            // Dropped, not selected: choosing five gates of which two had an override resets two.
            // One whole sentence per shape. Built from a count and three fragments it was four
            // rows no table could hold, and the plural rule of the language never got a say.
            android.content.Context context = Utils.getContext();
            if (dropped == total) {
                return L10n.quantity(context, dropped,
                        "Reset 1 gate. Restart TikTok to apply this.",
                        "Reset %1$d gates. Restart TikTok to apply this.");
            }
            return L10n.quantity(context, dropped,
                    "Reset 1 gate of %2$d. Restart TikTok to apply this.",
                    "Reset %1$d gates of %2$d. Restart TikTok to apply this.",
                    dropped, total);
        });
        if (started) {
            selection.clear();
            onSelectionChanged();
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

    /**
     * The Lab's overflow.
     *
     * <p>It was a platform PopupMenu on the platform's own Material theme, a grey sheet
     * next to everything else the bundle draws, and there is no way to give a popup a background
     * of ours: that goes through a style resource and the payload carries no resources at all.
     * A list in the bundle's own dialog surface themes correctly in both modes and, unlike a
     * popup menu, can show an action that is there but cannot be taken.
     *
     * <p>Undo used to be offered whether or not there was anything to undo, and the only way to
     * find out was to press it and be told off.
     */
    private void showOverflow() {
        if (getActivity() == null) return;
        boolean canUndo = FeatureGateLabUndo.canUndo();
        String[] labels = {
                L10n.t(getContext(), "Refresh values"),
                L10n.t(getContext(), "Export loaded values"),
                L10n.t(getContext(), "Import loaded values"),
                // Neither said what it took away, and the second one takes a good deal more
                // than overrides: the master switch, the acknowledgement and the recordings.
                L10n.t(getContext(), "Remove all overrides"),
                L10n.t(getContext(), "Clear all Lab data (overrides, switch, recordings)"),
                L10n.t(getContext(), "Reviewed presets"),
                L10n.t(getContext(), "Undo last Lab change"),
        };
        final int undoItem = labels.length - 1;

        android.widget.ArrayAdapter<String> items = new android.widget.ArrayAdapter<String>(
                getActivity(), android.R.layout.simple_list_item_1, labels) {
            @Override public boolean areAllItemsEnabled() {
                return canUndo;
            }

            @Override public boolean isEnabled(int position) {
                return canUndo || position != undoItem;
            }

            @Override public View getView(int position, View convertView, ViewGroup parent) {
                View view = super.getView(position, convertView, parent);
                TextView label = (TextView) view.findViewById(android.R.id.text1);
                boolean enabled = isEnabled(position);
                label.setTextColor(enabled
                        ? SettingsUi.textPrimary() : SettingsUi.textDisabled());
                label.setEnabled(enabled);
                view.setEnabled(enabled);
                return view;
            }
        };

        // Titled and with a Cancel, like every other menu in settings. It had neither, so it
        // was a list floating over the page with only a tap outside to leave it.
        AlertDialog menu = new AlertDialog.Builder(getActivity())
                .setTitle(L10n.t(getContext(), "Feature Gate Lab"))
                .setNegativeButton(L10n.t(getContext(), "Cancel"), null)
                .setAdapter(items, (dialog, which) -> {
                    switch (which) {
                        case 0: load(true); break;
                        case 1: exportLoadedValues(); break;
                        case 2: chooseLoadedValuesFile(); break;
                        case 3: reset(false); break;
                        case 4: reset(true); break;
                        case 5: showPresets(); break;
                        default:
                            runLabChange(FeatureGateLabUndo::undo, L10n.t(getContext(),
                                    "Lab settings put back. Restart TikTok to apply this."));
                            break;
                    }
                })
                .create();
        menu.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(menu));
        menu.show();
    }

    private void showPresets() {
        if (getActivity() == null) return;
        try {
            // Only the installed build's presets. With every preset reviewed for each catalog
            // build, listing them all put three copies of each row on the screen, two of which
            // could only say they weren't for this TikTok.
            String build = app.morphe.extension.shared.BuildNames.runningBuild();
            JSONObject version = FeatureGateLabStore.reviewedPresets().optJSONObject(build);
            if (version == null || version.length() == 0) {
                postToast(L10n.f(getContext(), "No presets have been reviewed for TikTok %1$s yet", build));
                return;
            }
            List<String> ids = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            java.util.Iterator<String> names = version.keys();
            while (names.hasNext()) {
                String id = names.next();
                ids.add(id);
                String title = version.getJSONObject(id).getString("title");
                labels.add(L10n.t(getContext(), title));
            }
            AlertDialog dialog = new AlertDialog.Builder(getActivity())
                    .setTitle(L10n.f(getContext(), "Reviewed presets for TikTok %1$s", build))
                    .setItems(labels.toArray(new String[0]),
                            (ignored, index) -> showPreset(build, ids.get(index)))
                    .setNegativeButton(L10n.t(getContext(), "Cancel"), null).create();
            showStyled(dialog);
        } catch (Exception error) {
            Logger.printException(() -> "Could not read bundled Lab presets", error);
            postToast(L10n.t(getContext(), "The presets couldn't load. Open the Lab again."));
        }
    }

    private void showPreset(String build, String id) {
        if (getActivity() == null) return;
        // The first snapshot holds only the values TikTok has stored. A preset reviewed against
        // it found gates TikTok never sent missing and refused as not matching this build.
        if (snapshot == null || !snapshot.catalogComplete) {
            postToast(L10n.t(getContext(), "Loaded values are still being read. Try again in a moment."));
            return;
        }
        try {
            JSONObject preset = FeatureGateLabStore.reviewedPresets().getJSONObject(build).getJSONObject(id);
            String title = preset.getString("title");
            StringBuilder preview = new StringBuilder(L10n.f(getContext(),
                    "Reviewed for TikTok %1$s", build));
            // What the gates alone don't do, ahead of the values so it's read first (#98).
            String note = preset.optString("note", "");
            if (!note.isEmpty()) preview.append("\n\n").append(L10n.t(getContext(), note));
            boolean compatible = build.equals(app.morphe.extension.shared.BuildNames.runningBuild());
            if (compatible) {
                FeatureGateLabStore.ImportReview review = FeatureGateLabStore.reviewPreset(build, id, snapshot.byIdentity);
                for (FeatureGateLabStore.Rule rule : review.accepted) {
                    FeatureGateLabStore.Rule existing = FeatureGateLabStore.rule(rule.manager, rule.key, rule.type);
                    FeatureGateCatalog.Entry gate = snapshot.byIdentity.get(rule.manager + "\n" + rule.key);
                    String before = existing != null && existing.enabled ? existing.value
                            : gate.loaded ? gate.currentValue : L10n.t(getContext(), "Not seen yet");
                    preview.append("\n\n").append(rule.key).append("\n")
                            .append(rule.manager).append(" / ").append(rule.type).append("\n")
                            .append(L10n.f(getContext(), "Value: %1$s to %2$s", before, rule.value));
                }
                // Undo holds the last Lab change only. The next one replaces it, and with overrides
                // off, turning them on is that next change, so the promise had to say so.
                preview.append("\n\n").append(L10n.t(getContext(), FeatureGateLabStore.masterEnabled()
                        ? "Apply enables these overrides. Undo last Lab change puts your previous rules back until you change anything else in the Lab."
                        : "Apply saves these overrides. Turn on overrides in the Lab to use them. Turning them on is a Lab change too, so after that Undo turns them off rather than putting your previous rules back."));
            } else {
                preview.append("\n\n").append(L10n.t(getContext(),
                        "This preset isn't available for your installed TikTok version."));
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity())
                    .setTitle(L10n.t(getContext(), title))
                    .setMessage(preview)
                    .setNegativeButton(L10n.t(getContext(), "Cancel"), null);
            if (compatible) builder.setPositiveButton(L10n.t(getContext(), "Apply"), (ignored, which) ->
                    runLabChange(() -> FeatureGateLabUndo.applyPreset(build, id, snapshot.byIdentity),
                            L10n.t(getContext(), FeatureGateLabStore.masterEnabled()
                                    ? "Preset applied. Restart TikTok to use it."
                                    : "Preset saved. Turn on overrides in the Lab to use it.")));
            showStyled(builder.create());
        } catch (Exception error) {
            Logger.printException(() -> "Could not review a bundled Lab preset", error);
            postToast(L10n.t(getContext(), "This preset doesn't match the installed gates. Nothing changed."));
        }
    }

    private void exportLoadedValues() {
        try {
            if (getActivity() == null) {
                postToast(L10n.t(Utils.getContext(), "Couldn't open the file picker to export. Try again."));
                return;
            }
            if (fileSlotTaken()) return;
            if (snapshot == null) {
                postToast(L10n.t(Utils.getContext(),
                        "Loaded values are still being read. Try again in a moment."));
                return;
            }
            // The stamp every other export takes, UTC, so the Lab's file sorts beside them.
            String timestamp = app.morphe.extension.shared.settings.preference.LogBufferManager.fileTimestamp();
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/gzip")
                    .putExtra(Intent.EXTRA_TITLE,
                            "tiktok-" + FeatureGateLabStore.targetVersion() + "-loaded-feature-gates-" + timestamp + ".json.gz");
            startActivityForResult(intent, REQUEST_EXPORT_LOADED);
        } catch (Throwable throwable) {
            Utils.showToastLong(L10n.t(Utils.getContext(),
                    "Couldn't open the file picker to export. Try again."));
        }
    }

    private void chooseLoadedValuesFile() {
        try {
            if (fileSlotTaken()) return;
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("*/*")
                    .putExtra(Intent.EXTRA_MIME_TYPES,
                            new String[]{"application/gzip", "application/json", "application/octet-stream"});
            startActivityForResult(intent, REQUEST_IMPORT_LOADED);
        } catch (Throwable throwable) {
            Utils.showToastLong(L10n.t(Utils.getContext(),
                    "Couldn't open the file picker to import. Try again."));
        }
    }

    private void writeLoadedValuesFile(Uri uri) {
        Activity activity = getActivity();
        Context context = activity == null ? Utils.getContext() : activity.getApplicationContext();
        // The values the Lab showed when Export was tapped, whatever it reads after.
        FeatureGateCatalog.Snapshot values = snapshot;
        startFileOperation(context, uri, true, file -> exportTo(context, uri, values, file));
    }

    private void readLoadedValuesFile(Uri uri) {
        Activity activity = getActivity();
        Context context = activity == null ? null : activity.getApplicationContext();
        if (context == null) {
            postToast(L10n.t(Utils.getContext(),
                    "The loaded-values file you chose couldn't be read. Try again."));
            return;
        }
        startFileOperation(context, uri, false, file -> importFrom(context, uri, file));
    }

    /**
     * Starts a loaded-value read or write on a worker of its own. While a file app keeps one
     * waiting it holds only the Lab's slot: a later tap says so instead of queueing behind it, and
     * after the stall threshold the Lab offers to stop waiting. Main thread.
     */
    private static void startFileOperation(Context context, Uri uri, boolean exports,
            DocumentOperation.Work work) {
        DocumentOperation[] started = new DocumentOperation[1];
        started[0] = DocumentOperation.start(DocumentOperation.Kind.LAB_FILE, work,
                () -> fileOperationChanged(started[0]));
        if (started[0] != null) {
            fileOperation = started[0];
            fileOperationExports = exports;
            stopOffered = false;
            return;
        }
        // The picker made a document for this export, and nothing will fill it now.
        if (exports && context != null) {
            ContentResolver resolver = context.getContentResolver();
            Utils.runOnOwnThread("Metra-LabCleanup",
                    () -> DocumentOperation.removeUnsaved(resolver, uri, false));
        }
        if (DocumentOperation.busy(DocumentOperation.Kind.LAB_FILE)) {
            tellFileOut();
        } else {
            fileNotice(L10n.t(context, "Couldn't start the Lab change. Try again in a moment."));
        }
    }

    /**
     * Writes the loaded values out once the file app has been asked for the document. It's opened
     * "wt": a plain "w" left the end of a longer file the user chose to replace, and a gzip reader
     * then found a broken file.
     */
    private static void exportTo(Context context, Uri uri, FeatureGateCatalog.Snapshot values,
            DocumentOperation file) {
        ContentResolver resolver = context.getContentResolver();
        boolean opened = false;
        try {
            ExportPayload payload = buildExportPayload(values);
            if (!file.publish()) {
                DocumentOperation.removeUnsaved(resolver, uri, false);
                return;
            }
            try (OutputStream output = file.openForWrite(resolver, uri, "wt")) {
                opened = true;
                output.write(payload.gzipBytes);
            }
            file.finish();
            fileNotice(L10n.quantity(context, payload.count,
                    "Exported 1 loaded value", "Exported %1$d loaded values"));
        } catch (Exception | OutOfMemoryError error) {
            // Stopped before the file app had anything: the stop has said what happened.
            if (file.stage() == DocumentOperation.Stage.STOPPED) {
                DocumentOperation.removeUnsaved(resolver, uri, false);
                return;
            }
            DocumentOperation.logFailure(file, () -> "Loaded-value file export failed", error);
            String message = file.stage() == DocumentOperation.Stage.STOPPED_WHILE_PUBLISHING
                    ? L10n.t(context, "The export wasn't saved. Export again when the file app is ready.")
                    : L10n.t(context, "Loaded-value file export failed");
            if (!DocumentOperation.removeUnsaved(resolver, uri, opened)) {
                message += " " + L10n.t(context,
                        "The partial file couldn't be removed. Delete it from the folder you chose.");
            }
            fileNotice(message);
        }
    }

    /**
     * Reads the chosen file and hands it to the Lab on screen for review. Showing the review is
     * the commit: a stop before it leaves the Lab as it was, and one after it can't take it back.
     */
    private void importFrom(Context context, Uri uri, DocumentOperation file) {
        try {
            byte[] encoded;
            try (InputStream input = file.openForRead(context.getContentResolver(), uri)) {
                encoded = readLimited(input, MAX_JSON_IMPORT_BYTES);
            }
            JSONObject imported = readLoadedJson(encoded);
            if (!file.commit()) return;
            // A Lab rebuilt while the file app had the file reviews it against what it shows now.
            FeatureGateLabFragment now = shown.get();
            FeatureGateLabFragment lab = now != null && now.isAdded() ? now : this;
            FeatureGateCatalog.Snapshot current = lab.snapshot != null ? lab.snapshot : snapshot;
            lab.reviewLoadedImport(imported, current);
        } catch (Exception | OutOfMemoryError error) {
            if (file.stage() == DocumentOperation.Stage.STOPPED) return;
            // A file the review could name is told to the reader. Anything else stays a patch
            // error: a parse failure is how an export the Lab should take shows up.
            if (error instanceof Refused) {
                Logger.printInfo(() -> "Loaded-value file import refused", (Refused) error);
            } else {
                Logger.printException(() -> "Loaded-value file import failed", error);
            }
            // A refusal the review could name says what it was. A file from another build
            // used to be reported as invalid or too large, the same as a corrupt one.
            fileNotice(error instanceof Refused
                    ? error.getMessage()
                    : L10n.t(context, "That file isn't a loaded-values export, or it's larger than the Lab accepts"));
        }
    }

    /** The Lab's file operation changed stage, stalled or ended. Main thread. */
    private static void fileOperationChanged(DocumentOperation operation) {
        if (operation == null || operation != fileOperation) return;
        Context context = window();
        if (!DocumentOperation.busy(DocumentOperation.Kind.LAB_FILE)) {
            fileOperation = null;
            SettingsActionBanner.dismissShowing(stillWaiting(context));
            return;
        }
        if (operation.offersStop()) {
            if (!stopOffered) {
                stopOffered = true;
                offerStop(context);
            }
        } else if (!operation.isStopped()) {
            // Past the gate, where a stop can't do anything any more.
            SettingsActionBanner.dismissShowing(stillWaiting(context));
        }
    }

    private static void offerStop(Context context) {
        String waiting = stillWaiting(context);
        SettingsActionBanner.showAction(context, waiting, L10n.t(context, "Stop waiting"),
                () -> stopWaiting(context), waiting);
    }

    private static String stillWaiting(Context context) {
        return L10n.t(context, "Still waiting for the file app.");
    }

    /** Main thread, from the banner's Stop waiting. */
    private static void stopWaiting(Context context) {
        DocumentOperation operation = fileOperation;
        if (operation == null) return;
        switch (operation.stop()) {
            case STOPPED:
                fileNotice(fileOperationExports
                        ? L10n.t(context, "Stopped waiting for the file app. Nothing was exported.")
                        : L10n.t(context, "Stopped waiting for the file app. Nothing was imported."));
                break;
            case STOPPED_WHILE_PUBLISHING:
                fileNotice(L10n.t(context,
                        "Stopped waiting for the file app. It hasn't said yet whether the export was saved."));
                break;
            default:
                // Past the gate: the review or the export's result comes on its own.
                break;
        }
    }

    /** Whether the Lab's file slot is out, saying why a new import or export can't start. Main thread. */
    private static boolean fileSlotTaken() {
        if (!DocumentOperation.busy(DocumentOperation.Kind.LAB_FILE)) return false;
        tellFileOut();
        return true;
    }

    private static void tellFileOut() {
        Context context = window();
        DocumentOperation operation = fileOperation;
        if (DocumentOperation.heldAfterStop(DocumentOperation.Kind.LAB_FILE)) {
            fileNotice(L10n.t(context,
                    "The file app still has the last file. Try again once it lets go, or restart TikTok."));
        } else if (operation != null && operation.offersStop()) {
            offerStop(context);
        } else {
            fileNotice(stillWaiting(context));
        }
    }

    /** Where file results are said: the Lab on screen, else the TikTok window in front. */
    private static Context window() {
        FeatureGateLabFragment lab = shown.get();
        Activity activity = lab == null ? null : lab.getActivity();
        return activity != null ? activity : Utils.getContext();
    }

    private static void fileNotice(String message) {
        SettingsActionBanner.showNotice(window(), message);
    }

    /** A change or a loaded-value file turned down, with the reason already in the reader's words. */
    private static final class Refused extends IllegalArgumentException {
        Refused(String sentence) {
            super(sentence);
        }
    }

    private void reviewLoadedImport(JSONObject imported, FeatureGateCatalog.Snapshot currentSnapshot)
            throws Exception {
        Context context = getActivity();
        if (context == null) context = Utils.getContext();
        if (currentSnapshot == null) {
            throw new Refused(L10n.t(context,
                    "Loaded values are still being read. Try again in a moment."));
        }
        if (!"loaded_values".equals(imported.optString("payload_kind"))) {
            throw new Refused(L10n.t(context, "This file isn't a loaded-values export from the Feature Gate Lab."));
        }
        if (!FeatureGateLabStore.targetVersion().equals(imported.optString("tiktok_version"))) {
            throw new Refused(L10n.t(context, "These loaded values are for a different TikTok version."));
        }

        Map<String, FeatureGateLabStore.Rule> existingRules = rulesByIdentity();
        JSONArray sourceRules = imported.optJSONArray("rules");
        JSONArray candidates = new JSONArray();
        int same = 0;
        int unavailable = 0;
        int malformed = 0;
        if (sourceRules == null) throw new Refused(L10n.t(context, "This file has no loaded values in it."));
        {
            for (int i = 0; i < sourceRules.length(); i++) {
                JSONObject item = sourceRules.optJSONObject(i);
                if (item == null) {
                    malformed++;
                    continue;
                }
                if (!isStringField(item, "manager") || !isStringField(item, "key")
                        || !isStringField(item, "type") || !isStringField(item, "value")) {
                    malformed++;
                    continue;
                }
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
        profile.put("tiktok_version", FeatureGateLabStore.targetVersion());
        profile.put("rules", candidates);
        FeatureGateLabStore.ImportReview review = FeatureGateLabStore.reviewProfile(
                profile.toString(), currentSnapshot.byIdentity);

        // An import that accepted nothing writes no undo copy, so a message offering Undo
        // would point at whatever the previous Lab change was.
        int rejected = review.rejected.size() + malformed;
        String firstRejection = review.rejected.isEmpty()
                ? null
                : FeatureGateLabText.importRejection(
                        Utils.getContext(), review.rejected.get(0));
        if (review.accepted.isEmpty()) {
            // Nothing changed, so one line is enough and the toast stays.
            String message = firstRejection == null
                    ? L10n.f(Utils.getContext(),
                            "Nothing new was imported. %1$d already matched, %2$d unavailable, %3$d rejected.",
                            same, unavailable, rejected)
                    : L10n.f(Utils.getContext(),
                            "Nothing new was imported. %1$d already matched, %2$d unavailable, %3$d rejected. First rejection: %4$s",
                            same, unavailable, rejected, firstRejection);
            runLabChange(() -> FeatureGateLabUndo.importRules(review), message);
            return;
        }
        // Five clauses in a toast that lasts 3.5 seconds could not be read, and the rejection
        // text could not be copied out of it. A dialog holds each count on its own line, keeps
        // the rejection selectable, and puts Undo beside Done.
        int accepted = review.accepted.size();
        int matched = same;
        int notAvailable = unavailable;
        runLabChange(() -> FeatureGateLabUndo.importRules(review), null,
                notice -> showImportResult(accepted, matched, notAvailable, rejected, firstRejection));
    }

    /** The import's counts as lines a reader can take in, with the first rejection selectable. */
    static LinearLayout importResultView(Context context, int accepted, int matched, int unavailable,
                                         int rejected, String firstRejection) {
        LinearLayout lines = new LinearLayout(context);
        lines.setOrientation(LinearLayout.VERTICAL);
        int side = FeatureGateLabUi.dp(context, 20);
        lines.setPadding(side, FeatureGateLabUi.dp(context, 8), side, 0);
        lines.addView(FeatureGateLabUi.body(context, L10n.f(context, "Imported %1$d values", accepted)),
                FeatureGateLabUi.matchWrap());
        lines.addView(FeatureGateLabUi.label(context, L10n.f(context, "%1$d already matched", matched)),
                FeatureGateLabUi.matchWrap());
        lines.addView(FeatureGateLabUi.label(context, L10n.f(context, "%1$d unavailable", unavailable)),
                FeatureGateLabUi.matchWrap());
        lines.addView(FeatureGateLabUi.label(context, L10n.f(context, "%1$d rejected", rejected)),
                FeatureGateLabUi.matchWrap());
        if (firstRejection != null) {
            lines.addView(FeatureGateLabUi.label(context, L10n.t(context, "First rejection:")),
                    FeatureGateLabUi.matchWrap());
            TextView rejection = FeatureGateLabUi.body(context, firstRejection);
            rejection.setTextIsSelectable(true);
            lines.addView(rejection, FeatureGateLabUi.matchWrap());
        }
        return lines;
    }

    private void showImportResult(int accepted, int matched, int unavailable, int rejected,
                                  String firstRejection) {
        Activity activity = getActivity();
        if (activity == null) {
            // The Lab was closed while the file was being read. There is nothing to hang a
            // dialog on, so the one line that matters goes out the way every other change is told.
            Utils.showToastLong(L10n.f(Utils.getContext(),
                    "Imported %1$d values. Undo last Lab change is in the menu.", accepted));
            return;
        }
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle(L10n.t(activity, "Import finished"))
                .setView(importResultView(activity, accepted, matched, unavailable, rejected, firstRejection))
                .setPositiveButton(L10n.t(activity, "Done"), null)
                .setNegativeButton(L10n.t(activity, "Undo"), (ignored, which) -> runLabChange(
                        FeatureGateLabUndo::undo,
                        L10n.t(activity, "Lab settings put back. Restart TikTok to apply this.")))
                .create();
        showStyled(dialog);
    }

    private static ExportPayload buildExportPayload(FeatureGateCatalog.Snapshot currentSnapshot)
            throws Exception {
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
        root.put("tiktok_version", FeatureGateLabStore.targetVersion());
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

    private static JSONObject readLoadedJson(byte[] encoded) throws Exception {
        byte[] json = isGzip(encoded) ? readGzipJson(encoded) : encoded;
        return SettingsJson.parseObject(json, IMPORT_JSON_LIMITS);
    }

    private static boolean isGzip(byte[] encoded) {
        return encoded.length >= 2 && (encoded[0] & 0xff) == 0x1f
                && (encoded[1] & 0xff) == 0x8b;
    }

    private static byte[] readGzipJson(byte[] compressed) throws Exception {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            return readLimited(gzip, MAX_JSON_IMPORT_BYTES);
        }
    }

    private static byte[] readLimited(InputStream input, int maxBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        long total = 0;
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

    private static boolean isStringField(JSONObject object, String key) {
        return object.has(key) && object.opt(key) instanceof String;
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

    private void reset(boolean allData) {
        runLabChange(() -> {
            boolean undoAvailable = FeatureGateLabUndo.reset(allData);
            if (allData && !undoAvailable) {
                return L10n.t(Utils.getContext(),
                        "Lab data was over its rule limit, so it was cleared without an undo copy. Restart TikTok to apply this.");
            }
            return allData
                    ? L10n.t(Utils.getContext(),
                            "Lab data reset. Undo last Lab change is in the menu. Restart TikTok to apply this.")
                    : L10n.t(Utils.getContext(),
                            "Lab overrides reset. Undo last Lab change is in the menu. Restart TikTok to apply this.");
        });
    }

    private interface LabChange { void run() throws Exception; }

    /** A change that decides its own message from what it turned out to do. */
    private interface ReportingLabChange { String run() throws Exception; }

    /**
     * The thread the last Lab change ran on.
     *
     * <p>Every one of them touches storage, so a test needs to be able to say which thread that
     * happened on rather than only that it happened.
     */
    private static volatile String lastChangeThreadForTests;

    static String lastChangeThreadForTests() {
        return lastChangeThreadForTests;
    }

    private void runLabChange(LabChange change, String message) {
        runLabChange(() -> {
            change.run();
            return message;
        });
    }

    /** How a finished change tells the reader. The toast is the default; an import opens a dialog. */
    interface NoticePresenter {
        void present(String notice);
    }

    private void runLabChange(LabChange change, String message, NoticePresenter presenter) {
        runLabChange(() -> {
            change.run();
            return message;
        }, presenter);
    }

    /**
     * @return false when another change was already running, so nothing was started. Callers with
     *         state of their own, such as the gate selection, keep it rather than giving it up
     *         for a change that never ran.
     */
    private boolean runLabChange(ReportingLabChange change) {
        return runLabChange(change, Utils::showToastLong);
    }

    private boolean runLabChange(ReportingLabChange change, NoticePresenter presenter) {
        if (!CHANGING.compareAndSet(false, true)) {
            postToast(L10n.t(Utils.getContext(), "A Lab change is already running"));
            return false;
        }
        boolean accepted = Utils.runOnBackgroundThread(() -> {
            lastChangeThreadForTests = Thread.currentThread().getName();
            String result;
            // A failure is told the plain way whatever the caller planned for a success: an
            // import that did not happen has no counts to put in a dialog.
            NoticePresenter tell = presenter;
            try {
                result = change.run();
            } catch (Exception error) {
                boolean refused = error instanceof Refused
                        || error instanceof FeatureGateLabStore.RuleLimitRefused;
                // A refusal is the Lab working, and the reader is told why. Filed as a patch
                // error, its debug toast covered that reason.
                if (refused) Logger.printInfo(() -> "Lab change refused", error);
                else Logger.printException(() -> "Lab change failed", error);
                // A refusal already carries its reason in the reader's words. Swapped for the
                // retry line, the reader was told to try again what would fail the same way.
                result = refused
                        ? error.getMessage()
                        : L10n.t(Utils.getContext(), "Couldn't change the Lab settings. Try again.");
                tell = Utils::showToastLong;
            }
            String notice = result;
            NoticePresenter presenting = tell;
            new Handler(Looper.getMainLooper()).post(() -> {
                // Released first. The flag is process-wide and nothing else clears it, so a
                // failure while putting the screen back used to refuse every later Lab change
                // until TikTok was restarted.
                CHANGING.set(false);
                syncMasterSwitch();
                rebuild();
                if (notice != null) presenting.present(notice);
                else presenting.present("");
            });
        });
        if (!accepted) {
            CHANGING.set(false);
            // The platform switch has already moved before this callback runs. Put it back on
            // the stored value now, otherwise the next tap only repairs the stale drawing and
            // the reader has to tap a third time before the requested change is submitted.
            syncMasterSwitch();
            postToast(L10n.t(Utils.getContext(),
                    "Couldn't start the Lab change. Try again in a moment."));
            return false;
        }
        return true;
    }

    private static void showStyled(AlertDialog dialog) {
        dialog.setOnShowListener(ignored -> FeatureGateLabUi.styleDialog(dialog));
        dialog.show();
    }

    private static int searchRank(
            FeatureGateCatalog.Entry entry,
            String query,
            String[] queryTokens,
            boolean fuzzy
    ) {
        String key = entry.normalizedKey;
        String title = entry.normalizedTitle;
        if (key.equals(query)) return 0;
        if (title.equals(query)) return 1;
        if (key.startsWith(query)) return 2;
        if (title.startsWith(query)) return 3;
        if (key.contains(query)) return 4;
        if (title.contains(query)) return 5;
        if (!fuzzy) return -1;

        int score = 10;
        for (String queryToken : queryTokens) {
            int bestTokenScore = Integer.MAX_VALUE;
            for (String candidateToken : entry.searchTokens) {
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
        return FeatureGateCatalog.normalizeSearchText(text);
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

        int[] previousPrevious = new int[right.length() + 1];
        int[] previous = new int[right.length() + 1];
        int[] current = new int[right.length() + 1];
        for (int column = 0; column <= right.length(); column++) previous[column] = column;

        for (int row = 1; row <= left.length(); row++) {
            current[0] = row;
            int rowMinimum = current[0];
            for (int column = 1; column <= right.length(); column++) {
                int substitutionCost = left.charAt(row - 1) == right.charAt(column - 1) ? 0 : 1;
                current[column] = Math.min(
                        Math.min(current[column - 1] + 1, previous[column] + 1),
                        previous[column - 1] + substitutionCost
                );
                if (row > 1
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
            int[] reusable = previousPrevious;
            previousPrevious = previous;
            previous = current;
            current = reusable;
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
        private Map<String, FeatureGateLabStore.Rule> rules = Collections.emptyMap();

        /**
         * A delegate of the row's own keeps the list from installing the one that offers its
         * items' click, so the row offers both actions itself and hands each to the list's own
         * listeners. Set on every bind: the list's recycler clears a delegate from each row it
         * scraps, so one set only when the row was built was gone after the first scroll. Before
         * this, Switch Access and Voice Access could not open a gate, and Select did nothing.
         */
        private final View.AccessibilityDelegate rowActions = new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(
                    View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                // What the list's own delegate would have said: the row's place in the list.
                ListView gates = list;
                int position = gates == null ? android.widget.AdapterView.INVALID_POSITION
                        : gates.getPositionForView(host);
                if (position != android.widget.AdapterView.INVALID_POSITION) {
                    gates.onInitializeAccessibilityNodeInfoForItem(host, position, info);
                }
                info.setClickable(true);
                info.setLongClickable(true);
                info.addAction(android.view.accessibility.AccessibilityNodeInfo
                        .AccessibilityAction.ACTION_CLICK);
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_LONG_CLICK.getId(),
                        L10n.t(host.getContext(), "Select")));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle arguments) {
                boolean click = action == android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;
                boolean hold = action == android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK;
                ListView gates = list;
                if ((click || hold) && gates != null) {
                    int position = gates.getPositionForView(host);
                    if (position != android.widget.AdapterView.INVALID_POSITION) {
                        long id = gates.getItemIdAtPosition(position);
                        if (click) return gates.performItemClick(host, position, id);
                        android.widget.AdapterView.OnItemLongClickListener listener =
                                gates.getOnItemLongClickListener();
                        return listener != null && listener.onItemLongClick(gates, host, position, id);
                    }
                }
                return super.performAccessibilityAction(host, action, arguments);
            }
        };

        GateAdapter(Context context, List<FeatureGateCatalog.Entry> entries) {
            this.context = context;
            this.entries = entries;
        }

        void setRules(Map<String, FeatureGateLabStore.Rule> rules) {
            this.rules = rules;
        }

        @Override public int getCount() { return entries.size(); }
        @Override public Object getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return ruleIdentity(entries.get(position)).hashCode(); }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            RowHolder holder;
            if (convertView == null) {
                // Two columns work while the text is small enough for both to fit. Past that
                // the state column is squeezing three labels into a width that does not grow
                // with them, so the row stacks and each half gets the whole width.
                boolean stacked = context.getResources().getConfiguration().fontScale > 1.3f;

                LinearLayout row = new LinearLayout(context);
                row.setOrientation(stacked ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
                row.setGravity(stacked ? Gravity.START : Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(FeatureGateLabUi.dp(context, 84));
                row.setPadding(
                        FeatureGateLabUi.dp(context, 16),
                        FeatureGateLabUi.dp(context, 16),
                        FeatureGateLabUi.dp(context, 16),
                        FeatureGateLabUi.dp(context, 16)
                );

                // Ahead of the text, and only there while the row is one of the chosen ones. The
                // activated fill behind the row says the same thing in colour; this says it
                // again in a shape, for a reader who cannot tell the two fills apart.
                android.widget.ImageView chosenMark = new android.widget.ImageView(context);
                chosenMark.setImageDrawable(SettingsUi.checkMark(context));
                chosenMark.setVisibility(View.GONE);
                chosenMark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                LinearLayout.LayoutParams markParams = new LinearLayout.LayoutParams(
                        FeatureGateLabUi.dp(context, 24), FeatureGateLabUi.dp(context, 24));
                markParams.setMarginEnd(FeatureGateLabUi.dp(context, 8));
                row.addView(chosenMark, markParams);

                LinearLayout textColumn = new LinearLayout(context);
                textColumn.setOrientation(LinearLayout.VERTICAL);
                TextView title = FeatureGateLabUi.text(context, "", 16, SettingsUi.textPrimary(), Typeface.BOLD);
                title.setMaxLines(2);
                TextView key = FeatureGateLabUi.label(context, "");
                key.setTypeface(Typeface.MONOSPACE);
                key.setPadding(0, FeatureGateLabUi.dp(context, 6), 0, 0);
                key.setSingleLine(!stacked);
                key.setMaxLines(stacked ? 2 : 1);
                key.setEllipsize(stacked ? null : TextUtils.TruncateAt.MIDDLE);
                textColumn.addView(title, FeatureGateLabUi.matchWrap());
                textColumn.addView(key, FeatureGateLabUi.matchWrap());
                row.addView(textColumn, stacked
                        ? new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT)
                        : new LinearLayout.LayoutParams(
                                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

                int sideGravity = stacked ? Gravity.START : Gravity.END;
                LinearLayout stateColumn = new LinearLayout(context);
                stateColumn.setOrientation(LinearLayout.VERTICAL);
                stateColumn.setGravity(sideGravity | Gravity.CENTER_VERTICAL);
                TextView type = FeatureGateLabUi.text(context, "", SettingsUi.TEXT_LABEL, SettingsUi.textSecondary(), Typeface.BOLD);
                type.setGravity(sideGravity);
                TextView state = FeatureGateLabUi.text(context, "", SettingsUi.TEXT_LABEL, SettingsUi.textSecondary(), Typeface.NORMAL);
                state.setGravity(sideGravity);
                TextView value = FeatureGateLabUi.text(context, "", SettingsUi.TEXT_LABEL, SettingsUi.textPrimary(), Typeface.NORMAL);
                value.setGravity(sideGravity);
                // The value is the reason the row is worth reading. Given the width it wraps
                // rather than shortening; only past three lines is there nothing else to do.
                value.setSingleLine(false);
                value.setMaxLines(3);
                value.setEllipsize(TextUtils.TruncateAt.END);
                stateColumn.addView(type, FeatureGateLabUi.matchWrap());
                stateColumn.addView(value, FeatureGateLabUi.matchWrap());
                stateColumn.addView(state, FeatureGateLabUi.matchWrap());
                LinearLayout.LayoutParams stateParams = stacked
                        ? new LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT)
                        : new LinearLayout.LayoutParams(
                                FeatureGateLabUi.dp(context, 116),
                                ViewGroup.LayoutParams.WRAP_CONTENT);
                if (stacked) stateParams.setMargins(0, FeatureGateLabUi.dp(context, 8), 0, 0);
                row.addView(stateColumn, stateParams);

                holder = new RowHolder(title, key, type, value, state, chosenMark);
                row.setTag(holder);
                convertView = row;
            } else {
                holder = (RowHolder) convertView.getTag();
            }
            convertView.setAccessibilityDelegate(rowActions);

            FeatureGateCatalog.Entry entry = entries.get(position);
            FeatureGateLabStore.Rule rule = rules.get(ruleIdentity(entry));
            holder.title.setText(entry.title);
            holder.key.setText(entry.key);
            holder.type.setText(L10n.t(holder.type.getContext(), entry.shortSourceName()) + " " + entry.type);
            String shownValue;
            if (rule != null && rule.enabled && FeatureGateLabStore.masterEnabled()) {
                shownValue = L10n.f(getContext(), "Returns %1$s", rule.value);
            } else if (entry.loaded) {
                shownValue = L10n.f(getContext(), "Current %1$s", entry.currentValue);
            } else if (rule != null) {
                shownValue = L10n.f(getContext(), "Saved %1$s", rule.value);
            } else {
                shownValue = L10n.t(getContext(), "No current value");
            }
            holder.value.setText(shownValue);
            holder.value.setVisibility(entry.loaded || rule != null ? View.VISIBLE : View.GONE);
            SettingsUi.applyGroupedRow(convertView, position == 0, position == entries.size() - 1);
            // A chosen row is drawn as chosen, and says so to a screen reader further down: a
            // selection you cannot see is a selection you act on by accident.
            boolean chosen = selection.containsKey(entry.identity());
            convertView.setActivated(chosen);
            holder.chosenMark.setVisibility(chosen ? View.VISIBLE : View.GONE);
            // Every row remains actionable in selection mode. The activated surface and spoken
            // selected state carry the distinction without reducing 12sp labels below contrast.
            convertView.setAlpha(1f);

            String state;
            int stateColor;
            if (rule != null && rule.enabled && FeatureGateLabRuntime.isTriggered(entry.manager, entry.key, entry.type)) {
                state = L10n.t(getContext(), "TikTok read it");
                stateColor = SettingsUi.accent();
            } else if (rule != null && rule.enabled) {
                state = L10n.t(getContext(), "Override set, not read yet");
                stateColor = FeatureGateLabUi.warningColor(context);
            } else if (rule != null) {
                state = L10n.t(getContext(), "Saved, override off");
                stateColor = SettingsUi.textSecondary();
            } else {
                state = L10n.t(getContext(), entry.loaded ? "Seen this session" : "Not seen yet");
                // Both in the secondary colour. A gate TikTok has not read yet was painted in
                // the disabled colour, which is about 3.7:1 on the dark surface and 3.4:1 on
                // white, under the 4.5:1 floor for 12sp text, and the row it sits on is fully
                // tappable, so "disabled" was the wrong thing to say as well as unreadable.
                // The word carries the meaning; the disabled colour stays for disabled controls.
                stateColor = SettingsUi.textSecondary();
            }
            holder.state.setText(state);
            holder.state.setTextColor(stateColor);
            // The row read out as one sentence. The parts are the gate's own words, and
            // only the last piece is ours, so only that one is a key.
            String spoken = L10n.f(getContext(), "%1$s, %2$s, %3$s, %4$s, %5$s",
                    entry.title, entry.key, entry.type, shownValue, state);
            convertView.setContentDescription(chosen
                    ? L10n.f(getContext(), "%1$s, selected", spoken)
                    : spoken);
            return convertView;
        }
    }

    private static final class RowHolder {
        final TextView title;
        final TextView key;
        final TextView type;
        final TextView value;
        final TextView state;
        final android.widget.ImageView chosenMark;

        RowHolder(TextView title, TextView key, TextView type, TextView value, TextView state,
                android.widget.ImageView chosenMark) {
            this.title = title;
            this.key = key;
            this.type = type;
            this.value = value;
            this.state = state;
            this.chosenMark = chosenMark;
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
