/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/privacy/NetworkRequests;"

/**
 * One attempt of one call through TikTok's Retrofit fork. `execute` and the runnable `enqueue`
 * posts both reach it, and both build the call's request (`originalRequest`) first
 * (NetworkRequestAnchorsTest). The class and method keep their real names.
 */
internal object ApiCallChainFingerprint : Fingerprint(
    definingClass = "Lcom/bytedance/retrofit2/SsHttpCall;",
    name = "getResponseWithInterceptorChain",
    parameters = listOf(),
)

@Suppress("unused")
val networkRequestReportPatch = bytecodePatch(
    name = "Network request report",
    description = "Counts the requests TikTok sends to its servers and adds the totals to the " +
        "diagnostic report. It changes nothing in TikTok, but it has no switch and most people " +
        "don't need it.",
    default = false,
) {
    category("Privacy")
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        ApiCallChainFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $EXTENSION_CLASS_DESCRIPTOR->onCall(Ljava/lang/Object;)V",
        )
        registerForBuildDetails("enableNetworkRequestReport")
    }
}
