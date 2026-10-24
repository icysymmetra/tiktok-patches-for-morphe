/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.comment;

import android.graphics.PorterDuff;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ImageView;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceIdCache;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorOverlay;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorMessages;
import app.morphe.extension.tiktok.blockauthor.BlockAuthorService;
import app.morphe.extension.tiktok.blockauthor.BlockGlyphDrawable;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.blockauthor.VideoAuthor;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.SignedInUser;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.preference.SettingsUi;
import app.morphe.extension.tiktok.settings.L10n;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Comment list tools: keyword, media and poll filters on loaded comment pages, and TikTok's
 * thumbs down control on each comment repurposed to block the commenter in one tap.
 *
 * Both entry points are called from the same places the comment translation patch hooks:
 * {@code BaseCommentCell} binding a cell, and the comment list response being handled.
 * The {@code Comment} and {@code CommentItemList} models keep their real names on TikTok
 * 46.2.3 ({@code getText}, {@code getUser}, {@code getCid}, {@code getReplyComments}, and
 * the public {@code items} list), so nothing here depends on an obfuscated name.
 *
 * The thumbs down is a RelativeLayout ({@code k2_} on 47.1.4, {@code jlk} on 46.x) holding an
 * icon ({@code mpe} on 47.1.4, {@code m3b} on 46.x) at the
 * right end of the comment's action row; both ids were read off a live comment panel. Only
 * the 47.1.4 names are looked up: an older build's names name other views there. TikTok
 * drives it with a touch listener that can be installed only once per view. A native install
 * hook keeps that listener in a view-owned wrapper, and the posted cell bind switches the
 * wrapper to blocking while the setting is enabled. The icon stays; a tap blocks the commenter
 * (a second tap unblocks), the row dims and the icon tints while the account is blocked, and
 * an undo banner is drawn in the window the comments live in, because the panel is not
 * always in the activity's window and anything added to the activity's content root then
 * sits underneath it.
 */
public final class CommentTools {
    /** The optional suggested-search banner, not Hushfeed's loaded-comment search box. */
    public static boolean shouldHideCommentSearchSuggestions() {
        HookStatus.bound("comment search suggestions", "comment_top banner factory");
        return Settings.HIDE_COMMENT_SEARCH_SUGGESTIONS.get();
    }

    /**
     * The search service's own "no comment suggestions here" answer, which every comment sheet
     * surface for the banner asks before it takes the header: the older header anchor's two
     * checks and the newer top bar's canShow. True closes it for all of them.
     *
     * <p>Answering only at the factory (above) left a blank band over the comment count (#161).
     * The older anchor, in one of its orders, claims the header slot as soon as the check says
     * yes and builds the banner after, so a null banner kept a slot TikTok then reserved 48 dp
     * for. Closed here, nothing claims the slot and the header lays out as on a video with no
     * suggestion.
     */
    public static boolean closeCommentSuggestionGate() {
        HookStatus.bound("comment search suggestions", "suggestion gate");
        return Settings.HIDE_COMMENT_SEARCH_SUGGESTIONS.get();
    }

    /**
     * The brand animation TikTok plays over the comment sheet when a comment matches an
     * advertiser's trigger. TikTok's server sends it as a surprise with the published comment or
     * the comment page, and every way the sheet shows it reads that surprise out of the struct
     * whose constructor calls this. Null leaves them nothing to play: each one checks the
     * surprise for null before it touches it.
     *
     * <p>The same struct carries TikTok's own celebrations, which are no advert and stay. Which
     * is which is decided by where the struct is being built, which the patch says just before
     * each constructor call, on the same thread: see {@link #keepSurprise}.
     */
    public static Object commentSurprise(Object surprise) {
        SurpriseOrigin origin = SURPRISE_ORIGIN.get();
        SURPRISE_ORIGIN.remove();
        if (surprise == null) return null;
        HookStatus.bound(EGGS_FAMILY, "CommentSurpriseStruct constructor");
        if (!Settings.HIDE_COMMENT_EGGS.get()) return surprise;
        // After the switch: a construction no site marks is only worth naming while it matters.
        noteSurpriseOrigin(origin);
        return keepSurprise(origin, surprise) ? surprise : null;
    }

    private static final String EGGS_FAMILY = "comment popup ads";

    /** TikTok's own first-comment celebration: the publish path reports it as first_comment_surprise_trigger. */
    static final int FIRST_COMMENT_SURPRISE = 1;

    /**
     * The comment-page scene a campaign's surprise arrives with. The page is fetched for this
     * default scene or for the author's own first comment (6), TikTok's own celebration. The
     * first-comment milestone (5) is a scene the publish request carries, not a page's, and its
     * celebration comes back with the published comment or through the milestone builder.
     */
    static final int PAGE_SCENE_DEFAULT = 0;

    /** Where a comment surprise struct is being built, said by the patch just before its constructor. */
    static final class SurpriseOrigin {
        static final int PAGE = 1;
        static final int PUBLISH = 2;
        static final int MILESTONE = 3;
        final int path;
        final int scene;

        SurpriseOrigin(int path, int scene) {
            this.path = path;
            this.scene = scene;
        }
    }

    private static final ThreadLocal<SurpriseOrigin> SURPRISE_ORIGIN = new ThreadLocal<>();
    private static volatile boolean notedPageSite;
    private static volatile boolean notedPublishSite;
    private static volatile boolean notedMilestoneSite;
    private static volatile boolean notedUnmarkedSite;

    /** The comment-page loader is about to build a struct from the page it fetched for {@code scene}. */
    public static void surpriseFromPage(int scene) {
        SURPRISE_ORIGIN.set(new SurpriseOrigin(SurpriseOrigin.PAGE, scene));
    }

