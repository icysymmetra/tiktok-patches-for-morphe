/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/** What the Compose palette's toString prints before its UIPageGrouped2 color. */
internal const val PAGE_GROUPED_2_LABEL = ", UIPageGrouped2="

/**
 * UIPageGrouped2 in TikTok's dark Compose palette: the gray of the cards on its Compose settings
 * pages, Settings and privacy among them (#152). The View pages read the same token through
 * attr/a0h on 47.4.3, whose dark literal (agp) the resource side already rewrites.
 */
internal const val DARK_CARD_GRAY = 0xff1e1e1eL

/** How far back a card color's literal may sit from its conversion. The builder sets a handful ahead. */
private const val LITERAL_REACH = 40

/**
 * One write of the card color in a palette builder: [index] is the field write, [register] the
 * wide register it stores, and [conversion] the static `(J)J` that packed the value into a Compose
 * Color the instruction before (a shift left by 32).
 */
internal class CardWrite(val index: Int, val register: Int, val conversion: MethodReference, val argument: Int)

/** The palette field [method]'s toString prints after [PAGE_GROUPED_2_LABEL], or null. */
internal fun Method.pageGrouped2Field(): FieldReference? {
    if (name != "toString" || parameterTypes.isNotEmpty() || returnType != "Ljava/lang/String;") return null
    val instructions = implementation?.instructions?.toList() ?: return null
    val label = instructions.indexOfFirst { it.getReference<StringReference>()?.string == PAGE_GROUPED_2_LABEL }
    if (label < 0) return null
    val field = instructions.drop(label + 1).take(4)
        .firstOrNull { it.opcode == Opcode.IGET_WIDE }
        ?.getReference<FieldReference>() ?: return null
    return field.takeIf { it.definingClass == definingClass && it.type == "J" }
}

/** The palette's card field, read off the one class whose toString labels it. Null unless exactly one does. */
internal fun Iterable<ClassDef>.composeCardField(): FieldReference? =
    flatMap { classDef -> classDef.methods.mapNotNull { it.pageGrouped2Field() } }
        .distinctBy { "${it.definingClass}->${it.name}" }
        .singleOrNull()

/**
 * Every write of [field] in this method, each a Compose Color packed by a static `(J)J` and
 * stored straight away, or null when any write is shaped otherwise or the writes use different
 * conversions. A builder this can't read whole is left alone.
 */
internal fun Method.cardWrites(field: FieldReference): List<CardWrite>? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val writes = mutableListOf<CardWrite>()
    instructions.forEachIndexed { index, instruction ->
        if (instruction.opcode != Opcode.IPUT_WIDE) return@forEachIndexed
        val target = instruction.getReference<FieldReference>() ?: return@forEachIndexed
        if (target.definingClass != field.definingClass || target.name != field.name || target.type != "J") {
            return@forEachIndexed
        }
        if (index < 2) return null
        val register = (instruction as OneRegisterInstruction).registerA
        val move = instructions[index - 1]
        if (move.opcode != Opcode.MOVE_RESULT_WIDE || (move as OneRegisterInstruction).registerA != register) return null
        val call = instructions[index - 2]
        val conversion = call.getReference<MethodReference>() ?: return null
        if (conversion.returnType != "J" || conversion.parameterTypes.map { it.toString() } != listOf("J")) return null
        val argument = when (call.opcode) {
            Opcode.INVOKE_STATIC -> (call as FiveRegisterInstruction).registerC
            Opcode.INVOKE_STATIC_RANGE -> (call as RegisterRangeInstruction).startRegister
            else -> return null
        }
        writes += CardWrite(index, register, conversion, argument)
    }
    if (writes.isEmpty()) return null
    if (writes.map { "${it.conversion.definingClass}->${it.conversion.name}" }.distinct().size != 1) return null
    return writes
}

/**
 * The literal a card write converts, when it is set by a const-wide shortly before and nothing
 * touches its register in between; null otherwise.
 */
