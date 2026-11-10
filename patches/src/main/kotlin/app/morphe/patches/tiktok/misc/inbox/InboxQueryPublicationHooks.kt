/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

internal const val INBOX_QUERY_EXTENSION = "Lapp/morphe/extension/tiktok/inbox/InboxFilter;"
internal const val INBOX_RESULT_FRAGMENT = "Lcom/ss/android/ugc/aweme/notification/v2/NotificationResultFragment;"
internal const val INBOX_MERGED_CLOSURE = "Lkotlin/jvm/internal/AwS439S0200000_37_I1;"
internal const val INBOX_INDICES_GATE = "hushLoadedInboxIndicesEnabled"

internal object InboxListPublication : Fingerprint(
    definingClass = "Lcom/bytedance/ies/powerlist/ListState;", name = "LIZ", returnType = "V",
    parameters = listOf("Lcom/bytedance/ies/powerlist/ListState;", "Ljava/util/List;", "Z", "Z", "Ljava/lang/Runnable;", "I"),
)
internal object InboxStubPublication : Fingerprint(
    definingClass = "Lcom/bytedance/ies/powerlist/PowerStub;", name = "onChanged", returnType = "V",
    parameters = listOf("Ljava/lang/Object;"),
)
internal object InboxDiffSubmission : Fingerprint(
    definingClass = "LX/09Mk;", name = "LJ", returnType = "V",
    parameters = listOf("Ljava/util/List;", "Ljava/util/List;", "Ljava/util/List;", "LX/0Gy2;"),
)
internal object InboxDiffAcceptance : Fingerprint(
    definingClass = "LX/09Mk;", name = "LIZIZ", returnType = "V",
    parameters = listOf("LX/00J8;", "LX/0Gy2;", "LX/00JY;"),
)
internal object InboxFragmentPublication : Fingerprint(
    definingClass = INBOX_RESULT_FRAGMENT, name = "tb", returnType = "V", parameters = listOf("Ljava/util/List;"),
)
internal object InboxIndexedCallback : Fingerprint(
    definingClass = INBOX_MERGED_CLOSURE, name = "invoke\$43", returnType = "Ljava/lang/Object;", parameters = listOf(INBOX_MERGED_CLOSURE),
)
internal object InboxVisibleIndices : Fingerprint(
    definingClass = INBOX_RESULT_FRAGMENT, name = "DX", returnType = "V", parameters = listOf("Z"),
)
internal object InboxNotificationClick : Fingerprint(
    definingClass = TEMPLATE_NOTIFICATION_CELL, name = "onClick", returnType = "V", parameters = listOf("Landroid/view/View;"),
)
internal object InboxSpringRefresh : Fingerprint(
    definingClass = "LX/09Ni;", name = "setRefreshing", returnType = "V", parameters = listOf("Z"),
)
internal object InboxSpringManualRefresh : Fingerprint(
    definingClass = "LX/09Ni;", name = "LJ", returnType = "V", parameters = emptyList(),
)

/** Resolve and validate the complete group before the patch writes any instruction. */
context(patchContext: BytecodePatchContext)
internal fun loadedInboxPublicationSeams(): InboxPublicationSeams {
    val indexed = InboxIndexedCallback.method
    val owner = patchContext.mutableClassDefBy(INBOX_RESULT_FRAGMENT)
    check(owner.methods.none { it.name == INBOX_INDICES_GATE }) { "Loaded Inbox index gate already exists" }
    val gate = loadedIndicesGate(indexed)
    return InboxPublicationSeams(InboxListPublication.method,
        InboxStubPublication.method, InboxDiffSubmission.method, InboxDiffAcceptance.method,
        InboxFragmentPublication.method, indexed, InboxVisibleIndices.method, InboxNotificationClick.method,
        InboxSpringRefresh.method, InboxSpringManualRefresh.method) { owner.methods.add(gate) }
}