    /** The publish response is about to build a struct from the surprise sent with a typed comment. */
    public static void surpriseFromPublish() {
        SURPRISE_ORIGIN.set(new SurpriseOrigin(SurpriseOrigin.PUBLISH, 0));
    }

    /** The publish view model is about to replay a cached first-comment surprise as a milestone. */
    public static void surpriseFromMilestone() {
        SURPRISE_ORIGIN.set(new SurpriseOrigin(SurpriseOrigin.MILESTONE, 0));
    }

    /**
     * Whether a surprise is TikTok's own, by where it comes from.
     *
     * <p>On the comment page the scene the page was fetched for says so: a campaign's surprise
     * arrives with the default scene, and TikTok's own celebrations ask for scenes of their own.
     * On the publish path the server's type says so: 1 is the first-comment celebration, and
     * TikTok's own code reads the type of a published surprise only to name its analytics event
     * (first_comment_surprise_trigger for 1, comment_easter_egg_trigger for the rest) and, in
     * the player, to skip type 3 (function_disable). The
     * milestone builder replays a cached first-comment surprise and is TikTok's own. A
     * construction the patch did not mark, which a host update could add, falls back to the
     * content: the type, then the keyword.
     */
    static boolean keepSurprise(SurpriseOrigin origin, Object surprise) {
        if (origin == null) return !isTriggeredSurprise(surprise);
        switch (origin.path) {
            case SurpriseOrigin.PAGE: return origin.scene != PAGE_SCENE_DEFAULT;
            case SurpriseOrigin.PUBLISH: return isFirstCommentSurprise(surprise);
            case SurpriseOrigin.MILESTONE: return true;
            default: return !isTriggeredSurprise(surprise);
        }
    }

    /**
     * Each site is reported once: found, so the Diagnostics row can say the build carries the
     * three marked sites, or, for a construction no site marked, missing, which is what a fourth
     * site added by a host update looks like.
     */
    private static void noteSurpriseOrigin(SurpriseOrigin origin) {
        if (origin == null) {
            if (!notedUnmarkedSite) {
                notedUnmarkedSite = true;
                HookStatus.missingMember(EGGS_FAMILY, "marked site",
                        "com.ss.android.ugc.aweme.comment.model.CommentSurpriseStruct", "constructor caller");
            }
            return;
        }
        switch (origin.path) {
            case SurpriseOrigin.PAGE:
                if (!notedPageSite) {
                    notedPageSite = true;
                    HookStatus.bound(EGGS_FAMILY, "comment page site");
                }
                break;
            case SurpriseOrigin.PUBLISH:
                if (!notedPublishSite) {
                    notedPublishSite = true;
                    HookStatus.bound(EGGS_FAMILY, "publish site");
                }
                break;
            case SurpriseOrigin.MILESTONE:
                if (!notedMilestoneSite) {
                    notedMilestoneSite = true;
                    HookStatus.bound(EGGS_FAMILY, "milestone site");
                }
                break;
            default:
                break;
        }
    }

    static void resetSurpriseOriginForTests() {
        SURPRISE_ORIGIN.remove();
        notedPageSite = false;
        notedPublishSite = false;
        notedMilestoneSite = false;
        notedUnmarkedSite = false;
    }

    /** The first-comment celebration, by the type the server writes on it. */
    static boolean isFirstCommentSurprise(Object surprise) {
        Object type = Reflect.property(surprise, "getSurpriseType", "surpriseType");
        return type instanceof Number && ((Number) type).intValue() == FIRST_COMMENT_SURPRISE;
    }

    /**
     * The content rule, for a construction the patch did not mark: a first-comment type stays,
     * and a surprise that names the keyword it matched goes.
     */
    static boolean isTriggeredSurprise(Object surprise) {
        if (isFirstCommentSurprise(surprise)) return false;
        return Reflect.string(surprise, "getKeyword", "keyword") != null;
    }

    private static final String POLL_HOOK_FAMILY = "comment polls";
    private static final String[] DISLIKE_BUTTON_IDS = {"47.4.3:k11"};

    /** One log line for a cell with no thumbs down, not a verdict on the build. */
    private static boolean warnedNoDislikeControl;
    private static final String[] DISLIKE_ICON_IDS = {"47.4.3:mne"};
    /**
     * Faded enough to read as blocked, still readable. At 0.35 the comment text dropped to about
     * 3:1 on the sheet, which is below the floor for text of that size.
     */
    private static final float BLOCKED_ROW_ALPHA = 0.55f;
    private static final int BLOCKED_TINT = SettingsUi.OVERLAY_ACCENT;

    /** Comment model bound to each cell view. */
    private static final WeakHashMap<View, Object> CELL_COMMENTS = new WeakHashMap<>();

    /**
     * The view owns its wrapper, native listener and saved state. Weak values matter too:
     * TikTok's listener owns its row, so a strong map value would retain its own weak key.
     */
    private static final WeakHashMap<View, WeakReference<ControlTouchListener>> CONTROL_TOUCHES =
            new WeakHashMap<>();

    /** The values the takeover overwrites. One instance is shared by a row's button and icon. */
    private static final class ControlState {
        final View button;
        final View icon;
        final View cell;
        final boolean buttonClickable;
        final CharSequence description;
        final CharSequence stateDescription;
        final int iconImportance;
        final android.graphics.ColorFilter iconFilter;
        final android.graphics.drawable.Drawable iconDrawable;
        final float cellAlpha;

