/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

/** The marked ads: switch each on or off, rename, delete, and the recent scan log. */
final class AdSegmentList {
    private final Activity activity;
    private final Dialog dialog;
    private LinearLayout list;

    private AdSegmentList(Activity activity) {
        this.activity = activity;
        this.dialog = new Dialog(activity, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
    }

    static void open(Activity activity) {
        if (activity == null || activity.isFinishing()) return;
        Utils.runOnMainThread(() -> new AdSegmentList(activity).show());
    }

    private void show() {
        Context c = activity;
        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(AdSkipUi.BG);

        LinearLayout bar = new LinearLayout(c);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(12), dp(8));
        ImageView close = AdSkipUi.iconButton(c, AdIcons.Icon.CLOSE, L10n.t("Close"), 40);
        close.setOnClickListener(v -> dialog.dismiss());
        bar.addView(close, new LinearLayout.LayoutParams(dp(40), dp(40)));
        TextView title = AdSkipUi.text(c, L10n.t("Marked ads"), 18, AdSkipUi.TEXT, true);
        title.setPadding(dp(12), 0, 0, 0);
        bar.addView(title, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(bar);

        ScrollView scroll = new ScrollView(c);
        list = new LinearLayout(c);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), dp(24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
        AdSkipUi.padForSystemBars(root);

        dialog.setContentView(root);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            window.setStatusBarColor(AdSkipUi.BG);
            window.setNavigationBarColor(AdSkipUi.BG);
        }
        refresh();
        dialog.show();
    }

    private void refresh() {
        Context c = activity;
        list.removeAllViews();
        List<AdSegment> segments = AdSegmentStore.all();

        list.addView(statistics());
        if (segments.isEmpty()) {
            TextView empty = AdSkipUi.text(c, L10n.t("No ads marked yet. Open a video's share sheet and tap Mark ad."),
                    13, AdSkipUi.TEXT_2, false);
            empty.setPadding(dp(4), dp(4), dp(4), dp(12));
            list.addView(empty);
        }

        List<AdSegment> disliked = new java.util.ArrayList<>();
        for (int i = segments.size() - 1; i >= 0; i--) {
            AdSegment segment = segments.get(i);
            if (SharedDb.myVote(segment.sharedServerId) < 0) disliked.add(segment);
            else list.addView(card(segment));
        }
        if (!disliked.isEmpty()) {
            TextView heading = AdSkipUi.text(c, L10n.t("Voted down"), 13, AdSkipUi.TEXT, true);
            heading.setPadding(dp(4), dp(16), 0, dp(2));
            list.addView(heading);
            TextView hint = AdSkipUi.text(c, L10n.t("These ads are not skipped for you. Tap Undo if you voted by mistake."),
                    12, AdSkipUi.TEXT_2, false);
            hint.setPadding(dp(4), 0, dp(4), dp(8));
            list.addView(hint);
            for (AdSegment segment : disliked) list.addView(card(segment));
        }

        if (!segments.isEmpty()) {
            TextView clear = AdSkipUi.chip(c, L10n.t("Delete all"), false);
            clear.setTextColor(AdSkipUi.ACCENT);
            clear.setOnClickListener(v -> confirm(L10n.t("Delete every marked ad?"), () -> {
                AdSegmentStore.clear();
                AdScanner.clearAll();
                refresh();
            }));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
            p.topMargin = dp(8);
            p.gravity = Gravity.END;
            list.addView(clear, p);
        }

        List<String> log = AdSkipLog.lines();
        if (!log.isEmpty()) {
            TextView heading = AdSkipUi.text(c, L10n.t("Recent checks"), 13, AdSkipUi.TEXT, true);
            heading.setPadding(dp(4), dp(24), 0, dp(6));
            list.addView(heading);
            StringBuilder text = new StringBuilder();
            for (String line : log) text.append(line).append('\n');
            TextView lines = AdSkipUi.text(c, text.toString().trim(), 11, AdSkipUi.TEXT_2, false);
            lines.setTypeface(android.graphics.Typeface.MONOSPACE);
            lines.setBackground(AdSkipUi.round(AdSkipUi.SURFACE, dp(12)));
            lines.setPadding(dp(12), dp(10), dp(12), dp(10));
            lines.setTextIsSelectable(true);
            list.addView(lines);
        }
    }

