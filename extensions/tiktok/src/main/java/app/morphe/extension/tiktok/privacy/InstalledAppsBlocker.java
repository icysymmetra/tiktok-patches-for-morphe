/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

import java.util.ArrayList;
import java.util.List;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.EarlySwitch;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The app inventory answered with an empty list while the switch is on.
 *
 * <p>The two whole-list calls are refused outright. The activity query is refused only when it
 * is the launcher enumeration, an ACTION_MAIN intent with the LAUNCHER category and no package
 * or component named, which is how a modern build reads the list of apps on the phone; a query
 * for the apps that can handle a share or a link is what the share sheet needs and goes through.
 * A read before the settings context exists takes the switch straight from the saved file.
 */
@SuppressWarnings("unused")
public final class InstalledAppsBlocker {
    /** {@link Settings#BLOCK_INSTALLED_APPS}'s key, for the read before the settings context. */
    static final String SWITCH_KEY = "block_installed_apps";

    private static boolean blocks() {
        return Utils.getContext() != null ? Settings.BLOCK_INSTALLED_APPS.get() : EarlySwitch.isOn(SWITCH_KEY);
    }

    public static List<PackageInfo> interceptGetInstalledPackages(PackageManager pm, int flags) {
        if (blocks()) {
            Logger.printInfo(() -> "Installed apps blocker: blocked getInstalledPackages");
            return new ArrayList<>();
        }
        return pm.getInstalledPackages(flags);
    }

    public static List<ApplicationInfo> interceptGetInstalledApplications(PackageManager pm, int flags) {
        if (blocks()) {
            Logger.printInfo(() -> "Installed apps blocker: blocked getInstalledApplications");
            return new ArrayList<>();
        }
        return pm.getInstalledApplications(flags);
    }

    public static List<ResolveInfo> interceptQueryIntentActivities(PackageManager pm, Intent intent, int flags) {
        if (isAppInventoryScan(intent) && blocks()) {
            Logger.printInfo(() -> "Installed apps blocker: blocked the launcher enumeration");
            return new ArrayList<>();
        }
        return pm.queryIntentActivities(intent, flags);
    }

    static boolean isAppInventoryScan(Intent intent) {
        return intent != null
                && Intent.ACTION_MAIN.equals(intent.getAction())
                && intent.hasCategory(Intent.CATEGORY_LAUNCHER)
                && intent.getPackage() == null
                && intent.getComponent() == null;
    }

    private InstalledAppsBlocker() {}
}
