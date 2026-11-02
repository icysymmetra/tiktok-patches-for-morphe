/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.featuregatelab;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

final class SettingsManagerObservationRecorder {
    private static final int MAX_OBSERVATIONS = 2048;
    private static final int MAX_DEPTH = 6;
    private static final int MAX_COLLECTION_ITEMS = 128;
    private static final int MAX_FIELDS = 128;
    private static final int MAX_STRING_LENGTH = 8192;
    private static final Object NO_DEFAULT = new Object();
    private static final Object OBSERVATION_LOCK = new Object();
    // Declared as the class, not Map: putIfAbsent below is the concrete API 1 method, while the
    // one on the Map interface is an API 24 default D8 cannot backport.
    private static final ConcurrentHashMap<String, Observation> OBSERVATIONS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> DEFAULT_WRAPPER_KEYS = new ConcurrentHashMap<>();
    /**
     * What the stack walk said about a key, so it is walked once rather than on every read.
     *
     * <p>A key TikTok only ever reads through the no-default getter never enters
     * {@link #DEFAULT_WRAPPER_KEYS}, so without this every read of it captured a full stack
     * trace, forever, on whatever thread the host reads settings from.
     */
    private static final ConcurrentHashMap<String, Boolean> WRAPPER_CHECKED = new ConcurrentHashMap<>();

    private SettingsManagerObservationRecorder() {
    }

    static void clear() {
        synchronized (OBSERVATION_LOCK) {
            OBSERVATIONS.clear();
            DEFAULT_WRAPPER_KEYS.clear();
            WRAPPER_CHECKED.clear();
        }
    }

    /** Observations are immutable serialized values and exist only for this process. */
    static Runnable checkpoint() {
        java.util.Map<String, Observation> observations;
        java.util.Map<String, Boolean> wrappers;
        synchronized (OBSERVATION_LOCK) {
            observations = new java.util.HashMap<>(OBSERVATIONS);
            wrappers = new java.util.HashMap<>(DEFAULT_WRAPPER_KEYS);
        }
        return () -> {
            synchronized (OBSERVATION_LOCK) {
                OBSERVATIONS.clear();
                DEFAULT_WRAPPER_KEYS.clear();
                WRAPPER_CHECKED.clear();
                OBSERVATIONS.putAll(observations);
                DEFAULT_WRAPPER_KEYS.putAll(wrappers);
            }
        };
    }

    static Object observeWithoutDefault(String key, Class<?> requestedClass, Object returnedValue) {
        FeatureGateLearnMode.observe(FeatureGateLabStore.MANAGER_SETTINGS_MANAGER, key,
                (requestedClass == null ? "unknown" : requestedClass.getName())
                        + "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;", returnedValue);
        if (key != null && (DEFAULT_WRAPPER_KEYS.containsKey(key) || cameFromDefaultWrapper(key))) {
            return returnedValue;
        }
        record(
                FeatureGateLabStore.MANAGER_SETTINGS_MANAGER,
                "runtime_observed_settings_manager_call",
                key,
                requestedClass,
                NO_DEFAULT,
                returnedValue,
                "(Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/Object;"
        );
        return returnedValue;
    }

    static Object observeWithDefault(
            String key,
            Class<?> requestedClass,
            Object defaultValue,
            Object returnedValue
    ) {
        if (key != null) {
            DEFAULT_WRAPPER_KEYS.put(key, Boolean.TRUE);
        }
        record(
                FeatureGateLabStore.MANAGER_SETTINGS_MANAGER,
                "runtime_observed_settings_manager_call",
                key,
                requestedClass,
                defaultValue,
                returnedValue,
                "(Ljava/lang/String;Ljava/lang/Class;Ljava/lang/Object;)Ljava/lang/Object;"
        );
        return returnedValue;
    }

