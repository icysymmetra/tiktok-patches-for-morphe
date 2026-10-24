/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * TikTok's Settings and privacy > Language page (the route `//display/caption/setting_compose`).
 * The class keeps its source name on every build.
 */
internal const val LANGUAGE_PAGE = "Lcom/ss/android/ugc/trill/setting/compose/DisplayAndCaptionComposePage;"

internal const val LANGUAGE_PAGE_TRANSLATIONS = "Lapp/morphe/extension/tiktok/translation/LanguagePageTranslations;"

/** The state update the page makes when the translation rows are allowed. */
private const val SET_STATE_PARAMETER = "Lkotlin/jvm/functions/Function1;"

/**
 * The two gate reads at the top of the Language page's onViewCreated, as the indices of their
 * move-result instructions, or an empty list when the method isn't shaped that way.
 *
 * TikTok draws the page's translation group (the Translation heading and the Always translate,
 * Translate into and Don't translate rows) only while its state says the group is visible, and
 * the page sets that only when neither of two App AB values hides it. On 47.1.4 they are
 * `feed_translation_reverse` (hides the group at 1) and `pure_feed_reverse` (hides it when not 0),
 * each read once per process behind a static `()Z` and each branching past the state update on
 * true. Both reads have to be here, one after the other, branching to the same place, with the
 * state update the first call after them, or nothing counts as found.
 */
internal fun Method.languagePageGateReads(): List<Int> {
    val instructions = implementation?.instructions?.toList() ?: return emptyList()
    val addresses = IntArray(instructions.size)
    var address = 0
    instructions.forEachIndexed { index, instruction ->
        addresses[index] = address
        address += instruction.codeUnits
    }

    val reads = (0 until instructions.size - 2)
        .filter { isGateRead(instructions, it) }
        .map { it + 1 }
    if (reads.size != 2 || reads[1] != reads[0] + 3) return emptyList()

    val targets = reads.map { move ->
        val branch = instructions[move + 1] as OffsetInstruction
        addresses[move + 1] + branch.codeOffset
    }
    if (targets[0] != targets[1]) return emptyList()

    val update = instructions.drop(reads[1] + 2).firstOrNull { it.opcode == Opcode.INVOKE_VIRTUAL }
        ?.getReference<MethodReference>()
    if (update?.name != "setState" || update.returnType != "V" ||
        update.parameterTypes.map { it.toString() } != listOf(SET_STATE_PARAMETER)
    ) {
        return emptyList()
    }
    return reads
}

/** A static call taking nothing and returning a boolean, moved into a register that skips ahead when true. */
private fun isGateRead(instructions: List<Instruction>, index: Int): Boolean {
    val call = instructions[index]
    if (call.opcode != Opcode.INVOKE_STATIC) return false
    val target = call.getReference<MethodReference>() ?: return false
    if (target.returnType != "Z" || target.parameterTypes.isNotEmpty()) return false
    val move = instructions[index + 1]
    val branch = instructions[index + 2]
    if (move.opcode != Opcode.MOVE_RESULT || branch.opcode != Opcode.IF_NEZ) return false
    return (move as OneRegisterInstruction).registerA == (branch as OneRegisterInstruction).registerA
}

/** Each gate's answer goes through LanguagePageTranslations.hidden before the page branches on it. */
internal fun MutableMethod.showLanguagePageTranslations() {
    val reads = languagePageGateReads()
    if (reads.isEmpty()) {
        throw PatchException("Translate comments: the Language page's translation gates are not where they were.")
    }
    reads.asReversed().forEach { move ->
        val register = getInstruction<OneRegisterInstruction>(move).registerA
        addInstructions(
            move + 1,
            """
                invoke-static/range {v$register .. v$register}, $LANGUAGE_PAGE_TRANSLATIONS->hidden(Z)Z
                move-result v$register
            """,
        )
    }
}

/** The App AB value that takes translation off the videos at 1 (#143). */
internal const val FEED_TRANSLATION_REVERSE = "feed_translation_reverse"

