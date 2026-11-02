/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.addInstruction
import app.morphe.util.classesCalling
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/inbox/SuggestedVideoPushBlock;"
private const val NOTIFICATION_MANAGER = "Landroid/app/NotificationManager;"
internal const val PUSH_HANDLER = "Lcom/ss/android/ugc/awemepushlib/manager/MessageShowHandler;"
private val NOTIFY_PARAMETERS = setOf(
    listOf("I", "Landroid/app/Notification;"),
    listOf("Ljava/lang/String;", "I", "Landroid/app/Notification;"),
)

/** A call handing Android a notification to post: NotificationManager.notify, with or without a tag. */
internal fun isNotifyCall(instruction: Instruction): Boolean {
    if (instruction.opcode != Opcode.INVOKE_VIRTUAL && instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE) return false
    val reference = instruction.getReference<MethodReference>() ?: return false
    return reference.definingClass == NOTIFICATION_MANAGER && reference.name == "notify" &&
        reference.returnType == "V" && reference.parameterTypes.map(CharSequence::toString) in NOTIFY_PARAMETERS
}

/**
 * A call that posts a notification, as TikTok wrote it or once this patch has routed it through its
 * filter. A patch that finds a method by its notify call uses this, so it still finds the method
 * whichever of the two patches runs first.
 */
internal fun MethodReference.postsNotification(): Boolean =
    name == "notify" && (definingClass == NOTIFICATION_MANAGER || definingClass == EXTENSION)

/** The same call made through the filter: the manager becomes its first argument and every register stays put. */
internal fun notifyThroughFilter(instruction: Instruction): String {
    val reference = instruction.getReference<MethodReference>()!!
    val target = "$EXTENSION->notify($NOTIFICATION_MANAGER${reference.parameterTypes.joinToString("")})V"
    return when (instruction) {
        is FiveRegisterInstruction -> {
            val registers = listOf(
                instruction.registerC, instruction.registerD, instruction.registerE,
                instruction.registerF, instruction.registerG,
            ).take(instruction.registerCount)
            "invoke-static {${registers.joinToString { "v$it" }}}, $target"
        }
        is RegisterRangeInstruction -> {
            val last = instruction.startRegister + instruction.registerCount - 1
            "invoke-static/range {v${instruction.startRegister} .. v$last}, $target"
        }
        else -> throw PatchException("Block suggested video notifications: unexpected notify call ${instruction.opcode}.")
    }
}

/** Every TikTok method that posts a notification. Hushfeed's own code is left out, the filter included. */
internal fun notifyingMethods(classes: Iterable<ClassDef>): List<Pair<ClassDef, Method>> =
    classes.filterNot { it.type.startsWith("Lapp/morphe/") }.flatMap { classDef ->
        classDef.methods.filter { method -> method.implementation?.instructions?.any(::isNotifyCall) == true }
            .map { classDef to it }
    }

internal fun MutableMethod.postThroughSuggestedVideoFilter() {
    implementation!!.instructions.withIndex().filter { isNotifyCall(it.value) }.map { it.index }.forEach { index ->
        // Replaced in place, so a branch to the call still lands on it.
        replaceInstruction(index, notifyThroughFilter(getInstruction(index)))
    }
}

/**
 * Routes every call in TikTok that hands Android a notification through the extension's one
 * filter. TikTok builds notifications along several routes (its push handler, a lambda the
 * handler posts from, local pushes, the androidx compat layer), so the filter stands in for
 * NotificationManager.notify itself.
 *
 * <p>No name, so it's never picked on its own. Block suggested video notifications and
 * Notification controls both drop notifications in that filter, and each one's check only counts
 * in a build carrying its own patch, so either can bring the routing in without the other.
 */
internal val notificationFilterPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // Only the classes the patcher's type index says call NotificationManager are read. On
        // 47.1.4 a full walk is about 40 million instructions for 22 calls in 66 classes (#54).
        val callers = classesCalling(listOf(NOTIFICATION_MANAGER))
        val found = mutableListOf<ClassDef>()
        classDefForEach { if (it.type in callers) found += it }
        val targets = notifyingMethods(found)
        if (targets.none { (classDef, _) -> classDef.type == PUSH_HANDLER }) {
            throw PatchException("Notification filter: TikTok's push handler no longer posts a notification.")
        }
        targets.forEach { (classDef, method) ->
            mutableClassDefBy(classDef).findMutableMethodOf(method).postThroughSuggestedVideoFilter()
        }
    }
}

/**
 * Blocks TikTok's "Videos you might like" pushes (the S25 got "25M+ people viewed: ..." on
 * 2026-10-02). The filter drops only that channel and posts everything else as TikTok asked.
 */
@Suppress("unused")
val suggestedVideoPushBlockPatch = bytecodePatch(
    name = "Block suggested video notifications",
    description = "Stops TikTok's \"Videos you might like\" notifications about popular videos " +
        "it picked for you. Messages, comments, likes and posts from people you follow still come " +
        "through. On by default. Turn it off in Metra settings > Interface.",
    default = true,
) {
    category("Inbox")
    dependsOn(settingsPatch, sharedExtensionPatch, notificationFilterPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableSuggestedVideoPushBlock()V",
        )
    }
}
