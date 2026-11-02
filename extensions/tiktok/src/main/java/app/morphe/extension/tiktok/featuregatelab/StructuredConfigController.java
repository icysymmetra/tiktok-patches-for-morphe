/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.featuregatelab;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class StructuredConfigController {
    private static final String ROOT_VALUE = "$value";

    private StructuredConfigController() {
    }

    static JSONArray actionableFields(String requestedClassName) {
        JSONArray result = new JSONArray();
        Class<?> requestedClass = classForName(requestedClassName);
        if (requestedClass == null || !canCopy(requestedClass)) {
            return result;
        }
        if (requestedClass.isArray()) {
            result.put(ROOT_VALUE);
            return result;
        }
        for (Field field : instanceFields(requestedClass)) {
            if (isEditable(field)) {
                result.put(field.getName());
            }
        }
        return result;
    }

    static boolean hasActionableFields(String requestedClassName) {
        return actionableFields(requestedClassName).length() > 0;
    }

    static boolean isRootArray(String requestedClassName) {
        Class<?> requestedClass = classForName(requestedClassName);
        return requestedClass != null && isSupportedRootArray(requestedClass);
    }

    static String fieldKind(String requestedClassName, String fieldName) {
        Class<?> requestedClass = classForName(requestedClassName);
        if (requestedClass != null
                && requestedClass.isArray()
                && ROOT_VALUE.equals(fieldName)
                && isSupportedRootArray(requestedClass)) {
            Class<?> component = requestedClass.getComponentType();
            return isScalar(component) ? "LIST_" + scalarKind(component) : "JSON";
        }
        Field field = requestedClass == null ? null : findField(requestedClass, fieldName);
        if (field == null || !isEditable(field)) {
            return "UNSUPPORTED";
        }
        Class<?> type = field.getType();
        if (type.isArray()) {
            return isScalar(type.getComponentType())
                    ? "LIST_" + scalarKind(type.getComponentType())
                    : "JSON";
        }
        if (Collection.class.isAssignableFrom(type)) {
            Class<?> elementType = collectionElementType(field.getGenericType());
            return elementType == null
                    ? "UNSUPPORTED"
                    : (isScalar(elementType) ? "LIST_" + scalarKind(elementType) : "JSON");
        }
        return isScalar(type) ? scalarKind(type) : "JSON";
    }

    static JSONObject defaultValue(String requestedClassName) {
        Class<?> requestedClass = classForName(requestedClassName);
        if (requestedClass != null && isSupportedRootArray(requestedClass)) {
            JSONObject result = new JSONObject();
            try {
                result.put(ROOT_VALUE, new JSONArray());
            } catch (Throwable ignored) {
            }
            return result;
        }
        if (requestedClass == null || !hasNoArgumentConstructor(requestedClass)) {
            return null;
        }
        try {
            Object serialized = SettingsManagerObservationRecorder.serializeValue(
                    newInstance(requestedClass)
            );
            return serialized instanceof JSONObject ? (JSONObject) serialized : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    static ApplyResult apply(
            Class<?> requestedClass,
            Object defaultValue,
            Object returnedValue,
            String patchText
    ) {
        Object source = returnedValue != null ? returnedValue : defaultValue;
        if (requestedClass == null || source == null || !requestedClass.isInstance(source)) {
            return ApplyResult.failure(returnedValue,
                    FeatureGateFailure.of(FeatureGateFailure.Reason.NO_OBJECT));
        }
        if (!canCopy(requestedClass)) {
            return ApplyResult.failure(returnedValue,
                    FeatureGateFailure.of(FeatureGateFailure.Reason.CANNOT_COPY));
        }

        try {
            JSONObject patch = parsed(patchText);
            if (requestedClass.isArray()) {
                if (!patch.has(ROOT_VALUE)) {
                    return ApplyResult.failure(returnedValue,
                            FeatureGateFailure.of(FeatureGateFailure.Reason.NO_LIST_VALUE));
                }
                Object forced = coerce(
                        patch.opt(ROOT_VALUE),
                        requestedClass,
                        requestedClass
                );
                return ApplyResult.success(forced, 1);
            }
            Object copy = newInstance(requestedClass);
            for (Field field : instanceFields(requestedClass)) {
                makeAccessible(field);
                field.set(copy, field.get(source));
            }

            int applied = 0;
            for (String fieldName : names(patch)) {
                if (fieldName.startsWith("__")) {
                    continue;
                }
                Field field = findField(requestedClass, fieldName);
                if (field == null || !isEditable(field)) {
                    return ApplyResult.failure(returnedValue, FeatureGateFailure.of(
                            FeatureGateFailure.Reason.UNSUPPORTED_FIELD, fieldName));
                }
                makeAccessible(field);
                field.set(copy, coerce(patch.opt(fieldName), field.getType(), field.getGenericType()));
                applied++;
            }
            if (applied == 0) {
                return ApplyResult.failure(returnedValue,
                        FeatureGateFailure.of(FeatureGateFailure.Reason.NO_FIELDS));
            }
            return ApplyResult.success(copy, applied);
        } catch (Throwable throwable) {
            return ApplyResult.failure(
                    returnedValue,
                    FeatureGateFailure.of(
                            FeatureGateFailure.Reason.THREW,
                            throwable.getClass().getSimpleName()
                                    + (throwable.getMessage() == null
                                            ? "" : ": " + throwable.getMessage())
                    )
            );
        }
    }

    private static boolean canCopy(Class<?> valueClass) {
        return isSupportedRootArray(valueClass) || hasNoArgumentConstructor(valueClass);
    }

    private static boolean isSupportedRootArray(Class<?> valueClass) {
        if (!valueClass.isArray()) {
            return false;
        }
        Class<?> component = valueClass.getComponentType();
        return isScalar(component) || hasNoArgumentConstructor(component);
    }

    private static Object newInstance(Class<?> valueClass) throws Exception {
        Constructor<?> constructor = valueClass.getDeclaredConstructor();
        makeAccessible(constructor);
        return constructor.newInstance();
    }

    /**
     * The parsed form of a stored value, kept because this runs on TikTok's own gate threads.
     *
     * <p>A structured gate the host reads often used to pay a JSON parse of up to 64 KB on every
     * read. The parsed object is only ever read from here, and it is published through a
     * concurrent map, so sharing one across those threads is safe. The cache is keyed by the
     * text, so a rule that changes parses again on its next read and the old entry falls out.
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, JSONObject> parsedPatches =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Bounds the cache, which is keyed by text and would otherwise hold every value ever saved. */
    private static final int MAX_PARSED_PATCHES = 32;

    private static JSONObject parsed(String patchText) throws org.json.JSONException {
        JSONObject cached = parsedPatches.get(patchText);
        if (cached != null) {
            return cached;
        }
        JSONObject patch = new JSONObject(patchText);
        parsesForTests++;
        if (parsedPatches.size() >= MAX_PARSED_PATCHES) {
            parsedPatches.clear();
        }
        parsedPatches.put(patchText, patch);
        return patch;
    }

    /** How many stored values were actually parsed, so a test can show the cache holds. */
    static int parsesForTests;

    static void clearParsedPatchesForTests() {
        parsedPatches.clear();
        parsesForTests = 0;
    }

    private static boolean isEditable(Field field) {
        int modifiers = field.getModifiers();
        if (Modifier.isStatic(modifiers) || field.isSynthetic()) {
            return false;
        }
        return isSupportedType(field.getType(), field.getGenericType());
    }

    /**
     * How deeply a stored value may nest. The same bound the save time check uses, so a value
     * that was accepted when it was written cannot be refused when it is read.
     */
    private static final int MAX_NESTING = 32;

    private static Object coerce(Object value, Class<?> targetType, Type genericType)
            throws Exception {
        return coerce(value, targetType, genericType, 0);
    }

    private static Object coerce(Object value, Class<?> targetType, Type genericType, int depth)
            throws Exception {
        if (depth > MAX_NESTING) {
            throw new IllegalArgumentException(
                    "nested more than " + MAX_NESTING + " deep, which no gate configuration is");
        }
        if (value == null || value == JSONObject.NULL) {
            if (targetType.isPrimitive()) {
                throw new IllegalArgumentException("null is not valid for " + targetType.getName());
            }
            return null;
        }
        if (targetType == String.class || targetType == CharSequence.class) {
            return String.valueOf(value);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            if (value instanceof Boolean) return value;
            String text = String.valueOf(value);
            if (!"true".equalsIgnoreCase(text) && !"false".equalsIgnoreCase(text)) {
                throw new IllegalArgumentException("expected true or false");
            }
            return Boolean.valueOf(text);
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return Byte.valueOf(numberText(value));
        }
        if (targetType == short.class || targetType == Short.class) {
            return Short.valueOf(numberText(value));
        }
        if (targetType == int.class || targetType == Integer.class) {
            return Integer.valueOf(numberText(value));
        }
        if (targetType == long.class || targetType == Long.class) {
            return Long.valueOf(numberText(value));
        }
        if (targetType == float.class || targetType == Float.class) {
            float parsed = Float.parseFloat(numberText(value));
            if (!Float.isFinite(parsed)) throw new IllegalArgumentException("number must be finite");
            return parsed;
        }
        if (targetType == double.class || targetType == Double.class) {
            double parsed = Double.parseDouble(numberText(value));
            if (!Double.isFinite(parsed)) throw new IllegalArgumentException("number must be finite");
            return parsed;
        }
        if (targetType == char.class || targetType == Character.class) {
            String text = String.valueOf(value);
            if (text.length() != 1) throw new IllegalArgumentException("expected one character");
            return text.charAt(0);
        }
        if (targetType.isEnum()) {
            @SuppressWarnings({"unchecked", "rawtypes"})
            Object parsed = Enum.valueOf((Class<? extends Enum>) targetType, String.valueOf(value));
            return parsed;
        }
        if (targetType.isArray()) {
            if (!(value instanceof JSONArray)) {
                throw new IllegalArgumentException("expected an array");
            }
            JSONArray source = (JSONArray) value;
            Class<?> elementType = targetType.getComponentType();
            Object result = Array.newInstance(elementType, source.length());
            for (int index = 0; index < source.length(); index++) {
                Array.set(result, index, coerce(source.opt(index), elementType, elementType, depth + 1));
            }
            return result;
        }
        if (Map.class.isAssignableFrom(targetType)) {
            if (!(value instanceof JSONObject)) {
                throw new IllegalArgumentException("expected an object map");
            }
            Type[] arguments = typeArguments(genericType);
            if (arguments.length != 2 || classForType(arguments[0]) != String.class) {
                throw new IllegalArgumentException("unsupported map type");
            }
            Class<?> elementType = classForType(arguments[1]);
            if (elementType == null) {
                throw new IllegalArgumentException("unsupported map value type");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            JSONObject source = (JSONObject) value;
            java.util.Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                result.put(key, coerce(source.opt(key), elementType, arguments[1], depth + 1));
            }
            return result;
        }
        if (Collection.class.isAssignableFrom(targetType)) {
            if (!(value instanceof JSONArray)) {
                throw new IllegalArgumentException("expected a list");
            }
            Class<?> elementType = collectionElementType(genericType);
            if (elementType == null
                    || (!isScalar(elementType) && !hasNoArgumentConstructor(elementType))) {
                throw new IllegalArgumentException("unsupported list element type");
            }
            Collection<Object> result = Set.class.isAssignableFrom(targetType)
                    ? new LinkedHashSet<>()
                    : new ArrayList<>();
            JSONArray source = (JSONArray) value;
            for (int index = 0; index < source.length(); index++) {
                result.add(coerce(source.opt(index), elementType, elementType, depth + 1));
            }
            return result;
        }
        if (value instanceof JSONObject && hasNoArgumentConstructor(targetType)) {
            Object result = newInstance(targetType);
            JSONObject source = (JSONObject) value;
            java.util.Iterator<String> keys = source.keys();
            while (keys.hasNext()) {
                String name = keys.next();
                if (name.startsWith("__")) {
                    continue;
                }
                Field field = findField(targetType, name);
                if (field == null || !isEditable(field)) {
                    throw new IllegalArgumentException("unsupported nested field " + name);
                }
                makeAccessible(field);
                field.set(result,
                        coerce(source.opt(name), field.getType(), field.getGenericType(), depth + 1));
            }
            return result;
        }
        throw new IllegalArgumentException("unsupported field type " + targetType.getName());
    }

    private static String numberText(Object value) {
        if (value instanceof Number) {
            return String.valueOf(value);
        }
        return String.valueOf(value).trim();
    }

    private static boolean isScalar(Class<?> type) {
        return type.isPrimitive()
                || type == Boolean.class
                || type == Byte.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == Character.class
                || type == String.class
                || type == CharSequence.class
                || type.isEnum();
    }

    private static String scalarKind(Class<?> type) {
        if (type == boolean.class || type == Boolean.class) return "BOOLEAN";
        if (type == byte.class || type == Byte.class
                || type == short.class || type == Short.class
                || type == int.class || type == Integer.class) return "INT";
        if (type == long.class || type == Long.class) return "LONG";
        if (type == float.class || type == Float.class) return "FLOAT";
        if (type == double.class || type == Double.class) return "DOUBLE";
        return "STRING";
    }

    private static Class<?> collectionElementType(Type genericType) {
        Type[] arguments = typeArguments(genericType);
        if (arguments.length != 1) {
            return null;
        }
        return classForType(arguments[0]);
    }

    private static Type[] typeArguments(Type genericType) {
        return genericType instanceof ParameterizedType
                ? ((ParameterizedType) genericType).getActualTypeArguments()
                : new Type[0];
    }

    private static Class<?> classForType(Type type) {
        if (type instanceof Class<?>) {
            return (Class<?>) type;
        }
        if (type instanceof ParameterizedType) {
            Type raw = ((ParameterizedType) type).getRawType();
            return raw instanceof Class<?> ? (Class<?>) raw : null;
        }
        return null;
    }

    /**
     * Whether a field is one this knows how to write.
     *
     * <p>This used to take a depth and refuse anything past four. It never recursed, and its one
     * caller passed zero, so the guard could not fire. What recurses is {@link #coerce}, through
     * this method and back, and that is where the bound now lives.
     */
    private static boolean isSupportedType(Class<?> type, Type genericType) {
        if (isScalar(type)) {
            return true;
        }
        if (type.isArray()) {
            Class<?> component = type.getComponentType();
            return isScalar(component) || hasNoArgumentConstructor(component);
        }
        if (Collection.class.isAssignableFrom(type)) {
            Class<?> element = collectionElementType(genericType);
            return element != null && (isScalar(element) || hasNoArgumentConstructor(element));
        }
        if (Map.class.isAssignableFrom(type)) {
            Type[] arguments = typeArguments(genericType);
            if (arguments.length != 2 || classForType(arguments[0]) != String.class) {
                return false;
            }
            Class<?> valueType = classForType(arguments[1]);
            return valueType != null && (isScalar(valueType) || hasNoArgumentConstructor(valueType));
        }
        return hasNoArgumentConstructor(type);
    }

    private static boolean hasNoArgumentConstructor(Class<?> valueClass) {
        try {
            Constructor<?> constructor = valueClass.getDeclaredConstructor();
            makeAccessible(constructor);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Field findField(Class<?> valueClass, String name) {
        for (Class<?> owner = valueClass; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            try {
                return owner.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
            }
        }
        return null;
    }

    private static List<Field> instanceFields(Class<?> valueClass) {
        List<Field> result = new ArrayList<>();
        for (Class<?> owner = valueClass; owner != null && owner != Object.class; owner = owner.getSuperclass()) {
            for (Field field : owner.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !field.isSynthetic()) {
                    result.add(field);
                }
            }
        }
        return result;
    }

    private static List<String> names(JSONObject object) {
        List<String> result = new ArrayList<>();
        java.util.Iterator<String> names = object.keys();
        while (names.hasNext()) {
            result.add(names.next());
        }
        return result;
    }

    private static Class<?> classForName(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        try {
            return Class.forName(name, false, StructuredConfigController.class.getClassLoader());
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings("deprecation")
    private static void makeAccessible(java.lang.reflect.AccessibleObject object) {
        if (!object.isAccessible()) {
            object.setAccessible(true);
        }
    }

    static final class ApplyResult {
        final Object value;
        final boolean applied;
        final int fieldCount;
        final FeatureGateFailure error;

        private ApplyResult(Object value, boolean applied, int fieldCount,
                FeatureGateFailure error) {
            this.value = value;
            this.applied = applied;
            this.fieldCount = fieldCount;
            this.error = error;
        }

        static ApplyResult success(Object value, int fieldCount) {
            return new ApplyResult(value, true, fieldCount, null);
        }

        static ApplyResult failure(Object original, FeatureGateFailure error) {
            return new ApplyResult(original, false, 0, error);
        }
    }
}
