package app.morphe.extension.tiktok.feedfilter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class FeedListRoutingHarness {
    public static void main(String[] args) {
        testDisabledFastPath();
        testSearchAdGating();
        testOrderingAndImmutableReplacement();
        testAiRunsAcrossCacheHitsAndExpiry();
        testIdentityCache();
        testErrorAndStaleFailOpen();
        testReasonPrecedenceAndAllAi();
        System.out.println("FeedListRoutingHarness OK");
    }

    private static void testDisabledFastPath() {
        ContentListFilter.clearCacheForTests();
        AtomicInteger extracts = new AtomicInteger();
        List<Item> input = new ArrayList<>(List.of(new Item("a")));
        ContentListFilter.Outcome result = run(
            input, value -> { extracts.incrementAndGet(); return value; }, null,
            false, false, false, false, 0, item -> null, (item, observation) -> 0
        );
        same(input, result.effectiveList, "disabled returns original");
        equal(0, extracts.get(), "disabled extraction count");
        check(!result.aiEnabled, "disabled outcome records captured setting");

        ContentListFilter.Outcome emptyEnabled = run(
            Collections.emptyList(), value -> value, null,
            false, false, true, false, 0, item -> null, (item, observation) -> 0
        );
        check(emptyEnabled.aiEnabled, "empty outcome records enabled setting");
        equal(0, emptyEnabled.aiEvaluated, "empty enabled list performs no reads");
    }

    private static void testSearchAdGating() {
        AtomicInteger nativeCalls = new AtomicInteger();
        Wrapper adCard = new Wrapper(null, true);
        List<Wrapper> input = new ArrayList<>(List.of(adCard));
        ContentListFilter.Outcome off = run(
            input, value -> ((Wrapper) value).item,
            value -> { nativeCalls.incrementAndGet(); return ((Wrapper) value).nativeAd; },
            false, false, false, false, 0, item -> null, (item, observation) -> 0
        );
        same(input, off.effectiveList, "ads off retains native ad card");
        equal(0, nativeCalls.get(), "ads off native predicate");

        ContentListFilter.Outcome on = run(
            input, value -> ((Wrapper) value).item,
            value -> { nativeCalls.incrementAndGet(); return ((Wrapper) value).nativeAd; },
            true, false, false, false, 0, item -> null, (item, observation) -> 0
        );
        equal(0, on.effectiveList.size(), "ads on removes card without Aweme");
        equal(0, on.aiOnlyRemoved, "ad removal is not counted as AI-caused");

        Item ai = new Item("ai");
        ai.aiReasons = AiContentClassifier.TIKTOK_AI_LABEL;
        Wrapper aiAdWrapper = new Wrapper(ai, true);
        ContentListFilter.Outcome aiOnly = run(
            new ArrayList<>(List.of(aiAdWrapper)), value -> ((Wrapper) value).item,
            value -> { throw new AssertionError("ad predicate called while ads disabled"); },
            false, false, true, false, 0, item -> null,
            (item, observation) -> ((Item) item).aiReasons
        );
        equal(0, aiOnly.effectiveList.size(), "AI removes wrapped post with ads off");
        equal(1, aiOnly.aiOnlyRemoved, "AI-only removal counted once");
    }

    private static void testOrderingAndImmutableReplacement() {
        Item a = new Item("a");
        Item b = new Item("b");
        b.aiReasons = AiContentClassifier.CREATED_BY_AI;
        Item c = new Item("c");
        List<Item> immutable = List.of(a, b, c);
        ContentListFilter.Outcome outcome = run(
            immutable, value -> value, null, false, false, true, false, 0,
            item -> null, (item, observation) -> ((Item) item).aiReasons
        );
        check(outcome.changed(), "immutable input should be replaced");
        equal(List.of(a, c), outcome.effectiveList, "order and references");
        equal(3, immutable.size(), "original immutable list unchanged");
    }

    private static void testAiRunsAcrossCacheHitsAndExpiry() {
        ContentListFilter.clearCacheForTests();
        Item a = new Item("a");
        Item middle = new Item("middle");
        Item z = new Item("z");
        List<Item> input = new ArrayList<>(List.of(a, middle, z));
        AtomicInteger nonAiCalls = new AtomicInteger();
        ContentListFilter.NonAiEvaluator nonAi = item -> { nonAiCalls.incrementAndGet(); return null; };
        ContentListFilter.AiEvaluator ai = (item, observation) -> ((Item) item).aiReasons;

        run(input, value -> value, null, false, true, true, true, 0, nonAi, ai);
        equal(3, nonAiCalls.get(), "initial non-AI scan");
        run(input, value -> value, null, false, true, true, true, 100, nonAi, ai);
        run(input, value -> value, null, false, true, true, true, 200, nonAi, ai);
        equal(3, nonAiCalls.get(), "AI-only calls do not run non-AI");
        middle.aiReasons = AiContentClassifier.CREATOR_LABEL;
        ContentListFilter.Outcome changed = run(
            input, value -> value, null, false, true, true, true, 201, nonAi, ai
        );
        equal(List.of(a, z), changed.effectiveList, "middle metadata change caught during cache hit");

        ContentListFilter.clearCacheForTests();
        middle.aiReasons = 0;
        nonAiCalls.set(0);
        run(input, value -> value, null, false, true, true, true, 0, nonAi, ai);
        run(input, value -> value, null, false, true, true, true, 100, nonAi, ai);
        run(input, value -> value, null, false, true, true, true, 200, nonAi, ai);
        run(input, value -> value, null, false, true, true, true, 250, nonAi, ai);
        equal(6, nonAiCalls.get(), "250ms expiry measured from real scan");
    }

    private static void testIdentityCache() {
        ContentListFilter.clearCacheForTests();
        Item item = new Item("same");
        CollisionList<Item> first = new CollisionList<>(item);
        CollisionList<Item> second = new CollisionList<>(item);
        AtomicInteger calls = new AtomicInteger();
        ContentListFilter.NonAiEvaluator evaluator = value -> { calls.incrementAndGet(); return null; };
        run(first, value -> value, null, false, true, false, true, 0, evaluator, (v, o) -> 0);
        run(second, value -> value, null, false, true, false, true, 1, evaluator, (v, o) -> 0);
        equal(2, calls.get(), "equal/hash-colliding lists have independent cache state");
    }

    private static void testErrorAndStaleFailOpen() {
        Item item = new Item("error");
        ContentListFilter.Outcome extractorError = run(
            new ArrayList<>(List.of(item)), value -> { throw new LinkageError("fixture"); }, null,
            false, true, true, false, 0, value -> "Reject", (value, observation) -> 1
        );
        equal(1, extractorError.effectiveList.size(), "extractor error keeps wrapper");

        ContentListFilter.Outcome aiError = run(
            new ArrayList<>(List.of(item)), value -> value, null,
            false, false, true, false, 0, value -> null,
            (value, observation) -> AiContentFilter.READ_ERROR
        );
        equal(1, aiError.effectiveList.size(), "AI read error alone keeps item");
        equal(1, aiError.aiReadErrors, "AI error counted");

        List<Item> concurrent = new ArrayList<>(List.of(new Item("a"), new Item("b")));
        AtomicInteger extracts = new AtomicInteger();
        ContentListFilter.Outcome stale = run(
            concurrent,
            value -> {
                if (extracts.incrementAndGet() == 1) concurrent.add(new Item("late"));
                return value;
            },
            null, false, true, false, false, 0, value -> "Reject", (value, observation) -> 0
        );
        check(stale.staleSnapshot, "concurrent mutation detected");
        same(concurrent, stale.effectiveList, "stale replacement abandoned");
    }

    private static void testReasonPrecedenceAndAllAi() {
        Item item = new Item("both");
        item.aiReasons = AiContentClassifier.MODERATOR_AI_LABEL;
        ContentListFilter.Outcome both = run(
            new ArrayList<>(List.of(item)), value -> value, null,
            false, true, true, false, 0, value -> "ExistingFilter",
            (value, observation) -> ((Item) value).aiReasons
        );
        equal(Integer.valueOf(1), both.reasonCounts.get("ExistingFilter"), "non-AI reason precedence");
        equal(1, both.aiMatched, "AI match still counted");
        equal(0, both.aiOnlyRemoved, "overlapping non-AI rejection is not AI-caused removal");
        equal(0, both.effectiveList.size(), "all-AI list becomes empty");
    }

    private static ContentListFilter.Outcome run(
        List list,
        ContentListFilter.Extractor extractor,
        ContentListFilter.ContainerPredicate nativePredicate,
        boolean nativeEnabled,
        boolean nonAiEnabled,
        boolean aiEnabled,
        boolean allowSkip,
        long time,
        ContentListFilter.NonAiEvaluator nonAi,
        ContentListFilter.AiEvaluator ai
    ) {
        return ContentListFilter.filter(new ContentListFilter.Request(
            "policy", list, extractor, nativePredicate, nonAi, ai, () -> true, null,
            nativeEnabled, nonAiEnabled, aiEnabled, allowSkip, true, time
        ));
    }

    private static void equal(Object expected, Object actual, String message) {
        check(expected == null ? actual == null : expected.equals(actual),
            message + ": expected=" + expected + " actual=" + actual);
    }

    private static void same(Object expected, Object actual, String message) {
        check(expected == actual, message);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static final class Item {
        final String name;
        int aiReasons;
        Item(String name) { this.name = name; }
        @Override public String toString() { return name; }
    }

    private static final class Wrapper {
        final Item item;
        final boolean nativeAd;
        Wrapper(Item item, boolean nativeAd) { this.item = item; this.nativeAd = nativeAd; }
    }

    private static final class CollisionList<T> extends ArrayList<T> {
        CollisionList(T item) { super(List.of(item)); }
        @Override public int hashCode() { return 7; }
        @Override public boolean equals(Object other) { return other instanceof CollisionList; }
    }
}
