/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import app.morphe.extension.shared.Logger;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The Android 11 and later half of {@link StoreIdentity}: how a store build's install source
 * reads. From Android 11 TikTok asks {@code getInstallSourceInfo} first and only falls back to
 * {@code getInstallerPackageName}, then reads who installed it, who started the install and where
 * it came from. A Play Store install answers the Play Store to the first two and nothing to the
 * third.
 *
 * <p>{@link InstallSourceInfo} can't be built by an app, so the real one is handed back and
 * remembered when it describes TikTok, and the patch stands in front of each of the three reads.
 * It doesn't override {@code equals}, so the map holds those exact objects, and only while TikTok
 * still does.
 */
@RequiresApi(30)
@SuppressWarnings("unused")
public final class StoreInstallSource {
    private static final Map<InstallSourceInfo, Boolean> OWN =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static volatile boolean logged;

    private StoreInstallSource() {
    }

    /** In place of {@link PackageManager#getInstallSourceInfo}: the real answer, with what it throws. */
    public static InstallSourceInfo sourceFor(PackageManager manager, String packageName)
            throws PackageManager.NameNotFoundException {
        InstallSourceInfo info = manager.getInstallSourceInfo(packageName);
        if (info != null && StoreIdentity.answersFor(packageName)) OWN.put(info, Boolean.TRUE);
        return info;
    }

    /** In place of {@link InstallSourceInfo#getInstallingPackageName()}. */
    @Nullable
    public static String installingOf(InstallSourceInfo info) {
        return answered(info) ? StoreIdentity.logged(StoreIdentity.installer()) : info.getInstallingPackageName();
    }

    /** In place of {@link InstallSourceInfo#getInitiatingPackageName()}. */
    @Nullable
    public static String initiatingOf(InstallSourceInfo info) {
        return answered(info) ? StoreIdentity.logged(StoreIdentity.installer()) : info.getInitiatingPackageName();
    }

    /** In place of {@link InstallSourceInfo#getOriginatingPackageName()}. */
    @Nullable
    public static String originatingOf(InstallSourceInfo info) {
        return answered(info) ? null : info.getOriginatingPackageName();
    }

    private static boolean answered(@Nullable InstallSourceInfo info) {
        if (info == null || !OWN.containsKey(info)) return false;
        if (!logged) {
            logged = true;
            Logger.printInfo(() -> "Store identity: answered an install source read as a store install");
        }
        return true;
    }
}
