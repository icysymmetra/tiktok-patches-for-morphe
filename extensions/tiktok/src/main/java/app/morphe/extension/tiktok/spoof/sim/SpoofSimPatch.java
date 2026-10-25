/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/spoof/sim/SpoofSimPatch.java
 */

package app.morphe.extension.tiktok.spoof.sim;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.Settings;

@SuppressWarnings("unused")
public class SpoofSimPatch {
    private static boolean isContextNotSet(String fieldSpoofed) {
        if (Utils.getContext() != null) {
            return false;
        }

        Logger.printException(() -> "Context is not yet set, cannot spoof: " + fieldSpoofed, null);
        return true;
    }

    /**
     * Whether TikTok's startup SIM change check should be skipped.
     *
     * <p>That check sends one telemetry event naming the country and carrier before and after
     * the change, how many SIMs the phone reports, and how long since it last looked. What
     * decides that something changed is the subscription id, an int none of the spoofed getters
     * covers, so the event fires on a real SIM swap or a change of which SIM carries data. The
     * country and carrier in it do read as the preset. The SIM count does not: it comes from
     * SubscriptionManager, and the event still reports that the hardware moved. Nothing else in
     * the app reads what the check stores.
     */
    public static boolean shouldSkipSimChangeReport() {
        if (isContextNotSet("simChangeReport")) return false;

        return Settings.SIM_SPOOF.get();
    }

    public static String getCountryIso(String value) {
        if (isContextNotSet("countryIso")) return value;

        if (Settings.SIM_SPOOF.get()) {
            String iso = Settings.SIM_SPOOF_ISO.get();
            // The dialog checks this; a restored backup does not go through the dialog, and
            // TikTok reads the value straight into code that expects a country code.
            if (!app.morphe.extension.tiktok.spoof.region.RegionSpoof.validCountry(iso)) {
                Logger.printInfo(() -> "Ignoring an unusable SIM country: " + iso);
                return value;
            }
            Logger.printDebug(() -> "Spoofing countryIso to: " + iso);
            return iso;
        }

        return value;
    }

    public static String getOperator(String value) {
        if (isContextNotSet("MCC-MNC")) return value;

        if (Settings.SIM_SPOOF.get()) {
            String mccMnc = mccMnc(Settings.SIMSPOOF_MCCMNC.get());
            if (!validMccMnc(mccMnc)) {
                Logger.printInfo(() -> "Ignoring an unusable MCC/MNC: " + mccMnc);
                return value;
            }
            Logger.printDebug(() -> "Spoofing sim MCC-MNC to: " + mccMnc);
            return mccMnc;
        }

        return value;
    }

    public static String getOperatorName(String value) {
        if (isContextNotSet("operatorName")) return value;

        if (Settings.SIM_SPOOF.get()) {
            String operator = Settings.SIMSPOOF_OP_NAME.get();
            Logger.printDebug(() -> "Spoofing sim operatorName to: " + operator);
            return operator;
        }

        return value;
    }

    public static String getMcc(String value) {
        return getMccMncPart(value, true);
    }

    public static String getMnc(String value) {
        return getMccMncPart(value, false);
    }

    /**
     * Five or six digits, which is what a network code is. Callers inside TikTok read the
     * first three as the country and parse them as a number, so anything else is refused
     * rather than handed on: a value typed before this check existed, or restored from a
     * backup, would otherwise reach them as letters.
     */
    public static boolean validMccMnc(String value) {
        String code = mccMnc(value);
        if (code.length() < 5 || code.length() > 6) return false;
        for (int index = 0; index < code.length(); index++) {
            if (code.charAt(index) < '0' || code.charAt(index) > '9') return false;
        }
        return true;
    }

    /**
     * The stored code without the spaces around it. The dialog and the getters below have to
     * read it the same way, or the field accepts a value that is then ignored every time.
     */
    private static String mccMnc(String value) {
        return value == null ? "" : value.trim();
    }

    private static String getMccMncPart(String value, boolean mcc) {
        if (isContextNotSet(mcc ? "cellMcc" : "cellMnc")) return value;
        if (!Settings.SIM_SPOOF.get()) return value;

        String combined = mccMnc(Settings.SIMSPOOF_MCCMNC.get());
        if (!validMccMnc(combined)) return value;

        String replacement = mcc ? combined.substring(0, 3) : combined.substring(3);
        Logger.printDebug(() -> "Spoofing " + (mcc ? "cell MCC" : "cell MNC")
                + " to: " + replacement);
        return replacement;
    }
}
