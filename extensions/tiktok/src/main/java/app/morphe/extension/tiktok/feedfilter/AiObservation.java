package app.morphe.extension.tiktok.feedfilter;

/** Invocation-owned diagnostic state populated from the same reads used for filtering. */
public final class AiObservation {
    public enum ReadState {
        VALUE,
        NULL_PARENT,
        READ_ERROR,
        NOT_READ
    }

    public ReadState aigcInfoState = ReadState.NOT_READ;
    public ReadState labelTypeState = ReadState.NOT_READ;
    public ReadState createdByAiState = ReadState.NOT_READ;
    public ReadState moderationInfoState = ReadState.NOT_READ;
    public ReadState moderationLabelTypeState = ReadState.NOT_READ;
    public int labelType;
    public boolean createdByAi;
    public int moderationLabelType;
    public int reasonMask;
    public int readErrors;

    void readError() {
        readErrors++;
    }
}
