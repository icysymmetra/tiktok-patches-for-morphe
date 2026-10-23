/*
 * Forked from:
 * https://github.com/ReVanced/revanced-patches/blob/377d4e15016296b45d809697f7f69bce74badd3a/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/ReVancedTikTokAboutPreference.java
 * Mirror, since GitHub blocks the original: https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/tiktok/src/main/java/app/revanced/extension/tiktok/settings/preference/ReVancedTikTokAboutPreference.java
 */
package app.morphe.extension.tiktok.settings.preference;

import android.content.Context;
import android.preference.Preference;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

@SuppressWarnings("deprecation")
public final class MorpheTikTokAboutPreference extends Preference
        implements app.morphe.extension.shared.settings.preference.ImmediateAction {
    /**
     * A key so the settings search can index this row: a keyed row with no Setting behind it is
     * skipped by the settings framework, so nothing tries to persist it. Without one, searching
     * "version" found nothing even though the row carries it.
     */
    public static final String KEY = "action_about";
    public static final String SOURCE_URL = "https://github.com/icysymmetra/tiktok-patches-for-morphe";

    public MorpheTikTokAboutPreference(Context context) {
        this(context, Utils.getPatchesReleaseVersion(), Utils.getAppVersionName());
    }

    /** The versions are handed in so a test can see the row as a patched phone shows it. */
    MorpheTikTokAboutPreference(Context context, String bundleVersion, String appVersion) {
        super(context);
        setKey(KEY);
        setTitle("Metra");
        setSummary(summaryFor(context, bundleVersion, appVersion));
        setOnPreferenceClickListener(preference -> {
            Utils.openLink(SOURCE_URL);
            return true;
        });
    }

    /** What the row says on this phone, for the search index as much as for the row. */
    public static CharSequence currentSummary(Context context) {
        return summaryFor(context, Utils.getPatchesReleaseVersion(), Utils.getAppVersionName());
    }

    /**
     * The bundle's own version and the app it is running in, where somebody filing a bug report
     * can read both off.
     *
     * <p>Nothing in the app said which Hushfeed was installed. The version is stamped into the
     * extension at patch time and only the exported diagnostic report printed it, so every one of
     * the first three bug reports had to be asked for it separately, and the settings search had
     * nothing to find when somebody looked for "version". The TikTok version rides along because
     * the second question on every report is which build the bundle was applied to.
     *
     * <p>Empty during tests and in an unpatched build, where the stamp has not been written. The
     * row falls back to what it always said rather than showing an empty version.
     */
    @Override
    public boolean actsOnTap() {
        return true;
    }

    static CharSequence summaryFor(Context context, String bundleVersion, String appVersion) {
        String source = L10n.t(context, "Opens github.com in your browser");
        CharSequence version = versionSummary(context, bundleVersion, appVersion);
        return version.length() == 0 ? source : version + ". " + source;
    }

    /** The compact version sentence shared by About and the master-page status card. */
    static CharSequence versionSummary(Context context, String bundleVersion, String appVersion) {
        if (bundleVersion == null || bundleVersion.isEmpty()) return "";
        // "Unknown" is the literal English word Utils hands back when the package manager
        // refuses, and splicing it into an otherwise translated sentence reads worse than
        // leaving the app out. The bundle version is the one the reporter is asked for anyway.
        boolean readable = appVersion != null && !appVersion.isEmpty()
                && !"Unknown".equals(appVersion);
        return readable
                ? L10n.f(context, "Version %1$s for TikTok %2$s", bundleVersion, appVersion)
                : L10n.f(context, "Version %1$s", bundleVersion);
    }
}
