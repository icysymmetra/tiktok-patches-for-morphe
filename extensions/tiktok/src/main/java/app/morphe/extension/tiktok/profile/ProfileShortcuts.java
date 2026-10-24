/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import androidx.annotation.Nullable;

import com.ss.android.ugc.profile.platform.base.data.ProfileComponents;
import com.ss.android.ugc.profile.platform.base.data.ProfileUser;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Hides the shortcuts you pick from the row under a profile's bio (#49): TikTok Studio, Your
 * orders, Shop, LIVE events and the rest.
 *
 * <p>The profile header is laid out by TikTok's server. The row is a component named
 * {@code advanced_feature}, and each shortcut is a child component named
 * {@code advanced_feature_<kind>} whose data carries a feature id and the title on screen.
 * Generic ones are {@code advanced_feature_base_item} and are told apart by id and title.
 * TikTok turns the response into its profile model before the header is built, and that is
 * where the chosen children are taken out of the row.
 */
public final class ProfileShortcuts {
    static final String ROW = "advanced_feature";
    static final String PREFIX = "advanced_feature_";
    static final String GENERIC_KIND = "base_item";
    private static final int MAX_DEPTH = 8;

    private static volatile Class<?> bizDataOwner;
    private static volatile Field bizDataField;

    private ProfileShortcuts() {
    }

    /** One shortcut in the row, as TikTok sent it. */
    static final class Shortcut {
        final String kind;
        @Nullable final Integer featureId;
        final String title;

        Shortcut(String kind, @Nullable Integer featureId, String title) {
            this.kind = kind;
            this.featureId = featureId;
            this.title = title;
        }

        /** What the checklist saves: the kind, or for a generic shortcut its feature id. */
        String key() {
            if (!kind.isEmpty() && !kind.equals(GENERIC_KIND)) return kind;
            if (featureId != null) return "feature " + featureId;
            return canonical(title);
        }

        /** What the checklist shows: the title on screen, or the key when there is none. */
        String label() {
            return title.isEmpty() ? key() : title;
        }

        boolean matches(Set<String> hidden) {
            if (hidden.isEmpty()) return false;
            return hidden.contains(canonical(key()))
                    || (!title.isEmpty() && hidden.contains(canonical(title)));
        }

        @Override
        public String toString() {
            return kind + " #" + featureId + " \"" + title + "\"";
        }
    }

    /** From TikTok's conversion of a profile response, before the header is built. */
    public static void onProfileData(ProfileUser user) {
        try {
            if (user == null || user.headerComponents == null) return;
            filter(user.headerComponents, hiddenKeys(), 0);
        } catch (Throwable error) {
            Logger.printException(() -> "Profile shortcuts: could not read the profile header", error);
        }
    }

    static void filter(ProfileComponents node, Set<String> hidden, int depth) {
        if (node == null || depth > MAX_DEPTH) return;
        List<?> children = node.components;
        if (children == null || children.isEmpty()) return;
        if (ROW.equals(node.componentName)) {
            filterRow(node, children, hidden);
            return;
        }
        for (Object child : children) {
            if (child instanceof ProfileComponents) filter((ProfileComponents) child, hidden, depth + 1);
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void filterRow(ProfileComponents row, List<?> children, Set<String> hidden) {
        List<Shortcut> seen = new ArrayList<>();
        List kept = new ArrayList<>(children.size());
        for (Object child : children) {
            if (!(child instanceof ProfileComponents)) {
                kept.add(child);
                continue;
            }
            Shortcut shortcut = read((ProfileComponents) child);
            seen.add(shortcut);
            boolean hide = shortcut.matches(hidden);
            Logger.printDebug(() -> "Profile shortcuts: " + shortcut + (hide ? " hidden" : " kept"));
            if (!hide) kept.add(child);
        }
        ProfileShortcutCatalog.observe(seen);
        if (kept.size() == children.size()) return;
        try {
            // In place first, so anything already holding the row's list sees the change.
            children.retainAll(kept);
        } catch (UnsupportedOperationException fixed) {
            row.components = kept;
        }
    }

    static Shortcut read(ProfileComponents component) {
        String name = component.componentName == null ? "" : component.componentName;
        String kind = name.startsWith(PREFIX) ? name.substring(PREFIX.length()) : name;
        Integer featureId = null;
        String title = "";
        String json = bizDataJson(component);
        if (json != null) {
            try {
                JSONObject data = new JSONObject(json);
                if (data.has("feature_id")) featureId = data.optInt("feature_id");
                JSONObject describe = data.optJSONObject("describe");
                if (describe != null) {
                    title = describe.optString("text", "").trim();
                    if (title.isEmpty()) title = describe.optString("starling_key", "").trim();
                }
            } catch (Exception malformed) {
                // A shortcut with unreadable data is still named by its kind.
            }
        }
        return new Shortcut(kind, featureId, title);
    }

    /** TikTok's bizData is a shrunk gson JsonObject; its toString is the JSON text. */
    @Nullable
    private static String bizDataJson(ProfileComponents component) {
        try {
            Class<?> owner = component.getClass();
            Field field = bizDataField;
            if (field == null || bizDataOwner != owner) {
                field = owner.getField("bizData");
                bizDataField = field;
                bizDataOwner = owner;
            }
            Object value = field.get(component);
            return value == null ? null : String.valueOf(value);
        } catch (ReflectiveOperationException | RuntimeException missing) {
            return null;
        }
    }

    /** Typed names and checklist picks hide alike. */
    static Set<String> hiddenKeys() {
        Set<String> keys = new HashSet<>();
        addTokens(keys, Settings.HIDDEN_PROFILE_SHORTCUTS.get());
        addTokens(keys, Settings.PROFILE_SHORTCUT_PICKS.get());
        return keys;
    }

    /** What the reader has saved to hide, paused or not, so the catalog never drops one of them. */
    static Set<String> savedHiddenKeys() {
        Set<String> keys = new HashSet<>();
        addTokens(keys, Settings.HIDDEN_PROFILE_SHORTCUTS.savedValue());
        addTokens(keys, Settings.PROFILE_SHORTCUT_PICKS.savedValue());
        return keys;
    }

    private static void addTokens(Set<String> keys, String stored) {
        if (stored == null) return;
        for (String token : stored.split("[,\\n]")) {
            String key = canonical(token);
            if (!key.isEmpty()) keys.add(key);
        }
    }

    public static String canonical(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT).replace('_', ' ').replaceAll("\\s+", " ");
    }
}
