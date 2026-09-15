package app.morphe.patches.tiktok.interaction.story

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
 * Bytecode patch to suppress story view reporting beacons.
 *
 * Technical Flow:
 * Locates the reportStoryViewed() API call and injects an unconditional goto
 * jumping past the reactive terminal subscription chain (e.g. LJJIIZ / subscribe),
 * landing safely prior to subsequent execution flow while strictly adhering to
 * Dalvik/ART bytecode verification rules regarding move-result instructions.
 */
@Suppress("unused")
val stealthStoryViewPatch = bytecodePatch(
    name = "Stealth story view",
    description = "Disables outgoing story view beacons to allow viewing stories without appearing in the author's viewer list.",
    default = true,
) {
    compatibleWith(*AppCompatibilities.tiktok4623())

    execute {
        val method = StoryViewReportFingerprint.method
        val implementation = method.implementation
            ?: throw PatchException("Method implementation is null for StoryViewReportFingerprint.")
        val instructions = implementation.instructions

        if (instructions.isEmpty()) {
            throw PatchException("Method instructions are empty. Cannot apply stealth story patch.")
        }

        with(method) {
            // Step 1: Locate reportStoryViewed invocation
            val reportIndex = indexOfFirstInstructionOrThrow {
                val reference = (this as? ReferenceInstruction)?.reference as? MethodReference
                reference?.definingClass == "Lcom/ss/android/ugc/aweme/story/api/StoryApi;" &&
                    reference.name == "reportStoryViewed"
            }

            val reportMethodRef = (instructions[reportIndex] as? ReferenceInstruction)?.reference as? MethodReference
            val streamReturnType = reportMethodRef?.returnType // Dynamically resolves to LX/0zTi;

            // Step 2: Scan forward for the terminal subscription operator (LJJIIZ or subscribe)
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
                    // Match either standard RxJava names OR obfuscated stream consumer (takes callbacks & terminates stream)
                    val isExplicitSubscriber = ref.name in setOf(
                        "subscribe",
                        "subscribeWith",
                        "subscribeActual",
                        "doFinally",
                    )

                    val isObfuscatedSubscriber = streamReturnType != null &&
                        ref.definingClass == streamReturnType &&
                        ref.returnType != streamReturnType &&
                        ref.parameterTypes.size in 1..2

                    val isKnownFallback = ref.returnType == "LX/070p;" || ref.name == "LJJIIZ"

                    if (isExplicitSubscriber || isObfuscatedSubscriber || isKnownFallback) {
                        val nextIndex = i + 1
                        if (nextIndex >= instructions.size) {
                            throw PatchException("Terminal operator at index $i has no following instruction.")
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

            // Step 3: Strict bounds validation
            if (landingIndex <= 0 || landingIndex >= instructions.size) {
                throw PatchException(
                    "Could not safely determine terminal subscription endpoint after reportStoryViewed at index $reportIndex."
                )
            }

            // Step 4: Inject unconditional bypass
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

