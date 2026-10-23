/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.settings.L10n;

/** The account that posted the video currently on screen. */
public final class VideoAuthor {
    public final String uid;
    public final String secUid;
    public final String displayName;
    /** Aweme id of the video the author was read from, used only for logging. */
    public final String awemeId;

    public VideoAuthor(String uid, String secUid, String displayName, String awemeId) {
        this.uid = uid;
        this.secUid = secUid;
        this.displayName = displayName;
        this.awemeId = awemeId;
    }

    /**
     * The block endpoint accepts either identifier, but rejects a request carrying
     * neither, so a usable author needs at least one.
     */
    public boolean isUsable() {
        return (uid != null && !uid.isEmpty()) || (secUid != null && !secUid.isEmpty());
    }

    /** Stable creator key used by local filtering when TikTok omits the ordinary uid. */
    public String stableId() {
        if (uid != null && !uid.isEmpty()) return uid;
        return secUid;
    }

    /**
     * How the account is named on screen: in a toast, an undo banner or a failure message.
     *
     * <p>Wrapped in Unicode's first-strong isolate, U+2068 to U+2069. Two things are bought with
     * that. A name in a right-to-left script no longer bends the sentence it sits in, which is the
     * pair's purpose. And every toast is written to the diagnostic buffer as it is shown, so the
     * exported report can tell a creator's name from the words around it in any language, and
     * leave the name out. See {@code DiagnosticRedactor}. Log lines take {@link #reference()}
     * instead.
     */
    public String label() {
        if (displayName != null && !displayName.isEmpty()) {
            return isolate(displayName);
        }
        if (uid != null && !uid.isEmpty()) {
            return isolate(uid);
        }
        if (secUid != null && !secUid.isEmpty()) {
            return isolate(secUid);
        }
        // Nobody to name, so nothing to hide. Left bare, otherwise a report of a block with no
        // identifiers at all reads "Blocked [name omitted]" and says less than the truth. The
        // feed calls the person a creator everywhere else, and the words sit inside a translated
        // sentence, so they are translated too. The activity is asked for rather than the
        // context: the plain lookup logs an error when no context is set, and this can run
        // before the hook sets one.
        return L10n.t(Utils.getActivity(), "this creator");
    }

    /**
     * How the account is named in a log line: a pseudonym rather than the account.
     *
     * <p>The same keyed digest the follow report uses, so one creator reads the same in a block
     * line as in a follow line, cut to twelve characters because the whole thing is sixty-four.
     * Nothing in it names the account, and a report from another phone gives it another value.
     */
    public String reference() {
        String id = stableId();
        if (id == null || id.isEmpty()) return "creator unnamed";
        String pseudonym = app.morphe.extension.tiktok.follow.FollowDiagnostics.pseudonym(id);
        return "creator " + (pseudonym.length() > 12 ? pseudonym.substring(0, 12) : pseudonym);
    }

    /**
     * The isolate pair the report's redactor looks for, around a name with nothing in it
     * that could break the pair.
     *
     * <p>A display name is whatever the account chose, so it can hold a line break, or a
     * copy of these very marks. Either one splits the run the redactor matches and leaves
     * the rest of the name in the report: a name with a break in it is hidden as far as the
     * break and no further, and a name carrying its own closing mark ends the pair early and
     * leaves what follows in the clear. Both are taken out here rather than guessed at by
     * the pattern, so what the pattern relies on, one run with no marks and no breaks inside
     * it, is true before it is relied on. A name is one line on screen anyway.
     */
    static String isolate(String text) {
        StringBuilder flat = new StringBuilder(text.length() + 2);
        flat.append('\u2068');
        for (int at = 0; at < text.length(); at++) {
            char character = text.charAt(at);
            if (character == '\u2068' || character == '\u2069') continue;
            flat.append(character == '\n' || character == '\r' ? ' ' : character);
        }
        return flat.append('\u2069').toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof VideoAuthor)) {
            return false;
        }
        VideoAuthor that = (VideoAuthor) other;
        String identity = stableId();
        return identity != null && identity.equals(that.stableId());
    }

    @Override
    public int hashCode() {
        String identity = stableId();
        return identity != null ? identity.hashCode() : 0;
    }
}
