/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH_NAME = "Translate comments"

private const val TRANSLATE_INTO = "Lapp/morphe/extension/tiktok/translation/TranslateInto;"

/**
 * The codes the translation service's target-language answer respells before it hands one back:
 * zh-hans and zh-hant get their script capitalized and fr-ca becomes fr. No other method of the
 * service spells all three.
 */
internal val TARGET_LANGUAGE_SPELLINGS = setOf("zh-hans", "zh-hant", "fr-ca")

/**
 * Whether [method] is the service's answer to which language to translate into: an instance
 * method taking nothing and returning String that spells [TARGET_LANGUAGE_SPELLINGS]. On 47.1.4
 * it is `LLILL`, read by the comment batch translator's request holder; the name is R8's.
 */
internal fun Method.isTargetLanguageAnswer(): Boolean {
    if (returnType != "Ljava/lang/String;" || parameterTypes.isNotEmpty() ||
        AccessFlags.STATIC.isSet(accessFlags)
    ) {
        return false
    }
    val strings = implementation?.instructions?.mapNotNull {
        it.getReference<StringReference>()?.string
    }?.toSet() ?: return false
    return strings.containsAll(TARGET_LANGUAGE_SPELLINGS)
}

/**
 * Sends the translation service's target language through the extension at every return, so a
 * language the reader typed goes into each translation request instead of TikTok's choice.
 * Fails closed: the service must hold exactly one such answer, or nothing is added.
 */
internal fun BytecodePatchContext.hookTranslateInto() {
    val service = mutableClassDefBy(TRANSLATION_SERVICE)
    val answers = service.methods.filter { it.isTargetLanguageAnswer() }
    if (answers.size != 1) {
        throw PatchException(
            "$PATCH_NAME: expected one target language answer in $TRANSLATION_SERVICE, found ${answers.size}.",
        )
    }
    answers.single().sendTargetThroughExtension()
}

/** Each returned language goes through TranslateInto.target, at the return's own label. */
internal fun MutableMethod.sendTargetThroughExtension() {
    findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN_OBJECT }.forEach { index ->
        val register = getInstruction<OneRegisterInstruction>(index).registerA
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static/range {v$register .. v$register}, $TRANSLATE_INTO->target(Ljava/lang/String;)Ljava/lang/String;
                move-result-object v$register
            """,
        )
    }
}
