/*
 * Copyright 2026 Metra contributors
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.adskip;

import android.animation.ValueAnimator;
import android.graphics.drawable.Drawable;
import android.util.Base64;
import android.view.View;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.GZIPInputStream;

/**
 * Drives the 14 KawaiiEmoji Lottie animations inside the skip notice's countdown ring using
 * TikTok's own built-in Lottie engine (no external library overhead).
 *
 * <p>Each composition is parsed off the main thread once and cached in memory, so showing a
 * toast never blocks a frame on JSON parsing or GZIP decompression.
 */
final class KawaiiLottie {
    private static final Object[] COMPOSITIONS = new Object[KawaiiLottieData.COUNT];
    private static final AtomicBoolean WARMING = new AtomicBoolean();
    private static volatile int lastIndex = -1;

    private static volatile boolean resolved;
    private static volatile boolean available;
    private static Method parseStreamSync;
    private static Field resultValueField;
    private static Constructor<?> drawableCtor;
    private static Method setComposition;
    private static Method setProgress;
    private static Method setRepeatCount;

    static final int COUNT = 14;
    static final int[] IDS = {9, 14, 15, 18, 19, 26, 29, 32, 34, 36, 47, 50, 52, 54};
    /** Exact duration of one loop in milliseconds for each of the 14 emojis (frames / 60 fps * 1000). */
    static final long[] CYCLE_MS = {
            3000L, 3000L, 3000L, 3000L, 2883L, 3000L, 3000L,
            3000L, 3000L, 3000L, 3000L, 3000L, 3000L, 3000L
    };
    /** Fixed 5-second visible time for the skip notice and 2 full Lottie cycles inside it. */
    static final long MIN_VISIBLE_MS = 5000L;
    static final int CYCLES_PER_SHOW = 2;

    /** Fixed 5-second visible duration in ms (two full Lottie loops play across the 5 seconds). */
    static long visibleDurationMs(int index) {
        return MIN_VISIBLE_MS;
    }

    /** Picks a new emoji index different from the last one shown, and kicks off background warm-up. */
    static int nextIndex() {
        warmUpAsync();
        int count = KawaiiLottieData.COUNT;
        if (count <= 1) return 0;
        int prev = lastIndex;
        int pick = ThreadLocalRandom.current().nextInt(count - 1);
        if (prev >= 0 && pick >= prev) pick++;
        lastIndex = pick;
        return pick;
    }

    /** Warms up compositions on a background thread so they are ready before the first skip. */
    static void warmUpAsync() {
        if (!WARMING.compareAndSet(false, true)) return;
        Utils.runOnOwnThread("metra-adskip-lottie", () -> {
            try {
                if (!resolveEngine()) return;
                for (int i = 0; i < KawaiiLottieData.COUNT; i++) {
                    loadComposition(i);
                }
            } catch (Throwable failure) {
                Logger.printDebug(() -> "Ad skip: KawaiiLottie warm-up skipped: " + failure);
            }
        });
    }

    /**
     * Creates a ready-to-draw Lottie {@link Drawable} for {@code index}, or requests background
     * loading and attaches it to {@code targetView} as soon as it finishes.
     */
    @Nullable
    static Drawable createDrawable(int index, @Nullable View targetView, @Nullable ReadyCallback callback) {
        if (index < 0 || index >= KawaiiLottieData.COUNT) return null;
        if (!resolveEngine()) return null;
        Object comp = COMPOSITIONS[index];
        if (comp != null) {
            return instantiateDrawable(comp, targetView);
        }
        Utils.runOnBackgroundThread(() -> {
            Object loaded = loadComposition(index);
            if (loaded != null && callback != null) {
                Utils.runOnMainThread(() -> {
                    Drawable d = instantiateDrawable(loaded, targetView);
                    if (d != null) callback.onReady(d);
                });
            }
        });
        return null;
    }

    interface ReadyCallback {
        void onReady(Drawable drawable);
    }

    /** Updates the animation progress [0..1] on the given Lottie drawable. */
    static void setProgress(@Nullable Drawable drawable, float progress) {
        if (drawable == null || setProgress == null) return;
        try {
            float clamped = Math.max(0f, Math.min(1f, progress));
            setProgress.invoke(drawable, clamped);
        } catch (Throwable ignored) {
        }
    }

    /** Decompresses the GZIP+Base64 JSON for {@code index}. Package-private for unit tests. */
    static byte[] decompressJson(int index) throws Exception {
        String b64 = KawaiiLottieData.base64(index);
        if (b64.isEmpty()) throw new IllegalArgumentException("Invalid emoji index " + index);
        byte[] compressed = Base64.decode(b64, Base64.DEFAULT);
        try (GZIPInputStream gis = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(compressed.length * 4);
            byte[] buf = new byte[8192];
            int r;
            while ((r = gis.read(buf)) != -1) out.write(buf, 0, r);
            return out.toByteArray();
        }
    }

