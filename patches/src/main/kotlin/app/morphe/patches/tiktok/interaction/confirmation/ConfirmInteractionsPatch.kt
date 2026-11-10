/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.confirmation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.blockAuthorPatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.findFreeRegister
import app.morphe.patcher.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val TAP_CONFIRMATION = "Lapp/morphe/extension/tiktok/interaction/TapConfirmation;"

private object FollowClickFingerprint : Fingerprint(
    strings = listOf("VIDEO_CANCEL_REPORT_SKIP_BEHAVIOR", "click_add", "guide_dm").map { string(it, StringComparisonType.CONTAINS) },
    parameters = listOf("Landroid/view/View;"),
    returnType = "V",
    custom = { method, _ -> method.name == "onClick" },
)

private object LikeClickFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/assem/digg/VideoDiggAssem;",
    strings = listOf("click_like", "long_press_like").map { string(it, StringComparisonType.CONTAINS) },
    parameters = listOf("Landroid/view/View;", "Ljava/lang/String;"),
    returnType = "V",
)

/** TikTok logs this as a comment's like is tapped, from each of the three comment like controls. */
internal const val COMMENT_LIKE_LOG = "onLikeClicked: comment id "
internal const val COMMENT_MODEL = "Lcom/ss/android/ugc/aweme/comment/model/Comment;"

/**
 * The comment list's like and dislike view (TikTok's log calls it LikeAndHateView), a
 * FrameLayout of its own. Its like handler takes where the like came from: click_button for a
 * tap on the heart, double_tap for a double tap on the comment.
 */
internal object CommentLikeClickFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf(COMMENT_LIKE_LOG).map { string(it, StringComparisonType.CONTAINS) },
    custom = { _, classDef -> classDef.superclass == "Landroid/widget/FrameLayout;" },
)

/**
 * A photo post's comment like, which TikTok keeps in two versions with the same handler. Neither
 * keeps the comment where the list's view does: the first reads it off the item it is bound to,
 * the second off a field of its own, and both do so before anything else happens.
 */
internal object PhotoCommentLikeClickFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/comment/photocomment/CommentInnerDiggAssem;",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf(COMMENT_LIKE_LOG).map { string(it, StringComparisonType.CONTAINS) },
)

internal object PhotoCommentLikeClickV2Fingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/comment/photocomment/CommentInnerDiggAssemV2;",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
    strings = listOf(COMMENT_LIKE_LOG).map { string(it, StringComparisonType.CONTAINS) },
)

internal const val STORY_DIGG_ASSEM = "Lcom/ss/android/ugc/aweme/story/feed/immersive/digg/ImmersiveStoryDiggAssem;"
private const val VIDEO_DIGG_VM = "Lcom/ss/android/ugc/aweme/feed/assem/digg/VideoDiggVM;"
private const val VIDEO_ITEM_PARAMS = "Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"

/**
 * A story's like. The story's like buttons share one of TikTok's generated click listeners,
 * whose onClick serves many call sites told apart by an index, and each index leads to a static
 * method of its own. This is the one that casts to the story's like component, reads the story's
 * video and asks the like view model to toggle it. The listener class, the index and the method
 * name all change between builds (84 on 47.0.3, 99 on 46.9.3, 48 on 46.2.3); what it does doesn't.
 */
internal object StoryLikeClickFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Landroid/view/View;"),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) && method.isStoryLikeToggle() },
)

internal fun Method.isStoryLikeToggle(): Boolean {
    val instructions = implementation?.instructions ?: return false
    var story = false
    var video = false
    var toggle = false
    for (instruction in instructions) {
        if (instruction.opcode == Opcode.CHECK_CAST &&
            instruction.getReference<TypeReference>()?.type == STORY_DIGG_ASSEM
        ) story = true
        val call = instruction.getReference<MethodReference>() ?: continue
        if (call.definingClass == VIDEO_ITEM_PARAMS && call.name == "getAweme") video = true
        if (call.definingClass == VIDEO_DIGG_VM &&
            call.parameterTypes.map(CharSequence::toString) == listOf("Lkotlin/jvm/functions/Function2;")
        ) toggle = true
    }
    return story && video && toggle
}

/**
 * TikTok's one-tap repost from the bar under a feed video. The bar's repost button calls it with the
 * flag set and the repost guide on the same bar calls it with the flag clear. Either way the repost
 * goes out at once: the flag only picks the log label, quick_repost or normal. A repost with a note
 * is sent by its own panel and never comes here.
 */
