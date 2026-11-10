package app.morphe

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.InstructionLocation
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference

/**
 * Whether the patcher would take [method] of [classDef] for this fingerprint, judged from the
 * fingerprint's own fields with Morphe Patcher 2.0's rules: a type written whole (`L...;`, a
 * primitive) is compared whole, one written without its `;` by prefix, a bare `...;` by suffix;
 * parameters one for one and the same count; each string filter uses its declared comparison,
 * with one load answering one string at most (see the strings check); and the custom
 * predicate last.
 *
 * <p>Fixture tests hold the real fingerprint objects to a build with this, instead of a copy of
 * their shape, so a change to a fingerprint is what the test sees (review #7: a copied string
 * list hid the 46.x keep-list failure until a full apply). Instruction filters that may each sit
 * anywhere after the one before are judged by the filters' own `matches`, which is the patcher's
 * code: the method takes them when its instructions hold them in order, with gaps, and taking
 * each filter's earliest instruction is enough to know. Filters placed any other way and class
 * fingerprints are not modelled, and a fingerprint using either is refused rather than half
 * judged.
 */
internal fun Fingerprint.takes(method: Method, classDef: ClassDef): Boolean {
    require(classFingerprint == null) { "class fingerprints are not modelled: $this" }
    val ordered = filters.orEmpty().also { all ->
        require(all.all { it.location is InstructionLocation.MatchAfterAnywhere }) {
            "only instruction filters placed anywhere are modelled: $this"
        }
    }
    declaredDefiningClass?.let { if (!typeMatches(method.definingClass, it)) return false }
    declaredName?.let { if (method.name != it) return false }
    declaredAccessFlags?.let { if (method.accessFlags != it) return false }
    declaredIsStatic?.let { if (AccessFlags.STATIC.isSet(method.accessFlags) != it) return false }
    declaredReturnType?.let { if (!typeMatches(method.returnType, it)) return false }
    declaredParameters?.let { wanted ->
        val actual = method.parameterTypes.map(CharSequence::toString)
        if (actual.size != wanted.size || actual.zip(wanted).any { (have, want) -> !typeMatches(have, want) }) return false
    }
    stringFilters?.let { wanted ->
        // The patcher walks the method's string loads in order and lets each one answer only the
        // first still-wanted string it contains, then drops that one. Two wanted strings that sit
        // inside one literal therefore never both match there. Checking each wanted string on its
        // own passed #169's "CommentLongPressFragment@" plus ".loadLongPressItems", which are both
        // pieces of one literal, and the real apply then failed.
        val remaining = wanted.toMutableList()
        method.implementation?.instructions?.forEach { instruction ->
            if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) return@forEach
            val answered = remaining.indexOfFirst { it.matches(method, instruction) }
            if (answered >= 0) remaining.removeAt(answered)
        }
        if (remaining.isNotEmpty()) return false
    }
    if (ordered.isNotEmpty()) {
        val instructions = method.implementation?.instructions?.toList() ?: return false
        var from = 0
        for (wanted in ordered) {
            val at = (from until instructions.size).firstOrNull { wanted.matches(method, instructions[it]) }
                ?: return false
            from = at + 1
        }
    }
    custom?.let { if (!it(method, classDef)) return false }
    return true
}

/*
 * The fields a fingerprint was declared with. Morphe patcher 1.15 made these internal (its #230:
 * a partial type written there reads like the matched method's whole one), so tests that judge a
 * fingerprint by its own declaration read the backing fields.
 */
internal val Fingerprint.declaredDefiningClass: String? get() = declared("definingClass")
internal val Fingerprint.declaredName: String? get() = declared("name")
internal val Fingerprint.declaredAccessFlags: Int? get() = declared("accessFlags")
internal val Fingerprint.declaredIsStatic: Boolean? get() = declared("isStatic")
internal val Fingerprint.declaredReturnType: String? get() = declared("returnType")
internal val Fingerprint.declaredParameters: List<String>? get() = declared("parameters")

@Suppress("UNCHECKED_CAST")
private fun <T> Fingerprint.declared(field: String): T? =
    Fingerprint::class.java.getDeclaredField(field).also { it.isAccessible = true }.get(this) as T?

/** The patcher's comparison for a type as a fingerprint writes it (StringComparisonType). */
internal fun typeMatches(actual: String, wanted: String): Boolean = when {
    wanted.length == 1 -> if (wanted[0] == 'L' || wanted[0] == '[') actual.startsWith(wanted) else actual == wanted
    wanted.startsWith("[") || wanted.startsWith("L") -> if (wanted.endsWith(";")) actual == wanted else actual.startsWith(wanted)
    wanted.endsWith(";") -> actual.endsWith(wanted)
    else -> actual.contains(wanted)
}
