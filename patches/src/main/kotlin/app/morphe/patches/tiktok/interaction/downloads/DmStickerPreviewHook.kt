/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructions
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val DM_STICKER_HANDLER = "Lcom/ss/android/ugc/aweme/im/sdk/chat/ui/gif/DefaultStickerClickHandler;"
internal const val DM_STICKER_TEMPLATE = "Lcom/ss/android/ugc/aweme/im/message/template/card/StickerTemplate;"
internal const val DM_STICKER_ABILITY = "Lcom/ss/android/ugc/aweme/im/messagelist/api/ability/MessageListStickerClickAbility;"
private const val IM_STICKER_API = "Lcom/ss/android/ugc/aweme/im/sticker/api/IMStickerApi;"
private const val SAVER = "Lapp/morphe/extension/tiktok/download/StickerGallerySaver;"

internal data class DmStickerPreviewAnchor(val method: Method, val previewCall: Int, val service: MethodReference)

/** The message sticker tap that builds a unified preview and ends with its preview service call. */
internal fun dmStickerPreviewAnchor(handler: ClassDef): DmStickerPreviewAnchor {
    if (handler.type != DM_STICKER_HANDLER || DM_STICKER_ABILITY !in handler.interfaces) {
        throw PatchException("Downloads: unexpected DM sticker click owner")
    }
    val matches = handler.methods.mapNotNull { method ->
        if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V"
            || method.parameterTypes.size != 4 || method.parameterTypes[0] != "Landroid/view/View;"
            || !method.parameterTypes[1].toString().startsWith("L")
            || !method.parameterTypes[2].toString().startsWith("L")
            || method.parameterTypes[3] != DM_STICKER_TEMPLATE) return@mapNotNull null
        val instructions = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        if (instructions.none { it.getReference<StringReference>()?.string == "chat_general_sticker_click" }) {
            return@mapNotNull null
        }
        val getter = instructions.mapNotNull { it.getReference<MethodReference>() }.singleOrNull {
            it.definingClass == IM_STICKER_API && it.name == "getImStickerInputPanelService"
                && it.parameterTypes.isEmpty()
        } ?: return@mapNotNull null
        val calls = instructions.withIndex().filter { (_, instruction) ->
            val reference = instruction.getReference<MethodReference>()
            instruction.opcode in setOf(Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE)
                && reference?.definingClass == getter.returnType && reference.returnType == "V"
                && reference.parameterTypes.size == 4
        }
        val call = calls.singleOrNull() ?: return@mapNotNull null
        if (instructions.getOrNull(call.index + 1)?.opcode != Opcode.RETURN_VOID) return@mapNotNull null
        DmStickerPreviewAnchor(method, call.index, call.value.getReference<MethodReference>()!!)
    }
    return matches.singleOrNull()
        ?: throw PatchException("Downloads: expected one DM sticker preview tap, found ${matches.size}")
}

/** Never put a second preview over a future native implementation that already owns a sheet. */
internal fun isEmptyDmPreview(method: Method): Boolean {
    val instructions = method.implementation?.instructions?.toList() ?: return false
    return instructions.any { it.opcode == Opcode.RETURN_VOID } && instructions.all {
        when (it.opcode) {
            Opcode.IGET_OBJECT, Opcode.RETURN_VOID -> true
            Opcode.INVOKE_VIRTUAL -> it.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == "Ljava/lang/Object;" && reference.name == "getClass"
                    && reference.parameterTypes.isEmpty()
            } == true
            else -> false
        }
    }
}

internal fun MutableMethod.addDmStickerPreviewHook(previewCall: Int) {
    requireLocals("Downloads DM sticker preview", 2)
    // The service returns void straight into RETURN_VOID, so these locals are dead. p1 and p4
    // are the tapped view and its template; context, message IDs and send callbacks never cross.
    addInstructions(previewCall + 1, """
        move-object/from16 v0, p1
        move-object/from16 v1, p4
        invoke-static {v0, v1}, $SAVER->openDmStickerPreview(Landroid/view/View;Ljava/lang/Object;)V
    """)
}

internal fun BytecodePatchContext.hookDmStickerPreview() {
    val handler = classDefByOrNull(DM_STICKER_HANDLER)
        ?: throw PatchException("Downloads: DM sticker click handler is missing")
    val anchor = dmStickerPreviewAnchor(handler)
    val implementations = mutableListOf<Method>()
    classDefForEach { definition ->
        if (anchor.service.definingClass in definition.interfaces) {
            implementations += definition.methods.filter {
                it.name == anchor.service.name && it.parameterTypes == anchor.service.parameterTypes
                    && it.returnType == anchor.service.returnType
            }
        }
    }
    if (implementations.isEmpty() || implementations.any { !isEmptyDmPreview(it) }) {
        throw PatchException("Downloads: the DM sticker preview service is no longer empty")
    }
    mutableClassDefBy(handler).findMutableMethodOf(anchor.method).addDmStickerPreviewHook(anchor.previewCall)
}
