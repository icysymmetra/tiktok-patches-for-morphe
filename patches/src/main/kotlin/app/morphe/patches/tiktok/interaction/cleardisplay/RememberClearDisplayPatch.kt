/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/interaction/cleardisplay/RememberClearDisplayPatch.kt
 */
package app.morphe.patches.tiktok.interaction.cleardisplay

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly

@Suppress("unused")
val rememberClearDisplayPatch = bytecodePatch(
    name = "Remember clear display",
    description = "Remembers TikTok's clear-display state between videos.",
    default = true,
) {
    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        // Prevent excessive logging in high-frequency feed paths.
        ClearModeLogCoreFingerprint.methodOrNull?.returnEarly()
        ClearModeLogStateFingerprint.methodOrNull?.returnEarly()
        ClearModeLogPlaytimeFingerprint.methodOrNull?.returnEarly()

        OnClearDisplayEventFingerprint.method.let { method ->
            val eventClass = method.parameters[0].type
            val eventRegister = method.implementation!!.registerCount - method.parameters.size
            method.addInstructions(
                0,
                "invoke-static/range {v$eventRegister .. v$eventRegister}, " +
                    "Lapp/morphe/extension/tiktok/cleardisplay/RememberClearDisplayPatch;->rememberClearDisplayEvent(Ljava/lang/Object;)V",
            )

            val clearDisplayEventClass = eventClass
            OnRenderFirstFrameBodyFingerprint.method.addInstructions(
                0,
                """
                    invoke-static {}, Lapp/morphe/extension/tiktok/cleardisplay/RememberClearDisplayPatch;->getClearDisplayState()Z
                    move-result v1

                    if-eqz v1, :clear_display_disabled

                    const/4 v2, 0x0
                    const-string v3, ""
                    const-string v4, "long_press"

                    new-instance v0, $clearDisplayEventClass
                    invoke-direct {v0, v1, v2, v3, v4}, $clearDisplayEventClass-><init>(ZILjava/lang/String;Ljava/lang/String;)V
                    invoke-virtual {v0}, $clearDisplayEventClass->post()Lcom/ss/android/ugc/governance/eventbus/IEvent;

                    :clear_display_disabled
                    nop
                """,
            )
        }
    }
}

