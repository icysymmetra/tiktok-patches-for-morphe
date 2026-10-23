/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patcher.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.Reference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/comment/CommentTools;"
private const val COMMENT_SURPRISE = "Lcom/ss/android/ugc/aweme/comment/model/CommentSurprise;"
private const val COMMENT_SURPRISE_STRUCT = "Lcom/ss/android/ugc/aweme/comment/model/CommentSurpriseStruct;"
private const val COMMENT_ITEM_LIST = "Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;"
private const val COMMENT_RESPONSE = "Lcom/ss/android/ugc/aweme/comment/model/CommentResponse;"

/**
 * The brand animation that plays over the comment sheet when a comment matches a campaign's
 * trigger. TikTok's server answers such a comment, or a comment page, with a CommentSurprise,
 * and every way the sheet shows one reads it out of a CommentSurpriseStruct: the publish view
 * model and the list helper that start the animation, and the observer that plays it. The
 * struct's one constructor is the only writer of that field, and every read checks it for null
 * straight away (CommentSurpriseAnchorsTest), so a constructor handed null leaves nothing to
 * play. Both classes keep their real names on every fixture.
 *
 * <p>Which surprise is a campaign's is decided by where the struct is built, not by what the
 * server wrote on the surprise: TikTok's own code reads neither the keyword nor, past the
 * publish path's analytics, the type before playing one. The struct is built in three places,
 * and each says so just before the constructor, on the same thread, so the constructor's hook
 * can decide by path. The comment-page loader builds it from the page's surprise and hands it,
 * with the scene the page was fetched for, to the one method that plays it: the default scene
 * is a campaign's, and TikTok's own celebrations (the first-comment milestone and the author's
 * own first comment) ask for scenes of their own. The publish response builds it from the
 * surprise sent with a typed comment, where type 1 is the first-comment celebration. The
 * milestone builder replays a cached first-comment surprise. Each site is a fingerprint of its
 * own shape, and the anchors test pins the three on 47.0.3. A construction none of them marks,
 * which 46.2.3 has in its image-comment publish, falls back to the old content rule, and the
 * Diagnostics row names it while the switch is on.
 *
 * <p>Through 0.58.0 this hooked the method that names comment_easter_egg_trigger. That method
 * only reports the animation to analytics, and its callers start the animation after it
 * returns, so returning from it early left the animation playing.
 */
private object CommentSurpriseStructFingerprint : Fingerprint(
    definingClass = COMMENT_SURPRISE_STRUCT,
    name = "<init>",
    returnType = "V",
    parameters = listOf("Lcom/ss/android/ugc/aweme/comment/model/Comment;", COMMENT_SURPRISE, "Z"),
)

private fun Method.references(): List<Reference> =
    implementation?.instructions?.mapNotNull { (it as? ReferenceInstruction)?.reference }.orEmpty()

private fun List<Reference>.constructTheStruct() =
    any { it is MethodReference && it.definingClass == COMMENT_SURPRISE_STRUCT && it.name == "<init>" }

private fun List<Reference>.readField(owner: String, field: String) =
    any { it is FieldReference && it.definingClass == owner && it.name == field }

/** The one method that plays a comment-page surprise: (struct, scene, string) returning nothing. */
private fun MethodReference.isPlayCall() =
    parameterTypes.map { it.toString() } == listOf(COMMENT_SURPRISE_STRUCT, "I", "Ljava/lang/String;") && returnType == "V"

/** The comment-page loader: builds the struct from the page's surprise and hands it, with the scene, to the play method. */
internal object CommentPageSurpriseFingerprint : Fingerprint(
    returnType = "V",
    // The filter repeats a reference the custom block requires, so the patcher reads only the
    // classes that make it (#54).
    filters = listOf(fieldAccess(definingClass = COMMENT_ITEM_LIST, name = "commentSurprise")),
    custom = { method, _ ->
        val references = method.references()
        references.constructTheStruct() && references.readField(COMMENT_ITEM_LIST, "commentSurprise") &&
            references.any { it is MethodReference && it.isPlayCall() }
    },
)

/** The publish response: builds the struct from the surprise the server sent with a typed comment. */
private object PublishedSurpriseFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    custom = { method, _ ->
        val references = method.references()
        references.constructTheStruct() && references.readField(COMMENT_RESPONSE, "commentSurprise")
    },
)

/** The milestone builder: replays a cached first-comment surprise for the scene it names. */
private object MilestoneSurpriseFingerprint : Fingerprint(
    returnType = "Ljava/lang/Object;",
    custom = { method, _ ->
        val references = method.references()
        references.constructTheStruct() &&
            references.any { it is FieldReference && it.name == "FIRST_COMMENT_MILESTONE" } &&
            references.any { it is MethodReference && it.definingClass == "Landroid/util/LruCache;" && it.name == "get" }
    },
)

