/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.refreshrate

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.util.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/misc/RefreshRate;"
private const val LAYOUT_PARAMS = "Landroid/view/WindowManager\$LayoutParams;"

private fun writesRefreshRate(instruction: Instruction) =
    instruction.opcode == Opcode.IPUT &&
        instruction.getReference<FieldReference>()?.let { reference ->
            reference.definingClass == LAYOUT_PARAMS && reference.name == "preferredRefreshRate"
        } == true

/**
 * Anything that asks the window for a refresh rate. The field is Android's, so the write is
 * named in full whatever TikTok called the method around it, and taking every one of them
 * means the switch does not depend on which surface asked. On 46.2.3 the feed's is in
 * PlayerController, where the rate it asks for is the frame rate of the video that just
 * started, which is what drops a 120 Hz screen to the video's 30.
 */
internal object RefreshRateWriteFingerprint : Fingerprint(
    // The filter repeats a reference the custom block requires, so the patcher reads only the
    // classes that make it (#54).
    filters = listOf(fieldAccess(definingClass = LAYOUT_PARAMS, name = "preferredRefreshRate", opcode = Opcode.IPUT)),
    custom = { method, _ ->
        method.implementation?.instructions?.any(::writesRefreshRate) == true
    },
)

@Suppress("unused")
val refreshRatePatch = bytecodePatch(
    name = "Keep the screen's refresh rate",
    description = "Stops TikTok slowing your screen down to the video's frame rate, so scrolling stays smooth on 90 or 120 Hz phones. Starts off. Turn it on in Metra settings > App behavior.",
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        var patched = 0
        RefreshRateWriteFingerprint.matchAll().forEach { match ->
            val method = match.method
            val implementation = method.implementation ?: return@forEach

            // Later writes first, because replacing one does not move the others but reading
            // them fresh each time would.
            val writes = implementation.instructions.withIndex()
                .filter { writesRefreshRate(it.value) }
                .map { it.index }
                .toList()

            writes.asReversed().forEach { index ->
                val store = method.implementation!!.instructions.elementAt(index)
                    as TwoRegisterInstruction
                // The store is replaced rather than its value rewritten. PlayerController puts
                // the same register into its own lastRefreshRate on the next line, and taking
                // the value away from it there would leave TikTok's bookkeeping holding zero.
                method.replaceInstruction(
                    index,
                    "invoke-static { v${store.registerB}, v${store.registerA} }, " +
                        "$EXTENSION->apply(${LAYOUT_PARAMS}F)V",
                )
                patched++
            }
        }

        if (patched == 0) throw PatchException("Keep the screen's refresh rate: nothing asks for one.")

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableRefreshRate()V",
        )
    }
}
