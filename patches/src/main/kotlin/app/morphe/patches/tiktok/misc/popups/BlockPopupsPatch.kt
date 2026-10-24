/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.popups

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.getFreeRegisterProvider
import app.morphe.util.namedRegisters
import app.morphe.patcher.util.smali.ExternalLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val POPUP_LABELS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/popups/PopupLabels;"
private const val POPUP_TASK_EXECUTOR = "Lcom/bytedance/poplayer/core/PopupTaskExecutor;"
private const val FILTER_TASK = "filterTask"
private const val POPUP_SWITCHES = "Lapp/morphe/extension/tiktok/popups/PopupSwitches;"
private const val POP_SUITE = "Lcom/ss/android/ugc/aweme/services/popsuite/PopSuiteManagerService;"
private const val CAMPAIGN = "Lcom/ss/android/ugc/aweme/IPopSuiteManagerService\$PopupConfigObject;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"

/**
 * TikTok's popup layer runs a queue of popup tasks. The method that starts one asks a filter
 * whether to drop it, with a string it logs before the call. The class and that string are the
 * same on 47.0.3, 47.1.3 and 47.1.4. The method's name and every type around it are renamed on
 * each build, so the call is found by its shape below, not by name.
 */
internal object PopupTaskStartFingerprint : Fingerprint(
    definingClass = POPUP_TASK_EXECUTOR,
    returnType = "V",
    strings = listOf("getEnterFrom: use custom enterFrom: ").map { string(it, StringComparisonType.CONTAINS) },
)

/** The filter call in the task's start method: where it is, and the registers it reads and gets back. */
internal class PopupFilterCall(
    val index: Int,
    val taskRegister: Int,
    val resultRegister: Int,
    /** Every register the call reads, so the hook's scratch register is none of them. */
    val callRegisters: List<Int>,
)

/**
 * Every `filterTask(context, task, String, observer)Z` call in [method], each followed by the
 * move-result that takes its answer, in code order. The answer is true when the popup is
 * dropped. Null when there is none, or when a call's answer isn't read right away. The start
 * method asks twice, once on each of its two paths to showing a popup, and both are hooked.
 */
internal fun popupFilterCalls(method: Method): List<PopupFilterCall>? {
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val found = mutableListOf<PopupFilterCall>()
    for ((index, instruction) in instructions.withIndex()) {
        if (instruction.opcode != Opcode.INVOKE_INTERFACE && instruction.opcode != Opcode.INVOKE_INTERFACE_RANGE) continue
        val reference = (instruction as ReferenceInstruction).reference as? MethodReference ?: continue
        if (reference.name != FILTER_TASK || reference.returnType != "Z") continue
        val types = reference.parameterTypes.map(CharSequence::toString)
        if (types.size != 4 || types[2] != "Ljava/lang/String;") continue
        val next = instructions.getOrNull(index + 1) ?: return null
        if (next.opcode != Opcode.MOVE_RESULT) return null
        // Receiver, context, task, label, observer: the task is the third register either way.
        val task = when (instruction) {
            is FiveRegisterInstruction -> instruction.registerE
            is RegisterRangeInstruction -> instruction.startRegister + 2
            else -> return null
        }
        found += PopupFilterCall(index, task, (next as OneRegisterInstruction).registerA, instruction.namedRegisters())
    }
    return found.takeIf { it.isNotEmpty() && it.size <= 2 }
}

/**
 * Puts the reader's ticks into one filter call: the hook runs on every path into the call and
 * its verdict is or-ed into the call's answer.
 */
