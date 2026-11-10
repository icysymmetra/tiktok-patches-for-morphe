/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.spoof.region;

import android.content.res.Resources;
import android.os.Build;
import android.os.SystemClock;
import androidx.annotation.Nullable;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.spoof.sim.SimPreset;
import app.morphe.extension.tiktok.spoof.sim.SimPresets;
import app.morphe.extension.tiktok.spoof.sim.SpoofSimPatch;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicInteger;

public final class RegionSpoof {
    private static final Set<String> COUNTRIES = new HashSet<>(Arrays.asList(Locale.getISOCountries()));

    /*
     * The patch sends every Locale.getDefault() and TimeZone.getDefault() in the app through
     * here, on every thread, and those are asked for all the time: each String.format, each
     * date and each lower-casing. Each answer used to compile a regex, build a Locale and scan
     * the presets (or ICU's whole zone table for a custom country). The last answer is kept
     * with what it was worked out from, so a changed setting still takes effect at once.
     */
    private static volatile String[] lastCountry = {"", ""};
    private static volatile LocaleAnswer lastLocale;
    private static volatile Object[] lastZone = {"", null};

    private static final class LocaleAnswer {
        final Locale original;
        final String country;
        final Locale answer;

        LocaleAnswer(Locale original, String country, Locale answer) {
            this.original = original;
            this.country = country;
            this.answer = answer;
        }
    }

    private RegionSpoof() { }

    public static boolean validCountry(String value) {
        if (value == null) return false;
        String code = value.trim();
        if (code.length() != 2) return false;
        for (int index = 0; index < 2; index++) {
            char letter = code.charAt(index);
            if ((letter < 'A' || letter > 'Z') && (letter < 'a' || letter > 'z')) return false;
        }
        return COUNTRIES.contains(code.toUpperCase(Locale.ROOT));
    }

    /*
     * Sign-in requests. TTNet's common-parameter handler hands over each request's path just
     * before it builds that request's parameters, on the same thread, and calls requestDone()
     * right after. While a /passport/ request's parameters are built with Match region fields in
     * requests on, that thread gets TikTok's own answers: the region getters, the locale, the
     * timezone and the request fields the builder hook would set. The two values TikTok worked
     * out once as it started, sys_region and timezone_name, were worked out under the preset and
     * kept, so requestParams() puts back what their sources say now.
     */
    /**
     * This thread's mark: when it was set, in {@link SystemClock#elapsedRealtime()}, and how many
     * sign-in fills it spans. A fill can run inside another for the same request (AppLog's inside
     * the handler's), and only the outer one's end takes the mark off.
     */
    private static final ThreadLocal<long[]> SIGN_IN = new ThreadLocal<>();
    /** Threads building a sign-in request's parameters now, so every other call skips the ThreadLocal. */
    private static final AtomicInteger signingIn = new AtomicInteger();
    private static final String SIGN_IN_PATH = "passport/";
    /**
     * How long a mark lasts. A fill takes milliseconds, so a mark this old is one whose fill threw
     * before requestDone() and whose thread hasn't sent another request since; it goes, rather
     * than leaving that thread on the real region until it does.
     */
    static final long MARK_LIFETIME_MS = 10_000L;

    /** From the common-parameter handler, with the path of the request it's about to fill. */
    public static void requestPath(@Nullable String path) {
        try {
            mark(isSignIn(path) && Utils.getContext() != null && Settings.REGION_REQUEST_SPOOF.get());
        } catch (RuntimeException error) {
            Logger.printException(() -> "Region spoof could not read a request's path", error);
        }
    }

    /**
     * With the full URL of the request about to be filled, from the routes that fill common
     * parameters without passing the handler's path read: the token interceptor (the token
     * heartbeat, token change and logout), AppLog's URL entry point, and the JS request helpers
     * that fill a POST body before they send it.
     */
    public static void requestUrl(@Nullable String url) {
        requestPath(pathOf(url));
    }

