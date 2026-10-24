package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceScreen;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.SettingsBackup;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * Undo is offered on the Diagnostics page whether or not there is anything to undo. On a clean
 * install tapping it reported that the settings could not be restored, which reads as something
 * having broken rather than as there being nothing there.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class UndoRowTest {
    private static final int RESET = 7313, UNDO = 7314;
    private Activity activity;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        undoFile().delete();
    }

    @Test public void undoIsOfferedOnlyWhenThereIsSomethingToUndo() throws Exception {
        Preference row = undoRow();
        assertFalse("Undo is offered on a clean install", row.isEnabled());

        try (FileOutputStream output = new FileOutputStream(undoFile())) {
            output.write("{}".getBytes(StandardCharsets.UTF_8));
        }
        assertTrue("the undo copy is there and the row is still greyed out",
                undoRow().isEnabled());
    }

    @Test public void anEmptyUndoSaysSoRatherThanReportingAFailure() throws Exception {
        java.lang.reflect.Method message = SettingsBackupPreference.class
                .getDeclaredMethod("failureMessage", int.class, Exception.class);
        message.setAccessible(true);

        Exception missing = new java.io.FileNotFoundException("hushfeed-settings-undo.json");
        assertTrue("an empty undo reads as a breakage",
                "Nothing to undo yet.".equals(message.invoke(null, UNDO, missing)));
        // A reset that really failed still says so, as a reset rather than a restore.
        assertTrue("a real failure was softened into the empty message",
                "Couldn't reset the settings. Try again.".equals(
                        message.invoke(null, RESET, new IllegalStateException("broken"))));
        assertTrue("a failed undo was reported as a restore",
                "Couldn't undo the last change. Try again.".equals(
                        message.invoke(null, UNDO, new IllegalStateException("broken"))));
    }

    /** A Reset or an Undo that stopped halfway names itself, not a restore nobody asked for. */
    @Test public void aResetOrUndoThatStoppedHalfwayNamesItself() throws Exception {
        java.lang.reflect.Method message = SettingsBackupPreference.class
                .getDeclaredMethod("failureMessage", int.class, Exception.class);
        message.setAccessible(true);
        java.lang.reflect.Constructor<SettingsBackup.RestoreException> make =
                SettingsBackup.RestoreException.class.getDeclaredConstructor(Throwable.class,
                        SettingsBackup.Failure.class, boolean.class, boolean.class);
        make.setAccessible(true);
        Exception halfway = make.newInstance(new IllegalStateException("interrupted"),
                SettingsBackup.Failure.RECOVERY_REQUIRED, false, true);
        Exception stuck = make.newInstance(new IllegalStateException("interrupted"),
                SettingsBackup.Failure.RECOVERY_REQUIRED, false, false);

        org.junit.Assert.assertEquals(
                "Reset didn't finish. Some settings may still be changed. Use Undo to put them back.",
                message.invoke(null, RESET, halfway));
        org.junit.Assert.assertEquals("Reset didn't finish. Some settings may still be changed.",
                message.invoke(null, RESET, stuck));
        org.junit.Assert.assertEquals(
                "Undo didn't finish. Some settings may still be changed. Try Undo again.",
                message.invoke(null, UNDO, halfway));
        org.junit.Assert.assertEquals("Undo didn't finish. Some settings may still be changed.",
                message.invoke(null, UNDO, stuck));
        // A restore from a file keeps its own words.
        assertTrue(((String) message.invoke(null, 7312, halfway)).startsWith("Restore failed."));
    }

    /** The real Undo row, built the way the Backup and restore page builds it. */
    private Preference undoRow() {
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putString("morphe_settings_section", "BACKUP");
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        PreferenceScreen screen = fragment.getPreferenceScreen();
        Preference row = screen == null ? null : screen.findPreference("settings_backup_" + UNDO);
        if (row == null) throw new AssertionError("the Backup and restore page has no Undo row");
        return row;
    }

    private File undoFile() {
        return new File(Utils.getContext().getFilesDir(), "hushfeed-settings-undo.json");
    }
}
