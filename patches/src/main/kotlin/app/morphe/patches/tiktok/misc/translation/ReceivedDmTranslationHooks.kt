/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val RECEIVED_PREVIEW = "Lapp/morphe/extension/tiktok/translation/ReceivedDmTranslationPreview;"
internal const val RECEIVED_CONTENT = "Lcom/ss/android/ugc/aweme/im/sdk/chat/ui/text/content/"
internal const val RECEIVED_ASSEM = "${RECEIVED_CONTENT}TextTranslateAssem;"
internal const val RECEIVED_ROW = "${RECEIVED_CONTENT}TextTranslateReceiveAssem;"
internal const val RECEIVED_CELL = "${RECEIVED_CONTENT}TextTranslateReceiveCell;"
internal const val RECEIVED_MENU = "${RECEIVED_CONTENT}TextTranslateReceiveCell\$createAssemAttached2Cell\$1;"
private const val RECEIVED = "Translate comments (received message preview)"
private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private const val MESSAGE = "LX/0BUG;"
private const val MODEL = "LX/0dfc;"
private const val SESSION = "Lcom/ss/android/ugc/aweme/im/chatroom/api/model/SessionInfo;"
private const val FUNCTION = "Lkotlin/jvm/functions/Function1;"
private val menuParameters = listOf(MESSAGE, "LX/0deu;", "Landroid/content/Context;", "Landroidx/fragment/app/FragmentManager;",
    "Lcom/ss/android/ugc/aweme/im/sdk/chat/feature/IMMessageListTranslationViewModel;",
    "Lcom/ss/android/ugc/aweme/im/messagelist/api/select/IMSelectTextAbility;", "LX/0dKt;", "Ljava/lang/CharSequence;")

internal data class ReceivedMenuResult(val call: Int, val result: Int, val returned: Int, val register: Int)
internal data class ReceivedPreviewTargets(val bind: Method, val unbind: Method, val menu: Method,
    val result: ReceivedMenuResult)

private fun Method.calls(owner: String, name: String, result: String, parameters: List<String> = emptyList()) =
    implementation?.instructions?.any { instruction -> instruction.getReference<MethodReference>()?.let {
        it.definingClass == owner && it.name == name && it.returnType == result &&
            it.parameterTypes.map(CharSequence::toString) == parameters
    } == true } == true

