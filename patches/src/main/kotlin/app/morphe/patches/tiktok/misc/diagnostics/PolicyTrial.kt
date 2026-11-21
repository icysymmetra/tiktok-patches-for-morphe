package app.morphe.patches.tiktok.misc.diagnostics

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.MutableMethodImplementation
import com.android.tools.smali.dexlib2.iface.*
import com.android.tools.smali.dexlib2.iface.instruction.*
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter

private const val RECEIPT = "Lapp/morphe/extension/shared/diagnostics/PolicyObservation;"
internal data class PolicyTarget(val native: Method, val caller: Method, val observer: String)
internal data class PolicyTargets(val calls: List<PolicyTarget>, val youngRequest: Method, val youngIndex: Int)
private fun MethodReference.signature() = "$definingClass->$name${parameterTypes.map { it.toString() }}$returnType"
private fun Instruction.methodRef() = (this as? ReferenceInstruction)?.reference as? MethodReference

/** Exact native contracts, all direct references and the young worker's construction boundary. */
internal fun policyTargets(classes: List<ClassDef>): PolicyTargets {
    fun fail(text: String): Nothing = throw PatchException("GC policy trial: $text")
    val methods = classes.flatMap { it.methods }
    fun callers(target: MethodReference): List<Method> {
        val owner=target.definingClass; val name=target.name; val result=target.returnType
        val params=target.parameterTypes.map { it.toString() }
        return methods.filter { m -> m.implementation?.instructions?.any {
            val ref=it.methodRef()
            ref!=null && ref.definingClass==owner && ref.name==name && ref.returnType==result &&
                ref.parameterTypes.map { p->p.toString() }==params
        } == true }
    }
    fun native(name: String, params: List<String>, result: String): Method = methods.singleOrNull {
        it.name==name && it.parameterTypes.map { p->p.toString() }==params && it.returnType==result &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.NATIVE.isSet(it.accessFlags)
    } ?: fail("native $name contract changed")
    val specs = listOf(Triple("adjustGCOption",listOf("I","J","J"),"enterConfig"),
        Triple("adjustGCOptionStart",listOf("I","Z","J","Z","J"),"enterStart"),
        Triple("adjustGCOptionEnd",listOf("I","Z","Z"),"enterEnd"))
    val calls=specs.map { (name,params,observer) ->
        val n=native(name,params,"I");val m=callers(n).singleOrNull()?:fail("$name must have one direct caller")
        val expected=when(name){"adjustGCOption"->listOf("J","J");"adjustGCOptionStart"->listOf("J","J","Z","Z");else->listOf("Z","Z")}
        if(m.definingClass!=n.definingClass || AccessFlags.STATIC.isSet(m.accessFlags) || m.returnType!="V" ||
            m.parameterTypes.map { it.toString() }!=expected || !AccessFlags.DECLARED_SYNCHRONIZED.isSet(m.accessFlags) ||
            m.implementation!!.instructions.count { it.opcode==Opcode.MONITOR_ENTER }!=1) fail("$name wrapper changed")
        val code=m.implementation!!.instructions.toList();val hits=code.withIndex().filter { it.value.methodRef()?.signature()==n.signature() }
        if(hits.size!=1 || code.getOrNull(hits.single().index+1)?.opcode?.name?.startsWith("move-result")==true) fail("$name result is now consumed")
        PolicyTarget(n,m,observer)
    }
    if(calls.map { it.native.definingClass }.distinct().size!=1)fail("collection policies split across owners")
    val young=native("doYoungGcOpt",listOf("D"),"Z")
    val worker=callers(young).singleOrNull()?:fail("young native must have one caller")
    val code=worker.implementation!!.instructions.toList();val index=code.indexOfFirst { it.methodRef()?.signature()==young.signature() }
    if(worker.name!="run" || worker.returnType!="V" || worker.parameterTypes.isNotEmpty() ||
        code.getOrNull(index+1)?.opcode?.name?.startsWith("move-result")==true)fail("young worker contract changed")
    val workerClass=classes.single { it.type==worker.definingClass }
    if(!AccessFlags.FINAL.isSet(workerClass.accessFlags)||"Ljava/lang/Runnable;" !in workerClass.interfaces)fail("young worker is no longer final Runnable")
    val constructors=workerClass.methods.filter { it.name=="<init>" }.toList()
    if(constructors.size!=1 || constructors.single().parameterTypes.map { it.toString() }!=listOf("D"))fail("young constructor changed")
    val request=callers(constructors.single()).singleOrNull()?:fail("young worker has alternative construction paths")
    if(!AccessFlags.STATIC.isSet(request.accessFlags)||request.returnType!="V"||request.parameterTypes.map { it.toString() }!=listOf("D")||
        request.implementation!!.tryBlocks.isNotEmpty()||request.implementation!!.registerCount<3)fail("young request boundary changed")
    val requestCode=request.implementation!!.instructions.toList()
    if(requestCode.map { it.opcode }!=listOf(Opcode.INVOKE_STATIC,Opcode.MOVE_RESULT,Opcode.IF_EQZ,
        Opcode.SGET_OBJECT,Opcode.IGET_OBJECT,Opcode.IF_EQZ,Opcode.NEW_INSTANCE,Opcode.INVOKE_DIRECT,
        Opcode.INVOKE_INTERFACE,Opcode.RETURN_VOID))fail("young eligibility gates changed")
    val resolved=calls+PolicyTarget(young,worker,"enterYoung")
    resolved.forEachIndexed { kind,target ->
        if(classes.single { it.type==target.native.definingClass }.methods.any { it.name=="metraPolicy$kind" })fail("policy bridge already exists")
    }
    return PolicyTargets(resolved,request,6)
}