    /**
     * From the places that add common parameters to a URL TikTok is still building, and the
     * StringBuilder holds that URL so far. Null when there's nothing to read.
     */
    public static void requestUrlBuilder(@Nullable StringBuilder url) {
        requestUrl(url == null ? null : url.toString());
    }

    /** The path of a URL, without its scheme, host, query or fragment. Null when it has none. */
    @Nullable
    static String pathOf(@Nullable String url) {
        if (url == null) return null;
        int scheme = url.indexOf("://");
        int start = scheme < 0 ? 0 : scheme + 3;
        // The host ends at the first slash, query or fragment. Only a slash starts a path.
        while (start < url.length() && "/?#".indexOf(url.charAt(start)) < 0) start++;
        if (start == url.length() || url.charAt(start) != '/') return null;
        int end = start;
        while (end < url.length() && "?#".indexOf(url.charAt(end)) < 0) end++;
        return url.substring(start, end);
    }

    /** From the common-parameter handler, once the request's parameters are built. */
    public static void requestDone() {
        long[] mark = SIGN_IN.get();
        if (mark != null && --mark[1] <= 0) clearMark();
    }

    /**
     * A sign-in's fill marks the thread, or goes one deeper into a mark already there. Any other
     * request takes the mark off, whatever its depth: that's how a fill that threw before its end
     * gets put right by the thread's next request.
     */
    private static void mark(boolean signIn) {
        long[] mark = SIGN_IN.get();
        if (signIn) {
            long now = SystemClock.elapsedRealtime();
            if (mark != null && now - mark[0] < MARK_LIFETIME_MS) {
                mark[1]++;
                return;
            }
            // A mark run out is a fill that threw long ago, so this sign-in starts its own.
            if (mark != null) clearMark();
            SIGN_IN.set(new long[]{now, 1});
            signingIn.incrementAndGet();
        } else if (mark != null) {
            clearMark();
        }
    }

    private static void clearMark() {
        SIGN_IN.remove();
        signingIn.decrementAndGet();
    }

    /** TikTok's sign-in, sign-up and account verification endpoints all sit under /passport/. */
    static boolean isSignIn(@Nullable String path) {
        if (path == null) return false;
        int start = 0;
        while (start < path.length() && path.charAt(start) == '/') start++;
        return start > 0 && path.regionMatches(start, SIGN_IN_PATH, 0, SIGN_IN_PATH.length());
    }

    private static boolean signingIn() {
        if (signingIn.get() <= 0) return false;
        long[] mark = SIGN_IN.get();
        if (mark == null) return false;
        if (SystemClock.elapsedRealtime() - mark[0] < MARK_LIFETIME_MS) return true;
        clearMark();
        return false;
    }

    private static String selectedCountry() {
        return signingIn() ? "" : presetCountry();
    }

    private static String presetCountry() {
        if (Utils.getContext() == null || !Settings.SIM_SPOOF.get() || !Settings.REGION_SPOOF.get()) return "";
        String value = Settings.SIM_SPOOF_ISO.get();
        if (value == null) return "";
        String[] last = lastCountry;
        if (value.equals(last[0])) return last[1];
        String country = validCountry(value) ? value.trim().toUpperCase(Locale.ROOT) : "";
        lastCountry = new String[]{value, country};
        return country;
    }

    public static String country(String original) {
        String country = selectedCountry();
        return country.isEmpty() ? original : country;
    }

    public static String storeCountry(String original) {
        return Utils.getContext() != null && Settings.REGION_STORE_SPOOF.get() ? country(original) : original;
    }

