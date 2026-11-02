/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.featuregatelab;

import android.content.Context;

import app.morphe.extension.tiktok.misc.BackgroundPlay;
import app.morphe.extension.tiktok.settings.L10n;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONObject;

/** Turns typed Lab outcomes into complete sentences at the UI boundary. */
final class FeatureGateLabText {
    enum ValueSource {
        CURRENT,
        DEFAULT,
        RESEARCHED,
        HISTORICAL,
        SELECTED
    }

    private FeatureGateLabText() {
    }

    /**
     * Why an override was refused, and what the reader can do about it.
     *
     * <p>The status line could only say that it had not been applied. The reason was in logcat,
     * where nobody holding a phone is going to find it, and there was no next step.
     */
    static String structuredFailure(Context context, FeatureGateFailure failure) {
        if (failure == null) return "";
        switch (failure.reason) {
            case NO_OBJECT:
                return L10n.t(context, "TikTok hasn't loaded this setting yet. Open the part of "
                        + "the app that uses it, then come back.");
            case CANNOT_COPY:
                return L10n.t(context, "This setting's value can't be copied on this build, so"
                        + " it can't be overridden. Reset the override.");
            case NO_LIST_VALUE:
                return L10n.t(context, "The override doesn't say what list to return. Edit the"
                        + " field values, or reset the override.");
            case UNSUPPORTED_FIELD:
                return L10n.f(context, "Field %1$s can't be changed on this build. Take it out"
                        + " of the override, or reset the override.", failure.detail);
            case NO_FIELDS:
                return L10n.t(context, "The override changes no fields. Edit the field values,"
                        + " or reset the override.");
            case NOT_IN_CATALOGUE:
                return L10n.t(context, "This key isn't in Metra's list, so its type can't"
                        + " be checked. Reset the override.");
            case TYPE_MISMATCH:
                return L10n.f(context, "The catalog says this key is %1$s and this override is"
                                + " %2$s. Reset the override and make a new one.",
                        failure.detail, failure.other);
            case THREW:
            default:
                return L10n.f(context, "The override couldn't be applied: %1$s. Edit the field"
                        + " values, or reset the override.", failure.detail);
        }
    }

    static String validation(
            Context context,
            FeatureGateLabStore.ValidationFailure failure
    ) {
        if (failure == null) return "";
        switch (failure.code) {
            case VALUE_MISSING:
                return L10n.t(context, "This value is required.");
            case EXPECTED_TRUE_OR_FALSE:
                return L10n.t(context, "Enter true or false.");
            case INVALID_NUMBER:
                return "INT".equals(failure.technicalType)
                        ? L10n.t(context, "Enter a whole number.")
                        : L10n.t(context, "Enter a number.");
            case VALUE_MUST_BE_FINITE:
                return L10n.t(context, "Enter a finite number.");
            case STRING_TOO_LONG:
                return L10n.f(context,
                        "Keep this string to %1$s characters or fewer.",
                        java.text.NumberFormat.getInstance().format(FeatureGateLabStore.MAX_STRING_CHARS));
            case STRUCTURED_VALUE_TOO_LARGE:
                return L10n.f(context,
                        "Keep this structured value to %1$s KB or less.",
                        java.text.NumberFormat.getInstance().format(FeatureGateLabStore.MAX_STRUCTURED_CHARS / 1024));
            case SELECT_AT_LEAST_ONE_FIELD:
                return L10n.t(context, "Select at least one field.");
            case UNSUPPORTED_TYPE:
                return L10n.t(context, "This gate type isn't supported.");
            case INVALID_JSON:
                return L10n.t(context, "That isn't valid JSON. Check the brackets and quotes.");
            case EXPECTED_JSON_OBJECT_OR_ARRAY:
                return L10n.t(context, "Enter a JSON object or array.");
            case INVALID_STRUCTURED_VALUE:
            default:
                return L10n.t(context, "Enter a valid structured value.");
        }
    }

    static String fieldValidation(
            Context context,
            String fieldName,
            FeatureGateLabStore.ValidationFailure failure
    ) {
        return L10n.f(context, "%1$s needs fixing. %2$s",
                fieldName, validation(context, failure));
    }

    static String importRejection(
            Context context,
            FeatureGateLabStore.ImportRejection rejection
    ) {
        switch (rejection.code) {
            case INVALID_OBJECT:
                return L10n.f(context, "Entry %1$d isn't an object.",
                        rejection.entryNumber);
            case INVALID_FIELD_TYPE:
                return L10n.f(context,
                        "Entry %1$d has a field with the wrong data type.",
                        rejection.entryNumber);
            case UNKNOWN_KEY:
                return L10n.f(context, "%1$s isn't in this Lab catalog.", rejection.key);
            case UNSUPPORTED_BOUNDARY:
                return L10n.f(context,
                        "%1$s is a kind of value the Lab can't override.",
                        rejection.key);
            case TYPE_MISMATCH:
                return L10n.f(context,
                        "%1$s has type %2$s, but the file says %3$s.",
                        rejection.key, rejection.expectedType, rejection.actualType);
            case INVALID_VALUE:
                return L10n.f(context, "%1$s was rejected. %2$s", rejection.key,
                        validation(context, rejection.validation));
            case DUPLICATE_RULE:
            default:
                return L10n.f(context,
                        "%1$s appears more than once in this file.", rejection.key);
        }
    }

