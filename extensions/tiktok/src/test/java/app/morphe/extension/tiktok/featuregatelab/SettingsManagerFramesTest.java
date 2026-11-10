package app.morphe.extension.tiktok.featuregatelab;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import com.bytedance.ies.abmock.SettingsManager;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/**
 * SettingsManager reads observed through the frames TikTok builds for them (the test's own
 * SettingsManager has the patch's calls where the patch puts them).
 *
 * <p>The recorder used to take the class that called its default hook as the default wrapper's.
 * The hook sits in SettingsManager, so from the first read with a default on, every read without
 * one matched on its own frame, and none of them was recorded again for the rest of the process.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SettingsManagerFramesTest {
    private boolean recorderEnabled;

    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        recorderEnabled = SettingsStatus.featureGateRecorderEnabled;
        SettingsManagerObservationRecorder.clear();
        FeatureGateLabRuntime.reloadRules();
        SettingsManager.VALUES.clear();
    }

    @After
    public void tearDown() {
        SettingsManagerObservationRecorder.clear();
        SettingsManager.VALUES.clear();
        SettingsStatus.featureGateRecorderEnabled = recorderEnabled;
    }

    @Test
    public void aReadWithoutADefaultIsStillRecordedAfterOneWithADefault() throws Exception {
        SettingsManager.VALUES.put("with_default", "server");
        SettingsManager.VALUES.put("without_default", "direct");

        new SettingsManager().readWithDefault("with_default", String.class, "fallback");
        SettingsManager.readWithoutDefault("without_default", String.class);

        List<String> seen = observed();
        assertTrue(seen.toString(), seen.contains("without_default (Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;"));
        // The read with a default passes through the getter without one on its way. It is one
        // read, recorded once, under the getter it was made through.
        assertEquals(seen.toString(), 1, seen.stream().filter(line -> line.startsWith("with_default ")).count());
        assertTrue(seen.toString(), seen.contains(
                "with_default (Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;"));
    }

    @Test
    public void theCallerRecordedIsTheCodeThatAsked() throws Exception {
        SettingsManager.VALUES.put("asked_here", "value");
        new SettingsManager().readWithDefault("asked_here", String.class, "fallback");

        JSONObject observation = SettingsManagerObservationRecorder.exportJson().getJSONObject(0);
        String caller = observation.getString("caller");
        assertTrue(caller, caller.startsWith(getClass().getName() + "#theCallerRecordedIsTheCodeThatAsked("));
    }

    private static List<String> observed() throws Exception {
        JSONArray all = SettingsManagerObservationRecorder.exportJson();
        List<String> result = new ArrayList<>();
        for (int index = 0; index < all.length(); index++) {
            JSONObject item = all.getJSONObject(index);
            result.add(item.getString("key") + " " + item.getString("settings_manager_method_descriptor"));
        }
        return result;
    }
}
