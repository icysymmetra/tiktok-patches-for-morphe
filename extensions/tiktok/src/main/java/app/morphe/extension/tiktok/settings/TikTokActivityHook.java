/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/TikTokActivityHook.java
 * Mirror, since GitHub blocks the original: https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/TikTokActivityHook.java
 */

package app.morphe.extension.tiktok.settings;

import android.app.Fragment;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.preference.PreferenceFragment;
import android.view.View;
import android.widget.FrameLayout;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.BuildDetails;
import app.morphe.extension.tiktok.settings.preference.SettingsActionBanner;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.preference.TikTokPreferenceFragment;

import com.bytedance.ies.ugc.aweme.commercialize.compliance.personalization.AdPersonalizationActivity;


/**
 * Hooks AdPersonalizationActivity to inject a custom {@link TikTokPreferenceFragment}.
 */
@SuppressWarnings({"deprecation", "unused"})
public class TikTokActivityHook {
    private static final String SETTINGS_ACTION = "morphe_settings";
    private static final String SETTINGS_EXTRA = "morphe";
    private static final String SETTINGS_SECTION_EXTRA = "morphe_settings_section";
    /** The row the opened page scrolls to and flashes; the fragment reads the same name. */
    private static final String SETTINGS_TARGET_KEY_EXTRA = "morphe_settings_target_key";
    private static final String SETTINGS_ROOT_TAG = "hushfeed_settings_root";
    /** The page a restart left ({@link SettingsReturn}), opened over the home page. */
    private static final String SETTINGS_PAGE_EXTRA = "hushfeed_settings_page";

    /***
     * Initialize the settings menu.
     * @param base The activity to initialize the settings menu on.
     * @return Whether the settings menu should be initialized.
     */
    public static boolean initialize(AdPersonalizationActivity base) {
        Intent intent = base.getIntent();
        Bundle extras = intent.getExtras();
        if ((extras == null || !extras.getBoolean(SETTINGS_EXTRA, false)) && !SETTINGS_ACTION.equals(intent.getAction())) {
            return false;
        }

        SettingsOperationJournal.initialize(base.getApplicationContext());
        SettingsOperationJournal.showRecoveryNotice(base);
        SettingsStatus.load();
        // The report section that says what the Lab was told to do. Registered beside the
        // load rather than inside it: the patcher fills load() with the patches' own
        // registrations and holds its unpatched body empty.
        app.morphe.extension.tiktok.featuregatelab.FeatureGateLabReport.install();
        // Which connections follow the network proxy and which stay direct.
        app.morphe.extension.tiktok.network.NetworkRoutes.install();
        // Which settings the reader changed, so an export answers "which switch is on" itself.
        ChangedSettingsReport.install();
        // Build details and the full log export list the switches the patcher registered, read
        // here for the same reason. Both open from this screen.
        BuildDetails.setRegisteredSwitches(SettingsStatus.registered());
        SettingsUi.syncDarkMode(base);

        FrameLayout settingsSurface = new FrameLayout(base);
        settingsSurface.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        settingsSurface.setTag(SettingsActionBanner.CONTENT_ROOT_TAG);
        // The background sits here rather than only on the fragment, so it reaches behind the
        // bars. fitsSystemWindows would pad this view too, but it consumes the insets on the way
        // past, and it has no answer for a display cutout on a side edge.
        settingsSurface.setBackgroundColor(SettingsUi.background());
        SystemBarInsets.applyTo(settingsSurface);
        settingsSurface.setTransitionGroup(true);

        FrameLayout fragment = new FrameLayout(base);
        fragment.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
        // The initial page stays findable by tag even while it sits on the back stack.
        // Reuse its container so Android can attach the fragments restored by super.onCreate.
        Fragment restoredRoot = base.getFragmentManager().findFragmentByTag(SETTINGS_ROOT_TAG);
        int fragmentId = restoredRoot == null ? View.generateViewId() : restoredRoot.getId();
        fragment.setId(fragmentId);

        // Fragments replace one another inside this child. Feedback belongs to the stable parent
        // above it: a fragment restored by Back is added after the banner and would otherwise
        // cover that banner even though its ten-second action was still alive.
        settingsSurface.addView(fragment);
        base.setContentView(settingsSurface);

        if (restoredRoot == null) {
            String section = intent.getStringExtra(SETTINGS_SECTION_EXTRA);
            // A restart pressed far down the home page comes back scrolled to where it was.
            PreferenceFragment preferenceFragment = TikTokPreferenceFragment.home(
                    section == null || section.isEmpty() ? intent.getStringExtra(SETTINGS_PAGE_EXTRA) : null);
            if (section != null && !section.isEmpty()) {
                Bundle arguments = new Bundle();
                arguments.putString(SETTINGS_SECTION_EXTRA, section);
                String targetKey = intent.getStringExtra(SETTINGS_TARGET_KEY_EXTRA);
                if (targetKey != null && !targetKey.isEmpty()) {
                    arguments.putString(SETTINGS_TARGET_KEY_EXTRA, targetKey);
                }
                preferenceFragment.setArguments(arguments);
            }
            base.getFragmentManager().beginTransaction()
                    .replace(fragmentId, preferenceFragment, SETTINGS_ROOT_TAG).commit();
            // Back to the page a restart was pressed on, with the home page under it, so Back
            // goes where it went before the restart rather than out of settings.
            PreferenceFragment returned = section == null || section.isEmpty()
                    ? TikTokPreferenceFragment.forPage(intent.getStringExtra(SETTINGS_PAGE_EXTRA)) : null;
            if (returned != null) {
                base.getFragmentManager().beginTransaction()
                        .replace(fragmentId, returned)
                        .addToBackStack("restart_return")
                        .commit();
            }
        }

        // Back here rode entirely on the host's onBackPressed, which stops being called once
        // TikTok drops enableOnBackInvokedCallback="false" from its manifest at target 36. The
        // Lab has registered a dispatcher callback since API 33; this screen had not, so it
        // would have lost Back the day that attribute goes. The hook below stays as the path
        // for everything older.
        new SystemBackHandler("HushfeedSettingsBackCallback")
                .registerUntilDetached(base, settingsSurface, () -> handleBackPressed(base));

        return true;
    }

