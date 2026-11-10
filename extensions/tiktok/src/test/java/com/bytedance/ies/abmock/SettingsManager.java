package com.bytedance.ies.abmock;

import app.morphe.extension.tiktok.featuregatelab.FeatureGateLabRuntime;

/**
 * TikTok's SettingsManager as far as the Lab's frames go, for tests: the two object getters the
 * patch hooks, with the call into the Lab where the patch puts it, and the getter with a default
 * reaching the one without through a settings cache and a wrapper, two frames, as it does on
 * 46.2.3, 47.0.3 and 47.1.3.
 */
public class SettingsManager {
    /** The getter without a default, static, as the patch finds it. */
    public static Object readWithoutDefault(String key, Class<?> model) {
        Object value = VALUES.get(key);
        return FeatureGateLabRuntime.observeSettingsObjectWithoutDefault(key, model, value);
    }

    /** The getter with a default, on the instance. */
    public Object readWithDefault(String key, Class<?> model, Object fallback) {
        Object value = Cache.read(key, fallback, model, new Wrapper());
        return FeatureGateLabRuntime.observeSettingsObject(key, model, fallback,
                value == null ? fallback : value);
    }

    public static final java.util.Map<String, Object> VALUES = new java.util.HashMap<>();

    interface Callback {
        Object call(Class<?> model, String key, Object fallback);
    }

    /** TikTok's settings cache (X.02ww on 47.0.3). */
    static final class Cache {
        static Object read(String key, Object fallback, Class<?> model, Callback callback) {
            return callback.call(model, key, fallback);
        }
    }

    /** The wrapper the getter with a default builds (X.03jr on 47.0.3). */
    static final class Wrapper implements Callback {
        @Override public Object call(Class<?> model, String key, Object fallback) {
            return readWithoutDefault(key, model);
        }
    }
}
