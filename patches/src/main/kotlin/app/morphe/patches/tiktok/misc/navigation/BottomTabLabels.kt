/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.navigation

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstruction
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val BOTTOM_TAB_LABELS_EXTENSION = "Lapp/morphe/extension/tiktok/navigation/BottomTabLabels;"
private const val TUX_TEXT_VIEW = "Lcom/bytedance/tux/input/TuxTextView;"
private const val CONTEXT = "Landroid/content/Context;"
private const val STRING = "Ljava/lang/String;"

/**
 * Where the names under the bottom tab icons are reached. Every piece is found from the tab icon
 * class (the one whose badge setters Feed tab navigation already answers) through members that
 * keep their real names on every declared build:
 *
 * - the icon view's constructor takes the icon data, whose base class stores the tab's tag
 *   from its first String argument, and the data compares that tag with HOME;
 * - setTitleText reads the name view from one field of the icon, and the tab logic (the type
 *   setIconTabLogic takes) stores that field in exactly one place, right after making the view.
 *
 * BottomTabLabelsAnchorsTest holds each piece to every declared build, and checks that nothing
 * else in the app writes the name view.
 */
internal class BottomTabLabelSites(
    /** The icon view's constructor on its icon data. */
    val constructor: Method,
    /** The icon data's tag. */
    val tag: FieldReference,
    /** The tab logic method that sets the name view on the icon, and where it does. */
    val labelSetter: Method,
    val labelStore: Int,
    /** The registers of that store: the name view, and the icon it goes on. */
    val label: Int,
    val icon: Int,
)

internal fun bottomTabLabelSites(icon: ClassDef, classBy: (String) -> ClassDef?): BottomTabLabelSites {
    fun fail(problem: String): Nothing = throw PatchException("Feed tab navigation: $problem")
    fun ClassDef.only(name: String): Method =
        methods.singleOrNull { it.name == name } ?: fail("$type has no single $name.")
    fun Method.takes(vararg types: String) = parameterTypes.map(CharSequence::toString) == types.toList()

    val title = icon.only("setTitleText").implementation?.instructions
        ?.firstOrNull { it.opcode == Opcode.IGET_OBJECT }?.getReference<FieldReference>()
        ?.takeIf { it.definingClass == icon.type && it.type == TUX_TEXT_VIEW }
        ?: fail("${icon.type}->setTitleText reads no name view of its own.")

    val dataType = icon.only("getIconData").returnType
    val constructor = icon.methods.singleOrNull { it.name == "<init>" && it.takes(dataType) }
        ?: fail("${icon.type} has no constructor on its icon data $dataType.")
    val code = constructor.implementation ?: fail("the tab icon constructor has no code.")
    // Two registers for the view and its data, a local below them for the tag, and both within
    // a four-bit call's reach.
    if (code.instructions.count { it.opcode == Opcode.RETURN_VOID } != 1 || code.registerCount !in 3..16) {
        fail("the tab icon constructor doesn't end in one return with a free local and its arguments in reach.")
    }
    // The hook reads the view (p0) and its data (p1) at the return, so nothing before may reuse them.
    val view = code.registerCount - 2
    if (code.instructions.any { it.writes(view) || it.writes(view + 1) }) {
        fail("the tab icon constructor reuses its view or data register before it returns.")
    }

    val data = classBy(dataType) ?: fail("no icon data class $dataType.")
    val comparesHome = data.methods.any { method ->
        method.name == "<init>" &&
            method.implementation?.instructions?.any { it.getReference<StringReference>()?.string == "HOME" } == true
    }
    if (!comparesHome) fail("$dataType never compares its tag with HOME, so it isn't the icon data.")
    val base = data.superclass?.let(classBy) ?: fail("no base class for $dataType.")
    val baseConstructor = base.methods.singleOrNull { it.name == "<init>" && it.takes(CONTEXT, STRING, STRING) }
        ?: fail("${base.type} has no constructor on a context and two strings.")
    val baseCode = baseConstructor.implementation ?: fail("${base.type}'s constructor has no code.")
    // The arguments fill the last registers: the instance, the context, then the tag.
    val tagRegister = baseCode.registerCount - 2
    val tag = baseCode.instructions
        .filter { it.opcode == Opcode.IPUT_OBJECT && (it as TwoRegisterInstruction).registerA == tagRegister }
        .mapNotNull { it.getReference<FieldReference>() }
        .singleOrNull()
        ?.takeIf { it.definingClass == base.type && it.type == STRING }
        ?: fail("${base.type} doesn't keep its tag in one field.")
    // The icon view reads the tag off its data, a class of its own, so the field has to be public.
    val tagPublic = base.fields.singleOrNull { it.name == tag.name && it.type == tag.type }
        ?.let { AccessFlags.PUBLIC.isSet(it.accessFlags) } == true
    if (!tagPublic) fail("${base.type}'s tag field isn't public, so the icon view can't read it.")

    val logicType = icon.only("setIconTabLogic").parameterTypes.singleOrNull()?.toString()
        ?: fail("${icon.type}->setIconTabLogic takes no single tab logic.")
    val logic = classBy(logicType) ?: fail("no tab logic class $logicType.")
    val stores = logic.methods.flatMap { method ->
        method.implementation?.instructions?.withIndex()?.filter { (_, instruction) ->
            instruction.opcode == Opcode.IPUT_OBJECT &&
                instruction.getReference<FieldReference>()?.let {
                    it.definingClass == title.definingClass && it.name == title.name && it.type == title.type
                } == true
        }?.map { method to it }.orEmpty()
    }
    val (labelSetter, store) = stores.singleOrNull()
        ?: fail("the tab logic $logicType sets the name view ${stores.size} times, not once.")
    val put = store.value as TwoRegisterInstruction
    if (put.registerA > 15 || put.registerB > 15) fail("the name view's store is out of a four-bit call's reach.")
    return BottomTabLabelSites(constructor, tag, labelSetter, store.index, put.registerA, put.registerB)
}

/**
 * Hands the icon view and its tab's tag to the extension as the constructor returns. Every local
 * is dead there, so v0 carries the tag.
 */
internal fun MutableMethod.reportBottomTab(tag: FieldReference) {
    val returnIndex = implementation!!.instructions.indexOfFirst { it.opcode == Opcode.RETURN_VOID }
    addInstructionsAtControlFlowLabel(
        returnIndex,
        """
            iget-object v0, p1, ${tag.definingClass}->${tag.name}:${tag.type}
            invoke-static { p0, v0 }, $BOTTOM_TAB_LABELS_EXTENSION->tabCreated(Landroid/view/View;Ljava/lang/String;)V
        """,
    )
}

/** Hands the name view to the extension right after the tab logic sets it on the icon. */
internal fun MutableMethod.reportBottomTabLabel(sites: BottomTabLabelSites) {
    addInstruction(
        sites.labelStore + 1,
        "invoke-static { v${sites.icon}, v${sites.label} }, " +
            "$BOTTOM_TAB_LABELS_EXTENSION->labelCreated(Landroid/view/View;Landroid/widget/TextView;)V",
    )
}

/** The mutable copy of [method] among these methods, matched by name and signature. */
internal fun Iterable<MutableMethod>.mutableCopyOf(method: Method): MutableMethod = single {
    it.name == method.name && it.returnType == method.returnType &&
        it.parameterTypes.map(CharSequence::toString) == method.parameterTypes.map(CharSequence::toString)
}
