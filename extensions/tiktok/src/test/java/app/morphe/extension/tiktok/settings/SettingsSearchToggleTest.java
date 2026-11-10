package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.SettingsContextRule;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/** A plain switch the search finds is flipped in the results; one with logic of its own opens its page. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class SettingsSearchToggleTest {
    @Rule public final SettingsContextRule settingsContext = new SettingsContextRule();

    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
    }

    @After public void tearDown() throws Exception {
        for (Map.Entry<Field, Boolean> entry : statuses.entrySet()) {
            entry.getKey().setBoolean(null, entry.getValue());
        }
        Settings.BLOCK_ADVERTISING_ID.resetToDefault();
    }

    @Test public void aPlainSwitchFoundBySearchFlipsWhereItStands() throws Exception {
        Settings.BLOCK_ADVERTISING_ID.save(false);
        TikTokPreferenceFragment search = attachSearch();
        Preference row = result(search, "advertising id", "Block the advertising ID");

        assertTrue("a plain switch still opens its page instead of flipping", row instanceof SwitchPreference);
        assertEquals(Settings.BLOCK_ADVERTISING_ID.key, row.getKey());
        assertTrue("the result lost the page it comes from",
                String.valueOf(row.getSummary()).startsWith("App behavior"));
        assertFalse(((SwitchPreference) row).isChecked());

        Method click = Preference.class.getDeclaredMethod("onClick");
        click.setAccessible(true);
        click.invoke(row);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        assertTrue(((SwitchPreference) row).isChecked());
        assertTrue("the flip never reached the setting", Settings.BLOCK_ADVERTISING_ID.get());
        assertSame("flipping a result left the search page", search,
                search.getActivity().getFragmentManager().findFragmentById(android.R.id.content));
    }

    @Test public void aSwitchWithAHandlerOfItsOwnStillOpensItsPage() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        Preference row = result(search, "speed tests", "Stop TikTok's speed tests");
        assertFalse("a switch whose change starts or stops a job was flipped outside its page",
                row instanceof SwitchPreference);
    }

    private static Preference result(TikTokPreferenceFragment fragment, String query, String title)
            throws Exception {
        Method update = TikTokPreferenceFragment.class.getDeclaredMethod("updateSearchResults", String.class);
        update.setAccessible(true);
        update.invoke(fragment, query);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        PreferenceScreen screen = fragment.getPreferenceScreen();
        for (int index = 0; index < screen.getPreferenceCount(); index++) {
            Preference row = screen.getPreference(index);
            if (row.getTitle() != null && title.contentEquals(row.getTitle())) return row;
        }
        assertNotNull("searching \"" + query + "\" did not find " + title, null);
        return null;
    }

    private static TikTokPreferenceFragment attachSearch() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putBoolean("morphe_settings_search", true);
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return fragment;
    }
}
