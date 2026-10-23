/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.settings.Setting;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** Display arguments only. No native account, relation data or network model is changed. */
@SuppressWarnings("unused")
public final class LocalProfileCounts {
    private static final int MAX_BYTES = 65_536, MAX_ACCOUNTS = 128;
    private static volatile Parsed cached;
    private static volatile String rejectedText;

    public enum Metric {
        FOLLOWERS("relation_info_follower", "followers", "Followers", Integer.MAX_VALUE),
        FOLLOWING("relation_info_following", "following", "Following", Integer.MAX_VALUE),
        LIKES("relation_info_like", "likes", "Likes", Long.MAX_VALUE);

        public final String component, key, title;
        public final long maximum;

        Metric(String component, String key, String title, long maximum) {
            this.component = component;
            this.key = key;
            this.title = title;
            this.maximum = maximum;
        }

        @Nullable static Metric from(String component) {
            for (Metric metric : values()) if (metric.component.equals(component)) return metric;
            return null;
        }
    }

    public enum SaveResult { SAVED, ACCOUNT_CHANGED, INVALID_VALUE, FAILED }

    public static boolean enabled() {
        return SettingsStatus.followStatusEnabled && Settings.LOCAL_PROFILE_COUNTS.get()
                && validUid(SignedInUser.id());
    }

    private static final class Parsed {
        final String text;
        final Map<String, Map<Metric, Number>> accounts;

        Parsed(String text, Map<String, Map<Metric, Number>> accounts) {
            this.text = text;
            this.accounts = accounts;
        }
    }

    /** Asked before native formatting, using the UID of the profile being bound. */
    @Nullable public static Number override(String viewedUid, String component) {
        if (!SettingsStatus.followStatusEnabled || !Settings.LOCAL_PROFILE_COUNTS.get()) return null;
        String own = SignedInUser.id();
        Metric metric = Metric.from(component);
        if (!validUid(own) || !own.equals(viewedUid) || metric == null) return null;
        try {
            Map<Metric, Number> account = parsed().accounts.get(own);
            Number value = account == null ? null : account.get(metric);
            return own.equals(SignedInUser.id()) && Settings.LOCAL_PROFILE_COUNTS.get() ? value : null;
        } catch (JSONException malformed) {
            return null;
        }
    }

    /** Revalidate after formatting so a changed account, Pause or edit keeps the native text. */
    public static CharSequence choose(CharSequence nativeText, CharSequence localText, Number expected,
            String viewedUid, String component) {
        return expected != null && expected.equals(override(viewedUid, component)) && localText != null
                ? localText : nativeText;
    }

    /** An empty editor field means native. A saved zero is an explicit zero. */
    public static String value(String uid, Metric metric) {
        try {
            Map<Metric, Number> account = parsed().accounts.get(uid);
            Number value = account == null ? null : account.get(metric);
            return value == null ? "" : value.toString();
        } catch (JSONException malformed) {
            return "";
        }
    }

    public static boolean validValue(Metric metric, String text) {
        try {
            parseValue(metric, text);
            return true;
        } catch (IllegalArgumentException invalid) {
            return false;
        }
    }

    /** Commit all three choices together on a worker. Other signed-in accounts retain theirs. */
    public static SaveResult save(String owner, Map<Metric, String> fields) {
        if (!validUid(owner) || !owner.equals(SignedInUser.id())) return SaveResult.ACCOUNT_CHANGED;
        if (fields == null) return SaveResult.INVALID_VALUE;
        EnumMap<Metric, Number> values = new EnumMap<>(Metric.class);
        try {
            for (Metric metric : Metric.values()) {
                Number value = parseValue(metric, fields.get(metric));
                if (value != null) values.put(metric, value);
            }
        } catch (IllegalArgumentException invalid) {
            return SaveResult.INVALID_VALUE;
        }
        synchronized (Setting.class) {
            try {
                Map<String, Map<Metric, Number>> accounts = new LinkedHashMap<>(parsed().accounts);
                if (values.isEmpty()) accounts.remove(owner);
                else accounts.put(owner, values);
                JSONObject encoded = new JSONObject();
                for (Map.Entry<String, Map<Metric, Number>> entry : accounts.entrySet()) {
                    JSONObject counts = new JSONObject();
                    for (Map.Entry<Metric, Number> count : entry.getValue().entrySet()) {
                        counts.put(count.getKey().key, count.getValue());
                    }
                    encoded.put(entry.getKey(), counts);
                }
                String text = accounts.isEmpty() ? ""
                        : new JSONObject().put("schema", 1).put("accounts", encoded).toString();
                decode(text);
                if (!owner.equals(SignedInUser.id())) return SaveResult.ACCOUNT_CHANGED;
                return Settings.LOCAL_PROFILE_COUNT_VALUES.save(text) ? SaveResult.SAVED : SaveResult.FAILED;
            } catch (JSONException malformed) {
                Logger.printException(() -> "Local profile counts: could not save choices", malformed);
                return SaveResult.FAILED;
            }
        }
    }

