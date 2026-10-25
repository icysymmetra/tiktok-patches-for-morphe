/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.BlockServiceFingerprint
import app.morphe.patches.tiktok.interaction.blockauthor.sessionPlaybackBridgePatch
import app.morphe.patches.tiktok.interaction.gesture.doubleTapPatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.translation.BaseCommentCellBindFingerprint
import app.morphe.patches.tiktok.misc.translation.CommentListLoadedFingerprint
import app.morphe.patches.tiktok.misc.translation.textTranslationCompletionCarriers
import app.morphe.patches.tiktok.shared.callThroughLocals
import app.morphe.patches.tiktok.shared.constantBefore
import app.morphe.patches.tiktok.shared.dispatchTarget
import app.morphe.patches.tiktok.shared.dispatchesOnIndex
import app.morphe.patches.tiktok.shared.objectIn
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.util.getFreeRegisterProvider
import app.morphe.patcher.util.getReference
import app.morphe.util.writeRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/comment/CommentTools;"
private const val TRANSLATED_COMMENT_FILTER_DESCRIPTOR = "Lapp/morphe/extension/tiktok/comment/TranslatedCommentFilter;"
private const val COMMENT_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/comment/model/Comment;"
private const val COMMENT_LIST_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;"
private const val TOUCH_LISTENER_DESCRIPTOR = "Landroid/view/View\$OnTouchListener;"
private const val RELATIVE_LAYOUT = "Landroid/widget/RelativeLayout;"

/** The optional banner factory already returns null when TikTok has no related search. */
internal fun isCommentSearchHeaderFactory(method: Method): Boolean {
    if (method.definingClass != COMMENT_SCENE_SERVICE_IMPL ||
        method.returnType != "Landroid/view/View;" ||
        method.parameterTypes.take(2).map(CharSequence::toString) != listOf(
            "Landroid/content/Context;", "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
        )
    ) return false
    val instructions = method.implementation?.instructions?.toList() ?: return false
    return instructions.any { it.getReference<StringReference>()?.string == "comment_top" } &&
        instructions.any {
            it.getReference<MethodReference>()?.let { ref ->
                ref.definingClass == "Lcom/ss/android/ugc/aweme/feed/model/Aweme;" &&
                    ref.name == "getCommentSuggestWordList" && ref.parameterTypes.isEmpty() &&
                    ref.returnType == "Lcom/ss/android/ugc/aweme/feed/model/search/CommentSuggestWordList;"
            } == true
        }
}

private object CommentSearchHeaderFactoryFingerprint : Fingerprint(
    custom = { method, _ -> isCommentSearchHeaderFactory(method) },
)

internal fun MutableMethod.resolveCommentSearchSuggestions(): CommentToolsWrite {
    requireLocals("Comment tools", 1)
    return {
        guardAtEntry(
            "Comment tools",
            "invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->shouldHideCommentSearchSuggestions()Z",
            "const/4 v0, 0x0\nreturn-object v0",
        )
    }
}

internal const val COMMENT_SCENE_SERVICE = "Lcom/ss/android/ugc/aweme/search/service/ISearchCommentSceneService;"
internal const val COMMENT_SCENE_SERVICE_IMPL =
    "Lcom/ss/android/ugc/aweme/search/common/communicate/SearchCommentSceneServiceImpl;"
internal const val COMMENT_TOP_RELATED_SEARCH =
    "Lcom/ss/android/ugc/aweme/search/pages/result/topsearch/rs/core/refactor/SearchCommentTopRelatedImpl;"

/**
 * The comment search service's own "no comment suggestions" check (#161), asked by every comment
 * sheet surface for the suggestion banner before it takes the header: the newer top bar's canShow
 * and the older header anchor's check. Guarding only the banner factory left the older anchor's
 * slot claimed with nothing in it, which TikTok then laid out as a 48 dp blank band over the
 * comment count. The check is renamed per build (`LLLZLL` on 47.1.4's AbsSearchService, `LJIIIZ`
 * on 47.4.3's SearchCommentSceneServiceImpl), so its name is read off the one comment search
 * service question the real-named top bar's canShow asks. 47.4.3 asks it through an obfuscated
 * facade whose method of the same name forwards to the service, so a facade call is followed one
 * step.
 */