    static Object observeLiveWithDefault(
            String key,
            Class<?> requestedClass,
            Object defaultValue,
            Object returnedValue,
            String methodDescriptor
    ) {
        record(
                FeatureGateLabStore.MANAGER_LIVE,
                "runtime_observed_live_settings_call",
                key,
                requestedClass,
                defaultValue,
                returnedValue,
                methodDescriptor
        );
        return returnedValue;
    }

    static JSONArray exportJson() {
        List<Observation> snapshot;
        synchronized (OBSERVATION_LOCK) {
            snapshot = new ArrayList<>(OBSERVATIONS.values());
        }
        Collections.sort(snapshot, (left, right) -> left.key.compareTo(right.key));
        JSONArray result = new JSONArray();
        for (Observation observation : snapshot) {
            result.put(observation.toJson());
        }
        return result;
    }

    static int size() {
        synchronized (OBSERVATION_LOCK) {
            return OBSERVATIONS.size();
        }
    }

    static String serializeText(Object value) {
        return serializeJsonText(value);
    }

    static Object serializeValue(Object value) {
        return serialize(value);
    }

    /** Counts stack walks, so a test can show a key is only walked once. */
    static volatile int wrapperWalks;

    /**
     * Whether this read came through the getter with a default, remembered per key.
     *
     * <p>That getter reaches this one through TikTok's settings cache and a wrapper, and it is
     * recorded on its own, so a read that came that way would be the same key twice. The
     * wrapper's class used to be named here, first 46.2.3's and then whatever class hooked the
     * getter with a default, which is SettingsManager itself: from then on every read of this
     * getter matched, its own frame included, and nothing read without a default was recorded.
     * What tells the two apart on every build is a second SettingsManager frame a few frames
     * out, see {@link GateCallers#throughAnotherSettingsManagerGetter}.
     *
     * <p>The answer for a given key does not change over a process, and the walk it takes is the
     * whole cost of this path, so it is taken once. Past the observation cap nothing new is
     * recorded anyway, so nothing new is remembered either.
     */
    private static boolean cameFromDefaultWrapper(String key) {
        Boolean known = WRAPPER_CHECKED.get(key);
        if (known != null) {
            return known;
        }
        wrapperWalks++;
        boolean fromWrapper = GateCallers.throughAnotherSettingsManagerGetter(
                Thread.currentThread().getStackTrace());
        if (WRAPPER_CHECKED.size() < MAX_OBSERVATIONS) {
            WRAPPER_CHECKED.putIfAbsent(key, fromWrapper);
        }
        return fromWrapper;
    }

    private static void record(
            String manager,
            String sourceType,
            String key,
            Class<?> requestedClass,
            Object defaultValue,
            Object returnedValue,
            String methodDescriptor
    ) {
        if (key == null || key.isEmpty() || requestedClass == null) {
            return;
        }
        if (defaultValue != NO_DEFAULT) {
            FeatureGateLearnMode.observe(manager, key, requestedClass.getName() + methodDescriptor, returnedValue);
        }
        String identity = manager + "\n" + key + "\n"
                + requestedClass.getName() + "\n" + methodDescriptor;
        // Read first, so a repeat of a key already seen costs one hash lookup. This runs on the
        // host's own settings threads.
        if (OBSERVATIONS.containsKey(identity) || OBSERVATIONS.size() >= MAX_OBSERVATIONS) {
            return;
        }
        // Built outside the lock. The constructor serialises the value by reflection, up to 128
        // fields deep, and doing that while holding the lock stopped every other thread that
        // reached a new identity at the same moment. Two threads racing the same identity build
        // one throwaway between them, which is far cheaper than the wait was.
        Observation observation = new Observation(
                manager,
                sourceType,
                key,
                requestedClass,
                defaultValue,
                returnedValue,
                methodDescriptor,
                captureCaller()
        );
        synchronized (OBSERVATION_LOCK) {
            if (OBSERVATIONS.size() >= MAX_OBSERVATIONS) {
                return;
            }
            OBSERVATIONS.putIfAbsent(identity, observation);
        }
    }

    private static String captureCaller() {
        return GateCallers.hostCallerWithLine(Thread.currentThread().getStackTrace());
    }