    private static Parsed parsed() throws JSONException {
        String text = Settings.LOCAL_PROFILE_COUNT_VALUES.savedValue();
        Parsed found = cached;
        if (found != null && found.text.equals(text)) return found;
        try {
            found = decode(text);
            rejectedText = null;
        } catch (JSONException malformed) {
            if (!text.equals(rejectedText)) {
                rejectedText = text;
                Logger.printException(() -> "Local profile counts: could not read choices", malformed);
            }
            throw malformed;
        }
        cached = found;
        return found;
    }

    private static Parsed decode(String text) throws JSONException {
        if (text.isEmpty()) return new Parsed(text, Collections.emptyMap());
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new JSONException("Count choices exceed limit");
        JSONObject root = new JSONObject(text);
        if (root.length() != 2 || !(root.get("schema") instanceof Integer) || root.getInt("schema") != 1) {
            throw new JSONException("Unknown count choices schema");
        }
        JSONObject source = root.getJSONObject("accounts");
        if (source.length() > MAX_ACCOUNTS) throw new JSONException("Too many count accounts");
        Map<String, Map<Metric, Number>> accounts = new LinkedHashMap<>();
        for (Iterator<String> ids = source.keys(); ids.hasNext();) {
            String uid = ids.next();
            if (!validUid(uid)) throw new JSONException("Invalid count account");
            JSONObject entry = source.getJSONObject(uid);
            EnumMap<Metric, Number> values = new EnumMap<>(Metric.class);
            for (Iterator<String> keys = entry.keys(); keys.hasNext();) {
                String key = keys.next();
                Metric metric = null;
                for (Metric candidate : Metric.values()) if (candidate.key.equals(key)) metric = candidate;
                Object raw = entry.get(key);
                if (metric == null || !(raw instanceof Integer || raw instanceof Long)) {
                    throw new JSONException("Invalid count value");
                }
                try {
                    values.put(metric, parseValue(metric, raw.toString()));
                } catch (IllegalArgumentException invalid) {
                    throw new JSONException("Count outside range");
                }
            }
            accounts.put(uid, Collections.unmodifiableMap(values));
        }
        return new Parsed(text, Collections.unmodifiableMap(accounts));
    }

    @Nullable private static Number parseValue(Metric metric, @Nullable String text) {
        if (text == null) throw new IllegalArgumentException("Missing count field");
        text = text.trim();
        if (text.isEmpty()) return null;
        if (text.length() > 19 || !text.matches("[0-9]+")) throw new IllegalArgumentException("Invalid count");
        long value;
        try { value = Long.parseLong(text); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid count", invalid); }
        if (value > metric.maximum) throw new IllegalArgumentException("Count outside range");
        if (metric == Metric.LIKES) return Long.valueOf(value);
        return Integer.valueOf((int) value);
    }

    private static boolean validUid(@Nullable String uid) {
        if (uid == null || uid.isEmpty() || uid.equals("0") || uid.length() > 128) return false;
        for (int index = 0; index < uid.length(); index++) {
            if (Character.isWhitespace(uid.charAt(index)) || Character.isISOControl(uid.charAt(index))) return false;
        }
        return true;
    }

    private LocalProfileCounts() {}
}
