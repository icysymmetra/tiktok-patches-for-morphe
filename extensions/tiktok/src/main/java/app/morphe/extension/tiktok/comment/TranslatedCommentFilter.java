/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.comment;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.feedfilter.KeywordRules;
import app.morphe.extension.tiktok.settings.Settings;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.WeakHashMap;

/**
 * The comment keyword filter, for the comments TikTok shows translated.
 *
 * <p>{@link CommentTools#onCommentListLoaded} judges a comment's text when its page loads and
 * drops the matches. A translation lands later. TikTok's batch completion writes the translated
 * text into the same {@code text} member the page filter read, marks the comment translated and
 * repaints the bound cell through a LiveData, and nothing judged the new text, so a blocked
 * phrase the original lacked could appear on screen (S22, 2026-09-23: 2 of 14 shown bodies with
 * translation on, 0 of 14 with it off).
 *
 * <p>Two moments cover it, both hooked by Comment tools, which owns the switch. When a text
 * batch completes, and before TikTok applies it (the hook sits at the start of the completion
 * that marks comments translated), each result's translated text is judged against the comment
 * it answers and the cell bound to a blocked one is collapsed. On every later bind, a comment
 * TikTok marks translated is judged by the text the cell will show: {@code text}, which the
 * batch replaced, and {@code translatedText}, which the supplement path fills beside the
 * original. A cell is collapsed by zeroing its layout size and hiding it, and gets both back on
 * the bind that hands it a clean comment, or any bind once the filter is off.
 *
 * <p>Untranslated comments are left to the page filter. They were judged when the page loaded,
 * and re-judging them on every bind would turn a filter that removes rows into one that also
 * hides the rows it could not remove, which is a different feature. A voice comment's translated
 * transcript is left alone for the same reason its original transcript is: the page filter
 * never judged transcripts, and TikTok's audio completion writes neither the comment's text nor
 * its translated flag.
 */
public final class TranslatedCommentFilter {
    private static final String FAMILY = "comment keyword filter";
    private static final int MAX_BOUND_CELLS = 512;

    private static final Object LOCK = new Object();
    /** The cell bound to each comment id, so a completion can reach the cell showing it. */
    private static final LinkedHashMap<String, WeakReference<View>> BOUND = new LinkedHashMap<>();
    /** Which comment id a cell was last bound to, so a recycled cell is not hidden for its old one. */
    private static final WeakHashMap<View, String> CELL_COMMENT = new WeakHashMap<>();
    /** Cells this collapsed, with the width, height and visibility they had. */
    private static final WeakHashMap<View, int[]> COLLAPSED = new WeakHashMap<>();

    private static volatile String cachedRaw;
    private static volatile List<KeywordRules.Rule> cachedRules = Collections.emptyList();
    /**
     * Each member is reported once either way: found, so the Diagnostics row can say the filter
     * reads this build, or missing, so it can name what a host update took. Not per bind or per
     * batch, since those run for every comment on a scrolling list.
     */
    private static volatile boolean reportedTranslatedContent;
    private static volatile boolean reportedTranslated;

    private TranslatedCommentFilter() { }

    /** Whether there is anything to judge: the switch is on and the list names a word. */
    public static boolean active() {
        return Settings.COMMENT_KEYWORD_FILTER.get() && !rules().isEmpty();
    }

    /** Whether some cell is still collapsed, so a bind has one to give back. */
    public static boolean anyCollapsed() {
        synchronized (LOCK) {
            return !COLLAPSED.isEmpty();
        }
    }

