/*
 * Thanks to lyyako for the original implementation and help with this patch.
 *
 * Originally adapted for TikTok 43.8.3; ported to TikTok 46.2.3:
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.misc.externalbrowser

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import com.android.tools.smali.dexlib2.Opcode

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/externalbrowser/ExternalBrowserPatch;"

@Suppress("unused")
val openExternalLinksPatch = bytecodePatch(
    name = "Open external links directly",
    description = "Opens profile and story website links in the system browser instead of TikTok's in-app browser. Thanks to lyyako for the original implementation.",
    default = true,
) {
    dependsOn(sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktok4714())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableExternalBrowser()V",
        )

        SparkThirdRouterOpenFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range {p0 .. p1}, $EXTENSION_CLASS_DESCRIPTOR->openSparkThirdContext(Landroid/content/Context;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :external_browser_spark_router_original
                return-void
            """,
            ExternalLabel(
                "external_browser_spark_router_original",
                SparkThirdRouterOpenFingerprint.method.getInstruction(0),
            ),
        )

        StoryLinkSheetFingerprint.method.addInstructionsWithLabels(
            0,
            """
                invoke-static/range {p0 .. p1}, $EXTENSION_CLASS_DESCRIPTOR->openStoryLink(Ljava/lang/Object;Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :external_browser_story_original
                return-void
            """,
            ExternalLabel(
                "external_browser_story_original",
                StoryLinkSheetFingerprint.method.getInstruction(0),
            ),
        )

        val superOnCreateIndex = SparkActivityOnCreateFingerprint.method.implementation!!.instructions
            .indexOfFirst { it.opcode == Opcode.INVOKE_SUPER }
        check(superOnCreateIndex >= 0) {
            "Could not find SparkActivity super.onCreate call"
        }
        SparkActivityOnCreateFingerprint.method.addInstructionsWithLabels(
            superOnCreateIndex + 1,
            """
                invoke-static/range {p0 .. p0}, $EXTENSION_CLASS_DESCRIPTOR->openSparkActivity(Landroid/app/Activity;)Z
                move-result v0
                if-eqz v0, :external_browser_spark_activity_original
                return-void
            """,
            ExternalLabel(
                "external_browser_spark_activity_original",
                SparkActivityOnCreateFingerprint.method.getInstruction(superOnCreateIndex + 1),
            ),
        )
    }
}
