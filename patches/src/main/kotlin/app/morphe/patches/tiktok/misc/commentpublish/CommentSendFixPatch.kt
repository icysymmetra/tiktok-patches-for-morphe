/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.commentpublish

import app.morphe.util.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/comment/CommentPublishDiagnostics;"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val CONTEXT_SOURCE = "Lcom/ss/android/ugc/aweme/comment/model/CommentContextSource;"
private const val STRING = "Ljava/lang/String;"
private const val ACTIVITY = "Landroid/app/Activity;"

/** How far after TikTok's top page read the entry asks the page for its screen. */
private const val TOP_PAGE_ASK_WINDOW = 4

/**
 * TikTok's check before a comment goes out: a static method taking the screen to answer on,
 * the video, where the panel was opened from, the action's name and the comment context, and
 * answering whether to stop. Given no screen it stops, with no toast and nothing in the log.
 */
internal fun Instruction.isSendCheck(): Boolean {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return false
    val target = getReference<MethodReference>() ?: return false
    val parameters = target.parameterTypes.map(CharSequence::toString)
    return target.returnType == "Z" && parameters.size == 5 && parameters[1] == AWEME &&
        parameters[2] == STRING && parameters[3] == STRING && parameters[4] == CONTEXT_SOURCE
}

/** The register an invoke passes first, whichever form it was written in. */
internal fun Instruction.firstArgumentRegister(): Int = when (this) {
    is FiveRegisterInstruction -> registerC
    is RegisterRangeInstruction -> startRegister
    else -> error("Comment send fix: $opcode passes no registers")
}

/**
 * Where the entry reads TikTok's top page: a static call with no arguments whose result is
 * asked for its screen within the next few instructions. Answers the index of the
 * move-result that takes the page, and its register.
 */
internal fun List<Instruction>.topPageRead(): Pair<Int, Int>? {
    for (index in 0 until size - 2) {
        val call = this[index]
        if (call.opcode != Opcode.INVOKE_STATIC) continue
        val target = call.getReference<MethodReference>() ?: continue
        if (target.parameterTypes.isNotEmpty() || !target.returnType.startsWith("L")) continue
        val result = this[index + 1]
        if (result.opcode != Opcode.MOVE_RESULT_OBJECT) continue
        val page = (result as OneRegisterInstruction).registerA
        val asked = (index + 2 until minOf(size, index + 2 + TOP_PAGE_ASK_WINDOW)).any { next ->
            val ask = this[next]
            val asks = ask.getReference<MethodReference>() ?: return@any false
            ask.opcode == Opcode.INVOKE_VIRTUAL && asks.definingClass == target.returnType &&
                asks.parameterTypes.isEmpty() && asks.returnType == ACTIVITY &&
                (ask as FiveRegisterInstruction).registerC == page
        }
        if (asked) return (index + 1) to page
    }
    return null
}

@Suppress("unused")
val commentSendFixPatch = bytecodePatch(
    name = "Comment send fix",
    description = "Fixes comments that TikTok sometimes drops without any message, so the " +
        "comment you send actually posts. Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Comments")
    // The diagnostics number the entry's exits first, so an exit keeps its number here.
    dependsOn(sharedExtensionPatch, commentPublishDiagnosticsPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        val entries = mutableListOf<Pair<ClassDef, Method>>()
        classDefForEach { classDef ->
            if (!classDef.type.endsWith(PUBLISH_VIEW_MODEL_SUFFIX)) return@classDefForEach
            classDef.methods.filter { it.isPublishEntryPart() && CLICK_EVENT in it.stringConstants() }
                .forEach { entries += classDef to it }
        }
        check(entries.size == 1) {
            "Comment send fix: expected one publish entry that logs the send click, found ${entries.size}."
        }
        val (classDef, entry) = entries.single()
        val method = mutableClassDefBy(classDef.type).findMutableMethodOf(entry)
        val instructions = method.implementation!!.instructions.toList()

        val checks = instructions.withIndex().filter { it.value.isSendCheck() }
        check(checks.size == 1) {
            "Comment send fix: expected one send check in ${entry.name}, found ${checks.size}."
        }
        val (checkIndex, sendCheck) = checks.single()
        val screen = sendCheck.firstArgumentRegister()
        val screenType = sendCheck.getReference<MethodReference>()!!.parameterTypes[0]
        val topPage = checkNotNull(instructions.topPageRead()) {
            "Comment send fix: ${entry.name} does not read TikTok's top page."
        }
        check(topPage.first < checkIndex) {
            "Comment send fix: ${entry.name} reads TikTok's top page after its send check."
        }

        // The check first, being further down, so the top page's index still holds.
        method.addInstructionsAtControlFlowLabel(
            checkIndex,
            """
                invoke-static/range { v$screen .. v$screen }, $EXTENSION->screenForSendCheck(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$screen
                check-cast v$screen, $screenType
            """,
        )
        val (pageIndex, page) = topPage
        method.addInstructions(
            pageIndex + 1,
            "invoke-static/range { v$page .. v$page }, $EXTENSION->onTopPage(Ljava/lang/Object;)V",
        )
        registerForBuildDetails("enableCommentSendFix")
    }
}
