/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.settings.preference;

import android.database.DataSetObserver;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.SwitchPreference;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.ListAdapter;
import android.widget.TextView;
import android.widget.WrapperListAdapter;

import app.morphe.extension.shared.settings.preference.AbstractPreferenceFragment;

/** Adds presentation to the platform adapter without changing preference binding or persistence. */
@SuppressWarnings("deprecation")
final class SettingsListAdapter extends BaseAdapter implements WrapperListAdapter {
    private final ListAdapter delegate;
    private final DataSetObserver observer = new DataSetObserver() {
        @Override public void onChanged() { notifyDataSetChanged(); }
        @Override public void onInvalidated() { notifyDataSetInvalidated(); }
    };

    SettingsListAdapter(ListAdapter delegate) {
        this.delegate = delegate;
        delegate.registerDataSetObserver(observer);
    }

    void dispose() { delegate.unregisterDataSetObserver(observer); }
    @Override public ListAdapter getWrappedAdapter() { return delegate; }
    @Override public int getCount() { return delegate.getCount(); }
    @Override public Object getItem(int position) { return delegate.getItem(position); }
    @Override public long getItemId(int position) { return delegate.getItemId(position); }
    @Override public boolean hasStableIds() { return delegate.hasStableIds(); }
    @Override public int getItemViewType(int position) { return delegate.getItemViewType(position); }
    @Override public int getViewTypeCount() { return delegate.getViewTypeCount(); }
    @Override public boolean areAllItemsEnabled() { return delegate.areAllItemsEnabled(); }
    @Override public boolean isEnabled(int position) { return delegate.isEnabled(position); }

    private boolean isBoundary(int position) {
        if (position < 0 || position >= getCount()) return true;
        Object item = getItem(position);
        return isErrorMessage(item)
                || item instanceof SettingsHeaderPreference || item instanceof PreferenceCategory
                || item instanceof SectionHeadingPreference
                || item instanceof SettingsStatusPreference
                || item instanceof SettingsSearchInputPreference
                || item instanceof SettingsSearchEntryPreference
                || item instanceof CalmFeedPresetPreference
                || item instanceof QuickSetupsPreference;
    }

    /** The recovery page's message, which is drawn as the page's title rather than as a row. */
    private static boolean isErrorMessage(Object item) {
        return item instanceof Preference && AbstractPreferenceFragment.INITIALIZATION_ERROR_KEY
                .equals(((Preference) item).getKey());
    }

    /** The width a row of this list gets: the list's own once it has one, the screen's before. */
    private static int rowWidth(ViewGroup list) {
        int width = list.getWidth() > 0 ? list.getWidth() : list.getMeasuredWidth();
        if (width <= 0) width = list.getResources().getDisplayMetrics().widthPixels;
        return width - list.getPaddingLeft() - list.getPaddingRight();
    }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        View row = delegate.getView(position, convertView, parent);
        if (isErrorMessage(getItem(position))) {
            SettingsUi.styleErrorHeading(row);
            return row;
        }
        if (isBoundary(position)) return row;
        Preference preference = (Preference) getItem(position);
        if (preference instanceof SettingsMenuPreference && ((SettingsMenuPreference) preference).isCompact()) {
            SettingsUi.styleCompactMenuRow(row, isBoundary(position + 1),
                    ((SettingsMenuPreference) preference).isAbout());
            SettingsUi.applyMetraRow(row, isBoundary(position + 1));
            return row;
        }
        SettingsUi.stylePreferenceRow(row);
        SettingsUi.applyMetraRow(row, isBoundary(position + 1));
        if (preference instanceof SwitchPreference) SettingsUi.reflowSwitchRow(row, rowWidth(parent));
        // The recovery page's two actions were styled while the rows were bound, and the row
        // styling above painted over it, so Retry and Back came out as two identical rows.
        String key = preference.getKey();
        if (AbstractPreferenceFragment.INITIALIZATION_RETRY_KEY.equals(key)
                || AbstractPreferenceFragment.INITIALIZATION_BACK_KEY.equals(key)) {
            SettingsUi.styleErrorAction(row,
                    AbstractPreferenceFragment.INITIALIZATION_RETRY_KEY.equals(key));
        }
        if (preference instanceof SettingsMenuPreference) {
            SettingsUi.styleCompactMenuRow(row, isBoundary(position + 1));
            return row;
        }

        View icon = row.findViewById(android.R.id.icon_frame);
        if (icon != null) icon.setVisibility(View.GONE);
        TextView summary = row.findViewById(android.R.id.summary);
        if (summary != null && (preference instanceof ListPreference
                || preference instanceof DownloadPathPreference)) {
            summary.setTextColor(preference.isEnabled() ? SettingsUi.textSecondary() : SettingsUi.textDisabled());
        }
        ViewGroup widget = row.findViewById(android.R.id.widget_frame);
        // A row that acts on the tap gets no chevron. Reset, Undo, Start today over and the two
        // clear rows all wore the one that means "opens a page", which is the wrong promise to
        // make about a row that replaces every setting.
        boolean acts = preference instanceof app.morphe.extension.shared.settings.preference.ImmediateAction
                && ((app.morphe.extension.shared.settings.preference.ImmediateAction) preference).actsOnTap();
        if (widget != null && !acts && !(preference instanceof SwitchPreference) && preference.isSelectable()) {
            widget.setVisibility(View.VISIBLE);
            if (widget.findViewWithTag("hushfeed_chevron") == null) {
                ImageView arrow = new ImageView(row.getContext());
                arrow.setTag("hushfeed_chevron");
                arrow.setImageDrawable(new SettingsMenuPreference.ChevronDrawable(row.getContext()));
                arrow.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                android.widget.LinearLayout.LayoutParams params = new android.widget.LinearLayout.LayoutParams(
                        SettingsUi.dp(row.getContext(), 18), SettingsUi.dp(row.getContext(), 24));
                params.gravity = Gravity.CENTER_VERTICAL;
                params.setMarginStart(SettingsUi.dp(row.getContext(), 12));
                widget.addView(arrow, params);
            }
        } else if (widget != null) {
            // Rows are recycled, and one class can make both kinds: Back up and Restore open a
            // file picker and keep the chevron, Reset and Undo act and do not. Adding one and
            // never taking it away meant "Reset settings" wore a chevron as soon as "Back up
            // settings" had scrolled past it, which is the promise this exists to stop making.
            View stale = widget.findViewWithTag("hushfeed_chevron");
            if (stale != null) {
                widget.removeView(stale);
                // The frame is here for the chevron on these rows, and an empty one still takes
                // width. Preference hides it when the view is built, which reuse skips.
                if (widget.getChildCount() == 0) widget.setVisibility(View.GONE);
            }
        }
        return row;
    }
}
