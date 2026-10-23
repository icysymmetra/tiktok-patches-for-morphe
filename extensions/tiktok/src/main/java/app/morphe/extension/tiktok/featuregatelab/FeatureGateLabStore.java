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
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.SettingsJson;
import app.morphe.extension.tiktok.settings.L10n;

public final class FeatureGateLabStore {
    public static final String MANAGER_ABMOCK = "abmock";
    public static final String MANAGER_PLAYER_CONFIG = "player_config";
    public static final String MANAGER_LIVE = "live";
    public static final String MANAGER_PIA_ACTIVITY_CENTER = "pia_activity_center";
    public static final String MANAGER_SETTINGS_MANAGER = "settings_manager";
    public static final String MANAGER_VE_CONFIG = "ve_config";

    public static final String PREFS_NAME = "morphe_feature_gate_lab";
    private static final String MASTER_KEY = "master_enabled";
    private static final String WARNING_ACK_KEY = "warning_acknowledged";
    private static final String RULE_IDS_KEY = "rule_ids";
    private static final String STORED_TARGET_VERSION_KEY = "stored_target_version";
    private static final String MIGRATION_NOTICE_KEY = "migration_notice_pending";
    /** How many rules the pending notice is about. */
    private static final String MIGRATION_TURNED_OFF_KEY = "migration_turned_off";
    private static final String TRANSLATION_PRESET_TITLE = "Show See translation";

    private FeatureGateLabStore() {
    }

    /**
     * The TikTok build the Lab is for: the one installed, which BuildNames reads the same way the
     * view names do. Its rules, exports and backups name this build, and its catalog is the
     * one generated from it. The version is unreadable only before the extension has a context,
     * when nothing is stored or shown; the newest catalog build stands in for it then.
     *
     * <p>This was once a constant carried on to the next build when that one was declared: the
     * Lab called the new build by the older name, and every rule saved on one moved to the other
     * unchecked.
     */
    public static String targetVersion() {
        String running = app.morphe.extension.shared.BuildNames.runningBuild();
        return running != null ? running : FeatureGateCatalog.newestCatalogBuild();
    }

    /**
     * Whether the stored rules belong to the running build. The main process moves them to it
     * as soon as it opens the store; a secondary process doesn't write, so until then its rules
     * are another build's and it applies none of them.
     */
    static boolean storedForRunningBuild() {
        SharedPreferences prefs = prefs();
        return prefs != null && targetVersion().equals(prefs.getString(STORED_TARGET_VERSION_KEY, ""));
    }

    public static boolean masterEnabled() {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(MASTER_KEY, false);
    }

