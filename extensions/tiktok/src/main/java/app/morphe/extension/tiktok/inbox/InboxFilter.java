/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.GlobalLayoutHook;
import app.morphe.extension.shared.ResourceIdCache;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.navigation.FrontWindow;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Hides individual rows and header controls on TikTok's Inbox tab, and adds a Clear all
 * control to the Suggested accounts section.
 *
 * TikTok builds the Inbox as one RecyclerView, so rows are filtered as they lay out
 * rather than by editing TikTok's data. Every pass re-decides every row from its current
 * content, which keeps recycled views correct: a hidden row that gets reused for
 * something else is shown again on the next pass.
 *
 * Resource ids are TikTok 47.1.4's, the declared target, the same views 47.0.3's live Inbox and
 * precompiled row inflaters were read off. No older build's name is kept as a fallback: TikTok
 * hands the same short names out again on every build, and an older build's name is some
 * other view on this one.
 * <pre>
 *   opi        bottom navigation, the Inbox tab (looked up by FeedVisibility)
 *   l91        the Inbox RecyclerView
 *   v1m        a system notice row, with its title in brl
 *   w54        the root of a chat row: a single chat, a group chat or the message requests row
 *   tv_request_unread_count  the request count, which only the message requests row has
 *   oom        the root of a "Say hi to" row, an account suggested with Follow and a wave
 *   user_name  the title of a chat row or a "Say hi to" row
 *   wuh        a title inside the horizontal stories tray
 *   q6c        the suggested accounts section header, holding u4x
 *   u4x        the suggested accounts section title
 *   fyd        remove an account from suggested accounts
 *   fhi        header, add people
 *   kqj        header, search
 *   l93        header, activity status
 * </pre>
 */
public final class InboxFilter {
    private static final String[] LIST_IDS = {"47.4.3:l92"};
    private static final String[] SYSTEM_ROW_IDS = {"47.4.3:uyv"};
    private static final String[] CHAT_ROW_IDS = {"47.4.3:w24"};
    private static final String[] MESSAGE_REQUESTS_IDS = {"tv_request_unread_count"};
    private static final String[] SAY_HI_ROW_IDS = {"47.4.3:omm"};
    private static final String[] SYSTEM_ROW_TITLE_IDS = {"47.4.3:brh"};
    private static final String[] USER_ROW_TITLE_IDS = {"user_name"};
    private static final String[] STORIES_TITLE_IDS = {"47.4.3:wrv"};
    private static final String[] SUGGESTED_HEADER_IDS = {"47.4.3:q4f"};
    private static final String[] SUGGESTED_TITLE_IDS = {"47.4.3:u2n"};
    private static final String[] SUGGESTED_REMOVE_IDS = {"47.4.3:fy6"};
    private static final String[] HEADER_ADD_PEOPLE_IDS = {"47.4.3:fhd"};
    private static final String[] HEADER_SEARCH_IDS = {"47.4.3:kp7"};
    private static final String[] HEADER_ACTIVITY_STATUS_IDS = {"47.4.3:l7j"};

    /** One dismissal at a time, so bulk clearing does not hammer TikTok's API. */
    private static final long DISMISS_INTERVAL_MS = 300L;

    /** Stops a runaway loop if TikTok keeps refilling the list while clearing. */
    private static final int MAX_CLEARED_PER_RUN = 60;
    /** The busy look. The control has to stay pressable, so it is dimmed rather than disabled. */
    private static final float BUSY_ALPHA = 0.6f;

    /** Id for the injected Clear all control, so it is only added once. */
    private static final int CLEAR_ALL_VIEW_ID = View.generateViewId();

    /**
     * Resource ids resolved once. The filter runs on every layout pass of the whole
     * activity, and a resource name lookup is a string search through the resource
     * table, so without this the feed would pay for a dozen lookups per frame.
     */
    private static final ResourceIdCache RESOURCE_IDS = new ResourceIdCache();

    /**
     * Accounts dismissed in the current Clear all run, keyed by the remove button's
     * description, which names the account. Keying on the view would skip an account
     * whose row reused a view already clicked, since the list recycles views as rows go.
     */
    private static final Set<String> DISMISSED_LABELS = new HashSet<>();

