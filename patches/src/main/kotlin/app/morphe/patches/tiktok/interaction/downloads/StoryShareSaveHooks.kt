/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.tiktok.interaction.sharesheet.SHARE_BUILDER_ACTIONS
import app.morphe.patches.tiktok.interaction.sharesheet.ShareSnapshotFingerprint
import app.morphe.patches.tiktok.misc.popups.writes
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutable
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val SHARE_SAVE = "Advanced downloads (story share sheet)"
internal const val STORY_SHARE_SAVE = "Lapp/morphe/extension/tiktok/download/StoryShareSave;"

/** How far past the builder's read its store may sit: Share sheet tools adds two, room for another hook's two. */
private const val STORE_REACH = 8
private const val SAVE_ACTIVITY = "Landroid/app/Activity;"
private const val SAVE_CONTEXT = "Landroid/content/Context;"
private const val SAVE_AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
private const val SAVE_STRING = "Ljava/lang/String;"
private const val SAVE_LIST = "Ljava/util/List;"
private const val SAVE_BASE_PACKAGE = "Lcom/ss/android/ugc/aweme/share/base/model/BaseSharePackage;"
private const val SAVE_SHARE_PACKAGE = "Lcom/ss/android/ugc/aweme/sharer/model/SharePackage;"

/** TikTok's key for its video save action. */
internal const val NATIVE_SAVE_KEY = "save"

/** The save action's constructor: the activity, the post, three log strings and their default mask. */
internal val SAVE_ACTION_PARAMETERS =
    listOf(SAVE_ACTIVITY, SAVE_AWEME, SAVE_STRING, SAVE_STRING, SAVE_STRING, "I")

/**
 * TikTok's own video save action, the share sheet item keyed `save` (#93). It's built from an
 * activity, the post and three log strings with their default mask, and it keeps the post in a
 * field. Its key answers the constant `save` and nothing else, it answers its own enable check,
 * and one click method takes (Context, BaseSharePackage) and casts the package to a SharePackage
 * before it starts TikTok's download. One class on 47.1.4 (X.0IIy there), and the only
 * constructor of that signature in the app.
 */
internal fun ClassDef.isNativeSaveAction(): Boolean {
    if (AccessFlags.INTERFACE.isSet(accessFlags) || AccessFlags.ABSTRACT.isSet(accessFlags)) return false
    if (methods.none { it.isSaveActionConstructor() }) return false
    if (fields.none { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == SAVE_AWEME }) return false
    if (methods.none { it.isSaveKey() }) return false
    if (methods.count { it.isEnableCheck() } != 1) return false
    return saveClicks().size == 1
}

internal fun Method.isSaveActionConstructor(): Boolean =
    name == "<init>" && returnType == "V" &&
        parameterTypes.map(CharSequence::toString) == SAVE_ACTION_PARAMETERS

/** `key()`, answering the constant `save` and doing nothing else. */
internal fun Method.isSaveKey(): Boolean {
    if (name != "key" || parameterTypes.isNotEmpty() || returnType != SAVE_STRING) return false
    val code = implementation?.instructions?.toList() ?: return false
    return code.size == 2 &&
        (code[0].opcode == Opcode.CONST_STRING || code[0].opcode == Opcode.CONST_STRING_JUMBO) &&
        ((code[0] as ReferenceInstruction).reference as? StringReference)?.string == NATIVE_SAVE_KEY &&
        code[1].opcode == Opcode.RETURN_OBJECT
}

/** The action's own `enable()`, which the panel asks before it greys a button and before it closes. */
internal fun Method.isEnableCheck(): Boolean =
    name == "enable" && parameterTypes.isEmpty() && returnType == "Z" &&
        !AccessFlags.STATIC.isSet(accessFlags) && implementation != null

/**
 * The action's click: an instance `(Context, BaseSharePackage)V` that casts the package to a
 * SharePackage. The class has two more methods of that signature, an empty one and one that hands
 * the tap to the panel's shared click helper, and neither casts.
 */
internal fun ClassDef.saveClicks(): List<Method> = methods.filter { method ->
    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
        method.parameterTypes.map(CharSequence::toString) == listOf(SAVE_CONTEXT, SAVE_BASE_PACKAGE) &&
        method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.CHECK_CAST &&
                ((instruction as ReferenceInstruction).reference as? TypeReference)?.type == SAVE_SHARE_PACKAGE
        } == true
}

internal object NativeSaveActionFingerprint : Fingerprint(
    returnType = "V",
    parameters = SAVE_ACTION_PARAMETERS,
    custom = { method, classDef -> method.name == "<init>" && classDef.isNativeSaveAction() },
)

