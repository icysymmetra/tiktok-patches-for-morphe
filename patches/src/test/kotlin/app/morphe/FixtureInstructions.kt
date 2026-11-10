package app.morphe

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions as patcherAddInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.builder.BuilderInstruction

// Fixture assembly includes payload tables, which the checked production helper refuses.
// Preserve the old append position with Patcher 2's explicit-index String API.
internal fun MutableMethod.appendFixtureInstructions(smali: String) =
    patcherAddInstructions(implementation!!.instructions.size, smali)

internal fun MutableMethod.appendFixtureInstructions(instructions: List<BuilderInstruction>) =
    patcherAddInstructions(instructions)
