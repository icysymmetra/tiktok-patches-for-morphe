/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val ANDROID_VIEW = "Landroid/view/View;"
private const val VIEW_HOLDER = "Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;"
private const val IM_CONTACT = "Lcom/ss/android/ugc/aweme/im/contacts/api/model/IMContact;"
private const val BASE_CONTACT = "Lcom/ss/android/ugc/aweme/im/common/model/BaseContact;"
private const val IM_CONVERSATION =
    "Lcom/ss/android/ugc/aweme/im/contacts/api/model/IMConversation;"
private const val SHARE_TOOLS = "Lapp/morphe/extension/tiktok/share/ShareSheetTools;"
private const val BASE_SHARE_PACKAGE = "Lcom/ss/android/ugc/aweme/share/base/model/BaseSharePackage;"

/**
 * The share model builder's actions row, the List the snapshot below copies its actions from. The
 * story save button (#93) finds the snapshot's own actions field by where this one is stored.
 */
internal const val SHARE_BUILDER_ACTIONS = "LJFF"

internal object ShareSnapshotFingerprint : Fingerprint(
    strings = listOf("click_to_respond_duration", "config_duration").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.name == "<init>" && method.parameterTypes.size == 1 },
)

/**
 * The contacts adapter bind shared by user and conversation rows. Obfuscated owner and helper
 * names changed in every retained build, while its RecyclerView signature and the model API it
 * renders stayed the same.
 */
private object ShareRecipientBinderFingerprint : Fingerprint(
    name = "onBindViewHolder",
    parameters = listOf(VIEW_HOLDER, "I"),
    returnType = "V",
    custom = { method, _ -> method.isShareRecipientBinder() },
)

context(patchContext: BytecodePatchContext)
internal fun hookShareModel() {
    val method = ShareSnapshotFingerprint.method
    val builder = method.parameterTypes.single().toString()
    val packages = patchContext.mutableClassDefBy(builder).fields
        .filter { it.type == BASE_SHARE_PACKAGE }
    check(packages.isNotEmpty()) {
        "Share sheet: $builder holds no BaseSharePackage, so it is not the share model builder."
    }
    check(packages.size == 1) {
        "Share sheet: $builder holds ${packages.size} BaseSharePackage fields, so which one the sheet is built from is no longer obvious."
    }
    val callbacks = mapOf("LIZ" to "channels", SHARE_BUILDER_ACTIONS to "actions", "LJJIIJZLJL" to "contacts")
    val found = mutableSetOf<String>()
    method.implementation!!.instructions.withIndex().toList().asReversed().forEach { (index, instruction) ->
        val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference ?: return@forEach
        val callback = callbacks[field.name] ?: return@forEach
        if (field.definingClass != builder) return@forEach
        val boolean = field.name == "LJJIIJZLJL"
        val opcode = if (boolean) Opcode.IGET_BOOLEAN else Opcode.IGET_OBJECT
        if (instruction.opcode != opcode) return@forEach
        val type = if (boolean) "Z" else "Ljava/util/List;"
        if (field.type != type) throw PatchException("Share builder ${field.name} changed type")
        val register = (instruction as TwoRegisterInstruction).registerA
        method.addInstructions(index + 1, """
            invoke-static/range { v$register .. v$register }, Lapp/morphe/extension/tiktok/share/ShareModelFilter;->$callback($type)$type
            move-result${if (boolean) "" else "-object"} v$register
        """)
        found.add(field.name)
    }
    if (found != callbacks.keys) throw PatchException("Share panel hooks are incomplete: $found")

    // Last, so the indices the three hooks above were placed by are not moved under them.
    method.injectShareSurface("$builder->${packages.single().name}:$BASE_SHARE_PACKAGE")
}

/**
 * Tells the filter which sheet is being built before any of its three callbacks run: the
 * package's itemType says video, profile or LIVE, and each can hide a different set of actions.
 *
 * <p>Index 0 of a constructor comes before its super call, which is fine because nothing here
 * touches p0. The builder is p1, read with iget-object, whose four bit register fields cannot
 * name p1 in a frame with more than fourteen locals. Every build so far has nine, and a larger
 * one copies p1 into v0 first rather than failing.
 */
internal fun MutableMethod.injectShareSurface(packageField: String) {
    requireLocals("Share sheet tools", 1)
    val locals = implementation!!.registerCount - numberOfParameterRegisters
    val builder = if (locals + 1 <= 15) "p1" else "v0"
    val copy = if (builder == "p1") "" else "move-object/from16 v0, p1"
    addInstructions(
        0,
        """
            $copy
            iget-object v0, $builder, $packageField
            invoke-static { v0 }, Lapp/morphe/extension/tiktok/share/ShareModelFilter;->surface(Ljava/lang/Object;)V
        """,
    )
}

/**
 * Tells the extension each time a contact cell in the Send to row is bound, so it can run its
 * hiding pass on a panel window the activity's layout listener doesn't see being built.
 */
context(patchContext: BytecodePatchContext)
internal fun hookShareContactBinds() {
    ShareRecipientBinderFingerprint.method.notifyContactBound()
}

internal fun Method.isShareRecipientBinder(): Boolean {
    if (name != "onBindViewHolder" || returnType != "V" ||
        parameterTypes.map(CharSequence::toString) != listOf(VIEW_HOLDER, "I") ||
        AccessFlags.STATIC.isSet(accessFlags)
    ) {
        return false
    }
    val implementation = implementation ?: return false
    val instructions = implementation.instructions.toList()
    val holderParameter = implementation.registerCount - numberOfParameterRegisters + 1
    val holderCopy = instructions.firstOrNull() as? TwoRegisterInstruction ?: return false
    if (holderCopy.opcode != Opcode.MOVE_OBJECT_FROM16 ||
        holderCopy.registerB != holderParameter || holderCopy.registerA >= 16
    ) {
        return false
    }
    val contactCasts = instructions.withIndex().filter { (index, instruction) ->
        instruction.opcode == Opcode.CHECK_CAST &&
            ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type == IM_CONTACT &&
            instructions.getOrNull(index + 1)?.opcode == Opcode.IF_NEZ
    }
    if (contactCasts.size != 1) return false

    return referencesMethod(BASE_CONTACT, "getDisplayName", emptyList(), "Ljava/lang/String;") &&
        referencesMethod(BASE_CONTACT, "getUid", emptyList(), "Ljava/lang/String;") &&
        referencesMethod(IM_CONVERSATION, "getConversationId", emptyList(), "Ljava/lang/String;") &&
        referencesMethod(
            ANDROID_VIEW,
            "setContentDescription",
            listOf("Ljava/lang/CharSequence;"),
            "V",
        )
}

internal fun MutableMethod.notifyContactBound() {
    check(isShareRecipientBinder()) {
        "Share sheet: the recipient binder no longer has its verified native shape."
    }
    val castIndex = implementation!!.instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.CHECK_CAST &&
            ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type == IM_CONTACT
    }
    // After the cast and on any labels of the null check that follows it, so a null model
    // reaches the call too.
    addInstructionsAtControlFlowLabel(castIndex + 1, "invoke-static {}, $SHARE_TOOLS->contactBound()V")
}

private fun Method.referencesMethod(
    owner: String,
    name: String,
    parameters: List<String>,
    result: String,
): Boolean = implementation!!.instructions.any { instruction ->
    val reference = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        ?: return@any false
    reference.definingClass == owner && reference.name == name &&
        reference.parameterTypes.map(CharSequence::toString) == parameters &&
        reference.returnType == result
}
