/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstruction
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PANEL_MARKER = "share_panel_action"
private const val VIEW = "Landroid/view/View;"
private const val RECYCLER = "Landroidx/recyclerview/widget/RecyclerView;"
private const val TOOLS = "Lapp/morphe/extension/tiktok/share/ShareSheetTools;"

// ACTIONS_LIST_IDS in ShareSheetTools: 47.1.4 a5u.
internal val SHARE_ACTION_IDS = setOf(0x7f0a013a)

internal data class SharePanelRow(val castIndex: Int, val register: Int)

/** The native panel attaches even when no contacts are bound or the window cannot take focus. */
internal fun resolveSharePanel(classes: Iterable<ClassDef>): Method {
    val candidates = classes.flatMap { classDef ->
        classDef.methods.filter { method ->
            method.name == "onAttachedToWindow" && method.returnType == "V" &&
                method.parameterTypes.isEmpty() && method.accessFlags and AccessFlags.STATIC.value == 0 &&
                method.implementation?.instructions?.any {
                    it.getReference<StringReference>()?.string == PANEL_MARKER
                } == true
        }.map { classDef to it }
    }
    val (owner, method) = candidates.singleOrNull()
        ?: throw PatchException("Share sheet: expected one native action panel attach method, found ${candidates.size}.")
    check(owner.superclass == "Landroid/widget/FrameLayout;") {
        "Share sheet: the native action panel is no longer a FrameLayout."
    }
    method.sharePanelRows() // Validate every insertion before making the method mutable.
    return method
}

/** Both layouts first check for the row, inflate it if absent, then look it up and cast it. */
internal fun Method.sharePanelRows(): List<SharePanelRow> {
    val body = checkNotNull(implementation) { "Share sheet: the panel has no implementation." }
    check(body.tryBlocks.isEmpty()) { "Share sheet: the native panel gained exception handlers." }
    val code = body.instructions.toList()
    check(code.count { it.opcode == Opcode.CONST &&
        (it as? NarrowLiteralInstruction)?.narrowLiteral in SHARE_ACTION_IDS } == 1) {
        "Share sheet: the panel's action resource ID is missing or ambiguous."
    }
    val addresses = IntArray(code.size + 1)
    code.forEachIndexed { index, instruction -> addresses[index + 1] = addresses[index] + instruction.codeUnits }
    val indexes = addresses.withIndex().associate { it.value to it.index }
    val predecessors = Array(code.size) { mutableSetOf<Int>() }
    code.forEachIndexed { index, instruction ->
        fun edge(address: Int) {
            val target = indexes[address]
                ?: throw PatchException("Share sheet: a panel branch has no instruction target.")
            check(target < code.size) { "Share sheet: a panel branch leaves the method." }
            predecessors[target].add(index)
        }
        if (instruction.opcode.canContinue() && index + 1 < code.size) edge(addresses[index + 1])
        if (instruction is OffsetInstruction && instruction.opcode != Opcode.FILL_ARRAY_DATA) {
            val target = addresses[index] + instruction.codeOffset
            if (instruction.opcode == Opcode.PACKED_SWITCH || instruction.opcode == Opcode.SPARSE_SWITCH) {
                val payload = code[indexes.getValue(target)] as SwitchPayload
                payload.switchElements.forEach { edge(addresses[index] + it.offset) }
            } else edge(target)
        }
    }

    fun actionIdAt(index: Int, register: Int): Boolean {
        val pending = ArrayDeque<Int>()
        pending.addAll(predecessors[index])
        val seen = mutableSetOf<Int>()
        var found = false
        while (pending.isNotEmpty()) {
            val previous = pending.removeFirst()
            if (!seen.add(previous)) continue
            val instruction = code[previous]
            val destination = (instruction as? OneRegisterInstruction)?.registerA
            val writes = instruction.opcode.setsRegister() && (destination == register ||
                instruction.opcode.setsWideRegister() && destination == register - 1)
            if (writes) {
                if (instruction.opcode != Opcode.CONST ||
                    (instruction as? NarrowLiteralInstruction)?.narrowLiteral !in SHARE_ACTION_IDS) return false
                found = true
            } else {
                if (predecessors[previous].isEmpty()) return false
                pending.addAll(predecessors[previous])
            }
        }
        return found
    }

    val lookups = code.indices.filter { index ->
        val instruction = code[index]
        val reference = instruction.getReference<MethodReference>()
        if (reference?.definingClass != VIEW || reference.name != "findViewById" ||
            reference.parameterTypes.map(CharSequence::toString) != listOf("I") || reference.returnType != VIEW) return@filter false
        val idRegister = when (instruction.opcode) {
            Opcode.INVOKE_VIRTUAL -> (instruction as FiveRegisterInstruction).takeIf { it.registerCount == 2 }?.registerD
            Opcode.INVOKE_VIRTUAL_RANGE -> (instruction as RegisterRangeInstruction).takeIf { it.registerCount == 2 }?.let { it.startRegister + 1 }
            else -> null
        }
        idRegister != null && actionIdAt(index, idRegister)
    }
    val rows = lookups.mapNotNull { callIndex ->
        val move = code.getOrNull(callIndex + 1)
        check(move?.opcode == Opcode.MOVE_RESULT_OBJECT && predecessors[callIndex + 1] == setOf(callIndex)) {
            "Share sheet: an action row lookup no longer has a safe object result."
        }
        val register = (move as OneRegisterInstruction).registerA
        val cast = code.getOrNull(callIndex + 2)
        if (cast?.opcode != Opcode.CHECK_CAST || cast.getReference<TypeReference>()?.type != RECYCLER) {
            check(cast?.opcode == Opcode.IF_NEZ && (cast as OneRegisterInstruction).registerA == register) {
                "Share sheet: the action row lookup is neither an existence check nor a typed row."
            }
            return@mapNotNull null
        }
        check((cast as OneRegisterInstruction).registerA == register &&
            predecessors[callIndex + 2] == setOf(callIndex + 1)) {
            "Share sheet: a branch can enter the action row cast without its lookup result."
        }
        SharePanelRow(callIndex + 2, register)
    }
    check(lookups.size == 4 && rows.size == 2) {
        "Share sheet: expected two checked action-row inflation paths, found ${lookups.size} lookups and ${rows.size} typed rows."
    }
    return rows
}

internal fun MutableMethod.notifyPanelBound() {
    sharePanelRows().asReversed().forEach { row ->
        addInstruction(row.castIndex + 1,
            "invoke-static/range { v${row.register} .. v${row.register} }, $TOOLS->panelBound($VIEW)V")
    }
}

context(patchContext: BytecodePatchContext)
internal fun hookSharePanel() {
    val method = resolveSharePanel(patchContext.classDefByStrings(PANEL_MARKER))
    patchContext.mutableClassDefBy(method.definingClass).findMutableMethodOf(method).notifyPanelBound()
}
