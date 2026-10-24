/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.shared

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction31t
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

/** The factory a merged lambda group hands out instances from, indexed by the lambda's number. */
private const val GROUP_FACTORY = "get\$arr\$"

/** R8's field holding the number that selects one lambda body from a merged group. */
private const val GROUP_INDEX_FIELD = "\$t"

/** How deep R8's split switch is followed before giving up. */
private const val MAX_DISPATCH_DEPTH = 8

/** How far back the constant handed to the factory is looked for. */
private const val CONSTANT_LOOKBACK = 4

/** How far past a switch target the call it makes is looked for. */
private const val TARGET_LOOKAHEAD = 4

/** Integer register copies R8 may place between a factory number and its call. */
private val INTEGER_MOVES = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)

/**
 * The one class that reads the setting [key] behind a lazy value, and the method on it of the
 * shape [gate] accepts.
 *
 * <p>TikTok generates a small class per AB-backed switch: one static field holding a lazy value,
 * a `<clinit>` that builds it, and one getter that reads it and answers. Hundreds of them are the
 * same shape, so what identifies one is the settings key its lazy value reads, and that key is a
 * plain string TikTok wrote.
 *
 * <p>Getting to it takes one of three routes, all of which this follows. The `<clinit>` can hold
 * the key itself. It can build a lambda of its own class, in which case the key is in that class's
 * one no-argument method. Or R8 has merged the lambda into a shared group, in which case the
 * `<clinit>` asks the group's factory for the instance numbered n, and the group's entry point is
 * a switch on that number which reaches the one body belonging to it. R8 splits a large switch
 * into a tree of them, so the switch is followed until what it reaches is not another switch.
 *
 * <p>The number is followed rather than the leaf's name. On the builds seen, the leaf of index n
 * is called `invoke$n`, which would make this a one-line lookup and would be one more thing R8
 * chooses.
 */
internal fun BytecodePatchContext.resolveLazyAbGate(
    what: String,
    key: String,
    gate: (Method, ClassDef) -> Boolean,
): MutableMethod {
    val (classDef, method) =
        LazyAbGateSearch(::classDefByOrNull).find(what, key, gate, ::classDefForEach)
    return mutableClassDefBy(classDef).findMutableMethodOf(method)
}

/**
 * The search behind [resolveLazyAbGate], over whatever [classOf] can look up. Separate from the
 * patch context so the rules can be run over a handful of classes built by hand.
 */
internal class LazyAbGateSearch(private val classOf: (String) -> ClassDef?) {

    /** The class that reads [key] and the one method on it of the shape [gate] accepts. */
    fun find(
        what: String,
        key: String,
        gate: (Method, ClassDef) -> Boolean,
        classes: ((ClassDef) -> Unit) -> Unit,
    ): Pair<ClassDef, Method> {
        val found = mutableListOf<Pair<ClassDef, List<Method>>>()
        classes { classDef ->
            // The shape first: it costs one pass over the class's own method signatures and
            // leaves a handful of candidates for the walk that follows to read instructions
            // for. Every method of the shape is kept rather than exactly one, so that a class
            // carrying two of them is refused by name below instead of being passed over here
            // as if it read some other key.
            val methods = classDef.methods.filter { gate(it, classDef) }
            if (methods.isEmpty()) return@classes
            val clinit = classDef.methods.firstOrNull {
                it.name == "<clinit>" && it.implementation != null
            } ?: return@classes
            if (!readsSettingsKey(clinit, key)) return@classes
            found += classDef to methods
        }
        if (found.size != 1) {
            val which = if (found.isEmpty()) "" else " (${found.joinToString { it.first.type }})"
            throw PatchException(
                "$what: expected one class whose lazily read setting is \"$key\", " +
                    "found ${found.size}$which.",
            )
        }
        val (classDef, methods) = found.single()
        if (methods.size != 1) {
            throw PatchException(
                "$what: ${classDef.type} reads \"$key\" but ${methods.size} of its methods " +
                    "have the gate's shape: " +
                    methods.joinToString { it.name + it.parameterTypes } + ".",
            )
        }
        return classDef to methods.single()
    }

