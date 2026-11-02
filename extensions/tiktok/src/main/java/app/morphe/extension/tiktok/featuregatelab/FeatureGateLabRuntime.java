/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.featuregatelab;

import android.net.Uri;
import android.util.Log;

import app.morphe.extension.shared.diagnostics.DiagnosticRedactor;

import org.json.JSONObject;
import org.json.JSONArray;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.lang.reflect.Type;

import app.morphe.extension.shared.settings.Setting;

public final class FeatureGateLabRuntime {
    private static final String TAG = "MorpheFeatureGateLab";
    private static volatile Snapshot snapshot;
    /** Bumped by every rule change, so a snapshot built from older rules is not published. */
    private static final java.util.concurrent.atomic.AtomicInteger generation =
            new java.util.concurrent.atomic.AtomicInteger();
    /** Monitor entries, so the boundary test can show a gate read is not taking the lock. */
    static volatile int snapshotMonitorEntries;
    private static final ThreadLocal<Boolean> buildingSnapshot = new ThreadLocal<>();
    private static final Set<String> triggered = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<String, String> firstCallers = new ConcurrentHashMap<>();
    private static final Map<String, String> originalValues = new ConcurrentHashMap<>();
    private static final Map<String, FeatureGateFailure> structuredFailures =
            new ConcurrentHashMap<>();
    // Declared as the class rather than Map: putIfAbsent on the Map interface is an API 24
    // default method D8 cannot backport, and this is read on the host's own gate threads.
    private static final ConcurrentHashMap<String, Object> observedPlayerValues = new ConcurrentHashMap<>();

    private FeatureGateLabRuntime() {
    }

    public static boolean isInstalled() {
        return false;
    }

    /**
     * Marks the rules changed.
     *
     * <p>The generation is what makes this safe against a gate thread that is already inside
     * {@link #buildSnapshot}. Clearing the field alone was not: the builder had read the old
     * rules, and it published them over the null a moment later, so the change stayed invisible
     * until something happened to reload again. A builder now publishes only if the generation
     * it started from is still current.
     */
    public static void reloadRules() {
        generation.incrementAndGet();
        snapshot = null;
    }

    public static void resetTriggered(String ruleId) {
        triggered.remove(ruleId);
        firstCallers.remove(ruleId);
        originalValues.remove(ruleId);
        structuredFailures.remove(ruleId);
    }

    public static void clearTriggered() {
        triggered.clear();
        firstCallers.clear();
        originalValues.clear();
        structuredFailures.clear();
        observedPlayerValues.clear();
    }

    public static boolean isTriggered(String manager, String key, String type) {
        return triggered.contains(FeatureGateLabStore.idFor(manager, key, type));
    }

    public static String firstCaller(String manager, String key, String type) {
        return firstCallers.get(FeatureGateLabStore.idFor(manager, key, type));
    }

    public static String originalValue(String manager, String key, String type) {
        return originalValues.get(FeatureGateLabStore.idFor(manager, key, type));
    }

    public static FeatureGateFailure structuredFailure(String manager, String key, String type) {
        return structuredFailures.get(FeatureGateLabStore.idFor(manager, key, type));
    }

    public static String runtimeRuleState(String manager, String key, String type) {
        if (!FeatureGateLabStore.runtimeStorageAvailable()) {
            return "Waiting for TikTok context";
        }
        Snapshot current = currentSnapshot();
        if (current == null) {
            return "Runtime snapshot unavailable";
        }
        if (!current.masterEnabled) {
            return "Master override is off";
        }
        if (!FeatureGateLabStore.supportsOverride(manager, type)) {
            return "No supported override boundary";
        }
        return current.rules.containsKey(identity(manager, key, type))
                ? "Exact typed rule loaded"
                : "Exact typed rule not loaded";
    }

    public static boolean overrideBoolean(String key, boolean original) {
        return overrideBooleanFor(FeatureGateLabStore.MANAGER_ABMOCK, key, original);
    }

