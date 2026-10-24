/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.navigation

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val TAB_ABILITY = "/TabAbilityAssem;"
private const val COPY_ON_WRITE = "Ljava/util/concurrent/CopyOnWriteArrayList;"
private const val TOP_TAB_SCENE = "scene_get_top_tab"
private const val BOTTOM_TAB_SCENE = "scene_get_bottom_tab"

/**
 * Whether the method is a tab list getter for [scene]: build the tabs, hand back the list.
 *
 * <p>`TabAbilityAssem` keeps its own name and carries five `()List` methods whose names R8
 * assigns, two of which the patch wants. They were written here as `W52` and `OA`, which is what
 * they are called on 46.2.3 and not on any build since. Three of the five do more than hand back a
 * list; the two that matter are three instructions long, and what tells those two apart is the
 * method each calls first to fill the list, one of which reports itself as `scene_get_top_tab` and
 * the other as `scene_get_bottom_tab`. Those two strings are on all three builds.
 */
private fun Method.isTabModelList(classDef: ClassDef, scene: String): Boolean {
    val instructions = implementation?.instructions?.toList() ?: return false
    if (instructions.size != 3) return false
    if (instructions[0].opcode != Opcode.INVOKE_VIRTUAL) return false
    if (instructions[1].opcode != Opcode.IGET_OBJECT) return false
    if (instructions[2].opcode != Opcode.RETURN_OBJECT) return false
    val list = instructions[1].getReference<FieldReference>() ?: return false
    if (list.definingClass != classDef.type || list.type != COPY_ON_WRITE) return false
    val ensure = instructions[0].getReference<MethodReference>() ?: return false
    if (ensure.definingClass != classDef.type) return false
    if (ensure.returnType != "V" || ensure.parameterTypes.isNotEmpty()) return false
    val filler = classDef.methods.firstOrNull {
        it.name == ensure.name && it.returnType == "V" && it.parameterTypes.none()
    } ?: return false
    return filler.implementation?.instructions?.any {
        it.getReference<StringReference>()?.string == scene
    } == true
}

/** The one getter for [scene], or nothing, so that a second appearing is a failure to read. */
private fun Method.isOnlyTabModelList(classDef: ClassDef, scene: String) =
    isTabModelList(classDef, scene) &&
        classDef.methods.count { it.isTabModelList(classDef, scene) } == 1

/**
 * The bottom tab icon's badge setters. The icon class is renamed on every build (`LX/0ru7;` on
 * 46.2.3, `LX/066P;` on 47.0.3), but its setters keep their names, and each is the one method of
 * that name and shape in the app. Every show of the red count (Inbox) or the dot (Profile) goes
 * through them; the callers that fetch the views themselves animate them and write no visibility.
 */
internal object TabCountDotVisibilityFingerprint : Fingerprint(
    name = "setCountDotVisibility",
    returnType = "V",
    parameters = listOf("I"),
)

internal object TabDotVisibilityFingerprint : Fingerprint(
    name = "setTabDotVisibility",
    returnType = "V",
    parameters = listOf("I"),
)

/**
 * The bubble TikTok floats over Inbox when it opens and whenever a count comes in, counting new
 * likes, followers, comments, mentions and who's LIVE (#170). It isn't a tab badge view: its
 * manager (`LX/0CxD;` on 47.1.4, the notice count tab badge presenter) builds it on its own, and
 * every show goes through the one method that logs "showBubble start" (`LJJIJ`), from a count
 * change, an in-app push, a shop tooltip closing or its own retry.
 */
internal object InboxNoticeBubbleFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("showBubble start").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The feed's LIVE button asks whether LIVE already has a bottom tab, and hides itself when it does.
 * The question is the one static ()Z the button carries on every build (LIZLLL on 47.0.3, LJIIJ on
 * 46.7.3), and it reads TikTok's own list of bottom tabs, a static CopyOnWriteArrayList the bottom
 * tab filter never touches.
 */
internal object LiveBottomTabCheckFingerprint : Fingerprint(
    definingClass = LIVE_ICON_GENERATOR,
    returnType = "Z",
    parameters = listOf(),
    custom = { method, _ -> method.isLiveBottomTabCheck() },
)