    /** Whether the static initialiser reaches [key], directly or through the lambda it builds. */
    fun readsSettingsKey(clinit: Method, key: String): Boolean {
        if (clinit.hasExactString(key)) return true
        val instructions = clinit.implementation?.instructions?.toList() ?: return false
        instructions.forEachIndexed { index, instruction ->
            when (instruction.opcode) {
                Opcode.NEW_INSTANCE -> {
                    // A lambda class of its own. Its body is a no-argument method, and so is the
                    // bridge Kotlin adds beside it, so every one of them is read rather than the
                    // first: the bridge holds no string and can come first.
                    val built = instruction.getReference<TypeReference>()?.type
                        ?: return@forEachIndexed
                    val bodies = classOf(built)?.methods?.filter {
                        it.name != "<init>" && it.parameterTypes.none() && it.implementation != null
                    } ?: return@forEachIndexed
                    if (bodies.any { it.hasExactString(key) }) return true
                }
                Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE -> {
                    val factory = instruction.getReference<MethodReference>()
                        ?: return@forEachIndexed
                    if (factory.name != GROUP_FACTORY) return@forEachIndexed
                    val argument = (instruction as? FiveRegisterInstruction)?.registerC
                        ?: (instruction as? RegisterRangeInstruction)?.startRegister
                        ?: return@forEachIndexed
                    val number = constantBefore(instructions, index, argument)
                        ?: return@forEachIndexed
                    if (groupBodies(factory.definingClass, number).any { it.hasExactString(key) }) {
                        return true
                    }
                }
                else -> Unit
            }
        }
        return false
    }

    /**
     * The bodies the group's entry points dispatch to for [number].
     *
     * <p>A group is merged per interface, so one built from `Function0` lambdas dispatches from
     * a no-argument `invoke` and one built from `Function1` lambdas from a one-argument `invoke`.
     * Every entry point that opens on the number is followed, so the shape of the interface is
     * not a condition of finding the body.
     */
    fun groupBodies(groupType: String, number: Int): List<Method> {
        val group = classOf(groupType) ?: return emptyList()
        return group.methods.filter { it.dispatchesOnIndex() }.mapNotNull { entry ->
            var at: Method = entry
            repeat(MAX_DISPATCH_DEPTH) {
                val next = at.dispatchTarget(group, number) ?: return@mapNotNull null
                if (!next.dispatchesOnIndex()) return@mapNotNull next
                at = next
            }
            null
        }
    }
}

/**
 * The constant the factory is handed: the last one loaded into the [register] the call reads,
 * looking back a few instructions. Matched on the register rather than on being the nearest
 * constant, because the initialiser loads other numbers around the call and the nearest one is
 * not necessarily the one handed over. One ordinary register move is followed, with a fresh
 * lookback from that move, because R8 may copy the number into the factory's argument register.
 */
internal fun constantBefore(instructions: List<Instruction>, at: Int, register: Int): Int? =
    constantBefore(instructions, at, register, followMove = true)

private fun constantBefore(
    instructions: List<Instruction>,
    at: Int,
    register: Int,
    followMove: Boolean,
): Int? {
    for (index in (at - 1) downTo maxOf(0, at - CONSTANT_LOOKBACK)) {
        val instruction = instructions[index]
        if (instruction is WideLiteralInstruction &&
            instruction.opcode.name.startsWith("const") &&
            (instruction as? OneRegisterInstruction)?.registerA == register
        ) {
            return instruction.wideLiteral.toInt()
        }
        if (!followMove || instruction.opcode !in INTEGER_MOVES) continue
        val move = instruction as? TwoRegisterInstruction ?: continue
        if (move.registerA != register) continue
        return constantBefore(instructions, index, move.registerB, followMove = false)
    }
    return null
}

/** Whether the method opens by switching on the group's own lambda number. */
internal fun Method.dispatchesOnIndex(): Boolean {
    val instructions = implementation?.instructions?.toList() ?: return false
    if (instructions.size < 2) return false
    val read = instructions[0]
    if (read.opcode != Opcode.IGET) return false
    val field = read.getReference<FieldReference>() ?: return false
    if (field.definingClass != definingClass ||
        field.name != GROUP_INDEX_FIELD ||
        field.type != "I"
    ) {
        return false
    }
    val switch = instructions[1]
    if (switch.opcode != Opcode.PACKED_SWITCH && switch.opcode != Opcode.SPARSE_SWITCH) return false
    return (read as? TwoRegisterInstruction)?.registerA ==
        (switch as? OneRegisterInstruction)?.registerA
}

