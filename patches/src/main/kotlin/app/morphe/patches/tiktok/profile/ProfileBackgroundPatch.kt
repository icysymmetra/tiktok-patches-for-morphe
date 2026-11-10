/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Which gate to answer comes from onlynazril's TikTok patches (GPL-3.0), which force the same
 * one. Hushfeed puts a switch in front of it and leaves the rest of the experiment alone.
 */
package app.morphe.patches.tiktok.profile

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Profile background"
internal const val PROFILE_BACKGROUND_SWITCH =
    "Lapp/morphe/extension/tiktok/profile/ProfileBackground;->openGate(Z)Z"

/**
 * The AB keys of TikTok's profile background experiment (31744) its gate's holder reads: the
 * allow list the server sets per account, and the consumption group earlier builds read there.
 */
internal val PROFILE_BACKGROUND_KEYS = setOf("profile_bg_in_allow_list", "profile_bg_enable_consumption_group")

/**
 * Whether [classDef] holds TikTok's profile background gate: it reads one of
 * [PROFILE_BACKGROUND_KEYS] and has a `(boolean) -> boolean` method next to a `(boolean) -> void`
 * one, the account-switch setter. On 47.1.4 that is `X.0OSK`, whose `LIZ()` snapshots
 * profile_bg_in_allow_list per signed-in account. The AB defaults registry reads the same keys
 * with nothing but void methods, and the class that only lists them in its static initializer
 * has no boolean method at all. Shared with ProfileBackgroundAnchorsTest, so the test holds the
 * patch's own rule to each declared build.
 */
internal fun ClassDef.isProfileBackgroundGateHolder(): Boolean {
    if (type.startsWith("Lapp/morphe/extension/")) return false
    if (methods.none { it.isProfileBackgroundGate() }) return false
    if (methods.none { it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("Z") }) {
        return false
    }
    return methods.any { method ->
        method.implementation?.instructions?.any {
            it.getReference<StringReference>()?.string in PROFILE_BACKGROUND_KEYS
        } == true
    }
}

/**
 * The gate itself (`LIZJ` on 47.1.4, `LIZIZ` up to 47.0.3): handed true for the editor, which
 * Edit profile asks before it builds the background editor, and false for showing a background on
 * a profile. With true TikTok wants the account's allow-list value to be 1 and the left-aligned
 * avatar layout on; with false it wants that layout and either the allow list or a consumption
 * group other than 1.
 */
internal fun Method.isProfileBackgroundGate(): Boolean =
    returnType == "Z" && parameterTypes.map(CharSequence::toString) == listOf("Z") && implementation != null

/**
 * The left-aligned avatar layout check the gate asks on both branches (`X.0ClT.LIZLLL()` on
 * 47.1.4): the one static no-argument boolean it calls, or null when there isn't exactly one.
 * A background drawn without that layout lands in the wrong place, so the switch stands in for
 * the allow list only and still asks this.
 */
internal fun Method.profileBackgroundLayoutCheck(): String? =
    implementation?.instructions
        ?.filter { it.opcode == Opcode.INVOKE_STATIC || it.opcode == Opcode.INVOKE_STATIC_RANGE }
        ?.mapNotNull { it.getReference<MethodReference>() }
        ?.filter { it.returnType == "Z" && it.parameterTypes.isEmpty() }
        ?.map { "${it.definingClass}->${it.name}()Z" }
        ?.distinct()
        ?.singleOrNull()

internal const val PROFILE_BACKGROUND_ALLOW_LIST =
    "Lapp/morphe/extension/tiktok/profile/ProfileBackground;->allowList(I)I"

/**
 * Where the gate's holder reads the account's profile_bg_in_allow_list value from TikTok's AB
 * store (`LIZ()` on 47.1.4, which snapshots it per account): the method and the index of the
 * move-result that takes the value, or null unless exactly one method names the key and an int
 * read follows it straight into a move-result. TikTok's own profile and other profiles' requests
 * (getSelfUserInfo, getOtherUserInfo) send that snapshot, and the server leaves the background out
 * of a profile asked for with 0, so the gate alone opened the editor but lost the saved
 * background on the next start (#80).
 */
