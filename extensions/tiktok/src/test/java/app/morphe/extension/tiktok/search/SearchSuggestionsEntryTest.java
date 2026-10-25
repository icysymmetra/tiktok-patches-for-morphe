package app.morphe.extension.tiktok.search;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.LinkedHashMap;
import java.util.Map;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class SearchSuggestionsEntryTest {
    @Before
    public void setUp() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        BaseSettings.DEBUG.save(false);
        SettingsStatus.hideSearchSuggestionsEnabled = true;
        Settings.HIDE_SEARCH_SUGGESTIONS.save(true);
    }

    @After
    public void tearDown() {
        Settings.HIDE_SEARCH_SUGGESTIONS.resetToDefault();
        SettingsStatus.hideSearchSuggestionsEnabled = false;
    }

    /**
     * Issue #21: the search rewards service is withheld only by its own switch, only while the
     * patch that hooks it is applied, and never as a side effect of hiding suggestions.
     */
    @Test
    public void searchRewardsFollowTheirOwnSwitchAndNeedThePatch() {
        try {
            assertFalse("hiding suggestions must not take the rewards with it",
                    SearchSuggestions.shouldHideRewards());
            Settings.HIDE_SEARCH_REWARDS.save(true);
            assertTrue(SearchSuggestions.shouldHideRewards());
            Settings.HIDE_SEARCH_SUGGESTIONS.save(false);
            assertTrue("rewards hiding depended on the suggestions switch", SearchSuggestions.shouldHideRewards());
            SettingsStatus.hideSearchSuggestionsEnabled = false;
            assertFalse("an unpatched build answered for a hook it does not have",
                    SearchSuggestions.shouldHideRewards());
            assertEquals(Boolean.FALSE, Settings.HIDE_SEARCH_REWARDS.defaultValue);
            assertTrue("the getters cache their answer", Settings.HIDE_SEARCH_REWARDS.rebootApp);
        } finally {
            Settings.HIDE_SEARCH_REWARDS.resetToDefault();
        }
    }

    /** Issue #21: a downloaded rewards feature reads as missing only while the switch is on. */
    @Test
    public void rewardsFeatureReadsAsMissingOnlyWhileHidden() {
        try {
            assertTrue(SearchSuggestions.filterRewardsLoaded(true));
            assertFalse("a missing feature stays missing", SearchSuggestions.filterRewardsLoaded(false));
            Settings.HIDE_SEARCH_REWARDS.save(true);
            assertFalse(SearchSuggestions.filterRewardsLoaded(true));
            assertFalse(SearchSuggestions.filterRewardsLoaded(false));
            SettingsStatus.hideSearchSuggestionsEnabled = false;
            assertTrue("an unpatched build answered for a hook it does not have",
                    SearchSuggestions.filterRewardsLoaded(true));
        } finally {
            Settings.HIDE_SEARCH_REWARDS.resetToDefault();
        }
    }

    @Test
    public void rawFirstScreenCacheDoesNotRestoreRecommendationsAfterRestart() throws Exception {
        assertCacheSuppressed(false);
    }

    @Test
    public void templateFirstScreenCacheDoesNotRestoreRecommendationsAfterRestart() throws Exception {
        assertCacheSuppressed(true);
    }

    private void assertCacheSuppressed(boolean template) throws Exception {
        NativeSearchEntry entry = new NativeSearchEntry();
        JSONObject data = entry.initialData(true, template);
        if (template) assertEquals(entry.history.toString(), data.get("historyList"));
        else assertSame("The same native history list reaches the page", entry.history, data.get("historyList"));
        assertEquals("No persisted recommendation read may bypass the disabled request", 0, entry.cacheReads);
        assertFalse(data.has(template ? "data" : "guessSearchData"));
    }

    @Test
    public void initialLynxParametersSuppressSuggestionsWithoutChangingOtherKeys() throws Exception {
        NativeSearchEntry entry = new NativeSearchEntry();
        Map<String, Object> parameters = entry.initialParameters();
        assertSame(entry.unrelated, parameters.get("show_most_visited_account"));
        assertEquals(0, parameters.get("show_suggest_search_words"));
        assertSame(Boolean.FALSE, parameters.get("is_lynx_request_suggest"));
        assertEquals(4, parameters.size());
        assertSame(entry.nativeParameters.get("intermediate_show_trending_billboard"),
                parameters.get("intermediate_show_trending_billboard"));
    }

    @Test
    public void reentryEventDisablesSuggestionsAndRetainsHistory() throws Exception {
        NativeSearchEntry entry = new NativeSearchEntry();
        JSONObject event = entry.reenter(1);
        assertSame(entry.history, entry.historyUpdate.get("historyList"));
        assertEquals("searchTransferEnter must not restore the native visible value", 0,
                event.getInt("show_suggest_search_words"));
        assertFalse(event.getBoolean("is_lynx_request_suggest"));
    }

    @Test
    public void disabledOrUnpatchedEntriesKeepNativeValuesAndCachedHistory() throws Exception {
        for (boolean patchApplied : new boolean[] {false, true}) {
            SettingsStatus.hideSearchSuggestionsEnabled = patchApplied;
            Settings.HIDE_SEARCH_SUGGESTIONS.save(!patchApplied);
            NativeSearchEntry entry = new NativeSearchEntry();
            for (Map.Entry<String, Object> value : entry.nativeParameters.entrySet()) {
                assertSame(value.getValue(), entry.initialParameters().get(value.getKey()));
            }
            for (int value : new int[] {0, 1, 7}) {
                assertEquals(value, entry.reenter(value).getInt("show_suggest_search_words"));
            }
            assertTrue(entry.initialData(true, false).has("guessSearchData"));
            JSONObject template = entry.initialData(true, true);
            assertTrue(template.has("data"));
            assertEquals(entry.history.toString(), template.get("historyList"));
            assertEquals(2, entry.cacheReads);
            assertFalse(entry.initialData(false, false).has("guessSearchData"));
            assertEquals(2, entry.cacheReads);
        }
    }

    @Test
    public void unrelatedInitialParametersRetainTheirNativeTypesWhenHiding() throws Exception {
        NativeSearchEntry entry = new NativeSearchEntry();
        for (Object value : new Object[] {null, Boolean.TRUE, Integer.valueOf(9), "native", new Object()}) {
            entry.nativeParameters.put("history_setting", value);
            entry.nativeParameters.put(null, value);
            Map<String, Object> parameters = entry.initialParameters();
            assertTrue(parameters.containsKey("history_setting"));
            assertTrue(parameters.containsKey(null));
            assertSame(value, parameters.get("history_setting"));
            assertSame(value, parameters.get(null));
        }
    }

    /**
     * Native 46.2.3 caller shapes: 0PTA.LIZJ parses cached raw data into TemplateData;
     * rS/LIZIZ wrap it in guessSearchData. Both keep historyList and consult 0OiL.LIZ.
     * The TemplateData property contains serialized history; raw data holds its JSONArray.
     * This exercises native payload assembly, not the remotely supplied Lynx template.
     */
    private static final class NativeSearchEntry {
        final JSONArray history = new JSONArray();
        final Object unrelated = new Object();
        final Map<String, Object> nativeParameters = new LinkedHashMap<>();
        int cacheReads;
        JSONObject historyUpdate;

        NativeSearchEntry() throws Exception {
            history.put(new JSONObject().put("word", "nasa"));
            history.put(new JSONObject().put("word", "natgeo"));
            nativeParameters.put("show_most_visited_account", unrelated);
            nativeParameters.put("show_suggest_search_words", Integer.valueOf(1));
            nativeParameters.put("intermediate_show_trending_billboard", Integer.valueOf(0));
            nativeParameters.put("is_lynx_request_suggest", Boolean.TRUE);
        }

        JSONObject initialData(boolean nativeCacheEnabled, boolean template) throws Exception {
            JSONObject data = new JSONObject();
            if (SearchSuggestions.filterCachedSuggestions(nativeCacheEnabled)) {
                cacheReads++;
                JSONObject cached = new JSONObject("{\"data\":[\"card slingshot\"]}");
                if (template) data = cached;
                else data.put("guessSearchData", cached);
            }
            return data.put("historyList", template ? history.toString() : history);
        }

        Map<String, Object> initialParameters() {
            Map<String, Object> parameters = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : nativeParameters.entrySet()) {
                parameters.put(entry.getKey(), SearchSuggestions.filterLynxParameter(entry.getKey(), entry.getValue()));
            }
            return parameters;
        }

        JSONObject reenter(int nativeVisibility) throws Exception {
            JSONObject event = new JSONObject()
                    .put("is_lynx_request_suggest", false)
                    .put("show_suggest_search_words", SearchSuggestions.filterReentryVisibility(nativeVisibility));
            // GS sends searchTransferEnter, then updates history through a separate data call.
            historyUpdate = new JSONObject().put("historyList", history);
            return event;
        }
    }
}
