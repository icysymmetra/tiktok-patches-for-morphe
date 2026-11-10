/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.feedtoolbar

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val FEATURE_CONTROLS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

internal fun MutableMethod.overrideToolbarButtonEnabled(extensionMethodName: String) {
    val returnIndices = implementation!!.instructions.withIndex()
        .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN }
        .map { (index, _) -> index }

    if (returnIndices.isEmpty()) {
        throw PatchException(
            "Found no return to wrap in $this, so $extensionMethodName would never run.",
        )
    }

    returnIndices
        .asReversed()
        .forEach { returnIndex ->
            val returnRegister = getInstruction<OneRegisterInstruction>(returnIndex).registerA
            addInstructionsAtControlFlowLabel(
                returnIndex,
                """
                    invoke-static/range { v$returnRegister .. v$returnRegister }, $FEATURE_CONTROLS_DESCRIPTOR->$extensionMethodName(Z)Z
                    move-result v$returnRegister
                """,
            )
        }
}
