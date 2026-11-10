/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 *
 * Thanks to lyyako for the original implementation and help with this patch.
 */
package app.morphe.patches.tiktok.interaction.antirecording

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.anyInstruction
import app.morphe.patcher.methodCall
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private data class ScreenCaptureCallSite(
    val classDef: ClassDef,
    val method: Method,
    val instructionIndexes: List<Int>,
)

private object ScreenCaptureCallbackFingerprint : Fingerprint(
    filters = listOf(
        anyInstruction(
            methodCall(definingClass = "Landroid/app/Activity;", name = "registerScreenCaptureCallback"),
            methodCall(definingClass = "Landroid/app/Activity;", name = "unregisterScreenCaptureCallback"),
        ),
    ),
)

/**
 * Bytecode only. 46.2.3 does declare android.permission.DETECT_SCREEN_CAPTURE, checked against
 * the vendor APK on 2026-09-08 with aapt2 dump xmltree; an earlier note here said it did not,
 * because the string is UTF-16 in the binary manifest and an ASCII search finds nothing.
 *
 * Leaving the permission in place is still right. Every call to
 * Activity.registerScreenCaptureCallback in 46.2.3 is typed on Activity itself, 11 of them
 * across four dex files with 11 matching unregister calls, and the sweep below replaces all of
 * them, so nothing ever registers a callback and the declared permission does nothing. Taking it
 * out would make the patcher decode the whole resource table, which is what put patching over
 * the memory Morphe Manager allows by default: measured 2026-09-06, this patch alone needed
 * 768 MB with it and 512 MB without.
 *
 * The sweep matches only receivers typed as Activity. A build that called the API through a
 * subclass reference would slip past it, and then the permission would matter, so check that
 * before widening compatibility past 46.2.3.
 */
@Suppress("unused")
val antiRecordingPatch = bytecodePatch(
    name = "Disable screen capture detection",
    description = "Stops TikTok from noticing and reacting when you take a screenshot or " +
        "record the screen. Works as soon as you patch it in, with no switch.",
    default = true,
) {
    category("Downloads")
    // Only so Build details can list it: the patch itself runs no extension code.
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        var returnedEarly = 0
        listOf(
            antiRecordingAddedFingerprint,
            antiRecordingRemovedFingerprint,
        ).forEach { fingerprint ->
            fingerprint.methodOrNull?.let {
                it.returnEarly()
                returnedEarly++
            }
        }

        val callSites = ScreenCaptureCallbackFingerprint.matchAllOrNull().orEmpty().mapNotNull { match ->
                val indexes = match.originalMethod.implementation?.instructions
                    ?.mapIndexedNotNull { index, instruction ->
                        val reference = (instruction as? ReferenceInstruction)
                            ?.reference as? MethodReference
                            ?: return@mapIndexedNotNull null
                        if (reference.definingClass != "Landroid/app/Activity;") {
                            return@mapIndexedNotNull null
                        }
                        if (
                            reference.name != "registerScreenCaptureCallback" &&
                            reference.name != "unregisterScreenCaptureCallback"
                        ) {
                            return@mapIndexedNotNull null
                        }
                        index
                    }
                    .orEmpty()
                if (indexes.isEmpty()) null
                else ScreenCaptureCallSite(match.originalClassDef, match.originalMethod, indexes)
        }

        // Both anchors are optional and the sweep is allowed to find nothing, so with neither
        // resolving the patch reported Applied and left screen capture detection exactly as it
        // was. That is the case the header warns about: a build calling the API through a
        // subclass reference, or declaring the permission.
        if (returnedEarly == 0 && callSites.isEmpty()) {
            throw PatchException(
                "Screen capture detection was not found. Neither " +
                    "antiRecordingAddedFingerprint nor antiRecordingRemovedFingerprint resolved, " +
                    "and no call to Activity.registerScreenCaptureCallback or " +
                    "unregisterScreenCaptureCallback was reached. This build likely calls it " +
                    "through a subclass reference or declares DETECT_SCREEN_CAPTURE, either of " +
                    "which needs the manifest cleanup described above.",
            )
        }

        callSites.forEach { callSite ->
            val mutableMethod = mutableClassDefBy(callSite.classDef)
                .findMutableMethodOf(callSite.method)
            callSite.instructionIndexes.forEach { index ->
                mutableMethod.replaceInstruction(index, "nop")
            }
        }
        registerForBuildDetails("enableNoScreenCaptureDetection")
    }
}