internal const val LIVE_ICON_GENERATOR = "Lcom/bytedance/tiktok/homepage/mainfragment/toolbar/LiveIconGenerator;"

internal fun Method.isLiveBottomTabCheck(): Boolean =
    com.android.tools.smali.dexlib2.AccessFlags.STATIC.isSet(accessFlags) &&
        com.android.tools.smali.dexlib2.AccessFlags.PUBLIC.isSet(accessFlags) &&
        returnType == "Z" && parameterTypes.isEmpty() &&
        implementation?.instructions?.any {
            it.opcode == Opcode.SGET_OBJECT && it.getReference<FieldReference>()?.type == COPY_ON_WRITE
        } == true

/** The corner button compares TikTok's LIVE placement with both top-tab modes here. */
internal object LiveTopTabModeFingerprint : Fingerprint(
    definingClass = LIVE_ICON_GENERATOR,
    name = "onLiveIconEntranceEnable",
    returnType = "V",
    strings = listOf("live_tab_single", "live_tab_double").map { string(it, StringComparisonType.CONTAINS) },
)

internal object TopTabModelListFingerprint : Fingerprint(
    definingClass = TAB_ABILITY,
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    custom = { method, classDef -> method.isOnlyTabModelList(classDef, TOP_TAB_SCENE) },
)

internal object BottomTabModelListFingerprint : Fingerprint(
    definingClass = TAB_ABILITY,
    returnType = "Ljava/util/List;",
    parameters = listOf(),
    custom = { method, classDef -> method.isOnlyTabModelList(classDef, BOTTOM_TAB_SCENE) },
)

internal const val FOR_YOU_FRAGMENT = "Lcom/ss/android/ugc/aweme/feed/ui/FeedRecommendFragment;"

/**
 * The For You feed's refresh. A tap on Home or on the For You tab while For You is showing, and a
 * pull down at the top of the feed, all come here with TikTok's trigger enum (CLICK_BOTTOM,
 * CLICK_TOP or PULL_DOWN_REFRESH), and the answer says whether a refresh started. Its name and the
 * enum's change every build (Nj, Jt, BO, vq and qN from 46.2.3 to 47.0.3); its log lines don't.
 */
internal object ForYouRefreshFingerprint : Fingerprint(
    definingClass = FOR_YOU_FRAGMENT,
    returnType = "Z",
    strings = listOf("[tryRefresh] view invalide", "[tryRefresh] presenter is null").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.parameterTypes.size == 1 },
)

/**
 * The For You fragment's refresh wrapper, which turns true into TikTok's Home-tap trigger and
 * false into its pull trigger before it calls the refresh. TikTok's own reloads come this way (the
 * one after you block the creator on screen, its observers). A tap comes from the home pager and a
 * pull from the refresh panel's listener, through an interface the fragment implements, both
 * straight to the refresh.
 */
internal object ForYouRefreshWrapperFingerprint : Fingerprint(
    definingClass = FOR_YOU_FRAGMENT,
    returnType = "Z",
    parameters = listOf("Z"),
    custom = { method, _ -> method.isForYouRefreshWrapper() },
)

internal fun Method.isForYouRefreshWrapper(): Boolean {
    val instructions = implementation?.instructions?.toList() ?: return false
    val trigger = instructions.firstOrNull {
        it.opcode == Opcode.SGET_OBJECT && it.getReference<FieldReference>()?.name == "CLICK_BOTTOM"
    }?.getReference<FieldReference>() ?: return false
    return instructions.any {
        it.opcode == Opcode.INVOKE_VIRTUAL && it.getReference<MethodReference>()?.let { call ->
            call.definingClass == definingClass && call.returnType == "Z" &&
                call.parameterTypes.map(CharSequence::toString) == listOf(trigger.type)
        } == true
    }
}

internal const val REFRESH_ABILITY = "Lcom/ss/android/ugc/feed/platform/panel/refreshpanel/IRefreshAbility;"
internal const val EVENT_BUS_EVENT = "Lcom/ss/android/ugc/governance/eventbus/IEvent;"

