/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/interaction/seekbar/ShowSeekbarPatch.kt
 */
package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.misc.absettings.hookAppAbIntBoundary
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/seekbar/SeekbarPatch;"

/**
 * Inserts a call to [SeekbarPatch.forceSeekbarRefresh] between the stored show-type read and
 * the native equality shortcut, so a zero rewrite from [SeekbarPatch.overrideSeekbarShowType]
 * cannot match a stored zero and skip the visible branch.
 */
internal fun MutableMethod.hookSeekbarTypeRefresh() {
    val instructions = implementation!!.instructions.toList()
    data class Candidate(val igetIndex: Int, val storedReg: Int, val paramReg: Int)
    val candidates = mutableListOf<Candidate>()
    for (i in instructions.indices) {
        val insn = instructions[i]
        if (insn.opcode != Opcode.IGET) continue
        val field = insn.getReference<FieldReference>() ?: continue
        if (field.type != "I") continue
        val two = insn as? TwoRegisterInstruction ?: continue
        val storedReg = two.registerA
        if (i + 1 >= instructions.size) continue
        val next = instructions[i + 1]
        if (next.opcode != Opcode.IF_EQ) continue
        val cmp = next as? TwoRegisterInstruction ?: continue
        val paramReg = when {
            cmp.registerA == storedReg -> cmp.registerB
            cmp.registerB == storedReg -> cmp.registerA
            else -> continue
        }
        candidates.add(Candidate(i, storedReg, paramReg))
    }
    if (candidates.size != 1) {
        throw PatchException(
            "Expected exactly one stored-type equality check, found ${candidates.size}."
        )
    }
    val (igetIndex, storedReg, paramReg) = candidates.single()
    addInstructions(
        igetIndex + 1,
        """
            invoke-static {v$storedReg, v$paramReg}, $EXTENSION_CLASS_DESCRIPTOR->forceSeekbarRefresh(II)I
            move-result v$storedReg
        """,
    )
}

@Suppress("unused")
val showSeekbarPatch = bytecodePatch(
    name = "Show the progress bar",
    description = "Shows the progress bar on videos where TikTok hides it, so you can see how long a video is and skip around. On by default. Turn it off in Metra settings > App behavior, where you can also show the time beside the bar or give it a bigger handle. A seek bar for video stories is there too.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // Checked before the first write: the predicate's injection writes v0 and then falls
        // into TikTok's own first instruction, so v0 has to be a local rather than p0.
        ShouldShowProgressBarFingerprint.method.requireLocals("Show the progress bar", 1)

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableShowSeekbar()V",
        )

        // This target is TikTok's short predicate used by the feed progress UI.
        ShouldShowProgressBarFingerprint.method.addInstructions(
            0,
            """
                if-eqz p0, :show_seekbar_original
                invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->isEnabled()Z
                move-result v0
                if-eqz v0, :show_seekbar_original
                const/4 v0, 0x1
                return v0
                :show_seekbar_original
            """,
        )

        SetSeekBarShowTypeFingerprint.method.apply {
            // The last parameter, which the fingerprint holds to being the int. An int takes one
            // register, so it is the top of the frame; range form, because that can sit past v15.
            val typeRegister = implementation!!.registerCount - 1
            addInstructions(
                0,
                """
                    invoke-static/range {v$typeRegister .. v$typeRegister}, $EXTENSION_CLASS_DESCRIPTOR->overrideSeekbarShowType(I)I
                    move-result v$typeRegister
                """,
            )
            hookSeekbarTypeRefresh()
        }

        // TikTok's reasons for keeping the bar off a video run before the show type is asked.
        // Two of them are experiments; both are found from their log line and answered here.
        val gates = SeekbarGateLogFingerprint.method
        val inverse = gates.gateBefore(INVERSE_EXPERIMENT_LOG)
        val drag = gates.gateBefore(CANNOT_DRAG_LOG)
        mutableClassDefBy(inverse.definingClass).methods.named(inverse).answerInverseExperiment()
        mutableClassDefBy(drag.definingClass).methods.named(drag).answerDraggable()

        // Both extras below are held to every declared build by their anchors tests, so a miss
        // there fails the patch. A build the patch is forced onto keeps the bar without them.
        // Every hook an extra needs is checked before any goes in, so an extra goes in whole or
        // not at all, and its switches show only once it's in.
        fun extra(what: String, status: String, hooks: () -> List<() -> Unit>) {
            val checked = try {
                hooks()
            } catch (problem: Exception) {
                if (packageMetadata.versionName in declaredVersions()) throw problem
                println("[Show the progress bar] Left out $what on ${packageMetadata.versionName}: ${problem.message}")
                return
            }
            checked.forEach { it() }
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->$status()V",
            )
        }

        // The time beside the bar and the bigger handle (#90), two switches that start off. Both
        // work through the bar's own methods, found from the show-type setter's class, and the
        // time learns the video and its length from the player's progress event.
        extra("the time beside the bar and the bigger handle", "enableSeekbarExtras") {
            val showType = SetSeekBarShowTypeFingerprint.method
            val bar = mutableClassDefBy(showType.definingClass)
            val event = PlayProgressFingerprint.method.progressEvent()
            listOf(
                bar.methods.named(barProgressOf(bar.type, bar.methods)).barProgressHook(),
                bar.methods.named(showType.barStyle()).barStyleHook(),
                mutableClassDefBy(event.definingClass).methods.named(event).progressTickHook(),
            )
        }

        // The seek bar on video stories (#72), a switch that starts off. The story cell reports
        // its view and each story it shows, and the same progress callback says which story is
        // playing and where.
        extra("the seek bar on video stories", "enableStorySeekbar") {
            listOf(
                StoryVideoCellViewFingerprint.method.storyCellViewHook(),
                StoryVideoCellBindFingerprint.method.storyCellBindHook(),
                PlayProgressFingerprint.method.storyProgressHook(),
            )
        }
    }
}

@Suppress("unused")
val showSeekbarThumbnailPatch = bytecodePatch(
    name = "Show the progress bar thumbnail",
    description = "Shows a small preview picture while you drag the progress bar, so you can " +
        "find the part you want. On by default. Turn it off in Metra settings > App behavior.",
    default = true,
) {
    category("Playback")
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSeekbarThumbnail()V",
        )
        hookAppAbIntBoundary(
            EXTENSION_CLASS_DESCRIPTOR,
            "overrideThumbnailGate",
        )

        // The preview check's diagnostic line (#140): whether the video came with preview
        // sheets, which only the server sends. Held to every declared build by
        // SeekbarPreviewAnchorsTest, left out with a note on a build the patch is forced onto.
        try {
            val seekbar = mutableClassDefBy(SeekbarGateLogFingerprint.method.definingClass)
            val check = previewCheckOf(seekbar)
            seekbar.methods.named(check.method).reportPreviewCheck(check.video)
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[Show the progress bar thumbnail] Left out the preview check line on ${packageMetadata.versionName}: ${problem.message}")
        }
    }
}
