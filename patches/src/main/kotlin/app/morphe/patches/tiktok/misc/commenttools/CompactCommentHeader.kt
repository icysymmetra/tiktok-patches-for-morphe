/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.util.addInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal val compactCommentHeaderComponents = mapOf(
    "Lcom/ss/android/ugc/aweme/commentv2/actionbar/CommentPageActionBarAssem;" to "bindCountBar",
    "Lcom/ss/android/ugc/aweme/commentv2/headeranchor/CommentHeaderAnchorAssem;" to "bindLegacyBanner",
    "Lcom/ss/android/ugc/aweme/commentv2/headeranchor/common/CommentCommonTopBarAssem;" to "bindCommonBanner",
)

internal fun isCompactCommentHeaderBind(method: Method): Boolean =
    method.definingClass in compactCommentHeaderComponents && method.name == "onViewCreated" &&
        method.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;") &&
        method.returnType == "V" && !AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.implementation?.instructions?.any {
            it.opcode in listOf(Opcode.INVOKE_SUPER, Opcode.INVOKE_SUPER_RANGE) &&
                it.getReference<MethodReference>()?.let { ref ->
                    ref.definingClass == "Lcom/bytedance/assem/arch/core/UIAssem;" &&
                        ref.name == "onViewCreated" && ref.returnType == "V" &&
                        ref.parameterTypes == listOf("Landroid/view/View;")
                } == true
        } == true

internal fun MutableMethod.resolveCompactCommentHeader(): CommentToolsWrite {
    if (!isCompactCommentHeaderBind(this) || implementation!!.registerCount < 2) {
        throw PatchException("Comment tools: incompatible compact header bind in $definingClass")
    }
    val target = compactCommentHeaderComponents.getValue(definingClass)
    return {
        // Entry is the one point p1 still holds the root on every compiler/register layout.
        // Range works even when the native View parameter is above v15; no local is clobbered.
        addInstruction(0, "invoke-static/range { p1 .. p1 }, " +
            "Lapp/morphe/extension/tiktok/comment/CompactCommentHeader;->$target(Landroid/view/View;)V")
    }
}
