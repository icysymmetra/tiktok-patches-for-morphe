/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.follow

import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.argumentRegister
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction3rc
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

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
val followDiagnosticsPatch = bytecodePatch(
    name = "Follow diagnostics",
    description = "Tells you when TikTok quietly turned down a follow, and why, since the app " +
        "shows it as if it worked. With diagnostic logging on, the details go in the report too. " +
        "Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Interaction")
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val patchesByMethod = linkedMapOf<Method, ArrayDeque<FollowCallPatch>>()
        // Each anchor is a name test inside a walk over every class, and a name that no method
        // carries any more simply never matches. Collected and counted before anything is
        // written, so that a build which renames one fails the patch by name instead of
        // shipping it with that hook missing, and fails it with nothing written: the patcher
        // does not take a failed patch's writes back out, and a hook written before the walk
        // reached a renamed one used to stay in the APK.
        val commonFollow = mutableListOf<Method>()
        val executeCall = mutableListOf<Method>()
        val parseResponse = mutableListOf<Method>()

        classDefForEach { classDef ->
            for (method in classDef.methods) {
                val implementation = method.implementation ?: continue

                if (classDef.type == COMMON_FOLLOW_API_DESCRIPTOR && method.name == "LIZ") {
                    commonFollow += method
                    continue
                }

                if (classDef.type == CALL_SERVER_INTERCEPTOR_DESCRIPTOR && method.name == NETWORK_EXECUTE_CALL_METHOD) {
                    executeCall += method
                    continue
                }

                if (classDef.type == CALL_SERVER_INTERCEPTOR_DESCRIPTOR && method.name == NETWORK_PARSE_RESPONSE_METHOD) {
                    parseResponse += method
                    continue
                }

                implementation.instructions.forEachIndexed { index, instruction ->
                    val beforeInstructions = followRequestInstructions(instruction) ?: return@forEachIndexed

                    patchesByMethod.getOrPut(method) { ArrayDeque() }
                        .add(FollowCallPatch(index, beforeInstructions))
                }
            }
        }

        check(commonFollow.size == 1) {
            "Follow diagnostics: expected one $COMMON_FOLLOW_API_DESCRIPTOR->LIZ to hook, " +
                "found ${commonFollow.size}."
        }
        check(executeCall.size == 1) {
            "Follow diagnostics: expected one $CALL_SERVER_INTERCEPTOR_DESCRIPTOR->" +
                "$NETWORK_EXECUTE_CALL_METHOD to hook, found ${executeCall.size}."
        }
        check(parseResponse.size == 1) {
            "Follow diagnostics: expected one $CALL_SERVER_INTERCEPTOR_DESCRIPTOR->" +
                "$NETWORK_PARSE_RESPONSE_METHOD to hook, found ${parseResponse.size}."
        }
        check(patchesByMethod.isNotEmpty()) {
            "Follow diagnostics: no call site asks the follow services for a request."
        }

        // Every insertion of the three hooks is worked out, and every anchor it needs checked,
        // before the first one is written.
        fun mutable(method: Method) = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
        val commonFollowMethod = mutable(commonFollow.single())
        val executeCallMethod = mutable(executeCall.single())
        val parseResponseMethod = mutable(parseResponse.single())
        listOf(
            commonFollowMethod to planCommonFollowApi(commonFollowMethod),
            executeCallMethod to planNetworkExecuteCall(executeCallMethod),
            parseResponseMethod to planNetworkParseResponse(parseResponseMethod),
        ).forEach { (method, insertions) -> method.insertAll(insertions) }

        patchesByMethod.forEach { (method, patches) ->
            val mutableMethod = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)

            while (patches.isNotEmpty()) {
                val patch = patches.removeLast()
                mutableMethod.patchFollowCall(patch.index, patch.beforeInstructions)
            }
        }
        registerForBuildDetails("enableFollowDiagnostics")
    }
}

internal fun followRequestInstructions(instruction: Instruction): String? {
    if (instruction.opcode != Opcode.INVOKE_INTERFACE &&
        instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE
    ) return null
    val methodReference = instruction.getReference<MethodReference>() ?: return null
    // IUserService.LIZLLL and LJ both reach the CommonFollowApi hook. LJFF only observes
    // existing follow state. Instrumenting those outer calls duplicates or invents requests.
    return when (methodReference.definingClass) {
        JEDI_FOLLOW_API_DESCRIPTOR -> when (methodReference.name) {
            "followWithRetrofitPost" -> jediFollowRequestInstructions(instruction)
            else -> null
        }
        else -> null
    }
}

