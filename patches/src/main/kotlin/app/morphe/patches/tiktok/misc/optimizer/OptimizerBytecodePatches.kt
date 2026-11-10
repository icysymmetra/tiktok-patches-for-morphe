/*
 * Adapted from kveld9/kveld-morphe-patches at
 * fcb1768620b8f98a6dd31e801074589ce9a63356 (GPL-3.0).
 * https://github.com/kveld9/kveld-morphe-patches/tree/fcb1768620b8f98a6dd31e801074589ce9a63356
 */
package app.morphe.patches.tiktok.misc.optimizer

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.returnBooleanWhenOn
import app.morphe.patches.tiktok.shared.returnVoidWhenOn
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import app.morphe.util.implementationOrPatchException
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** Where the four Performance patches ask whether their switch is on. */
internal const val PERFORMANCE_SWITCHES = "Lapp/morphe/extension/tiktok/misc/PerformanceSwitches;"
private const val SETTINGS_STATUS = "Lapp/morphe/extension/tiktok/settings/SettingsStatus;"

internal const val SPLASH_AD_SWITCH = "$PERFORMANCE_SWITCHES->skipSplashAd()Z"
internal const val BACKGROUND_TRAFFIC_SWITCH = "$PERFORMANCE_SWITCHES->limitBackgroundTraffic()Z"
internal const val UPDATE_CHECKS_SWITCH = "$PERFORMANCE_SWITCHES->skipUpdateChecks()Z"

private const val SPLASH = "Skip the splash ad"
private const val TRAFFIC = "Limit background traffic"
private const val ANIMATED_CACHE = "Drop the animated image cache"
private const val UPDATE_CHECKS = "Skip update checks"

@Suppress("unused")
val instantLaunchSplashBlockerPatch = bytecodePatch(
    name = "Skip the splash ad",
    description = "Lets you stop the full-screen ad TikTok can show while it starts up. Starts " +
        "off. Turn it on in Metra settings > App behavior.",
) {
    category("Performance")
    dependsOn(sharedExtensionPatch, settingsPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        installSplashAdSwitch()
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $SETTINGS_STATUS->enableSkipSplashAd()V",
        )
    }
}

/**
 * Skip the splash ad's hooks, without its settings row, so a test can put them on a build that
 * carries no extension. The four preload tasks return at once and every reviewed splash gate
 * answers no, all of them only while the switch is on.
 */
context(patchContext: BytecodePatchContext)
internal fun installSplashAdSwitch() {
    // DeferredSplashAdManagerPreloadTask (47.1.x) only calls SplashAdManagerPreloadTask.run.
    val voidMethods = listOf(
        SplashPreloadTaskFingerprint.method,
        SplashPreloadEntryFingerprint.method,
        TopViewPreloadTaskFingerprint.method,
        RealTimeSplashTaskFingerprint.method,
    )
    fun reviewedBooleanMethods(classDescriptor: String, expectedCounts: List<Int>, boundary: String) =
        patchContext.mutableClassDefBy(classDescriptor).methods.filter { method ->
            method.returnType == "Z" && method.parameterTypes.isEmpty() &&
                AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.FINAL.isSet(method.accessFlags)
        }.also { methods ->
            val counts = methods.map {
                it.implementationOrPatchException("Instant Launch & Splash Blocker").instructions.count()
            }.sorted()
            if (counts != expectedCounts.sorted()) {
                throw PatchException(
                    "Instant Launch & Splash Blocker: $boundary has unreviewed Boolean method shapes: $counts.",
                )
            }
        }

    val fixedBooleanMethods = reviewedBooleanMethods(
        SPLASH_SETTING_DESCRIPTOR,
        listOf(9, 11),
        "SplashSettingServiceImpl",
    ) + reviewedBooleanMethods(
        REALTIME_SPLASH_DESCRIPTOR,
        listOf(3),
        "RealTimeSplashManagerImpl",
    )
    val splashService = patchContext.mutableClassDefBy(SPLASH_SERVICE_DESCRIPTOR)
    val serviceGates = splashService.methods.filter { method ->
        method.returnType == "Z" && method.parameterTypes.isEmpty() &&
            AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.FINAL.isSet(method.accessFlags)
    }
    val serviceGateInstructionCounts = serviceGates.map {
        it.implementationOrPatchException("Instant Launch & Splash Blocker").instructions.count()
    }
    if (!isReviewedSplashGateShape(serviceGateInstructionCounts)) {
        throw PatchException(
            "Instant Launch & Splash Blocker: SplashAdServiceImpl has unreviewed Boolean method shapes: " +
                serviceGateInstructionCounts.sorted().joinToString(),
        )
    }

    voidMethods.forEach { it.returnVoidWhenOn(SPLASH, SPLASH_AD_SWITCH) }
    (fixedBooleanMethods + serviceGates).forEach { it.returnBooleanWhenOn(SPLASH, SPLASH_AD_SWITCH, false) }
}

