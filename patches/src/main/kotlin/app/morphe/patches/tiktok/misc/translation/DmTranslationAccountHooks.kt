/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val DM_ACCOUNT_EVENTS = "Lapp/morphe/extension/tiktok/translation/DmTranslationAccountEvents;"
internal const val DM_ACCOUNT_BROADCASTER = "LX/03Fk;"
internal const val DM_ACCOUNT_LISTENER = "LX/03Sk;"
private const val USER = "Lcom/ss/android/ugc/aweme/profile/model/User;"
private const val ACCOUNT = "Translate comments (DM account transitions)"
private const val CALLBACK = "$DM_ACCOUNT_LISTENER->onAccountResult(IZI$USER)V"
private const val LIST = "Ljava/util/concurrent/CopyOnWriteArrayList;"
private const val ITERATOR = "Ljava/util/Iterator;"
private const val METHOD_IDS = "Lcom/bytedance/pumbaa/utility/method_id/MethodIDManager;"

internal data class DmAccountTargets(val login: Method, val logout: Method, val switch: Method)

/** These are synchronous account-result broadcasts, before native listeners can bind new UI owners. */
internal fun findDmAccountTargets(classOf: (String) -> ClassDef?): DmAccountTargets {
    fun owner(type: String) = classOf(type) ?: throw PatchException("$ACCOUNT: missing $type")
    val listener = owner(DM_ACCOUNT_LISTENER)
    if (!AccessFlags.INTERFACE.isSet(listener.accessFlags) || listener.methods.count {
        it.name == "onAccountResult" && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("I", "Z", "I", USER) &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    } != 1) throw PatchException("$ACCOUNT: changed native account callback")
    val broadcaster = owner(DM_ACCOUNT_BROADCASTER)
    if (broadcaster.fields.count { it.name == "LIZ" && it.type == "Ljava/util/List;" && AccessFlags.STATIC.isSet(it.accessFlags) } != 1)
        throw PatchException("$ACCOUNT: changed native listener list")
    fun broadcast(name: String, parameters: List<String>, registers: Int, arguments: List<Int>,
        constants: List<Pair<Int, Int>>): Method {
        val method = broadcaster.methods.singleOrNull { it.name == name && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == parameters &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            ?: throw PatchException("$ACCOUNT: changed $name ABI")
        val code = method.implementation ?: throw PatchException("$ACCOUNT: missing $name body")
        val instructions = code.instructions.toList()
        val calls = instructions.mapNotNull { it.getReference<MethodReference>()?.toString() }
        val expectedCalls = listOf("$LIST->iterator()$ITERATOR", "$ITERATOR->hasNext()Z",
            "$ITERATOR->next()Ljava/lang/Object;", CALLBACK)
        val switch = name == "LJIIIZ"
        val expected = if (switch) listOf("$METHOD_IDS->push(I)Z") + expectedCalls + "$METHOD_IDS->pop()V" else expectedCalls
        val loop = listOf(Opcode.SGET_OBJECT, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT_OBJECT,
            Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.INVOKE_INTERFACE,
            Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST) + List(if (name == "LJII") 4 else 2) { Opcode.CONST_4 } +
            listOf(Opcode.INVOKE_INTERFACE, Opcode.GOTO)
        val shape = if (switch) listOf(Opcode.CONST, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT) + loop +
            listOf(Opcode.IF_EQZ, Opcode.INVOKE_STATIC, Opcode.RETURN_VOID) else loop + Opcode.RETURN_VOID
        val callback = instructions.singleOrNull { it.getReference<MethodReference>()?.toString() == CALLBACK } as? FiveRegisterInstruction
        val actualArguments = callback?.let { listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG) }
        val actualConstants = instructions.mapNotNull { instruction ->
            val value = instruction as? NarrowLiteralInstruction ?: return@mapNotNull null
            (instruction as OneRegisterInstruction).registerA to value.narrowLiteral
        }
        val fields = instructions.mapNotNull { it.getReference<FieldReference>()?.toString() }
        val casts = instructions.filter { it.opcode == Opcode.CHECK_CAST }.map { it.getReference<TypeReference>()?.type }
        val firstParameter = registers - parameters.size
        val overwritesParameter = instructions.any { instruction -> instruction.opcode.setsRegister() &&
            (instruction as? OneRegisterInstruction)?.registerA?.let { it >= firstParameter } == true }
        if (code.registerCount != registers || calls != expected || instructions.map { it.opcode } != shape ||
            callback?.registerCount != 5 || actualArguments != arguments || actualConstants != constants ||
            overwritesParameter || fields != listOf("$DM_ACCOUNT_BROADCASTER->LIZ:Ljava/util/List;") || casts != listOf(LIST, DM_ACCOUNT_LISTENER))
            throw PatchException("$ACCOUNT: changed synchronous $name dispatch or account arguments")
        return method
    }
    val login = broadcast("LJI", listOf(USER), 5, listOf(2, 0, 0, 1, 4), listOf(1 to 0, 0 to 1))
    val logout = broadcast("LJII", emptyList(), 6, listOf(4, 1, 0, 3, 2), listOf(3 to 0, 2 to 0, 1 to 4, 0 to 1))
    val switch = broadcast("LJIIIZ", listOf(USER, "Z"), 7, listOf(2, 1, 6, 0, 5), listOf(0 to 71776, 1 to 2, 0 to 0))
    return DmAccountTargets(login, logout, switch)
}

internal fun MutableMethod.captureDmAccountEvent(switch: Boolean = false) = addInstructions(0,
    if (switch) "invoke-static/range {p1 .. p1}, $DM_ACCOUNT_EVENTS->switchResult(Z)V"
    else "invoke-static {}, $DM_ACCOUNT_EVENTS->changed()V")

internal fun BytecodePatchContext.hookDmTranslationAccountEvents(targets: DmAccountTargets) {
    fun mutable(method: Method) = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
    mutable(targets.login).captureDmAccountEvent()
    mutable(targets.logout).captureDmAccountEvent()
    mutable(targets.switch).captureDmAccountEvent(true)
}
