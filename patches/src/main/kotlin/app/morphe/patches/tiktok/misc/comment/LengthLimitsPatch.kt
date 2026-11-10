/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.comment

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.instructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val LIMITS = "Lapp/morphe/extension/tiktok/comment/LengthLimits;"
private const val KEYBOARD = "Lcom/ss/android/ugc/aweme/comment/keyboard/keyboardv2/refactor/"
internal const val LONG_COMMENT_MAX_INPUT =
    "Lcom/ss/android/ugc/aweme/comment/experiment/LongCommentWritingConfig;->maxInputLimit:J"
internal const val PROFILE_EDIT = "Lcom/ss/android/ugc/profile/business/ur/ui/"

/** The bio length TikTok's bio editor stops at on 47.x. */
internal const val BIO_LIMIT = 160

/** The classes whose onViewCreated gives a repost note box, or the reply box under a repost, its length filter. */
internal val REPOST_NOTE_INPUTS = listOf(
    "Lcom/ss/android/ugc/aweme/upvote/detail/input/RepostInputFragment;",
    "Lcom/ss/android/ugc/aweme/feed/adapter/widget/repost/RepostAddNoteInputFragment;",
    "Lcom/ss/android/ugc/aweme/upvote/detail/replypanel/assem/RepostReplyKeyboard;",
)

/** The classes of the bio editor that compare the bio with its limit. */
internal val BIO_EDITOR_CLASSES = listOf("${PROFILE_EDIT}ProfileEditBioViewModel;", "${PROFILE_EDIT}ProfileEditBioAssem;")

/**
 * The comment box's limit: the getter the comment keyboard sizes its length filter and its
 * counter with, which answers the long comment experiment's maxInputLimit or the comment
 * character limit config. R8 renames it (ht on 47.0.3, gt on 47.1.x).
 */
internal object CommentInputLimitFingerprint : Fingerprint(
    definingClass = "${KEYBOARD}PortraitInputKeyboard;",
    parameters = emptyList(),
    returnType = "I",
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.IGET_WIDE &&
                ((instruction as? ReferenceInstruction)?.reference as? FieldReference)?.toString() == LONG_COMMENT_MAX_INPUT
        } == true
    },
)

private val ON_VIEW_CREATED = listOf("Landroid/view/View;", "Landroid/os/Bundle;")

/** onViewCreated of each repost note box. */
internal val repostNoteInputFingerprints = REPOST_NOTE_INPUTS.map { type ->
    Fingerprint(definingClass = type, name = "onViewCreated", parameters = ON_VIEW_CREATED, returnType = "V")
}

/**
 * The indices of each `new <filter>(int max, Function0 onFull)` constructor call whose class is
 * an InputFilter. There are two such filters, each renamed by R8 every build: the emoji-aware one
 * (0XbP on 47.0.3, 0pG2 on 47.1.3, 12Rw on 47.1.4), which also builds the comment box's filter,
 * and a plain one (0XXO, 0Viw, 0Vjf) the repost note box and the repost reply box build instead
 * when TikTok's emoji counting is off. The LIVE repost note box only builds the first.
 */
internal fun List<Instruction>.lengthFilterConstructions(isInputFilter: (String) -> Boolean): List<Int> =
    indices.filter { index ->
        val instruction = this[index]
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        (instruction.opcode == Opcode.INVOKE_DIRECT || instruction.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
            reference != null && reference.name == "<init>" && reference.returnType == "V" &&
            reference.parameterTypes.map { it.toString() } == listOf("I", "Lkotlin/jvm/functions/Function0;") &&
            isInputFilter(reference.definingClass)
    }

/** The register that carries the max into the filter's constructor. */
internal fun Instruction.lengthFilterMaxRegister(): Int = when (this) {
    is FiveRegisterInstruction -> registerD
    is RegisterRangeInstruction -> startRegister + 1
    else -> throw IllegalStateException("Not an invoke: $opcode")
}

/** The indices of the constants that load the bio limit. */
internal fun List<Instruction>.bioLimitConstants(): List<Int> = indices.filter { index ->
    val instruction = this[index]
    (instruction.opcode == Opcode.CONST_16 || instruction.opcode == Opcode.CONST) &&
        (instruction as NarrowLiteralInstruction).narrowLiteral == BIO_LIMIT
}

private fun MutableMethod.limitBefore(index: Int, register: Int) = addInstructionsAtControlFlowLabel(index, """
    invoke-static/range { v$register .. v$register }, $LIMITS->limit(I)I
    move-result v$register
""")

@Suppress("unused")
val liftLengthLimitsPatch = bytecodePatch(
    name = "Lift text length limits",
    description = "Lets you write longer comments, repost notes and bios than the app " +
        "normally allows. TikTok can still refuse one that's too long. Starts off. Turn it on in " +
        "Metra settings > Comments and translation.",
    default = true,
) {
    category("Comments")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(settingsPatch, sharedExtensionPatch)
    execute {
        CommentInputLimitFingerprint.method.apply {
            findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN }.forEach { index ->
                limitBefore(index, getInstruction<OneRegisterInstruction>(index).registerA)
            }
        }

        val isInputFilter = { type: String ->
            classDefByOrNull(type)?.interfaces?.contains("Landroid/text/InputFilter;") == true
        }
        repostNoteInputFingerprints.forEach { fingerprint ->
            fingerprint.method.apply {
                val sites = instructions.toList().lengthFilterConstructions(isInputFilter)
                if (sites.isEmpty()) throw PatchException("$definingClass no longer builds a length filter")
                sites.reversed().forEach { index -> limitBefore(index, getInstruction<Instruction>(index).lengthFilterMaxRegister()) }
            }
        }

        var bioSites = 0
        BIO_EDITOR_CLASSES.forEach { type ->
            mutableClassDefBy(type).methods.forEach { method ->
                if (method.implementation == null) return@forEach
                method.instructions.toList().bioLimitConstants().reversed().forEach { index ->
                    val register = method.getInstruction<OneRegisterInstruction>(index).registerA
                    method.addInstructions(index + 1, """
                        invoke-static/range { v$register .. v$register }, $LIMITS->limit(I)I
                        move-result v$register
                    """)
                    bioSites++
                }
            }
        }
        // The overflow check, the mention check and the counter, on every declared build.
        if (bioSites != 3) throw PatchException("The bio editor compares with $BIO_LIMIT at $bioSites places, not 3")

        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLengthLimits()V")
    }
}