/**
 * How the For You refresh ends when it starts nothing: a refresh asked for while the feed is still
 * loading stops the refresh panel's spinner and posts TikTok's refresh-end event, which turns the
 * Home icon back from its refresh arrow. The getter, the event and the bus call are read off the
 * method itself, since R8 renames all three every build.
 */
internal class RefreshEnding(val panelGetter: MethodReference, val eventInit: MethodReference, val post: MethodReference)

internal fun Method.refreshEnding(): RefreshEnding? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val stop = instructions.indexOfFirst {
        it.getReference<MethodReference>()?.let { call -> call.definingClass == REFRESH_ABILITY && call.name == "setRefreshing" } == true
    }
    if (stop < 0) return null
    val getter = instructions.subList(0, stop).lastOrNull {
        it.opcode == Opcode.INVOKE_VIRTUAL && it.getReference<MethodReference>()?.let { call ->
            call.returnType == REFRESH_ABILITY && call.parameterTypes.isEmpty()
        } == true
    }?.getReference<MethodReference>() ?: return null
    val after = instructions.drop(stop + 1)
    val init = after.firstOrNull {
        it.opcode == Opcode.INVOKE_DIRECT && it.getReference<MethodReference>()?.let { call ->
            call.name == "<init>" && call.parameterTypes.isEmpty()
        } == true
    }?.getReference<MethodReference>() ?: return null
    val post = after.firstOrNull {
        it.opcode == Opcode.INVOKE_STATIC && it.getReference<MethodReference>()?.let { call ->
            call.returnType == EVENT_BUS_EVENT && call.parameterTypes.map(CharSequence::toString) == listOf(EVENT_BUS_EVENT)
        } == true
    }?.getReference<MethodReference>() ?: return null
    return RefreshEnding(getter, init, post)
}

internal const val REFRESH_PANEL = "Lcom/ss/android/ugc/feed/platform/panel/refreshpanel/RefreshPanelComponent;"
private const val FRAGMENT = "Landroidx/fragment/app/Fragment;"

/**
 * The refresh panel's pull listener. A pull down that lets go past the threshold lands here: it
 * reads the panel's fragment and asks that to refresh, then tells the feed and the fragment's other
 * refresh listeners. Stopping only the fragment's refresh left those listeners loading the feed
 * anyway (seen on the S22), so a kept pull ends here, before any of it.
 */
internal object PullRefreshListenerFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("slide_down", "pull_refresh").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.pullPanelReads() != null },
)

/** How the pull listener reaches its fragment: its panel field, the panel's context getter, the context's fragment. */
internal class PullPanelReads(val panel: FieldReference, val context: MethodReference, val fragment: FieldReference)

internal fun Method.pullPanelReads(): PullPanelReads? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val panel = instructions.firstOrNull {
        it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.let { field ->
            field.type == REFRESH_PANEL && field.definingClass == definingClass
        } == true
    }?.getReference<FieldReference>() ?: return null
    val context = instructions.firstOrNull {
        it.opcode == Opcode.INVOKE_VIRTUAL && it.getReference<MethodReference>()?.let { call ->
            call.name == "getPanelContext" && call.parameterTypes.isEmpty()
        } == true
    }?.getReference<MethodReference>() ?: return null
    val fragment = instructions.firstOrNull {
        it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.let { field ->
            field.type == FRAGMENT && field.definingClass == context.returnType
        } == true
    }?.getReference<FieldReference>() ?: return null
    return PullPanelReads(panel, context, fragment)
}

internal const val MAIN_ACTIVITY = "Lcom/ss/android/ugc/aweme/main/MainActivity;"

/**
 * The main activity's onNewIntent, which a link reaches while the app is already running. The
 * feed lock reads it to let one linked video through. Not obfuscated: both the class and the
 * lifecycle method keep their names, and the method takes the Intent in p1.
 */
internal object MainNewIntentFingerprint : Fingerprint(
    definingClass = MAIN_ACTIVITY,
    name = "onNewIntent",
    returnType = "V",
    parameters = listOf("Landroid/content/Intent;"),
)

