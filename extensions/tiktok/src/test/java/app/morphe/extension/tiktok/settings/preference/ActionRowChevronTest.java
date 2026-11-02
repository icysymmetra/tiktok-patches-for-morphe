package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import android.app.Activity;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListAdapter;
import android.widget.ListView;

import app.morphe.extension.shared.Utils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * The chevron is the ">" that means "this opens a page". It was appended to every selectable row
 * that was not a switch, so "Start today over", which ends a running hold, looked exactly like
 * the row that opens Diagnostics.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class ActionRowChevronTest {
    private Activity activity;

    @Before public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        activity = Robolectric.buildActivity(Activity.class).setup().get();
    }

    @Test public void aRowThatActsOnTheTapHasNoChevronAndOneThatOpensAPageStillDoes() {
        Preference opensAPage = new Preference(activity);
        opensAPage.setKey("opens_a_page");
        opensAPage.setTitle("Diagnostics");

        PreferenceScreen screen = screenWith(opensAPage, new StartTodayOverPreference(activity),
                new ClearSeenVideoHistoryPreference(activity));

        assertNotNull("the control row lost its chevron too, so this proves nothing",
                chevronOf(screen, 0));
        assertNull("Start today over still looks like a row that opens a page",
                chevronOf(screen, 1));
        assertNull("Clear seen videos still looks like a row that opens a page",
                chevronOf(screen, 2));
    }

    @Test public void aRowThatActsDoesNotInheritAChevronFromTheRowItWasRecycledFrom() {
        // One class makes both kinds of row, and a ListView recycles freely between views of
        // the same type. The chevron was only ever added, never taken away, so "Reset settings"
        // wore one as soon as a row that opens a picker had scrolled past it.
        Preference opensAPage = new Preference(activity);
        opensAPage.setKey("opens_a_page");
        opensAPage.setTitle("Diagnostics");
        PreferenceScreen screen = screenWith(opensAPage, new StartTodayOverPreference(activity));

        ListAdapter delegate = screen.getRootAdapter();
        SettingsListAdapter adapter = new SettingsListAdapter(delegate);
        try {
            ListView parent = new ListView(activity);
            View pageRow = adapter.getView(0, null, parent);
            assertNotNull("the control row lost its chevron, so this proves nothing",
                    chevron(pageRow));

            // The same view handed back for the acting row, which is what scrolling does.
            View actingRow = adapter.getView(1, pageRow, parent);
            assertNull("a recycled row kept the chevron of the row before it", chevron(actingRow));
        } finally {
            adapter.dispose();
        }
    }

    private static View chevron(View row) {
        ViewGroup widget = row.findViewById(android.R.id.widget_frame);
        return widget == null ? null : widget.findViewWithTag("hushfeed_chevron");
    }

    private View chevronOf(PreferenceScreen screen, int position) {
        ListAdapter delegate = screen.getRootAdapter();
        SettingsListAdapter adapter = new SettingsListAdapter(delegate);
        try {
            return chevron(adapter.getView(position, null, new ListView(activity)));
        } finally {
            adapter.dispose();
        }
    }

    private PreferenceScreen screenWith(Preference... rows) {
        HostFragment fragment = new HostFragment();
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment).commit();
        activity.getFragmentManager().executePendingTransactions();
        PreferenceScreen screen = fragment.getPreferenceManager().createPreferenceScreen(activity);
        fragment.setPreferenceScreen(screen);
        for (Preference row : rows) screen.addPreference(row);
        return screen;
    }

    /** A preference fragment only so the framework will hand out a PreferenceScreen. */
    public static class HostFragment extends android.preference.PreferenceFragment {
    }
}
