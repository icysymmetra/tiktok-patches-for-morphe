package app.morphe.extension.tiktok.feedfilter;

import com.ss.android.ugc.aweme.feed.AIGCInfo;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.ModerationAigcInfo;

public final class AiContentFilterHarness {
    public static void main(String[] args) {
        Aweme item = new Aweme();
        equal(0, AiContentFilter.evaluate(item, false, null), "disabled");
        equal(0, AiContentFilter.evaluate(item, true, new AiObservation()), "null models");

        item.aigcInfo = new AIGCInfo(2, true);
        int combined = AiContentFilter.evaluate(item, true, new AiObservation());
        has(combined, AiContentClassifier.TIKTOK_AI_LABEL, "type 2");
        has(combined, AiContentClassifier.CREATED_BY_AI, "createdByAI");

        item = new Aweme();
        item.throwAigcGetter = true;
        item.moderationInfo = new ModerationAigcInfo(3);
        AiObservation getterFailure = new AiObservation();
        int moderation = AiContentFilter.evaluate(item, true, getterFailure);
        has(moderation, AiContentFilter.READ_ERROR, "getter read error");
        has(moderation, AiContentClassifier.MODERATOR_AI_LABEL, "moderation survives getter error");

        item = new Aweme();
        item.aigcInfo = new AIGCInfo(0, true);
        item.aigcInfo.throwLabelGetter = true;
        int labelFailure = AiContentFilter.evaluate(item, true, new AiObservation());
        has(labelFailure, AiContentFilter.READ_ERROR, "label read error");
        has(labelFailure, AiContentClassifier.CREATED_BY_AI, "created survives label error");

        item = new Aweme();
        item.aigcInfo = new AIGCInfo(1, false);
        item.throwModerationGetter = true;
        int moderationFailure = AiContentFilter.evaluate(item, true, new AiObservation());
        has(moderationFailure, AiContentFilter.READ_ERROR, "moderation getter error");
        has(moderationFailure, AiContentClassifier.CREATOR_LABEL, "creator survives moderation error");
        System.out.println("AiContentFilterHarness OK");
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
