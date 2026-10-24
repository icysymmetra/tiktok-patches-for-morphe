/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.download;

import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;
import com.ss.android.ugc.aweme.base.model.UrlModel;
import java.util.List;
import java.util.ArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Selects only actual video gears, never cover or thumbnail URLs. */
public final class QualitySelector {
    private static final Pattern HEIGHT = Pattern.compile("(?<![0-9])(2160|1440|1080|960|720|640|576|540|480|432|360|288|240)(?![0-9])");
    private QualitySelector() {}

    /**
     * Every rendition of the video as TikTok received it, never the playback getter, which
     * Playback quality may narrow. getRawBitRate returns the backing list whole (Playback quality
     * leaves it alone for exactly this); without it the field is read under both names it has
     * had: bitRate on 46.2.3, bitRateList on 47.0.3. Reading bitRate alone found nothing on
     * 47.0.3, so every chosen quality fell back to TikTok's own save (S22, 2026-09-23).
     */
    static Object rawGears(Object video) {
        if (video == null) return null;
        Object raw = Reflect.invoke(video, "getRawBitRate");
        if (raw == null) raw = Reflect.readField(video, "bitRateList");
        if (raw == null) raw = Reflect.readField(video, "bitRate");
        return raw;
    }

    public static UrlModel download(Object video) {
        String mode = Settings.DOWNLOAD_VIDEO_QUALITY.get();
        if ("auto".equals(mode)) return null;
        // Separate DASH tracks need the full download path and muxer, not a video-only URL.
        if (Boolean.TRUE.equals(Reflect.invoke(video, "hasDashBitrate"))) return null;
        Object raw = rawGears(video);
        Object gear = raw instanceof List<?> ? chooseForFile(video, (List<?>) raw, mode) : null;
        Object address = Reflect.property(gear, "getPlayAddr", "playAddr");
        return address instanceof UrlModel ? (UrlModel) address : null;
    }

    /** The rendition TikTok's player is to play: any codec, since TikTok decodes them all. */
    public static Object choose(List<?> gears, String mode) {
        return choose(gears, mode, false);
    }

    /**
     * The rendition a saved file is made from: only one other players open ({@link #playable}),
     * and at the same height H.264 over HEVC.
     */
    static Object chooseForFile(List<?> gears, String mode) {
        return choose(gears, mode, true);
    }

    /**
     * The rendition a saved file of this video is made from, or null for TikTok's own file. When
     * the asked height is served only as ByteVC2 the playable choice is a shorter one, and
     * TikTok's own watermark-free download, which is H.264, can be the asked height: it wins when
     * its address says it is taller than the choice and no taller than asked (refutation review
     * of bd52abf1). An address that says nothing keeps the playable choice.
     */
    static Object chooseForFile(Object video, List<?> gears, String mode) {
        Object chosen = chooseForFile(gears, mode);
        if (chosen == null || "lowest".equals(mode) || "medium".equals(mode)) return chosen;
        int chosenHeight = height(chosen);
        Object tallest = choose(gears, mode, false);
        if (tallest == null || height(tallest) <= chosenHeight) return chosen;
        int own = ownHeight(video);
        int asked = "highest".equals(mode) ? 0 : Integer.parseInt(mode);
        return own > chosenHeight && (asked == 0 || own <= asked) ? null : chosen;
    }

    /**
     * How tall TikTok's own watermark-free download is, as the shorter side the way gear names
     * count; 0 when unknown or when only the watermarked address exists. Only the clean address
     * counts: a deferred save falls to the download addresses, and deferring onto a video with
     * nothing but the watermarked one would put the watermark on a save the rendition path kept
     * clean (refutation review of f792e71e).
     */
    static int ownHeight(Object video) {
        Object address = Reflect.property(video, "getDownloadNoWatermarkAddr", "downloadNoWatermarkAddr");
        return usable(address) ? dimension(address) : 0;
    }

    private static Object choose(List<?> gears, String mode, boolean forFile) {
        if (gears == null || "auto".equals(mode)) return null;
        if ("medium".equals(mode)) {
            List<Object> usable = new ArrayList<>();
            for (Object gear : gears) {
                if (hasUsableAddress(gear) && (!forFile || playable(gear))) usable.add(gear);
            }
            java.util.Collections.sort(usable, (a, b) -> {
                int rank = Integer.compare(height(a), height(b));
                if (rank == 0) rank = Long.compare(number(a, "getBitRate", "bitRate"), number(b, "getBitRate", "bitRate"));
                if (rank == 0) rank = Long.compare(size(a), size(b));
                return rank;
            });
            return usable.isEmpty() ? null : usable.get(usable.size() / 2);
        }
        int target;
        switch (mode == null ? "" : mode) {
            case "highest": case "lowest": target = 0; break;
            case "1080": case "720": case "540": case "480": case "360": target = Integer.parseInt(mode); break;
            default: return null;
        }
        Object best = null;
        for (Object gear : gears) {
            if (!hasUsableAddress(gear)) continue;
            if (forFile && !playable(gear)) continue;
            if (target > 0 && height(gear) == 0) continue;
            if (best == null || better(gear, best, target, "lowest".equals(mode), forFile)) best = gear;
        }
        return best;
    }