    /*
     * The region fields AppLog's common-parameter builder puts on every request TikTok sends.
     * carrier_region, sys_region and region are the region hub's own answers, which the getter
     * hooks already turn into the preset, so they are only looked at here, never written.
     * current_region and residence are what TikTok's servers last told this phone, kept in its
     * preferences, and carrier_region_v2 is the network's country code (an MCC) read from the
     * system configuration. No getter hook reaches those three.
     *
     * Two fields in that map are copies TikTok took once as it started: sys_region, from the
     * hub's getter, and timezone_name, from TimeZone.getDefault().getID(). Both were worked out
     * with the hooks answering the preset, and the copies stay in a cache for the life of the
     * process. A sign-in puts the live answers back: the system locale's country, and the
     * system's default zone. This class is skipped by the TimeZone hook, so its own calls are
     * the unhooked ones.
     */
    static final String[] SAVED_REGION_FIELDS = {"current_region", "residence"};
    static final String NETWORK_COUNTRY_FIELD = "carrier_region_v2";
    static final String[] HUB_REGION_FIELDS = {"carrier_region", "sys_region", "region"};
    static final String SYSTEM_REGION_FIELD = "sys_region";
    static final String TIMEZONE_NAME_FIELD = "timezone_name";
    private static volatile String lastRequestReport = "";

    /**
     * Called with the parameter map at the end of the builder. Only fields TikTok already put in
     * the map are changed: one it left out stays out.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void requestParams(Map params) {
        try {
            if (params == null || Utils.getContext() == null || !Settings.REGION_REQUEST_SPOOF.get()) return;
            if (signingIn()) {
                // A sign-in goes out with the fields TikTok set, so the account sees the real
                // region, and the copies it made at start-up are put back to what they are now.
                List<String> live = liveStartUpFields(params);
                String preset = presetCountry();
                if (!preset.isEmpty()) report(params, preset, null, live);
                return;
            }
            String country = selectedCountry();
            if (country.isEmpty()) return;
            List<String> set = new ArrayList<>();
            for (String field : SAVED_REGION_FIELDS) {
                if (params.get(field) instanceof String) {
                    params.put(field, country);
                    set.add(field);
                }
            }
            Object network = params.get(NETWORK_COUNTRY_FIELD);
            String mcc = presetMcc();
            if (mcc != null && network instanceof String && isMcc((String) network)) {
                params.put(NETWORK_COUNTRY_FIELD, mcc);
                set.add(NETWORK_COUNTRY_FIELD);
            }
            report(params, country, set, null);
        } catch (RuntimeException error) {
            Logger.printException(() -> "Region spoof could not set a request's region fields", error);
        }
    }

    /**
     * Puts the live answer in each start-up copy the map holds, and returns the fields it did.
     * A field TikTok left out stays out, and so does one that isn't a String.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static List<String> liveStartUpFields(Map params) {
        List<String> live = new ArrayList<>();
        if (params.get(SYSTEM_REGION_FIELD) instanceof String) {
            String region = systemRegion();
            // TikTok's cache never takes an empty value, so a system locale with no country
            // would have left the field out altogether.
            if (region.isEmpty()) params.remove(SYSTEM_REGION_FIELD);
            else params.put(SYSTEM_REGION_FIELD, region);
            live.add(SYSTEM_REGION_FIELD);
        }
        if (params.get(TIMEZONE_NAME_FIELD) instanceof String) {
            params.put(TIMEZONE_NAME_FIELD, TimeZone.getDefault().getID());
            live.add(TIMEZONE_NAME_FIELD);
        }
        return live;
    }

    /** The country of the system configuration's locale, which is what the hub's sys_region getter reads. */
    @SuppressWarnings("deprecation")
    private static String systemRegion() {
        Locale locale = Resources.getSystem().getConfiguration().locale;
        return locale == null ? "" : locale.getCountry();
    }

    /** The first three digits of the SIM preset's operator code, or null when there isn't a usable one. */
    private static String presetMcc() {
        String code = Settings.SIMSPOOF_MCCMNC.get();
        if (code == null || !SpoofSimPatch.validMccMnc(code)) return null;
        return code.trim().substring(0, 3);
    }

