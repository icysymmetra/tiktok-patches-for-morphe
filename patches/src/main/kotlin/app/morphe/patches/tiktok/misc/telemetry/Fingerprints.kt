/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/telemetry/Fingerprints.kt
 * Npth task selectors adapted from kveld9/kveld-morphe-patches at
 * fcb1768620b8f98a6dd31e801074589ce9a63356 (GPL-3.0):
 * https://github.com/kveld9/kveld-morphe-patches/tree/fcb1768620b8f98a6dd31e801074589ce9a63356
 */
package app.morphe.patches.tiktok.misc.telemetry

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags

private const val APP_LOG_DESCRIPTOR = "Lcom/bytedance/applog/AppLog;"
private const val APPS_FLYER_LIB_DESCRIPTOR = "Lcom/appsflyer/AppsFlyerLib;"
private const val BD_LOCATION_CONFIG_DESCRIPTOR = "Lcom/bytedance/bdlocation/client/BDLocationConfig;"
private const val FIREBASE_ANALYTICS_DESCRIPTOR = "Lcom/google/firebase/analytics/FirebaseAnalytics;"
private const val MONITOR_CRASH_DESCRIPTOR = "Lcom/bytedance/crash/MonitorCrash;"

private val publicStaticAccessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC)
private val publicFinalAccessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)

internal object AppLogOnEventFingerprint : Fingerprint(
    definingClass = APP_LOG_DESCRIPTOR,
    name = "onEvent",
    accessFlags = publicStaticAccessFlags,
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
)

internal object AppLogOnEventV3StringFingerprint : Fingerprint(
    definingClass = APP_LOG_DESCRIPTOR,
    name = "onEventV3",
    accessFlags = publicStaticAccessFlags,
    returnType = "V",
    parameters = listOf("Ljava/lang/String;"),
)

internal object AppLogOnEventV3JsonFingerprint : Fingerprint(
    definingClass = APP_LOG_DESCRIPTOR,
    name = "onEventV3",
    accessFlags = publicStaticAccessFlags,
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Lorg/json/JSONObject;"),
)

internal object AppLogOnEventV3BundleFingerprint : Fingerprint(
    definingClass = APP_LOG_DESCRIPTOR,
    name = "onEventV3",
    accessFlags = publicStaticAccessFlags,
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Landroid/os/Bundle;"),
)

internal object AppLogOnMiscEventFingerprint : Fingerprint(
    definingClass = APP_LOG_DESCRIPTOR,
    name = "onMiscEvent",
    accessFlags = publicStaticAccessFlags,
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Lorg/json/JSONObject;"),
)

internal object AppLogFlushFingerprint : Fingerprint(
    definingClass = APP_LOG_DESCRIPTOR,
    name = "flush",
    accessFlags = publicStaticAccessFlags,
    returnType = "V",
    parameters = emptyList(),
)

internal object AppLogFlushAsyncFingerprint : Fingerprint(
    definingClass = APP_LOG_DESCRIPTOR,
    name = "flushAsync",
    accessFlags = publicStaticAccessFlags,
    returnType = "V",
    parameters = emptyList(),
)

internal object AppLogOnActivityPauseFingerprint : Fingerprint(
    definingClass = APP_LOG_DESCRIPTOR,
    name = "onActivityPause",
    accessFlags = publicStaticAccessFlags,
    returnType = "V",
    parameters = emptyList(),
)

internal object InitAppsFlyerClassFingerprint : Fingerprint(
    name = "<clinit>",
    strings = listOf("XY8Lpakui8g4kBcposRgxA").map { string(it, StringComparisonType.CONTAINS) },
)