    public static void setMasterEnabled(boolean enabled) {
        if (!canWrite()) return;
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putBoolean(MASTER_KEY, enabled).apply();
        }
        FeatureGateLabRuntime.clearTriggered();
        FeatureGateLabRuntime.reloadRules();
        FeatureGateLabSession.markRestartNeeded();
    }

    public static boolean warningAcknowledged() {
        SharedPreferences prefs = prefs();
        return prefs != null && prefs.getBoolean(WARNING_ACK_KEY, false);
    }

    public static void acknowledgeWarning() {
        if (!canWrite()) return;
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit().putBoolean(WARNING_ACK_KEY, true).apply();
        }
    }

    /**
     * How many rules were turned off because their gates could not be carried to this build,
     * once, then zero until it happens again. A notice left by an older Hushfeed, which turned
     * off every rule on a change of build and kept no count, reads as all of them.
     */
    public static int consumeMigrationNotice() {
        SharedPreferences prefs = prefs();
        if (prefs == null || !prefs.getBoolean(MIGRATION_NOTICE_KEY, false)) {
            return 0;
        }
        if (!canWrite()) return 0;
        int turnedOff = prefs.getInt(MIGRATION_TURNED_OFF_KEY, -1);
        if (turnedOff < 0) turnedOff = ruleIds(prefs).size();
        prefs.edit().putBoolean(MIGRATION_NOTICE_KEY, false).remove(MIGRATION_TURNED_OFF_KEY).apply();
        return turnedOff;
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
        Collections.sort(result, (left, right) -> Long.compare(right.updatedAtMs, left.updatedAtMs));
        return result;
    }

    /**
     * Writes a rule, and answers whether it was written.
     *
     * <p>A structured value is checked here as well as in the screen that collects it. This is
     * the one way into the store that skipped the check, so a structured value saved through it
     * carried no depth bound of its own and reached the reflective apply on a gate thread
     * unchecked. Scalars are deliberately not checked: a malformed one is refused at the
     * boundary, where the test for that behaviour drives it.
     */
    public static boolean saveRule(String manager, String key, String type, String value, boolean enabled) {
        if (!canWrite()) return false;
        SharedPreferences prefs = prefs();
        if (prefs == null) {
            return false;
        }
        if ("OBJECT".equals(normalizeType(type))) {
            ValidationFailure rejected = validateValue(type, value);
            if (rejected != null) {
                Logger.printInfo(() -> "Refused a structured Lab rule for " + key
                        + ": " + rejected.code);
                return false;
            }
        }
        String id = idFor(manager, key, type);
        List<String> ids = ruleIds(prefs);
        if (!ids.contains(id)) {
            if (ids.size() >= MAX_RULES) {
                String message = ruleLimitMessage(ids.size() + 1);
                Logger.printInfo(() -> message);
                return false;
            }
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
        return true;
    }

    public static void deleteRule(String manager, String key, String type) {
        deleteRuleById(idFor(manager, key, type));
    }

    public static void resetAllLabData() {
        if (!canWrite()) return;
        SettingsManagerObservationRecorder.clear();
        SharedPreferences prefs = prefs();
        if (prefs != null) {
            prefs.edit()
                    .clear()
                    .putString(STORED_TARGET_VERSION_KEY, targetVersion())
                    .commit();
        }
        FeatureGateLabRuntime.clearTriggered();
        FeatureGateLabRuntime.reloadRules();
        FeatureGateLabSession.markRestartNeeded();
    }

    public static String exportProfile() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("schema", 1);
        root.put("target", "TikTok global");
        root.put("tiktok_version", targetVersion());
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

    public static JSONObject exportSettings() throws JSONException {
        return new JSONObject(exportProfile()).put("master", masterEnabled())
                .put("acknowledged", warningAcknowledged());
    }

    /** Compares durable Lab state while ignoring rule ordering and update timestamps. */
    public static boolean settingsMatch(JSONObject expected) {
        try {
            if (!(expected.get("master") instanceof Boolean)
                    || !(expected.get("acknowledged") instanceof Boolean)
                    || expected.getBoolean("master") != masterEnabled()
                    || expected.getBoolean("acknowledged") != warningAcknowledged()) {
                return false;
            }
            List<Rule> wanted = parseSettings(expected);
            Map<String, Rule> actual = new HashMap<>();
            for (Rule rule : rules()) actual.put(rule.id, rule);
            if (actual.size() != wanted.size()) return false;
            for (Rule rule : wanted) {
                Rule candidate = actual.get(rule.id);
                if (candidate == null || !sameDurableRule(rule, candidate)) return false;
            }
            return true;
        } catch (Exception error) {
            return false;
        }
    }

    private static boolean sameDurableRule(Rule left, Rule right) {
        return left.id.equals(right.id)
                && left.manager.equals(right.manager)
                && left.key.equals(right.key)
                && left.type.equals(right.type)
                && left.value.equals(right.value)
                && left.enabled == right.enabled;
    }

    /**
     * The most rules the Lab keeps. Its own import already stopped here; a settings backup, the
     * undo copy and the journal went through parseSettings with no count at all, so a hand-edited
     * backup of some 26,000 rules fitted the 2 MB file cap, restored, and then made every later
     * backup and every Lab undo copy too large to write, which refused Restore, Reset and every
     * Lab change after it.
     */
    public static final int MAX_RULES = 1024;

    /** Refuse an oversized replacement before a journal, undo file or preference editor changes. */
    static void requireRuleLimit(List<Rule> rules) throws java.io.IOException {
        if (rules == null) throw new java.io.IOException("Lab rules are missing");
        if (rules.size() > MAX_RULES) {
            throw new RuleLimitRefused(ruleLimitMessage(rules.size()));
        }
    }

    /** The rule cap, refused with the sentence the reader is shown, so it can be shown. */
    static final class RuleLimitRefused extends java.io.IOException {
        RuleLimitRefused(String sentence) {
            super(sentence);
        }
    }

    /** For a settings restore that rewrote the Lab: its own undo copy no longer applies. */
    public static void discardLabUndo() {
        FeatureGateLabUndo.discardAfterSettingsChange();
    }

    static String ruleLimitMessage(int resultingCount) {
        NumberFormat numbers = NumberFormat.getIntegerInstance(Locale.getDefault());
        return L10n.f("The Lab keeps at most %1$s rules. This change would make %2$s, so nothing was changed.",
                numbers.format(MAX_RULES), numbers.format(resultingCount));
    }

    /** The raw identity count is readable even when the current state is too large to parse. */
    static int storedRuleCount() {
        SharedPreferences prefs = prefs();
        return prefs == null ? 0 : ruleIds(prefs).size();
    }

    /**
     * Recovery for a state whose own rule count prevents the ordinary undo snapshot from parsing.
     * It clears only Feature Gate Lab preferences and observations.
     */
    static void clearAllLabDataWithoutParsing() throws java.io.IOException {
        if (!canWrite()) {
            throw new java.io.IOException("Feature Gate Lab is writable only from the main process");
        }
        SharedPreferences prefs = prefs();
        if (prefs == null) throw new java.io.IOException("Lab storage unavailable");
        boolean saved = prefs.edit()
                .clear()
                .putString(STORED_TARGET_VERSION_KEY, targetVersion())
                .commit();
        if (!saved) throw new java.io.IOException("Could not clear Lab settings");
        SettingsManagerObservationRecorder.clear();
        FeatureGateLabRuntime.clearTriggered();
        FeatureGateLabRuntime.reloadRules();
        FeatureGateLabSession.markRestartNeeded();
    }

    /**
     * Decode the entire backup before any setting or rule is changed.
     *
     * <p>Rules saved on another TikTok build come back as they are only where both builds'
     * catalogs carry the gate unchanged, or, from a build the catalog no longer has, where the
     * running build's catalog carries it (FeatureGateCatalog.compatibleRuleIds); the rest come
     * back turned off, marked so that storing them raises the Lab's notice. A backup, an undo copy or a journal entry can each be older
     * than the build now running, and none of them may put another build's rule back on.
     */
    public static List<Rule> parseSettings(JSONObject root) throws JSONException {
        if (!Integer.valueOf(1).equals(root.get("schema"))
                || !(root.opt("tiktok_version") instanceof String)
                || root.getString("tiktok_version").isEmpty()
                || !(root.get("master") instanceof Boolean)
                || !(root.get("acknowledged") instanceof Boolean)) {
            throw new JSONException("Invalid Lab backup or TikTok version");
        }
        JSONArray items = root.getJSONArray("rules");
        if (items.length() > MAX_RULES) {
            throw new JSONException("Lab backup holds " + items.length() + " rules, past the "
                    + MAX_RULES + " the Lab keeps");
        }
        List<Rule> rules = new ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.getJSONObject(i);
            for (String field : new String[]{"manager", "key", "type", "value"}) {
                if (!(item.get(field) instanceof String)) throw new JSONException("Invalid Lab " + field);
            }
            String manager = item.getString("manager"), key = item.getString("key");
            String type = normalizeType(item.getString("type")), value = item.getString("value");
            String id = idFor(manager, key, type);
            if (key.isEmpty() || !(item.get("force") instanceof Boolean) || !ids.add(id)
                    || validateValue(type, value) != null) throw new JSONException("Invalid Lab rule: " + key);
            rules.add(new Rule(id, manager, key, type, value, item.getBoolean("force"), System.currentTimeMillis()));
        }
        return forRunningBuild(root.getString("tiktok_version"), rules);
    }

    /**
     * [rules], saved on [build], as they may stand on the running build: each one
     * FeatureGateCatalog.compatibleRuleIds doesn't carry over is turned off and marked. The same
     * answer for the same input, so a journal's expected state and the store it is compared with
     * agree.
     */
    static List<Rule> forRunningBuild(String build, List<Rule> rules) {
        String running = targetVersion();
        if (running.equals(build) || rules.isEmpty()) return rules;
        java.util.Set<String> compatible = compatibleRuleIds(build, running, rules);
        List<Rule> result = new ArrayList<>(rules.size());
        for (Rule rule : rules) {
            result.add(rule.enabled && !compatible.contains(rule.id) ? rule.offForThisBuild() : rule);
        }
        return result;
    }

    /** The rules that carry from [from] to [to] as they are, or none when a catalog can't be read. */
    private static java.util.Set<String> compatibleRuleIds(String from, String to, List<Rule> rules) {
        try {
            return FeatureGateCatalog.compatibleRuleIds(from, to, rules);
        } catch (Exception error) {
            Logger.printException(() -> "Could not compare the Lab catalogs of TikTok " + from
                    + " and " + to + "; every rule is turned off", error);
            return java.util.Collections.emptySet();
        }
    }

    /**
     * Replaces the configuration. Captured observations are retained, and so is what the Lab
     * recorded about which overrides fired. Call on a worker thread.
     */
    public static void replaceSettings(List<Rule> rules, boolean master, boolean acknowledged)
            throws java.io.IOException {
        replaceSettings(rules, master, acknowledged, false);
    }

    /**
     * As above, where {@code puttingBack} says these rules are the ones that were loaded until a
     * moment ago.
     *
     * <p>What the Lab recorded about which overrides fired describes the rules that were loaded
     * when they fired, so a change to those rules throws the record away. Putting the previous
     * rules back is the case where that is wrong, and the store cannot tell on its own: a
     * SharedPreferences commit that reports failure has already updated the map it reports on,
     * so the rules it holds after a failed write are the new ones and a rollback looks like a
     * change. Only the caller knows which it is doing.
     */
    public static void replaceSettings(List<Rule> rules, boolean master, boolean acknowledged,
            boolean puttingBack) throws java.io.IOException {
        requireRuleLimit(rules);
        if (!canWrite()) {
            throw new java.io.IOException("Feature Gate Lab is writable only from the main process");
        }
        SharedPreferences prefs = prefs();
        if (prefs == null) throw new java.io.IOException("Lab storage unavailable");
        SharedPreferences.Editor editor = prefs.edit();
        for (String id : ruleIds(prefs)) removeRuleFields(editor, id);
        List<String> ids = new ArrayList<>();
        int turnedOff = 0;
        for (Rule rule : rules) {
            ids.add(rule.id);
            if (rule.turnedOffForBuild) turnedOff++;
            String prefix = "rule." + rule.id + ".";
            editor.putString(prefix + "manager", rule.manager).putString(prefix + "key", rule.key)
                    .putString(prefix + "type", rule.type).putString(prefix + "value", rule.value)
                    .putBoolean(prefix + "enabled", rule.enabled).putLong(prefix + "updated", rule.updatedAtMs);
        }
        // Rules another build saved that this one could not carry say so when the Lab opens,
        // as they do when TikTok itself changes build under them.
        if (turnedOff > 0) editor.putInt(MIGRATION_TURNED_OFF_KEY, turnedOff);
        else editor.remove(MIGRATION_TURNED_OFF_KEY);
        boolean saved = editor.putString(RULE_IDS_KEY, join(ids)).putBoolean(MASTER_KEY, master)
                .putBoolean(WARNING_ACK_KEY, acknowledged).putBoolean(MIGRATION_NOTICE_KEY, turnedOff > 0)
                .putString(STORED_TARGET_VERSION_KEY, targetVersion()).commit();
        if (!saved) throw new java.io.IOException("Could not save Lab settings");
        if (!puttingBack) FeatureGateLabRuntime.clearTriggered();
        FeatureGateLabRuntime.reloadRules();
        FeatureGateLabSession.markRestartNeeded();
    }

    public static ImportReview reviewProfile(String text, Map<String, FeatureGateCatalog.Entry> catalog) throws JSONException {
        JSONObject root = new JSONObject(text);
        String version = root.optString("tiktok_version", "");
        if (!targetVersion().equals(version)) {
            throw new JSONException("Profile targets TikTok " + version + "; this Lab requires " + targetVersion());
        }

        JSONArray items = root.optJSONArray("rules");
        if (items == null) {
            throw new JSONException("Profile has no rules array");
        }
        List<Rule> accepted = new ArrayList<>();
        List<ImportRejection> rejected = new ArrayList<>();
        java.util.Set<String> acceptedIds = new java.util.HashSet<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                rejected.add(ImportRejection.at(
                        ImportRejectionCode.INVALID_OBJECT, i + 1));
                continue;
            }
            if (!(item.opt("manager") instanceof String)
                    || !(item.opt("key") instanceof String)
                    || !(item.opt("type") instanceof String)
                    || !(item.opt("value") instanceof String)) {
                rejected.add(ImportRejection.at(
                        ImportRejectionCode.INVALID_FIELD_TYPE, i + 1));
                continue;
            }
            String manager = item.optString("manager", "");
            String key = item.optString("key", "");
            String importedType = item.optString("type", "");
            String type = normalizeType(importedType);
            String value = item.optString("value", "");
            FeatureGateCatalog.Entry entry = catalog.get(manager + "\n" + key);
            if (entry == null) {
                rejected.add(ImportRejection.forKey(
                        ImportRejectionCode.UNKNOWN_KEY, i + 1, key));
                continue;
            }
            if (!entry.userVisible()) {
                rejected.add(ImportRejection.forKey(
                        ImportRejectionCode.UNSUPPORTED_BOUNDARY, i + 1, key));
                continue;
            }
            if (!normalizeType(entry.type).equals(type)) {
                rejected.add(ImportRejection.typeMismatch(
                        i + 1, key, entry.type, importedType));
                continue;
            }
            ValidationFailure error = validateValue(type, value);
            if (error != null) {
                rejected.add(ImportRejection.invalidValue(i + 1, key, error));
                continue;
            }
            String id = idFor(manager, key, type);
            if (!acceptedIds.add(id)) {
                rejected.add(ImportRejection.forKey(
                        ImportRejectionCode.DUPLICATE_RULE, i + 1, key));
                continue;
            }
            accepted.add(new Rule(id, manager, key, type, value, false, System.currentTimeMillis()));
        }
        return new ImportReview(accepted, rejected);
    }

    /**
     * One reviewed preset: AB rules written as key:TYPE:value, and the builds whose catalogs they
     * were checked against. Most turn on what other TikTok mods force on by flag; the values are
     * the ones those mods ship. FeatureGatePresetsTest reads every listed build's shipped catalog
     * and fails on a key the build doesn't register, a type it registers differently or a value
     * the Lab would refuse. A preset may pin the in-code default, since the server overrides it.
     * A rule is an AB (abmock) key unless it starts with {@link #LIVE_RULE}, which names one of
     * TikTok's LIVE settings, read through its own settings manager.
     */
    static final class Preset {
        final String id;
        final String title;
        final String[] builds;
        final String[] rules;
        /** What the preset needs besides its gates, shown in its preview. Null for most. */
        String note;

        Preset(String id, String title, String[] builds, String... rules) {
            this.id = id;
            this.title = title;
            this.builds = builds;
            this.rules = rules;
        }

        Preset note(String text) {
            note = text;
            return this;
        }

        JSONObject toJson() throws JSONException {
            JSONArray array = new JSONArray();
            for (String rule : rules) {
                String manager = MANAGER_ABMOCK;
                if (rule.startsWith(LIVE_RULE)) {
                    manager = MANAGER_LIVE;
                    rule = rule.substring(LIVE_RULE.length());
                }
                String[] parts = rule.split(":", 3);
                array.put(new JSONObject().put("manager", manager).put("key", parts[0])
                        .put("type", parts[1]).put("value", parts[2]));
            }
            JSONObject json = new JSONObject().put("title", title).put("rules", array);
            return note == null ? json : json.put("note", note);
        }
    }

    /** Marks a preset rule as a LIVE setting rather than an AB key. */
    static final String LIVE_RULE = "live/";

    private static final String[] CATALOG_BUILDS = GeneratedGateCatalogBuilds.BUILDS;

    static final Preset[] PRESETS = {
            new Preset("see_translation", TRANSLATION_PRESET_TITLE, CATALOG_BUILDS,
                    "feed_translation_reverse:INT:0", "cla_translate_button_weaken_v2:INT:0"),
            new Preset("comment_reposts", "Repost with a comment", CATALOG_BUILDS,
                    "tt_comment_repost_interaction:INT:1", "repost_support_reply:INT:1",
                    "repost_support_digg:INT:1"),
            // Edit profile's Add background on its own (#80), without the layout below.
            new Preset("profile_banner", "Profile banner", CATALOG_BUILDS,
                    "profile_bg_in_allow_list:INT:1", "profile_bg_enable_consumption_group:INT:3"),
            new Preset("profile_layout", "Profile banner and new profile layout", CATALOG_BUILDS,
                    "profile_bg_in_allow_list:INT:1", "profile_bg_enable_consumption_group:INT:3",
                    "tux_profile_icon_update:INT:1", "profile_left_align:INT:1",
                    "profile_left_align_ab_group:INT:11", "profile_left_align_avatar_and_info_merge:INT:1",
                    "profile_left_align_pronouns:INT:1", "profile_left_align_recommend_card:INT:1",
                    "profile_left_align_avatar_opt:BOOLEAN:true",
                    "profile_left_align_avatar_at_right_opt:BOOLEAN:true",
                    "profile_left_align_right_avatar_nickname_opt_v2:BOOLEAN:true",
                    "enable_profile_left_align_second_line_two_lines_show:BOOLEAN:true",
                    "profile_left_align_relation_info_font_size_opt:BOOLEAN:true",
                    "profile_left_align_right_avatar_large_font_opt:BOOLEAN:true",
                    "profile_left_align_bio_style_opt:BOOLEAN:true",
                    "profile_left_align_cta_style_opt:BOOLEAN:true",
                    "profile_left_align_advance_feature_style_opt:BOOLEAN:true",
                    "profile_left_align_right_avatar_nickname_wordbreak_opt:BOOLEAN:true"),
            new Preset("comment_media", "Comment with live photos, the camera or audio", CATALOG_BUILDS,
                    "comment_enable_live_photo:INT:1", "comment_by_shooting:INT:1",
                    "audio_comment_publish:INT:1"),
            // Japan's several photos per comment (#98). Group 2 is the only one the comment
            // keyboard and the publish path read as on; 0, 1 and anything else keep one photo.
            new Preset("comment_photos", "Several photos in one comment", CATALOG_BUILDS,
                    "comment_multi_photo_group:INT:2"),
            // The shared INT reader serves both the comment UI and native emoji catalog fetch.
            // Group 2 enables send and view while retaining availability and V0 fallback checks.
            new Preset("comment_emoji", "New comment emoji", CATALOG_BUILDS,
                    "tt_cmt_new_emoji:INT:2"),
            new Preset("comment_favorites", "Save comments to Favorites", CATALOG_BUILDS,
                    "add_comments_to_favorites:INT:3", "enable_favorite_long_click:INT:2"),
            new Preset("comment_dislike", "Comment sort and dislike styles", CATALOG_BUILDS,
                    "comment_sort_opt_style:INT:1", "comment_hate_opt:INT:1",
                    "tt_comment_hate_animation_opt:INT:2"),
            new Preset("messages", "Message bubble colors, Inbox archive and sharing to more chats", CATALOG_BUILDS,
                    "dm_customize_message_bubble:INT:1", "inbox_bb_archive_enable:INT:1",
                    "im_contacts_multi_select_limit:INT:999"),
            new Preset("manage_topics", "Manage topics", CATALOG_BUILDS,
                    "manage_topics_enable:BOOLEAN:true"),
            new Preset("visual_search", "Visual search", CATALOG_BUILDS,
                    "show_visual_search_entrance:BOOLEAN:true"),
            new Preset("ai_self", "AI Self", CATALOG_BUILDS,
                    "ai_self_enable:BOOLEAN:true", "ai_self_effects_panel_enabled:BOOLEAN:true",
                    "ai_self_free_create_enable:BOOLEAN:true"),
            new Preset("long_press_menu", "Long-press menu on every post", CATALOG_BUILDS,
                    "feed_long_press_panel_support_all_type:BOOLEAN:true"),
            new Preset("hold_to_speed_up", "TikTok's hold to speed up", CATALOG_BUILDS,
                    "long_press_speed_up_enable:BOOLEAN:true", "long_press_speed_up_lock:INT:120"),
            new Preset("background_play", "TikTok's background play and Auto scroll", CATALOG_BUILDS,
                    "background_play_enable:INT:1", "fyp_auto_scroll:INT:1"),
            new Preset("feed_timestamps", "Post dates in the feed", CATALOG_BUILDS,
                    "feed_title_timestamp_trial:STRING:v3"),
            // Korea's one-line comments that scroll across a video (#98). Group 1 or 2 turns the
            // feature on once TikTok's own strategy switch allows it, and the landscape flag
            // only counts after that. Each video still carries its own server flag for them.
            new Preset("scrolling_comments", "Scrolling comments on videos", CATALOG_BUILDS,
                    "danmaku_exp:INT:1", "landscape_support_danmaku:INT:1"),
            // Japan's LIVE chat auto-translate (#98). The audience setting is an INT in LIVE's own
            // settings, 0 by default. Anything above 0 counts as on for the audience, and above 1
            // also runs the buffered automatic translation, so 2 is the one that translates by
            // itself. The anchor's own setting is left alone: it only matters in a room you host.
            // The Translate comments button also wants a Japanese SIM: BynchPOP saw it only with
            // SIM spoof on, Country Code JP and the NTT DOCOMO preset, after a restart or two (#98).
            new Preset("live_translate", "LIVE chat auto-translate", CATALOG_BUILDS,
                    LIVE_RULE + "live_auto_translate_model_audience:INT:2")
                    .note("The Translate comments button in a LIVE room also needs a Japanese SIM. Turn on Override SIM details in Metra settings > Region spoof, set SIM country preset to Japan, NTT DOCOMO (44010), then restart TikTok."),
            // Disable the client check; changing its separate maximum to 0 would reject all tags.
            new Preset("hashtag_limit", "More hashtags per post", CATALOG_BUILDS,
                    "studio_publish_limit_hashtag_switch:BOOLEAN:false"),
    };

    /**
     * Bundled JSON, since the injected extension carries code but no Android resources. Build,
     * then preset id, in {@link #PRESETS} order.
     */
    static JSONObject reviewedPresets() throws JSONException {
        JSONObject byBuild = new JSONObject();
        for (Preset preset : PRESETS) {
            for (String build : preset.builds) {
                JSONObject version = byBuild.optJSONObject(build);
                if (version == null) {
                    version = new JSONObject();
                    byBuild.put(build, version);
                }
                version.put(preset.id, preset.toJson());
            }
        }
        return byBuild;
    }

    static ImportReview reviewPreset(String build, String id,
            Map<String, FeatureGateCatalog.Entry> catalog) throws JSONException {
        // Unknown host versions must not inherit targetVersion's early-startup fallback.
        if (!build.equals(app.morphe.extension.shared.BuildNames.runningBuild())) {
            throw new JSONException("Preset does not match the installed TikTok version");
        }
        JSONObject profile = reviewedPresets().getJSONObject(build).getJSONObject(id);
        profile.put("tiktok_version", build);
        ImportReview review = reviewProfile(profile.toString(), catalog);
        if (!review.rejected.isEmpty() || review.accepted.isEmpty()) {
            throw new JSONException("Preset gates do not match the installed catalog");
        }
        return review;
    }

    public static ValidationFailure validateValue(String type, String value) {
        String normalized = normalizeType(type);
        if (value == null) {
            return ValidationFailure.of(ValidationCode.VALUE_MISSING, normalized);
        }
        try {
            switch (normalized) {
                case "BOOLEAN":
                    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
                        return ValidationFailure.of(
                                ValidationCode.EXPECTED_TRUE_OR_FALSE, normalized);
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
                    return Float.isFinite(parsed) ? null : ValidationFailure.of(
                            ValidationCode.VALUE_MUST_BE_FINITE, normalized);
                }
                case "DOUBLE": {
                    double parsed = Double.parseDouble(value);
                    return Double.isFinite(parsed) ? null : ValidationFailure.of(
                            ValidationCode.VALUE_MUST_BE_FINITE, normalized);
                }
                case "STRING":
                    return value.length() <= MAX_STRING_CHARS ? null : ValidationFailure.of(
                            ValidationCode.STRING_TOO_LONG, normalized);
                case "OBJECT": {
                    if (value.length() > MAX_STRUCTURED_CHARS) {
                        return ValidationFailure.of(
                                ValidationCode.STRUCTURED_VALUE_TOO_LARGE, normalized);
                    }
                    // Through the bounded reader, not the platform one. This string arrives
                    // inside a backup file, and the document around it being depth-checked says
                    // nothing about the string's own contents: the platform parser recurses per
                    // level, and a deeply nested value here raised StackOverflowError, which is
                    // an Error and walked past every catch on the restore path.
                    JSONObject object = SettingsJson.parseObject(value, STRUCTURED_VALUE_LIMITS);
                    if (object.length() == 0) {
                        return ValidationFailure.of(
                                ValidationCode.SELECT_AT_LEAST_ONE_FIELD, normalized);
                    }
                    return null;
                }
                default:
                    return ValidationFailure.of(ValidationCode.UNSUPPORTED_TYPE, normalized);
            }
        } catch (NumberFormatException exception) {
            return ValidationFailure.of(ValidationCode.INVALID_NUMBER, normalized);
        } catch (JSONException | java.io.IOException exception) {
            return ValidationFailure.of(ValidationCode.INVALID_STRUCTURED_VALUE, normalized);
        }
    }

    /**
     * What a stored structured value may be. The depth is the one that matters: it is the only
     * bound between a backup file and a stack overflow, and 32 is far past anything a real gate
     * config uses while staying nowhere near the worker thread's limit. The others are a second
     * line rather than a restatement of the 64 KB check above: that one counts characters, while
     * maxBytes counts UTF-8 and maxNodes counts the whole document.
     */
    private static final SettingsJson.Limits STRUCTURED_VALUE_LIMITS =
            new SettingsJson.Limits(32, 16384, 64 * 1024, 4096, 64 * 1024);

    /**
     * How long a string value and a structured value may be, in characters. The two refusals
     * the Lab shows format these in, so a change here changes the words.
     */
    public static final int MAX_STRING_CHARS = 4096;
    public static final int MAX_STRUCTURED_CHARS = 64 * 1024;

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
        // ROOT, like every other fold in this package: the result is an identity that goes into
        // idFor and into supportsOverride, and a Turkish phone folds a lowercase `int` to
        // "İNT" under the default locale, so an imported rule stops matching itself.
        return safe(type).trim().toUpperCase(Locale.ROOT);
    }

    private static void deleteRuleById(String id) {
        if (!canWrite()) return;
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
        return prefs == null ? null : loadRule(prefs, id);
    }

    private static Rule loadRule(SharedPreferences prefs, String id) {
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

    /**
     * Moves the stored rules to the running build the first time the store is opened on it.
     *
     * <p>A rule stays as it was only where the catalogs of the build it was saved on and of this
     * one carry its gate identically: the same manager, key and type, the same default and
     * provenance, and for a SettingsManager read the same model class and default. The bundle
     * declares only the newest build, so the one a user moves from is usually no longer in the
     * catalog: then a rule stays where this build's catalog carries its gate under the same
     * manager, key and type, unless it is a SettingsManager read through a model class R8
     * renamed. Every other enabled rule is turned off and counted for the Lab's notice,
     * including all of them when this build has no catalog. The master switch stays as it was,
     * so what did carry over keeps working. Until 2026-09-27 this turned everything off on any
     * change of build, and the Lab called two declared builds by one name.
     */
    private static synchronized void ensureTargetVersion(SharedPreferences prefs) {
        String storedVersion = prefs.getString(STORED_TARGET_VERSION_KEY, "");
        String running = targetVersion();
        if (running.equals(storedVersion) || !Utils.isMainProcess()) {
            return;
        }

        List<Rule> stored = new ArrayList<>();
        for (String id : ruleIds(prefs)) {
            Rule rule = loadRule(prefs, id);
            if (rule != null) stored.add(rule);
        }
        List<Rule> carried = forRunningBuild(storedVersion, stored);
        SharedPreferences.Editor editor = prefs.edit().putString(STORED_TARGET_VERSION_KEY, running);
        int turnedOff = 0;
        StringBuilder names = new StringBuilder();
        for (Rule rule : carried) {
            if (!rule.turnedOffForBuild) continue;
            editor.putBoolean("rule." + rule.id + ".enabled", false);
            turnedOff++;
            if (names.length() < 400) names.append(names.length() == 0 ? "" : ", ").append(rule.key);
        }
        if (turnedOff > 0) {
            int pending = prefs.getBoolean(MIGRATION_NOTICE_KEY, false)
                    ? Math.max(0, prefs.getInt(MIGRATION_TURNED_OFF_KEY, 0)) : 0;
            editor.putBoolean(MIGRATION_NOTICE_KEY, true).putInt(MIGRATION_TURNED_OFF_KEY, pending + turnedOff);
        }
        editor.apply();
        int off = turnedOff;
        Logger.printInfo(() -> "Feature Gate Lab moved from TikTok " + (storedVersion.isEmpty() ? "(none)" : storedVersion)
                + " to " + running + ": " + (stored.size() - off) + " rules as they were, " + off + " turned off"
                + (off == 0 ? "" : ": " + names));
    }

    static boolean runtimeStorageAvailable() {
        return Utils.getContext() != null;
    }

    private static boolean canWrite() {
        if (Utils.isMainProcess()) return true;
        Logger.printInfo(() -> "Ignored Feature Gate Lab write from a secondary process");
        return false;
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

    public enum ValidationCode {
        VALUE_MISSING,
        EXPECTED_TRUE_OR_FALSE,
        INVALID_NUMBER,
        VALUE_MUST_BE_FINITE,
        STRING_TOO_LONG,
        STRUCTURED_VALUE_TOO_LARGE,
        SELECT_AT_LEAST_ONE_FIELD,
        UNSUPPORTED_TYPE,
        INVALID_STRUCTURED_VALUE,
        INVALID_JSON,
        EXPECTED_JSON_OBJECT_OR_ARRAY
    }

    /** A model-layer refusal with no wording tied to the reader's language. */
    public static final class ValidationFailure {
        public final ValidationCode code;
        public final String technicalType;

        private ValidationFailure(ValidationCode code, String technicalType) {
            this.code = code;
            this.technicalType = technicalType == null ? "" : technicalType;
        }

        static ValidationFailure of(ValidationCode code, String technicalType) {
            return new ValidationFailure(code, technicalType);
        }
    }

    public enum ImportRejectionCode {
        INVALID_OBJECT,
        INVALID_FIELD_TYPE,
        UNKNOWN_KEY,
        UNSUPPORTED_BOUNDARY,
        TYPE_MISMATCH,
        INVALID_VALUE,
        DUPLICATE_RULE
    }

    /** One rejected import row, retaining exact technical values but no display sentence. */
    public static final class ImportRejection {
        public final ImportRejectionCode code;
        public final int entryNumber;
        public final String key;
        public final String expectedType;
        public final String actualType;
        public final ValidationFailure validation;

        private ImportRejection(
                ImportRejectionCode code,
                int entryNumber,
                String key,
                String expectedType,
                String actualType,
                ValidationFailure validation
        ) {
            this.code = code;
            this.entryNumber = entryNumber;
            this.key = key == null ? "" : key;
            this.expectedType = expectedType == null ? "" : expectedType;
            this.actualType = actualType == null ? "" : actualType;
            this.validation = validation;
        }

        static ImportRejection at(ImportRejectionCode code, int entryNumber) {
            return new ImportRejection(code, entryNumber, "", "", "", null);
        }

        static ImportRejection forKey(
                ImportRejectionCode code,
                int entryNumber,
                String key
        ) {
            return new ImportRejection(code, entryNumber, key, "", "", null);
        }

        static ImportRejection typeMismatch(
                int entryNumber,
                String key,
                String expectedType,
                String actualType
        ) {
            return new ImportRejection(
                    ImportRejectionCode.TYPE_MISMATCH,
                    entryNumber,
                    key,
                    expectedType,
                    actualType,
                    null
            );
        }

        static ImportRejection invalidValue(
                int entryNumber,
                String key,
                ValidationFailure validation
        ) {
            return new ImportRejection(
                    ImportRejectionCode.INVALID_VALUE,
                    entryNumber,
                    key,
                    "",
                    "",
                    validation
            );
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
        /**
         * Turned off on the way from another TikTok build because its gate did not carry over
         * unchanged. Not stored: whoever writes such a rule raises the Lab's notice for it.
         */
        final boolean turnedOffForBuild;

        Rule(String id, String manager, String key, String type, String value, boolean enabled, long updatedAtMs) {
            this(id, manager, key, type, value, enabled, updatedAtMs, false);
        }

        private Rule(String id, String manager, String key, String type, String value, boolean enabled,
                long updatedAtMs, boolean turnedOffForBuild) {
            this.id = id;
            this.manager = manager;
            this.key = key;
            this.type = type;
            this.value = value;
            this.enabled = enabled;
            this.updatedAtMs = updatedAtMs;
            this.turnedOffForBuild = turnedOffForBuild;
        }

        Rule offForThisBuild() {
            return new Rule(id, manager, key, type, value, false, updatedAtMs, true);
        }
    }

    public static final class ImportReview {
        public final List<Rule> accepted;
        public final List<ImportRejection> rejected;

        ImportReview(List<Rule> accepted, List<ImportRejection> rejected) {
            this.accepted = Collections.unmodifiableList(accepted);
            this.rejected = Collections.unmodifiableList(rejected);
        }
    }
}