internal fun Method.receivedMenuResult(): ReceivedMenuResult {
    if (definingClass != RECEIVED_MENU || name != "kq" || returnType != LIST ||
        parameterTypes.map(CharSequence::toString) != listOf(MESSAGE) || AccessFlags.STATIC.isSet(accessFlags))
        throw PatchException("$RECEIVED: changed received menu ABI")
    val code = implementation ?: throw PatchException("$RECEIVED: missing received menu body")
    if (code.registerCount !in 3..16) throw PatchException("$RECEIVED: received menu registers changed")
    val instructions = code.instructions.toList()
    val calls = instructions.withIndex().filter { (_, instruction) ->
        instruction.getReference<MethodReference>()?.let { reference -> reference.definingClass == "LX/0dd4;" &&
            reference.name == "LIZ" && reference.returnType == LIST &&
            reference.parameterTypes.map(CharSequence::toString) == menuParameters } == true
    }
    val call = calls.singleOrNull()?.index ?: throw PatchException("$RECEIVED: expected one completed native menu")
    val result = instructions.getOrNull(call + 1) as? OneRegisterInstruction
    val returned = instructions.getOrNull(call + 2) as? OneRegisterInstruction
    if (instructions.getOrNull(call + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT ||
        instructions.getOrNull(call + 2)?.opcode != Opcode.RETURN_OBJECT || result == null || returned == null ||
        result.registerA != returned.registerA || result.registerA >= code.registerCount - 2)
        throw PatchException("$RECEIVED: native menu no longer returns its untouched result")
    return ReceivedMenuResult(call, call + 1, call + 2, result.registerA)
}

/** The two bind parameters must still be the native receiver/model at every completed return. */
internal fun Method.receivedBindReturns(): List<Int> {
    val code = implementation ?: throw PatchException("$RECEIVED: missing bind body")
    if (AccessFlags.STATIC.isSet(accessFlags) || parameterTypes.map(CharSequence::toString) != listOf(MODEL, "Z") ||
        returnType != "V" || code.registerCount < 3) throw PatchException("$RECEIVED: changed bind parameters")
    val receiver = code.registerCount - 3
    val instructions = code.instructions.toList()
    if (instructions.any { instruction ->
        val destination = (instruction as? OneRegisterInstruction)?.registerA
        instruction.opcode.setsRegister() && destination != null &&
            (destination in receiver..receiver + 1 || instruction.opcode.setsWideRegister() && destination + 1 in receiver..receiver + 1)
    }) throw PatchException("$RECEIVED: bind overwrites a captured native parameter")
    val cancellations = instructions.withIndex().filter { (_, instruction) -> instruction.getReference<MethodReference>()?.let {
        it.definingClass == RECEIVED_ASSEM && it.name == "cq" && it.returnType == "V" && it.parameterTypes.isEmpty()
    } == true }
    val cancellation = cancellations.singleOrNull()?.index ?: throw PatchException("$RECEIVED: changed native bind cancellation")
    return instructions.withIndex().filter { (index, instruction) -> index > cancellation && instruction.opcode == Opcode.RETURN_VOID }
        .map { it.index }.also { if (it.isEmpty()) throw PatchException("$RECEIVED: no completed native bind") }
}

/** Resolve every reflected owner before modifying a native instruction. */
internal fun findReceivedPreviewTargets(classOf: (String) -> ClassDef?): ReceivedPreviewTargets {
    validateNativeDmTranslation(classOf)
    fun owner(type: String) = classOf(type) ?: throw PatchException("$RECEIVED: missing $type")
    fun abi(type: String, name: String, result: String, parameters: List<String> = emptyList(), static: Boolean = false): Method {
        val pending = ArrayDeque<String>().apply { add(type) }
        val seen = mutableSetOf<String>()
        while (pending.isNotEmpty() && seen.size < 32) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            val definition = owner(current)
            val methods = definition.methods.filter { it.name == name && it.returnType == result &&
                it.parameterTypes.map(CharSequence::toString) == parameters && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
                AccessFlags.STATIC.isSet(it.accessFlags) == static }
            if (methods.size > 1) throw PatchException("$RECEIVED: ambiguous $type->$name")
            methods.singleOrNull()?.let { return it }
            if (name == "<init>") break
            definition.superclass?.takeUnless { it == "Ljava/lang/Object;" }?.let(pending::add)
            definition.interfaces.forEach { pending.add(it) }
        }
        throw PatchException("$RECEIVED: changed $type->$name ABI")
    }
    fun field(type: String, name: String, fieldType: String, static: Boolean = false) {
        if (owner(type).fields.count { it.name == name && it.type == fieldType && AccessFlags.STATIC.isSet(it.accessFlags) == static } != 1)
            throw PatchException("$RECEIVED: changed $type->$name field")
    }
    if (owner(RECEIVED_ROW).superclass != RECEIVED_ASSEM || MODEL !in owner("LX/0dfZ;").interfaces)
        throw PatchException("$RECEIVED: changed received-row subtype")
    field(RECEIVED_MENU, "LLLILZ", RECEIVED_CELL)
    val menu = abi(RECEIVED_MENU, "kq", LIST, listOf(MESSAGE))
    val result = menu.receivedMenuResult()
    if (!menu.calls("LX/03Sz;", "getChildren", LIST) ||
        !menu.calls("Lcom/bytedance/ies/powerlist/PowerCell;", "getItem", "LX/05u8;") ||
        menu.implementation!!.instructions.none { it.opcode == Opcode.CHECK_CAST && it.getReference<com.android.tools.smali.dexlib2.iface.reference.TypeReference>()?.type == "LX/0dfZ;" })
        throw PatchException("$RECEIVED: changed current received-cell ownership")
    val bind = abi(RECEIVED_ASSEM, "Tp", "V", listOf(MODEL, "Z"))
    bind.receivedBindReturns()
    val unbind = abi(RECEIVED_ASSEM, "cq", "V")
    if (!abi(RECEIVED_ASSEM, "unBind", "V").calls(RECEIVED_ASSEM, "cq", "V"))
        throw PatchException("$RECEIVED: changed native row retirement")
    abi("LX/03Sz;", "getChildren", LIST)
    abi("Lcom/bytedance/ies/powerlist/PowerCell;", "getItem", "LX/05u8;")
    abi("LX/08oa;", "LIZ", OBJECT, listOf("Lcom/bytedance/assem/arch/reused/ReusedAssem;"), true)
    abi("LX/0cxv;", "LIZ", MESSAGE, listOf("LX/0dSc;"), true)
    abi(MODEL, "LLIIJLIL", STRING)
    abi(RECEIVED_ASSEM, "mq", "LX/0dfB;")
    field("LX/0dfB;", "LL", "Landroid/view/View;")
    field(RECEIVED_ASSEM, "LLJJL", "LX/04QJ;")
    abi("LX/04QJ;", "getValue", OBJECT)
    field("LX/0YZ7;", "LL", SESSION)
    listOf("isSingleChat", "isPreviewMode", "isReportPage").forEach { abi(SESSION, it, "Z") }
    abi(SESSION, "getConversationId", STRING)
    if (owner("LX/0dli;").superclass != SESSION) throw PatchException("$RECEIVED: changed single-peer session")
    abi("LX/0dli;", "getSingleChatFromUserId", STRING)
    listOf("getUuid", "getConversationId", "getContent").forEach { abi(MESSAGE, it, STRING) }
    abi(MESSAGE, "getSender", "J")
    abi(MESSAGE, "getMsgType", "I")
    abi("LX/0CVs;", "LJI", "Z", listOf(MESSAGE), true)
    abi("LX/0CWU;", "LJIIIZ", "Z", listOf(MESSAGE, "LX/0dRY;"), true)
    field("LX/0dRY;", "TRANSLATE", "LX/0dRY;", true)
    field("LX/0CHK;", "LIZIZ", "LX/0CHK;", true)
    abi("LX/0CHK;", "LJIIZILJ", "Z")
    abi("LX/18pP;", "<init>", "V", listOf(STRING, "Ljava/lang/Enum;", "LX/18h3;", "LX/18h1;", "LX/18kj;", "LX/19Hs;", "Z", FUNCTION))
    field("LX/18pP;", "LIZ", STRING)
    field("LX/18pP;", "LJII", FUNCTION)
    abi("LX/18h2;", "<init>", "V", listOf("I"))
    abi("LX/18gz;", "<init>", "V", listOf(STRING))
    abi("LX/19Hs;", "<init>", "V")
    if ("LX/18h3;" !in owner("LX/18h2;").interfaces || "LX/18h1;" !in owner("LX/18gz;").interfaces)
        throw PatchException("$RECEIVED: changed menu icon/text wrappers")
    field("LX/18kj;", "PRIMARY", "LX/18kj;", true)
    field("LX/0dJL;", "TRANSLATE", "LX/0dJL;", true)
    field("Lkotlin/Unit;", "LIZ", "Lkotlin/Unit;", true)
    abi(FUNCTION, "invoke", OBJECT, listOf(OBJECT))
    return ReceivedPreviewTargets(bind, unbind, menu, result)
}

