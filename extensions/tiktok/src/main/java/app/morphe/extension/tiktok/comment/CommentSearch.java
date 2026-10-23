/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.comment;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * A box above the comments that narrows them to the ones you are looking for.
 *
 * <p>Nothing is taken out of TikTok's own list. A comment that does not match is collapsed
 * where it sits, the same way the inbox hides a row it was told to hide, so clearing the box
 * brings everything straight back and TikTok's paging, replies and counts never learn that
 * anything happened.
 */
public final class CommentSearch {
    /** Rows put back at the height they had, once the box is cleared again. */
    private static final Map<View, Integer> ORIGINAL_HEIGHTS = new WeakHashMap<>();
    /** What each bound row is showing, so typing can go over the rows already on screen. */
    private static final Map<View, Object> ROW_COMMENTS = new WeakHashMap<>();
    /** A reply control can also be collapsed by TikTok after all replies have been loaded. */
    private static final Map<View, Boolean> COLLAPSED_REPLY_ROWS = new WeakHashMap<>();
    /** Bound rows report attach and detach so the visible count follows scrolling. */
    private static final Map<View, Boolean> ROW_ATTACH_LISTENERS = new WeakHashMap<>();
    /** The list owns its decoration through its listener; neither map side retains that tree. */
    private static final Map<ViewGroup, WeakReference<SearchField>> DECORATED = new WeakHashMap<>();
    /**
     * Every comment a sheet has loaded, by its own id, one record per sheet.
     *
     * <p>The count used to be taken from the rows the list had attached, which is the handful on
     * screen. Scrolling changed it, so a reader watched "3 results" become "5 results" become
     * "2 results" while nothing was being searched, and the status line is a live region, so a
     * screen reader read every one of those out. A comment is counted once, when it comes into
     * view, and scrolling past it again does not count it twice or stop counting it.
     *
     * <p>Keyed by the list rather than cleared when the sheet changes. Comments arrive before
     * anything knows which sheet they belong to, so a clear on the way in threw away the ones
     * that had just landed. A sheet that goes away takes its record with it.
     *
     * <p>It is still only what has come into view. TikTok pages comments and nothing here sees
     * the ones it has not fetched, which is why the line says so rather than claiming a total.
     */
    private static final Map<ViewGroup, LoadedComments> LOADED_COMMENTS = new WeakHashMap<>();
    /** Enough to count honestly on any sheet a reader will scroll; past it the line says so. */
    static final int MAX_LOADED_COMMENTS = 2000;

    /** One sheet's comments and whether it stopped taking them. */
    private static final class LoadedComments {
        final LinkedHashMap<String, Object> byId = new LinkedHashMap<>();
        boolean truncated;
        /** Bumped whenever a comment is added or a rebind brings a newer model for one. */
        int changes;
    }

    /**
     * The action that clears the box for a screen reader.
     *
     * <p>Above {@code ACTION_TYPE_MASK}, so it cannot be read as one of the platform's own
     * action bits, and fixed rather than generated for the same reason the overlay's are.
     */
    private static final int ACTION_CLEAR_SEARCH = 0x0F0B0001;

    static final String FIELD_TAG = "comment_search_field";
    static final String STATUS_TAG = "comment_search_status";
    static final String EXPORT_TAG = "comment_export_row";
    static final String EXPORT_COUNT_TAG = "comment_export_count";