/** The App AB int getter's parameters: the setting id, the default, the key and whether to log the exposure. */
private val APP_AB_INT_PARAMETERS = listOf("I", "I", "Ljava/lang/String;", "Z")

/**
 * The index of the move-result that takes TikTok's `feed_translation_reverse` here, or null when
 * this method doesn't read it.
 *
 * At 1 the value takes translation off the videos: on 47.1.4 every check in
 * `CLATranslatabilityServiceImpl` (See translation under a description, translated captions,
 * photo titles, text stickers) asks it first and gives up. TikTok reads it in one place, a lazy
 * that hands the App AB int getter the key as its third argument and keeps the answer for the
 * process. Only that read counts. The key also sits in TikTok's list of every AB value and in a
 * debug map, and neither of those is a read.
 */
internal fun Method.feedTranslationReverseRead(): Int? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val reads = instructions.indices.filter { index ->
        val call = instructions[index]
        val target = call.getReference<MethodReference>() ?: return@filter false
        if (target.returnType != "I" || target.parameterTypes.map { it.toString() } != APP_AB_INT_PARAMETERS) {
            return@filter false
        }
        if (instructions.getOrNull(index + 1)?.opcode != Opcode.MOVE_RESULT) return@filter false
        val arguments = call.argumentRegisters() ?: return@filter false
        // The key is the third parameter, after `this` on an instance call.
        val key = arguments.getOrNull(if (call.opcode.isStaticInvoke()) 2 else 3) ?: return@filter false
        keyLoadedInto(instructions, index, key)
    }
    return reads.singleOrNull()?.plus(1)
}

/** Whether the last write to [register] before [call] loads the reverse key. */
private fun keyLoadedInto(instructions: List<Instruction>, call: Int, register: Int): Boolean {
    for (index in call - 1 downTo 0) {
        val instruction = instructions[index]
        if (instruction !is OneRegisterInstruction || instruction.registerA != register) continue
        if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) return false
        return instruction.getReference<StringReference>()?.string == FEED_TRANSLATION_REVERSE
    }
    return false
}

private fun Opcode.isStaticInvoke() = this == Opcode.INVOKE_STATIC || this == Opcode.INVOKE_STATIC_RANGE

/** The registers an invoke passes, in order, or null for anything that isn't an invoke. */
private fun Instruction.argumentRegisters(): List<Int>? = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> null
}

/** TikTok's reverse answer goes through LanguagePageTranslations.feedReverse before the lazy keeps it. */
internal fun MutableMethod.keepTranslationOnVideos() {
    val move = feedTranslationReverseRead()
        ?: throw PatchException("Translate comments: TikTok's $FEED_TRANSLATION_REVERSE read is not where it was.")
    val register = getInstruction<OneRegisterInstruction>(move).registerA
    addInstructions(
        move + 1,
        """
            invoke-static/range {v$register .. v$register}, $LANGUAGE_PAGE_TRANSLATIONS->feedReverse(I)I
            move-result v$register
        """,
    )
}

/**
 * Lets the reader keep See translation on videos when TikTok's server takes it off their account
 * (#143). Fails closed: exactly one method may read the value the way TikTok's lazy does.
 */
internal fun BytecodePatchContext.hookFeedTranslations() {
    FeedTranslationReverseFingerprint.method.keepTranslationOnVideos()
}

/**
 * Lets the reader bring back the translation rows TikTok hides on its Language page for some
 * accounts (#143). Fails closed: the page must have its onViewCreated with both gate reads.
 */
internal fun BytecodePatchContext.hookLanguagePageTranslations() {
    val page = mutableClassDefBy(LANGUAGE_PAGE)
    val created = page.methods.filter {
        it.name == "onViewCreated" && it.returnType == "V" &&
            it.parameterTypes.map { type -> type.toString() } == listOf("Landroid/view/View;", "Landroid/os/Bundle;")
    }
    if (created.size != 1) {
        throw PatchException("Translate comments: expected one onViewCreated in $LANGUAGE_PAGE, found ${created.size}.")
    }
    created.single().showLanguagePageTranslations()
}