        ControlState(View button, View icon, View cell) {
            this.button = button;
            this.icon = icon;
            this.cell = cell;
            this.buttonClickable = button != null && button.isClickable();
            this.description = button == null ? null : button.getContentDescription();
            this.stateDescription = button != null && android.os.Build.VERSION.SDK_INT >= 30
                    ? button.getStateDescription() : null;
            this.iconImportance = icon == null
                    ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : icon.getImportantForAccessibility();
            this.iconFilter = icon instanceof ImageView ? ((ImageView) icon).getColorFilter() : null;
            this.iconDrawable = icon instanceof ImageView ? ((ImageView) icon).getDrawable() : null;
            this.cellAlpha = cell == null ? 1f : cell.getAlpha();
        }
    }

    /**
     * Accounts blocked this session, so a recycled cell shows the right state. Kept per signed-in
     * account: a commenter one account blocked showed as blocked to the next account signed in,
     * and the first tap there sent that account an unblock.
     */
    private static final Set<String> BLOCKED_UIDS = Collections.synchronizedSet(new HashSet<>());

    private static final ResourceIdCache RESOURCE_IDS = new ResourceIdCache();
    private static final DislikeTouchListener DISLIKE_TOUCH = new DislikeTouchListener();

    /**
     * Block and unblock requests on their way, by commenter and signed-in account. One flag for
     * every commenter dropped the banner's Undo while another commenter's request was pending,
     * after the banner had already gone.
     */
    private static final Set<String> IN_FLIGHT = Collections.synchronizedSet(new HashSet<>());

    private CommentTools() {
    }

    /**
     * Called as a comment cell is bound. {@code manager} is the cell's state holder, whose
     * fields include the bound {@code Comment}.
     */
    public static void registerCommentCell(View itemView, Object manager) {
        if (itemView == null || manager == null) {
            return;
        }
        // Before the switches below: a sheet is open whichever of the comment tools are on,
        // and this is the only callback that says so.
        app.morphe.extension.tiktok.playback.PausePlayback.onCommentCellBound(itemView);
        CommentLikeTouchTarget.onCellBound(itemView);

        boolean block = Settings.BLOCK_FROM_COMMENT.get();
        if (!block) {
            // The takeover used to be one way. A cell sitting in the RecyclerView's pool kept it
            // after the setting was turned off, so the thumbs down went on blocking and a screen
            // reader went on reading "Block this commenter", until the pool emptied, which is
            // not something a reader can see or bring about. Every pooled cell is rebound before
            // it is shown again, so handing the control back on a bind is self healing and needs
            // no record of which cells were ever taken over.
            synchronized (CELL_COMMENTS) {
                CELL_COMMENTS.remove(itemView);
            }
            itemView.post(() -> releaseDislike(itemView));
        }
        boolean links = Settings.COMMENT_LINKS.get();
        // The keyword filter judges a comment TikTok shows translated at its bind, and a cell it
        // collapsed gets its size back on a bind once the filter is off.
        boolean judging = TranslatedCommentFilter.active() || TranslatedCommentFilter.anyCollapsed();
        if (!block && !links && !CommentSearch.enabled() && !judging) {
            CommentSearch.onCellBound(itemView, null);
            return;
        }

        try {
            Object comment = findComment(manager);
            if (comment == null) {
                Logger.printDebug(() -> "Comment cell bound but no comment found on " + manager.getClass().getName());
                return;
            }

            if (judging) TranslatedCommentFilter.onCellBound(itemView, comment);
            CommentSearch.onCellBound(itemView, comment);
            // Posted for the same reason the takeover is: the text view is not laid out while
            // the cell is being bound, and a link cannot be placed on a line that has no
            // width yet.
            if (links) itemView.post(() -> CommentLinks.apply(itemView, comment));
            if (!block) {
                return;
            }

            Object previous;
            synchronized (CELL_COMMENTS) {
                previous = CELL_COMMENTS.put(itemView, comment);
            }
            // A different comment in the same row, rather than the same one bound again for a
            // changed like count: only the first has to drop a press taken before the swap.
            boolean holdsAnotherComment = previous != null && previous != comment;

            // TikTok wires the thumbs down during this same bind, so the takeover runs once
            // the bind has returned. For a cell that is not attached yet, View.post runs the
            // work on attach, which is still after the bind.
            itemView.post(() -> takeOverDislike(itemView, holdsAnotherComment));
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not register a comment cell", ex);
        }
    }

