/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.translation;

/** Retire both preview generations before the native account broadcaster notifies its listeners. */
@SuppressWarnings("unused")
public final class DmTranslationAccountEvents {
    public static void changed() {
        Runnable draft = DmTranslationPreview.retireAccount();
        Runnable received = ReceivedDmTranslationPreview.retireAccount();
        // A synchronous request cancellation or dialog dismissal can reenter the other preview.
        try { draft.run(); }
        finally { received.run(); }
    }

    public static void switchResult(boolean success) {
        if (success) changed();
    }

    private DmTranslationAccountEvents() {}
}
