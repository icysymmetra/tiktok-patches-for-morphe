/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.shared.callThroughLocals
import app.morphe.patches.tiktok.shared.objectIn
import app.morphe.util.getFreeRegisterProvider
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/translation/CommentBatchTranslator;"

/** The line TikTok logs as a comment translation batch finishes. */
private const val COMPLETION_ANCHOR = "MultiCommentTranslationTask startTranslate onComplete "

/**
 * Every method that carries the completion anchor.
 *
 * <p>The shape is checked rather than assumed: the hook passes p0 as the batch runner, which is
 * the first parameter only in a static method, so a carrier that is not static, not void, or
 * does not take exactly one object would be hooked wrongly rather than not at all. A host that
 * changes any of that stops the build instead of shipping a hook that reads the wrong register.
 */
private fun BytecodePatchContext.completionCarriers(): List<MutableMethod> {
    val carriers = mutableListOf<MutableMethod>()
    val wrongShape = mutableListOf<String>()
    classDefForEach { classDef ->
        for (method in classDef.methods) {
            val carriesAnchor = method.implementation?.instructions?.any { instruction ->
                instruction.getReference<StringReference>()?.string == COMPLETION_ANCHOR
            } == true
            if (!carriesAnchor) continue

            // Two shapes carry the anchor. Up to 46.8.3 it is an R8-outlined static `(runner)V`
            // taking the Runnable it was lifted out of; on 46.9.3 it is that Runnable's own
            // `run()V`. Either way p0 is the runner the extension reads its two fields off.
            val isStatic = AccessFlags.STATIC.isSet(method.accessFlags)
            val outlined = isStatic && method.parameterTypes.size == 1 &&
                method.parameterTypes.single().startsWith("L")
            val own = !isStatic && method.name == "run" && method.parameterTypes.isEmpty()
            if (method.returnType != "V" || !(outlined || own)) {
                wrongShape += "${method.definingClass}->${method.name}"
                continue
            }
            // A Runnable of its own is its own runner, so what the extension needs of it is
            // asked here: exactly two instance reference fields, one of them the results List,
            // the other the task. That is the shape the extension reads by kind, and a class
            // that does not have it would be stood down at runtime on the first batch.
            if (own && !classDef.holdsResultsAndTask()) {
                wrongShape += "${method.definingClass}->${method.name}"
                continue
            }
            carriers += mutableClassDefBy(classDef).findMutableMethodOf(method)
        }
    }

    if (wrongShape.isNotEmpty()) {
        throw PatchException(
            "Translate comments: the batch completion anchor is on a method this cannot hook: " +
                wrongShape.joinToString(", ") + ".",
        )
    }
    if (carriers.isEmpty()) {
        throw PatchException("Translate comments: no method carries the batch completion anchor.")
    }
    // All of them have to take the same thing. The extension answers a runner with no results
    // field by standing the whole feature down for the session, so hooking a carrier that takes
    // some other object would turn the first comment list into the opposite of this fix.
    // toString because dexlib2 hands back CharSequence, which does not sort or compare.
    // Two Runnables of their own may finish two different batch kinds, and each carries its
    // own runner in `this`; the rule about taking the same thing is for outlined carriers, which
    // are handed a runner they do not own.
    val parameterTypes = carriers.filter { it.parameterTypes.isNotEmpty() }
        .map { it.parameterTypes.single().toString() }.toSet()
    if (parameterTypes.size > 1) {
        throw PatchException(
            "Translate comments: the batch completion carriers take different things, so one of " +
                "them is not the batch runner: " + parameterTypes.sorted().joinToString(", ") + ".",
        )
    }
    return carriers
}

private const val COMMENT_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/comment/model/Comment;"

/**
 * The completion carriers that apply a text batch: the ones that mark comments translated.
 * The audio completion carries the same anchor but writes a voice comment's transcript and
 * leaves the comment's text and its translated flag alone. Comment tools judges translations
 * here, before TikTok applies them.
 *
 * <p>Lenient where [completionCarriers] is strict: a carrier of a shape the hook cannot take is
 * left out rather than stopping the build, since the comment filters do not own this hook and
 * still judge every translated comment at its bind without it.
 */
internal fun BytecodePatchContext.textTranslationCompletionCarriers(): List<MutableMethod> {
    val carriers = mutableListOf<MutableMethod>()
    classDefForEach { classDef ->
        for (method in classDef.methods) {
            if (isTextTranslationCompletionCarrier(classDef, method)) {
                carriers += mutableClassDefBy(classDef).findMutableMethodOf(method)
            }
        }
    }
    return carriers
}