    static String sourceLabel(Context context, FeatureGateCatalog.Entry entry) {
        String name = sourceName(context, entry.manager);
        if (entry.registered && entry.loaded) {
            return L10n.f(context,
                    "%1$s · declared by TikTok, with a current value", name);
        }
        if (entry.registered) {
            return L10n.f(context, "%1$s · declared by TikTok", name);
        }
        if (entry.loaded) {
            return L10n.f(context, "%1$s · current value only", name);
        }
        return L10n.f(context, "%1$s · Metra's list only", name);
    }

    private static String sourceName(Context context, String manager) {
        if (FeatureGateLabStore.MANAGER_PIA_ACTIVITY_CENTER.equals(manager)) {
            return L10n.t(context, "Activity center (PIA)");
        }
        if (FeatureGateLabStore.MANAGER_PLAYER_CONFIG.equals(manager)) {
            return L10n.t(context, "Video player settings");
        }
        if (FeatureGateLabStore.MANAGER_LIVE.equals(manager)) {
            return L10n.t(context, "LIVE settings");
        }
        if (FeatureGateLabStore.MANAGER_VE_CONFIG.equals(manager)) {
            return L10n.t(context, "Media config (VE)");
        }
        if (FeatureGateLabStore.MANAGER_SETTINGS_MANAGER.equals(manager)) {
            return L10n.t(context, "Config (Settings Manager)");
        }
        if (FeatureGateLabStore.MANAGER_ABMOCK.equals(manager)) {
            return L10n.t(context, "App AB");
        }
        return manager;
    }

    static String effectiveValue(Context context, FeatureGateCatalog.Entry entry) {
        if (entry == null) return L10n.t(context, "Unavailable");
        // Hushfeed's switch replaces the value after TikTok reads it, so it wins over an override.
        if (BackgroundPlay.decidesGate(entry.key)) {
            return L10n.t(context, "2, from Keep playing in the background");
        }
        FeatureGateLabStore.Rule rule = FeatureGateLabStore.rule(
                entry.manager, entry.key, entry.type);
        if (FeatureGateLabStore.masterEnabled() && rule != null && rule.enabled) {
            boolean triggered = FeatureGateLabRuntime.isTriggered(
                    entry.manager, entry.key, entry.type);
            if ("OBJECT".equals(entry.type)) {
                int fields = structuredFieldCount(rule.value);
                if (fields == 1) {
                    return L10n.t(context, triggered
                            ? "1 field overridden"
                            : "1 field will be overridden when requested");
                }
                return L10n.f(context, triggered
                                ? "%1$d fields overridden"
                                : "%1$d fields will be overridden when requested",
                        fields);
            }
            return L10n.f(context, triggered
                            ? "%1$s (override returned)"
                            : "%1$s (will be returned when requested)",
                    rule.value);
        }
        return entry.loaded
                ? L10n.f(context, "%1$s (TikTok value)", entry.currentValue)
                : L10n.t(context, "No current value and no active override");
    }

    static String optionLabel(
            Context context,
            String value,
            List<ValueSource> sources
    ) {
        List<String> labels = new ArrayList<>(sources.size());
        for (ValueSource source : sources) labels.add(valueSource(context, source));
        return L10n.f(context, "%1$s (%2$s)", value, join(labels));
    }

    static String customOptionLabel(Context context, String value) {
        return L10n.f(context, "%1$s (custom, unverified)", value);
    }

    static String rawValues(Context context, List<String> values) {
        return values == null || values.isEmpty()
                ? L10n.t(context, "None recorded")
                : join(values);
    }

    private static String valueSource(Context context, ValueSource source) {
        switch (source) {
            case CURRENT:
                return L10n.t(context, "Current");
            case DEFAULT:
                return L10n.t(context, "Default");
            case RESEARCHED:
                return L10n.t(context, "Researched");
            case HISTORICAL:
                return L10n.t(context, "Historical");
            case SELECTED:
            default:
                return L10n.t(context, "Selected");
        }
    }

    private static String join(List<String> values) {
        StringBuilder result = new StringBuilder();
        for (String value : values) {
            if (result.length() > 0) result.append(", ");
            result.append(value);
        }
        return result.toString();
    }

    private static int structuredFieldCount(String text) {
        try {
            return text == null ? 0 : new JSONObject(text).length();
        } catch (Throwable ignored) {
            return 0;
        }
    }
}