internal class InboxPublicationSeams(
    private val publication: MutableMethod, private val stub: MutableMethod,
    private val submit: MutableMethod, private val accepted: MutableMethod,
    private val fragment: MutableMethod, private val indexed: MutableMethod,
    private val visible: MutableMethod, private val click: MutableMethod,
    private val refresh: MutableMethod, private val manualRefresh: MutableMethod,
    private val installGate: () -> Unit,
) {
    init {
        publication.loadedPublicationHook(true)
        stub.loadedStubHook(true)
        submit.loadedDiffSubmissionHook(true)
        accepted.loadedDiffAcceptanceHook(true)
        indexed.loadedIndexedHook(true)
        visible.loadedVisibleHook(true)
        click.requireLocals("Loaded Inbox search", 1)
        refresh.loadedRefreshHook(true)
        manualRefresh.loadedRefreshHook(true)
    }
    fun install() {
        publication.loadedPublicationHook()
        stub.loadedStubHook()
        submit.loadedDiffSubmissionHook()
        accepted.loadedDiffAcceptanceHook()
        indexed.loadedIndexedHook()
        visible.loadedVisibleHook()
        fragment.addInstruction(0, "invoke-static/range {p0 .. p0}, $INBOX_QUERY_EXTENSION->onLoadedFragmentBound(Ljava/lang/Object;)V")
        click.loadedGuard("allowLoadedNotificationClick")
        refresh.loadedRefreshHook()
        manualRefresh.loadedRefreshHook()
        installGate()
    }
}

/** Repeat the native live enable check without asking ZX to initialize its lazy helper. */
internal fun loadedIndicesGate(indexed: MutableMethod): MutableMethod {
    val code = indexed.implementation!!.instructions.toList()
    val getter = code[0].getReference<MethodReference>()
    val enabled = code[2].getReference<FieldReference>()
    check(code[0].opcode == Opcode.INVOKE_STATIC && getter?.definingClass == "LX/0ISl;"
        && getter.name == "LIZ" && getter.parameterTypes.isEmpty()
        && getter.returnType == "Lcom/ss/android/ugc/aweme/inbox/widget/change/InboxListChangeConfigModel;")
    check(code[1].opcode == Opcode.MOVE_RESULT_OBJECT && code[2].opcode == Opcode.IGET_BOOLEAN
        && enabled?.definingClass == getter.returnType && enabled.name == "enable" && enabled.type == "Z"
        && code[3].opcode == Opcode.IF_EQZ)
    return MutableMethod(ImmutableMethod(INBOX_RESULT_FRAGMENT, INBOX_INDICES_GATE, emptyList(), "Z",
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.SYNTHETIC.value, null, null,
        ImmutableMethodImplementation(2, emptyList(), null, null))).also {
        it.addInstructions("""
            invoke-static {}, $getter
            move-result-object v0
            iget-boolean v0, v0, $enabled
            return v0
        """.trimIndent())
    }
}

internal fun MutableMethod.loadedRefreshHook(checkOnly: Boolean = false) {
    check(definingClass == "LX/09Ni;" && returnType == "V")
    val manual = name == "LJ" && parameterTypes.isEmpty()
    check(manual || (name == "setRefreshing" && parameterTypes.map(CharSequence::toString) == listOf("Z")))
    requireLocals("Loaded Inbox refresh", 1)
    val code = implementation!!.instructions
    check(code.any { it.opcode == Opcode.IPUT_BOOLEAN && it.getReference<FieldReference>()?.let { field ->
        field.definingClass == definingClass && field.name == if (manual) "LLLIIIIL" else "LLLIZZ"
    } == true })
    if (checkOnly) return
    if (manual) loadedGuard("allowLoadedManualRefresh")
    else loadedGuard("allowLoadedRefresh", "Ljava/lang/Object;Z", "p0 .. p1")
}

internal fun MutableMethod.loadedPublicationHook(checkOnly: Boolean = false) {
    check(definingClass == "Lcom/bytedance/ies/powerlist/ListState;" && name == "LIZ" && parameterTypes.size == 6)
    check(implementation!!.registerCount - 5 <= 255) { "Loaded Inbox publication register changed" }
    if (checkOnly) return
    addInstructions(0, """
        invoke-static/range {p0 .. p5}, $INBOX_QUERY_EXTENSION->onLoadedPublication(Ljava/lang/Object;Ljava/util/List;ZZLjava/lang/Runnable;I)Ljava/util/List;
        move-result-object p1
    """.trimIndent())
}

internal fun MutableMethod.loadedStubHook(checkOnly: Boolean = false) {
    val constructors = implementation!!.instructions.withIndex().filter {
        it.value.getReference<MethodReference>()?.let { ref -> ref.definingClass == "LX/0Gy2;" && ref.name == "<init>" } == true
    }
    check(constructors.size == 1)
    val constructor = constructors.single()
    val metadata = (constructor.value as RegisterRangeInstruction).startRegister
    check(implementation!!.registerCount <= 16 && metadata <= 15)
    if (checkOnly) return
    addInstruction(constructor.index + 1, "invoke-static {p0, p1, v$metadata}, " +
        "$INBOX_QUERY_EXTENSION->onLoadedStubPublication(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V")
}

