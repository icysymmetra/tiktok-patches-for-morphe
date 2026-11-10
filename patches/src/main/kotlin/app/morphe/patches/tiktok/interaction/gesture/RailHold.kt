/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val RAIL_HOLD_EXTENSION = "Lapp/morphe/extension/tiktok/interaction/RailHold;"
internal const val VIDEO_COMMENT_ASSEM = "Lcom/ss/android/ugc/aweme/feed/assem/videocomment/VideoCommentAssem;"
internal const val VIDEO_SHARE_ASSEM = "Lcom/ss/android/ugc/aweme/feed/assem/share/VideoShareAssem;"
internal const val COMMENT_ABILITY = "Lcom/ss/android/ugc/aweme/feed/assem/ability/IVideoCommentAbility;"
internal const val SHARE_ABILITY = "Lcom/ss/android/ugc/aweme/feed/assem/ability/IVideoShareAbility;"
internal const val FAVORITES_ABILITY = "Lcom/ss/android/ugc/feed/platform/cell/ability/VideoFavoriteAssemAbility;"
internal const val COMMENT_MENU_KEY = "video_comment_button_long_click"
internal const val FAVORITES_MENU_KEY = "video_favorite_button_long_click"
internal const val FAVORITES_MENU_EVENT = "long_press_favorite"
internal const val EDGE_SPEEDUP_ABILITY = "Lcom/ss/android/ugc/aweme/feed/longvideo/edgespeedup/EdgeSpeedupAbility;"
private const val VIEW = "Landroid/view/View;"
private const val ON_LONG_CLICK = "Landroid/view/View\$OnLongClickListener;"
private const val HANDLER = "Landroid/os/Handler;"
private const val RUNNABLE = "Ljava/lang/Runnable;"

/** Whether the instruction is TikTok's own View.setOnLongClickListener call. */
internal fun Instruction.setsLongClick(): Boolean =
    opcode == Opcode.INVOKE_VIRTUAL && getReference<MethodReference>()?.let {
        it.definingClass == VIEW && it.name == "setOnLongClickListener" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(ON_LONG_CLICK)
    } == true

/**
 * The Comment button's long press, which opens the emoji row. TikTok reaches it two ways: a
 * long-click listener on the button, or, when a server flag says so, the button's touch listener
 * after a timer of its own that also keeps letting go from counting as a tap. On 47.0.3 the S22
 * gets the second, where no long-click listener is set at all. Both call this one method, which
 * hands the button's place to the emoji row under a key naming it, and that key picks the method
 * out of the class on every build.
 */
