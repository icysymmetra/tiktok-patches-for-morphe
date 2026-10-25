package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.util.AtomicFile;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabStore;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "night")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SettingsBackupUndoGenerationTest {
    private static final String CURRENT = "metra-settings-undo.json";
    private static final String LEGACY = "hushfeed-settings-undo.json";

    private Context context;

    @Before public void setup() throws Exception {
        context = RuntimeEnvironment.getApplication();
        Utils.setContext(context);
        Settings.REGION_SPOOF.get();
        resetSettings();
        FeatureGateLabStore.resetAllLabData();
        deleteUndoGenerations();
        new AtomicFile(new File(context.getFilesDir(), SettingsOperationJournal.FILE_NAME)).delete();
        SettingsOperationJournal.consumeRecoveryNotice();
    }

    @After public void tearDown() {
        deleteUndoGenerations();
        resetSettings();
        Utils.setContext(RuntimeEnvironment.getApplication());
    }

    @Test public void currentBaseIsReadable() throws Exception {
        assertRestoresFrom(CURRENT, false, 11);
    }

    @Test public void currentBackupOnlyIsReadable() throws Exception {
        assertRestoresFrom(CURRENT, true, 12);
    }

    @Test public void legacyBaseIsReadable() throws Exception {
        assertRestoresFrom(LEGACY, false, 13);
    }

    @Test public void legacyBackupOnlyIsReadable() throws Exception {
        assertRestoresFrom(LEGACY, true, 14);
    }

    @Test public void currentBackupWinsWhenLegacyBaseAlsoExists() throws Exception {
        writeGeneration(LEGACY, false, snapshot(21));
        writeGeneration(CURRENT, true, snapshot(42));
        Settings.MAX_VIDEO_SECONDS.save(99);

        assertTrue(SettingsBackup.hasUndo(context));
        SettingsBackup.undo(context);

        assertEquals(42, (int) Settings.MAX_VIDEO_SECONDS.get());
    }

    @Test public void newWritesUseCurrentGenerationAndRetireLegacy() throws Exception {
        writeGeneration(LEGACY, false, snapshot(23));
        String replacement = snapshot(71);
        Settings.MAX_VIDEO_SECONDS.save(37);
        String expectedUndo = SettingsBackup.create(false);

        SettingsBackup.restore(context, replacement, true);

        File current = file(CURRENT);
        assertEquals(71, (int) Settings.MAX_VIDEO_SECONDS.get());
        assertTrue("the new undo base was not written", current.isFile());
        assertEquals(expectedUndo, SettingsBackup.read(new AtomicFile(current).openRead()));
        assertFalse("the legacy base survived a verified current write", file(LEGACY).isFile());
        assertFalse("the legacy backup survived a verified current write", backup(LEGACY).isFile());

        SettingsBackup.undo(context);
        assertEquals(37, (int) Settings.MAX_VIDEO_SECONDS.get());
    }

    private void assertRestoresFrom(String name, boolean backupOnly, int expected) throws Exception {
        writeGeneration(name, backupOnly, snapshot(expected));
        Settings.MAX_VIDEO_SECONDS.save(99);

        assertTrue(SettingsBackup.hasUndo(context));
        SettingsBackup.undo(context);

        assertEquals(expected, (int) Settings.MAX_VIDEO_SECONDS.get());
    }

    private String snapshot(int maxVideoSeconds) throws Exception {
        Settings.MAX_VIDEO_SECONDS.save(maxVideoSeconds);
        return SettingsBackup.create(false);
    }

    private void writeGeneration(String name, boolean backupOnly, String text) throws Exception {
        File target = backupOnly ? backup(name) : file(name);
        try (FileOutputStream output = new FileOutputStream(target)) {
            output.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private File file(String name) {
        return new File(context.getFilesDir(), name);
    }

    private File backup(String name) {
        return new File(context.getFilesDir(), name + ".bak");
    }

    private void deleteUndoGenerations() {
        if (context == null) return;
        new AtomicFile(file(CURRENT)).delete();
        new AtomicFile(file(LEGACY)).delete();
    }

    private static void resetSettings() {
        for (Setting<?> setting : Setting.allLoadedSettings()) setting.resetToDefault();
    }
}
