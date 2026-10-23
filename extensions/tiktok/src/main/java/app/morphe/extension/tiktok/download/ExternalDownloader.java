/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;

import app.morphe.extension.shared.Logger;
import androidx.annotation.Nullable;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.share.ShareUrlSanitizer;

/**
 * Hands the video's link to another app instead of saving it here.
 *
 * <p>Some people already have a downloader they trust, and would rather the save button
 * opened that with the link filled in. The link is TikTok's own: {@code Aweme.getShareUrl()}
 * kept its name, and when it is empty the handle and the id build the same address.
 */
public final class ExternalDownloader {
    private static final String YTDLNIS_TYPE_EXTRA = "TYPE";
    private static final String YTDLNIS_BACKGROUND_EXTRA = "BACKGROUND";

    private ExternalDownloader() {}

    /** True when the link went to another app, so nothing here should save anything. */
    public static boolean handOff(Object aweme, Context context) {
        String target = packageName();
        if (target.isEmpty() || aweme == null || context == null) return false;

        String url = shareUrl(aweme);
        if (url == null) {
            Utils.showToastShort(L10n.t(
                    "This video has no link to send, so TikTok's own save runs instead"));
            return false;
        }

        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        // The same treatment a shared link gets. TikTok's own link carries the parameters that
        // say who sent it, and handing that to another app is still handing it out.
        send.putExtra(Intent.EXTRA_TEXT, ShareUrlSanitizer.rewriteShareUrl(url));
        if (isYtdlnis()) {
            send.putExtra(YTDLNIS_TYPE_EXTRA, ytdlnisDownloadType());
            send.putExtra(YTDLNIS_BACKGROUND_EXTRA, Settings.YTDLNIS_BACKGROUND.get());
        }
        send.setPackage(target);
        send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(send);
            return true;
        } catch (ActivityNotFoundException notInstalled) {
            String label = appLabel(context, target);
            Utils.showToastShort(label != null
                    ? L10n.f("%1$s isn't installed or doesn't take links", label)
                    : L10n.t("The downloader app you chose isn't installed. Check Send links to another app under Downloads."));
            return false;
        } catch (RuntimeException exception) {
            Logger.printException(() -> "Could not hand the link to " + target, exception);
            return false;
        }
    }

    @Nullable
    private static String appLabel(android.content.Context context, String packageName) {
        try {
            return context.getPackageManager()
                    .getApplicationLabel(context.getPackageManager()
                            .getApplicationInfo(packageName, 0))
                    .toString();
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            return null;
        }
    }

    /**
     * What is wrong with the app name as typed, or null when nothing is. An empty box is
     * fine and means the save stays in TikTok. Anything else has to look like a package
     * name, because {@link #packageName()} quietly drops what does not and the reader would
     * otherwise only find out at the next save.
     */
    @Nullable
    public static String packageNameProblem(String value) {
        if (value == null) return null;
        String name = value.trim();
        if (name.isEmpty() || name.matches(PACKAGE_NAME)) return null;
        return L10n.t("That isn't an app name. Try something like com.example.downloader, "
                + "or leave the box empty to keep saving in TikTok.");
    }

    /** What an Android package name looks like, and nothing that could be a path or an argument. */
    private static final String PACKAGE_NAME = "[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+";

    /** The app the link goes to, as typed, or empty when the save stays here. */
    static String packageName() {
        String value = Settings.EXTERNAL_DOWNLOADER_PACKAGE.get();
        if (value == null) return "";
        String name = value.trim();
        if (name.isEmpty() || !name.matches(PACKAGE_NAME)) {
            return "";
        }
        return name;
    }

    /** True only for the provider whose extra names and values are documented. */
    static boolean isYtdlnis() {
        return Settings.YTDLNIS_PACKAGE_NAME.equals(packageName());
    }

    /** A malformed imported value must never turn into an unsupported provider command. */
    static String ytdlnisDownloadType() {
        return "audio".equals(Settings.YTDLNIS_DOWNLOAD_TYPE.get()) ? "audio" : "video";
    }

    /** TikTok's own link for the video, or one built from the handle and the id. */
    /** The post's own link, which the long-press copy action reads as well as the hand-off. */
    public static String shareUrl(Object aweme) {
        String shared = Reflect.string(aweme, "getShareUrl", "shareUrl");
        if (shared != null) return shared;
        Object author = Reflect.property(aweme, "getAuthor", "author");
        String handle = Reflect.string(author, "getUniqueId", "uniqueId");
        String id = Reflect.string(aweme, "getAid", "aid");
        if (handle == null || id == null) return null;
        return "https://www.tiktok.com/@" + handle + "/video/" + id;
    }
}
