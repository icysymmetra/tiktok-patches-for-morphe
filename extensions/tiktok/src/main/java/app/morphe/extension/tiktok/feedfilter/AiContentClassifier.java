package app.morphe.extension.tiktok.feedfilter;

/** Pure policy for server-provided AI classification fields. */
public final class AiContentClassifier {
    public static final int CREATOR_LABEL = 1;
    public static final int TIKTOK_AI_LABEL = 1 << 1;
    public static final int CREATED_BY_AI = 1 << 2;
    public static final int MODERATOR_AI_LABEL = 1 << 3;
    public static final int UNKNOWN_LABEL_TYPE = 1 << 4;
    public static final int REMOVAL_MASK = CREATOR_LABEL
        | TIKTOK_AI_LABEL
        | CREATED_BY_AI
        | MODERATOR_AI_LABEL;

    private AiContentClassifier() {
    }

    public static int classify(
        int labelType,
        boolean createdByAi,
        boolean moderationInfoPresent,
        int moderationLabelType
    ) {
        int result = 0;
        if (labelType == 1) {
            result |= CREATOR_LABEL;
        } else if (labelType == 2) {
            result |= TIKTOK_AI_LABEL;
        } else if (labelType != 0) {
            result |= UNKNOWN_LABEL_TYPE;
        }
        if (createdByAi) result |= CREATED_BY_AI;
        if (moderationInfoPresent && moderationLabelType > 0) {
            result |= MODERATOR_AI_LABEL;
        }
        return result;
    }

    public static boolean removes(int reasonMask) {
        return (reasonMask & REMOVAL_MASK) != 0;
    }
}
