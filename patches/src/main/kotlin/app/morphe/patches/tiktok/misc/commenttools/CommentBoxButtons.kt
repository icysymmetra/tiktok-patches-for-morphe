/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstruction
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val BASE_INPUT_ASSEM = "Lcom/ss/android/ugc/aweme/comment/keyboard/keyboardv2/refactor/BaseInputAssem;"
internal const val BASE_FAKE_INPUT = "Lcom/ss/android/ugc/aweme/comment/keyboard/keyboardv2/refactor/BaseFakeInput;"
internal const val TUX_ICON_VIEW = "Lcom/bytedance/tux/icon/TuxIconView;"
internal const val COMMENT_BOX_BUTTONS_CLASS_DESCRIPTOR = "Lapp/morphe/extension/tiktok/comment/CommentBoxButtons;"

/**
 * The comment box's view setup. Every comment input, the "Add comment..." bar and the one over the
 * keyboard alike, extends BaseInputAssem, and this is where it finds its icon buttons and keeps
 * each in a field. The field names move from build to build.
 */
internal object CommentInputViewCreatedFingerprint : Fingerprint(
    definingClass = BASE_INPUT_ASSEM,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

/** The "Add comment..." bar's setup, which gives each icon field its click listener. */
internal object FakeInputViewCreatedFingerprint : Fingerprint(
    definingClass = BASE_FAKE_INPUT,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;"),
)

/** The comment box buttons Hide comment box buttons takes away, each with its extension entry. */
internal enum class CommentBoxButton(val entry: String) {
    PHOTO("photo"),
    MENTION("mention"),
    GIFT("gift"),
}

/**
 * Which button each icon field is, told by what its click listener does: the photo button logs
 * click_comment_photo, and the @ and gift buttons open the input's MENTION and GIFT panels (the
 * panel enum keeps its constant names). The emoji button opens EMOJI and stays.
 */
internal fun commentBoxButtonFields(
    fakeInputViewCreated: Method,
    classDefOf: (String) -> ClassDef?,
): Map<CommentBoxButton, FieldReference> {
    val instructions = fakeInputViewCreated.implementation?.instructions?.toList()
        ?: throw PatchException("Comment tools: the comment box setup has no body")
    val found = HashMap<CommentBoxButton, MutableList<FieldReference>>()
    instructions.forEachIndexed { index, instruction ->
        if (instruction.opcode != Opcode.IGET_OBJECT) return@forEachIndexed
        val field = instruction.getReference<FieldReference>() ?: return@forEachIndexed
        if (field.definingClass != BASE_INPUT_ASSEM || field.type != TUX_ICON_VIEW) return@forEachIndexed
        val listener = instructions.subList(index + 1, minOf(index + 4, instructions.size))
            .firstOrNull { it.opcode == Opcode.NEW_INSTANCE }
            ?.getReference<TypeReference>()?.type ?: return@forEachIndexed
        val button = classDefOf(listener)?.let(::commentBoxButtonOf) ?: return@forEachIndexed
        found.getOrPut(button) { mutableListOf() }.add(field)
    }
    return CommentBoxButton.entries.associateWith { button ->
        val fields = found[button].orEmpty().distinctBy { it.name }
        fields.singleOrNull() ?: throw PatchException(
            "Comment tools: expected one comment box ${button.entry} button field, found ${fields.map { it.name }}",
        )
    }
}

internal fun commentBoxButtonOf(listener: ClassDef): CommentBoxButton? {
    val instructions = listener.methods.flatMap { it.implementation?.instructions ?: emptyList() }
    if (instructions.any { it.getReference<StringReference>()?.string == "click_comment_photo" }) {
        return CommentBoxButton.PHOTO
    }
    val panels = instructions.filter { it.opcode == Opcode.SGET_OBJECT }
        .mapNotNull { it.getReference<FieldReference>() }
        .filter { it.type == it.definingClass }
        .map { it.name }
    return when {
        "MENTION" in panels -> CommentBoxButton.MENTION
        "GIFT" in panels -> CommentBoxButton.GIFT
        else -> null
    }
}

/** Where the input's setup stores [field]: the one iput-object of it, by index. */
internal fun commentBoxButtonStore(viewCreated: Method, field: FieldReference): Int {
    val stores = viewCreated.implementation!!.instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.IPUT_OBJECT &&
            instruction.getReference<FieldReference>()?.let {
                it.definingClass == BASE_INPUT_ASSEM && it.name == field.name && it.type == TUX_ICON_VIEW
            } == true
    }
    return stores.singleOrNull()?.index ?: throw PatchException(
        "Comment tools: the comment box setup stores ${field.name} ${stores.size} times, not once",
    )
}

/** Hands each button to the extension the moment the setup stores it. */
internal fun MutableMethod.resolveCommentBoxButtons(fields: Map<CommentBoxButton, FieldReference>): CommentToolsWrite {
    val stores = fields.map { (button, field) ->
        val index = commentBoxButtonStore(this, field)
        val view = (implementation!!.instructions.elementAt(index) as TwoRegisterInstruction).registerA
        Triple(index, view, button)
    }
    return {
        stores.sortedByDescending { it.first }.forEach { (index, view, button) ->
            addInstruction(
                index + 1,
                "invoke-static/range {v$view .. v$view}, $COMMENT_BOX_BUTTONS_CLASS_DESCRIPTOR->${button.entry}(Landroid/view/View;)V",
            )
        }
    }
}
