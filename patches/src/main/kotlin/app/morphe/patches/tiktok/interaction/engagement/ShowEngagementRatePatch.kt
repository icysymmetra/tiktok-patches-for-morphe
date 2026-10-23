/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.engagement

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.authorregion.authorRowPatch
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.ControlFlow
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/feed/ProfileGridCount;"
private const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val STATISTICS = "Lcom/ss/android/ugc/aweme/feed/model/AwemeStatistics;"
private const val WHAT = "Profile grid count"

/**
 * The bind of the older profile grid adapter (X/0Of2 on 47.1.4, X/0Oey on 47.1.3, X/0lEk on
 * 47.0.3, built by AwemeListFragmentImpl and ProfileBaseAwemeListFragment). It reads one view
 * count: the cell's play icon label, formatted by TikTok's main count formatter. On the S22 with
 * 47.1.4 the count on screen came from the cell holder's own bind below instead (2026-10-11).
 */
internal object ProfileGridBindFingerprint : Fingerprint(
    name = "onBindBasicViewHolder",
    returnType = "V",
    parameters = listOf("Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;", "I"),
    strings = listOf("AwemeListFragment", "not supported in i18n").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.viewCountReads().size == 1 },
)

private fun Instruction.calls(owner: String, name: String) =
    getReference<MethodReference>()?.let { it.definingClass == owner && it.name == name } == true

private fun Method.viewCountReads(): List<Int> {
    val instructions = implementation?.instructions?.toList().orEmpty()
    return instructions.indices.filter { instructions[it].calls(STATISTICS, "getPlayCount") }
}

/**
 * Where the grid's view count text is ready: [insertAt] is the instruction after the formatter's
 * move-result-object into [textRegister], and [itemRegister] still holds the cell's Aweme there.
 */
internal data class GridCountSite(val insertAt: Int, val textRegister: Int, val itemRegister: Int)

/**
 * The cell's view count runs `item.getStatistics()`, then `getPlayCount()` on the result, a static
 * (J)String formatter and its move-result-object. The hook goes right after that and reads the
 * item from the register the statistics were asked of, so every path into the stretch from there
 * to the hook has to come through that call, and nothing in it may write the item's register.
 */
internal fun Method.gridCountSite(): GridCountSite {
    val instructions = implementation?.instructions?.toList()
        ?: throw PatchException("$WHAT: the profile grid bind has no code.")
    val read = viewCountReads().singleOrNull()
        ?: throw PatchException("$WHAT: expected one view count read in the profile grid bind.")
    val format = read + 2
    val result = read + 3
    val formatter = instructions.getOrNull(format)?.getReference<MethodReference>()
    if (instructions.getOrNull(read + 1)?.opcode != Opcode.MOVE_RESULT_WIDE ||
        instructions[format].opcode !in setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE) ||
        formatter?.returnType != "Ljava/lang/String;" ||
        formatter.parameterTypes.map(CharSequence::toString) != listOf("J") ||
        instructions.getOrNull(result)?.opcode != Opcode.MOVE_RESULT_OBJECT
    ) {
        throw PatchException("$WHAT: the view count isn't formatted to text right after it's read.")
    }
    val textRegister = (instructions[result] as OneRegisterInstruction).registerA
    val statisticsRegister = (instructions[read] as FiveRegisterInstruction).registerC

    val statisticsStored = (read - 1 downTo 1).firstOrNull {
        (instructions[it] as? OneRegisterInstruction)?.registerA == statisticsRegister &&
            instructions[it].opcode.setsRegister()
    } ?: throw PatchException("$WHAT: the view count's statistics come from nowhere.")
    val asked = statisticsStored - 1
    if (instructions[statisticsStored].opcode != Opcode.MOVE_RESULT_OBJECT ||
        instructions[asked].opcode != Opcode.INVOKE_VIRTUAL || !instructions[asked].calls(AWEME, "getStatistics")
    ) {
        throw PatchException("$WHAT: the view count's statistics aren't the item's own.")
    }
    val itemRegister = (instructions[asked] as FiveRegisterInstruction).registerC

    val insertAt = result + 1
    val stretch = asked until insertAt
    if (stretch.any { index ->
            val instruction = instructions[index]
            instruction.opcode.setsRegister() && (instruction as? OneRegisterInstruction)?.registerA.let {
                it == itemRegister || (instruction.opcode.setsWideRegister() && it == itemRegister - 1)
            }
        }
    ) {
        throw PatchException("$WHAT: the item's register is written between its statistics and the view count.")
    }
    val flow = ControlFlow.of(this)
    instructions.indices.forEach { from ->
        if (from in stretch) return@forEach
        (flow.normal[from] + flow.exceptional[from]).forEach { to ->
            if (to > asked && to <= insertAt) {
                throw PatchException("$WHAT: instruction $from jumps into the view count at $to.")
            }
        }
    }
    if (flow.normal[result] != listOf(insertAt)) {
        throw PatchException("$WHAT: the view count's text doesn't fall through to the hook.")
    }
    // The 4-bit invoke below names both registers.
    if (textRegister > 15 || itemRegister > 15) {
        throw PatchException("$WHAT: v$textRegister or v$itemRegister doesn't fit a short invoke.")
    }
    return GridCountSite(insertAt, textRegister, itemRegister)
}

