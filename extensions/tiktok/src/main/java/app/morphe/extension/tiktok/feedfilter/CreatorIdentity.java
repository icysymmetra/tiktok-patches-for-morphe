/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.feedfilter;

import androidx.annotation.Nullable;

import app.morphe.extension.tiktok.blockauthor.Reflect;

import com.ss.android.ugc.aweme.feed.model.Aweme;

import java.util.Set;

/**
 * Who posted a feed item, read one way for every rule that looks at the creator.
 *
 * <p>The block list, the local hide list and the exception list all ask the same question of
 * an item. One reader means they cannot drift apart: the uid off the author, or off the item
 * when the author is missing (TikTok keeps the two in step), the stable secUid, the handle,
 * and the display name, which only a block pattern may see. The three ids are also carried
 * normalized the way a typed entry is, so a list and an item meet on equal terms.
 */
final class CreatorIdentity {
    @Nullable final String uid;
    @Nullable final String secUid;
    @Nullable final String handle;
    @Nullable final String nickname;
    final String normalizedUid;
    final String normalizedSecUid;
    final String normalizedHandle;

    private CreatorIdentity(@Nullable String uid, @Nullable String secUid,
            @Nullable String handle, @Nullable String nickname) {
        this.uid = uid;
        this.secUid = secUid;
        this.handle = handle;
        this.nickname = nickname;
        normalizedUid = AdvancedFeedRules.normalizedCreator(uid);
        normalizedSecUid = AdvancedFeedRules.normalizedCreator(secUid);
        normalizedHandle = AdvancedFeedRules.normalizedCreator(handle);
    }

    static CreatorIdentity of(Aweme item) {
        Object author = Reflect.property(item, "getAuthor", "author");
        return new CreatorIdentity(uid(item, author),
                Reflect.string(author, "getSecUid", "secUid"),
                Reflect.string(author, "getUniqueId", "uniqueId"),
                Reflect.string(author, "getNickname", "nickname"));
    }

    /** A creator read from somewhere other than a feed item, like a LIVE room's owner. */
    static CreatorIdentity of(@Nullable String uid, @Nullable String secUid,
            @Nullable String handle, @Nullable String nickname) {
        return new CreatorIdentity(uid, secUid, handle, nickname);
    }

    /** The author's uid alone, for a check that needs nothing else. */
    static String uidOf(Aweme item) {
        return uid(item, Reflect.property(item, "getAuthor", "author"));
    }

    private static String uid(Aweme item, Object author) {
        return Reflect.firstNonBlank(
                Reflect.string(author, "getUid", "uid"),
                Reflect.string(item, "getAuthorUid", "authorUid"));
    }

    /** Whether one of the ids is on the list, exactly. The display name never counts. */
    boolean namedIn(Set<String> normalizedNames) {
        return (!normalizedUid.isEmpty() && normalizedNames.contains(normalizedUid))
                || (!normalizedSecUid.isEmpty() && normalizedNames.contains(normalizedSecUid))
                || (!normalizedHandle.isEmpty() && normalizedNames.contains(normalizedHandle));
    }
}
