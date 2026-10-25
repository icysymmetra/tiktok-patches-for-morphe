/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.comment;

import android.text.Layout;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.method.MovementMethod;
import android.text.style.ClickableSpan;
import android.text.util.Linkify;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.regex.Pattern;

/**
 * Makes a link someone left in a comment tappable.
 *
 * <p>TikTok draws comment text as plain text, so a reader who wants the address in it has to
 * copy the comment and paste it somewhere. Hashtags and mentions are already spans, so the
 * machinery for a tappable piece of a comment is there; a bare address just never gets one.
 *
 * <p>Nothing here looks up a view id. The comment text view is found by what it is showing:
 * the comment this cell was bound to, in a view whose own text carries something that looks
 * like an address. An id would be one more name to chase every time TikTok renames its
 * resources, and this is the same answer on any build.
 *
 * <p>The cheap check comes first and almost every comment fails it, so a scroll pays a regular
 * expression over a short string per cell and nothing else.
 */
public final class CommentLinks {
    private static final String FAMILY = "comment links";

    /**
     * Cheap enough to run per cell. Deliberately looser than what Linkify will accept: this
     * only decides whether looking is worth it, and Linkify decides what is really a link.
     */
    private static final Pattern LOOKS_LIKE_A_LINK =
            Pattern.compile("(?i)(?:\\bhttps?://|\\bwww\\.)\\S");

    /** Short comments are not worth matching a view on; a "hi" would match half the cell. */
    private static final int MIN_MATCHABLE_LENGTH = 4;

    /** Deep enough for a comment cell; the text is never far from the row it is bound to. */
    private static final int MAX_DEPTH = 8;

    private CommentLinks() {
    }

    /** Called from the posted cell bind, with the comment that row is showing. */
    public static void apply(View cell, Object comment) {
        if (cell == null || comment == null) return;
        try {
            if (!Settings.COMMENT_LINKS.get()) return;
            String text = Reflect.string(comment, "getText", "text");
            if (text == null || !LOOKS_LIKE_A_LINK.matcher(text).find()) return;

            TextView view = findCommentTextView(cell, text.trim(), 0);
            if (view == null) {
                // Not a hook status miss. A cell whose text view is not laid out yet, or a
                // comment whose address TikTok has already shortened out of the text, would
                // otherwise mark the whole build broken for good.
                Logger.printDebug(() -> "No comment text view carried the link to make tappable");
                return;
            }

            // Read before Linkify runs. It installs a plain LinkMovementMethod of its own on
            // the way out, so afterwards there is no telling TikTok's from the one it just put
            // there, and the restore below would have nothing to restore to.
            MovementMethod before = view.getMovementMethod();
            boolean wasClickable = view.isClickable();
            boolean wasLongClickable = view.isLongClickable();

            if (!Linkify.addLinks(view, Linkify.WEB_URLS)) return;
            HookStatus.bound(FAMILY, "comment text");
            ensureLinksCanBeTapped(view, before, wasClickable, wasLongClickable);
        } catch (Throwable ex) {
            HookStatus.threw(FAMILY, "linkify", ex);
            Logger.printException(() -> "Could not make a link in a comment tappable", ex);
        }
    }

    /**
     * The view showing this comment, which has to be showing the address as well.
     *
     * <p>Both conditions matter. The username sits in its own view and the comment's own text
     * can be repeated in a reply preview, so the address is what separates the view a reader
     * wants to tap from the ones that merely mention the same words.
     */
    private static TextView findCommentTextView(View view, String comment, int depth) {
        if (view == null || depth > MAX_DEPTH) return null;
        if (view instanceof TextView) {
            CharSequence shown = ((TextView) view).getText();
            if (shown == null) return null;
            String rendered = shown.toString();
            if (!LOOKS_LIKE_A_LINK.matcher(rendered).find()) return null;
            boolean carriesTheComment = comment.length() >= MIN_MATCHABLE_LENGTH
                    ? rendered.contains(comment)
                    : rendered.equals(comment);
            return carriesTheComment ? (TextView) view : null;
        }
        if (!(view instanceof ViewGroup)) return null;
        ViewGroup group = (ViewGroup) view;
        for (int index = 0; index < group.getChildCount(); index++) {
            TextView found = findCommentTextView(group.getChildAt(index), comment, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    /**
     * Gives the view a way to open a link, without taking its ordinary taps away.
     *
     * <p>A view that already had one before Linkify ran is left alone: TikTok's own hashtags
     * and mentions are clickable spans, so a cell that has them is already set up and replacing
     * that would be changing how they behave. A view with none gets one that answers only for a
     * touch that actually landed on a link, and gets its touch flags put back to what they were.
     */
    private static void ensureLinksCanBeTapped(TextView view, MovementMethod before,
            boolean wasClickable, boolean wasLongClickable) {
        if (before instanceof LinkMovementMethod) return;

        view.setMovementMethod(LinkOnlyMovementMethod.INSTANCE);
        // setMovementMethod makes the view clickable, and a clickable view consumes every touch
        // that reaches it whatever its movement method decided. On its own that takes the cell's
        // tap away from every comment that happens to carry an address, which is the reply sheet
        // not opening on exactly the comments this is meant to improve. TextView still asks the
        // movement method before it falls back to the clickable answer, so a tap on a link works
        // either way and a tap anywhere else goes back to the row.
        view.setClickable(wasClickable);
        view.setLongClickable(wasLongClickable);
    }

    /** A {@link LinkMovementMethod} that refuses a touch which did not land on a link. */
    static final class LinkOnlyMovementMethod extends LinkMovementMethod {
        static final LinkOnlyMovementMethod INSTANCE = new LinkOnlyMovementMethod();

        @Override
        public boolean onTouchEvent(TextView widget, Spannable buffer, MotionEvent event) {
            int action = event.getActionMasked();
            if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP)
                    && spanUnder(widget, buffer, event) == null) {
                return false;
            }
            return super.onTouchEvent(widget, buffer, event);
        }

        private static ClickableSpan spanUnder(TextView widget, Spannable buffer,
                MotionEvent event) {
            Layout layout = widget.getLayout();
            if (layout == null) return null;
            int x = (int) event.getX() - widget.getTotalPaddingLeft() + widget.getScrollX();
            int y = (int) event.getY() - widget.getTotalPaddingTop() + widget.getScrollY();
            int line = layout.getLineForVertical(y);
            // getOffsetForHorizontal answers with the nearest offset on the line even for a
            // touch past its end, so a tap in the empty space beside a short line would come
            // back as the last character of a link that is nowhere near the finger.
            if (x < layout.getLineLeft(line) || x > layout.getLineRight(line)) return null;
            int offset = layout.getOffsetForHorizontal(line, x);
            ClickableSpan[] spans = buffer.getSpans(offset, offset, ClickableSpan.class);
            return spans.length == 0 ? null : spans[0];
        }
    }
}
