/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.cleardisplay

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findFreeRegister
import app.morphe.util.getFreeRegisterProvider
import app.morphe.patcher.util.getReference
import app.morphe.util.implementationOrPatchException
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val SKYLIGHT_BUBBLE = "Lcom/ss/android/ugc/aweme/base/ui/assem/FeedSkylightBubbleAssem;"
internal const val SKYLIGHT_LIST = "Lcom/ss/android/ugc/aweme/base/vm/FeedSkylightListViewModel;"
internal const val POWER_LIST = "Lcom/bytedance/ext_power_list/AssemSingleListViewModel;"
internal const val POWER_LIST_ALL = "$POWER_LIST->listGetAll()Ljava/util/List;"
private const val BUBBLE_EXTENSION = "Lapp/morphe/extension/tiktok/cleardisplay/FollowingStoriesBubble;"
private const val WHAT = "Following stories bubble"

/**
 * Where the Following stories bubble meets clear display. Its handler hides it on the way in with
 * [toggle] (false, true) at [toggleIndex], and on the way out runs from [exitIndex] without ever
 * showing it again. [view] is the assem's own view and [list] the list model behind it.
 */
internal class BubbleClearMode(
    val handler: Method,
    val toggleIndex: Int,
    val toggle: MethodReference,
    val view: MethodReference,
    val list: Method,
    val exitIndex: Int,
)

/**
 * Finds [BubbleClearMode] in [bubble] (FeedSkylightBubbleAssem). The handler is TikTok's
 * onClearModeEvent, an event bus name R8 keeps. The only call on the assem taking two booleans
 * in it is the hide; the test of the event's own boolean right above it splits the way in from
 * the way out, so its branch target is where the way out starts.
 */
internal fun findBubbleClearMode(bubble: ClassDef): BubbleClearMode {
    val handler = bubble.methods.filter {
        it.name == "onClearModeEvent" && it.parameterTypes.size == 1 && it.returnType == "V" &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    }.singleOrPatchException("$WHAT: clear mode handler")
    val implementation = handler.implementationOrPatchException(WHAT)
    val body = implementation.instructions.toList()
    val eventType = handler.parameterTypes.single().toString()
    val eventRegister = implementation.registerCount - 1

    val toggleIndex = body.indices.filter { at ->
        val reference = body[at].getReference<MethodReference>()
        body[at].opcode == Opcode.INVOKE_VIRTUAL && reference?.definingClass == bubble.type &&
            reference.returnType == "V" && reference.parameterTypes.map { it.toString() } == listOf("Z", "Z")
    }.singleOrPatchException("$WHAT: hide on the way into clear display")
    val toggle = body[toggleIndex].getReference<MethodReference>()!!
    if ((body[toggleIndex] as FiveRegisterInstruction).registerC != implementation.registerCount - 2) {
        throw PatchException("$WHAT: the hide isn't called on the bubble itself")
    }

    val test = (toggleIndex - 1 downTo 1).firstOrNull { at ->
        val load = body[at - 1] as? TwoRegisterInstruction
        body[at].opcode == Opcode.IF_EQZ && body[at - 1].opcode == Opcode.IGET_BOOLEAN &&
            load?.registerB == eventRegister &&
            load.registerA == (body[at] as OneRegisterInstruction).registerA &&
            body[at - 1].getReference<FieldReference>()?.definingClass == eventType
    } ?: throw PatchException("$WHAT: no test of the event's enter flag above the hide")
    val exitIndex = branchTarget(handler, test)
    if (exitIndex <= toggleIndex) throw PatchException("$WHAT: the way out no longer follows the way in")
    // The way in ends in a goto on 47.1.4. If it ran on instead, showAgain would undo the hide.
    if (body[exitIndex - 1].opcode.canContinue()) throw PatchException("$WHAT: the way in runs on into the way out")

    val toggleBody = bubble.methods.filter {
        it.name == toggle.name && it.parameterTypes.map { type -> type.toString() } == listOf("Z", "Z") &&
            it.returnType == "V"
    }.singleOrPatchException("$WHAT: show and hide method").implementationOrPatchException(WHAT).instructions
    // The hook makes the same virtual call on the assem, so a view read any other way won't do.
    val view = toggleBody.firstOrNull {
        it.opcode == Opcode.INVOKE_VIRTUAL && it.getReference<MethodReference>()?.let { reference ->
            reference.parameterTypes.isEmpty() && reference.returnType == "Landroid/view/View;"
        } == true
    }?.getReference<MethodReference>() ?: throw PatchException("$WHAT: the show and hide method reads no view")
    val list = bubble.methods.filter {
        it.parameterTypes.isEmpty() && it.returnType == SKYLIGHT_LIST && !AccessFlags.STATIC.isSet(it.accessFlags)
    }.singleOrPatchException("$WHAT: list model getter")
    return BubbleClearMode(handler, toggleIndex, toggle, view, list, exitIndex)
}

