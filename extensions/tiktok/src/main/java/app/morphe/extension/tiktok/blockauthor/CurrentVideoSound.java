/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import app.morphe.extension.tiktok.feedfilter.SoundIdentity;

/**
 * The sound on the video currently on screen, captured alongside its author.
 */
public final class CurrentVideoSound {
    public final String id;
    public final String name;
    public final String author;

    private static volatile CurrentVideoSound current;

    private CurrentVideoSound(String id, String name, String author) {
        this.id = id;
        this.name = name;
        this.author = author;
    }

    /** @return the current sound, or null when the current item has none. */
    public static CurrentVideoSound get() {
        return current;
    }

    /**
     * How the sound is named on screen. Isolated like {@link VideoAuthor#label()}, and for the
     * same two reasons: the sound's author is a creator, and the toast that names them is
     * written to the diagnostic buffer.
     */
    public String label() {
        if (name != null && !name.isEmpty()) {
            return VideoAuthor.isolate(
                    author != null && !author.isEmpty() ? name + " (" + author + ")" : name);
        }
        return VideoAuthor.isolate(id != null ? "sound " + id : "this sound");
    }

    /** The block button needs either an id to record or a name to match on. */
    public boolean isUsable() {
        return (id != null && !id.isEmpty()) || (name != null && !name.isEmpty());
    }

    static void update(Object aweme) {
        Object music = aweme == null ? null : Reflect.property(aweme, "getMusic", "music");
        if (music == null) {
            current = null;
            return;
        }
        current = new CurrentVideoSound(
                SoundIdentity.idOf(music), SoundIdentity.nameOf(music), SoundIdentity.authorOf(music));
    }

    static void clear() {
        current = null;
    }
}