internal fun commentSuggestionGate(classOf: (String) -> ClassDef?): Method {
    fun fail(why: String): Nothing = throw PatchException("Comment tools: $why")
    val related = classOf(COMMENT_TOP_RELATED_SEARCH) ?: fail("$COMMENT_TOP_RELATED_SEARCH is not in the app")
    val canShow = related.methods.singleOrNull { it.name == "canShow" && it.returnType == "Z" }
        ?: fail("expected one canShow on $COMMENT_TOP_RELATED_SEARCH")
    val names = canShow.questions().flatMap { ask ->
        if (isCommentSceneService(ask.definingClass, classOf)) listOf(ask.name)
        else classOf(ask.definingClass)?.methods
            ?.singleOrNull { it.name == ask.name && it.parameterTypes.isEmpty() && it.returnType == "Z" }
            ?.questions()?.filter { isCommentSceneService(it.definingClass, classOf) }?.map { it.name }
            .orEmpty()
    }.distinct()
    val name = names.singleOrNull()
        ?: fail("expected the comment top bar to ask the comment search service one question, found ${names.size}")
    val service = classOf(COMMENT_SCENE_SERVICE_IMPL)?.takeIf { COMMENT_SCENE_SERVICE in it.interfaces }
        ?: fail("$COMMENT_SCENE_SERVICE_IMPL is not in the app")
    return service.methods.singleOrNull {
        it.name == name && it.parameterTypes.isEmpty() && it.returnType == "Z" && it.implementation != null
    } ?: fail("$COMMENT_SCENE_SERVICE_IMPL has no $name()Z to close")
}

private fun Method.questions(): List<MethodReference> = implementation?.instructions?.toList().orEmpty()
    .mapNotNull { it.getReference<MethodReference>() }
    .filter { it.parameterTypes.isEmpty() && it.returnType == "Z" }

private fun isCommentSceneService(type: String, classOf: (String) -> ClassDef?): Boolean =
    type == COMMENT_SCENE_SERVICE || classOf(type)?.interfaces?.contains(COMMENT_SCENE_SERVICE) == true

/** True closes the gate, which every asker already reads as "no suggestion for this video". */
internal fun MutableMethod.resolveCommentSuggestionGate(): CommentToolsWrite {
    requireLocals("Comment tools", 1)
    return {
        guardAtEntry(
            "Comment tools",
            "invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->closeCommentSuggestionGate()Z",
            "const/4 v0, 0x1\nreturn v0",
        )
    }
}

/**
 * The comment like-and-hate view's bind. The view keeps no name from build to build (`LX/0nvj;`
 * on 46.2.3, `LX/0lNa;` on 46.8.3), but the line its bind logs is the same on every build and in
 * no other method of the app.
 */
private object CommentLikeAndHateBindFingerprint : Fingerprint(
    strings = listOf("diggView bind: comment id ").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The cell keeps its own name and so does `onBindItemView`; the model it is handed does not. That
 * was `LX/0lOS;` on 46.2.3 and is `LX/0U5F;` on 46.8.3, and the class has one `onBindItemView`
 * on every build, so the parameter is taken as any object.
 */
private object CommentMoreCellBindFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/commentv2/commentlist/powercell/CommentMoreItemCell;",
    returnType = "V",
    parameters = listOf("L"),
    custom = { method, _ -> method.name == "onBindItemView" },
)

internal typealias CommentToolsWrite = () -> Unit

/** Resolve every host contract before applying any write the patcher cannot roll back. */
internal fun applyAfterCommentToolsPreflight(vararg resolve: () -> CommentToolsWrite) {
    val writes = resolve.map { it() }
    writes.forEach { it() }
}

/**
 * Hooks the same two places the comment translation patch does: the comment cell being
 * bound (to attach the block gesture) and the comment list response being handled (to
 * filter it). The fingerprints are shared with that patch; the injection is repeated here
 * so this patch works without it.
 */
