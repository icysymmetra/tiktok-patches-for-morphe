/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.searchfeatures

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.absettings.hookAppAbIntBoundary
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val FEATURE_CONTROLS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

private fun BytecodePatchContext.patchBooleanGate(
    fingerprint: Fingerprint,
    extensionMethod: String,
) {
    fingerprint.method.apply {
        val returns = implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN }
            .map { it.index }
        // A gate with no boolean return, because it throws or boxes its answer, would leave
        // the switch applied and doing nothing.
        check(returns.isNotEmpty()) {
            "Search features: $definingClass->$name has no boolean return to hook for $extensionMethod."
        }
        returns.asReversed()
            .forEach { returnIndex ->
                // The range form names any register a return can hold, so a wide frame is no
                // reason to refuse the gate.
                val register = getInstruction<OneRegisterInstruction>(returnIndex).registerA
                addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                        invoke-static/range {v$register .. v$register}, $FEATURE_CONTROLS_DESCRIPTOR->$extensionMethod(Z)Z
                        move-result v$register
                    """,
                )
            }
    }
}

@Suppress("unused")
val enableNonPersonalizedSearchPatch = bytecodePatch(
    name = "Use non-personalized search",
    description = "Shows search results that aren't tailored to your account. Starts off. " +
        "Turn it on in Metra settings > App behavior.",
    default = true,
) {
    category("Search")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

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
    name = "Show LIVE search",
    description = "Adds TikTok's search button inside the LIVE section where TikTok supports " +
        "it, so you can look for LIVEs. Starts off. Turn it on in Metra settings > App behavior.",
    default = true,
) {
    category("Search")
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLiveSearch()V",
        )
        patchBooleanGate(LiveSearchGateFingerprint, "enableLiveSearch")
    }
}
