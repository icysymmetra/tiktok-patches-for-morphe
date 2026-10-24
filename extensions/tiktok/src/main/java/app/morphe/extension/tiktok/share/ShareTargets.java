/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.share;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.reflect.Constructor;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds apps you pick to the share sheet's Share via row.
 *
 * <p>TikTok already has a channel for a share target its server names: it's built from a
 * {@code ShareChannelInfo} (a key, a package, a label) and sends the link to that package as plain
 * text, the same way the built-in WhatsApp or Telegram channels do. Each picked app gets one of
 * those, keyed {@code hushfeed_app_<package>}, with its own launcher icon put where TikTok looks a
 * channel's icon up. They go in just before More, so More stays last. An app that's uninstalled,
 * or that no longer takes shared text, is left out, and so is one TikTok already shows.
 *
 * <p>TikTok keeps only the channels its server lists for the sheet, and sorts them in the
 * server's order, so the apps go in after both, where the sheet stores its finished row. Its
 * share code also looks each key's share mode up in that list, and {@link #shareModeOf} answers
 * for these keys with the plain link share.
 *
 * <p>The link it gets is the one every other channel gets, so it's cleaned the same way when
 * Sanitize sharing links is on.
 */
public final class ShareTargets {
    static final String KEY_PREFIX = "hushfeed_app_";
    static final String MORE_KEY = "more";
    /** TikTok looks a channel's square icon up under its key with this added. */
    static final String SQUARE_ICON_SUFFIX = "__square_icon";
    static final String CHANNEL_INFO = "com.ss.android.ugc.aweme.share.base.model.ShareChannelInfo";
    static final String TARGET_INFO = "com.ss.android.ugc.aweme.share.base.model.TargetComponentInfo";

    /** TikTok's share mode for a channel that shares the link as text, as its own app channels do. */
    static final int LINK_SHARE_MODE = 0;
    /** Not an added app's channel: TikTok answers from its server's platform list. */
    static final int NOT_OURS = -1;

    static volatile boolean iconMapRefused;

    private ShareTargets() {}

    /** An installed app that takes shared text. */
    public static final class App {
        public final String packageName;
        public final String label;

