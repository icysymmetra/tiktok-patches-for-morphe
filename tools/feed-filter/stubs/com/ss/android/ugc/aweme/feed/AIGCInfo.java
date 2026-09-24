package com.ss.android.ugc.aweme.feed;

public class AIGCInfo {
    public boolean createByAI;
    public int labelType;
    public boolean throwLabelGetter;

    public AIGCInfo(int labelType, boolean createByAI) {
        this.labelType = labelType;
        this.createByAI = createByAI;
    }

    public int getAIGCLabelType() {
        if (throwLabelGetter) throw new LinkageError("label fixture");
        return labelType;
    }
}
