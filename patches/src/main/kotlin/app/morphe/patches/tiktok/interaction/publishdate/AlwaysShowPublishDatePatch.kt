/*
 * Thanks to lyyako for the original implementation and help with this patch.
 *
 * Originally adapted for TikTok 43.8.3; ported to TikTok 46.2.3:
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.publishdate

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.engagement.profileGridCountPatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.ControlFlow
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.util.BitSet

private const val EXTENSION_CLASS_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/publishdate/AlwaysShowPublishDatePatch;"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val WHAT = "Always show publish date"
private val FORMATTER_CALLS = setOf(
    Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE, Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE,
)

@Suppress("unused")
val alwaysShowPublishDatePatch = bytecodePatch(
    name = "Always show publish date",
    description = "Shows the date a video was posted next to the creator's name, so you can " +
        "tell how old it is. It can also show the exact time, or dates on profile grids. On by " +
        "default. Turn it off in Metra settings > Interface.",
    default = true,
) {
    category("Feed")
    // The grid cell's count comes with the grid patch, which Show engagement rate shares.
    dependsOn(settingsPatch, sharedExtensionPatch, profileGridCountPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAlwaysShowPublishDate()V",
        )

        VideoAuthorInfoStateFingerprint.method.apply {
            // The post time text sits after the visibility gates, so it goes in first and the
            // gates' indices still hold.
            val site = postTimeSite()
            addInstructions(
                site.insertAt,
                """
                    invoke-static { v${site.textRegister}, v${site.itemRegister} }, $EXTENSION_CLASS_DESCRIPTOR->postTime(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
                    move-result-object v${site.textRegister}
                """,
            )
            showPostTimeForMainFeeds()
        }
    }
}

private fun Instruction.calls(owner: String, name: String) =
    getReference<MethodReference>()?.let { it.definingClass == owner && it.name == name } == true

private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

/**
 * Where the author row's post time text is ready: [insertAt] is the first instruction past
 * TikTok's empty check of [textRegister], and [itemRegister] holds the video's Aweme there.
 * [formatters] counts the date formatters whose text can reach it.
 */
internal data class PostTimeSite(
    val insertAt: Int,
    val textRegister: Int,
    val itemRegister: Int,
    val formatters: Int,
)

/**
 * The row's post time runs `item.getCreateTime()`, times 1000, through one of a few static
 * (J...)String date formatters (the Following feed's, a short one and the default relative one;
 * 47.4.3 inlines the relative one as a chain of `BDDateFormat` (J, Locale) and static (I, unit)
 * formats), and all of them meet at one `TextUtils.isEmpty` of the text. Past its `if-nez` the text is a
 * date TikTok is about to show, which is where the hook goes. 47.1.x hands it to a small static
 * helper there and 47.0.3 builds the row text inline, so the anchor is the check, not what follows.
 *
 * The hook reads the item from the register the time was asked of, so every path into the hook
 * has to come through that call with nothing writing the register on the way, and nothing may
 * jump straight to the hooked instruction, which would skip it.
 */