internal fun MutableMethod.hookPopupFilterCall(call: PopupFilterCall) {
    val after = call.index + 2
    // A register that is dead here and that neither the call nor its answer uses: the
    // hook's verdict waits in it across the call. The call may clobber the task's own
    // register with its answer, so the verdict is taken before the call, not after.
    val scratch = getFreeRegisterProvider(
        call.index, 1, call.callRegisters + call.resultRegister,
    ).getFreeRegister()
    if (scratch > 255 || call.resultRegister > 255) {
        throw PatchException("Block popups: no register low enough for the hook (v$scratch).")
    }
    // After the call: a popup the reader ticked is dropped as if TikTok's filter had. A plain
    // insert leaves any branch that lands on the next instruction pointing at that instruction,
    // so a path that skipped the call also skips this, and never reads the scratch register.
    addInstruction(after, "or-int v${call.resultRegister}, v${call.resultRegister}, v$scratch")
    // Before the call, on every path into it. The range form takes any register.
    addInstructionsAtControlFlowLabel(
        call.index,
        """
            invoke-static/range {v${call.taskRegister} .. v${call.taskRegister}}, $POPUP_LABELS_DESCRIPTOR->shouldDrop(Ljava/lang/Object;)Z
            move-result v$scratch
        """,
    )
}

/**
 * Where Pop Suite picks up the campaign it's about to show. Pop Suite is how TikTok's server sends
 * its own sheets and floating cards, and every one of them comes through here, named by the
 * campaign. The popup layer task it queues carries no label, so without this the checklist never
 * sees them. The service keeps its own names on 47.0.3, 47.1.3 and 47.1.4.
 */
internal object PopSuiteTriggerFingerprint : Fingerprint(
    definingClass = POP_SUITE,
    name = "popSuiteTriggerPopupInternal",
    returnType = "V",
)

/**
 * The check-cast that types the campaign Pop Suite has just read from currPopupConfigObj, with the
 * null check that ends the method right after it: the cast's index, or null when the method reads
 * its campaign any other way.
 */
internal fun campaignCast(method: Method): Int? {
    val instructions = method.implementation?.instructions?.toList() ?: return null
    for ((index, instruction) in instructions.withIndex()) {
        if (instruction.opcode != Opcode.CHECK_CAST) continue
        if (((instruction as ReferenceInstruction).reference as? TypeReference)?.type != CAMPAIGN) continue
        val register = (instruction as OneRegisterInstruction).registerA
        val result = instructions.getOrNull(index - 1)
        if (result?.opcode != Opcode.MOVE_RESULT_OBJECT || (result as OneRegisterInstruction).registerA != register) return null
        val get = (instructions.getOrNull(index - 2) as? ReferenceInstruction)?.reference as? MethodReference
        if (get == null || get.definingClass != "Ljava/util/concurrent/atomic/AtomicReference;" || get.name != "get") return null
        val field = (instructions.getOrNull(index - 3) as? ReferenceInstruction)?.reference as? FieldReference
        if (field?.name != "currPopupConfigObj") return null
        val check = instructions.getOrNull(index + 1)
        if (check?.opcode != Opcode.IF_NEZ || (check as OneRegisterInstruction).registerA != register) return null
        return index
    }
    return null
}

/**
 * The p register of the trigger's failure callback, its one Function0 parameter, or null when it
 * takes none or more than one. Pop Suite's popup task runs it when the popup layer turns the popup
 * down, and callers reset their own state there.
 */
internal fun failureCallbackRegister(method: Method): Int? {
    val types = method.parameterTypes.map { it.toString() }
    if (types.count { it == FUNCTION0 } != 1) return null
    val before = types.take(types.indexOf(FUNCTION0)).sumOf { if (it == "J" || it == "D") 2 else 1 }
    return before + if (AccessFlags.STATIC.isSet(method.accessFlags)) 0 else 1
}

/**
 * Hands the campaign to the checklist right after its cast. A ticked one comes back null, which
 * the null check below already treats as nothing to show, so Pop Suite returns before it builds
 * the sheet, queues a task or records a showing. With no task the failure callback would never
 * run, so a dropped campaign hands it to the extension, which runs it the way a turned-down popup
 * does. A campaign that was already null takes the method's own path untouched, as before.
 */
