/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.profile;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.diagnostics.HookStatus;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * The switch in front of TikTok's profile background gate (#80).
 *
 * <p>TikTok ships the whole profile background feature, the editor under Edit profile included,
 * and decides per account with one gate in its profile background experiment (31744). The editor
 * asks it with true and needs the account on the server's allow list; a profile asks it with
 * false to decide whether to show a background. With the switch on the gate skips the allow list
 * both ways and answers with TikTok's own left-aligned avatar layout check, which every
 * background is drawn into, so the editor TikTok already has opens wherever that layout is on.
 * Whether a background saved that way is kept is
 * the server's call: saving goes to TikTok's own commit endpoint, which may refuse an account
 * outside the rollout. With the switch off, and while Hushfeed is paused, the gate runs as TikTok
 * ships it.
 *
 * <p>The gate alone wasn't enough: TikTok's own profile and other profiles are fetched with the
 * account's allow-list value, snapshotted once per account and process, and the server leaves the
 * background out of a profile fetched with 0. So a saved background was there until the next
 * start (0.72.0). The switch answers that read with 1 as well, the value the server sets for an
 * account in the rollout and the one Feature Gate Lab's Profile banner preset forces.
 */
@SuppressWarnings("unused")
public final class ProfileBackground {
    private static final String HOOK_FAMILY = "profile background";

    private ProfileBackground() {
    }

    /**
     * True while the gate should answer yes. {@code editor} is the gate's own argument, true when
     * Edit profile asks. It only goes into the export, whose count then says whether the editor,
     * a profile or both reached the hook.
     */
    public static boolean openGate(boolean editor) {
        HookStatus.bound(HOOK_FAMILY, editor ? "editor" : "profile");
        // A profile can't be built before the application context exists, but loading Settings
        // without one throws inside TikTok, so this never assumes it.
        return Utils.getContext() != null && Settings.PROFILE_BACKGROUND.get();
    }

    /** The allow-list value TikTok read for the signed-in account, as the profile requests send it. */
    public static int allowList(int original) {
        HookStatus.bound(HOOK_FAMILY, "allow list");
        return original != 1 && Utils.getContext() != null && Settings.PROFILE_BACKGROUND.get() ? 1 : original;
    }
}
