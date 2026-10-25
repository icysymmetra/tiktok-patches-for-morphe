/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import android.content.Context;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/** An owned overlay reserves list padding instead of reparenting a native RecyclerView. */
final class InboxQueryControls implements ViewTreeObserver.OnGlobalLayoutListener {
    private final InboxQueryOwners.Owner owner;
    private ViewGroup host;
    private LinearLayout bar;
    private EditText editor;
    private Button clear;
    private int top, addedPadding;
    private boolean internal;
    private String applied = "";
    private final Runnable apply = () -> guard("search query", this::apply);

    InboxQueryControls(InboxQueryOwners.Owner owner) { this.owner = owner; }

    void attach() {
        View list = owner.list.get();
        if (list != null) list.getViewTreeObserver().addOnGlobalLayoutListener(this);
        onGlobalLayout();
    }
    @Override public void onGlobalLayout() { guard("search bar layout", this::layout); }

    /** These run on TikTok's main thread: a throw hands the list back to TikTok instead of crashing it. */
    private void guard(String hook, Runnable body) {
        try { body.run(); }
        catch (Throwable failure) { InboxQueryNative.failed(owner, hook, failure); }
    }

    private void layout() {
        View list = owner.list.get();
        if (list == null || !owner.current()) { owner.close(); return; }
        if (!Settings.INBOX_LOADED_SEARCH.get()) {
            if (!applied.isEmpty() || owner.query.phase() == LoadedInboxQuery.Phase.QUERY) InboxQueryNative.query(owner, "");
            applied = "";
            removeBar();
            return;
        }
        if (bar == null && !install(list)) return;
        if (host != resolveHost(list)) { removeBar(); return; }
        int[] listPosition = new int[2], hostPosition = new int[2];
        list.getLocationInWindow(listPosition);
        host.getLocationInWindow(hostPosition);
        bar.setX(listPosition[0] - hostPosition[0]);
        bar.setY(listPosition[1] - hostPosition[1]);
        ViewGroup.LayoutParams params = bar.getLayoutParams();
        if (params.width != list.getWidth()) { params.width = list.getWidth(); bar.setLayoutParams(params); }
        int height = bar.getMeasuredHeight();
        if (height > 0 && height != addedPadding) {
            // Keep independently updated native padding rather than restoring a stale snapshot.
            int currentTop = list.getPaddingTop() - addedPadding;
            top = currentTop;
            addedPadding = height;
            list.setPadding(list.getPaddingLeft(), top + height, list.getPaddingRight(), list.getPaddingBottom());
        }
        boolean idle = InboxQueryOwners.requestsIdle(owner);
        boolean available = owner.seamsReady() && owner.pagingIsolated();
        editor.setEnabled(available && idle && owner.query.phase() != LoadedInboxQuery.Phase.CLEARING);
        editor.setHint(L10n.t(list.getContext(), !available ? "Search is unavailable on this screen"
                : !idle ? "Wait for loading to finish" : "Search loaded activity"));
        clear.setEnabled(!editor.getText().toString().isEmpty());
    }

