package com.ss.android.ugc.aweme.comment.model;

/** Test stand-in for TikTok's comment model: its id and whether the viewer has liked it. */
public class Comment {
    private final String cid;
    private final boolean userDigged;

    public Comment(String cid, boolean userDigged) {
        this.cid = cid;
        this.userDigged = userDigged;
    }

    public String getCid() { return cid; }

    public boolean isUserDigged() { return userDigged; }
}