internal fun MutableMethod.loadedDiffSubmissionHook(checkOnly: Boolean = false) {
    val writes = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.IPUT &&
        it.value.getReference<FieldReference>()?.let { ref -> ref.definingClass == "LX/09Mk;" && ref.name == "LJIIJ" && ref.type == "I" } == true }
    check(writes.size == 1 && parameterTypes.size == 4)
    if (checkOnly) return
    addInstruction(writes.single().index + 1, "invoke-static/range {p0 .. p4}, " +
        "$INBOX_QUERY_EXTENSION->onLoadedDiffSubmitted(Ljava/lang/Object;Ljava/util/List;Ljava/util/List;Ljava/util/List;Ljava/lang/Object;)V")
}

internal fun MutableMethod.loadedDiffAcceptanceHook(checkOnly: Boolean = false) {
    val completion = implementation!!.instructions.withIndex().filter { it.value.getReference<FieldReference>()?.let { ref ->
        ref.definingClass == "LX/0Gy2;" && ref.name == "LJ" && ref.type == "Ljava/util/List;" } == true }
    check(completion.size == 1 && implementation!!.registerCount <= 16)
    val dispatch = implementation!!.instructions.indexOfFirst { it.getReference<MethodReference>()?.let { ref ->
        ref.definingClass == "LX/00J8;" && ref.name == "LIZIZ" } == true }
    check(dispatch >= 0 && dispatch < completion.single().index)
    if (checkOnly) return
    val index = completion.single().index
    val originalRead = implementation!!.instructions[index]
    // The listener-loop exit branches to this location. Preserve its label on the hook.
    replaceInstruction(index, "invoke-static {p0, p2}, $INBOX_QUERY_EXTENSION->onLoadedDiffAccepted(Ljava/lang/Object;Ljava/lang/Object;)V")
    implementation!!.addInstruction(index + 1, originalRead)
}

internal fun MutableMethod.loadedIndexedHook(checkOnly: Boolean = false) {
    val calls = implementation!!.instructions.withIndex().filter { it.value.getReference<MethodReference>()?.let { ref ->
        ref.definingClass == "LX/0NaB;" && ref.name == "LJFF" && ref.parameterTypes.map(CharSequence::toString) == listOf("Ljava/util/List;", "Lkotlin/Pair;") } == true }
    check(calls.size == 1 && implementation!!.registerCount <= 16)
    val call = calls.single()
    val registers = call.value as FiveRegisterInstruction
    check(registers.registerCount == 3)
    if (checkOnly) return
    replaceInstruction(call.index, "invoke-static {p0, v${registers.registerC}, v${registers.registerD}, v${registers.registerE}}, " +
        "$INBOX_QUERY_EXTENSION->onLoadedIndexedCallback(Ljava/lang/Object;Ljava/lang/Object;Ljava/util/List;Ljava/lang/Object;)V")
}

internal fun MutableMethod.loadedVisibleHook(checkOnly: Boolean = false) {
    val reads = implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.IGET_OBJECT &&
        it.value.getReference<FieldReference>()?.let { ref -> ref.definingClass == "LX/0FXE;" && ref.name == "LIZ" && ref.type == "Ljava/util/List;" } == true }
    check(reads.size == 2 && implementation!!.registerCount <= 16)
    requireLocals("Loaded Inbox search", 1)
    if (checkOnly) return
    reads.asReversed().forEach { read ->
        val result = (read.value as TwoRegisterInstruction).registerA
        addInstructions(read.index + 1, """
            invoke-static {p0, v$result}, $INBOX_QUERY_EXTENSION->onLoadedIndexedList(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;
            move-result-object v$result
        """.trimIndent())
    }
    loadedGuard("allowLoadedVisibleIndices", "Ljava/lang/Object;Z", "p0 .. p1")
}

internal fun MutableMethod.loadedGuard(function: String, parameters: String = "Ljava/lang/Object;", registers: String = "p0 .. p0") {
    requireLocals("Loaded Inbox search", 1)
    addInstructionsWithLabels(0, """
        invoke-static/range {$registers}, $INBOX_QUERY_EXTENSION->$function($parameters)Z
        move-result v0
        if-nez v0, :native_body
        return-void
    """.trimIndent(), ExternalLabel("native_body", getInstruction(0)))
}
