/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.adskip;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Utils;

import java.security.SecureRandom;

/**
 * The ad skip's one preferences file: the shared base's sync state and sync token, the reader's
 * votes and the lifetime skip counters. It is on TikTok's launch-crash keep-list, see
 * LaunchCrashCleanup.
 */
public final class AdSkipPrefs {
    public static final String NAME = "metra_adskip";
    private static final String KEY_TOKEN = "sync_token";

    private AdSkipPrefs() {}

    @Nullable
    static SharedPreferences get() {
        Context context = Utils.getContext();
        return context == null ? null : context.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    /** 32 random bytes, made once and kept on the phone. It is never logged. */
    @Nullable
    static synchronized String token() {
        SharedPreferences prefs = get();
        if (prefs == null) return null;
        String token = prefs.getString(KEY_TOKEN, null);
        if (token == null) {
            byte[] bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            token = Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            prefs.edit().putString(KEY_TOKEN, token).apply();
        }
        return token;
    }
}
