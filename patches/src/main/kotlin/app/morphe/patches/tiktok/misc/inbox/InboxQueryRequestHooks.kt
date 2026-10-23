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
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val INBOX_REGULAR_VM = "Lcom/ss/android/ugc/aweme/notification/v2/viewmodel/NotificationResultViewModel;"
internal const val INBOX_CREATOR_VM = "Lcom/ss/android/ugc/aweme/notification/v2/viewmodel/NotificationCreatorResultViewModel;"
internal const val INBOX_WIDGET = "Lcom/ss/android/ugc/aweme/notification/v2/widget/NotificationWidget;"
internal const val INBOX_PAGE_LOADER = "Lcom/bytedance/ies/powerlist/page/PowerPageLoader;"

internal data class InboxRequestSpec(val owner: String, val name: String, val parameters: List<String>,
    val tracked: Boolean = false, val closure: Boolean = false) {
    val counted = tracked || owner in setOf(INBOX_REGULAR_VM, INBOX_CREATOR_VM,
        "Lcom/ss/android/ugc/aweme/notification/v2/viewmodel/NotificationAllTabResultViewModel;")
    val fingerprint = Fingerprint(definingClass = owner, name = name,
        returnType = if (closure) "Ljava/lang/Object;" else "V", parameters = parameters)
}

internal val loadedInboxRequestSpecs = listOf(
    InboxRequestSpec("Lcom/ss/android/ugc/aweme/inbox/widget/v2/InboxWidgetContainer;", "LJJIJIL", emptyList()),
    InboxRequestSpec(INBOX_WIDGET, "LJJIJIL", emptyList()),
    InboxRequestSpec(INBOX_WIDGET, "Rp", listOf("LX/0NZp;")),
    InboxRequestSpec(INBOX_REGULAR_VM, "m33", emptyList(), tracked = true),
    InboxRequestSpec(INBOX_CREATOR_VM, "m33", emptyList(), tracked = true),
    InboxRequestSpec(INBOX_REGULAR_VM, "n33", listOf("LX/0NZp;")),
    InboxRequestSpec(INBOX_CREATOR_VM, "n33", listOf("LX/0NZp;")),
    InboxRequestSpec("Lcom/ss/android/ugc/aweme/notification/v2/viewmodel/NotificationAllTabResultViewModel;", "n33", listOf("LX/0NZp;")),
    InboxRequestSpec(INBOX_MERGED_CLOSURE, "invoke\$45", listOf(INBOX_MERGED_CLOSURE), tracked = true, closure = true),
    InboxRequestSpec(INBOX_MERGED_CLOSURE, "invoke\$46", listOf(INBOX_MERGED_CLOSURE), tracked = true, closure = true),
    InboxRequestSpec(INBOX_PAGE_LOADER, "LJII", listOf("Ljava/lang/Object;"), tracked = true),
    InboxRequestSpec(INBOX_PAGE_LOADER, "LJIIIIZZ", listOf("Ljava/lang/Object;"), tracked = true),
    InboxRequestSpec(INBOX_PAGE_LOADER, "LJIIIZ", emptyList(), tracked = true),
)

context(patchContext: BytecodePatchContext)
internal fun loadedInboxRequestSeams() = InboxRequestSeams(loadedInboxRequestSpecs.map { it to it.fingerprint.method })

internal class InboxRequestSeams(private val methods: List<Pair<InboxRequestSpec, MutableMethod>>) {
    init { methods.forEach { (spec, method) -> method.loadedRequestHook(spec, true) } }
    fun install() { methods.forEach { (spec, method) -> method.loadedRequestHook(spec) } }
}

internal fun MutableMethod.loadedRequestHook(spec: InboxRequestSpec, checkOnly: Boolean = false) {
    check(definingClass == spec.owner && name == spec.name && parameterTypes.map(CharSequence::toString) == spec.parameters)
    requireLocals("Loaded Inbox search", 1)
    val native = implementation!!.instructions.toList()
    val returns = native.withIndex().filter { it.value.opcode == if (spec.closure) Opcode.RETURN_OBJECT else Opcode.RETURN_VOID }
    check(returns.isNotEmpty())
    val launches = native.withIndex().filter { it.value.getReference<MethodReference>()?.let { reference ->
        reference.definingClass == "LX/03WS;" && reference.name == "LIZLLL" && reference.returnType == "LX/03Wz;" } == true }
    if (spec.tracked) check(launches.size == 1) { "Loaded Inbox request must launch exactly one reviewed native job" }
    if (spec.tracked) check(implementation!!.registerCount <= 16)
    if (checkOnly) return

    if (spec.counted) {
        // Return labels must land on the counter cleanup, including branches in the refresh
        // closure. This marks only synchronous method exit, never asynchronous job completion.
        returns.asReversed().forEach { returned ->
            val function = if (spec.closure) "returnedLoadedClosureRequest" else "returnedLoadedRequest"
            replaceInstruction(returned.index, "invoke-static/range {p0 .. p0}, $INBOX_QUERY_EXTENSION->$function(Ljava/lang/Object;)V")
            implementation!!.addInstruction(returned.index + 1, returned.value)
        }
    }
    if (spec.tracked) {
        // The returned Job was previously discarded at VM/loader entries. Capture that genuine
        // result. Existing refresh closures already move it into a native job field.
        val launch = launches.single().value
        val launchIndex = implementation!!.instructions.indexOf(launch)
        val resultInstruction = implementation!!.instructions[launchIndex + 1]
        val existingResult = resultInstruction.opcode == Opcode.MOVE_RESULT_OBJECT
        val result = if (existingResult) (resultInstruction as OneRegisterInstruction).registerA else 0
        val function = if (spec.closure) "onLoadedClosureRequestJob" else "onLoadedRequestJob"
        if (existingResult) addInstruction(launchIndex + 2,
            "invoke-static {p0, v$result}, $INBOX_QUERY_EXTENSION->$function(Ljava/lang/Object;Ljava/lang/Object;)V")
        else addInstructions(launchIndex + 1, """
            move-result-object v0
            invoke-static {p0, v0}, $INBOX_QUERY_EXTENSION->$function(Ljava/lang/Object;Ljava/lang/Object;)V
        """.trimIndent())
    }

    val guard = if (spec.closure) "beginLoadedClosureRequest" else if (spec.counted) "beginLoadedRequest" else "allowLoadedRequest"
    val refusal = if (spec.closure) "sget-object v0, Lkotlin/Unit;->LIZ:Lkotlin/Unit;\nreturn-object v0" else "return-void"
    addInstructionsWithLabels(0, """
        invoke-static/range {p0 .. p0}, $INBOX_QUERY_EXTENSION->$guard(Ljava/lang/Object;)Z
        move-result v0
        if-nez v0, :native_request
        $refusal
    """.trimIndent(), ExternalLabel("native_request", getInstruction(0)))
}
