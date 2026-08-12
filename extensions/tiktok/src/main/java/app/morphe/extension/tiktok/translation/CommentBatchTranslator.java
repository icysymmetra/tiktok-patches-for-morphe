package app.morphe.extension.tiktok.translation;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class CommentBatchTranslator {
    private static final long STALE_ENTRY_MS = 15_000L;
    private static final long LOADED_BATCH_STALE_MS = 60_000L;
    private static final int MAX_LOADED_BATCHES = 4;
    private static final int MAX_REQUESTED_BATCH_KEYS = 12;

    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, VisibleComment> visibleComments = new LinkedHashMap<>();
    private static final LinkedHashMap<String, LoadedBatch> loadedBatches = new LinkedHashMap<>();
    private static final LinkedHashSet<String> requestedLoadedBatchKeys = new LinkedHashSet<>();
    private static LoadedBatch latestLoadedBatch;
    private static WeakReference<Object> lastManager = new WeakReference<>(null);
    private static volatile Object nativeLanguageService;
    private static volatile Method nativeTargetLanguageGetter;
    private static volatile Object nativeLanguageSettings;
    private static volatile Method nativeDoNotTranslateGetter;

    private CommentBatchTranslator() {
    }

    public static void registerCommentCell(View itemView, Object manager) {
        if (!Settings.COMMENT_BATCH_TRANSLATION.get()) return;
        if (itemView == null || manager == null) return;

        try {
            AnchorParts parts = resolveAnchorParts(manager);
            if (parts == null) return;
            Object comment = parts.comment;
            Object context = parts.context;

            String cid = invokeString(comment, "getCid");
            if (isBlank(cid)) return;

            long now = System.currentTimeMillis();
            synchronized (LOCK) {
                pruneLocked(now);
                visibleComments.put(cid, new VisibleComment(manager, comment, context, now));
                lastManager = new WeakReference<>(manager);
            }

            translateLoadedBatchIfReady(manager, false);
            itemView.postDelayed(() -> translateLoadedBatchIfReady(manager, true), 350);
        } catch (Throwable ex) {
            Logger.printDebug(() -> "[Morphe CommentBatchTranslator] register failed", asException(ex));
        }
    }

    public static void onCommentListLoaded(Object commentItemList) {
        if (!Settings.COMMENT_BATCH_TRANSLATION.get()) return;
        if (commentItemList == null) return;

        try {
            Object itemsObject = readField(commentItemList, "items");
            if (!(itemsObject instanceof List)) {
                Logger.printDebug(() -> "[Morphe CommentBatchTranslator] loaded.batch ignored items="
                        + className(itemsObject));
                return;
            }

            List<?> items = (List<?>) itemsObject;
            ArrayList<Object> comments = new ArrayList<>();
            LinkedHashSet<String> cids = new LinkedHashSet<>();
            String aid = null;

            for (Object item : items) {
                if (item == null) continue;

                String cid = invokeStringQuiet(item, "getCid");
                if (isBlank(cid) || cids.contains(cid)) continue;

                String itemAid = invokeStringQuiet(item, "getAwemeId");
                if (isBlank(aid) && !isBlank(itemAid)) {
                    aid = itemAid;
                }

                comments.add(item);
                cids.add(cid);
            }

            if (comments.isEmpty()) {
                Logger.printDebug(() -> "[Morphe CommentBatchTranslator] loaded.batch empty"
                        + " rawSize=" + items.size());
                return;
            }

            LoadedBatch batch = new LoadedBatch(aid, comments, cids, System.currentTimeMillis());
            synchronized (LOCK) {
                pruneLocked(batch.loadedAtMs);
                latestLoadedBatch = batch;
                loadedBatches.put(batch.key(), batch);
                while (loadedBatches.size() > MAX_LOADED_BATCHES) {
                    Iterator<String> iterator = loadedBatches.keySet().iterator();
                    if (!iterator.hasNext()) break;
                    iterator.next();
                    iterator.remove();
                }
            }

            if (BaseSettings.DEBUG.get()) {
                String loadedAid = aid;
                String firstCid = comments.isEmpty() ? null : invokeStringQuiet(comments.get(0), "getCid");
                Logger.printInfo(() -> "[Morphe CommentBatchTranslator] loaded.batch"
                        + " rawSize=" + items.size()
                        + " eligibleSize=" + comments.size()
                        + " aid=" + value(loadedAid)
                        + " firstCid=" + value(firstCid));
            }

            translateLoadedBatchIfReady(lastManager.get(), false);
        } catch (Throwable ex) {
            Logger.printDebug(() -> "[Morphe CommentBatchTranslator] loaded.batch failed", asException(ex));
        }
    }

    public static void onNativeBatchStart(Object comments, Object context, boolean forceWithoutAweme) {
        if (!BaseSettings.DEBUG.get()) return;

        Logger.printInfo(() -> "[Morphe CommentBatchTranslator] native.start"
                + " size=" + collectionSize(comments)
                + " contextAid=" + value(readFieldQuiet(context, "LIZIZ"))
                + " forceWithoutAweme=" + forceWithoutAweme);
    }

    public static void onNativeBatchComplete(Object runner) {
        if (!BaseSettings.DEBUG.get()) return;

        Object results = readFieldQuiet(runner, "l0");
        Object task = readFieldQuiet(runner, "l1");
        Object requested = readFieldQuiet(task, "LIZ");
        Logger.printInfo(() -> "[Morphe CommentBatchTranslator] native.complete"
                + " requestedSize=" + collectionSize(requested)
                + " resultSize=" + collectionSize(results));
    }

    private static void translateLoadedBatchIfReady(Object anchor, boolean allowVisibleFallback) {
        Batch batch = buildLoadedBatch(anchor, allowVisibleFallback);
        if (batch.comments.isEmpty()) {
            return;
        }

        String effectiveRequestKey = batch.requestKey + ":language-policy:" + currentLanguagePolicyKey();

        try {
            synchronized (LOCK) {
                if (requestedLoadedBatchKeys.contains(effectiveRequestKey)) {
                    return;
                }
            }

            Method method = findNativeBatchMethod(batch.nativeManagerClass, batch.context.getClass());
            if (method == null) {
                throw new NoSuchMethodException(
                        batch.nativeManagerClass.getName() + ".LJFF(List, "
                                + batch.context.getClass().getName() + ", boolean)"
                );
            }
            method.setAccessible(true);
            method.invoke(null, batch.comments, batch.context, false);

            synchronized (LOCK) {
                requestedLoadedBatchKeys.add(effectiveRequestKey);
                while (requestedLoadedBatchKeys.size() > MAX_REQUESTED_BATCH_KEYS) {
                    Iterator<String> iterator = requestedLoadedBatchKeys.iterator();
                    if (!iterator.hasNext()) break;
                    iterator.next();
                    iterator.remove();
                }
            }

            Logger.printInfo(() -> "[Morphe CommentBatchTranslator] requested"
                    + " size=" + batch.comments.size()
                    + " requestKey=" + effectiveRequestKey
                    + " aid=" + value(readFieldQuiet(batch.context, "LIZIZ")));
        } catch (Throwable ex) {
            Logger.printException(() -> "[Morphe CommentBatchTranslator] native request failed", ex);
        }
    }

    private static Method findNativeBatchMethod(Class<?> managerClass, Class<?> contextClass) {
        Class<?> current = managerClass;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != void.class) continue;

                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length != 3) continue;
                if (!parameters[0].isAssignableFrom(ArrayList.class)
                        && !parameters[0].isAssignableFrom(List.class)) continue;
                if (!parameters[1].isAssignableFrom(contextClass)) continue;
                if (parameters[2] != boolean.class && parameters[2] != Boolean.class) continue;
                return method;
            }
            current = current.getSuperclass();
        }
        return null;
    }

    private static Batch buildLoadedBatch(Object anchor, boolean allowVisibleFallback) {
        long now = System.currentTimeMillis();
        AnchorParts parts = resolveAnchorParts(anchor);
        Object anchorContext = parts == null ? null : parts.context;
        Object nativeManager = parts == null ? null : parts.nativeManager;
        Object anchorComment = parts == null ? null : parts.comment;
        String anchorAid = invokeStringQuiet(anchorComment, "getAwemeId");
        String anchorCid = invokeStringQuiet(anchorComment, "getCid");
        Set<?> pending = readPendingSet(nativeManager);

        ArrayList<Object> comments = new ArrayList<>();
        Object context = anchorContext;
        Class<?> nativeManagerClass = nativeManager == null ? null : nativeManager.getClass();
        String requestKey = null;

        synchronized (LOCK) {
            pruneLocked(now);
            LoadedBatch loadedBatch = findLoadedBatchLocked(anchorAid, anchorCid);
            if (loadedBatch != null) {
                requestKey = loadedBatch.requestKey();
                for (Object comment : loadedBatch.comments) {
                    if (comment == null) continue;
                    if (!matchesAid(anchorAid, invokeStringQuiet(comment, "getAwemeId"))) continue;
                    if (isTranslated(comment)) continue;
                    if (shouldSkipTranslation(comment)) continue;

                    String cid = invokeStringQuiet(comment, "getCid");
                    if (pending != null && cid != null && pending.contains(cid)) continue;

                    comments.add(comment);
                }
            } else if (allowVisibleFallback) {
                requestKey = buildVisibleFallbackLocked(anchorAid, anchorContext, pending, comments);
            }
        }

        if (context == null || nativeManagerClass == null || requestKey == null) {
            comments.clear();
        }
        return new Batch(comments, context, nativeManagerClass, requestKey);
    }

    private static String buildVisibleFallbackLocked(
            String anchorAid,
            Object anchorContext,
            Set<?> pending,
            ArrayList<Object> comments
    ) {
        if (anchorContext == null) return null;

        LinkedHashSet<String> cids = new LinkedHashSet<>();
        for (VisibleComment entry : visibleComments.values()) {
            Object context = entry.context.get();
            if (context != anchorContext) continue;

            Object comment = entry.comment.get();
            if (comment == null || isTranslated(comment)) continue;
            if (shouldSkipTranslation(comment)) continue;
            if (!matchesAid(anchorAid, invokeStringQuiet(comment, "getAwemeId"))) continue;

            String cid = invokeStringQuiet(comment, "getCid");
            if (isBlank(cid) || cids.contains(cid)) continue;
            if (pending != null && pending.contains(cid)) continue;

            comments.add(comment);
            cids.add(cid);
        }

        if (cids.isEmpty()) return null;

        String firstCid = cids.iterator().next();
        String lastCid = firstCid;
        for (String cid : cids) {
            lastCid = cid;
        }
        String scope = isBlank(anchorAid) ? "context:" + System.identityHashCode(anchorContext) : anchorAid;
        return "visible:" + scope + ":" + comments.size() + ":" + firstCid + ":" + lastCid;
    }

    private static LoadedBatch findLoadedBatchLocked(String anchorAid, String anchorCid) {
        LoadedBatch latest = latestLoadedBatch;
        if (!isBlank(anchorCid)) {
            if (latest != null && latest.cids.contains(anchorCid)) return latest;

            LoadedBatch cidMatchedBatch = null;
            for (LoadedBatch batch : loadedBatches.values()) {
                if (batch.cids.contains(anchorCid)) {
                    cidMatchedBatch = batch;
                }
            }
            if (cidMatchedBatch != null) return cidMatchedBatch;

            return null;
        }

        LoadedBatch loadedBatch = isBlank(anchorAid) ? null : loadedBatches.get(anchorAid);
        if (loadedBatch != null) return loadedBatch;

        if (latest == null) return null;
        if (!isBlank(anchorAid) && sameValue(anchorAid, latest.aid)) return latest;
        return null;
    }

    private static void pruneLocked(long now) {
        Iterator<Map.Entry<String, VisibleComment>> iterator = visibleComments.entrySet().iterator();
        while (iterator.hasNext()) {
            VisibleComment entry = iterator.next().getValue();
            if (now - entry.lastSeenMs > STALE_ENTRY_MS
                    || entry.manager.get() == null
                    || entry.comment.get() == null
                    || entry.context.get() == null) {
                iterator.remove();
            }
        }

        Iterator<Map.Entry<String, LoadedBatch>> batchIterator = loadedBatches.entrySet().iterator();
        while (batchIterator.hasNext()) {
            LoadedBatch entry = batchIterator.next().getValue();
            if (now - entry.loadedAtMs > LOADED_BATCH_STALE_MS) {
                batchIterator.remove();
                if (entry == latestLoadedBatch) latestLoadedBatch = null;
            }
        }
    }

    private static boolean isTranslated(Object comment) {
        String translated = invokeStringQuiet(comment, "isTranslated");
        return "true".equalsIgnoreCase(translated);
    }

    private static boolean shouldSkipTranslation(Object comment) {
        String commentLanguage = primaryLanguageTag(invokeStringQuiet(comment, "getCommentLanguage"));
        if (isBlank(commentLanguage)) return false;

        if (TranslationFilter.isLanguageExcluded(commentLanguage)) return true;

        String targetLanguage = primaryLanguageTag(getNativeTranslationTargetLanguage());
        if (!isBlank(targetLanguage) && commentLanguage.equals(targetLanguage)) return true;

        for (String language : getNativeDoNotTranslateLanguages()) {
            if (commentLanguage.equals(primaryLanguageTag(language))) return true;
        }
        return false;
    }

    private static String currentLanguagePolicyKey() {
        StringBuilder key = new StringBuilder("native-target:")
                .append(value(primaryLanguageTag(getNativeTranslationTargetLanguage())))
                .append(":custom-excluded:")
                .append(value(Settings.COMMENT_TRANSLATION_EXCLUDED_LANGUAGES.get()))
                .append(":dnt");
        for (String language : getNativeDoNotTranslateLanguages()) {
            key.append(':').append(value(primaryLanguageTag(language)));
        }
        return key.toString().toLowerCase(Locale.ROOT);
    }

    private static String getNativeTranslationTargetLanguage() {
        try {
            Object service = nativeLanguageService;
            Method getter = nativeTargetLanguageGetter;
            if (service == null || getter == null) {
                synchronized (LOCK) {
                    service = nativeLanguageService;
                    getter = nativeTargetLanguageGetter;
                    if (service == null || getter == null) {
                        Class<?> serviceClass = Class.forName(
                                "com.ss.android.ugc.aweme.translation.service.TranslationLangKevaServiceImpl"
                        );
                        service = serviceClass.getDeclaredConstructor().newInstance();
                        for (Method candidate : serviceClass.getDeclaredMethods()) {
                            if (candidate.getParameterTypes().length == 0 &&
                                    candidate.getReturnType() == String.class) {
                                candidate.setAccessible(true);
                                getter = candidate;
                                nativeLanguageService = service;
                                nativeTargetLanguageGetter = getter;
                                break;
                            }
                        }
                    }
                }
            }

            if (service != null && getter != null) {
                Object selected = getter.invoke(service);
                if (selected instanceof String && !isBlank((String) selected)) {
                    return (String) selected;
                }
            }
        } catch (Throwable ex) {
            Logger.printDebug(() -> "[Morphe CommentBatchTranslator] native target language unavailable", asException(ex));
        }

        Context context = Utils.getContext();
        if (context != null) {
            Configuration configuration = context.getResources().getConfiguration();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                LocaleList locales = configuration.getLocales();
                if (!locales.isEmpty()) return locales.get(0).toLanguageTag();
            } else if (configuration.locale != null) {
                return configuration.locale.toLanguageTag();
            }
        }
        return Locale.getDefault().toLanguageTag();
    }

    private static String[] getNativeDoNotTranslateLanguages() {
        try {
            Object settings = nativeLanguageSettings;
            Method getter = nativeDoNotTranslateGetter;
            if (settings == null || getter == null) {
                synchronized (LOCK) {
                    settings = nativeLanguageSettings;
                    getter = nativeDoNotTranslateGetter;
                    if (settings == null || getter == null) {
                        Class<?> serviceClass = Class.forName(
                                "com.ss.android.ugc.aweme.translation.service.TranslationLangKevaServiceImpl"
                        );
                        Object service = serviceClass.getDeclaredConstructor().newInstance();
                        for (Method provider : serviceClass.getDeclaredMethods()) {
                            if (provider.getParameterTypes().length != 0 ||
                                    provider.getReturnType() == void.class) continue;
                            try {
                                Method candidate = provider.getReturnType().getMethod(
                                        "getSelectedDoNotTranslateLanguageCodes"
                                );
                                provider.setAccessible(true);
                                Object resolvedSettings = provider.invoke(service);
                                if (resolvedSettings == null) continue;
                                candidate.setAccessible(true);
                                settings = resolvedSettings;
                                getter = candidate;
                                nativeLanguageSettings = settings;
                                nativeDoNotTranslateGetter = getter;
                                break;
                            } catch (NoSuchMethodException ignored) {
                            }
                        }
                    }
                }
            }

            if (settings != null && getter != null) {
                Object value = getter.invoke(settings);
                if (value instanceof String[]) return (String[]) value;
            }
        } catch (Throwable ex) {
            Logger.printDebug(() -> "[Morphe CommentBatchTranslator] native language policy unavailable", asException(ex));
        }
        return new String[0];
    }

    private static String primaryLanguageTag(String language) {
        if (isBlank(language)) return null;

        String normalized = language.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        int separatorIndex = normalized.indexOf('-');
        return separatorIndex > 0 ? normalized.substring(0, separatorIndex) : normalized;
    }

    private static Set<?> readPendingSet(Object nativeManager) {
        if (nativeManager == null) return null;
        try {
            Class<?> current = nativeManager.getClass();
            while (current != null) {
                for (Field field : current.getDeclaredFields()) {
                    if (!Modifier.isStatic(field.getModifiers()) ||
                            !Set.class.isAssignableFrom(field.getType())) continue;
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof Set) return (Set<?>) value;
                }
                current = current.getSuperclass();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static AnchorParts resolveAnchorParts(Object anchor) {
        if (anchor == null) return null;

        try {
            ArrayList<Object> values = readInstanceFieldValues(anchor);
            Object comment = null;
            for (Object value : values) {
                if (value != null && hasNoArgMethod(value.getClass(), "getCid")) {
                    comment = value;
                    break;
                }
            }
            if (comment == null) return null;

            for (Object nativeManager : values) {
                if (nativeManager == null || nativeManager == comment) continue;
                for (Object context : values) {
                    if (context == null || context == comment || context == nativeManager) continue;
                    if (findNativeBatchMethod(nativeManager.getClass(), context.getClass()) != null) {
                        return new AnchorParts(comment, context, nativeManager);
                    }
                }
            }
        } catch (Throwable ex) {
            Logger.printDebug(() -> "[Morphe CommentBatchTranslator] manager resolution failed", asException(ex));
        }
        return null;
    }

    private static ArrayList<Object> readInstanceFieldValues(Object instance) throws IllegalAccessException {
        ArrayList<Object> values = new ArrayList<>();
        Class<?> current = instance.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(instance);
                if (value != null) values.add(value);
            }
            current = current.getSuperclass();
        }
        return values;
    }

    private static boolean hasNoArgMethod(Class<?> type, String name) {
        try {
            type.getMethod(name);
            return true;
        } catch (NoSuchMethodException ignored) {
            return false;
        }
    }

    private static Object readField(Object instance, String name) throws NoSuchFieldException, IllegalAccessException {
        Field field = findField(instance.getClass(), name);
        if (field == null) throw new NoSuchFieldException(name);
        field.setAccessible(true);
        return field.get(instance);
    }

    private static Object readFieldQuiet(Object instance, String name) {
        if (instance == null) return null;
        try {
            return readField(instance, name);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Field findField(Class<?> type, String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static String invokeString(Object instance, String methodName) throws Exception {
        Method method = instance.getClass().getMethod(methodName);
        Object value = method.invoke(instance);
        return value == null ? null : String.valueOf(value);
    }

    private static String invokeStringQuiet(Object instance, String methodName) {
        if (instance == null) return null;
        try {
            return invokeString(instance, methodName);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int collectionSize(Object value) {
        return value instanceof java.util.Collection ? ((java.util.Collection<?>) value).size() : -1;
    }

    private static boolean sameValue(String left, String right) {
        if (left == null) return right == null;
        return left.equals(right);
    }

    private static boolean matchesAid(String anchorAid, String itemAid) {
        return isBlank(anchorAid) || isBlank(itemAid) || sameValue(anchorAid, valueOrNull(itemAid));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static String valueOrNull(String value) {
        return isBlank(value) ? null : value;
    }

    private static String value(Object value) {
        return value == null ? "null" : String.valueOf(value);
    }

    private static String className(Object value) {
        return value == null ? "null" : value.getClass().getName();
    }

    private static Exception asException(Throwable throwable) {
        return throwable instanceof Exception ? (Exception) throwable : new Exception(throwable);
    }

    private static final class VisibleComment {
        final WeakReference<Object> manager;
        final WeakReference<Object> comment;
        final WeakReference<Object> context;
        final long lastSeenMs;

        VisibleComment(Object manager, Object comment, Object context, long lastSeenMs) {
            this.manager = new WeakReference<>(manager);
            this.comment = new WeakReference<>(comment);
            this.context = new WeakReference<>(context);
            this.lastSeenMs = lastSeenMs;
        }
    }

    private static final class AnchorParts {
        final Object comment;
        final Object context;
        final Object nativeManager;

        AnchorParts(Object comment, Object context, Object nativeManager) {
            this.comment = comment;
            this.context = context;
            this.nativeManager = nativeManager;
        }
    }

    private static final class Batch {
        final ArrayList<Object> comments;
        final Object context;
        final Class<?> nativeManagerClass;
        final String requestKey;

        Batch(ArrayList<Object> comments, Object context, Class<?> nativeManagerClass, String requestKey) {
            this.comments = comments;
            this.context = context;
            this.nativeManagerClass = nativeManagerClass;
            this.requestKey = requestKey;
        }
    }

    private static final class LoadedBatch {
        final String aid;
        final ArrayList<Object> comments;
        final LinkedHashSet<String> cids;
        final long loadedAtMs;

        LoadedBatch(String aid, ArrayList<Object> comments, LinkedHashSet<String> cids, long loadedAtMs) {
            this.aid = aid;
            this.comments = comments;
            this.cids = cids;
            this.loadedAtMs = loadedAtMs;
        }

        String key() {
            if (!isBlank(aid)) return aid;
            return cids.isEmpty() ? "unknown" : "cid:" + cids.iterator().next();
        }

        String requestKey() {
            String firstCid = cids.isEmpty() ? "unknown" : cids.iterator().next();
            String lastCid = firstCid;
            for (String cid : cids) {
                lastCid = cid;
            }
            return key() + ":" + comments.size() + ":" + firstCid + ":" + lastCid;
        }
    }
}
