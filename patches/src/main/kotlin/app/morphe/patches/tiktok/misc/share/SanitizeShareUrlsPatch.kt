/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/misc/share/SanitizeShareUrlsPatch.kt
 */
package app.morphe.patches.tiktok.misc.share

import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.requireRegisters
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/share/ShareUrlSanitizer;"

/** A long or a double takes two registers of the frame; everything else takes one. */
private fun widthOf(type: String) = if (type == "J" || type == "D") 2 else 1

@Suppress("unused")
val sanitizeShareUrlsPatch = bytecodePatch(
    name = "Sanitize sharing links",
    description = "Removes the tracking codes from TikTok links you share, so the link " +
        "carries less about you. It can also swap tiktok.com for another site. On by default. " +
        "Turn it off in Metra settings > App behavior.",
    default = true,
) {
    category("Interaction")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSanitizeShareUrls()V",
        )

        ShareUrlTrackerFingerprint.method.apply {
            // p1, worked out from the registers the frame actually holds. Counting parameters
            // and adding one for a wide first parameter is right only when nothing else is wide,
            // and lands off the end of the frame when the first one is.
            val urlRegister = implementation!!.registerCount - numberOfParameterRegisters +
                (if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1) +
                widthOf(parameterTypes[0].toString())

            // The parameter has to be the string the extension is declared to take. The
            // fingerprint asks for two strings somewhere in the list, not for this one here.
            check(parameterTypes.getOrNull(1)?.toString() == "Ljava/lang/String;") {
                "Sanitize sharing links: ${definingClass}->$name does not take the URL as its " +
                    "second parameter: ${parameterTypes.joinToString()}"
            }
            // The rewrite returns at once, so v0 may be a parameter; the frame has to hold it.
            requireRegisters("Sanitize sharing links", 1)
            // Range form: a parameter of a large frame sits past v15.
            addInstructions(
                0,
                """
                    invoke-static/range {v$urlRegister .. v$urlRegister}, $EXTENSION_CLASS_DESCRIPTOR->rewriteShareUrl(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v0
                    return-object v0
                """,
            )
        }
    }
}