internal fun Method.cardLiteral(write: CardWrite): Long? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val stop = maxOf(0, write.index - 2 - LITERAL_REACH)
    for (at in write.index - 3 downTo stop) {
        val instruction = instructions[at]
        if (!instruction.opcode.setsRegister() || instruction !is OneRegisterInstruction) continue
        val set = instruction.registerA
        val wide = instruction.opcode.setsWideRegister()
        val touches = set == write.argument || set == write.argument + 1 || (wide && set + 1 == write.argument)
        if (!touches) continue
        return if (set == write.argument && wide && instruction is WideLiteralInstruction) instruction.wideLiteral else null
    }
    return null
}

/**
 * Whether this is the dark palette builder: a static method taking nothing that returns the
 * palette and whose first card color is TikTok's dark gray. The builder goes on to write the card
 * color again for TikTok's two tinted dark variants (#1A1E24 and #16191D on 47.1.4); the high
 * contrast dark palette is another builder, with its own lighter cards, and stays as it is.
 */
internal fun Method.isDarkPaletteBuilder(field: FieldReference): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags) || parameterTypes.isNotEmpty() || returnType != field.definingClass) {
        return false
    }
    val writes = cardWrites(field) ?: return false
    return cardLiteral(writes.first()) == DARK_CARD_GRAY
}

/** The opaque Compose literal for an AMOLED color, `#RRGGBB` or `#FFRRGGBB`. */
internal fun composeArgb(color: String): Long = 0xff000000L or color.takeLast(6).toLong(16)

/** Each card color this builder writes becomes [argb], packed by the builder's own conversion. */
internal fun MutableMethod.paintCards(field: FieldReference, argb: Long) {
    val writes = cardWrites(field)
        ?: throw PatchException("AMOLED dark theme: the dark palette's card colors are not written the way they were.")
    writes.asReversed().forEach { write ->
        val register = write.register
        val conversion = write.conversion
        addInstructions(
            write.index,
            """
                const-wide v$register, 0x${argb.toString(16)}L
                invoke-static/range {v$register .. v${register + 1}}, ${conversion.definingClass}->${conversion.name}(J)J
                move-result-wide v$register
            """,
        )
    }
}

/**
 * The cards on TikTok's Compose settings pages, painted [color] in the dark palette (#152).
 *
 * TikTok's Settings and privacy list is Compose, and its cards fill with UIPageGrouped2 from the
 * Compose palette, which reads no resource, so the style rewrite above never reached it. This
 * changes the dark palette builder only: Light mode, the high contrast palette and every sheet
 * (the profile menu panel's cards are UISheetGrouped2) keep their colors. It runs only as part of
 * AMOLED dark theme, with that patch's color, and it is held to the declared build by
 * AmoledComposeCardsTest. A build whose palette wasn't read is refused here the way the resource
 * side refuses it, so neither half goes in without the other.
 */
internal fun amoledComposeCardsPatch(color: () -> String?) = bytecodePatch {
    execute {
        // Refuses an unread build before anything changes, as the resource side does.
        darkBackgroundColors(packageMetadata.versionName)
        val chosen = amoledColor(color())
        val candidates = mutableListOf<ClassDef>()
        classDefForEach { classDef ->
            if (classDef.methods.any { it.name == "toString" && it.pageGrouped2Field() != null }) candidates += classDef
        }
        val field = candidates.composeCardField()
            ?: throw PatchException("AMOLED dark theme: couldn't find the Compose palette's card color.")
        val builders = mutableListOf<Pair<String, String>>()
        classDefForEach { classDef ->
            classDef.methods.filter { it.isDarkPaletteBuilder(field) }.forEach { builders += classDef.type to it.name }
        }
        val (owner, name) = builders.singleOrNull()
            ?: throw PatchException("AMOLED dark theme: expected one dark palette builder, found ${builders.size}.")
        val builder = mutableClassDefBy(owner).methods.single {
            it.name == name && it.parameterTypes.isEmpty() && it.returnType == field.definingClass
        }
        builder.paintCards(field, composeArgb(chosen))
    }
}

/** The patch's color option, held to an opaque `#RRGGBB` or `#FFRRGGBB`. */
internal fun amoledColor(value: String?): String {
    val color = value ?: throw PatchException("Choose a background color")
    if (!Regex("#[0-9a-fA-F]{6}|#[fF]{2}[0-9a-fA-F]{6}").matches(color)) {
        throw PatchException("Background color must be opaque #RRGGBB or #FFRRGGBB")
    }
    return color
}
