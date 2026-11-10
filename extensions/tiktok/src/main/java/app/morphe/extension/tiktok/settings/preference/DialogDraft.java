/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.settings.preference;

import android.app.Dialog;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.preference.Preference;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * The choices an open editor dialog hasn't saved yet, kept through an Activity recreation.
 *
 * <p>A rotation, a resize or a dark mode switch rebuilds the settings screen. DialogPreference
 * then builds the dialog's view again from the saved settings before it restores the view
 * hierarchy, and these editors make their rows and fields without ids that survive that (the
 * field labels give them generated ones, which change each time), so whatever was picked or typed
 * used to be lost. An editor puts its draft in {@link #values} while the dialog is showing, and
 * reads it back once the rebuilt dialog is up. Nothing here is saved to the settings: Cancel still
 * leaves them as they were.
 */
@SuppressWarnings("deprecation")
final class DialogDraft extends Preference.BaseSavedState {
    final Bundle values;

    DialogDraft(Parcelable superState, Bundle values) {
        super(superState);
        this.values = values;
    }

    DialogDraft(Parcel source) {
        super(source);
        values = source.readBundle(DialogDraft.class.getClassLoader());
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeBundle(values);
    }

    /** Whether a draft is worth keeping: only while the editor's dialog is open. */
    static boolean open(Dialog dialog) {
        return dialog != null && dialog.isShowing();
    }

    private static final String PICKS = "picks";
    private static final String QUERY = "query";

    /** A checklist's draft: the keys ticked, and the search they were ticked under. */
    static Bundle ofPicks(Collection<String> picked, CharSequence query) {
        Bundle draft = new Bundle();
        draft.putStringArrayList(PICKS, new ArrayList<>(picked));
        draft.putString(QUERY, query == null ? "" : query.toString());
        return draft;
    }

    static List<String> picks(Bundle draft) {
        List<String> picked = draft.getStringArrayList(PICKS);
        return picked == null ? Collections.emptyList() : picked;
    }

    static String query(Bundle draft) {
        String query = draft.getString(QUERY);
        return query == null ? "" : query;
    }

    public static final Parcelable.Creator<DialogDraft> CREATOR = new Parcelable.Creator<DialogDraft>() {
        @Override
        public DialogDraft createFromParcel(Parcel source) {
            return new DialogDraft(source);
        }

        @Override
        public DialogDraft[] newArray(int size) {
            return new DialogDraft[size];
        }
    };
}