/** The snapshot's field for the package its sheet was built from, and the one for its actions row. */
internal data class SnapshotActionFields(val sharePackage: String, val actions: String)

/**
 * The two fields of the share sheet snapshot ([ShareSnapshotFingerprint]) the save button needs:
 * its one BaseSharePackage, and its actions row, which is where the builder's actions row is
 * stored when the snapshot keeps it as it came. The dedupe, the filter and the sort store into that
 * same field, and nothing in the constructor reads it after, so the row is final at the return.
 * A store a few instructions on is still the same one: Share sheet tools filters the read first.
 */
internal fun Method.snapshotActionFields(snapshot: ClassDef): SnapshotActionFields {
    val builder = parameterTypes.singleOrNull()?.toString()
        ?: throw PatchException("$SHARE_SAVE: the share sheet snapshot no longer takes one builder")
    val packages = snapshot.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == SAVE_BASE_PACKAGE }
    val sharePackage = packages.singleOrNull() ?: throw PatchException(
        "$SHARE_SAVE: ${snapshot.type} holds ${packages.size} BaseSharePackage fields, so which sheet it is for isn't clear",
    )
    val code = implementation?.instructions?.toList()
        ?: throw PatchException("$SHARE_SAVE: $definingClass->$name has no code")
    val stores = mutableSetOf<String>()
    code.forEachIndexed { index, instruction ->
        if (instruction.opcode != Opcode.IGET_OBJECT) return@forEachIndexed
        val read = (instruction as ReferenceInstruction).reference as? FieldReference ?: return@forEachIndexed
        if (read.definingClass != builder || read.name != SHARE_BUILDER_ACTIONS || read.type != SAVE_LIST) {
            return@forEachIndexed
        }
        val register = (instruction as TwoRegisterInstruction).registerA
        // Not past a branch or a return: a store there might not follow this read. Nor past a
        // load into the register, since a store after that stores another list. A cast, and a
        // filter handing back the row it was given (Share sheet tools'), are the writes let through.
        val window = code.subList(index + 1, minOf(code.size, index + 1 + STORE_REACH))
        val reach = window.indices.takeWhile { at ->
            val next = window[at]
            next !is OffsetInstruction && next.opcode.canContinue() && (!next.writes(register) ||
                next.opcode == Opcode.CHECK_CAST ||
                next.opcode == Opcode.MOVE_RESULT_OBJECT && at > 0 && window[at - 1].passes(register))
        }.map { window[it] }
        val store = reach.firstOrNull { next ->
            next.opcode == Opcode.IPUT_OBJECT && (next as TwoRegisterInstruction).registerA == register &&
                ((next as ReferenceInstruction).reference as? FieldReference)
                    ?.let { it.definingClass == definingClass && it.type == SAVE_LIST } == true
        } ?: return@forEachIndexed
        stores += ((store as ReferenceInstruction).reference as FieldReference).name
    }
    val actions = stores.singleOrNull() ?: throw PatchException(
        "$SHARE_SAVE: the share sheet snapshot stores its builder's actions in ${stores.size} fields $stores",
    )
    return SnapshotActionFields(sharePackage.name, actions)
}

/** Whether this is a call that takes [register] as one of its arguments. */
private fun Instruction.passes(register: Int): Boolean {
    if ((this as? ReferenceInstruction)?.reference !is MethodReference) return false
    return when (this) {
        is RegisterRangeInstruction -> register in startRegister until startRegister + registerCount
        is FiveRegisterInstruction ->
            register in listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        else -> false
    }
}

/**
 * Before each return of the snapshot's constructor, hands its finished actions row and its package
 * to StoryShareSave.actions and stores the row it gets back. Off, paused or on anything but a
 * story, that is the row TikTok built.
 */
internal fun MutableMethod.addStorySaveToSnapshot(fields: SnapshotActionFields) =
    addShareActionToSnapshot(fields, STORY_SHARE_SAVE)

/** Both additions read the current finished row, so neither replaces the other's action. */
internal fun MutableMethod.addShareActionToSnapshot(fields: SnapshotActionFields, extension: String) {
    val code = implementation?.instructions?.toList()
        ?: throw PatchException("$SHARE_SAVE: $definingClass->$name has no code")
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
    if (returns.isEmpty()) throw PatchException("$SHARE_SAVE: the share sheet snapshot never returns")
    // iget-object and iput-object name the object in four bits.
    val locals = implementation!!.registerCount - numberOfParameterRegisters
    val self = if (locals <= 15) "p0" else "v2"
    requireLocals(SHARE_SAVE, if (self == "p0") 2 else 3)
    val copy = if (self == "p0") "" else "move-object/from16 v2, p0"
    val sharePackage = "$definingClass->${fields.sharePackage}:$SAVE_BASE_PACKAGE"
    val actions = "$definingClass->${fields.actions}:$SAVE_LIST"
    for (index in returns.asReversed()) {
        addInstructionsAtControlFlowLabel(
            index,
            """
                $copy
                iget-object v0, $self, $sharePackage
                iget-object v1, $self, $actions
                invoke-static { v0, v1 }, $extension->actions(Ljava/lang/Object;$SAVE_LIST)$SAVE_LIST
                move-result-object v1
                iput-object v1, $self, $actions
            """,
        )
    }
}