    /**
     * A cell was bound to {@code comment}. Remembers the pair, collapses the cell when the
     * comment is shown translated and the translation is blocked, and gives a collapsed cell
     * its size back otherwise, the switch going off included. With the filter off and nothing
     * collapsed there is nothing to do.
     */
    public static void onCellBound(View itemView, Object comment) {
        if (itemView == null || comment == null) return;
        if (!active() && !anyCollapsed()) return;
        try {
            String cid = Reflect.string(comment, "getCid", "cid");
            synchronized (LOCK) {
                if (cid != null) {
                    BOUND.remove(cid);
                    BOUND.put(cid, new WeakReference<>(itemView));
                    Iterator<String> eldest = BOUND.keySet().iterator();
                    while (BOUND.size() > MAX_BOUND_CELLS && eldest.hasNext()) {
                        eldest.next();
                        eldest.remove();
                    }
                    CELL_COMMENT.put(itemView, cid);
                } else {
                    CELL_COMMENT.remove(itemView);
                }
            }
            if (active() && translated(comment) && blocked(comment)) {
                collapse(itemView);
            } else {
                restore(itemView);
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Comment keyword filter failed at a translated cell", ex);
        }
    }

    /**
     * A text batch completed: read the comments it asked about and the results off TikTok's
     * batch runner (see {@code CommentBatchTranslator#completedBatch}) and judge them before
     * TikTok applies them. Hooked by Comment tools at the start of every completion that marks
     * comments translated.
     */
    public static void onTextBatchComplete(Object runner) {
        if (runner == null || !active()) return;
        try {
            Object[] batch = app.morphe.extension.tiktok.translation.CommentBatchTranslator.completedBatch(runner);
            if (batch != null) onTranslationsReady((List<?>) batch[0], batch[1]);
        } catch (Throwable ex) {
            Logger.printException(() -> "Comment keyword filter could not read a finished translation", ex);
        }
    }

    /**
     * A batch completed and TikTok is about to apply it. Judges each result's translated text
     * against the comment it answers and collapses the cell bound to a blocked one.
     *
     * <p>Pairs by position, which is how TikTok's text completion applies them: it skips the
     * whole batch when the counts differ, and so does this, since nothing it skips gets shown.
     */
    public static void onTranslationsReady(List<?> comments, Object results) {
        if (comments == null || comments.isEmpty() || results == null || !active()) return;
        try {
            List<Object> answers = elements(results);
            Object sample = null;
            for (Object answer : answers) {
                if (answer != null) {
                    sample = answer;
                    break;
                }
            }
            if (sample == null) return;
            boolean carries = carriesTranslatedContent(sample);
            if (!reportedTranslatedContent) {
                reportedTranslatedContent = true;
                if (carries) HookStatus.bound(FAMILY, "translation result text");
                else HookStatus.missingMember(FAMILY, "field", sample.getClass().getName(),
                        "translatedContent");
            }
            if (!carries) return;
            if (answers.size() != comments.size()) return;

            List<View> cells = new ArrayList<>();
            for (int index = 0; index < comments.size(); index++) {
                Object comment = comments.get(index);
                if (comment == null) continue;
                String cid = Reflect.string(comment, "getCid", "cid");
                Object answer = answers.get(index);
                String translation = Reflect.string(answer, "getTranslatedContent", "translatedContent");
                if (!KeywordRules.anyMatches(rules(), translation)) continue;
                Logger.printDebug(() -> "Comment filter hides a comment whose translation matches");
                View cell = boundCell(cid);
                if (cell != null) cells.add(cell);
            }
            if (cells.isEmpty()) return;
            Runnable collapseAll = () -> {
                for (View cell : cells) collapse(cell);
            };
            if (Looper.myLooper() == Looper.getMainLooper()) collapseAll.run();
            else new Handler(Looper.getMainLooper()).post(collapseAll);
        } catch (Throwable ex) {
            Logger.printException(() -> "Comment keyword filter failed at a finished translation", ex);
        }
    }

    private static List<KeywordRules.Rule> rules() {
        String raw = Settings.COMMENT_BLOCKED_KEYWORDS.get();
        if (raw == null) raw = "";
        if (!raw.equals(cachedRaw)) {
            cachedRules = KeywordRules.parse(raw);
            cachedRaw = raw;
        }
        return cachedRules;
    }

    /** Whether TikTok marks the comment as showing a translation. A host that lost the member is named once. */
    private static boolean translated(Object comment) {
        Object value = Reflect.property(comment, "isTranslated", "isTranslated");
        boolean readable = value instanceof Boolean;
        if (!reportedTranslated) {
            reportedTranslated = true;
            if (readable) HookStatus.bound(FAMILY, "comment translated flag");
            else HookStatus.missingMember(FAMILY, "method", comment.getClass().getName(), "isTranslated");
        }
        return readable && (Boolean) value;
    }

    /** Whether the text the cell will show for a translated comment holds a blocked word. */
    private static boolean blocked(Object comment) {
        List<KeywordRules.Rule> rules = rules();
        return KeywordRules.anyMatches(rules, Reflect.string(comment, "getText", "text"))
                || KeywordRules.anyMatches(rules, Reflect.string(comment, "getTranslatedText", "translatedText"));
    }

    private static boolean carriesTranslatedContent(Object answer) {
        Class<?> type = answer.getClass();
        return Reflect.method(type, "getTranslatedContent") != null
                || Reflect.field(type, "translatedContent") != null;
    }

    /** The results in their positions, a null one kept in its slot so pairing by position holds. */
    private static List<Object> elements(Object results) {
        List<Object> list = new ArrayList<>();
        if (results instanceof Iterable<?>) {
            for (Object element : (Iterable<?>) results) list.add(element);
        } else if (results.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(results);
            for (int index = 0; index < length; index++) list.add(java.lang.reflect.Array.get(results, index));
        } else {
            list.add(results);
        }
        return list;
    }

    /** The cell bound to {@code cid} now, or null once it was recycled to another comment. */
    private static View boundCell(String cid) {
        if (cid == null) return null;
        synchronized (LOCK) {
            WeakReference<View> reference = BOUND.get(cid);
            View cell = reference == null ? null : reference.get();
            if (cell == null) return null;
            return cid.equals(CELL_COMMENT.get(cell)) ? cell : null;
        }
    }

    private static void collapse(View cell) {
        ViewGroup.LayoutParams params = cell.getLayoutParams();
        synchronized (LOCK) {
            if (COLLAPSED.containsKey(cell)) return;
            COLLAPSED.put(cell, new int[]{
                    params == null ? ViewGroup.LayoutParams.MATCH_PARENT : params.width,
                    params == null ? ViewGroup.LayoutParams.WRAP_CONTENT : params.height,
                    cell.getVisibility()});
        }
        if (params == null) {
            params = new ViewGroup.LayoutParams(0, 0);
        } else {
            params.width = 0;
            params.height = 0;
        }
        cell.setLayoutParams(params);
        cell.setVisibility(View.GONE);
    }

    private static void restore(View cell) {
        int[] saved;
        synchronized (LOCK) {
            saved = COLLAPSED.remove(cell);
        }
        if (saved == null) return;
        ViewGroup.LayoutParams params = cell.getLayoutParams();
        if (params != null) {
            params.width = saved[0];
            params.height = saved[1];
            cell.setLayoutParams(params);
        }
        cell.setVisibility(saved[2]);
    }

    public static void resetForTests() {
        synchronized (LOCK) {
            BOUND.clear();
            CELL_COMMENT.clear();
            COLLAPSED.clear();
        }
        cachedRaw = null;
        cachedRules = Collections.emptyList();
        reportedTranslatedContent = false;
        reportedTranslated = false;
    }
}
