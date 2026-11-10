/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.extension.tiktok.translation;

import android.content.Context;
import android.content.res.Configuration;
import android.os.Build;
import android.os.LocaleList;
import android.os.SystemClock;
import android.view.View;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class CommentBatchTranslator {
    private static final long STALE_ENTRY_MS = 15_000L;
    private static final long LOADED_BATCH_STALE_MS = 60_000L;
    private static final long PENDING_REQUEST_STALE_MS = 15_000L;
    private static final int MAX_LOADED_BATCHES = 4;
    private static final int MAX_REQUESTED_BATCH_KEYS = 12;
    private static final int MAX_RETIRED_REQUESTS = MAX_REQUESTED_BATCH_KEYS * 2;
    /**
     * A batch that fails is asked for again on the next cell bind, and cells bind many times a
     * second while a comment list scrolls, so a host that is refusing the call was asked over and
     * over for as long as the list was open. Three tries, spaced, and then the batch is left
     * alone until the list reloads.
     */
    private static final long[] RETRY_DELAYS_MS = {2_000L, 8_000L, 30_000L};
    /**
     * One more than the number of waits: three delays sit between four tries. Set to the number
     * of delays instead, the last one was never reached and the class said it waited thirty
     * seconds when it had already given up.
     */
    private static final int MAX_ATTEMPTS = RETRY_DELAYS_MS.length + 1;
    private static final int MAX_RETRY_STATES = MAX_REQUESTED_BATCH_KEYS * 2;

    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, VisibleComment> visibleComments = new LinkedHashMap<>();
    private static final LinkedHashMap<String, LoadedBatch> loadedBatches = new LinkedHashMap<>();
    private static final LinkedHashSet<String> requestedLoadedBatchKeys = new LinkedHashSet<>();
    private static final LinkedHashMap<String, PendingRequest> pendingRequests = new LinkedHashMap<>();
    /**
     * How many requests are still waiting on an answer, published so {@link #onNativeBatchComplete}
     * can tell whether it has anything to do without taking {@link #LOCK}. That hook runs on
     * TikTok's own completion thread for every native batch, including every batch this feature
     * never asked for, so the cheap answer is the one that matters. Every place that changes
     * either map calls {@link #publishOutstandingLocked()} while holding the lock.
     */
    private static volatile int outstandingRequests;
    /** How many completions got past the guard, so a test can show an idle one costs nothing. */
    private static volatile int completionsHandledForTests;
    private static final LinkedHashMap<Long, PendingRequest> retiredRequests = new LinkedHashMap<>();
    private static final LinkedHashMap<String, RetryState> retryStates = new LinkedHashMap<>();
    /** Set when the host stops carrying a results field, which no amount of retrying will fix. */
    private static volatile boolean disabledForSession;
    private static long nextRequestGeneration;
    private static LoadedBatch latestLoadedBatch;
    // Written under LOCK on the bind thread, read without it on the response thread. The
    // volatile is the happens-before edge; the other cross-thread fields here carry one too.
    private static volatile WeakReference<Object> lastManager = new WeakReference<>(null);
    private static volatile Object nativeLanguageService;
    private static volatile Method nativeTargetLanguageGetter;
    private static volatile Object nativeLanguageSettings;
    private static volatile Method nativeDoNotTranslateGetter;
    /**
     * Guards the one attempt made to find TikTok's language service.
     *
     * <p>Deliberately not {@code LOCK}. These lookups run once per comment from
     * {@code shouldSkipTranslation}, and {@code LOCK} is the one TikTok's own thread needs to
     * hand a finished batch back. Building a Keva-backed service while holding it made a thirty
     * comment bind wait on work that has nothing to do with the batch.
     */
    private static final String LANGUAGE_SERVICE_CLASS =
            "com.ss.android.ugc.aweme.translation.service.TranslationLangKevaServiceImpl";
    private static final Object LANGUAGE_LOOKUP_LOCK = new Object();
    /**
     * Whether the lookup has been tried, as opposed to whether it worked.
     *
     * <p>The result used to be remembered only when a member was found, so a build whose service
     * does not have one constructed it again for every comment, forever.
     */
    private static volatile boolean nativeTargetLanguageLookedUp;
    private static volatile boolean nativeDoNotTranslateLookedUp;

    /**
     * The Hook status family for the four places TikTok calls into this.
     *
     * <p>Each of them reads a member of an object this code did not declare, and a build that
     * renames one leaves the switch on with nothing happening behind it, which is the failure
     * the Diagnostics row exists to name. The readers further in do not report: they walk
     * whatever they are handed and take what is there, so a member that is absent is an answer
     * rather than a miss.
     *
     * <p>The cell anchor is the one report that comes from a search rather than a named member,
     * so it is held to a stricter rule: it is reported once, and only while no cell has ever
     * resolved. A single bind that arrives before the manager's fields are set is a race, not a
     * broken build, and a miss is never retracted once the row has it.
     */
    private static final String FAMILY = "comment translation";

    /**
     * What each cell manager class has done, so a miss is judged against that class rather than
     * against the first one that happened to work.
     *
     * <p>Kept per class for two reasons. A build that renames the members on one cell type and
     * not another would otherwise be reported for neither, once any other type resolved. And a
     * single bind that lands before a manager's fields are set is a race, so a class is only
     * reported after it has missed twice running, which a renamed member does immediately and a
     * race never does.
     */
    private static final Map<String, AnchorHistory> CELL_ANCHORS = new HashMap<>();

    /** A cap, because the key is a host class name and this must not grow without bound. */
    private static final int MAX_TRACKED_MANAGERS = 32;

    private static final class AnchorHistory {
        boolean resolved;
        int missesRunning;
        /** The row generation this class was last reported in, or -1. A clear starts a new one. */
        long reportedGeneration = -1;
    }

    private CommentBatchTranslator() {
    }

    public static void registerCommentCell(View itemView, Object manager) {
        if (itemView == null || manager == null) return;
        if (disabledForSession || !Settings.COMMENT_BATCH_TRANSLATION.get()) return;

        try {
            AnchorParts parts = resolveAnchorParts(manager);
            if (parts == null) {
                // Nothing on the cell's manager looks like a comment plus a native translator,
                // so every comment of this kind takes this path and the feature does nothing.
                noteCellAnchorMiss(manager.getClass().getName());
                return;
            }
            noteCellAnchorResolved(manager.getClass().getName());
            HookStatus.bound(FAMILY, "cell anchor");
            Object comment = parts.comment;
            Object context = parts.context;

            String cid = invokeString(comment, "getCid");
            if (isBlank(cid)) return;

            long now = SystemClock.elapsedRealtime();
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

    /**
     * Remembers that a cell of this manager class could not be read, and reports it once the
     * class has missed twice running in the current generation of the Diagnostics row.
     *
     * <p>Not reported on the first miss: the anchor is found by searching the manager's fields,
     * so a bind that arrives before they are set misses on a host that works, and a miss is
     * never retracted once the row has it. Not reported per bind either: this runs for every
     * comment on a scrolling list, and {@code HookStatus} builds a miss's key before it checks
     * whether it already has it.
     */
    private static void noteCellAnchorMiss(String manager) {
        boolean report = false;
        synchronized (CELL_ANCHORS) {
            AnchorHistory history = CELL_ANCHORS.get(manager);
            if (history == null) {
                if (CELL_ANCHORS.size() >= MAX_TRACKED_MANAGERS) return;
                history = new AnchorHistory();
                CELL_ANCHORS.put(manager, history);
            }
            if (history.resolved) return;
            history.missesRunning++;
            long generation = HookStatus.generation();
            if (history.missesRunning >= 2 && history.reportedGeneration != generation) {
                history.reportedGeneration = generation;
                report = true;
            }
        }
        if (report) {
            HookStatus.missingMember(FAMILY, "field", manager, "comment and native translator");
        }
    }

    /** A cell of this manager class was read, so nothing about this class is a host failure. */
    private static void noteCellAnchorResolved(String manager) {
        synchronized (CELL_ANCHORS) {
            AnchorHistory history = CELL_ANCHORS.get(manager);
            if (history == null) {
                if (CELL_ANCHORS.size() >= MAX_TRACKED_MANAGERS) return;
                history = new AnchorHistory();
                CELL_ANCHORS.put(manager, history);
            }
            history.resolved = true;
            history.missesRunning = 0;
        }
    }

    public static void onCommentListLoaded(Object commentItemList) {
        if (!Settings.COMMENT_BATCH_TRANSLATION.get()) return;
        if (commentItemList == null) return;

        try {
            Field itemsField = findField(commentItemList.getClass(), "items");
            if (itemsField == null) {
                HookStatus.missingMember(FAMILY, "field", commentItemList.getClass().getName(),
                        "items");
                Logger.printDebug(() -> "[Morphe CommentBatchTranslator] loaded.batch has no items");
                return;
            }
            itemsField.setAccessible(true);
            Object itemsObject = itemsField.get(commentItemList);
            HookStatus.bound(FAMILY, "comment list items");
            // TikTok 47.0.3 leaves this public field null for a valid empty first page. That is
            // content state, not evidence that the model anchor disappeared.
            if (itemsObject == null) {
                Logger.printDebug(() -> "[Morphe CommentBatchTranslator] loaded.batch empty rawSize=0");
                return;
            }
            if (!(itemsObject instanceof List)) {
                HookStatus.missingMember(FAMILY, "List field", commentItemList.getClass().getName(),
                        "items");
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

            LoadedBatch batch = new LoadedBatch(aid, comments, cids, SystemClock.elapsedRealtime());
            synchronized (LOCK) {
                pruneLocked(batch.loadedAtMs);
                // A list that has loaded again is a fresh ask. Without this the remembered keys
                // were only ever let go by the twelve entry eviction, so a reload landing on the
                // same comments stayed suppressed and the batch was never retried, which is not
                // what "until the list reloads" means.
                forgetRequestsForLocked(batch.key());
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
        // Above the debug gate: this is the only sign that the patch's call site still exists,
        // and a reader reporting a broken build has debug off.
        HookStatus.bound(FAMILY, "native batch start");
        if (!BaseSettings.DEBUG.get()) return;

        Logger.printInfo(() -> "[Morphe CommentBatchTranslator] native.start"
                + " size=" + collectionSize(comments)
                + " contextAid=" + value(readFieldQuiet(context, "LIZIZ"))
                + " forceWithoutAweme=" + forceWithoutAweme);
    }

    public static void onNativeBatchComplete(Object runner) {
        // Injected at index 0 of TikTok's own completion method, so this runs for every native
        // translation batch whether or not the feature asked for one. With the switch off and
        // nothing outstanding there is nothing here to do, and everything below it walks the
        // declared fields of two objects and takes the global lock on TikTok's thread.
        if (!Settings.COMMENT_BATCH_TRANSLATION.get() && outstandingRequests == 0) return;

        completionsHandledForTests++;
        Field resultsField = runner == null ? null : runnerField(runner.getClass(), "l0", true);
        Field taskField = runner == null ? null : runnerField(runner.getClass(), "l1", false);
        if (runner != null && resultsField == null) {
            HookStatus.missingMember(FAMILY, "field", runner.getClass().getName(), "l0");
            // The field holding the results is gone, which is what a host update looks like.
            // Every batch would read as a failure from here on, so the feature stands down for
            // the session instead of asking again three times for every batch on every list.
            disableForSession(runner);
            return;
        }
        if (runner != null) {
            HookStatus.bound(FAMILY, "batch results");
            // The results field is checked above; these two are read quietly further down, and a
            // build that renames either leaves every batch unmatched with the switch still on.
            // Asked of the class rather than the value: a field that is present and null is a
            // moment in a batch's life, a field that is gone is a host update.
            if (taskField == null) {
                HookStatus.missingMember(FAMILY, "field", runner.getClass().getName(), "l1");
            } else {
                HookStatus.bound(FAMILY, "batch task");
            }
        }
        Object results = readFieldQuiet(runner, resultsField);
        Object task = readFieldQuiet(runner, taskField);
        if (task != null) {
            if (findField(task.getClass(), "LIZ") == null) {
                HookStatus.missingMember(FAMILY, "field", task.getClass().getName(), "LIZ");
            } else {
                HookStatus.bound(FAMILY, "requested comments");
            }
        }
        Object requested = readFieldQuiet(task, "LIZ");
        Set<String> requestedCids = commentIds(requested);
        boolean succeeded = results != null && !hasCompletionFailure(runner, task);
        Set<String> translatedCids = commentIds(results);
        synchronized (LOCK) {
            PendingRequest pending = findPendingRequestLocked(requestedCids, requested);
            if (removePendingRequestLocked(pending)) {
                // Nothing readable in the results means the shape is not one this knows how to
                // walk, so the batch is taken at its word rather than retried for comments that
                // may well have come back translated.
                boolean whole = translatedCids.isEmpty() || translatedCids.containsAll(pending.cids);
                if (succeeded && whole) {
                    requestedLoadedBatchKeys.add(pending.key);
                    trimRequestedKeysLocked();
                    retryStates.remove(pending.key);
                } else if (succeeded && shrankLocked(pending, translatedCids)) {
                    // Some of what was asked for came back and the next round will be smaller
                    // for it. The key is built from the whole loaded list, so a batch answered a
                    // few comments at a time keeps the same key round after round, and counting
                    // those rounds as failures abandoned a thirty comment list after three of
                    // them. Starting the count again cannot go on for ever, because a round only
                    // counts as progress when it asked for less than the one before.
                    //
                    // Nothing is removed here on purpose: shrankLocked records how many this
                    // round asked for, and clearing that afterwards left the next round with
                    // nothing to compare against, so every round read as progress again.
                } else {
                    noteAttemptLocked(pending.key, SystemClock.elapsedRealtime());
                }
            }
            pruneLocked(SystemClock.elapsedRealtime());
        }
        if (BaseSettings.DEBUG.get()) {
            Logger.printInfo(() -> "[Morphe CommentBatchTranslator] native.complete"
                    + " requestedSize=" + collectionSize(requested)
                    + " resultSize=" + collectionSize(results)
                    + " succeeded=" + succeeded);
        }
    }

    /**
     * The comments a finished batch asked about and the results it got, as {@code {requested,
     * results}}, read quietly off TikTok's batch runner as a completion hook sees it, or null
     * when the runner does not have that shape. The keyword filter reads a batch through this.
     */
    public static Object[] completedBatch(Object runner) {
        if (runner == null) return null;
        Object results = readFieldQuiet(runner, runnerField(runner.getClass(), "l0", true));
        Object task = readFieldQuiet(runner, runnerField(runner.getClass(), "l1", false));
        Object requested = readFieldQuiet(task, "LIZ");
        return requested instanceof List ? new Object[] {requested, results} : null;
    }

    private static void translateLoadedBatchIfReady(Object anchor, boolean allowVisibleFallback) {
        if (disabledForSession) return;
        if (!Settings.COMMENT_BATCH_TRANSLATION.get()) return;
        Batch batch = buildLoadedBatch(anchor, allowVisibleFallback);
        if (batch.comments.isEmpty()) {
            return;
        }

        String effectiveRequestKey = batch.requestKey + ":language-policy:" + currentLanguagePolicyKey();

        long startedAtMs = SystemClock.elapsedRealtime();
        PendingRequest pending;
        synchronized (LOCK) {
            pruneLocked(startedAtMs);
            if (requestedLoadedBatchKeys.contains(effectiveRequestKey)
                    || pendingRequests.containsKey(effectiveRequestKey)) return;
            RetryState retry = retryStates.get(effectiveRequestKey);
            if (retry != null && startedAtMs < retry.retryAfterMs) return;
            pending = new PendingRequest(
                    effectiveRequestKey,
                    commentIds(batch.comments),
                    startedAtMs,
                    batch.comments,
                    ++nextRequestGeneration);
            pendingRequests.put(effectiveRequestKey, pending);
            publishOutstandingLocked();
        }

        try {
            if (!Settings.COMMENT_BATCH_TRANSLATION.get()) {
                removePendingRequest(effectiveRequestKey, pending);
                return;
            }
            Method method = findNativeBatchMethod(batch.nativeManagerClass, batch.context.getClass());
            if (method == null) {
                throw new NoSuchMethodException(
                        batch.nativeManagerClass.getName() + ".LJFF(List, "
                                + batch.context.getClass().getName() + ", boolean)"
                );
            }
            method.setAccessible(true);
            if (!Settings.COMMENT_BATCH_TRANSLATION.get()) {
                removePendingRequest(effectiveRequestKey, pending);
                return;
            }
            method.invoke(null, batch.comments, batch.context, false);

            Logger.printInfo(() -> "[Morphe CommentBatchTranslator] requested"
                    + " size=" + batch.comments.size()
                    + " requestKey=" + effectiveRequestKey
                    + " aid=" + value(readFieldQuiet(batch.context, "LIZIZ")));
        } catch (Throwable ex) {
            // Same storm as a failed completion, and the usual cause is worse: the host method
            // this looks up is gone, so it will fail on every bind for as long as the list is up.
            removePendingRequest(effectiveRequestKey, pending);
            synchronized (LOCK) {
                noteAttemptLocked(effectiveRequestKey, SystemClock.elapsedRealtime());
            }
            Logger.printException(() -> "[Morphe CommentBatchTranslator] native request failed", ex);
        }
    }

    /**
     * The native batch method per (manager class, context class), the pairs that have none
     * included. Both answers are properties of the two classes, and a comment sheet asks the
     * same question for every cell it binds: forty cells used to walk the manager's declared
     * methods a hundred and twenty times on the thread that binds the list.
     */
    private static final ConcurrentHashMap<String, ResolvedMethod> NATIVE_BATCH_METHODS = new ConcurrentHashMap<>();

    /** How many times the declared methods were actually walked; a test binds ten cells and expects one. */
    static int nativeMethodWalksForTests;

    private static final class ResolvedMethod {
        final Method method;
        ResolvedMethod(Method method) { this.method = method; }
    }

    private static Method findNativeBatchMethod(Class<?> managerClass, Class<?> contextClass) {
        String key = managerClass.getName() + '|' + contextClass.getName();
        ResolvedMethod cached = NATIVE_BATCH_METHODS.get(key);
        if (cached != null) return cached.method;
        Method found = walkForNativeBatchMethod(managerClass, contextClass);
        NATIVE_BATCH_METHODS.put(key, new ResolvedMethod(found));
        return found;
    }

    private static Method walkForNativeBatchMethod(Class<?> managerClass, Class<?> contextClass) {
        nativeMethodWalksForTests++;
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
        long now = SystemClock.elapsedRealtime();
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

    private static void removePendingRequest(String key, PendingRequest expected) {
        synchronized (LOCK) {
            if (pendingRequests.get(key) == expected) {
                pendingRequests.remove(key);
            } else if (expected != null && retiredRequests.get(expected.generation) == expected) {
                retiredRequests.remove(expected.generation);
            }
            publishOutstandingLocked();
        }
    }

    private static boolean removePendingRequestLocked(PendingRequest expected) {
        if (expected == null) return false;
        if (pendingRequests.get(expected.key) == expected) {
            pendingRequests.remove(expected.key);
            publishOutstandingLocked();
            return true;
        }
        if (retiredRequests.get(expected.generation) == expected) {
            retiredRequests.remove(expected.generation);
            publishOutstandingLocked();
            return true;
        }
        return false;
    }

    /** How many completions got past the guard, so a test can show an idle one costs nothing. */
    static int completionsHandledForTests() {
        return completionsHandledForTests;
    }

    /** Call while holding {@link #LOCK}, after anything that changes either request map. */
    private static void publishOutstandingLocked() {
        outstandingRequests = pendingRequests.size() + retiredRequests.size();
    }

    /**
     * Whether this answer actually moved the batch along.
     *
     * <p>Returning a comment without marking it translated leaves it in the next batch, and the
     * key does not change, so clearing the attempt state on any answer at all let a host that
     * does that spin: forty binds, forty requests, no backoff and the same three comments every
     * time. Progress is the batch getting smaller, which can only happen so many times.
     */
    private static boolean shrankLocked(PendingRequest pending, Set<String> translatedCids) {
        if (Collections.disjoint(translatedCids, pending.cids)) return false;
        RetryState retry = retryStates.get(pending.key);
        int asked = pending.cids.size();
        if (retry != null && retry.lastAsked > 0 && asked >= retry.lastAsked) return false;
        RetryState progress = retry == null ? new RetryState() : retry;
        progress.attempts = 0;
        progress.retryAfterMs = 0;
        progress.lastAsked = asked;
        retryStates.put(pending.key, progress);
        return true;
    }

    /** Lets go of everything remembered about one comment list, so a reload starts clean. */
    private static void forgetRequestsForLocked(String batchKey) {
        // Both shapes of key for this list. A request built from what was on screen rather than
        // from the loaded list is prefixed, and forgetting only the plain one left that half
        // suppressed across a reload.
        String prefix = batchKey + ":";
        String visiblePrefix = "visible:" + batchKey + ":";
        // A visible request built without an aid is keyed by the context's identity rather than
        // by any list, so no prefix from this batch can find it. A reload is as good a moment as
        // any to let those go, and leaving them suppressed that half of the path across a reload.
        String contextPrefix = "visible:context:";
        for (Iterator<String> keys = requestedLoadedBatchKeys.iterator(); keys.hasNext(); ) {
            String key = keys.next();
            if (key.startsWith(prefix) || key.startsWith(visiblePrefix)
                    || key.startsWith(contextPrefix)) {
                keys.remove();
            }
        }
        for (Iterator<String> keys = retryStates.keySet().iterator(); keys.hasNext(); ) {
            String key = keys.next();
            if (key.startsWith(prefix) || key.startsWith(visiblePrefix)
                    || key.startsWith(contextPrefix)) {
                keys.remove();
            }
        }
    }

    private static void trimRequestedKeysLocked() {
        while (requestedLoadedBatchKeys.size() > MAX_REQUESTED_BATCH_KEYS) {
            Iterator<String> iterator = requestedLoadedBatchKeys.iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

/**
     * Records one attempt at a batch that got nowhere, and decides when it may be asked for
     * again. A round that translated something is not one of these: it clears the state instead.
     */
    private static void noteAttemptLocked(String key, long now) {
        RetryState retry = retryStates.get(key);
        if (retry == null) {
            retry = new RetryState();
            retryStates.put(key, retry);
        }
        retry.attempts++;
        // Whatever the last round asked for stays recorded, so a round that neither shrank the
        // batch nor succeeded cannot be read as progress by the one after it.
        if (retry.attempts >= MAX_ATTEMPTS) {
            // Out of tries. Remembering the key is what keeps the next bind from asking again.
            retryStates.remove(key);
            requestedLoadedBatchKeys.add(key);
            trimRequestedKeysLocked();
            return;
        }
        retry.retryAfterMs = now + RETRY_DELAYS_MS[retry.attempts - 1];
        while (retryStates.size() > MAX_RETRY_STATES) {
            Iterator<String> iterator = retryStates.keySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
    }

    private static void disableForSession(Object runner) {
        boolean announce = !disabledForSession;
        disabledForSession = true;
        synchronized (LOCK) {
            pendingRequests.clear();
            retiredRequests.clear();
            publishOutstandingLocked();
            retryStates.clear();
        }
        if (announce) {
            Logger.printException(() -> "[Morphe CommentBatchTranslator] "
                    + runner.getClass().getName() + " carries no results field, so comment batch"
                    + " translation is off until TikTok is restarted");
        }
    }

    private static PendingRequest findPendingRequestLocked(
            Set<String> requestedCids,
            Object requestedComments
    ) {
        if (requestedCids.isEmpty()) return null;
        PendingRequest overlapping = null;
        for (PendingRequest pending : allRequestsInGenerationOrder()) {
            if (pending.requestedComments == requestedComments) return pending;
            for (String cid : requestedCids) {
                if (pending.cids.contains(cid)) {
                    if (overlapping == null || pending.generation < overlapping.generation) {
                        overlapping = pending;
                    }
                    break;
                }
            }
        }
        return overlapping;
    }

    private static Set<String> commentIds(Object comments) {
        if (comments == null) return Collections.emptySet();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (comments instanceof Iterable<?>) {
            for (Object comment : (Iterable<?>) comments) addCommentId(ids, comment);
        } else if (comments.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(comments);
            for (int index = 0; index < length; index++) {
                addCommentId(ids, java.lang.reflect.Array.get(comments, index));
            }
        } else {
            addCommentId(ids, comments);
        }
        return ids.isEmpty() ? Collections.emptySet() : Collections.unmodifiableSet(ids);
    }

    private static void addCommentId(Set<String> ids, Object comment) {
        String cid = invokeStringQuiet(comment, "getCid");
        if (!isBlank(cid)) ids.add(cid);
    }

    private static boolean hasCompletionFailure(Object runner, Object task) {
        if (runner == null) return true;
        if (completionFailureField(runner) || completionFailureField(task)) return true;
        return false;
    }

    private static boolean completionFailureField(Object instance) {
        if (instance == null) return false;
        Class<?> current = instance.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                String name = field.getName().toLowerCase(Locale.ROOT);
                if (!name.contains("error") && !name.contains("exception")
                        && !name.contains("failure")) continue;
                try {
                    field.setAccessible(true);
                    Object value = field.get(instance);
                    if (value instanceof Throwable) return true;
                } catch (Throwable ignored) {
                }
            }
            current = current.getSuperclass();
        }
        return false;
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

        Iterator<Map.Entry<String, PendingRequest>> requestIterator = pendingRequests.entrySet().iterator();
        while (requestIterator.hasNext()) {
            PendingRequest entry = requestIterator.next().getValue();
            if (now - entry.startedAtMs > PENDING_REQUEST_STALE_MS) {
                requestIterator.remove();
                retiredRequests.put(entry.generation, entry);
            }
        }
        while (retiredRequests.size() > MAX_RETIRED_REQUESTS) {
            Iterator<Long> retiredIterator = retiredRequests.keySet().iterator();
            if (!retiredIterator.hasNext()) break;
            retiredIterator.next();
            retiredIterator.remove();
        }
        publishOutstandingLocked();
    }

    private static List<PendingRequest> allRequestsInGenerationOrder() {
        ArrayList<PendingRequest> requests = new ArrayList<>(
                retiredRequests.size() + pendingRequests.size());
        requests.addAll(retiredRequests.values());
        requests.addAll(pendingRequests.values());
        Collections.sort(requests, (left, right) -> Long.compare(left.generation, right.generation));
        return requests;
    }

    private static boolean isTranslated(Object comment) {
        String translated = invokeStringQuiet(comment, "isTranslated");
        return "true".equalsIgnoreCase(translated);
    }

    private static boolean shouldSkipTranslation(Object comment) {
        String commentLanguage = primaryLanguageTag(invokeStringQuiet(comment, "getCommentLanguage"));
        if (isBlank(commentLanguage)) return false;

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
                .append(":dnt");
        for (String language : getNativeDoNotTranslateLanguages()) {
            key.append(':').append(value(primaryLanguageTag(language)));
        }
        return key.toString().toLowerCase(Locale.ROOT);
    }

    private static String getNativeTranslationTargetLanguage() {
        try {
            if (!nativeTargetLanguageLookedUp) lookUpTargetLanguageGetter();

            Object service = nativeLanguageService;
            Method getter = nativeTargetLanguageGetter;
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

    /** TikTok's Don't translate list with the languages Hushfeed's own row adds (#121). */
    private static String[] getNativeDoNotTranslateLanguages() {
        return DoNotAutoTranslate.withExcluded(readNativeDoNotTranslateLanguages());
    }

    private static String[] readNativeDoNotTranslateLanguages() {
        try {
            if (!nativeDoNotTranslateLookedUp) lookUpDoNotTranslateGetter();

            Object settings = nativeLanguageSettings;
            Method getter = nativeDoNotTranslateGetter;
            if (settings != null && getter != null) {
                Object value = getter.invoke(settings);
                if (value instanceof String[]) return (String[]) value;
            }
        } catch (Throwable ex) {
            Logger.printDebug(() -> "[Morphe CommentBatchTranslator] native language policy unavailable", asException(ex));
        }
        return new String[0];
    }

    /** Builds the language service once and keeps the no-argument String getter it carries. */
    private static void lookUpTargetLanguageGetter() {
        synchronized (LANGUAGE_LOOKUP_LOCK) {
            if (nativeTargetLanguageLookedUp) return;
            // Set before the attempt, not after it. A build with no such member has to be asked
            // once, not once per comment.
            nativeTargetLanguageLookedUp = true;
            try {
                Class<?> serviceClass = Class.forName(LANGUAGE_SERVICE_CLASS);
                Object service = serviceClass.getDeclaredConstructor().newInstance();
                for (Method candidate : serviceClass.getDeclaredMethods()) {
                    if (candidate.getParameterTypes().length == 0
                            && candidate.getReturnType() == String.class) {
                        candidate.setAccessible(true);
                        nativeLanguageService = service;
                        nativeTargetLanguageGetter = candidate;
                        return;
                    }
                }
                Logger.printDebug(() -> "[Morphe CommentBatchTranslator] "
                        + LANGUAGE_SERVICE_CLASS + " has no target language getter; using the"
                        + " phone's language instead");
            } catch (Throwable ex) {
                Logger.printDebug(() -> "[Morphe CommentBatchTranslator] native target language"
                        + " unavailable", asException(ex));
            }
        }
    }

    /**
     * Builds the language service once and keeps the do-not-translate list it can reach.
     *
     * <p>Only a method whose declared return type has {@code getSelectedDoNotTranslateLanguageCodes}
     * is called, so this asks the host for a settings object rather than calling whatever it
     * finds and seeing what comes back.
     */
    private static void lookUpDoNotTranslateGetter() {
        synchronized (LANGUAGE_LOOKUP_LOCK) {
            if (nativeDoNotTranslateLookedUp) return;
            nativeDoNotTranslateLookedUp = true;
            try {
                Class<?> serviceClass = Class.forName(LANGUAGE_SERVICE_CLASS);
                Object service = serviceClass.getDeclaredConstructor().newInstance();
                for (Method provider : serviceClass.getDeclaredMethods()) {
                    if (provider.getParameterTypes().length != 0
                            || provider.getReturnType() == void.class) continue;
                    Method candidate;
                    try {
                        candidate = provider.getReturnType().getMethod(
                                "getSelectedDoNotTranslateLanguageCodes");
                    } catch (NoSuchMethodException notThisOne) {
                        continue;
                    }
                    provider.setAccessible(true);
                    Object resolvedSettings = provider.invoke(service);
                    if (resolvedSettings == null) continue;
                    candidate.setAccessible(true);
                    nativeLanguageSettings = resolvedSettings;
                    nativeDoNotTranslateGetter = candidate;
                    return;
                }
                Logger.printDebug(() -> "[Morphe CommentBatchTranslator] "
                        + LANGUAGE_SERVICE_CLASS + " carries no do-not-translate list; every"
                        + " language stays translatable");
            } catch (Throwable ex) {
                Logger.printDebug(() -> "[Morphe CommentBatchTranslator] native language policy"
                        + " unavailable", asException(ex));
            }
        }
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

    /**
     * Which fields of a cell manager class held the comment, the native manager and the context
     * the last time the full walk found them. The next cell of that class is three field reads
     * and two cached class questions; the walk runs again only if one of the three is empty or
     * no longer answers, which is how a class that carries two candidates and switches between
     * them is still found.
     */
    private static final ConcurrentHashMap<Class<?>, AnchorShape> ANCHOR_SHAPES = new ConcurrentHashMap<>();

    private static final class AnchorShape {
        final Field comment;
        final Field context;
        final Field nativeManager;
        AnchorShape(Field comment, Field context, Field nativeManager) {
            this.comment = comment;
            this.context = context;
            this.nativeManager = nativeManager;
        }
    }

    private static AnchorParts resolveAnchorParts(Object anchor) {
        if (anchor == null) return null;

        try {
            AnchorShape shape = ANCHOR_SHAPES.get(anchor.getClass());
            if (shape != null) {
                Object comment = shape.comment.get(anchor);
                Object context = shape.context.get(anchor);
                Object nativeManager = shape.nativeManager.get(anchor);
                if (comment != null && context != null && nativeManager != null
                        && hasNoArgMethod(comment.getClass(), "getCid")
                        && findNativeBatchMethod(nativeManager.getClass(), context.getClass()) != null) {
                    return new AnchorParts(comment, context, nativeManager);
                }
            }

            ArrayList<Field> fields = new ArrayList<>();
            ArrayList<Object> values = new ArrayList<>();
            readInstanceFields(anchor, fields, values);
            int commentAt = -1;
            for (int i = 0; i < values.size(); i++) {
                if (hasNoArgMethod(values.get(i).getClass(), "getCid")) {
                    commentAt = i;
                    break;
                }
            }
            if (commentAt < 0) return null;
            Object comment = values.get(commentAt);

            for (int m = 0; m < values.size(); m++) {
                Object nativeManager = values.get(m);
                if (nativeManager == comment) continue;
                for (int c = 0; c < values.size(); c++) {
                    Object context = values.get(c);
                    if (context == comment || context == nativeManager) continue;
                    if (findNativeBatchMethod(nativeManager.getClass(), context.getClass()) != null) {
                        ANCHOR_SHAPES.put(anchor.getClass(),
                                new AnchorShape(fields.get(commentAt), fields.get(c), fields.get(m)));
                        return new AnchorParts(comment, context, nativeManager);
                    }
                }
            }
        } catch (Throwable ex) {
            Logger.printDebug(() -> "[Morphe CommentBatchTranslator] manager resolution failed", asException(ex));
        }
        return null;
    }

    /** Every non-null instance field of the object, superclasses included, with the field that held each value. */
    private static void readInstanceFields(Object instance, ArrayList<Field> fields, ArrayList<Object> values)
            throws IllegalAccessException {
        Class<?> current = instance.getClass();
        while (current != null) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                field.setAccessible(true);
                Object value = field.get(instance);
                if (value != null) {
                    fields.add(field);
                    values.add(value);
                }
            }
            current = current.getSuperclass();
        }
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

    /**
     * The runner's field under the name R8's outlining gave it up to 46.8.3 (`l0` the results,
     * `l1` the task), or, on a build that keeps the runner as a Runnable of its own, the one
     * field of that kind: 46.9.3's carries `LIZ:List` and `LIZIZ:task`. The fallback is taken
     * only when the class holds exactly two instance reference fields with exactly one of them a
     * List, so a runner that merely lost a name is still reported as missing rather than guessed.
     */
    private static Field runnerField(Class<?> type, String outlinedName, boolean list) {
        Field named = findField(type, outlinedName);
        if (named != null) return named;
        List<Field> references = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) || field.getType().isPrimitive()) continue;
                references.add(field);
            }
        }
        if (references.size() != 2) return null;
        Field lists = null;
        Field other = null;
        for (Field field : references) {
            if (List.class.isAssignableFrom(field.getType())) lists = lists == null ? field : null;
            else other = other == null ? field : null;
        }
        if (lists == null || other == null) return null;
        return list ? lists : other;
    }

    private static Object readFieldQuiet(Object instance, Field field) {
        if (instance == null || field == null) return null;
        try {
            field.setAccessible(true);
            return field.get(instance);
        } catch (Throwable ignored) {
            return null;
        }
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

    /** How many times a batch has come back unfinished, and when it may be asked for again. */
    private static final class RetryState {
        int attempts;
        long retryAfterMs;
        /** How many comments the last round asked for, so a round that shrinks is recognisable. */
        int lastAsked;
    }

    private static final class PendingRequest {
        final String key;
        final Set<String> cids;
        final long startedAtMs;
        final Object requestedComments;
        final long generation;

        PendingRequest(
                String key,
                Set<String> cids,
                long startedAtMs,
                Object requestedComments,
                long generation
        ) {
            this.key = key;
            this.cids = cids;
            this.startedAtMs = startedAtMs;
            this.requestedComments = requestedComments;
            this.generation = generation;
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