    private static final View.OnAttachStateChangeListener ROW_ATTACH_LISTENER =
            new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View view) {
                    scheduleCountRefresh();
                }

                @Override public void onViewDetachedFromWindow(View view) {
                    scheduleCountRefresh();
                }
            };

    /** How far above the list to look for something that stacks its children. */
    private static final int MAX_COLUMN_LEVELS = 4;

    private static volatile String query = "";
    private static WeakReference<ViewGroup> shown = new WeakReference<>(null);
    private static boolean warnedNoColumn;

    private CommentSearch() {}

    public static boolean enabled() {
        return Settings.COMMENT_SEARCH.get();
    }

    /** The settings page calls this after saving, including when the comment sheet is cached. */
    public static void onSettingChanged() {
        try {
            if (enabled()) {
                trackBoundRowAttachments();
                ViewGroup listView = shown.get();
                if (listView != null && listView.isAttachedToWindow()) {
                    // Rebuilt, so the export buttons follow their own switch.
                    for (WeakReference<SearchField> reference : new ArrayList<>(DECORATED.values())) {
                        SearchField field = reference.get();
                        if (field != null && field.listView == listView) field.remove(false);
                    }
                    addSearchField(listView);
                    narrowShownRows();
                }
                return;
            }
            setQuery("");
            for (WeakReference<SearchField> reference : new ArrayList<>(DECORATED.values())) {
                SearchField field = reference.get();
                if (field != null) field.remove(false);
            }
            for (View row : new ArrayList<>(ROW_ATTACH_LISTENERS.keySet())) {
                untrackRowAttachments(row);
            }
            // Retain bound models for re-enabling on the same sheet without another native bind.
            for (View row : ROW_COMMENTS.keySet()) setRowHidden(row, false);
        } catch (Throwable exception) {
            Logger.printException(() -> "Could not update comment search after its setting changed", exception);
        }
    }

    /** Restores listeners removed while search was off, including rows retained by a cached sheet. */
    private static void trackBoundRowAttachments() {
        for (View row : new ArrayList<>(ROW_COMMENTS.keySet())) trackRowAttachments(row);
    }

    /** What is in the box, lower cased once so every comparison does not have to be. */
    static String query() {
        return query;
    }

    static void setQuery(String text) {
        query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
    }

    /**
     * Whether a comment stays on screen. An empty box keeps everything, and a comment matches
     * on what it says or on who said it, by handle or by the name they show.
     */
    static boolean matches(Object comment, String wanted) {
        if (wanted.isEmpty()) return true;
        if (comment == null) return false;

        String text = Reflect.string(comment, "getText", "text");
        if (contains(text, wanted)) return true;

        Object user = Reflect.property(comment, "getUser", "user");
        return contains(Reflect.string(user, "getUniqueId", "uniqueId"), wanted)
                || contains(Reflect.string(user, "getNickname", "nickname"), wanted);
    }

    private static boolean contains(String haystack, String wanted) {
        return haystack != null && haystack.toLowerCase(Locale.ROOT).contains(wanted);
    }

    /** Called for each comment row as it is bound, with the comment that row is showing. */
    public static void onCellBound(View itemView, Object comment) {
        if (itemView == null) return;
        try {
            if (!enabled()) {
                untrackRowAttachments(itemView);
                if (ROW_COMMENTS.containsKey(itemView)) {
                    // Body binding leaves the root height that search collapsed in place.
                    setRowHidden(itemView, false);
                    ROW_COMMENTS.remove(itemView);
                    ORIGINAL_HEIGHTS.remove(itemView);
                }
                return;
            }
            trackRowAttachments(itemView);
            ROW_COMMENTS.put(itemView, comment);
            setRowHidden(itemView, !matches(comment, query));
            // A list binds a row before putting it in place, and detaches one it is about to
            // rebind, so the sheet is not reachable from the row while this runs. Waiting for
            // the row to be attached is the only time the list can be found.
            itemView.post(() -> decorate(itemView));
        } catch (Throwable exception) {
            Logger.printException(() -> "Could not narrow a comment row", exception);
        }
    }

    /** Called after the native reply control binds its parent and computes its render state. */
    public static void onReplyControlBound(View itemView, Object parentComment, int nativeState) {
        if (itemView == null) return;
        if (!enabled()) {
            untrackRowAttachments(itemView);
            if (COLLAPSED_REPLY_ROWS.remove(itemView) != null) {
                ROW_COMMENTS.remove(itemView);
                ORIGINAL_HEIGHTS.remove(itemView);
                // Q5 has already set the current native height, including zero for state4.
                // Release only our visibility; an old search height must not replace it.
                if (itemView.getVisibility() == View.GONE) itemView.setVisibility(View.VISIBLE);
            }
            return;
        }
        COLLAPSED_REPLY_ROWS.put(itemView, nativeState == 4);
        onCellBound(itemView, parentComment);
    }

    /** Runs once the bound row is in place, which is the first moment the list can be read. */
    private static void decorate(View itemView) {
        try {
            if (!enabled()) return;
            ViewParent parent = itemView.getParent();
            if (!(parent instanceof ViewGroup)) return;
            ViewGroup listView = (ViewGroup) parent;
            if (shown.get() != listView) {
                // A different sheet. Whatever was typed into the last one was about that
                // video's comments, so it does not follow the reader to this one.
                SearchField previous = searchFieldFor(shown.get());
                shown = new WeakReference<>(listView);
                setQuery("");
                if (previous != null) previous.clearForSheetChange();
            }
            addSearchField(listView);
            narrowShownRows();
        } catch (Throwable exception) {
            Logger.printException(() -> "Could not put a box above the comments", exception);
        }
    }

    /**
     * Puts the box above the comments, once per list. Something above the list has to lay its
     * children out one under another for a box added there to land above the list rather than
     * across it, so the nearest few ancestors are tried and anything else is left alone.
     */
    private static void addSearchField(ViewGroup listView) {
        View anchor = listView;
        ViewParent parent = listView.getParent();
        for (int level = 0; level < MAX_COLUMN_LEVELS && parent instanceof ViewGroup; level++) {
            if (parent instanceof LinearLayout
                    && ((LinearLayout) parent).getOrientation() == LinearLayout.VERTICAL) {
                insertBox((LinearLayout) parent, anchor, listView);
                return;
            }
            anchor = (View) parent;
            parent = parent.getParent();
        }
        if (!warnedNoColumn) {
            warnedNoColumn = true;
            ViewParent nearest = listView.getParent();
            String name = nearest == null ? "none" : nearest.getClass().getName();
            Logger.printInfo(() -> "Nothing above the comment list stacks its children, so the "
                    + "search box has nowhere to go. Nearest parent: " + name);
        }
    }

    /**
     * Whether the sheet this box is going into is dark.
     *
     * <p>Read from the context the sheet itself was built with, not from the shared dark mode
     * flag: that flag is only ever written when Hushfeed's own settings screen opens, so in a
     * session that never opened it the answer comes from the system configuration instead,
     * which does not carry the night mode TikTok applied to itself. A white card in a black
     * comment sheet is the visible version of that.
     */
    private static boolean isDarkSheet(Context context) {
        return SettingsUi.isDarkContext(context);
    }

    /**
     * The field's background, which is also the only thing that shows it has focus. A plain
     * drawable would look the same focused and not, and replacing the platform background is
     * what took the underline away in the first place.
     */
    private static StateListDrawable fieldBackground(Context context, boolean dark) {
        float radius = SettingsUi.dp(context, SettingsUi.RADIUS_FIELD);
        int stroke = Math.max(1, Math.round(context.getResources().getDisplayMetrics().density));

        // The theme is worked out locally, because the comment sheet is drawn in TikTok's theme
        // rather than the system's, and then the matching palette value is asked for by name.
        // These eight colours used to be hex literals copied out of SettingsUi, which is the
        // same palette maintained in two places and free to drift in one of them.
        // Focus lifts the field off the sheet in dark, where there is somewhere to lift to. In
        // light the resting fill is already the brightest surface, so the accent ring does that
        // job alone, which is what the two hex literals here used to say.
        GradientDrawable focused = new GradientDrawable();
        focused.setColor(dark ? SettingsUi.liftedSurfaceOn(true) : SettingsUi.surfaceOn(false));
        focused.setCornerRadius(radius);
        focused.setStroke(stroke * 2, SettingsUi.accentOn(dark));

        GradientDrawable resting = new GradientDrawable();
        resting.setColor(SettingsUi.surfaceOn(dark));
        resting.setCornerRadius(radius);
        resting.setStroke(stroke, SettingsUi.borderOn(dark));

        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused}, focused);
        states.addState(new int[]{}, resting);
        return states;
    }

    /** The count of what has loaded and the two Export buttons, under the box and its status line. */
    private static LinearLayout exportRow(Context context, boolean dark, ViewGroup listView,
            int padding, int sideMargin) {
        LinearLayout row = new LinearLayout(context);
        row.setTag(EXPORT_TAG);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(sideMargin, 0, sideMargin, SettingsUi.dp(context, 8));
        row.setLayoutParams(rowParams);

        TextView count = new TextView(context);
        count.setTag(EXPORT_COUNT_TAG);
        count.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
        count.setTextColor(SettingsUi.textSecondaryOn(dark));
        count.setPadding(padding, 0, padding, 0);
        row.addView(count, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(exportButton(context, dark, "Export CSV", listView, CommentExport.Format.CSV, padding));
        row.addView(exportButton(context, dark, "Export JSON", listView, CommentExport.Format.JSON, padding));
        return row;
    }

    private static TextView exportButton(Context context, boolean dark, String label, ViewGroup listView,
            CommentExport.Format format, int padding) {
        TextView button = new TextView(context);
        button.setText(L10n.t(context, label));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
        button.setTextColor(SettingsUi.accentOn(dark));
        button.setGravity(Gravity.CENTER);
        button.setPadding(padding, padding, padding, padding);
        button.setMinimumHeight(SettingsUi.dp(context, 48));
        button.setMinimumWidth(SettingsUi.dp(context, 48));
        button.setClickable(true);
        button.setFocusable(true);
        button.setOnClickListener(view -> {
            LoadedComments loaded = LOADED_COMMENTS.get(listView);
            android.app.Activity activity = activityOf(view.getContext());
            CommentExport.begin(activity, format, loaded == null ? null : loaded.byId.values(),
                    loaded != null && loaded.truncated);
        });
        return button;
    }

    private static android.app.Activity activityOf(Context context) {
        while (context instanceof android.content.ContextWrapper) {
            if (context instanceof android.app.Activity) return (android.app.Activity) context;
            context = ((android.content.ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** Builds the box and puts it in {@code column}, directly above whatever holds the list. */
    private static void insertBox(LinearLayout column, View anchor, ViewGroup listView) {
        WeakReference<SearchField> existing = DECORATED.get(column);
        if (existing != null && existing.get() != null) return;

        Context context = column.getContext();
        EditText box = new EditText(context);
        box.setTag(FIELD_TAG);
        box.setHint(L10n.t(context, "Search these comments"));
        // No content description on a search box. On an editable view it replaces what was
        // typed in the announcement, so "cats" came back as the label. The hint names it.
        box.setSingleLine(true);
        // A single line field with a newline key is a dead end; this is a search.
        box.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        box.setInputType(InputType.TYPE_CLASS_TEXT);
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
        boolean dark = isDarkSheet(context);
        box.setTextColor(SettingsUi.textPrimaryOn(dark));
        box.setHintTextColor(SettingsUi.textSecondaryOn(dark));
        // A transparent background takes the focus underline with it, which left nothing
        // saying this was a field at all, so the border does that job instead.
        box.setBackground(fieldBackground(context, dark));
        int padding = SettingsUi.dp(context, 12);
        box.setPadding(padding, padding, padding, padding);
        // A minimum rather than a height: 48dp is the touch target, but at a large font scale
        // the text needs more than that and a fixed height would cut the letters off.
        box.setMinimumHeight(SettingsUi.dp(context, 48));
        int sideMargin = SettingsUi.dp(context, 16);
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        boxParams.setMargins(sideMargin, SettingsUi.dp(context, 8), sideMargin, 0);
        box.setLayoutParams(boxParams);
        TextView status = SettingsUi.resultCount(context, STATUS_TAG);
        status.setTextColor(SettingsUi.textSecondaryOn(dark));
        status.setFocusable(false);
        status.setClickable(false);
        status.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        status.setVisibility(View.GONE);
        status.setPadding(padding, 0, padding, SettingsUi.dp(context, 4));
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParams.setMargins(sideMargin, 0, sideMargin, SettingsUi.dp(context, 8));
        status.setLayoutParams(statusParams);
        LinearLayout exports = CommentExport.enabled()
                ? exportRow(context, dark, listView, padding, sideMargin) : null;
        box.setText(query);
        box.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable typed) {
                if (!enabled()) return;
                setQuery(typed == null ? "" : typed.toString());
                narrowShownRows();
            }
        });
        box.setOnEditorActionListener((view, actionId, event) -> {
            if (actionId != EditorInfo.IME_ACTION_SEARCH) return false;
            // Filtering already happened as it was typed, so the key's job is to get the
            // keyboard out of the way of the comments it just narrowed down.
            InputMethodManager keyboard = (InputMethodManager)
                    context.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.hideSoftInputFromWindow(view.getWindowToken(), 0);
            view.clearFocus();
            return true;
        });
        int insertionIndex = column.indexOfChild(anchor);
        column.addView(box, insertionIndex);
        column.addView(status, insertionIndex + 1);
        if (exports != null) column.addView(exports, insertionIndex + 2);
        // Only once it is really in. Marking the column first would blacklist it for good if
        // anything above threw, and the sheet would never get a box again.
        SearchField field = new SearchField(column, listView, box, status,
                new SettingsUi.ClearGlyphDrawable(context, SettingsUi.textSecondaryOn(dark)), exports);
        field.wireClearControl();
        DECORATED.put(column, new WeakReference<>(field));

        // The column can be further up than the sheet and outlive it, so the box leaves with
        // the list it belongs to rather than being left over the feed.
        listView.addOnAttachStateChangeListener(field);
    }

    private static final class SearchField implements View.OnAttachStateChangeListener {
        private final LinearLayout column;
        private final ViewGroup listView;
        private final EditText box;
        private final TextView status;
        private final Drawable clearIcon;
        /** The export buttons, or null while their switch is off. */
        private final LinearLayout exports;

        SearchField(LinearLayout column, ViewGroup listView, EditText box, TextView status,
                Drawable clearIcon, LinearLayout exports) {
            this.exports = exports;
            this.column = column;
            this.listView = listView;
            this.box = box;
            this.status = status;
            this.clearIcon = clearIcon;
        }

        /**
         * The X at the end of the box, for a finger and for a screen reader.
         *
         * <p>A compound drawable is not a view, so nothing can focus it. The touch listener
         * gives it to a finger and the action on the box itself gives it to everyone else,
         * which is the whole control in one node rather than a second stop in the traversal
         * that reads as another field.
         */
        void wireClearControl() {
            box.setOnTouchListener((view, event) -> {
                if (event.getActionMasked() != MotionEvent.ACTION_UP) return false;
                if (!touchedClearControl(event.getX())) return false;
                clearQuery();
                return true;
            });
            box.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public void onInitializeAccessibilityNodeInfo(
                        View host, AccessibilityNodeInfo info) {
                    super.onInitializeAccessibilityNodeInfo(host, info);
                    if (box.length() == 0) return;
                    info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                            ACTION_CLEAR_SEARCH, L10n.t(host.getContext(), "Clear the search")));
                }

                @Override public boolean performAccessibilityAction(
                        View host, int action, Bundle arguments) {
                    if (action == ACTION_CLEAR_SEARCH) {
                        clearQuery();
                        return true;
                    }
                    return super.performAccessibilityAction(host, action, arguments);
                }
            });
        }

        /** Whether a release landed on the X rather than in the text. */
        private boolean touchedClearControl(float x) {
            Drawable clear = box.getCompoundDrawablesRelative()[2];
            if (clear == null) return false;
            int reach = clear.getIntrinsicWidth() + box.getCompoundDrawablePadding();
            return box.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL
                    ? x <= box.getPaddingStart() + reach
                    : x >= box.getWidth() - box.getPaddingEnd() - reach;
        }

        private void clearQuery() {
            box.setText("");
            setQuery("");
            narrowShownRows();
        }

        void remove(boolean detaching) {
            // Keep one listener while the cached list is detached. It is owned by the list and
            // can rebuild the decoration when that same native tree returns without another bind.
            if (!detaching) listView.removeOnAttachStateChangeListener(this);
            WeakReference<SearchField> current = DECORATED.get(column);
            if (current != null && current.get() == this) DECORATED.remove(column);
            // Detach runs during native child removal; don't alter a second child mid-dispatch.
            if (detaching) {
                column.post(() -> {
                    column.removeView(box);
                    column.removeView(status);
                    if (exports != null) column.removeView(exports);
                });
            } else {
                column.removeView(box);
                column.removeView(status);
                if (exports != null) column.removeView(exports);
            }
        }

        /** The loaded set's change count the number beside the buttons was last worked out from. */
        private int countedChanges = -1;
        private long countedAt;

        /** What the buttons would write: every comment and reply the sheet has loaded. */
        void updateExportCount(LoadedComments loaded) {
            if (exports == null) return;
            View view = exports.findViewWithTag(EXPORT_COUNT_TAG);
            if (!(view instanceof TextView)) return;
            // Every row bind lands here, and counting reads each loaded comment through
            // reflection, so it runs when the set changed. A model can also grow its reply
            // previews in place, which no change count sees, so it runs once a second anyway.
            int changes = loaded == null ? 0 : loaded.changes;
            long now = SystemClock.uptimeMillis();
            if (changes == countedChanges && now - countedAt < 1000L) return;
            countedChanges = changes;
            countedAt = now;
            int total = loaded == null ? 0 : CommentExport.count(loaded.byId.values());
            Context context = view.getContext();
            SettingsUi.setTextIfChanged((TextView) view, L10n.quantity(context, total,
                    "1 comment or reply loaded", "%1$d comments and replies loaded"));
        }

        void updateResult(String wanted, Matches matches) {
            showClearControl(!wanted.isEmpty());
            if (wanted.isEmpty()) {
                clearStatus();
                return;
            }
            status.setVisibility(View.VISIBLE);
            Context context = status.getContext();
            String next;
            if (matches.found == 0) {
                // It used to tell the reader to clear the search, with nothing on the screen
                // that could. The box now carries a clear control, so the sentence points at
                // something that exists.
                next = L10n.t(context, "No matching comments. Try a different word, or clear "
                        + "the search with the X in the box.");
            } else if (matches.truncated) {
                next = L10n.f(context, "%1$d results so far, and more not counted", matches.found);
            } else {
                // "So far": TikTok pages comments and this has only seen the ones it loaded.
                // The old line said "3 results" about the three rows that happened to be on
                // screen, and changed as the reader scrolled past them.
                next = L10n.quantity(context, matches.found, "1 result so far", "%1$d results so far");
            }
            if (!TextUtils.equals(status.getText(), next)) status.setText(next);
        }

        /** The X inside the box, which only exists while there is something to clear. */
        private void showClearControl(boolean wanted) {
            Drawable clear = wanted ? clearIcon : null;
            Drawable[] current = box.getCompoundDrawablesRelative();
            if (current[2] == clear) return;
            box.setCompoundDrawablesRelativeWithIntrinsicBounds(null, null, clear, null);
        }

        void clearForSheetChange() {
            if (box.length() != 0) box.setText("");
            clearStatus();
        }

        private void clearStatus() {
            if (status.length() != 0) status.setText("");
            if (status.getVisibility() != View.GONE) status.setVisibility(View.GONE);
        }

        @Override public void onViewAttachedToWindow(View view) {
            // Adding siblings while Android dispatches attach can upset the native parent. Post the
            // remount, then replace this detached-field listener with the newly created field's.
            listView.post(() -> {
                if (!listView.isAttachedToWindow()) return;
                listView.removeOnAttachStateChangeListener(this);
                if (!enabled()) return;
                trackBoundRowAttachments();
                addSearchField(listView);
                narrowShownRows();
            });
        }

        @Override public void onViewDetachedFromWindow(View view) {
            remove(true);
            if (shown.get() == listView) setQuery("");
        }
    }

    /**
     * Goes over the rows already on screen. Rows scrolled to afterwards are dealt with as
     * they bind, and a row the list recycles onto a different comment is put right by the
     * bind that recycled it.
     */
    static void narrowShownRows() {
        ViewGroup listView = shown.get();
        if (listView == null) return;
        boolean filtering = enabled();
        String wanted = query;
        for (int index = 0; index < listView.getChildCount(); index++) {
            View row = listView.getChildAt(index);
            if (!ROW_COMMENTS.containsKey(row)) continue;
            Object comment = ROW_COMMENTS.get(row);
            rememberLoadedComment(listView, comment);
            boolean rowMatches = !filtering || matches(comment, wanted);
            setRowHidden(row, filtering && !rowMatches);
        }
        SearchField field = searchFieldFor(listView);
        if (field != null) {
            field.updateResult(filtering ? wanted : "", countLoadedMatches(listView, wanted));
            LoadedComments loaded = LOADED_COMMENTS.get(listView);
            field.updateExportCount(loaded);
        }
    }

    /** Adds a comment to what this sheet has loaded, keyed by its own id so a rebind is free. */
    private static void rememberLoadedComment(ViewGroup listView, Object comment) {
        String id = commentId(comment);
        if (id == null) return;
        LoadedComments loaded = LOADED_COMMENTS.get(listView);
        if (loaded == null) {
            loaded = new LoadedComments();
            LOADED_COMMENTS.put(listView, loaded);
        }
        if (loaded.byId.containsKey(id) || loaded.byId.size() < MAX_LOADED_COMMENTS) {
            if (loaded.byId.put(id, comment) != comment) loaded.changes++;
        } else {
            loaded.truncated = true;
        }
    }

    /**
     * A comment's own id. Without one it cannot be counted, because the alternative is counting
     * the same comment again every time the list rebinds a row onto it.
     */
    private static String commentId(Object comment) {
        if (comment == null) return null;
        String cid = Reflect.string(comment, "getCid", "cid");
        if (cid != null && !cid.isEmpty()) return cid;
        // A build that renamed the id. Object identity is the conservative answer: TikTok's
        // list holds one model per comment, so rebinding a recycled row onto the same comment
        // finds the same key, and the worst a rebuilt model costs is one comment counted twice.
        return "model:" + System.identityHashCode(comment);
    }

    /** How many of the comments loaded on this sheet match, and whether more are uncounted. */
    private static Matches countLoadedMatches(ViewGroup listView, String wanted) {
        LoadedComments loaded = LOADED_COMMENTS.get(listView);
        if (loaded == null) return new Matches(0, false);
        int found = 0;
        for (Object comment : loaded.byId.values()) {
            if (matches(comment, wanted)) found++;
        }
        return new Matches(found, loaded.truncated);
    }

    /** A count and whether it stopped counting, which is a different thing from a total. */
    static final class Matches {
        final int found;
        final boolean truncated;

        Matches(int found, boolean truncated) {
            this.found = found;
            this.truncated = truncated;
        }
    }

    private static SearchField searchFieldFor(ViewGroup listView) {
        if (listView == null) return null;
        for (WeakReference<SearchField> reference : new ArrayList<>(DECORATED.values())) {
            SearchField field = reference.get();
            if (field != null && field.listView == listView) return field;
        }
        return null;
    }

    private static void trackRowAttachments(View row) {
        if (ROW_ATTACH_LISTENERS.put(row, Boolean.TRUE) == null) {
            row.addOnAttachStateChangeListener(ROW_ATTACH_LISTENER);
        }
    }

    private static void untrackRowAttachments(View row) {
        if (ROW_ATTACH_LISTENERS.remove(row) != null) {
            row.removeOnAttachStateChangeListener(ROW_ATTACH_LISTENER);
        }
    }

    private static void scheduleCountRefresh() {
        ViewGroup listView = shown.get();
        if (listView != null) listView.post(CommentSearch::narrowShownRows);
    }

    /**
     * A scrolling list measures its children itself and does not honour {@code GONE}, so a
     * hidden row also needs a zero height to collapse. Nothing is written when the row is
     * already in the wanted state.
     */
    private static void setRowHidden(View row, boolean hidden) {
        hidden |= Boolean.TRUE.equals(COLLAPSED_REPLY_ROWS.get(row));
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params == null) {
            int wanted = hidden ? View.GONE : View.VISIBLE;
            if (row.getVisibility() != wanted) row.setVisibility(wanted);
            return;
        }

        Integer original = ORIGINAL_HEIGHTS.get(row);
        if (original == null && params.height != 0) {
            original = params.height;
            ORIGINAL_HEIGHTS.put(row, original);
        }

        if (hidden) {
            if (row.getVisibility() != View.GONE || params.height != 0) {
                row.setVisibility(View.GONE);
                params.height = 0;
                row.setLayoutParams(params);
            }
        } else {
            int restored = original != null ? original : ViewGroup.LayoutParams.WRAP_CONTENT;
            if (row.getVisibility() != View.VISIBLE || params.height != restored) {
                row.setVisibility(View.VISIBLE);
                params.height = restored;
                row.setLayoutParams(params);
            }
        }
    }
}
