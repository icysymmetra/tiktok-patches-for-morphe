/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.settings

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.getReference
import app.morphe.util.implementationOrPatchException
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val LAUNCH_CRASH_CLEANUP_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/settings/LaunchCrashCleanup;"
private const val CONTRACT = "Settings: TikTok's launch-crash keep-list"

/**
 * One of TikTok's own files on the keep-list on every build read (46.2.3 to 47.0.3); the array
 * holding it is the one to extend. 47.0.3 appended its language preferences
 * (`key_language_sp_key`) to the same list, which 46.x's doesn't name, so that name can't be the
 * anchor: it stopped the Settings patch, and all 73 patches that depend on it, on every forced
 * 46.x build.
 */
private const val KEPT_LAUNCH_CRASH_PREFERENCES = "launch_crash_intercept_sp"

/**
 * The static initialiser that builds the keep-list of TikTok's launch-crash cleanup.
 *
 * <p>On the third launch crash in a row, TikTok deletes every file in `shared_prefs` whose name
 * isn't on this list (`X.03JS` on 47.0.3, `X.0CpP` on 46.2.3). Hushfeed's settings weren't on
 * it, so a crash loop erased all of them one start before Hushfeed's safe mode could pause
 * anything. TikTok's SafeMode dialog runs the same deletion when its clean button is pressed.
 */
internal object LaunchCrashKeepListFingerprint : Fingerprint(
    name = "<clinit>",
    returnType = "V",
    parameters = emptyList(),
    strings = listOf(KEPT_LAUNCH_CRASH_PREFERENCES, "safe_mode_config_sp").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * Adds Hushfeed's preferences files to the keep-list [clinit] builds.
 *
 * <p>The list is the first `String[]` stored after TikTok's own launch-crash preferences are
 * named, and it has to be the one a method of the same class reads while walking `shared_prefs`.
 * That is checked before anything is written, so a build where the arrays moved refuses instead
 * of extending a list nothing reads.
 */
internal fun keepHushfeedPreferenceFiles(clinit: MutableMethod, ownerMethods: Iterable<Method>) {
    val instructions = clinit.implementationOrPatchException(CONTRACT).instructions.toList()
    val nameIndex = instructions.indexOfFirst {
        it.getReference<StringReference>()?.string == KEPT_LAUNCH_CRASH_PREFERENCES
    }
    if (nameIndex < 0) throw PatchException("$CONTRACT: the list no longer names $KEPT_LAUNCH_CRASH_PREFERENCES.")
    val storeIndex = (nameIndex until instructions.size).firstOrNull { index ->
        instructions[index].opcode == Opcode.SPUT_OBJECT &&
            instructions[index].getReference<FieldReference>()?.type == "[Ljava/lang/String;"
    } ?: throw PatchException("$CONTRACT: the list is never stored.")
    val keepList = instructions[storeIndex].getReference<FieldReference>()!!

    val readWhileDeleting = ownerMethods.any { method ->
        val body = method.implementation?.instructions ?: return@any false
        body.any { it.getReference<StringReference>()?.string == "shared_prefs" } &&
            body.any { it.opcode == Opcode.SGET_OBJECT && it.getReference<FieldReference>() == keepList }
    }
    if (!readWhileDeleting) {
        throw PatchException("$CONTRACT: nothing that walks shared_prefs reads the list stored by the initialiser.")
    }

    val register = (instructions[storeIndex] as OneRegisterInstruction).registerA
    clinit.addInstructionsAtControlFlowLabel(
        storeIndex,
        """
            invoke-static/range { v$register .. v$register }, $LAUNCH_CRASH_CLEANUP_CLASS_DESCRIPTOR->keepHushfeedFiles([Ljava/lang/String;)[Ljava/lang/String;
            move-result-object v$register
        """,
    )
}
