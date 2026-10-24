/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.feed;

import android.app.Activity;
import android.app.Application;
import android.os.Build;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import app.morphe.extension.shared.GlobalLayoutHook;
import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceIdCache;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.shared.settings.HushfeedPause;
import app.morphe.extension.tiktok.blockauthor.FeedVisibility;
import app.morphe.extension.tiktok.cleardisplay.RememberClearDisplayPatch;
import app.morphe.extension.tiktok.navigation.FrontWindow;
import app.morphe.extension.tiktok.navigation.NavigationTabsFilter;
import app.morphe.extension.tiktok.profile.ProfileTopBar;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;
import app.morphe.extension.tiktok.settings.SettingsStatus;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Hides controls TikTok lays over the video player.
 *
 * Ids are TikTok 47.1.4's, the one declared build, read off its live view hierarchy with a video
 * paused, apart from the two search module ids, which were read off the module's code in the
 * APK. TikTok hands its short names out again on every build, so only the declared build's
 * name for a view is looked up: an older build's name names some other view there, which a
 * fallback would have hidden in any cell that lacked the current one.
 * <pre>
 *   df_search_biz:id/fo   the full screen layer the visual search prompt lives in, the root
 *                         of the layout SearchVisualSearchContainerComponentV2 inflates
 *   df_search_biz:id/d4   the clickable visual search pill, the root of both layouts the VTag
 *                         processors inflate
 *   id/kam                the Live entrance, top left, 158 px square, no description
 *   id/llj                the right-hand column's slots. The caption frame bqv,
 *                         videomusiccoverblock and the search bar's container ll8 sit beside it
 *   id/f98                the root of every feed survey card; the cell's survey ViewStubs
 *                         carry no inflatedId, so the card keeps its own layout id. All
 *                         seven layouts those stubs inflate have it.
 *                         It is a shared root id: the profile's Favorites page is a
 *                         LinearLayout with the same id.
 *   id/view_rootview      the root of every feed cell (VideoViewCellRootView). The ids below
 *                         it here are feed furniture inside one, apart from uzf, qqw and cnk,
 *                         which sit outside the cells. id/long_press_layout
 *                         is a sibling layer under it, not an ancestor of the rail: scoping
 *                         to that from 0.35.0 hid nothing in the right column (S22, 2026-09-17,
 *                         read off the live tree with the probe's views action).
 *   id/uzf                the strip across the top holding For You, Following and the rest
 *   id/qqw id/cnk         on a video opened from a profile, a hashtag, a sound or search, the
 *                         Add comment bar and the plain View under the pager that keeps its
 *                         138 px (S22). Clear display hides the bar's frame and keeps the
 *                         strip, so both go and the pager takes the room back (#50). cnk also
 *                         names a Space in other layouts, so only the child of
 *                         id/viewpager_container counts
 *   id/j2g id/g5p        on a video opened from a profile, a hashtag, a sound or search, the
 *                         search bar across the top (its back button and search box) and the
 *                         dark gradient behind it. Both are children of the pager's page layer,
 *                         outside the cells, and only the detail layouts have them (S22, 2026-10-10)
 *   id/i_l id/g85 id/eq5  the avatar, like and comment controls
 *   id/i93 id/pqg id/wct  the favourite, music and share controls
 *   id/g87 id/ek6         the rows under like, comment, favourite and share
 *   id/i83 id/w_1         whose icon stays put when its count goes
 *   id/g86 id/ek5         the numeric TextViews inside those rows
 *   id/i82 id/w_0
 * </pre>
 * The first two belong to TikTok's search dynamic feature module, so they resolve under
 * that module's package name rather than the app's. Views are re-hidden on every layout
 * pass, since TikTok shows the prompt again for each video that has something to search.
 */
public final class VideoOverlayHider {
    // TikTok's own ids are looked up under the running package, which a cloned build renames
    // along with its resource table (#59). Feature modules keep their original names there.
    private static final String SEARCH_MODULE_PACKAGE = "com.zhiliaoapp.musically.df_search_biz";
    private static final String[] VISUAL_SEARCH_LAYER_IDS = {"47.4.3:fr"};
    private static final String[] VISUAL_SEARCH_PILL_IDS = {"d4"};
    private static final String[] LIVE_ENTRANCE_IDS = {"47.4.3:k_d"};

    /** The caption under the creator's name. */
    private static final String[] CAPTION_IDS = {"desc"};
    /** VideoMusicTitleAssem's label container, including ordinary and matched-song titles. */
    private static final String[] MUSIC_IDS = {"47.4.3:o7a"};
    private static final String[] ACTION_BAR_IDS = {"47.4.3:mud"};
    private static final String[] SURVEY_IDS = {"47.4.3:f92"};
    private static final String[] TAB_STRIP_IDS = {"47.4.3:uwq"};
    /**
     * The bottom tab bar (Home, Friends, the create button, Inbox, Profile). TikTok takes it away
     * when Clear display starts, but the automatic path can switch the mode on before the bar is
     * laid out after a cold start, and then the bar stays over the first video (#84). It sits
     * outside the cells, under the feed.
     */
    private static final String[] BOTTOM_TABS_IDS = {"47.4.3:onp"};
    /**
     * The hairline along the tab bar's top edge. It's the bar's sibling in the main layout, not
     * its child, and TikTok puts it away with the bar only when its own clear lands, so taking
     * the bar alone left a thin line across the bottom of each video until then (#84).
     */
    private static final String[] BOTTOM_TABS_DIVIDER_IDS = {"47.4.3:cnj"};
    /**
     * TikTok's loading line, the thin bar along the bottom of each video that grows from the
     * middle while it buffers. With the Clear display controls hidden it was the one bar left at
     * the bottom of a cleared screen (#84). TikTok shows and animates it on every buffer, so the
     * frame that holds it goes see-through instead of the line itself.
     */
    private static final String[] LOADING_LINE_IDS = {"47.4.3:nf6"};
    /** The story-count button is a sibling of the main feed, outside its tab strip and cells. */
    private static final String[] FOLLOWING_STORY_IDS = {"47.4.3:wr5"};
    /**
     * The bar TikTok shows only while Clear display is on: its round close button and the
     * pause and speed pill beside it, with the progress bar above them (#97). The main feed keeps
     * them in its seek bar mask and an opened video in its pager column, outside the cells on both.
     */
    private static final String[] CLEAR_EXIT_IDS = {"47.4.3:e_3"};
    private static final String[] CLEAR_PLAYBACK_IDS = {"47.4.3:l6j"};
    private static final String[] CLEAR_SEEK_BAR_IDS = {"video_seek_bar"};
    /**
     * A photo post's Clear display has no such bar, only this close button at the bottom right,
     * inside the cell. TikTok takes it and its parent away as Clear display ends.
     */
    private static final String[] CLEAR_PHOTO_EXIT_IDS = {"47.4.3:uym"};
    /**
     * The anchor row under the caption: a related search, a place, a product or a template.
     * Clear display only fades it, so it stayed tappable while out of sight and a tap near the
     * bottom of the picture opened a search (#84). It goes invisible rather than gone, which
     * takes it out of reach without moving the caption above it.
     */
    private static final String[] ANCHOR_IDS = {"47.4.3:bqr"};
    /**
     * The spinning music disc at the bottom right, beside the rail's column. On 47.1.4 the
     * anchor's frame above also holds the caption and the music row, and neither sits in the
     * column, so the fade reaches them through these two (S22, 2026-10-08).
     */
    private static final String[] MUSIC_COVER_IDS = {"videomusiccoverblock"};
    /**
     * The full-width search bar under the caption ("Search · ..."). On 47.1.4 it sits in its own
     * container beside the cell's interaction area, outside both the column and the anchor's
     * frame, so neither the fade nor Clear display reached it (S22, 2026-10-08). The music disc
     * is laid out above it, which is how its name is found on each build.
     */
    private static final String[] SEARCH_BAR_IDS = {"47.4.3:ljb"};
    /**
     * What {@link #beforeClearDisplay} notes before TikTok's Clear display: the wrappers the rail's
     * buttons and the search bar sit in, and the frames the anchor row and the music disc sit in.
     * Constants, so the anchor test can trace the lookups.
     */
    private static final String[][] SHOWN_COLUMN_IDS = {ACTION_BAR_IDS, SEARCH_BAR_IDS};
    private static final String[][] SHOWN_FRAME_IDS = {ANCHOR_IDS, MUSIC_COVER_IDS};
    /**
     * The blank TikTok keeps above the video on tall screens, as tall as the status bar, so the
     * bar never covers the picture. With the bar hidden it's only a black strip (#97).
     */
    private static final String[] STATUS_BAR_SPACER_IDS = {"47.4.3:dus"};
    /**
     * The black band TikTok lays over the top of each feed cell, as tall as the status bar. It
     * sat over the spacer, so nobody saw it until the spacer went: the video then went up under
     * it and the strip under the clock stayed black (#145, S22). It goes with the spacer,
     * invisible rather than gone, so nothing laid out against it moves.
     */
    private static final String[] STATUS_BAR_SCRIM_IDS = {"47.4.3:wiz"};
    /**
     * The player in each feed cell, around the video's surface. With the spacer and the band gone
     * a 9:16 video still sat centered in it with black above and below, so the see-through status
     * bar grows the surface to fill it (#145, S22). Absent from photo and LIVE cells.
     */
    private static final String[] PLAYER_VIEW_IDS = {"player_view"};
    /**
     * The blank under the video, the height of the bottom tab bar, with a background from the
     * theme. It is the cell's last child (dux first, the video area between), and the reason the
     * picture stops above the bar. See-through bottom bar collapses it (#145).
     */
    private static final String[] BOTTOM_SPACER_IDS = {"47.4.3:duc"};
    /**
     * The search bar across the top of a video opened from search or a profile, and the gradient
     * under it. The fade never reached them: they sit outside the cells and TikTok's Clear display
     * leaves them up (#84). Only ever faded, never hidden.
     */
    private static final String[] TOP_SEARCH_BAR_IDS = {"47.4.3:j1z"};
    private static final String[] TOP_SEARCH_SCRIM_IDS = {"47.4.3:g5g"};
    /**
     * The search box in that bar, beside the Back button (#158). It is the root of the layout
     * TikTok's DetailFeedSearchHelper inflates into the bar on every page it serves: a video
     * opened from search, a profile's posts, Favorites or Likes, watch history, a hashtag or a
     * sound. Another page's layout reuses the name, so only a child of the bar counts. It goes
     * invisible, so Back keeps its place.
     */
    private static final String[] DETAIL_SEARCH_BOX_IDS = {"47.4.3:o94"};
    private static final String[] DETAIL_COMMENT_BAR_IDS = {"47.4.3:qox"};
    private static final String[] DETAIL_COMMENT_STRIP_IDS = {"47.4.3:cnc"};
    /**
     * The slot the "Message..." bar goes in on a mutual friend's opened video, where the comment
     * bar would be, over the same strip (#153, a reporter's layout recording on 47.1.4). It goes
     * with the comment bar. TikTok's quick DM fragment puts the bar in this slot, and the bottom
     * container around it also holds other pages' bars, so only the slot is hidden.
     */
    private static final String[] DETAIL_MESSAGE_BAR_IDS = {"47.4.3:kyn"};
    /**
     * The feed cell root. Furniture is only hidden underneath one: Hide feed surveys used to
     * take every survey card id in the window, and on the profile that is the Favorites tab's whole
     * page, which showed as an empty tab (a Galaxy S25, 2026-09-16, found by restoring the
     * settings one group at a time). The tab strip and Following story button sit outside
     * the cells and stay window-wide. A build that renames the cell root falls back to the
     * whole window, and the hook status names the miss.
     */
    private static final String CELL_ROOT_ID = "view_rootview";
    /**
     * The column the detail pager shares with the comment strip under it. The strip's id isn't
     * its own: on 47.1.4 it also names a Space in twenty other layouts, any of which
     * may be inflated in the detail window, so only the column's own child counts as the strip.
     * The column keeps this name on every build so far.
     */
    private static final String PAGER_COLUMN_ID = "viewpager_container";
    /**
     * The slot the action column stacks each rail button in. The like button sits one wrapper
     * deeper than the rest: slot, then a 120 px row holding the heart and its count. Hiding both
     * left that row standing as a hole between the avatar and the comments (Login2, 2026-10-18,
     * #190), so collapseRailWrappers takes the row away once it holds nothing that shows.
     */
    private static final String RAIL_SLOT_ID = "47.4.3:exj";
    /** How far up from a button the slot may be; a button with no slot that close is left alone. */
    private static final int RAIL_SLOT_DEPTH = 3;
    /** Wrappers collapseWrappers hid, so the pass keeps looking them up until they're back. */
    private static final Map<View, Boolean> RAIL_WRAPPERS_HIDDEN = new WeakHashMap<>();
    /** Whether the last pass left the rail buttons scaled, so the next one can put them back. */
    private static boolean scaledLastPass;
    /** The index of the music row in {@link #RAIL_BUTTON_IDS}; it spans the width and is not scaled. */
    private static final int RAIL_MUSIC_INDEX = 4;
    /**
     * The one size the column has room for. The slots sit at a 169 px pitch on the S22 with a
     * 126 px icon area and the count in the rest, so an icon grown from its bottom edge by a
     * quarter still clears the count under it and the button above it; anything more lands on
     * a neighbour. A larger stored value, from the days the row offered 1.5x and 2x, is read as
     * this.
     */
    static final float MAX_TOUCH_SCALE = 1.25f;
    /**
     * The rail buttons the last walk scaled, put back to the chosen size before every frame
     * while a size other than Normal is chosen.
     *
     * <p>TikTok animates scaleX and scaleY on these very buttons (the like bounce, the avatar
     * pulse), and an animation ends by writing 1 back. The walk scales on a layout pass and an
     * animation is not a layout, which is why the S22 showed the first video's heart at 2x and
     * the next video's at 1x, with the comment, save and share buttons never enlarged at all.
     * A pre-draw listener runs before every frame, so a 1 that TikTok wrote back is corrected
     * before it is drawn. The values are compared first: setting a scale invalidates, and an
     * unconditional write from pre-draw would be a frame that never stops.
     */
    private static final List<WeakReference<View>> SCALED = new ArrayList<>();
    private static float scaleWanted = 1f;
    private static WeakReference<View> rescaleRoot = new WeakReference<>(null);
    private static final ViewTreeObserver.OnPreDrawListener RESCALE = () -> {
        reapplyScale();
        reapplyFade();
        return true;
    };
    /** The row under each rail button holding its count, without the button itself. */
    private static final String[] LIKE_COUNT_ROW_IDS = {"47.4.3:g8c"};
    private static final String[] COMMENT_COUNT_ROW_IDS = {"47.4.3:ek4"};
    private static final String[] FAVORITE_COUNT_ROW_IDS = {"47.4.3:i8a"};
    private static final String[] SHARE_COUNT_ROW_IDS = {"47.4.3:w70"};
    private static final String[][] RAIL_COUNT_ROW_IDS = {
            LIKE_COUNT_ROW_IDS, COMMENT_COUNT_ROW_IDS,
            FAVORITE_COUNT_ROW_IDS, SHARE_COUNT_ROW_IDS
    };
    /** The numeric text inside each row, retained by layouts that replace the row wrapper. */
    private static final String[] LIKE_COUNT_TEXT_IDS = {"47.4.3:g8b"};
    private static final String[] COMMENT_COUNT_TEXT_IDS = {"47.4.3:ek3"};
    private static final String[] FAVORITE_COUNT_TEXT_IDS = {"47.4.3:i8_"};
    private static final String[] SHARE_COUNT_TEXT_IDS = {"47.4.3:w6z"};
    private static final String[][] RAIL_COUNT_TEXT_IDS = {
            LIKE_COUNT_TEXT_IDS, COMMENT_COUNT_TEXT_IDS,
            FAVORITE_COUNT_TEXT_IDS, SHARE_COUNT_TEXT_IDS
    };
    /**
     * Which button each count belongs to, as an index into {@link #RAIL_BUTTON_IDS}: like,
     * comment, favourite, share. A count goes with its button. "Hide like button" used to
     * leave the number standing under an empty space, half under the avatar, which read as
     * the switch doing nothing, and was reported as exactly that.
     */
    private static final int[] RAIL_COUNT_BUTTON_INDEX = {1, 2, 3, 5};
    /** The six buttons inside the action column, in the order they are stacked. */
    private static final String[] AVATAR_BUTTON_IDS = {"47.4.3:i_t"};
    private static final String[] LIKE_BUTTON_IDS = {"47.4.3:g8a"};
    private static final String[] COMMENT_BUTTON_IDS = {"47.4.3:eq2"};
    private static final String[] FAVORITE_BUTTON_IDS = {"47.4.3:i9a"};
    private static final String[] MUSIC_BUTTON_IDS = {"47.4.3:pop"};
    private static final String[] SHARE_BUTTON_IDS = {"47.4.3:w_s"};
    private static final String[][] RAIL_BUTTON_IDS = {
            AVATAR_BUTTON_IDS, LIKE_BUTTON_IDS, COMMENT_BUTTON_IDS,
            FAVORITE_BUTTON_IDS, MUSIC_BUTTON_IDS, SHARE_BUTTON_IDS
    };
    private static final int CAPTION_TARGET = 0;
    private static final int MUSIC_TARGET = 1;
    private static final int ACTION_BAR_TARGET = 2;
    private static final int SURVEY_TARGET = 3;
    private static final int TAB_STRIP_TARGET = 4;
    private static final int DETAIL_COMMENT_BAR_TARGET = 5;
    private static final int DETAIL_COMMENT_STRIP_TARGET = 6;
    private static final int FOLLOWING_STORY_TARGET = 7;
    private static final int CLEAR_EXIT_TARGET = 8;
    private static final int CLEAR_PLAYBACK_TARGET = 9;
    private static final int CLEAR_SEEK_BAR_TARGET = 10;
    private static final int RAIL_TARGET_START = 11;
    private static final int COUNT_ROW_TARGET_START = RAIL_TARGET_START + RAIL_BUTTON_IDS.length;
    private static final int COUNT_TEXT_TARGET_START = COUNT_ROW_TARGET_START
            + RAIL_COUNT_ROW_IDS.length;
    private static final int STATUS_BAR_SPACER_TARGET = COUNT_TEXT_TARGET_START
            + RAIL_COUNT_TEXT_IDS.length;
    private static final int CLEAR_PHOTO_EXIT_TARGET = STATUS_BAR_SPACER_TARGET + 1;
    private static final int BOTTOM_TABS_TARGET = CLEAR_PHOTO_EXIT_TARGET + 1;
    private static final int ANCHOR_TARGET = BOTTOM_TABS_TARGET + 1;
    private static final int MUSIC_COVER_TARGET = ANCHOR_TARGET + 1;
    private static final int SEARCH_BAR_TARGET = MUSIC_COVER_TARGET + 1;
    private static final int BOTTOM_TABS_DIVIDER_TARGET = SEARCH_BAR_TARGET + 1;
    private static final int LOADING_LINE_TARGET = BOTTOM_TABS_DIVIDER_TARGET + 1;
    private static final int TOP_SEARCH_BAR_TARGET = LOADING_LINE_TARGET + 1;
    private static final int TOP_SEARCH_SCRIM_TARGET = TOP_SEARCH_BAR_TARGET + 1;
    private static final int BOTTOM_SPACER_TARGET = TOP_SEARCH_SCRIM_TARGET + 1;
    private static final int DETAIL_SEARCH_BOX_TARGET = BOTTOM_SPACER_TARGET + 1;
    private static final int STATUS_BAR_SCRIM_TARGET = DETAIL_SEARCH_BOX_TARGET + 1;
    private static final int PLAYER_VIEW_TARGET = STATUS_BAR_SCRIM_TARGET + 1;
    private static final int DETAIL_MESSAGE_BAR_TARGET = PLAYER_VIEW_TARGET + 1;
    private static final String[][] TRAVERSAL_TARGET_IDS = traversalTargetIds();
    private static final int LOGICAL_TARGET_COUNT = TRAVERSAL_TARGET_IDS.length;
    private static final int TRAVERSAL_TARGET_COUNT = candidateCount(TRAVERSAL_TARGET_IDS);

    private static final int LEGACY_STATUS_BAR_FLAGS = View.SYSTEM_UI_FLAG_FULLSCREEN
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY;

    private static final ResourceIdCache RESOURCE_IDS = new ResourceIdCache();
    private static final TraversalScratch TRAVERSAL = new TraversalScratch(TRAVERSAL_TARGET_COUNT);

    /**
     * Views this class hid, with the visibility each had before, so turning a switch back
     * off restores exactly that and a view TikTok hid for its own reasons is never forced
     * back on.
     */
    private static final Map<View, Integer> HIDDEN_HERE = new WeakHashMap<>();

    /** Views this class made see-through instead of hiding, with the alpha each had before. */
    private static final Map<View, Float> FADED_HERE = new WeakHashMap<>();

    /**
     * Views faded to the chosen opacity (#84), each with five numbers: [0] the alpha TikTok last
     * gave it, [1] the alpha this class wrote, so a value TikTok wrote since (an animation ending)
     * is told apart from our own, [2] the fraction it is faded by, [3] whether that fraction is
     * the whole opacity rather than a share of TikTok's, and [4] whether the last pass asked for
     * that. The pre-draw pass reads them to fade a value TikTok wrote again.
     */
    private static final Map<View, float[]> FADED_TO = new WeakHashMap<>();
    /**
     * When each view still held at the whole level stopped being asked for it. A control TikTok
     * rests below full opacity after Clear display (or hides by opacity) never reaches 1, so the
     * hold lets go after {@link #WHOLE_HOLD_MS} either way, well past TikTok's restore animation.
     */
    private static final Map<View, Long> HELD_SINCE = new WeakHashMap<>();
    private static final long WHOLE_HOLD_MS = 1000;

    /** TikTok's progress bar rows this class moved down, with the offset it wrote; see alignBarRows. */
    private static final Map<View, Float> BAR_ROWS_MOVED = new WeakHashMap<>();
    /** Caption frames this class lifted over that row, with the offset it wrote. */
    private static final Map<View, Float> CAPTIONS_LIFTED = new WeakHashMap<>();
    /** Music discs lifted with them: TikTok's own offset under the lift, then the offset written. */
    private static final Map<View, float[]> DISCS_LIFTED = new WeakHashMap<>();
    /** Rail columns lifted clear of the row when Share is their lowest button, with the offset written. */
    private static final Map<View, Float> RAILS_LIFTED = new WeakHashMap<>();
    /** When a disc's offset first changed under a lift this second, how often, and a pause until. */
    private static final Map<View, long[]> DISC_CHANGES = new WeakHashMap<>();

    /** The lowest opacity the tabs are faded to, so the way around the app stays findable. */
    static final int NAVIGATION_FADE_FLOOR = 10;

    /** The opacity the current pass fades the controls to, 100 meaning not at all. */
    private static int fadeLevel = 100;

    /** Whether the current pass has See-through bottom bar on, for the main feed (#145). */
    private static boolean seeThroughBarNow;
    /** Whether that bar floats as a rounded pill this pass (#124). */
    private static boolean floatingBarNow;

    /**
     * Whether the current pass is in Clear display with the fade between 0 and 100 (#84). The
     * controls the fade covers then stay in sight at that level, whatever opacity TikTok's own
     * Clear display gives them, and a finger goes through them to the video; see
     * {@link TapThroughControls}.
     */
    private static boolean fadedClear;

    /**
     * Whether the current pass holds an opened video's controls away before its own clear has
     * landed. The top bar is left to TikTok otherwise, and its Clear display takes it off a video
     * opened from search, but until then the bar and its "find related content" box showed over
     * the cleared video as the page opened (#84, Login2 emulator 2026-10-15).
     */
    private static boolean topBarHeld;

    /**
     * How long a status bar may stay visible before it is hidden again on Android 11 and
     * up. A swipe from the top shows the bar transiently and the system takes it away by
     * itself, so re-hiding inside this window would only snap a peek shut. TikTok's own
     * show calls are caught by the layout pass after the window ends.
     */
    static final long STATUS_BAR_PEEK_MS = 4000L;

    private static WeakReference<Application> followed = new WeakReference<>(null);

    private static WeakReference<Activity> activityReference = new WeakReference<>(null);
    /**
     * The windows whose status bar this class, rather than TikTok, is holding away, by decor
     * view, with when it last hid it. One per window: the main feed and a video opened from a
     * profile, a hashtag or a sound (DetailActivity) are two, and one flag for both would give
     * back the wrong one's bar (#50).
     */
    private static final Map<View, Long> STATUS_BAR_HIDDEN_AT = new WeakHashMap<>();
    /** Decor views of the windows this class let draw behind the cutout; see drawIntoCutout. */
    private static final Map<View, Boolean> CUTOUT_OPENED = new WeakHashMap<>();
    private static final GlobalLayoutHook LAYOUT_HOOK = new GlobalLayoutHook();

    /**
     * Moves the one layout listener to whichever feed window is in front: a detail pager as it
     * opens, the main activity again as the user comes back to it. TikTok's launcher entry is a
     * second MainActivity under the feed's, and a theme change resumes it for a moment while the
     * feed can stay in front, so the listener goes back to the feed when that copy pauses.
     */
    private static final FrontWindow FRONT = new FrontWindow(true, new FrontWindow.Listener() {
        @Override public void onFront(Activity activity) {
            installNow(activity);
        }

        @Override public void onGone(Activity activity) {
        }
    });

    private VideoOverlayHider() {
    }

    /**
     * Called from the patched {@code MainActivity.onCreate} and {@code DetailActivity.onCreate}, so
     * a process a deep link starts with no main feed still follows its videos; the work is
     * posted. A copy recreated
     * behind the feed runs it after it has stopped again, and leaves the hides where they are.
     */
    public static void install(Activity activity) {
        if (activity == null) {
            return;
        }
        if (FeedVisibility.isDetailPager(activity)) RememberClearDisplayPatch.onPageOpened(activity);
        FRONT.add(activity);
        Utils.runOnMainThread(() -> {
            if (FRONT.mayTake(activity)) installNow(activity);
        });
    }

    private static void installNow(Activity activity) {
        try {
            if (activity.isFinishing()) {
                LAYOUT_HOOK.detach();
                return;
            }
            ViewGroup root = activity.findViewById(android.R.id.content);
            if (root == null) {
                LAYOUT_HOOK.detach();
                Logger.printInfo(() -> "Video overlay hider found no content view to watch");
                return;
            }
            boolean installed = LAYOUT_HOOK.install(root, VideoOverlayHider::refresh);
            activityReference = new WeakReference<>(activity);
            LiveStatusBar.follow(activity);
            EdgeSwipeLevels.sync(activity);
            follow(activity.getApplication());
            if (installed) {
                Logger.printDebug(() -> "Video overlay hider installed on " + activity.getClass().getSimpleName());
            }
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not install the video overlay hider", ex);
        }
    }

    /**
     * Gives a window the brightness back as it pauses. Registered once per application, from
     * the first install; {@link #FRONT} moves the layout listener.
     */
    private static void follow(Application application) {
        if (application == null || followed.get() == application) return;
        followed = new WeakReference<>(application);
        application.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityResumed(Activity resumed) { }
            @Override public void onActivityCreated(Activity created, Bundle state) { }
            @Override public void onActivityStarted(Activity started) { }
            @Override public void onActivityPaused(Activity paused) {
                EdgeSwipeLevels.onPaused(paused);
                RememberClearDisplayPatch.onPageLeft(paused);
            }
            @Override public void onActivityStopped(Activity stopped) { }
            @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
            @Override public void onActivityDestroyed(Activity destroyed) { }
        });
    }

    /** Refreshes the current window on the main thread, including state changes without a layout. */
    public static void refresh() {
        Activity activity = activityReference.get();
        if (activity == null) {
            LAYOUT_HOOK.detach();
            return;
        }
        if (activity.isFinishing()) {
            LAYOUT_HOOK.detach();
            return;
        }
        applyTo(activity);
    }

    /** One pass over {@code activity}, the same one the layout listener runs. */
    static void applyTo(Activity activity) {
        try {
            if (activity.isFinishing()) {
                return;
            }
            // Swipe for brightness and volume wraps the window once, and gives the brightness
            // back on this pass when its switch goes off or Hushfeed is paused.
            EdgeSwipeLevels.sync(activity);
            // The names above the feed follow their own switch on the strip TikTok's tab filter
            // hides; this pass is the one thing that runs on every layout, so it carries the ask.
            NavigationTabsFilter.refreshTopTabStrips();

            if (Settings.HIDE_VISUAL_SEARCH.get()) {
                hide(activity, SEARCH_MODULE_PACKAGE, VISUAL_SEARCH_LAYER_IDS);
                hide(activity, SEARCH_MODULE_PACKAGE, VISUAL_SEARCH_PILL_IDS);
            }
            boolean detailPager = FeedVisibility.isDetailPager(activity);
            // The LIVE entrance is the main feed's; looked for in the detail pager it was reported
            // missing on every pass, and the hook table called the overlay hooks broken.
            if (Settings.HIDE_LIVE_ENTRANCE.get() && !detailPager) {
                hide(activity, activity.getPackageName(), LIVE_ENTRANCE_IDS);
            }

            // These are ordinary feed furniture rather than a prompt, so they come back
            // when their switch goes off instead of staying gone until the next video. The
            // feed keeps the neighbouring cells inflated too, so the first match is not
            // always the cell on screen: every cell is covered in one walk of the tree,
            // and the walk is skipped while nothing is on and nothing is left to restore.
            // Caption above comments puts the whole caption at the top of the comments, so the
            // one over the video goes with it rather than showing twice.
            boolean caption = Settings.HIDE_FEED_CAPTION.get() || Settings.CAPTION_ABOVE_COMMENTS.get();
            boolean music = Settings.HIDE_FEED_MUSIC.get();
            boolean actionBar = Settings.HIDE_FEED_ACTION_BAR.get();
            boolean surveys = Settings.HIDE_FEED_SURVEYS.get();
            // Clear display hides the tab strip when it starts and TikTok brings it back on
            // the first swipe. Following the live state keeps it away until the tap that ends
            // the mode. The persisted setting cannot be used here: the automatic path never
            // writes it, so it would answer false for exactly the case this is meant to fix.
            // Automatic clear display with no delay clears each video as it starts, so between
            // a swipe and that clear the incoming video would show its buttons, caption, music,
            // the tabs and (with the controls hidden) TikTok's ordinary progress bar (#84).
            // Carried over, they stay away across the swipe.
            boolean carry = !HushfeedPause.isPaused() && RememberClearDisplayPatch.isCarryingClear();
            boolean clearLive = !HushfeedPause.isPaused()
                    && (RememberClearDisplayPatch.isClearDisplayNow() || carry);
            // The fade belongs to the overlay patch: a value saved before a repatch without it
            // does nothing, and Pause answers the stock look.
            boolean overlaysOn = !HushfeedPause.isPaused() && SettingsStatus.videoOverlaysEnabled;
            int chosenFade = overlaysOn ? Math.max(0, Math.min(100, Settings.FADE_CONTROLS_OPACITY.get())) : 100;
            // With the fade set, Clear display keeps the controls it covers in sight at that
            // level instead of taking them away, and lets taps through them (#84). Across a
            // carried swipe the incoming video's controls show the same way rather than going.
            // Asked of the chosen level: the burn-in dim lowers what's drawn, never what Clear
            // display does. With Keep faded controls in Clear display off, Clear display clears
            // them as it does at 100 and the fade is left to the rest of the time.
            fadedClear = clearLive && chosenFade > 0 && chosenFade < 100 && Settings.FADE_IN_CLEAR_DISPLAY.get();
            fadeLevel = BurnInGuard.pass(activity, chosenFade, overlaysOn);
            boolean carryHides = carry && !fadedClear;
            topBarHeld = carryHides && !RememberClearDisplayPatch.isClearDisplayNow();
            boolean tabStrip = !detailPager && clearLive;
            // The comment bar is the opened post's own; the main feed has the tabs there. TikTok
            // leaves it up in Clear display on a photo or video opened from search or a profile
            // (#84), so it goes then too and comes back with the controls. A video opens in the
            // detail pager, but on 47.1.4 a photo from search opens in the main activity
            // (DetailSafRootFragment) with the same bar, so neither the switch nor Clear display
            // asks which window it's in: only an opened post has the bar, and the strip under it
            // is left alone wherever the bar isn't found (see the pairing after the walk). Asked
            // only in the detail pager, the switch left that photo's bar up.
            // The faded Clear display keeps the bar in sight at the chosen level like the rest of
            // the controls, and the fade fades it outside Clear display too; only a fade of 0 takes
            // it away, with its strip, the way the switch does.
            boolean detailCommentBar = Settings.HIDE_DETAIL_COMMENT_BAR.get()
                    || (clearLive && !fadedClear) || chosenFade == 0;
            // The faded Clear display keeps the anchor row and the search bar in sight but out of
            // reach, which is what hiding them was for.
            boolean anchor = clearLive && !fadedClear;
            // Asked for here and confirmed after the walk by TikTok's own bar being on screen,
            // which it shows only in Clear display; see gateClearControls.
            boolean clearControls = !HushfeedPause.isPaused() && Settings.HIDE_CLEAR_DISPLAY_CONTROLS.get();
            boolean statusBar = Settings.HIDE_STATUS_BAR.get();
            // The main feed's video goes up under a see-through status bar: the spacer TikTok keeps
            // for the bar above each video goes, as it does with the bar hidden, which wins (#145).
            boolean seeThroughStatusBar = Settings.SEE_THROUGH_STATUS_BAR.get() && !detailPager && !statusBar;
            // The bottom bar goes clear and the spacer under each video goes, on the main feed
            // only: the opened-post pager has no tab bar (#145).
            boolean seeThroughBar = Settings.SEE_THROUGH_BOTTOM_BAR.get() && !detailPager;
            if (!seeThroughBar) SeeThroughBottomBar.reset();
            seeThroughBarNow = seeThroughBar;
            floatingBarNow = seeThroughBar && Settings.FLOATING_BOTTOM_BAR.get();
            // On the S22 the main feed's room for the bar is the pager column's strip, the one an
            // opened post keeps under its comment bar; the spacer in each cell is already 0 there.
            boolean stripForBar = seeThroughBar && SeeThroughBottomBar.collapseAllowed();
            boolean counts = Settings.HIDE_RAIL_COUNTS.get();
            // The search box at the top of an opened video goes with the feed's search button,
            // when its own row says so too (#158).
            boolean detailSearchBox = Settings.HIDE_FEED_SEARCH_BUTTON.get()
                    && Settings.HIDE_OPENED_VIDEO_SEARCH.get();
            boolean[] rail = TRAVERSAL.rail;
            updateRailButtonsWanted(rail);
            boolean anyRail = counts;
            for (boolean one : rail) {
                anyRail |= one;
            }
            // The rail scale needs the same walk the hiding uses, so it joins the gate. Without
            // it a build with every hide switch off never traversed and the chosen size did
            // nothing at all (seen on the S22, 2026-09-16). scaledLastPass keeps the walk alive
            // for the one pass that writes the buttons back to 1.
            float touchScale = 1f;
            try {
                touchScale = Float.parseFloat(Settings.TOUCH_TARGET_SCALE.get());
            } catch (NumberFormatException ignored) {
            }
            touchScale = Math.min(MAX_TOUCH_SCALE, Math.max(1f, touchScale));
            if (caption || music || actionBar || surveys || tabStrip || carry || detailCommentBar || anchor
                    || clearControls || detailSearchBox
                    || statusBar || seeThroughStatusBar || SeeThroughStatusBar.anyFilled()
                    || seeThroughBar || SeeThroughBottomBar.holdsBackground()
                    || anyRail || !HIDDEN_HERE.isEmpty() || !FADED_HERE.isEmpty() || !WIDGETS_LIFTED.isEmpty()
                    || fadeLevel != 100 || !FADED_TO.isEmpty() || !BAR_ROWS_MOVED.isEmpty()
                    || !CAPTIONS_LIFTED.isEmpty() || !DISCS_LIFTED.isEmpty() || !RAILS_LIFTED.isEmpty()
                    || touchScale != 1f || scaledLastPass) {
                ViewGroup root = activity.findViewById(android.R.id.content);
                int[] ids = TRAVERSAL.ids;
                boolean[] hidden = TRAVERSAL.hidden;
                boolean[] wanted = TRAVERSAL.wanted;
                wanted[CAPTION_TARGET] = caption || carryHides;
                wanted[MUSIC_TARGET] = music || carryHides;
                wanted[ACTION_BAR_TARGET] = actionBar;
                wanted[SURVEY_TARGET] = surveys;
                wanted[TAB_STRIP_TARGET] = tabStrip;
                // Only over the Home feed. A live state that outlasts the feed (Inbox opened from
                // a notification) must not take the tabs off another page. The tabs ride on the
                // feed's page, so they stay away while it's scrolled sideways to a creator's
                // profile: put back there, nothing took them off again when the swipe back
                // settled without a layout pass (#84).
                wanted[BOTTOM_TABS_TARGET] = tabStrip && (FeedVisibility.isOnFeed(activity)
                        || FeedVisibility.isFeedPageScrolledAway(activity));
                wanted[BOTTOM_TABS_DIVIDER_TARGET] = wanted[BOTTOM_TABS_TARGET];
                wanted[FOLLOWING_STORY_TARGET] = tabStrip && !FeedVisibility.isStoryVisible(activity);
                wanted[DETAIL_COMMENT_BAR_TARGET] = detailCommentBar;
                wanted[DETAIL_MESSAGE_BAR_TARGET] = detailCommentBar;
                wanted[DETAIL_COMMENT_STRIP_TARGET] = detailCommentBar || stripForBar;
                wanted[CLEAR_EXIT_TARGET] = clearControls;
                wanted[CLEAR_PLAYBACK_TARGET] = clearControls;
                wanted[CLEAR_SEEK_BAR_TARGET] = clearControls;
                wanted[LOADING_LINE_TARGET] = clearControls;
                wanted[CLEAR_PHOTO_EXIT_TARGET] = clearControls;
                wanted[ANCHOR_TARGET] = anchor;
                // Only ever faded, never hidden by a switch of its own.
                wanted[MUSIC_COVER_TARGET] = false;
                wanted[SEARCH_BAR_TARGET] = anchor;
                // Only ever faded, never hidden.
                wanted[TOP_SEARCH_BAR_TARGET] = false;
                wanted[TOP_SEARCH_SCRIM_TARGET] = false;
                wanted[DETAIL_SEARCH_BOX_TARGET] = detailSearchBox;
                wanted[STATUS_BAR_SPACER_TARGET] = statusBar || seeThroughStatusBar;
                wanted[STATUS_BAR_SCRIM_TARGET] = statusBar || seeThroughStatusBar;
                // Grown, not hidden: see SeeThroughStatusBar.fill.
                wanted[PLAYER_VIEW_TARGET] = seeThroughStatusBar;
                wanted[BOTTOM_SPACER_TARGET] = seeThroughBar && SeeThroughBottomBar.collapseAllowed();
                for (int i = 0; i < RAIL_BUTTON_IDS.length; i++) {
                    wanted[RAIL_TARGET_START + i] = rail[i] || carryHides;
                }
                for (int i = 0; i < RAIL_COUNT_ROW_IDS.length; i++) {
                    boolean hideCount = counts || rail[RAIL_COUNT_BUTTON_INDEX[i]];
                    wanted[COUNT_ROW_TARGET_START + i] = hideCount || carryHides;
                    wanted[COUNT_TEXT_TARGET_START + i] = hideCount || carryHides;
                }

                int candidateAt = 0;
                boolean[] needsCell = TRAVERSAL.needsCell;
                for (int target = 0; target < TRAVERSAL_TARGET_IDS.length; target++) {
                    boolean hideTarget = wanted[target];
                    for (String name : TRAVERSAL_TARGET_IDS[target]) {
                        ids[candidateAt] = resolveIdentifier(
                                activity, activity.getPackageName(), name, false);
                        hidden[candidateAt] = hideTarget;
                        needsCell[candidateAt] = !outsideCells(target);
                        candidateAt++;
                    }
                }

                List<List<View>> found = TRAVERSAL.found;
                for (List<View> views : found) {
                    views.clear();
                }
                int cellId = identifier(activity, activity.getPackageName(), CELL_ROOT_ID);
                try {
                    collect(root, ids, found, cellId, cellId == 0, needsCell);
                    // Only a strip this class hid is ever put back, and that one was the
                    // column's, so the column is looked up only while the strip is wanted.
                    if (detailCommentBar || stripForBar) {
                        keepColumnStrips(found, identifier(activity, activity.getPackageName(), PAGER_COLUMN_ID));
                    }
                    keepTopBarSearchBoxes(found, ids[firstCandidate(TOP_SEARCH_BAR_TARGET)]);
                    selectCurrentTargets(found, TRAVERSAL.selected);
                    pairDetailCommentBar(hidden, TRAVERSAL.selected, ids, detailPager, stripForBar);
                    List<View> strips = selectedViews(found, TRAVERSAL.selected, DETAIL_COMMENT_STRIP_TARGET);
                    // Before the guard, so it measures the caption where this pass leaves it.
                    liftWidgetLayers(strips, seeThroughBar, seeThroughBar
                            ? identifier(activity, activity.getPackageName(), WIDGET_LAYER_ID) : 0, cellId);
                    guardBottomSpacer(hidden, found, TRAVERSAL.selected, seeThroughBar);
                    gateClearControls(hidden, found, TRAVERSAL.selected, carry);
                    applySelectedTargets(ids, hidden, found, TRAVERSAL.selected,
                            touchScale != 1f, detailPager);
                    // After every target, so the like count is already gone when its row is weighed.
                    collapseRailWrappers(activity, found, TRAVERSAL.selected, hidden);
                    settleStripColumns(strips);
                    if (detailPager && detailCommentBar) noteColumnChildren(strips);
                    alignBarRows(found, TRAVERSAL.selected, hidden, cellId);
                    fadeFullscreenEntries();
                    int photoExit = TRAVERSAL.selected[CLEAR_PHOTO_EXIT_TARGET];
                    TapThroughControls.photoExits(activity, !clearControls && photoExit >= 0 ? found.get(photoExit) : null);
                    if (fadedClear && TapThroughControls.anyMarked()) TapThroughControls.watch(activity);
                    // The size goes on the icon inside each button, not the button. The slots
                    // are packed with no room between them, so a grown button lands on its
                    // neighbours; an icon grown from its bottom edge stays inside its slot at
                    // the one size the column has room for. The parents between the icon and
                    // the column stop clipping, or the growth is cut off at the slot's edge and
                    // never seen. Written every pass, 1 included, so returning the row to
                    // Normal puts the icons back. The music row spans the width and is left alone.
                    List<View> scaled = TRAVERSAL.scaled;
                    scaled.clear();
                    for (int i = 0; i < RAIL_BUTTON_IDS.length; i++) {
                        if (i == RAIL_MUSIC_INDEX) continue;
                        int selected = TRAVERSAL.selected[RAIL_TARGET_START + i];
                        if (selected < 0) continue;
                        for (View button : found.get(selected)) {
                            collectIcons(button, scaled);
                        }
                    }
                    int actionBarId = selectedId(
                            ids, TRAVERSAL.selected[ACTION_BAR_TARGET]);
                    for (View icon : scaled) {
                        if (touchScale != 1f) unclipUpTo(icon, actionBarId);
                        scaleView(icon, touchScale);
                    }
                    if (touchScale != 1f) {
                        final int count = scaled.size();
                        final float scale = touchScale;
                        Logger.printDebug(() -> "Rail scale " + scale + " written to " + count + " icons");
                    }
                    rememberScaled(scaled, touchScale, root);
                    scaledLastPass = touchScale != 1f;
                } finally {
                    for (List<View> views : found) {
                        views.clear();
                    }
                    TRAVERSAL.scaled.clear();
                }
            }
            ClearDisplayShownControls.apply(fadedClear, clearOn());
            // The Profile tab is a page of the feed's window, so its top bar rides on this pass.
            ProfileTopBar.apply(activity.findViewById(android.R.id.content));

            setStatusBarHidden(activity, statusBar);
            // Only while the feed is in front: the window's other pages keep the color TikTok gives them.
            SeeThroughStatusBar.apply(activity, seeThroughStatusBar && FeedVisibility.isOnFeed(activity));
        } catch (Throwable ex) {
            Logger.printException(() -> "Video overlay hider failed", ex);
        }
    }

    /**
     * The icon-sized leaves under a rail button: the glyph itself, and for the avatar button
     * its picture, its ring and the follow plus. Read off the S22 (2026-09-17): the like heart
     * is a 127 px leaf inside a 180 by 126 area, the comment and share glyphs 90 px, the count
     * rows 180 wide, so a leaf between 22 and 52 dp on both sides is a glyph and nothing else.
     */
    static void collectIcons(View view, List<View> out) {
        if (view instanceof ViewGroup && ((ViewGroup) view).getChildCount() > 0) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                collectIcons(group.getChildAt(i), out);
            }
            return;
        }
        if (view.getVisibility() != View.VISIBLE) return;
        float density = view.getResources().getDisplayMetrics().density;
        int min = Math.round(22 * density);
        int max = Math.round(52 * density);
        int width = view.getWidth();
        int height = view.getHeight();
        if (width >= min && width <= max && height >= min && height <= max) out.add(view);
    }

    /**
     * Lets the growth be drawn. Every ViewGroup clips its children to its own bounds by
     * default, and the icon sits in a frame its own size, so a scaled glyph was cut off at the
     * frame's edge. Unclipped up to the interaction area, which is the whole column and clips
     * nothing that matters.
     */
    private static void unclipUpTo(View icon, int stopId) {
        android.view.ViewParent parent = icon.getParent();
        for (int depth = 0; depth < 8 && parent instanceof ViewGroup; depth++) {
            ViewGroup group = (ViewGroup) parent;
            if (stopId != 0 && group.getId() == stopId) break;
            if (group.getClipChildren()) group.setClipChildren(false);
            if (group.getClipToPadding()) group.setClipToPadding(false);
            parent = group.getParent();
        }
    }

    /**
     * The scale is written whatever the measured size, because a button skipped for having no
     * width yet was never revisited and stayed at 1 while its neighbours grew. The pivot is the
     * bottom centre, so a grown glyph rises into the free space above it and leaves the count
     * under it readable; it needs a real size, so it waits.
     */
    private static void scaleView(View view, float scale) {
        if (view.getWidth() > 0) {
            view.setPivotX(view.getWidth() / 2f);
            view.setPivotY(view.getHeight());
        }
        if (view.getScaleX() != scale) view.setScaleX(scale);
        if (view.getScaleY() != scale) view.setScaleY(scale);
    }

    /**
     * Keeps the scaled icons for the pre-draw pass, and the pass itself on the root while
     * anything is scaled or faded. The fade needs it too: Clear display ending animates the
     * caption, the rail and the search bar back to full opacity, and no layout follows, so
     * they stayed at full until the next video (S22, 2026-10-08).
     */
    private static void rememberScaled(List<View> scaled, float touchScale, View root) {
        SCALED.clear();
        scaleWanted = touchScale;
        if (touchScale != 1f) {
            for (View view : scaled) {
                SCALED.add(new WeakReference<>(view));
            }
        }
        View watched = rescaleRoot.get();
        if (touchScale != 1f || !FADED_TO.isEmpty() || !FADED_HERE.isEmpty()
                || ClearDisplayShownControls.anyKept()) {
            if (watched != root && root != null) {
                if (watched != null && watched.getViewTreeObserver().isAlive()) {
                    watched.getViewTreeObserver().removeOnPreDrawListener(RESCALE);
                }
                root.getViewTreeObserver().addOnPreDrawListener(RESCALE);
                rescaleRoot = new WeakReference<>(root);
            }
        } else if (watched != null) {
            if (watched.getViewTreeObserver().isAlive()) {
                watched.getViewTreeObserver().removeOnPreDrawListener(RESCALE);
            }
            rescaleRoot = new WeakReference<>(null);
        }
    }

    /** When the frame pass last said what it put back, so the log is not written per frame. */
    private static long rescaleLoggedAt;

    /** The pre-draw pass: every remembered button back to the chosen size, if it moved. */
    static void reapplyScale() {
        float scale = scaleWanted;
        if (scale == 1f) return;
        int corrected = 0;
        int seen = 0;
        for (WeakReference<View> held : SCALED) {
            View view = held.get();
            if (view == null || !view.isAttachedToWindow()) continue;
            seen++;
            if (view.getScaleX() != scale || view.getScaleY() != scale) corrected++;
            scaleView(view, scale);
        }
        long now = SystemClock.uptimeMillis();
        if (corrected > 0 && now - rescaleLoggedAt > 2000L) {
            rescaleLoggedAt = now;
            final int put = corrected;
            final int held = seen;
            Logger.printDebug(() -> "Rail scale " + scale + " put back on " + put + " of " + held + " icons");
        }
    }

    /** When the frame pass last said what it faded again, so the log is not written per frame. */
    private static long refadeLoggedAt;

    /** The pre-draw pass: every faded view TikTok has written its own opacity to, faded again. */
    static void reapplyFade() {
        ClearDisplayShownControls.apply(tapsGoThroughNow(), clearOn());
        if (FADED_TO.isEmpty() && FADED_HERE.isEmpty()) return;
        int corrected = 0;
        for (Map.Entry<View, Float> entry : FADED_HERE.entrySet()) {
            View view = entry.getKey();
            if (view == null || !view.isAttachedToWindow()) continue;
            float nativeAlpha = view.getAlpha();
            if (nativeAlpha == 0f) continue;
            entry.setValue(nativeAlpha);
            view.setAlpha(0f);
            corrected++;
        }
        for (Map.Entry<View, float[]> entry : FADED_TO.entrySet()) {
            View view = entry.getKey();
            float[] held = entry.getValue();
            if (view == null || !view.isAttachedToWindow()) continue;
            // A hold past its time is let go here too, though TikTok wrote nothing since.
            boolean holding = held[3] != 0f && held[4] == 0f;
            if (view.getAlpha() == held[1] && !holding) continue;
            if (view.getAlpha() != held[1]) held[0] = view.getAlpha();
            settleWhole(view, held);
            float next = fadedAlpha(held);
            held[1] = next;
            if (view.getAlpha() == next) continue;
            view.setAlpha(next);
            corrected++;
        }
        long now = SystemClock.uptimeMillis();
        if (corrected > 0 && now - refadeLoggedAt > 2000L) {
            refadeLoggedAt = now;
            final int put = corrected;
            Logger.printDebug(() -> "Fade put back on " + put + " views TikTok had written");
        }
    }

    private static void updateRailButtonsWanted(boolean[] rail) {
        rail[0] = Settings.HIDE_RAIL_FOLLOW.get();
        rail[1] = Settings.HIDE_RAIL_LIKE.get();
        rail[2] = Settings.HIDE_RAIL_COMMENTS.get();
        rail[3] = Settings.HIDE_RAIL_FAVOURITE.get();
        rail[4] = Settings.HIDE_RAIL_MUSIC.get();
        rail[5] = Settings.HIDE_RAIL_SHARE.get();
    }

    private static String[][] traversalTargetIds() {
        String[][] targets = new String[DETAIL_MESSAGE_BAR_TARGET + 1][];
        targets[CAPTION_TARGET] = CAPTION_IDS;
        targets[MUSIC_TARGET] = MUSIC_IDS;
        targets[ACTION_BAR_TARGET] = ACTION_BAR_IDS;
        targets[SURVEY_TARGET] = SURVEY_IDS;
        targets[TAB_STRIP_TARGET] = TAB_STRIP_IDS;
        targets[FOLLOWING_STORY_TARGET] = FOLLOWING_STORY_IDS;
        targets[CLEAR_EXIT_TARGET] = CLEAR_EXIT_IDS;
        targets[CLEAR_PLAYBACK_TARGET] = CLEAR_PLAYBACK_IDS;
        targets[CLEAR_SEEK_BAR_TARGET] = CLEAR_SEEK_BAR_IDS;
        targets[DETAIL_COMMENT_BAR_TARGET] = DETAIL_COMMENT_BAR_IDS;
        targets[DETAIL_COMMENT_STRIP_TARGET] = DETAIL_COMMENT_STRIP_IDS;
        for (int i = 0; i < RAIL_BUTTON_IDS.length; i++) {
            targets[RAIL_TARGET_START + i] = RAIL_BUTTON_IDS[i];
        }
        for (int i = 0; i < RAIL_COUNT_ROW_IDS.length; i++) {
            targets[COUNT_ROW_TARGET_START + i] = RAIL_COUNT_ROW_IDS[i];
            targets[COUNT_TEXT_TARGET_START + i] = RAIL_COUNT_TEXT_IDS[i];
        }
        targets[STATUS_BAR_SPACER_TARGET] = STATUS_BAR_SPACER_IDS;
        targets[CLEAR_PHOTO_EXIT_TARGET] = CLEAR_PHOTO_EXIT_IDS;
        targets[BOTTOM_TABS_TARGET] = BOTTOM_TABS_IDS;
        targets[ANCHOR_TARGET] = ANCHOR_IDS;
        targets[MUSIC_COVER_TARGET] = MUSIC_COVER_IDS;
        targets[SEARCH_BAR_TARGET] = SEARCH_BAR_IDS;
        targets[BOTTOM_TABS_DIVIDER_TARGET] = BOTTOM_TABS_DIVIDER_IDS;
        targets[LOADING_LINE_TARGET] = LOADING_LINE_IDS;
        targets[TOP_SEARCH_BAR_TARGET] = TOP_SEARCH_BAR_IDS;
        targets[TOP_SEARCH_SCRIM_TARGET] = TOP_SEARCH_SCRIM_IDS;
        targets[BOTTOM_SPACER_TARGET] = BOTTOM_SPACER_IDS;
        targets[DETAIL_SEARCH_BOX_TARGET] = DETAIL_SEARCH_BOX_IDS;
        targets[STATUS_BAR_SCRIM_TARGET] = STATUS_BAR_SCRIM_IDS;
        targets[PLAYER_VIEW_TARGET] = PLAYER_VIEW_IDS;
        targets[DETAIL_MESSAGE_BAR_TARGET] = DETAIL_MESSAGE_BAR_IDS;
        return targets;
    }

    /**
     * Main-feed tabs (top and bottom, with the bottom bar's hairline) and story count, the Clear
     * display controls, and the detail pager's comment and Message bars are outside the cells.
     */
    private static boolean outsideCells(int target) {
        return (target >= TAB_STRIP_TARGET && target < RAIL_TARGET_START) || target == BOTTOM_TABS_TARGET
                || target == BOTTOM_TABS_DIVIDER_TARGET || target == TOP_SEARCH_BAR_TARGET
                || target == TOP_SEARCH_SCRIM_TARGET || target == DETAIL_SEARCH_BOX_TARGET
                || target == DETAIL_MESSAGE_BAR_TARGET;
    }

    private static int candidateCount(String[][] targets) {
        int count = 0;
        for (String[] candidates : targets) count += candidates.length;
        return count;
    }

    /** Where a target's candidates start in the per-candidate arrays. */
    private static int firstCandidate(int target) {
        int at = 0;
        for (int i = 0; i < target; i++) at += TRAVERSAL_TARGET_IDS[i].length;
        return at;
    }

    /** When a column last failed to settle here, so a column that won't settle isn't looped. */
    private static final Map<View, Long> COLUMNS_UNSETTLED = new WeakHashMap<>();

    /**
     * Gives the pager back the room of a strip this class hid. Swiping through an opened grid on
     * the S22 left every post ending 138 px short over a black band (#153): the strip read GONE,
     * yet the column was never measured again, so its pager kept the height it had beside the
     * strip. TikTok's ScreenAdaptionComponent shows the strip and writes its height on each page
     * change, and the layout that GONE asks for went missing on the way. A column whose shown
     * children no longer fill it is measured and laid out again at its own size, which doesn't
     * depend on that request.
     *
     * <p>It happens right here in the pass, which runs after the layout and before the frame is
     * drawn. On photo posts TikTok shows the strip again about twice a second (S22, 2026-10-14),
     * and a relayout posted at most once every half second left the band up most of the time. Only
     * a column that didn't settle waits half a second before it's tried again.
     */
    static void settleStripColumns(List<View> strips) {
        for (View strip : strips) {
            if (strip.getVisibility() != View.GONE || !HIDDEN_HERE.containsKey(strip)) continue;
            android.view.ViewParent parent = strip.getParent();
            if (!(parent instanceof android.widget.LinearLayout)) continue;
            android.widget.LinearLayout column = (android.widget.LinearLayout) parent;
            if (!leavesRoom(column)) continue;
            Long failed = COLUMNS_UNSETTLED.get(column);
            if (failed != null && SystemClock.uptimeMillis() - failed < 500L) continue;
            if (column.isInLayout()) {
                column.post(() -> relayoutColumn(column));
            } else {
                relayoutColumn(column);
            }
        }
    }

    /** The last arrangement {@link #noteColumnChildren} logged, so only a new one is logged. */
    private static volatile String lastColumnNote;

    /**
     * With debug logging on, names what an opened video's column shows beside the comment strip,
     * once for each new arrangement. A mutual friend's video shows a "Message..." bar that Hide
     * the comment bar on opened videos leaves (#153), and no account here has a mutual friend, so
     * a reporter's diagnostic export is what can name it.
     */
    static void noteColumnChildren(List<View> strips) {
        if (!BaseSettings.DEBUG.get()) return;
        for (View strip : strips) {
            if (!(strip.getParent() instanceof ViewGroup)) continue;
            String note = columnNote((ViewGroup) strip.getParent(), strip);
            if (note.equals(lastColumnNote)) continue;
            lastColumnNote = note;
            Logger.printDebug(() -> "Opened video column shows: " + note);
        }
    }

    /** The column's shown children, the strip's own shown children in brackets after it. */
    static String columnNote(ViewGroup column, View strip) {
        StringBuilder note = new StringBuilder();
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            if (note.length() > 0) note.append(", ");
            describe(note, child);
        }
        if (strip instanceof ViewGroup) {
            ViewGroup inside = (ViewGroup) strip;
            note.append("; strip ").append(strip.getVisibility() == View.GONE ? "hidden" : "shown").append(" [");
            boolean first = true;
            for (int i = 0; i < inside.getChildCount(); i++) {
                View child = inside.getChildAt(i);
                if (child.getVisibility() == View.GONE) continue;
                if (!first) note.append(", ");
                first = false;
                describe(note, child);
            }
            note.append(']');
        }
        return note.toString();
    }

    private static void describe(StringBuilder note, View view) {
        String name;
        int id = view.getId();
        if (id == View.NO_ID) {
            name = "no id";
        } else {
            try {
                name = view.getResources().getResourceEntryName(id);
            } catch (android.content.res.Resources.NotFoundException ex) {
                name = "0x" + Integer.toHexString(id);
            }
        }
        note.append(name).append(' ').append(view.getClass().getSimpleName()).append(' ')
                .append(view.getHeight()).append("px");
    }

    /** Whether a laid-out vertical column's shown children leave part of its height unclaimed. */
    static boolean leavesRoom(android.widget.LinearLayout column) {
        if (column.getOrientation() != android.widget.LinearLayout.VERTICAL || column.getHeight() <= 0) {
            return false;
        }
        int used = column.getPaddingTop() + column.getPaddingBottom();
        for (int i = 0; i < column.getChildCount(); i++) {
            View child = column.getChildAt(i);
            if (child.getVisibility() == View.GONE) continue;
            used += child.getHeight();
            ViewGroup.LayoutParams params = child.getLayoutParams();
            if (params instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams) params;
                used += margins.topMargin + margins.bottomMargin;
            }
        }
        return column.getHeight() - used > 1;
    }

    /** Measures and lays out a column again inside the bounds it already has. */
    static void relayoutColumn(View column) {
        if (!column.isAttachedToWindow() || !(column instanceof android.widget.LinearLayout)
                || !leavesRoom((android.widget.LinearLayout) column)) {
            return;
        }
        if (column.isInLayout()) {
            column.requestLayout();
            return;
        }
        int width = column.getWidth();
        int height = column.getHeight();
        // A strip hidden during the column's own layout leaves no request on the column, and a
        // measure at the size it already has is then answered from the cache (S22, 2026-10-14).
        column.forceLayout();
        column.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        column.layout(column.getLeft(), column.getTop(), column.getRight(), column.getBottom());
        column.invalidate();
        boolean settled = !leavesRoom((android.widget.LinearLayout) column);
        if (settled) {
            COLUMNS_UNSETTLED.remove(column);
        } else {
            COLUMNS_UNSETTLED.put(column, SystemClock.uptimeMillis());
        }
        Logger.printDebug(() -> settled
                ? "Gave the pager back the hidden comment strip's room"
                : "Laid the pager column out again, but it still leaves the strip's room empty");
    }

    /** Drops every strip that isn't the pager column's own child; see {@link #PAGER_COLUMN_ID}. */
    private static void keepColumnStrips(List<List<View>> found, int columnId) {
        int at = firstCandidate(DETAIL_COMMENT_STRIP_TARGET);
        for (int i = 0; i < DETAIL_COMMENT_STRIP_IDS.length; i++) {
            List<View> strips = found.get(at + i);
            for (int j = strips.size() - 1; j >= 0; j--) {
                android.view.ViewParent parent = strips.get(j).getParent();
                if (columnId == 0 || !(parent instanceof View)
                        || ((View) parent).getId() != columnId) {
                    strips.remove(j);
                }
            }
        }
    }

    /** Keeps only the search boxes that sit directly in an opened video's top bar (#158). */
    private static void keepTopBarSearchBoxes(List<List<View>> found, int barId) {
        int at = firstCandidate(DETAIL_SEARCH_BOX_TARGET);
        for (int i = 0; i < DETAIL_SEARCH_BOX_IDS.length; i++) {
            List<View> boxes = found.get(at + i);
            for (int j = boxes.size() - 1; j >= 0; j--) {
                android.view.ViewParent parent = boxes.get(j).getParent();
                if (barId == 0 || !(parent instanceof View) || ((View) parent).getId() != barId) {
                    boxes.remove(j);
                }
            }
        }
    }

    /**
     * The comment bar and its strip go together or not at all. The strip alone grows the pager
     * under a bar that still shows, which then covers the caption and the music row; the bar
     * alone leaves the black strip. When one of them can't be found, the one that was found
     * stays (or comes back) and the other is reported missing.
     *
     * <p>One exception: an opened video whose build still names the bar. An opened grid that
     * starts on photos, or runs through several, has no bar in its tree on 47.1.4 until a video
     * binds one, and putting the strip back there left the photo ending a bar's height short over
     * a black band (#153). A bar renamed by another build doesn't resolve at all, so that case
     * still keeps its strip. A mutual friend's video has no comment bar either, its Message bar
     * sits over the strip instead, and it goes only when the strip does.
     */
    private static void pairDetailCommentBar(boolean[] hidden, int[] selected, int[] ids,
                                             boolean detailPager, boolean stripForBar) {
        boolean bar = selected[DETAIL_COMMENT_BAR_TARGET] >= 0;
        boolean strip = selected[DETAIL_COMMENT_STRIP_TARGET] >= 0;
        if (bar && !strip) {
            hidden[firstCandidate(DETAIL_COMMENT_BAR_TARGET)] = false;
        }
        // See-through bottom bar takes the main feed's strip on its own; no comment bar there.
        if (strip && !bar && !stripForBar && !(detailPager && resolves(ids, DETAIL_COMMENT_BAR_TARGET))) {
            hidden[firstCandidate(DETAIL_COMMENT_STRIP_TARGET)] = false;
        }
        if (!(strip && hidden[firstCandidate(DETAIL_COMMENT_STRIP_TARGET)])) {
            hidden[firstCandidate(DETAIL_MESSAGE_BAR_TARGET)] = false;
        }
    }

    /** Whether any of a target's ids names a resource in this build. */
    private static boolean resolves(int[] ids, int target) {
        int at = firstCandidate(target);
        for (int i = 0; i < TRAVERSAL_TARGET_IDS[target].length; i++) {
            if (ids[at + i] != 0) return true;
        }
        return false;
    }

    /**
     * Keeps the progress bar of an opened video where TikTok draws it, along the bottom edge of the
     * caption's frame, while the comment bar is away (#50, #153). The row isn't in the cell: it sits
     * at the top of its own slot beside the pager (S25, 2026-10-11: row 2025 to 2104 in a slot
     * down to 2205), and the slot keeps room under it for the comment bar. TikTok draws the line
     * through the middle of the row, and with the bar up that middle meets the cell's bottom, where
     * the caption's frame ends. With the bar and its strip gone the cell grows into that room and
     * the caption comes down with it, so a long video's bar drew across the creator's name.
     *
     * <p>The row comes down until its middle meets the frame's bottom, never past its own slot.
     * Matching the row's bottom instead left the line half a row up inside the caption, across its
     * last line (#153). When the slot runs out first, as it does where the caption reaches the
     * bottom of the screen, the frame goes up by what's left, so the caption ends on the line, and
     * the music disc beside it goes up to end above the row (see captionLifts). On 47.1.4 TikTok
     * holds the disc up with an offset of its own, so the
     * disc's lift goes on top of that one (see liftDisc); before it did, the disc stayed with its
     * bottom on the line (S22, 2026-10-13: 2146 before and after, against 2013 over a line at 2038
     * with the comment bar up). Every cell's
     * frame gets its lift as it's laid out, from where it
     * sits in its own cell, so the next video arrives already in place rather than jumping after
     * the swipe. A cell whose caption ends higher (TikTok's search bar or a series bar under it)
     * needs no lift. Both go back with the bar, and a row or frame TikTok moved itself since is
     * left to TikTok.
     */
    private static void alignBarRows(List<List<View>> found, int[] selected, boolean[] hidden, int cellId) {
        int rowsAt = selected[CLEAR_SEEK_BAR_TARGET];
        List<View> rows = rowsAt >= 0 ? found.get(rowsAt) : new ArrayList<>();
        boolean barGone = selected[DETAIL_COMMENT_STRIP_TARGET] >= 0
                && hidden[firstCandidate(DETAIL_COMMENT_STRIP_TARGET)]
                && ((selected[DETAIL_COMMENT_BAR_TARGET] >= 0 && hidden[firstCandidate(DETAIL_COMMENT_BAR_TARGET)])
                        || (selected[DETAIL_MESSAGE_BAR_TARGET] >= 0 && hidden[firstCandidate(DETAIL_MESSAGE_BAR_TARGET)]));
        List<View> frames = underShownParents(selectedViews(found, selected, ANCHOR_TARGET));
        List<View> discs = underShownParents(selectedViews(found, selected, MUSIC_COVER_TARGET));
        // The rail's buttons, the disc's own button apart: it spans the rail's width, and only the
        // disc inside it counts (see captionLifts).
        List<View> buttons = new ArrayList<>();
        List<View> musicButtons = new ArrayList<>();
        for (int i = 0; i < RAIL_BUTTON_IDS.length; i++) {
            List<View> shown = underShownParents(selectedViews(found, selected, RAIL_TARGET_START + i));
            (RAIL_BUTTON_IDS[i] == MUSIC_BUTTON_IDS ? musicButtons : buttons).addAll(shown);
        }
        // The heart's count is a row under the heart, not inside its button the way the others'
        // are, so a heart left lowest ended on the row with its count over the time (#145).
        for (int i = 0; i < RAIL_COUNT_ROW_IDS.length; i++) {
            buttons.addAll(underShownParents(selectedViews(found, selected, COUNT_ROW_TARGET_START + i)));
        }
        List<View> columns = new ArrayList<>();
        // Null while the lifts are left as they are: a row on screen with no frame over its slot
        // is a cell still being bound, and putting the captions back then would make them jump.
        Map<View, Float> lifts = barGone ? null : new HashMap<>();
        boolean rowShown = false;
        for (View row : rows) {
            float drop = 0f;
            View frame = barGone ? overSlot(row, frames) : null;
            boolean shown = barGone && shownUpTheTree(row);
            rowShown |= shown;
            if (frame != null) {
                if (shown && lifts == null) {
                    lifts = captionLifts(row, frame, frames, discs, buttons, musicButtons, columns, cellId);
                }
                Float lift = lifts == null ? null : lifts.get(frame);
                drop = barRowDrop(row, frame, lift == null ? 0f : lift);
            }
            moveBarRow(row, drop);
        }
        // With no row on screen (a photo, or the progress bar turned off) nothing needs room.
        if (lifts == null && !rowShown) lifts = new HashMap<>();
        if (lifts != null) {
            for (View frame : frames) {
                Float lift = lifts.get(frame);
                offsetOwned(CAPTIONS_LIFTED, frame, lift == null ? 0f : -lift);
            }
            for (View disc : discs) {
                Float lift = lifts.get(disc);
                liftDisc(disc, lift == null ? 0f : lift);
            }
            for (View column : columns) {
                Float lift = lifts.get(column);
                offsetOwned(RAILS_LIFTED, column, lift == null ? 0f : -lift);
            }
            if (!RAILS_LIFTED.isEmpty()) {
                for (View column : new ArrayList<>(RAILS_LIFTED.keySet())) {
                    if (!columns.contains(column)) offsetOwned(RAILS_LIFTED, column, 0f);
                }
            }
            if (!CAPTIONS_LIFTED.isEmpty()) {
                for (View view : new ArrayList<>(CAPTIONS_LIFTED.keySet())) {
                    if (!frames.contains(view)) offsetOwned(CAPTIONS_LIFTED, view, 0f);
                }
            }
            if (!DISCS_LIFTED.isEmpty()) {
                for (View disc : new ArrayList<>(DISCS_LIFTED.keySet())) {
                    if (!discs.contains(disc)) liftDisc(disc, 0f);
                }
            }
        }
        if (BAR_ROWS_MOVED.isEmpty()) return;
        for (View row : new ArrayList<>(BAR_ROWS_MOVED.keySet())) {
            if (!rows.contains(row)) moveBarRow(row, 0f);
        }
    }

    /**
     * Checks, with the spacer under each video collapsed, that the caption frame and the buttons'
     * column did not move under the bar. They sit in the same cell and TikTok may anchor them to
     * its bottom, in which case the collapse would put them behind the tabs: the spacer is then
     * put back for good (until the switch is turned off) and only the bar's background is left
     * clear. Before the collapse the same call notes which of them sit clear of the bar.
     */
    private static void guardBottomSpacer(boolean[] hidden, List<List<View>> found, int[] selected,
            boolean seeThroughBar) {
        int spacerAt = selected[BOTTOM_SPACER_TARGET];
        int stripAt = selected[DETAIL_COMMENT_STRIP_TARGET];
        if (!seeThroughBar || (spacerAt < 0 && stripAt < 0)) return;
        boolean collapsed = false;
        for (int at : new int[] {spacerAt, stripAt}) {
            if (at < 0) continue;
            for (View spacer : found.get(at)) {
                collapsed |= HIDDEN_HERE.containsKey(spacer);
            }
        }
        List<View> bars = selectedViews(found, selected, BOTTOM_TABS_TARGET);
        List<View> overlays = new ArrayList<>(selectedViews(found, selected, ANCHOR_TARGET));
        overlays.addAll(selectedViews(found, selected, ACTION_BAR_TARGET));
        boolean refused = false;
        for (View bar : bars) {
            refused |= SeeThroughBottomBar.judge(bar, overlays, collapsed);
        }
        if (refused) {
            // selected holds the candidate each target settled on, which is hidden's index too.
            if (spacerAt >= 0) hidden[spacerAt] = false;
            if (stripAt >= 0) hidden[stripAt] = false;
            Logger.printDebug(() -> "See-through bottom bar: the collapse put the caption or buttons under the bar, spacer kept");
        }
    }

    /**
     * The layer in each cell that holds the caption, the buttons and the music row, laid out
     * against the cell's bottom (S22, 2026-10-13: 2175 tall in a 2043 cell, bottom margin -132).
     */
    private static final String WIDGET_LAYER_ID = "widget_container";
    /** Widget layers lifted over the see-through bar, with the offset written. */
    private static final Map<View, Float> WIDGETS_LIFTED = new WeakHashMap<>();

    /**
     * Keeps the caption and the buttons where TikTok had them once See-through bottom bar gives
     * the main feed's pager the strip's room (#145). The cells grow by the strip's height and
     * everything in them laid out against the bottom would go down behind the tabs, so each
     * cell's widget layer goes up by the same amount while the picture fills the room. Nothing
     * moves until the column has actually been laid out without the strip, and a layer TikTok
     * offsets itself is left to TikTok.
     */
    private static void liftWidgetLayers(List<View> strips, boolean seeThroughBar, int layerId, int cellId) {
        List<View> lifted = new ArrayList<>();
        if (seeThroughBar && layerId != 0 && cellId != 0) {
            for (View strip : strips) {
                float gained = roomGained(strip);
                if (gained <= 0f) continue;
                List<View> layers = new ArrayList<>();
                collectWidgetLayers((View) strip.getParent(), layerId, cellId, 0, layers);
                for (View layer : layers) {
                    offsetOwned(WIDGETS_LIFTED, layer, -gained);
                    lifted.add(layer);
                }
            }
        }
        if (WIDGETS_LIFTED.isEmpty()) return;
        for (View layer : new ArrayList<>(WIDGETS_LIFTED.keySet())) {
            if (!lifted.contains(layer)) offsetOwned(WIDGETS_LIFTED, layer, 0f);
        }
    }

    /**
     * The room a strip this class hid has given its column's pager: the strip's height once the
     * column has been laid out without it, 0 before then or while the strip shows.
     */
    static float roomGained(View strip) {
        if (strip.getVisibility() != View.GONE || !HIDDEN_HERE.containsKey(strip)
                || !(strip.getParent() instanceof android.widget.LinearLayout)) {
            return 0f;
        }
        android.widget.LinearLayout column = (android.widget.LinearLayout) strip.getParent();
        ViewGroup.LayoutParams params = strip.getLayoutParams();
        if (column.getHeight() <= 0 || leavesRoom(column) || params == null) return 0f;
        return Math.max(0, params.height);
    }

    /** Each cell's widget layer under {@code view}: column, wrapper, pager, cell at the most. */
    private static void collectWidgetLayers(View view, int layerId, int cellId, int depth, List<View> out) {
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        if (view.getId() == cellId) {
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getId() == layerId) out.add(child);
            }
            return;
        }
        if (depth >= 4) return;
        for (int i = 0; i < group.getChildCount(); i++) {
            collectWidgetLayers(group.getChildAt(i), layerId, cellId, depth + 1, out);
        }
    }

    private static List<View> selectedViews(List<List<View>> found, int[] selected, int target) {
        int at = selected[target];
        return at >= 0 ? found.get(at) : new ArrayList<>();
    }

    /**
     * Hides the wrappers between each hidden rail button and its slot once nothing in them
     * shows, and puts back the ones this class hid when the button comes back; see
     * {@link #RAIL_SLOT_ID}. The slot id is looked up only while a button is wanted hidden or a
     * wrapper hidden here may need putting back.
     */
    private static void collapseRailWrappers(Activity activity, List<List<View>> found,
            int[] selected, boolean[] hidden) {
        boolean anyWanted = false;
        for (int i = 0; i < RAIL_BUTTON_IDS.length; i++) {
            anyWanted |= hidden[firstCandidate(RAIL_TARGET_START + i)];
        }
        if (!anyWanted && RAIL_WRAPPERS_HIDDEN.isEmpty()) return;
        int slotId = identifier(activity, activity.getPackageName(), RAIL_SLOT_ID);
        if (slotId == 0) return;
        for (int i = 0; i < RAIL_BUTTON_IDS.length; i++) {
            int target = RAIL_TARGET_START + i;
            boolean wanted = hidden[firstCandidate(target)];
            for (View button : selectedViews(found, selected, target)) {
                collapseWrappers(button, wanted, slotId);
            }
        }
    }

    /**
     * The wrappers from {@code button} up to (not including) the slot with {@code slotId},
     * innermost first, each hidden only once all its children are gone. Nothing is touched when
     * the slot isn't within {@link #RAIL_SLOT_DEPTH} levels, or when the button is the slot's
     * own child, since the slot then wraps to nothing on its own.
     */
    static void collapseWrappers(View button, boolean hidden, int slotId) {
        List<ViewGroup> wrappers = new ArrayList<>(RAIL_SLOT_DEPTH);
        android.view.ViewParent parent = button.getParent();
        while (parent instanceof ViewGroup && ((ViewGroup) parent).getId() != slotId) {
            if (wrappers.size() == RAIL_SLOT_DEPTH) return;
            wrappers.add((ViewGroup) parent);
            parent = parent.getParent();
        }
        if (!(parent instanceof ViewGroup)) return;
        for (ViewGroup wrapper : wrappers) {
            if (hidden && !allChildrenGone(wrapper)) return;
            setHidden(wrapper, hidden);
            if (hidden) RAIL_WRAPPERS_HIDDEN.put(wrapper, Boolean.TRUE);
            else RAIL_WRAPPERS_HIDDEN.remove(wrapper);
        }
    }

    private static boolean allChildrenGone(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            if (group.getChildAt(i).getVisibility() != View.GONE) return false;
        }
        return true;
    }

    /**
     * The views whose parents all show. A video opened from search plays in the main activity
     * with the For You pager still in the tree behind it, gone, and that pager's caption ran to
     * the bottom over more of the row's slot than the opened video's own, so the row came down
     * into TikTok's search bar (S22, 2026-10-14). The view's own visibility is left out, so a
     * disc TikTok hides in a cell on screen keeps its lift.
     */
    private static List<View> underShownParents(List<View> views) {
        List<View> shown = new ArrayList<>(views.size());
        for (View view : views) {
            android.view.ViewParent parent = view.getParent();
            if (!(parent instanceof View) || shownUpTheTree((View) parent)) shown.add(view);
        }
        return shown;
    }

    /**
     * The frame crossing the row's slot, or mid-swipe the one covering more of it, measured where
     * it sits without a lift of ours; null without one, or for a row that isn't laid out.
     */
    static View overSlot(View row, List<View> frames) {
        if (row.getHeight() == 0 || !(row.getParent() instanceof View)) return null;
        View slot = (View) row.getParent();
        float slotTop = topInWindow(slot) + slot.getTranslationY();
        float slotBottom = slotTop + slot.getHeight();
        View over = null;
        float covered = 0f;
        for (View frame : frames) {
            if (frame.getHeight() == 0) continue;
            float top = unliftedTop(frame);
            float overlap = Math.min(slotBottom, top + frame.getHeight()) - Math.max(slotTop, top);
            if (overlap > covered) {
                covered = overlap;
                over = frame;
            }
        }
        return over;
    }

    /**
     * How far {@code row} comes down so the line through its middle meets the bottom of
     * {@code frame} once the frame is up by {@code lift}, at most to the bottom of the row's slot.
     * Zero for a frame that already ends above that line.
     */
    static float barRowDrop(View row, View frame, float lift) {
        float room = slotRoom(row);
        if (room <= 0f) return 0f;
        float line = topInWindow(row) + row.getHeight() / 2f;
        float frameBottom = unliftedTop(frame) + frame.getHeight() - lift;
        return Math.round(Math.max(0f, Math.min(room, frameBottom - line)));
    }

    /** How far the row can come down inside its slot, its own offset left out. */
    private static float slotRoom(View row) {
        View slot = (View) row.getParent();
        float rowBottom = topInWindow(row) + row.getHeight();
        return topInWindow(slot) + slot.getTranslationY() + slot.getHeight() - rowBottom;
    }

    /**
     * How far each frame goes up so it ends no lower than the lowest place the row's line can
     * reach, and how far the music disc in the same cell goes up so it ends above the row. The
     * line runs along the caption's bottom, so a disc lifted only with its caption sat on it, and
     * a short caption gave it no lift at all: the disc covered the end of the line and the time
     * beside it (#153, 0.73.0). With the comment bar up TikTok keeps the disc clear of the line,
     * and the row's top is the nearest place that clears the time too. Measured from the top of
     * each frame's own cell, which lines up with the cell over the slot once it's swiped in.
     * Without a cell to measure from only the frame over the slot is lifted, from where it is now.
     *
     * <p>The rail's column goes up too when its lowest button would reach the row. With a disc
     * under the buttons it never does, but a video with no disc, or one hidden by Hide the music
     * button, leaves Share lowest, and Share and its label sat over the end of the line and the
     * time (#153, 0.74.0). {@code buttons} holds the count rows too, since the heart's sits under
     * it rather than inside it (#145). The column carries the disc, so the disc's own lift is
     * what's left of its lift after the column's. Each column found is added to {@code columns}.
     *
     * <p>On some phones the disc is the column's own last button, in {@code musicButtons}, and the
     * column went up only for Share, so the disc stayed across the line and the time (#153, 0.75.0).
     * That button spans the rail's width and holds the notes that float off the disc, a layer that
     * reaches its bottom well left of the other buttons, so the disc counts by what it shows in
     * their lane (see discBottom), not by the button's own bottom.
     */
    private static Map<View, Float> captionLifts(View row, View current, List<View> frames,
                                                 List<View> discs, List<View> buttons,
                                                 List<View> musicButtons, List<View> columns,
                                                 int cellId) {
        Map<View, Float> lifts = new HashMap<>();
        float lowestLine = topInWindow(row) + row.getHeight() / 2f + Math.max(0f, slotRoom(row));
        View currentCell = cellOf(current, cellId);
        if (currentCell == null) {
            float lift = Math.round(Math.max(0f, unliftedTop(current) + current.getHeight() - lowestLine));
            if (lift > 0f) lifts.put(current, lift);
            return lifts;
        }
        float lineInCell = lowestLine - unliftedTop(currentCell);
        for (View frame : frames) {
            View cell = cellOf(frame, cellId);
            if (cell == null || frame.getHeight() == 0) continue;
            float cellTop = unliftedTop(cell);
            float bottomInCell = unliftedTop(frame) + frame.getHeight() - cellTop;
            float lift = Math.round(Math.max(0f, bottomInCell - lineInCell));
            if (lift > 0f) lifts.put(frame, lift);
            // The row comes down until its line meets the caption's bottom, never past its slot.
            float rowTopInCell = Math.min(bottomInCell, lineInCell) - row.getHeight() / 2f;
            View column = railColumn(buttons, cell, cellId);
            float columnOffset = column == null ? 0f : ownedOffset(RAILS_LIFTED, column);
            float railLift = 0f;
            if (column != null) {
                columns.add(column);
                float lowest = Float.NEGATIVE_INFINITY;
                float laneLeft = Float.POSITIVE_INFINITY;
                float laneRight = Float.NEGATIVE_INFINITY;
                for (View button : buttons) {
                    if (cellOf(button, cellId) != cell || button.getHeight() == 0
                            || button.getVisibility() != View.VISIBLE) continue;
                    lowest = Math.max(lowest, topInWindow(button) + button.getTranslationY()
                            - columnOffset + button.getHeight() - cellTop);
                    float left = leftInWindow(button) + button.getTranslationX();
                    laneLeft = Math.min(laneLeft, left);
                    laneRight = Math.max(laneRight, left + button.getWidth());
                }
                for (View music : musicButtons) {
                    if (cellOf(music, cellId) != cell || !holds(column, music)) continue;
                    lowest = Math.max(lowest, discBottom(music, laneLeft, laneRight) - columnOffset - cellTop);
                }
                railLift = Math.round(Math.max(0f, lowest - rowTopInCell));
                if (railLift > 0f) lifts.put(column, railLift);
            }
            for (View disc : discs) {
                if (cellOf(disc, cellId) != cell || disc.getHeight() == 0) continue;
                float discBottomInCell = unliftedDiscTop(disc) - columnOffset + disc.getHeight() - cellTop;
                float discLift = Math.round(Math.max(lift, discBottomInCell - rowTopInCell) - railLift);
                if (discLift > 0f) lifts.put(disc, discLift);
            }
        }
        return lifts;
    }

    /**
     * The view holding the rail's buttons in {@code cell}: the nearest parent shared by all of
     * them, never the cell itself. Null with fewer than two buttons, since one alone can't say
     * which of its parents is the column.
     */
    private static View railColumn(List<View> buttons, View cell, int cellId) {
        View column = null;
        int counted = 0;
        for (View button : buttons) {
            if (cellOf(button, cellId) != cell) continue;
            counted++;
            if (column == null) {
                column = button.getParent() instanceof View ? (View) button.getParent() : null;
                continue;
            }
            while (column != null && column != cell && !holds(column, button)) {
                column = column.getParent() instanceof View ? (View) column.getParent() : null;
            }
        }
        return counted < 2 || column == null || column == cell ? null : column;
    }

    private static boolean holds(View group, View view) {
        for (android.view.ViewParent parent = view.getParent(); parent instanceof View;
             parent = parent.getParent()) {
            if (parent == group) return true;
        }
        return false;
    }

    /** The offset of ours {@code view} still carries, or 0 once TikTok has written its own. */
    private static float ownedOffset(Map<View, Float> owned, View view) {
        Float wrote = owned.get(view);
        return wrote != null && view.getTranslationY() == wrote ? wrote : 0f;
    }

    /** Where {@code disc} sits in its window with TikTok's own offset and without our lift. */
    private static float unliftedDiscTop(View disc) {
        float now = disc.getTranslationY();
        float[] held = DISCS_LIFTED.get(disc);
        float base = held != null && now == held[1] ? held[0] : now;
        return topInWindow(disc) + base;
    }

    /** The feed cell {@code view} sits in, or null outside one or without the cell's id. */
    private static View cellOf(View view, int cellId) {
        if (cellId == 0) return null;
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View) {
            if (((View) parent).getId() == cellId) return (View) parent;
            parent = parent.getParent();
        }
        return null;
    }

    /** Where {@code view} sits in its window with any lift of ours left out. */
    private static float unliftedTop(View view) {
        Float wrote = CAPTIONS_LIFTED.get(view);
        float ours = wrote != null && view.getTranslationY() == wrote ? wrote : 0f;
        return topInWindow(view) + view.getTranslationY() - ours;
    }

    /**
     * Whether {@code view} and everything above it is VISIBLE. Unlike isShown this doesn't ask
     * whether the window is attached yet, which a pass can run before.
     */
    private static boolean shownUpTheTree(View view) {
        View at = view;
        while (true) {
            if (at.getVisibility() != View.VISIBLE) return false;
            android.view.ViewParent parent = at.getParent();
            if (!(parent instanceof View)) return true;
            at = (View) parent;
        }
    }

    /**
     * Where {@code view}'s laid-out top sits in its window, through each parent's position,
     * scroll and offset, without the view's own offset.
     */
    private static float topInWindow(View view) {
        float top = view.getTop();
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View) {
            View group = (View) parent;
            top += group.getTop() - group.getScrollY() + group.getTranslationY();
            parent = group.getParent();
        }
        return top;
    }

    /** Where {@code view}'s laid-out left sits in its window, as {@link #topInWindow} has the top. */
    private static float leftInWindow(View view) {
        float left = view.getLeft();
        android.view.ViewParent parent = view.getParent();
        while (parent instanceof View) {
            View group = (View) parent;
            left += group.getLeft() - group.getScrollX() + group.getTranslationX();
            parent = group.getParent();
        }
        return left;
    }

    /**
     * Where the disc in a music button ends in its window: the bottom of the lowest view shown in
     * it, with nothing shown inside, whose middle sits between {@code laneLeft} and
     * {@code laneRight}. Negative infinity when the button shows nothing in that lane.
     */
    private static float discBottom(View view, float laneLeft, float laneRight) {
        if (view.getVisibility() != View.VISIBLE || view.getWidth() == 0 || view.getHeight() == 0) {
            return Float.NEGATIVE_INFINITY;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            boolean shownInside = false;
            float lowest = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE || child.getWidth() == 0 || child.getHeight() == 0) continue;
                shownInside = true;
                lowest = Math.max(lowest, discBottom(child, laneLeft, laneRight));
            }
            if (shownInside) return lowest;
        }
        float middle = leftInWindow(view) + view.getTranslationX() + view.getWidth() / 2f;
        if (middle < laneLeft || middle > laneRight) return Float.NEGATIVE_INFINITY;
        return topInWindow(view) + view.getTranslationY() + view.getHeight();
    }

    /**
     * Lifts a music disc by {@code lift} over the offset TikTok holds it at, or puts it back there
     * for a lift of zero. 47.1.4 keeps the disc about 40 px up with an offset of its own, and the
     * caption frame's rule leaves any offset it didn't write, so the disc never moved and sat on the
     * line (#153). An offset TikTok writes under the lift, as when it binds the cell again, becomes
     * the one the disc is lifted from. One that keeps changing (more than three times within a
     * second, the way an animation would) is left to TikTok for a moment rather than fought over.
     */
    static void liftDisc(View disc, float lift) {
        float now = disc.getTranslationY();
        float[] held = DISCS_LIFTED.get(disc);
        long at = android.os.SystemClock.uptimeMillis();
        long[] changes = DISC_CHANGES.get(disc);
        if (held != null && now != held[1]) {
            DISCS_LIFTED.remove(disc);
            held = null;
            if (changes == null || at - changes[0] > 1000L) {
                changes = new long[] {at, 0L, 0L};
                DISC_CHANGES.put(disc, changes);
            }
            if (++changes[1] > 3) changes[2] = at + 300L;
        }
        if (changes != null && at < changes[2]) return;
        if (lift <= 0f) {
            if (held != null) {
                disc.setTranslationY(held[0]);
                DISCS_LIFTED.remove(disc);
            }
            return;
        }
        float base = held != null ? held[0] : now;
        float target = base - lift;
        if (now != target) disc.setTranslationY(target);
        DISCS_LIFTED.put(disc, new float[] {base, target});
    }

    private static void moveBarRow(View row, float drop) {
        if (offsetOwned(BAR_ROWS_MOVED, row, drop)) {
            Logger.printDebug(() -> "Progress bar row on an opened video moved down " + drop + " px");
        }
    }

    /**
     * Writes {@code offset} as {@code view}'s translationY and notes it in {@code owned}, or forgets
     * the view once TikTok has written an offset of its own there since. True when it wrote.
     */
    private static boolean offsetOwned(Map<View, Float> owned, View view, float offset) {
        Float wrote = owned.get(view);
        float ours = wrote == null ? 0f : wrote;
        if (view.getTranslationY() != ours) {
            owned.remove(view);
            return false;
        }
        boolean writes = ours != offset;
        if (writes) view.setTranslationY(offset);
        if (offset == 0f) {
            owned.remove(view);
        } else {
            owned.put(view, offset);
        }
        return writes;
    }

    /**
     * The Clear display controls go only while TikTok's own clear-mode bar is on screen, which is
     * the one signal that holds for the main feed and an opened video alike. The progress bar
     * shares its id with the one TikTok shows outside Clear display, so without this the switch
     * would take that away too; and when TikTok takes the bar down, all three come back.
     */
    private static void gateClearControls(boolean[] hidden, List<List<View>> found, int[] selected,
                                          boolean carry) {
        // A photo's close button carries its own signal: TikTok shows its parent only in Clear
        // display. Put back once that parent is gone, the button stays out of sight and out of reach.
        int photoExit = selected[CLEAR_PHOTO_EXIT_TARGET];
        if (photoExit < 0 || !anyParentShown(found.get(photoExit))) {
            hidden[firstCandidate(CLEAR_PHOTO_EXIT_TARGET)] = false;
        }
        int exit = selected[CLEAR_EXIT_TARGET];
        if (exit >= 0 && anyParentShown(found.get(exit))) return;
        hidden[firstCandidate(CLEAR_EXIT_TARGET)] = false;
        hidden[firstCandidate(CLEAR_PLAYBACK_TARGET)] = false;
        // Across a carried swipe the bar on screen is the incoming video's ordinary one, which
        // its own clear is about to take; it stays away until then. So does the incoming
        // video's loading line, which is on screen exactly then.
        if (!carry) {
            hidden[firstCandidate(CLEAR_SEEK_BAR_TARGET)] = false;
            hidden[firstCandidate(LOADING_LINE_TARGET)] = false;
        }
    }

    private static boolean anyParentShown(List<View> views) {
        for (View view : views) {
            android.view.ViewParent parent = view.getParent();
            if (parent instanceof View && ((View) parent).isShown()) return true;
        }
        return false;
    }

    /** Chooses the newest candidate that actually occurs in the current hierarchy. */
    private static void selectCurrentTargets(List<List<View>> found, int[] selected) {
        int candidateAt = 0;
        for (int target = 0; target < TRAVERSAL_TARGET_IDS.length; target++) {
            selected[target] = -1;
            for (int i = 0; i < TRAVERSAL_TARGET_IDS[target].length; i++, candidateAt++) {
                if (selected[target] < 0 && !found.get(candidateAt).isEmpty()) {
                    selected[target] = candidateAt;
                }
            }
        }
    }

    /** Applies each logical switch only to its selected version-specific candidate. */
    private static void applySelectedTargets(int[] ids, boolean[] hidden,
            List<List<View>> found, int[] selected, boolean scaling, boolean detailPager) {
        int candidateAt = 0;
        for (int target = 0; target < TRAVERSAL_TARGET_IDS.length; target++) {
            String[] names = TRAVERSAL_TARGET_IDS[target];
            int chosen = selected[target];
            boolean wanted = hidden[candidateAt];
            if (chosen >= 0) {
                String diagnostic = String.join("|", names);
                HookStatus.recoveredViewId("overlay", diagnostic);
                HookStatus.bound("overlay", names[chosen - candidateAt]);
                for (View view : found.get(chosen)) {
                    // The progress bar only goes see-through, so a drag along the bottom edge
                    // still seeks while it's out of sight (#84). Otherwise it fades with the
                    // controls. A native alpha write or comments rebind can bring the row back,
                    // so the pre-draw pass keeps the transparent row out of sight too.
                    if (target == PLAYER_VIEW_TARGET) {
                        SeeThroughStatusBar.fill(view, wanted);
                    } else if (target == CLEAR_SEEK_BAR_TARGET) {
                        if (wanted) {
                            setFaded(view, 100);
                            setTransparent(view, true);
                        } else {
                            setTransparent(view, false);
                            setFaded(view, fadeLevel, fadedClear);
                        }
                    } else if (target == TOP_SEARCH_BAR_TARGET || target == TOP_SEARCH_SCRIM_TARGET) {
                        // The way back out of the page: faded like the tabs, never past the floor,
                        // and out of sight only until TikTok's own clear lands on the page.
                        setFaded(view, topBarHeld ? 0 : Math.max(NAVIGATION_FADE_FLOOR, fadeLevel), fadedClear);
                    } else if (target == DETAIL_SEARCH_BOX_TARGET || target == STATUS_BAR_SCRIM_TARGET) {
                        // Invisible, not gone: the bar keeps its shape and Back stays put, and
                        // the cell's band keeps its place.
                        setHidden(view, wanted, View.INVISIBLE);
                    } else if (target == DETAIL_COMMENT_BAR_TARGET || target == DETAIL_MESSAGE_BAR_TARGET) {
                        // Hidden with its strip by the switch, Clear display and a fade of 0;
                        // otherwise faded, and out of a finger's way in the faded Clear display.
                        setFaded(view, wanted ? 100 : fadeLevel, fadedClear && !wanted);
                        TapThroughControls.mark(view, !wanted && fadedClear);
                        setHidden(view, wanted);
                    } else if (target == LOADING_LINE_TARGET) {
                        android.view.ViewParent frame = view.getParent();
                        setTransparent(frame instanceof View ? (View) frame : view, wanted);
                    } else if (target == ANCHOR_TARGET || target == MUSIC_COVER_TARGET
                            || target == SEARCH_BAR_TARGET) {
                        // 47.1.4 keeps the caption frame, the music disc and the search bar
                        // beside the column, so they fade here. Invisible rather than gone, so
                        // the caption doesn't move.
                        boolean gone = wanted || fadeLevel == 0;
                        setFaded(view, gone ? 100 : fadeLevel, fadedClear);
                        TapThroughControls.mark(view, !gone && fadedClear);
                        setHidden(view, gone, View.INVISIBLE);
                    } else if (target == ACTION_BAR_TARGET) {
                        // The rail's column. Fully faded it goes the way Clear display takes it,
                        // so nothing invisible takes a tap.
                        boolean gone = wanted || fadeLevel == 0;
                        setFaded(view, gone ? 100 : fadeLevel, fadedClear);
                        TapThroughControls.mark(view, !gone && fadedClear);
                        setHidden(view, gone);
                    } else if (target == TAB_STRIP_TARGET || target == BOTTOM_TABS_TARGET
                            || target == BOTTOM_TABS_DIVIDER_TARGET || target == FOLLOWING_STORY_TARGET) {
                        // See-through bottom bar (#145): the bar's own background goes clear and
                        // its hairline goes see-through, while the tabs keep their icons and the
                        // fade. Clear display still takes the bar away whole.
                        boolean clearBar = seeThroughBarNow && !wanted;
                        // Floating bottom bar draws a rounded pill there instead (#124).
                        if (target == BOTTOM_TABS_TARGET) {
                            SeeThroughBottomBar.setBackgroundClear(view, clearBar, floatingBarNow);
                        }
                        if (target == BOTTOM_TABS_DIVIDER_TARGET && clearBar) {
                            setFaded(view, 100);
                            setTransparent(view, true);
                            continue;
                        }
                        if (target == BOTTOM_TABS_DIVIDER_TARGET) setTransparent(view, false);
                        // The Following stories pill is the strip's neighbour at the top of the
                        // feed, so it fades with the tabs; it was only ever hidden (#84).
                        setFaded(view, wanted ? 100 : Math.max(NAVIGATION_FADE_FLOOR, fadeLevel));
                        setHidden(view, wanted);
                    } else setHidden(view, wanted);
                }
            // A survey is content TikTok inserts only on selected posts. Its absence from an
            // ordinary feed cell says nothing about whether this build still has the anchor.
            // Nor does the comment bar's from the main feed, which never has one: with its switch
            // on, every pass there called the overlay hooks broken (#153). The detail pager
            // has it, so a rename still shows there. The search box at the top of an opened
            // video is there only on the pages TikTok gives a search bar, the player only in
            // video cells, and the Message bar only on a mutual friend's video.
            } else if (target != SURVEY_TARGET && target != DETAIL_SEARCH_BOX_TARGET
                    && target != PLAYER_VIEW_TARGET && target != DETAIL_MESSAGE_BAR_TARGET
                    && (detailPager || (target != DETAIL_COMMENT_BAR_TARGET
                            && target != DETAIL_COMMENT_STRIP_TARGET))
                    && (wanted || (scaling
                    && target >= RAIL_TARGET_START
                    && target < COUNT_ROW_TARGET_START))) {
                HookStatus.missingViewId("overlay", String.join("|", names));
            }
            candidateAt += names.length;
        }
    }

    /**
     * The Full screen buttons TikTok has bound, by the patch that hands each one to
     * {@link FeedOverlayControls#bindFullscreen}. The hider looks up no id for it, so the faded
     * look reaches it through the view the patch proved is the entry.
     */
    private static final Map<View, Boolean> FULLSCREEN_ENTRIES = new WeakHashMap<>();

    /** Called as TikTok binds a Full screen button; the next pass fades it with the controls. */
    static void noteFullscreenEntry(View entry) {
        if (entry != null) FULLSCREEN_ENTRIES.put(entry, Boolean.TRUE);
    }

    /**
     * Fades every noted Full screen button to the chosen level, whole in the faded Clear display
     * where a finger goes through it, and out of sight at 0 without moving what is beside it.
     * Put back to what TikTok had given it once the fade is off.
     */
    private static void fadeFullscreenEntries() {
        if (FULLSCREEN_ENTRIES.isEmpty()) return;
        boolean gone = fadeLevel == 0;
        for (View entry : new ArrayList<>(FULLSCREEN_ENTRIES.keySet())) {
            if (entry == null || !entry.isAttachedToWindow()) continue;
            setFaded(entry, gone ? 100 : fadeLevel, fadedClear);
            TapThroughControls.mark(entry, !gone && fadedClear);
            setHidden(entry, gone, View.INVISIBLE);
        }
    }

    private static int selectedId(int[] ids, int selected) {
        return selected < 0 ? 0 : ids[selected];
    }

    private static void hide(Activity activity, String packageName, String[] names) {
        View view = view(activity, packageName, names);
        if (view != null && view.getVisibility() != View.GONE) {
            view.setVisibility(View.GONE);
        }
    }

    private static View view(Activity activity, String packageName, String[] names) {
        boolean retryMissing = SEARCH_MODULE_PACKAGE.equals(packageName);
        String family = overlayFamily(retryMissing);
        String diagnostic = String.join("|", names);
        for (String name : names) {
            int id = resolveIdentifier(activity, packageName, name, retryMissing);
            View candidate = id == 0 ? null : activity.findViewById(id);
            if (candidate != null) {
                HookStatus.recoveredViewId(family, diagnostic);
                HookStatus.bound(family, name);
                return candidate;
            }
        }
        if (!retryMissing) HookStatus.missingViewId(family, diagnostic);
        return null;
    }

    /** Every descendant of {@code root} carrying {@code id}, in tree order. */
    static List<View> viewsWithId(View root, int id) {
        return viewsWithIds(root, new int[]{id}).get(0);
    }

    /**
     * One walk of the tree under {@code root} collecting the views for several ids at
     * once. The result holds one list per id, in the order given; an id of zero, which is
     * what an unresolved name gives, matches nothing.
     */
    static List<List<View>> viewsWithIds(View root, int[] ids) {
        List<List<View>> found = new ArrayList<>(ids.length);
        boolean anyId = false;
        for (int id : ids) {
            found.add(new ArrayList<>());
            anyId |= id != 0;
        }
        if (anyId && root != null) {
            collect(root, ids, found);
        }
        return found;
    }

    private static void collect(View view, int[] ids, List<List<View>> found) {
        collect(view, ids, found, 0, true, null);
    }

    /**
     * The walk. A target flagged in {@code needsCell} is collected only under a view whose
     * id is {@code cellId}; with no cell id resolved ({@code inCell} starts true) every
     * match is taken, which is what the walk did before the cell root was known.
     */
    private static void collect(View view, int[] ids, List<List<View>> found,
                                int cellId, boolean inCell, boolean[] needsCell) {
        int viewId = view.getId();
        if (cellId != 0 && viewId == cellId) {
            inCell = true;
        }
        if (viewId != View.NO_ID) {
            for (int i = 0; i < ids.length; i++) {
                if (ids[i] == viewId && (inCell || needsCell == null || !needsCell[i])) {
                    found.get(i).add(view);
                }
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0, count = group.getChildCount(); i < count; i++) {
                collect(group.getChildAt(i), ids, found, cellId, inCell, needsCell);
            }
        }
    }

    private static final class TraversalScratch {
        final int[] ids;
        final boolean[] hidden;
        final boolean[] needsCell;
        final boolean[] rail;
        final boolean[] wanted;
        final int[] selected;
        final List<List<View>> found;
        /** The icons one pass scaled, cleared with the rest once the pass is over. */
        final List<View> scaled = new ArrayList<>();

        TraversalScratch(int targetCount) {
            ids = new int[targetCount];
            hidden = new boolean[targetCount];
            needsCell = new boolean[targetCount];
            rail = new boolean[RAIL_BUTTON_IDS.length];
            wanted = new boolean[LOGICAL_TARGET_COUNT];
            selected = new int[LOGICAL_TARGET_COUNT];
            found = new ArrayList<>(targetCount);
            for (int i = 0; i < targetCount; i++) {
                found.add(new ArrayList<>());
            }
        }
    }

    /**
     * Takes the status bar away for the whole activity, or gives it back if this class was
     * the one that hid it. TikTok sets its own system UI flags when it moves between
     * pages, so this runs on every layout pass and only touches the window when the bar
     * is not already in the wanted state. A bar TikTok hid itself is left to TikTok.
     */
    static void setStatusBarHidden(Activity activity, boolean hidden) {
        Window window = activity.getWindow();
        if (window == null) {
            return;
        }
        View decor = window.getDecorView();
        if (hidden) {
            // Every pass, so a page that puts the default mode back is opened up again.
            if (STATUS_BAR_HIDDEN_AT.containsKey(decor)) drawIntoCutout(window, decor);
            if (isStatusBarHidden(decor)) {
                return;
            }
            long now = SystemClock.uptimeMillis();
            if (!rehideAllowed(decor, now)) {
                return;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller == null) {
                    return;
                }
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                controller.hide(WindowInsets.Type.statusBars());
            } else {
                decor.setSystemUiVisibility(decor.getSystemUiVisibility() | LEGACY_STATUS_BAR_FLAGS);
            }
            STATUS_BAR_HIDDEN_AT.put(decor, now);
            drawIntoCutout(window, decor);
            return;
        }
        restoreCutoutMode(window, decor);
        if (STATUS_BAR_HIDDEN_AT.remove(decor) != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                WindowInsetsController controller = decor.getWindowInsetsController();
                if (controller != null) {
                    controller.show(WindowInsets.Type.statusBars());
                }
            } else {
                decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~LEGACY_STATUS_BAR_FLAGS);
            }
        }
    }

    /**
     * Lets a window whose status bar this class hid draw behind the display cutout. A window in
     * the default cutout mode is kept below a punch hole or notch whenever its status bar is
     * hidden, which left a black strip across the top of opened videos (#97). Android 15 and up
     * treat the default as drawing there for TikTok, whose theme doesn't opt out of edge to
     * edge, so those are left alone. A mode TikTok chose itself is left alone too.
     */
    static void drawIntoCutout(Window window, View decor) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P || Build.VERSION.SDK_INT >= 35) return;
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (attributes.layoutInDisplayCutoutMode
                != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT) return;
        CUTOUT_OPENED.put(decor, Boolean.TRUE);
        attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        window.setAttributes(attributes);
    }

    /**
     * Gives a window this class let draw behind the cutout its default mode back, unless TikTok
     * has set a mode of its own on it since.
     */
    static void restoreCutoutMode(Window window, View decor) {
        if (CUTOUT_OPENED.remove(decor) == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return;
        WindowManager.LayoutParams attributes = window.getAttributes();
        if (attributes.layoutInDisplayCutoutMode
                != WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES) return;
        attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
        window.setAttributes(attributes);
    }

    /**
     * False while a hide this class issued on that window is younger than the peek window: the
     * bar is either a swipe peek the system will end by itself, or the request is still landing.
     */
    static boolean rehideAllowed(View decor, long now) {
        Long hiddenAt = STATUS_BAR_HIDDEN_AT.get(decor);
        return hiddenAt == null || now - hiddenAt >= STATUS_BAR_PEEK_MS;
    }

    private static boolean isStatusBarHidden(View decor) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            WindowInsets insets = decor.getRootWindowInsets();
            // Before the first attach there is nothing to read; treat it as showing so the
            // request goes out and the layout pass after attach sees the real state.
            return insets != null && !insets.isVisible(WindowInsets.Type.statusBars());
        }
        return (decor.getSystemUiVisibility() & View.SYSTEM_UI_FLAG_FULLSCREEN) != 0;
    }

    /**
     * Hides a view and remembers it, or puts back one this class hid. A view that was
     * already gone when the switch went on is left alone on the way back, because TikTok
     * had its own reason for that.
     */
    static void setHidden(View view, boolean hidden) {
        setHidden(view, hidden, View.GONE);
    }

    /**
     * The same with INVISIBLE, which keeps the view's space: only a showing view is taken, so
     * one TikTok had already put away stays as TikTok left it.
     */
    static void setHidden(View view, boolean hidden, int hiddenAs) {
        if (view == null) {
            return;
        }

        if (hidden) {
            int visibility = view.getVisibility();
            if (hiddenAs == View.GONE ? visibility != View.GONE : visibility == View.VISIBLE) {
                HIDDEN_HERE.put(view, visibility);
                view.setVisibility(hiddenAs);
            }
            return;
        }

        Integer before = HIDDEN_HERE.remove(view);
        if (before != null && view.getVisibility() == hiddenAs) {
            view.setVisibility(before);
        }
    }

    /**
     * Makes a view see-through but leaves it on screen and touchable, or puts back one this
     * class faded. Every pass sets it again, since TikTok may fade its own bar back in.
     */
    static void setTransparent(View view, boolean transparent) {
        if (view == null) {
            return;
        }

        if (transparent) {
            float nativeAlpha = view.getAlpha();
            if (!FADED_HERE.containsKey(view) || nativeAlpha != 0f) {
                FADED_HERE.put(view, nativeAlpha);
            }
            if (nativeAlpha != 0f) view.setAlpha(0f);
            return;
        }

        Float before = FADED_HERE.remove(view);
        if (before != null && view.getAlpha() == 0f) {
            view.setAlpha(before);
        }
    }

    /**
     * Fades a view to {@code percent} of the opacity it had, or puts back one this class faded.
     * Every pass writes it again: views are recycled and re-bound, and TikTok writes alpha itself
     * (an animation ends by writing its own value back). Whether it still takes taps is
     * {@link TapThroughControls}'s business.
     */
    static void setFaded(View view, int percent) {
        setFaded(view, percent, false);
    }

    /**
     * The same, and with {@code whole} the view is drawn at {@code percent} itself rather than at
     * that share of TikTok's opacity. TikTok's Clear display takes its own to 0, and the faded
     * Clear display keeps the controls in sight at the chosen level (#84). Putting the view back
     * gives it the opacity TikTok last wrote, so in Clear display that's TikTok's cleared look.
     */
    static void setFaded(View view, int percent, boolean whole) {
        if (view == null) {
            return;
        }
        float[] held = FADED_TO.get(view);
        if (percent >= 100) {
            if (held != null) {
                FADED_TO.remove(view);
                if (view.getAlpha() == held[1]) view.setAlpha(held[0]);
            }
            return;
        }
        if (held == null) {
            held = new float[]{view.getAlpha(), view.getAlpha(), 1f, 0f, 0f};
            FADED_TO.put(view, held);
        } else if (view.getAlpha() != held[1]) {
            // TikTok wrote its own value since the last pass; that is the new look to fade.
            held[0] = view.getAlpha();
        }
        held[2] = percent / 100f;
        held[4] = whole ? 1f : 0f;
        settleWhole(view, held);
        held[1] = fadedAlpha(held);
        if (view.getAlpha() != held[1]) view.setAlpha(held[1]);
    }

    /**
     * Keeps a view at the whole level while asked, and after that until TikTok's own opacity is
     * back at full. Leaving Clear display, TikTok animates the controls up from 0, and a share of
     * that would drop them to nothing and bring them back; held, they stay where they were. The
     * hold ends at full opacity or after {@link #WHOLE_HOLD_MS}, whichever comes first.
     */
    private static void settleWhole(View view, float[] held) {
        if (held[4] != 0f || held[3] == 0f || held[0] >= 1f) {
            held[3] = held[4];
            HELD_SINCE.remove(view);
            return;
        }
        long now = SystemClock.uptimeMillis();
        Long since = HELD_SINCE.get(view);
        if (since == null) {
            HELD_SINCE.put(view, now);
        } else if (now - since > WHOLE_HOLD_MS) {
            held[3] = 0f;
            HELD_SINCE.remove(view);
        }
    }

    private static float fadedAlpha(float[] held) {
        return held[3] != 0f ? held[2] : held[0] * held[2];
    }

    /**
     * Whether a finger goes through the faded controls right now: Clear display (live, or carried
     * across a swipe) with the fade between 0 and 100 kept in it, not paused, and the overlay patch
     * in. Asked on every finger down, so it reads the live state rather than the last pass's.
     */
    static boolean tapsGoThroughNow() {
        if (HushfeedPause.isPaused() || !SettingsStatus.videoOverlaysEnabled) return false;
        if (!Settings.FADE_IN_CLEAR_DISPLAY.get()) return false;
        int level = Settings.FADE_CONTROLS_OPACITY.get();
        return level > 0 && level < 100 && (RememberClearDisplayPatch.isClearDisplayNow()
                || RememberClearDisplayPatch.isCarryingClear());
    }

    /** TikTok's Clear display, live or carried across a swipe, whatever Pause says. */
    private static boolean clearOn() {
        return RememberClearDisplayPatch.isClearDisplayNow() || RememberClearDisplayPatch.isCarryingClear();
    }

    /**
     * Called as TikTok's clear-mode event arrives, before TikTok handles it: notes which of the
     * current video's controls are showing, so the faded Clear display can keep them (#84).
     * TikTok's Clear display puts them away by visibility as well as opacity; see
     * {@link ClearDisplayShownControls}. The current video's are the ones on the screen.
     */
    public static void beforeClearDisplay(Object event) {
        if (Looper.myLooper() != Looper.getMainLooper()) return;
        Activity activity = activityReference.get();
        if (activity == null) return;
        try {
            View root = activity.findViewById(android.R.id.content);
            if (root == null) return;
            // Collection.removeIf is API 24, and the payload's floor is 23.
            List<Integer> columnIds = new ArrayList<>();
            List<Integer> frameIds = new ArrayList<>();
            // The search bar holds its row in the same kind of wrapper the rail's buttons sit in.
            for (String[] columnNames : SHOWN_COLUMN_IDS) {
                for (String columnName : columnNames) {
                    int id = resolveIdentifier(activity, activity.getPackageName(), columnName, false);
                    if (id != 0) columnIds.add(id);
                }
            }
            for (String[] frameNames : SHOWN_FRAME_IDS) {
                for (String frameName : frameNames) {
                    int id = resolveIdentifier(activity, activity.getPackageName(), frameName, false);
                    if (id != 0) frameIds.add(id);
                }
            }
            List<View> columns = new ArrayList<>();
            List<View> frames = new ArrayList<>();
            DisplayMetrics screen = activity.getResources().getDisplayMetrics();
            int[] where = new int[2];
            ArrayDeque<View> pending = new ArrayDeque<>();
            pending.add(root);
            while (!pending.isEmpty()) {
                View view = pending.removeFirst();
                int id = view.getId();
                boolean column = id != View.NO_ID && columnIds.contains(id);
                if (column || (id != View.NO_ID && frameIds.contains(id))) {
                    view.getLocationOnScreen(where);
                    if (where[0] >= 0 && where[0] < screen.widthPixels
                            && where[1] >= 0 && where[1] < screen.heightPixels) {
                        (column ? columns : frames).add(view);
                    }
                }
                if (view instanceof ViewGroup) {
                    ViewGroup group = (ViewGroup) view;
                    for (int i = 0; i < group.getChildCount(); i++) pending.add(group.getChildAt(i));
                }
            }
            ClearDisplayShownControls.note(event, columns, frames);
        } catch (Throwable ex) {
            Logger.printException(() -> "Could not note the controls Clear display takes away", ex);
        }
    }

    /** Lets a test stand in for a TikTok resource id, which only the real APK resolves. */
    static void resolveForTests(String name, int id) {
        RESOURCE_IDS.putForTests(Utils.getContext().getPackageName(), name, id);
    }

    static void resolveSearchModuleForTests(String name, int id) {
        RESOURCE_IDS.putForTests(SEARCH_MODULE_PACKAGE, name, id);
    }

    /**
     * Resolves a resource id by name once and remembers it, including a miss. The search
     * module's ids only exist once that module has loaded, so a miss for those is retried
     * rather than cached.
     */
    /** The search module's ids live in their own package, so they are counted on their own. */
    private static String overlayFamily(boolean searchModule) {
        return searchModule ? "overlay (search)" : "overlay";
    }

    private static int identifier(Activity activity, String packageName, String name) {
        boolean retryMissing = SEARCH_MODULE_PACKAGE.equals(packageName);
        int id = resolveIdentifier(activity, packageName, name, retryMissing);
        if (id != 0) {
            // A family per package rather than a composed key: this runs on every layout pass,
            // and the two id spaces can hand out the same two-character obfuscated name.
            HookStatus.bound(overlayFamily(retryMissing), name);
            return id;
        }
        if (!retryMissing) {
            HookStatus.missingViewId(overlayFamily(false), name);
        }
        return id;
    }

    private static int resolveIdentifier(Activity activity, String packageName, String name,
            boolean retryMissing) {
        return RESOURCE_IDS.resolve(
                activity == null ? null : activity.getResources(), packageName, name, retryMissing);
    }
}