    /** Clicks and delayed steps run on the main thread and share one dismissal run. */
    private static boolean clearingSuggested;
    /** A tap on the control while a run is going: honoured at the run's next step. */
    private static boolean stopRequested;
    /** How many the current run has dismissed, for a control rebuilt while it is going. */
    private static int dismissedSoFar;

    /**
     * The injected Clear all control, so a run that takes about eighteen seconds can say so on
     * the control the reader pressed rather than only in a toast at the end. Weak because the
     * control belongs to TikTok's header and dies with the Inbox; a new Inbox installs its own
     * and replaces this.
     */
    private static WeakReference<TextView> clearAllControl;

    /** Original row heights, so a hidden row can be restored exactly. */
    private static final WeakHashMap<View, Integer> ORIGINAL_HEIGHTS = new WeakHashMap<>();
    private static final WeakHashMap<View, BooleanSetting> SYSTEM_ROWS = new WeakHashMap<>();

    /** Rows recognised as the stories tray, retained until a recycled row changes shape. */
    private static final WeakHashMap<View, Boolean> STORY_ROWS = new WeakHashMap<>();

    /** Native MultiBaseVH binding supplies category identity before localized text is laid out. */
    public static void onRowBound(Object holder, int position, Object model) {
        Object item = app.morphe.extension.tiktok.blockauthor.Reflect.readField(holder, "itemView");
        if (!(item instanceof View)) return;
        View row = (View) item;
        if (SYSTEM_ROWS.remove(row) != null) setRowHidden(row, false);
        BooleanSetting setting = InboxModelFilter.settingFor(model);
        if (setting != null) {
            SYSTEM_ROWS.put(row, setting);
            setRowHidden(row, setting.get());
        }
    }

    /** Called after native normal/payload notification binds, with the current PowerCell item. */
    public static void onNotificationRowBound(Object holder) {
        InboxQueryOwners.bound(holder);
    }

    /** Receives the native getter's result, without asking a dormant widget to create a VM. */
    public static void onNotificationVmObserved(Object widget, Object vm) {
        InboxQueryOwners.observedVm(widget, vm);
    }

    public static void onLoadedFragmentBound(Object fragment) {
        try { InboxQueryOwners.fragmentBound(fragment); }
        catch (Throwable failure) { InboxQueryNative.contain("loaded fragment", failure); }
    }

    public static java.util.List<?> onLoadedPublication(Object state, java.util.List<?> models,
            boolean first, boolean second, Runnable completion, int mask) {
        return InboxQueryNative.publication(state, models, first, second, completion, mask);
    }

    public static void onLoadedStubPublication(Object stub, Object wrapper, Object metadata) {
        InboxQueryNative.stubPublication(stub, wrapper, metadata);
    }

    public static void onLoadedDiffSubmitted(Object diff, java.util.List<?> oldFiltered,
            java.util.List<?> old, java.util.List<?> aggregate, Object metadata) {
        InboxQueryNative.submitted(diff, oldFiltered, old, aggregate, metadata);
    }

    public static void onLoadedDiffAccepted(Object diff, Object metadata) { InboxQueryNative.accepted(diff, metadata); }

    public static void onLoadedIndexedCallback(Object closure, Object helper, java.util.List<?> original, Object pair) {
        InboxQueryNative.indexed(closure, helper, original, pair);
    }

    public static java.util.List<?> onLoadedIndexedList(Object fragment, java.util.List<?> original) {
        return InboxQueryNative.indexedList(fragment, original);
    }

    public static boolean allowLoadedVisibleIndices(Object fragment, boolean loadingRow) {
        return InboxQueryNative.visibleIndices(fragment, loadingRow);
    }

