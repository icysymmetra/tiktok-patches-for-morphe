package app.morphe.patches.tiktok.interaction.story

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val stealthStoryViewPatch = bytecodePatch(
    name = "Stealth story view",
    description = "Disables outgoing story view beacons to allow viewing stories without appearing in the author's viewer list.",
    default = true,
) {
    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        StoryViewReportFingerprint.method.apply {
            val instructions = implementation!!.instructions

            val reportIndex = indexOfFirstInstructionOrThrow {
                val reference = (this as? ReferenceInstruction)?.reference as? MethodReference
                reference?.definingClass == "Lcom/ss/android/ugc/aweme/story/api/StoryApi;" &&
                    reference.name == "reportStoryViewed"
            }

            // Find the downstream subscription call that consumes the Rx observable
            var targetIndex = -1
            for (i in (reportIndex + 1) until instructions.size) {
                val inst = instructions[i]
                if (inst.opcode == Opcode.INVOKE_VIRTUAL || inst.opcode == Opcode.INVOKE_INTERFACE) {
                    val ref = (inst as? ReferenceInstruction)?.reference as? MethodReference
                    if (ref?.returnType == "LX/070p;" || ref?.parameterTypes?.size == 2) {
                        targetIndex = i + 1
                        break
                    }
                }
            }

            // Fallback: If exact subscriber signature changes, jump past the immediate chain
            val landingIndex = if (targetIndex != -1 && targetIndex < instructions.size) targetIndex else reportIndex + 4

            addInstructionsWithLabels(
                reportIndex,
                """
                    goto :story_stealth_skip
                """,
                ExternalLabel("story_stealth_skip", getInstruction(landingIndex)),
            )
        }
    }
}
