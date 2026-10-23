/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/privacy/WatchHistoryRecording;"

/** Keeps its real name on every build; only its methods and their item class are renamed. */
internal const val AWEME_STATS_API = "Lcom/ss/android/ugc/aweme/feed/api/AwemeStatsApi;"

private fun Method.loads(value: String) =
    implementation?.instructions?.any { it.getReference<StringReference>()?.string == value } == true

private fun Method.isStaticVoidOnStatsApi() =
    definingClass == AWEME_STATS_API && AccessFlags.STATIC.isSet(accessFlags) &&
        returnType == "V" && parameterTypes.size == 1

/**
 * The view report for one watched video (/aweme/v1/aweme/stats/), LIZIZ on 47.0.3, 47.1.3 and
 * 47.1.4 with the item class renamed each time (09gd, 09bJ, 09bN). It either sends at once or,
 * for For You with `basic_vv_batch_size` above one, queues the item and sends a batch when the
 * queue is full. It's the only method that reads that setting, and it already returns early
 * on a path of TikTok's own, so leaving at entry is a state TikTok handles.
 */
internal fun isViewReport(method: Method): Boolean =
    method.isStaticVoidOnStatsApi() && method.parameterTypes.single().toString() != "Ljava/util/List;" &&
        method.loads("basic_vv_batch_size") && method.loads("homepage_hot")

/**
 * The batch send, LIZ(List), reached from the view report when the queue fills and from
 * AwemeStatsBatchFlushComponent when the feed pauses. It returns at once on an empty list.
 */
internal fun isViewReportFlush(method: Method): Boolean =
    method.isStaticVoidOnStatsApi() && method.parameterTypes.single().toString() == "Ljava/util/List;" &&
        method.loads("first_install_time")

private object ViewReportFingerprint : Fingerprint(
    definingClass = AWEME_STATS_API,
    returnType = "V",
    custom = { method, _ -> isViewReport(method) },
)

private object ViewReportFlushFingerprint : Fingerprint(
    definingClass = AWEME_STATS_API,
    returnType = "V",
    parameters = listOf("Ljava/util/List;"),
    custom = { method, _ -> isViewReportFlush(method) },
)

/** Leaves a view report sender before it builds or sends anything when the switch is on. */
internal fun MutableMethod.skipWhenWatchHistoryOff() = guardAtEntry(
    "Stop recording watch history",
    "invoke-static {}, $EXTENSION->shouldSkip()Z",
    "return-void",
)

/**
 * Stops the view report TikTok sends for each video you watch, which is where its Watch history
 * comes from. Both senders return before they build a request: the single report, so nothing is
 * queued, and the batch send, so a queue filled before the switch went on isn't sent either.
 * Stories keep their own guard in Ghost mode, upstream of these.
 */
@Suppress("unused")
val watchHistoryPatch = bytecodePatch(
    name = "Stop recording watch history",
    description = "Stops TikTok adding the videos you watch to your Watch history. The catch: " +
        "your views stop counting, and For You learns less about what you like. Starts off. Turn " +
        "it on in Metra settings > App behavior.",
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        // Both resolved before anything is written, so a build missing either one leaves the
        // patch unapplied rather than half applied.
        val report = ViewReportFingerprint.method
        val flush = ViewReportFlushFingerprint.method
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableWatchHistory()V",
        )
        report.skipWhenWatchHistoryOff()
        flush.skipWhenWatchHistoryOff()
    }
}