    public static boolean overrideLiveBoolean(String key, boolean original) {
        return overrideBooleanFor(FeatureGateLabStore.MANAGER_LIVE, key, original);
    }

    public static Boolean overrideVeBoolean(String key, Boolean original) {
        if (original == null) {
            return null;
        }
        return Boolean.valueOf(overrideBooleanFor(
                FeatureGateLabStore.MANAGER_VE_CONFIG,
                key,
                original.booleanValue()
        ));
    }

    private static boolean overrideBooleanFor(String manager, String key, boolean original) {
        FeatureGateLearnMode.observe(manager, key, "BOOLEAN", original);
        FeatureGateLabStore.Rule rule = activeRule(manager, key, "BOOLEAN");
        if (rule == null) {
            return original;
        }
        Boolean forced = parseBoolean(rule.value);
        if (forced == null) {
            return original;
        }
        markTriggered(rule, original, forced);
        return forced.booleanValue();
    }

    public static int overrideInt(String key, int original) {
        return overrideIntFor(FeatureGateLabStore.MANAGER_ABMOCK, key, original);
    }

    public static int overrideLiveInt(String key, int original) {
        return overrideIntFor(FeatureGateLabStore.MANAGER_LIVE, key, original);
    }

    public static int overrideVeInt(String key, int original) {
        return overrideIntFor(FeatureGateLabStore.MANAGER_VE_CONFIG, key, original);
    }

    private static int overrideIntFor(String manager, String key, int original) {
        FeatureGateLearnMode.observe(manager, key, "INT", original);
        FeatureGateLabStore.Rule rule = activeRule(manager, key, "INT");
        if (rule == null) {
            return original;
        }
        try {
            int forced = Integer.parseInt(rule.value);
            markTriggered(rule, original, forced);
            return forced;
        } catch (NumberFormatException ignored) {
            return original;
        }
    }

    public static long overrideLong(String key, long original) {
        return overrideLongFor(FeatureGateLabStore.MANAGER_ABMOCK, key, original);
    }

    public static long overrideLiveLong(String key, long original) {
        return overrideLongFor(FeatureGateLabStore.MANAGER_LIVE, key, original);
    }

    public static long overrideVeLong(String key, long original) {
        return overrideLongFor(FeatureGateLabStore.MANAGER_VE_CONFIG, key, original);
    }

    private static long overrideLongFor(String manager, String key, long original) {
        FeatureGateLearnMode.observe(manager, key, "LONG", original);
        FeatureGateLabStore.Rule rule = activeRule(manager, key, "LONG");
        if (rule == null) {
            return original;
        }
        try {
            long forced = Long.parseLong(rule.value);
            markTriggered(rule, original, forced);
            return forced;
        } catch (NumberFormatException ignored) {
            return original;
        }
    }

    public static float overrideFloat(String key, float original) {
        return overrideFloatFor(FeatureGateLabStore.MANAGER_ABMOCK, key, original);
    }

    public static float overrideLiveFloat(String key, float original) {
        return overrideFloatFor(FeatureGateLabStore.MANAGER_LIVE, key, original);
    }

    public static float overrideVeFloat(String key, float original) {
        return overrideFloatFor(FeatureGateLabStore.MANAGER_VE_CONFIG, key, original);
    }

    private static float overrideFloatFor(String manager, String key, float original) {
        FeatureGateLearnMode.observe(manager, key, "FLOAT", original);
        FeatureGateLabStore.Rule rule = activeRule(manager, key, "FLOAT");
        if (rule == null) {
            return original;
        }
        try {
            float forced = Float.parseFloat(rule.value);
            if (!Float.isFinite(forced)) {
                return original;
            }
            markTriggered(rule, original, forced);
            return forced;
        } catch (NumberFormatException ignored) {
            return original;
        }
    }

    public static double overrideDouble(String key, double original) {
        return overrideDoubleFor(FeatureGateLabStore.MANAGER_ABMOCK, key, original);
    }

    public static double overrideLiveDouble(String key, double original) {
        return overrideDoubleFor(FeatureGateLabStore.MANAGER_LIVE, key, original);
    }