@Suppress("unused")
val commentToolsPatch = bytecodePatch(
    name = "Comment tools",
    description = "Hides comments with words you pick or from accounts you pick, and adds a " +
        "search box for a video's comments. It can also make links tappable and show poll results " +
        "before you vote. Starts off. Turn it on in Metra settings > Comments and translation.",
) {
    category("Comments")
    // The player bridge: "Pause the video while comments are open" pauses the video through
    // TikTok's own pause, which only that patch wires in. Without it the request for the audio
    // focus was all the switch did, and 47.x's player plays on through that.
    dependsOn(settingsPatch, sharedExtensionPatch, doubleTapPatch, sessionPlaybackBridgePatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // The patcher keeps writes made by a patch that later fails. Each resolver below returns
        // a deferred write, so even the last reply/list/register refusal leaves the APK untouched.
        applyAfterCommentToolsPreflight(
            {
                // The keyword filter judges a translation as TikTok's text completion finishes,
                // before the text lands in the comment. It is hooked here, not only by Translate
                // comments, because the filter's switch is Comment tools' own and TikTok's own
                // translations reach it without that patch.
                val carriers = textTranslationCompletionCarriers()
                val write: CommentToolsWrite = {
                    carriers.forEach { carrier ->
                        carrier.addInstructions(
                            0,
                            "invoke-static/range {p0 .. p0}, $TRANSLATED_COMMENT_FILTER_DESCRIPTOR->onTextBatchComplete(Ljava/lang/Object;)V",
                        )
                    }
                }
                write
            },
            {
                val writes = compactCommentHeaderComponents.keys.map { owner ->
                    val method = mutableClassDefBy(owner).methods.singleOrNull(::isCompactCommentHeaderBind)
                        ?: throw PatchException("Comment tools: expected one compact header bind in $owner")
                    method.resolveCompactCommentHeader()
                }
                val write: CommentToolsWrite = { writes.forEach { it() } }
                write
            },
            { CommentSearchHeaderFactoryFingerprint.method.resolveCommentSearchSuggestions() },
            {
                val gate = commentSuggestionGate { classDefByOrNull(it) }
                mutableClassDefBy(gate.definingClass).findMutableMethodOf(gate).resolveCommentSuggestionGate()
            },
            {
                // A comment poll draws its bars and percentages once the poll has ended or the
                // reader voted, and one method on the poll row answers that for everything the row
                // draws. The counts are already in the page TikTok fetched, so Show poll results
                // before voting answers yes first. A tap on an answer is wired without it.
                val method = PollShowsResultsFingerprint.method
                method.requireLocals("Comment tools", 1)
                val write: CommentToolsWrite = {
                    method.guardAtEntry(
                        "Comment tools",
                        "invoke-static {}, $POLL_RESULTS_CLASS_DESCRIPTOR->showBeforeVoting()Z",
                        "return v0",
                    )
                }
                write
            },
            {
                // A comment survey comes from one config getter, and null is its own answer for
                // no survey. Hide comment surveys asks first and, on yes, answers null.
                val method = CommentSurveyConfigFingerprint.method
                method.requireLocals("Comment tools", 1)
                val write: CommentToolsWrite = {
                    method.guardAtEntry(
                        "Comment tools",
                        "invoke-static {}, $COMMENT_SURVEY_CLASS_DESCRIPTOR->hide()Z",
                        "const/4 v0, 0x0\nreturn-object v0",
                    )
                }
                write
            },
            {
                // The comment box's photo, @ and gift buttons go to Hide comment box buttons as the
                // input's setup stores them. The bar's click listeners say which field is which.
                val fields = commentBoxButtonFields(FakeInputViewCreatedFingerprint.method) { classDefByOrNull(it) }
                CommentInputViewCreatedFingerprint.method.resolveCommentBoxButtons(fields)
            },
            {
                // Tapping more under a video can open its comments with the caption at the top
                // (upstream #156). The caption's expand asks first and, on yes, Double-tap
                // controls' comment press opens the sheet. TikTok's comment list starts with the
                // caption as a row when its opener asks, and the extension answers for that list.
                CaptionExpandFingerprint.method.resolveCaptionOpensComments()
            },
            { CaptionRowFingerprint.method.resolveCaptionRow() },
            {
                // Block this commenter in the menu a long press on a comment opens (#169). The
                // finished rows pass through the extension on their way into the panel's adapter,
                // and the row's icon is TikTok's own block glyph in the menu's icon style.
                val icon = commentMenuBlockIcon(
                    CommentMenuItemsFingerprint.originalMethod,
                    AdReportActionKeyFingerprint.matchAll().map { it.originalClassDef },
                    ProfileBlockActionKeyFingerprint.originalClassDef,
                )
                CommentMenuListFingerprint.method.resolveCommentMenuBlock(icon)
            },
            {
                val settingsStatus = SettingsStatusLoadFingerprint.method
                // Blocking a commenter goes through the same BlockApi as the block button. Fail
                // the build rather than ship a gesture that silently does nothing.
                BlockServiceFingerprint.method
                val write: CommentToolsWrite = {
                    settingsStatus.addInstruction(
                        0,
                        "invoke-static {}, " +
                            "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCommentTools()V",
                    )
                }
                write
            },
            {
                // The view installs its like and dislike touch listeners from one parameterless
                // method, each on a RelativeLayout it keeps. Found inside the view's own class.
                val likeAndHate = CommentLikeAndHateBindFingerprint.originalClassDef
                val installers = likeAndHate.methods.filter {
                    it.returnType == "V" && it.parameterTypes.isEmpty() && it.touchInstalls().isNotEmpty()
                }
                if (installers.size != 1) {
                    throw PatchException(
                        "Comment tools: expected one method of ${likeAndHate.type} to install its " +
                            "touch listeners, found ${installers.size}",
                    )
                }
                val method = mutableClassDefBy(likeAndHate.type).findMutableMethodOf(installers.single())
                val dislike = method.resolveDislikeTouchListener { classDefByOrNull(it) }
                val like = method.resolveLikeTouchListener { classDefByOrNull(it) }
                val write: CommentToolsWrite = { dislike(); like() }
                write
            },
            {
                CommentMoreCellBindFingerprint.method.resolveReplySearch { classDefByOrNull(it) }
            },
            {
                val method = BaseCommentCellBindFingerprint.method
                val instructions = method.implementation!!.instructions
                val managerMatch = instructions.withIndex().mapNotNull { (index, instruction) ->
                    val field = instruction.getReference<FieldReference>() ?: return@mapNotNull null
                    if (instruction.opcode != Opcode.IPUT_OBJECT ||
                        field.type != COMMENT_DESCRIPTOR ||
                        instruction !is TwoRegisterInstruction
                    ) {
                        return@mapNotNull null
                    }

                    // The cell's state holder is written in a burst of iput-objects into the
                    // same register; the comment is one of them. Take the last write of the
                    // burst so the holder is fully initialised when the hook runs.
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
                    "Comment tools: could not locate the initialised comment cell state holder.",
                )
                val (managerReadyIndex, managerRegister) = managerMatch

                // Plain invoke-static cannot encode a register above v15 (see the block button
                // patch); the translation patch relies on this register being low as well.
                if (managerRegister > 15) {
                    throw PatchException(
                        "Comment tools: cell state register v$managerRegister is above v15.",
                    )
                }

                // A register nothing is holding here. This injects into the middle of the bind,
                // where v0 belongs to the host, and it was written over on the strength of being
                // dead on this one build.
                val cellRegister = method.getFreeRegisterProvider(
                    managerReadyIndex + 1,
                    1,
                    listOf(managerRegister),
                ).getFreeRegister4Bit()
                val write: CommentToolsWrite = {
                    method.addInstructions(
                        managerReadyIndex + 1,
                        """
                            move-object/from16 v$cellRegister, p0
                            iget-object v$cellRegister, v$cellRegister, Landroidx/recyclerview/widget/RecyclerView${'$'}ViewHolder;->itemView:Landroid/view/View;
                            invoke-static {v$cellRegister, v$managerRegister}, $EXTENSION_CLASS_DESCRIPTOR->registerCommentCell(Landroid/view/View;Ljava/lang/Object;)V
                        """,
                    )
                }
                write
            },
            {
                val method = CommentListLoadedFingerprint.method
                val responseReadyIndex = method.implementation!!.instructions.withIndex()
                    .firstOrNull { (_, instruction) ->
                        instruction.getReference<FieldReference>()?.let { reference ->
                            reference.definingClass == COMMENT_LIST_DESCRIPTOR &&
                                reference.name == "lazySplitItemsParseTask"
                        } == true
                    }?.index ?: throw PatchException(
                        "Comment tools: could not locate the loaded comment list response.",
                    )

                val responseRegister = (method.implementation!!.instructions.elementAt(responseReadyIndex)
                    as? TwoRegisterInstruction)?.registerB ?: throw PatchException(
                    "Comment tools: the loaded comment list is not read from a register.",
                )
                val bridge = method.callThroughLocals(
                    "Comment tools",
                    "invoke-static",
                    "$EXTENSION_CLASS_DESCRIPTOR->onCommentListLoaded(Ljava/lang/Object;)V",
                    false,
                    objectIn("v$responseRegister"),
                )
                val write: CommentToolsWrite = {
                    method.addInstructions(responseReadyIndex, bridge)
                }
                write
            },
        )
    }
}

