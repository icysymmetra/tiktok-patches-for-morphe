package app.morphe.extension.tiktok.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.TextView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabFragment;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

/**
 * Settings search folds accents away before comparing, so a query of nothing but combining marks
 * folds to nothing. The empty check ran on the query as typed, which such a query passes, and
 * every setting then matched because they all contain the empty string.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class SettingsSearchQueryTest {
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
        statuses.clear();
    }

    @Test public void aQueryOfNothingButAccentMarksMatchesNothing() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        int all = search(search, "").size();
        assertTrue("the search page showed no rows at all", all >= 1);

        // U+0301 on its own: not empty as typed, nothing at all once folded.
        assertEquals("an accent mark on its own matched the whole catalogue",
                all, search(search, "́").size());
    }

    @Test public void anOrdinaryQueryStillNarrowsTheList() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        int indexed = indexSize(search);
        int matches = search(search, "comment").size();

        assertTrue("the index is too small to tell narrowing from matching everything", indexed > 20);
        assertTrue("nothing matched an ordinary word", matches > 1);
        // Against the whole catalogue, not against the one row the empty state shows: comparing
        // with the empty query only proved the word produced two rows rather than one.
        assertTrue("the query matched the whole catalogue: " + matches + " rows of " + indexed,
                matches < indexed);
    }

    @Test public void groupingTheMenuPreservesTheLeafIndexWithoutIndexingNavigationRows() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        Field index = TikTokPreferenceFragment.class.getDeclaredField("searchIndex");
        index.setAccessible(true);
        java.util.Set<String> sections = new java.util.HashSet<>();
        for (Object result : (java.util.List<?>) index.get(search)) {
            Field key = result.getClass().getDeclaredField("key");
            Field section = result.getClass().getDeclaredField("section");
            key.setAccessible(true);
            section.setAccessible(true);
            String route = String.valueOf(key.get(result));
            assertFalse("a page row became a search result: " + route, route.startsWith("hub_"));
            assertFalse("a section route became a duplicate search result: " + route,
                    route.startsWith("section_"));
            if (section.get(result) != null) sections.add(section.get(result).toString());
        }
        assertEquals("a settings page disappeared from the searchable catalogue",
                java.util.Set.of("FEED_FILTER", "FEED_NAVIGATION", "INTERFACE",
                        "COMMENTS", "DOWNLOADS", "SHARE",
                        "REGION", "BEHAVIOR", "DIAGNOSTICS", "BACKUP"), sections);
    }

    @Test public void filteredSettingsExposeAndAnnounceTheirResultCount() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        search(search, "comment");
        TextView count = search.getView().findViewWithTag("settings_search_result_count");
        assertNotNull("the search field has no result status", count);
        int rows = dynamicRows(search);
        assertEquals(rows == 1 ? "1 result" : rows + " results",
                count.getText().toString());
        assertEquals(View.ACCESSIBILITY_LIVE_REGION_POLITE,
                count.getAccessibilityLiveRegion());

        search(search, "nothing-could-match-this-query");
        assertEquals("0 results", count.getText().toString());
    }

    private static int dynamicRows(TikTokPreferenceFragment fragment) throws Exception {
        Field field = TikTokPreferenceFragment.class.getDeclaredField("searchRows");
        field.setAccessible(true);
        return ((java.util.List<?>) field.get(fragment)).size();
    }

    private static int indexSize(TikTokPreferenceFragment fragment) throws Exception {
        java.lang.reflect.Field field = TikTokPreferenceFragment.class.getDeclaredField("searchIndex");
        field.setAccessible(true);
        return ((java.util.List<?>) field.get(fragment)).size();
    }

    @Test public void anAccentedQueryStillFindsItsUnaccentedSetting() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        assertEquals("folding stopped matching the word it is there for",
                search(search, "comment").size(), search(search, "cómment").size());
    }

    @Test public void theInstalledFeatureGateLabCanBeFoundAndOpened() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        Method build = TikTokPreferenceFragment.class
                .getDeclaredMethod("buildSearchIndex", Context.class, boolean.class);
        build.setAccessible(true);
        Field index = TikTokPreferenceFragment.class.getDeclaredField("searchIndex");
        index.setAccessible(true);
        index.set(search, build.invoke(search, search.getActivity(), true));

        java.util.List<String> titles = search(search, "override gates");
        assertTrue("the installed Lab was absent from settings search: " + titles,
                titles.contains("Feature Gate Lab"));
        Preference result = null;
        for (int position = 0; position < search.getPreferenceScreen().getPreferenceCount(); position++) {
            Preference candidate = search.getPreferenceScreen().getPreference(position);
            if (candidate.getTitle() != null
                    && "Feature Gate Lab".contentEquals(candidate.getTitle())) {
                result = candidate;
                break;
            }
        }
        assertNotNull(result);
        assertTrue(result.getOnPreferenceClickListener().onPreferenceClick(result));
        search.getActivity().getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(search.getActivity().getFragmentManager().findFragmentById(android.R.id.content)
                instanceof FeatureGateLabFragment);
    }

    /** The rows the search page is showing for a query, by title. */
    private static java.util.List<String> search(TikTokPreferenceFragment fragment, String query)
            throws Exception {
        Method update = TikTokPreferenceFragment.class
                .getDeclaredMethod("updateSearchResults", String.class);
        update.setAccessible(true);
        update.invoke(fragment, query);
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        java.util.List<String> titles = new java.util.ArrayList<>();
        PreferenceScreen screen = fragment.getPreferenceScreen();
        for (int index = 0; index < screen.getPreferenceCount(); index++) {
            Preference row = screen.getPreference(index);
            if (row.getTitle() != null) titles.add(row.getTitle().toString());
        }
        return titles;
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

    @Test public void theSearchFindsTheRowsPeopleReachForWhenSomethingHasGoneWrong() throws Exception {
        // The header promises a search of every title, description and category, and every row
        // that acts rather than holds a value was missing from it: the four backup rows because
        // they are added to the section screen rather than into its category, and the rest
        // because a row with no key was skipped. Those are exactly the rows someone goes looking
        // for when they need to undo, clear or report something.
        TikTokPreferenceFragment search = attachSearch();
        String[][] wanted = {
                {"restore", "Restore settings"},
                {"back up", "Back up settings"},
                {"undo", "Undo last restore or reset"},
                {"hook", "Hook status"},
                {"diagnostic", "Export diagnostic report"},
        };
        for (String[] pair : wanted) {
            java.util.List<String> titles = search(search, pair[0]);
            assertTrue("searching \"" + pair[0] + "\" did not find \"" + pair[1] + "\", it found "
                    + titles, titles.contains(pair[1]));
        }
        // The seen video row's title changes with its undo state, so check either one.
        java.util.List<String> seenTitles = search(search, "seen");
        assertTrue("searching \"seen\" did not find the seen videos row, it found "
                + seenTitles,
                seenTitles.contains("Clear seen videos")
                        || seenTitles.contains("Undo clearing seen videos"));
    }

    @Test public void aTitleHitRanksAboveARowThatOnlyNamesItInItsDescription() throws Exception {
        // On the S22, "original photos" listed Photo filename, whose description names Download
        // original photos, above that switch. Both were whole-word hits, so page order decided.
        TikTokPreferenceFragment search = attachSearch();
        java.util.List<String> titles = search(search, "original photos");
        int titleHit = titles.indexOf("Download original photos");
        int descriptionHit = titles.indexOf("Photo filename");
        assertTrue("both rows should match, found " + titles, titleHit >= 0 && descriptionHit >= 0);
        assertTrue("the description hit ranked above the title hit: " + titles,
                titleHit < descriptionHit);
    }

    @Test public void debugAndLogsFindLogDiagnostics() throws Exception {
        // Bug report templates ask for "debug logs", and "debug" answered No matching settings.
        TikTokPreferenceFragment search = attachSearch();
        for (String query : new String[] {"debug", "debugging", "logs", "logging"}) {
            java.util.List<String> titles = search(search, query);
            assertTrue("searching \"" + query + "\" did not find Log diagnostics, it found "
                    + titles, titles.contains("Log diagnostics"));
        }
        assertEquals("the keywords reached another row",
                java.util.List.of("Log diagnostics"), search(search, "debugging"));
    }

    @Config(qualifiers = "de")
    @Test public void theTranslatedKeywordFindsLogDiagnosticsToo() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        assertTrue("the German keyword missed the row",
                search(search, "fehlersuche").contains("Diagnose protokollieren"));
        assertTrue("English stopped matching under a translation",
                search(search, "debug").contains("Diagnose protokollieren"));
    }

    @Test public void theAboutRowIsFoundByItsNameAndByWhatItSays() throws Exception {
        // The row sits on the master menu, which the index never walked, and had no key besides,
        // so "hushfeed" and "version" both answered "No matching settings" on a phone whose
        // About row showed the version. The summary is what carries it, so the summary is the
        // half that has to match.
        TikTokPreferenceFragment search = attachSearch();

        assertTrue("the About row is not indexed by its name",
                search(search, "metra").contains("Metra"));
        assertTrue("the About row's summary is not searched",
                search(search, "github").contains("Metra"));
    }

    /**
     * Build details sat on About Hushfeed outside every walked category, so "build" found nothing
     * and the empty state blamed unticked patches. The hand-indexed rows also all said they were
     * in "Settings", which is no page a reader can find.
     */
    @Test public void handIndexedRowsAreFoundAndNameTheirPage() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        assertTrue("Build details is not indexed", search(search, "build details").contains("Build details"));
        assertEquals("About Metra", category(search, "Build details"));
        assertEquals("About Metra", category(search, "Metra"));
        // Pause sits on the home page, so its line names the heading it's under there.
        assertEquals("Metra", category(search, "Pause Metra"));
    }

    /**
     * A hand-indexed About row has no section to open, so opening it fell through to the master
     * menu. It lands on About Hushfeed with the row as the target.
     */
    @Test public void openingFoundBuildDetailsLandsOnAboutHushfeed() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        search(search, "build details");
        Preference result = null;
        PreferenceScreen screen = search.getPreferenceScreen();
        for (int position = 0; position < screen.getPreferenceCount(); position++) {
            Preference candidate = screen.getPreference(position);
            if (candidate.getTitle() != null && "Build details".contentEquals(candidate.getTitle())) {
                result = candidate;
                break;
            }
        }
        assertNotNull(result);
        assertTrue(result.getOnPreferenceClickListener().onPreferenceClick(result));
        search.getActivity().getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        android.app.Fragment opened = search.getActivity().getFragmentManager()
                .findFragmentById(android.R.id.content);
        assertTrue(opened instanceof TikTokPreferenceFragment);
        assertEquals("ABOUT", opened.getArguments().getString("morphe_settings_hub"));
        assertEquals("action_build_details", opened.getArguments().getString("morphe_settings_target_key"));
    }

    /** What's new leaves About Hushfeed once its notes are read, and its result goes with it. */
    @Test public void whatsNewIsNotFoundWhenThereIsNothingNew() throws Exception {
        // Unpatched, the release version is empty, so nothing is pending.
        TikTokPreferenceFragment search = attachSearch();
        assertFalse(search(search, "release notes").contains("What's new"));
    }

    /** While its notes are unread, What's new is found under About Hushfeed and opens there. */
    @Test public void whatsNewIsFoundWhileItsNotesAreUnread() throws Exception {
        // A release with its own bundled notes. With nothing dismissed yet, only the installed
        // release's section counts as unread, so a version with no section would find nothing.
        TikTokPreferenceFragment.setReleaseVersionForTests("0.8.1");
        try {
            TikTokPreferenceFragment search = attachSearch();
            assertTrue("What's new is not indexed while pending",
                    search(search, "release notes").contains("What's new"));
            assertEquals("About Metra", category(search, "What's new"));

            Preference result = null;
            PreferenceScreen screen = search.getPreferenceScreen();
            for (int position = 0; position < screen.getPreferenceCount(); position++) {
                Preference candidate = screen.getPreference(position);
                if (candidate.getTitle() != null && "What's new".contentEquals(candidate.getTitle())) {
                    result = candidate;
                    break;
                }
            }
            assertNotNull(result);
            assertTrue(result.getOnPreferenceClickListener().onPreferenceClick(result));
            search.getActivity().getFragmentManager().executePendingTransactions();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            android.app.Fragment opened = search.getActivity().getFragmentManager()
                    .findFragmentById(android.R.id.content);
            assertTrue(opened instanceof TikTokPreferenceFragment);
            assertEquals("ABOUT", opened.getArguments().getString("morphe_settings_hub"));
            assertEquals("action_release_notes", opened.getArguments().getString("morphe_settings_target_key"));
        } finally {
            TikTokPreferenceFragment.setReleaseVersionForTests(null);
        }
    }

    /** "???" folds to nothing, and the page said Start typing under a box with text in it. */
    @Test public void aQueryOfOnlyPunctuationSaysNothingMatched() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        java.util.List<String> titles = search(search, "???");
        assertTrue("punctuation alone was answered with " + titles, titles.contains("No matching settings"));
        assertFalse(titles.contains("Start typing"));
        assertTrue(search(search, "  ").contains("Start typing"));
    }

    private static String category(TikTokPreferenceFragment fragment, String wanted) throws Exception {
        java.lang.reflect.Field field = TikTokPreferenceFragment.class.getDeclaredField("searchIndex");
        field.setAccessible(true);
        for (Object entry : (java.util.List<?>) field.get(fragment)) {
            java.lang.reflect.Field title = entry.getClass().getDeclaredField("title");
            title.setAccessible(true);
            if (!wanted.equals(title.get(entry))) continue;
            java.lang.reflect.Field category = entry.getClass().getDeclaredField("category");
            category.setAccessible(true);
            return (String) category.get(entry);
        }
        throw new AssertionError(wanted + " is not in the index at all");
    }

    @Test public void openingAFoundBackupRowLandsOnDiagnostics() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        java.lang.reflect.Field field = TikTokPreferenceFragment.class.getDeclaredField("searchIndex");
        field.setAccessible(true);
        Object found = null;
        for (Object entry : (java.util.List<?>) field.get(search)) {
            java.lang.reflect.Field title = entry.getClass().getDeclaredField("title");
            title.setAccessible(true);
            if ("Restore settings".equals(title.get(entry))) {
                found = entry;
                break;
            }
        }
        assertNotNull("the Restore settings row is not in the index at all", found);
        java.lang.reflect.Field section = found.getClass().getDeclaredField("section");
        section.setAccessible(true);
        assertEquals("opening it would not land on Backup and restore",
                "BACKUP", ((Enum<?>) section.get(found)).name());
    }
}
