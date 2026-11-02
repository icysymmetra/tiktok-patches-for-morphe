/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.util.getFreeRegisterProvider
import app.morphe.patcher.util.getReference
import app.morphe.util.writeRegister
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val COMMENT_MENU_BLOCK_DESCRIPTOR = "Lapp/morphe/extension/tiktok/comment/CommentMenuBlock;"
internal const val COMMENT_MENU_PANEL = "Lcom/ss/android/ugc/aweme/comment/longpresspanel/ui/CommentLongPressFragment;"
private const val USER = "Lcom/ss/android/ugc/aweme/profile/model/User;"
private const val LIST = "Ljava/util/List;"

/**
 * The comment menu's one list sink (#169): the panel's single `(List)` method, which builds its
 * adapter, stores the finished rows in it and sets it on the RecyclerView. The panel keeps its
 * name and both callers (the preloaded rows and the loaded ones) go through it.
 */
internal fun isCommentMenuListSink(method: Method): Boolean {
    if (method.definingClass != COMMENT_MENU_PANEL || method.returnType != "V" ||
        method.parameterTypes.map(CharSequence::toString) != listOf(LIST)
    ) return false
    val implementation = method.implementation ?: return false
    val instructions = implementation.instructions.toList()
    val rows = implementation.registerCount - 1
    val adapters = instructions.filter { it.opcode == Opcode.NEW_INSTANCE }
        .mapNotNull { it.getReference<TypeReference>()?.type }.toSet()
    val storesRows = instructions.any { instruction ->
        val field = instruction.getReference<FieldReference>()
        instruction.opcode == Opcode.IPUT_OBJECT && instruction is TwoRegisterInstruction &&
            instruction.registerA == rows && field?.type == LIST && field.definingClass in adapters
    }
    val setsAdapter = instructions.any { instruction ->
        instruction.getReference<MethodReference>()?.let {
            it.definingClass == "Landroidx/recyclerview/widget/RecyclerView;" && it.name == "setAdapter" &&
                it.parameterTypes.size == 1
        } == true
    }
    return storesRows && setsAdapter
}

internal object CommentMenuListFingerprint : Fingerprint(
    definingClass = COMMENT_MENU_PANEL,
    returnType = "V",
    parameters = listOf(LIST),
    custom = { method, _ -> isCommentMenuListSink(method) },
)

/**
 * The coroutine that builds the comment menu's rows. Its log names the panel's
 * `loadLongPressItems`, and the Report row it builds carries the icon the block row matches.
 * One string, not two: the log is a single literal ("CommentLongPressFragment@922.loadLongPressItems$2"
 * on 47.1.4), and the patcher lets one string load answer one wanted string only.
 */
internal object CommentMenuItemsFingerprint : Fingerprint(
    name = "invokeSuspend",
    returnType = "Ljava/lang/Object;",
    strings = listOf(".loadLongPressItems").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.reportRowIcon() != null },
)

/** A share action's `key()` that answers [key] and nothing else. */
internal fun isShareActionKey(method: Method, key: String): Boolean {
    if (method.name != "key" || method.returnType != "Ljava/lang/String;" || method.parameterTypes.isNotEmpty()) {
        return false
    }
    val instructions = method.implementation?.instructions?.toList() ?: return false
    return instructions.size == 2 && instructions[0].opcode == Opcode.CONST_STRING &&
        instructions[0].getReference<StringReference>()?.string == key &&
        instructions[1].opcode == Opcode.RETURN_OBJECT
}

/** TikTok's profile Block share action, whose icon getters draw its block glyph. */
internal object ProfileBlockActionKeyFingerprint : Fingerprint(
    name = "key",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("block_user").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> isShareActionKey(method, "block_user") },
)

/**
 * The ad Report share actions (47.1.4 has two, for two panels). One icon getter among them
 * returns the icon the comment menu's Report row uses, which says which of the share actions'
 * icon getters draws the menu's style. Taken with `matchAll`.
 */
internal object AdReportActionKeyFingerprint : Fingerprint(
    name = "key",
    returnType = "Ljava/lang/String;",
    parameters = listOf(),
    strings = listOf("ad_report").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> isShareActionKey(method, "ad_report") },
)

/** The literal a const writes, or null for anything else. */
private fun Instruction.literal(): Int? {
    if (!opcode.name.startsWith("const") || opcode.name.startsWith("const-string") ||
        opcode.name.startsWith("const-class")
    ) return null
    return when (this) {
        is WideLiteralInstruction -> wideLiteral.toInt()
        is NarrowLiteralInstruction -> narrowLiteral
        else -> null
    }
}

private fun Method.literals(): Set<Int> =
    implementation?.instructions?.mapNotNull { it.literal() }?.toSet().orEmpty()

