/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.searchfeatures

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.absettings.hookAppAbIntBoundary
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val FEATURE_CONTROLS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

private fun BytecodePatchContext.patchBooleanGate(
    fingerprint: Fingerprint,
    extensionMethod: String,
) {
    fingerprint.method.apply {
        implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN }
            .map { it.index }
            .asReversed()
            .forEach { returnIndex ->
                val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                addInstructions(
                    returnIndex,
                    """
                        invoke-static {v$register}, $FEATURE_CONTROLS_DESCRIPTOR->$extensionMethod(Z)Z
                        move-result v$register
                    """,
                )
            }
    }
}

@Suppress("unused")
val enableNonPersonalizedSearchPatch = bytecodePatch(
    name = "Enable non-personalized search",
    description = "Uses TikTok's non-personalized search mode instead of its saved account choice.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableNonPersonalizedSearch()V",
        )
        patchBooleanGate(NonPersonalizedSearchGateFingerprint, "enableNonPersonalizedSearch")
        patchBooleanGate(NonPersonalizedSearchStateFingerprint, "enableNonPersonalizedSearch")
        hookAppAbIntBoundary(
            FEATURE_CONTROLS_DESCRIPTOR,
            "forceNonPersonalizedSearchGate",
        )
    }
}

@Suppress("unused")
val enableLiveSearchPatch = bytecodePatch(
    name = "Enable Live search",
    description = "Shows TikTok's search entry in the Live drawer where supported.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLiveSearch()V",
        )
        patchBooleanGate(LiveSearchGateFingerprint, "enableLiveSearch")
    }
}