    public static boolean allowLoadedNotificationClick(Object holder) { return InboxQueryNative.click(holder); }
    public static boolean allowLoadedRefresh(Object spring, boolean starting) { return InboxQueryNative.refresh(spring, starting); }
    public static boolean allowLoadedManualRefresh(Object spring) { return InboxQueryNative.refresh(spring, true); }
    // TikTok's paging frames call these around its own requests. A throw in Hushfeed's part ends
    // the search and lets the request go ahead as it would without Hushfeed.
    public static boolean allowLoadedRequest(Object target) {
        try { InboxQueryNative.probe("loaded request"); return InboxNativeRequests.allowed(target); }
        catch (Throwable failure) { InboxQueryNative.contain("loaded request", failure); return true; }
    }
    public static boolean beginLoadedRequest(Object target) {
        try { InboxQueryNative.probe("loaded request start"); return InboxNativeRequests.begin(target); }
        catch (Throwable failure) { InboxQueryNative.contain("loaded request start", failure); return true; }
    }
    public static void returnedLoadedRequest(Object target) {
        try { InboxQueryNative.probe("loaded request return"); InboxNativeRequests.returned(target); }
        catch (Throwable failure) { InboxQueryNative.contain("loaded request return", failure); }
    }
    public static void onLoadedRequestJob(Object target, Object job) {
        try { InboxQueryNative.probe("loaded request job"); InboxNativeRequests.launched(target, job); }
        catch (Throwable failure) { InboxQueryNative.contain("loaded request job", failure); }
    }
    public static boolean beginLoadedClosureRequest(Object closure) {
        try { InboxQueryNative.probe("loaded request start"); return InboxNativeRequests.begin(closureTarget(closure)); }
        catch (Throwable failure) { InboxQueryNative.contain("loaded request start", failure); return true; }
    }
    public static void returnedLoadedClosureRequest(Object closure) {
        try { InboxQueryNative.probe("loaded request return"); InboxNativeRequests.returned(closureTarget(closure)); }
        catch (Throwable failure) { InboxQueryNative.contain("loaded request return", failure); }
    }
    public static void onLoadedClosureRequestJob(Object closure, Object job) {
        try { InboxQueryNative.probe("loaded request job"); InboxNativeRequests.launched(closureTarget(closure), job); }
        catch (Throwable failure) { InboxQueryNative.contain("loaded request job", failure); }
    }

    private static Object closureTarget(Object closure) {
        return app.morphe.extension.tiktok.blockauthor.Reflect.readField(closure, "l0");
    }

    private static WeakReference<Activity> activityReference = new WeakReference<>(null);
    private static final GlobalLayoutHook LAYOUT_HOOK = new GlobalLayoutHook();

    /**
     * The main activity in front. TikTok's launcher entry is a second MainActivity under the
     * feed's, and changing TikTok's appearance recreates it last, so the newest one created is
     * the buried copy: the filter followed it and the Inbox in front went unfiltered until TikTok
     * restarted. The filter moves to whichever main activity resumes.
     */
    private static final FrontWindow FRONT = new FrontWindow(false, new FrontWindow.Listener() {
        @Override public void onFront(Activity activity) {
            installNow(activity);
        }

        @Override public void onGone(Activity activity) {
        }
    });

    private InboxFilter() {
    }

    /**
     * Called from the patched {@code MainActivity.onCreate}, before the activity's own
     * onCreate body has run. The work is posted so it happens once the window content
     * exists, whatever TikTok does in between. A copy recreated behind the one in front runs it
     * after it has stopped again, and leaves the filter where it is.
     *
     * @param activity the TikTok main activity
     */
    public static void install(Activity activity) {
        if (activity == null) {
            return;
        }
        FRONT.add(activity);
        Utils.runOnMainThread(() -> {
            if (FRONT.mayTake(activity)) installNow(activity);
        });
    }

