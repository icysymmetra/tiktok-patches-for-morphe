package app.morphe.patches.tiktok.misc.share

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/share/ShareUrlSanitizer;"

@Suppress("unused")
val sanitizeShareUrlsPatch = bytecodePatch(
    name = "Sanitize sharing links",
    description = "Removes tracking parameters from shared links. (Supports TikTok 43.8.3.)",
    default = true,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok4383())

    execute {
        ShareUrlTrackerFingerprint.method.apply {
            val urlRegister = implementation!!.registerCount - parameterTypes.size +
                if (parameterTypes[0] in arrayOf("J", "D")) 2 else 1

            addInstructions(
                0,
                """
                    invoke-static {v$urlRegister}, $EXTENSION_CLASS_DESCRIPTOR->stripAllQueryParams(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v0
                    return-object v0
                """,
            )
        }
    }
}