/**
 * Whether [method] of [classDef] finishes a text batch in a shape the completion hook can take:
 * it carries the completion anchor, marks comments translated, returns V, and is either an
 * outlined static taking the runner or the runner's own `run()`.
 */
internal fun isTextTranslationCompletionCarrier(classDef: ClassDef, method: Method): Boolean {
    val instructions = method.implementation?.instructions ?: return false
    if (instructions.none { it.getReference<StringReference>()?.string == COMPLETION_ANCHOR }) return false
    val marksTranslated = instructions.any { instruction ->
        instruction.getReference<MethodReference>()?.let {
            it.definingClass == COMMENT_DESCRIPTOR && it.name == "setTranslated"
        } == true
    }
    if (!marksTranslated) return false
    val isStatic = AccessFlags.STATIC.isSet(method.accessFlags)
    val outlined = isStatic && method.parameterTypes.size == 1 &&
        method.parameterTypes.single().startsWith("L")
    val own = !isStatic && method.name == "run" && method.parameterTypes.isEmpty() &&
        classDef.holdsResultsAndTask()
    return method.returnType == "V" && (outlined || own)
}

/** Whether [method] carries the batch completion anchor at all, text or audio. */
internal fun carriesTranslationCompletionAnchor(method: Method): Boolean =
    method.implementation?.instructions?.any { it.getReference<StringReference>()?.string == COMPLETION_ANCHOR } == true

/** Exactly two instance reference fields, one of them a List: the results and the task. */
private fun ClassDef.holdsResultsAndTask(): Boolean {
    val references = fields.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.type.startsWith("L")
    }
    return references.size == 2 && references.count { it.type == "Ljava/util/List;" } == 1
}

