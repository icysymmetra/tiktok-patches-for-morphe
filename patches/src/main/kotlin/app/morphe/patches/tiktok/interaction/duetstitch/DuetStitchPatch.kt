/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.duetstitch

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION = "Lapp/morphe/extension/tiktok/misc/DuetStitch;"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
internal const val USER = "Lcom/ss/android/ugc/aweme/profile/model/User;"

/**
 * The creator's own choice for the video, which the app reads straight off the model. Both
 * the model and these two getters kept their names.
 */
private object DuetSettingFingerprint : Fingerprint(
    definingClass = AWEME,
    name = "getDuetSetting",
    returnType = "I",
    parameters = emptyList(),
)

private object StitchSettingFingerprint : Fingerprint(
    definingClass = AWEME,
    name = "getStitchSetting",
    returnType = "I",
    parameters = emptyList(),
)

/**
 * The creator's account-wide choice, which TikTok checks as well as the video's own: the duet
 * one through DuetHelperKt.checkDuetSetting, the stitch one inline in the share sheet's Stitch
 * entry, the stitch status and the Stitch button. A video that allowed friends could still
 * lose Duet to an account that allowed nobody. On every fixture those checks are the only
 * readers of these two getters, so the owner's own settings screen is not among them, and
 * DuetStitchCallersTest holds each build to that.
 */
private object AuthorDuetSettingFingerprint : Fingerprint(
    definingClass = USER,
    name = "getDuetSetting",
    returnType = "I",
    parameters = emptyList(),
)

private object AuthorStitchSettingFingerprint : Fingerprint(
    definingClass = USER,
    name = "getStitchSetting",
    returnType = "I",
    parameters = emptyList(),
)

@Suppress("unused")
val duetStitchPatch = bytecodePatch(
    name = "Allow Duet and Stitch",
    description = "Shows the Duet and Stitch buttons on videos whose creator turned them off, " +
        "so you can still make one. TikTok may still refuse some videos or the upload. Starts " +
        "off. Turn it on in Metra settings > Share sheet.",
) {
    category("Downloads")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        for ((fingerprint, answer) in listOf(
            DuetSettingFingerprint to "setting",
            StitchSettingFingerprint to "setting",
            AuthorDuetSettingFingerprint to "authorSetting",
            AuthorStitchSettingFingerprint to "authorSetting",
        )) {
            fingerprint.method.apply {
                // The value is taken as the getter hands it back, rather than the getter being
                // answered before it reads anything, because the number it returns says which
                // of two refusals applies and only one of them is ours to answer.
                val returns = implementation!!.instructions.withIndex()
                    .filter { it.value.opcode == Opcode.RETURN }
                    .map { it.index }
                    .toList()
                check(returns.isNotEmpty()) {
                    "Allow Duet and Stitch: $definingClass->$name does not return a value."
                }
                returns.asReversed().forEach { index ->
                    // The range form names any register a return can hold.
                    val setting = getInstruction<OneRegisterInstruction>(index).registerA
                    addInstructionsAtControlFlowLabel(
                        index,
                        """
                            invoke-static/range { v$setting .. v$setting }, $EXTENSION->$answer(I)I
                            move-result v$setting
                        """,
                    )
                }
            }
        }

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableDuetStitch()V",
        )
    }
}