        App(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    /** The picked packages, in the order they were picked. */
    public static List<String> picked(@Nullable String stored) {
        List<String> packages = new ArrayList<>();
        if (stored == null) return packages;
        for (String token : stored.split("[,\\n]")) {
            String value = token.trim();
            if (!value.isEmpty() && !packages.contains(value)) packages.add(value);
        }
        return packages;
    }

    /** Every installed app other than TikTok that takes shared text, by label. */
    public static List<App> installed(Context context) {
        PackageManager manager = context.getPackageManager();
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain");
        Map<String, App> apps = new LinkedHashMap<>();
        for (ResolveInfo target : manager.queryIntentActivities(send, 0)) {
            if (target.activityInfo == null) continue;
            String packageName = target.activityInfo.packageName;
            if (packageName == null || packageName.equals(context.getPackageName())) continue;
            if (!apps.containsKey(packageName)) apps.put(packageName, new App(packageName, labelOf(manager, packageName)));
        }
        List<App> sorted = new ArrayList<>(apps.values());
        Collator collator = Collator.getInstance();
        Collections.sort(sorted, (a, b) -> collator.compare(a.label, b.label));
        return sorted;
    }

    /**
     * The sheet's finished channel row with the picked apps added before More. Called from the
     * patched share sheet after TikTok and Hushfeed have filtered the row, so a name hidden by hand
     * doesn't take a picked app away, and nothing is added when the whole row is hidden. Hands back
     * the list it was given when there's nothing to add or anything goes wrong.
     */
    public static List<?> withPicked(List<?> channels) {
        try {
            if (Settings.HIDE_SHARE_CHANNELS.get()) return channels;
            return addPicked(channels);
        } catch (Throwable ex) {
            HookStatus.threw(ShareModelFilter.FAMILY, "added apps", ex);
            Logger.printException(() -> "Could not add the picked apps to the share channels row", ex);
            return channels;
        }
    }

    static List<?> addPicked(List<?> channels) {
        if (channels == null) return null;
        List<String> picked = picked(Settings.SHARE_ADDED_APPS.get());
        Context context = Utils.getContext();
        if (picked.isEmpty() || context == null) return channels;
        PackageManager manager = context.getPackageManager();

        Set<String> shown = new HashSet<>();
        int more = -1;
        for (int index = 0; index < channels.size(); index++) {
            Object channel = channels.get(index);
            String key = Reflect.string(channel, "key", "key");
            if (more < 0 && MORE_KEY.equals(key)) more = index;
            if (key != null && key.startsWith(KEY_PREFIX)) shown.add(key.substring(KEY_PREFIX.length()));
            String packageName = packageOf(channel);
            if (packageName != null) shown.add(packageName);
        }

        List<Object> row = new ArrayList<>(channels);
        int at = more < 0 ? row.size() : more;
        int added = 0;
        for (String packageName : picked) {
            if (shown.contains(packageName) || !takesText(manager, packageName)) continue;
            Object channel = channelFor(manager, packageName);
            if (channel == null) continue;
            row.add(at++, channel);
            shown.add(packageName);
            added++;
        }
        if (added == 0) return channels;
        HookStatus.bound(ShareModelFilter.FAMILY, "added apps");
        return row;
    }

    /**
     * The share mode for a channel key. TikTok looks every channel's key up in a platform list its
     * server sends and drops a channel the list doesn't name, so an added app's channel is
     * answered here with the plain link share. Any other key gets {@link #NOT_OURS}.
     */
    public static int shareModeOf(@Nullable String key) {
        return key != null && key.startsWith(KEY_PREFIX) ? LINK_SHARE_MODE : NOT_OURS;
    }

    static boolean takesText(PackageManager manager, String packageName) {
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(packageName);
        return !manager.queryIntentActivities(send, 0).isEmpty();
    }

    @Nullable
    private static Object channelFor(PackageManager manager, String packageName) {
        String key = KEY_PREFIX + packageName;
        try {
            Class<?> targetType = Class.forName(TARGET_INFO);
            Class<?> infoType = Class.forName(CHANNEL_INFO);
            Constructor<?> info = infoType.getConstructor(String.class, String.class, String.class,
                    String.class, String.class, targetType);
            // No icon address and no target class: the channel sends to the package and Android
            // picks its share screen.
            Object channel = newChannel(info.newInstance(key, packageName, labelOf(manager, packageName),
                    "", "", targetType.getConstructor().newInstance()));
            if (channel != null) putIcon(manager, packageName, key);
            return channel;
        } catch (Throwable failure) {
            HookStatus.threw(ShareModelFilter.FAMILY, "added app", failure);
            Logger.printException(() -> "Could not add a picked app to the share sheet", failure);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static void putIcon(PackageManager manager, String packageName, String key) {
        if (iconMapRefused) return;
        try {
            Object map = iconMap();
            if (!(map instanceof Map)) return;
            Drawable icon = manager.getApplicationIcon(packageName);
            if (icon == null) return;
            Map<Object, Object> icons = (Map<Object, Object>) map;
            icons.put(key, icon);
            Drawable.ConstantState state = icon.getConstantState();
            icons.put(key + SQUARE_ICON_SUFFIX, state == null ? icon : state.newDrawable());
        } catch (UnsupportedOperationException readOnly) {
            // TikTok's map takes no additions on this build. The app still shows, without its icon.
            iconMapRefused = true;
            Logger.printInfo(() -> "Share sheet: the channel icon map is read only");
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not set a picked app's share sheet icon", failure);
        }
    }

    static String labelOf(PackageManager manager, String packageName) {
        try {
            ApplicationInfo info = manager.getApplicationInfo(packageName, 0);
            CharSequence label = manager.getApplicationLabel(info);
            return label == null || label.length() == 0 ? packageName : label.toString();
        } catch (Throwable notInstalled) {
            return packageName;
        }
    }

    // ---------------------------------------------------------------------------------------
    // TikTok's channel class, its package getter and its icon map change name with every build, so
    // the patch writes them into the bodies of these three bridges. What they do without the patch
    // is for the tests.

    /** TikTok's members as the tests stand them in. */
    interface Native {
        Object newChannel(Object info);
        String packageOf(Object channel);
        Object iconMap();
    }

    static volatile Native nativeForTests;

    static Object newChannel(Object info) {
        Native stand = nativeForTests;
        return stand == null ? null : stand.newChannel(info);
    }

    static String packageOf(Object channel) {
        Native stand = nativeForTests;
        return stand == null ? null : stand.packageOf(channel);
    }

    static Object iconMap() {
        Native stand = nativeForTests;
        return stand == null ? null : stand.iconMap();
    }
}