    private boolean install(View list) {
        host = resolveHost(list);
        if (host == null) return false;
        Context context = list.getContext();
        boolean dark = SettingsUi.isDarkContext(context);
        bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(android.view.Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(dark ? Color.BLACK : Color.WHITE);
        int inset = SettingsUi.dp(context, 8);
        bar.setPadding(inset, 0, inset, 0);
        editor = new EditText(context);
        editor.setSingleLine(true);
        editor.setTextSize(16);
        editor.setTextColor(dark ? Color.WHITE : Color.BLACK);
        editor.setHintTextColor(dark ? Color.LTGRAY : Color.DKGRAY);
        editor.setContentDescription(L10n.t(context, "Search loaded activity"));
        editor.setFilters(new InputFilter[]{new InputFilter.LengthFilter(256)});
        editor.setMinHeight(SettingsUi.dp(context, 48));
        clear = new Button(context);
        clear.setText(L10n.t(context, "Clear"));
        clear.setAllCaps(false);
        clear.setTextColor(SettingsUi.overlayAccentOn(dark));
        clear.setMinHeight(SettingsUi.dp(context, 48));
        clear.setOnClickListener(view -> { editor.setText(""); editor.clearFocus(); });
        bar.addView(editor, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(clear, new LinearLayout.LayoutParams(-2, -2));
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence value, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence value, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable value) {
                if (internal) return;
                guard("search typing", () -> {
                    list.removeCallbacks(apply);
                    list.postDelayed(apply, 150);
                });
            }
        });
        top = list.getPaddingTop();
        host.addView(bar, new FrameLayout.LayoutParams(Math.max(1, list.getWidth()), -2));
        return true;
    }

    private FrameLayout resolveHost(View list) {
        Object fragment = owner.fragment.get();
        Object root = Reflect.invoke(fragment, "getView"), spring = Reflect.invoke(fragment, "bY");
        // The native SpringLayout is itself a FrameLayout but permits only its header and
        // nested list. Its immediate framework FrameLayout parent is the verified overlay host.
        if (!(root instanceof View) || !(spring instanceof View) || list.getParent() != spring) return null;
        Object parent = ((View) spring).getParent();
        return parent != null && parent.getClass() == FrameLayout.class && inside((View) parent, (View) root)
                ? (FrameLayout) parent : null;
    }

    private void apply() {
        if (editor == null || !owner.current()) return;
        String next = editor.getText().toString();
        if (InboxQueryNative.query(owner, next)) applied = next;
        else {
            internal = true;
            editor.setText(applied);
            editor.setSelection(editor.length());
            internal = false;
            String message = InboxQueryOwners.requestsIdle(owner) ? "Search is unavailable on this screen" : "Wait for loading to finish";
            Toast.makeText(editor.getContext(), L10n.t(editor.getContext(), message), Toast.LENGTH_SHORT).show();
        }
        layout();
    }

    void detach() {
        View list = owner.list.get();
        if (list != null) {
            list.removeCallbacks(apply);
            if (list.getViewTreeObserver().isAlive()) list.getViewTreeObserver().removeOnGlobalLayoutListener(this);
        }
        removeBar(true);
    }
    void cleared() {
        applied = "";
        View list = owner.list.get();
        if (list != null) list.removeCallbacks(apply);
        if (editor == null) return;
        internal = true;
        editor.setText("");
        internal = false;
    }
    void failed() {
        cleared();
        View list = owner.list.get();
        if (list != null && owner.current()) Toast.makeText(list.getContext(),
                L10n.t(list.getContext(), "Search is unavailable on this screen"), Toast.LENGTH_SHORT).show();
    }
    private void removeBar() { removeBar(false); }
    private void removeBar(boolean afterDetach) {
        if (bar == null) return;
        View list = owner.list.get();
        LinearLayout retiredBar = bar;
        ViewGroup retiredHost = retiredBar.getParent() instanceof ViewGroup ? (ViewGroup) retiredBar.getParent() : null;
        int retiredPadding = addedPadding;
        if (clear != null) clear.setOnClickListener(null);
        if (editor != null) editor.setEnabled(false);
        bar = null; editor = null; clear = null; host = null; addedPadding = 0;
        Runnable cleanup = () -> {
            if (list != null && retiredPadding != 0) list.setPadding(list.getPaddingLeft(), list.getPaddingTop() - retiredPadding,
                    list.getPaddingRight(), list.getPaddingBottom());
            if (retiredHost != null && retiredBar.getParent() == retiredHost) retiredHost.removeView(retiredBar);
        };
        // Android notifies detach listeners before clearing attachment, while an ancestor may
        // still be iterating its cached child array. Retire ownership now, mutate that tree later.
        if (afterDetach) Utils.runOnMainThread(cleanup);
        else cleanup.run();
    }
    private static boolean inside(View child, View root) {
        for (View current = child; current != null; current = current.getParent() instanceof View ? (View) current.getParent() : null) {
            if (current == root) return true;
        }
        return false;
    }
}
