package app.morphe.patches.tiktok.interaction.antirecording

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.returnEarly
import org.w3c.dom.Element

@Suppress("unused")
val antiRecordingPatch = resourcePatch(
    name = "Disable screen capture detection",
    description = "Disables screen recording and screenshot detection. (Supports TikTok 43.8.3.)",
    default = true,
) {
    compatibleWith(*AppCompatibilities.tiktok4383())

    dependsOn(
        bytecodePatch {
            execute {
                listOf(
                    antiRecordingAddedFingerprint,
                    antiRecordingRemovedFingerprint,
                ).forEach { fingerprint ->
                    fingerprint.methodOrNull?.returnEarly()
                }
            }
        },
    )

    finalize {
        document("AndroidManifest.xml").use { document ->
            document.documentElement.removeElementsByAndroidName("uses-permission", "android.permission.DETECT_SCREEN_CAPTURE")
            document.documentElement.removeElementsByAndroidName("permission", "android.permission.DETECT_SCREEN_CAPTURE")
        }
    }
}

private fun Element.removeElementsByAndroidName(tagName: String, value: String) {
    buildList {
        val nodes = getElementsByTagName(tagName)
        for (index in 0 until nodes.length) {
            (nodes.item(index) as? Element)
                ?.takeIf { it.getAttribute("android:name") == value }
                ?.let(::add)
        }
    }.forEach { element ->
        element.parentNode?.removeChild(element)
    }
}
