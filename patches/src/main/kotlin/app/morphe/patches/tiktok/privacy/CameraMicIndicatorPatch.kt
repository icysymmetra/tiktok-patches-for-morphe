/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.util.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.MainActivityOnCreateFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patcher.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

private const val EXTENSION = "Lapp/morphe/extension/tiktok/privacy/CameraMicIndicator;"

private val CAMERA_OPEN = setOf(
    "Landroid/hardware/Camera;->open()Landroid/hardware/Camera;",
    "Landroid/hardware/Camera;->open(I)Landroid/hardware/Camera;",
)
private val CAMERA_START = setOf(
    "Landroid/hardware/camera2/CameraManager;->openCamera(Ljava/lang/String;Landroid/hardware/camera2/CameraDevice\$StateCallback;Landroid/os/Handler;)V",
    "Landroid/hardware/camera2/CameraManager;->openCamera(Ljava/lang/String;Ljava/util/concurrent/Executor;Landroid/hardware/camera2/CameraDevice\$StateCallback;)V",
)
private val CAMERA_STOP = setOf(
    "Landroid/hardware/Camera;->release()V",
    "Landroid/hardware/camera2/CameraDevice;->close()V",
)
private val MIC_START = setOf(
    "Landroid/media/AudioRecord;->startRecording()V",
    "Landroid/media/AudioRecord;->startRecording(Landroid/media/MediaSyncEvent;)V",
    "Landroid/media/MediaRecorder;->start()V",
)
private val MIC_STOP = setOf(
    "Landroid/media/AudioRecord;->stop()V",
    "Landroid/media/AudioRecord;->release()V",
    "Landroid/media/MediaRecorder;->stop()V",
    "Landroid/media/MediaRecorder;->release()V",
)

@Suppress("unused")
val cameraMicIndicatorPatch = bytecodePatch(
    name = "Camera and microphone indicator",
    description = "Shows a small mark in the top corner while TikTok is using the camera or microphone, so you always know when it is. Starts off. Turn it on in Metra settings > App behavior.",
) {
    category("Privacy")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableCameraMicIndicator()V",
        )
        // The screen in front has to be followed before the first camera opens: TikTok's cameras
        // are scenes inside an activity that has already resumed by then, and a mark placed on the
        // main activity instead sat hidden underneath it (the S25, 2026-09-26). A range invoke, so
        // the parameter register's number never has to fit a 4-bit operand.
        MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION->install(Landroid/app/Activity;)V",
        )

        val opens = invokeSitesOf(CAMERA_OPEN, static = true)
        val cameraStarts = invokeSitesOf(CAMERA_START)
        val cameraStops = invokeSitesOf(CAMERA_STOP)
        val micStarts = invokeSitesOf(MIC_START)
        val micStops = invokeSitesOf(MIC_STOP)
        if (opens.isEmpty() && cameraStarts.isEmpty() && micStarts.isEmpty()) {
            throw PatchException("Camera and microphone indicator: no camera or recorder call site was found.")
        }

        // Each site gets one instruction, and within a method the later ones go first so the
        // earlier indices stay right. Every report goes after its call, never before it. All
        // of these calls sit in TikTok's privacy wrapper, which branches straight to the call
        // whenever its policy check hands back no replacement argument, and an instruction
        // added in front of the call keeps the branch pointing at the call: the report was
        // skipped on the path that runs. The S22 showed it as a camera dot that never went
        // away after the recorder closed. After the call, every path that made the call
        // falls through into the report. A start that returns nothing is reported after
        // the call, so a call that throws shows no dot. Camera.open returns the camera, and
        // nothing may sit between an invoke and its move-result, so its report goes after the
        // move-result and carries the result: a null open shows nothing either. A stop that
        // throws goes unreported, which leaves the dot on, and that is the truthful state
        // for a release that failed.
        data class Edit(val site: InvokeSite, val offset: Int, val instruction: String)
        val edits = mutableListOf<Edit>()
        opens.forEach { site ->
            val instructions = site.method.implementation!!.instructions.toList()
            val next = instructions.getOrNull(site.index + 1)
            if (next != null && next.opcode == Opcode.MOVE_RESULT_OBJECT) {
                val register = (next as OneRegisterInstruction).registerA
                edits += Edit(site, 2, "invoke-static { v$register }, $EXTENSION->onCameraOpened(Landroid/hardware/Camera;)V")
            } else {
                edits += Edit(site, 1, "invoke-static {}, $EXTENSION->onCameraStart()V")
            }
        }
        cameraStarts.forEach { edits += Edit(it, 1, "invoke-static {}, $EXTENSION->onCameraStart()V") }
        micStarts.forEach { edits += Edit(it, 1, "invoke-static {}, $EXTENSION->onMicStart()V") }
        cameraStops.forEach { edits += Edit(it, 1, "invoke-static {}, $EXTENSION->onCameraStop()V") }
        micStops.forEach { edits += Edit(it, 1, "invoke-static {}, $EXTENSION->onMicStop()V") }

        edits.sortedWith(compareBy({ it.site.owner.type }, { it.site.method.toString() }, { -(it.site.index + it.offset) }))
            .forEach { edit ->
                val method = mutableClassDefBy(edit.site.owner).findMutableMethodOf(edit.site.method)
                val at = edit.site.index + edit.offset
                if (edit.offset == 2) {
                    // The instruction the report is placed after has to be the move-result the
                    // site was read with; a method mutated since would put the call elsewhere.
                    val previous = method.getInstruction(at - 1)
                    if (previous.opcode != Opcode.MOVE_RESULT_OBJECT) {
                        throw PatchException("Camera and microphone indicator: the camera open at ${edit.site.method} lost its move-result.")
                    }
                }
                method.addInstruction(at, edit.instruction)
            }
        println(
            "[Camera/mic indicator] Instrumented ${opens.size + cameraStarts.size} camera opens, " +
                "${cameraStops.size} camera releases, ${micStarts.size} recorder starts and ${micStops.size} recorder stops.",
        )
    }
}
