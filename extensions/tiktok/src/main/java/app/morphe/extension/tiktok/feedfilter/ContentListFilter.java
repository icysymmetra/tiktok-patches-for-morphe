package app.morphe.extension.tiktok.feedfilter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/** Shared, host-testable membership loop for feed-like container lists. */
final class ContentListFilter {
    private static final long CACHE_TTL_MS = 250;
    private static final int CACHE_MAX_LISTS = 256;
    private static final IdentityHashMap<List, ProcessedListState> PROCESSED_LISTS =
        new IdentityHashMap<>();

    private ContentListFilter() {
    }

    @FunctionalInterface
    interface Extractor {
        Object extract(Object container);
    }

    @FunctionalInterface
    interface ContainerPredicate {
        boolean rejects(Object container);
    }

    @FunctionalInterface
    interface NonAiEvaluator {
        String rejectReason(Object item);
    }

    @FunctionalInterface
    interface AiEvaluator {
        int evaluate(Object item, AiObservation observation);
    }

    @FunctionalInterface
    interface PolicyVerifier {
        boolean isUnchanged();
    }

    @FunctionalInterface
    interface RemovalObserver {
        void removed(Object item, String primaryReason, int aiReasonMask, AiObservation observation);
    }

    static final class Request {
        final String policyKey;
        final List list;
        final Extractor extractor;
        final ContainerPredicate nativeAdPredicate;
        final NonAiEvaluator nonAiEvaluator;
        final AiEvaluator aiEvaluator;
        final PolicyVerifier policyVerifier;
        final RemovalObserver removalObserver;
        final boolean nativeAdEnabled;
        final boolean nonAiActive;
        final boolean aiEnabled;
        final boolean allowRecentSkip;
        final boolean diagnostics;
        final long elapsedMs;

        Request(
            String policyKey,
            List list,
            Extractor extractor,
            ContainerPredicate nativeAdPredicate,
            NonAiEvaluator nonAiEvaluator,
            AiEvaluator aiEvaluator,
            PolicyVerifier policyVerifier,
            RemovalObserver removalObserver,
            boolean nativeAdEnabled,
            boolean nonAiActive,
            boolean aiEnabled,
            boolean allowRecentSkip,
            boolean diagnostics,
            long elapsedMs
        ) {
            this.policyKey = policyKey;
            this.list = list;
            this.extractor = extractor;
            this.nativeAdPredicate = nativeAdPredicate;
            this.nonAiEvaluator = nonAiEvaluator;
            this.aiEvaluator = aiEvaluator;
            this.policyVerifier = policyVerifier;
            this.removalObserver = removalObserver;
            this.nativeAdEnabled = nativeAdEnabled;
            this.nonAiActive = nonAiActive;
            this.aiEnabled = aiEnabled;
            this.allowRecentSkip = allowRecentSkip;
            this.diagnostics = diagnostics;
            this.elapsedMs = elapsedMs;
        }
    }

    static final class Outcome {
        final List originalList;
        final List effectiveList;
        final Map<String, Integer> reasonCounts;
        final int inputSize;
        final int rejectedCandidates;
        final int removed;
        final int aiOnlyRemoved;
        final int aiEvaluated;
        final int aiMatched;
        final int aiReadErrors;
        final int creatorLabels;
        final int tiktokLabels;
        final int createdByAi;
        final int moderatorLabels;
        final int unknownLabels;
        final int callbackErrors;
        final boolean diagnosticsEnabled;
        final boolean aiEnabled;
        final boolean nonAiSkipped;
        final boolean staleSnapshot;

        private Outcome(
            List originalList,
            List effectiveList,
            Map<String, Integer> reasonCounts,
            int inputSize,
            int rejectedCandidates,
            int removed,
            int aiOnlyRemoved,
            int aiEvaluated,
            int aiMatched,
            int aiReadErrors,
            int creatorLabels,
            int tiktokLabels,
            int createdByAi,
            int moderatorLabels,
            int unknownLabels,
            int callbackErrors,
            boolean diagnosticsEnabled,
            boolean aiEnabled,
            boolean nonAiSkipped,
            boolean staleSnapshot
        ) {
            this.originalList = originalList;
            this.effectiveList = effectiveList;
            this.reasonCounts = reasonCounts;
            this.inputSize = inputSize;
            this.rejectedCandidates = rejectedCandidates;
            this.removed = removed;
            this.aiOnlyRemoved = aiOnlyRemoved;
            this.aiEvaluated = aiEvaluated;
            this.aiMatched = aiMatched;
            this.aiReadErrors = aiReadErrors;
            this.creatorLabels = creatorLabels;
            this.tiktokLabels = tiktokLabels;
            this.createdByAi = createdByAi;
            this.moderatorLabels = moderatorLabels;
            this.unknownLabels = unknownLabels;
            this.callbackErrors = callbackErrors;
            this.diagnosticsEnabled = diagnosticsEnabled;
            this.aiEnabled = aiEnabled;
            this.nonAiSkipped = nonAiSkipped;
            this.staleSnapshot = staleSnapshot;
        }