/** The save action's enable check answers true for an action StoryShareSave built, so it isn't greyed. */
internal fun MutableMethod.answerStorySaveEnable() {
    check(isEnableCheck()) { "$SHARE_SAVE: $definingClass->$name is not the save action's enable check" }
    guardAtEntry(
        SHARE_SAVE,
        "invoke-static/range { p0 .. p0 }, $STORY_SHARE_SAVE->owns(Ljava/lang/Object;)Z",
        """
            const/4 v0, 0x1
            return v0
        """,
    )
}

/** A tap on an action StoryShareSave built saves its story, and TikTok's download never starts. */
internal fun MutableMethod.takeStorySaveClick() {
    check(
        !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
            parameterTypes.map(CharSequence::toString) == listOf(SAVE_CONTEXT, SAVE_BASE_PACKAGE),
    ) { "$SHARE_SAVE: $definingClass->$name is not the save action's click" }
    guardAtEntry(
        SHARE_SAVE,
        "invoke-static/range { p0 .. p1 }, $STORY_SHARE_SAVE->click(Ljava/lang/Object;$SAVE_CONTEXT)Z",
        "return-void",
    )
}

/**
 * StoryShareSave.newSaveAction(Activity, Object) with TikTok's constructor written in: a new save
 * action for the story, from the activity, with empty log strings and no default mask. The click
 * that would read those strings never runs for it.
 */
internal fun Method.withStorySaveBody(actionType: String): MutableMethod {
    check(
        AccessFlags.STATIC.isSet(accessFlags) && returnType == "Ljava/lang/Object;" &&
            parameterTypes.map(CharSequence::toString) == listOf(SAVE_ACTIVITY, "Ljava/lang/Object;"),
    ) { "$SHARE_SAVE: $definingClass->$name is not the save action bridge" }
    // Seven locals in a row for the constructor's range call: the action, then its six arguments.
    return cloneMutable(additionalRegisters = 7).apply {
        addInstructions(
            0,
            """
                check-cast p1, $SAVE_AWEME
                new-instance v0, $actionType
                move-object/from16 v1, p0
                move-object/from16 v2, p1
                const-string v3, ""
                move-object v4, v3
                move-object v5, v3
                const/4 v6, 0x0
                invoke-direct/range { v0 .. v6 }, $actionType-><init>(${SAVE_ACTION_PARAMETERS.joinToString("")})V
                return-object v0
            """,
        )
    }
}

/** Puts a save button in the share sheet a story's Share button opens (#93). */
internal fun BytecodePatchContext.hookStoryShareSave() {
    val constructor = NativeSaveActionFingerprint.method
    val action = NativeSaveActionFingerprint.classDef
    for ((what, flags) in listOf(
        action.type to action.accessFlags,
        "${action.type}-><init>" to constructor.accessFlags,
    )) {
        if (!AccessFlags.PUBLIC.isSet(flags)) {
            throw PatchException("$SHARE_SAVE: $what isn't public in this build, and the extension builds it from outside.")
        }
    }
    val enable = action.methods.singleOrNull { it.isEnableCheck() }
        ?: throw PatchException("$SHARE_SAVE: ${action.type} has no single enable check")
    val clicks = action.saveClicks()
    val click = clicks.singleOrNull() as? MutableMethod
        ?: throw PatchException("$SHARE_SAVE: ${action.type} has ${clicks.size} click methods that cast to a SharePackage")
    enable.answerStorySaveEnable()
    click.takeStorySaveClick()

    val bridges = mutableClassDefBy(STORY_SHARE_SAVE)
    val original = bridges.methods.singleOrNull { it.name == "newSaveAction" && it.parameterTypes.size == 2 }
        ?: throw PatchException("$SHARE_SAVE: StoryShareSave has no single newSaveAction bridge")
    val bridge = original.withStorySaveBody(action.type)
    bridges.methods.remove(original)
    bridges.methods.add(bridge)

    // Last: the snapshot's other hooks are placed by index, and these go in at its returns.
    val snapshot = ShareSnapshotFingerprint.method
    snapshot.addStorySaveToSnapshot(snapshot.snapshotActionFields(ShareSnapshotFingerprint.classDef))
}
