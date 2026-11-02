/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.repost

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.follow.followDiagnosticsPatch
import app.morphe.patches.tiktok.misc.follow.insertAll
import app.morphe.patches.tiktok.misc.follow.planNetworkLancet
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.iface.Method

private const val UPVOTE_API = "Lcom/ss/android/ugc/aweme/upvote/api/UpvoteApi;"
private const val INTERCEPTOR = "Lcom/bytedance/retrofit2/CallServerInterceptor;"
private const val EXECUTE =
    "com_bytedance_retrofit2_CallServerInterceptor_com_ss_android_ugc_aweme_feed_lancet_NetworkUtilsLancet_executeCall"
private const val PARSE =
    "com_bytedance_retrofit2_CallServerInterceptor_com_ss_android_ugc_aweme_feed_lancet_NetworkUtilsLancet_parseResponse"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/repost/RepostDiagnostics;"

private val PUBLISH_PARAMETERS = listOf(
    "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/Boolean;",
    "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;",
    "Ljava/lang/Long;", "Ljava/lang/Integer;", "Ljava/lang/String;",
    "Ljava/lang/String;", "Ljava/lang/Boolean;",
)

@Suppress("unused")
val repostDiagnosticsPatch = bytecodePatch(
    name = "Repost diagnostics",
    description = "With diagnostic logging on, notes when you repost a video and what TikTok " +
        "answered, to help track down reposts that don't stick. Works as soon as you patch it in, " +
        "with no switch.",
    default = true,
) {
    category("Interaction")
    dependsOn(sharedExtensionPatch, followDiagnosticsPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val publish = mutableListOf<Method>()
        val execute = mutableListOf<Method>()
        val parse = mutableListOf<Method>()
        classDefForEach { type ->
            for (method in type.methods) {
                if (method.implementation == null) continue
                when {
                    type.type == UPVOTE_API && method.name == "publishUpvote" -> publish += method
                    type.type == INTERCEPTOR && method.name == EXECUTE -> execute += method
                    type.type == INTERCEPTOR && method.name == PARSE -> parse += method
                }
            }
        }
        if (publish.size != 1 || execute.size != 1 || parse.size != 1) {
            throw PatchException(
                "Repost diagnostics: expected one publish and two network lancets, found " +
                    "${publish.size}, ${execute.size}, ${parse.size}.",
            )
        }
        val api = publish.single()
        if (api.parameters.map { it.type } != PUBLISH_PARAMETERS) {
            throw PatchException("Repost diagnostics: UpvoteApi.publishUpvote parameters moved.")
        }
        fun mutable(method: Method) = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
        val executeMethod = mutable(execute.single())
        val parseMethod = mutable(parse.single())
        val executeHooks = planNetworkLancet(
            executeMethod,
            "com_bytedance_retrofit2_CallServerInterceptor__executeCall\$___twin___",
            "$EXTENSION->onNetworkRequest(Ljava/lang/Object;)V",
            "$EXTENSION->onNetworkResponse(Ljava/lang/Object;Ljava/lang/Object;)V",
            "$EXTENSION->onNetworkThrowable(Ljava/lang/Object;Ljava/lang/Throwable;)V",
        )
        val parseHooks = planNetworkLancet(
            parseMethod,
            "com_bytedance_retrofit2_CallServerInterceptor__parseResponse\$___twin___",
            null,
            "$EXTENSION->onParsedResponse(Ljava/lang/Object;Ljava/lang/Object;)V",
            "$EXTENSION->onParseThrowable(Ljava/lang/Object;Ljava/lang/Throwable;)V",
        )
        val publishMethod = mutable(api)
        val firstParameter = publishMethod.implementation!!.registerCount - api.numberOfParameterRegisters
        executeMethod.insertAll(executeHooks)
        parseMethod.insertAll(parseHooks)
        publishMethod.addInstructionsAtControlFlowLabel(
            0,
            "invoke-static/range {v${firstParameter + 1} .. v${firstParameter + 2}}, " +
                "$EXTENSION->onPublishRequested(Ljava/lang/String;Ljava/lang/String;)V",
        )
        registerForBuildDetails("enableRepostDiagnostics")
    }
}
