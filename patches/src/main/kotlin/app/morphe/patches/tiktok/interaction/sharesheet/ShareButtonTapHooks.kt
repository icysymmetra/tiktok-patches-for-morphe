/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.interaction.gesture.VIDEO_SHARE_ASSEM
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val SHARE_BUTTON_TAP = "Lapp/morphe/extension/tiktok/share/ShareButtonTap;"
private const val REUSED_ASSEM = "Lcom/bytedance/assem/arch/reused/ReusedAssem;"
private const val VIDEO_ITEM_PARAMS = "Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;"
private const val CLICK_SETTER = "Landroid/view/View\$OnClickListener;"

/** The feed's Share button is its assem's own click listener: this is the press. */
internal object ShareButtonClickFingerprint : Fingerprint(
    definingClass = VIDEO_SHARE_ASSEM,
    name = "onClick",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

/** Where the assem makes itself the click listener of its views. */
internal object ShareButtonViewCreatedFingerprint : Fingerprint(
    definingClass = VIDEO_SHARE_ASSEM,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

/**
 * How the click reads the item bound to its cell: the reused assem's state field off `this`, then
 * that state's value, cast to VideoItemParams. TikTok's own share sheet is built from it.
 */
internal data class ShareItemRead(val state: FieldReference, val value: FieldReference)

internal fun Method.shareItemRead(): ShareItemRead? {
    val body = implementation ?: return null
    val instructions = body.instructions.toList()
    val self = body.registerCount - parameterTypes.size - 1
    for (cast in instructions.indices) {
        if (instructions[cast].opcode != Opcode.CHECK_CAST ||
            instructions[cast].getReference<TypeReference>()?.type != VIDEO_ITEM_PARAMS
        ) continue
        val value = instructions.getOrNull(cast - 1) ?: continue
        if (value.opcode != Opcode.IGET_OBJECT) continue
        val valueField = value.getReference<FieldReference>() ?: continue
        if (valueField.type != "Ljava/lang/Object;") continue
        val holder = (value as TwoRegisterInstruction).registerB
        val state = (cast - 2 downTo maxOf(0, cast - 5)).map { instructions[it] }.firstOrNull {
            it.opcode == Opcode.IGET_OBJECT && it is TwoRegisterInstruction &&
                it.registerA == holder && it.registerB == self &&
                it.getReference<FieldReference>()?.definingClass == REUSED_ASSEM
        } ?: continue
        return ShareItemRead(state.getReference<FieldReference>()!!, valueField)
    }
    return null
}

/** The calls in onViewCreated that set the assem as a view's click listener, by index. */
internal fun Method.clickListenerSites(): List<Int> =
    implementation?.instructions?.withIndex()?.filter { (_, instruction) ->
        instruction.opcode == Opcode.INVOKE_STATIC && instruction.getReference<MethodReference>()?.let {
            it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf(CLICK_SETTER, "Landroid/view/View;")
        } == true
    }?.map { it.index } ?: emptyList()

private fun FieldReference.smali() = "$definingClass->$name:$type"

/**
 * Copy the link with one press on Share. The press asks the extension first, with the cell's own
 * item, before TikTok's method-id push, so a press Hushfeed takes leaves TikTok's bookkeeping as
 * it was. Each view the assem listens to also gets the screen reader label.
 */
context(_: BytecodePatchContext)
internal fun hookShareButtonTap() {
    ShareButtonClickFingerprint.method.apply {
        val read = shareItemRead()
            ?: throw PatchException("Share sheet tools: the Share button no longer reads the video it shares")
        requireLocals("Share sheet tools", 1)
        // The invoke below names p1 directly, which only reaches the first 16 registers.
        if (implementation!!.registerCount > 16) {
            throw PatchException("Share sheet tools: the Share button's click has too many registers to call out from")
        }
        addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, ${read.state.smali()}
                if-eqz v0, :share_tap
                iget-object v0, v0, ${read.value.smali()}
                :share_tap
                invoke-static { p1, v0 }, $SHARE_BUTTON_TAP->onTap(Landroid/view/View;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :share_sheet
                return-void
            """,
            ExternalLabel("share_sheet", getInstruction(0)),
        )
    }
    ShareButtonViewCreatedFingerprint.method.apply {
        val sites = clickListenerSites()
        if (sites.isEmpty()) throw PatchException("Share sheet tools: the Share button no longer sets its click listener")
        sites.reversed().forEach { index ->
            val view = getInstruction<FiveRegisterInstruction>(index).registerD
            addInstruction(index + 1, "invoke-static/range { v$view .. v$view }, $SHARE_BUTTON_TAP->bind(Landroid/view/View;)V")
        }
    }
}
