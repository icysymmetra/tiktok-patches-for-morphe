/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.commentpublish

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val PUBLISH_VIEW_MODEL_SUFFIX = "/commentlist/viewmodel/CommentPublishViewModel;"
private const val COMMENT = "Lcom/ss/android/ugc/aweme/comment/model/Comment;"
private const val FUNCTION1 = "Lkotlin/jvm/functions/Function1;"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/comment/CommentPublishDiagnostics;"

/** The event the entry logs on the way in, on every build. */
internal const val CLICK_EVENT = "click_comment_send"

/** The event the checks log when they refuse, on every build. */
private const val REFUSAL_EVENT = "invalid_post_comment"

/** Exit numbers from the second part of a split entry start here. */
internal const val PART_STRIDE = 1000

internal fun Method.stringConstants(): Set<String> =
    implementation?.instructions?.mapNotNull { it.getReference<StringReference>()?.string }?.toSet()
        ?: emptySet()

/**
 * A part of the publish entry: an instance method of the publish view model taking the publish
 * parameters, the comment being replied to and a completion callback, whose body logs either the
 * send click or a refusal.
 *
 * <p>On 46.2.3 one method does both. 46.7.3 and 46.8.3 split it: one method logs the click and
 * runs the first checks, then calls a second of the same shape that runs the rest and hands the
 * comment to the request. Their names are R8's and change with every build; the shape and the
 * two event names do not.
 */
internal fun Method.isPublishEntryPart(): Boolean {
    if (returnType != "V" || parameterTypes.size != 3) return false
    if (parameterTypes[1].toString() != COMMENT || parameterTypes[2].toString() != FUNCTION1) return false
    val strings = stringConstants()
    return CLICK_EVENT in strings || REFUSAL_EVENT in strings
}

/**
 * A call from the entry into the request builder of its own class: the method that takes the
 * comment replied to, the publish parameters and a flag. Everything before it is a check that
 * can return without a word; from it on, the comment is on its way to the server.
 */
internal fun Instruction.isHandOff(owner: String): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL && opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val target = getReference<MethodReference>() ?: return false
    val parameters = target.parameterTypes.map(CharSequence::toString)
    return target.definingClass == owner && parameters.size == 3 &&
        parameters[0] == COMMENT && parameters[2] == "Z"
}

@Suppress("unused")
val commentPublishDiagnosticsPatch = bytecodePatch(
    name = "Comment publish diagnostics",
    description = "Adds a note to Metra's diagnostic report about how far a comment got " +
        "when it fails to post, which helps track down comments that vanish without an error. " +
        "Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Comments")
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        val parts = mutableListOf<Pair<ClassDef, Method>>()
        classDefForEach { classDef ->
            if (!classDef.type.endsWith(PUBLISH_VIEW_MODEL_SUFFIX)) return@classDefForEach
            classDef.methods.filter { it.isPublishEntryPart() }.forEach { parts += classDef to it }
        }
        check(parts.isNotEmpty()) { "Comment publish diagnostics: no publish entry logs the send click or a refusal." }
        check(parts.size <= 2) {
            "Comment publish diagnostics: expected the publish entry in one or two methods, found ${parts.size}" +
                parts.joinToString(prefix = " [", postfix = "]") { "${it.first.type}->${it.second.name}" }
        }
        val entries = parts.filter { CLICK_EVENT in it.second.stringConstants() }
        check(entries.size == 1) {
            "Comment publish diagnostics: expected one method that logs the send click, found ${entries.size}."
        }
        // The click half first, so its exits are numbered from zero and the other half's from
        // PART_STRIDE, whichever order R8 laid them out in.
        val ordered = entries + parts.filterNot { it in entries }

        var exits = 0
        var handOffs = 0
        ordered.forEachIndexed { ordinal, (classDef, part) ->
            val method = mutableClassDefBy(classDef.type).findMutableMethodOf(part)
            val implementation = method.implementation!!
            val instructions = implementation.instructions.toList()
            val parameterBase = implementation.registerCount - method.numberOfParameterRegisters
            check(parameterBase >= 1) {
                "Comment publish diagnostics: ${part.name} has no local register to stage an exit number in."
            }

            // Every way out before the request, and every hand-off to it. Indices are read off
            // the untouched body and written highest first, so none of them moves under another.
            val exitIndices = instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
            val handOffIndices = instructions.withIndex().filter { it.value.isHandOff(classDef.type) }.map { it.index }
            exits += exitIndices.size
            handOffs += handOffIndices.size

            val insertions = exitIndices.map { index ->
                index to """
                    const/16 v0, ${ordinal * PART_STRIDE + index}
                    invoke-static { v0 }, $EXTENSION->onPublishExit(I)V
                """
            } + handOffIndices.map { index ->
                index to "invoke-static {}, $EXTENSION->onPublishHandedOff()V"
            } + if (ordinal == 0) {
                listOf(
                    0 to "invoke-static/range { v$parameterBase .. v${parameterBase + 1} }, " +
                        "$EXTENSION->onPublishRequested(Ljava/lang/Object;Ljava/lang/Object;)V",
                )
            } else {
                emptyList()
            }
            insertions.sortedByDescending { it.first }.forEach { (index, code) ->
                method.addInstructionsAtControlFlowLabel(index, code)
            }
        }
        check(exits > 0) { "Comment publish diagnostics: the publish entry never returns." }
        check(handOffs > 0) {
            "Comment publish diagnostics: the publish entry never hands the comment to the request."
        }
        registerForBuildDetails("enableCommentPublishDiagnostics")
    }
}