    /**
     * What skipping has given back, as SponsorBlock shows it: ads skipped and time saved on this
     * phone, then, once the shared base answers, what the reader's own cuts saved everyone else
     * and what the base holds as a whole.
     */
    private View statistics() {
        Context c = activity;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(AdSkipUi.round(AdSkipUi.SURFACE, dp(16)));
        box.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams boxParams = new LinearLayout.LayoutParams(-1, -2);
        boxParams.bottomMargin = dp(12);
        box.setLayoutParams(boxParams);

        LinearLayout tiles = new LinearLayout(c);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.addView(tile(String.valueOf(AdSkipStats.skips()), L10n.t("Ads skipped")),
                new LinearLayout.LayoutParams(0, -2, 1f));
        tiles.addView(tile(AdSkipStats.duration(AdSkipStats.savedMs()), L10n.t("Time saved")),
                new LinearLayout.LayoutParams(0, -2, 1f));
        box.addView(tiles);

        if (!SharedDb.enabled()) return box;

        View divider = new View(c);
        divider.setBackgroundColor(AdSkipUi.SURFACE_HI);
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(-1, dp(1));
        dividerParams.topMargin = dp(16);
        dividerParams.bottomMargin = dp(14);
        box.addView(divider, dividerParams);

        TextView heading = AdSkipUi.text(c, L10n.t("Shared base"), 14, AdSkipUi.TEXT, true);
        heading.setCompoundDrawablesRelative(AdIcons.of(c, AdIcons.Icon.CLOUD, AdSkipBar.MARK, 18), null, null, null);
        heading.setCompoundDrawablePadding(dp(8));
        heading.setGravity(Gravity.CENTER_VERTICAL);
        box.addView(heading);

        TextView status = AdSkipUi.text(c, L10n.t("Loading the shared base…"), 12, AdSkipUi.TEXT_2, false);
        status.setPadding(0, dp(10), 0, 0);
        box.addView(status);

        LinearLayout groups = new LinearLayout(c);
        groups.setOrientation(LinearLayout.VERTICAL);
        groups.setVisibility(View.GONE);
        box.addView(groups);

        Utils.runOnOwnThread("metra-adskip-stats", () -> {
            SharedDb.Stats stats = SharedDb.fetchStats();
            Utils.runOnMainThread(() -> {
                if (stats == null) {
                    status.setText(L10n.t("The shared base can't be reached right now."));
                    return;
                }
                status.setVisibility(View.GONE);
                groups.setVisibility(View.VISIBLE);
                if (stats.mine) {
                    groups.addView(group(L10n.t("Your contribution"),
                            String.valueOf(stats.mySegments), L10n.t("Your marks"),
                            String.valueOf(stats.mySkips), L10n.t("Skipped for others"),
                            AdSkipStats.duration(stats.mySavedMs), L10n.t("Saved others")));
                }
                groups.addView(group(L10n.t("All users"),
                        String.valueOf(stats.segments), L10n.t("Ads in the base"),
                        String.valueOf(stats.contributors), L10n.t("Contributors"),
                        AdSkipStats.duration(stats.savedMs), L10n.t("Saved everyone")));
            });
        });
        return box;
    }

    private View tile(String value, String label) {
        LinearLayout tile = new LinearLayout(activity);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.addView(AdSkipUi.text(activity, value, 26, AdSkipBar.MARK, true));
        TextView caption = AdSkipUi.text(activity, label, 12, AdSkipUi.TEXT_2, false);
        caption.setPadding(0, dp(2), dp(8), 0);
        tile.addView(caption);
        return tile;
    }

