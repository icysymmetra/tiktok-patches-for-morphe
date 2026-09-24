package app.morphe.extension.tiktok.feedfilter;

import com.ss.android.ugc.aweme.feed.AIGCInfo;
import com.ss.android.ugc.aweme.feed.model.Aweme;
import com.ss.android.ugc.aweme.feed.model.ModerationAigcInfo;

/** Reads the exact target fields and delegates policy to {@link AiContentClassifier}. */
public final class AiContentFilter {
    static final int READ_ERROR = 1 << 30;

    private AiContentFilter() {
    }

    public static int evaluate(Aweme item, boolean enabled, AiObservation observation) {
        if (!enabled || item == null) return 0;

        int labelType = 0;
        boolean createdByAi = false;
        boolean moderationPresent = false;
        int moderationLabelType = 0;
        boolean readError = false;

        AIGCInfo aigcInfo = null;
        try {
            aigcInfo = item.getAigcInfo();
            if (observation != null) {
                observation.aigcInfoState = aigcInfo == null
                    ? AiObservation.ReadState.NULL_PARENT
                    : AiObservation.ReadState.VALUE;
            }
        } catch (RuntimeException | LinkageError error) {
            readError = true;
            if (observation != null) {
                observation.aigcInfoState = AiObservation.ReadState.READ_ERROR;
                observation.readError();
            }
        }

        if (aigcInfo == null) {
            if (observation != null && observation.aigcInfoState != AiObservation.ReadState.READ_ERROR) {
                observation.labelTypeState = AiObservation.ReadState.NULL_PARENT;
                observation.createdByAiState = AiObservation.ReadState.NULL_PARENT;
            }
        } else {
            try {
                labelType = aigcInfo.getAIGCLabelType();
                if (observation != null) observation.labelTypeState = AiObservation.ReadState.VALUE;
            } catch (RuntimeException | LinkageError error) {
                readError = true;
                if (observation != null) {
                    observation.labelTypeState = AiObservation.ReadState.READ_ERROR;
                    observation.readError();
                }
            }
            try {
                createdByAi = aigcInfo.createByAI;
                if (observation != null) observation.createdByAiState = AiObservation.ReadState.VALUE;
            } catch (RuntimeException | LinkageError error) {
                readError = true;
                if (observation != null) {
                    observation.createdByAiState = AiObservation.ReadState.READ_ERROR;
                    observation.readError();
                }
            }
        }

        ModerationAigcInfo moderationInfo = null;
        try {
            moderationInfo = item.getModerationAigcInfo();
            moderationPresent = moderationInfo != null;
            if (observation != null) {
                observation.moderationInfoState = moderationPresent
                    ? AiObservation.ReadState.VALUE
                    : AiObservation.ReadState.NULL_PARENT;
            }
        } catch (RuntimeException | LinkageError error) {
            readError = true;
            if (observation != null) {
                observation.moderationInfoState = AiObservation.ReadState.READ_ERROR;
                observation.readError();
            }
        }

        if (moderationInfo == null) {
            if (observation != null
                && observation.moderationInfoState != AiObservation.ReadState.READ_ERROR) {
                observation.moderationLabelTypeState = AiObservation.ReadState.NULL_PARENT;
            }
        } else {
            try {
                moderationLabelType = moderationInfo.moderationAigcLabelType;
                if (observation != null) {
                    observation.moderationLabelTypeState = AiObservation.ReadState.VALUE;
                }
            } catch (RuntimeException | LinkageError error) {
                readError = true;
                if (observation != null) {
                    observation.moderationLabelTypeState = AiObservation.ReadState.READ_ERROR;
                    observation.readError();
                }
            }
        }

        int reasons = AiContentClassifier.classify(
            labelType,
            createdByAi,
            moderationPresent,
            moderationLabelType
        );
        if (observation != null) {
            observation.labelType = labelType;
            observation.createdByAi = createdByAi;
            observation.moderationLabelType = moderationLabelType;
            observation.reasonMask = reasons;
        }
        return readError ? reasons | READ_ERROR : reasons;
    }
}