/**
 * The method the switch this body opens with calls for [number].
 *
 * <p>Only the switch that follows the read of the number is consulted, the one
 * [dispatchesOnIndex] saw. A later switch in the same method is on something else, and an
 * answer read from it would be a body for some other value that happened to share the number.
 */
internal fun Method.dispatchTarget(group: ClassDef, number: Int): Method? {
    val instructions = implementation?.instructions?.toList() ?: return null
    if (!dispatchesOnIndex()) return null
    val addresses = IntArray(instructions.size)
    var address = 0
    instructions.forEachIndexed { index, instruction ->
        addresses[index] = address
        address += instruction.codeUnits
    }
    val switch = instructions[1] as? Instruction31t ?: return null
    val switchAddress = addresses[1]
    val payload = instructions.getOrNull(
        addresses.indexOf(switchAddress + switch.codeOffset),
    ) as? SwitchPayload ?: return null
    val element = payload.switchElements.firstOrNull { it.key == number } ?: return null
    val target = addresses.indexOf(switchAddress + element.offset)
    if (target < 0) return null
    for (candidate in target until minOf(instructions.size, target + TARGET_LOOKAHEAD)) {
        val call = instructions[candidate]
        if (call.opcode != Opcode.INVOKE_STATIC && call.opcode != Opcode.INVOKE_STATIC_RANGE) {
            continue
        }
        val reference = call.getReference<MethodReference>() ?: continue
        if (reference.definingClass != group.type) continue
        return group.methods.firstOrNull {
            it.name == reference.name &&
                it.parameterTypes.map(CharSequence::toString) ==
                reference.parameterTypes.map(CharSequence::toString) &&
                it.returnType == reference.returnType
        }
    }
    return null
}

/**
 * Whether the method is one of these gates' getters: read the lazy value, unwrap the number it
 * holds, answer from it.
 *
 * <p>The key says which class; this says the method on it is the one that reads the setting
 * rather than some other member of the same signature. The read is matched by the name and
 * shape of `getValue` alone, because the interface it is called on is `kotlin.Lazy` under a name
 * R8 assigns per build; what pins it down is the unwrap of a `Number` straight after.
 */
/**
 * Whether the method reads the lazy value itself, or through one static no-argument helper on
 * its own class that does. 46.9.3 splits the comment sort gate that way: `LIZ(Aweme)Z` asks
 * `LIZIZ()Z` on the same class and the read moved there with it, while the getter the patch
 * hooks kept its shape. One level, on the owner only: a helper elsewhere is some other gate.
 */
internal fun Method.readsLazyAb(owner: ClassDef): Boolean {
    if (isLazyAbRead()) return true
    val helpers = implementation?.instructions
        ?.mapNotNull { it.getReference<MethodReference>() }
        ?.filter { it.definingClass == owner.type && it.parameterTypes.none() }
        ?: return false
    return helpers.any { helper ->
        owner.methods.any {
            it.name == helper.name && it.parameterTypes.none() &&
                it.returnType == helper.returnType && AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.isLazyAbRead()
        }
    }
}

internal fun Method.isLazyAbRead(): Boolean {
    val calls = implementation?.instructions
        ?.mapNotNull { it.getReference<MethodReference>() }
        ?.toList() ?: return false
    val readsValue = calls.any {
        it.name == "getValue" && it.returnType == "Ljava/lang/Object;" && it.parameterTypes.none()
    }
    val unwrapsNumber = calls.any {
        it.definingClass == "Ljava/lang/Number;" && it.name == "intValue" && it.returnType == "I"
    }
    return readsValue && unwrapsNumber
}

/** Whether any string constant of the method is exactly this one. */
private fun Method.hasExactString(value: String) =
    implementation?.instructions?.any {
        it.getReference<StringReference>()?.string == value
    } == true