internal const val MAIN_ACTIVITY_ASSEM = "Lcom/ss/android/ugc/aweme/main/assems/MainActivityBusinessAssem;"
internal const val PUSH_TAB_EXTRA = "com.ss.android.ugc.aweme.intent.extra.EXTRA_AWEME_PUSH_TAB"
private const val INTENT = "Landroid/content/Intent;"

/**
 * The main activity's onCreate, where a cold start works out the tab it opens on: the tab a
 * notification names ([PUSH_TAB_EXTRA]), else a saved one, else one TikTok's landing rules pick,
 * else "HOME". Every one of those paths meets at a comparison with "HOME", and the tag ends up in
 * the cold-boot tab switch together with the activity's intent.
 */
internal object ColdStartTabFingerprint : Fingerprint(
    definingClass = MAIN_ACTIVITY_ASSEM,
    name = "onCreate",
    returnType = "V",
    parameters = listOf("Landroid/os/Bundle;"),
    strings = listOf(PUSH_TAB_EXTRA).map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.coldStartTab() != null },
)

/**
 * Where the start page asks in [ColdStartTabFingerprint]'s method: right after the
 * `const-string "HOME"` every path meets at ([insertAt]), with the register holding the tag and
 * the one holding the activity. Two things say that register holds the activity there: the
 * cold-boot switch's intent comes from its getIntent(), and TikTok passes it to the splash theme
 * restore right after the comparison.
 */
internal class ColdStartTab(val insertAt: Int, val tag: Int, val activity: Int)

internal fun Method.coldStartTab(): ColdStartTab? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val switchAt = instructions.indices.firstOrNull { i ->
        i >= 3 && instructions[i].opcode == Opcode.INVOKE_VIRTUAL &&
            instructions[i].getReference<MethodReference>()?.let { call ->
                val parameters = call.parameterTypes.map(CharSequence::toString)
                call.definingClass == definingClass && call.returnType == "V" && parameters.size == 3 &&
                    parameters[0] == INTENT && parameters[2] == "Ljava/lang/String;"
            } == true &&
            instructions[i - 1].opcode == Opcode.SGET_OBJECT &&
            instructions[i - 1].getReference<FieldReference>()?.name == "COLD_BOOT"
    } ?: return null
    val switch = instructions[switchAt] as FiveRegisterInstruction
    if (switch.registerCount != 4) return null
    val intentResult = instructions[switchAt - 2]
    if (intentResult.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    if ((intentResult as OneRegisterInstruction).registerA != switch.registerD) return null
    val getIntent = instructions[switchAt - 3]
    val readsIntent = getIntent.opcode == Opcode.INVOKE_VIRTUAL && getIntent.getReference<MethodReference>()?.let {
        it.name == "getIntent" && it.returnType == INTENT && it.parameterTypes.isEmpty()
    } == true
    if (!readsIntent) return null
    val activity = (getIntent as FiveRegisterInstruction).registerC
    val tag = switch.registerF
    val join = instructions.indices.firstOrNull { i ->
        i + 4 < switchAt && instructions[i].opcode == Opcode.CONST_STRING &&
            instructions[i].getReference<StringReference>()?.string == "HOME" &&
            instructions[i + 1].opcode == Opcode.INVOKE_STATIC &&
            (instructions[i + 1] as FiveRegisterInstruction).let { compare ->
                compare.registerCount == 2 && compare.registerD == tag &&
                    compare.registerC == (instructions[i] as OneRegisterInstruction).registerA
            }
    } ?: return null
    val restore = instructions[join + 4]
    val restoresTheActivity = restore.opcode == Opcode.INVOKE_STATIC &&
        (restore as FiveRegisterInstruction).registerCount == 2 && restore.registerC == activity &&
        restore.getReference<MethodReference>()?.let {
            it.returnType == "V" && it.parameterTypes.map(CharSequence::toString).getOrNull(1) == "Z"
        } == true
    if (!restoresTheActivity) return null
    // A jump to the comparison itself would skip anything put in front of it.
    if (join + 1 in branchTargets()) return null
    // The hook hands over the saved state from p1, the method's last register, so nothing before
    // it may write that register, a wide write into the one below included.
    val savedState = implementation!!.registerCount - 1
    val overwritten = instructions.take(join + 1).any { instruction ->
        if (!instruction.opcode.setsRegister()) return@any false
        val written = (instruction as? OneRegisterInstruction)?.registerA ?: return@any false
        written == savedState || (instruction.opcode.setsWideRegister() && written + 1 == savedState)
    }
    if (overwritten) return null
    return ColdStartTab(join + 1, tag, activity)
}

