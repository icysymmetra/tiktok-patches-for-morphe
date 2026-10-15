package app.morphe.patches.tiktok.misc.follow

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.util.findMutableMethodOf
import app.morphe.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val USER_SERVICE_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/userservice/api/IUserService;"
private const val COMMON_FOLLOW_API_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/userservice/CommonFollowApi;"
private const val JEDI_FOLLOW_API_DESCRIPTOR = "Lcom/ss/android/ugc/aweme/userservice/jedi/model/JediFollowApi;"
private const val CALL_SERVER_INTERCEPTOR_DESCRIPTOR = "Lcom/bytedance/retrofit2/CallServerInterceptor;"
private const val NETWORK_EXECUTE_CALL_METHOD =
    "com_bytedance_retrofit2_CallServerInterceptor_com_ss_android_ugc_aweme_feed_lancet_NetworkUtilsLancet_executeCall"
private const val NETWORK_PARSE_RESPONSE_METHOD =
    "com_bytedance_retrofit2_CallServerInterceptor_com_ss_android_ugc_aweme_feed_lancet_NetworkUtilsLancet_parseResponse"
private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/follow/FollowDiagnostics;"

private data class FollowCallPatch(
    val index: Int,
    val beforeInstructions: String,
)

@Suppress("unused")
private val followDiagnosticsPatch = bytecodePatch(
    name = "Follow diagnostics",
    description = "Adds debug-only logs around TikTok follow requests to help diagnose follow actions that do not persist. Supports TikTok 47.1.4.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        val patchesByMethod = linkedMapOf<Method, ArrayDeque<FollowCallPatch>>()

        classDefForEach { classDef ->
            for (method in classDef.methods) {
                val implementation = method.implementation ?: continue

                if (classDef.type == COMMON_FOLLOW_API_DESCRIPTOR && method.name == "LIZ") {
                    val mutableMethod = mutableClassDefBy(classDef.type).findMutableMethodOf(method)
                    patchCommonFollowApi(mutableMethod)
                    continue
                }

                if (classDef.type == CALL_SERVER_INTERCEPTOR_DESCRIPTOR && method.name == NETWORK_EXECUTE_CALL_METHOD) {
                    val mutableMethod = mutableClassDefBy(classDef.type).findMutableMethodOf(method)
                    patchNetworkExecuteCall(mutableMethod)
                    continue
                }

                if (classDef.type == CALL_SERVER_INTERCEPTOR_DESCRIPTOR && method.name == NETWORK_PARSE_RESPONSE_METHOD) {
                    val mutableMethod = mutableClassDefBy(classDef.type).findMutableMethodOf(method)
                    patchNetworkParseResponse(mutableMethod)
                    continue
                }

                implementation.instructions.forEachIndexed { index, instruction ->
                    if (instruction.opcode != Opcode.INVOKE_INTERFACE &&
                        instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE
                    ) {
                        return@forEachIndexed
                    }

                    val methodReference = instruction.getReference<MethodReference>() ?: return@forEachIndexed

                    val beforeInstructions = when (methodReference.definingClass) {
                        USER_SERVICE_DESCRIPTOR -> when (methodReference.name) {
                            "LJ" -> simpleFollowRequestInstructions(instruction)
                            "LJFF" -> detailedFollowRequestInstructions(instruction)
                            else -> null
                        }

                        JEDI_FOLLOW_API_DESCRIPTOR -> when (methodReference.name) {
                            "followWithRetrofitPost" -> jediFollowRequestInstructions(instruction)
                            else -> null
                        }

                        else -> null
                    } ?: return@forEachIndexed

                    patchesByMethod.getOrPut(method) { ArrayDeque() }
                        .add(FollowCallPatch(index, beforeInstructions))
                }
            }
        }

        patchesByMethod.forEach { (method, patches) ->
            val mutableMethod = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)

            while (patches.isNotEmpty()) {
                val patch = patches.removeLast()
                val moveResult = mutableMethod.implementation!!.instructions
                    .getOrNull(patch.index + 1)
                    ?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction

                mutableMethod.addInstructions(
                    patch.index + 2,
                    moveResult?.let { result ->
                        if (patch.beforeInstructions.contains("logJediFollowRequest")) {
                            "invoke-static/range {v${result.registerA} .. v${result.registerA}}, " +
                                "$EXTENSION_CLASS_DESCRIPTOR->logFollowStream(Ljava/lang/Object;)V"
                        } else {
                            "invoke-static/range {v${result.registerA} .. v${result.registerA}}, " +
                                "$EXTENSION_CLASS_DESCRIPTOR->logFollowResult(Ljava/lang/Object;)V"
                        }
                    } ?: "nop",
                )

                mutableMethod.addInstructions(patch.index, patch.beforeInstructions)
            }
        }
    }
}

