package app.morphe.extension.tiktok;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.settings.IntegerSetting;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.HashSet;
import java.util.Set;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** What a test plants in the settings registry does not outlive the test. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SettingsRegistryRuleTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();
    @Rule public final SettingsRegistryRule settingsRegistry = new SettingsRegistryRule();

    /** A second rule, driven by hand, so each case can look at the registry after it. */
    private final SettingsRegistryRule rule = new SettingsRegistryRule();

    @Test public void aPlantedSettingIsTakenBackOut() throws Throwable {
        int size = Setting.allLoadedSettings().size();
        rule.before();
        IntegerSetting planted = new IntegerSetting("registry_rule_test_planted", 1);
        planted.save(7);
        assertEquals(size + 1, Setting.allLoadedSettings().size());

        rule.after();

        assertEquals("a planted setting stayed in the registry", size, Setting.allLoadedSettings().size());
        assertNull("the planted key still finds a setting", Setting.getSettingFromPath("registry_rule_test_planted"));
        assertEquals("the planted value is still on disk",
                "gone", Setting.preferences.getString("registry_rule_test_planted", "gone"));
    }

    @Test public void aKeyPlantedTwiceLeavesEveryKeyListedOnce() throws Throwable {
        rule.before();
        new IntegerSetting("registry_rule_test_twice", 1);
        new IntegerSetting("registry_rule_test_twice", 2);

        rule.after();

        // Two entries under one key is what made a later backup incomplete.
        Set<String> keys = new HashSet<>();
        for (Setting<?> each : Setting.allLoadedSettings()) {
            assertTrue("the registry lists " + each.key + " twice", keys.add(each.key));
        }
    }

    @Test public void aSettingPlantedUnderARealKeyGivesTheKeyBack() throws Throwable {
        IntegerSetting real = Settings.EDGE_SEEK_SECONDS;
        int size = Setting.allLoadedSettings().size();
        rule.before();
        new IntegerSetting(real.key, 5).withRange(1, 60);
        assertTrue("the planted setting did not take the key, so this checks nothing",
                Setting.getSettingFromPath(real.key) != real);

        rule.after();

        assertSame("the key did not find the real setting again", real, Setting.getSettingFromPath(real.key));
        assertEquals(size, Setting.allLoadedSettings().size());
        assertTrue("the real setting left the registry", Setting.allLoadedSettings().contains(real));
    }
}