/** The indexes of every instruction a branch, a switch case or an exception handler can land on. */
internal fun Method.branchTargets(): Set<Int> {
    val implementation = implementation ?: return emptySet()
    val instructions = implementation.instructions.toList()
    val addresses = IntArray(instructions.size)
    var address = 0
    instructions.forEachIndexed { index, instruction ->
        addresses[index] = address
        address += instruction.codeUnits
    }
    val indexAt = addresses.withIndex().associate { it.value to it.index }
    val targets = HashSet<Int>()
    instructions.forEachIndexed { index, instruction ->
        if (instruction !is OffsetInstruction) return@forEachIndexed
        val target = addresses[index] + instruction.codeOffset
        if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
            val payload = instructions[indexAt.getValue(target)] as SwitchPayload
            payload.switchElements.forEach { element -> indexAt[addresses[index] + element.offset]?.let(targets::add) }
        } else {
            indexAt[target]?.let(targets::add)
        }
    }
    implementation.tryBlocks.forEach { block ->
        block.exceptionHandlers.forEach { handler -> indexAt[handler.handlerCodeAddress]?.let(targets::add) }
    }
    return targets
}

internal const val A11Y_FEED_TOOL = "Lcom/ss/android/ugc/feed/platform/panel/accessibility/A11yFeedToolComponent;"

/**
 * The check TikTok's feed button row makes before it shows: its own switch for the row, stored as
 * "settings_switch_on", and an accessibility service with touch exploration running. The row
 * (play and pause, previous, next) is built into every feed page and stays hidden when this says no.
 */
internal object FeedButtonsGateFingerprint : Fingerprint(
    definingClass = A11Y_FEED_TOOL,
    returnType = "Z",
    parameters = listOf(),
    strings = listOf("settings_switch_on").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            it.getReference<MethodReference>()?.name == "isTouchExplorationEnabled"
        } == true
    },
)

/**
 * How TikTok sets one of the feed row's buttons each time the row updates: its tint for whether it
 * can act and its accessibility state. Each button in the layout is focusable in touch mode, so
 * a tap on one that doesn't hold focus only takes it. A screen reader never meets that (it acts
 * on the button directly), a finger does. Called with the button (a TuxIconView) and whether it can act.
 */
internal object FeedButtonStateFingerprint : Fingerprint(
    definingClass = A11Y_FEED_TOOL,
    returnType = "V",
    parameters = listOf("Lcom/bytedance/tux/icon/TuxIconView;", "Z"),
    custom = { method, _ ->
        method.implementation?.instructions?.any {
            it.getReference<MethodReference>()?.name == "setTintColorRes"
        } == true
    },
)

internal const val HOME_VIEW_PAGER_ASSEM = "Lcom/ss/android/ugc/aweme/main/assems/mainfragment/HomeViewPagerAssem;"
internal const val HOME_PAGE_EX_SERVICE = "Lcom/ss/android/ugc/aweme/homepage/IHomePageExService;"
internal const val HOX = "Lcom/bytedance/hox/Hox;"

/**
 * The home pager's view setup, where it picks the feed tab across the top it opens on. For a
 * signed-in account outside teen mode that is the tab TikTok's home page service names, else
 * "For You", handed to Hox's tab switch by its tag. The start page answers there.
 */
internal object FirstTopTabFingerprint : Fingerprint(
    definingClass = HOME_VIEW_PAGER_ASSEM,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
    strings = listOf("For You").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.firstTopTab() != null },
)

/** Where the home pager opens its first feed tab: the tab switch's index and the register with the tag. */
internal class FirstTopTab(val switchAt: Int, val tag: Int)

