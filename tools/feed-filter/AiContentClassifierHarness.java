package app.morphe.extension.tiktok.feedfilter;

public final class AiContentClassifierHarness {
    public static void main(String[] args) {
        equal(0, classify(0, false, false, 0), "defaults");
        has(classify(1, false, false, 0), AiContentClassifier.CREATOR_LABEL, "creator");
        has(classify(2, false, false, 0), AiContentClassifier.TIKTOK_AI_LABEL, "tiktok");
        has(classify(0, true, false, 0), AiContentClassifier.CREATED_BY_AI, "created");
        has(classify(0, false, true, 1), AiContentClassifier.MODERATOR_AI_LABEL, "moderator");
        equal(0, classify(0, false, true, 0), "moderation zero");
        equal(0, classify(0, false, true, -1), "moderation negative");
        int unknown = classify(7, false, false, 0);
        has(unknown, AiContentClassifier.UNKNOWN_LABEL_TYPE, "unknown diagnostic");
        check(!AiContentClassifier.removes(unknown), "unknown label must not remove");
        int combined = classify(1, true, true, 9);
        has(combined, AiContentClassifier.CREATOR_LABEL, "combined creator");
        has(combined, AiContentClassifier.CREATED_BY_AI, "combined created");
        has(combined, AiContentClassifier.MODERATOR_AI_LABEL, "combined moderator");
        System.out.println("AiContentClassifierHarness OK");
    }

    private static int classify(int label, boolean created, boolean moderation, int moderationLabel) {
        return AiContentClassifier.classify(label, created, moderation, moderationLabel);
    }

    private static void equal(int expected, int actual, String name) {
        check(expected == actual, name + ": expected=" + expected + " actual=" + actual);
    }

    private static void has(int actual, int flag, String name) {
        check((actual & flag) != 0, name + ": missing flag " + flag + " in " + actual);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
