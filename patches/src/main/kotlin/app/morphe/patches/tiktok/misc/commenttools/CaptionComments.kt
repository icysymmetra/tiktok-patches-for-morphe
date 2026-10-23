/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val CAPTION_COMMENTS_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/comment/CaptionComments;"
internal const val POST_MODE_MECHANISM = "Lcom/ss/android/ugc/aweme/model/PostModeEntranceMechanism;"
internal const val COMMENT_COMMON_MODEL = "Lcom/ss/android/ugc/aweme/comment/model/CommentCommonModel;"
internal const val COMMENT_CONTEXT_SOURCE_KT = "Lcom/ss/android/ugc/aweme/comment/model/CommentContextSourceKt;"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"

/**
 * The feed caption's expand and collapse. The "more" button and a tap on the caption text both
 * run it, with the target state, TikTok's name for what was hidden and how the caption was
 * reached. Photo posts are sent to TikTok's post page from inside it. Its class is obfuscated,
 * and it is the one method in the app taking a boolean, a string and the post page's entrance
 * enum in that order.
 */
internal object CaptionExpandFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf("Z", "Ljava/lang/String;", POST_MODE_MECHANISM),
)

/**
 * The comment list's decision to start with the video's caption as a row. It reads the opener's
 * shouldShowVideoDesc through a null-safe (Boolean)Z check, and leaves the row out for TikTok's
 * title panel, which shows the caption above the list itself.
 */
internal object CaptionRowFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(AWEME),
    custom = { method, _ -> method.captionRowAnswer() != null },
)

/** Whether the instruction reads CommentCommonModel.shouldShowVideoDesc. */
private fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.readsShowVideoDesc() =
    opcode == Opcode.IGET_BOOLEAN && getReference<FieldReference>()?.let {
        it.definingClass == COMMENT_COMMON_MODEL && it.name == "shouldShowVideoDesc"
    } == true

/**
 * The index of the move that holds the list's answer: the first move-result after the
 * shouldShowVideoDesc read, straight after a static (Boolean)Z call, in a method that also asks
 * whether the list is the title panel's. Null when the method isn't that shape.
 */
internal fun Method.captionRowAnswer(): Int? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val read = instructions.indexOfFirst { it.readsShowVideoDesc() }
    if (read < 0 || instructions.count { it.readsShowVideoDesc() } != 1) return null
    val asksTitlePanel = instructions.any {
        it.opcode == Opcode.INVOKE_STATIC && it.getReference<MethodReference>()?.let { call ->
            call.definingClass == COMMENT_CONTEXT_SOURCE_KT && call.name == "isFromTitlePanel"
        } == true
    }
    if (!asksTitlePanel) return null
    val move = (read + 1 until instructions.size).firstOrNull { instructions[it].opcode == Opcode.MOVE_RESULT } ?: return null
    val ask = instructions[move - 1]
    val isCheck = ask.opcode == Opcode.INVOKE_STATIC && ask.getReference<MethodReference>()?.let {
        it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Boolean;")
    } == true
    return if (isCheck) move else null
}

/**
 * Hands the caption's expand to the extension before anything of TikTok's runs. On yes the
 * comments opened instead, and the method returns. The arguments sit in p0 to p3 in order, which
 * is what lets one range call pass them all whatever the frame's size.
 */
internal fun MutableMethod.resolveCaptionOpensComments(): CommentToolsWrite {
    if (AccessFlags.STATIC.isSet(accessFlags)) {
        throw PatchException("Comment tools: $definingClass->$name is static, so p0 isn't the caption.")
    }
    requireLocals("Comment tools", 1)
    val method = this
    return {
        method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range {p0 .. p3}, $CAPTION_COMMENTS_CLASS_DESCRIPTOR->opensComments(Ljava/lang/Object;ZLjava/lang/String;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :tiktok_caption
                return-void
            """,
            ExternalLabel("tiktok_caption", method.getInstruction(0)),
        )
    }
}

/**
 * Puts the extension's word on the list's answer, with the list's video handed over first.
 * Inserted after the answer's move, where both of the method's paths to the check have joined.
 */
internal fun MutableMethod.resolveCaptionRow(): CommentToolsWrite {
    val move = captionRowAnswer()
        ?: throw PatchException("Comment tools: $definingClass->$name no longer reads shouldShowVideoDesc through one check.")
    val register = (implementation!!.instructions[move] as OneRegisterInstruction).registerA
    val method = this
    return {
        method.addInstructions(
            move + 1,
            """
                invoke-static/range {p1 .. p1}, $CAPTION_COMMENTS_CLASS_DESCRIPTOR->listFor(Ljava/lang/Object;)V
                invoke-static/range {v$register .. v$register}, $CAPTION_COMMENTS_CLASS_DESCRIPTOR->showCaption(Z)Z
                move-result v$register
            """,
        )
    }
}
