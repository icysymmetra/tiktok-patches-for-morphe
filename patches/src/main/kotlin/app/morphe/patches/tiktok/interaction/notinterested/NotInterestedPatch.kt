/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.notinterested

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.blockAuthorPatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.cloneMutable
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.value.StringEncodedValue

private const val EXTENSION = "Lapp/morphe/extension/tiktok/notinterested/NotInterested;"

private object DislikeRequestFactoryFingerprint : Fingerprint(
    strings = listOf("dislike_reason_id", "music_id", "author_id", "commit_type").map { string(it, StringComparisonType.CONTAINS) },
    parameters = listOf("Lcom/ss/android/ugc/aweme/feed/model/Aweme;", "Ljava/lang/String;", "Ljava/lang/String;"),
    returnType = "Ljava/lang/String;",
)

@Suppress("unused")
val notInterestedPatch = bytecodePatch(
    name = "Not interested button",
    description = "Adds a button on videos that tells TikTok you're not interested in one tap, so it can show you fewer like it. Starts off. Turn it on in Metra settings > Interface.",
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch, blockAuthorPatch)
    compatibleWith(*AppCompatibilities.tiktok())
    execute {
        val factory = mutableClassDefBy(DislikeRequestFactoryFingerprint.method.definingClass)
        val service = factory.fields.filter { it.accessFlags and AccessFlags.STATIC.value != 0 }
            .singleOrPatchException("Not interested: dislike request service field")
        val endpoint = mutableClassDefBy(service.type).methods.filter { method ->
            method.parameterTypes == listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/util/Map;") &&
                method.annotations.any { annotation -> annotation.elements.any {
                    (it.value as? StringEncodedValue)?.value == "/aweme/v1/commit/dislike/item/"
                } }
        }.singleOrPatchException("Not interested: annotated dislike endpoint")
        // Resolve the service and endpoint from code/annotations. No obfuscated names in the extension.
        val bridgeClass = mutableClassDefBy(EXTENSION)
        val original = bridgeClass.methods.filter { it.name == "createCall" }
            .singleOrPatchException("Not interested: extension createCall bridge")
        val bridge = original.cloneMutable(additionalRegisters = 4)
        bridgeClass.methods.remove(original)
        bridgeClass.methods.add(bridge)
        bridge.addInstructions(0, """
            sget-object v0, ${service.definingClass}->${service.name}:${service.type}
            move-object/from16 v1, p0
            move-object/from16 v2, p1
            move-object/from16 v3, p2
            invoke-interface/range { v0 .. v3 }, $endpoint
            move-result-object v0
            return-object v0
        """)
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableNotInterested()V")
    }
}
