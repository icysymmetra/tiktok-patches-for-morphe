/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.navigation

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val TOP_TAB_LAYOUT_ABILITY =
    "Lcom/ss/android/ugc/aweme/homepage/ui/view/tab/top/TopTabLayoutAbility;"

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/navigation/NavigationTabsFilter;"
private const val TAB_BADGES_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/navigation/TabBadges;"
private const val FEED_REFRESH_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/navigation/FeedRefresh;"
private const val FEED_LOCK_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/wellbeing/FeedLock;"
private const val START_PAGE_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/navigation/StartPage;"
private const val FEED_BUTTONS_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/navigation/FeedButtons;"

/** A method reference the way smali writes it after the invoke's registers. */
private fun com.android.tools.smali.dexlib2.iface.reference.MethodReference.smali(): String =
    "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

/** A field reference the way smali writes it after the instruction's registers. */
private fun com.android.tools.smali.dexlib2.iface.reference.FieldReference.smali(): String =
    "$definingClass->$name:$type"

internal fun isTopTabLayoutConstructor(method: com.android.tools.smali.dexlib2.iface.Method): Boolean =
    method.definingClass == TOP_TAB_LAYOUT_ABILITY &&
        method.name == "<init>" &&
        method.returnType == "V" &&
        method.parameterTypes.size == 2 &&
        method.parameterTypes[0].startsWith("L") &&
        method.parameterTypes[1] == "Landroid/widget/FrameLayout;"

private object TopTabLayoutConstructorFingerprint : app.morphe.patcher.Fingerprint(
    definingClass = TOP_TAB_LAYOUT_ABILITY,
    returnType = "V",
    custom = { method, _ -> isTopTabLayoutConstructor(method) },
)

/** Each answer of the LIVE button's bottom tab check goes through [EXTENSION_CLASS_DESCRIPTOR] on its way out. */
internal fun app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.answerLiveBottomTab() {
    val returns = implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN }
        .map { it.index }
    if (returns.isEmpty()) throw PatchException("Feed tab navigation: $definingClass->$name never returns.")
    returns.asReversed().forEach { returnIndex ->
        val register = (implementation!!.instructions[returnIndex] as OneRegisterInstruction).registerA
        addInstructionsAtControlFlowLabel(
            returnIndex,
            """
                invoke-static/range {v$register .. v$register}, $EXTENSION_CLASS_DESCRIPTOR->liveHasBottomTab(Z)Z
                move-result v$register
            """,
        )
    }
}

/**
 * Answer every one of the button's LIVE top-tab comparisons from the filtered tab model, by
 * taking the mode where it is read. 47.0.3 reads it right before comparing it with the two LIVE
 * modes; 47.1.3 reads it at the top of the method and compares it twice, once for a diagnostic
 * reason list and once for the button itself.
 */
internal fun app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.answerLiveTopTabMode() {
    val read = liveTopTabModeRead()
        ?: throw PatchException("Feed tab navigation: the LIVE button's top-tab mode read moved.")
    addInstructions(
        read.index + 1,
        """
            invoke-static/range {v${read.register} .. v${read.register}}, $EXTENSION_CLASS_DESCRIPTOR->liveTopTabMode(Ljava/lang/String;)Ljava/lang/String;
            move-result-object v${read.register}
        """,
    )
}

/** The LIVE top-tab modes the corner button compares TikTok's mode with. */
internal val LIVE_TOP_TAB_MODES = setOf("live_tab_single", "live_tab_double")

/** Where the mode string lands: the move-result-object at [index], into [register]. */
internal class LiveTopTabModeRead(val index: Int, val register: Int)

/**
 * The read every comparison with a LIVE mode compares, or null when the method no longer has
 * that shape: each mode loaded and handed straight to a static boolean equality check with one
 * other register, the same register in every check, last written by the move-result-object of
 * a call returning a String, and not written again before the last check.
 */
internal fun com.android.tools.smali.dexlib2.iface.Method.liveTopTabModeRead(): LiveTopTabModeRead? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val compared = mutableSetOf<Int>()
    val modes = mutableSetOf<String>()
    var first = -1
    var last = -1
    for ((index, instruction) in instructions.withIndex()) {
        if (instruction.opcode != Opcode.CONST_STRING) continue
        val mode = instruction.getReference<StringReference>()?.string
        if (mode !in LIVE_TOP_TAB_MODES) continue
        val constant = (instruction as OneRegisterInstruction).registerA
        val check = instructions.getOrNull(index + 1) as? FiveRegisterInstruction ?: return null
        val callee = instructions[index + 1].getReference<MethodReference>() ?: return null
        if (check.opcode != Opcode.INVOKE_STATIC || check.registerCount != 2 || callee.returnType != "Z" ||
            callee.parameterTypes.size != 2
        ) {
            return null
        }
        val other = when (constant) {
            check.registerD -> check.registerC
            check.registerC -> check.registerD
            else -> return null
        }
        compared += other
        modes += mode!!
        if (first < 0) first = index
        last = index + 1
    }
    if (modes != LIVE_TOP_TAB_MODES || compared.size != 1) return null
    val register = compared.single()
    val read = (first - 1 downTo 0).firstOrNull { instructions[it].writes(register) } ?: return null
    if (read < 1 || instructions[read].opcode != Opcode.MOVE_RESULT_OBJECT ||
        instructions[read - 1].getReference<MethodReference>()?.returnType != "Ljava/lang/String;"
    ) {
        return null
    }
    if ((read + 1..last).any { instructions[it].writes(register) }) return null
    return LiveTopTabModeRead(read, register)
}

