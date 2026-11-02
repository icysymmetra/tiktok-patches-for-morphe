/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val VIEW_HOLDER = "Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;"
private const val GRID_PICKER = "Lapp/morphe/extension/tiktok/download/GridPicker;"
private const val GRID_AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val COLLECTION_CONTENT_ASSEM =
    "Lcom/ss/android/ugc/aweme/favorites/business/collection/assem/CollectionContentAssem;"

/** How far up the holder's superclasses RecyclerView.ViewHolder is looked for. */
private const val HOLDER_DEPTH = 8

/**
 * A Favorites collection folder's cell holder's bind (X/10J3.z0 on 47.1.4). The folder draws its
 * posts with its own holder and adapter (X/10J1), not the profile grid's, and this bind takes no
 * post: the adapter stores the post in the holder's field and then calls it, and it reads that
 * field first. Its holder is the one built for CollectionContentAssem, a name TikTok keeps, and
 * it logs as "AbsAwemeViewHolder" while it loads the cover.
 */
internal object CollectionGridHolderBindFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("AbsAwemeViewHolder").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, classDef ->
        classDef.methods.any {
            it.name == "<init>" && it.parameterTypes.firstOrNull()?.toString() == COLLECTION_CONTENT_ASSEM
        } && method.boundPostField() != null
    },
)

/** `this` plus each parameter, a long or a double taking two. */
private fun Method.parameterRegisters() = parameterTypes.fold(1) { total, type ->
    total + if (type.toString() == "J" || type.toString() == "D") 2 else 1
}

/**
 * The holder field a bind that takes no post reads it from: its first instruction has to be
 * `iget-object` of a post off `this`. Null otherwise.
 */
internal fun Method.boundPostField(): FieldReference? {
    val body = implementation ?: return null
    val first = body.instructions.firstOrNull() ?: return null
    if (first.opcode != Opcode.IGET_OBJECT) return null
    val field = (first as ReferenceInstruction).reference as? FieldReference ?: return null
    val thisRegister = body.registerCount - parameterRegisters()
    return field.takeIf { it.type == GRID_AWEME && (first as TwoRegisterInstruction).registerB == thisRegister }
}

/**
 * Why the profile grid cell holder's bind can't take the picker's hook, or null when it can. The
 * bind is ProfileGridHolderBindFingerprint's method (X/0Oec.r6 on 47.1.4), which every profile,
 * Liked and Favorites cell runs through with the post it is about to draw first.
 *
 * <p>The hook reads the holder's `itemView` at the very top, so the bind has to be an instance
 * method of a RecyclerView.ViewHolder (the field is named, androidx keeps it), it has to take the
 * post as its first parameter, and it needs two locals of its own for the view and the post.
 */
internal fun Method.gridPickProblem(superclassOf: (String) -> String?): String? {
    if (AccessFlags.STATIC.isSet(accessFlags)) return "the grid holder's bind is static"
    if (parameterTypes.firstOrNull()?.toString() != GRID_AWEME) {
        return "the grid holder's bind doesn't take the cell's post first"
    }
    return holderProblem(superclassOf)
}

/**
 * The same for a bind that reads its post from the holder (CollectionGridHolderBindFingerprint's):
 * instead of taking the post, it has to read it from `this` as its first instruction.
 */
internal fun Method.gridPickFieldProblem(superclassOf: (String) -> String?): String? {
    if (AccessFlags.STATIC.isSet(accessFlags)) return "the grid holder's bind is static"
    if (boundPostField() == null) return "the grid holder's bind doesn't read the cell's post first"
    return holderProblem(superclassOf)
}

private fun Method.holderProblem(superclassOf: (String) -> String?): String? {
    val body = implementation ?: return "the grid holder's bind has no code"
    if (body.registerCount - parameterRegisters() < 2) {
        return "the grid holder's bind has fewer than two local registers"
    }
    var type: String? = definingClass
    repeat(HOLDER_DEPTH) {
        if (type == VIEW_HOLDER) return null
        type = type?.let(superclassOf) ?: return "the grid holder isn't a RecyclerView.ViewHolder"
    }
    return if (type == VIEW_HOLDER) null else "the grid holder isn't a RecyclerView.ViewHolder"
}

/**
 * Hands each grid cell's root view and post to the picker before TikTok draws the post (#27). It
 * adds two views of its own over the cell when the switch is on and never touches TikTok's
 * listeners, so off or paused the cell is TikTok's as it was.
 */
internal fun MutableMethod.hookGridPick(superclassOf: (String) -> String?) {
    gridPickProblem(superclassOf)?.let { throw PatchException("Advanced downloads: $it.") }
    addInstructions(
        0,
        """
            iget-object v0, p0, $VIEW_HOLDER->itemView:Landroid/view/View;
            move-object/from16 v1, p1
            invoke-static { v0, v1 }, $GRID_PICKER->bind(Landroid/view/View;Ljava/lang/Object;)V
        """,
    )
}

/**
 * The same hand-over for a bind that reads its post from the holder, as a Favorites collection
 * folder's does: the post comes from the field the bind itself reads first, which the adapter
 * has just set.
 */
internal fun MutableMethod.hookGridPickFromField(superclassOf: (String) -> String?) {
    gridPickFieldProblem(superclassOf)?.let { throw PatchException("Advanced downloads: $it.") }
    val post = boundPostField()!!
    addInstructions(
        0,
        """
            iget-object v0, p0, $VIEW_HOLDER->itemView:Landroid/view/View;
            iget-object v1, p0, ${post.definingClass}->${post.name}:${post.type}
            invoke-static { v0, v1 }, $GRID_PICKER->bind(Landroid/view/View;Ljava/lang/Object;)V
        """,
    )
}
