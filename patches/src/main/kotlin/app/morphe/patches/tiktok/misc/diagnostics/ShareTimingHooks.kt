package app.morphe.patches.tiktok.misc.diagnostics

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.copyids.SHARE_APPS_MARKER
import app.morphe.patches.tiktok.interaction.copyids.SHARE_PANEL_MARKER
import app.morphe.patches.tiktok.interaction.copyids.resolveShareAppsPanel
import app.morphe.patches.tiktok.interaction.sharesheet.ShareButtonClickFingerprint
import app.morphe.patches.tiktok.interaction.sharesheet.ShareSnapshotFingerprint
import app.morphe.patches.tiktok.interaction.sharesheet.resolveSharePanel
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.Opcode

private const val TRACE = "Lapp/morphe/extension/shared/diagnostics/MainThreadStallProbe;"

/** Resolve every native boundary before emitting any optional diagnostic call. */
context(context: BytecodePatchContext)
internal fun resolveShareTimingWrites(): () -> Unit {
    val panel = resolveSharePanel(context.classDefByStrings(SHARE_PANEL_MARKER))
    val apps = resolveShareAppsPanel(context.classDefByStrings(SHARE_APPS_MARKER))
    val points = listOf(
        Triple(ShareButtonClickFingerprint.method, "shareClickStarted", "shareClickFinished"),
        Triple(ShareSnapshotFingerprint.method, "shareModelStarted", "shareModelFinished"),
        Triple(context.mutableClassDefBy(panel.definingClass).findMutableMethodOf(panel), "shareActionsStarted", "shareActionsFinished"),
        Triple(context.mutableClassDefBy(apps.definingClass).findMutableMethodOf(apps), "shareAppsStarted", "shareAppsFinished"),
    ).map { (method, start, end) ->
        val exits = method.implementation?.instructions?.withIndex()
            ?.filter { it.value.opcode == Opcode.RETURN_VOID }?.map { it.index }
            ?: throw PatchException("Diagnostics: share timing boundary has no implementation")
        if (exits.isEmpty()) throw PatchException("Diagnostics: share timing boundary has no normal exit")
        ShareTimingWrite(method, start, end, exits)
    }
    return { points.forEach { it.apply() } }
}

private data class ShareTimingWrite(
    val method: MutableMethod,
    val start: String,
    val end: String,
    val exits: List<Int>,
) {
    fun apply() = with(method) {
        // Zero arguments and no target-owned state. This is also safe before a constructor's
        // super call. Existing branches to a return must land on the completion marker.
        exits.asReversed().forEach { addInstructionsAtControlFlowLabel(it, "invoke-static {}, $TRACE->$end()V") }
        addInstruction(0, "invoke-static {}, $TRACE->$start()V")
    }
}