    private static double overrideDoubleFor(String manager, String key, double original) {
        FeatureGateLearnMode.observe(manager, key, "DOUBLE", original);
        FeatureGateLabStore.Rule rule = activeRule(manager, key, "DOUBLE");
        if (rule == null) {
            return original;
        }
        try {
            double forced = Double.parseDouble(rule.value);
            if (!Double.isFinite(forced)) {
                return original;
            }
            markTriggered(rule, original, forced);
            return forced;
        } catch (NumberFormatException ignored) {
            return original;
        }
    }

    public static String overrideString(String key, String original) {
        return overrideStringFor(FeatureGateLabStore.MANAGER_ABMOCK, key, original);
    }

    public static String overrideLiveString(String key, String original) {
        return overrideStringFor(FeatureGateLabStore.MANAGER_LIVE, key, original);
    }

    public static String overrideVeString(String key, String original) {
        return overrideStringFor(FeatureGateLabStore.MANAGER_VE_CONFIG, key, original);
    }

    public static Object overrideRawAbValue(String key, Object original, boolean returnStringForObject) {
        FeatureGateLearnMode.observe(FeatureGateLabStore.MANAGER_ABMOCK, key, "RAW", original);
        if (returnStringForObject) {
            return original;
        }
        String type = scalarTypeOf(original);
        FeatureGateLabStore.Rule rule;
        if (type == null) {
            if (original != null) {
                return original;
            }
            rule = catalogAgrees(key, uniqueActiveAbRule(key));
        } else {
            rule = activeRule(FeatureGateLabStore.MANAGER_ABMOCK, key, type);
        }
        if (rule == null) {
            return original;
        }
        Object forced = parseBoxedScalar(rule);
        if (forced == null) {
            return original;
        }
        markTriggered(rule, original, forced);
        return forced;
    }

    public static Object overridePlayerValue(String key, Type declaredType, Object original) {
        FeatureGateLearnMode.observe(FeatureGateLabStore.MANAGER_PLAYER_CONFIG, key, String.valueOf(declaredType), original);
        String type = scalarTypeOf(declaredType, original);
        if (type == null || key == null) {
            return original;
        }
        if (original != null) {
            observedPlayerValues.putIfAbsent(key, original);
        }
        FeatureGateLabStore.Rule rule = activeRule(
                FeatureGateLabStore.MANAGER_PLAYER_CONFIG,
                key,
                type
        );
        if (rule == null) {
            return original;
        }
        Object forced = parsePlayerScalar(rule, declaredType);
        if (forced == null) {
            return original;
        }
        markTriggered(rule, original, forced);
        return forced;
    }

    public static Map<String, Object> playerObservedValues() {
        return new HashMap<>(observedPlayerValues);
    }

    public static Object observeSettingsObjectWithoutDefault(
            String key,
            Class<?> requestedClass,
            Object returnedValue
    ) {
        Object observed = SettingsManagerObservationRecorder.observeWithoutDefault(
                key,
                requestedClass,
                returnedValue
        );
        return overrideSettingsObject(key, requestedClass, null, observed);
    }

    public static Object observeSettingsObject(
            String key,
            Class<?> requestedClass,
            Object defaultValue,
            Object returnedValue
    ) {
        Object observed = SettingsManagerObservationRecorder.observeWithDefault(
                key,
                requestedClass,
                defaultValue,
                returnedValue
        );
        return overrideSettingsObject(key, requestedClass, defaultValue, observed);
    }

    public static Object observeLiveSettingsObject(
            String key,
            Object defaultValue,
            Object returnedValue
    ) {
        Class<?> requestedClass = classForValue(defaultValue, returnedValue);
        if (requestedClass == null) {
            return returnedValue;
        }
        Object observed = SettingsManagerObservationRecorder.observeLiveWithDefault(
                key,
                requestedClass,
                defaultValue,
                returnedValue,
                "(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;"
        );
        return overrideStructuredObject(
                FeatureGateLabStore.MANAGER_LIVE,
                key,
                requestedClass,
                defaultValue,
                observed
        );
    }

