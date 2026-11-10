/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.extension.tiktok.blockauthor;

import android.content.Context;

import app.morphe.extension.tiktok.settings.L10n;

/** Localized UI sentences for the typed block transport outcomes. */
public final class BlockAuthorMessages {
    private BlockAuthorMessages() {
    }

    public static String blockFailure(
            Context context,
            BlockAuthorService.Result result,
            String author
    ) {
        if (result == BlockAuthorService.Result.REJECTED) {
            return L10n.f(context,
                    "TikTok didn't block %1$s. Try again in a moment.", author);
        }
        return L10n.f(context, "Couldn't confirm the block for %1$s. Open their profile to check.", author);
    }

    public static String unblockResult(
            Context context,
            BlockAuthorService.Result result,
            String author
    ) {
        if (result == BlockAuthorService.Result.CONFIRMED) {
            return L10n.f(context, "Unblocked %1$s", author);
        }
        if (result == BlockAuthorService.Result.REJECTED) {
            return L10n.f(context,
                    "TikTok didn't unblock %1$s. Try again in a moment.", author);
        }
        return L10n.f(context, "Couldn't confirm the unblock for %1$s. Open their profile to check.", author);
    }
}