    /**
     * Called with the {@code CommentItemList} once TikTok has parsed a page of comments,
     * before they are shown. Matching comments are removed from the list in place, and optional
     * page-level poll metadata is cleared before TikTok can build its row.
     */
    public static void onCommentListLoaded(Object commentItemList) {
        boolean byWord = Settings.COMMENT_KEYWORD_FILTER.get();
        boolean media = Settings.HIDE_COMMENT_MEDIA.get();
        boolean polls = Settings.HIDE_COMMENT_POLLS.get();
        boolean previews = Settings.HIDE_COMMENT_REPLY_PREVIEWS.get();
        if ((!byWord && !media && !polls && !previews) || commentItemList == null) {
            return;
        }

        try {
            if (polls) {
                clearPoll(commentItemList);
            }
            if (previews) {
                clearReplyPreviews(commentItemList);
            }
            if (!byWord && !media) {
                return;
            }

            List<app.morphe.extension.tiktok.feedfilter.KeywordRules.Rule> keywords = byWord
                    ? app.morphe.extension.tiktok.feedfilter.KeywordRules.parse(
                            Settings.COMMENT_BLOCKED_KEYWORDS.get())
                    : List.of();
            List<String> users = byWord ? entries(Settings.COMMENT_BLOCKED_USERS.get()) : List.of();
            if (keywords.isEmpty() && users.isEmpty() && !media) {
                return;
            }

            Object itemsObject = Reflect.readField(commentItemList, "items");
            if (!(itemsObject instanceof List)) {
                return;
            }

            // Looked up once for the page, not once per comment: two reflective calls into
            // TikTok's account service.
            String self = media ? signedInUserId() : null;

            int removed = filterComments((List<?>) itemsObject, keywords, users, media, self);
            if (removed > 0) {
                final int count = removed;
                Logger.printDebug(() -> "Comment filter removed " + count + " comment(s)");
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Comment filter failed", ex);
        }
    }

    /**
     * A poll is page metadata, separate from the ordinary comment list. TikTok reads it after
     * this hook to build the poll row, so clearing the model member prevents the row from being
     * created and leaves the comments themselves untouched.
     */
    private static void clearPoll(Object commentItemList) {
        Class<?> type = commentItemList.getClass();
        Field pollInfo = Reflect.field(type, "pollInfo");
        if (pollInfo == null) {
            HookStatus.missingMember(POLL_HOOK_FAMILY, "field", type.getName(), "pollInfo");
            return;
        }
        try {
            pollInfo.set(commentItemList, null);
            HookStatus.bound(POLL_HOOK_FAMILY, type.getName() + "#pollInfo");
        } catch (Throwable ex) {
            HookStatus.missingMember(
                    POLL_HOOK_FAMILY, "writable field", type.getName(), "pollInfo");
            Logger.printException(() -> "Could not hide the comment poll", ex);
        }
    }

    /**
     * TikTok sends the reply it opens under a comment inside that comment, as its reply list.
     * Emptying the list leaves only View replies, which loads every reply from the server,
     * the one that was opened included (#193). A reply page has no replies inside its
     * replies, so it goes through here untouched.
     */
    private static void clearReplyPreviews(Object commentItemList) {
        Object items = Reflect.readField(commentItemList, "items");
        if (!(items instanceof List)) {
            return;
        }
        int cleared = 0;
        for (Object comment : (List<?>) items) {
            Object replies = comment == null ? null
                    : Reflect.property(comment, "getReplyComments", "replyComments");
            if (!(replies instanceof List) || ((List<?>) replies).isEmpty()) {
                continue;
            }
            try {
                ((List<?>) replies).clear();
                cleared++;
            } catch (UnsupportedOperationException ex) {
                Logger.printInfo(() -> "A reply preview list is immutable; it stays open");
                return;
            }
        }
        if (cleared > 0) {
            final int count = cleared;
            Logger.printDebug(() -> "Closed the reply preview under " + count + " comment(s)");
        }
    }

    // ---- thumbs down takeover ----------------------------------------------------------

    private static void takeOverDislike(View cell, boolean holdsAnotherComment) {
        try {
            View button = commentControl(cell, DISLIKE_BUTTON_IDS);
            if (button == null) {
                // Deliberately not a hook status miss. This runs per comment cell, and a row
                // variant without the control, or one not fully inflated when the posted
                // runnable lands, would otherwise mark the whole build broken for good.
                if (!warnedNoDislikeControl) {
                    warnedNoDislikeControl = true;
                    Logger.printInfo(() -> "Comment thumbs down control '"
                            + String.join("|", DISLIKE_BUTTON_IDS)
                            + "' not found in this comment cell");
                }
                return;
            }

            // Switches the view-owned listener to blocking; the icon gets one too so a touch
            // that lands on it never reaches TikTok's handling either.
            // A press taken while this row held a different comment must not be released onto
            // the account that just arrived in it.
            View icon = commentControl(cell, DISLIKE_ICON_IDS);
            rememberBeforeTakeover(cell, button, icon);
            wireBlockControl(button, icon);
            if (holdsAnotherComment) {
                // Forget the old comment, but keep ownership of its unfinished press even
                // if the switch is turned off before the finger comes up.
                control(button).discardUntilRelease |= DISLIKE_TOUCH.forget(button);
                if (icon != null) control(icon).discardUntilRelease |= DISLIKE_TOUCH.forget(icon);
            }

            applyBlockedState(cell);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not take over the comment thumbs down", ex);
        }
    }

    /** Called instead of the verified native thumbs-down setOnTouchListener call. */
    public static void setDislikeTouchListener(View view, View.OnTouchListener nativeListener) {
        if (view != null) control(view).nativeListener = nativeListener;
    }

    private static ControlTouchListener existingControl(View view) {
        synchronized (CONTROL_TOUCHES) {
            WeakReference<ControlTouchListener> reference = CONTROL_TOUCHES.get(view);
            return reference == null ? null : reference.get();
        }
    }

    private static ControlTouchListener control(View view) {
        synchronized (CONTROL_TOUCHES) {
            ControlTouchListener listener = existingControl(view);
            if (listener == null) {
                listener = new ControlTouchListener();
                CONTROL_TOUCHES.put(view, new WeakReference<>(listener));
            }
            view.setOnTouchListener(listener);
            return listener;
        }
    }

    /** The native handler stays strongly owned by its view even while the takeover is off. */
    private static final class ControlTouchListener implements View.OnTouchListener {
        View.OnTouchListener nativeListener;
        ControlState state;
        boolean blocking;
        boolean discardUntilRelease;

        void handBack(View view) {
            discardUntilRelease |= DISLIKE_TOUCH.forget(view);
            blocking = false;
            state = null;
        }

        @Override public boolean onTouch(View view, MotionEvent event) {
            if (blocking && !Settings.BLOCK_FROM_COMMENT.get()) releaseDislike(view);
            if (discardUntilRelease) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    discardUntilRelease = false;
                } else {
                    if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                        discardUntilRelease = false;
                    }
                    return true;
                }
            }
            if (blocking && Settings.BLOCK_FROM_COMMENT.get()) {
                return DISLIKE_TOUCH.onTouch(view, event);
            }
            return nativeListener != null && nativeListener.onTouch(view, event);
        }
    }

    /** Records what the takeover is about to overwrite, the first time it touches a control. */
    private static void rememberBeforeTakeover(View cell, View button, View icon) {
        if (button == null) return;
        ControlTouchListener listener = control(button);
        if (listener.state != null) return;
        ControlState state = new ControlState(button, icon, cell);
        listener.state = state;
        if (icon != null) control(icon).state = state;
    }

    /** Hands back whichever of a row's controls this took over, and only those. */
    private static void releaseDislike(View touched) {
        if (touched == null) return;
        try {
            ControlTouchListener listener = existingControl(touched);
            if (listener == null || listener.state == null) {
                // A bind supplies itemView; a touch supplies either indexed control.
                View button = commentControl(touched, DISLIKE_BUTTON_IDS);
                listener = existingControl(button);
            }
            if (listener != null && listener.state != null) unwireBlockControl(listener.state);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not hand the comment thumbs down back", ex);
        }
    }

    static void unwireBlockControl(ControlState state) {
        View button = state.button;
        if (button != null) {
            ControlTouchListener listener = existingControl(button);
            if (listener != null) listener.handBack(button);
            CommentLikeTouchTarget.detachBlockHalo(button);
            // The native thumbs down control uses touch handling; this click belongs to the takeover.
            button.setOnClickListener(null);
            button.setClickable(state.buttonClickable);
            button.setContentDescription(state.description);
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                button.setStateDescription(state.stateDescription);
            }
        }
        View icon = state.icon;
        if (icon != null) {
            ControlTouchListener listener = existingControl(icon);
            if (listener != null) listener.handBack(icon);
            icon.setImportantForAccessibility(state.iconImportance);
            if (icon instanceof ImageView) {
                ImageView image = (ImageView) icon;
                if (image.getDrawable() instanceof BlockGlyphDrawable) {
                    image.setImageDrawable(state.iconDrawable);
                }
                image.setColorFilter(state.iconFilter);
            }
        }
        View cell = state.cell;
        if (cell != null && cell.getAlpha() != state.cellAlpha) cell.setAlpha(state.cellAlpha);
    }

    /**
     * Swallows every touch on the thumbs down so TikTok's dislike never fires, and turns a
     * clean tap into a block. A drag is left to the list (the RecyclerView intercepts it
     * before the control sees more than the first events).
     */
    private static final class DislikeTouchListener implements View.OnTouchListener {
        /** Where one control's press started, and whether it has since become a drag. */
        private static final class Gesture {
            final float downX;
            final float downY;
            boolean moved;

            Gesture(float downX, float downY) {
                this.downX = downX;
                this.downY = downY;
            }
        }

        /**
         * One press per control. The listener is shared by every comment on screen, so keeping
         * the press on the listener let a second finger, or a cell rebound between the press and
         * the release, decide what a release somewhere else did. A release with no press of its
         * own now does nothing rather than blocking whoever the other press was aimed at.
         */
        private final WeakHashMap<View, Gesture> gestures = new WeakHashMap<>();

        boolean forget(View view) {
            if (view == null) return false;
            synchronized (gestures) {
                return gestures.remove(view) != null;
            }
        }

        // Touches arrive on the main thread, but the cell maps in this class are all guarded, and
        // a WeakHashMap corrupts rather than fails if that ever stops being true.
        @Override
        public boolean onTouch(View view, MotionEvent event) {
            boolean tapped = false;
            synchronized (gestures) {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        gestures.put(view, new Gesture(event.getX(), event.getY()));
                        break;
                    case MotionEvent.ACTION_MOVE: {
                        Gesture gesture = gestures.get(view);
                        if (gesture != null && !gesture.moved) {
                            int slop = ViewConfiguration.get(view.getContext()).getScaledTouchSlop();
                            gesture.moved = Math.abs(event.getX() - gesture.downX) > slop
                                    || Math.abs(event.getY() - gesture.downY) > slop;
                        }
                        break;
                    }
                    case MotionEvent.ACTION_CANCEL:
                        gestures.remove(view);
                        break;
                    case MotionEvent.ACTION_UP: {
                        Gesture gesture = gestures.remove(view);
                        tapped = gesture != null && !gesture.moved;
                        break;
                    }
                    default:
                        break;
                }
            }

            // Outside the lock: blocking an account reaches well beyond this listener.
            if (tapped) {
                onDislikeTapped(view);
            }
            return true;
        }
    }

    /**
     * Takes the control over for both a finger and an accessibility service. TalkBack and Switch
     * Access activate a control with {@code performClick()}, which produces no MotionEvents at
     * all, so a touch listener on its own left them reaching TikTok's dislike instead of the
     * block. The touch listener always consumes, so a finger never reaches the click listener.
     */
    static void wireBlockControl(View button, View icon) {
        if (button == null) return;
        control(button).blocking = true;
        button.setOnClickListener(CommentTools::onDislikeTapped);
        // About 40 x 24 dp on the S25's sheet; the halo grows it toward 48 x 48 in blank space.
        CommentLikeTouchTarget.attachBlockHalo(button);
        if (icon != null) {
            control(icon).blocking = true;
            // One target for the row rather than two, so the label and the state are in one
            // place and a screen reader does not read the same control twice.
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        }
    }

    /**
     * What the control is for and what it did. The label still said "dislike" for a control that
     * blocks, and a faded row was the only sign an account was blocked, which a screen reader
     * cannot see at all.
     */
    static void describeBlockControl(View button, boolean blocked) {
        if (button == null) return;
        button.setContentDescription(L10n.t(blocked
                ? "Unblock this commenter" : "Block this commenter"));
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            button.setStateDescription(L10n.t(blocked ? "Blocked" : "Not blocked"));
        }
    }

    private static void onDislikeTapped(View touched) {
        if (!Settings.BLOCK_FROM_COMMENT.get()) {
            // A listener left on a cell from before the setting was turned off. One tap spent
            // handing the control back is the right answer; blocking someone the reader did not
            // choose to block is not.
            releaseDislike(touched);
            return;
        }
        try {
            View cell = cellOf(touched);
            if (cell == null) {
                Utils.showToastShort(L10n.t("Couldn't read who posted this comment. Open their profile and block them there."));
                return;
            }
            toggleBlock(cell);
        } catch (Throwable ex) {
            Logger.printException(() -> "Comment block tap failed", ex);
        }
    }

    /** The registered cell is the nearest ancestor of the control that was bound to a comment. */
    private static View cellOf(View view) {
        View current = view;
        for (int depth = 0; current != null && depth < 12; depth++) {
            synchronized (CELL_COMMENTS) {
                if (CELL_COMMENTS.containsKey(current)) {
                    return current;
                }
            }
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return null;
    }

    /**
     * Every cell still registered, which is every comment on screen plus any pooled rows the
     * map has not let go of yet. Refreshing a pooled row costs nothing and it is bound again
     * before it is shown. Not only the one that was tapped: a thread usually holds several
     * comments by the same account, and refreshing one left the others reading "Block this
     * commenter, not blocked" for an account that is already blocked. Acting on that label did
     * the opposite of what it said, because the toggle reads the blocked set rather than the
     * label, so a screen reader user was told to block and unblocked instead.
     */
    private static void applyBlockedEverywhere() {
        java.util.List<View> cells;
        synchronized (CELL_COMMENTS) {
            cells = new java.util.ArrayList<>(CELL_COMMENTS.keySet());
        }
        for (View cell : cells) applyBlockedState(cell);
    }

    private static void applyBlockedState(View cell) {
        Object comment;
        synchronized (CELL_COMMENTS) {
            comment = CELL_COMMENTS.get(cell);
        }
        boolean blocked = comment != null && isBlocked(comment);

        float alpha = blocked ? BLOCKED_ROW_ALPHA : 1f;
        if (cell.getAlpha() != alpha) {
            cell.setAlpha(alpha);
        }

        // The label still said "dislike" for a control that blocks, and a faded row was the
        // only sign an account was blocked, which a screen reader cannot see at all.
        describeBlockControl(commentControl(cell, DISLIKE_BUTTON_IDS), blocked);

        View icon = commentControl(cell, DISLIKE_ICON_IDS);
        if (icon instanceof ImageView) {
            ImageView image = (ImageView) icon;
            // A control that blocks an account in one tap looked exactly like TikTok's thumbs
            // down, so the only thing telling a reader what the tap would do was having read
            // the setting. The tint said "blocked" once it was too late. While the takeover
            // holds the control it draws the block symbol instead, and the native drawable
            // goes back the moment the control is handed over.
            if (!(image.getDrawable() instanceof BlockGlyphDrawable)) {
                image.setImageDrawable(new BlockGlyphDrawable(
                        glyphColour(cell, image.getContext()), SettingsUi.dp(image.getContext(), 2)));
            }
            if (blocked) {
                image.setColorFilter(BLOCKED_TINT, PorterDuff.Mode.SRC_IN);
            } else {
                image.clearColorFilter();
            }
        }
    }

    /**
     * What to draw the block symbol in while the account is not blocked.
     *
     * <p>The comment sheet follows whatever theme TikTok is in, and there is one colour that is
     * wrong in both: a fixed one. A glyph painted the accent red at rest would shout on a screen
     * where nothing has happened yet, and a fixed white one disappears on the light sheet. The
     * colour TikTok gives most of the row's own text (the name, the time, Reply, the like count)
     * is its secondary grey for the sheet it is drawing, the weight the icon beside it has, so
     * the control keeps that weight and only its shape changes. The Android theme's secondary
     * text colour was read before, but TikTok's dark mode is its own and doesn't follow the
     * Android theme: on the S25's dark sheet that put a near black symbol on a near black sheet,
     * about 1.13:1 (2026-09-26). It stays as the fallback for a row with no text, and the accent
     * after it.
     */
    static int glyphColour(View cell, android.content.Context context) {
        Integer fromRow = mostCommonTextColour(cell);
        return fromRow != null ? fromRow : glyphColour(context);
    }

    /** The text colour most of a row's visible, non-empty TextViews share, or null for none. */
    static Integer mostCommonTextColour(View root) {
        java.util.Map<Integer, Integer> counts = new java.util.LinkedHashMap<>();
        java.util.ArrayDeque<View> pending = new java.util.ArrayDeque<>();
        if (root != null) pending.add(root);
        while (!pending.isEmpty()) {
            View view = pending.poll();
            if (view.getVisibility() != View.VISIBLE) continue;
            if (view instanceof android.widget.TextView) {
                android.widget.TextView text = (android.widget.TextView) view;
                if (text.getText() != null && text.getText().length() > 0) {
                    Integer colour = text.getCurrentTextColor();
                    Integer seen = counts.get(colour);
                    counts.put(colour, seen == null ? 1 : seen + 1);
                }
            } else if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
            }
        }
        Integer best = null;
        int bestCount = 0;
        for (java.util.Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }
        return best;
    }

    private static int glyphColour(android.content.Context context) {
        try {
            android.util.TypedValue value = new android.util.TypedValue();
            if (context.getTheme().resolveAttribute(
                    android.R.attr.textColorSecondary, value, true)) {
                if (value.resourceId != 0) {
                    // The theme overload, which resolves a colour state list to its default and
                    // is the one that exists at minSdk 23.
                    return context.getResources().getColor(value.resourceId, context.getTheme());
                }
                if (value.type >= android.util.TypedValue.TYPE_FIRST_COLOR_INT
                        && value.type <= android.util.TypedValue.TYPE_LAST_COLOR_INT) {
                    return value.data;
                }
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not read the theme colour for the block symbol", ex);
        }
        return BLOCKED_TINT;
    }

    private static boolean isBlocked(Object comment) {
        String uid = uidOf(comment);
        return uid != null && BLOCKED_UIDS.contains(blockKey(uid));
    }

    /** The commenter as blocked by the account signed in now. */
    private static String blockKey(String uid) {
        String account = signedInUserId();
        return (account == null ? "" : account) + '/' + uid;
    }

    /** Records {@code uid} as blocked by the account signed in now. Tests only. */
    static void markBlockedForTests(String uid) {
        BLOCKED_UIDS.add(blockKey(uid));
    }

    private static String uidOf(Object comment) {
        Object user = Reflect.property(comment, "getUser", "user");
        return Reflect.string(user, "getUid", "uid");
    }

    // ---- blocking ----------------------------------------------------------------------

    private static void toggleBlock(View cell) {
        Object comment;
        synchronized (CELL_COMMENTS) {
            comment = CELL_COMMENTS.get(cell);
        }
        Object user = comment == null ? null : Reflect.property(comment, "getUser", "user");
        if (user == null) {
            Utils.showToastShort(L10n.t("Couldn't read who posted this comment. Open their profile and block them there."));
            return;
        }

        VideoAuthor author = new VideoAuthor(
                Reflect.string(user, "getUid", "uid"),
                Reflect.string(user, "getSecUid", "secUid"),
                Reflect.firstNonBlank(
                        Reflect.string(user, "getUniqueId", "uniqueId"),
                        Reflect.string(user, "getNickname", "nickname")),
                Reflect.string(comment, "getCid", "cid"));
        if (!author.isUsable()) {
            Utils.showToastShort(L10n.t("Couldn't read who posted this comment. Open their profile and block them there."));
            return;
        }

        // One request per commenter at a time: a second tap on the same row sent a second block
        // or unblock. Another commenter's request doesn't wait on this one.
        String flight = flightKey(author);
        if (!IN_FLIGHT.add(flight)) {
            return;
        }
        cell.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
        if (author.uid != null && BLOCKED_UIDS.contains(blockKey(author.uid))) {
            unblock(cell, author, flight);
        } else {
            block(cell, author, flight);
        }
    }

    /** The commenter as the account signed in now sees them, for the in-flight set. */
    private static String flightKey(VideoAuthor author) {
        return blockKey(author.uid != null ? author.uid : "sec:" + author.secUid);
    }

    private static void block(View cell, VideoAuthor author, String flight) {
        // The account that blocks is the one signed in at the tap, whatever happens meanwhile.
        String key = author.uid == null ? null : blockKey(author.uid);
        BlockAuthorService.block(author, result -> {
            IN_FLIGHT.remove(flight);
            if (result != BlockAuthorService.Result.CONFIRMED) {
                Utils.showToastLong(BlockAuthorMessages.blockFailure(
                        Utils.getContext(), result, author.label()));
                return;
            }

            if (key != null) {
                BLOCKED_UIDS.add(key);
            }
            // The cell reads its current comment, so a recycled row is never mis-styled.
            applyBlockedEverywhere();

            View root = cell.getRootView();
            BlockAuthorOverlay.showUndoBanner(root instanceof ViewGroup ? (ViewGroup) root : null,
                    L10n.f("Blocked %1$s", author.label()), () -> {
                        // Like a tap on the row: one request for this commenter at a time. A
                        // tap on their row already on its way is that same unblock.
                        if (!IN_FLIGHT.add(flight)) return;
                        BlockAuthorService.unblock(author, undoResult -> {
                            IN_FLIGHT.remove(flight);
                            if (undoResult == BlockAuthorService.Result.CONFIRMED) {
                                if (key != null) {
                                    BLOCKED_UIDS.remove(key);
                                }
                                applyBlockedEverywhere();
                            }
                            Utils.showToastShort(BlockAuthorMessages.unblockResult(
                                    Utils.getContext(), undoResult, author.label()));
                        });
                    });
        });
    }

    private static void unblock(View cell, VideoAuthor author, String flight) {
        String key = author.uid == null ? null : blockKey(author.uid);
        BlockAuthorService.unblock(author, result -> {
            IN_FLIGHT.remove(flight);
            if (result != BlockAuthorService.Result.CONFIRMED) {
                Utils.showToastLong(BlockAuthorMessages.unblockResult(
                        Utils.getContext(), result, author.label()));
                return;
            }
            if (key != null) {
                BLOCKED_UIDS.remove(key);
            }
            applyBlockedEverywhere();
            Utils.showToastShort(BlockAuthorMessages.unblockResult(
                    Utils.getContext(), result, author.label()));
        });
    }

    // ---- keyword filter ----------------------------------------------------------------

    private static int filterComments(List<?> comments,
            List<app.morphe.extension.tiktok.feedfilter.KeywordRules.Rule> keywords,
            List<String> users, boolean media, String self) {
        int removed = 0;
        Iterator<?> iterator = comments.iterator();
        while (iterator.hasNext()) {
            Object comment = iterator.next();
            if (comment == null) {
                continue;
            }

            if (matches(comment, keywords, users, media, self)) {
                try {
                    iterator.remove();
                    removed++;
                    continue;
                } catch (UnsupportedOperationException ex) {
                    Logger.printInfo(() -> "Comment list is immutable; the keyword filter cannot remove from it");
                    return removed;
                }
            }

            Object replies = Reflect.property(comment, "getReplyComments", "replyComments");
            if (replies instanceof List) {
                removed += filterComments((List<?>) replies, keywords, users, media, self);
            }
        }
        return removed;
    }

    private static boolean matches(Object comment,
            List<app.morphe.extension.tiktok.feedfilter.KeywordRules.Rule> keywords,
            List<String> users, boolean media, String self) {
        // Your own stickers and images stay. Hiding comments with pictures is about what other
        // people post, and having your own disappear from a thread you are in reads as the
        // comment having failed to send.
        if (media && hasMedia(comment) && !isOwnComment(comment, self)) {
            return true;
        }

        String text = Reflect.string(comment, "getText", "text");
        // Plain phrases as before, plus "a" & "b" and "a" !& "b".
        if (app.morphe.extension.tiktok.feedfilter.KeywordRules.anyMatches(keywords, text)) {
            return true;
        }

        if (!users.isEmpty()) {
            Object user = Reflect.property(comment, "getUser", "user");
            String uniqueId = Reflect.string(user, "getUniqueId", "uniqueId");
            String nickname = Reflect.string(user, "getNickname", "nickname");
            for (String blocked : users) {
                String wanted = blocked.startsWith("@") ? blocked.substring(1) : blocked;
                if (wanted.equalsIgnoreCase(uniqueId) || wanted.equalsIgnoreCase(nickname)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Whether a comment carries a picture rather than words. TikTok keeps three shapes on
     * the model: the attached images, a sticker struct, and the post items a text-on-image
     * comment is built from.
     */
    /**
     * Whether this comment was written by the account that is signed in.
     *
     * <p>Answers false when nobody is signed in, when the account service is not reachable, or
     * when either side has no id, so a comment is only ever kept by a positive match.
     */
    private static boolean isOwnComment(Object comment, String self) {
        if (self == null || self.isEmpty()) {
            return false;
        }
        Object user = Reflect.property(comment, "getUser", "user");
        if (user == null) {
            return false;
        }
        String uid = Reflect.string(user, "getUid", "uid");
        if (self.equals(uid)) {
            return true;
        }
        String secUid = Reflect.string(user, "getSecUid", "secUid");
        return secUid != null && !secUid.isEmpty() && self.equals(secUid);
    }

    /**
     * The signed in account's id, or null when there is not one.
     *
     * <p>Both ids are worth having: a comment carries the plain uid and the sec uid, and which
     * of them is filled in varies by where the list came from.
     */
    private static String signedInUserId() {
        if (signedInUserIdForTests != null) {
            return signedInUserIdForTests.isEmpty() ? null : signedInUserIdForTests;
        }
        // Null when signed out or on a build where the account service moved. Either way the
        // filter behaves as it did before: it hides every comment carrying a picture.
        return SignedInUser.id();
    }

    /** So a test can stand in for the account service, which needs the host to be running. */
    static String signedInUserIdForTests;

    private static boolean hasMedia(Object comment) {
        Object images = Reflect.property(comment, "getImageList", "imageList");
        if (images instanceof List && !((List<?>) images).isEmpty()) {
            return true;
        }
        Object posts = Reflect.property(comment, "getTextImageCommentPostItemList", "textImageCommentPostItemList");
        if (posts instanceof List && !((List<?>) posts).isEmpty()) {
            return true;
        }
        return Reflect.property(comment, "getStickerStruct", "stickerStruct") != null;
    }

    // ---- model access ------------------------------------------------------------------

    /*
     * The field each manager class keeps its comment in, once the scan below has found it. The
     * scan runs on the main thread for every comment row bound, and it read every field of the
     * class and its parents, and probed each value, each time.
     */
    private static final java.util.concurrent.ConcurrentHashMap<Class<?>, Object[]> COMMENT_FIELDS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** The bound comment is the manager field whose value answers to {@code getCid}. */
    private static Object findComment(Object manager) throws IllegalAccessException {
        // The field and the class of the comment the scan found in it: a value of that class has
        // already answered both checks, and any other value goes through the scan again.
        Object[] known = COMMENT_FIELDS.get(manager.getClass());
        if (known != null) {
            Object value = ((Field) known[0]).get(manager);
            if (value != null && value.getClass() == known[1]) return value;
        }
        Class<?> type = manager.getClass();
        while (type != null && type != Object.class) {
            for (Field field : type.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(manager);
                if (value != null && Reflect.string(value, "getCid", "cid") != null
                        && hasMethod(value.getClass(), "getUser")) {
                    COMMENT_FIELDS.put(manager.getClass(), new Object[]{field, value.getClass()});
                    return value;
                }
            }
            type = type.getSuperclass();
        }
        return null;
    }

    private static boolean hasMethod(Class<?> type, String name) {
        while (type != null && type != Object.class) {
            try {
                type.getDeclaredMethod(name);
                return true;
            } catch (NoSuchMethodException ignored) {
                type = type.getSuperclass();
            }
        }
        return false;
    }

    /** Chooses the newest candidate that actually occurs in this comment cell. */
    private static View commentControl(View root, String[] candidates) {
        if (root == null) return null;
        boolean resolvedAny = false;
        String diagnostic = String.join("|", candidates);
        for (String name : candidates) {
            int id = RESOURCE_IDS.resolve(root.getResources(), root.getContext().getPackageName(), name, false);
            if (id == 0) continue;
            resolvedAny = true;
            View control = root.findViewById(id);
            if (control == null) continue;

            HookStatus.recoveredViewId("comments", diagnostic);
            HookStatus.bound("comments", name);
            return control;
        }
        // Some row variants intentionally omit the control. That is not a broken hook. A build
        // with none of the candidate resources is a contract failure and should say so once.
        if (!resolvedAny) HookStatus.missingViewId("comments", diagnostic);
        return null;
    }

    private static List<String> entries(String stored) {
        List<String> entries = new ArrayList<>();
        if (stored == null) {
            return entries;
        }
        for (String part : stored.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return entries;
    }
}
