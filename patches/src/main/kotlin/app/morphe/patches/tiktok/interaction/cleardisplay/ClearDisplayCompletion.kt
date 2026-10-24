/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.cleardisplay

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.cloneMutable
import app.morphe.patcher.util.getReference
import app.morphe.util.implementationOrPatchException
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/cleardisplay/RememberClearDisplayPatch;"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val PANEL_ABILITY = "Lcom/ss/android/ugc/aweme/feed/assem/ability/IFeedPanelPlatformAbility;"
private const val PLAYER = "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;"
private const val BASE_PANEL = "Lcom/ss/android/ugc/aweme/feed/panel/BaseListFragmentPanel;"

internal class ClearDisplayControllerMembers(
    val panelAbility: MethodReference,
    val feedController: MethodReference,
    val controller: MethodReference,
)

/** The handler's own panel ability leads to its controller, not the shared player manager. */
internal fun resolveClearDisplayController(
    panel: Method,
    classOf: (String) -> ClassDef?,
): ClearDisplayControllerMembers {
    fun definition(type: String) = classOf(type)
        ?: throw PatchException("Clear display controller: missing $type")
    fun methods(type: String): List<Method> {
        val visited = mutableSetOf<String>()
        val found = mutableListOf<Method>()
        fun visit(current: String) {
            if (!visited.add(current)) return
            val owner = definition(current)
            found += owner.methods
            owner.interfaces.forEach(::visit)
        }
        visit(type)
        return found
    }
    val calls = panel.implementationOrPatchException("Clear display controller").instructions
        .mapNotNull { instruction ->
            instruction.getReference<MethodReference>()?.let { instruction.opcode to it }
        }.distinctBy { it.second.toString() }
    val ability = calls.filter { (opcode, method) ->
        opcode == Opcode.INVOKE_VIRTUAL && method.definingClass == panel.definingClass
            && method.parameterTypes.isEmpty() && method.returnType == PANEL_ABILITY
    }.singleOrPatchException("Clear display controller: owning panel ability").second
    val candidates = calls.filter { (opcode, method) ->
        opcode == Opcode.INVOKE_INTERFACE && method.definingClass == PANEL_ABILITY
            && method.parameterTypes.isEmpty() && method.returnType.startsWith("L")
    }.mapNotNull { (_, method) ->
        val type = definition(method.returnType)
        if (!AccessFlags.INTERFACE.isSet(type.accessFlags)) return@mapNotNull null
        val getters = methods(type.type).filter {
            it.parameterTypes.isEmpty() && it.returnType == PLAYER
                && !AccessFlags.STATIC.isSet(it.accessFlags)
        }.distinctBy { it.toString() }
        if (getters.isEmpty()) null else method to getters.singleOrPatchException(
            "Clear display controller: exact controller getter",
        )
    }
    val (feedController, controller) = candidates
        .singleOrPatchException("Clear display controller: native controller route")
    fun sameSignature(left: MethodReference, right: MethodReference) = left.name == right.name
        && left.parameterTypes == right.parameterTypes && left.returnType == right.returnType
    fun declared(reference: MethodReference) = definition(reference.definingClass).methods.filter {
        sameSignature(it, reference) && AccessFlags.PUBLIC.isSet(it.accessFlags)
            && !AccessFlags.STATIC.isSet(it.accessFlags)
    }.singleOrPatchException("Clear display controller: declared $reference")
    declared(ability)
    declared(feedController)
    declared(controller)

    // The panel getter must read its owned controller. A singleton player manager cannot
    // distinguish two panels playing the same item in the same activity.
    val panelGetter = definition(BASE_PANEL).methods.filter { sameSignature(it, feedController) }
        .singleOrPatchException("Clear display controller: panel controller getter")
    val getterBody = panelGetter.implementationOrPatchException("Clear display controller").instructions.toList()
    val field = getterBody.firstOrNull()?.getReference<FieldReference>()
    val load = getterBody.firstOrNull() as? TwoRegisterInstruction
    val returned = getterBody.lastOrNull() as? OneRegisterInstruction
    if (getterBody.size != 2 || getterBody[0].opcode != Opcode.IGET_OBJECT
        || getterBody[1].opcode != Opcode.RETURN_OBJECT || field == null || field.definingClass != BASE_PANEL
        || load == null || returned == null || load.registerB != panelGetter.implementation!!.registerCount - 1
        || returned.registerA != load.registerA)
        throw PatchException("Clear display controller: panel no longer returns its owned controller")
    val lineage = mutableSetOf<String>()
    var type: String? = field.type
    while (type != null && type != PLAYER && lineage.add(type)) type = definition(type).superclass
    if (type != PLAYER) throw PatchException("Clear display controller: panel field is not a PlayerController")

    val identity = definition(PLAYER).methods.filter { sameSignature(it, controller) }
        .singleOrPatchException("Clear display controller: player identity getter")
    val identityBody = identity.implementationOrPatchException("Clear display controller").instructions.toList()
    if (identityBody.size != 1 || identityBody[0].opcode != Opcode.RETURN_OBJECT
        || (identityBody[0] as? OneRegisterInstruction)?.registerA != identity.implementation!!.registerCount - 1)
        throw PatchException("Clear display controller: getter no longer returns this controller")
    return ClearDisplayControllerMembers(ability, feedController, controller)
}