/**
 * The reply control's bound model and the three members the hook reads off it: its state, the
 * data it keeps about the parent comment, and the parent itself. Every one is renamed per build
 * (`LX/0nlo;`, `LIZ()I`, `LLILZIL` and `LX/0nls;->LJI` on 46.2.3; `LX/0lDH;`, `LIZ()I`,
 * `LLJILLL` and `LX/0lDJ;->LJI` on 46.8.3), so none is written down: the model is what the bind
 * casts its item to, the state is the one `()I` the cell's own relayout of that model reads, and
 * the parent is the one `Comment` held by one of the model's fields.
 */
private class ReplyModel(
    val type: String,
    val state: MethodReference,
    val data: FieldReference,
    val parent: FieldReference,
)

private fun MutableMethod.replyModel(classOf: (String) -> ClassDef?): ReplyModel {
    fun fail(why: String): Nothing = throw PatchException("Comment tools: $why")
    val instructions = implementation?.instructions?.toList() ?: fail("reply bind has no body")
    val item = implementation!!.registerCount - 1

    // The model: what the bind casts its item to, directly or through the copy made just before.
    val type = instructions.withIndex().firstNotNullOfOrNull { (index, instruction) ->
        if (instruction.opcode != Opcode.CHECK_CAST) return@firstNotNullOfOrNull null
        val register = (instruction as OneRegisterInstruction).registerA
        val copy = instructions.getOrNull(index - 1)
        val holdsItem = register == item ||
            (copy is TwoRegisterInstruction && copy.opcode.name.startsWith("move-object") &&
                copy.registerA == register && copy.registerB == item)
        if (holdsItem) instruction.getReference<TypeReference>()?.type else null
    } ?: fail("the reply bind does not cast its item to a model")

    // The state: the one no-argument int the cell's own (model, boolean) relayout reads off it.
    val cell = classOf(definingClass) ?: fail("the reply cell $definingClass is not in the app")
    val relayouts = cell.methods.filter {
        it.parameterTypes.map(CharSequence::toString) == listOf(type, "Z") && it.returnType == "V"
    }
    if (relayouts.size != 1) fail("expected one ($type, boolean) method on the reply cell, found ${relayouts.size}")
    val states = relayouts.single().implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.INVOKE_VIRTUAL }
        .mapNotNull { it.getReference<MethodReference>() }
        .filter {
            it.definingClass == type && it.parameterTypes.isEmpty() && it.returnType == "I" &&
                it.name != "hashCode"
        }
        .distinctBy { it.name }
    if (states.size != 1) fail("expected the reply cell to read one state off $type, found ${states.size}")

    // The parent: the one Comment held by one of the model's fields.
    val model = classOf(type) ?: fail("the reply model $type is not in the app")
    val holders = model.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.mapNotNull { field ->
        val comments = classOf(field.type)?.fields
            ?.filter { it.type == COMMENT_DESCRIPTOR && !AccessFlags.STATIC.isSet(it.accessFlags) }
            .orEmpty()
        if (comments.size == 1) field to comments.single() else null
    }
    if (holders.size != 1) fail("expected one field of $type to hold the parent comment, found ${holders.size}")
    val (data, parent) = holders.single()
    return ReplyModel(type, states.single(), data, parent)
}

