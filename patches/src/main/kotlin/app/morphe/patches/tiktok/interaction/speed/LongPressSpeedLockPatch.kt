/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.speed

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val FEATURE_CONTROLS_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

@Suppress("unused")
val longPressSpeedLockPatch = bytecodePatch(
    name = "Hold-and-slide 2x lock",
    description = "Enables TikTok's native hold, slide down, and release gesture to lock 2x speed.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLongPressSpeedLock()V",
        )

        LongPressSpeedUpEnableFingerprint.method.let { method ->
            val lookupIndex = method.indexOfFirstInstructionOrThrow {
                getReference<MethodReference>()?.let { reference ->
                    reference.parameterTypes == listOf("I", "Ljava/lang/String;", "Z", "Z") &&
                        reference.returnType == "Z"
                } == true
            }
            val resultRegister = method.getInstruction<OneRegisterInstruction>(lookupIndex + 1).registerA
            method.addInstructions(
                lookupIndex + 2,
                """
                    invoke-static {v$resultRegister}, $FEATURE_CONTROLS_CLASS_DESCRIPTOR->overrideLongPressSpeedUpEnabled(Z)Z
                    move-result v$resultRegister
                """,
            )
        }

        LongPressSpeedUpLockFingerprint.method.let { method ->
            val lookupIndex = method.indexOfFirstInstructionOrThrow {
                getReference<MethodReference>()?.let { reference ->
                    reference.parameterTypes == listOf("I", "I", "Ljava/lang/String;", "Z") &&
                        reference.returnType == "I"
                } == true
            }
            val resultRegister = method.getInstruction<OneRegisterInstruction>(lookupIndex + 1).registerA
            method.addInstructions(
                lookupIndex + 2,
                """
                    invoke-static {v$resultRegister}, $FEATURE_CONTROLS_CLASS_DESCRIPTOR->overrideLongPressSpeedUpLockDistance(I)I
                    move-result v$resultRegister
                """,
            )
        }
    }
}