private fun patchNetworkParseResponse(method: MutableMethod) {
    val implementation = method.implementation ?: return

    val parseResponseIndex = implementation.instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
            instruction.getReference<MethodReference>()?.name ==
            "com_bytedance_retrofit2_CallServerInterceptor__parseResponse\$___twin___"
    }

    val responseIndex = if (parseResponseIndex >= 0) parseResponseIndex + 1 else -1
    val exceptionIndex = implementation.instructions.indexOfFirst { it.opcode == Opcode.MOVE_EXCEPTION }

    if (exceptionIndex >= 0) {
        method.addInstructions(
            exceptionIndex + 1,
            "invoke-static {v5, v4}, " +
                "$EXTENSION_CLASS_DESCRIPTOR->logParseThrowable(Ljava/lang/Object;Ljava/lang/Throwable;)V",
        )
    }

    if (responseIndex >= 0) {
        method.addInstructions(
            responseIndex + 1,
            "invoke-static {v5, v3}, " +
                "$EXTENSION_CLASS_DESCRIPTOR->logParsedResponse(Ljava/lang/Object;Ljava/lang/Object;)V",
        )
    }
}

private fun patchCommonFollowApi(method: MutableMethod) {
    val implementation = method.implementation ?: return
    val returnIndex = implementation.instructions.indexOfLast { it.opcode == Opcode.RETURN_OBJECT }
    if (returnIndex >= 0) {
        method.addInstructions(
            returnIndex,
            "invoke-static/range {v4 .. v4}, " +
                "$EXTENSION_CLASS_DESCRIPTOR->logFollowResult(Ljava/lang/Object;)V",
        )
    }

    method.addInstructions(
        0,
        "invoke-static/range {v5 .. v14}, " +
            "$EXTENSION_CLASS_DESCRIPTOR->logCommonFollowRequest(IIIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V",
    )
}

private fun patchNetworkExecuteCall(method: MutableMethod) {
    val implementation = method.implementation ?: return

    val requestIndex = implementation.instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.IGET_OBJECT &&
            instruction.getReference<FieldReference>()?.name == "mOriginalRequest"
    }

    val executeCallIndex = implementation.instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
            instruction.getReference<MethodReference>()?.name ==
            "com_bytedance_retrofit2_CallServerInterceptor__executeCall\$___twin___"
    }

    val responseIndex = if (executeCallIndex >= 0) executeCallIndex + 1 else -1
    val exceptionIndex = implementation.instructions.indexOfFirst { it.opcode == Opcode.MOVE_EXCEPTION }

    if (exceptionIndex >= 0) {
        method.addInstructions(
            exceptionIndex + 1,
            "invoke-static {v3, v4}, " +
                "$EXTENSION_CLASS_DESCRIPTOR->logNetworkThrowable(Ljava/lang/Object;Ljava/lang/Throwable;)V",
        )
    }

    if (responseIndex >= 0) {
        method.addInstructions(
            responseIndex + 1,
            "invoke-static {v3, v0}, " +
                "$EXTENSION_CLASS_DESCRIPTOR->logNetworkResponse(Ljava/lang/Object;Ljava/lang/Object;)V",
        )
    }

    if (requestIndex >= 0) {
        method.addInstructions(
            requestIndex + 1,
            "invoke-static/range {v3 .. v3}, " +
                "$EXTENSION_CLASS_DESCRIPTOR->logNetworkRequest(Ljava/lang/Object;)V",
        )
    }
}

private fun simpleFollowRequestInstructions(instruction: Instruction): String? {
    val actionRegister = instruction.argumentRegister(1) ?: return null
    val uidRegister = instruction.argumentRegister(2) ?: return null
    val secUidRegister = instruction.argumentRegister(3) ?: return null

    return if (instruction is Instruction3rc) {
        "invoke-static/range {v$actionRegister .. v$secUidRegister}, " +
            "$EXTENSION_CLASS_DESCRIPTOR->logSimpleFollowRequest(ILjava/lang/String;Ljava/lang/String;)V"
    } else {
        if (listOf(actionRegister, uidRegister, secUidRegister).any { it > 15 }) return null

        "invoke-static {v$actionRegister, v$uidRegister, v$secUidRegister}, " +
            "$EXTENSION_CLASS_DESCRIPTOR->logSimpleFollowRequest(ILjava/lang/String;Ljava/lang/String;)V"
    }
}

private fun detailedFollowRequestInstructions(instruction: Instruction): String? {
    val firstArgumentRegister = instruction.argumentRegister(1) ?: return null
    val lastArgumentRegister = instruction.argumentRegister(10) ?: return null

    if (instruction is Instruction3rc) {
        return "invoke-static/range {v$firstArgumentRegister .. v$lastArgumentRegister}, " +
            "$EXTENSION_CLASS_DESCRIPTOR->logDetailedFollowRequest(IIIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V"
    }

    return null
}

private fun jediFollowRequestInstructions(instruction: Instruction): String? {
    val firstArgumentRegister = instruction.argumentRegister(1) ?: return null
    val lastArgumentRegister = instruction.argumentRegister(11) ?: return null

    if (instruction is Instruction3rc) {
        return "invoke-static/range {v$firstArgumentRegister .. v$lastArgumentRegister}, " +
            "$EXTENSION_CLASS_DESCRIPTOR->logJediFollowRequest(Ljava/lang/String;Ljava/lang/String;IILjava/lang/Integer;Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;)V"
    }

    return null
}

private fun Instruction.argumentRegister(argumentIndex: Int): Int? =
    when (this) {
        is Instruction35c -> when (argumentIndex) {
            0 -> registerC
            1 -> registerD
            2 -> registerE
            3 -> registerF
            4 -> registerG
            else -> null
        }

        is Instruction3rc -> startRegister + argumentIndex
        else -> null
    }