    public static Object observeLiveSettingsClassObject(
            Class<?> requestedClass,
            Object returnedValue
    ) {
        if (requestedClass == null) {
            return returnedValue;
        }
        String key = liveValueForClass("getKey", requestedClass);
        Object defaultValue = liveObjectForClass("getDefaultValue", requestedClass);
        if (key == null || key.isEmpty()) {
            return returnedValue;
        }
        Object observed = SettingsManagerObservationRecorder.observeLiveWithDefault(
                key,
                requestedClass,
                defaultValue,
                returnedValue,
                "(Ljava/lang/Class;)Ljava/lang/Object;"
        );
        return overrideStructuredObject(
                FeatureGateLabStore.MANAGER_LIVE,
                key,
                requestedClass,
                defaultValue,
                observed
        );
    }

    public static JSONArray settingsManagerObservationsJson() {
        return SettingsManagerObservationRecorder.exportJson();
    }

    private static Object overrideSettingsObject(
            String key,
            Class<?> requestedClass,
            Object defaultValue,
            Object original
    ) {
        return overrideStructuredObject(
                FeatureGateLabStore.MANAGER_SETTINGS_MANAGER,
                key,
                requestedClass,
                defaultValue,
                original
        );
    }

    private static Object overrideStructuredObject(
            String manager,
            String key,
            Class<?> requestedClass,
            Object defaultValue,
            Object original
    ) {
        FeatureGateLabStore.Rule rule = activeRule(
                manager,
                key,
                "OBJECT"
        );
        if (rule == null) {
            return original;
        }
        StructuredConfigController.ApplyResult result = StructuredConfigController.apply(
                requestedClass,
                defaultValue,
                original,
                rule.value
        );
        if (!result.applied) {
            structuredFailures.put(rule.id, result.error);
            return original;
        }
        structuredFailures.remove(rule.id);
        markTriggered(
                rule,
                SettingsManagerObservationRecorder.serializeText(original),
                result.fieldCount + " structured field" + (result.fieldCount == 1 ? "" : "s")
        );
        return result.value;
    }

    private static Class<?> classForValue(Object defaultValue, Object returnedValue) {
        if (defaultValue != null) {
            return defaultValue.getClass();
        }
        return returnedValue == null ? null : returnedValue.getClass();
    }

    private static String liveValueForClass(String methodName, Class<?> requestedClass) {
        Object value = liveObjectForClass(methodName, requestedClass);
        return value == null ? null : String.valueOf(value);
    }

    /*
     * DataCenter's two lookups, found once. The Lab sits on TikTok's LIVE settings getter, which
     * is on by default and runs whenever LIVE reads a setting, and each read used to load the
     * class by name and look the method up again, twice. A missing class or method is kept as
     * missing, so it is not asked for again either.
     */
    private static final Object NO_METHOD = new Object();
    private static final ConcurrentHashMap<String, Object> DATA_CENTER_METHODS = new ConcurrentHashMap<>();

