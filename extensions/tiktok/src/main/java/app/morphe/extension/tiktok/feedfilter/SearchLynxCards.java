/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.FeedFilterCounters;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Locale;
import java.util.WeakHashMap;

/**
 * The server-drawn Lynx cards in the search results, TikTok's Short Drama block among them: a
 * header, an Explore button, category chips and a row of dramas, sitting in the results list as
 * a card of its own.
 *
 * <p>TikTok 47.0.3 streams the Top results in chunks, and a chunk that carries no card still
 * carries patches, from which the page's model builds a Lynx card and slots it into the list. So
 * the card never passes through the result list the other search filters read. It is judged where
 * its row binds it instead: the Top results adapter hands each card to one of two holders (the
 * ten-parameter one, or DynamicViewHolder for the cards TikTok routes there), and both hand this
 * (holder, fragment, patch); the other lists' Lynx card cell hands (cell, item). A card Hide mini
 * dramas takes, or a hotel deals block Remove ads takes, has its row collapsed to nothing, and the
 * row comes back on the bind that hands it another card, or on any bind once the switch is off.
 */
public final class SearchLynxCards {
    /** Route name for the diagnostic report. */
    static final String SOURCE = "SearchLynxCard";
    static final String DRAMA_REASON = "miniDramaCard";
    static final String HOTEL_REASON = "hotelDealsCard";

    private static final Object LOCK = new Object();
    /** Rows this collapsed, with the height and visibility they had. */
    private static final WeakHashMap<View, int[]> COLLAPSED = new WeakHashMap<>();
    /** The card each row was last bound to, so a late re-collapse can tell a rebind happened. */
    private static final WeakHashMap<View, Object> BOUND = new WeakHashMap<>();
    private static volatile boolean warned;

    private SearchLynxCards() {
    }

    /** The Lynx card cell {@code cell} is binding {@code item}. Nothing may escape into TikTok's bind. */
    public static void onCardBound(Object cell, Object item) {
        try {
            judge(cell, patchOf(item));
        } catch (Throwable failure) {
            warnOnce(failure);
        }
    }

    /**
     * One of the Top results' Lynx holders is binding {@code patch} into its row, hooked as
     * (holder, fragment, patch) on both.
     */
    public static void onHolderBound(Object holder, Object fragment, Object patch) {
        try {
            judge(holder, patch);
        } catch (Throwable failure) {
            warnOnce(failure);
        }
    }

    private static void warnOnce(Throwable failure) {
        if (!warned) {
            warned = true;
            Logger.printException(() -> "Could not judge a search Lynx card", failure);
        }
    }

    /** Collapses the row a hidden card is bound into, and gives any other row its size back. */
    private static void judge(Object holder, Object patch) {
        Object itemView = Reflect.readField(holder, "itemView");
        if (!(itemView instanceof View)) return;
        View row = (View) itemView;
        // A row is bound again and again while the list scrolls; the export counts a card once
        // for each time a row takes it, not once per bind.
        boolean fresh;
        synchronized (LOCK) {
            fresh = BOUND.put(row, patch) != patch;
        }
        String kind = kind(patch);
        if (fresh) {
            FeedFilterCounters.sawList(SOURCE, 1);
            FeedFilterCounters.sawKind(SOURCE, kind);
        }
        if (BaseSettings.DEBUG.get()) {
            String shape = schemaShape(Reflect.readField(patch, "schema"));
            String fields = patchFields(patch);
            Logger.printDebug(() -> "[Morphe TikTok FeedFilter] search lynx card: " + kind + " | " + shape + " | " + fields);
        }
        String reason = hideReason(patch);
        if (reason != null) {
            collapse(row);
            if (fresh) {
                FeedFilterCounters.removedItems(SOURCE, 1, reason);
                if (HOTEL_REASON.equals(reason)) FeedFilterCounters.sawKind(AdsFilter.AD_SIGNALS_SOURCE, "hotel deals card");
            }
            // TikTok's own bind runs after this and may size the row again, so once it has
            // returned the row is collapsed again, if it still holds this card. The pass runs on
            // the main looper, where nothing may escape either.
            row.post(() -> {
                try {
                    Object current;
                    synchronized (LOCK) {
                        current = BOUND.get(row);
                    }
                    if (current == patch && hideReason(patch) != null) collapse(row);
                } catch (Throwable failure) {
                    warnOnce(failure);
                }
            });
        } else {
            restore(row);
        }
    }