context(context: BytecodePatchContext)
internal fun applyPolicyObservations(targets: PolicyTargets, bypass: Boolean) {
    context.mutableClassDefBy(RECEIPT).methods.single { it.name=="bypassEnabled" }.returnEarly(bypass)
    targets.calls.forEachIndexed { kind, target ->
        val owner=context.mutableClassDefBy(target.native.definingClass)
        val name="metraPolicy${kind}"
        if(owner.methods.any { it.name==name })throw PatchException("GC policy trial: duplicate bridge")
        val params=target.native.parameterTypes.map { it.toString() }
        val words=params.sumOf { if(it=="J"||it=="D")2 else 1 }
        val descriptor="(${params.joinToString("")})${target.native.returnType}"
        val bridge=MutableMethod(ImmutableMethod(owner.type,name,params.map { ImmutableMethodParameter(it,null,null) },target.native.returnType,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.SYNTHETIC.value,null,null,MutableMethodImplementation(words+3)))
        bridge.addInstructions(0,"""
            invoke-static/range {p0 .. p${words-1}}, $RECEIPT->${target.observer}(${params.joinToString("")})I
            move-result v0
            invoke-static {}, $RECEIPT->bypassEnabled()Z
            move-result v1
            if-eqz v1, :native
            const/4 v1, 0x0
            return v1
            :native
            invoke-static/range {p0 .. p${words-1}}, ${target.native.definingClass}->${target.native.name}$descriptor
            move-result v1
            invoke-static {v0, v1}, $RECEIPT->returned(II)V
            return v1
        """.trimIndent())
        owner.methods.add(bridge)
        val caller=context.mutableClassDefBy(target.caller.definingClass).findMutableMethodOf(target.caller)
        caller.implementation!!.instructions.toList().withIndex().filter { it.value.methodRef()?.signature()==target.native.signature() }.forEach { (index,i) ->
            val registers=when(i){
                is RegisterRangeInstruction->"{v${i.startRegister} .. v${i.startRegister+i.registerCount-1}}"
                is FiveRegisterInstruction->listOf(i.registerC,i.registerD,i.registerE,i.registerF,i.registerG).take(i.registerCount).joinToString(", ","{","}"){"v$it"}
                else->throw PatchException("GC policy trial: unsupported invocation")
            }
            val opcode=if(i is RegisterRangeInstruction)"invoke-static/range" else "invoke-static"
            caller.replaceInstruction(index,"$opcode $registers, ${owner.type}->$name$descriptor")
        }
    }
    val request=context.mutableClassDefBy(targets.youngRequest.definingClass).findMutableMethodOf(targets.youngRequest)
    request.addInstructionsAtControlFlowLabel(targets.youngIndex,"""
        invoke-static/range {p0 .. p1}, $RECEIPT->youngRequest(D)Z
        move-result v0
        if-eqz v0, :original
        return-void
        :original
        nop
    """.trimIndent())
}