internal fun clearDisplayPanelBridge(
    original: MutableMethod,
    members: ClearDisplayControllerMembers,
): MutableMethod = original.cloneMutable(additionalRegisters = 1).apply {
    addInstructionsWithLabels(0, """
        move-object/from16 v0, p2
        if-eqz v0, :notify
        check-cast v0, ${members.panelAbility.definingClass}
        invoke-virtual { v0 }, ${members.panelAbility}
        move-result-object v0
        if-eqz v0, :notify
        invoke-interface { v0 }, ${members.feedController}
        move-result-object v0
        if-eqz v0, :notify
        invoke-interface { v0 }, ${members.controller}
        move-result-object v0
        :notify
        move-object/16 p2, v0
        invoke-static/range { p0 .. p2 }, $EXTENSION->beforeNativeApply(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V
        return-void
    """)
}

/** Posting and the cell's early return both precede the actual native control update. */
internal fun hookClearDisplayCompletion(panel: MutableMethod, cell: MutableMethod) {
    val body = cell.implementationOrPatchException("Clear display completion").instructions.toList()
    val marker = body.indices.filter {
        body[it].getReference<StringReference>()?.string == "event_enter_clear_mode"
    }.singleOrPatchException("Clear display completion: cell notification")
    val stringRegister = (body[marker] as? OneRegisterInstruction)?.registerA
        ?: throw PatchException("Clear display completion: notification string register")
    val notification = ((marker + 1) until body.size).firstOrNull { at ->
        val reference = body[at].getReference<MethodReference>()
        val call = body[at] as? FiveRegisterInstruction
        body[at].opcode == Opcode.INVOKE_STATIC && reference?.returnType == "V"
            && reference.parameterTypes.size == 3
            && reference.parameterTypes.drop(1).map(CharSequence::toString) ==
                listOf("Ljava/lang/String;", "Ljava/lang/Object;")
            && call?.registerD == stringRegister
    } ?: throw PatchException("Clear display completion: native notification call")

    val instructions = panel.implementationOrPatchException("Clear display completion").instructions.toList()
    val eventType = panel.parameterTypes.single().toString()
    val sites = instructions.indices.filter { at ->
        val reference = instructions[at].getReference<MethodReference>()
        instructions[at].opcode == Opcode.INVOKE_INTERFACE && reference?.name == cell.name
            && reference.returnType == "V" && reference.parameterTypes.map(CharSequence::toString) == listOf("Z", "Z")
    }
    if (sites.isEmpty()) throw PatchException("Clear display completion: no current-holder apply")
    val calls = sites.map { at ->
        val call = instructions[at] as FiveRegisterInstruction
        val load = instructions.getOrNull(at - 1) as? TwoRegisterInstruction
        val flag = instructions.getOrNull(at - 1)?.getReference<FieldReference>()
        if (instructions.getOrNull(at - 1)?.opcode != Opcode.IGET_BOOLEAN || load?.registerA != call.registerD
            || flag?.definingClass != eventType || flag.type != "Z")
            throw PatchException("Clear display completion: current-holder event argument changed")
        // The same panel just triggered its immersive clear update for this Aweme.
        val ownerCall = (at - 2 downTo 0).firstOrNull { index ->
            val reference = instructions[index].getReference<MethodReference>()
            instructions[index].opcode == Opcode.INVOKE_VIRTUAL && reference?.definingClass == panel.definingClass
                && reference.returnType == "V"
                && reference.parameterTypes.map(CharSequence::toString) == listOf(AWEME, "Z")
        } ?: throw PatchException("Clear display completion: owning panel update")
        val owner = (instructions[ownerCall] as FiveRegisterInstruction).registerC
        val registers = listOf(load.registerB, call.registerC, owner)
        if (registers.any { it > 15 }) throw PatchException("Clear display completion: arguments exceed invoke register limit")
        at to "invoke-static { ${registers.joinToString { "v$it" }} }, " +
            "$EXTENSION->onNativePanelApply(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"
    }
    calls.asReversed().forEach { (at, instruction) -> panel.addInstruction(at, instruction) }
    cell.addInstruction(notification + 1,
        "invoke-static/range { p0 .. p1 }, $EXTENSION->onNativeApplied(Ljava/lang/Object;Z)V")
}