internal fun ClassDef.profileBackgroundAllowListRead(): Pair<Method, Int>? {
    val readers = methods.filter { method ->
        method.implementation?.instructions?.any { it.getReference<StringReference>()?.string == ALLOW_LIST_KEY } == true
    }
    val method = readers.singleOrNull() ?: return null
    val instructions = method.implementation!!.instructions.toList()
    val key = instructions.indexOfFirst { it.getReference<StringReference>()?.string == ALLOW_LIST_KEY }
    val read = (key + 1 until instructions.size).firstOrNull {
        instructions[it].getReference<MethodReference>()?.returnType == "I"
    } ?: return null
    val moveResult = read + 1
    if (instructions.getOrNull(moveResult)?.opcode != Opcode.MOVE_RESULT) return null
    return method to moveResult
}

private const val ALLOW_LIST_KEY = "profile_bg_in_allow_list"

@Suppress("unused")
val profileBackgroundPatch = bytecodePatch(
    // A literal, not PATCH: the README and default-selection tests find patches by reading it.
    name = "Profile background",
    description = "Opens TikTok's own profile background editor in Edit profile on accounts TikTok " +
        "hasn't offered it to yet, and shows the backgrounds other profiles have set. TikTok's " +
        "servers may still refuse to save one for such an account. Starts off. Turn it on in " +
        "Metra settings > App behavior.",
    default = true,
) {
    category("Interaction")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        val holders = mutableListOf<ClassDef>()
        classDefForEach { classDef -> if (classDef.isProfileBackgroundGateHolder()) holders += classDef }
        val holder = holders.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one class holding the profile background gate (reading " +
                "${PROFILE_BACKGROUND_KEYS.joinToString()}, with a (boolean) -> boolean gate and a " +
                "(boolean) -> void setter), found ${holders.map { it.type }}",
        )
        // Two gates would mean the shape no longer picks this one out, and answering the wrong
        // one is a failure rather than a coin toss.
        val gates = holder.methods.filter { it.isProfileBackgroundGate() }
        val gate = gates.singleOrNull() ?: throw PatchException(
            "$PATCH: expected one (boolean) -> boolean gate in ${holder.type}, found ${gates.map { it.name }}",
        )
        // The switch is handed the gate's argument, so the export can tell the editor from a
        // profile. The no-argument boolean beside it on 47.1.4 is a status bar fix of the same
        // experiment that defaults on, not the gate, and stays as TikTok has it.
        val argument = if (AccessFlags.STATIC.isSet(gate.accessFlags)) "p0" else "p1"
        val layoutCheck = gate.profileBackgroundLayoutCheck() ?: throw PatchException(
            "$PATCH: ${holder.type}->${gate.name} doesn't call exactly one static () -> boolean " +
                "layout check, so the switch can't keep TikTok's avatar layout condition.",
        )
        val (reader, moveResult) = holder.profileBackgroundAllowListRead() ?: throw PatchException(
            "$PATCH: ${holder.type} has no single method reading $ALLOW_LIST_KEY into a move-result, " +
                "so a saved background would be left out of the profile requests.",
        )
        val mutableHolder = mutableClassDefBy(holder)
        mutableHolder.findMutableMethodOf(gate).guardAtEntry(
            PATCH,
            "invoke-static/range { $argument .. $argument }, $PROFILE_BACKGROUND_SWITCH",
            """
                invoke-static {}, $layoutCheck
                move-result v0
                return v0
            """,
        )
        // The value the profile requests send, so the server returns a background saved on
        // the account the switch opened the editor for.
        val value = (reader.implementation!!.instructions.elementAt(moveResult) as OneRegisterInstruction).registerA
        mutableHolder.findMutableMethodOf(reader).addInstructions(
            moveResult + 1,
            """
                invoke-static/range { v$value .. v$value }, $PROFILE_BACKGROUND_ALLOW_LIST
                move-result v$value
            """,
        )

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableProfileBackground()V",
        )
    }
}
