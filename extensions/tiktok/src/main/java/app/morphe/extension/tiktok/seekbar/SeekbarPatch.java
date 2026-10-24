/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.seekbar;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

@SuppressWarnings("unused")
public final class SeekbarPatch {
    private static final String LOG_PREFIX = "[Morphe TikTok Seekbar]";

    private SeekbarPatch() {}

    public static boolean isEnabled() {
        return Settings.SHOW_SEEKBAR.get();
    }

    public static int overrideSeekbarShowType(int seekbarType) {
        if (!Settings.SHOW_SEEKBAR.get()) return seekbarType;
        if (seekbarType != 3 && seekbarType != 4) return seekbarType;

        if (Settings.DEBUG.get()) {
            Logger.printDebug(() -> LOG_PREFIX + " Overrode seekbar show type " + seekbarType + " -> 0");
        }
        return 0;
    }

    /**
     * TikTok 47.0.3 returns from its show-type setter when the stored type already equals the
     * requested type. After {@link #overrideSeekbarShowType(int)} changes a hidden request to
     * zero, that shortcut can leave the actual progress view invisible even though the stored
     * type says it is visible. Give only that equality check a value that cannot match so the
     * host runs its normal visible branch and writes the real requested type back to the field.
     */
    public static int forceSeekbarRefresh(int storedType, int requestedType) {
        if (!Settings.SHOW_SEEKBAR.get() || requestedType != 0 || storedType != requestedType) {
            return storedType;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * TikTok's "draft progress bar" flag on a video, read by its can-drag gate once the gate's
     * own experiment has said no. The server leaves it off on most videos, so with the
     * experiment off the bar stayed hidden on all of them. Any non-zero value passes the gate.
     */
    public static int overrideDraftProgressBar(int value) {
        if (!Settings.SHOW_SEEKBAR.get()) return value;
        return value != 0 ? value : 1;
    }

    public static int overrideThumbnailGate(String key, int value) {
        if (!"seekbar_show_thumbnail_when_drag".equals(key)) return value;
        return Settings.SHOW_SEEKBAR_THUMBNAIL.get() ? 1 : value;
    }

    /** Videos a preview line was written for, so each logs once; weak, so none are kept alive. */
    private static final Map<Object, Boolean> previewLogged =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * TikTok's check for the picture above the bar while it's dragged (#140). Past the gate
     * {@link #overrideThumbnailGate} opens, the picture needs the video's preview sheets
     * ({@code Video.bigThumbs}) with an image address on each, and those come from the server
     * alone. With Log diagnostics on, each video logs once how many sheets it brought, so a
     * report says which side is missing. TikTok asks this while laying out the bar, so it runs
     * often; with Log diagnostics off it stops at the setting.
     */
    public static void onPreviewCheck(Object video) {
        HookStatus.bound("seekbar preview", "check");
        if (!BaseSettings.DEBUG.get() || !firstPreviewLook(video)) return;
        String sheets = describePreviewSheets(video);
        Logger.printDebug(() -> LOG_PREFIX + " preview " + sheets
                + " switch=" + Settings.SHOW_SEEKBAR_THUMBNAIL.get());
    }

    /** True the first time a video is seen here, never for null. */
    static boolean firstPreviewLook(Object video) {
        return video != null && previewLogged.put(video, Boolean.TRUE) == null;
    }

    /**
     * {@code sheets=N complete=M}: the video's preview sheets and how many carry both image
     * lists, which is what TikTok's loader asks of every one before it shows anything.
     */
    static String describePreviewSheets(Object aweme) {
        Object video = Reflect.property(aweme, "getVideo", "video");
        if (video == null) return "sheets=none (no video)";
        Object sheets = Reflect.property(video, "getBigThumbs", "bigThumbs");
        if (!(sheets instanceof List)) return "sheets=0 complete=0";
        List<?> list = (List<?>) sheets;
        int complete = 0;
        for (Object sheet : list) {
            if (hasItems(Reflect.property(sheet, "getImgUrls", "imgUrls"))
                    && hasItems(Reflect.property(sheet, "getImgUris", "imgUris"))) {
                complete++;
            }
        }
        return "sheets=" + list.size() + " complete=" + complete;
    }

    private static boolean hasItems(Object value) {
        return value instanceof List && !((List<?>) value).isEmpty();
    }
}
