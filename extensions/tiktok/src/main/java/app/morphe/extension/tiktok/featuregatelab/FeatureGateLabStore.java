/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.featuregatelab;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Utils;

public final class FeatureGateLabStore {
    public static final String TARGET_VERSION = "47.1.4";
    public static final String MANAGER_ABMOCK = "abmock";
    public static final String MANAGER_PLAYER_CONFIG = "player_config";
    public static final String MANAGER_LIVE = "live";
    public static final String MANAGER_PIA_ACTIVITY_CENTER = "pia_activity_center";
    public static final String MANAGER_SETTINGS_MANAGER = "settings_manager";
    public static final String MANAGER_VE_CONFIG = "ve_config";

    private static final String PREFS_NAME = "morphe_feature_gate_lab";
    private static final String MASTER_KEY = "master_enabled";
    private static final String WARNING_ACK_KEY = "warning_acknowledged";
    private static final String RULE_IDS_KEY = "rule_ids";
    private static final String STORED_TARGET_VERSION_KEY = "stored_target_version";
    private static final String MIGRATION_NOTICE_KEY = "migration_notice_pending";

    private FeatureGateLabStore() {
    }

    public static boolean masterEnabled() {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(MASTER_KEY, false);
    }

    public static void setMasterEnabled(boolean enabled) {
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putBoolean(MASTER_KEY, enabled).apply();
        }
        FeatureGateLabRuntime.reloadRules();
        FeatureGateLabSession.markRestartNeeded();
    }

    public static boolean warningAcknowledged() {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(WARNING_ACK_KEY, false);
    }

    public static void acknowledgeWarning() {
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putBoolean(WARNING_ACK_KEY, true).apply();
        }
    }

    public static boolean consumeMigrationNotice() {
        SharedPreferences prefs = prefs();
        if (prefs == null || !prefs.getBoolean(MIGRATION_NOTICE_KEY, false)) {
            return false;
        }
        prefs.edit().putBoolean(MIGRATION_NOTICE_KEY, false).apply();
        return true;
    }

    public static Rule rule(String manager, String key, String type) {
        return loadRule(idFor(manager, key, type));
    }

    public static List<Rule> rules() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return Collections.emptyList();
        }
        List<Rule> result = new ArrayList<>();
        for (String id : ruleIds(prefs)) {
            Rule rule = loadRule(id);
            if (rule != null) {
                result.add(rule);
            }
        }
        result.sort((left, right) -> Long.compare(right.updatedAtMs, left.updatedAtMs));
        return result;
    }

    public static void saveRule(String manager, String key, String type, String value, boolean enabled) {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        String id = idFor(manager, key, type);
        List<String> ids = ruleIds(prefs);
        if (!ids.contains(id)) {
            ids.add(id);
        }
        String prefix = "rule." + id + ".";
        prefs.edit()
                .putString(RULE_IDS_KEY, join(ids))
                .putString(prefix + "manager", safe(manager))
                .putString(prefix + "key", safe(key))
                .putString(prefix + "type", normalizeType(type))
                .putString(prefix + "value", safe(value))
                .putBoolean(prefix + "enabled", enabled)
                .putLong(prefix + "updated", System.currentTimeMillis())
                .apply();
        FeatureGateLabRuntime.resetTriggered(id);
        FeatureGateLabRuntime.reloadRules();
        FeatureGateLabSession.markRestartNeeded();
    }

    public static void deleteRule(String manager, String key, String type) {
        deleteRuleById(idFor(manager, key, type));
    }

    public static void resetAllOverrides() {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs.edit();
        for (String id : ruleIds(prefs)) {
            removeRuleFields(editor, id);
        }
        editor.remove(RULE_IDS_KEY).apply();
        FeatureGateLabRuntime.clearTriggered();
        FeatureGateLabRuntime.reloadRules();
        FeatureGateLabSession.markRestartNeeded();
    }

    public static void resetAllLabData() {
        SettingsManagerObservationRecorder.clear();
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit()
                    .clear()
                    .putString(STORED_TARGET_VERSION_KEY, TARGET_VERSION)
                    .apply();
        }
        FeatureGateLabRuntime.clearTriggered();
        FeatureGateLabRuntime.reloadRules();
        FeatureGateLabSession.markRestartNeeded();
    }

    public static String exportProfile() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schema", 1);
        root.put("target", "TikTok global");
        root.put("tiktok_version", TARGET_VERSION);
        JSONArray rules = new JSONArray();
        for (Rule rule : rules()) {
            JSONObject item = new JSONObject();
            item.put("manager", rule.manager);
            item.put("key", rule.key);
            item.put("type", rule.type);
            item.put("value", rule.value);
            item.put("force", rule.enabled);
            rules.put(item);
        }
        root.put("rules", rules);
        return root.toString(2);
    }

    public static ImportReview reviewProfile(String text, Map<String, FeatureGateCatalog.Entry> catalog) throws JSONException {
        JSONObject root = new JSONObject(text);
        String version = root.optString("tiktok_version", "");
        if (!TARGET_VERSION.equals(version)) {
            throw new JSONException("Profile targets TikTok " + version + "; this Lab requires " + TARGET_VERSION);
        }

        JSONArray items = root.optJSONArray("rules");
        if (items == null) {
            throw new JSONException("Profile has no rules array");
        }
        List<Rule> accepted = new ArrayList<>();
        List<String> rejected = new ArrayList<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                rejected.add("Entry " + (i + 1) + ": invalid object");
                continue;
            }
            String manager = item.optString("manager", "");
            String key = item.optString("key", "");
            String type = normalizeType(item.optString("type", ""));
            String value = item.optString("value", "");
            FeatureGateCatalog.Entry entry = catalog.get(manager + "\n" + key);
            if (entry == null) {
                rejected.add(key + ": unknown key");
                continue;
            }
            if (!entry.userVisible()) {
                rejected.add(key + ": no supported override boundary in this Lab build");
                continue;
            }
            if (!normalizeType(entry.type).equals(type)) {
                rejected.add(key + ": type mismatch");
                continue;
            }
            String error = validateValue(type, value);
            if (error != null) {
                rejected.add(key + ": " + error);
                continue;
            }
            accepted.add(new Rule(idFor(manager, key, type), manager, key, type, value, false, System.currentTimeMillis()));
        }
        return new ImportReview(accepted, rejected);
    }

    public static void applyImport(ImportReview review) {
        if (review == null) {
            return;
        }
        for (Rule rule : review.accepted) {
            saveRule(rule.manager, rule.key, rule.type, rule.value, false);
        }
    }

    public static String validateValue(String type, String value) {
        String normalized = normalizeType(type);
        if (value == null) {
            return "value is missing";
        }
        try {
            switch (normalized) {
                case "BOOLEAN":
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        return "expected true or false";
                    }
                    return null;
                case "INT":
                    Integer.parseInt(value);
                    return null;
                case "LONG":
                    Long.parseLong(value);
                    return null;
                case "FLOAT": {
                    float parsed = Float.parseFloat(value);
                    return Float.isFinite(parsed) ? null : "value must be finite";
                }
                case "DOUBLE": {
                    double parsed = Double.parseDouble(value);
                    return Double.isFinite(parsed) ? null : "value must be finite";
                }
                case "STRING":
                    return value.length() <= 4096 ? null : "string exceeds 4096 characters";
                case "OBJECT": {
                    if (value.length() > 64 * 1024) {
                        return "structured value exceeds 64 KB";
                    }
                    JSONObject object = new JSONObject(value);
                    if (object.length() == 0) {
                        return "select at least one field";
                    }
                    return null;
                }
                default:
                    return "unsupported type";
            }
        } catch (NumberFormatException exception) {
            return "invalid " + normalized.toLowerCase() + " value";
        } catch (JSONException exception) {
            return "invalid structured value";
        }
    }

    public static boolean supportsOverride(String manager, String type) {
        if (MANAGER_SETTINGS_MANAGER.equals(manager)) {
            return "OBJECT".equals(normalizeType(type));
        }
        if (MANAGER_LIVE.equals(manager) && "OBJECT".equals(normalizeType(type))) {
            return true;
        }
        if (!MANAGER_ABMOCK.equals(manager)
                && !MANAGER_PLAYER_CONFIG.equals(manager)
                && !MANAGER_LIVE.equals(manager)
                && !MANAGER_PIA_ACTIVITY_CENTER.equals(manager)
                && !MANAGER_VE_CONFIG.equals(manager)) {
            return false;
        }
        switch (normalizeType(type)) {
            case "BOOLEAN":
            case "INT":
            case "LONG":
            case "FLOAT":
            case "DOUBLE":
            case "STRING":
                return true;
            default:
                return false;
        }
    }

    public static String idFor(String manager, String key, String type) {
        return sha256(safe(manager) + "\n" + safe(key) + "\n" + normalizeType(type));
    }

    public static String normalizeType(String type) {
        return safe(type).trim().toUpperCase();
    }

    private static void deleteRuleById(String id) {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return;
        }
        List<String> ids = ruleIds(prefs);
        ids.remove(id);
        SharedPreferences.Editor editor = prefs.edit().putString(RULE_IDS_KEY, join(ids));
        removeRuleFields(editor, id);
        editor.apply();
        FeatureGateLabRuntime.resetTriggered(id);
        FeatureGateLabRuntime.reloadRules();
        FeatureGateLabSession.markRestartNeeded();
    }

    private static void removeRuleFields(SharedPreferences.Editor editor, String id) {
        String prefix = "rule." + id + ".";
        editor.remove(prefix + "manager")
                .remove(prefix + "key")
                .remove(prefix + "type")
                .remove(prefix + "value")
                .remove(prefix + "enabled")
                .remove(prefix + "updated");
    }

    private static Rule loadRule(String id) {
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return null;
        }
        String prefix = "rule." + id + ".";
        String key = prefs.getString(prefix + "key", null);
        if (key == null) {
            return null;
        }
        return new Rule(
                id,
                prefs.getString(prefix + "manager", ""),
                key,
                normalizeType(prefs.getString(prefix + "type", "")),
                prefs.getString(prefix + "value", ""),
                prefs.getBoolean(prefix + "enabled", false),
                prefs.getLong(prefix + "updated", 0L)
        );
    }

    private static List<String> ruleIds(SharedPreferences prefs) {
        String raw = prefs.getString(RULE_IDS_KEY, "");
        List<String> result = new ArrayList<>();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String id : raw.split("\\n")) {
            if (!id.trim().isEmpty() && !result.contains(id.trim())) {
                result.add(id.trim());
            }
        }
        return result;
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) {
                result.append('\n');
            }
            result.append(value);
        }
        return result.toString();
    }

    private static SharedPreferences prefs() {
        Context context = Utils.getContext();
        if (context == null) {
            return null;
        }
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        ensureTargetVersion(prefs);
        return prefs;
    }

    private static synchronized void ensureTargetVersion(SharedPreferences prefs) {
        String storedVersion = prefs.getString(STORED_TARGET_VERSION_KEY, "");
        if (TARGET_VERSION.equals(storedVersion)) {
            return;
        }

        List<String> ids = ruleIds(prefs);
        boolean hadSavedState = prefs.getBoolean(MASTER_KEY, false) || !ids.isEmpty();
        SharedPreferences.Editor editor = prefs.edit()
                .putString(STORED_TARGET_VERSION_KEY, TARGET_VERSION)
                .putBoolean(MASTER_KEY, false);
        for (String id : ids) {
            editor.putBoolean("rule." + id + ".enabled", false);
        }
        if (hadSavedState) {
            editor.putBoolean(MIGRATION_NOTICE_KEY, true);
        }
        editor.apply();
    }

    static boolean runtimeStorageAvailable() {
        return Utils.getContext() != null;
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes("UTF-8"));
            StringBuilder result = new StringBuilder();
            for (byte item : digest) {
                result.append(String.format("%02x", item));
            }
            return result.toString();
        } catch (Exception ignored) {
            return Integer.toHexString(value.hashCode());
        }
    }

    public static final class Rule {
        public final String id;
        public final String manager;
        public final String key;
        public final String type;
        public final String value;
        public final boolean enabled;
        public final long updatedAtMs;

        Rule(String id, String manager, String key, String type, String value, boolean enabled, long updatedAtMs) {
            this.id = id;
            this.manager = manager;
            this.key = key;
            this.type = type;
            this.value = value;
            this.enabled = enabled;
            this.updatedAtMs = updatedAtMs;
        }
    }

    public static final class ImportReview {
        public final List<Rule> accepted;
        public final List<String> rejected;

        ImportReview(List<Rule> accepted, List<String> rejected) {
            this.accepted = Collections.unmodifiableList(accepted);
            this.rejected = Collections.unmodifiableList(rejected);
        }
    }
}
