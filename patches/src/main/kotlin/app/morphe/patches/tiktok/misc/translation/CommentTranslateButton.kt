/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.LazyAbGateSearch
import app.morphe.patches.tiktok.shared.checkBooleanWhenOn
import app.morphe.patches.tiktok.shared.returnBooleanWhenOn
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH_NAME = "Translate comments"

internal const val COMMENT_TRANSLATE_BUTTON = "Lapp/morphe/extension/tiktok/translation/CommentTranslateButton;"

/** The switch in front of both gates the button is built behind. */
internal const val COMMENT_TRANSLATE_BUTTON_SWITCH = "$COMMENT_TRANSLATE_BUTTON->isOn()Z"

/**
 * The setting TikTok builds its comment auto translation behind, the translate button in the
 * comment header among it. Its default is off; TikTok turns it on per account.
 */
internal const val AUTO_TRANSLATION_KEY = "comment_auto_translation_enabled"

/** The key TikTok keeps the comment header's translate toggle under, in its own storage. */
internal const val TOGGLE_STATE_KEY = "key_is_comment_translation_on"

/**
 * Whether [method] is the gate's enabled read: static, takes nothing, answers a boolean, and
 * reads the lazy value and unwraps a Boolean from it. On 47.1.4 it is `LIZIZ` on the class that
 * reads [AUTO_TRANSLATION_KEY]. Its sibling that also asks TikTok's v2 rollout takes nothing and
 * answers a boolean too, but reads no lazy value of its own, so it isn't this.
 */
internal fun Method.isAutoTranslationEnabledRead(): Boolean {
    if (returnType != "Z" || parameterTypes.isNotEmpty() || !AccessFlags.STATIC.isSet(accessFlags)) return false
    val calls = implementation?.instructions?.mapNotNull { it.getReference<MethodReference>() } ?: return false
    val readsValue = calls.any {
        it.name == "getValue" && it.returnType == "Ljava/lang/Object;" && it.parameterTypes.isEmpty()
    }
    val unwrapsBoolean = calls.any {
        it.definingClass == "Ljava/lang/Boolean;" && it.name == "booleanValue" && it.returnType == "Z"
    }
    return readsValue && unwrapsBoolean
}

/**
 * Whether [method] is the gate's answer to whether the header shows the button: static, takes
 * one boolean (whether the reader wrote the video) and answers one, and asks [enabledRead] on
 * its own class. On 47.1.4 it is `LIZ(Z)Z`, which also wants a style number of 2 from TikTok.
 */
internal fun Method.isButtonStyleAnswer(enabledRead: Method): Boolean {
    if (returnType != "Z" || !AccessFlags.STATIC.isSet(accessFlags)) return false
    if (parameterTypes.map(CharSequence::toString) != listOf("Z")) return false
    return implementation?.instructions?.any { instruction ->
        instruction.opcode == Opcode.INVOKE_STATIC &&
            instruction.getReference<MethodReference>()?.let {
                it.definingClass == enabledRead.definingClass && it.name == enabledRead.name &&
                    it.parameterTypes.isEmpty() && it.returnType == "Z"
            } == true
    } == true
}

/** Whether [method] spells [TOGGLE_STATE_KEY]. */
private fun Method.holdsToggleKey(): Boolean =
    implementation?.instructions?.any { it.getReference<StringReference>()?.string == TOGGLE_STATE_KEY } == true

/** The toggle's read: static, takes nothing, answers a boolean, and spells the key. */
internal fun Method.isToggleStateRead(): Boolean =
    returnType == "Z" && parameterTypes.isEmpty() && AccessFlags.STATIC.isSet(accessFlags) && holdsToggleKey()

/**
 * The toggle's write: static, void, takes the new state as a Boolean and the source of the
 * change as some object, and spells the key. The header's buttons hand over no source; TikTok's
 * server sync and its after-a-few-taps trigger hand over one.
 */
internal fun Method.isToggleStateWrite(): Boolean =
    returnType == "V" && AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.size == 2 &&
        parameterTypes[0].toString() == "Ljava/lang/Boolean;" && parameterTypes[1].startsWith("L") &&
        holdsToggleKey()

/** The four methods the button needs, found before any of them is changed. */
internal class CommentTranslateButtonTargets(
    val enabledRead: Method,
    val styleAnswer: Method,
    val stateRead: Method,
    val stateWrite: Method,
)

/**
 * Finds the button's gate and its toggle over whatever [classes] walks and [classOf] can look up.
 * Separate from the patch context so a test can run it over a build's own classes.
 *
 * <p>The gate is the class whose lazy value reads [AUTO_TRANSLATION_KEY], and on it exactly one
 * enabled read and exactly one style answer. The toggle is exactly one read and one write of
 * [TOGGLE_STATE_KEY], both on one class. TikTok's change listener also spells the key, but it is
 * an instance method of another class and takes neither shape.
 */