    private static void installNow(Activity activity) {
        try {
            if (activity.isFinishing()) {
                LAYOUT_HOOK.detach();
                return;
            }

            ViewGroup root = activity.findViewById(android.R.id.content);
            if (root == null) {
                LAYOUT_HOOK.detach();
                Logger.printInfo(() -> "Inbox filter found no content view to watch");
                return;
            }

            boolean installed = LAYOUT_HOOK.install(root, InboxFilter::apply);
            activityReference = new WeakReference<>(activity);
            if (installed) {
                Logger.printDebug(() -> "Inbox filter installed");
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not install the inbox filter", ex);
        }
    }

    private static void apply() {
        try {
            Activity activity = activityReference.get();
            if (activity == null) {
                LAYOUT_HOOK.detach();
                return;
            }
            if (activity.isFinishing()) {
                LAYOUT_HOOK.detach();
                return;
            }

            // Cheap gate: do nothing unless the Inbox tab is the one on show.
            View inboxTab = FeedVisibility.inboxTabView(activity);
            if (inboxTab == null || !inboxTab.isSelected()) {
                return;
            }

            boolean fullFilter = SettingsStatus.inboxFilterEnabled;
            if (fullFilter) {
                applyHeader(activity);
            }

            View list = findRequired(activity, "list", LIST_IDS);
            if (!(list instanceof ViewGroup)) {
                return;
            }

            ViewGroup rows = (ViewGroup) list;
            for (int index = 0; index < rows.getChildCount(); index++) {
                View row = rows.getChildAt(index);
                if (fullFilter) {
                    setRowHidden(row, shouldHideRow(activity, row));
                } else {
                    applyStoriesOnly(activity, row);
                }
            }

            if (fullFilter) {
                addClearAllControl(activity);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Inbox filter failed", ex);
        }
    }

    private static void applyHeader(Activity activity) {
        setHidden(findRequired(activity, "add people", HEADER_ADD_PEOPLE_IDS),
                Settings.HIDE_INBOX_ADD_PEOPLE.get());
        setHidden(findRequired(activity, "search", HEADER_SEARCH_IDS),
                Settings.HIDE_INBOX_SEARCH.get());
        setHidden(findRequired(activity, "activity status", HEADER_ACTIVITY_STATUS_IDS),
                Settings.HIDE_INBOX_ACTIVITY_STATUS.get());
    }

    /**
     * Runs when only the dedicated stories patch is installed. Unknown rows and controls are
     * never written, while a row previously recognised as the tray is restored if RecyclerView
     * reuses it for another item.
     */
    private static void applyStoriesOnly(Activity activity, View row) {
        boolean stories = findWithin(activity, row, STORIES_TITLE_IDS) != null;
        boolean wasStories = STORY_ROWS.remove(row) != null;
        if (stories) {
            STORY_ROWS.put(row, Boolean.TRUE);
            setRowHidden(row, Settings.HIDE_INBOX_STORIES.get());
        } else if (wasStories) {
            setRowHidden(row, false);
        }
    }

    private static boolean shouldHideRow(Activity activity, View row) {
        // The stories tray is the row that holds the horizontal avatar titles.
        if (findWithin(activity, row, STORIES_TITLE_IDS) != null) {
            return Settings.HIDE_INBOX_STORIES.get();
        }

        // Suggested accounts is a section header followed by one row per account. The
        // header carries the section title, and each account row carries the remove
        // button. Neither uses the row title ids, so both are matched on their own marker.
        // A "Say hi to" row suggests an account as well, with a Follow button and a wave
        // that messages them. Nobody has written to anybody yet, so it is a suggestion and
        // not a conversation.
        if (findWithin(activity, row, SUGGESTED_TITLE_IDS) != null
                || findWithin(activity, row, SUGGESTED_REMOVE_IDS) != null
                || hasId(activity, row, SAY_HI_ROW_IDS)) {
            return Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.get();
        }

        // Single chats, group chats and the message requests row are one family of cells
        // with the same root id. Only the requests row has the request count, so it is
        // tested first, and every other chat row is a conversation. A chat title is a
        // person's or a group's name, so it is only ever matched against the user's own
        // list, never the system labels.
        if (findWithin(activity, row, MESSAGE_REQUESTS_IDS) != null) {
            return Settings.HIDE_INBOX_MESSAGE_REQUESTS.get()
                    || matchesCustomList(textOf(findWithin(activity, row, USER_ROW_TITLE_IDS)));
        }

        if (hasId(activity, row, CHAT_ROW_IDS)) {
            // A pod the model named (Bulletin board, say) can be drawn on a chat's root. Its own
            // switch hides it as well, and the conversations switch still does what it always did.
            BooleanSetting named = SYSTEM_ROWS.get(row);
            return (named != null && named.get())
                    || Settings.HIDE_INBOX_CONVERSATIONS.get()
                    || matchesCustomList(textOf(findWithin(activity, row, USER_ROW_TITLE_IDS)));
        }

        BooleanSetting category = SYSTEM_ROWS.get(row);
        if (category != null) {
            return category.get()
                    || matchesCustomList(textOf(findWithin(activity, row, SYSTEM_ROW_TITLE_IDS)));
        }

        // Unknown system rows still support the user's exact custom title list.
        if (hasId(activity, row, SYSTEM_ROW_IDS)) {
            String title = textOf(findWithin(activity, row, SYSTEM_ROW_TITLE_IDS));
            return matchesCustomList(title);
        }

        return false;
    }

    /** Lets the user hide a row this patch does not know about by typing its title. */
    private static boolean matchesCustomList(String title) {
        if (title == null || title.isEmpty()) {
            return false;
        }

        String custom = Settings.HIDE_INBOX_CUSTOM_TITLES.get();
        if (custom == null || custom.trim().isEmpty()) {
            return false;
        }

        for (String entry : custom.split(",")) {
            String trimmed = entry.trim();
            if (!trimmed.isEmpty() && title.equalsIgnoreCase(trimmed)) {
                return true;
            }
        }
        return false;
    }

    static void resolveForTests(String packageName, String name, int id) {
        RESOURCE_IDS.putForTests(packageName, name, id);
    }

    /**
     * The colour TikTok's own header text is using, so this row is readable whatever theme the
     * app is in. The theme flag is not usable here: it is a cached value the Hushfeed settings
     * screen sets, and away from that screen it answers for the system rather than for TikTok's
     * own in-app theme, which would put a dark crimson on a dark sheet. Falls back to the brand
     * accent when the header holds no text of its own to copy.
     */
    private static int headerTextColour(ViewGroup header) {
        // Descends, because the heading is often wrapped in a layout of its own rather than
        // sitting directly in the header.
        for (int index = 0; index < header.getChildCount(); index++) {
            View child = header.getChildAt(index);
            if (child.getId() == CLEAR_ALL_VIEW_ID) continue;
            if (child instanceof TextView) return ((TextView) child).getCurrentTextColor();
            if (child instanceof ViewGroup) {
                int nested = headerTextColour((ViewGroup) child);
                if (nested != SettingsUi.OVERLAY_ACCENT) return nested;
            }
        }
        return SettingsUi.OVERLAY_ACCENT;
    }

    /**
     * The header's own colour when the control is live, and the same colour faded while a run
     * holds it, so the disabled state is visible as well as announced. Faded rather than taken
     * from {@code SettingsUi.textDisabled()} for the reason {@link #headerTextColour} exists:
     * the theme flag answers for the system away from the settings screen, not for the theme
     * TikTok is actually drawing this header in. 38% is Android's own disabled text alpha.
     */
    private static ColorStateList clearAllColours(int enabled) {
        int faded = (enabled & 0x00FFFFFF)
                | (Math.round(Color.alpha(enabled) * 0.38f) << 24);
        return new ColorStateList(
                new int[][]{new int[]{-android.R.attr.state_enabled}, new int[]{}},
                new int[]{faded, enabled});
    }

    /**
     * Puts a Clear all control at the right end of the Suggested accounts heading.
     *
     * Pressing it works through the remove buttons one at a time, which is the same
     * action as pressing each x by hand, so TikTok stops suggesting those accounts.
     */
    private static void addClearAllControl(Activity activity) {
        if (Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.get()) {
            return;
        }

        View header = findOptional(activity, SUGGESTED_HEADER_IDS);
        if (!(header instanceof ViewGroup)) {
            return;
        }

        ViewGroup headerGroup = (ViewGroup) header;
        if (headerGroup.findViewById(CLEAR_ALL_VIEW_ID) != null) {
            return;
        }

        TextView clearAll = new TextView(activity);
        clearAll.setId(CLEAR_ALL_VIEW_ID);
        clearAll.setText(L10n.t(activity, "Clear all"));
        clearAll.setTextColor(clearAllColours(headerTextColour(headerGroup)));
        // Taking the heading's colour makes it readable in either theme, but it also makes it
        // look like a heading. This is a bulk action that dismisses every suggestion, so it has
        // to read as something you can press.
        clearAll.setTypeface(clearAll.getTypeface(), android.graphics.Typeface.BOLD);
        clearAll.setPaintFlags(clearAll.getPaintFlags() | android.graphics.Paint.UNDERLINE_TEXT_FLAG);
        clearAll.setTextSize(TypedValue.COMPLEX_UNIT_SP, SettingsUi.TEXT_BODY_SMALL);
        clearAll.setGravity(Gravity.CENTER_VERTICAL | Gravity.END);
        clearAll.setContentDescription(L10n.t(activity, "Clear all suggested accounts"));
        clearAll.setMinimumWidth(SettingsUi.dp(activity, 48));
        clearAll.setMinimumHeight(SettingsUi.dp(activity, 48));
        int padding = SettingsUi.dp(activity, 16);
        clearAll.setPadding(padding, 0, padding, 0);
        clearAll.setOnClickListener(view -> clearAllSuggested(activity));
        // A press and a focus ring, the same pair every control this bundle draws now carries.
        // Underlined bold text was the only sign it could be pressed at all.
        // In the heading's own colour: this header is light in the light theme, where a white
        // ripple and a white ring are invisible.
        clearAll.setBackground(SettingsUi.overlayAction(activity, SettingsUi.RADIUS_CONTROL,
                headerTextColour(headerGroup)));
        clearAll.setFocusable(true);
        // It is a TextView because the header styles its own children, so the role has to be
        // said out loud or a reader is told the word "Clear all" and no way to press it.
        SettingsUi.markAsButton(clearAll);
        clearAllControl = new WeakReference<>(clearAll);
        // Every dismissal relays out the list, and TikTok can rebuild this heading while a run is
        // going. A control installed onto the new heading would otherwise come up saying "Clear
        // all", enabled, while a run it silently refuses is still working through the list.
        setClearAllBusy(clearingSuggested);

        // 47.1.4 wraps the title and help icon in a MATCH_PARENT child. Giving only our
        // action weight leaves it zero pixels wide beyond the screen. Reserve the action's
        // measured width and give that native wrapper the remaining space instead.
        ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        if (headerGroup instanceof LinearLayout
                && ((LinearLayout) headerGroup).getOrientation() == LinearLayout.HORIZONTAL) {
            boolean hasFullWidthChild = false;
            for (int index = 0; index < headerGroup.getChildCount(); index++) {
                View child = headerGroup.getChildAt(index);
                ViewGroup.LayoutParams original = child.getLayoutParams();
                if (!(original instanceof LinearLayout.LayoutParams)) continue;
                if (original.width != ViewGroup.LayoutParams.MATCH_PARENT
                        && !(original.width == 0
                                && ((LinearLayout.LayoutParams) original).weight > 0f)) continue;
                LinearLayout.LayoutParams remaining = new LinearLayout.LayoutParams(
                        (LinearLayout.LayoutParams) original);
                remaining.width = 0;
                remaining.weight = 1f;
                child.setLayoutParams(remaining);
                hasFullWidthChild = true;
                // The native title must leave room for its help icon as its wrapper shrinks.
                View title = findOptional(activity, SUGGESTED_TITLE_IDS);
                if (title != null && title.getParent() == child
                        && child instanceof LinearLayout
                        && ((LinearLayout) child).getOrientation() == LinearLayout.HORIZONTAL
                        && title.getLayoutParams() instanceof LinearLayout.LayoutParams) {
                    LinearLayout.LayoutParams text = new LinearLayout.LayoutParams(
                            (LinearLayout.LayoutParams) title.getLayoutParams());
                    text.width = 0;
                    text.weight = 1f;
                    title.setLayoutParams(text);
                }
            }
            params = hasFullWidthChild
                    ? new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT)
                    : new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        }
        headerGroup.addView(clearAll, params);

        Logger.printDebug(() -> "Clear all control added to the suggested accounts heading");
    }

    private static void clearAllSuggested(Activity activity) {
        if (clearingSuggested) {
            // The second tap is the reader asking for it to stop. The run is paced, so the
            // step that is already scheduled honours it rather than this thread cutting in.
            stopRequested = true;
            return;
        }
        clearingSuggested = true;
        stopRequested = false;
        DISMISSED_LABELS.clear();
        setClearAllBusy(true);
        clearNextSuggested(activity, 0);
    }

    /**
     * Sixty accounts paced at 300 ms is about eighteen seconds. The control stays pressable
     * through it, because the tap is how the reader stops it, and says how far it has got
     * instead; every terminal path puts it back so a stopped or failed run can be tried again.
     * It is dimmed rather than disabled: a disabled view takes no touch, and a run nobody can
     * stop is the eighteen seconds of nothing this used to be.
     */
    private static void setClearAllBusy(boolean busy) {
        TextView control = clearAllControl == null ? null : clearAllControl.get();
        if (control == null) return;
        control.setAlpha(busy ? BUSY_ALPHA : 1f);
        if (busy && dismissedSoFar > 0) {
            // A heading rebuilt mid-run gets the count the run is at, not the opening word.
            showProgress(dismissedSoFar);
            return;
        }
        control.setText(L10n.t(control.getContext(), busy ? "Clearing" : "Clear all"));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            control.setStateDescription(busy ? L10n.t(control.getContext(), "Clearing") : null);
            return;
        }
        // Below 30 there is no state to set, and the changed label is invisible to a reader
        // because the content description replaces it. Without this the whole eighteen second
        // wait is announced as "Clear all suggested accounts" and nothing else.
        control.setContentDescription(L10n.t(control.getContext(),
                busy ? "Clearing suggested accounts" : "Clear all suggested accounts"));
    }

