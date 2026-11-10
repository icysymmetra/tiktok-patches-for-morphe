/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 */
package app.morphe.patches.tiktok.misc.comment

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.translation.CommentListLoadedFingerprint
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructionsWithLabels
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val RESPONSE = "Lcom/ss/android/ugc/aweme/comment/model/CommentItemList;"
private const val STRING = "Ljava/lang/String;"
private const val UNIT = "Lkotlin/Unit;"
private const val GUARD = "Lapp/morphe/extension/tiktok/comment/CommentResponseGuard;"
private const val PATCH = "Comment response ownership"

/**
 * The native method compares CommentContextSource.aweme().aid with the request's string in
 * its dislike-filter branch. Follow that comparison's copied parameter, not a fixed p4 or an
 * obfuscated field name. Other response branches do not make that ownership check.
 */
private fun MutableMethod.requestedVideoParameter(): Int {
    check(!AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" && parameterTypes.none { it == "J" || it == "D" } &&
        parameterTypes.firstOrNull() == RESPONSE) { "$PATCH: unexpected response method shape." }
    val instructions = implementation!!.instructions.toList()
    val locals = implementation!!.registerCount - numberOfParameterRegisters
    val matches = instructions.indices.mapNotNull { index ->
        val equality = instructions[index].getReference<MethodReference>() ?: return@mapNotNull null
        if (equality.definingClass != "Lkotlin/jvm/internal/Intrinsics;" ||
            equality.returnType != "Z" || equality.parameterTypes != listOf("Ljava/lang/Object;", "Ljava/lang/Object;")) {
            return@mapNotNull null
        }
        val call = instructions[index] as? FiveRegisterInstruction ?: return@mapNotNull null
        val copy = instructions.getOrNull(index - 1) as? TwoRegisterInstruction ?: return@mapNotNull null
        if (copy.opcode !in setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)) return@mapNotNull null
        val result = instructions.getOrNull(index - 2) as? OneRegisterInstruction ?: return@mapNotNull null
        val getter = instructions.getOrNull(index - 3)?.getReference<MethodReference>() ?: return@mapNotNull null
        if (result.opcode != Opcode.MOVE_RESULT_OBJECT || getter.definingClass != AWEME ||
            getter.name != "getAid" || getter.parameterTypes.isNotEmpty() || getter.returnType != STRING ||
            setOf(call.registerC, call.registerD) != setOf(result.registerA, copy.registerA)) return@mapNotNull null
        val parameter = copy.registerB - locals
        if (parameter <= 0 || parameter > parameterTypes.size || parameterTypes[parameter - 1] != STRING) return@mapNotNull null
        parameter
    }.distinct()
    return matches.singleOrNull() ?: throw PatchException("$PATCH: expected one native request-video comparison, found ${matches.size}.")
}

private fun MutableMethod.guardResponse(loadOwners: String, returnValue: String) {
    requireLocals(PATCH, 2)
    addInstructionsWithLabels(0, """
        $loadOwners
        invoke-static {v0, v1}, $GUARD->shouldDiscard($AWEME$STRING)Z
        move-result v0
        if-eqz v0, :metra_current_comment_response
        $returnValue
    """, ExternalLabel("metra_current_comment_response", getInstruction(0)))
}

/** Shared by the patches that enable or modify this native comment processor. */
internal val commentResponseOwnershipPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)
    execute {
        val response = CommentListLoadedFingerprint.method
        val parameter = response.requestedVideoParameter()
        val body = response.implementation!!.instructions.toList()
        val aweme = body.filter { it.opcode == Opcode.IGET_OBJECT }
            .mapNotNull { it.getReference<FieldReference>() }
            .filter { it.definingClass == response.definingClass && it.type == AWEME }
            .distinctBy { it.toString() }.singleOrNull()
            ?: throw PatchException("$PATCH: response processor has no unique current-video field.")

        // The processor can defer publication until the pane becomes ready. That closure keeps
        // both this processor and its request aid, so it must check again when actually invoked.
        val publisherType = body.mapNotNull { it.getReference<MethodReference>() }.filter {
            it.name == "<init>" && it.parameterTypes.size == 7 &&
                it.parameterTypes.take(4) == listOf(response.definingClass, "Z", "Ljava/util/List;", RESPONSE) &&
                it.parameterTypes.last() == STRING
        }.map { it.definingClass }.distinct().singleOrNull()
            ?: throw PatchException("$PATCH: no unique captured response publisher.")
        val publisherClass = classDefByOrNull(publisherType)
            ?: throw PatchException("$PATCH: response publisher class is absent.")
        val fields = publisherClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
        val processor = fields.singleOrNull { it.type == response.definingClass }
            ?: throw PatchException("$PATCH: publisher has no unique processor field.")
        val requested = fields.singleOrNull { it.type == STRING }
            ?: throw PatchException("$PATCH: publisher has no unique captured request-video field.")
        val publish = publisherClass.methods.singleOrNull {
            it.name == "invoke" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/Object;"
        } ?: throw PatchException("$PATCH: publisher has no unique zero-argument invoke.")
        val unit = publish.implementation!!.instructions.mapNotNull { it.getReference<FieldReference>() }
            .filter { it.definingClass == UNIT && it.type == UNIT }.distinctBy { it.toString() }.singleOrNull()
            ?: throw PatchException("$PATCH: publisher does not return Kotlin Unit.")
        val responseClass = classDefByOrNull(response.definingClass)
            ?: throw PatchException("$PATCH: response processor class is absent.")
        // The error callback carries the same captured request id. Otherwise an obsolete error
        // could finish the newer video's loading state even though old successes are rejected.
        val failure = responseClass.methods.singleOrNull {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.size == 5 &&
                it.parameterTypes.take(2) == listOf("Ljava/lang/Throwable;", "Z") &&
                it.parameterTypes[2].startsWith("L") && it.parameterTypes.takeLast(2) == listOf(STRING, "I")
        } ?: throw PatchException("$PATCH: no unique failure callback with a captured video id.")
        val failureParameter = failure.parameterTypes.indexOf(STRING) + 1
        val mutableFailure = mutableClassDefBy(response.definingClass).findMutableMethodOf(failure)
        // Resolve and validate every boundary before writing any method.
        response.requireLocals(PATCH, 2)
        val mutablePublish = mutableClassDefBy(publisherType).findMutableMethodOf(publish)
        mutablePublish.requireLocals(PATCH, 2)
        mutableFailure.requireLocals(PATCH, 2)
        response.guardResponse("""
            move-object/from16 v0, p0
            iget-object v0, v0, $aweme
            move-object/from16 v1, p$parameter
        """.trimIndent(), "return-void")
        mutablePublish.guardResponse("""
            move-object/from16 v0, p0
            iget-object v1, v0, $requested
            iget-object v0, v0, $processor
            iget-object v0, v0, $aweme
        """.trimIndent(), "sget-object v0, $unit\nreturn-object v0")
        mutableFailure.guardResponse("""
            move-object/from16 v0, p0
            iget-object v0, v0, $aweme
            move-object/from16 v1, p$failureParameter
        """.trimIndent(), "return-void")
    }
}
