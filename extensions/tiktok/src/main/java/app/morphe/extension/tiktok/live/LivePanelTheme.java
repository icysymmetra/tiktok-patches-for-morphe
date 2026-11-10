/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.live;

import app.morphe.extension.shared.Logger;

import android.app.Activity;
import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** The theme LIVE panels inflate with while Dark LIVE panels is on in dark mode (#159). */
@SuppressWarnings("unused")
public final class LivePanelTheme {
    /** The base of every themed LIVE dialog (LiveDialogFragment and the rest). */
    static final String THEMED_DIALOG = "com.bytedance.android.live.design.resource.theme.LiveThemeAwareDialogFragment";
    /** The base of LIVE's sheet pages: the fan club sheets and most newer LIVE panels. */
    static final String SHEET = "com.bytedance.android.live.design.view.sheet.LiveSheetFragment";
    /** The daily ranking, whose sheet backgrounds are fixed light drawables it sets itself. */
    static final String RANK_LIST = "com.bytedance.android.live.rank.impl.list.RankListDialog";
    /** The trending ranking's first-time guide over the daily ranking, drawn on a fixed light image. */
    static final String RANK_GUIDE = "com.bytedance.android.live.rank.impl.list.view.PopularityRankingEducationDialog";
    private static final Set<String> STOCK_PANELS = new HashSet<>(Arrays.asList(RANK_LIST, RANK_GUIDE));

    private static final Map<Class<?>, Boolean> PANEL_TYPES = new ConcurrentHashMap<>();
    private static volatile boolean overlayFailureLogged;

    private LivePanelTheme() {
    }

    /**
     * Called with each layout inflater a themed LIVE dialog or LIVE fragment hands its views,
     * with the dialog or fragment. TikTok gives a LIVE panel its light colors by inflating it
     * through its light LIVE theme, applied over the room's own with force. The dark one it only
     * adds to the room's theme without force, so every color the room's light theme already set
     * wins and the panel stays white, which is what the S25 showed with only the provider
     * answering dark (#159). While Dark LIVE panels is on and the panel's context is in dark
     * mode, the panel's views inflate through its own theme overlay, which the provider hook
     * makes TikTok's dark LIVE theme, applied with force the way TikTok applies the light one. A
     * room's own fragments aren't panels and keep TikTok's inflater, and anything that fails
     * hands TikTok's back.
     */
    public static LayoutInflater darkLivePanel(Object panel, LayoutInflater inflater) {
        if (panel == null || inflater == null) {
            return inflater;
        }
        try {
            Context context = inflater.getContext();
            if (!LiveControls.darkLivePanels(context) || !isPanel(panel.getClass())) {
                return inflater;
            }
            int overlay = themeOverlay(panel, context);
            if (overlay == 0) {
                return inflater;
            }
            return inflater.cloneInContext(new ContextThemeWrapper(context, overlay));
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not darken a LIVE panel", failure);
            return inflater;
        }
    }

    /**
     * Called by the LIVE room activity's setTheme right after its last room theme. The room
     * ignores the theme it's handed and builds its own out of light styles applied with force,
     * each sitting on TikTok's light app theme, so TikTok's generic sheets over a room (Share, a
     * business room's Contact sheet) open white in a dark app, and the panel overlay never
     * reaches them (#159). While Dark LIVE panels is on and TikTok itself is in dark mode,
     * TikTok's dark app theme goes on over them with force, as TikTok themes its other screens
     * in dark mode. The room's color scheme styles still apply after it.
     */
    public static void darkLiveRoom(Activity room) {
        darkLiveRoom(room, darkAppTheme());
    }

    static void darkLiveRoom(Activity room, int theme) {
        if (room == null) {
            return;
        }
        try {
            if (theme == 0 || !LiveControls.darkLivePanels(room)) {
                return;
            }
            room.getTheme().applyStyle(theme, true);
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not darken a LIVE room", failure);
        }
    }

    /**
     * The style TikTok gives its screens in dark mode, 0 when unknown. The patch rewrites this to
     * return the style it read from TikTok's own dark mode code.
     */
    static int darkAppTheme() {
        return 0;
    }

    /**
     * Called by the LIVE profile card with the color it picked for a translated bio's "See
     * original" link and the bio's view. The card's theme already gives the dark theme's light
     * link color, but the link is drawn through a replacement span, which skips color spans and
     * paints with the view's own text color, the light theme's dark one, so the link read dark
     * on the dark card (#159). While Dark LIVE panels is on in dark mode, the view's text color
     * becomes the link color. The bio's own text keeps the color its span gives it.
     */
    public static void bioLinkColor(int color, Object bio) {
        try {
            if (bio instanceof TextView && LiveControls.darkLivePanels(((TextView) bio).getContext())) {
                ((TextView) bio).setTextColor(color);
            }
        } catch (Throwable failure) {
            Logger.printException(() -> "Could not color a LIVE bio's link", failure);
        }
    }

    /**
     * True for a themed LIVE dialog or a LIVE sheet page, by TikTok's own class names. A panel
     * that paints its own fixed light backgrounds stays stock, subclasses included, or the
     * overlay's light text lands on white: the daily ranking's title and its guide's text (#159).
     */
    static boolean isPanel(Class<?> type) {
        Boolean known = PANEL_TYPES.get(type);
        if (known != null) {
            return known;
        }
        boolean panel = false;
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            String name = current.getName();
            if (STOCK_PANELS.contains(name)) {
                break;
            }
            if (THEMED_DIALOG.equals(name) || SHEET.equals(name)) {
                panel = true;
                break;
            }
        }
        PANEL_TYPES.put(type, panel);
        return panel;
    }

    /**
     * The panel's own LIVE theme overlay (getThemeOverlay), 0 when it has none. A panel that
     * names its own overlay gets that one again, so only the default one turns dark.
     */
    static int themeOverlay(Object panel, Context context) {
        try {
            Method method = panel.getClass().getMethod("getThemeOverlay", Context.class);
            Object overlay = method.invoke(panel, context);
            return overlay instanceof Integer ? (Integer) overlay : 0;
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (!overlayFailureLogged) {
                overlayFailureLogged = true;
                Logger.printException(() -> "A LIVE panel has no theme overlay to darken it with", failure);
            }
            return 0;
        }
    }
}