    private static boolean isMcc(String value) {
        if (value.length() != 3) return false;
        for (int index = 0; index < 3; index++) {
            if (value.charAt(index) < '0' || value.charAt(index) > '9') return false;
        }
        return true;
    }

    /*
     * One debug line each time the outcome changes, not one per request. It names fields and says
     * whether the hub's fields already carry the preset, and never prints a value the phone had
     * before, since that is the real region. A null set is a sign-in request, where nothing is set
     * to the preset, every region field is looked at, and live names the start-up copies that were
     * put back to their live answers.
     */
    @SuppressWarnings("rawtypes")
    private static void report(Map params, String country, @Nullable List<String> set,
                               @Nullable List<String> live) {
        if (!BaseSettings.DEBUG.get()) return;
        List<String> matching = new ArrayList<>();
        List<String> other = new ArrayList<>();
        List<String> fields = new ArrayList<>(Arrays.asList(HUB_REGION_FIELDS));
        if (set == null) fields.addAll(Arrays.asList(SAVED_REGION_FIELDS));
        for (String field : fields) {
            Object value = params.get(field);
            if (!(value instanceof String)) continue;
            (country.equalsIgnoreCase((String) value) ? matching : other).add(field);
        }
        String line = set == null
                ? "Sign-in request region fields left as TikTok set them for " + country
                        + ", start-up copies put back " + live
                        + ". Still the preset " + matching + ", something else " + other
                : "Request region fields for " + country + ". Set to the preset " + set
                        + ", already the preset " + matching + ", something else " + other;
        if (line.equals(lastRequestReport)) return;
        lastRequestReport = line;
        Logger.printDebug(() -> line);
    }

    public static Locale locale(Locale original) {
        String country = selectedCountry();
        if (original == null || country.isEmpty() || country.equals(original.getCountry())) return original;
        LocaleAnswer last = lastLocale;
        if (last != null && last.country.equals(country)
                && (last.original == original || last.original.equals(original))) {
            return last.answer;
        }
        Locale answer = withCountry(original, country);
        lastLocale = new LocaleAnswer(original, country, answer);
        return answer;
    }

    private static Locale withCountry(Locale original, String country) {
        try {
            return new Locale.Builder().setLocale(original).setRegion(country).build();
        } catch (java.util.IllformedLocaleException error) {
            // Legacy variants can be invalid BCP 47. Keep the other locale fields intact.
            try {
                Locale.Builder builder = new Locale.Builder().setLanguage(original.getLanguage())
                        .setScript(original.getScript()).setRegion(country);
                for (Character key : original.getExtensionKeys()) {
                    builder.setExtension(key, original.getExtension(key));
                }
                return builder.build();
            } catch (java.util.IllformedLocaleException invalidLanguage) {
                return original;
            }
        }
    }

    public static TimeZone timeZone(TimeZone original) {
        String country = selectedCountry();
        if (original == null || country.isEmpty()) return original;
        Object[] last = lastZone;
        TimeZone zone;
        if (country.equals(last[0])) {
            zone = (TimeZone) last[1];
        } else {
            zone = zoneOf(country);
            lastZone = new Object[]{country, zone};
        }
        // A copy each time, as TimeZone.getDefault() gives: a TimeZone can be changed by whoever
        // holds it, and the kept one is shared by every caller.
        return zone == null ? original : (TimeZone) zone.clone();
    }

    private static TimeZone zoneOf(String country) {
        for (SimPreset preset : SimPresets.PRESETS) {
            if (country.equalsIgnoreCase(preset.iso)) return TimeZone.getTimeZone(preset.timeZone);
        }
        if (Build.VERSION.SDK_INT >= 24) {
            String[] zones = android.icu.util.TimeZone.getAvailableIDs(country);
            if (zones.length > 0) return TimeZone.getTimeZone(zones[0]);
        }
        return null;
    }
}
