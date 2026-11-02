/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.quality

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION = "Lapp/morphe/extension/tiktok/playback/PlaybackQuality;"
private class QualityGetter(owner: String, getter: String, result: String) : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/model/$owner;",
    name = getter, parameters = emptyList(), returnType = result,
)

@Suppress("unused")
val playbackQualityPatch = bytecodePatch(
    name = "Playback quality",
    description = "Lets you choose the video quality, like highest, lowest or 720p, and set a lower quality for mobile data to use less data. Starts off. Pick a quality in Metra settings > App behavior.",
) {
    category("Playback")
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())
    dependsOn(settingsPatch, sharedExtensionPatch)
    execute {
        listOf("Video", "VideoUrlModel").forEach { owner ->
            val prefix = if (owner == "Video") "getVideoModel" else "getDashVideoModel"
            // Each getter has its own entry point so Hook status can name the one that handed
            // back a string or a list no gear can be chosen out of. A single shared callback
            // could only say that some getter did.
            val json = if (owner == "Video") "filterVideoModelJson" else "filterDashVideoModelJson"
            val gears = if (owner == "Video") "filterVideoGears" else "filterDashGears"
            listOf(
                Triple("getBitRate", "Ljava/util/List;", gears),
                Triple(prefix + "Str", "Ljava/lang/String;", json),
                Triple(prefix + "Map", "Ljava/util/Map;", "filterMap"),
            ).plus(if (owner == "Video") listOf(Triple("getVideoModelObject", "Ljava/lang/Object;", "cacheModel")) else emptyList())
                .forEach { (getter, type, callback) ->
                QualityGetter(owner, getter, type).method.apply {
                    findInstructionIndicesReversedOrThrow { opcode == Opcode.RETURN_OBJECT }.forEach { index ->
                        val register = getInstruction<OneRegisterInstruction>(index).registerA
                        addInstructionsAtControlFlowLabel(index, """
                            invoke-static/range { v$register .. v$register }, $EXTENSION->$callback($type)$type
                            move-result-object v$register
                        """)
                    }
                }
            }
        }
        // The player never reads those getters. All four converters that build the player kit's
        // SimVideo and SimVideoUrlModel copy getRawBitRate, the list left whole below for downloads
        // and native DASH reconstruction, and the player's bitrate selectors choose out of what
        // the setter stored. So the gear picked above was reported and never played (issue #3).
        // The setter is the one door into the player's list whichever converter came through it,
        // and it carries this name and shape on all four retained builds.
        mapOf("SimVideo" to "filterPlayerVideoGears", "SimVideoUrlModel" to "filterPlayerUrlModelGears")
            .forEach { (owner, callback) ->
                Fingerprint(
                    definingClass = "Lcom/ss/android/ugc/playerkit/simapicommon/model/$owner;",
                    name = "setBitRate", parameters = listOf("Ljava/util/List;"), returnType = "V",
                ).method.addInstructions(0, """
                    invoke-static/range { p1 .. p1 }, $EXTENSION->$callback(Ljava/util/List;)Ljava/util/List;
                    move-result-object p1
                """)
            }
        // Keep raw gears intact so the native JSON/map cache and downloads retain every variant.
        Fingerprint(
            definingClass = "Lcom/ss/android/ugc/aweme/feed/model/Video;", name = "setVideoModelObject",
            parameters = listOf("Ljava/lang/Object;"), returnType = "V",
        ).method.addInstructions(0, """
            invoke-static/range { p1 .. p1 }, $EXTENSION->cacheModel(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object p1
        """)
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enablePlaybackQuality()V")
    }
}
