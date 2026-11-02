/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.spoof.sim;

import java.util.Locale;

public class SimPreset {
    public final String country;
    public final String iso;
    public final String mccMnc;
    public final String operatorName;
    public final String timeZone;

    private final String searchableText;

    public SimPreset(String country, String iso, String mccMnc, String operatorName, String timeZone) {
        this.country = country;
        this.iso = iso;
        this.mccMnc = mccMnc;
        this.operatorName = operatorName;
        this.timeZone = timeZone;
        this.searchableText = (country + " " + iso + " " + mccMnc + " " + operatorName)
                .toLowerCase(Locale.US);
    }

    public String getSummary() {
        return operatorName + " (" + mccMnc + ", " + iso + ")";
    }

    public boolean matches(String query) {
        return query == null || query.isEmpty()
                || searchableText.contains(query.toLowerCase(Locale.US).trim());
    }

    public boolean hasSameValues(String currentIso, String currentMccMnc, String currentOperatorName) {
        return iso.equalsIgnoreCase(trimOrEmpty(currentIso))
                && mccMnc.equals(trimOrEmpty(currentMccMnc))
                && operatorName.equalsIgnoreCase(trimOrEmpty(currentOperatorName));
    }

    public boolean isValid() {
        return !trimOrEmpty(country).isEmpty()
                && iso != null && iso.matches("[a-z]{2}")
                && mccMnc != null && mccMnc.matches("\\d{5,6}")
                && !trimOrEmpty(operatorName).isEmpty();
    }

    private static String trimOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }
}
