/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feedfilter;

import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.util.Locale;

/**
 * Drops videos that use a blocked sound.
 *
 * {@code Aweme.getMusic()} is unobfuscated on TikTok 46.2.3 and returns the
 * {@code com.ss.android.ugc.aweme.music.model.Music} model. A sound is matched by its id,
 * which the player's block-sound button records, or by a phrase in its name, since the
 * same audio is re-uploaded under many ids and "original sound" variants.
 */
public class SoundFilter implements IFilter {
    @Override
    public boolean getEnabled() {
        if (!Settings.HIDE_BLOCKED_SOUNDS.get()) {
            return false;
        }
        return !isBlank(Settings.BLOCKED_SOUND_IDS.get()) || !isBlank(Settings.BLOCKED_SOUND_NAMES.get());
    }

    @Override
    public boolean getFiltered(Aweme item) {
        Object music = Reflect.property(item, "getMusic", "music");
        if (music == null) {
            return false;
        }

        String id = SoundIdentity.idOf(music);
        if (id != null && SoundIdentity.containsEntry(Settings.BLOCKED_SOUND_IDS.get(), id)) {
            return true;
        }

        String name = SoundIdentity.nameOf(music);
        if (name == null) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        for (String phrase : SoundIdentity.entries(Settings.BLOCKED_SOUND_NAMES.get())) {
            if (lower.contains(phrase.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
