package com.ss.android.ugc.aweme.commentv2.commentlist.viewmodel;

import com.ss.android.ugc.aweme.profile.model.User;

import java.util.ArrayList;
import java.util.List;

/**
 * Test stand-in for TikTok's comment delete model: its user-blocked subscriber and that event,
 * which carries the blocked account in one User field and posts itself.
 */
public class CommentDeleteVM {
    /** The accounts posted as blocked, in order. */
    public static final List<User> POSTED = new ArrayList<>();

    public static final class UserBlocked {
        public User user;

        public UserBlocked post() {
            POSTED.add(user);
            return this;
        }
    }

    public void onUserBlockedEvent(UserBlocked event) {
    }
}