internal fun Method.postTimeSite(): PostTimeSite {
    val flow = ControlFlow.of(this)
    val instructions = flow.instructions
    val count = instructions.size

    val read = instructions.indices.singleOrNull { index ->
        instructions[index].opcode == Opcode.INVOKE_VIRTUAL &&
            instructions[index].calls(AWEME, "getCreateTime") &&
            instructions.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_WIDE &&
            (instructions.getOrNull(index + 2) as? WideLiteralInstruction)?.wideLiteral == 1000L &&
            instructions.getOrNull(index + 3)?.opcode == Opcode.MUL_LONG_2ADDR
    } ?: throw PatchException("$WHAT: expected one post time read turned into milliseconds in the author row.")
    val itemRegister = (instructions[read] as FiveRegisterInstruction).registerC

    val emptyCheck = (read + 4 until count).firstOrNull { instructions[it].calls("Landroid/text/TextUtils;", "isEmpty") }
        ?: throw PatchException("$WHAT: the post time is never checked for text.")
    val result = instructions.getOrNull(emptyCheck + 1)
    val branch = instructions.getOrNull(emptyCheck + 2)
    if (instructions[emptyCheck].opcode != Opcode.INVOKE_STATIC ||
        result?.opcode != Opcode.MOVE_RESULT ||
        branch?.opcode != Opcode.IF_NEZ ||
        (branch as OneRegisterInstruction).registerA != (result as OneRegisterInstruction).registerA
    ) {
        throw PatchException("$WHAT: the post time's empty check doesn't skip an empty text.")
    }
    val textRegister = (instructions[emptyCheck] as FiveRegisterInstruction).registerC
    val insertAt = emptyCheck + 3
    if (insertAt >= count) throw PatchException("$WHAT: the post time's empty check ends the method.")

    val predecessors = Array(count) { mutableListOf<Int>() }
    for (from in 0 until count) {
        (flow.normal[from] + flow.exceptional[from]).forEach { predecessors[it] += from }
    }

    // Each text the check can see was last written by a date formatter's result: a static one taking
    // the time, or since 47.4.3 the inlined relative-date chain's virtual (time, locale) format and
    // static (count, unit) ones.
    val formatters = sortedSetOf<Int>()
    val seenBack = BitSet()
    val pendingBack = ArrayDeque(predecessors[emptyCheck])
    while (pendingBack.isNotEmpty()) {
        val at = pendingBack.removeFirst()
        if (seenBack[at]) continue
        seenBack.set(at)
        if (at == read) throw PatchException("$WHAT: the post time text can reach its check unformatted.")
        if (instructions[at].writes(textRegister)) {
            val call = instructions.getOrNull(at - 1)
            val formatter = call?.getReference<MethodReference>()
            val fromFormatter = instructions[at].opcode == Opcode.MOVE_RESULT_OBJECT &&
                call != null && call.opcode in FORMATTER_CALLS &&
                formatter != null && formatter.returnType == "Ljava/lang/String;" &&
                formatter.parameterTypes.firstOrNull()?.toString() in setOf("J", "I")
            if (!fromFormatter) {
                throw PatchException("$WHAT: instruction $at writes the post time text, and it isn't a date formatter.")
            }
            formatters += at
            continue
        }
        if (at == 0) throw PatchException("$WHAT: the post time text can reach its check from the method's start.")
        pendingBack.addAll(predecessors[at])
    }
    if (formatters.isEmpty()) throw PatchException("$WHAT: no date formatter feeds the post time text.")

    // Nothing reaches the hook without reading the time first.
    val seenFromStart = BitSet()
    val pendingFromStart = ArrayDeque(listOf(0))
    while (pendingFromStart.isNotEmpty()) {
        val at = pendingFromStart.removeFirst()
        if (seenFromStart[at]) continue
        seenFromStart.set(at)
        if (at == insertAt) throw PatchException("$WHAT: a path reaches the post time text without reading the time.")
        if (at == read) continue
        pendingFromStart.addAll(flow.normal[at] + flow.exceptional[at])
    }

    // Nothing between the read and the hook writes the item's register: an instruction on such a
    // path is one the read reaches without passing the hook, and that reaches the hook without
    // passing the read.
    val afterRead = BitSet()
    val pendingAfter = ArrayDeque(flow.normal[read] + flow.exceptional[read])
    while (pendingAfter.isNotEmpty()) {
        val at = pendingAfter.removeFirst()
        if (afterRead[at] || at == insertAt || at == read) continue
        afterRead.set(at)
        pendingAfter.addAll(flow.normal[at] + flow.exceptional[at])
    }
    val beforeHook = BitSet()
    val pendingBefore = ArrayDeque(predecessors[insertAt])
    while (pendingBefore.isNotEmpty()) {
        val at = pendingBefore.removeFirst()
        if (beforeHook[at] || at == read) continue
        beforeHook.set(at)
        pendingBefore.addAll(predecessors[at])
    }
    afterRead.and(beforeHook)
    var on = afterRead.nextSetBit(0)
    while (on >= 0) {
        if (instructions[on].writes(itemRegister)) {
            throw PatchException("$WHAT: instruction $on writes the item's register between its time and the post time text.")
        }
        on = afterRead.nextSetBit(on + 1)
    }

    // Code put in front of an instruction runs only for the paths that fall into it: a branch to
    // it lands past the hook.
    if (predecessors[insertAt] != listOf(emptyCheck + 2)) {
        throw PatchException("$WHAT: something other than the empty check leads to the post time text.")
    }
    // The 4-bit invoke names both registers.
    if (textRegister > 15 || itemRegister > 15) {
        throw PatchException("$WHAT: v$textRegister or v$itemRegister doesn't fit a short invoke.")
    }
    return PostTimeSite(insertAt, textRegister, itemRegister, formatters.size)
}

private fun app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.showPostTimeForMainFeeds() {
    val instructions = implementation!!.instructions
    val regionStart = instructions.indexOfFirst { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference
        reference is StringReference && reference.string == "v3"
    }
    val regionEnd = instructions.withIndex().indexOfFirst { (index, instruction) ->
        if (index <= regionStart) {
            return@indexOfFirst false
        }
        val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        reference?.name == "getCreateTime" &&
            reference.parameterTypes.isEmpty() &&
            reference.returnType == "J"
    }

    check(regionStart >= 0 && regionEnd > regionStart) {
        "Could not find video author post-time visibility region"
    }

    val gateCallIndices = instructions.withIndex()
        .filter { (index, instruction) ->
            index in regionStart..regionEnd && instruction.isStaticStringBooleanCall()
        }
        .filter { (index, _) -> getInstruction(index + 1).opcode == Opcode.MOVE_RESULT }
        .map { it.index }

    check(gateCallIndices.size == 5) {
        "Expected five video author post-time visibility gates, found ${gateCallIndices.size}"
    }

    gateCallIndices.asReversed().forEach { index ->
        val resultRegister = getInstruction<OneRegisterInstruction>(index + 1).registerA
        addInstructions(
            index + 2,
            """
                invoke-static/range {v$resultRegister .. v$resultRegister}, $EXTENSION_CLASS_DESCRIPTOR->showPostTimeForMainFeeds(Z)Z
                move-result v$resultRegister
            """,
        )
    }
}

private fun Any.isStaticStringBooleanCall(): Boolean {
    if ((this as? com.android.tools.smali.dexlib2.iface.instruction.Instruction)?.opcode != Opcode.INVOKE_STATIC) {
        return false
    }

    val reference = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return reference.returnType == "Z" &&
        reference.parameterTypes == listOf("Ljava/lang/String;")
}
