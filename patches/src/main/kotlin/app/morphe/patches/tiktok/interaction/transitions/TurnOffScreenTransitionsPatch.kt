/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.transitions

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.HostApplicationAttachBaseContextFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.patcher.util.getReference
import app.morphe.util.indexOfFirstInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val SCREEN_TRANSITIONS = "Lapp/morphe/extension/tiktok/interaction/ScreenTransitions;"

/**
 * Where the extension starts following TikTok's screens: just past the host application's call to
 * the framework's attachBaseContext, before any activity exists, so a screen a link opens first is
 * followed too. p0 is the application the callbacks are registered on.
 */
internal fun screenTransitionsInstallIndex(method: Method): Int {
    if (AccessFlags.STATIC.isSet(method.accessFlags)) {
        throw PatchException("Turn off screen transitions: the host application's attachBaseContext is static, so it has no application to follow.")
    }
    val superCall = method.indexOfFirstInstruction {
        getReference<MethodReference>()?.let { reference ->
            reference.definingClass == "Landroid/app/Application;" &&
                reference.name == "attachBaseContext" &&
                reference.parameterTypes == listOf("Landroid/content/Context;")
        } == true
    }
    if (superCall < 0) {
        throw PatchException("Turn off screen transitions: the host application no longer calls Application.attachBaseContext, so there is nowhere to start from.")
    }
    return superCall + 1
}

/**
 * Opens and closes TikTok's screens without their slide. The extension's ScreenTransitions does
 * the work from activity callbacks, so the patch only installs it.
 *
 * In the default selection with its switch off, like Turn off haptics.
 */
@Suppress("unused")
val turnOffScreenTransitionsPatch = bytecodePatch(
    name = "Turn off screen transitions",
    description = "Opens and closes TikTok's screens without the sliding animation, so moving " +
        "around feels quicker. Swipes still follow your finger. Starts off. Turn it on in " +
        "Metra settings > App behavior.",
) {
    category("Interface")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableScreenTransitions()V",
        )
        // A range invoke, so the parameter register's number never has to fit a 4-bit operand.
        HostApplicationAttachBaseContextFingerprint.method.apply {
            addInstruction(
                screenTransitionsInstallIndex(this),
                "invoke-static/range { p0 .. p0 }, $SCREEN_TRANSITIONS->install(Landroid/content/Context;)V",
            )
        }
    }
}