    /** The removal reason when a switch that's on hides this card, or null when it stays. */
    private static String hideReason(Object patch) {
        if (Settings.HIDE_MINI_DRAMAS.get() && isDrama(patch)) return DRAMA_REASON;
        if (Settings.REMOVE_ADS.get() && isHotelDeals(patch)) return HOTEL_REASON;
        return null;
    }

    /**
     * TikTok's hotel deals block (a cashback banner over hotels with prices quoted by booking
     * sites). It carries no ad flag or ad key, so it is told by its raw data: 47.4.3 serves it as
     * a nearby_poi object whose hotels each list the merchants quoting a price (ls_merchant_list).
     * A landmark's place card (poi_content_card) and the Must Eat ranking
     * (poi_must_eat_ranking_card) list no merchants and stay.
     */
    static boolean isHotelDeals(Object patch) {
        if (patch == null) return false;
        Object raw = Reflect.readField(patch, "rawData");
        if (!(raw instanceof String) || !((String) raw).contains("ls_merchant_list")) return false;
        try {
            org.json.JSONObject nearby = new org.json.JSONObject((String) raw).optJSONObject("nearby_poi");
            org.json.JSONArray hotels = nearby == null ? null : nearby.optJSONArray("poi_info_list");
            if (hotels == null) return false;
            for (int i = 0; i < hotels.length(); i++) {
                org.json.JSONObject hotel = hotels.optJSONObject(i);
                org.json.JSONArray merchants = hotel == null ? null : hotel.optJSONArray("ls_merchant_list");
                if (merchants != null && merchants.length() > 0) return true;
            }
        } catch (org.json.JSONException notAnObject) {
            // The raw data comes from the network; a card it can't read is ordinary content.
        }
        return false;
    }

    /**
     * A short-drama module: the Lynx template or the server's source type names short or mini
     * dramas (short_drama_general_card, short_drama_search_card_rlynx2 on 47.0.3). A card that only
     * says "drama", a TV-drama hub or a melodrama, is not one.
     */
    static boolean isDrama(Object patch) {
        if (patch == null) return false;
        return namesDrama(lynxTemplate(Reflect.readField(patch, "schema")))
                || namesDrama(FeedItemsFilter.searchCardSource(Reflect.readField(patch, "alaSrc")));
    }

    private static boolean namesDrama(String name) {
        if (name == null) return false;
        String folded = name.toLowerCase(Locale.ROOT);
        for (String token : DRAMA_TOKENS) {
            if (folded.contains(token)) return true;
        }
        return false;
    }

    private static final String[] DRAMA_TOKENS = {"short_drama", "shortdrama", "mini_drama", "minidrama"};

    /*
     * Each item class's DynamicPatch field, or NO_PATCH_FIELD, found once. This runs for every
     * Lynx card bound, and every call used to copy out the declared fields of the class and
     * each parent.
     */
    private static final Object NO_PATCH_FIELD = new Object();
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Object> PATCH_FIELDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The Lynx data an item carries: the one field whose type is TikTok's DynamicPatch. */
    static Object patchOf(Object item) {
        if (item == null) return null;
        Object found = PATCH_FIELDS.get(item.getClass());
        if (found == null) {
            found = patchField(item.getClass());
            PATCH_FIELDS.put(item.getClass(), found);
        }
        if (found == NO_PATCH_FIELD) return null;
        try {
            return ((Field) found).get(item);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object patchField(Class<?> itemClass) {
        for (Class<?> type = itemClass; type != null && type != Object.class; type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!"DynamicPatch".equals(field.getType().getSimpleName())) continue;
                try {
                    field.setAccessible(true);
                    return field;
                } catch (Throwable ignored) {
                    return NO_PATCH_FIELD;
                }
            }
        }
        return NO_PATCH_FIELD;
    }