    @Nullable
    private static Object loadComposition(int index) {
        Object existing = COMPOSITIONS[index];
        if (existing != null) return existing;
        synchronized (COMPOSITIONS) {
            existing = COMPOSITIONS[index];
            if (existing != null) return existing;
            try {
                String b64 = KawaiiLottieData.base64(index);
                if (b64.isEmpty()) return null;
                byte[] compressed = Base64.decode(b64, Base64.DEFAULT);
                String cacheKey = "metra_kawaii_" + KawaiiLottieData.IDS[index];
                Object lottieResult;
                try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                    lottieResult = parseStreamSync.invoke(null, in, cacheKey);
                }
                if (lottieResult == null) return null;
                Object comp = resultValueField.get(lottieResult);
                if (comp != null) {
                    COMPOSITIONS[index] = comp;
                }
                return comp;
            } catch (Throwable failure) {
                Logger.printDebug(() -> "Ad skip: could not parse KawaiiLottie " + index + ": " + failure);
                return null;
            }
        }
    }

    @Nullable
    private static Drawable instantiateDrawable(Object composition, @Nullable View targetView) {
        try {
            Drawable drawable = (Drawable) drawableCtor.newInstance();
            if (targetView != null) drawable.setCallback(targetView);
            setComposition.invoke(drawable, composition);
            if (setRepeatCount != null) {
                setRepeatCount.invoke(drawable, ValueAnimator.INFINITE);
            }
            return drawable;
        } catch (Throwable failure) {
            Logger.printDebug(() -> "Ad skip: could not create KawaiiLottie drawable: " + failure);
            return null;
        }
    }

    private static boolean resolveEngine() {
        if (resolved) return available;
        synchronized (KawaiiLottie.class) {
            if (resolved) return available;
            try {
                ClassLoader cl = KawaiiLottie.class.getClassLoader();
                // Try TikTok 47.4.3 obfuscated Lottie classes first, then standard un-obfuscated Airbnb Lottie names.
                Class<?> compClass = findClass(cl, "X.00RA", "com.airbnb.lottie.LottieComposition");
                Class<?> factoryClass = findClass(cl, "X.00RW", "com.airbnb.lottie.LottieCompositionFactory");
                Class<?> resultClass = findClass(cl, "X.00RP", "com.airbnb.lottie.LottieResult");
                Class<?> drawableClass = findClass(cl, "X.00RG", "com.airbnb.lottie.LottieDrawable");
                if (compClass == null || factoryClass == null || resultClass == null || drawableClass == null) {
                    resolved = true;
                    available = false;
                    return false;
                }

                // Find static (InputStream, String) -> LottieResult on factoryClass
                for (Method m : factoryClass.getDeclaredMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (java.lang.reflect.Modifier.isStatic(m.getModifiers())
                            && pts.length == 2
                            && pts[0] == InputStream.class
                            && pts[1] == String.class
                            && m.getReturnType() == resultClass) {
                        m.setAccessible(true);
                        parseStreamSync = m;
                        break;
                    }
                }

                // Find value field (Object or LottieComposition) on LottieResult
                for (Field f : resultClass.getDeclaredFields()) {
                    if (!java.lang.reflect.Modifier.isStatic(f.getModifiers())
                            && (f.getType() == Object.class || f.getType() == compClass)) {
                        f.setAccessible(true);
                        resultValueField = f;
                        break;
                    }
                }

                drawableCtor = drawableClass.getDeclaredConstructor();
                drawableCtor.setAccessible(true);

                // Find setComposition(LottieComposition) -> boolean on LottieDrawable
                for (Method m : drawableClass.getDeclaredMethods()) {
                    Class<?>[] pts = m.getParameterTypes();
                    if (pts.length == 1 && pts[0] == compClass && m.getReturnType() == boolean.class) {
                        m.setAccessible(true);
                        setComposition = m;
                        break;
                    }
                }

                // Find setProgress(float) -> void on LottieDrawable.
                // In X.00RG, LJJIII(float) is setProgress; in unobfuscated Lottie it's setProgress(float).
                try {
                    Method m = drawableClass.getDeclaredMethod("setProgress", float.class);
                    m.setAccessible(true);
                    setProgress = m;
                } catch (NoSuchMethodException obfuscated) {
                    Method m = drawableClass.getDeclaredMethod("LJJIII", float.class);
                    m.setAccessible(true);
                    setProgress = m;
                }

                try {
                    Method m = drawableClass.getDeclaredMethod("setRepeatCount", int.class);
                    m.setAccessible(true);
                    setRepeatCount = m;
                } catch (NoSuchMethodException obfuscated) {
                    try {
                        Method m = drawableClass.getDeclaredMethod("LJJIIJ", int.class);
                        m.setAccessible(true);
                        setRepeatCount = m;
                    } catch (NoSuchMethodException ignored) {
                    }
                }

                available = parseStreamSync != null && resultValueField != null
                        && drawableCtor != null && setComposition != null && setProgress != null;
            } catch (Throwable failure) {
                available = false;
            } finally {
                resolved = true;
            }
            return available;
        }
    }

    @Nullable
    private static Class<?> findClass(ClassLoader cl, String... names) {
        for (String name : names) {
            try {
                return Class.forName(name, false, cl);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return null;
    }
}