@Suppress("unused")
val commentTranslationPatch = bytecodePatch(
    name = "Translate comments",
    description = "Translates comments as they load, using TikTok's own translator, so you don't have to tap Translate on each one. Starts off. Turn it on in Metra settings > Comments and translation, where more rows list languages TikTok's automatic translation should leave alone, pick the language translations come out in, and add TikTok's translate button beside the comment count.",
    default = true,
) {
    category("Comments")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCommentTranslation()V",
        )

        BaseCommentCellBindFingerprint.method.apply {
            val instructions = implementation!!.instructions
            val managerMatch = instructions.withIndex().mapNotNull { (index, instruction) ->
                val field = instruction.getReference<FieldReference>()
                    ?: return@mapNotNull null
                if (instruction.opcode != Opcode.IPUT_OBJECT ||
                    field.type != "Lcom/ss/android/ugc/aweme/comment/model/Comment;" ||
                    instruction !is TwoRegisterInstruction
                ) {
                    return@mapNotNull null
                }

                val managerRegister = instruction.registerB
                var matchingWrites = 0
                var lastWriteIndex = index
                val searchEnd = (index + 6).coerceAtMost(instructions.lastIndex)
                for (candidateIndex in (index + 1)..searchEnd) {
                    val candidate = instructions[candidateIndex]
                    val candidateField = candidate.getReference<FieldReference>()
                    if (candidate.opcode == Opcode.IPUT_OBJECT &&
                        candidate is TwoRegisterInstruction &&
                        candidate.registerB == managerRegister &&
                        candidateField?.definingClass == field.definingClass
                    ) {
                        matchingWrites++
                        lastWriteIndex = candidateIndex
                    }
                }

                if (matchingWrites >= 2) lastWriteIndex to managerRegister else null
            }.lastOrNull() ?: throw PatchException(
                "Translate comments: could not locate initialized native comment translation manager.",
            )
            val (managerReadyIndex, managerRegister) = managerMatch

            // A register nothing is holding here. This injects into the middle of the bind,
            // where v0 belongs to the host, and it was written over on the strength of being
            // dead on this one build.
            val cellRegister = getFreeRegisterProvider(
                managerReadyIndex + 1,
                1,
                listOf(managerRegister),
            ).getFreeRegister4Bit()

            addInstructions(
                managerReadyIndex + 1,
                """
                    move-object/from16 v$cellRegister, p0
                    iget-object v$cellRegister, v$cellRegister, Landroidx/recyclerview/widget/RecyclerView${'$'}ViewHolder;->itemView:Landroid/view/View;
                    invoke-static {v$cellRegister, v$managerRegister}, $EXTENSION_CLASS_DESCRIPTOR->registerCommentCell(Landroid/view/View;Ljava/lang/Object;)V
                """,
            )
        }

        CommentListLoadedFingerprint.method.apply {
            val responseReadyIndex = implementation!!.instructions.withIndex()
                .firstOrNull { (_, instruction) ->
                    instruction.getReference<FieldReference>()?.let { reference ->
                        reference.definingClass == "Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;" &&
                            reference.name == "lazySplitItemsParseTask"
                    } == true
                }?.index ?: throw PatchException(
                "Translate comments: could not locate loaded comment list response.",
            )

            val responseRegister = (implementation!!.instructions.elementAt(responseReadyIndex)
                as? TwoRegisterInstruction)?.registerB ?: throw PatchException(
                "Translate comments: the loaded comment list is not read from a register.",
            )

            addInstructions(
                responseReadyIndex,
                callThroughLocals(
                    "Translate comments",
                    "invoke-static",
                    "$EXTENSION_CLASS_DESCRIPTOR->onCommentListLoaded(Ljava/lang/Object;)V",
                    false,
                    objectIn("v$responseRegister"),
                ),
            )
        }

        MultiCommentTranslationStartFingerprint.method.apply {
            check(AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.size == 3 &&
                parameterTypes[2] == "Z"
            ) {
                "Translate comments: the batch start is not the three argument static this reads."
            }
            addInstructions(
                0,
                """
                    invoke-static/range {p0 .. p2}, $EXTENSION_CLASS_DESCRIPTOR->onNativeBatchStart(Ljava/lang/Object;Ljava/lang/Object;Z)V
                """,
            )
        }

        // The languages TikTok's automatic translation leaves alone (#121). Required on a declared
        // build, where DoNotAutoTranslateAnchorsTest holds it, and left out with a note on any
        // other, so a reworked translation service doesn't take comment translation down with it.
        val doNotAutoTranslate = try {
            hookDoNotAutoTranslate()
            true
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[Translate comments] Left out Don't auto translate on ${packageMetadata.versionName}: ${problem.message}")
            false
        }
        if (doNotAutoTranslate) {
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableDoNotAutoTranslate()V",
            )
        }

        // The language translations come out in. Held to declared builds the same way, by
        // TranslateIntoAnchorsTest.
        val translateInto = try {
            hookTranslateInto()
            true
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[Translate comments] Left out Translate into on ${packageMetadata.versionName}: ${problem.message}")
            false
        }
        if (translateInto) {
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableTranslateInto()V",
            )
        }

        // TikTok's translate button in the comment header (#85). Held to declared builds by
        // CommentTranslateButtonAnchorsTest and SwitchedReturnFixturesTest, and left out with a
        // note on any other, the same way.
        val translateButton = try {
            installCommentTranslateButton()
            true
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[Translate comments] Left out the translate button on ${packageMetadata.versionName}: ${problem.message}")
            false
        }
        if (translateButton) {
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCommentTranslateButton()V",
            )
        }

        // The translation rows TikTok hides on its own Settings and privacy > Language page for
        // some accounts (#143). Held to declared builds by LanguagePageTranslationsAnchorsTest and
        // left out with a note on any other, the same way.
        val languagePage = try {
            hookLanguagePageTranslations()
            true
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[Translate comments] Left out the Language page rows on ${packageMetadata.versionName}: ${problem.message}")
            false
        }
        if (languagePage) {
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLanguagePageTranslations()V",
            )
        }

        // See translation on videos, which the same server value takes away from those accounts
        // (#143). Held to declared builds by FeedTranslationsAnchorsTest the same way.
        val videos = try {
            hookFeedTranslations()
            true
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[Translate comments] Left out See translation on videos on ${packageMetadata.versionName}: ${problem.message}")
            false
        }
        if (videos) {
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFeedTranslations()V",
            )
        }

        // Every method carrying the anchor, not the first one a fingerprint happened to
        // match. On 46.2.3 the string sits in two bodies of the same class, both static and
        // both V(L), and `.method` takes one of them without a word about the other. A batch
        // finishing through the unhooked path was never marked done or failed, so its key sat
        // pending and the batch was either refused for good or asked for again on every bind.
        completionCarriers().forEach { carrier ->
            carrier.addInstructions(
                0,
                """
                    invoke-static/range {p0 .. p0}, $EXTENSION_CLASS_DESCRIPTOR->onNativeBatchComplete(Ljava/lang/Object;)V
                """,
            )
        }
    }
}