private val FieldReference.smali get() = "$definingClass->$name:$type"

/** Resolve every reply row insertion without changing the bind method. */
internal fun MutableMethod.resolveReplySearch(classOf: (String) -> ClassDef?): CommentToolsWrite {
    val native = implementation ?: throw PatchException("Comment tools: reply bind has no body")
    val model = replyModel(classOf)
    val returns = native.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    if (returns.isEmpty()) throw PatchException("Comment tools: reply bind has no return")
    val holderRegister = native.registerCount - 2
    val itemRegister = native.registerCount - 1
    val insertions = returns.reversed().map { index ->
        val registers = getFreeRegisterProvider(index, 3, listOf(holderRegister, itemRegister))
        val viewRegister = registers.getFreeRegister4Bit()
        val modelRegister = registers.getFreeRegister4Bit()
        val stateRegister = registers.getFreeRegister4Bit()
        // The relayout has finished changing the control's native height. Its model owns the
        // parent Comment and computes state4 for a control that must stay collapsed when search
        // clears.
        index to """
                move-object/from16 v$viewRegister, p0
                iget-object v$viewRegister, v$viewRegister, Landroidx/recyclerview/widget/RecyclerView${'$'}ViewHolder;->itemView:Landroid/view/View;
                move-object/from16 v$modelRegister, p1
                check-cast v$modelRegister, ${model.type}
                invoke-virtual {v$modelRegister}, ${model.type}->${model.state.name}()I
                move-result v$stateRegister
                iget-object v$modelRegister, v$modelRegister, ${model.data.smali}
                iget-object v$modelRegister, v$modelRegister, ${model.parent.smali}
                invoke-static {v$viewRegister, v$modelRegister, v$stateRegister}, Lapp/morphe/extension/tiktok/comment/CommentSearch;->onReplyControlBound(Landroid/view/View;Ljava/lang/Object;I)V
            """
    }
    return {
        for ((index, instructions) in insertions) {
            addInstructionsAtControlFlowLabel(index, instructions)
        }
    }
}