@Suppress("unused")
val networkTrafficGovernorPatch = bytecodePatch(
    name = "Limit background traffic",
    description = "Lets you stop TikTok loading upcoming videos ahead of time, which uses less " +
        "data in the background, but videos may take a moment longer to start. Starts off. Turn " +
        "it on in Metra settings > App behavior.",
) {
    category("Performance")
    dependsOn(sharedExtensionPatch, settingsPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    val skipPushSetup by booleanOption(
        "skipPushSetup",
        default = false,
        title = "Skip notification setup",
        description = "With this on, the Limit background traffic switch in Metra settings " +
            "also stops TikTok setting up notifications, so you won't get any, messages included. " +
            "Turn the switch off or pause Metra to get them back.",
        required = false,
    )

    execute {
        val skipsPush = skipsPushSetup(skipPushSetup)
        limitBackgroundTraffic(skipsPush)
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $SETTINGS_STATUS->enableLimitBackgroundTraffic()V",
        )
        if (skipsPush) {
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, $SETTINGS_STATUS->enableSkipPushSetup()V",
            )
        }
    }
}

/** Only an explicit yes skips push setup: a missing or null option leaves it to TikTok (#86). */
internal fun skipsPushSetup(option: Boolean?): Boolean = option == true

/**
 * Limit background traffic's hooks, without its settings row, so a test can put them on a build
 * that carries no extension. Both ask the patch's one switch: the buffer preload gate answers no
 * with it on, and the push setup task, hooked only when the option asks for it, returns at once.
 */
context(patchContext: BytecodePatchContext)
internal fun limitBackgroundTraffic(skipPushSetup: Boolean) {
    BufferPreloadGateFingerprint.method.returnBooleanWhenOn(TRAFFIC, BACKGROUND_TRAFFIC_SWITCH, false)
    if (skipPushSetup) InitPushTaskFingerprint.method.returnVoidWhenOn(TRAFFIC, BACKGROUND_TRAFFIC_SWITCH)
}

@Suppress("unused")
val runtimeMemoryGovernorPatch = bytecodePatch(
    name = "Drop the animated image cache",
    description = "Lets you make TikTok decode animated stickers and GIFs one frame at a time as " +
        "they play, instead of a few frames ahead, so they use a little less memory. Starts off. " +
        "Turn it on in Metra settings > App behavior.",
) {
    category("Performance")
    dependsOn(sharedExtensionPatch, settingsPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        installAnimatedImageCacheSwitch()
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $SETTINGS_STATUS->enableAnimatedImageCache()V",
        )
    }
}

/**
 * Drop the animated image cache's hook, without its settings row. With the switch on, the
 * backend builder skips the frame preparer, so each frame is decoded as it's drawn and TikTok's
 * own frame cache keeps it. 0.69.0 also swapped in Fresco's keep-last-frame cache (caching
 * strategy 3), which left animated stickers in comments on their first frame (#130), and the
 * version before that nulled the FrescoFrameCache reads, so every frame walked back toward the
 * first (#100). Fresco itself skips the preparer when its count is 0. With the switch off the
 * count is TikTok's own.
 */
