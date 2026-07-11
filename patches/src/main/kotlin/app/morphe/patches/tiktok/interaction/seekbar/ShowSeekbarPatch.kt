package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/seekbar/SeekbarPatch;"

@Suppress("unused")
val showSeekbarPatch = bytecodePatch(
    name = "Show seekbar",
    description = "Shows a progress bar for all videos. (Supports TikTok 43.8.3.)",
    default = true,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok4383())

    execute {
        val targetClass = VanillaLongFilterFingerprint.method.definingClass
        val targetMethodName = VanillaLongFilterFingerprint.method.name

        ShouldShowProgressBarFingerprint.method.addInstructions(
            0,
            """
                if-eqz p0, :show_seekbar_not_video
                invoke-static {}, $EXTENSION_CLASS_DESCRIPTOR->isEnabled()Z
                move-result v0
                if-eqz v0, :show_seekbar_not_video
                invoke-static {p0}, ${targetClass}->${targetMethodName}(Lcom/ss/android/ugc/aweme/feed/model/Aweme;)Z
                move-result v0
                return v0
                :show_seekbar_not_video
                const/4 v0, 0x0
                return v0
            """,
        )

        SetSeekBarShowTypeFingerprint.method.apply {
            val typeRegister = implementation!!.registerCount - 1
            addInstructions(
                0,
                """
                    invoke-static {v$typeRegister}, $EXTENSION_CLASS_DESCRIPTOR->overrideSeekbarShowType(I)I
                    move-result v$typeRegister
                """,
            )
        }
    }
}