internal fun MutableMethod.patchFollowCall(index: Int, beforeInstructions: String) {
    val moveResult = implementation!!.instructions.getOrNull(index + 1)
        ?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction
    addInstructions(
        index + 2,
        moveResult?.let { result ->
            "invoke-static/range {v${result.registerA} .. v${result.registerA}}, " +
                "$EXTENSION_CLASS_DESCRIPTOR->logFollowStream(Ljava/lang/Object;)V"
        } ?: "nop",
    )
    addInstructions(index, beforeInstructions)
}

/**
 * Lines to add to one method and the index each goes in front of, every index read off the
 * method before anything was added to it.
 */
internal typealias Insertions = List<Pair<Int, String>>

internal fun MutableMethod.insertAll(insertions: Insertions) {
    // Highest index first. Every index was read from the untouched method, and inserting at a
    // lower one moves all of them; this held for the lancets only because the catch handler
    // happens to sit last on this build.
    insertions.sortedByDescending { it.first }.forEach { (index, instruction) ->
        addInstructionsAtControlFlowLabel(index, instruction)
    }
}

/**
 * Both network lancets have the same three anchors: the read of the request being sent, the
 * result of the call TikTok's own method makes, and the exception when it throws. Every register
 * is read off the instruction it belongs to, so the layout of the surrounding method is free to
 * move; only the anchors themselves have to be there, and a missing one fails the build.
 */
internal fun planNetworkLancet(
    method: Method,
    twinName: String,
    requestLogger: String?,
    responseLogger: String,
    throwableLogger: String,
): Insertions {
    val implementation = method.implementation
        ?: throw PatchException("Follow diagnostics: ${method.name} has no body.")
    val instructions = implementation.instructions.toList()

    val requestIndex = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.IGET_OBJECT &&
            instruction.getReference<FieldReference>()?.name == "mOriginalRequest"
    }
    if (requestIndex < 0) {
        throw PatchException("Follow diagnostics: mOriginalRequest is not read in ${method.name}.")
    }
    val requestRegister = (instructions[requestIndex] as OneRegisterInstruction).registerA

    val twinIndex = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
            instruction.getReference<MethodReference>()?.name == twinName
    }
    if (twinIndex < 0) {
        throw PatchException("Follow diagnostics: $twinName is not called in ${method.name}.")
    }
    val responseInstruction = instructions.getOrNull(twinIndex + 1)
    if (responseInstruction?.opcode != Opcode.MOVE_RESULT_OBJECT) {
        throw PatchException(
            "Follow diagnostics: $twinName in ${method.name} is followed by " +
                "${responseInstruction?.opcode?.name ?: "nothing"}, not a move-result-object, " +
                "so there is no response to read.",
        )
    }
    val responseRegister = (responseInstruction as OneRegisterInstruction).registerA

    val exceptionIndex = instructions.indexOfFirst { it.opcode == Opcode.MOVE_EXCEPTION }
    if (exceptionIndex < 0) {
        throw PatchException("Follow diagnostics: ${method.name} catches nothing to report.")
    }
    val throwableRegister = (instructions[exceptionIndex] as OneRegisterInstruction).registerA

    // Two-argument calls in the plain form, injected where the host's locals are live, so
    // nothing can be staged into a lower register. The registers come off eight bit
    // instructions and are refused here rather than at the assembler, which names nothing.
    for ((what, register) in listOf("request" to requestRegister, "response" to responseRegister, "throwable" to throwableRegister)) {
        if (register > 15) {
            throw PatchException(
                "Follow diagnostics: ${method.name} keeps its $what in v$register, past what " +
                    "the plain invoke can name.",
            )
        }
    }

    return listOfNotNull(
        (exceptionIndex + 1) to
            "invoke-static {v$requestRegister, v$throwableRegister}, $throwableLogger",
        (twinIndex + 2) to
            "invoke-static {v$requestRegister, v$responseRegister}, $responseLogger",
        requestLogger?.let {
            (requestIndex + 1) to "invoke-static/range {v$requestRegister .. v$requestRegister}, $it"
        },
    )
}

