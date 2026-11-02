/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/categories/SimSpoofPreferenceCategory.java
 * Mirror, since GitHub blocks the original: https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/categories/SimSpoofPreferenceCategory.java
 */

package app.morphe.extension.tiktok.settings.preference.categories;

import android.content.Context;
import android.preference.PreferenceScreen;

import app.morphe.extension.tiktok.network.NetworkProxy;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.ChoicePreference;
import app.morphe.extension.tiktok.settings.preference.InputTextPreference;
import app.morphe.extension.tiktok.settings.preference.SectionHeadingPreference;
import app.morphe.extension.tiktok.settings.preference.SimPresetPreference;
import app.morphe.extension.tiktok.settings.preference.TogglePreference;
import app.morphe.extension.tiktok.spoof.sim.SpoofSimPatch;
import app.morphe.extension.tiktok.spoof.region.RegionSpoof;
import app.morphe.extension.tiktok.settings.L10n;

@SuppressWarnings("deprecation")
public class SimSpoofPreferenceCategory extends ConditionalPreferenceCategory {
    public SimSpoofPreferenceCategory(Context context, PreferenceScreen screen) {
        super(context, screen);
        setTitle("Region");
    }

    /** Whether this page has anything on it. The row into it asks the same question. */
    public static boolean isAvailable() {
        return SettingsStatus.simSpoofEnabled
                || SettingsStatus.regionSpoofEnabled
                || SettingsStatus.networkProxyEnabled;
    }

    @Override
    public boolean getSettingsStatus() {
        return isAvailable();
    }

    @Override
    public void addPreferences(Context context) {
        // A bundle with only the proxy has no SIM or region rows to show: each would change nothing.
        if (SettingsStatus.simSpoofEnabled || SettingsStatus.regionSpoofEnabled) addRegionRows(context);
        if (SettingsStatus.networkProxyEnabled) addProxyRows(context);
    }

    /**
     * The proxy TikTok's own traffic goes through. Every row is read once as TikTok starts, so
     * each says a restart applies it, and each greys under the switch.
     */
    private void addProxyRows(Context context) {
        addPreference(new SectionHeadingPreference(context, "Network proxy"));
        addPreference(new TogglePreference(
                context,
                "Send TikTok through a proxy",
                "Sends TikTok's feed, search, comments and other app traffic through the proxy "
                        + "below. Videos and LIVE streams still connect directly. Saves and "
                        + "short-link lookups from Metra also connect directly, after their own "
                        + "address checks. Other apps aren't affected.",
                Settings.NETWORK_PROXY
        ));
        addPreference(new ChoicePreference(context, "Proxy type", Settings.NETWORK_PROXY_TYPE,
                new String[]{"HTTP", "SOCKS5"},
                new String[]{NetworkProxy.TYPE_HTTP, NetworkProxy.TYPE_SOCKS5}));
        addPreference(new InputTextPreference(
                context,
                "Proxy host", "The proxy's address, like 192.168.1.20 or proxy.example.com.",
                Settings.NETWORK_PROXY_HOST
        ).withCheck(NetworkProxy::hostProblem).withNameKeyboard());
        addPreference(new InputTextPreference(
                context,
                "Proxy port", "The port the proxy listens on, like 8080 or 1080.",
                Settings.NETWORK_PROXY_PORT
        ).withCheck(NetworkProxy::portProblem));
        addPreference(new InputTextPreference(
                context,
                "Proxy user name",
                "Optional. Only connections made by Android itself can sign in to a proxy, not "
                        + "TikTok's own. Leave empty for a proxy without a password.",
                Settings.NETWORK_PROXY_USER
        ).withNameKeyboard());
        addPreference(new InputTextPreference(
                context,
                "Proxy password", "Optional, used with the user name. It isn't shown, logged or put in a backup.",
                Settings.NETWORK_PROXY_PASSWORD
        ).withSecret());
    }

