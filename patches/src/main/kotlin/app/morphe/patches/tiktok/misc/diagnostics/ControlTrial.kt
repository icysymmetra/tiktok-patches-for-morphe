package app.morphe.patches.tiktok.misc.diagnostics

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import java.io.File
import java.security.MessageDigest

private const val OBSERVER = "Lapp/morphe/extension/shared/diagnostics/ControlObservation;"
private const val SPEC = "metra-control-arm.txt"
internal data class HeapObservationTargets(val wrapper: Method, val native: Method, val gate: MethodReference, val provider: Method)

/** Stable native contract and config key, never obfuscated wrapper/provider names. */
internal fun heapObservationTargets(classes: List<ClassDef>): HeapObservationTargets {
    fun fail(message: String): Nothing = throw PatchException("Control observations: $message")
    val native = classes.flatMap { it.methods }.singleOrNull {
        it.name == "nativeAdjustHeapGcParameters" && it.parameterTypes.map { p -> p.toString() } == listOf("J", "J", "D") &&
            it.returnType == "I" && AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.NATIVE.isSet(it.accessFlags)
    } ?: fail("expected one heap-parameter native (JJD)I")
    fun reference(i: com.android.tools.smali.dexlib2.iface.instruction.Instruction) =
        (i as? ReferenceInstruction)?.reference as? MethodReference
    val callers = classes.flatMap { it.methods }.filter { m -> m.implementation?.instructions?.any {
        reference(it)?.let { r -> r.definingClass == native.definingClass && r.name == native.name } == true
    } == true }
    val wrapper = callers.singleOrNull() ?: fail("native caller contract changed")
    val code = wrapper.implementation!!.instructions.toList()
    val gate = code.firstOrNull()?.let(::reference) ?: fail("missing library gate")
    if (!AccessFlags.STATIC.isSet(wrapper.accessFlags) || wrapper.returnType != "V" ||
        wrapper.parameterTypes.map { it.toString() } != listOf("D", "J", "J") ||
        wrapper.implementation!!.tryBlocks.isNotEmpty() || code.map { it.opcode } != listOf(
            Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_NEZ, Opcode.RETURN_VOID,
            Opcode.MOVE_WIDE, Opcode.MOVE_WIDE, Opcode.INVOKE_STATIC_RANGE, Opcode.RETURN_VOID) ||
        gate.returnType != "Z" || gate.parameterTypes.isNotEmpty()) fail("guarded heap wrapper changed")
    val provider = classes.flatMap { it.methods }.singleOrNull { m ->
        m.returnType == "Ljava/lang/Object;" && m.parameterTypes.isEmpty() &&
            m.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == "art_gc_param_ab" } == true
    } ?: fail("expected one nullable art_gc_param_ab provider")
    if (provider.implementation!!.instructions.none { it.opcode == Opcode.RETURN_OBJECT }) fail("provider returns changed")
    return HeapObservationTargets(wrapper, native, gate, provider)
}

context(context: BytecodePatchContext)
internal fun configureControlTrial(arm: String) {
    File(context.fileWorkspace, SPEC).writeText(arm)
    if (arm == "off") return
    val classes = mutableListOf<ClassDef>(); context.classDefForEach { classes += it }
    val target = heapObservationTargets(classes) // Resolve everything before mutating.
    val owner = context.mutableClassDefBy(target.wrapper.definingClass)
    val original = owner.findMutableMethodOf(target.wrapper)
    val method = MutableMethod(ImmutableMethod(original.definingClass, original.name, original.parameters,
        original.returnType, original.accessFlags, original.annotations, original.hiddenApiRestrictions,
        MutableMethodImplementation(14)))
    owner.methods.remove(original); owner.methods.add(method)
    method.addInstructions(0, """
        invoke-static/range {p0 .. p5}, $OBSERVER->begin(DJJ)I
        move-result v0
        invoke-static {}, ${target.gate}
        move-result v1
        if-nez v1, :loaded
        invoke-static {v0}, $OBSERVER->unavailable(I)V
        return-void
        :loaded
        move-wide v2, p2
        move-wide v4, p4
        move-wide v6, p0
        invoke-static {v0}, $OBSERVER->nativeStarting(I)V
        invoke-static/range {v2 .. v7}, ${target.native.definingClass}->${target.native.name}(JJD)I
        move-result v1
        invoke-static {v0, v1}, $OBSERVER->returned(II)V
        return-void
    """.trimIndent())
    val provider = context.mutableClassDefBy(target.provider.definingClass).findMutableMethodOf(target.provider)
    provider.implementation!!.instructions.toList().withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .asReversed().forEach { (index, instruction) ->
            val register = (instruction as OneRegisterInstruction).registerA
            provider.addInstructionsAtControlFlowLabel(index, "invoke-static/range {v$register .. v$register}, $OBSERVER->configObserved(Ljava/lang/Object;)V")
        }
}

/** Run after the shared context hooks too, which are injected by a deeper dependency's finalizer. */
context(context: BytecodePatchContext)
internal fun finishControlTrial() {
    val spec = File(context.fileWorkspace, SPEC)
    if (!spec.isFile || spec.readText() == "off") return
    val calls = sortedSetOf<String>()
    val owners = sortedSetOf<String>()
    context.classDefForEach { cls ->
        if (!cls.type.startsWith("Lapp/morphe/extension/")) cls.methods.forEach { m ->
            m.implementation?.instructions?.forEach { instruction ->
                val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                if (ref?.definingClass?.startsWith("Lapp/morphe/extension/") == true) {
                    calls += "${cls.type}->${m.name}${m.parameterTypes}${m.returnType} $ref"
                    owners += ref.definingClass
                }
            }
        }
    }
    val inventory = calls.joinToString("\n", postfix = "\n")
    val sha = MessageDigest.getInstance("SHA-256").digest(inventory.toByteArray()).joinToString("") { "%02x".format(it) }
    // The arm is declared. The observed inventory/hash, and the external APK proof, verify scope.
    val profile = "declared_arm=${spec.readText()} target_call_sites=${calls.size} target_owners=${owners.size} hook_sha256=$sha"
    File(context.fileWorkspace, "metra-control-hook-inventory.txt").writeText(inventory)
    context.mutableClassDefBy(OBSERVER).methods.single { it.name == "profile" && it.parameterTypes.isEmpty() }.returnEarly(profile)
}
