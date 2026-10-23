/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Follows kveld9's TikTok patch notes for the chat title bar and the sticker banner.
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

/*
 * The chat screen's clutter, hidden one switch at a time. The title bar and the gesture
 * dispatcher keep their real names on 47.4.3. The three message list protocols with an enable
 * check do not: R8 renamed them, so each is found by the MessageListProtocolEnum constant its
 * constructor registers.
 */

private const val CHAT_TITLE_BAR_EXTENSION = "Lapp/morphe/extension/tiktok/inbox/ChatTitleBar;"
private const val TUX_ICON_VIEW = "Lcom/bytedance/tux/icon/TuxIconView;"

/** The call buttons at the right of a single chat's title bar are bound in this one method. */
internal object ChatTitleBarRightBindFingerprint : Fingerprint(
    definingClass = "/BaseSingleChatTitleBarRightAssem;",
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

internal const val MESSAGE_LIST_PROTOCOL = "Lcom/ss/android/ugc/aweme/im/messagelist/api/cell/IMMessageListProtocol;"

/**
 * Whether the class is the chat message list protocol that registers [constant], a
 * MessageListProtocolEnum name its constructor stores. R8 renames the protocol classes from build
 * to build, but the enum keeps its names.
 */
internal fun ClassDef.registersMessageListProtocol(constant: String): Boolean =
    MESSAGE_LIST_PROTOCOL in interfaces && methods.any { method ->
        method.name == "<init>" &&
            method.implementation?.instructions?.any { it.getReference<FieldReference>()?.name == constant } == true
    }

/**
 * The banner of sticker suggestions in a chat. Its enable check answers true. On 47.4.3 R8 renamed
 * the protocol class (it was PreshownStickerBannerProtocol); it is the one IMMessageListProtocol
 * whose constructor stores MessageListProtocolEnum.PRESHOWN_STICKER_BANNER_PROTOCOL.
 */
internal object ChatStickerBannerEnabledFingerprint : Fingerprint(
    name = "isEnabled",
    returnType = "Z",
    parameters = emptyList(),
    custom = { _, classDef -> classDef.registersMessageListProtocol("PRESHOWN_STICKER_BANNER_PROTOCOL") },
)

/** The suggested reply cells in a chat's message list. On 47.4.3 R8 renamed the class (IMUnifiedSuggestedReplyMsgProtocol); it registers UNIFIED_SUGGESTED_REPLY_MSG. */
internal object ChatSuggestedReplyEnabledFingerprint : Fingerprint(
    name = "isEnabled",
    returnType = "Z",
    parameters = emptyList(),
    custom = { _, classDef -> classDef.registersMessageListProtocol("UNIFIED_SUGGESTED_REPLY_MSG") },
)

/** The banner that introduces suggested replies. On 47.4.3 R8 renamed the class (SmartReplyIntroBannerProtocol); it registers SMART_REPLY_ENTRANCE_CARD. */
internal object ChatSmartReplyIntroEnabledFingerprint : Fingerprint(
    name = "isEnabled",
    returnType = "Z",
    parameters = emptyList(),
    custom = { _, classDef -> classDef.registersMessageListProtocol("SMART_REPLY_ENTRANCE_CARD") },
)

private const val CHAT_GESTURES_EXTENSION = "Lapp/morphe/extension/tiktok/inbox/ChatGestures;"

/**
 * The message cell's gesture dispatcher: an instance method of the chat's skeleton layout that
 * takes the gesture as its last argument, an enum of SINGLE_TAP, DOUBLE_TAP, LONG_PRESS and
 * SWIPE, and switches on its ordinal. DOUBLE_TAP adds the pending heart reaction (logged as
 * "double_click") and SWIPE starts a reply (and stores "key_has_swipe_for_reply").
 * ChatDeclutterAnchorsTest maps each name to its branch on every declared build.
 */
internal object ChatMessageGestureFingerprint : Fingerprint(
    definingClass = "/SkeletonLayoutAssembler;",
    returnType = "V",
    strings = listOf("double_click", "key_has_swipe_for_reply").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The dispatcher's gesture register (its last parameter), or a reason it can't be hooked: the
 * method has to be an instance method on three arguments with the gesture object last, a local
 * below the parameters for the extension's answer, and the gesture has to fit a four-bit call.
 */
internal fun chatGestureRegister(method: Method): Int {
    val code = method.implementation
        ?: throw PatchException("Hide inbox items: the chat gesture dispatcher has no code.")
    val parameters = method.parameterTypes.map(CharSequence::toString)
    if (AccessFlags.STATIC.isSet(method.accessFlags) || parameters.size != 3 || !parameters.last().startsWith("L")) {
        throw PatchException("Hide inbox items: ${method.definingClass}->${method.name} no longer takes the gesture last on an instance.")
    }
    val parameterRegisters = 1 + parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
    val gesture = code.registerCount - 1
    if (gesture > 15 || code.registerCount <= parameterRegisters) {
        throw PatchException("Hide inbox items: the chat gesture dispatcher's registers don't fit the check.")
    }
    return gesture
}

/**
 * Returns from the dispatcher before it acts on a gesture the extension says to skip. v0 is a
 * local, written here before the original code ever reads it.
 */
internal fun MutableMethod.skipChatGestures(gesture: Int) {
    addInstructionsWithLabels(
        0,
        """
            invoke-static { v$gesture }, $CHAT_GESTURES_EXTENSION->skip(Ljava/lang/Enum;)Z
            move-result v0
            if-eqz v0, :dispatch
            return-void
        """,
        ExternalLabel("dispatch", getInstruction(0)),
    )
}

/**
 * Where a call button is stored: the `iput-object` of a TuxIconView into a field of the
 * title bar's own class. The field names are obfuscated and differ between builds, so the
 * type is what identifies them. The chat details button next to them is never stored in a
 * field, which is what keeps it out of this.
 */
internal fun MutableMethod.callButtonStores(): List<Int> {
    val code = implementation ?: return emptyList()
    return code.instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.IPUT_OBJECT &&
            instruction.getReference<FieldReference>()?.let {
                it.type == TUX_ICON_VIEW && it.definingClass == definingClass
            } == true
    }.map { it.index }
}

/**
 * Hands each call button to the extension as it is stored. Exactly two stores are expected,
 * and anything else stops the patch rather than hiding a guess.
 */
internal fun MutableMethod.hideCallButtonsAtBind() {
    val stores = callButtonStores()
    if (stores.size != EXPECTED_CALL_BUTTONS) {
        throw PatchException(
            "Hide inbox items: the chat title bar stores ${stores.size} icon views in " +
                "$definingClass->$name, not $EXPECTED_CALL_BUTTONS; the call buttons moved.",
        )
    }
    // Later stores first, so each index is still where it was when the earlier ones go in.
    stores.sortedDescending().forEach { index ->
        val source = (implementation!!.instructions.elementAt(index) as OneRegisterInstruction).registerA
        addInstruction(
            index,
            "invoke-static/range {v$source .. v$source}, " +
                "$CHAT_TITLE_BAR_EXTENSION->hideCallButton(Landroid/view/View;)V",
        )
    }
}

internal const val EXPECTED_CALL_BUTTONS = 2
