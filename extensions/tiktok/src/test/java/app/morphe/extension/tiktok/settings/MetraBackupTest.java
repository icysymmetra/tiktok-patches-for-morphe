package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.*;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class MetraBackupTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();
    @org.junit.Before public void defaults() {
        Settings.REGION_SPOOF.get();
        for (Setting<?> setting : Setting.allLoadedSettings()) setting.resetToDefault();
    }
    private static SettingsBackup.Snapshot parse(String text) throws Exception {
        Method method = SettingsBackup.class.getDeclaredMethod("parse", String.class);
        method.setAccessible(true);
        try { return (SettingsBackup.Snapshot) method.invoke(null, text); }
        catch (java.lang.reflect.InvocationTargetException error) { throw (Exception) error.getCause(); }
    }

    @Test public void originalClipboardExportMigratesWithoutResettingNewControls() throws Exception {
        Settings.AUTO_ADVANCE.save(true);
        SettingsBackup.Snapshot result = parse("\"hide_ai_content\":true,\"download_video_quality\":\"medium\",\"share_sheet_send_to\":false");
        assertEquals(true, result.values.get(Settings.HIDE_AI_GENERATED));
        assertEquals("medium", result.values.get(Settings.DOWNLOAD_VIDEO_QUALITY));
        assertEquals(true, result.values.get(Settings.HIDE_SHARE_CONTACTS));
        assertEquals(true, result.values.get(Settings.AUTO_ADVANCE));
        assertFalse("Parsing must never write settings", Settings.HIDE_SHARE_CONTACTS.get());
        Settings.AUTO_ADVANCE.resetToDefault();
    }

    @Test public void bothStructuredFormatsReadAndOnlyMetraIsExported() throws Exception {
        JSONObject backup = new JSONObject(SettingsBackup.create(false));
        assertEquals("metra-settings", backup.getString("format"));
        assertNotNull(parse(backup.toString()));
        assertNotNull(parse(backup.put("format", "hushfeed-settings").toString()));
    }

    @Test public void malformedLegacyValuesAndForeignObjectsAreRejectedBeforeWriting() throws Exception {
        assertThrows(SettingsBackup.RejectedBackup.class, () -> parse("{\"share_sheet_send_to\":\"bad\"}"));
        assertThrows(SettingsBackup.RejectedBackup.class, () -> parse("{\"unrelated\":true}"));
        assertThrows(SettingsBackup.RejectedBackup.class, () -> parse("{}"));
        assertThrows(SettingsBackup.RejectedBackup.class, () -> parse("{\"format\":\"future\"}"));
    }

    @Test public void explicitModernValuesWinInStructuredLegacyFiles() throws Exception {
        JSONObject root = new JSONObject(SettingsBackup.create(false));
        root.getJSONObject("settings").put("hide_ai_content", true).put("hide_ai_generated", false);
        root.getJSONArray("setting_keys").put("hide_ai_content");
        assertEquals(false, parse(root.toString()).values.get(Settings.HIDE_AI_GENERATED));
    }
}