internal fun MutableMethod.passCampaignToChecklist() {
    val index = campaignCast(this)
        ?: throw PatchException("Block popups: Pop Suite no longer reads its campaign the way the hook expects.")
    val callback = failureCallbackRegister(this)
        ?: throw PatchException("Block popups: Pop Suite's trigger no longer takes one failure callback.")
    val register = (getInstruction(index) as OneRegisterInstruction).registerA
    addInstructionsWithLabels(
        index + 1,
        """
            if-eqz v$register, :morphe_pop_suite_null_check
            invoke-static/range {v$register .. v$register}, $POPUP_SWITCHES->campaign(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v$register
            check-cast v$register, $CAMPAIGN
            if-nez v$register, :morphe_pop_suite_null_check
            invoke-static/range {p$callback .. p$callback}, $POPUP_SWITCHES->campaignDropped(Ljava/lang/Object;)V
        """,
        ExternalLabel("morphe_pop_suite_null_check", getInstruction(index + 1)),
    )
}

/**
 * LiveBubbleUtil's check before it floats the LIVE bubble at the top of the feed: renamed on each
 * build, found by the trace section it opens with. It never goes through the popup layer.
 */
internal object LiveBubbleCheckFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/feed/util/LiveBubbleUtil;",
    returnType = "V",
    parameters = listOf("Lcom/bytedance/android/livesdkapi/depend/model/live/bubble/LiveBubbleData;"),
    strings = listOf("livesdk_bubble_checkIsShowBubblePopWindow").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * Returns at the very start of this method with [returning] when the extension's [switch] says so.
 * v0 holds the answer, so the method needs a local register below its parameters; nothing has been
 * written there yet at the start, and the original first instruction is where a no lands.
 */
internal fun MutableMethod.returnEarlyWhen(switch: String, returning: String) {
    val registers = implementation?.registerCount
        ?: throw PatchException("Block popups: $definingClass->$name has no code.")
    val wide = setOf("J", "D")
    val ins = parameterTypes.sumOf { type -> if (type.toString() in wide) 2 else 1 } +
        if (AccessFlags.STATIC.isSet(accessFlags)) 0 else 1
    if (registers <= ins) throw PatchException("Block popups: $definingClass->$name has no local register for the check.")
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $POPUP_SWITCHES->$switch()Z
            move-result v0
            if-eqz v0, :morphe_popup_shows
            $returning
        """,
        ExternalLabel("morphe_popup_shows", getInstruction(0)),
    )
}

@Suppress("unused")
val blockPopupsPatch = bytecodePatch(
    name = "Block popups",
    description = "Lets you stop TikTok's popups, like friend suggestions and offers. Each " +
        "one joins a checklist after it first appears, and once ticked it won't come back. " +
        "Sign-in, age and verification screens are never blocked. Starts off. Turn it on in " +
        "Metra settings > Interface.",
    default = true,
) {
    category("Feed")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // The wind-down screens are optional on a build nobody has checked: found before anything
        // is written, and left out with a note rather than failing the whole patch.
        val windDown = try {
            windDownSites { classDefByOrNull(it) }
        } catch (problem: PatchException) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[Block popups] Left out the wind-down screens on ${packageMetadata.versionName}: ${problem.message}")
            null
        }

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enablePopupLabels()V",
        )
        val method = PopupTaskStartFingerprint.method
        val calls = popupFilterCalls(method)
            ?: throw PatchException("Block popups: the popup layer has no filterTask call to hook.")
        // Later calls first, so an earlier insert doesn't move the index of the next one.
        for (call in calls.asReversed()) method.hookPopupFilterCall(call)

        PopSuiteTriggerFingerprint.method.passCampaignToChecklist()
        LiveBubbleCheckFingerprint.method.returnEarlyWhen("hideLiveBubble", "return-void")

        if (windDown != null) {
            windDown.triggers.forEach { trigger ->
                mutableClassDefBy(trigger.definingClass).methods.single {
                    it.name == trigger.name && it.parameterTypes.isEmpty() && it.returnType == "Z"
                }.keepBackWindDown()
            }
            SettingsStatusLoadFingerprint.method.addInstruction(
                0,
                "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableWindDownScreens()V",
            )
        }
    }
}