    /** How far the run has got, on the label and where a screen reader hears it. */
    private static void showProgress(int dismissed) {
        dismissedSoFar = dismissed;
        TextView control = clearAllControl == null ? null : clearAllControl.get();
        if (control == null) return;
        // A count so far, not "of 60": sixty is the run's cap, and a list of eight would have
        // read "3 of 60" and ended at 8.
        String progress = L10n.f(control.getContext(), "Clearing, %1$d so far", dismissed);
        control.setText(progress);
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            control.setStateDescription(progress);
        } else {
            control.setContentDescription(progress);
        }
    }

    /**
     * Dismisses one account then schedules the next, rather than clicking everything at
     * once, so TikTok sees the same pacing as a person tapping.
     */
    private static void clearNextSuggested(Activity activity, int cleared) {
        // What the reader is told if this step throws. It goes up the moment the click returns,
        // because a throw from the scheduling below it comes after an account really was
        // dismissed and reporting the count from before the click would be one short.
        int dismissed = cleared;
        try {
            if (cleared >= MAX_CLEARED_PER_RUN || activity.isFinishing()) {
                report(cleared);
                return;
            }
            if (stopRequested) {
                // Never at zero: the first click is made before the first step can be
                // stopped, and a run with nothing to click reports on its own. After the cap,
                // so a tap landing on the sixtieth is reported as the run it completed.
                finishRun(failureMessage(cleared));
                return;
            }

            View list = findRequired(activity, "list", LIST_IDS);
            // Each build's name for the remove button; only the running build's resolves, and
            // taking the first one alone left Clear all with nothing to press on 47.1.3.
            int removeId = 0;
            for (String name : SUGGESTED_REMOVE_IDS) {
                removeId = identifier(activity, name);
                if (removeId != 0) break;
            }
            View button = (list == null || removeId == 0) ? null : findUndismissed(list, removeId);

            if (button == null) {
                report(cleared);
                return;
            }

            DISMISSED_LABELS.add(labelOf(button));
            button.performClick();
            dismissed = cleared + 1;
            showProgress(dismissed);

            Utils.runOnMainThreadDelayed(
                    () -> clearNextSuggested(activity, cleared + 1), DISMISS_INTERVAL_MS);
        } catch (Throwable ex) {
            // Logged first so the reader's outcome is the message left on screen: with
            // debugging on, printException puts the stack trace in a toast of its own.
            Logger.printException(() -> "Couldn't clear the suggested accounts. Reopen the Inbox and try again.", ex);
            finishRun(failureMessage(dismissed));
        }
    }

    private static void report(int cleared) {
        finishRun(successMessage(cleared));
    }

    /** Every way a run ends: the control comes back and the outcome is said once. */
    private static void finishRun(String outcome) {
        clearingSuggested = false;
        stopRequested = false;
        dismissedSoFar = 0;
        DISMISSED_LABELS.clear();
        setClearAllBusy(false);
        Utils.showToastShort(outcome);
        // A toast is the sighted half. This is the other half, because the control that was
        // pressed keeps focus and nothing about it changes to say the long run is over.
        TextView control = clearAllControl == null ? null : clearAllControl.get();
        if (control != null) control.announceForAccessibility(outcome);
    }

    private static String successMessage(int cleared) {
        if (cleared == 0) return L10n.t("No suggested accounts to clear");
        if (cleared == 1) return L10n.t("Dismissed one suggested account");
        return L10n.f("Dismissed %1$s suggested accounts", cleared);
    }

    private static String failureMessage(int cleared) {
        if (cleared == 0) return L10n.t("Couldn't clear the suggested accounts. Reopen the Inbox and try again.");
        if (cleared == 1) return L10n.t("Stopped after dismissing one suggested account");
        return L10n.f("Stopped after dismissing %1$s suggested accounts", cleared);
    }

    /** Depth first search for a remove button whose account has not been dismissed yet. */
    private static View findUndismissed(View view, int removeId) {
        if (view.getId() == removeId && view.isShown() && !DISMISSED_LABELS.contains(labelOf(view))) {
            return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                View match = findUndismissed(group.getChildAt(index), removeId);
                if (match != null) {
                    return match;
                }
            }
        }
        return null;
    }

    /**
     * The remove button's description names the account, which identifies the row however
     * its view gets recycled. Read off the live hierarchy on 46.2.3: "Remove Whitemanstandup
     * from suggested accounts", "Remove chieftwit7 from suggested accounts", one per row. A
     * button with no description falls back to its identity, which is the pre-recycling
     * behaviour rather than a stop.
     */
    private static String labelOf(View button) {
        CharSequence description = button.getContentDescription();
        if (description != null && description.length() > 0) {
            return description.toString();
        }
        return "view:" + System.identityHashCode(button);
    }

    /**
     * RecyclerView measures its children itself and does not honour {@code GONE}, so a
     * hidden row also needs a zero height to actually collapse. Nothing is written when
     * the row is already in the wanted state, which is what keeps the layout listener
     * from triggering itself.
     */
    private static void setRowHidden(View row, boolean hidden) {
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params == null) {
            setHidden(row, hidden);
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

    private static void setHidden(View view, boolean hidden) {
        if (view == null) {
            return;
        }
        int wanted = hidden ? View.GONE : View.VISIBLE;
        if (view.getVisibility() != wanted) {
            view.setVisibility(wanted);
        }
    }

    private static String textOf(View view) {
        if (view instanceof TextView) {
            CharSequence text = ((TextView) view).getText();
            if (text != null) {
                // TikTok pads titles with a left to right mark.
                return text.toString().replace("‎", "").trim();
            }
        }
        return null;
    }

    private static boolean hasId(Activity activity, View view, String[] names) {
        for (String name : names) {
            int id = identifier(activity, name);
            if (id != 0 && view.getId() == id) {
                HookStatus.bound("inbox", name);
                return true;
            }
        }
        return false;
    }

    private static View findRequired(Activity activity, String description, String[] names) {
        String diagnosticName = description + " (" + join(names) + ")";
        View view = findOptional(activity, names);
        if (view == null) {
            HookStatus.missingViewId("inbox", diagnosticName);
        } else {
            HookStatus.recoveredViewId("inbox", diagnosticName);
        }
        return view;
    }

    private static View findOptional(Activity activity, String[] names) {
        for (String name : names) {
            int id = identifier(activity, name);
            if (id == 0) continue;
            View view = activity.findViewById(id);
            if (view != null) {
                HookStatus.bound("inbox", name);
                return view;
            }
        }
        return null;
    }

    private static View findWithin(Activity activity, View parent, String[] names) {
        for (String name : names) {
            int id = identifier(activity, name);
            if (id == 0) continue;
            View view = parent.findViewById(id);
            if (view != null) {
                HookStatus.bound("inbox", name);
                return view;
            }
        }
        return null;
    }

    /** Resolves a resource id by name once and remembers it, including a miss. */
    private static int identifier(Activity activity, String name) {
        return RESOURCE_IDS.resolve(
                activity == null ? null : activity.getResources(),
                activity == null ? "" : activity.getPackageName(),
                name,
                false);
    }

    private static String join(String[] names) {
        StringBuilder joined = new StringBuilder();
        for (String name : names) {
            if (joined.length() != 0) joined.append('/');
            joined.append(name);
        }
        return joined.toString();
    }
}