/** Whether this instruction writes [register], either half of a wide write included. */
private fun Instruction.writes(register: Int) = opcode.setsRegister() &&
    (this as? OneRegisterInstruction)?.registerA.let {
        it == register || (opcode.setsWideRegister() && it == register - 1)
    }

/**
 * The profile grid cell holder's own bind, which sets the count a profile's Videos tab shows on
 * 47.1.4 (X/0Oec.r6, seen on the S22 2026-10-11 on another creator's Videos tab and on Favorites
 * and Liked: the cell's tv_play_count is its field, and the adapter's count above never reached
 * the screen). It formats the count through a (I, Number, String)CharSequence formatter on two
 * branches, one for a pinned or scheduled cell.
 */
internal object ProfileGridHolderBindFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(
        AWEME, "I", "Z", "Z", "I", "Ljava/lang/String;", "Ljava/lang/String;", "Z", "Z", "Ljava/util/Set;", "Ljava/util/Set;",
    ),
    custom = { method, _ -> method.viewCountReads().isNotEmpty() },
)

/** One formatted count in the holder's bind: the hook goes at [insertAt], after the text's cast to String. */
internal data class HolderCountSite(val insertAt: Int, val textRegister: Int)

/** The holder bind's count sites, highest first so inserting at one leaves the others' indices alone. */
internal data class HolderCountSites(val itemRegister: Int, val sites: List<HolderCountSite>)

private val NUMBER_FORMATTER_PARAMETERS = listOf("I", "Ljava/lang/Number;", "Ljava/lang/String;")

/**
 * The holder reads the cell's statistics once, from the register holding the cell's item (a
 * story cell swaps in its first story before that), and every view count it reads comes from
 * them. Each count is boxed with Long.valueOf, formatted, cast to String and later set on the
 * count's view. The item's register is last written before the statistics read and nothing jumps
 * back over that write, so it holds the same item at every hook.
 */