    private static Object liveObjectForClass(String methodName, Class<?> requestedClass) {
        Object method = DATA_CENTER_METHODS.get(methodName);
        if (method == null) {
            try {
                Class<?> dataCenter = Class.forName(
                        "com.bytedance.android.live_settings.DataCenter",
                        false,
                        FeatureGateLabRuntime.class.getClassLoader()
                );
                method = dataCenter.getMethod(methodName, Class.class);
            } catch (Throwable missing) {
                method = NO_METHOD;
            }
            DATA_CENTER_METHODS.put(methodName, method);
        }
        if (method == NO_METHOD) return null;
        try {
            return ((java.lang.reflect.Method) method).invoke(null, requestedClass);
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static boolean wasPlayerObserved(String key) {
        return key != null && observedPlayerValues.containsKey(key);
    }

    public static String transformActivityCenterSchema(String schema) {
        if (schema == null || !schema.contains("activity_center")) {
            return schema;
        }
        Snapshot current = currentSnapshot();
        if (current == null || !current.masterEnabled) {
            return schema;
        }
        try {
            Uri outer = Uri.parse(schema);
            String innerText = outer.getQueryParameter("url");
            if (innerText == null || !innerText.contains("activity_center")) {
                return schema;
            }
            Uri inner = Uri.parse(innerText);
            String existingText = inner.getQueryParameter("dev_fg_json");
            JSONObject values = existingText == null || existingText.isEmpty()
                    ? new JSONObject()
                    : new JSONObject(existingText);
            boolean changed = false;
            for (FeatureGateLabStore.Rule rule : current.rules.values()) {
                if (!FeatureGateLabStore.MANAGER_PIA_ACTIVITY_CENTER.equals(rule.manager)) {
                    continue;
                }
                Object original = values.has(rule.key) ? values.opt(rule.key) : null;
                Object forced = parseJsonScalar(rule);
                if (forced == null) {
                    continue;
                }
                values.put(rule.key, forced);
                markTriggered(rule, original == null ? "absent" : String.valueOf(original), String.valueOf(forced));
                changed = true;
            }
            if (!changed) {
                return schema;
            }
            Uri updatedInner = replaceQueryParameter(inner, "dev_fg_json", values.toString());
            return replaceQueryParameter(outer, "url", updatedInner.toString()).toString();
        } catch (Throwable throwable) {
            Log.w(TAG, "Activity Center override skipped: " + throwable.getClass().getSimpleName());
            return schema;
        }
    }

    private static String overrideStringFor(String manager, String key, String original) {
        FeatureGateLearnMode.observe(manager, key, "STRING", original);
        FeatureGateLabStore.Rule rule = activeRule(manager, key, "STRING");
        if (rule == null) {
            return original;
        }
        markTriggered(rule, original, rule.value);
        return rule.value;
    }

    private static Object parseJsonScalar(FeatureGateLabStore.Rule rule) {
        try {
            switch (rule.type) {
                case "BOOLEAN":
                    return parseBoolean(rule.value);
                case "INT":
                    return Integer.parseInt(rule.value);
                case "LONG":
                    return Long.parseLong(rule.value);
                case "FLOAT": {
                    float value = Float.parseFloat(rule.value);
                    return Float.isFinite(value) ? value : null;
                }
                case "DOUBLE": {
                    double value = Double.parseDouble(rule.value);
                    return Double.isFinite(value) ? value : null;
                }
                case "STRING":
                    return rule.value;
                default:
                    return null;
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Object parseBoxedScalar(FeatureGateLabStore.Rule rule) {
        try {
            switch (rule.type) {
                case "BOOLEAN":
                    return parseBoolean(rule.value);
                case "INT":
                    return Integer.valueOf(rule.value);
                case "LONG":
                    return Long.valueOf(rule.value);
                case "FLOAT": {
                    float value = Float.parseFloat(rule.value);
                    return Float.isFinite(value) ? value : null;
                }
                case "DOUBLE": {
                    double value = Double.parseDouble(rule.value);
                    return Double.isFinite(value) ? value : null;
                }
                case "STRING":
                    return rule.value;
                default:
                    return null;
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Object parsePlayerScalar(FeatureGateLabStore.Rule rule, Type declaredType) {
        Object parsed = parseBoxedScalar(rule);
        if (parsed == null) {
            return null;
        }
        if ((declaredType == Short.TYPE || declaredType == Short.class) && parsed instanceof Integer) {
            int value = ((Integer) parsed).intValue();
            return value >= Short.MIN_VALUE && value <= Short.MAX_VALUE
                    ? Short.valueOf((short) value)
                    : null;
        }
        return parsed;
    }

    private static Boolean parseBoolean(String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            return null;
        }
        return Boolean.valueOf(value);
    }

    private static String scalarTypeOf(Object value) {
        if (value instanceof Boolean) return "BOOLEAN";
        if (value instanceof Integer) return "INT";
        if (value instanceof Long) return "LONG";
        if (value instanceof Float) return "FLOAT";
        if (value instanceof Double) return "DOUBLE";
        if (value instanceof String) return "STRING";
        return null;
    }

    private static String scalarTypeOf(Type declaredType, Object value) {
        String runtimeType = scalarTypeOf(value);
        if (runtimeType != null) {
            return runtimeType;
        }
        if (declaredType == Boolean.TYPE || declaredType == Boolean.class) return "BOOLEAN";
        if (declaredType == Short.TYPE || declaredType == Short.class
                || declaredType == Integer.TYPE || declaredType == Integer.class) return "INT";
        if (declaredType == Long.TYPE || declaredType == Long.class) return "LONG";
        if (declaredType == Float.TYPE || declaredType == Float.class) return "FLOAT";
        if (declaredType == Double.TYPE || declaredType == Double.class) return "DOUBLE";
        if (declaredType == String.class) return "STRING";
        return null;
    }

    /** So the background catalogue load below is asked for once in a process, not per read. */
    private static final AtomicBoolean catalogRequested = new AtomicBoolean();

    /** Puts a test back on a process that has not asked for the catalogue yet. */
    static void clearCatalogRequestForTests() {
        catalogRequested.set(false);
    }

    /**
     * Refuses a rule whose type the catalogue disagrees with.
     *
     * <p>Only the null-cached path needs this. Everywhere else the value TikTok already holds
     * names the type, and the rule is looked up by it; here there is nothing to check against,
     * so a rule matched by key alone can hand the host a String where its caller casts to a
     * Number, and the ClassCastException lands in TikTok's own frame.
     *
     * <p>Nothing but the Lab's own screen used to load the catalogue, so on a fresh launch this
     * check could not refuse anything until that screen was opened, which is the case it exists
     * for. Reaching it with nothing cached now asks for the catalogue in the background and takes
     * the rule as it stands this once; every later read has something to check against. The load
     * is asked for only where a rule of this shape exists, so a phone with no AB rules saved
     * never pays for it, and only once per process either way.
     */
    private static FeatureGateLabStore.Rule catalogAgrees(String key, FeatureGateLabStore.Rule rule) {
        if (rule == null) {
            return null;
        }
        // One string per AB key rather than the whole 16,052 entry snapshot. This runs on a
        // launch where the Lab screen may never be opened, and whatever it holds it holds for
        // the life of the process. The Lab screen still loads the snapshot, and that fills this.
        Map<String, String> catalogTypes = FeatureGateCatalog.cachedAbTypes();
        if (catalogTypes == null) {
            if (catalogRequested.compareAndSet(false, true)) {
                // Nothing to do when it lands: the next read reads the map it fills.
                FeatureGateCatalog.loadAbTypesAsync(new FeatureGateCatalog.AbTypesCallback() {
                    @Override public void onLoaded(Map<String, String> abTypes) { }

                    @Override public void onError(String message) {
                        // Let the next read ask again. Left set, one failed load would have put
                        // the process back to where it was before this existed, silently.
                        catalogRequested.set(false);
                        Log.w(TAG, DiagnosticRedactor.redact(
                                "catalog load for the AB fallback failed: " + message));
                    }
                });
            }
            return rule;
        }
        String catalogType = catalogTypes.get(key);
        if (catalogType == null) {
            return refuse(rule, FeatureGateFailure.of(
                    FeatureGateFailure.Reason.NOT_IN_CATALOGUE));
        }
        if (!FeatureGateLabStore.normalizeType(catalogType)
                .equals(FeatureGateLabStore.normalizeType(rule.type))) {
            return refuse(rule, FeatureGateFailure.of(
                    FeatureGateFailure.Reason.TYPE_MISMATCH, catalogType, rule.type));
        }
        structuredFailures.remove(rule.id);
        return rule;
    }

    /**
     * Records why a rule was not used, and says so in the log.
     *
     * <p>The record is filed under the rule's own identity, which includes the type the rule
     * carries. The detail screen looks a rule up by the catalogue's type, so for the one refusal
     * that is about a type disagreeing it finds no rule at all and says the gate is using
     * TikTok's value. The log line is the only place this is visible, which is why it is here.
     */
    private static FeatureGateLabStore.Rule refuse(FeatureGateLabStore.Rule rule,
            FeatureGateFailure reason) {
        structuredFailures.put(rule.id, reason);
        Log.i(TAG, DiagnosticRedactor.redact("refused manager=" + rule.manager + " key=" + rule.key
                + " type=" + rule.type + " reason=" + reason.text()));
        return null;
    }

    private static FeatureGateLabStore.Rule uniqueActiveAbRule(String key) {
        Snapshot current = currentSnapshot();
        if (current == null || !current.masterEnabled || key == null) {
            return null;
        }
        FeatureGateLabStore.Rule match = null;
        for (FeatureGateLabStore.Rule candidate : current.rules.values()) {
            if (!FeatureGateLabStore.MANAGER_ABMOCK.equals(candidate.manager)
                    || !key.equals(candidate.key)) {
                continue;
            }
            if (match != null) {
                return null;
            }
            match = candidate;
        }
        return match;
    }

    private static Uri replaceQueryParameter(Uri uri, String targetName, String targetValue) {
        Uri.Builder builder = uri.buildUpon().clearQuery();
        for (String name : uri.getQueryParameterNames()) {
            if (targetName.equals(name)) {
                continue;
            }
            for (String value : uri.getQueryParameters(name)) {
                builder.appendQueryParameter(name, value);
            }
        }
        builder.appendQueryParameter(targetName, targetValue);
        return builder.build();
    }

    private static FeatureGateLabStore.Rule activeRule(String manager, String key, String type) {
        Snapshot current = currentSnapshot();
        if (current == null || !current.masterEnabled || key == null) {
            return null;
        }
        return current.rules.get(identity(manager, key, type));
    }

    private static Snapshot currentSnapshot() {
        int wanted = generation.get();
        Snapshot current = snapshot;
        if (current != null && current.generation == wanted && usable(current)) {
            return current;
        }
        if (Boolean.TRUE.equals(buildingSnapshot.get())) {
            return null;
        }
        synchronized (FeatureGateLabRuntime.class) {
            snapshotMonitorEntries++;
            wanted = generation.get();
            current = snapshot;
            if (current != null && current.generation == wanted && usable(current)) {
                return current;
            }
            buildingSnapshot.set(Boolean.TRUE);
            try {
                current = buildSnapshot(wanted);
                // Only if nothing changed the rules while they were being read. Otherwise this
                // would put the rules it started from back over a change that has already
                // happened, and that change would not be seen until the next reload.
                //
                // This and the generation comparison on the read path above cover each other:
                // remove either one alone and the tests still pass, because the other catches
                // the stale snapshot. The read path is the one that has to be right; this one
                // saves a rebuild by not caching what is already known to be out of date.
                if (generation.get() == wanted) {
                    snapshot = current;
                }
            } finally {
                buildingSnapshot.remove();
            }
        }
        return current;
    }

    /**
     * Whether a cached snapshot can still be served without rebuilding.
     *
     * <p>A snapshot built before TikTok gave the extension a context carries no rules and is
     * cached anyway, so process start does not put every gate read on every thread through the
     * monitor. It stops being usable the moment storage appears, which is one static read.
     */
    private static boolean usable(Snapshot current) {
        return !current.unavailable || !FeatureGateLabStore.runtimeStorageAvailable();
    }

    private static Snapshot buildSnapshot(int builtAt) {
        if (!FeatureGateLabStore.runtimeStorageAvailable()) {
            // Not null: a null was rebuilt on every read, and every one of those took the class
            // monitor, at the point in start-up where the host reads gates hardest.
            return new Snapshot(builtAt, true, false, Collections.emptyMap());
        }
        Map<String, FeatureGateLabStore.Rule> active = new HashMap<>();
        // Rules another TikTok build saved wait until the main process has checked them against
        // this build's catalog. A secondary process opens the store without doing that.
        boolean forThisBuild = FeatureGateLabStore.storedForRunningBuild();
        for (FeatureGateLabStore.Rule rule : forThisBuild
                ? FeatureGateLabStore.rules() : Collections.<FeatureGateLabStore.Rule>emptyList()) {
            if (rule.enabled && FeatureGateLabStore.supportsOverride(rule.manager, rule.type)) {
                active.put(identity(rule.manager, rule.key, rule.type), rule);
            }
        }
        // The Lab's master switch lives in its own store, outside the settings Pause Hushfeed
        // answers for, so a paused process turns it off here.
        boolean masterEnabled = FeatureGateLabStore.masterEnabled() && !Setting.isPaused();
        Log.i(TAG, DiagnosticRedactor.redact("snapshot master=" + masterEnabled
                + " active_rules=" + active.size()
                + " identities=" + summarizeRules(active)));
        // Between reading the rules above and returning, a save on another thread may have
        // already moved the generation on. The publisher checks that; this seam exists so a
        // test can create that overlap deterministically.
        Runnable overlap = rulesReadHook;
        if (overlap != null) overlap.run();
        return new Snapshot(builtAt, false, masterEnabled, Collections.unmodifiableMap(active));
    }

    /** Runs inside a snapshot build, after the rules are read. Set only by tests. */
    static volatile Runnable rulesReadHook;

    private static String summarizeRules(Map<String, FeatureGateLabStore.Rule> rules) {
        if (rules.isEmpty()) {
            return "none";
        }
        StringBuilder result = new StringBuilder();
        for (FeatureGateLabStore.Rule rule : rules.values()) {
            if (result.length() > 0) {
                result.append(',');
            }
            result.append(rule.manager).append('/').append(safeLog(rule.key)).append(':').append(rule.type);
        }
        return result.toString();
    }

    private static String identity(String manager, String key, String type) {
        return manager + "\n" + key + "\n" + FeatureGateLabStore.normalizeType(type);
    }

    /**
     * Records that a rule replaced a value, on every read the rule answers.
     *
     * <p>Takes the values rather than their text: this runs on the host's gate threads, and the
     * forced value is only ever read by the line logged once per rule below. The original is
     * still written every time, because the detail screen calls it the <em>last</em> original
     * value, but only when it differs from what is already there, which for a gate read over and
     * over with the same original is never after the first.
     */
    private static void markTriggered(FeatureGateLabStore.Rule rule, Object original, Object forced) {
        String originalText = original == null ? "null" : String.valueOf(original);
        if (!originalText.equals(originalValues.get(rule.id))) {
            originalValues.put(rule.id, originalText);
        }
        if (!triggered.add(rule.id)) {
            return;
        }
        String caller = findCaller();
        firstCallers.put(rule.id, caller);
        Log.i(TAG, DiagnosticRedactor.redact("manager=" + rule.manager
                + " key=" + rule.key
                + " type=" + rule.type
                + " original=" + safeLog(originalText)
                + " forced=" + safeLog(String.valueOf(forced))
                + " caller=" + caller));
    }

    private static String findCaller() {
        return GateCallers.hostCaller(Thread.currentThread().getStackTrace());
    }

    private static String safeLog(String value) {
        if (value == null) {
            return "null";
        }
        String singleLine = value.replace('\n', ' ').replace('\r', ' ');
        return singleLine.length() <= 160 ? singleLine : singleLine.substring(0, 157) + "...";
    }

    private static final class Snapshot {
        /** The rule generation this was built from. */
        final int generation;
        /** True when it was built with no context, so it holds no rules and stands in for null. */
        final boolean unavailable;
        final boolean masterEnabled;
        final Map<String, FeatureGateLabStore.Rule> rules;

        Snapshot(int generation, boolean unavailable, boolean masterEnabled,
                Map<String, FeatureGateLabStore.Rule> rules) {
            this.generation = generation;
            this.unavailable = unavailable;
            this.masterEnabled = masterEnabled;
            this.rules = rules;
        }
    }
}
