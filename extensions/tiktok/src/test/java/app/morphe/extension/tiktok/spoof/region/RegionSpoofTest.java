package app.morphe.extension.tiktok.spoof.region;

import static org.junit.Assert.*;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.categories.SimSpoofPreferenceCategory;
import app.morphe.extension.tiktok.spoof.sim.SimPreset;
import app.morphe.extension.tiktok.spoof.sim.SimPresets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import org.junit.Before;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {23, 28})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RegionSpoofTest {
    /** The newest API in the @Config above, and the only run that publishes a picture. */
    private static final int PUBLISHED_CAPTURE_SDK = 28;

    @Before public void setup() {
        Utils.setContext(RuntimeEnvironment.getApplication());
        Settings.SIM_SPOOF.save(true);
        Settings.REGION_SPOOF.save(true);
        Settings.REGION_STORE_SPOOF.save(false);
        Settings.REGION_REQUEST_SPOOF.save(false);
        Settings.SIM_SPOOF_ISO.save("jp");
        Settings.SIMSPOOF_MCCMNC.save("44010");
    }
    @After public void tearDown() {
        RegionSpoof.requestDone();
        SettingsStatus.simSpoofEnabled = false;
        SettingsStatus.regionSpoofEnabled = false;
    }

    @Test public void onlyPassportPathsAreSignIns() {
        for (String path : new String[]{"/passport/user/login/", "/passport/mobile/send_code/v1/",
                "//passport/email/register/v2/", "/passport/"}) {
            assertTrue(path, RegionSpoof.isSignIn(path));
        }
        for (String path : new String[]{null, "", "/", "/aweme/v1/feed/", "/aweme/v1/passport/",
                "/passports/", "/passport", "passport/user/login/", "/Passport/user/login/"}) {
            assertFalse(String.valueOf(path), RegionSpoof.isSignIn(path));
        }
    }

    @Test public void theTokenInterceptorsUrlsAreSignInsByTheirPath() {
        for (String url : new String[]{
                "https://api16-normal-useast5.tiktokv.us/passport/token/beat/v2/?aid=1233&ts=1",
                "https://api.tiktokv.com/passport/token/change/",
                "https://api.tiktokv.com/passport/user/logout/#x"}) {
            assertTrue(url, RegionSpoof.isSignIn(RegionSpoof.pathOf(url)));
        }
        for (String url : new String[]{null, "", "https://api.tiktokv.com",
                "https://api.tiktokv.com?next=/passport/user/logout/",
                "https://api.tiktokv.com/aweme/v1/feed/?next=/passport/token/beat/",
                "https://api.tiktokv.com/aweme/v1/passport/"}) {
            assertFalse(String.valueOf(url), RegionSpoof.isSignIn(RegionSpoof.pathOf(url)));
        }
        assertEquals("/passport/token/beat/", RegionSpoof.pathOf("https://h/passport/token/beat/?a=1#b"));
    }

    @Test public void aMarkWhoseEndNeverCameRunsOut() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        // TikTok's fill threw before requestDone() and the thread sends nothing after it.
        RegionSpoof.requestPath("/passport/user/login/");
        assertEquals("US", RegionSpoof.country("US"));
        ShadowSystemClock.advanceBy(
                Duration.ofMillis(RegionSpoof.MARK_LIFETIME_MS - 1));
        assertEquals("still in the fill's time", "US", RegionSpoof.country("US"));
        ShadowSystemClock.advanceBy(Duration.ofMillis(1));
        assertEquals("the mark ran out", "JP", RegionSpoof.country("US"));
        // And it went cleanly: the next sign-in marks the thread again, and its end takes it off.
        RegionSpoof.requestPath("/passport/user/login/");
        assertEquals("US", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        assertEquals("JP", RegionSpoof.country("US"));
    }

    @Test public void aFillInsideASignInsOwnLeavesTheMarkToTheOuterEnd() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        RegionSpoof.requestPath("/passport/user/login/");
        // AppLog's own fill for the same URL, run from inside the handler's.
        RegionSpoof.requestUrl("https://api.tiktokv.com/passport/user/login/?aid=1233");
        assertEquals("US", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        assertEquals("the handler's fill is still a sign-in's", "US", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        assertEquals("JP", RegionSpoof.country("US"));
    }

    @Test public void aSignInAfterAMarkRanOutGetsAFullMarkOfItsOwn() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        RegionSpoof.requestPath("/passport/user/login/");
        ShadowSystemClock.advanceBy(Duration.ofMillis(RegionSpoof.MARK_LIFETIME_MS));
        // The first fill threw before its end. The next sign-in comes after its mark ran out.
        RegionSpoof.requestPath("/passport/user/login/");
        ShadowSystemClock.advanceBy(Duration.ofMillis(RegionSpoof.MARK_LIFETIME_MS - 1));
        assertEquals("US", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        assertEquals("its end takes it off", "JP", RegionSpoof.country("US"));
    }

    @Test public void aTokenRequestMarksTheThreadLikeASignIn() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        RegionSpoof.requestUrl("https://api.tiktokv.com/passport/token/beat/v2/?aid=1233");
        assertEquals("US", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestUrl("https://api.tiktokv.com/aweme/v1/feed/");
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
    }

    @Test public void aSignInKeepsTheRealRegionOnItsOwnThreadOnly() throws Exception {
        Settings.REGION_REQUEST_SPOOF.save(true);
        Settings.REGION_STORE_SPOOF.save(true);
        Locale locale = Locale.US;
        TimeZone zone = TimeZone.getTimeZone("UTC");
        Map<String, String> signIn = requestParams();
        Map<String, String> feed = requestParams();
        String[] elsewhere = new String[1];
        withSystem(Locale.GERMANY, "Europe/Paris", () -> {
            RegionSpoof.requestPath("/passport/user/login/");
            RegionSpoof.requestParams(signIn);
            assertEquals("US", RegionSpoof.country("US"));
            assertEquals("US", RegionSpoof.storeCountry("US"));
            assertSame(locale, RegionSpoof.locale(locale));
            assertSame(zone, RegionSpoof.timeZone(zone));

            // A feed request built on another thread at the same moment keeps the preset.
            try {
                Thread other = new Thread(() -> elsewhere[0] = RegionSpoof.country("US"));
                other.start();
                other.join();
            } catch (InterruptedException error) {
                throw new AssertionError(error);
            }

            RegionSpoof.requestDone();
            assertEquals("JP", RegionSpoof.country("US"));
            assertEquals("JP", RegionSpoof.locale(locale).getCountry());
            RegionSpoof.requestPath("/aweme/v1/feed/");
            RegionSpoof.requestParams(feed);
            RegionSpoof.requestDone();
        });
        // The region TikTok saved and the account's own stay as TikTok set them, and the two
        // copies it took at start-up are what the system says now.
        Map<String, String> expected = requestParams();
        expected.put("sys_region", "DE");
        expected.put("timezone_name", "Europe/Paris");
        assertEquals("a sign-in's fields took the preset", expected, signIn);
        assertEquals("JP", elsewhere[0]);
        assertEquals("JP", feed.get("current_region"));
        assertEquals("440", feed.get("carrier_region_v2"));
        assertEquals("a feed request's copies were replaced", "US", feed.get("sys_region"));
        assertEquals("a feed request's copies were replaced", "Asia/Tokyo", feed.get("timezone_name"));
    }

    @Test public void aSignInOnlyPutsBackTheStartUpCopiesTikTokPutInTheMap() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        withSystem(Locale.GERMANY, "Europe/Paris", () -> {
            RegionSpoof.requestPath("/passport/user/login/");
            Map<String, String> sparse = new HashMap<>();
            sparse.put("aid", "1233");
            RegionSpoof.requestParams(sparse);
            assertEquals("a field TikTok left out was added", 1, sparse.size());

            Map<String, Object> odd = new HashMap<>();
            odd.put("sys_region", 7);
            odd.put("timezone_name", null);
            RegionSpoof.requestParams(odd);
            assertEquals("a value that isn't a String was replaced", 7, odd.get("sys_region"));
            assertNull(odd.get("timezone_name"));
            assertEquals(2, odd.size());

            Map<String, String> onlyZone = new HashMap<>();
            onlyZone.put("timezone_name", "Asia/Tokyo");
            RegionSpoof.requestParams(onlyZone);
            assertEquals("Europe/Paris", onlyZone.get("timezone_name"));
            assertEquals("sys_region was added", 1, onlyZone.size());
            RegionSpoof.requestDone();
        });
    }

    @Test public void aSystemLocaleWithoutACountryLeavesSysRegionOutOfASignIn() {
        // TikTok's start-up cache never takes an empty value, so with no country there'd be no field.
        Settings.REGION_REQUEST_SPOOF.save(true);
        withSystem(Locale.GERMAN, "Europe/Paris", () -> {
            RegionSpoof.requestPath("/passport/user/login/");
            Map<String, String> params = requestParams();
            RegionSpoof.requestParams(params);
            RegionSpoof.requestDone();
            assertFalse(params.containsKey("sys_region"));
            assertEquals("Europe/Paris", params.get("timezone_name"));
            assertEquals(requestParams().size() - 1, params.size());
        });
    }

    @Test public void aSignInGetsTheLiveValuesEvenWhenThePresetIsOffNow() {
        // The copies were made when the preset was on. Switching it off doesn't change them
        // until TikTok restarts, so a sign-in puts them right whatever the preset says.
        Settings.REGION_REQUEST_SPOOF.save(true);
        Settings.REGION_SPOOF.save(false);
        withSystem(Locale.GERMANY, "Europe/Paris", () -> {
            RegionSpoof.requestPath("/passport/user/login/");
            Map<String, String> params = requestParams();
            RegionSpoof.requestParams(params);
            RegionSpoof.requestDone();
            assertEquals("DE", params.get("sys_region"));
            assertEquals("Europe/Paris", params.get("timezone_name"));
            assertEquals("US", params.get("current_region"));
        });
    }

    @Test public void aSignInMapIsLeftAloneWithoutTheRequestSwitch() {
        withSystem(Locale.GERMANY, "Europe/Paris", () -> {
            RegionSpoof.requestPath("/passport/user/login/");
            Map<String, String> params = requestParams();
            RegionSpoof.requestParams(params);
            RegionSpoof.requestDone();
            assertEquals(requestParams(), params);
        });
    }

    @Test public void aUrlBeingBuiltMarksTheThreadLikeAUrlAndANullOneDoesNothing() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        RegionSpoof.requestUrlBuilder(new StringBuilder("https://api.tiktokv.com/passport/user/login/?aid=1233"));
        assertEquals("US", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestUrlBuilder(new StringBuilder("https://api.tiktokv.com/aweme/v1/feed/?next=/passport/"));
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        RegionSpoof.requestUrlBuilder(null);
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestUrl((String) null);
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
    }

    @Test public void aSignInIsLeftAloneWithoutTheRequestSwitchAndAMissedEndIsPutRight() {
        // Without Match region fields in requests a sign-in is spoofed as it always was.
        RegionSpoof.requestPath("/passport/user/login/");
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestDone();

        Settings.REGION_REQUEST_SPOOF.save(true);
        RegionSpoof.requestPath("/passport/user/login/");
        // TikTok's fill threw before the end was called: the thread's next request puts it right.
        RegionSpoof.requestPath("/aweme/v1/feed/");
        assertEquals("JP", RegionSpoof.country("US"));
        RegionSpoof.requestDone();
        RegionSpoof.requestDone();
        assertEquals("JP", RegionSpoof.country("US"));
    }
    @Test public void aKeptAnswerFollowsTheSettingAndEachZoneIsItsOwnCopy() {
        // Answers are kept between calls, because every Locale and TimeZone default in the app
        // comes through here. One must not outlive the setting it was worked out from, and a
        // TimeZone handed out must not be one the next caller sees changed.
        Locale original = Locale.US;
        TimeZone zone = TimeZone.getTimeZone("UTC");
        assertEquals("JP", RegionSpoof.locale(original).getCountry());
        assertSame("the same question was worked out again",
                RegionSpoof.locale(original), RegionSpoof.locale(original));
        TimeZone first = RegionSpoof.timeZone(zone);
        assertEquals("Asia/Tokyo", first.getID());
        first.setRawOffset(0);
        TimeZone second = RegionSpoof.timeZone(zone);
        assertNotSame("a caller could change the zone the next caller gets", first, second);
        assertEquals("a caller's change reached the next caller",
                TimeZone.getTimeZone("Asia/Tokyo").getRawOffset(), second.getRawOffset());

        Settings.SIM_SPOOF_ISO.save("de");
        assertEquals("DE", RegionSpoof.locale(original).getCountry());
        assertEquals("Europe/Berlin", RegionSpoof.timeZone(zone).getID());
        Settings.REGION_SPOOF.save(false);
        assertSame(original, RegionSpoof.locale(original));
        assertSame(zone, RegionSpoof.timeZone(zone));
    }

    @Test public void allPresetsSupplyRealCountryTimezonesWithoutChangingGlobalDefaults() {
        Locale systemLocale = Locale.getDefault();
        TimeZone systemZone = TimeZone.getDefault();
        for (SimPreset preset : SimPresets.PRESETS) {
            Settings.SIM_SPOOF_ISO.save(preset.iso);
            assertTrue(preset.iso, RegionSpoof.validCountry(preset.iso));
            TimeZone result = RegionSpoof.timeZone(systemZone);
            assertEquals(preset.timeZone, result.getID());
        }
        assertEquals(systemLocale, Locale.getDefault());
        assertEquals(systemZone, TimeZone.getDefault());
    }

    @Test public void allPresetsMatchTheBundledTzdbRegionWhenAvailable() {
        if (Build.VERSION.SDK_INT < 24) return;
        TimeZone systemZone = TimeZone.getDefault();
        for (SimPreset preset : SimPresets.PRESETS) {
            Settings.SIM_SPOOF_ISO.save(preset.iso);
            TimeZone result = RegionSpoof.timeZone(systemZone);
            assertEquals(preset.iso.toUpperCase(Locale.ROOT),
                    android.icu.util.TimeZone.getRegion(result.getID()));
        }
    }
    @Test public void localeKeepsLanguageScriptAndExtensionsAndFlagsAreIndependent() {
        Locale original = Locale.forLanguageTag("zh-Hant-TW-u-nu-hanidec");
        Locale changed = RegionSpoof.locale(original);
        assertEquals("JP", changed.getCountry());
        assertEquals(original.getLanguage(), changed.getLanguage());
        assertEquals(original.getScript(), changed.getScript());
        assertEquals(original.getUnicodeLocaleType("nu"), changed.getUnicodeLocaleType("nu"));
        assertEquals("JP", RegionSpoof.country("US"));
        assertEquals("US", RegionSpoof.storeCountry("US"));
        Settings.REGION_STORE_SPOOF.save(true);
        assertEquals("JP", RegionSpoof.storeCountry("US"));
        Settings.REGION_SPOOF.save(false);
        assertEquals("US", RegionSpoof.storeCountry("US"));
        assertSame(original, RegionSpoof.locale(original));
        Settings.REGION_SPOOF.save(true);
        Settings.SIM_SPOOF.save(false);
        assertSame(original, RegionSpoof.locale(original));
        assertEquals("US", RegionSpoof.storeCountry("US"));
    }
    @Test public void invalidCountryAndMissingValuesPreserveNativeInputs() {
        Locale original = Locale.CANADA_FRENCH;
        TimeZone zone = TimeZone.getTimeZone("America/Toronto");
        for (String value : new String[]{"", "zz", "USA", "123", "ja-JP"}) {
            Settings.SIM_SPOOF_ISO.save(value);
            assertFalse(value, RegionSpoof.validCountry(value));
            assertSame(original, RegionSpoof.locale(original));
            assertSame(zone, RegionSpoof.timeZone(zone));
            assertNull(RegionSpoof.country(null));
        }
        Settings.SIM_SPOOF_ISO.save(" jp ");
        assertEquals("JP", RegionSpoof.country(null));
        assertNull(RegionSpoof.locale(null));
        assertNull(RegionSpoof.timeZone(null));
        Settings.SIM_SPOOF_ISO.save("is");
        if (Build.VERSION.SDK_INT >= 24) assertEquals("IS", android.icu.util.TimeZone.getRegion(RegionSpoof.timeZone(zone).getID()));
        else assertSame(zone, RegionSpoof.timeZone(zone));
    }
    @Test public void requestFieldsTakeThePresetOnlyWhereTikTokSentThemAndOnlyWithTheirSwitch() {
        assertEquals(Boolean.FALSE, Settings.REGION_REQUEST_SPOOF.defaultValue);
        Map<String, String> params = requestParams();
        RegionSpoof.requestParams(params);
        assertEquals("the fields changed with their switch off", requestParams(), params);

        Settings.REGION_REQUEST_SPOOF.save(true);
        RegionSpoof.requestParams(params);
        assertEquals("JP", params.get("current_region"));
        assertEquals("JP", params.get("residence"));
        assertEquals("the network country code is the preset's MCC", "440", params.get("carrier_region_v2"));
        // The hub's fields are the getter hooks' to answer, and the rest is not a region.
        assertEquals("US", params.get("carrier_region"));
        assertEquals("US", params.get("sys_region"));
        assertEquals("US", params.get("region"));
        assertEquals("US", params.get("op_region"));
        assertEquals("US", params.get("account_region"));
        assertEquals("en", params.get("app_language"));
        assertEquals("a start-up copy changed on an ordinary request", "Asia/Tokyo", params.get("timezone_name"));
        assertEquals(requestParams().size(), params.size());

        Map<String, String> sparse = new HashMap<>();
        sparse.put("aid", "1233");
        RegionSpoof.requestParams(sparse);
        assertEquals("a field TikTok left out was added", 1, sparse.size());
        RegionSpoof.requestParams(null);
    }

    @Test public void requestFieldsStayWhenThePresetCannotSayWhatToSend() {
        Settings.REGION_REQUEST_SPOOF.save(true);
        Settings.SIMSPOOF_MCCMNC.save("44a10");
        Map<String, String> params = requestParams();
        RegionSpoof.requestParams(params);
        assertEquals("JP", params.get("current_region"));
        assertEquals("an unusable operator code still went out", "310", params.get("carrier_region_v2"));

        Settings.SIMSPOOF_MCCMNC.save("44010");
        params = requestParams();
        params.put("carrier_region_v2", "us");
        RegionSpoof.requestParams(params);
        assertEquals("a value that isn't an MCC was replaced by one", "us", params.get("carrier_region_v2"));

        for (Runnable off : new Runnable[]{
                () -> Settings.SIM_SPOOF_ISO.save("zz"),
                () -> Settings.REGION_SPOOF.save(false),
                () -> Settings.SIM_SPOOF.save(false)}) {
            setup();
            Settings.REGION_REQUEST_SPOOF.save(true);
            off.run();
            params = requestParams();
            RegionSpoof.requestParams(params);
            assertEquals(requestParams(), params);
        }
    }

    private static Map<String, String> requestParams() {
        Map<String, String> params = new HashMap<>();
        for (String field : new String[]{"carrier_region", "sys_region", "region", "op_region",
                "current_region", "residence", "account_region"}) {
            params.put(field, "US");
        }
        params.put("carrier_region_v2", "310");
        params.put("app_language", "en");
        // Worked out once as TikTok started, with the preset's zone, like sys_region above.
        params.put("timezone_name", "Asia/Tokyo");
        return params;
    }

    /**
     * Runs the body with the system configuration's locale and the default time zone set to
     * these, which are what a sign-in's start-up copies are put back to, and restores both.
     */
    @SuppressWarnings("deprecation")
    private static void withSystem(Locale locale, String zone, Runnable body) {
        Configuration system = Resources.getSystem().getConfiguration();
        Locale savedLocale = system.locale;
        TimeZone savedZone = TimeZone.getDefault();
        try {
            system.locale = locale;
            TimeZone.setDefault(TimeZone.getTimeZone(zone));
            body.run();
        } finally {
            system.locale = savedLocale;
            TimeZone.setDefault(savedZone);
        }
    }

    @Test public void legacyVariantKeepsScriptAndExtensionsWhenCountryChanges() {
        Locale original = Locale.forLanguageTag("zh-Hant-TW-u-nu-hanidec-x-custom-lvariant-WIN");
        Locale changed = RegionSpoof.locale(original);
        assertEquals("JP", changed.getCountry());
        assertEquals(original.getLanguage(), changed.getLanguage());
        assertEquals(original.getScript(), changed.getScript());
        for (Character key : original.getExtensionKeys()) {
            assertEquals(original.getExtension(key), changed.getExtension(key));
        }
    }
    @Test public void countryCodesRejectUnicodeCaseExpansion() {
        Locale original = Locale.CANADA_FRENCH;
        TimeZone zone = TimeZone.getTimeZone("America/Toronto");
        for (String value : new String[]{"ß", "ſs", "ıS", "ｊｐ"}) {
            Settings.SIM_SPOOF_ISO.save(value);
            assertFalse(value, RegionSpoof.validCountry(value));
            assertSame(original, RegionSpoof.locale(original));
            assertSame(zone, RegionSpoof.timeZone(zone));
            assertEquals("CA", RegionSpoof.country("CA"));
        }
        assertTrue(RegionSpoof.validCountry(" ss "));
    }
    @Test public void regionControlsAreReachableAndRejectInvalidCountryInput() throws Exception {
        try (var owner = Robolectric.buildActivity(app.morphe.extension.tiktok.interaction.GestureActionsTest.TestActivity.class).setup()) {
            var activity = owner.get();
            Utils.setContext(activity);
            Utils.setIsDarkModeEnabled(true);
            SettingsStatus.simSpoofEnabled = true;
            SettingsStatus.regionSpoofEnabled = true;
            var screen = activity.getPreferenceManager().createPreferenceScreen(activity);
            new SimSpoofPreferenceCategory(activity, screen);
            assertNotNull(screen.findPreference("region_spoof"));
            assertNotNull(screen.findPreference("region_store_spoof"));
            assertNotNull(screen.findPreference("region_request_spoof"));
            var country = (app.morphe.extension.tiktok.settings.preference.InputTextPreference)
                    screen.findPreference("simspoof_iso");
            assertFalse(country.callChangeListener("zz"));
            assertFalse(country.callChangeListener(""));
            assertTrue(country.callChangeListener("de"));
            // The listener behind the check still does its own job.
            assertNotNull(country.getOnPreferenceChangeListener());
            activity.setPreferenceScreen(screen);
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            // This class runs at both 23 and 28 and both runs wrote the same file, so which one
            // ended up published depended on which finished last. At 23 the decor view draws
            // nothing at all, so half the time the published picture would have been blank.
            // Published captures come from the newest API this class runs at.
            if (Build.VERSION.SDK_INT == PUBLISHED_CAPTURE_SDK) {
                app.morphe.extension.tiktok.UiCapture.save(
                        activity.getWindow().getDecorView(), "region-settings.png");
            }
        }
    }
}
