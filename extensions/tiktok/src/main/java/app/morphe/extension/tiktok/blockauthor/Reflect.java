/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import app.morphe.extension.shared.diagnostics.HookStatus;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Small reflection helpers used to read TikTok model objects without compiling against
 * their obfuscated signatures.
 */
public final class Reflect {
    /**
     * Lookups run on every feed bind, and a miss walks the whole class hierarchy throwing
     * once per level, so both hits and misses are remembered per concrete class.
     */
    private static final Object MISSING = new Object();
    private static final Map<String, Object> METHODS = new ConcurrentHashMap<>();
    private static final Map<String, Object> FIELDS = new ConcurrentHashMap<>();

    private Reflect() {
    }

    /** The hook family every {@link #required} lookup belongs to. */
    static final String FAMILY = "feed models";

    /** Every member looked for and not found, first miss first. */
    public static List<String> missingMembers() {
        return HookStatus.missing(FAMILY);
    }

    private static void noteMissing(String family, String kind, Class<?> type, String name) {
        HookStatus.missingMember(family, kind, type.getName(), name);
    }

    private static void noteMissing(String kind, Class<?> type, String name) {
        noteMissing(FAMILY, kind, type, name);
    }

    /** The key both caches and the hook report use, built once per lookup. */
    private static String key(Class<?> type, String name) {
        return type.getName() + '#' + name;
    }

    /**
     * The no-argument method {@code name} declared on {@code type} or a superclass, or inherited
     * from a public interface, or null.
     */
    public static Method method(Class<?> type, String name) {
        String key = key(type, name);
        Object cached = METHODS.get(key);
        if (cached == null) {
            cached = MISSING;
            for (Class<?> current = type; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                try {
                    Method method = current.getDeclaredMethod(name);
                    method.setAccessible(true);
                    cached = method;
                    break;
                } catch (NoSuchMethodException ignored) {
                    // keep climbing
                } catch (Throwable ignored) {
                    break;
                }
            }
            if (cached == MISSING) {
                try {
                    Method method = type.getMethod(name);
                    if (method.getDeclaringClass() != Object.class) {
                        method.setAccessible(true);
                        cached = method;
                    }
                } catch (NoSuchMethodException ignored) {
                    // Public interface methods are the inherited shape the declared walk misses.
                } catch (Throwable ignored) {
                    // An unusable public method is a miss to the caller.
                }
            }
            METHODS.put(key, cached);
        }
        return cached == MISSING ? null : (Method) cached;
    }

    /**
     * The first of {@code names} that {@code type} or a superclass declares, or null.
     *
     * <p>For a model TikTok renamed between builds: list the newer name first, since an older
     * build never declares it and falls through to the name it does have.
     */
    public static Field firstField(Class<?> type, String... names) {
        for (String name : names) {
            Field found = field(type, name);
            if (found != null) return found;
        }
        return null;
    }

    /** The field {@code name} on {@code type} or a superclass, made accessible, or null. */
    public static Field field(Class<?> type, String name) {
        String key = type.getName() + '#' + name;
        Object cached = FIELDS.get(key);
        if (cached == null) {
            cached = MISSING;
            for (Class<?> current = type; current != null && current != Object.class;
                 current = current.getSuperclass()) {
                try {
                    Field field = current.getDeclaredField(name);
                    field.setAccessible(true);
                    cached = field;
                    break;
                } catch (NoSuchFieldException ignored) {
                    // keep climbing
                } catch (Throwable ignored) {
                    break;
                }
            }
            FIELDS.put(key, cached);
        }
        return cached == MISSING ? null : (Field) cached;
    }

    /**
     * Calls a no-argument method the caller has no fallback for, and reports it once if this
     * build does not have it. Returns null both when the member is gone and when it returned
     * null; {@link #missingMembers()} is what tells those apart afterwards.
     */
    public static Object required(Object target, String methodName) {
        return required(target, methodName, FAMILY);
    }

    /** A required no-argument method belonging to the caller's own hook family. */
    public static Object required(Object target, String methodName, String family) {
        if (target == null) {
            return null;
        }
        Method method = method(target.getClass(), methodName);
        if (method == null) {
            noteMissing(family, "method", target.getClass(), methodName);
            return null;
        }
        HookStatus.bound(family, key(target.getClass(), methodName));
        try {
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** A required field belonging to the caller's own hook family. */
    public static Object requiredField(Object target, String fieldName, String family) {
        if (target == null) return null;
        Class<?> type = target.getClass();
        Field field = field(type, fieldName);
        if (field == null) {
            noteMissing(family, "field", type, fieldName);
            return null;
        }
        HookStatus.bound(family, key(type, fieldName));
        try {
            return field.get(target);
        } catch (Throwable ignored) {
            noteMissing(family, "field", type, fieldName);
            return null;
        }
    }

    /** A class whose absence disables the caller's whole hook family. */
    public static Class<?> requiredClass(String className, String family) {
        try {
            Class<?> type = Class.forName(className);
            HookStatus.bound(family, "TikTok#" + className);
            return type;
        } catch (Throwable ignored) {
            HookStatus.missingMember(family, "class", "TikTok", className);
            return null;
        }
    }

    /** A public method whose absence disables the caller's whole hook family. */
    public static Method requiredMethod(
            Class<?> type,
            String methodName,
            String family,
            Class<?>... parameterTypes
    ) {
        if (type == null) return null;
        try {
            Method method = type.getMethod(methodName, parameterTypes);
            method.setAccessible(true);
            HookStatus.bound(family, key(type, methodName));
            return method;
        } catch (Throwable ignored) {
            noteMissing(family, "method", type, methodName);
            return null;
        }
    }

    /** Invokes a required member and reports an unusable member instead of hiding the failure. */
    public static Object invokeRequired(
            Method method,
            Object target,
            String family,
            Object... arguments
    ) {
        if (method == null) return null;
        try {
            return method.invoke(target, arguments);
        } catch (Throwable ignored) {
            noteMissing(family, "call", method.getDeclaringClass(), method.getName());
            return null;
        }
    }

    public static Object invoke(Object target, String methodName) {
        if (target == null) {
            return null;
        }
        Method method = method(target.getClass(), methodName);
        if (method == null) {
            return null;
        }
        try {
            return method.invoke(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    public static Object readField(Object target, String fieldName) {
        if (target == null) {
            return null;
        }
        Field field = field(target.getClass(), fieldName);
        if (field == null) {
            return null;
        }
        try {
            return field.get(target);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Reads a property that may be exposed either as a getter or as a field.
     * Kotlin data classes in the TikTok feed models use both shapes.
     */
    public static Object property(Object target, String getterName, String fieldName) {
        Object value = invoke(target, getterName);
        return value != null ? value : readField(target, fieldName);
    }

    public static String string(Object target, String getterName, String fieldName) {
        Object value = property(target, getterName, fieldName);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    public static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }
}