/**
 * The icon the comment menu's Report row is built with: the constant boxed by the
 * `Integer.valueOf` right after each `"report"` key. Null unless every Report row agrees.
 */
internal fun Method.reportRowIcon(): Int? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val icons = instructions.withIndex().filter { (_, instruction) ->
        instruction.opcode == Opcode.CONST_STRING && instruction.getReference<StringReference>()?.string == "report"
    }.map { (index, _) ->
        val box = instructions.getOrNull(index + 1) ?: return null
        val call = box.getReference<MethodReference>()
        if (call?.definingClass != "Ljava/lang/Integer;" || call.name != "valueOf") return null
        val register = when (box) {
            is RegisterRangeInstruction -> box.startRegister
            is FiveRegisterInstruction -> box.registerC
            else -> return null
        }
        val writer = (index downTo 0).firstOrNull { instructions[it].writeRegister == register } ?: return null
        instructions[writer].literal() ?: return null
    }.toSet()
    return icons.singleOrNull()?.takeIf { it ushr 24 == 0x7f }
}

/**
 * The icon the getter returns for an account that isn't blocked yet. It reads the user's
 * isBlock field, or asks isBlock() as 47.4.3 does, and branches on it straight away.
 */
internal fun Method.unblockedIcon(): Int? {
    val instructions = implementation?.instructions?.toList() ?: return null
    val read = instructions.indices.firstOrNull { index ->
        val instruction = instructions[index]
        when (instruction.opcode) {
            Opcode.IGET_BOOLEAN -> instruction.getReference<FieldReference>()?.let { field ->
                field.definingClass == USER && field.name == "isBlock"
            } == true
            Opcode.MOVE_RESULT -> instructions.getOrNull(index - 1)?.getReference<MethodReference>()?.let { call ->
                call.definingClass == USER && call.name == "isBlock" && call.parameterTypes.isEmpty()
            } == true
            else -> false
        }
    } ?: return null
    val blocked = (instructions[read] as OneRegisterInstruction).registerA
    val test = instructions.getOrNull(read + 1) ?: return null
    if ((test.opcode != Opcode.IF_EQZ && test.opcode != Opcode.IF_NEZ) ||
        (test as OneRegisterInstruction).registerA != blocked
    ) return null
    val addresses = instructions.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    val target = addresses.indexOf(addresses[read + 1] + (test as OffsetInstruction).codeOffset)
    // if-eqz jumps when the account isn't blocked; if-nez falls through then.
    val from = if (test.opcode == Opcode.IF_EQZ) target else read + 2
    if (from < 0 || from >= instructions.size) return null
    return instructions[from].literal()?.takeIf { it ushr 24 == 0x7f }
}

/**
 * TikTok's block glyph in the style the comment menu draws its icons: the profile Block action's
 * not-blocked icon, read from the same getter that gives an ad Report action the icon the
 * menu's Report row uses.
 */
internal fun commentMenuBlockIcon(items: Method, reportActions: List<ClassDef>, blockAction: ClassDef): Int {
    fun fail(why: String): Nothing = throw PatchException("Comment tools: $why")
    val report = items.reportRowIcon() ?: fail("the comment menu's Report row has no single icon constant")
    val getters = reportActions.flatMap { action ->
        action.methods.filter { it.returnType == "I" && it.parameterTypes.isEmpty() && report in it.literals() }
    }
    if (getters.size != 1) {
        fail("expected one icon getter on the ad Report actions ${reportActions.map { it.type }} returning " +
            "the Report row's icon, found ${getters.size}")
    }
    val name = getters.single().name
    val getter = blockAction.methods.singleOrNull {
        it.name == name && it.returnType == "I" && it.parameterTypes.isEmpty()
    } ?: fail("the profile Block action ${blockAction.type} has no icon getter $name")
    return getter.unblockedIcon() ?: fail("could not read the not-blocked icon from ${blockAction.type}->$name")
}

/** Routes the comment menu's finished rows through the extension, which adds the Block row. */
internal fun MutableMethod.resolveCommentMenuBlock(icon: Int): CommentToolsWrite {
    if (!isCommentMenuListSink(this)) throw PatchException("Comment tools: $this is not the comment menu's list sink")
    // Nothing is live yet at the method's first instruction, so any local is free there.
    val iconRegister = getFreeRegisterProvider(0, 1).getFreeRegister4Bit()
    return {
        addInstructions(
            0,
            """
                const v$iconRegister, 0x${Integer.toHexString(icon)}
                invoke-static {v$iconRegister}, $COMMENT_MENU_BLOCK_DESCRIPTOR->setIcon(I)V
                invoke-static/range {p0 .. p1}, $COMMENT_MENU_BLOCK_DESCRIPTOR->items(Ljava/lang/Object;Ljava/util/List;)Ljava/util/List;
                move-result-object p1
            """,
        )
    }
}