    private static String serializeJsonText(Object value) {
        Object serialized = serialize(value);
        if (serialized == null || serialized == JSONObject.NULL) {
            return "null";
        }
        try {
            if (serialized instanceof JSONObject || serialized instanceof JSONArray) {
                return serialized.toString();
            }
            String wrapped = new JSONArray().put(serialized).toString();
            return wrapped.substring(1, wrapped.length() - 1);
        } catch (Throwable ignored) {
            return String.valueOf(serialized);
        }
    }

    private static Object parseJsonText(String text) {
        if (text == null || "null".equals(text)) {
            return JSONObject.NULL;
        }
        try {
            return new JSONTokener(text).nextValue();
        } catch (Throwable ignored) {
            return text;
        }
    }

    private static Object serialize(Object value) {
        return serialize(value, 0, new IdentityHashMap<>());
    }

    private static Object serialize(Object value, int depth, IdentityHashMap<Object, Boolean> visited) {
        if (value == null) return JSONObject.NULL;
        if (value instanceof String || value instanceof Character || value instanceof CharSequence) {
            return truncate(String.valueOf(value));
        }
        if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return value;
        }
        if (value instanceof Float) {
            float number = ((Float) value).floatValue();
            return Float.isFinite(number) ? number : String.valueOf(number);
        }
        if (value instanceof Double) {
            double number = ((Double) value).doubleValue();
            return Double.isFinite(number) ? number : String.valueOf(number);
        }
        if (value instanceof Enum<?>) {
            return ((Enum<?>) value).name();
        }
        if (value instanceof Class<?>) {
            return ((Class<?>) value).getName();
        }
        if (depth >= MAX_DEPTH) {
            return marker("depth_limit", value.getClass());
        }
        if (visited.put(value, Boolean.TRUE) != null) {
            return marker("cycle", value.getClass());
        }
        try {
            Class<?> valueClass = value.getClass();
            if (valueClass.isArray()) {
                JSONArray array = new JSONArray();
                int length = Math.min(Array.getLength(value), MAX_COLLECTION_ITEMS);
                for (int index = 0; index < length; index++) {
                    array.put(serialize(Array.get(value, index), depth + 1, visited));
                }
                if (Array.getLength(value) > length) {
                    array.put(marker("items_truncated", valueClass));
                }
                return array;
            }
            if (value instanceof Iterable<?>) {
                JSONArray array = new JSONArray();
                int count = 0;
                for (Object item : (Iterable<?>) value) {
                    if (count++ >= MAX_COLLECTION_ITEMS) {
                        array.put(marker("items_truncated", valueClass));
                        break;
                    }
                    array.put(serialize(item, depth + 1, visited));
                }
                return array;
            }
            if (value instanceof Map<?, ?>) {
                JSONObject object = new JSONObject();
                object.put("__class", valueClass.getName());
                int count = 0;
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                    if (count++ >= MAX_COLLECTION_ITEMS) {
                        object.put("__truncated", true);
                        break;
                    }
                    object.put(truncate(String.valueOf(entry.getKey())),
                            serialize(entry.getValue(), depth + 1, visited));
                }
                return object;
            }
            return serializeFields(value, depth, visited);
        } catch (Throwable throwable) {
            JSONObject failed = marker("serialization_failed", value.getClass());
            try {
                failed.put("__error", throwable.getClass().getName());
            } catch (Throwable ignored) {
            }
            return failed;
        } finally {
            visited.remove(value);
        }
    }

    private static JSONObject serializeFields(
            Object value,
            int depth,
            IdentityHashMap<Object, Boolean> visited
    ) {
        JSONObject object = new JSONObject();
        JSONObject fieldTypes = new JSONObject();
        Class<?> valueClass = value.getClass();
        put(object, "__class", valueClass.getName());
        int count = 0;
        for (Class<?> owner = valueClass; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                    continue;
                }
                if (count++ >= MAX_FIELDS) {
                    put(object, "__truncated", true);
                    put(object, "__field_types", fieldTypes);
                    return object;
                }
                String name = field.getName();
                put(fieldTypes, name, field.getType().getName());
                try {
                    if (!field.isAccessible()) {
                        field.setAccessible(true);
                    }
                    put(object, name, serialize(field.get(value), depth + 1, visited));
                } catch (Throwable throwable) {
                    put(object, name, marker("field_unavailable", field.getType()));
                }
            }
        }
        put(object, "__field_types", fieldTypes);
        return object;
    }

    private static JSONObject marker(String reason, Class<?> valueClass) {
        JSONObject marker = new JSONObject();
        try {
            marker.put("__class", valueClass == null ? "unknown" : valueClass.getName());
            marker.put("__marker", reason);
        } catch (Throwable ignored) {
        }
        return marker;
    }

    private static String truncate(String value) {
        if (value == null || value.length() <= MAX_STRING_LENGTH) {
            return value;
        }
        return value.substring(0, MAX_STRING_LENGTH) + "...[truncated]";
    }

    private static void put(JSONObject object, String key, Object value) {
        try {
            object.put(key, value);
        } catch (Throwable ignored) {
        }
    }

    private static String titleFor(String key) {
        StringBuilder title = new StringBuilder();
        for (String word : key.split("[_\\-.\\s]+")) {
            if (word.isEmpty()) continue;
            if (title.length() > 0) title.append(' ');
            title.append(Character.toUpperCase(word.charAt(0)));
            if (word.length() > 1) title.append(word.substring(1));
        }
        return title.toString();
    }

    private static final class Observation {
        final String manager;
        final String sourceType;
        final String key;
        final String requestedClass;
        final String methodDescriptor;
        final boolean hasDefault;
        final String defaultJson;
        final String currentJson;
        final String sourceHint;
        final long observedAtMs;
        final String caller;

        Observation(
                String manager,
                String sourceType,
                String key,
                Class<?> requestedClass,
                Object defaultValue,
                Object returnedValue,
                String methodDescriptor,
                String caller
        ) {
            this.manager = manager;
            this.sourceType = sourceType;
            this.key = key;
            this.requestedClass = requestedClass.getName();
            this.methodDescriptor = methodDescriptor;
            this.hasDefault = defaultValue != NO_DEFAULT;
            this.defaultJson = hasDefault ? serializeJsonText(defaultValue) : "null";
            this.currentJson = serializeJsonText(returnedValue);
            this.sourceHint = sourceHint(defaultValue, returnedValue);
            this.observedAtMs = System.currentTimeMillis();
            this.caller = caller;
        }

        JSONObject toJson() {
            JSONObject object = new JSONObject();
            put(object, "title", titleFor(key));
            put(object, "key", key);
            put(object, "manager", manager);
            put(object, "source_type", sourceType);
            put(object, "type", "JSON");
            put(object, "registered", JSONObject.NULL);
            put(object, "loaded", true);
            put(object, "runtime_only", true);
            put(object, "requested_class", requestedClass);
            put(object, "settings_manager_method_descriptor", methodDescriptor);
            put(object, "has_explicit_default", hasDefault);
            put(object, "default_value", parseJsonText(defaultJson));
            put(object, "current_value", parseJsonText(currentJson));
            JSONArray actionableFields =
                    StructuredConfigController.actionableFields(requestedClass);
            put(object, "actionable", actionableFields.length() > 0);
            put(object, "actionable_fields", actionableFields);
            put(object, "source_hint", sourceHint);
            put(object, "observed_at_ms", observedAtMs);
            put(object, "caller", caller);
            return object;
        }

        private static String sourceHint(Object defaultValue, Object returnedValue) {
            if (returnedValue == null) {
                return "null_no_value";
            }
            if (defaultValue != NO_DEFAULT && returnedValue == defaultValue) {
                return "explicit_fallback_default_identity";
            }
            return "provider_value_origin_unknown";
        }
    }
}
