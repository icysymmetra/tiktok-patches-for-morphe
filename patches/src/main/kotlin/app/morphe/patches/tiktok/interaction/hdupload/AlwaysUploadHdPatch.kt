/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.hdupload

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/upload/HdUpload;"

/** The Keva repository TikTok keeps the post page's choices in. */
internal const val PUBLISH_REPO = "TOOLS_PUBLISH_REPO_NAME"

/** The post page's HD switch as TikTok stores it: 0 never touched, 1 turned on, 2 turned off. */
internal const val HD_CHOICE_KEY = "USER_HD_VIDEO_SWITCH_SETTING"

internal const val KEVA_GET_INT = "Lcom/bytedance/keva/Keva;->getInt(Ljava/lang/String;I)I"

/** How far before a `Keva.getInt` its key's `const-string` may sit (two on every declared build). */
private const val KEY_LOOKBACK = 3

/**
 * TikTok's decision whether a post goes up in HD, given the measured upload speed: the stored
 * HD choice, then at 0 the server's default-on switch (enable_default_open_hd_video_switch) and
 * a speed floor. yyi.w0.LIZJ on 47.0.3 and u4j.w0.LIZJ on 47.1.3 and 47.1.4, the only static
 * (J)Z reading both strings. It is what the encoder settings builder, the output size, the
 * post's upload_HD_button field and the publish event's is_open_hd ask, and the iOS tweaks
 * hook the same is_open_hd. Its class holds the other two readers of the choice.
 */
internal object HdUploadDecisionFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf("J"),
    strings = listOf(PUBLISH_REPO, HD_CHOICE_KEY).map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * Where [method] reads the stored HD choice: the index of the `move-result` after each
 * `Keva.getInt` whose key register was last set by a `const-string` of [HD_CHOICE_KEY] a few
 * instructions before. A key loaded any other way isn't counted, so a reshaped read leaves the
 * patch short of its sites and it stops instead of hooking something else.
 */
internal fun hdChoiceReads(method: Method): List<Int> {
    val instructions = method.implementation?.instructions?.toList() ?: return emptyList()
    return instructions.indices.mapNotNull { index ->
        val call = instructions[index]
        if (call.opcode != Opcode.INVOKE_VIRTUAL || call.getReference<MethodReference>()?.toString() != KEVA_GET_INT) {
            return@mapNotNull null
        }
        val invoke = call as? FiveRegisterInstruction ?: return@mapNotNull null
        if (invoke.registerCount != 3) return@mapNotNull null
        val keyRegister = invoke.registerD
        val keyLoad = (index - 1 downTo maxOf(0, index - KEY_LOOKBACK))
            .firstOrNull { (instructions[it] as? OneRegisterInstruction)?.registerA == keyRegister }
            ?.let { instructions[it] }
        val loadsKey = keyLoad?.opcode == Opcode.CONST_STRING &&
            keyLoad?.getReference<StringReference>()?.string == HD_CHOICE_KEY
        val result = instructions.getOrNull(index + 1)
        if (loadsKey && result?.opcode == Opcode.MOVE_RESULT) index + 1 else null
    }
}

@Suppress("unused")
val alwaysUploadHdPatch = bytecodePatch(
    name = "Always upload in HD",
    description = "Posts every video you upload in HD, as if you'd turned on TikTok's own HD " +
        "upload setting each time. Videos TikTok doesn't count as high quality post as usual. " +
        "Starts off. Turn it on in Metra settings > App behavior.",
) {
    category("Interaction")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        // Everything is found and checked before the first write: the patcher doesn't take a
        // failed patch's writes back out.
        val decision = HdUploadDecisionFingerprint.method
        if (hdChoiceReads(decision).size != 1) {
            throw PatchException(
                "Always upload in HD: ${decision.definingClass}->${decision.name} no longer reads " +
                    "$HD_CHOICE_KEY exactly once through Keva.getInt.",
            )
        }
        // The decision, the analytics string and the "chosen by hand" check all sit in one
        // helper class, so the post's fields agree with what was encoded.
        val sites = mutableClassDefBy(decision.definingClass).methods
            .map { it to hdChoiceReads(it) }
            .filter { (_, reads) -> reads.isNotEmpty() }

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHdUpload()V",
        )
        for ((method, reads) in sites) {
            // Highest first, so the indices below stay where they were read.
            for (resultIndex in reads.sortedDescending()) {
                val register = method.getInstruction<OneRegisterInstruction>(resultIndex).registerA
                method.addInstructions(
                    resultIndex + 1,
                    """
                        invoke-static/range {v$register .. v$register}, $EXTENSION->userChoice(I)I
                        move-result v$register
                    """,
                )
            }
        }
    }
}
