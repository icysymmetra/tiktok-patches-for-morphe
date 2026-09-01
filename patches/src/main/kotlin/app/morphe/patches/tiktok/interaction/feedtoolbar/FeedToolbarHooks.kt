/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.feedtoolbar

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val FEATURE_CONTROLS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

internal fun MutableMethod.overrideToolbarButtonEnabled(extensionMethodName: String) {
    implementation!!.instructions.withIndex()
        .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN }
        .map { (index, _) -> index }
        .asReversed()
        .forEach { returnIndex ->
            val returnRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
            addInstructions(
                returnIndex,
                """
                    invoke-static {v$returnRegister}, $FEATURE_CONTROLS_DESCRIPTOR->$extensionMethodName(Z)Z
                    move-result v$returnRegister
                """,
            )
        }
}
