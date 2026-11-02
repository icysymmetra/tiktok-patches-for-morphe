/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.pip

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.sessionPlaybackBridgePatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.patcher.util.findMutableMethodOf
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val EXTENSION = "Lapp/morphe/extension/tiktok/playback/PictureInPicture;"

/** The feed, and the pager a video opened from a profile, search or a sound plays in. */
internal val PICTURE_IN_PICTURE_WINDOWS = listOf(
    "Lcom/ss/android/ugc/aweme/main/MainActivity;",
    "Lcom/ss/android/ugc/aweme/detail/ui/DetailActivity;",
)

internal fun Method.isUserLeaveHint() =
    name == "onUserLeaveHint" && parameterTypes.isEmpty() && returnType == "V"

internal fun Method.isPictureInPictureModeChange() =
    name == "onPictureInPictureModeChanged" && returnType == "V" &&
        parameterTypes.map(CharSequence::toString) == listOf("Z", "Landroid/content/res/Configuration;")

/**
 * The first class from [start] up that declares an instance method [matches] with code, which is
 * the one Android runs for that window: TikTok's renamed base activity for onUserLeaveHint
 * (X.03s4 on 47.0.3, X.03uF on 47.1.3, X.03uJ on 47.1.4) and androidx's ComponentActivity for the
 * window change. Null when the walk leaves the app's own classes first.
 */
internal fun firstDeclaration(
    start: String,
    classDefByOrNull: (String) -> ClassDef?,
    matches: (Method) -> Boolean,
): Method? {
    val seen = mutableSetOf<String>()
    var type: String? = start
    while (type != null && seen.add(type)) {
        val classDef = classDefByOrNull(type) ?: return null
        classDef.methods.firstOrNull {
            it.implementation != null && !AccessFlags.STATIC.isSet(it.accessFlags) && matches(it)
        }?.let { return it }
        type = classDef.superclass
    }
    return null
}

/** Where each hook goes: one method per declaration, however many windows share it. */
internal data class PictureInPictureHooks(val leaveHints: List<Method>, val modeChanges: List<Method>)

internal fun pictureInPictureHooks(classDefByOrNull: (String) -> ClassDef?): PictureInPictureHooks {
    fun declarations(what: String, matches: (Method) -> Boolean) = PICTURE_IN_PICTURE_WINDOWS.map { window ->
        firstDeclaration(window, classDefByOrNull, matches)
            ?: throw PatchException("Picture-in-picture: nothing between $window and Activity declares $what")
    }.distinctBy { it.definingClass }
    return PictureInPictureHooks(
        declarations("onUserLeaveHint()", Method::isUserLeaveHint),
        declarations("onPictureInPictureModeChanged(boolean, Configuration)", Method::isPictureInPictureModeChange),
    )
}

/** Leaving hands the window to the extension first, while the player still reports it playing. */
internal fun MutableMethod.handLeaveHintToPictureInPicture() = addInstruction(
    0,
    "invoke-static/range { p0 .. p0 }, $EXTENSION->onUserLeaveHint(Landroid/app/Activity;)V",
)

/** The window opening or closing reaches the extension before TikTok's own listeners. */
internal fun MutableMethod.handWindowChangeToPictureInPicture() = addInstruction(
    0,
    "invoke-static/range { p0 .. p1 }, $EXTENSION->onModeChanged(Landroid/app/Activity;Z)V",
)

/**
 * Lets both windows open a picture-in-picture window. The feed activity already declares it for
 * TikTok's LIVE window on every supported build; the detail pager doesn't. Both already take size
 * changes themselves (configChanges covers screen size, smallest width and layout), so shrinking
 * into the window doesn't rebuild either.
 */
internal fun allowPictureInPicture(xml: Document) {
    val manifest = xml.documentElement
    val packageName = manifest.getAttribute("package")
    val wanted = PICTURE_IN_PICTURE_WINDOWS.map { it.removePrefix("L").removeSuffix(";").replace('/', '.') }.toSet()
    val found = mutableSetOf<String>()
    val activities = xml.getElementsByTagName("activity")
    for (index in 0 until activities.length) {
        val activity = activities.item(index) as Element
        val declared = activity.getAttribute("android:name")
        val name = if (declared.startsWith(".")) packageName + declared else declared
        if (name !in wanted) continue
        activity.setAttribute("android:supportsPictureInPicture", "true")
        found += name
    }
    val missing = wanted - found
    if (missing.isNotEmpty()) throw PatchException("Picture-in-picture: the manifest doesn't declare $missing")
}

private val pictureInPictureManifestPatch = resourcePatch {
    execute {
        document("AndroidManifest.xml").use { xml -> allowPictureInPicture(xml) }
    }
}

@Suppress("unused")
val pictureInPicturePatch = bytecodePatch(
    name = "Picture-in-picture",
    description = "Keeps the video playing in a small window when you leave TikTok, so you can keep watching while you use other apps. Starts off. Turn it on in Metra settings > App behavior.",
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch, sessionPlaybackBridgePatch, pictureInPictureManifestPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val hooks = pictureInPictureHooks { classDefByOrNull(it) }
        // Both run first, before TikTok's own handling: leaving decides whether to open the
        // window while the player still reports the video playing, and the window change only
        // acts for a window the extension opened, so TikTok's LIVE window stays TikTok's.
        hooks.leaveHints.forEach { method ->
            mutableClassDefBy(method.definingClass).findMutableMethodOf(method).handLeaveHintToPictureInPicture()
        }
        hooks.modeChanges.forEach { method ->
            mutableClassDefBy(method.definingClass).findMutableMethodOf(method).handWindowChangeToPictureInPicture()
        }
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enablePictureInPicture()V",
        )
    }
}
