/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.accountmanagement;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.NumberFormat;
import java.util.Collections;
import java.util.List;

import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Kind;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Row;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.Snapshot;
import app.morphe.extension.tiktok.accountmanagement.AccountListSession.State;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/** Two explicit read-only views. Rows have no profile, follow, unblock or delete actions. */
public final class AccountManagement {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private AccountManagement() {}

    public static void showSavedPosts(Context context) { show(context, Kind.SAVED_POSTS); }
    public static void showBlockedAccounts(Context context) { show(context, Kind.BLOCKED_ACCOUNTS); }

    private static void show(Context context, Kind kind) {
        if (Looper.myLooper() != Looper.getMainLooper()) { MAIN.post(() -> show(context, kind)); return; }
        Activity activity = activity(context);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        String key = "account_management_" + kind.name();
        if (SettingsUi.dialogOpen(key, activity)) return;
        AlertDialog dialog = open(activity, kind, new NativeAccountOwner(activity.getClassLoader()),
                new NativeAccountLists(activity.getClassLoader()));
        SettingsUi.dialogOpened(key, activity, dialog);
    }

    static AlertDialog open(Context context, Kind kind, AccountListSession.Accounts accounts,
            AccountListSession.Transport transport) {
        SettingsUi.syncDarkMode(context);
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = SettingsUi.dp(context, 22);
        body.setPadding(padding, padding, padding, padding);
        body.addView(SettingsUi.dialogTitle(context, L10n.t(context,
                kind == Kind.SAVED_POSTS ? "Saved posts" : "Blocked accounts")));
        TextView scope = label(context, L10n.t(context, kind == Kind.SAVED_POSTS
                ? "These are posts saved by your signed-in account. Other Favorites categories aren't included."
                : "Read-only list. Accounts won't be opened or unblocked."));
        body.addView(scope);
        TextView progress = label(context, ""); progress.setTag("account_list_progress");
        TextView status = label(context, ""); status.setTag("account_list_status");
        TextView metadata = label(context, ""); metadata.setTag("account_list_metadata");
        body.addView(progress); body.addView(status); body.addView(metadata);
        Rows adapter = new Rows(context, kind);
        ListView list = new ListView(context);
        list.setTag("account_list_rows"); list.setAdapter(adapter);
        list.setDividerHeight(0);
        SettingsUi.styleScrollableList(list);
        body.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                SettingsUi.dialogListHeight(context, 360)));
        AlertDialog dialog = new AlertDialog.Builder(context).setView(body)
                .setPositiveButton(L10n.t(context, "Load more"), null)
                .setNeutralButton(L10n.t(context, "Cancel"), null)
                .setNegativeButton(L10n.t(context, "Close"), null).create();
        AccountListSession.Dispatch dispatch = new AccountListSession.Dispatch() {
            @Override public long now() { return SystemClock.uptimeMillis(); }
            @Override public void post(Runnable task) { MAIN.post(task); }
            @Override public AccountListSession.Cancellation after(long delay, Runnable task) {
                MAIN.postDelayed(task, delay); return () -> MAIN.removeCallbacks(task);
            }
        };
        AccountListSession session = new AccountListSession(kind, accounts, transport, dispatch, value -> {
            if (!dialog.isShowing() || !body.isAttachedToWindow()) return;
            progress.setText(L10n.f(context, "%1$s items loaded", number(value.rows.size())));
            status.setText(status(context, value));
            metadata.setVisibility(value.blocks == null ? View.GONE : View.VISIBLE);
            if (value.blocks != null) metadata.setText(L10n.f(context,
                    "TikTok's total: %1$s. Filtered accounts: %2$s.", number(value.blocks.total),
                    number(value.blocks.filteredCount)) + (value.blocks.filteredText.isEmpty() ? "" : "\n" + value.blocks.filteredText));
            adapter.replace(value.rows);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(value.canLoad());
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(value.state == State.LOADING);
        });
        dialog.setOnDismissListener(ignored -> session.close());
        body.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) {
                // Retire ownership only. Never edit a child collection during Android's detach traversal.
                session.close();
            }
        });
        dialog.show();
        SettingsUi.styleFramedDialog(dialog);
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> session.loadMore());
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(ignored -> session.cancelLoading());
        session.open();
        return dialog;
    }

    private static TextView label(Context context, String text) {
        TextView label = SettingsUi.text(context, text, SettingsUi.TEXT_BODY_SMALL,
                SettingsUi.textSecondary(), Typeface.NORMAL);
        label.setPadding(0, SettingsUi.dp(context, 8), 0, SettingsUi.dp(context, 4));
        return label;
    }

    private static String status(Context context, Snapshot value) {
        if (value.state == State.ACCOUNT_CHANGED)
            return L10n.t(context, "Your account changed. Close this list and open it again.");
        if (value.state == State.CANCELLED) return L10n.t(context, "Loading cancelled. The list is incomplete.");
        if (value.state == State.LOADING) return L10n.f(context, "Loading page %1$s...", number(value.pages + 1));
        if (value.state == State.COMPLETE) return L10n.t(context, "TikTok reports no more items in this list.");
        if (value.state == State.MORE) return L10n.t(context, "More items are available.");
        switch (value.reason) {
            case SIGNED_OUT: return L10n.t(context, "Sign in to view this list");
            case UNSUPPORTED: return L10n.t(context, "This list isn't available on this TikTok build.");
            case REFUSED: return value.errorCode == null ? L10n.t(context, "Couldn't read this list. Close it and try again.")
                    : L10n.f(context, "TikTok refused this request (%1$s).", number(value.errorCode));
            case TIMEOUT: return L10n.t(context, "The request timed out. The list may be incomplete.");
            case STALLED: case EMPTY_MORE: case DUPLICATE_PAGE:
                return L10n.t(context, "The list stopped making progress. Results are incomplete.");
            case LIMIT: return L10n.t(context, "The list reached its limit. More items may remain.");
            case LIST_CHANGED: case COUNT_MISMATCH:
                return L10n.t(context, "The list changed while loading. Results are incomplete.");
            case FILTERED_MORE: return L10n.t(context, "TikTok reports additional filtered accounts. This list is incomplete.");
            default: return L10n.t(context, "Couldn't read this list. Close it and try again.");
        }
    }

    private static String number(long value) { return NumberFormat.getIntegerInstance().format(value); }
    private static Activity activity(Context context) {
        for (int count = 0; count < 8 && context != null; count++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) return null;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) return null;
            context = next;
        }
        return null;
    }

    private static final class Rows extends BaseAdapter {
        final Context context;
        final Kind kind;
        List<Row> rows = Collections.emptyList();
        Rows(Context context, Kind kind) { this.context = context; this.kind = kind; }
        void replace(List<Row> rows) { this.rows = rows; notifyDataSetChanged(); }
        @Override public int getCount() { return rows.size(); }
        @Override public Row getItem(int position) { return rows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return false; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            LinearLayout row;
            if (recycled instanceof LinearLayout) row = (LinearLayout) recycled;
            else {
                row = new LinearLayout(context); row.setOrientation(LinearLayout.VERTICAL);
                int space = SettingsUi.dp(context, 12); row.setPadding(0, space, 0, space);
                row.addView(SettingsUi.text(context, "", SettingsUi.TEXT_BODY, SettingsUi.textPrimary(), Typeface.NORMAL));
                row.addView(label(context, ""));
            }
            Row value = getItem(position);
            TextView title = (TextView) row.getChildAt(0), detail = (TextView) row.getChildAt(1);
            title.setMaxLines(3);
            title.setText(value.title.isEmpty() ? L10n.t(context, kind == Kind.SAVED_POSTS ? "Saved post" : "Blocked account") : value.title);
            detail.setText(value.detail); detail.setVisibility(value.detail.isEmpty() ? View.GONE : View.VISIBLE);
            row.setClickable(false); row.setLongClickable(false); row.setFocusable(false);
            return row;
        }
    }
}