internal object QuickRepostFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/upvote/detail/whitebar/UpvoteVideoAssemNew;",
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Z", "Lkotlin/jvm/functions/Function1;"),
    strings = listOf("quick_repost").map { string(it, StringComparisonType.CONTAINS) },
)

/** The first read of a comment in this method, or -1. */
internal fun Method.firstCommentRead(): Int = implementation?.instructions?.indexOfFirst {
    it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.type == COMMENT_MODEL
} ?: -1

/** Asks with the comment the method has just read, before it has done anything else. */
internal fun MutableMethod.hookAfterCommentRead() {
    val read = firstCommentRead()
    if (read < 0) throw PatchException("Confirm interactions: $definingClass no longer reads the comment it likes")
    val comment = getInstruction<TwoRegisterInstruction>(read).registerA
    val owner = implementation!!.registerCount - parameterTypes.size - 1
    val at = read + 1
    val answer = findFreeRegister(at, comment, owner)
    addInstructionsWithLabels(at, """
        invoke-static { p0, v$comment }, $TAP_CONFIRMATION->commentLike(Ljava/lang/Object;Ljava/lang/Object;)Z
        move-result v$answer
        if-nez v$answer, :allow_comment_like
        return-void
    """, ExternalLabel("allow_comment_like", getInstruction(at)))
}

/**
 * Asks after the story's video is read, so the extension judges that story rather than the
 * feed video behind the story viewer, and before TikTok asks the view model to like it.
 */
internal fun MutableMethod.hookStoryLike() {
    val read = indexOfFirstInstructionOrThrow {
        getReference<MethodReference>()?.let { it.definingClass == VIDEO_ITEM_PARAMS && it.name == "getAweme" } == true
    }
    val result = getInstruction(read + 1)
    if (result.opcode != Opcode.MOVE_RESULT_OBJECT || result !is OneRegisterInstruction) {
        throw PatchException("Confirm interactions: the story like no longer keeps the video it reads")
    }
    val video = result.registerA
    val at = read + 2
    val answer = findFreeRegister(at, video)
    addInstructionsWithLabels(at, """
        invoke-static { p1, v$video }, $TAP_CONFIRMATION->storyLike(Landroid/view/View;Ljava/lang/Object;)Z
        move-result v$answer
        if-nez v$answer, :allow_story_like
        return-void
    """, ExternalLabel("allow_story_like", getInstruction(at)))
}

@Suppress("unused")
val confirmInteractionsPatch = bytecodePatch(
    name = "Confirm feed interactions",
    description = "Asks for a second tap before Follow, a like, or a quick repost goes through, so a stray tap doesn't count. Starts off. Turn it on in Metra settings > Interface.",
) {
    category("Interaction")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(settingsPatch, sharedExtensionPatch, blockAuthorPatch)
    execute {
        listOf(FollowClickFingerprint to "follow", LikeClickFingerprint to "like").forEach { (fingerprint, callback) ->
            val method = fingerprint.method
            method.requireLocals("Confirm interactions", 1)
            method.addInstructionsWithLabels(0, """
                invoke-static/range { p1 .. p1 }, $TAP_CONFIRMATION->$callback(Landroid/view/View;)Z
                move-result v0
                if-nez v0, :original
                return-void
            """, ExternalLabel("original", method.getInstruction(0)))
        }
        // The comment list's like view reads its comment late, after its tracing has started, so
        // it asks on the way in and the extension reads the comment off the view.
        CommentLikeClickFingerprint.method.apply {
            requireLocals("Confirm interactions", 1)
            addInstructionsWithLabels(0, """
                invoke-static/range { p0 .. p0 }, $TAP_CONFIRMATION->commentLike(Ljava/lang/Object;)Z
                move-result v0
                if-nez v0, :original
                return-void
            """, ExternalLabel("original", getInstruction(0)))
        }
        PhotoCommentLikeClickFingerprint.method.hookAfterCommentRead()
        PhotoCommentLikeClickV2Fingerprint.method.hookAfterCommentRead()
        StoryLikeClickFingerprint.method.hookStoryLike()
        // Every repost the bar sends asks, from its repost button and from its guide alike.
        QuickRepostFingerprint.method.apply {
            requireLocals("Confirm interactions", 1)
            addInstructionsWithLabels(0, """
                invoke-static/range { p0 .. p0 }, $TAP_CONFIRMATION->quickRepost(Ljava/lang/Object;)Z
                move-result v0
                if-nez v0, :original
                return-void
            """, ExternalLabel("original", getInstruction(0)))
        }
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableConfirmInteractions()V")
    }
}