/**
 * The first tab switch in [FirstTopTabFingerprint]'s method: the call on Hox that takes a Bundle,
 * the tab's tag and a flag, a few steps after TikTok asks its home page service for a default
 * tab and moves a non-null answer into the register "For You" was loaded into. Null when any of
 * that no longer holds, so the patch stops instead of handing over the wrong register.
 */
internal fun Method.firstTopTab(): FirstTopTab? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val service = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_INTERFACE && instruction.getReference<MethodReference>()?.let {
            it.definingClass == HOME_PAGE_EX_SERVICE && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
        } == true
    }
    if (service < 0 || instructions.getOrNull(service + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) return null
    val answer = (instructions[service + 1] as OneRegisterInstruction).registerA
    val switchAt = (service + 2 until minOf(instructions.size, service + 6)).firstOrNull { index ->
        val instruction = instructions[index]
        instruction.opcode == Opcode.INVOKE_VIRTUAL && instruction.getReference<MethodReference>()?.let {
            it.definingClass == HOX && it.returnType == "V" &&
                it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/os/Bundle;", "Ljava/lang/String;", "Z")
        } == true
    } ?: return null
    val tag = (instructions[switchAt] as FiveRegisterInstruction).registerE
    val answered = instructions.subList(service + 2, switchAt).any {
        it.opcode == Opcode.MOVE_OBJECT && (it as TwoRegisterInstruction).registerA == tag && it.registerB == answer
    }
    val defaulted = instructions.subList(0, service).any {
        it.opcode == Opcode.CONST_STRING && (it as OneRegisterInstruction).registerA == tag &&
            it.getReference<StringReference>()?.string == "For You"
    }
    return if (answered && defaulted) FirstTopTab(switchAt, tag) else null
}

internal const val SHARE_PREF_CACHE = "Lcom/ss/android/ugc/aweme/app/SharePrefCache;"
internal const val TOP_TAB_PROTOCOL = "Lcom/bytedance/tiktok/homepage/mainfragment/TopTabProtocol;"

/**
 * The home pager's default page, which it moves to once the first frame is up and again when
 * its tab list is set: the tab TikTok's home page service names, Following when TikTok's own
 * "change follow tab" preference is on for a signed-in account, else the tab its strip shows,
 * found among the top tabs by tag. A sibling method reads the same preference for reports, but
 * never looks at the strip's tabs.
 */
internal object DefaultPageFingerprint : Fingerprint(
    definingClass = HOME_VIEW_PAGER_ASSEM,
    returnType = "I",
    parameters = listOf(),
    custom = { method, _ ->
        method.followTabChoice() != null && method.implementation!!.instructions.any {
            it.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == TOP_TAB_PROTOCOL && reference.name == "getTag"
            } == true
        }
    },
)

/** Where the default page has read TikTok's "change follow tab" preference: the next index and the register. */
internal class FollowTabChoice(val insertAt: Int, val register: Int)

/**
 * The read of TikTok's "change follow tab" preference in [DefaultPageFingerprint]'s method: its
 * getter on SharePrefCache, the unboxing a few steps on, and the register the answer lands in.
 * Null when the answer isn't moved into a register or a jump lands right after it.
 */
internal fun Method.followTabChoice(): FollowTabChoice? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val read = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL && instruction.getReference<MethodReference>()?.let {
            it.definingClass == SHARE_PREF_CACHE && it.name == "getIsChangeFollowTab" && it.parameterTypes.isEmpty()
        } == true
    }
    if (read < 0) return null
    val unbox = (read + 1 until minOf(instructions.size, read + 8)).firstOrNull { index ->
        instructions[index].getReference<MethodReference>()?.let {
            it.definingClass == "Ljava/lang/Boolean;" && it.name == "booleanValue"
        } == true
    } ?: return null
    val result = instructions.getOrNull(unbox + 1)
    if (result?.opcode != Opcode.MOVE_RESULT) return null
    val insertAt = unbox + 2
    if (insertAt >= instructions.size || insertAt in branchTargets()) return null
    return FollowTabChoice(insertAt, (result as OneRegisterInstruction).registerA)
}