internal object InitAppsFlyerRunFingerprint : Fingerprint(
    classFingerprint = InitAppsFlyerClassFingerprint,
    name = "run",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

internal object AppsFlyerGetInstanceFingerprint : Fingerprint(
    definingClass = APPS_FLYER_LIB_DESCRIPTOR,
    name = "getInstance",
    accessFlags = publicStaticAccessFlags,
    returnType = APPS_FLYER_LIB_DESCRIPTOR,
    parameters = emptyList(),
)

internal object AppsFlyerLogEventFingerprint : Fingerprint(
    definingClass = APPS_FLYER_LIB_DESCRIPTOR,
    name = "logEvent",
    returnType = "V",
    parameters = listOf(
        "Landroid/content/Context;",
        "Ljava/lang/String;",
        "Ljava/util/Map;",
    ),
)

internal object AppsFlyerLogLocationFingerprint : Fingerprint(
    definingClass = APPS_FLYER_LIB_DESCRIPTOR,
    name = "logLocation",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;", "D", "D"),
)

internal object BDLocationSetUploadFingerprint : Fingerprint(
    definingClass = BD_LOCATION_CONFIG_DESCRIPTOR,
    name = "setUpload",
    accessFlags = publicStaticAccessFlags,
    returnType = "V",
    parameters = listOf("Z"),
)

internal object BDLocationIsUploadFingerprint : Fingerprint(
    definingClass = BD_LOCATION_CONFIG_DESCRIPTOR,
    name = "isUpload",
    accessFlags = publicStaticAccessFlags,
    returnType = "Z",
    parameters = emptyList(),
)

internal object BDLocationIsUploadGPSFingerprint : Fingerprint(
    definingClass = BD_LOCATION_CONFIG_DESCRIPTOR,
    name = "isUploadGPS",
    accessFlags = publicStaticAccessFlags,
    returnType = "Z",
    parameters = emptyList(),
)

internal object BDLocationIsUploadLocationFingerprint : Fingerprint(
    definingClass = BD_LOCATION_CONFIG_DESCRIPTOR,
    name = "isUploadLocation",
    accessFlags = publicStaticAccessFlags,
    returnType = "Z",
    parameters = emptyList(),
)

internal object FirebaseSetCurrentScreenFingerprint : Fingerprint(
    definingClass = FIREBASE_ANALYTICS_DESCRIPTOR,
    name = "setCurrentScreen",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(
        "Landroid/app/Activity;",
        "Ljava/lang/String;",
        "Ljava/lang/String;",
    ),
)

internal object MonitorCrashReportCustomErrFingerprint : Fingerprint(
    definingClass = MONITOR_CRASH_DESCRIPTOR,
    name = "reportCustomErr",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/Throwable;"),
)

internal object MonitorCrashReportEventFingerprint : Fingerprint(
    definingClass = MONITOR_CRASH_DESCRIPTOR,
    name = "reportEvent",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = listOf(
        "Ljava/lang/String;",
        "I",
        "Lorg/json/JSONObject;",
        "Lorg/json/JSONObject;",
    ),
)

internal object NpthCoreInitTaskFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/legoImp/task/NpthCoreInitTask;",
    name = "run",
    accessFlags = publicFinalAccessFlags,
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

internal object NpthSecondInitTaskFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/legoImp/task/NpthSecondInitTask;",
    name = "run",
    accessFlags = publicFinalAccessFlags,
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

/**
 * The AppLog pack send. The pack worker and the real-time sender hand it a pack and read the
 * status it returns, and it parses the server's config out of the response. The forward worker
 * and the priority uploader post on their own, through the SDK's network client, and have
 * fingerprints of their own below. On 47.0.3 it is LX/03R3;->LJFF([String], [B, config,
 * [String], I, String, Map, Z, I, pack)I. Named by the two strings only it carries together: the
 * response's magic tag and the forward header, both of which the SDK has kept across builds.
 */
internal object AppLogSendPackFingerprint : Fingerprint(
    returnType = "I",
    strings = listOf("ss_app_log", "applog_forward").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        method.parameterTypes.size == 10 && method.parameterTypes[1].toString() == "[B"
    },
)

/**
 * The AppLog forward send. The forward worker reads the events flagged for forwarding out of
 * its own table, deletes them, and posts each pack to the forward hosts straight through the
 * SDK's network client, never through the pack send above. On 47.0.3 it is
 * LX/0Aom;->LJII(I, List, JSONObject)V. Named by its own log line, which the SDK has kept
 * across builds, and by its shape.
 */
internal object AppLogForwardSendFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("trySendForward start requestId={}, url={}").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        method.parameterTypes.size == 3 &&
            method.parameterTypes[1].toString() == "Ljava/util/List;" &&
            method.parameterTypes[2].toString() == "Lorg/json/JSONObject;"
    },
)

/**
 * The AppLog priority uploader: events the SDK sends ahead of the pack queue, posted straight
 * through its network client. The native priority engine posts through the same method. Its
 * callers take a 2xx reply whose data says message success and magic_tag ss_app_log as
 * delivered and drop the events. Real names on every fixture.
 */
internal object AppLogPrioritySendFingerprint : Fingerprint(
    definingClass = "Lcom/bytedance/applog/priority/PriorityCallbackImpl;",
    name = "doHttpPost",
    returnType = "Lcom/bytedance/applog/priority/PriorityHttpResponse;",
    parameters = listOf("Ljava/lang/String;", "[B", "Lkotlin/Pair;"),
)

/**
 * The install SDK's activation check. Once a start, the SDK's active job fetches the log host's
 * app_alert_check path with the advertising id, carrier, SIM region and time zone in the query,
 * and reads the reply for the word success. This static helper is the one place that fetch goes
 * out: it takes the SDK's client, the URL and the headers and answers whether the reply said
 * success. On 47.0.3 it is LX/0fx7;->LIZ(client, String, Z, LX/07Wh, Z, HashMap)Z. Named by the
 * tag its error lines carry, which the SDK has kept across builds, and by its shape.
 */
internal object InstallActiveCheckFingerprint : Fingerprint(
    returnType = "Z",
    strings = listOf("Register#active http error = ").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        method.parameterTypes.size == 6 &&
            method.parameterTypes[1].toString() == "Ljava/lang/String;" &&
            method.parameterTypes[5].toString() == "Ljava/util/HashMap;"
    },
)