internal object CommentMenuFingerprint : Fingerprint(
    definingClass = VIDEO_COMMENT_ASSEM,
    returnType = "V",
    parameters = listOf(),
    strings = listOf(COMMENT_MENU_KEY).map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The Favorites button's long press, which offers a new collection. TikTok's touch listener on the
 * button runs it from a timer that first marks the press as a long one, so letting go doesn't save
 * the video. It's a lambda with an obfuscated name. What picks it out is that it both logs the
 * long press and hands the button's place to the offer under a key naming it, and only it does.
 */
internal object FavoritesMenuFingerprint : Fingerprint(
    name = "invoke",
    returnType = "Ljava/lang/Object;",
    parameters = listOf(),
    strings = listOf(FAVORITES_MENU_KEY, FAVORITES_MENU_EVENT).map { string(it, StringComparisonType.CONTAINS) },
)

/** Whether the instruction is a Handler.removeCallbacks(Runnable) call. */
internal fun Instruction.dropsCallbacks(): Boolean =
    opcode == Opcode.INVOKE_VIRTUAL && getReference<MethodReference>()?.let {
        it.definingClass == HANDLER && it.name == "removeCallbacks" &&
            it.parameterTypes.map(CharSequence::toString) == listOf(RUNNABLE)
    } == true

/** Whether the instruction asks the hold ability's (FF)Z check. */
internal fun Instruction.asksHoldCheck(): Boolean =
    opcode == Opcode.INVOKE_INTERFACE && getReference<MethodReference>()?.let {
        it.definingClass == EDGE_SPEEDUP_ABILITY && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("F", "F")
    } == true

/**
 * TikTok's hold gesture's touch listener. As a press lands it runs the hold check and, when the
 * check lets the press through, posts the hold's start for 300 ms later. It drops that post with
 * removeCallbacks when the finger moves off the spot, when a second finger lands and when the
 * press ends. The class is obfuscated. It is the only touch listener that asks the hold ability's
 * check.
 */
internal object HoldTouchFingerprint : Fingerprint(
    name = "onTouch",
    returnType = "Z",
    parameters = listOf(VIEW, "Landroid/view/MotionEvent;"),
    custom = { method, classDef ->
        "Landroid/view/View\$OnTouchListener;" in classDef.interfaces &&
            method.implementation?.instructions?.any { it.asksHoldCheck() } == true &&
            method.implementation?.instructions?.any { it.dropsCallbacks() } == true
    },
)

/** The Share button's view setup, which gives it and two of its children their long presses. */
internal object ShareViewCreatedFingerprint : Fingerprint(
    definingClass = VIDEO_SHARE_ASSEM,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf(VIEW),
    custom = { method, _ -> method.implementation?.instructions?.any { it.setsLongClick() } == true },
)

/**
 * The indexes in TikTok's hold check of the asks to [ability] whether a press is on its button:
 * an interface call taking the press's two floats and answering Z, its answer moved straight
 * into a register. The index is the move's.
 */
internal fun Method.railHitTests(ability: String): List<Int> {
    val instructions = implementation?.instructions?.toList() ?: return emptyList()
    return instructions.indices.filter { index ->
        val instruction = instructions[index]
        instruction.opcode == Opcode.INVOKE_INTERFACE && instruction.getReference<MethodReference>()?.let {
            it.definingClass == ability && it.returnType == "Z" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("F", "F")
        } == true && instructions.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT
    }.map { it + 1 }
}

/**
 * Hands each Comment, Share and Favorites ask in TikTok's hold check to the extension, which
 * answers "not on it" for a button a hold plays at the hold speed on. Inserted after each answer's
 * move, last one first so the indexes found hold.
 */
internal fun MutableMethod.answerRailHitTests() {
    val asks = railHitTests(COMMENT_ABILITY).map { it to "commentStopsHold" } +
        railHitTests(SHARE_ABILITY).map { it to "shareStopsHold" } +
        railHitTests(FAVORITES_ABILITY).map { it to "favoritesStopsHold" }
    for (answer in listOf("commentStopsHold", "shareStopsHold", "favoritesStopsHold")) {
        if (asks.none { it.second == answer }) {
            throw PatchException("Long-press controls: the hold check no longer asks the button $answer answers for.")
        }
    }
    asks.sortedByDescending { it.first }.forEach { (moveAt, answer) ->
        val register = (implementation!!.instructions[moveAt] as OneRegisterInstruction).registerA
        addInstructions(
            moveAt + 1,
            """
                invoke-static/range {v$register .. v$register}, $RAIL_HOLD_EXTENSION->$answer(Z)Z
                move-result v$register
            """,
        )
    }
}

/** Returns from the Comment button's long press before the emoji row when the press is held. */
internal fun MutableMethod.skipCommentMenuWhenHeld() =
    guardAtEntry("Long-press controls", "invoke-static {}, $RAIL_HOLD_EXTENSION->skipCommentMenu()Z", "return-void")

/**
 * Leaves the Favorites long press before it logs or offers anything when the press is held. The
 * lambda answers Kotlin's Unit, whose field name is obfuscated too, so the skip jumps to where
 * the lambda itself loads Unit and returns it.
 */
internal fun MutableMethod.skipFavoritesMenuWhenHeld() {
    val instructions = implementation!!.instructions.toList()
    val unit = instructions.indices.lastOrNull { index ->
        instructions[index].opcode == Opcode.SGET_OBJECT &&
            instructions[index].getReference<FieldReference>()?.type == "Lkotlin/Unit;" &&
            instructions.getOrNull(index + 1)?.opcode == Opcode.RETURN_OBJECT &&
            (instructions[index] as OneRegisterInstruction).registerA ==
            (instructions[index + 1] as OneRegisterInstruction).registerA
    } ?: throw PatchException("Long-press controls: $definingClass->$name no longer returns Unit at its end.")
    requireLocals("Long-press controls", 1)
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $RAIL_HOLD_EXTENSION->skipFavoritesMenu()Z
            move-result v0
            if-nez v0, :rail_hold_unit
        """,
        ExternalLabel("rail_hold_unit", getInstruction(unit)),
    )
}

/**
 * Sends each of the hold listener's drops through the extension, which drops the post the same
 * way and forgets every pass, so a press TikTok won't hold opens its button's menu as before.
 */
internal fun MutableMethod.routeHoldDrops() {
    val sites = implementation!!.instructions.withIndex().filter { it.value.dropsCallbacks() }.map { it.index }
    if (sites.isEmpty()) throw PatchException("Long-press controls: $definingClass->$name no longer drops its hold.")
    sites.forEach { index ->
        val call = implementation!!.instructions[index] as FiveRegisterInstruction
        replaceInstruction(
            index,
            "invoke-static {v${call.registerC}, v${call.registerD}}, $RAIL_HOLD_EXTENSION->holdDropped($HANDLER$RUNNABLE)V",
        )
    }
}

/** Sends each of the method's long-click sets through [hook], which wraps TikTok's listener. */
internal fun MutableMethod.routeLongClicks(hook: String) {
    val sites = implementation!!.instructions.withIndex().filter { it.value.setsLongClick() }.map { it.index }
    if (sites.isEmpty()) throw PatchException("Long-press controls: $definingClass->$name sets no long press.")
    sites.forEach { index ->
        val call = implementation!!.instructions[index] as FiveRegisterInstruction
        replaceInstruction(
            index,
            "invoke-static {v${call.registerC}, v${call.registerD}}, $RAIL_HOLD_EXTENSION->$hook($VIEW$ON_LONG_CLICK)V",
        )
    }
}