/** The reply control is a separate holder whose bound model carries its parent comment. */
internal fun MutableMethod.registerReplySearch(classOf: (String) -> ClassDef?) {
    resolveReplySearch(classOf).invoke()
}

/**
 * One `setOnTouchListener` on a RelativeLayout the view keeps, with the merged listener group and
 * the number it was built with.
 */
/** One `setOnTouchListener` call: where it is, the listener's class, and the number that picks its body in a merged group, or null for a class of its own. */
private class TouchInstall(val index: Int, val group: String, val number: Int?)

/**
 * Every touch listener the method installs on a RelativeLayout field of its own class, built
 * just above the install as `new Group(view, number)`. Anything else is not one of these.
 */
private fun Method.touchInstalls(): List<TouchInstall> {
    val instructions = implementation?.instructions?.toList() ?: return emptyList()
    return instructions.withIndex().mapNotNull { (index, instruction) ->
        val target = instruction.getReference<MethodReference>()
        if ((instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) ||
            target?.definingClass != "Landroid/view/View;" || target.name != "setOnTouchListener" ||
            target.parameterTypes.map(CharSequence::toString) != listOf(TOUCH_LISTENER_DESCRIPTOR) ||
            target.returnType != "V"
        ) {
            return@mapNotNull null
        }
        val (receiver, listener) = when (instruction) {
            is FiveRegisterInstruction -> instruction.registerC to instruction.registerD
            is RegisterRangeInstruction -> instruction.startRegister to instruction.startRegister + 1
            else -> return@mapNotNull null
        }
        val field = instructions.getOrNull(instructions.writerOf(receiver, index))
            ?.takeIf { it.opcode == Opcode.IGET_OBJECT }
            ?.getReference<FieldReference>()
        if (field?.definingClass != definingClass || field.type != RELATIVE_LAYOUT) return@mapNotNull null

        // The listener: the constructor call on the register handed over, taking the view and
        // then the number that picks its body.
        val initIndex = (index - 1 downTo 0).firstOrNull { at ->
            val call = instructions[at]
            val first = when (call) {
                is FiveRegisterInstruction -> call.registerC
                is RegisterRangeInstruction -> call.startRegister
                else -> null
            }
            (call.opcode == Opcode.INVOKE_DIRECT || call.opcode == Opcode.INVOKE_DIRECT_RANGE) &&
                call.getReference<MethodReference>()?.name == "<init>" && first == listener
        } ?: return@mapNotNull null
        val init = instructions[initIndex]
        val constructor = init.getReference<MethodReference>()!!
        // A merged listener group takes the number that picks its body last. A listener class
        // of its own, which is what 46.9.3 makes of both of these, takes the view alone and has
        // nothing to pick with: its own onTouch is the body.
        if (constructor.parameterTypes.lastOrNull()?.toString() != "I") {
            return@mapNotNull TouchInstall(index, constructor.definingClass, null)
        }
        val numberRegister = when (init) {
            is FiveRegisterInstruction -> listOf(init.registerC, init.registerD, init.registerE, init.registerF, init.registerG)[init.registerCount - 1]
            is RegisterRangeInstruction -> init.startRegister + init.registerCount - 1
            else -> return@mapNotNull null
        }
        val number = constantBefore(instructions, initIndex, numberRegister) ?: return@mapNotNull null
        TouchInstall(index, constructor.definingClass, number)
    }
}

