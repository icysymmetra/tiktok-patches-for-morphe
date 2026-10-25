/*
 * Copyright 2026 Metra contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.feedfilter

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

internal const val REJECTED_CACHE_COPY = "metraRejectedCacheCopy"
internal const val EMPTY_CACHE_COPY = "metraEmptyCacheCopy"

/** Copy all constructor-owned state and change only success, without writing any final field. */
internal fun rejectedCacheCopy(owner: ClassDef, success: FieldReference, emptyPayload: FieldReference? = null): MutableMethod {
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || owner.superclass != "Ljava/lang/Object;") {
        throw PatchException("Cache result is not a public Object-derived value")
    }
    val fields = owner.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }
    if (fields.count { it.type == "Z" } != 1 || success !in fields || fields.size !in 2..14) {
        throw PatchException("Unreviewed cache result state: ${owner.type}")
    }
    if (fields.any { !AccessFlags.PUBLIC.isSet(it.accessFlags) }) {
        throw PatchException("Cache copy cannot read an inaccessible field")
    }
    val name = if (emptyPayload == null) REJECTED_CACHE_COPY else EMPTY_CACHE_COPY
    if (emptyPayload != null && emptyPayload !in fields) throw PatchException("Cache payload does not belong to the result")
    if (owner.methods.any { it.name == name }) {
        throw PatchException("Cache result already contains the Metra copy helper")
    }
    val candidates = owner.methods.mapNotNull { constructor ->
        // Dexlib may expose parameter descriptors as MethodParameter CharSequences.
        // Normalize before comparing them with field descriptor Strings.
        val parameterTypes = constructor.parameterTypes.map(CharSequence::toString)
        if (constructor.name != "<init>" || !AccessFlags.PUBLIC.isSet(constructor.accessFlags) ||
            parameterTypes.size != fields.size || parameterTypes.any {
                it != "Z" && !it.startsWith("L") && !it.startsWith("[")
            }) return@mapNotNull null
        val body = constructor.implementation ?: return@mapNotNull null
        val receiver = body.registerCount - parameterTypes.size - 1
        val code = body.instructions.toList()
        // The reviewed constructor is a pure value store. A future null check, state mutation
        // or side effect invalidates the proof that a null-payload copy is safe.
        if (body.tryBlocks.isNotEmpty() || code.size != fields.size + 2 ||
            code.last().opcode != Opcode.RETURN_VOID) return@mapNotNull null
        val init = code.first()
        val objectInit = init.getReference<MethodReference>()
        val initializesReceiver = when (init.opcode) {
            Opcode.INVOKE_DIRECT -> (init as FiveRegisterInstruction).let {
                it.registerCount == 1 && it.registerC == receiver
            }
            Opcode.INVOKE_DIRECT_RANGE -> (init as RegisterRangeInstruction).let {
                it.registerCount == 1 && it.startRegister == receiver
            }
            else -> false
        }
        if (!initializesReceiver || objectInit?.toString() != "Ljava/lang/Object;-><init>()V") {
            return@mapNotNull null
        }
        val copied = parameterTypes.mapIndexed { index, type ->
            val opcode = if (type == "Z") Opcode.IPUT_BOOLEAN else Opcode.IPUT_OBJECT
            body.instructions.filter { instruction ->
                instruction.opcode == opcode && (instruction as TwoRegisterInstruction).let {
                    it.registerA == receiver + index + 1 && it.registerB == receiver
                }
            }.mapNotNull { it.getReference<FieldReference>() }.singleOrNull()
                ?.takeIf { it.definingClass == owner.type && it.type == type }
                ?: return@mapNotNull null
        }
        if (copied.toSet() != fields.toSet() || copied.size != copied.toSet().size) return@mapNotNull null
        constructor to copied
    }
    if (candidates.size != 1) throw PatchException("Expected one complete cache result constructor, found ${candidates.size}")
    val (constructor, copied) = candidates.single()
    val locals = copied.size + 1
    return MutableMethod(ImmutableMethod(owner.type, name,
        listOf(ImmutableMethodParameter(owner.type, null, null)), owner.type,
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,
        null, null, ImmutableMethodImplementation(locals + 1, emptyList(), null, null))).apply {
        val arguments = copied.mapIndexed { index, field ->
            val register = index + 1
            when {
                emptyPayload == null && field == success -> "const/4 v$register, 0x0"
                field == emptyPayload -> "const/4 v$register, 0x0"
                field.type == "Z" -> "iget-boolean v$register, p0, $field"
                else -> "iget-object v$register, p0, $field"
            }
        }.joinToString("\n")
        addInstructions(0, """
            new-instance v0, ${owner.type}
            $arguments
            invoke-direct/range {v0 .. v${copied.size}}, $constructor
            return-object v0
        """)
    }
}
