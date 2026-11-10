/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.quickactions

import app.morphe.util.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val FEATURE_CONTROLS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

@Suppress("unused")
val disableLongPressQuickSharePatch = bytecodePatch(
    name = "Disable the long press quick share",
    description = "Stops a long press on Share from opening TikTok's quick share, so you don't send a video by accident. Starts off. Turn it on in Metra settings > Interface.",
    default = true,
) {
    category("Interaction")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableDisableLongPressQuickShare()V",
        )

        resolveLongPressQuickShareGate().apply {
            // The register the gate is actually returning in, and every return of it. v0 was
            // right only because this compiles to a single register today, and nothing said so.
            findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN }.forEach { returnIndex ->
                val gateRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                if (gateRegister > 15) {
                    throw PatchException(
                        "Long press quick share: the gate returns in v$gateRegister, which a " +
                            "plain invoke cannot name.",
                    )
                }
                addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                        invoke-static {v$gateRegister}, $FEATURE_CONTROLS_DESCRIPTOR->overrideLongPressQuickShare(I)I
                        move-result v$gateRegister
                    """,
                )
            }
        }
    }
}
