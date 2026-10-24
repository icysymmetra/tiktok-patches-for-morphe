/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.spoof.region.RegionSpoof;

import com.ss.android.ugc.aweme.feed.model.Aweme;

/**
 * Keeps or drops a video by the country it was posted from, the same value the author row
 * shows when that switch is on. A video TikTok sends with no region is always kept: the two
 * lists say what to do with a country, not what to do when there isn't one.
 */
public final class RegionFilter implements IFilter {
    private final Set<String> only;
    private final Set<String> never;

    RegionFilter() {
        only = countries(Settings.REGION_ONLY_FROM.get());
        never = countries(Settings.REGION_NEVER_FROM.get());
    }

    private static final String SEPARATOR = "\\s*[,\\n]\\s*";

    /**
     * The country codes in a comma or newline separated list, upper case.
     *
     * <p>An entry that is not a country code is dropped rather than kept as a code that can
     * never match. On the only-from list a kept one hid the entire feed: every video's country
     * failed to match it and there was nothing on screen to say why. A saved list from before
     * the row checked what it was given still reaches this.
     */
    static Set<String> countries(String value) {
        Set<String> codes = new LinkedHashSet<>();
        if (value == null) return codes;
        for (String entry : value.trim().split(SEPARATOR)) {
            String code = canonical(entry);
            if (!code.isEmpty() && RegionSpoof.validCountry(code)) codes.add(code);
        }
        return codes;
    }

    /**
     * What is wrong with a country list, for the row to say before it saves one, or null when
     * every entry is a country.
     *
     * <p>The row took anything at all, and the two lists are the one place in the feed filter
     * where a typo does more than nothing: "UK" or "United Kingdom" in Only from these countries
     * matched no video, so the whole feed went blank and read as the filter being broken.
     */
    public static String countryProblem(String value) {
        if (value == null) return null;
        for (String entry : value.trim().split(SEPARATOR)) {
            String code = canonical(entry);
            if (code.isEmpty() || RegionSpoof.validCountry(code)) continue;
            return L10n.f(
                    "%1$s isn't a country code, so no video would ever match it. Use two letters, like GB, IE or US.",
                    entry.trim());
        }
        return null;
    }

    /**
     * An entry as the video models spell it. Only one spelling needs moving: people write "UK"
     * and the videos carry "GB", which is the ISO code. Everything else is the code itself,
     * upper cased, because the list is typed by hand and the model's case is not the reader's.
     */
    private static String canonical(String entry) {
        String code = entry.trim().toUpperCase(Locale.ROOT);
        return "UK".equals(code) ? "GB" : code;
    }

    @Override
    public boolean getEnabled() {
        return !only.isEmpty() || !never.isEmpty();
    }

    @Override
    public boolean getFiltered(Aweme item) {
        String region = Reflect.string(item, "getRegion", "region");
        if (region == null) return false;

        String code = region.trim().toUpperCase(Locale.ROOT);
        if (code.isEmpty()) return false;
        if (never.contains(code)) return true;
        return !only.isEmpty() && !only.contains(code);
    }
}
