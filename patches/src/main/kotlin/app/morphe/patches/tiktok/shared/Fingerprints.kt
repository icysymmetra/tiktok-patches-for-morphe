/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/shared/Fingerprints.kt
 */
package app.morphe.patches.tiktok.shared

import app.morphe.patcher.Fingerprint

/**
 * The render event handler keeps its name; the event class it takes does not. It was `LX/0pb0;`
 * on 46.2.3, `LX/0pQO;` on 46.7.3 and `LX/0rog;` on 46.8.3, so the one-parameter shape is the
 * anchor and the parameter's type is read off the match by whoever needs it.
 */
internal object OnRenderFirstFrameFingerprint : Fingerprint(
    definingClass = "/feed/controller/PlayerController;",
    name = "onRenderFirstFrame",
    returnType = "V",
    custom = { method, _ -> method.parameterTypes.size == 1 },
)