private fun MutableMethod.indexOfStructConstructor() = indexOfFirstInstructionOrThrow {
    getReference<MethodReference>()?.let { it.definingClass == COMMENT_SURPRISE_STRUCT && it.name == "<init>" } == true
}

/** Says where this site is, just before the constructor: the site is a different method from the constructor, so the mark is the same thread's. */
private fun MutableMethod.markBeforeConstructor(mark: String) = addInstructions(
    indexOfStructConstructor(),
    "invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->$mark()V",
)

private val MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

@Suppress("unused")
val hideCommentEggsPatch = bytecodePatch(
    name = "Hide comment popup ads",
    description = "Stops the brand animation that pops up over the comments when someone types a word or emoji an advertiser paid for. Starts off. Turn it on in Metra settings > Comments and translation.",
) {
    category("Comments")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideCommentEggs()V",
        )

        // p2 is the surprise. Replacing it before the super call is allowed: only `this` has to
        // wait for the constructor it calls.
        CommentSurpriseStructFingerprint.method.addInstructions(
            0,
            """
                invoke-static/range {p2 .. p2},$EXTENSION_CLASS_DESCRIPTOR->commentSurprise(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object p2
                check-cast p2, $COMMENT_SURPRISE
            """,
        )

        // Each site says where it is just before the constructor, so the hook above can decide
        // by path. The marks take no register, and the page loader's takes the scene.
        PublishedSurpriseFingerprint.method.markBeforeConstructor("surpriseFromPublish")
        MilestoneSurpriseFingerprint.method.markBeforeConstructor("surpriseFromMilestone")
        CommentPageSurpriseFingerprint.method.apply {
            val constructor = indexOfStructConstructor()
            val play = indexOfFirstInstructionOrThrow(constructor) { getReference<MethodReference>()?.isPlayCall() == true }
            val scene = commentPageSceneRegister(
                implementation!!.instructions.toList(),
                constructor,
                play,
                implementation!!.registerCount - numberOfParameterRegisters(),
            )
            addInstructions(
                constructor,
                "invoke-static/range {v$scene .. v$scene}, $EXTENSION_CLASS_DESCRIPTOR->surpriseFromPage(I)V",
            )
        }
    }
}

/** Whether this instruction writes [register], either half of a wide write included. */
private fun Instruction.writes(register: Int): Boolean {
    if (this !is OneRegisterInstruction || !opcode.setsRegister()) return false
    return registerA == register || (opcode.setsWideRegister() && registerA + 1 == register)
}

/** The registers a method's parameters take, `this` included when it has one. */
private fun Method.numberOfParameterRegisters(): Int =
    (if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1) +
        parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }

/**
 * The register that holds the comment page's scene at the struct's constructor, which is where
 * the page site's mark goes. The play call (this, struct, scene, string) takes the scene as its
 * third register. On every fixture that is a copy made after the constructor with a plain `move`,
 * so the register it was copied from holds the int scene before it. With no such copy, the play
 * call's own register is taken only when it is a parameter register nothing writes before the
 * play call, since then it holds the same int at the constructor. Anything else stops the build:
 * the register the play call reads can hold the struct itself at the constructor (it does on
 * every fixture), and handing that to an int parameter fails verification of the whole class.
 */
internal fun commentPageSceneRegister(
    instructions: List<Instruction>,
    constructor: Int,
    play: Int,
    firstParameterRegister: Int,
): Int {
    val sceneAtPlay = when (val call = instructions[play]) {
        is FiveRegisterInstruction -> call.registerE
        is RegisterRangeInstruction -> call.startRegister + 2
        else -> throw PatchException("Hide comment popup ads: the play call is not an invoke this reads.")
    }
    ((constructor + 1) until play).reversed().forEach { index ->
        val instruction = instructions[index]
        if (instruction.writes(sceneAtPlay)) {
            if (instruction.opcode in MOVES && instruction is TwoRegisterInstruction) {
                val source = instruction.registerB
                if (((constructor + 1) until index).none { instructions[it].writes(source) }) return source
            }
            throw PatchException(
                "Hide comment popup ads: the comment page's scene reaches the play call through " +
                    "${instruction.opcode.name}, not a copy this can follow back to the constructor.",
            )
        }
    }
    val writtenBeforePlay = (0 until play).any { instructions[it].writes(sceneAtPlay) }
    if (sceneAtPlay >= firstParameterRegister && !writtenBeforePlay) return sceneAtPlay
    throw PatchException(
        "Hide comment popup ads: the comment page's scene (v$sceneAtPlay at the play call) is not a copy " +
            "or an untouched parameter, so what it holds at the constructor is unknown.",
    )
}
