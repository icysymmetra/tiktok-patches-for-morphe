/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 * Follows hxreborn/hxreborn-tiktok-patches (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patcher.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/inbox/InboxControls;"
private const val ATOMIC_BOOLEAN_DESCRIPTOR = "Ljava/util/concurrent/atomic/AtomicBoolean;"

/**
 * Both containers keep a "collapsed" flag that decides whether the list stops at a few
 * rows behind a View all button. The flag's field is found through the method that logs
 * `expandNotification()` rather than by name, then every write of it is fed from the
 * setting while preserving the native flag when expansion is off.
 */
@Suppress("unused")
val expandActivityListPatch = bytecodePatch(
    name = "Expand activity list",
    description = "Shows the whole Activity and New followers lists in the Inbox, so you " +
        "don't have to tap View all. Starts off. Turn it on in Metra settings > Interface.",
) {
    category("Inbox")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableExpandActivityList()V",
        )

        val collapsedField = with(ExpandNotificationListFingerprint.method) {
            getInstruction<ReferenceInstruction>(indexOfFirstInstructionOrThrow(Opcode.IPUT_BOOLEAN))
                .reference as FieldReference
        }

        // The constructor sets the flag and onCreate sets it again, so both are rewritten.
        listOf(
            NotificationWidgetContainerConstructorFingerprint,
            NotificationWidgetContainerOnCreateFingerprint,
        ).forEach { fingerprint ->
            fingerprint.method.apply {
                val index = indexOfFirstInstructionOrThrow {
                    opcode == Opcode.IPUT_BOOLEAN && getReference<FieldReference>() == collapsedField
                }

                overrideCollapsedFlag(index, getInstruction<TwoRegisterInstruction>(index).registerA)
            }
        }

        // New followers keeps the same state in an AtomicBoolean built in its constructor.
        FollowerWidgetContainerConstructorFingerprint.method.apply {
            val index = indexOfFirstInstructionOrThrow {
                opcode == Opcode.INVOKE_DIRECT &&
                    getReference<MethodReference>()?.let { reference ->
                        reference.definingClass == ATOMIC_BOOLEAN_DESCRIPTOR &&
                            reference.name == "<init>" &&
                            reference.parameterTypes.map(CharSequence::toString) == listOf("Z")
                    } == true
            }

            overrideCollapsedFlag(index, getInstruction<FiveRegisterInstruction>(index).registerD)
        }
    }
}

internal fun MutableMethod.overrideCollapsedFlag(index: Int, flagRegister: Int) {
    addInstructions(
        index,
        """
            invoke-static/range { v$flagRegister .. v$flagRegister }, $EXTENSION_CLASS_DESCRIPTOR->shouldCollapseActivityList(Z)Z
            move-result v$flagRegister
        """,
    )
}
