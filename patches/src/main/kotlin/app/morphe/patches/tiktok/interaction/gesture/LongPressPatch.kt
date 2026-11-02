/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.blockAuthorPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/interaction/GestureActions;"
private const val SEEK_EXTENSION = "Lapp/morphe/extension/tiktok/interaction/FeedSeek;"
private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val EDGE_SPEEDUP = "Lcom/ss/android/ugc/aweme/feed/longvideo/edgespeedup/EdgeSpeedupAssem;"
private const val FEED_PAGE_PARAMS = "Lcom/ss/android/ugc/aweme/feed/model/BaseFeedPageParams;"
internal const val DIGG_CLASS = "Lcom/ss/android/ugc/aweme/feed/assem/digg/VideoDiggAssem;"
internal const val LANDSCAPE_CELL = "Lcom/ss/android/ugc/aweme/feed/landscape/LandscapeVideoViewCell;"

/** Whether the method is the coordinate callback: a public final one taking two floats. */
private fun Method.isCoordinateCallback() =
    returnType == "V" &&
        parameterTypes.map(CharSequence::toString) == listOf("F", "F") &&
        accessFlags == (AccessFlags.PUBLIC.value or AccessFlags.FINAL.value)

/**
 * The feed's two coordinate long-press listeners, found by what they are.
 *
 * <p>They were `LX/0QPc;` and `LX/0QPd;` in this patch, which are the 46.2.3 names. On 46.7.3
 * they are `LX/0QYB;` and `LX/0QYC;`, and on 46.8.3 they are back to `LX/0QPc;` and `LX/0QPd;`
 * again, which is worse than a clean break: the literals resolve on the newest build by
 * coincidence and would have hooked whatever else those names landed on if they had not.
 *
 * <p>What holds is the shape. Both carry a `BaseFeedPageParams`, which TikTok did not rename,
 * both implement the same one-method interface, and that method takes the two floats. Exactly
 * two classes match on all three builds and they are the two the literals named. One of them
 * holds a Context and the other an int, which is the ordinary cell against the adapter's; the
 * patch hooks both the same way so it does not need to tell them apart.
 */
internal fun BytecodePatchContext.coordinateLongPressCallbacks(): List<MutableMethod> {
    val found = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEach { classDef ->
        if (classDef.fields.none { it.type == FEED_PAGE_PARAMS }) return@classDefForEach
        val callback = classDef.methods.singleOrNull { it.isCoordinateCallback() }
            ?: return@classDefForEach
        found += classDef to callback
    }
    if (found.size != 2) {
        throw PatchException(
            "Long-press controls: expected two feed coordinate listeners holding a " +
                "BaseFeedPageParams and one (float, float) callback, found ${found.size}.",
        )
    }
    val shared = found[0].first.interfaces.toSet() intersect found[1].first.interfaces.toSet()
    val declaresCallback = shared.singleOrNull()?.let { type ->
        classDefByOrNull(type)?.methods?.singleOrNull { method ->
            method.returnType == "V" &&
                method.parameterTypes.map(CharSequence::toString) == listOf("F", "F")
        }
    } != null
    if (!declaresCallback) {
        throw PatchException(
            "Long-press controls: the two feed coordinate listeners no longer share one " +
                "interface declaring the (float, float) callback; they share $shared.",
        )
    }
    return found.map { (classDef, callback) ->
        mutableClassDefBy(classDef).findMutableMethodOf(callback)
    }
}

internal fun MutableMethod.hookCoordinateLongPress() {
    check(isCoordinateCallback()) {
        "Long-press controls: unexpected native coordinate callback signature."
    }
    val registers = implementation!!.registerCount
    requireLocals("Long-press controls", 1)
    check(registers <= 16) {
        "Long-press controls: native coordinates no longer fit the gesture hook."
    }
    // 0R9T's existing timer supplies local DOWN x in p1. Native p2/y and the event body survive.
    addInstructionsWithLabels(0, """
        invoke-static { p1 }, $EXTENSION->onLongPress(F)Z
        move-result v0
        if-eqz v0, :native_coordinate_action
        return-void
    """, ExternalLabel("native_coordinate_action", getInstruction(0)))
}

/**
 * EdgeSpeedupAssem keeps its name and its eligibility check does not: it was `NU1` on 46.2.3
 * and is `J22` on 46.8.3. It is the class's only public final method taking two floats and
 * answering a boolean, and the custom clause holds it to being the only one, so a second
 * arriving is a failure to read rather than a coin toss.
 */
private object EdgeSpeedupEligibilityFingerprint : Fingerprint(
    definingClass = EDGE_SPEEDUP,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    parameters = listOf("F", "F"),
    returnType = "Z",
    custom = { _, classDef -> classDef.methods.count { it.isEdgeSpeedupEligibility() } == 1 },
)

/** Whether the method is the edge-speedup eligibility check: public final, two floats, boolean. */
private fun Method.isEdgeSpeedupEligibility() =
    returnType == "Z" &&
        parameterTypes.map(CharSequence::toString) == listOf("F", "F") &&
        accessFlags == (AccessFlags.PUBLIC.value or AccessFlags.FINAL.value)

internal fun MutableMethod.preserveConfiguredLongPressFromEdgeSpeedup() {
    check(definingClass == EDGE_SPEEDUP && isEdgeSpeedupEligibility()) {
        "Long-press controls: unexpected native edge-speedup eligibility signature."
    }
    val registers = implementation!!.registerCount
    requireLocals("Long-press controls", 1)
    check(registers <= 16) {
        "Long-press controls: edge-speedup coordinates no longer fit the gesture hook."
    }
    // Native NU1 gets local DOWN x in p1. False prevents its earlier 300 ms speedup timer.
    addInstructionsWithLabels(0, """
        invoke-static { p1 }, $EXTENSION->allowNativeEdgeSpeedup(F)Z
        move-result v0
        if-nez v0, :native_edge_speedup
        return v0
    """, ExternalLabel("native_edge_speedup", getInstruction(0)))
}

