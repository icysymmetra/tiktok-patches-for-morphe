/*
 * Selectors adapted from kveld9/kveld-morphe-patches at
 * fcb1768620b8f98a6dd31e801074589ce9a63356 (GPL-3.0).
 * https://github.com/kveld9/kveld-morphe-patches/tree/fcb1768620b8f98a6dd31e801074589ce9a63356
 */
package app.morphe.patches.tiktok.misc.optimizer

import app.morphe.patcher.Fingerprint
import com.android.tools.smali.dexlib2.AccessFlags

internal const val SPLASH_SERVICE_DESCRIPTOR =
    "Lcom/bytedance/ies/ugc/aweme/commercialize/splash/core/SplashAdServiceImpl;"
internal const val SPLASH_SETTING_DESCRIPTOR =
    "Lcom/bytedance/ies/ugc/aweme/commercialize/splash/core/SplashSettingServiceImpl;"
internal const val REALTIME_SPLASH_DESCRIPTOR =
    "Lcom/bytedance/ies/ugc/aweme/commercialize/splash/realtimesplash/RealTimeSplashManagerImpl;"
internal const val LIVE_WIDGET_DESCRIPTOR = "Lcom/bytedance/ies/sdk/widgets/LiveWidget;"
internal const val FRESCO_FRAME_CACHE_DESCRIPTOR =
    "Lcom/facebook/fresco/animation/bitmap/cache/FrescoFrameCache;"

private val publicFinal = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL)

internal object SplashPreloadTaskFingerprint : Fingerprint(
    definingClass = "Lcom/bytedance/ies/ugc/aweme/commercialize/splash/SplashAdManagerPreloadTask;",
    name = "run",
    accessFlags = publicFinal,
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

/** Downloads the next TopView takeover ad's assets at startup. */
internal object TopViewPreloadTaskFingerprint : Fingerprint(
    definingClass = "Lcom/bytedance/ies/ugc/aweme/commercialize/splash/topview/TopViewPreloadTask;",
    name = "run",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

/** Starts the real-time splash manager, which asks the ad server for a splash at launch. */
internal object RealTimeSplashTaskFingerprint : Fingerprint(
    definingClass = "Lcom/bytedance/ies/ugc/aweme/commercialize/splash/topview/RealTimeSplashTask;",
    name = "run",
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

internal object SplashPreloadEntryFingerprint : Fingerprint(
    definingClass = "Lcom/bytedance/ies/ugc/aweme/commercialize/splash/SplashAdManagerPreloadTaskEntry;",
    name = "run",
    accessFlags = publicFinal,
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

internal object BufferPreloadGateFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/video/preload/model/PreloadStrategyConfig;",
    name = "isEnableBufferPreload",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "Z",
    parameters = emptyList(),
)

internal object InitPushTaskFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/legoImp/task/InitPushTask;",
    name = "run",
    accessFlags = publicFinal,
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

internal object LiveGiftInitViewFingerprint : Fingerprint(
    definingClass = "Lcom/bytedance/android/livesdk/gift/base/platform/business/effect/LiveGiftEffectWidget;",
    name = "initView",
    accessFlags = listOf(AccessFlags.PRIVATE, AccessFlags.FINAL),
    returnType = "V",
    parameters = emptyList(),
)

internal object LiveGiftOnCreateFingerprint : Fingerprint(
    definingClass = "Lcom/bytedance/android/livesdk/gift/base/platform/business/effect/LiveGiftEffectWidget;",
    name = "onCreate",
    accessFlags = listOf(AccessFlags.PUBLIC),
    returnType = "V",
    parameters = emptyList(),
)

internal object UpdateBackgroundTaskFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/legoImp/task/CheckUpdateChangeDeviceIDTaskHolder\$Background;",
    name = "run",
    accessFlags = publicFinal,
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

internal object UpdateBootFinishedTaskFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/legoImp/task/CheckUpdateChangeDeviceIDTaskHolder\$BootFinish;",
    name = "run",
    accessFlags = publicFinal,
    returnType = "V",
    parameters = listOf("Landroid/content/Context;"),
)

/** Fresco's animated drawable factory, which picks the frame cache by caching strategy. */
internal object AnimatedDrawableFactoryFingerprint : Fingerprint(
    returnType = ANIMATED_DRAWABLE_DESCRIPTOR,
    custom = { method, _ -> method.isAnimatedDrawableFactory() },
)