        boolean changed() {
            return effectiveList != originalList;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    static Outcome filter(Request request) {
        List list = request.list;
        int inputSize = list == null ? -1 : list.size();
        if (list == null || list.isEmpty()
            || (!request.nativeAdEnabled && !request.nonAiActive && !request.aiEnabled)) {
            return emptyOutcome(list, inputSize, request.diagnostics, request.aiEnabled);
        }

        boolean skipNonAi = request.nonAiActive
            && request.allowRecentSkip
            && recentlyProcessed(list, request.policyKey, request.elapsedMs);
        boolean runNonAi = request.nonAiActive && !skipNonAi;
        List snapshot = new ArrayList(list);
        ArrayList kept = null;
        ArrayList<RemovedRecord> removedRecords = request.removalObserver == null
            ? null
            : new ArrayList<>();
        Map<String, Integer> reasonCounts = request.diagnostics ? new HashMap<>() : null;

        int rejectedCandidates = 0;
        int aiOnlyRemoved = 0;
        int aiEvaluated = 0;
        int aiMatched = 0;
        int aiReadErrors = 0;
        int creatorLabels = 0;
        int tiktokLabels = 0;
        int createdByAi = 0;
        int moderatorLabels = 0;
        int unknownLabels = 0;
        int callbackErrors = 0;

        for (int index = 0; index < snapshot.size(); index++) {
            Object container = snapshot.get(index);
            boolean nativeAdRejected = false;
            if (request.nativeAdEnabled && request.nativeAdPredicate != null) {
                try {
                    nativeAdRejected = request.nativeAdPredicate.rejects(container);
                } catch (RuntimeException | LinkageError error) {
                    callbackErrors++;
                }
            }

            Object item = null;
            try {
                item = request.extractor.extract(container);
            } catch (RuntimeException | LinkageError error) {
                callbackErrors++;
            }

            String nonAiReason = nativeAdRejected ? "AdsFilter" : null;
            if (item != null && runNonAi) {
                try {
                    String evaluatedReason = request.nonAiEvaluator.rejectReason(item);
                    if (nonAiReason == null) nonAiReason = evaluatedReason;
                } catch (RuntimeException | LinkageError error) {
                    callbackErrors++;
                }
            }

            AiObservation observation = request.diagnostics && item != null && request.aiEnabled
                ? new AiObservation()
                : null;
            int aiResult = 0;
            if (item != null && request.aiEnabled) {
                aiEvaluated++;
                try {
                    aiResult = request.aiEvaluator.evaluate(item, observation);
                } catch (RuntimeException | LinkageError error) {
                    aiResult = AiContentFilter.READ_ERROR;
                    if (observation != null) observation.readError();
                }
            }
            int aiReasons = aiResult & AiContentClassifier.REMOVAL_MASK;
            boolean aiRejected = AiContentClassifier.removes(aiReasons);
            if ((aiResult & AiContentFilter.READ_ERROR) != 0) aiReadErrors++;
            if ((aiResult & AiContentClassifier.CREATOR_LABEL) != 0) creatorLabels++;
            if ((aiResult & AiContentClassifier.TIKTOK_AI_LABEL) != 0) tiktokLabels++;
            if ((aiResult & AiContentClassifier.CREATED_BY_AI) != 0) createdByAi++;
            if ((aiResult & AiContentClassifier.MODERATOR_AI_LABEL) != 0) moderatorLabels++;
            if ((aiResult & AiContentClassifier.UNKNOWN_LABEL_TYPE) != 0) unknownLabels++;
            if (aiRejected) aiMatched++;

            boolean rejected = nonAiReason != null || aiRejected;
            if (!rejected) {
                if (kept != null) kept.add(container);
                continue;
            }

            rejectedCandidates++;
            if (nonAiReason == null && aiRejected) aiOnlyRemoved++;
            String primaryReason = nonAiReason == null
                ? AiContentFilter.class.getSimpleName()
                : nonAiReason;
            increment(reasonCounts, primaryReason);
            if (kept == null) {
                kept = new ArrayList(snapshot.size());
                kept.addAll(snapshot.subList(0, index));
            }
            if (removedRecords != null) {
                removedRecords.add(new RemovedRecord(item, primaryReason, aiReasons, observation));
            }
        }

        boolean membershipStable = sameReferences(list, snapshot);
        if (!membershipStable) {
            forget(list);
            return new Outcome(
                list, list, immutableCounts(reasonCounts), inputSize, rejectedCandidates, 0, 0,
                aiEvaluated, aiMatched, aiReadErrors, creatorLabels, tiktokLabels, createdByAi,
                moderatorLabels, unknownLabels, callbackErrors, request.diagnostics,
                request.aiEnabled, skipNonAi, true
            );
        }

        List effective = kept == null ? list : kept;
        int removed = kept == null ? 0 : inputSize - kept.size();
        if (removed > 0) {
            forget(list);
        } else if (runNonAi && callbackErrors == 0 && request.policyVerifier.isUnchanged()) {
            remember(list, request.policyKey, request.elapsedMs);
        }

        if (removed > 0 && removedRecords != null) {
            for (RemovedRecord record : removedRecords) {
                request.removalObserver.removed(
                    record.item,
                    record.primaryReason,
                    record.aiReasonMask,
                    record.observation
                );
            }
        }

        return new Outcome(
            list, effective, immutableCounts(reasonCounts), inputSize, rejectedCandidates, removed,
            aiOnlyRemoved,
            aiEvaluated, aiMatched, aiReadErrors, creatorLabels, tiktokLabels, createdByAi,
            moderatorLabels, unknownLabels, callbackErrors, request.diagnostics,
            request.aiEnabled, skipNonAi, false
        );
    }

    private static Outcome emptyOutcome(
        List list,
        int inputSize,
        boolean diagnosticsEnabled,
        boolean aiEnabled
    ) {
        return new Outcome(
            list, list, Collections.emptyMap(), inputSize, 0, 0, 0, 0, 0, 0,
            0, 0, 0, 0, 0, 0, diagnosticsEnabled, aiEnabled, false, false
        );
    }

    private static Map<String, Integer> immutableCounts(Map<String, Integer> counts) {
        return counts == null || counts.isEmpty()
            ? Collections.emptyMap()
            : Collections.unmodifiableMap(counts);
    }

    private static void increment(Map<String, Integer> counts, String reason) {
        if (counts == null || reason == null) return;
        Integer count = counts.get(reason);
        counts.put(reason, count == null ? 1 : count + 1);
    }

    private static boolean sameReferences(List current, List snapshot) {
        if (current.size() != snapshot.size()) return false;
        for (int index = 0; index < snapshot.size(); index++) {
            if (current.get(index) != snapshot.get(index)) return false;
        }
        return true;
    }

    private static boolean recentlyProcessed(List list, String policyKey, long elapsedMs) {
        synchronized (PROCESSED_LISTS) {
            ProcessedListState state = PROCESSED_LISTS.get(list);
            if (state == null || !state.policyKey.equals(policyKey)) return false;
            long age = elapsedMs - state.processedAtMs;
            return age >= 0 && age < CACHE_TTL_MS && state.matches(list);
        }
    }

    private static void remember(List list, String policyKey, long elapsedMs) {
        synchronized (PROCESSED_LISTS) {
            if (!PROCESSED_LISTS.containsKey(list) && PROCESSED_LISTS.size() >= CACHE_MAX_LISTS) {
                PROCESSED_LISTS.clear();
            }
            PROCESSED_LISTS.put(list, new ProcessedListState(list, policyKey, elapsedMs));
        }
    }

    private static void forget(List list) {
        synchronized (PROCESSED_LISTS) {
            PROCESSED_LISTS.remove(list);
        }
    }

    static void clearCacheForTests() {
        synchronized (PROCESSED_LISTS) {
            PROCESSED_LISTS.clear();
        }
    }

    private static final class RemovedRecord {
        final Object item;
        final String primaryReason;
        final int aiReasonMask;
        final AiObservation observation;

        RemovedRecord(
            Object item,
            String primaryReason,
            int aiReasonMask,
            AiObservation observation
        ) {
            this.item = item;
            this.primaryReason = primaryReason;
            this.aiReasonMask = aiReasonMask;
            this.observation = observation;
        }
    }

    private static final class ProcessedListState {
        final String policyKey;
        final long processedAtMs;
        final int size;
        final Object first;
        final Object middle;
        final Object last;

        ProcessedListState(List list, String policyKey, long processedAtMs) {
            this.policyKey = policyKey;
            this.processedAtMs = processedAtMs;
            this.size = list.size();
            this.first = size == 0 ? null : list.get(0);
            this.middle = size == 0 ? null : list.get(size / 2);
            this.last = size == 0 ? null : list.get(size - 1);
        }

        boolean matches(List list) {
            return list.size() == size
                && (size == 0 || (list.get(0) == first
                    && list.get(size / 2) == middle
                    && list.get(size - 1) == last));
        }
    }
}