    public static boolean handleBackPressed(AdPersonalizationActivity activity) {
        Intent intent = activity.getIntent();
        if (intent == null
                || (!SETTINGS_ACTION.equals(intent.getAction())
                && !intent.getBooleanExtra(SETTINGS_EXTRA, false))) {
            return false;
        }

        if (activity.getFragmentManager().getBackStackEntryCount() > 0) {
            activity.getFragmentManager().popBackStack();
        } else {
            activity.finish();
        }
        return true;
    }

    private static void startSettingsActivity() {
        startSettingsActivity(null, null, null);
    }

    /** Opens the extension settings at their home page. */
    public static void openSettings() {
        startSettingsActivity(null, null, null);
    }

    /** Opens the extension settings directly at the feed-filter page. */
    public static void openFeedFilterSettings() {
        startSettingsActivity("FEED_FILTER", null, null);
    }

    /** Opens one settings page scrolled to, and flashing, the row with this setting key. */
    public static void openSettingsRow(String section, String settingKey) {
        startSettingsActivity(section, settingKey, null);
    }

    /**
     * Opens the settings on the page a restart was pressed on, the way the Home tab's long press
     * opens them, with the home page under it.
     */
    public static void openSettingsPage(String page) {
        startSettingsActivity(null, null, page);
    }

    private static void startSettingsActivity(String section, String settingKey, String page) {
        Context appContext = Utils.getContext();
        if (appContext != null) {
            Intent intent = new Intent(appContext, AdPersonalizationActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(SETTINGS_ACTION);
            intent.putExtra(SETTINGS_EXTRA, true);
            if (section != null) {
                intent.putExtra(SETTINGS_SECTION_EXTRA, section);
            }
            if (settingKey != null) {
                intent.putExtra(SETTINGS_TARGET_KEY_EXTRA, settingKey);
            }
            if (page != null) {
                intent.putExtra(SETTINGS_PAGE_EXTRA, page);
            }
            appContext.startActivity(intent);
        } else {
            Logger.printDebug(() -> "Utils.getContext() return null");
        }
    }
}