context(patchContext: BytecodePatchContext)
internal fun installAnimatedImageCacheSwitch() {
    val factory = AnimatedDrawableFactoryFingerprint.method
    val builderCall = factory.backendBuilderCall()
        ?: throw PatchException("$ANIMATED_CACHE: no animation backend builder call.")
    val builder = patchContext.mutableClassDefBy(factory.definingClass).methods.single {
        it.name == builderCall.name && it.parameterTypes.map(CharSequence::toString) == builderCall.parameterTypes.map(CharSequence::toString) &&
            it.returnType == builderCall.returnType
    }
    val gate = builder.framePreparerGateIndex()
        ?: throw PatchException("$ANIMATED_CACHE: the frame preparer gate has an unreviewed shape.")
    val gateRegister = builder.getInstruction<OneRegisterInstruction>(gate).registerA

    // The count passes through the switch, which hands TikTok's own back while it's off. Nothing
    // may jump straight to the gate, or that path would skip the question. framePreparerGateIndex
    // only looks at branches, so the gate is checked again here for switch cases and handlers.
    if (builder.isBranchTarget(gate)) {
        throw PatchException("$ANIMATED_CACHE: a switch case or handler lands on the frame preparer gate.")
    }
    builder.addInstructions(gate, passThrough(gateRegister, "framesToPrepare"))
}

/**
 * Hands the int in [register] to [bridge], an `(I)I` method of the Performance switches, and
 * puts its answer back in the same register. A one-register range call reaches any register
 * and needs no scratch one.
 */
internal fun passThrough(register: Int, bridge: String): String {
    if (register > 255) throw PatchException("$ANIMATED_CACHE: v$register is out of move-result's reach.")
    return """
        invoke-static/range {v$register .. v$register}, $PERFORMANCE_SWITCHES->$bridge(I)I
        move-result v$register
    """
}

@Suppress("unused")
val updatePromptSuppressorPatch = bytecodePatch(
    name = "Skip update checks",
    description = "Lets you stop two background tasks TikTok uses to check for updates, one of " +
        "them when your phone starts. Some in-app update prompts may stop. Play Store updates " +
        "still work. Starts off. Turn it on in Metra settings > App behavior.",
) {
    category("Performance")
    dependsOn(sharedExtensionPatch, settingsPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        installUpdateCheckSwitch()
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, $SETTINGS_STATUS->enableSkipUpdateChecks()V",
        )
    }
}

/** Skip update checks' hooks, without its settings row: both tasks return at once with the switch on. */
context(patchContext: BytecodePatchContext)
internal fun installUpdateCheckSwitch() {
    listOf(
        UpdateBackgroundTaskFingerprint.method,
        UpdateBootFinishedTaskFingerprint.method,
    ).forEach { it.returnVoidWhenOn(UPDATE_CHECKS, UPDATE_CHECKS_SWITCH) }
}

/** Bytecode half of LIVE Stream Suite Optimizer. It is selected only through the resource patch. */
internal val liveGiftEffectOptimizerPatch = bytecodePatch {
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        LiveGiftInitViewFingerprint.method.returnEarly()
        LiveGiftOnCreateFingerprint.method.keepOnlyLiveWidgetCreate()
    }
}

/**
 * Returns right after the gift widget's call to LiveWidget.onCreate, skipping the widget's own
 * setup. That call creates the CompositeDisposable LiveWidget.onDestroy disposes without a null
 * test, so returning before it crashed TikTok on leaving a LIVE room (#119).
 */
internal fun MutableMethod.keepOnlyLiveWidgetCreate() {
    val superCreate = liveWidgetCreateIndex()
        ?: throw PatchException("Remove LIVE extras: the gift widget's onCreate doesn't start with LiveWidget.onCreate.")
    addInstruction(superCreate + 1, "return-void")
}

/** The index of the method's opening LiveWidget.onCreate super call, or null when it opens with anything else. */
internal fun Method.liveWidgetCreateIndex(): Int? {
    val first = implementation?.instructions?.firstOrNull() ?: return null
    val call = first.getReference<MethodReference>() ?: return null
    // A method with more than 16 registers gets the range form of the same call.
    val superCall = first.opcode == Opcode.INVOKE_SUPER || first.opcode == Opcode.INVOKE_SUPER_RANGE
    val opensWithSuper = superCall && call.definingClass == LIVE_WIDGET_DESCRIPTOR &&
        call.name == "onCreate" && call.parameterTypes.isEmpty() && call.returnType == "V"
    return if (opensWithSuper) 0 else null
}
