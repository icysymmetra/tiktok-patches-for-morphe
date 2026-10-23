/* Copyright 2026 Metra contributors. */
package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import app.morphe.extension.shared.settings.PausedProcess;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.download.AdvancedDownloadsTest.Gear;
import app.morphe.extension.tiktok.download.QualitySelector;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/** Second-round checks cover additional failure boundaries without changing product behavior. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ExpandedReviewRegressionTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Test public void migrationRetriesFalseBeforeWriteFalseAfterWriteAndExceptionAfterWrite() {
        Context context = RuntimeEnvironment.getApplication();
        SharedPreferences prefs = context.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
        // 0: no memory update; 1: memory changed but disk reports false; 2: commit throws after updating memory.
        for (int scenario = 0; scenario < 3; scenario++) {
            final int failureMode = scenario;
            Settings.HIDE_AI_GENERATED.save(false);
            prefs.edit().clear().putBoolean("hide_ai_content", true).putInt("retained_test_value", 7).commit();
            AtomicInteger commits = new AtomicInteger();
            SharedPreferences wrapped = (SharedPreferences) Proxy.newProxyInstance(
                    prefs.getClass().getClassLoader(), new Class[]{SharedPreferences.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("edit")) return method.invoke(prefs, args);
                        SharedPreferences.Editor editor = prefs.edit();
                        return Proxy.newProxyInstance(editor.getClass().getClassLoader(),
                                new Class[]{SharedPreferences.Editor.class}, (editorProxy, call, values) -> {
                                    boolean firstCommit = call.getName().equals("commit") && commits.incrementAndGet() == 1;
                                    if (firstCommit && failureMode == 0) return false;
                                    Object result = call.invoke(editor, values);
                                    if (firstCommit && failureMode == 1) return false;
                                    if (firstCommit && failureMode == 2) throw new IllegalStateException("Simulated commit failure");
                                    return result instanceof SharedPreferences.Editor ? editorProxy : result;
                                });
                    });
            Context failing = new android.content.ContextWrapper(context) {
                @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                    return Setting.PREFERENCES_NAME.equals(name) ? wrapped : super.getSharedPreferences(name, mode);
                }
            };
            MetraLegacySettings.migrate(failing);
            assertFalse("Failed migration appeared complete in scenario " + scenario, prefs.contains("metra_legacy_settings_v1"));
            assertFalse(prefs.contains("hide_ai_generated"));
            assertFalse(Settings.HIDE_AI_GENERATED.savedValue());
            assertEquals(7, prefs.getInt("retained_test_value", 0));
            assertTrue(prefs.getBoolean("hide_ai_content", false));
            MetraLegacySettings.migrate(failing);
            assertTrue(prefs.getBoolean("metra_legacy_settings_v1", false));
            assertTrue(Settings.HIDE_AI_GENERATED.savedValue());
            assertEquals(7, prefs.getInt("retained_test_value", 0));
        }
    }

    @Test public void everyDownloadQualityResumesItsChoiceAndBackupsKeepItWhilePaused() throws Exception {
        Gear small = new Gear("normal_720_0", 200, "https://example.com/720");
        Gear large = new Gear("normal_1080_0", 300, "https://example.com/1080");
        Object video = new Object() {
            public List<?> getRawBitRate() { return List.of(small, large); }
            public boolean hasDashBitrate() { return false; }
        };
        try {
            for (String mode : new String[]{"auto", "highest", "lowest", "medium", "720"}) {
                PausedProcess.set(false);
                assertTrue(Settings.DOWNLOAD_VIDEO_QUALITY.save(mode));
                Object address = QualitySelector.download(video);
                assertEquals("auto".equals(mode), address == null);
                PausedProcess.set(true);
                assertNull(QualitySelector.download(video));
                assertEquals(mode, Settings.DOWNLOAD_VIDEO_QUALITY.savedValue());
                JSONObject backup = new JSONObject(SettingsBackup.create(false));
                assertEquals(mode, backup.getJSONObject("settings").getString("download_video_quality"));
                PausedProcess.set(false);
                assertSame(address, QualitySelector.download(video));
            }
        } finally {
            PausedProcess.set(false);
            Settings.DOWNLOAD_VIDEO_QUALITY.resetToDefault();
        }
    }
}
