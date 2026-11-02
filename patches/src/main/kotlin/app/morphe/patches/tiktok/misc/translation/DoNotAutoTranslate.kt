/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH_NAME = "Translate comments"

/** The service every automatic translation decision goes through. The name survives obfuscation. */
internal const val TRANSLATION_SERVICE = "Lcom/ss/android/ugc/aweme/translation/service/TranslationServiceImpl;"

/** How many times that service reads TikTok's Don't translate list on 47.1.4: six decisions and one accessor. */
internal const val DO_NOT_TRANSLATE_READS = 7

private const val DO_NOT_TRANSLATE_GETTER = "getSelectedDoNotTranslateLanguageCodes"

private const val DO_NOT_AUTO_TRANSLATE = "Lapp/morphe/extension/tiktok/translation/DoNotAutoTranslate;"

/**
 * The index of the move-result-object after each read of TikTok's Don't translate list in
 * [method]. The getter is found by name and shape, a no-argument call returning String[], and
 * not by the R8 name of the class it is called on. A read that isn't followed by the move that
 * takes its result stops the patch: the hook would otherwise land between the two.
 */
internal fun Method.doNotTranslateReads(): List<Int> {
    val instructions = implementation?.instructions?.toList() ?: return emptyList()
    val results = mutableListOf<Int>()
    instructions.forEachIndexed { index, instruction ->
        if (instruction.opcode != Opcode.INVOKE_VIRTUAL) return@forEachIndexed
        val call = instruction.getReference<MethodReference>() ?: return@forEachIndexed
        if (call.name != DO_NOT_TRANSLATE_GETTER || call.returnType != "[Ljava/lang/String;" ||
            call.parameterTypes.isNotEmpty()
        ) {
            return@forEachIndexed
        }
        val move = instructions.getOrNull(index + 1)
        if (move == null || move.opcode != Opcode.MOVE_RESULT_OBJECT) {
            throw PatchException("$PATCH_NAME: the read of the Don't translate list in $definingClass->$name isn't followed by its result.")
        }
        results += index + 1
    }
    return results
}

/**
 * Hands TikTok its Don't translate list with the user's languages added, at each of the seven
 * places the translation service reads it (#121). Every automatic decision there compares an
 * item's language with that list, so a language in it stays in the original. Nothing is written
 * back to TikTok's list, and translating by hand never asks it.
 *
 * Fails closed: the service must hold exactly [DO_NOT_TRANSLATE_READS] reads, each result in a
 * register a plain invoke can name. Anything else stops the hook before an instruction is added.
 */
internal fun BytecodePatchContext.hookDoNotAutoTranslate() {
    val service = mutableClassDefBy(TRANSLATION_SERVICE)
    val sites = service.methods.map { it to it.doNotTranslateReads() }.filter { it.second.isNotEmpty() }
    val total = sites.sumOf { it.second.size }
    if (total != DO_NOT_TRANSLATE_READS) {
        throw PatchException(
            "$PATCH_NAME: expected $DO_NOT_TRANSLATE_READS reads of the Don't translate list in $TRANSLATION_SERVICE, found $total.",
        )
    }
    // Every register is checked before the first instruction goes in, so a refusal leaves the class as it was.
    val hooks = sites.flatMap { (method, results) ->
        results.map { index ->
            val register = (method.implementation!!.instructions.elementAt(index) as OneRegisterInstruction).registerA
            if (register > 15) {
                throw PatchException("$PATCH_NAME: the Don't translate list sits in v$register of ${method.definingClass}->${method.name}.")
            }
            Triple(method, index, register)
        }
    }
    // Highest first within a method, so the indices still point where they were read.
    for ((method, index, register) in hooks.sortedByDescending { it.second }) {
        method.addWithExcluded(index, register)
    }
}

/** Puts the user's languages into the list that [resultIndex]'s move-result-object just took into [register]. */
internal fun MutableMethod.addWithExcluded(resultIndex: Int, register: Int) {
    addInstructions(
        resultIndex + 1,
        """
            invoke-static {v$register}, $DO_NOT_AUTO_TRANSLATE->withExcluded([Ljava/lang/String;)[Ljava/lang/String;
            move-result-object v$register
        """,
    )
}