internal fun MutableMethod.captureReceivedBinding() {
    receivedBindReturns().asReversed().forEach { index -> addInstructionsAtControlFlowLabel(index,
        "invoke-static/range {p0 .. p1}, $RECEIVED_PREVIEW->bound($OBJECT$OBJECT)V") }
    addInstructions(0, "invoke-static/range {p0 .. p0}, $RECEIVED_PREVIEW->unbound($OBJECT)V")
}

internal fun MutableMethod.appendReceivedMenu(result: ReceivedMenuResult) {
    addInstructionsAtControlFlowLabel(result.returned, """
        invoke-static {v${result.register}, p0, p1}, $RECEIVED_PREVIEW->appendAction($LIST$OBJECT$OBJECT)$LIST
        move-result-object v${result.register}
    """)
}

internal fun BytecodePatchContext.hookReceivedDmTranslationPreview(targets: ReceivedPreviewTargets) {
    fun mutable(method: Method) = mutableClassDefBy(method.definingClass).findMutableMethodOf(method)
    mutable(targets.bind).captureReceivedBinding()
    mutable(targets.unbind).addInstructions(0, "invoke-static/range {p0 .. p0}, $RECEIVED_PREVIEW->unbound($OBJECT)V")
    mutable(targets.menu).appendReceivedMenu(targets.result)
}
