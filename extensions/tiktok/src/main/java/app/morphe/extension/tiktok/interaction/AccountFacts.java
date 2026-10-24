/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.interaction;

import android.app.Activity;
import android.app.AlertDialog;

import androidx.annotation.Nullable;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.L10n;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;

/**
 * Account facts: what TikTok's own account model already holds about a profile, shown from a
 * button on the profile's share sheet beside the copy buttons. Nothing is fetched. The fields
 * are filled from the profile response (create_time, region, account_region, language,
 * unique_id_modify_time, nick_name_modify_ts, secret and has_open_favorite, the last being what
 * the profile's own tab pager reads to show the liked videos tab) and read by their real names.
 * A date or name the server didn't send says so rather than guessing. secret and
 * has_open_favorite can't say that, so they only get a line when they're true.
 */
public final class AccountFacts {
    static final String SHOW_FACTS = "Account facts";
    static final String FACTS_COPIED = "Account facts copied";
    static final String NOT_SENT = "Not sent";
    /** A seconds value past this is really milliseconds; it's the year 5138 in seconds. */
    private static final long LARGEST_SECONDS = 100_000_000_000L;

    private AccountFacts() {
    }

    static boolean enabled() {
        return SettingsStatus.copyIdsEnabled && Settings.ACCOUNT_FACTS.get();
    }

    /** One line of the sheet: its label, which is a key, and its value in the phone's words. */
    static final class Fact {
        final String label;
        final String value;

        Fact(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    /** The sheet's lines for {@code user}, in the order they show. */
    static List<Fact> of(Object user, Locale locale, TimeZone zone) {
        List<Fact> facts = new ArrayList<>();
        // 47.4.3 renamed the boxed join time to _createTime.
        Long joined = seconds(Reflect.readField(user, "_createTime"));
        if (joined == null) joined = seconds(Reflect.readField(user, "createTime"));
        if (joined == null) joined = seconds(Reflect.readField(user, "registerTime"));
        facts.add(new Fact("Joined", date(joined, locale, zone)));
        String region = text(Reflect.readField(user, "region"));
        facts.add(new Fact("Region", country(region, locale)));
        String accountRegion = text(Reflect.readField(user, "accountRegion"));
        if (accountRegion != null && !accountRegion.equalsIgnoreCase(region)) {
            facts.add(new Fact("Account region", country(accountRegion, locale)));
        }
        facts.add(new Fact("Language", language(text(Reflect.readField(user, "language")), locale)));
        facts.add(new Fact("Username last changed",
                date(seconds(Reflect.readField(user, "uniqueIdModifyTime")), locale, zone)));
        facts.add(new Fact("Display name last changed",
                date(seconds(Reflect.readField(user, "nickNameModifyTs")), locale, zone)));
        // Both are plain booleans on the model, so one the server left out reads false. Only a
        // true one is a fact, and false leaves its line out. 47.4.3 renamed secret to isSecret,
        // and PineDrama 47.1.4 still has secret.
        Object secret = Reflect.readField(user, "isSecret");
        if (secret == null) secret = Reflect.readField(user, "secret");
        if (Boolean.TRUE.equals(secret)) {
            facts.add(new Fact("Private account", L10n.t("Yes")));
        }
        if (Boolean.TRUE.equals(Reflect.readField(user, "hasOpenFavorite"))) {
            facts.add(new Fact("Liked videos", L10n.t("Public")));
        }
        return facts;
    }

    /** The sheet as text, one "label: value" line per fact, which is also what Copy copies. */
    static String format(List<Fact> facts) {
        StringBuilder text = new StringBuilder();
        for (Fact fact : facts) {
            if (text.length() > 0) text.append('\n');
            text.append(L10n.t(fact.label)).append(": ").append(fact.value);
        }
        return text.toString();
    }

    /** From the facts button: the sheet over whatever screen is showing. */
    static void show(Object user) {
        try {
            Activity activity = Utils.getVisibleActivity();
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
            String text = format(of(user, Locale.getDefault(), TimeZone.getDefault()));
            String handle = Reflect.string(user, "getUniqueId", "uniqueId");
            SettingsUi.syncDarkMode(activity);
            AlertDialog dialog = new AlertDialog.Builder(activity)
                    .setTitle(handle == null ? L10n.t(SHOW_FACTS) : "@" + handle)
                    .setMessage(text)
                    .setPositiveButton(L10n.t("Close"), null)
                    .setNeutralButton(L10n.t("Copy"), (ignored, which) -> {
                        if (GestureActions.copyToClipboard(L10n.t("TikTok account facts"), text)) {
                            Utils.showToastShort(L10n.t(FACTS_COPIED));
                        }
                    })
                    .create();
            dialog.setOnShowListener(ignored -> SettingsUi.styleStandardAlertDialog(dialog));
            dialog.show();
        } catch (Throwable error) {
            Logger.printException(() -> "Could not show the account facts", error);
        }
    }

    /** A Unix time in seconds, or null for nothing or zero. A milliseconds value is turned into seconds. */
    @Nullable
    static Long seconds(@Nullable Object value) {
        if (!(value instanceof Number)) return null;
        long raw = ((Number) value).longValue();
        if (raw <= 0) return null;
        return raw > LARGEST_SECONDS ? raw / 1000L : raw;
    }

    static String date(@Nullable Long seconds, Locale locale, TimeZone zone) {
        if (seconds == null) return L10n.t(NOT_SENT);
        DateFormat format = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT, locale);
        format.setTimeZone(zone);
        return format.format(new Date(seconds * 1000L));
    }

    /** A region code with its country's name in the phone's language: "Germany (DE)". */
    static String country(@Nullable String code, Locale locale) {
        if (code == null) return L10n.t(NOT_SENT);
        String upper = code.toUpperCase(Locale.ROOT);
        String name = new Locale("", upper).getDisplayCountry(locale);
        // A code with no country behind it (ZZ, or a user-assigned one) gets the same generic
        // "Unknown Region" the platform gives ZZ, in the phone's language. That says nothing
        // about the account, so the code is shown as it came.
        String generic = new Locale("", "ZZ").getDisplayCountry(locale);
        return name.isEmpty() || name.equalsIgnoreCase(upper) || name.equals(generic)
                ? upper : name + " (" + upper + ")";
    }

    /** A language tag with its name in the phone's language: "German (de)". */
    static String language(@Nullable String tag, Locale locale) {
        if (tag == null) return L10n.t(NOT_SENT);
        String name = Locale.forLanguageTag(tag.replace('_', '-')).getDisplayName(locale);
        return name.isEmpty() || name.equalsIgnoreCase(tag) ? tag : name + " (" + tag + ")";
    }

    @Nullable
    private static String text(@Nullable Object value) {
        if (value == null) return null;
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }
}
