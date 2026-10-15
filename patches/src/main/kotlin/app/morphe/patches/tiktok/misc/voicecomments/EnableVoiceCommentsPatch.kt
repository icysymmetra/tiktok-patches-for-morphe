/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.voicecomments

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.getMutableMethod
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

@Suppress("unused")
val enableVoiceCommentsPatch = bytecodePatch(
    name = "Enable voice comments",
    description = "Enables TikTok's native voice-comment recording and publishing entry points.",
    default = true,
) {
    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        // The publish gate is the first parameterless static boolean the entry check calls
        // (right after the "comment_audio_publish_entry_forbidden" setting lookup, which takes arguments).
        val publishGate = VoiceCommentEntryAllowedFingerprint.method.implementation!!.instructions
            .firstNotNullOfOrNull { instruction ->
                if (instruction.opcode != Opcode.INVOKE_STATIC) return@firstNotNullOfOrNull null
                instruction.getReference<MethodReference>()?.takeIf { reference ->
                    reference.parameterTypes.isEmpty() && reference.returnType == "Z"
                }
            } ?: throw PatchException("Enable voice comments: publish gate was not resolved.")

        publishGate.getMutableMethod().addInstructions(
            0,
            """
                const/4 v0, 0x1
                return v0
            """,
        )
    }
}