    /** A captioned row of three small figures, each on its own raised cell. */
    private View group(String caption, String v1, String l1, String v2, String l2, String v3, String l3) {
        Context c = activity;
        LinearLayout group = new LinearLayout(c);
        group.setOrientation(LinearLayout.VERTICAL);
        group.setPadding(0, dp(12), 0, 0);
        TextView title = AdSkipUi.text(c, caption, 12, AdSkipUi.TEXT_2, true);
        title.setPadding(dp(2), 0, 0, dp(6));
        group.addView(title);

        LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        String[][] cells = {{v1, l1}, {v2, l2}, {v3, l3}};
        for (int i = 0; i < cells.length; i++) {
            LinearLayout cell = new LinearLayout(c);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setBackground(AdSkipUi.round(AdSkipUi.SURFACE_HI, dp(12)));
            cell.setPadding(dp(10), dp(10), dp(10), dp(10));
            TextView value = AdSkipUi.text(c, cells[i][0], 17, AdSkipUi.TEXT, true);
            value.setSingleLine(true);
            value.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cell.addView(value);
            TextView label = AdSkipUi.text(c, cells[i][1], 11, AdSkipUi.TEXT_2, false);
            label.setMaxLines(2);
            label.setPadding(0, dp(2), 0, 0);
            cell.addView(label);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1f);
            if (i > 0) params.leftMargin = dp(8);
            row.addView(cell, params);
        }
        group.addView(row);
        return group;
    }

    private View card(AdSegment segment) {
        Context c = activity;
        LinearLayout card = new LinearLayout(c);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(AdSkipUi.round(AdSkipUi.SURFACE, dp(14)));
        card.setPadding(dp(14), dp(12), dp(8), dp(12));
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
        cardParams.bottomMargin = dp(8);
        card.setLayoutParams(cardParams);

        LinearLayout text = new LinearLayout(c);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = AdSkipUi.text(c, segment.name, 15, segment.enabled ? AdSkipUi.TEXT : AdSkipUi.TEXT_2, true);
        text.addView(name);
        String details = L10n.f("%1$s s · skipped %2$s times · %3$s",
                AdSkipUi.seconds(segment.durationMs), String.valueOf(segment.skips),
                DateFormat.getDateInstance(DateFormat.SHORT).format(new Date(segment.createdAt)));
        TextView detailsView = AdSkipUi.text(c, details, 12, AdSkipUi.TEXT_2, false);
        if (segment.sharedServerId != null) {
            detailsView.setCompoundDrawablesRelative(AdIcons.of(c, AdIcons.Icon.CLOUD, AdSkipBar.MARK, 14), null, null, null);
            detailsView.setCompoundDrawablePadding(dp(6));
            card.setContentDescription(L10n.t("In the shared base"));
        }
        text.addView(detailsView);
        card.addView(text, new LinearLayout.LayoutParams(0, -2, 1f));

        if (SharedDb.myVote(segment.sharedServerId) < 0) {
            name.setTextColor(AdSkipUi.TEXT_2);
            TextView undo = AdSkipUi.chip(c, L10n.t("Undo"), false);
            undo.setTextColor(AdSkipUi.ACCENT);
            undo.setOnClickListener(v -> {
                SharedDb.vote(segment.sharedServerId, 0);
                refresh();
            });
            card.addView(undo);
        } else {
            Switch toggle = new Switch(c);
            toggle.setChecked(segment.enabled);
            toggle.setOnCheckedChangeListener((b, on) -> {
                AdSegmentStore.setEnabled(segment.id, on);
                name.setTextColor(on ? AdSkipUi.TEXT : AdSkipUi.TEXT_2);
            });
            card.addView(toggle);
        }

        ImageView more = AdSkipUi.iconButton(c, AdIcons.Icon.MORE, L10n.t("More options"), 36);
        more.setOnClickListener(v -> menu(segment));
        LinearLayout.LayoutParams moreParams = new LinearLayout.LayoutParams(dp(36), dp(36));
        moreParams.leftMargin = dp(6);
        card.addView(more, moreParams);
        card.setOnClickListener(v -> menu(segment));
        return card;
    }

    private void menu(AdSegment segment) {
        String[] items = {L10n.t("Rename"), L10n.t("Delete")};
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(segment.name)
                .setItems(items, (d, which) -> {
                    if (which == 0) rename(segment);
                    else confirm(L10n.f("Delete \"%1$s\"?", segment.name), () -> {
                        AdSegmentStore.remove(segment.id);
                        refresh();
                    });
                })
                .show();
    }

    private void rename(AdSegment segment) {
        EditText field = new EditText(activity);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        field.setText(segment.name);
        field.setSelection(field.getText().length());
        LinearLayout box = new LinearLayout(activity);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(field, new LinearLayout.LayoutParams(-1, -2));
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(L10n.t("Rename"))
                .setView(box)
                .setPositiveButton(L10n.t("Save"), (d, w) -> {
                    String value = field.getText().toString().trim();
                    if (!value.isEmpty()) AdSegmentStore.rename(segment.id, value);
                    refresh();
                })
                .setNegativeButton(L10n.t("Cancel"), null)
                .show();
    }

    private void confirm(String message, Runnable yes) {
        new AlertDialog.Builder(activity, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setMessage(message)
                .setPositiveButton(L10n.t("Delete"), (d, w) -> yes.run())
                .setNegativeButton(L10n.t("Cancel"), null)
                .show();
    }

    private int dp(float v) {
        return AdSkipUi.dp(activity, v);
    }
}
