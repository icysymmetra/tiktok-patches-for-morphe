/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstruction
import com.android.tools.smali.dexlib2.AccessFlags

/*
 * Seek bar on video stories (#72), part of Show the progress bar.
 *
 * A video story is a cell whose root component, StoryVideoCellRootComponent, is one of the classes
 * TikTok did not rename. Its onViewCreated hands over the view the cell built, a layout whose root
 * holds the story's rounded video frame, and its one instance method taking a VideoItemParams is
 * the bind, run with each story the cell shows. Photo stories are other cells, so they never reach
 * either. The story panels play through PlayerController, whose onPlayProgressChange reports the
 * story's id, the position and the length on every tick; the extension puts its line into the frame
 * of the cell showing that story, and a drag on it seeks through the controller's player manager.
 */

private const val PATCH = "Show the progress bar"
internal const val STORY_SEEKBAR = "Lapp/morphe/extension/tiktok/seekbar/StorySeekbar;"

/** The video story cell's root component, a real class name on every declared build. */
internal const val STORY_VIDEO_CELL =
    "Lcom/ss/android/ugc/aweme/story/feed/common/sub/video/component/StoryVideoCellRootComponent;"

internal const val VIDEO_ITEM_PARAMS = "Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"

/** The cell's onViewCreated, which hands over the view the cell built. */
internal object StoryVideoCellViewFingerprint : Fingerprint(
    definingClass = STORY_VIDEO_CELL,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

/**
 * The cell's bind: its one instance method taking a VideoItemParams and returning nothing. The name
 * is one R8 gave it; the generic bind that takes an Object calls it.
 */
internal object StoryVideoCellBindFingerprint : Fingerprint(
    definingClass = STORY_VIDEO_CELL,
    returnType = "V",
    parameters = listOf(VIDEO_ITEM_PARAMS),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) },
)

/*
 * Each hook checks its method and returns the call it would add, adding nothing yet, so the patch
 * can check all three before any goes in.
 */

/** Hands the extension the view the story cell built, before the cell sets it up. */
internal fun MutableMethod.storyCellViewHook(): () -> Unit {
    requireInstanceParameters(listOf("Landroid/view/View;"))
    return { addInstruction(0, "invoke-static/range {p0 .. p1}, $STORY_SEEKBAR->onCellView(Ljava/lang/Object;Landroid/view/View;)V") }
}

/** Hands the extension each story the cell is bound to, before the cell shows it. */
internal fun MutableMethod.storyCellBindHook(): () -> Unit {
    requireInstanceParameters(listOf(VIDEO_ITEM_PARAMS))
    return { addInstruction(0, "invoke-static/range {p0 .. p1}, $STORY_SEEKBAR->onCellBind(Ljava/lang/Object;Ljava/lang/Object;)V") }
}

/**
 * Hands the extension every progress tick: the controller, the story's id, the position and the
 * length, p0 to p5 as they arrive. A range call takes them in place, however high the frame puts
 * them.
 */
internal fun MutableMethod.storyProgressHook(): () -> Unit {
    requireInstanceParameters(listOf("Ljava/lang/String;", "J", "J"))
    return { addInstruction(0, "invoke-static/range {p0 .. p5}, $STORY_SEEKBAR->onProgress(Ljava/lang/Object;Ljava/lang/String;JJ)V") }
}

/**
 * Refuses a method that is static or takes other parameters than the call passes on. The range
 * call names p0 onwards in one block, so it reads exactly these.
 */
private fun MutableMethod.requireInstanceParameters(wanted: List<String>) {
    val actual = parameterTypes.map(CharSequence::toString)
    if (AccessFlags.STATIC.isSet(accessFlags) || actual != wanted) {
        throw PatchException("$PATCH: $definingClass->$name takes $actual, expected an instance method taking $wanted")
    }
}