/** The body the group's own `onTouch` dispatches to for the install's number. */
private fun TouchInstall.body(classOf: (String) -> ClassDef?): Method? {
    val listeners = classOf(group) ?: return null
    var at: Method = listeners.methods.firstOrNull {
        it.name == "onTouch" && it.returnType == "Z" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;", "Landroid/view/MotionEvent;")
    } ?: return null
    // No number, no group: the class's own onTouch is the body, as long as it is not a group
    // dispatcher that was somehow built without one.
    val number = number ?: return if (at.dispatchesOnIndex()) null else at
    repeat(4) {
        val next = at.dispatchTarget(listeners, number) ?: return null
        if (!next.dispatchesOnIndex()) return next
        at = next
    }
    return null
}

/** Whether the method asks the comment the named question. */
private fun Method.asksComment(question: String) =
    implementation?.instructions?.any {
        val call = it.getReference<MethodReference>()
        call?.definingClass == COMMENT_DESCRIPTOR && call.name == question
    } == true

/** The nearest instruction above [index] that writes [register], or -1. */
private fun List<Instruction>.writerOf(register: Int, index: Int): Int {
    for (at in index - 1 downTo 0) {
        if (this[at].writeRegister == register) return at
    }
    return -1
}

/**
 * The dislike's native touch listener, handed to the extension instead of installed.
 *
 * <p>The view installs the like and the dislike back to back, identical in shape, differing only
 * by the RelativeLayout each lands on (both R8 names) and by the number the one merged listener
 * group is built with. Taking the last install worked on 46.2.3 and would have put the block
 * gesture on the like button the day the order flipped. Which is which is read off what the
 * listener does when touched: the like asks the comment `isUserDigged`, the dislike
 * `isUserBuried`, and both names are the named model's own. 46.2.3 installs the listener once
 * in one rollout and on every bind in the other; both come through here.
 */
internal fun MutableMethod.resolveDislikeTouchListener(classOf: (String) -> ClassDef?): CommentToolsWrite {
    return resolveCommentTouchListener(classOf, "isUserBuried", "isUserDigged", "dislike",
        "$EXTENSION_CLASS_DESCRIPTOR->setDislikeTouchListener")
}

internal fun MutableMethod.resolveLikeTouchListener(classOf: (String) -> ClassDef?): CommentToolsWrite {
    return resolveCommentTouchListener(classOf, "isUserDigged", "isUserBuried", "like",
        "Lapp/morphe/extension/tiktok/comment/CommentLikeTouchTarget;->setNativeListener")
}

private fun MutableMethod.resolveCommentTouchListener(
    classOf: (String) -> ClassDef?, question: String, excludedQuestion: String, control: String, hook: String,
): CommentToolsWrite {
    val instructions = implementation!!.instructions.toList()
    val installs = touchInstalls().filter { install ->
        val body = install.body(classOf) ?: return@filter false
        body.asksComment(question) && !body.asksComment(excludedQuestion)
    }
    if (installs.size != 1) {
        throw PatchException("Comment tools: expected one native $control touch install, found ${installs.size}")
    }
    val index = installs.single().index
    val instruction = instructions[index]
    val (count, operands) = when (instruction) {
        is FiveRegisterInstruction -> Pair(
            instruction.registerCount,
            "{v${instruction.registerC}, v${instruction.registerD}}",
        )
        is RegisterRangeInstruction -> Pair(
            instruction.registerCount,
            "{v${instruction.startRegister} .. v${instruction.startRegister + 1}}",
        )
        else -> throw PatchException("Comment tools: unexpected native touch invocation")
    }
    if (count != 2) throw PatchException("Comment tools: native $control touch receiver changed")
    val range = if (instruction is RegisterRangeInstruction) "/range" else ""
    val replacement =
        "invoke-static$range $operands, $hook(Landroid/view/View;$TOUCH_LISTENER_DESCRIPTOR)V"
    return { replaceInstruction(index, replacement) }
}

internal fun MutableMethod.captureDislikeTouchListener(classOf: (String) -> ClassDef?) {
    resolveDislikeTouchListener(classOf).invoke()
}
