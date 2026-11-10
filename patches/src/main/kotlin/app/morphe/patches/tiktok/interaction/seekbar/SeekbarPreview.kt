/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/seekbar/SeekbarPatch;"
private const val PATCH = "Show the progress bar thumbnail"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"

/** The seek bar's preview check and the video field its thumbnail gate reads. */
internal class PreviewCheck(val method: Method, val video: FieldReference)

private fun Method.isBooleanGetter() = returnType == "Z" && parameterTypes.isEmpty() &&
    !AccessFlags.STATIC.isSet(accessFlags)

private fun MethodReference.isBooleanGetter() = returnType == "Z" && parameterTypes.isEmpty()

/**
 * TikTok's "may the preview picture show" check on the feed seek bar (#140), in the class that
 * logs the seek bar's reasons ("can not show seekbar, state: N"). On 47.1.4 it is `06rW.LJIJI`:
 * the class's own thumbnail gate first (`LJII`, which reads `seekbar_show_thumbnail_when_drag` and
 * the experiment's scenes), then a boolean on the preview loader the class keeps in a field
 * (`0F5J.LIZ`, which wants the video's `bigThumbs` sheets with an image address on each). No other
 * boolean getter of the class calls one of its own getters and then a getter on a field's object.
 * The video is the Aweme field that gate reads.
 */
internal fun previewCheckOf(seekbar: ClassDef): PreviewCheck {
    val own = seekbar.methods.filter { it.isBooleanGetter() }
    val found = own.mapNotNull { method ->
        val instructions = method.implementation?.instructions?.toList() ?: return@mapNotNull null
        val firstCall = instructions.firstOrNull { it.getReference<MethodReference>() != null } ?: return@mapNotNull null
        val gate = firstCall.getReference<MethodReference>()!!
        if (firstCall.opcode != Opcode.INVOKE_VIRTUAL || gate.definingClass != seekbar.type ||
            !gate.isBooleanGetter() || gate.name == method.name
        ) return@mapNotNull null
        val loader = instructions.indexOfFirst {
            it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.definingClass == seekbar.type
        }
        if (loader < 0) return@mapNotNull null
        val asksLoader = instructions.drop(loader + 1).any {
            val call = it.getReference<MethodReference>()
            it.opcode == Opcode.INVOKE_VIRTUAL && call != null && call.isBooleanGetter() &&
                call.definingClass != seekbar.type
        }
        if (asksLoader) method to gate else null
    }
    if (found.size != 1) {
        throw PatchException(
            "$PATCH: expected one preview check in ${seekbar.type}, found ${found.map { it.first.name }}",
        )
    }
    val (check, gateReference) = found.single()
    val gate = own.firstOrNull { it.name == gateReference.name }
        ?: throw PatchException("$PATCH: ${seekbar.type} has no ${gateReference.name}")
    val videos = gate.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
        instruction.getReference<FieldReference>()?.takeIf {
            instruction.opcode == Opcode.IGET_OBJECT && it.definingClass == seekbar.type && it.type == AWEME
        }
    }.distinctBy { it.name }
    if (videos.size != 1) {
        throw PatchException("$PATCH: the thumbnail gate ${gate.name} reads ${videos.size} video fields, expected one")
    }
    return PreviewCheck(check, videos.single())
}

/**
 * Hands the video to [SeekbarPatch.onPreviewCheck] as the check starts, so a diagnostic export
 * says whether the video came with preview sheets. TikTok's own answer runs untouched.
 */
internal fun MutableMethod.reportPreviewCheck(video: FieldReference) {
    val registers = implementation!!.registerCount
    val self = registers - 1
    if (registers < 2) throw PatchException("$PATCH: $definingClass->$name has no local register for the video")
    if (self > 15) throw PatchException("$PATCH: $definingClass->$name keeps this in v$self, past iget's reach")
    addInstructions(
        0,
        """
            iget-object v0, p0, $video
            invoke-static {v0}, $EXTENSION->onPreviewCheck(Ljava/lang/Object;)V
        """,
    )
}
