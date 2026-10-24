/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.popups

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val WIND_DOWN_SCREENS = "Lapp/morphe/extension/tiktok/popups/WindDownScreens;"
internal const val SLEEP_HOUR_TRIGGER =
    "Lcom/ss/android/ugc/aweme/compliance/api/services/digitalwellbeing/ISleepHourComponentTrigger;"
private const val TIMELOCK_ASSEM = "Lcom/ss/android/ugc/aweme/compliance/protection/timelock/ui/assem/"
internal const val SLEEP_HOUR_SLOT = "${TIMELOCK_ASSEM}SleepHourComponent;"

/**
 * The takeovers the feed's sleep-hour slot puts over a video, by the names they keep on 47.0.3,
 * 47.1.3 and 47.1.4: the server-driven sleep-hours overlay (a Lynx page built from the bedtime the
 * compliance settings send), the breathing exercise, and the daily screen-time limit.
 */
internal val WIND_DOWN_TRIGGERS = listOf(
    "${TIMELOCK_ASSEM}STMLynxOverlayTrigger;",
    "${TIMELOCK_ASSEM}STMMeditationTrigger;",
    "${TIMELOCK_ASSEM}STMDailyScreenTimeTrigger;",
)

/** Each trigger's "has something to show" check, under the one name the interface gives it. */
internal class WindDownSites(val check: String, val triggers: List<Method>)

/**
 * The name of the trigger interface's "has something to show" check. The interface declares two
 * no-argument booleans and both are renamed on every build. The slot asks this one of every trigger
 * as its view is built and keeps only the ones that say yes, and its view model asks it again before
 * it prepares one or switches to it. The other is "show it now", which only the slot's feed
 * callbacks ask, after this one said yes.
 */
internal fun windDownCheckName(classBy: (String) -> ClassDef?): String {
    val trigger = classBy(SLEEP_HOUR_TRIGGER)
        ?: throw PatchException("Block popups: there is no sleep-hour trigger interface.")
    val checks = trigger.methods.filter { it.parameterTypes.isEmpty() && it.returnType == "Z" }.map { it.name }.toSet()
    if (checks.size != 2) {
        throw PatchException("Block popups: the sleep-hour trigger has ${checks.size} yes-or-no checks, not 2.")
    }
    val slot = classBy(SLEEP_HOUR_SLOT) ?: throw PatchException("Block popups: there is no sleep-hour slot.")
    val built = slot.methods.singleOrNull {
        it.name == "onViewCreated" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;")
    } ?: throw PatchException("Block popups: the sleep-hour slot has no onViewCreated(View).")
    val asked = built.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
        instruction.getReference<MethodReference>()?.takeIf {
            it.definingClass == SLEEP_HOUR_TRIGGER && it.name in checks && it.parameterTypes.isEmpty()
        }?.name
    }.toSet()
    return asked.singleOrNull() ?: throw PatchException(
        "Block popups: the sleep-hour slot asks its triggers ${asked.size} of their checks as it's built, not 1.",
    )
}

/**
 * Every wind-down trigger's own "has something to show" check, with its registers checked before
 * anything is written: the hook hands p0 and each returned register to a plain invoke, so all of
 * them have to fit its four bits.
 */
internal fun windDownSites(classBy: (String) -> ClassDef?): WindDownSites {
    val check = windDownCheckName(classBy)
    val triggers = WIND_DOWN_TRIGGERS.map { type ->
        val trigger = classBy(type) ?: throw PatchException("Block popups: there is no $type.")
        if (SLEEP_HOUR_TRIGGER !in trigger.interfaces) {
            throw PatchException("Block popups: $type is no longer a sleep-hour trigger.")
        }
        val method = trigger.methods.singleOrNull {
            it.name == check && it.parameterTypes.isEmpty() && it.returnType == "Z" &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: throw PatchException("Block popups: $type has no $check()Z of its own.")
        val code = method.implementation ?: throw PatchException("Block popups: $type->$check has no code.")
        val returns = returnRegisters(method)
        if (returns.isEmpty()) throw PatchException("Block popups: $type->$check never returns.")
        val self = code.registerCount - 1
        if (self > 15 || returns.any { (_, register) -> register > 15 }) {
            throw PatchException("Block popups: $type->$check works in registers too high for the hook.")
        }
        // The hook hands p0 over as the trigger at each return, so nothing before may reuse it.
        if (code.instructions.any { it.writes(self) }) {
            throw PatchException("Block popups: $type->$check reuses p0, so it isn't the trigger at its returns.")
        }
        method
    }
    return WindDownSites(check, triggers)
}

/** Whether this instruction writes [register], either half of a wide write included. */
internal fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister() || this !is OneRegisterInstruction) return false
    return registerA == register || (opcode.setsWideRegister() && registerA + 1 == register)
}

/** Each return in [method] with the register it returns, in code order. */
internal fun returnRegisters(method: Method): List<Pair<Int, Int>> =
    method.implementation?.instructions?.toList().orEmpty().withIndex()
        .filter { it.value.opcode == Opcode.RETURN }
        .map { (index, instruction) -> index to (instruction as OneRegisterInstruction).registerA }

/**
 * Hands each answer the check gives to the extension right before it returns, so a screen kept back
 * answers no. At the control-flow label, since a branch may land on the return itself.
 */
internal fun MutableMethod.keepBackWindDown() {
    for ((index, register) in returnRegisters(this).asReversed()) {
        addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static { p0, v$register }, $WIND_DOWN_SCREENS->eligible(Ljava/lang/Object;Z)Z
                move-result v$register
            """,
        )
    }
}
