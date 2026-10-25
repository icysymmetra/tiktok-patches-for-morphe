/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.inbox;

import android.view.View;
import android.view.ViewGroup;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.blockauthor.Reflect;
import app.morphe.extension.tiktok.settings.Settings;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * Collapses TikTok's suggestion cells wherever they are bound.
 *
 * <p>"People you may like" has no one source. The inbox, Activity and New followers lists
 * have injectors that {@link InboxControls} answers, but the profile header, the Friends tab
 * and the feed's account cards each build their own list, and every one of those lists is
 * made of cells that extend {@code AbsRecUserCell}. Its bind is the one place they all pass
 * through, and this is called at the end of it with the cell, after TikTok has laid the cell
 * out: the Rect cells write their own top margin inside the bind, so a hook ahead of it would
 * have been undone. The cell is a RecyclerView ViewHolder, so its view is the real-named
 * {@code itemView}.
 *
 * <p>The cell is collapsed to nothing rather than only hidden: a GONE child of a horizontal
 * list still holds its width in some layouts, and its margins hold space in every layout.
 * Size, margins and visibility are remembered so a cell recycled after the switch goes off
 * comes back exactly as TikTok had it, invisible cells included.
 */
public final class SuggestedAccountCells {
    private static final String HOOK_FAMILY = "suggested accounts";

    /**
     * What a cell was before it was collapsed: width, height, visibility, followed by the four
     * margins only when the original params carried them. Negative margins are valid values.
     */
    private static final Map<View, int[]> COLLAPSED = new WeakHashMap<>();

    private SuggestedAccountCells() {
    }

    /** Called at the end of every suggestion cell's bind, with the cell. */
    public static void onBind(Object cell) {
        try {
            Object view = Reflect.readField(cell, "itemView");
            if (!(view instanceof View)) return;
            apply((View) view, Settings.HIDE_INBOX_SUGGESTED_ACCOUNTS.get());
        } catch (Throwable error) {
            Logger.printException(() -> "Could not collapse a suggestion cell", error);
        }
    }

    /** Collapses the view, or puts back what a collapse took, and says which it did. */
    static boolean apply(View view, boolean hide) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        ViewGroup.MarginLayoutParams margins = params instanceof ViewGroup.MarginLayoutParams
                ? (ViewGroup.MarginLayoutParams) params : null;
        synchronized (COLLAPSED) {
            int[] original = COLLAPSED.get(view);
            if (hide) {
                if (original == null && params != null) {
                    COLLAPSED.put(view, margins == null
                            ? new int[]{params.width, params.height, view.getVisibility()}
                            : new int[]{params.width, params.height, view.getVisibility(),
                                    margins.leftMargin, margins.topMargin,
                                    margins.rightMargin, margins.bottomMargin});
                }
                if (params != null) {
                    params.width = 0;
                    params.height = 0;
                    if (margins != null) margins.setMargins(0, 0, 0, 0);
                    view.setLayoutParams(params);
                }
                view.setVisibility(View.GONE);
                return true;
            }
            if (original == null) return false;
            COLLAPSED.remove(view);
            if (params != null) {
                params.width = original[0];
                params.height = original[1];
                if (margins != null && original.length == 7) {
                    margins.setMargins(original[3], original[4], original[5], original[6]);
                }
                view.setLayoutParams(params);
            }
            view.setVisibility(original[2]);
            return false;
        }
    }
}
