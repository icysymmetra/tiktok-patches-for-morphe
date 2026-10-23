/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Parcel;
import android.os.Parcelable;
import android.preference.Preference;
import android.view.View;
import android.widget.ListView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.Utils;
import app.morphe.extension.tiktok.settings.L10n;

/**
 * One row over a family of switches. The dialog is a list of check boxes, one per switch, and
 * the row's second line names the ones that are on. The six right column hides were six rows
 * in a row that each said "Hide the ... button"; one row whose second line says "Hidden: Like,
 * Share" is the same choice read at a glance, and the page is six rows shorter.
 *
 * <p>The row has a key of its own, so search can land on it, but no setting behind it: the
 * settings are the items'. Quick setups finds them on a page through {@link #settings()}.
 */
@SuppressWarnings("deprecation")
public final class SwitchListPreference extends Preference {
    /** One check box: a label already in the reader's language, and the switch it drives. */
    public static final class Item {
        final String label;
        final BooleanSetting setting;

        public Item(String label, BooleanSetting setting) {
            this.label = label;
            this.setting = setting;
        }
    }

    private final List<Item> items;
    private final String description;
    /** The dialog on screen and its unsaved boxes, kept so a recreated screen can reopen both. */
    private AlertDialog openDialog;
    private boolean[] openChoices;

    /**
     * @param title       the row's title, an English key
     * @param description the row's first summary line, an English key
     */
    public SwitchListPreference(Context context, String key, String title, String description,
            List<Item> items) {
        super(context);
        setKey(key);
        setTitle(L10n.t(context, title));
        this.description = L10n.t(context, description);
        this.items = Collections.unmodifiableList(new ArrayList<>(items));
    }

    /** The switches behind the row, for whatever counts settings by row. */
    public List<BooleanSetting> settings() {
        List<BooleanSetting> settings = new ArrayList<>();
        for (Item item : items) settings.add(item.setting);
        return settings;
    }

    /**
     * The description, then the state on a line of its own. Two lines rather than one sentence
     * with the labels written into it: the labels are entries of their own, and a line built
     * from them is read as parts by the translation sweep only when it stands on its own line.
     */
    @Override
    public CharSequence getSummary() {
        List<String> on = new ArrayList<>();
        for (Item item : items) if (item.setting.savedValue()) on.add(item.label);
        String joined = String.join(", ", on);
        String state = on.isEmpty()
                ? L10n.t(getContext(), "Nothing hidden.")
                : L10n.f(getContext(), "Hidden: %1$s", joined);
        return description + "\n" + state;
    }

    /** The check boxes, in dialog order, for the search index. */
    List<Item> items() {
        return items;
    }

    @Override
    protected void onClick() {
        showChoices(null, null);
    }

    /**
     * Opens the dialog the way a tap does, then scrolls to the box for {@code settingKey} and
     * moves focus onto it. A search result for one box lands here, and only the box moves: its
     * value is whatever the reader last saved.
     */
    void showChoicesAt(String settingKey) {
        showChoices(null, settingKey);
    }

