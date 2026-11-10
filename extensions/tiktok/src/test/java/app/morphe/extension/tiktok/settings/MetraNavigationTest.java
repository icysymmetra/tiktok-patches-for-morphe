package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.*;
import android.app.Activity;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceGroup;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class MetraNavigationTest {
    @Rule public final SettingsContextRule settings = new SettingsContextRule();

    @Test public void mergedPagesHaveNoDuplicateSettingKeysAndLegacyRoutesStillOpenThem() throws Exception {
        Map<Field, Boolean> previous = new LinkedHashMap<>();
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                previous.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
        try (var owner = Robolectric.buildActivity(SettingsPagesTest.PageActivity.class).setup().visible()) {
            Activity activity = owner.get();
            Utils.setContext(activity);
            for (String route : new String[]{"BEHAVIOR", "INTERFACE", "PLAYBACK", "PRIVACY", "SCREEN_TIME", "INBOX", "SHARE_SHEET"}) {
                TikTokPreferenceFragment page = new TikTokPreferenceFragment();
                Bundle args = new Bundle();
                args.putString("morphe_settings_section", route);
                page.setArguments(args);
                activity.getFragmentManager().beginTransaction().replace(android.R.id.content, page).commit();
                activity.getFragmentManager().executePendingTransactions();
                Set<String> keys = new HashSet<>();
                unique(page.getPreferenceScreen(), keys);
                assertTrue(route + " should contain real controls", keys.size() > 3);
            }
        } finally {
            for (Map.Entry<Field, Boolean> item : previous.entrySet()) item.getKey().setBoolean(null, item.getValue());
        }
    }

    private static void unique(PreferenceGroup group, Set<String> keys) {
        for (int i = 0; i < group.getPreferenceCount(); i++) {
            Preference row = group.getPreference(i);
            if (row.getKey() != null) assertTrue("Duplicate key in merged page: " + row.getKey(), keys.add(row.getKey()));
            if (row instanceof PreferenceGroup) unique((PreferenceGroup) row, keys);
        }
    }
}