internal fun findCommentTranslateButtonTargets(
    classOf: (String) -> ClassDef?,
    classes: ((ClassDef) -> Unit) -> Unit,
): CommentTranslateButtonTargets {
    val (gate, enabledRead) = LazyAbGateSearch(classOf).find(
        "$PATCH_NAME (translate button)",
        AUTO_TRANSLATION_KEY,
        { method, _ -> method.isAutoTranslationEnabledRead() },
        classes,
    )
    val styleAnswers = gate.methods.filter { it.isButtonStyleAnswer(enabledRead) }
    if (styleAnswers.size != 1) {
        throw PatchException(
            "$PATCH_NAME: expected one answer to whether the comment header shows the translate " +
                "button on ${gate.type}, found ${styleAnswers.size}.",
        )
    }

    val reads = mutableListOf<Method>()
    val writes = mutableListOf<Method>()
    classes { classDef ->
        for (method in classDef.methods) {
            if (method.isToggleStateRead()) reads += method
            if (method.isToggleStateWrite()) writes += method
        }
    }
    if (reads.size != 1 || writes.size != 1) {
        throw PatchException(
            "$PATCH_NAME: expected one read and one write of \"$TOGGLE_STATE_KEY\", " +
                "found ${reads.size} and ${writes.size}.",
        )
    }
    if (reads.single().definingClass != writes.single().definingClass) {
        throw PatchException(
            "$PATCH_NAME: the translate toggle's read and write are on different classes: " +
                "${reads.single().definingClass} and ${writes.single().definingClass}.",
        )
    }
    return CommentTranslateButtonTargets(enabledRead, styleAnswers.single(), reads.single(), writes.single())
}

/**
 * The write tells the extension what the toggle is being set to and where from, before TikTok
 * stores it. That is how the extension knows which toggle Hushfeed's button turned on.
 */
internal fun MutableMethod.reportToggleWrite() {
    addInstructions(
        0,
        """
            invoke-static/range {p0 .. p1}, $COMMENT_TRANSLATE_BUTTON->onToggleWritten(Ljava/lang/Boolean;Ljava/lang/Object;)V
        """,
    )
}

/** Refuses a read with no return to send through the extension, before anything is hooked. */
internal fun Method.checkToggleStateRead() {
    if (implementation?.instructions?.any { it.opcode == Opcode.RETURN } != true) {
        throw PatchException("$PATCH_NAME: $definingClass->$name has no return to send the toggle's state through.")
    }
}

/** Every answer of the read goes through the extension, at the return's own label. */
internal fun MutableMethod.sendToggleStateThroughExtension() {
    findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN }.forEach { index ->
        val register = getInstruction<OneRegisterInstruction>(index).registerA
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range {v$register .. v$register}, $COMMENT_TRANSLATE_BUTTON->toggleOn(Z)Z
                move-result v$register
            """,
        )
    }
}

/**
 * TikTok's translate button in the comment header (#85): the 文A icon beside the comment count
 * and sort icon that translates every loaded comment on one tap and shows the originals on the
 * next. It is TikTok's own, built for accounts that have its comment auto translation, and the
 * translation is TikTok's own service.
 *
 * <p>Two gates decide whether the header builds it, and both answer yes with the switch on. The
 * enabled read is answered rather than the AB value under it, because it also turns the feature
 * off for accounts in TikTok's reverse translation rollout (the test account is one), and setting
 * that rollout's value would change See translation on captions too. The style answer is the
 * second condition on top. With the switch off, and while Hushfeed is paused, both read
 * TikTok's own values.
 *
 * <p>The toggle is TikTok's too, and it stays where TikTok stores it when the switch goes off.
 * A toggle left on would keep TikTok translating comments with no button left to turn it off, so
 * the read goes through the extension, which answers off for a toggle Hushfeed's button turned
 * on once the switch is off. Fails closed: everything is found before anything is changed.
 */
context(patchContext: BytecodePatchContext)
internal fun installCommentTranslateButton() {
    val targets = findCommentTranslateButtonTargets(patchContext::classDefByOrNull, patchContext::classDefForEach)
    fun mutable(method: Method) = patchContext.mutableClassDefBy(method.definingClass).findMutableMethodOf(method)

    val enabledRead = mutable(targets.enabledRead)
    val styleAnswer = mutable(targets.styleAnswer)
    val stateRead = mutable(targets.stateRead)
    val stateWrite = mutable(targets.stateWrite)
    // Both gates and the toggle's read are checked before anything is hooked, so a refusal leaves
    // no hook behind. The write's shape was checked when it was found.
    enabledRead.checkBooleanWhenOn(PATCH_NAME)
    styleAnswer.checkBooleanWhenOn(PATCH_NAME)
    stateRead.checkToggleStateRead()

    enabledRead.returnBooleanWhenOn(PATCH_NAME, COMMENT_TRANSLATE_BUTTON_SWITCH, true)
    styleAnswer.returnBooleanWhenOn(PATCH_NAME, COMMENT_TRANSLATE_BUTTON_SWITCH, true)
    stateWrite.reportToggleWrite()
    stateRead.sendToggleStateThroughExtension()
}
