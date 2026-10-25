/*
 * Copyright 2026 Metra contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.settings;

import android.content.Context;
import android.content.SharedPreferences;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.Setting;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** One atomic, repeatable migration of Metra's stored choices into the expanded feature set. */
public final class MetraLegacySettings {
    private static final String DONE = "metra_legacy_settings_v1";
    public static final String HIDDEN_CHANNELS = "metra_share_hidden_channels";
    public static final String HIDDEN_ACTIONS = "metra_share_hidden_actions";
    // Metra v0.8.1's seeded option IDs. Old clipboard exports omitted the observed lists.
    // Only these known options can be recovered from such an allow-list; new IDs stay allowed.
    private static final String KNOWN_CHANNELS = "repost,copy,ai_remix,live_repost_note,chat_merge,qr_code,sms,email,facebook,facebook_lite,facebook_group,facebook_story,messenger,messenger_lite,instagram,instagram_story,instagram_dm,whatsapp,whatsapp_business,whatsapp_status,telegram,twitter,snapchat,snapchat_chats,reddit,imo,pinterest,imgur,line,viber,kakaotalk,kakao_story,google_messages,youtube,zalo,band,lemon8,vk,more,upscrolled";
    private static final String KNOWN_ACTIONS = "report,dislike,why_this_video,duet,stitch,save_photo,save,gif,captions,live_photo,im_create_group,create_sticker,playback_speed,promote_for_others_fyp,share_to_story,show_in_chat";
    public static Set<String> legacyKeys() {
        return new LinkedHashSet<>(java.util.Arrays.asList(
                "comment_sort_force_show", "comment_translation_excluded_languages", "hide_ai_content",
                "hide_alternate_for_you_batches", "hide_captcha_popups", "hide_feed_live_button",
                "hide_suggested_accounts", "share_sheet_send_to", "share_sheet_channels", "share_sheet_actions",
                "share_sheet_channels_enabled", "share_sheet_channels_observed",
                "share_sheet_actions_enabled", "share_sheet_actions_observed"));
    }
    private MetraLegacySettings() {}

    public static void migrate(Context context) {
        if (context == null || !Utils.isMainProcess()) return;
        // Share the normal settings-write lock, including the snapshot and live-value reload.
        // Otherwise a setting saved after getAll() can be overwritten by an older alias.
        synchronized (Setting.class) {
          try {
            SharedPreferences prefs = context.getSharedPreferences(Setting.PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (prefs.getBoolean(DONE, false)) return;
            Map<String, ?> original = prefs.getAll();
            Map<String, Object> updates = migratedValues(original);
            SharedPreferences.Editor editor = prefs.edit();
            for (Map.Entry<String, Object> item : updates.entrySet()) {
                put(editor, item.getKey(), item.getValue());
            }
            try {
                if (!editor.putBoolean(DONE, true).commit()) {
                    throw new IllegalStateException("Could not commit Metra settings migration");
                }
            } catch (RuntimeException failure) {
                // commit() can change memory before reporting a disk failure. Restore only this
                // transaction's keys so an in-memory DONE cannot suppress the next attempt.
                try {
                    SharedPreferences.Editor rollback = prefs.edit();
                    for (String key : updates.keySet()) put(rollback, key, original.get(key));
                    put(rollback, DONE, original.get(DONE));
                    if (!rollback.commit()) throw new IllegalStateException("Could not roll back Metra settings migration");
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
                throw failure;
            }
            Setting.reloadAfterMigration(updates.keySet());
          } catch (RuntimeException error) {
            // A malformed legacy value cannot prevent TikTok from opening; no partial migration is saved.
            Logger.printException(() -> "Could not migrate previous Metra settings", error);
          }
        }
    }

    private static void put(SharedPreferences.Editor editor, String key, Object value) {
        if (value == null) editor.remove(key);
        else if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
        else editor.putString(key, (String) value);
    }

    /** New explicit choices always win. Legacy keys are retained for a data-preserving rollback. */
    public static Map<String, Object> migratedValues(Map<String, ?> original) {
        Map<String, Object> updates = new LinkedHashMap<>();
        copy(original, updates, "comment_sort_force_show", "comment_sort_controls", Boolean.class, false);
        copy(original, updates, "comment_translation_excluded_languages", "dont_auto_translate_languages", String.class, false);
        copy(original, updates, "hide_ai_content", "hide_ai_generated", Boolean.class, false);
        copy(original, updates, "hide_alternate_for_you_batches", "hide_unpersonalized_for_you", Boolean.class, false);
        copy(original, updates, "hide_captcha_popups", "hide_browsing_captcha_popups", Boolean.class, false);
        copy(original, updates, "hide_feed_live_button", "hide_live_entrance", Boolean.class, false);
        copy(original, updates, "hide_suggested_accounts", "hide_inbox_suggested_accounts", Boolean.class, false);
        copy(original, updates, "share_sheet_send_to", "hide_share_contacts", Boolean.class, true);
        copy(original, updates, "share_sheet_channels", "hide_share_channels", Boolean.class, true);
        copy(original, updates, "share_sheet_actions", "hide_share_actions", Boolean.class, true);
        Object quality = original.get("download_video_quality");
        if (quality instanceof String) {
            String mode = ((String) quality).trim().toLowerCase(Locale.ROOT);
            if ("high".equals(mode)) updates.put("download_video_quality", "highest");
            else if ("low".equals(mode)) updates.put("download_video_quality", "lowest");
            else if ("medium".equals(mode) && !mode.equals(quality)) updates.put("download_video_quality", mode);
            // medium retains its original median-choice meaning.
        }
        hidden(original, updates, "share_sheet_channels", HIDDEN_CHANNELS);
        hidden(original, updates, "share_sheet_actions", HIDDEN_ACTIONS);
        return updates;
    }

    private static void copy(Map<String, ?> original, Map<String, Object> updates,
            String oldKey, String newKey, Class<?> type, boolean invert) {
        if (!original.containsKey(oldKey) || original.containsKey(newKey)) return;
        Object value = original.get(oldKey);
        if (!type.isInstance(value)) throw new IllegalArgumentException("Invalid legacy setting: " + oldKey);
        updates.put(newKey, invert ? !((Boolean) value) : value);
    }

    /** Old Metra enabled newly observed options automatically. Only known disabled options move. */
    private static void hidden(Map<String, ?> original, Map<String, Object> updates, String prefix, String destination) {
        if (original.containsKey(destination)) return;
        Object observed = original.get(prefix + "_observed");
        // An empty old allow-list was the default and therefore had no stored key.
        Object enabled = original.containsKey(prefix + "_enabled") ? original.get(prefix + "_enabled") : "";
        if (observed == null) {
            if (!original.containsKey(prefix + "_enabled")) return;
            observed = "share_sheet_channels".equals(prefix) ? KNOWN_CHANNELS : KNOWN_ACTIONS;
        }
        if (!(observed instanceof String) || !(enabled instanceof String)) {
            throw new IllegalArgumentException("Invalid legacy share list: " + prefix);
        }
        Set<String> disabled = keys((String) observed);
        disabled.removeAll(keys((String) enabled));
        updates.put(destination, android.text.TextUtils.join(",", disabled));
    }

    private static Set<String> keys(String source) {
        Set<String> result = new LinkedHashSet<>();
        for (String part : source.split(",")) {
            String key = part.trim().toLowerCase(Locale.ROOT);
            if (!key.isEmpty()) result.add(key);
        }
        return result;
    }
}
