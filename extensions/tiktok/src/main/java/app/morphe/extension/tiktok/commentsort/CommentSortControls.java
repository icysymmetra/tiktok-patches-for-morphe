package app.morphe.extension.tiktok.commentsort;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

@SuppressWarnings("unused")
public final class CommentSortControls {
    private static final int FULL_SORT_SHEET_STYLE = 2;

    private CommentSortControls() {
    }

    public static int forceOptionStyle(int originalStyle) {
        boolean force = Settings.COMMENT_SORT_FORCE_SHOW.get();
        if (force) {
            Logger.printDebug(() -> "[Morphe CommentSort] comment_sort_opt_style "
                    + originalStyle + " -> " + FULL_SORT_SHEET_STYLE);
        }
        return force ? FULL_SORT_SHEET_STYLE : originalStyle;
    }

    public static boolean shouldForceSortEligibility() {
        boolean force = Settings.COMMENT_SORT_FORCE_SHOW.get();
        if (force) {
            Logger.printDebug(() -> "[Morphe CommentSort] overriding native author eligibility gate");
        }
        return force;
    }
}
