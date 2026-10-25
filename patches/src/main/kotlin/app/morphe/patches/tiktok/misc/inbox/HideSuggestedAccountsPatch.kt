/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 * Follows hxreborn/hxreborn-tiktok-patches (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.util.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.getFreeRegisterProvider

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/inbox/InboxControls;"

/**
 * Answers the same `hide_inbox_suggested_accounts` switch as the row hiding in
 * `Hide inbox items`, one layer earlier: the widget is never built, so nothing flashes,
 * and the Activity and New followers pages are covered as well as the Inbox tab.
 */
@Suppress("unused")
val hideSuggestedAccountsPatch = bytecodePatch(
    name = "Hide suggested accounts",
    description = "Hides TikTok's People you may like suggestions in the Inbox, on profiles, " +
        "on the Friends tab and in the feed. It uses the same switch as Hide inbox items. Starts " +
        "on, retaining Metra's existing default. Control it in Metra settings > Interface.",
) {
    category("Inbox")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideSuggestedAccounts()V",
        )

        listOf(
            ActivityRecommendUserWidgetEnableFingerprint,
            NewFollowersRecommendUserWidgetEnableFingerprint,
            NewFollowersRecommendUserSkeletonEnableFingerprint,
            InboxRecommendUserWidgetEnableFingerprint,
        ).forEach { fingerprint ->
            fingerprint.method.hideInboxWidget("shouldShowSuggestedAccounts")
        }

        collapseSuggestionCells()
    }
}

private const val CELLS_EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/inbox/SuggestedAccountCells;"
private const val CELL_PACKAGE = "/relation/usercard/impl/cell/"
private const val CELL_SUFFIX = "RecUserCell;"

/**
 * Collapses every suggestion cell at its bind, whichever list it sits in.
 *
 * <p>The inbox gates above stop three lists from being built. The profile header, the
 * Friends tab and the feed's account cards each build their own list from a different
 * component with no shared source to anchor on, but every one of them is made of cells that
 * extend the real-named `AbsRecUserCell`, and every cell's `onBindItemView` bridge is a
 * PowerCell method with a real name. Each bridge in that family is hooked, so a build that
 * adds a cell class is covered as long as it keeps the package and the suffix, and a build
 * that renames the package fails here rather than shipping a switch that does nothing.
 *
 * @return how many bind methods were hooked.
 */
internal fun BytecodePatchContext.collapseSuggestionCells(): Int {
    // Collected first, mutated after: the walk is over the immutable classes.
    val binds = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEach { classDef ->
        if (!classDef.type.contains(CELL_PACKAGE) || !classDef.type.endsWith(CELL_SUFFIX)) return@classDefForEach
        classDef.methods.forEach { method ->
            if (method.name == "onBindItemView" && method.implementation != null) binds += classDef to method
        }
    }
    if (binds.isEmpty()) {
        throw PatchException(
            "Hide suggested accounts: no suggestion cell binds under $CELL_PACKAGE; the cells moved.",
        )
    }
    binds.forEach { (classDef, method) ->
        mutableClassDefBy(classDef).findMutableMethodOf(method).collapseSuggestionCellAtBind()
    }
    return binds.size
}

/**
 * Hands the cell to the extension as TikTok's bind returns, so what the bind did to the item
 * view is what gets collapsed. The Rect cells write their own top margin inside the bind; a
 * call ahead of it would have been undone. Every return is covered, in range form so a
 * register above v15 on some later build assembles.
 */
internal fun MutableMethod.collapseSuggestionCellAtBind() {
    val implementation = implementation ?: return
    val self = implementation.registerCount - (parameters.size + 1)
    val returns = implementation.instructions.withIndex()
        .filter { (_, instruction) -> instruction.opcode == Opcode.RETURN_VOID }
        .map { it.index }
    returns.sortedDescending().forEach { index ->
        addInstruction(
            index,
            "invoke-static/range {v$self .. v$self}, " +
                "$CELLS_EXTENSION_CLASS_DESCRIPTOR->onBind(Ljava/lang/Object;)V",
        )
    }
}

/** A disabled hide switch must leave the native rollout and app-availability checks intact. */
internal fun MutableMethod.hideInboxWidget(extensionMethod: String) {
    val answerRegister = getFreeRegisterProvider(0, 1).getFreeRegister4Bit()
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->$extensionMethod()Z
            move-result v$answerRegister
            if-nez v$answerRegister, :native_widget_gate
            return v$answerRegister
        """,
        ExternalLabel("native_widget_gate", getInstruction(0)),
    )
}