    /** What a Lynx card is, by shape alone: its source type and its template. Never what it says. */
    static String kind(Object patch) {
        if (patch == null) return "lynx card without data";
        StringBuilder kind = new StringBuilder("lynx card");
        String source = FeedItemsFilter.searchCardSource(Reflect.readField(patch, "alaSrc"));
        if (source != null) kind.append(' ').append(source);
        String template = lynxTemplate(Reflect.readField(patch, "schema"));
        if (template != null) kind.append(" template ").append(template);
        return kind.toString();
    }

    /**
     * The template a Lynx schema names: its channel, or else the folder its bundle sits in. Nothing
     * else from the schema, whose other parameters can carry the search itself.
     */
    static String lynxTemplate(Object schema) {
        if (!(schema instanceof String)) return null;
        String value = schemaParameter((String) schema, "channel");
        if (value == null) {
            String url = schemaParameter((String) schema, "surl");
            if (url == null) url = schemaParameter((String) schema, "url");
            if (url != null) {
                int cut = url.indexOf('?');
                String path = cut >= 0 ? url.substring(0, cut) : url;
                String[] parts = path.split("/");
                value = parts.length >= 2 ? parts[parts.length - 2] : null;
            }
        }
        if (value == null) return null;
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) {
            if (out.length() == 60) break;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') out.append(c);
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** For the debug log only: a schema's scheme, host and path, its parameter names, and its template parameters. */
    static String schemaShape(Object schema) {
        if (!(schema instanceof String)) return "no schema";
        String text = (String) schema;
        int query = text.indexOf('?');
        StringBuilder out = new StringBuilder(query >= 0 ? text.substring(0, query) : text);
        if (query >= 0) {
            out.append(" params");
            for (String pair : text.substring(query + 1).split("&")) {
                int eq = pair.indexOf('=');
                out.append(' ').append(eq >= 0 ? pair.substring(0, eq) : pair);
            }
            for (String name : new String[] {"channel", "bundle", "surl", "url"}) {
                String value = schemaParameter(text, name);
                if (value != null) {
                    int cut = value.indexOf('?');
                    out.append(" | ").append(name).append('=').append(cut >= 0 ? value.substring(0, cut) : value);
                }
            }
        }
        return out.toString();
    }

    /**
     * For the debug log only: the members that say what sort of card a patch is (its origin and
     * token types, its popup type, whether it is commerce) and the key names of its extra info,
     * global props and raw data, three levels deep (a list shows its length and its first entry's
     * names). Codes are cut to identifier characters. Of the values, only the raw data's one-word
     * type codes are shown, since the rest carry what the card says and the search.
     */
    static String patchFields(Object patch) {
        if (patch == null) return "no patch";
        StringBuilder out = new StringBuilder("originType=").append(Reflect.readField(patch, "originType"));
        for (String name : new String[] {"tokenType", "popupType", "qrecVirtualEnable"}) {
            String code = identifier(Reflect.readField(patch, name));
            if (code != null) out.append(' ').append(name).append('=').append(code);
        }
        for (String name : new String[] {"isEcom", "needDynamicPatchVideos", "needGeneralSearchVideos"}) {
            Object flag = Reflect.readField(patch, name);
            if (flag instanceof Boolean) out.append(' ').append(name).append('=').append(flag);
        }
        Object videos = Reflect.readField(patch, "awemeList");
        if (videos instanceof java.util.Collection) out.append(" videos=").append(((java.util.Collection<?>) videos).size());
        if (Reflect.readField(patch, "nearByAuth") != null) out.append(" nearByAuth");
        for (String name : new String[] {"extraInfo", "globalProps"}) {
            Object map = Reflect.readField(patch, name);
            if (!(map instanceof java.util.Map) || ((java.util.Map<?, ?>) map).isEmpty()) continue;
            out.append(' ').append(name).append('{');
            appendNames(out, ((java.util.Map<?, ?>) map).keySet().iterator());
            out.append('}');
        }
        Object raw = Reflect.readField(patch, "rawData");
        if (raw instanceof String && !((String) raw).isEmpty()) {
            out.append(" rawData");
            try {
                appendJsonNames(out, new org.json.JSONObject((String) raw), 3);
            } catch (Exception notAnObject) {
                out.append(" not an object");
            }
        }
        return out.toString();
    }

    private static final int MAX_NAMES = 40;

    private static void appendNames(StringBuilder out, java.util.Iterator<?> names) {
        int count = 0;
        while (names.hasNext()) {
            String name = identifier(names.next());
            if (name == null) continue;
            if (count == MAX_NAMES) {
                out.append(" ...");
                return;
            }
            if (count++ > 0) out.append(' ');
            out.append(name);
        }
    }

    private static void appendJsonNames(StringBuilder out, org.json.JSONObject object, int depth) {
        out.append('{');
        java.util.Iterator<String> names = object.keys();
        int count = 0;
        while (names.hasNext()) {
            String key = names.next();
            String name = identifier(key);
            if (name == null) continue;
            if (count == MAX_NAMES) {
                out.append(" ...");
                break;
            }
            if (count++ > 0) out.append(' ');
            out.append(name);
            Object value = object.opt(key);
            if (CODE_KEYS.contains(key)) {
                String code = code(value);
                if (code != null) out.append('=').append(code);
            }
            if (depth > 1 && value instanceof org.json.JSONObject) {
                appendJsonNames(out, (org.json.JSONObject) value, depth - 1);
            } else if (value instanceof org.json.JSONArray) {
                org.json.JSONArray array = (org.json.JSONArray) value;
                out.append('[').append(array.length()).append(']');
                Object first = array.opt(0);
                if (depth > 1 && first instanceof org.json.JSONObject) {
                    appendJsonNames(out, (org.json.JSONObject) first, depth - 1);
                }
            }
        }
        out.append('}');
    }

    /**
     * Raw data keys whose value is a type code rather than text, so the debug line shows it: what
     * tells a hotel deals card from a place card is in these, not in the key names.
     */
    private static final java.util.Set<String> CODE_KEYS = new java.util.HashSet<>(java.util.Arrays.asList(
            "type", "doc_type", "card_type", "card_intent_type", "biz_type", "service_type",
            "local_service_key_category", "local_service_key_category_enum", "poi_type", "poi_backend_type"));

    /** A number, a flag or a one-word code as written; null for anything with spaces or other text. */
    private static String code(Object value) {
        if (value instanceof Number || value instanceof Boolean) return value.toString();
        if (!(value instanceof String)) return null;
        String text = (String) value;
        String cut = identifier(text);
        return cut != null && cut.equals(text) ? cut : null;
    }

    /** A name or type code cut to at most 40 identifier characters, or null when nothing is left. */
    private static String identifier(Object value) {
        if (value == null) return null;
        StringBuilder out = new StringBuilder();
        for (char c : value.toString().toCharArray()) {
            if (out.length() == 40) break;
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-') out.append(c);
        }
        return out.length() == 0 ? null : out.toString();
    }

    /** A schema parameter's decoded value, or null. */
    static String schemaParameter(String schema, String name) {
        int query = schema.indexOf('?');
        if (query < 0) return null;
        for (String pair : schema.substring(query + 1).split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0 || !pair.substring(0, eq).equals(name)) continue;
            try {
                return java.net.URLDecoder.decode(pair.substring(eq + 1), "UTF-8");
            } catch (Exception badEncoding) {
                return pair.substring(eq + 1);
            }
        }
        return null;
    }

    /** Collapses the row to no height and hides it. The size it had is kept the first time only. */
    private static void collapse(View row) {
        ViewGroup.LayoutParams params = row.getLayoutParams();
        synchronized (LOCK) {
            if (!COLLAPSED.containsKey(row)) {
                COLLAPSED.put(row, new int[] {
                        params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.height, row.getVisibility()});
            }
        }
        if (params != null) {
            params.height = 0;
            row.setLayoutParams(params);
        }
        row.setVisibility(View.GONE);
    }

    private static void restore(View row) {
        int[] saved;
        synchronized (LOCK) {
            saved = COLLAPSED.remove(row);
        }
        if (saved == null) return;
        ViewGroup.LayoutParams params = row.getLayoutParams();
        if (params != null) {
            params.height = saved[0];
            row.setLayoutParams(params);
        }
        row.setVisibility(saved[1]);
    }

    static void resetForTests() {
        synchronized (LOCK) {
            COLLAPSED.clear();
            BOUND.clear();
        }
        warned = false;
    }
}
