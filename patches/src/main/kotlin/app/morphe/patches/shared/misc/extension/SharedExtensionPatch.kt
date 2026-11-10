/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/shared/misc/extension/SharedExtensionPatch.kt
 */
package app.morphe.patches.shared.misc.extension

import app.morphe.patcher.Fingerprint
import app.morphe.util.addInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.Method
import java.io.File
import java.net.URI
import java.net.URL
import java.nio.file.Paths
import java.util.jar.JarFile

internal const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/shared/Utils;"

/**
 * A patch to extend with an extension shared with multiple patches.
 *
 * @param extensionName The name of the extension to extend with.
 * @param isYouTubeOrYouTubeMusic Whether the patch targets YouTube and YouTube Music.
 */
fun sharedExtensionPatch(
    extensionName: String,
    isYouTubeOrYouTubeMusic: Boolean,
    vararg hooks: ExtensionHook,
) = bytecodePatch {
    dependsOn(sharedExtensionPatch(isYouTubeOrYouTubeMusic, *hooks))

    extendWith("extensions/$extensionName.mpe")
}

/**
 * A patch to extend with the "shared" extension.
 *
 * @param hooks The hooks to get the application context for use in the extension,
 * commonly for the onCreate method of exported activities.
 */
fun sharedExtensionPatch(
    isYouTubeOrYouTubeMusic: Boolean,
    vararg hooks: ExtensionHook,
) = bytecodePatch {
    val extensionName = if (isYouTubeOrYouTubeMusic) "shared-youtube" else "shared"
    extendWith("extensions/$extensionName.mpe")

    execute {
        // Verify the extension class exists.
        classDefBy(EXTENSION_CLASS_DESCRIPTOR)
    }

    finalize {
        // The hooks are made in finalize to ensure that the context is hooked before any other patches.
        hooks.forEach { hook -> hook(EXTENSION_CLASS_DESCRIPTOR) }

        // Modify Utils method to include the patches release version.
        MorpheUtilsPatchesVersionFingerprint.method.apply {
            /**
             * @return The file path for the jar this classfile is contained inside.
             */
            fun getCurrentJarFilePath(): String {
                val className = object {}::class.java.enclosingClass.name.replace('.', '/') + ".class"
                val classUrl = object {}::class.java.classLoader?.getResource(className)
                return localBundleFile(classUrl).path
            }

            /**
             * @return The value for the manifest entry,
             *         or "Unknown" if the entry does not exist or is blank.
             */
            @Suppress("SameParameterValue")
            fun getPatchesManifestEntry(attributeKey: String) = JarFile(getCurrentJarFilePath()).use { jarFile ->
                jarFile.manifest.mainAttributes.entries.firstOrNull { it.key.toString() == attributeKey }?.value?.toString()
                    ?: "Unknown"
            }

            val manifestValue = getPatchesManifestEntry("Version")
            returnEarly(manifestValue)
        }
    }
}

/** Resolve only a local file URI. No URL connection or JAR handle is opened here. */
internal fun localBundleFile(classUrl: URL?): File {
    val location = classUrl?.toExternalForm()
    if (location == null || !location.startsWith("jar:file:")) {
        throw IllegalStateException("Not running from inside a local JAR file.")
    }
    val separator = location.lastIndexOf('!')
    return Paths.get(URI(location.substring("jar:".length, separator))).toFile()
}

/**
 * Handles passing the application context to the extension code. Typically, the main activity
 * onCreate() method is hooked, but sometimes additional hooks are required if extension code
 * can be reached before the main activity is fully created.
 */
open class ExtensionHook(
    internal val fingerprint: Fingerprint,
    private val insertIndexResolver: BytecodePatchContext.(Method) -> Int = { 0 },
    private val contextRegisterResolver: BytecodePatchContext.(Method) -> String = { "p0" },
    private val postContextClassDescriptor: String? = null,
    private val postContextMethodName: String? = null,
) {
    context(patchContext: BytecodePatchContext)
    operator fun invoke(extensionClassDescriptor: String) {
        fingerprint.clearMatch()
        with(patchContext) { fingerprint.method }.apply {
            val insertIndex = with(patchContext) { insertIndexResolver(this@apply) }
            val contextRegister = with(patchContext) { contextRegisterResolver(this@apply) }

            addInstruction(
                insertIndex,
                "invoke-static/range { $contextRegister .. $contextRegister }, " +
                        "$extensionClassDescriptor->setContext(Landroid/content/Context;)V",
            )
            if (postContextClassDescriptor != null && postContextMethodName != null) {
                addInstruction(
                    insertIndex + 1,
                    "invoke-static/range { $contextRegister .. $contextRegister }, " +
                            "$postContextClassDescriptor->$postContextMethodName(Landroid/content/Context;)V",
                )
            }
        }
    }
}

/**
 * Creates an extension hook from a non-obfuscated activity, which typically is the main activity
 * defined in the app manifest.xml file.
 *
 * @param activityClassType Either the full activity class type such as `Lcom/company/MainActivity;`
 *                          or the 'ends with' string for the activity such as `/MainActivity;`
 * @param targetBundleMethod If the extension should hook `onCreate(Landroid/os/Bundle;)` or `onCreate()`
 */
fun activityOnCreateExtensionHook(activityClassType: String, targetBundleMethod: Boolean = true): ExtensionHook {
    require(activityClassType.endsWith(';')) {
        "Class type must end with a semicolon: $activityClassType"
    }

    val fingerprint = Fingerprint(
        definingClass = activityClassType,
        name = "onCreate",
        returnType = "V",
        parameters = if (targetBundleMethod) {
            listOf("Landroid/os/Bundle;")
        } else {
            listOf()
        }
    )

    return ExtensionHook(fingerprint)
}
