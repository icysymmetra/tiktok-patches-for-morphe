package app.morphe.patches.tiktok.misc.diagnostics

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private val INSTALLERS = listOf("nEnable", "nEnableV2")
private const val RECEIPT = "Lapp/morphe/extension/shared/diagnostics/RecoveryCapture;"

/** Resolve both native installers and their guarded wrappers before changing either method. */
internal fun softReferenceInstallers(classes: Iterable<ClassDef>): List<Method> {
    fun fail(message: String): Nothing = throw PatchException("Soft-reference trial: $message")
    val owners = classes.filter { owner -> INSTALLERS.all { name -> owner.methods.any { it.name == name } } }
    val owner = owners.singleOrNull() ?: fail("expected one native installer owner, found ${owners.size}")
    val methods = owner.methods.toList()
    val installedFlags = mutableSetOf<String>()
    val wrappers = INSTALLERS.map { name ->
        val native = methods.singleOrNull { it.name == name } ?: fail("ambiguous $name")
        if (native.returnType != "I" || native.parameterTypes.map { it.toString() } != listOf("J", "I") ||
            !AccessFlags.STATIC.isSet(native.accessFlags) || !AccessFlags.NATIVE.isSet(native.accessFlags)) {
            fail("$name no longer has the static native (JI)I contract")
        }
        fun calls(method: Method) = method.implementation?.instructions?.toList().orEmpty().count { instruction ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            ref?.definingClass == owner.type && ref.name == name && ref.returnType == "I" &&
                ref.parameterTypes.map { it.toString() } == listOf("J", "I")
        }
        val wrapper = methods.singleOrNull { calls(it) > 0 } ?: fail("expected one wrapper for $name")
        val instructions = wrapper.implementation!!.instructions.toList()
        if (calls(wrapper) != 1 || wrapper.returnType != "V" ||
            wrapper.parameterTypes.map { it.toString() } != listOf("I", "J") ||
            !AccessFlags.STATIC.isSet(wrapper.accessFlags) ||
            !AccessFlags.DECLARED_SYNCHRONIZED.isSet(wrapper.accessFlags) ||
            instructions.count { it.opcode == Opcode.MONITOR_ENTER } != 1 ||
            instructions.none { it.opcode == Opcode.MONITOR_EXIT }) fail("changed guarded wrapper for $name")
        fun flags(opcode: Opcode) = instructions.filter { it.opcode == opcode }.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? FieldReference
        }.filter { it.definingClass == owner.type && it.type == "Z" }.map { it.name }.toSet()
        val reads = flags(Opcode.SGET_BOOLEAN)
        if (reads.size != 1 || flags(Opcode.SPUT_BOOLEAN) != reads) fail("changed installed flag for $name")
        installedFlags += reads
        wrapper
    }
    if (wrappers.distinct().size != 2) fail("installers do not have separate wrappers")
    if (installedFlags.size != 1) fail("installers no longer share their installed flag")
    // A new direct native caller would evade an entry bypass. Reject that target explicitly.
    classes.forEach { candidate -> candidate.methods.forEach { method ->
        method.implementation?.instructions?.forEach { instruction ->
            val ref = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            if (ref?.definingClass == owner.type && ref.name in INSTALLERS && method !in wrappers) {
                fail("native installer has a caller outside its verified wrapper")
            }
        }
    } }
    return wrappers
}

context(context: BytecodePatchContext)
internal fun applySoftReferenceTrial() {
    val classes = mutableListOf<ClassDef>()
    context.classDefForEach { classes += it }
    val wrappers = softReferenceInstallers(classes)
    wrappers.forEachIndexed { index, method ->
        context.mutableClassDefBy(method.definingClass).findMutableMethodOf(method).addInstructions(0, """
            invoke-static {}, $RECEIPT->softReferenceV${index + 1}Bypassed()V
            return-void
        """.trimIndent())
    }
}
