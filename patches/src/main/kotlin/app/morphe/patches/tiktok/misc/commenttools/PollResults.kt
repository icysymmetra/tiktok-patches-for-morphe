/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val POLL_CELL = "Lcom/ss/android/ugc/aweme/commentv2/commentlist/powercell/PollCell;"
internal const val POLL_INFO = "Lcom/ss/android/ugc/aweme/comment/model/PollInfo;"
internal const val POLL_RESULTS_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/comment/PollResults;"

/**
 * The comment poll row's check for drawing results: the poll has ended (the row's own check on
 * the poll's status and end time) or the reader voted (userPollOption at zero or more). The row
 * asks it as it binds, as it redraws after a vote and as it works out the bars' percentages, and
 * it wires a tap on each answer without asking it. The row and the poll model keep their names;
 * the check is z6 on 47.0.3 and has another name on every build.
 */
internal object PollShowsResultsFingerprint : Fingerprint(
    definingClass = POLL_CELL,
    returnType = "Z",
    parameters = listOf(POLL_INFO),
    custom = { method, _ -> method.isPollShowsResults() },
)

/** Static on the row, reads the reader's vote, and asks the row's own (PollInfo)Z ended check. */
internal fun Method.isPollShowsResults(): Boolean {
    if (!AccessFlags.STATIC.isSet(accessFlags)) return false
    val instructions = implementation?.instructions?.toList() ?: return false
    val readsVote = instructions.any {
        it.opcode == Opcode.IGET_OBJECT && it.getReference<FieldReference>()?.let { field ->
            field.definingClass == POLL_INFO && field.name == "userPollOption"
        } == true
    }
    val asksEnded = instructions.any {
        it.opcode == Opcode.INVOKE_STATIC && it.getReference<MethodReference>()?.let { call ->
            call.definingClass == POLL_CELL && call.returnType == "Z" &&
                call.parameterTypes.map(CharSequence::toString) == listOf(POLL_INFO)
        } == true
    }
    return readsVote && asksEnded
}
