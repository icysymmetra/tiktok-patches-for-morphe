package app.morphe.extension.tiktok.settings.preference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.FragmentManager;
import android.os.Bundle;
import android.os.Looper;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.view.View;
import android.widget.ListView;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BooleanSetting;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

/**
 * A checklist row's summary names only the boxes that are ticked, so the search index, which
 * reads titles and summaries, could not find an unticked box at all: "counts" answered nothing
 * while Counts under the buttons sat clear inside the Right column row. Every box is a result
 * now, and opening one opens that row's dialog on that box without touching its value.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
@SuppressWarnings("deprecation")
public class SettingsSearchMembersTest {
    private static final String ROW = "Hide buttons in the right column";
    private static final String[] LABELS = {"Avatar and follow button", "Like", "Comments", "Save",
            "Share", "Music disc", "Counts under the buttons"};

    /** The boxes in dialog order. A method, not a field: Settings loads only once setUp has
     *  given Utils its context. */
    private static BooleanSetting[] boxes() {
        return new BooleanSetting[]{Settings.HIDE_RAIL_FOLLOW, Settings.HIDE_RAIL_LIKE,
                Settings.HIDE_RAIL_COMMENTS, Settings.HIDE_RAIL_FAVOURITE, Settings.HIDE_RAIL_SHARE,
                Settings.HIDE_RAIL_MUSIC, Settings.HIDE_RAIL_COUNTS};
    }

    private final Map<Field, Boolean> statuses = new LinkedHashMap<>();
    private ActivityController<Activity> owner;

    @Before public void setUp() throws Exception {
        Utils.setContext(RuntimeEnvironment.getApplication());
        for (Field field : SettingsStatus.class.getDeclaredFields()) {
            if (field.getType() == boolean.class && Modifier.isStatic(field.getModifiers())) {
                field.setAccessible(true);
                statuses.put(field, field.getBoolean(null));
                field.setBoolean(null, true);
            }
        }
        for (BooleanSetting box : boxes()) box.resetToDefault();
    }

    @After public void tearDown() throws Exception {
        if (owner != null) owner.pause().stop().destroy();
        for (BooleanSetting box : boxes()) box.resetToDefault();
        for (Map.Entry<Field, Boolean> entry : statuses.entrySet()) {
            entry.getKey().setBoolean(null, entry.getValue());
        }
        statuses.clear();
    }

    @Test public void everyBoxTickedOrNotOpensOnItselfAndBackReturnsToTheResults() throws Exception {
        // Two ticked and five clear, so both states are searched for.
        Settings.HIDE_RAIL_LIKE.save(true);
        Settings.HIDE_RAIL_SHARE.save(true);
        List<Boolean> saved = values();

        for (int box = 0; box < LABELS.length; box++) {
            TikTokPreferenceFragment search = attachSearch();
            Preference result = memberResult(search, LABELS[box], ROW);
            assertNotNull("searching \"" + LABELS[box] + "\" did not find its box", result);
            assertTrue(result.getOnPreferenceClickListener().onPreferenceClick(result));
            settle();

            FragmentManager manager = owner.get().getFragmentManager();
            TikTokPreferenceFragment page =
                    (TikTokPreferenceFragment) manager.findFragmentById(android.R.id.content);
            assertEquals("the box did not open the Feed screen page",
                    "INTERFACE", page.getArguments().getString("morphe_settings_section"));
            AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull("opening " + LABELS[box] + " opened no dialog", dialog);
            assertTrue("the checklist is not showing for " + LABELS[box], dialog.isShowing());
            assertEquals(ROW, Shadows.shadowOf(dialog).getTitle().toString());

            // Short enough to scroll: the last boxes are in sight only if the dialog moved to them.
            ListView list = layOut(dialog.getListView());
            assertTrue(LABELS[box] + " was scrolled out of sight: rows "
                            + list.getFirstVisiblePosition() + " to " + list.getLastVisiblePosition(),
                    list.getFirstVisiblePosition() <= box && box <= list.getLastVisiblePosition());
            for (int other = list.getFirstVisiblePosition(); other <= list.getLastVisiblePosition(); other++) {
                assertEquals("the dialog shows a box other than as saved",
                        saved.get(other), list.isItemChecked(other));
            }
            assertEquals("opening a box changed a setting", saved, values());

            dialog.cancel();
            settle();
            assertTrue("Back did not return to the results", manager.popBackStackImmediate());
            settle();
            assertTrue(((TikTokPreferenceFragment) manager.findFragmentById(android.R.id.content))
                    .getArguments().getBoolean("morphe_settings_search"));
            assertEquals("leaving the box changed a setting", saved, values());
            owner.pause().stop().destroy();
            owner = null;
        }
    }

    @Test public void theRowTitleStillFindsTheRowAloneAndItsSummaryIsUnchanged() throws Exception {
        Settings.HIDE_RAIL_SHARE.save(true);
        TikTokPreferenceFragment search = attachSearch();
        List<String> titles = search(search, "hide buttons in the right");
        assertEquals("the row's title listed its boxes as well: " + titles, List.of(ROW), titles);
        Preference row = memberResult(search, ROW, "Interface");
        assertNotNull(row);
        assertTrue("the row's summary no longer says what is hidden: " + row.getSummary(),
                row.getSummary().toString().endsWith("Hidden: Share"));
    }

    @Test public void aBoxOfAnAbsentPatchIsNotFound() throws Exception {
        // The Right column row exists only with the video overlays patch.
        statusField("videoOverlaysEnabled").setBoolean(null, false);
        TikTokPreferenceFragment search = attachSearch();
        assertEquals(null, memberResult(search, "Counts under the buttons", ROW));
    }

    @Test @Config(sdk = 28, qualifiers = "de")
    public void aTranslatedQueryFindsTheTranslatedBox() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        Preference result = memberResult(search, "Zahlen unter den Schaltflächen",
                "Schaltflächen in der rechten Spalte ausblenden");
        assertNotNull("the German box label was not searchable", result);
        assertTrue(result.getOnPreferenceClickListener().onPreferenceClick(result));
        settle();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue(dialog.isShowing());
        ListView list = layOut(dialog.getListView());
        assertTrue(list.getLastVisiblePosition() >= 6);
        assertFalse("the clear box opened ticked", list.isItemChecked(6));
    }

    /**
     * "counts" listed Hide verified accounts and Blocked creators, where it sits inside
     * "accounts", above the rows about counts, because results kept page order. Whole words come
     * first now, ticked box or not, and each group keeps page order.
     */
    @Test public void wholeWordHitsComeFirstAndEachGroupKeepsPageOrder() throws Exception {
        for (boolean ticked : new boolean[]{false, true}) {
            Settings.HIDE_RAIL_COUNTS.save(ticked);
            TikTokPreferenceFragment search = attachSearch();
            List<String> shown = shown(search, "counts");
            assertEquals(expectedOrder(search, "counts"), shown);
            assertTrue("nothing was reordered, so this proves nothing",
                    !pageOrder(search, "counts").equals(shown));
            List<String> titles = titlesOf(shown);
            assertTrue("Counts under the buttons came after accounts: " + titles,
                    titles.indexOf("Counts under the buttons") < titles.indexOf("Hide verified accounts"));
            // A ticked box is named in its row's summary, which makes the row a whole-word hit too.
            assertEquals(ticked, titles.contains(ROW));
            if (ticked) assertTrue(titles.indexOf(ROW) < titles.indexOf("Hide verified accounts"));
            owner.pause().stop().destroy();
            owner = null;
        }
    }

    @Test @Config(sdk = 28, qualifiers = "de")
    public void aTranslatedQueryRanksWholeWordsByTheSameRule() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        List<String> expected = expectedOrder(search, "video");
        assertEquals(expected, shown(search, "video"));
        assertTrue("the German query needs hits of both kinds", !pageOrder(search, "video").equals(expected));
    }

    @Test public void aQueryThatMatchesNoWholeWordRanksTitleHitsFirstThenPageOrder() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        List<String> shown = shown(search, "coun");
        assertTrue(shown.size() > 1);
        assertEquals(rankedOrder(search, "coun"), shown);
    }

    @Test public void recreationKeepsAnOpenBoxDialogAndItsUnsavedTicks() throws Exception {
        TikTokPreferenceFragment search = attachSearch();
        Preference result = memberResult(search, "Counts under the buttons", ROW);
        assertTrue(result.getOnPreferenceClickListener().onPreferenceClick(result));
        settle();
        AlertDialog first = ShadowAlertDialog.getLatestAlertDialog();
        ListView list = layOut(first.getListView());
        list.performItemClick(null, 6, 6);
        assertTrue(list.isItemChecked(6));

        owner.recreate();
        settle();
        AlertDialog again = ShadowAlertDialog.getLatestAlertDialog();
        assertTrue("the recreated page dropped the open checklist", again != first && again.isShowing());
        assertTrue("the unsaved tick was lost", layOut(again.getListView()).isItemChecked(6));
        assertFalse("recreation saved an unsaved tick", Settings.HIDE_RAIL_COUNTS.savedValue());

        again.cancel();
        settle();
        owner.recreate();
        settle();
        assertSame("a dialog closed before recreation came back",
                again, ShadowAlertDialog.getLatestAlertDialog());
        assertFalse(Settings.HIDE_RAIL_COUNTS.savedValue());
    }

    private static List<Boolean> values() {
        List<Boolean> values = new ArrayList<>();
        for (BooleanSetting box : boxes()) values.add(box.savedValue());
        return values;
    }

    private static Field statusField(String name) throws Exception {
        Field field = SettingsStatus.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    /** Short enough that the last boxes start out of sight, as on a phone with large text. */
    private static ListView layOut(ListView list) {
        list.measure(View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 1080, 260);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        return list;
    }

    private void settle() {
        if (owner != null) owner.get().getFragmentManager().executePendingTransactions();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    /** The result row titled {@code title} whose second line names {@code context}. */
    private static Preference memberResult(TikTokPreferenceFragment search, String title,
            String context) throws Exception {
        search(search, title);
        PreferenceScreen screen = search.getPreferenceScreen();
        for (int index = 0; index < screen.getPreferenceCount(); index++) {
            Preference row = screen.getPreference(index);
            if (row.getTitle() != null && title.contentEquals(row.getTitle())
                    && row.getSummary() != null && row.getSummary().toString().contains(context)) {
                return row;
            }
        }
        return null;
    }

    private static List<String> search(TikTokPreferenceFragment fragment, String query)
            throws Exception {
        Method update = TikTokPreferenceFragment.class.getDeclaredMethod("updateSearchResults", String.class);
        update.setAccessible(true);
        update.invoke(fragment, query);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        // The result rows only, not the header and field above them.
        Field rows = TikTokPreferenceFragment.class.getDeclaredField("searchRows");
        rows.setAccessible(true);
        List<String> titles = new ArrayList<>();
        for (Object row : (List<?>) rows.get(fragment)) {
            Preference result = (Preference) row;
            if (result.isSelectable()) titles.add(String.valueOf(result.getTitle()));
        }
        return titles;
    }

    /** Each result row as "title, newline, second line", in the order the search shows them. */
    private static List<String> shown(TikTokPreferenceFragment fragment, String query) throws Exception {
        search(fragment, query);
        Field rows = TikTokPreferenceFragment.class.getDeclaredField("searchRows");
        rows.setAccessible(true);
        List<String> shown = new ArrayList<>();
        for (Object row : (List<?>) rows.get(fragment)) {
            Preference result = (Preference) row;
            if (result.isSelectable()) shown.add(result.getTitle() + "\n" + result.getSummary());
        }
        return shown;
    }

    /** Every index entry the query is in, in page order, without any ranking. */
    private static List<String> pageOrder(TikTokPreferenceFragment fragment, String query) throws Exception {
        List<String> all = new ArrayList<>();
        sortHits(fragment, query, new ArrayList<>(), all);
        return all;
    }

    /** The same entries with the ones holding the query as whole words first. */
    private static List<String> expectedOrder(TikTokPreferenceFragment fragment, String query) throws Exception {
        List<List<String>> groups = new ArrayList<>();
        sortHits(fragment, query, groups, new ArrayList<>());
        assertTrue("no whole-word hit for " + query,
                !groups.get(0).isEmpty() || !groups.get(1).isEmpty());
        return flatten(groups);
    }

    /** Whole-word hits first; inside each kind a hit in the title before one only elsewhere. */
    private static List<String> rankedOrder(TikTokPreferenceFragment fragment, String query) throws Exception {
        List<List<String>> groups = new ArrayList<>();
        sortHits(fragment, query, groups, new ArrayList<>());
        return flatten(groups);
    }

    private static List<String> flatten(List<List<String>> groups) {
        List<String> ordered = new ArrayList<>();
        for (List<String> group : groups) ordered.addAll(group);
        return ordered;
    }

    /**
     * Reads the index the search is built from and splits its hits into four groups (whole word
     * in the title, whole word elsewhere, inside a word in the title, inside a word elsewhere),
     * judged here by the folded text's own words rather than by the code under test.
     */
    private static void sortHits(TikTokPreferenceFragment fragment, String query,
            List<List<String>> groups, List<String> all) throws Exception {
        for (int group = 0; group < 4; group++) groups.add(new ArrayList<>());
        Field index = TikTokPreferenceFragment.class.getDeclaredField("searchIndex");
        index.setAccessible(true);
        String folded = app.morphe.extension.tiktok.settings.SearchText.normalize(query);
        List<String> wanted = java.util.Arrays.asList(folded.split(" "));
        for (Object entry : (List<?>) index.get(fragment)) {
            String normalized = (String) field(entry, "normalized");
            if (!normalized.contains(folded)) continue;
            Method summary = entry.getClass().getDeclaredMethod("displaySummary");
            summary.setAccessible(true);
            String text = field(entry, "title") + "\n" + summary.invoke(entry);
            all.add(text);
            boolean wholeWords = java.util.Collections.indexOfSubList(
                    java.util.Arrays.asList(normalized.split(" ")), wanted) >= 0;
            String title = app.morphe.extension.tiktok.settings.SearchText.normalize(
                    (String) field(entry, "title"));
            boolean inTitle = wholeWords
                    ? java.util.Collections.indexOfSubList(
                            java.util.Arrays.asList(title.split(" ")), wanted) >= 0
                    : title.contains(folded);
            groups.get((wholeWords ? 0 : 2) + (inTitle ? 0 : 1)).add(text);
        }
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static List<String> titlesOf(List<String> shown) {
        List<String> titles = new ArrayList<>();
        for (String row : shown) titles.add(row.substring(0, row.indexOf('\n')));
        return titles;
    }

    private TikTokPreferenceFragment attachSearch() {
        owner = Robolectric.buildActivity(Activity.class).setup().visible();
        Activity activity = owner.get();
        TikTokPreferenceFragment fragment = new TikTokPreferenceFragment();
        Bundle arguments = new Bundle();
        arguments.putBoolean("morphe_settings_search", true);
        fragment.setArguments(arguments);
        activity.getFragmentManager().beginTransaction()
                .replace(android.R.id.content, fragment)
                .addToBackStack("search")
                .commit();
        settle();
        return fragment;
    }
}