    /**
     * @param pending    the boxes as they stood when the screen was torn down, or null for the
     *                   saved values
     * @param focusKey   the setting whose box gets focus, or null
     */
    private void showChoices(boolean[] pending, String focusKey) {
        Context context = getContext();
        CharSequence[] labels = new CharSequence[items.size()];
        boolean[] checked = new boolean[items.size()];
        for (int i = 0; i < items.size(); i++) {
            labels[i] = items.get(i).label;
            checked[i] = pending != null && pending.length == items.size()
                    ? pending[i] : items.get(i).setting.savedValue();
        }
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(getTitle())
                .setMultiChoiceItems(labels, checked, (ignored, which, isChecked) -> checked[which] = isChecked)
                // Apply, not Save: one of the boxes in this dialog is the save button itself,
                // and a dialog whose confirm action reads the same as one of its rows is a
                // dialog people misread. The diagnostics picker says Apply for the same reason.
                .setPositiveButton(L10n.t(context, "Apply"), null)
                .setNegativeButton(L10n.t(context, "Cancel"), null)
                .show();
        openDialog = dialog;
        openChoices = checked;
        dialog.setOnDismissListener(ignored -> {
            if (openDialog == dialog) {
                openDialog = null;
                openChoices = null;
            }
        });
        SettingsUi.styleStandardAlertDialog(dialog);
        int focus = indexOf(focusKey);
        if (focus >= 0) {
            // The list has no rows until its first layout, which is also where the selection
            // takes effect, so the highlight waits for a pass that shows the box.
            ListView list = dialog.getListView();
            list.setSelection(focus);
            list.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                @Override
                public void onLayoutChange(View view, int left, int top, int right, int bottom,
                        int oldLeft, int oldTop, int oldRight, int oldBottom) {
                    if (focus < list.getFirstVisiblePosition() || focus > list.getLastVisiblePosition()) {
                        return;
                    }
                    list.removeOnLayoutChangeListener(this);
                    TikTokPreferenceFragment.highlightRow(list, focus);
                }
            });
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!view.isEnabled()) return;
            Map<Setting<?>, Object> changes = new LinkedHashMap<>();
            for (int i = 0; i < items.size(); i++) {
                BooleanSetting setting = items.get(i).setting;
                if (setting.savedValue() != checked[i]) changes.put(setting, checked[i]);
            }
            if (changes.isEmpty()) {
                dialog.dismiss();
                return;
            }
            setChoicesEnabled(dialog, false);
            boolean accepted = app.morphe.extension.shared.Utils.runOnBackgroundThread(() -> {
                boolean saved = false;
                try {
                    Setting.saveAll(changes);
                    saved = true;
                } catch (java.io.IOException failure) {
                    app.morphe.extension.shared.Logger.printException(
                            () -> "Could not save checklist choices", failure);
                }
                boolean success = saved;
                app.morphe.extension.shared.Utils.runOnMainThread(() -> {
                    // A recreation while the save ran took this dialog's window with the old
                    // activity, and dismissing a window that's gone throws. The restored page
                    // shows its own dialog, so this one only reports.
                    if (!onScreen(dialog)) {
                        if (success) notifyChanged();
                        else reportSaveFailure();
                        return;
                    }
                    setChoicesEnabled(dialog, true);
                    if (success) {
                        notifyChanged();
                        dialog.dismiss();
                    } else reportSaveFailure();
                });
            });
            if (!accepted) {
                setChoicesEnabled(dialog, true);
                reportSaveFailure();
            }
        });
    }

    private int indexOf(String settingKey) {
        if (settingKey == null) return -1;
        for (int i = 0; i < items.size(); i++) {
            if (settingKey.equals(items.get(i).setting.key)) return i;
        }
        return -1;
    }

    /**
     * A dialog open when the activity is recreated comes back with the boxes as they were, the
     * way the platform's own dialog preferences do. Without this a rotation or a theme change
     * dropped the reader's unsaved choices, and a search result that had just opened one box
     * left the page with nothing open.
     */
    @Override
    protected Parcelable onSaveInstanceState() {
        Parcelable superState = super.onSaveInstanceState();
        if (openDialog == null || !openDialog.isShowing() || openChoices == null) {
            return superState;
        }
        OpenState state = new OpenState(superState);
        state.choices = openChoices.clone();
        return state;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        if (!(state instanceof OpenState)) {
            super.onRestoreInstanceState(state);
            return;
        }
        OpenState open = (OpenState) state;
        super.onRestoreInstanceState(open.getSuperState());
        showChoices(open.choices, null);
    }

    private static final class OpenState extends BaseSavedState {
        boolean[] choices;

        OpenState(Parcelable superState) {
            super(superState);
        }

        OpenState(Parcel source) {
            super(source);
            choices = source.createBooleanArray();
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            super.writeToParcel(dest, flags);
            dest.writeBooleanArray(choices);
        }

        public static final Parcelable.Creator<OpenState> CREATOR = new Parcelable.Creator<OpenState>() {
            @Override
            public OpenState createFromParcel(Parcel source) {
                return new OpenState(source);
            }

            @Override
            public OpenState[] newArray(int size) {
                return new OpenState[size];
            }
        };
    }

    private static boolean onScreen(AlertDialog dialog) {
        return dialog.isShowing() && dialog.getWindow() != null
                && dialog.getWindow().getDecorView().isAttachedToWindow();
    }

    private static void setChoicesEnabled(AlertDialog dialog, boolean enabled) {
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(enabled);
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(enabled);
        dialog.getListView().setEnabled(enabled);
    }

    private void reportSaveFailure() {
        app.morphe.extension.shared.Utils.showToastShort(L10n.t(getContext(),
                "Couldn't save these choices. Try again."));
    }

    @Override
    protected void onBindView(View view) {
        super.onBindView(view);
        Utils.setTitleAndSummaryColor(view);
    }
}