/**
 * The feed's gesture listener. Its class name is obfuscated and changes between builds, so
 * it is found by shape: the OnGestureListener whose {@code onDoubleTap} hands the event to
 * the real-named {@code handleDoubleClick(MotionEvent)}, the one VideoViewCell installs on its
 * gradual-mask view. Ordinary cells also have a separate coordinate timer hooked above. On
 * 47.1.4 the landscape cell's listener reaches handleDoubleClick too, so it's told apart by the
 * LandscapeVideoViewCell it holds rather than by which of the two the patcher meets first.
 */
internal object FeedLongPressFingerprint : Fingerprint(
    name = "onLongPress",
    parameters = listOf(MOTION_EVENT),
    returnType = "V",
    custom = { _, classDef ->
        classDef.fields.none { it.type == LANDSCAPE_CELL } && classDef.methods.any { method ->
            method.name == "onDoubleTap" &&
                method.parameterTypes == listOf(MOTION_EVENT) &&
                method.implementation?.instructions?.any { instruction ->
                    instruction.getReference<MethodReference>()?.let { reference ->
                        reference.name == "handleDoubleClick" &&
                            reference.parameterTypes == listOf(MOTION_EVENT) &&
                            reference.returnType == "V"
                    } == true
                } == true
        }
    },
)

/**
 * The heart's assem, which kept its name as the comment assem did. Like the video presses the
 * heart in the cell of the comment button a gesture picks, so the heart's view is registered the
 * way Double-tap controls registers the comment button's. The press itself goes through the
 * ability the assem implements, found by shape at the time of the press.
 */
internal object LikeViewFingerprint : Fingerprint(
    definingClass = DIGG_CLASS,
    name = "onViewCreated",
    parameters = listOf("Landroid/view/View;"),
    returnType = "V",
)

/**
 * PlayerController is one of the classes TikTok did not rename, and neither is this callback
 * on it. It runs several times a second while a video plays and carries the source id, the
 * position and the length, which is what an edge seek needs to know, and the controller it
 * runs on is the one holding the player to seek. The other {@code onPlayProgressChange} on
 * the class takes a single float and is empty, so the parameters pick the wanted one.
 *
 * "Block the author", "Hide already seen videos" and "Stay paused after a scroll" also
 * hook this method. The first two prepend like this one; the third finds its own site by
 * searching the instructions, so a prepend at zero moves it along with everything else.
 */
private object PlayerProgressFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "J", "J"),
    custom = { method, classDef ->
        method.name == "onPlayProgressChange" && classDef.endsWith("/PlayerController;")
    },
)

/**
 * Runs before TikTok's own long press handling, which is the 2x hold and the quick share
 * sheet. When the setting asks for something else the gesture is swallowed here, so those
 * two features and their switches only ever see a long press when the setting is left on
 * TikTok's own action.
 */
@Suppress("unused")
val longPressPatch = bytecodePatch(
    name = "Long-press controls",
    description = "Lets a long press on a video like it, open the comments, copy the link, save " +
        "the sound or the frame on screen, or set a sleep timer, and can give a creator's videos " +
        "For You's full long-press menu. Starts off. Pick an action in Metra settings > Interface.",
) {
    category("Interaction")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(settingsPatch, blockAuthorPatch, doubleTapPatch)

    execute {
        EdgeSpeedupEligibilityFingerprint.method.preserveConfiguredLongPressFromEdgeSpeedup()
        // A long press on Comment, Share or Favorites can play at the hold speed (upstream #87).
        // TikTok's hold check asks each side button whether a press is on it, and the extension
        // answers no for a chosen one. That button's own long press, its menu, is skipped for a
        // press the check let through, until TikTok drops the hold it meant to start.
        EdgeSpeedupEligibilityFingerprint.method.answerRailHitTests()
        HoldTouchFingerprint.method.routeHoldDrops()
        CommentMenuFingerprint.method.skipCommentMenuWhenHeld()
        FavoritesMenuFingerprint.method.skipFavoritesMenuWhenHeld()
        ShareViewCreatedFingerprint.method.routeLongClicks("setShareLongClick")
        // A creator's videos can get For You's full long-press menu instead of the small sheet (#120).
        LongPressSceneFingerprint.method.aliasProfileLongPressScene()
        coordinateLongPressCallbacks().forEach(MutableMethod::hookCoordinateLongPress)

        FeedLongPressFingerprint.method.apply {
            // v0 is scratch; the check keeps it a local rather than a parameter register.
            requireLocals("Long-press controls", 1)
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range { p1 .. p1 }, $EXTENSION->onLongPress($MOTION_EVENT)Z
                    move-result v0
                    if-eqz v0, :original
                    return-void
                """,
                ExternalLabel("original", getInstruction(0)),
            )
        }

        LikeViewFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p1 }, " +
                "$EXTENSION->registerLikeView(Ljava/lang/Object;Landroid/view/View;)V",
        )

        // p0 is the controller, p1 the source id, then the position and the length, each a
        // pair of registers. The range form is what reaches them in a method this wide.
        PlayerProgressFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p5 }, " +
                "$SEEK_EXTENSION->recordProgress(Ljava/lang/Object;Ljava/lang/String;JJ)V",
        )

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLongPress()V",
        )
    }
}