    /**
     * TikTok's codec code for a rendition (is_bytevc1): 0 is H.264, 1 is ByteVC1, which is HEVC
     * and plays on Android and in most players, and 2 is ByteVC2, which only TikTok's own player
     * decodes. A rendition without the code reads as H.264.
     */
    static int codec(Object gear) {
        Object code = Reflect.property(gear, "isBytevc1", "isBytevc1");
        return code instanceof Number ? ((Number) code).intValue() : 0;
    }

    /**
     * Whether a saved file of this rendition plays outside TikTok. 47.0.3 serves some heights only
     * as ByteVC2 (gear names ending in _2): saved, those came out with a video track no player
     * knew (codec tag bvc2, S22, 2026-09-23). None of them is chosen for a file; when nothing else
     * is left, TikTok's own save, which is H.264, runs instead. Playback keeps them.
     */
    static boolean playable(Object gear) {
        int code = codec(gear);
        return code == 0 || code == 1;
    }

    private static boolean better(Object candidate, Object current, int target, boolean lowest, boolean forFile) {
        int a = height(candidate), b = height(current);
        if (target > 0) {
            if ((a <= target) != (b <= target)) return a <= target;
            if (a != b) return a <= target ? a > b : a < b;
        }
        // At the same height, the file more players can open: H.264 over HEVC.
        if (forFile && a == b && codec(candidate) != codec(current)) return codec(candidate) < codec(current);
        int compare = Integer.compare(a, b);
        if (compare == 0) compare = Long.compare(number(candidate, "getBitRate", "bitRate"), number(current, "getBitRate", "bitRate"));
        if (compare == 0) compare = Long.compare(size(candidate), size(current));
        return lowest ? compare < 0 : compare > 0;
    }

    /** A gear the way a report names it: its gear name and the height it plays at. */
    public static String describe(Object gear) {
        String name = Reflect.string(gear, "getGearName", "gearName");
        int height = height(gear);
        return (name == null || name.isEmpty() ? "unnamed" : name) + (height > 0 ? " " + height + "p" : "");
    }

    /** A gear's size as the shorter side, from its name or its address, or 0. */
    public static int heightOf(Object gear) {
        return height(gear);
    }

    private static int height(Object gear) {
        String name = Reflect.string(gear, "getGearName", "gearName");
        if (name != null) {
            Matcher matcher = HEIGHT.matcher(name);
            if (matcher.find()) return Integer.parseInt(matcher.group(1));
        }
        return dimension(Reflect.property(gear, "getPlayAddr", "playAddr"));
    }

    /** An address's size as the shorter side, or the one side it gives, or 0. */
    private static int dimension(Object address) {
        long width = number(address, "getWidth", "width"), height = number(address, "getHeight", "height");
        return (int) (width > 0 && height > 0 ? Math.min(width, height) : Math.max(width, height));
    }

    static long size(Object gear) {
        return addressSize(Reflect.property(gear, "getPlayAddr", "playAddr"));
    }

    /** The size TikTok lists for an address, or 0. */
    static long addressSize(Object address) {
        return number(address, "getSize", "size");
    }

    private static long number(Object value, String getter, String field) {
        Object raw = Reflect.property(value, getter, field);
        return raw instanceof Number ? ((Number) raw).longValue() : 0;
    }

    /**
     * Whether a rendition's play address holds a URL this would choose: the same test for the
     * player's gears and a save's, and nothing about the codec, so ByteVC2 passes it. A missing,
     * empty or unreadable address does not.
     */
    public static boolean hasUsableAddress(Object gear) {
        return usable(Reflect.property(gear, "getPlayAddr", "playAddr"));
    }

    private static boolean usable(Object address) {
        Object urls = Reflect.property(address, "getUrlList", "urlList");
        if (!(urls instanceof List<?>)) return false;
        for (Object url : (List<?>) urls) {
            if (url instanceof String && MediaTransport.hasAllowedShape((String) url)) return true;
        }
        return false;
    }
}
