/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.speed

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patcher.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.moveResultRegisterAfter
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val FEATURE_CONTROLS_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/featurecontrols/FeatureControls;"

@Suppress("unused")
val longPressSpeedLockPatch = bytecodePatch(
    name = "Hold-and-slide 2x lock",
    description = "Turns on TikTok's own gesture where you hold a video, slide down and let go to keep it playing fast without holding. Starts off. Turn it on in Metra settings > Interface.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

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
            val resultRegister = method.moveResultRegisterAfter(lookupIndex, "Hold-and-slide 2x lock")
            method.addInstructions(
                lookupIndex + 2,
                """
                    invoke-static/range {v$resultRegister .. v$resultRegister}, $FEATURE_CONTROLS_CLASS_DESCRIPTOR->overrideLongPressSpeedUpEnabled(Z)Z
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
            val resultRegister = method.moveResultRegisterAfter(lookupIndex, "Hold-and-slide 2x lock")
            method.addInstructions(
                lookupIndex + 2,
                """
                    invoke-static/range {v$resultRegister .. v$resultRegister}, $FEATURE_CONTROLS_CLASS_DESCRIPTOR->overrideLongPressSpeedUpLockDistance(I)I
                    move-result v$resultRegister
                """,
            )
        }
    }
}
