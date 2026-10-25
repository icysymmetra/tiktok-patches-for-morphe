/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.extendsClass
import app.morphe.patcher.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val ANDROID_VIEW = "Landroid/view/View;"
private const val TOUCH_LISTENER = "Landroid/view/View\$OnTouchListener;"
private const val RUNNABLE = "Ljava/lang/Runnable;"

/**
 * The story press-and-hold timer, found by what it is rather than what it is called.
 *
 * <p>It was `LX/0Qet;` with its view `LX/0R9T;` and the view field `LL`, all three written into
 * this patch along with the exact registers its body happened to use. It is `LX/0QpH;` on 46.7.3
 * and `LX/0Qep;` on 46.8.3.
 *
 * <p>The shape does not move: a Runnable holding one field, that field a View subclass which
 * holds a field back of the Runnable's own type, built from that view, with one plain `()V`
 * method beside `run`. Two classes in the app have that shape and the other one's view is a
 * drawing surface, full of Paints and RectFs; the story view is the one that holds an
 * `OnTouchListener`, which is a platform name and cannot be renamed.
 */
internal fun BytecodePatchContext.resolveStoryLongPressTimer(): MutableMethod {
    val found = mutableListOf<Pair<ClassDef, Method>>()
    classDefForEach { classDef ->
        if (RUNNABLE !in classDef.interfaces) return@classDefForEach
        val field = classDef.fields.singleOrNull() ?: return@classDefForEach
        val view = classDefByOrNull(field.type) ?: return@classDefForEach
        if (!extendsAndroidView(view)) return@classDefForEach
        if (view.fields.none { it.type == TOUCH_LISTENER }) return@classDefForEach
        if (view.fields.none { it.type == classDef.type }) return@classDefForEach
        if (classDef.methods.none {
                it.name == "<init>" && it.parameterTypes.toList() == listOf(field.type)
            }
        ) {
            return@classDefForEach
        }
        val timer = classDef.methods.singleOrNull {
            it.name != "<init>" && it.name != "run" && it.returnType == "V" &&
                it.parameterTypes.none() && AccessFlags.STATIC.value and it.accessFlags == 0
        } ?: return@classDefForEach
        found += classDef to timer
    }
    if (found.size != 1) {
        throw PatchException(
            "Advanced downloads: expected one story press-and-hold timer, a Runnable holding the " +
                "touch view that holds it back, found ${found.size}.",
        )
    }
    val (classDef, timer) = found.single()
    return mutableClassDefBy(classDef).findMutableMethodOf(timer)
}

/** Whether the class reaches `android.view.View` through what it extends. */
private fun BytecodePatchContext.extendsAndroidView(classDef: ClassDef): Boolean =
    extendsClass(classDef.type, ANDROID_VIEW)

internal fun MutableMethod.interceptStoryLongPress() {
    check(parameterTypes.none() && returnType == "V" &&
        AccessFlags.STATIC.value and accessFlags == 0) {
        "Advanced downloads: unexpected native story timer signature."
    }
    // The view read and the read that follows it, rather than registers 1, 3 and 0. The body is
    // the same two instructions on 46.2.3, 46.7.3 and 46.8.3, and the registers were the same
    // too, which is exactly why writing them down looked safe.
    val viewRead = getInstruction<TwoRegisterInstruction>(0)
    val viewField = getInstruction<ReferenceInstruction>(0).reference as? FieldReference
    val scratchWrite = getInstruction<TwoRegisterInstruction>(1)
    check(viewRead.opcode == Opcode.IGET_OBJECT && viewField != null &&
        viewField.definingClass == definingClass && scratchWrite.opcode == Opcode.IGET_OBJECT &&
        scratchWrite.registerB == viewRead.registerA) {
        "Advanced downloads: the native story timer no longer reads its view before its monitor."
    }
    val view = viewRead.registerA
    // The register the next instruction is about to write is free to borrow for the answer.
    val scratch = scratchWrite.registerA
    check(view != scratch && view < 16 && scratch < 16) {
        "Advanced downloads: the story timer's view and scratch registers do not fit the hook."
    }
    addInstructionsWithLabels(1, """
        invoke-static { v$view }, Lapp/morphe/extension/tiktok/download/StoryDownloads;->onNativeLongPress(Landroid/view/View;)Z
        move-result v$scratch
        if-eqz v$scratch, :native_story_hold
        return-void
    """, ExternalLabel("native_story_hold", getInstruction(1)))
}
