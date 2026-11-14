/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/settings/EnableOpenDebugPatch.kt
 *
 * TikTok 43.6.2: ReVanced MR !6535. OpenDebug hooks use heavy obfuscation; names differ by APK variant.
 * State class: many short LX/… UI states reuse obfuscated field names like LLILLL—selection is driven by the
 * unique Jetpack compose `LIZ` method that calls Context.getString(I) and reads LLILL (OpenDebug row).
 */
package app.morphe.patches.tiktok.misc.settings

import app.morphe.util.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.booleanOption
import app.morphe.patcher.patch.stringOption
import app.morphe.patches.tiktok.misc.diagnostics.configureControlTrial
import app.morphe.util.returnEarly
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.diagnostics.resolveShareTimingWrites
import app.morphe.patches.tiktok.misc.diagnostics.applySoftReferenceTrial
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode

private const val CRASH_CAPTURE_INITIALIZER =
    "Lapp/morphe/extension/tiktok/diagnostics/JavaCrashCapture;->initialize(Landroid/content/Context;)V"

@Suppress("unused")
val enableOpenDebugPatch = bytecodePatch(
    name = "Diagnostic tools",
    description = "Adds logs and reports you can save or share when something goes wrong, so " +
        "a problem is easier to report and fix. Starts off. Turn it on in Metra settings > " +
        "Diagnostics.",
) {
    category("Settings")
    dependsOn(sharedExtensionPatch, settingsPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    val recoveryTrial by booleanOption(
        key = "recoveryTrial",
        default = false,
        title = "Automatic recovery report trial",
        description = "Requested trials on Android 11 or later only: captures failures from up to " +
            "three process starts in 30 minutes. Reopen after a freeze to save a report in Downloads/Morphe. " +
            "Re-patching starts a fresh trial. No uploads or other settings changes. Leave off for normal use.",
    )

    val softReferenceTrial by booleanOption(
        key = "softReferenceTrial",
        default = false,
        title = "Soft-reference compatibility trial",
        description = "Requested TikTok 47.4.3 global trial only. Disables one cache optimization " +
            "and enables the automatic recovery report trial. May change cache performance. " +
            "Leave off for normal use.",
    )

    val controlArm by stringOption(
        key = "controlArm", default = "off", required = true,
        values = mapOf("Off" to "off", "Minimal diagnostic control" to "minimal", "Feature reference" to "reference",
            "GC policy compatibility trial" to "gc_policy"),
        title = "Issue 215 diagnostic comparison",
        description = "Requested 47.4.3 global trials only. Records bounded GC-tuning observations and automatic reports. " +
            "The label does not select patches: use the supplied selection. GC policy mode suppresses three tuning mechanisms. Leave off normally.",
    )

    execute {
        val arm = controlArm ?: "off"
        check(arm in setOf("off", "minimal", "reference", "gc_policy")) { "Unknown diagnostic control arm" }
        if (arm != "off") {
            check(packageMetadata.packageName == "com.zhiliaoapp.musically" && packageMetadata.versionName == "47.4.3") {
                "Diagnostic controls require TikTok 47.4.3 global"
            }
            check(softReferenceTrial != true) { "Control modes select their reference policy; leave the standalone soft-reference option off" }
            mutableClassDefBy("Lapp/morphe/extension/shared/diagnostics/RecoveryCapture;").methods
                .single { it.name == "controlArm" && it.parameterTypes.isEmpty() }.returnEarly(arm)
        }
        configureControlTrial(arm)
        if (softReferenceTrial == true || arm == "gc_policy") {
            check(packageMetadata.packageName == "com.zhiliaoapp.musically" &&
                packageMetadata.versionName == "47.4.3") {
                "Soft-reference trial requires TikTok 47.4.3 global."
            }
            if (softReferenceTrial == true) applySoftReferenceTrial()
            mutableClassDefBy("Lapp/morphe/extension/shared/diagnostics/RecoveryCapture;").methods
                .single { it.name == "softReferenceTrialEnabled" && it.parameterTypes.isEmpty() }
                .returnEarly(true)
        }
        if (recoveryTrial == true || softReferenceTrial == true || arm != "off") {
            mutableClassDefBy("Lapp/morphe/extension/shared/diagnostics/RecoveryCapture;").methods
                .single { it.name == "trialId" && it.parameterTypes.isEmpty() }
                .returnEarly("issue215-${if (arm != "off") "control-v3" else if (softReferenceTrial == true) "softref-v1" else "v1"}-${java.util.UUID.randomUUID()}")
        }
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableDiagnostics()V",
        )

        val npthExtent = NpthExtentTaskInitFingerprint.method
        // The fingerprint holds the context to the first and only parameter, so it is p0 on a
        // static method and p1 behind `this` on an instance one. Both are named here rather
        // than assumed: a frame past v15 cannot be reached by the plain invoke below.
        val contextRegister =
            if (npthExtent.accessFlags and AccessFlags.STATIC.value != 0) "p0" else "p1"
        val contextIndex = npthExtent.implementation!!.registerCount -
            npthExtent.numberOfParameterRegisters +
            (if (npthExtent.accessFlags and AccessFlags.STATIC.value != 0) 0 else 1)
        check(contextIndex <= 15) {
            "Diagnostics: NpthExtentTask.LIZ keeps its context in v$contextIndex, past what " +
                "the plain invoke can name."
        }
        val returnIndices = npthExtent.implementation!!.instructions.withIndex()
            .filter { it.value.opcode == Opcode.RETURN_VOID }
            .map { it.index }

        returnIndices.asReversed().forEach { returnIndex ->
            npthExtent.addInstructionsAtControlFlowLabel(
                returnIndex,
                "invoke-static {$contextRegister}, $CRASH_CAPTURE_INITIALIZER",
            )
        }
    }

    finalize {
        // Other selected patches can prepend share interception and add normal returns.
        // Time the completed native methods, including those optional extension paths.
        resolveShareTimingWrites()()
    }
}