    private void addRegionRows(Context context) {
        addPreference(new TogglePreference(
                context,
                "Override SIM details",
                // The operator rows are only added when the SIM spoof patch is in the
                // bundle, so on one without it this promised two fields that are not there.
                SettingsStatus.simSpoofEnabled
                        ? "Makes TikTok see the country and carrier you pick as your SIM. Your "
                                + "IP address, account history and language don't change, and "
                                + "any one of them can make TikTok keep your current region. "
                                + "Restart TikTok to see the change."
                        : "Makes TikTok see the country you pick as your SIM. Your IP address, "
                                + "account history and language don't change, and any one of "
                                + "them can make TikTok keep your current region. Restart "
                                + "TikTok to see the change.",
                Settings.SIM_SPOOF
        ));
        if (SettingsStatus.regionSpoofEnabled) {
            addPreference(new TogglePreference(context, "Match locale and timezone to country",
                    "Also change the region TikTok reports internally. Keeps your interface language. Your IP address and your account's own rules still apply.", Settings.REGION_SPOOF));
            addPreference(new TogglePreference(context, "Override store region (experimental)",
                    "Use the preset for the region TikTok reports for your account and its shop too. May affect search.", Settings.REGION_STORE_SPOOF));
            addPreference(new TogglePreference(context, "Match region fields in requests",
                    "Also replaces the saved region and country code TikTok sends with every "
                            + "request. Signing in still sends your real region. Your IP "
                            + "address and your account's own rules still apply.", Settings.REGION_REQUEST_SPOOF));
        }
        InputTextPreference countryIsoPreference = new InputTextPreference(
                context,
                "Country code", "Two letters, like us, gb or jp.",
                Settings.SIM_SPOOF_ISO
        ).withCheck(value -> RegionSpoof.validCountry(value)
                ? null : L10n.t("Enter a valid two-letter country code")).withNameKeyboard();
        InputTextPreference mccMncPreference = new InputTextPreference(
                context,
                "Operator code", "Your operator's numeric code, like 310260.",
                Settings.SIMSPOOF_MCCMNC
        ).withCheck(value -> SpoofSimPatch.validMccMnc(value.trim()) ? null
                : L10n.t("An operator code is five or six digits, like 310260"));
        InputTextPreference operatorNamePreference = new InputTextPreference(
                context,
                "Operator name", "Your operator's name, like T-Mobile.",
                Settings.SIMSPOOF_OP_NAME
        );
        SimPresetPreference simPresetPreference = new SimPresetPreference(
                context,
                countryIsoPreference,
                mccMncPreference,
                operatorNamePreference
        );

        countryIsoPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            simPresetPreference.refreshSummary(
                    newValue.toString(),
                    mccMncPreference.getText(),
                    operatorNamePreference.getText()
            );
            return true;
        });
        mccMncPreference.setOnPreferenceChangeListener((preference, newValue) -> {
            simPresetPreference.refreshSummary(
                    countryIsoPreference.getText(),
                    newValue.toString(),
                    operatorNamePreference.getText()
            );
            return true;
        });
        operatorNamePreference.setOnPreferenceChangeListener((preference, newValue) -> {
            simPresetPreference.refreshSummary(
                    countryIsoPreference.getText(),
                    mccMncPreference.getText(),
                    newValue.toString()
            );
            return true;
        });

        // The region patch reads the country override and the country code, so those belong to
        // either patch. The operator code and name are read only by the SIM spoof patch, and on
        // a bundle without it they were rows that changed nothing.
        addPreference(countryIsoPreference);
        if (SettingsStatus.simSpoofEnabled) {
            addPreference(simPresetPreference);
            addPreference(mccMncPreference);
            addPreference(operatorNamePreference);
        } else {
            // The preset row is not on the page, so the listeners that keep its summary in step
            // with three fields have nothing to keep in step. Left in place they read text from
            // two rows that were never attached.
            countryIsoPreference.setOnPreferenceChangeListener(null);
            mccMncPreference.setOnPreferenceChangeListener(null);
            operatorNamePreference.setOnPreferenceChangeListener(null);
        }
    }
}
