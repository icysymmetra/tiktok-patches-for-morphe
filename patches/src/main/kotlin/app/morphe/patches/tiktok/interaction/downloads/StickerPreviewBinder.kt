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
import app.morphe.util.extendsClass
import app.morphe.patcher.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val LINEAR_LAYOUT = "Landroid/widget/LinearLayout;"
private const val SMART_IMAGE_VIEW = "Lcom/bytedance/lighten/loader/SmartImageView;"
private const val TUX_TEXT_VIEW = "Lcom/bytedance/tux/input/TuxTextView;"
private const val URL_MODEL = "Lcom/ss/android/ugc/aweme/base/model/UrlModel;"

/** How far up a class's superclasses a base type is looked for before giving up. */
private const val MAX_SUPERCLASS_DEPTH = 20

/**
 * Whether the reference is the sticker preview bind: a payload, a boolean, a name and a map.
 *
 * <p>Written as a shape because a call site cannot see which class was resolved, and the four
 * types together are specific enough that no other call in the sticker sheet matches.
 */
internal fun MethodReference.isStickerPreviewBind() =
    returnType == "V" &&
        parameterTypes.size == 4 &&
        parameterTypes[0].toString().startsWith("L") &&
        parameterTypes[1].toString() == "Z" &&
        parameterTypes[2].toString() == "Ljava/lang/String;" &&
        parameterTypes[3].toString() == "Ljava/util/Map;"

/**
 * The row that shows a sticker in the message sheet, found by what it is built from.
 *
 * <p>It was `LX/0ULN;` with its payload `LX/0ULM;`, and the patch named both, along with two more
 * obfuscated classes it happens to call. It is `LX/0WTz;` on 46.7.3 and `LX/0m0P;` on 46.8.3.
 *
 * <p>What holds is that it is a LinearLayout holding a `SmartImageView` and a `TuxTextView`, which
 * are ByteDance's own library types and are not renamed, and that it binds from a payload class
 * carrying a `UrlModel`. Exactly one class matches on all three builds, and it is the one the
 * literal named.
 */
internal fun BytecodePatchContext.resolveStickerPreviewBind(): MutableMethod {
    val found = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEach { classDef ->
        if (!extendsLinearLayout(classDef)) return@classDefForEach
        val fieldTypes = classDef.fields.mapTo(HashSet()) { it.type }
        if (SMART_IMAGE_VIEW !in fieldTypes || TUX_TEXT_VIEW !in fieldTypes) return@classDefForEach
        classDef.methods.forEach { method ->
            if (!method.isStickerPreviewBind()) return@forEach
            // An instance method: the save button hook hands p0 and p1 to the extension as the
            // row and its payload, and the call sites read the row out of the invoke's first
            // register. A static bind of the same declared signature would put the boolean where
            // the payload belongs, apply cleanly, and fail the verifier on a phone.
            if (AccessFlags.STATIC.value and method.accessFlags != 0) return@forEach
            val payload = classDefByOrNull(method.parameterTypes[0].toString()) ?: return@forEach
            if (payload.fields.none { it.type == URL_MODEL }) return@forEach
            found += classDef to method
        }
    }
    if (found.size != 1) {
        throw PatchException(
            "Downloads: expected one sticker preview row, a LinearLayout holding a SmartImageView " +
                "and a TuxTextView that binds from a payload carrying a UrlModel, found " +
                "${found.size}.",
        )
    }
    val (classDef, bind) = found.single()
    return mutableClassDefBy(classDef).findMutableMethodOf(bind)
}

private fun BytecodePatchContext.extendsLinearLayout(classDef: ClassDef): Boolean =
    extendsClass(classDef.type, LINEAR_LAYOUT, MAX_SUPERCLASS_DEPTH)
