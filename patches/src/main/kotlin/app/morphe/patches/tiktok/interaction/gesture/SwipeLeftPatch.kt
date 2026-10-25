/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.blockAuthorPatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION = "Lapp/morphe/extension/tiktok/interaction/GestureActions;"
private const val TOUCH = "(Ljava/lang/Object;Landroid/view/MotionEvent;)V"

/** The one class that declares setPagingMainValve: TikTok's feed-to-profile pager. */
private val onPagerBase: (com.android.tools.smali.dexlib2.iface.Method, com.android.tools.smali.dexlib2.iface.ClassDef) -> Boolean =
    { _, classDef -> classDef.methods.any { it.name == "setPagingMainValve" } }

/**
 * TikTok's feed-to-profile pager, the one a left swipe on a video slides to the creator's
 * profile. Its base class is the only one that declares setPagingMainValve, and every paging
 * decision asks its private getIsPageEnabled: the dispatch, the intercept and the pager's own
 * onTouchEvent, each only while the valve is set (SwipeLeftAnchorsTest). The home pager's class
 * extends this base and overrides none of the hooked methods, and a video opened from a profile,
 * search or a link sits in a bare instance of the base inflated from XML, so the hooks below
 * reach both.
 */
internal object MainPagerPageEnabledFingerprint : Fingerprint(
    name = "getIsPageEnabled",
    returnType = "Z",
    parameters = listOf(),
    custom = onPagerBase,
)

/** The base's dispatch, which sees every event first, before anything asks whether it may page. */
internal object MainPagerDispatchFingerprint : Fingerprint(
    name = "dispatchTouchEvent",
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;"),
    custom = onPagerBase,
)

/**
 * The base's intercept. It is offered each event of a gesture until the pager takes the gesture
 * or a child (a photo carousel, say) claims it. The home pager's own intercept calls it.
 */
internal object MainPagerInterceptFingerprint : Fingerprint(
    name = "onInterceptTouchEvent",
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;"),
    custom = onPagerBase,
)

/**
 * The base's onTouchEvent, which gets the rest of a gesture once the pager has taken it. With
 * paging held, the intercept still takes a clearly leftward swipe (TikTok's own path for a held
 * pager, which only records the scroll) and hands the rest here, so the intercept and this
 * together see every event of a swipe no child claimed, and nothing of one a child did.
 */
internal object MainPagerTouchFingerprint : Fingerprint(
    name = "onTouchEvent",
    returnType = "Z",
    parameters = listOf("Landroid/view/MotionEvent;"),
    custom = onPagerBase,
)

@Suppress("unused")
val swipeLeftPatch = bytecodePatch(
    name = "Swipe-left controls",
    description = "Lets a left swipe on a video open its comments or do nothing, instead of opening the creator's profile. Starts off. Pick an action in Metra settings > Interface.",
) {
    category("Interaction")
    compatibleWith(*AppCompatibilities.tiktok())
    // Double-tap controls registers the comment buttons that "Open comments" presses.
    dependsOn(settingsPatch, sharedExtensionPatch, blockAuthorPatch, doubleTapPatch)
    execute {
        // TikTok's own answer, at each return: the extension keeps a no and may turn a yes into
        // a no. Each return hands back v0, and no branch lands on one, so an instruction placed
        // before it runs on every path to it (SwipeLeftAnchorsTest holds both on the fixture).
        MainPagerPageEnabledFingerprint.method.apply {
            val returns = implementation!!.instructions.withIndex()
                .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN }
                .map { (index, instruction) ->
                    if ((instruction as OneRegisterInstruction).registerA != 0) {
                        throw PatchException("Swipe left: getIsPageEnabled returns a register other than v0 at $index.")
                    }
                    index
                }
            if (returns.isEmpty()) throw PatchException("Swipe left: getIsPageEnabled has no return to hook.")
            returns.sortedDescending().forEach { index ->
                addInstructions(index, """
                    invoke-static { p0, v0 }, $EXTENSION->pagingEnabled(Ljava/lang/Object;Z)Z
                    move-result v0
                """)
            }
        }
        MainPagerDispatchFingerprint.method.addInstruction(0,
            "invoke-static/range {p0 .. p1}, $EXTENSION->onMainPagerDispatch$TOUCH")
        MainPagerInterceptFingerprint.method.addInstruction(0,
            "invoke-static/range {p0 .. p1}, $EXTENSION->onMainPagerTouch$TOUCH")
        MainPagerTouchFingerprint.method.addInstruction(0,
            "invoke-static/range {p0 .. p1}, $EXTENSION->onMainPagerOwnTouch$TOUCH")
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSwipeLeft()V")
    }
}
