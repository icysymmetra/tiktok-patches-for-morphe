package app.morphe.patches.tiktok.interaction.profile

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val asymmetricalProfileViewPatch = bytecodePatch(
    name = "Asymmetrical profile view",
    description = "Disables outgoing profile view beacons to allow browsing user profiles without appearing in their viewer history.",
    default = true,
) {
    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        ProfileViewReportFingerprint.method.apply {
            val instructions = implementation!!.instructions

            // Find the singleton field access immediately preceding the reportView call
            val singletonIndex = indexOfFirstInstructionOrThrow {
                val fieldRef = (this as? ReferenceInstruction)?.reference as? FieldReference
                fieldRef?.definingClass == "Lcom/ss/android/ugc/profile/business/ci/viewer/api/ProfileViewerApiService;" &&
                    fieldRef.name == "LIZIZ"
            }

            // Find the downstream subscription call (LX/070p; return type)
            var targetIndex = -1
            for (i in singletonIndex until instructions.size) {
                val inst = instructions[i]
                if (inst.opcode == Opcode.INVOKE_VIRTUAL || inst.opcode == Opcode.INVOKE_INTERFACE) {
                    val ref = (inst as? ReferenceInstruction)?.reference as? MethodReference
                    if (ref?.returnType == "LX/070p;" || ref?.parameterTypes?.size == 2) {
                        targetIndex = i + 1
                        break
                    }
                }
            }

            val landingIndex = if (targetIndex != -1 && targetIndex < instructions.size) targetIndex else singletonIndex + 6

            addInstructionsWithLabels(
                singletonIndex,
                """
                    goto :profile_stealth_skip
                """,
                ExternalLabel("profile_stealth_skip", getInstruction(landingIndex)),
            )
        }
    }
}
