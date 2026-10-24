/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.playback;

import android.content.Context;
import android.net.ConnectivityManager;
import android.os.SystemClock;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.download.QualitySelector;
import app.morphe.extension.tiktok.settings.Settings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class PlaybackQuality {
    /**
     * How long the metered answer is reused. Every parsed video model asks, and the answer
     * comes from a system service, so it is not worth a binder call each time.
     */
    static final long METERED_CACHE_MS = 5_000L;

    /** The name this reports under on the Hook status row. */
    static final String FAMILY = "playback quality";

    /** The two getters the patch hooks, named the way the report should read. */
    static final String VIDEO_MODEL = "Video";
    static final String VIDEO_MODEL_GETTER = "getVideoModelStr";
    static final String DASH_MODEL = "VideoUrlModel";
    static final String DASH_MODEL_GETTER = "getDashVideoModelStr";
    /** The gear list getter both owners share, the path a phone with empty models is left with. */
    static final String GEARS_GETTER = "getBitRate";
    /**
     * The player kit's own models and the one setter every route into them goes through.
     *
     * <p>The player never reads {@code Video.getBitRate}. All four converters that build a
     * {@code SimVideo} or {@code SimVideoUrlModel} copy {@code getRawBitRate}, the list the
     * patch leaves whole for downloads and native DASH reconstruction, and the player's bitrate
     * selectors then choose out of what the setter stored. So a gear picked out of the aweme
     * model's getter was reported in the export and never played, which is what issue #3
     * described. Filtering what reaches the setter filters what the player chooses from, and
     * nothing else.
     */
    static final String PLAYER_VIDEO = "SimVideo";
    static final String PLAYER_URL_MODEL = "SimVideoUrlModel";
    static final String PLAYER_SETTER = "setBitRate";

    /**
     * The last few models read, by getter, quality and text. It held one: TikTok reads both model
     * getters for a video and a prefetched neighbour between them, so each switch re-parsed and
     * re-serialised a model it had just filtered, with TikTok's own parsed cache turned off by
     * cacheModel while a quality is set.
     */
    private static final int CACHE_ENTRIES = 4;
    private static final Map<JsonKey, String> CACHE = new LinkedHashMap<JsonKey, String>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<JsonKey, String> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };
    /** Models actually parsed, for tests to tell a cache hit from a second parse. */
    static volatile int parsedForTests;
    private static volatile MeteredState meteredState;

    /**
     * Getters already described in the log, so an unusable model costs one line and not one per
     * video. Hook status dedupes the row itself; this holds the log to the same bound.
     */
    private static final Set<String> DESCRIBED =
            Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** Distinct gear choices already written, so a feed of the same gears costs one line. */
    private static final Set<String> CHOICES = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final int MAX_CHOICES = 32;

    private PlaybackQuality() {}

    /**
     * The quality that applies right now. The mobile data choice is a ceiling: it lowers what
     * plays on a metered connection and never raises it, so setting 360p everywhere and 720p
     * on mobile data still gives 360p.
     */
    public static String mode() {
        return effectiveMode(Settings.PLAYBACK_QUALITY.get(), Settings.PLAYBACK_QUALITY_METERED.get(), isMetered());
    }

    static String effectiveMode(String mode, String meteredMode, boolean metered) {
        if (!metered || meteredMode == null || "off".equals(meteredMode)) return mode;
        return ceiling(meteredMode) < ceiling(mode) ? meteredMode : mode;
    }

    /** The tallest video a mode allows. Lower is more restrictive. */
    private static int ceiling(String mode) {
        if (mode == null) return Integer.MAX_VALUE;
        switch (mode) {
            case "lowest": return 0;
            case "highest": return Integer.MAX_VALUE - 1;
            case "auto": return Integer.MAX_VALUE;
            default:
                try {
                    return Integer.parseInt(mode);
                } catch (NumberFormatException ignored) {
                    return Integer.MAX_VALUE;
                }
        }
    }

    private static boolean isMetered() {
        long now = SystemClock.elapsedRealtime();
        MeteredState cached = meteredState;
        if (cached != null && now - cached.atMs <= METERED_CACHE_MS) return cached.metered;

        boolean metered = false;
        try {
            Context context = Utils.getContext();
            ConnectivityManager manager = context == null ? null
                    : (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            metered = manager != null && manager.isActiveNetworkMetered();
        } catch (Throwable ignored) {
            // No permission or no service: treat the connection as unmetered and change nothing.
        }
        meteredState = new MeteredState(metered, now);
        return metered;
    }

    /** The gear list {@code Video.getBitRate} hands back. */
    public static List<?> filterVideoGears(List<?> original) {
        return filterGears(original, VIDEO_MODEL, GEARS_GETTER);
    }

    /** The gear list {@code VideoUrlModel.getBitRate} hands back. */
    public static List<?> filterDashGears(List<?> original) {
        return filterGears(original, DASH_MODEL, GEARS_GETTER);
    }

    /** The gear list handed to {@code SimVideo.setBitRate}, which the player carries. */
    public static List<?> filterPlayerVideoGears(List<?> original) {
        return filterGears(original, PLAYER_VIDEO, PLAYER_SETTER);
    }

    /** The gear list handed to {@code SimVideoUrlModel.setBitRate}, which the player chooses from. */
    public static List<?> filterPlayerUrlModelGears(List<?> original) {
        return filterGears(original, PLAYER_URL_MODEL, PLAYER_SETTER);
    }

    /**
     * Picks one gear out of the list the player chooses from, and says which.
     *
     * <p>This is the path issue #3's phone is left with: both model strings come back empty
     * there, and until now this path said nothing, so an export could not tell a list that was
     * never handed over from a gear that was chosen and then ignored by the player. A chosen
     * gear is a bound member of the Hook status family plus one report line per distinct
     * choice, naming the gear, its height and everything it was picked from. A list with gears
     * in it and nothing playable among them is a miss, named by its getter. A null or empty
     * list is an item with no gears at all, a photo post for one, and is left alone like a
     * model with one gear: on the S22 every feed has some, and a miss for each would leave the
     * family reading as broken on a build where the path works.
     */
    private static List<?> filterGears(List<?> original, String owner, String member) {
        String mode = mode();
        if (original == null || original.isEmpty() || "auto".equals(mode)) return original;
        // With Prefer H.264 video or Play SDR instead of HDR on, the choice is made among the
        // gears those switches keep, whichever of the setter hooks runs first.
        List<?> offered = H264Playback.preferredGears(original);
        Object selected = QualitySelector.choose(offered, mode);
        if (selected == null) {
            HookStatus.missingMember(FAMILY, "playable gear list from", owner, member);
            if (DESCRIBED.add(owner + '#' + member)) {
                Logger.printDebug(() -> owner + '.' + member
                        + " returned gears with no playable address, so playback quality leaves it to the app");
            }
            return offered;
        }
        HookStatus.bound(FAMILY, owner + '#' + member);
        // One gear is nothing to choose from. The list goes back as it came and no line is
        // written, or a feed of single-gear items pushes the choices that matter out of the
        // export, which is what the first export with gear lines in it looked like.
        if (offered.size() == 1) return offered;
        describeChoice(owner, member, mode, offered, selected);
        return new ArrayList<>(Collections.singletonList(selected));
    }

    /**
     * One line per kind of choice: the mode, the gear it settled on and the gears it had.
     * That is the line the device check reads to prove "lowest" is the smallest offered.
     *
     * <p>A kind is the mode, the source, the ladder of sizes offered and the size picked. Gear
     * names and bitrates differ on nearly every video, and keyed by them the lines filled the
     * event buffer: an export of 2026-09-27 (#21) was almost nothing else. The first line of
     * each kind keeps the names. The set starts over once it holds {@link #MAX_CHOICES} kinds,
     * so a quality mode switched late in a long session still writes the line that proves it.
     */
    private static void describeChoice(String owner, String member, String mode, List<?> offered, Object selected) {
        java.util.TreeSet<Integer> ladder = new java.util.TreeSet<>();
        for (Object gear : offered) ladder.add(QualitySelector.heightOf(gear));
        String kind = mode + '|' + owner + '#' + member + '|' + ladder + '|' + QualitySelector.heightOf(selected);
        if (CHOICES.size() >= MAX_CHOICES) CHOICES.clear();
        if (!CHOICES.add(kind)) return;
        StringBuilder gears = new StringBuilder();
        for (Object gear : offered) {
            if (gears.length() > 0) gears.append(", ");
            gears.append(QualitySelector.describe(gear));
        }
        String line = "Playback quality " + mode + " picked " + QualitySelector.describe(selected)
                + " of " + offered.size() + " gears from " + owner + '#' + member + ": " + gears;
        Logger.printInfo(() -> line);
    }

    public static Object cacheModel(Object original) {
        // Native parsed objects can retain a previous quality. Rebuild them from the filtered model.
        return "auto".equals(mode()) ? original : null;
    }

    public static Map<?, ?> filterMap(Map<?, ?> original) {
        String mode = mode();
        if (original == null || "auto".equals(mode)) return original;
        Object raw = original.get("dynamic_video");
        if (!(raw instanceof Map<?, ?>)) return original;
        Map<?, ?> dynamic = (Map<?, ?>) raw;
        Object list = dynamic.get("dynamic_video_list");
        if (!(list instanceof List<?>) || ((List<?>) list).size() < 2) return original;
        List<?> variants = (List<?>) list;
        int selected = select(variants, mode);
        if (selected < 0) return original;
        Map<Object, Object> filtered = new LinkedHashMap<>(dynamic);
        filtered.put("dynamic_video_list", new ArrayList<>(Collections.singletonList(variants.get(selected))));
        Map<Object, Object> result = new LinkedHashMap<>(original);
        result.put("dynamic_video", filtered);
        return result;
    }

    /** The model string {@code Video.getVideoModelStr} hands back. */
    public static String filterVideoModelJson(String original) {
        return filterJson(original, VIDEO_MODEL, VIDEO_MODEL_GETTER);
    }

    /** The adaptive model string {@code VideoUrlModel.getDashVideoModelStr} hands back. */
    public static String filterDashVideoModelJson(String original) {
        return filterJson(original, DASH_MODEL, DASH_MODEL_GETTER);
    }

    /**
     * Picks one gear out of an adaptive model, and hands back whatever it was given when it
     * cannot.
     *
     * <p>A getter that returns something this cannot read is not an error to show anyone. Some
     * builds and some accounts leave the string empty, and a build that renames the model shape
     * hands back something else again; either way the right answer is the native quality and
     * silence. It used to parse whatever arrived and report the {@link JSONException} through
     * {@code Logger.printException}, which put {@code PlaybackQuality: Could not read the
     * playback quality model} on screen once per video. The place that question belongs is the
     * Hook status row, which names the getter and says it once.
     */
    private static String filterJson(String original, String owner, String getter) {
        String mode = mode();
        if (original == null || "auto".equals(mode)) return original;
        // The getter is part of what is cached. Both getters can hand back the same unusable
        // string, and without this the first one to arrive answers for the second and Hook
        // status names only one of them.
        JsonKey key = new JsonKey(getter, mode, original);
        synchronized (CACHE) {
            String cached = CACHE.get(key);
            if (cached != null) return cached;
        }

        String result = original;
        // An empty string is what issue #3 reported: new JSONObject("") throws "End of input at
        // character 0". A model is a JSON object or it is not one this can pick a gear out of.
        // Read the first character rather than trimming: a well formed model is the whole
        // response body and copying it on every video costs more than the check saves.
        int first = 0;
        while (first < original.length() && Character.isWhitespace(original.charAt(first))) first++;
        if (first == original.length()) {
            // An empty model is an ordinary video, not a miss. Video.getVideoModelStr hands its
            // stored string back only when hasDashBitrate() is true and builds nothing
            // otherwise, so every non-adaptive item on every retained build arrives here as "",
            // and the picker's work for that item is done on the gear list. Counting it as a
            // miss made the reporter's "2 found, 2 missing" read as a broken build.
            if (DESCRIBED.add(owner + '#' + getter + " empty")) {
                Logger.printDebug(() -> owner + '.' + getter
                        + " returned an empty model, so playback quality reads the gear list for that video");
            }
        } else if (original.charAt(first) != '{') {
            unusable(owner, getter, "a model that is not a JSON object");
        } else {
            try {
                parsedForTests++;
                JSONObject root = new JSONObject(original);
                JSONObject dynamic = root.optJSONObject("dynamic_video");
                JSONArray values = dynamic == null ? null : dynamic.optJSONArray("dynamic_video_list");
                if (values != null && values.length() > 1) {
                    List<Object> variants = new ArrayList<>();
                    for (int i = 0; i < values.length(); i++) variants.add(values.get(i));
                    int selected = select(variants, mode);
                    if (selected >= 0) {
                        dynamic.put("dynamic_video_list", new JSONArray().put(values.get(selected)));
                        result = root.toString();
                    }
                }
                // A readable object with one gear, or none, is an ordinary video rather than a
                // miss: the getter gave what it promised and there was nothing to choose from.
                HookStatus.bound(FAMILY, owner + '#' + getter);
            } catch (JSONException exception) {
                unusable(owner, getter, "a model it could not read");
            }
        }
        synchronized (CACHE) {
            CACHE.put(key, result);
        }
        return result;
    }

    /**
     * Records a getter whose model no gear can be chosen out of. Hook status carries it to the
     * Diagnostics row and the exported report; the log line is written once per getter, so a
     * feed that scrolls all afternoon adds nothing after the first video.
     */
    private static void unusable(String owner, String getter, String what) {
        HookStatus.missingMember(FAMILY, "usable model from", owner, getter);
        if (!DESCRIBED.add(owner + '#' + getter)) return;
        Logger.printDebug(() -> owner + '.' + getter + " returned " + what
                + ", so playback quality leaves it to the app");
    }

    /** Forgets which getters have been described, so a test can watch the first one again. */
    static void resetForTests() {
        DESCRIBED.clear();
        CHOICES.clear();
        synchronized (CACHE) {
            CACHE.clear();
        }
        parsedForTests = 0;
    }

    private static int select(List<?> variants, String mode) {
        List<Variant> gears = new ArrayList<>();
        for (int i = 0; i < variants.size(); i++) gears.add(new Variant(variants.get(i), i));
        Object selected = QualitySelector.choose(gears, mode);
        return selected == null ? -1 : ((Variant) selected).index;
    }

    private static Object value(Object source, String key) {
        if (source instanceof JSONObject) return ((JSONObject) source).opt(key);
        return source instanceof Map<?, ?> ? ((Map<?, ?>) source).get(key) : null;
    }

    private static long number(Object source, String key) {
        Object value = value(source, key);
        if (value instanceof Number) return ((Number) value).longValue();
        try { return value == null ? 0 : Long.parseLong(value.toString()); }
        catch (NumberFormatException exception) { return 0; }
    }

    /** Named fields let the shared gear selector read the same metadata as native BitRate objects. */
    public static final class Variant {
        public final String gearName;
        public final long bitRate;
        public final Address playAddr;
        final int index;
        Variant(Object source, int index) {
            this.index = index;
            Object name = value(source, "gear_name");
            gearName = name == null ? "" : name.toString();
            long actual = number(source, "real_bitrate");
            bitRate = actual > 0 ? actual : number(source, "bitrate");
            playAddr = new Address(source);
        }
    }

    public static final class Address {
        public final long size, width, height;
        public final List<String> urlList = new ArrayList<>();
        Address(Object source) {
            size = number(source, "size"); width = number(source, "vwidth"); height = number(source, "vheight");
            for (String key : new String[]{"main_url", "backup_url_1", "backup_url_2", "backup_url_3"}) {
                Object url = value(source, key);
                if (url instanceof String) urlList.add((String) url);
            }
        }
    }

    private static final class MeteredState {
        final boolean metered;
        final long atMs;
        MeteredState(boolean metered, long atMs) { this.metered = metered; this.atMs = atMs; }
    }

    /** A model by getter, quality and text; the text's hash is the String's own, computed once. */
    private static final class JsonKey {
        final String getter, mode, source;
        JsonKey(String getter, String mode, String source) {
            this.getter = getter; this.mode = mode; this.source = source;
        }
        @Override public boolean equals(Object other) {
            if (!(other instanceof JsonKey)) return false;
            JsonKey that = (JsonKey) other;
            return getter.equals(that.getter) && mode.equals(that.mode) && source.equals(that.source);
        }
        @Override public int hashCode() {
            return (getter.hashCode() * 31 + mode.hashCode()) * 31 + source.hashCode();
        }
    }
}
