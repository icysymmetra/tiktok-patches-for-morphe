package app.morphe.patches.tiktok.misc.comment

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import app.morphe.patcher.util.proxy.mutableTypes.MutableField.Companion.toMutable
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.tiktok.misc.commenttools.NativeCommentTextBindFingerprint
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructionsWithLabels
import app.morphe.util.writeRegister
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableField

private const val PATCH = "Comment text controller ownership"
internal const val COMMENT_TEXT_CONTROLLER = "metraCommentTextController"

internal object CommentTranslatedTextFingerprint : Fingerprint(
    strings = listOf("CommentViewWrapper setCommentTranslatedText, awemeId: ")
        .map { string(it, StringComparisonType.CONTAINS) },
)

internal data class CommentTextController(val constructor: Method, val cancel: Method)

/** Follow the real wrapper/renderer relationship, never a version's obfuscated names. */
internal fun commentTextController(wrapper: ClassDef, renderer: ClassDef): CommentTextController {
    fun fail(why: String): Nothing = throw PatchException("$PATCH: $why")
    if (renderer.fields.any { it.name == COMMENT_TEXT_CONTROLLER }) fail("renderer already has the ownership field")
    val field = wrapper.fields.singleOrNull {
        it.type == renderer.type && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: fail("expected one renderer field on the text wrapper")
    val constructor = wrapper.methods.singleOrNull {
        it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf(
            "Landroid/content/Context;", renderer.type, "Landroidx/fragment/app/Fragment;",
        ) && it.returnType == "V" && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: fail("expected one native renderer-wrapper constructor")
    val instructions = constructor.implementation?.instructions?.toList() ?: fail("constructor has no body")
    val firstParameter = constructor.implementation!!.registerCount - constructor.numberOfParameterRegisters
    val viewParameter = firstParameter + 2
    val writes = instructions.filter { it.opcode == Opcode.IPUT_OBJECT && it.getReference<FieldReference>() == field }
    if (writes.size != 1 || (writes.single() as TwoRegisterInstruction).let {
            it.registerA != viewParameter || it.registerB != firstParameter
        }) fail("constructor no longer stores its renderer argument")
    if (instructions.any { it.writeRegister == firstParameter || it.writeRegister == viewParameter }) {
        fail("constructor overwrites its receiver or renderer argument")
    }
    if (instructions.count { it.opcode == Opcode.RETURN_VOID } != 1 || instructions.last().opcode != Opcode.RETURN_VOID) {
        fail("constructor has an unreviewed exit")
    }
    if (firstParameter < 1 || viewParameter > 15 || firstParameter > 15) fail("constructor frame cannot encode ownership")
    val cancel = wrapper.methods.singleOrNull { method ->
        method.parameterTypes.isEmpty() && method.returnType == "V" &&
            AccessFlags.PUBLIC.isSet(method.accessFlags) && !AccessFlags.STATIC.isSet(method.accessFlags) &&
            method.implementation?.instructions?.any {
                it.getReference<MethodReference>()?.let { ref ->
                    ref.definingClass == "Landroid/animation/AnimatorSet;" && ref.name == "cancel" &&
                        ref.parameterTypes.isEmpty() && ref.returnType == "V"
                } == true
            } == true
    } ?: fail("expected one native controller cancellation method")
    // This is full native cleanup, not merely stopping alpha: it also calls its own
    // overlay/spring reset routine after cancelling the AnimatorSet.
    if (cancel.implementation!!.instructions.none {
            it.getReference<MethodReference>()?.let { ref ->
                ref.definingClass == wrapper.type && ref.name != cancel.name && ref.parameterTypes.isEmpty() &&
                    ref.returnType == "V"
            } == true
        }) fail("native cancellation no longer resets the renderer")
    return CommentTextController(constructor, cancel)
}

/** The native constructor has finished initialising this controller before taking ownership. */
internal fun MutableMethod.claimCommentTextRenderer(renderer: String, cancel: Method) {
    requireLocals(PATCH, 1)
    val exit = implementation!!.instructions.lastIndex
    val field = "$renderer->$COMMENT_TEXT_CONTROLLER:$definingClass"
    addInstructionsWithLabels(exit, """
        if-eqz p2, :metra_controller_done
        iget-object v0, p2, $field
        if-eqz v0, :metra_controller_claim
        invoke-virtual {v0}, ${cancel.definingClass}->${cancel.name}()V
        :metra_controller_claim
        iput-object p0, p2, $field
    """, ExternalLabel("metra_controller_done", getInstruction(exit)))
}

/**
 * Legacy comment binds allocate a new wrapper without cancelling the previous one.
 * Its 100 ms translation callback can then repaint the recycled view with the old
 * comment. Keep one native controller per renderer, cancelling it on replacement.
 * The field is instance-owned, so neither views nor activities enter a static map.
 */
internal val commentTextControllerPatch = bytecodePatch {
    execute {
        val renderer = NativeCommentTextBindFingerprint.originalClassDef
        val wrapper = CommentTranslatedTextFingerprint.originalClassDef
        val targets = commentTextController(wrapper, renderer)
        val constructor = mutableClassDefBy(wrapper.type).findMutableMethodOf(targets.constructor)
        constructor.claimCommentTextRenderer(renderer.type, targets.cancel)
        val owner = mutableClassDefBy(renderer.type)
        val field = ImmutableField(
            renderer.type, COMMENT_TEXT_CONTROLLER, wrapper.type,
            AccessFlags.PUBLIC.value or AccessFlags.SYNTHETIC.value, null, null, null,
        ).toMutable()
        owner.fields.add(field)
        owner.instanceFields.add(field)
    }
}
