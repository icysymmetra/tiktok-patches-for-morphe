/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/interaction/speed/PlaybackSpeedPatch.kt
 */
package app.morphe.patches.tiktok.interaction.speed

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.shared.OnRenderFirstFrameFingerprint
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val playbackSpeedPatch = bytecodePatch(
    name = "Playback speed",
    description = "Enables playback-speed controls for all videos and remembers the selected speed between videos.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        PlaybackSpeedSelectionBoundaryFingerprint.method.addInstruction(
            0,
            "invoke-static {p0, p3}, " +
                "Lapp/morphe/extension/tiktok/speed/PlaybackSpeedPatch;->rememberPlaybackSpeed(FLjava/lang/String;)V",
        )

        val controllerSetSpeed = PlayerControllerSetSpeedFingerprint.method
        // The player-manager speed setter was named setSpeed in 46.2.3 and is obfuscated in 47.1.4,
        // so resolve it by shape: the last interface call taking a single float.
        val playerManagerSetSpeed = controllerSetSpeed.implementation!!.instructions
            .mapNotNull { instruction ->
                instruction.getReference<MethodReference>()?.takeIf { reference ->
                    instruction.opcode == Opcode.INVOKE_INTERFACE &&
                        reference.parameterTypes == listOf("F") &&
                        reference.returnType == "V"
                }
            }.lastOrNull() ?: throw PatchException(
            "Playback speed: player-manager speed boundary was not resolved.",
        )

        val playerControllerClass = mutableClassDefBy(controllerSetSpeed.definingClass)
        val transitionResetCandidates = playerControllerClass.methods.filter { method ->
            method.parameterTypes == listOf(
                "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
                "Z",
            ) && method.returnType == "V" && method.implementation?.instructions?.count { instruction ->
                instruction.getReference<MethodReference>() == playerManagerSetSpeed
            } == 3
        }
        val transitionReset = transitionResetCandidates.singleOrNull() ?: throw PatchException(
            "Playback speed: expected one three-branch feed transition reset, " +
                "found ${transitionResetCandidates.size}.",
        )

        val transitionCallers = playerControllerClass.methods.filter { method ->
            val instructions = method.implementation?.instructions ?: return@filter false
            val callsTransitionReset = instructions.any { instruction ->
                instruction.getReference<MethodReference>()?.let { reference ->
                    reference.definingClass == transitionReset.definingClass &&
                        reference.name == transitionReset.name &&
                        reference.parameterTypes == transitionReset.parameterTypes &&
                        reference.returnType == transitionReset.returnType
                } == true
            }
            callsTransitionReset && instructions.any { instruction ->
                instruction.getReference<MethodReference>() == playerManagerSetSpeed
            }
        }
        if (transitionCallers.isEmpty()) {
            throw PatchException(
                "Playback speed: no feed transition caller with a direct speed write was found.",
            )
        }

        (listOf(transitionReset) + transitionCallers).forEach { method ->
            val directCalls = method.implementation!!.instructions.withIndex().mapNotNull { (index, instruction) ->
                if (instruction.getReference<MethodReference>() != playerManagerSetSpeed) {
                    return@mapNotNull null
                }
                val speedRegister = when (instruction) {
                    is FiveRegisterInstruction -> instruction.registerD
                    is RegisterRangeInstruction -> instruction.startRegister + 1
                    else -> throw PatchException(
                        "Playback speed: unsupported feed transition speed invocation shape.",
                    )
                }
                index to speedRegister
            }
            directCalls.asReversed().forEach { (index, speedRegister) ->
                method.addInstructions(
                    index,
                    """
                        invoke-static {v$speedRegister}, Lapp/morphe/extension/tiktok/speed/PlaybackSpeedPatch;->preserveTransitionSpeed(F)F
                        move-result v$speedRegister
                    """,
                )
            }
        }

        // The controller setter is named setSpeed in 46.2.3 and obfuscated in 47.1.4; use the resolved method.
        OnRenderFirstFrameFingerprint.method.addInstructions(
            0,
            """
                invoke-static {}, Lapp/morphe/extension/tiktok/speed/PlaybackSpeedPatch;->getPlaybackSpeed()F
                move-result v0
                invoke-virtual {p0, v0}, ${controllerSetSpeed.definingClass}->${controllerSetSpeed.name}(F)V
            """,
        )
    }
}
