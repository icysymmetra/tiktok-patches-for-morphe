package app.morphe.patches.tiktok.misc.comment

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val COMMENT_CLASS_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/comment/model/Comment;"
private const val CLIP_DATA_CLASS_DESCRIPTOR = "Landroid/content/ClipData;"
private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/comment/CommentCopySanitizer;"

private val clipboardTextHelperFingerprint = Fingerprint(
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
        "Ljava/lang/String;",
        "Landroid/content/Context;",
        "Lcom/bytedance/bpea/basics/Cert;",
    ),
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<MethodReference>()?.isClipDataNewPlainText() == true
        } == true
    },
)

@Suppress("unused")
val copyCommentsWithoutUsernamePatch = bytecodePatch(
    name = "Copy comments without username",
    description = "Copies only the comment text without including the creator's username.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCopyCommentsWithoutUsername()V",
        )

        val clipboardHelperMatch = clipboardTextHelperFingerprint.match()
        val clipboardHelper = MethodSignature(
            clipboardHelperMatch.originalClassDef.type,
            clipboardHelperMatch.originalMethod.name,
            clipboardHelperMatch.originalMethod.parameterTypes.map { it.toString() },
            clipboardHelperMatch.originalMethod.returnType,
        )

        val commentCopyFingerprint = Fingerprint(
            custom = { method, _ ->
                method.isCommentCopyBuilder(clipboardHelper)
            },
        )

        var patchedCalls = 0
        commentCopyFingerprint.matchAll().forEach { match ->
            val method = match.method
            val helperCallIndexes = method.findClipboardHelperCallIndexes(clipboardHelper)

            helperCallIndexes.asReversed().forEach { helperCallIndex ->
                val helperInstruction = method.getInstruction<Instruction>(helperCallIndex)
                val copiedTextRegister = helperInstruction.argumentRegister(1)
                    ?: throw PatchException(
                        "Copy comments without username: clipboard helper call is not register-addressable in ${match.originalClassDef.type}->${method.name}.",
                    )
                val copiedTextAssignmentIndex = method.findCopiedTextAssignmentBefore(helperCallIndex, copiedTextRegister)
                    ?: throw PatchException(
                        "Copy comments without username: could not resolve copied text assignment in ${match.originalClassDef.type}->${method.name}.",
                    )
                val insertIndex = copiedTextAssignmentIndex + 1
                val commentTextRegister = method.findLastCommentTextRegisterBefore(insertIndex)
                    ?: throw PatchException(
                        "Copy comments without username: could not resolve comment text register in ${match.originalClassDef.type}->${method.name}.",
                    )

                method.addCommentCopySanitizerInstructions(insertIndex, copiedTextRegister, commentTextRegister)
                patchedCalls++
            }
        }

        if (patchedCalls == 0) {
            throw PatchException("Copy comments without username: no comment copy clipboard calls were patched.")
        }
    }
}

private data class MethodSignature(
    val definingClass: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
) {
    fun matches(reference: MethodReference): Boolean =
        reference.definingClass == definingClass &&
            reference.name == name &&
            reference.parameterTypes.map { it.toString() } == parameters &&
            reference.returnType == returnType
}

private fun Method.isCommentCopyBuilder(clipboardHelper: MethodSignature): Boolean {
    val implementation = implementation ?: return false
    var hasCommentText = false
    var hasCommentUser = false
    var hasClipboardHelperCall = false

    implementation.instructions.forEach { instruction ->
        val reference = instruction.getReference<MethodReference>() ?: return@forEach
        if (reference.isCommentGetText()) hasCommentText = true
        if (reference.isCommentGetUser()) hasCommentUser = true
        if (clipboardHelper.matches(reference)) hasClipboardHelperCall = true
    }

    return hasCommentText && hasCommentUser && hasClipboardHelperCall
}

private fun Method.findClipboardHelperCallIndexes(clipboardHelper: MethodSignature): List<Int> =
    implementation!!.instructions.withIndex().mapNotNull { (index, instruction) ->
        val reference = instruction.getReference<MethodReference>() ?: return@mapNotNull null
        if (clipboardHelper.matches(reference)) index else null
    }

private fun Method.findCopiedTextAssignmentBefore(index: Int, copiedTextRegister: Int): Int? =
    implementation!!.instructions.take(index).withIndex().lastOrNull { (_, instruction) ->
        instruction.opcode == Opcode.MOVE_RESULT_OBJECT &&
            (instruction as? OneRegisterInstruction)?.registerA == copiedTextRegister
    }?.index

private fun Method.findLastCommentTextRegisterBefore(index: Int): Int? {
    val instructions = implementation!!.instructions.toList()
    val getTextInvokeIndex = instructions.take(index).withIndex().lastOrNull { (_, instruction) ->
        instruction.getReference<MethodReference>()?.isCommentGetText() == true
    }?.index ?: return null
    val moveResultInstruction = instructions.getOrNull(getTextInvokeIndex + 1) ?: return null

    if (moveResultInstruction.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    return (moveResultInstruction as? OneRegisterInstruction)?.registerA
}

private fun MutableMethod.addCommentCopySanitizerInstructions(
    insertIndex: Int,
    copiedTextRegister: Int,
    commentTextRegister: Int,
) {
    if (copiedTextRegister <= 15 && commentTextRegister <= 15) {
        addInstructions(
            insertIndex,
            """
                invoke-static {v$copiedTextRegister, v$commentTextRegister}, $EXTENSION_CLASS_DESCRIPTOR->sanitizeCopiedCommentText(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$copiedTextRegister
            """,
        )
        return
    }

    val registerProvider = getFreeRegisterProvider(insertIndex, 2, copiedTextRegister, commentTextRegister)
    val copiedTextTempRegister = registerProvider.getFreeRegister()
    val commentTextTempRegister = registerProvider.getFreeRegister()

    if (copiedTextTempRegister > 15 || commentTextTempRegister > 15) {
        throw PatchException(
            "Copy comments without username: could not allocate low temporary registers for sanitizer call.",
        )
    }

    addInstructions(
        insertIndex,
        """
            move-object/from16 v$copiedTextTempRegister, v$copiedTextRegister
            move-object/from16 v$commentTextTempRegister, v$commentTextRegister
            invoke-static {v$copiedTextTempRegister, v$commentTextTempRegister}, $EXTENSION_CLASS_DESCRIPTOR->sanitizeCopiedCommentText(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
            move-result-object v$copiedTextTempRegister
            move-object/from16 v$copiedTextRegister, v$copiedTextTempRegister
        """,
    )
}

private fun Instruction.argumentRegister(argumentIndex: Int): Int? =
    when (this) {
        is FiveRegisterInstruction -> when (argumentIndex) {
            0 -> registerC
            1 -> registerD
            2 -> registerE
            3 -> registerF
            4 -> registerG
            else -> null
        }
        is RegisterRangeInstruction -> startRegister + argumentIndex
        else -> null
    }

private fun MethodReference.isClipDataNewPlainText(): Boolean =
    definingClass == CLIP_DATA_CLASS_DESCRIPTOR &&
        name == "newPlainText" &&
        parameterTypes == listOf("Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;") &&
        returnType == "Landroid/content/ClipData;"

private fun MethodReference.isCommentGetText(): Boolean =
    definingClass == COMMENT_CLASS_DESCRIPTOR &&
        name == "getText" &&
        parameterTypes.isEmpty() &&
        returnType == "Ljava/lang/String;"

private fun MethodReference.isCommentGetUser(): Boolean =
    definingClass == COMMENT_CLASS_DESCRIPTOR &&
        name == "getUser" &&
        parameterTypes.isEmpty()
