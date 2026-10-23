/*
 * Forked from MorpheApp/morphe-patches (GPL-3.0), by way of
 * icysymmetra/tiktok-patches-for-morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Imported carrying no notice of its own. Morphe hard forked ReVanced, so parts of
 * this file may originate there.
 */
package app.morphe.extension.shared.settings.preference;

import android.content.Context;
import android.preference.Preference;
import android.util.AttributeSet;

/**
 * A custom preference that clears the Morphe debug log buffer when clicked.
 * Invokes the {@link LogBufferManager#clearLogBuffer} method.
 */
@SuppressWarnings({"unused", "deprecation"})
public class ClearLogBufferPreference extends Preference implements ImmediateAction {
    @Override public boolean actsOnTap() { return true; }

    private CharSequence clearSummary;
    private CharSequence undoSummary = "Diagnostic data cleared. Tap again to put it back.";

    {
        // A key so the settings search can index this row. Nothing in the settings
        // framework treats it as a setting: a key with no Setting behind it is skipped.
        setKey("action_clear_diagnostic_data");
        setOnPreferenceClickListener(pref -> {
            if (LogBufferManager.canUndoClear()) {
                LogBufferManager.UndoResult result = LogBufferManager.undoClear();
                if (result != LogBufferManager.UndoResult.FAILED && clearSummary != null) {
                    setSummary(clearSummary);
                }
                return true;
            }

            if (clearSummary == null) clearSummary = getSummary();
            LogBufferManager.clearLogBuffer();
            if (LogBufferManager.canUndoClear()) setSummary(undoSummary);
            return true;
        });
    }

    /** Supplies the two row summaries in the bundle's language. */
    public final void setClearAndUndoSummaries(CharSequence clearSummary, CharSequence undoSummary) {
        this.clearSummary = clearSummary;
        this.undoSummary = undoSummary;
        setSummary(LogBufferManager.canUndoClear() ? undoSummary : clearSummary);
    }

    public ClearLogBufferPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public ClearLogBufferPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public ClearLogBufferPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public ClearLogBufferPreference(Context context) {
        super(context);
    }
}