private fun planNetworkParseResponse(method: Method) = planNetworkLancet(
    method,
    twinName = "com_bytedance_retrofit2_CallServerInterceptor__parseResponse\$___twin___",
    // The parse lancet logs the request only alongside a response or a throwable: on its own it
    // says nothing the execute lancet has not already reported for the same request.
    requestLogger = null,
    responseLogger = "$EXTENSION_CLASS_DESCRIPTOR->logParsedResponse(Ljava/lang/Object;Ljava/lang/Object;)V",
    throwableLogger = "$EXTENSION_CLASS_DESCRIPTOR->logParseThrowable(Ljava/lang/Object;Ljava/lang/Throwable;)V",
)

/**
 * The ten parameters the extension's logCommonFollowRequest reads, in order. LIZ is matched by
 * name alone, so this is what says the method found is the one meant: another LIZ with a
 * different shape would otherwise be handed ten registers holding something else.
 */
private val COMMON_FOLLOW_PARAMETERS = listOf(
    "I", "I", "I", "I",
    "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;",
    "Ljava/lang/String;", "Ljava/util/Map;",
)

internal fun patchCommonFollowApi(method: MutableMethod) = method.insertAll(planCommonFollowApi(method))

private fun planCommonFollowApi(method: Method): Insertions {
    val implementation = method.implementation
        ?: throw PatchException("Follow diagnostics: CommonFollowApi.LIZ has no body.")

    val parameters = method.parameters.map { it.type }
    if (!AccessFlags.STATIC.isSet(method.accessFlags) || parameters != COMMON_FOLLOW_PARAMETERS) {
        throw PatchException(
            "Follow diagnostics: CommonFollowApi.LIZ is not the follow request this reads. " +
                "Expected a static method taking $COMMON_FOLLOW_PARAMETERS, found " +
                "${if (AccessFlags.STATIC.isSet(method.accessFlags)) "a static" else "an instance"} " +
                "method taking $parameters.",
        )
    }

    // Where the parameters actually sit. On 46.2.3 that is v5 to v14 of fifteen registers, and
    // it moves the moment TikTok's own method needs one more local.
    val firstParameter = implementation.registerCount - method.numberOfParameterRegisters
    val lastParameter = implementation.registerCount - 1

    // Every way the request can answer, not the last one written. A build that returns a cached
    // status down one path would have had that follow go unreported.
    val returnIndices = implementation.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index }
    if (returnIndices.isEmpty()) {
        throw PatchException("Follow diagnostics: CommonFollowApi.LIZ returns no object to read.")
    }
    return returnIndices.map { returnIndex ->
        val returnRegister =
            (implementation.instructions.elementAt(returnIndex) as OneRegisterInstruction).registerA
        returnIndex to "invoke-static/range {v$returnRegister .. v$returnRegister}, " +
            "$EXTENSION_CLASS_DESCRIPTOR->logFollowResult(Ljava/lang/Object;)V"
    } + (
        0 to "invoke-static/range {v$firstParameter .. v$lastParameter}, " +
            "$EXTENSION_CLASS_DESCRIPTOR->logCommonFollowRequest(IIIILjava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/util/Map;)V"
        )
}

private fun planNetworkExecuteCall(method: Method) = planNetworkLancet(
    method,
    twinName = "com_bytedance_retrofit2_CallServerInterceptor__executeCall\$___twin___",
    requestLogger = "$EXTENSION_CLASS_DESCRIPTOR->logNetworkRequest(Ljava/lang/Object;)V",
    responseLogger = "$EXTENSION_CLASS_DESCRIPTOR->logNetworkResponse(Ljava/lang/Object;Ljava/lang/Object;)V",
    throwableLogger = "$EXTENSION_CLASS_DESCRIPTOR->logNetworkThrowable(Ljava/lang/Object;Ljava/lang/Throwable;)V",
)

private fun jediFollowRequestInstructions(instruction: Instruction): String? {
    val firstArgumentRegister = instruction.argumentRegister(1) ?: return null
    val lastArgumentRegister = instruction.argumentRegister(11) ?: return null

    if (instruction is Instruction3rc) {
        return "invoke-static/range {v$firstArgumentRegister .. v$lastArgumentRegister}, " +
            "$EXTENSION_CLASS_DESCRIPTOR->logJediFollowRequest(Ljava/lang/String;Ljava/lang/String;IILjava/lang/Integer;Ljava/lang/String;Ljava/lang/Integer;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;Ljava/lang/Integer;)V"
    }

    return null
}
