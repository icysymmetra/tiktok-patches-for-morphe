/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.shared

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.cloneMutable
import app.morphe.util.cloneMutableAndPreserveParameters
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patcher.util.numberOfParameterRegistersLogical
import com.android.tools.smali.dexlib2.iface.Method

/** Labels for the cloned form below, unique across one patching run. */
private var switchedReturnLabels = 0

/**
 * The switched form of `returnEarly()`: the method returns at once when [switch], a static `()Z`
 * extension method, answers true, and runs as TikTok wrote it when it answers false.
 *
 * <p>A patch in the default selection has to leave TikTok as it ships until the reader turns its
 * switch on, so a bare returnEarly keeps a patch out of it. This is the guard Disable telemetry
 * puts in front of AppLog. The answer needs a register nothing has written yet: a method with a
 * local gets it in v0 through [guardAtEntry], and one whose frame is all parameters is cloned
 * with room for them first, so the answer never lands on `this` or an argument. The clone takes
 * the original's place in its class, and a fingerprint's `method` matched before still points at
 * the original, so a cloned method (RealTimeSplashTask.run today) takes no other edit after this.
 */
context(patchContext: BytecodePatchContext)
internal fun MutableMethod.returnVoidWhenOn(patch: String, switch: String) {
    if (returnType != "V") throw PatchException("$patch: $definingClass->$name returns $returnType, not void.")
    returnWhenOn(patch, switch) { "return-void" }
}

/** [returnVoidWhenOn] for a method answering `Z`, which answers [value] with the switch on. */
context(patchContext: BytecodePatchContext)
internal fun MutableMethod.returnBooleanWhenOn(patch: String, switch: String, value: Boolean) {
    checkBooleanWhenOn(patch)
    val literal = if (value) "0x1" else "0x0"
    returnWhenOn(patch, switch) { register -> "${constantInto(register, literal)}\nreturn v$register" }
}

/** [returnVoidWhenOn] for a method answering an object, which answers null with the switch on. */
context(patchContext: BytecodePatchContext)
internal fun MutableMethod.returnNullWhenOn(patch: String, switch: String) {
    if (!returnType.startsWith("L") && !returnType.startsWith("[")) {
        throw PatchException("$patch: $definingClass->$name returns $returnType, not an object.")
    }
    returnWhenOn(patch, switch) { register -> "${constantInto(register, "0x0")}\nreturn-object v$register" }
}

/**
 * Throws whatever [returnBooleanWhenOn] would refuse this method for, and changes nothing, so a
 * patch hooking two methods can check both before it hooks either.
 */
internal fun Method.checkBooleanWhenOn(patch: String) {
    if (returnType != "Z") throw PatchException("$patch: $definingClass->$name returns $returnType, not a boolean.")
    checkReturnWhenOn(patch)
}

private fun Method.checkReturnWhenOn(patch: String) {
    val body = implementation ?: throw PatchException("$patch: $definingClass->$name has no implementation")
    // With no local the frame is cloned with one more register, which move-result has to reach.
    if (body.registerCount - numberOfParameterRegisters < 1 && body.registerCount > 255) {
        throw PatchException("$patch: $definingClass->$name has no register move-result can reach.")
    }
}

/**
 * A constant into [register]. `const/4` names its register in four bits, so it reaches v15 and
 * no further; the scratch register of a cloned frame sits above the old frame and can be higher.
 */
private fun constantInto(register: Int, literal: String): String =
    if (register <= 15) "const/4 v$register, $literal" else "const/16 v$register, $literal"

context(patchContext: BytecodePatchContext)
private fun MutableMethod.returnWhenOn(patch: String, switch: String, answer: (register: Int) -> String) {
    checkReturnWhenOn(patch)
    val body = implementation!!
    if (body.registerCount - numberOfParameterRegisters >= 1) {
        guardAtEntry(patch, "invoke-static {}, $switch", answer(0))
        return
    }

    // No local: the frame is all parameters. Clone it with room for them, the way Disable
    // telemetry does, and ask in the register the first one used to be copied from. The clone
    // copies the parameters back down before anything else runs, so the guard goes after that.
    val scratch = body.registerCount
    if (scratch > 255) throw PatchException("$patch: $definingClass->$name has no register move-result can reach.")
    val guarded = if (numberOfParameterRegisters == 0) {
        cloneMutable(additionalRegisters = 1).also { clone ->
            patchContext.mutableClassDefBy(definingClass).methods.apply {
                remove(this@returnWhenOn)
                add(clone)
            }
        }
    } else {
        with(patchContext) { cloneMutableAndPreserveParameters() }
    }
    val at = numberOfParameterRegistersLogical
    val through = "morphe_switched_through_${switchedReturnLabels++}"
    guarded.addInstructionsWithLabels(
        at,
        """
            invoke-static {}, $switch
            move-result v$scratch
            if-eqz v$scratch, :$through
            ${answer(scratch)}
        """,
        ExternalLabel(through, guarded.getInstruction(at)),
    )
}
