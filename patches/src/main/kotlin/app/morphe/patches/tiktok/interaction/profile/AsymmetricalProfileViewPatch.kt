package app.morphe.patches.tiktok.interaction.profile

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * Bytecode patch to suppress profile view beacons (Asymmetrical Profile View).
 *
 * Technical Flow:
 * Identifies the reportView() invocation inside ProfilePlatformViewModel,
 * backtracks to safely include the singleton field access, and jumps past
 * the downstream reactive terminal consumer (LJJLL / subscribe), landing cleanly
 * before subsequent flow instructions while maintaining Dalvik/ART verifier integrity.
 */
@Suppress("unused")
val asymmetricalProfileViewPatch = bytecodePatch(
    name = "Asymmetrical profile view",
    description = "Disables outgoing profile view beacons to allow browsing user profiles without appearing in their viewer history.",
    default = true,
) {
    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        val method = ProfileViewReportFingerprint.method
        val implementation = method.implementation
            ?: throw PatchException("Method implementation is null for ProfileViewReportFingerprint.")
        val instructions = implementation.instructions

        if (instructions.isEmpty()) {
            throw PatchException("Method instructions are empty. Cannot apply profile stealth patch.")
        }

        with(method) {
            // Step 1: Locate reportView call directly (immune to obfuscated field names)
            val reportIndex = indexOfFirstInstructionOrThrow {
                val reference = (this as? ReferenceInstruction)?.reference as? MethodReference
                reference?.definingClass == "Lcom/ss/android/ugc/profile/business/ci/viewer/api/ProfileViewerApiService;" &&
                    reference.name == "reportView"
            }

            // Backtrack to skip singleton access if present immediately before reportView
            val jumpOriginIndex = if (reportIndex > 0 && instructions[reportIndex - 1].opcode == Opcode.SGET_OBJECT) {
                reportIndex - 1
            } else {
                reportIndex
            }

            val reportMethodRef = (instructions[reportIndex] as? ReferenceInstruction)?.reference as? MethodReference
            val streamReturnType = reportMethodRef?.returnType // Dynamically resolves to LX/0zX6;

            // Step 2: Dynamically find downstream reactive consumer (LJJLL / subscribe)
            var landingIndex = -1

            for (i in (reportIndex + 1) until instructions.size) {
                val currentInst = instructions[i]

                if (currentInst.opcode !in setOf(
                        Opcode.INVOKE_STATIC,
                        Opcode.INVOKE_VIRTUAL,
                        Opcode.INVOKE_INTERFACE,
                        Opcode.INVOKE_DIRECT,
                        Opcode.INVOKE_SUPER,
                    )
                ) {
                    continue
                }

                val ref = (currentInst as? ReferenceInstruction)?.reference as? MethodReference
                if (ref != null) {
                    val isExplicitSubscriber = ref.name in setOf(
                        "subscribe",
                        "subscribeWith",
                        "subscribeActual",
                        "doFinally",
                    )

                    val isObfuscatedConsumer = streamReturnType != null &&
                        ref.definingClass == streamReturnType &&
                        ref.returnType != streamReturnType &&
                        ref.parameterTypes.size in 1..2

                    val isKnownFallback = ref.returnType == "LX/070p;" || ref.name == "LJJLL"

                    if (isExplicitSubscriber || isObfuscatedConsumer || isKnownFallback) {
                        val nextIndex = i + 1
                        if (nextIndex >= instructions.size) {
                            throw PatchException("Terminal consumer at index $i has no following instruction.")
                        }

                        val nextInst = instructions[nextIndex]

                        // ART Verifier Safety: Never land directly on a move-result-* instruction
                        if (nextInst.opcode in setOf(
                                Opcode.MOVE_RESULT,
                                Opcode.MOVE_RESULT_WIDE,
                                Opcode.MOVE_RESULT_OBJECT,
                            )
                        ) {
                            landingIndex = nextIndex + 1
                        } else {
                            landingIndex = nextIndex
                        }
                        break
                    }
                }
            }

            // Step 3: Strict validation (No blind guessing offsets)
            if (landingIndex <= 0 || landingIndex >= instructions.size) {
                throw PatchException(
                    "Could not safely resolve landing site after reportView at index $reportIndex. Aborting injection."
                )
            }

            // Step 4: Inject unconditional bypass
            addInstructionsWithLabels(
                jumpOriginIndex,
                """
                    goto :profile_stealth_skip
                """,
                ExternalLabel("profile_stealth_skip", getInstruction(landingIndex)),
            )
        }
    }
}