/** The bubble's list model must be a single list model that still lists its items, the way out reads them. */
internal fun checkBubbleList(classOf: (String) -> ClassDef?) {
    val seen = mutableSetOf<String>()
    var type: String? = SKYLIGHT_LIST
    while (type != null && type != POWER_LIST && seen.add(type)) type = classOf(type)?.superclass
    if (type != POWER_LIST) throw PatchException("$WHAT: $SKYLIGHT_LIST is no longer a $POWER_LIST")
    val lists = classOf(POWER_LIST)?.methods?.any {
        it.name == "listGetAll" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/util/List;" &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: false
    if (!lists) throw PatchException("$WHAT: $POWER_LIST no longer has listGetAll")
}

/** The index [method]'s branch at [index] lands on. */
internal fun branchTarget(method: Method, index: Int): Int {
    val instructions = method.implementation!!.instructions.toList()
    val addresses = IntArray(instructions.size)
    var address = 0
    instructions.forEachIndexed { at, instruction ->
        addresses[at] = address
        address += instruction.codeUnits
    }
    val target = addresses[index] + (instructions[index] as OffsetInstruction).codeOffset
    return addresses.indexOf(target).takeIf { it >= 0 }
        ?: throw PatchException("$WHAT: a branch lands between instructions")
}

/**
 * On the way in, tells the extension whether the bubble was showing just before TikTok hides it.
 * On the way out, asks the extension and, on yes, makes TikTok's own show call (true, true), which
 * sets it visible and logs the show as TikTok does. The way out goes first so the way in's index
 * still holds. A missing list model asks with no list, which answers no, rather than throw inside
 * TikTok's handler.
 */
internal fun MutableMethod.hookBubbleClearMode(found: BubbleClearMode) {
    val self = implementation!!.registerCount - 2
    val out = getFreeRegisterProvider(found.exitIndex, 2, self)
    val bubble = out.getFreeRegister()
    val items = out.getFreeRegister()
    if (maxOf(self, bubble, items) > 15) throw PatchException("$WHAT: registers past v15 at the way out")
    val listCall = if (AccessFlags.PRIVATE.isSet(found.list.accessFlags)) "invoke-direct" else "invoke-virtual"
    addInstructionsAtControlFlowLabel(
        found.exitIndex,
        """
            invoke-virtual { v$self }, ${found.view}
            move-result-object v$bubble
            $listCall { v$self }, ${found.list}
            move-result-object v$items
            if-eqz v$items, :ask
            invoke-virtual { v$items }, $POWER_LIST_ALL
            move-result-object v$items
            :ask
            invoke-static { v$bubble, v$items }, $BUBBLE_EXTENSION->showAgain(Landroid/view/View;Ljava/util/List;)Z
            move-result v$bubble
            if-eqz v$bubble, :keep
            const/4 v$bubble, 0x1
            invoke-virtual { v$self, v$bubble, v$bubble }, ${found.toggle}
            :keep
            nop
        """,
    )

    val call = getInstruction<FiveRegisterInstruction>(found.toggleIndex)
    val held = findFreeRegister(found.toggleIndex, call.registerC, call.registerD, call.registerE)
    if (held > 15) throw PatchException("$WHAT: registers past v15 at the way in")
    addInstructionsAtControlFlowLabel(
        found.toggleIndex,
        """
            invoke-virtual { v$self }, ${found.view}
            move-result-object v$held
            invoke-static { v$held }, $BUBBLE_EXTENSION->hiding(Landroid/view/View;)V
        """,
    )
}