internal fun Method.holderCountSites(): HolderCountSites {
    val instructions = implementation?.instructions?.toList()
        ?: throw PatchException("$WHAT: the grid holder's bind has no code.")
    val reads = viewCountReads()
    val statistics = instructions.indices.filter { instructions[it].calls(AWEME, "getStatistics") }.singleOrNull()
        ?: throw PatchException("$WHAT: expected one statistics read in the grid holder's bind.")
    val statisticsRegister = (instructions.getOrNull(statistics + 1) as? OneRegisterInstruction)
        ?.takeIf { instructions[statistics + 1].opcode == Opcode.MOVE_RESULT_OBJECT }?.registerA
        ?: throw PatchException("$WHAT: the grid holder's statistics aren't kept.")
    val counts = reads.map { read ->
        if ((instructions[read] as FiveRegisterInstruction).registerC != statisticsRegister ||
            instructions.getOrNull(read + 1)?.opcode != Opcode.MOVE_RESULT_WIDE
        ) {
            throw PatchException("$WHAT: a grid holder view count isn't read from the cell's statistics.")
        }
        (instructions[read + 1] as OneRegisterInstruction).registerA
    }.toSet()

    val itemRegister = (instructions[statistics] as FiveRegisterInstruction).registerC
    val lastWrite = instructions.indices.lastOrNull { instructions[it].writes(itemRegister) } ?: -1
    if (lastWrite >= statistics) {
        throw PatchException("$WHAT: the grid holder's item changes after its statistics are read.")
    }
    val flow = ControlFlow.of(this)
    instructions.indices.forEach { from ->
        if (from > lastWrite && (flow.normal[from] + flow.exceptional[from]).any { it <= lastWrite }) {
            throw PatchException("$WHAT: instruction $from jumps back over the grid holder's item.")
        }
    }

    val predecessors = List(instructions.size) { mutableListOf<Int>() }.also { into ->
        instructions.indices.forEach { from -> (flow.normal[from] + flow.exceptional[from]).forEach { into[it] += from } }
    }
    val sites = instructions.indices.filter { index ->
        val reference = instructions[index].getReference<MethodReference>()
        instructions[index].opcode == Opcode.INVOKE_STATIC && reference?.returnType == "Ljava/lang/CharSequence;" &&
            reference.parameterTypes.map(CharSequence::toString) == NUMBER_FORMATTER_PARAMETERS
    }.map { format ->
        val number = (instructions[format] as FiveRegisterInstruction).registerD
        val boxed = instructions.getOrNull(format - 1)
        val boxing = instructions.getOrNull(format - 2)
        val cast = instructions.getOrNull(format + 2)
        if (boxed?.opcode != Opcode.MOVE_RESULT_OBJECT || (boxed as OneRegisterInstruction).registerA != number ||
            boxing?.calls("Ljava/lang/Long;", "valueOf") != true ||
            (boxing as FiveRegisterInstruction).registerC !in counts ||
            instructions.getOrNull(format + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT ||
            cast?.opcode != Opcode.CHECK_CAST || cast.getReference<TypeReference>()?.type != "Ljava/lang/String;"
        ) {
            throw PatchException("$WHAT: a grid holder count isn't boxed, formatted and cast to text in a row.")
        }
        val textRegister = (instructions[format + 1] as OneRegisterInstruction).registerA
        if ((cast as OneRegisterInstruction).registerA != textRegister) {
            throw PatchException("$WHAT: the grid holder casts another register than its count text.")
        }
        val insertAt = format + 3
        if (flow.normal[format + 2] != listOf(insertAt)) {
            throw PatchException("$WHAT: the grid holder's count text doesn't fall through to the hook.")
        }
        // Paths may join at the boxing call (the missing-statistics zero does), but nothing may
        // enter between it and the hook, where the value it boxed would be skipped.
        val boxingAt = format - 2
        instructions.indices.forEach { from ->
            (flow.normal[from] + flow.exceptional[from]).forEach { to ->
                if (to in boxingAt + 1..insertAt && from != to - 1) {
                    throw PatchException("$WHAT: instruction $from jumps into a grid holder count at $to.")
                }
            }
        }
        val boxedRegister = (boxing as FiveRegisterInstruction).registerC
        if (!carriesViewCount(instructions, predecessors, boxingAt, boxedRegister, mutableSetOf())) {
            throw PatchException("$WHAT: a grid holder count isn't the view count on every path.")
        }
        if (textRegister > 15) throw PatchException("$WHAT: v$textRegister doesn't fit a short invoke.")
        HolderCountSite(insertAt, textRegister)
    }
    if (sites.size != reads.size) {
        throw PatchException("$WHAT: the grid holder reads ${reads.size} view counts but formats ${sites.size}.")
    }
    if (itemRegister > 15) throw PatchException("$WHAT: v$itemRegister doesn't fit a short invoke.")
    return HolderCountSites(itemRegister, sites.sortedByDescending { it.insertAt })
}

private val GOTOS = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
private val WIDE_CONSTANTS = setOf(Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE, Opcode.CONST_WIDE_HIGH16)

/**
 * Whether every path into [index] arrives with [register] holding a view count: the wide result
 * of a getPlayCount read, or the zero a cell without statistics gets. Gotos are followed back to
 * where they came from, so a like count or any other number joining there fails the patch rather
 * than getting the rate and the date.
 */
private fun carriesViewCount(
    instructions: List<Instruction>,
    predecessors: List<List<Int>>,
    index: Int,
    register: Int,
    visited: MutableSet<Int>,
): Boolean {
    val into = predecessors[index]
    return into.isNotEmpty() && into.all { from ->
        if (!visited.add(from)) return@all true
        val instruction = instructions[from]
        when (instruction.opcode) {
            Opcode.MOVE_RESULT_WIDE -> (instruction as OneRegisterInstruction).registerA == register &&
                instructions.getOrNull(from - 1)?.calls(STATISTICS, "getPlayCount") == true
            in WIDE_CONSTANTS -> (instruction as OneRegisterInstruction).registerA == register &&
                (instruction as WideLiteralInstruction).wideLiteral == 0L
            in GOTOS -> carriesViewCount(instructions, predecessors, from, register, visited)
            else -> false
        }
    }
}

/**
 * Hands each profile grid cell's view count text and its Aweme to the extension, in the old
 * adapter's bind and in the cell holder's own bind, since a build can set the count in either.
 * Show engagement rate, Always show publish date and Advanced downloads share it, so a build with
 * them installs the hooks once and each adds its part to the text: the rate after the count, the
 * date on a line of its own, the saved mark before it.
 */
internal val profileGridCountPatch = bytecodePatch {
    dependsOn(sharedExtensionPatch)

    execute {
        ProfileGridBindFingerprint.method.apply {
            if (AccessFlags.STATIC.isSet(accessFlags)) {
                throw PatchException("$WHAT: the profile grid bind is static.")
            }
            val site = gridCountSite()
            addInstructions(
                site.insertAt,
                """
                    invoke-static { v${site.textRegister}, v${site.itemRegister} }, $EXTENSION->text(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
                    move-result-object v${site.textRegister}
                """,
            )
        }
        ProfileGridHolderBindFingerprint.method.apply {
            if (AccessFlags.STATIC.isSet(accessFlags)) {
                throw PatchException("$WHAT: the grid holder's bind is static.")
            }
            val sites = holderCountSites()
            sites.sites.forEach { site ->
                addInstructions(
                    site.insertAt,
                    """
                        invoke-static { v${site.textRegister}, v${sites.itemRegister} }, $EXTENSION->text(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/String;
                        move-result-object v${site.textRegister}
                    """,
                )
            }
        }
    }
}

@Suppress("unused")
val showEngagementRatePatch = bytecodePatch(
    name = "Show engagement rate",
    description = "Shows how many people liked, commented on, shared or saved a video " +
        "compared with how many watched it, next to the creator's name. Starts off. Turn it on in " +
        "Metra settings > Interface.",
) {
    category("Feed")
    // The author row install and the player's current video come with the row patch, the grid
    // cell's count with the grid patch.
    dependsOn(settingsPatch, sharedExtensionPatch, authorRowPatch, profileGridCountPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableEngagementRate()V",
        )
    }
}
