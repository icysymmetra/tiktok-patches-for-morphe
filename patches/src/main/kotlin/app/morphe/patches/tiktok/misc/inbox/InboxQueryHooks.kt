/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstruction
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction

internal const val TEMPLATE_NOTIFICATION_CELL =
    "Lcom/ss/android/ugc/aweme/notification/v2/cell/TemplateNotificationCell;"

internal object InboxNotificationNormalBind : Fingerprint(
    definingClass = TEMPLATE_NOTIFICATION_CELL,
    name = "onBindItemView",
    returnType = "V",
    parameters = listOf("LX/05u8;"),
)

internal object InboxNotificationPayloadBind : Fingerprint(
    definingClass = TEMPLATE_NOTIFICATION_CELL,
    name = "onBindItemView",
    returnType = "V",
    parameters = listOf("LX/05u8;", "Ljava/util/List;"),
)

internal object InboxNotificationVmGetter : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/notification/v2/widget/NotificationWidget;",
    name = "hq",
    returnType = "Lcom/ss/android/ugc/aweme/notification/v2/viewmodel/BaseNotificationResultViewModel;",
    parameters = emptyList(),
)

/** Capture only after a successful native bind. Native listeners and current model stay intact. */
internal fun MutableMethod.captureLoadedInboxOwner() {
    check(definingClass == TEMPLATE_NOTIFICATION_CELL && name == "onBindItemView" && returnType == "V"
        && parameterTypes.map(CharSequence::toString) in listOf(listOf("LX/05u8;"), listOf("LX/05u8;", "Ljava/util/List;"))
        && !AccessFlags.STATIC.isSet(accessFlags)) { "Loaded Inbox owner must be a native notification bind" }
    val returns = implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    check(returns.isNotEmpty()) { "Loaded Inbox bind has no successful return" }
    returns.asReversed().forEach { index ->
        val originalReturn = implementation!!.instructions[index]
        // Keep inbound branch labels on the hook, including payload bind's early returns.
        replaceInstruction(index, "invoke-static/range {p0 .. p0}, " +
            "Lapp/morphe/extension/tiktok/inbox/InboxFilter;->onNotificationRowBound(Ljava/lang/Object;)V")
        implementation!!.addInstruction(index + 1, originalReturn)
    }
}

internal fun MutableMethod.captureLoadedInboxVm() {
    check(definingClass == "Lcom/ss/android/ugc/aweme/notification/v2/widget/NotificationWidget;"
        && name == "hq" && returnType == "Lcom/ss/android/ugc/aweme/notification/v2/viewmodel/BaseNotificationResultViewModel;"
        && !AccessFlags.STATIC.isSet(accessFlags) && parameterTypes.isEmpty()) { "Expected the native widget VM getter" }
    val receiver = implementation!!.registerCount - 1
    check(receiver <= 15) { "Loaded Inbox VM capture requires the reviewed getter register layout" }
    val returns = implementation!!.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
    check(returns.isNotEmpty() && returns.all { it.second <= 15 }) { "Loaded Inbox VM getter has no reviewed return" }
    returns.asReversed().forEach { (index, result) ->
        addInstruction(index, "invoke-static {p0, v$result}, " +
            "Lapp/morphe/extension/tiktok/inbox/InboxFilter;->onNotificationVmObserved(Ljava/lang/Object;Ljava/lang/Object;)V")
    }
}