/** Whether the instruction writes [register], as itself or as the high half of a wide pair. */
internal fun com.android.tools.smali.dexlib2.iface.instruction.Instruction.writes(register: Int): Boolean {
    val target = (this as? OneRegisterInstruction)?.registerA ?: return false
    return (opcode.setsRegister() && target == register) || (opcode.setsWideRegister() && target + 1 == register)
}

@Suppress("unused")
val feedTabNavigationPatch = bytecodePatch(
    name = "Feed tab navigation",
    description = "Lets you hide feed and bottom tabs you don't use, rename the bottom tabs, " +
        "choose the tab TikTok opens on and stop For You reloading when you tap Home. Starts off, " +
        "except holding Home to open Metra settings. Find it in Metra settings > Feed navigation.",
    default = true,
) {
    category("Settings")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // The bottom tab names are found before anything is written, since a patch that fails
        // part way keeps what it wrote. On a declared build BottomTabLabelsAnchorsTest holds
        // them, so a miss there fails the patch; on any other build it leaves just the names out.
        val labelSites = try {
            bottomTabLabelSites(classDefBy(TabCountDotVisibilityFingerprint.method.definingClass)) {
                classDefByOrNull(it)
            }
        } catch (problem: PatchException) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[Feed tab navigation] Left out the bottom tab names on ${packageMetadata.versionName}: ${problem.message}")
            null
        }

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFeedNavigation()V",
        )

        // One method answering to both scenes would take both hooks and leave the other list
        // unfiltered, with nothing failing.
        if (TopTabModelListFingerprint.method.name == BottomTabModelListFingerprint.method.name) {
            throw PatchException(
                "Feed tab navigation: both tab lists resolved to " +
                    "${TopTabModelListFingerprint.method.name}.",
            )
        }

        // The feed's LIVE button hides itself while LIVE has a bottom tab, reading TikTok's own
        // list, so a LIVE tab taken off the bar here took the button with it (issue #28).
        LiveBottomTabCheckFingerprint.method.answerLiveBottomTab()
        LiveTopTabModeFingerprint.method.answerLiveTopTabMode()

        TopTabModelListFingerprint.method.let { method ->
            val returnIndices = method.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_OBJECT }
                .map { it.index }

            returnIndices.asReversed().forEach { returnIndex ->
                val register = (method.implementation!!.instructions[returnIndex] as OneRegisterInstruction).registerA
                method.addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                        invoke-static/range {v$register .. v$register}, $EXTENSION_CLASS_DESCRIPTOR->filterTopTabs(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """,
                )
            }
        }

        BottomTabModelListFingerprint.method.let { method ->
            val returnIndices = method.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_OBJECT }
                .map { it.index }

            returnIndices.asReversed().forEach { returnIndex ->
                val register = (method.implementation!!.instructions[returnIndex] as OneRegisterInstruction).registerA
                method.addInstructionsAtControlFlowLabel(
                    returnIndex,
                    """
                        invoke-static/range {v$register .. v$register}, $EXTENSION_CLASS_DESCRIPTOR->filterBottomTabs(Ljava/util/List;)Ljava/util/List;
                        move-result-object v$register
                    """,
                )
            }
        }

        TopTabLayoutConstructorFingerprint.method.let { method ->
            val returns = method.implementation!!.instructions.withIndex()
                .filter { it.value.opcode == Opcode.RETURN_VOID }
                .map { it.index }
            val returnIndex = returns.singleOrNull()
                ?: throw PatchException(
                    "Feed tab navigation: expected one TopTabLayoutAbility constructor return, " +
                        "found ${returns.size}.",
                )
            method.addInstruction(
                returnIndex,
                "invoke-static {p1}, $EXTENSION_CLASS_DESCRIPTOR->" +
                    "installLoneForYouHeaderHider(Landroid/view/View;)V",
            )
        }

        // The unread badges on the bottom tabs (the red count on Inbox, the dot on Profile):
        // every show of either passes through the tab icon's own setter, so the visibility is
        // answered there, and a repeated native update cannot bring a hidden badge back.
        val countSetter = TabCountDotVisibilityFingerprint.method
        val dotSetter = TabDotVisibilityFingerprint.method
        check(countSetter.definingClass == dotSetter.definingClass) {
            "Feed tab navigation: the badge setters sit on ${countSetter.definingClass} and " +
                "${dotSetter.definingClass}, not on one tab icon."
        }
        val icon = mutableClassDefBy(countSetter.definingClass)
        check(icon.methods.any { it.name == "getCountDotView" } && icon.methods.any { it.name == "getRedDotVIew" }) {
            "Feed tab navigation: ${icon.type} has no badge views, so it is not the tab icon."
        }
        listOf(countSetter to "countDotVisibility", dotSetter to "tabDotVisibility").forEach { (setter, hook) ->
            setter.addInstructions(
                0,
                """
                    invoke-static/range {p1 .. p1}, $TAB_BADGES_CLASS_DESCRIPTOR->$hook(I)I
                    move-result p1
                """,
            )
        }
        // The bubble over Inbox counting new likes, followers and who's LIVE, which TikTok floats
        // up when it opens (#170). It goes with the badges: its show returns before it starts.
        InboxNoticeBubbleFingerprint.method.apply {
            requireLocals("Feed tab navigation", 1)
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $TAB_BADGES_CLASS_DESCRIPTOR->hideInboxBubble()Z
                    move-result v0
                    if-eqz v0, :bubble
                    return-void
                """,
                ExternalLabel("bubble", getInstruction(0)),
            )
        }

        // The names under the bottom tab icons: the icon reports its tab's tag when it is built,
        // and its name view once the tab logic has made it. The extension holds that view to the
        // switches from then on, however each kind of tab writes its name.
        labelSites?.let { sites ->
            icon.methods.mutableCopyOf(sites.constructor).reportBottomTab(sites.tag)
            mutableClassDefBy(sites.labelSetter.definingClass).methods.mutableCopyOf(sites.labelSetter)
                .reportBottomTabLabel(sites)
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableBottomTabLabels()V",
            )
        }

        // A Home tap or a For You tab tap asks the For You fragment's refresh with its trigger. A
        // kept tap ends the way TikTok ends a refresh that arrives mid-load: the spinner stops,
        // the refresh-end event goes out (it turns the Home icon back from its refresh arrow),
        // and the answer is TikTok's own "handled".
        val ending = ForYouRefreshFingerprint.method.refreshEnding()
            ?: throw PatchException("Feed tab navigation: the For You refresh no longer ends a refresh it skips.")
        ForYouRefreshFingerprint.method.apply {
            requireLocals("Feed tab navigation", 2)
            addInstructionsWithLabels(
                0,
                """
                    invoke-static/range {p1 .. p1}, $FEED_REFRESH_CLASS_DESCRIPTOR->allowRefresh(Ljava/lang/Object;)Z
                    move-result v0
                    if-nez v0, :refresh
                    invoke-virtual {p0}, ${ending.panelGetter.smali()}
                    move-result-object v0
                    if-eqz v0, :panel_done
                    const/4 v1, 0x0
                    invoke-interface {v0, v1}, $REFRESH_ABILITY->setRefreshing(Z)V
                    :panel_done
                    new-instance v0, ${ending.eventInit.definingClass}
                    invoke-direct {v0}, ${ending.eventInit.smali()}
                    invoke-static {v0}, ${ending.post.smali()}
                    const/4 v0, 0x1
                    return v0
                """,
                ExternalLabel("refresh", getInstruction(0)),
            )
        }

        // TikTok's own reloads come to the same refresh through the fragment's wrapper with the
        // Home-tap trigger. The wrapper says so right before each call, so the tap switch lets them
        // through. A branch can land on the call itself, hence the label move.
        ForYouRefreshWrapperFingerprint.method.apply {
            val refresh = ForYouRefreshFingerprint.method
            val calls = implementation!!.instructions.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                    instruction.getReference<MethodReference>()?.let {
                        it.definingClass == refresh.definingClass && it.name == refresh.name &&
                            it.parameterTypes.map(CharSequence::toString) == refresh.parameterTypes.map(CharSequence::toString)
                    } == true
            }.map { it.index }
            if (calls.isEmpty()) throw PatchException("Feed tab navigation: the refresh wrapper no longer calls the refresh.")
            calls.asReversed().forEach { index ->
                addInstructionsAtControlFlowLabel(index, "invoke-static {}, $FEED_REFRESH_CLASS_DESCRIPTOR->refreshFromWrapper()V")
            }
        }

        // A pull down is kept at the refresh panel's pull listener, before it asks the fragment
        // to refresh and before it tells the feed and the fragment's other refresh listeners, any
        // of which loads the feed. The listener reads its fragment the same way to begin with.
        PullRefreshListenerFingerprint.method.apply {
            requireLocals("Feed tab navigation", 2)
            val reads = pullPanelReads()
                ?: throw PatchException("Feed tab navigation: the pull listener no longer reads its fragment.")
            addInstructionsWithLabels(
                0,
                """
                    iget-object v0, p0, ${reads.panel.smali()}
                    invoke-virtual {v0}, ${reads.context.smali()}
                    move-result-object v1
                    iget-object v1, v1, ${reads.fragment.smali()}
                    invoke-static {v1}, $FEED_REFRESH_CLASS_DESCRIPTOR->keepPull(Ljava/lang/Object;)Z
                    move-result v1
                    if-eqz v1, :pull
                    const/4 v1, 0x0
                    invoke-virtual {v0, v1}, $REFRESH_PANEL->setRefreshing(Z)V
                    new-instance v0, ${ending.eventInit.definingClass}
                    invoke-direct {v0}, ${ending.eventInit.smali()}
                    invoke-static {v0}, ${ending.post.smali()}
                    return-void
                """,
                ExternalLabel("pull", getInstruction(0)),
            )
        }

        // The tab a cold start opens on. TikTok's main activity works it out as it is created and
        // every path meets at one comparison with "HOME"; the start page answers there, so the
        // steps TikTok takes for a start on that tab (the splash theme put back, the handlers it
        // uses when a notification opens Friends, Inbox or Profile) run on the answer.
        ColdStartTabFingerprint.method.apply {
            val start = coldStartTab()
                ?: throw PatchException("Feed tab navigation: the main activity no longer works out its first tab where it did.")
            val savedState = implementation!!.registerCount - 1
            if (maxOf(start.activity, start.tag, savedState) > 15) {
                throw PatchException("Feed tab navigation: the first tab's registers are past v15.")
            }
            addInstructions(
                start.insertAt,
                """
                    invoke-static {v${start.activity}, v${start.tag}, p1}, $START_PAGE_CLASS_DESCRIPTOR->coldStartTag(Landroid/app/Activity;Ljava/lang/String;Landroid/os/Bundle;)Ljava/lang/String;
                    move-result-object v${start.tag}
                """,
            )
        }

        // A link that reaches the running app. The feed lock lets the one video it opens through,
        // so it reads the intent first thing: p1 is the Intent and nothing has touched it yet.
        MainNewIntentFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p1 .. p1 }, $FEED_LOCK_CLASS_DESCRIPTOR->onNewIntent(Landroid/content/Intent;)V",
        )

        // The feed tab across the top a start opens on. The home pager picks it as its view is
        // built, from TikTok's home page service or else "For You", and switches to it by its tag;
        // a jump lands on that switch when the service has no answer, so the ask goes on its label.
        FirstTopTabFingerprint.method.apply {
            val first = firstTopTab()
                ?: throw PatchException("Feed tab navigation: the home pager no longer picks its first tab where it did.")
            addInstructionsAtControlFlowLabel(
                first.switchAt,
                """
                    invoke-static/range {v${first.tag} .. v${first.tag}}, $START_PAGE_CLASS_DESCRIPTOR->firstTopTab(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v${first.tag}
                """,
            )
        }

        // Home's default page, where the pager moves once the first frame is up. TikTok takes
        // Following there when its own "change follow tab" preference is on for a signed-in
        // account; the start page answers that preference right after a start from the icon.
        DefaultPageFingerprint.method.apply {
            val choice = followTabChoice()
                ?: throw PatchException("Feed tab navigation: the home pager no longer reads its Following default where it did.")
            addInstructions(
                choice.insertAt,
                """
                    invoke-static/range {v${choice.register} .. v${choice.register}}, $START_PAGE_CLASS_DESCRIPTOR->followingFirst(Z)Z
                    move-result v${choice.register}
                """,
            )
        }

        // TikTok's feed button row (play and pause, previous, next) shows only while a screen reader
        // with touch exploration runs. Its check answers yes first when the switch is on.
        FeedButtonsGateFingerprint.method.apply {
            requireLocals("Feed tab navigation", 1)
            addInstructionsWithLabels(
                0,
                """
                    invoke-static {}, $FEED_BUTTONS_CLASS_DESCRIPTOR->showWithoutScreenReader()Z
                    move-result v0
                    if-eqz v0, :gate
                    const/4 v0, 0x1
                    return v0
                """,
                ExternalLabel("gate", getInstruction(0)),
            )
        }

        // Each button of that row takes focus on a first tap when no screen reader runs, and only a
        // second tap presses it. The row's own update hands each button over first, while it shows.
        FeedButtonStateFingerprint.method.addInstruction(
            0,
            "invoke-static {p1}, $FEED_BUTTONS_CLASS_DESCRIPTOR->buttonShown(Landroid/view/View;)V",
        )
    }
}
