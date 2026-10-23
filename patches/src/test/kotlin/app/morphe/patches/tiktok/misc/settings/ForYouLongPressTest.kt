/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.settings

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction3rc
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val STRIP = "LX/Strip;"
private const val TAB_VIEW = "LX/TabView;"
private const val TAB_MODEL = "LX/TabModel;"
private const val TAB = "LX/Tab;"
private const val VIEW = "Landroid/view/View;"

/**
 * A long press on For You opens Hushfeed settings: the settings patch hands every top tab TikTok
 * binds to the extension, with the tag of the tab it shows. The site is held to every declared
 * build, and a build that binds its top tabs some other way keeps the rest of the settings patch
 * and leaves For You as TikTok made it.
 */
class ForYouLongPressTest {
    private val tagRead = ImmutableMethodReference(TAB_MODEL, "tag", emptyList(), "Ljava/lang/String;")
    private val bind = ImmutableMethodReference(TAB_VIEW, "bind", emptyList(), "V")

    @Test
    fun `the strip's tab factory is the one place a top tab is handed over`() {
        val lookup = findTopTabBind(classes(factory("make")))
        val site = lookup.site ?: throw AssertionError("no site: ${lookup.miss}")

        assertNull(lookup.miss)
        assertEquals("make", site.method.name)
        assertEquals("the return right after the bind", 5, site.index)
        assertEquals("the tab's view", 1, site.tabView)
        assertEquals("the tab model parameter", 6, site.tabModel)
        assertEquals(tagRead, site.tagRead)
        assertEquals("the lowest register nothing reads at the return", 0, site.tag)
    }

    @Test
    fun `the hook goes after the bind, and a tab without a view still jumps past it`() {
        val site = findTopTabBind(classes(factory("make"))).site!!
        val method = MutableMethod(site.method)

        method.handTopTabOver(site)

        val code = method.implementation!!.instructions.toList()
        assertEquals(
            listOf(
                Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT, Opcode.NEW_INSTANCE, Opcode.IF_EQZ,
                Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT, Opcode.INVOKE_STATIC,
                Opcode.RETURN_OBJECT,
            ),
            code.map { it.opcode },
        )
        assertHandsOver(code, site.index, site, "synthetic")
        assertEquals("the null check no longer lands on the return", 8,
            (code[3] as BuilderOffsetInstruction).target.location.index)
    }

    @Test
    fun `a TikTok without the top tab ability has nothing to hook`() {
        val lookup = findTopTabBind { null }

        assertNull(lookup.site)
        assertTrue(lookup.miss!!, lookup.miss!!.contains("has no $TOP_TAB_LAYOUT_ABILITY"))
    }

    @Test
    fun `a factory that does more between the bind and the return is not a site`() {
        val moved = factory("make", afterBind = listOf(ImmutableInstruction11n(Opcode.CONST_4, 0, 0)))

        assertNoSite(classes(moved), "binds a top tab in 0 places, not one")
    }

    @Test
    fun `a tag read from a local register is not the tab model`() {
        assertNoSite(classes(factory("make", modelRegister = 2)), "binds a top tab in 0 places, not one")
    }

    @Test
    fun `a bind on a view the strip doesn't build is not a site`() {
        assertNoSite(classes(factory("make"), viewConstructor = listOf("Landroid/content/Context;")),
            "binds a top tab in 0 places, not one")
    }

    @Test
    fun `two factories are one too many`() {
        assertNoSite(classes(factory("make"), factory("makeAgain")), "binds a top tab in 2 places, not one")
    }

    @Test
    fun `a factory whose tag read needs range registers is left alone`() {
        assertNoSite(classes(factory("make", registers = 20)), "binds a top tab in 0 places, not one")
    }

    @Test
    fun `each declared build hands its top tabs over once, from the tab factory`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val app = load(apk)
            val lookup = findTopTabBind { app[it] }
            val site = lookup.site ?: throw AssertionError("$version: ${lookup.miss}")

            val strip = app.getValue(topTabStripType(app.getValue(TOP_TAB_LAYOUT_ABILITY))!!)
            assertEquals("$version: the strip", "Landroid/widget/HorizontalScrollView;", strip.superclass)
            assertEquals("$version: the factory takes a view and the tab model",
                listOf(VIEW, site.tagRead.definingClass), site.method.parameterTypes.map(CharSequence::toString))
            val body = site.method.implementation!!.instructions.toList()
            val view = app.getValue(((body[site.index - 1] as ReferenceInstruction).reference as MethodReference).definingClass)
            assertEquals("$version: TikTok's top tab", "Landroid/widget/LinearLayout;", view.superclass)

            // Every top tab view is made in the factory, so every one TikTok shows passes the hook.
            val makers = app.values.flatMap { owner ->
                owner.methods.filter { method -> method.implementation?.instructions?.any { it.makes(view.type) } == true }
                    .map { "${owner.type}->${it.name}" }
            }
            assertEquals("$version: what makes a top tab view", listOf("${strip.type}->${site.method.name}"), makers)

            // TikTok's tap is the tab's own performClick, and nothing gives the tab a long press or a
            // touch listener, so the extension's touch listener takes nothing of TikTok's.
            assertTrue("$version: the tab's tap", view.methods.any {
                it.name == "performClick" && it.parameterTypes.isEmpty() && it.returnType == "Z"
            })
            for (owner in listOf(strip, view)) for (method in owner.methods) {
                method.implementation?.instructions?.forEach { instruction ->
                    val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference ?: return@forEach
                    assertFalse("$version: ${owner.type}->${method.name} calls ${call.name}",
                        call.name in setOf("setOnTouchListener", "setOnLongClickListener", "setLongClickable"))
                }
            }

            // Applied to the real factory: the hook sits between the bind and the return, and the
            // jump TikTok takes for a tab without a view still lands on the return, past it.
            val patched = MutableMethod(site.method)
            fun labels(index: Int) =
                (patched.implementation!!.instructions[index] as BuilderInstruction).location.labels.size
            val jumpsToReturn = labels(site.index)
            assertTrue("$version: nothing jumps to the factory's return", jumpsToReturn > 0)
            patched.handTopTabOver(site)
            val code = patched.implementation!!.instructions.toList()
            assertHandsOver(code, site.index, site, version)
            assertEquals("$version: the hook took a jump", 0, labels(site.index))
            assertEquals("$version: the return lost a jump", jumpsToReturn, labels(site.index + 3))
            assertEquals("$version: the return", Opcode.RETURN_OBJECT, code[site.index + 3].opcode)
        }
    }

    private fun assertHandsOver(code: List<Instruction>, at: Int, site: TopTabBindSite, where: String) {
        val read = code[at] as FiveRegisterInstruction
        assertEquals("$where: the tag read", Opcode.INVOKE_INTERFACE, read.opcode)
        assertEquals("$where: the tag read's model", site.tabModel, read.registerC)
        assertEquals("$where: the tag getter", site.tagRead, (read as ReferenceInstruction).reference)
        assertEquals("$where: the tag's register", site.tag, (code[at + 1] as OneRegisterInstruction).registerA)
        val hook = code[at + 2] as FiveRegisterInstruction
        assertEquals("$where: the hook", Opcode.INVOKE_STATIC, hook.opcode)
        assertEquals("$where: the hook target", TOP_TAB_HOOK, (hook as ReferenceInstruction).reference.toString())
        assertEquals("$where: the hook's arguments", listOf(site.tabView, site.tag), listOf(hook.registerC, hook.registerD))
        assertEquals("$where: the hook's argument count", 2, hook.registerCount)
    }

    private fun assertNoSite(classOf: (String) -> ClassDef?, why: String) {
        val lookup = findTopTabBind(classOf)
        assertNull("found a site", lookup.site)
        assertTrue("${lookup.miss}", lookup.miss!!.contains(why))
    }

    private fun Instruction.makes(type: String) =
        opcode == Opcode.NEW_INSTANCE && (this as ReferenceInstruction).reference.toString() == type

    /**
     * A tab factory shaped like 47.1.4's: the tag read from the model parameter, the view made,
     * a jump to the return when there's no view, the bind, then the return of the tab.
     */
    private fun factory(
        name: String,
        registers: Int = 7,
        modelRegister: Int = registers - 1,
        afterBind: List<Instruction> = emptyList(),
    ): Method {
        val returnAt = 11 + afterBind.sumOf { it.codeUnits }
        val read: Instruction = if (modelRegister <= 15) {
            ImmutableInstruction35c(Opcode.INVOKE_INTERFACE, 1, modelRegister, 0, 0, 0, 0, tagRead)
        } else {
            ImmutableInstruction3rc(Opcode.INVOKE_INTERFACE_RANGE, modelRegister, 1, tagRead)
        }
        val code = listOf(
            read,
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, ImmutableTypeReference(TAB_VIEW)),
            ImmutableInstruction21t(Opcode.IF_EQZ, 1, returnAt - 6),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 1, 1, 0, 0, 0, 0, bind),
        ) + afterBind + ImmutableInstruction11x(Opcode.RETURN_OBJECT, 3)
        return ImmutableMethod(
            STRIP, name, listOf(VIEW, TAB_MODEL).map { ImmutableMethodParameter(it, null, null) }, TAB,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(registers, code, null, null),
        )
    }

    private fun constructor(owner: String, parameters: List<String>) = ImmutableMethod(
        owner, "<init>", parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
        AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, null, null,
        ImmutableMethodImplementation(parameters.size + 1, emptyList(), null, null),
    )

    private fun classDef(type: String, superclass: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, superclass, null, null, null, null, methods)

    /** TikTok's three classes: the ability that takes the strip, the strip and its tab view. */
    private fun classes(
        vararg factories: Method,
        viewConstructor: List<String> = listOf(STRIP, "Landroid/content/Context;"),
    ): (String) -> ClassDef? {
        val all = listOf(
            classDef(TOP_TAB_LAYOUT_ABILITY, "Ljava/lang/Object;",
                listOf(constructor(TOP_TAB_LAYOUT_ABILITY, listOf(STRIP, "Landroid/widget/FrameLayout;")))),
            classDef(STRIP, "Landroid/widget/HorizontalScrollView;", factories.toList()),
            classDef(TAB_VIEW, "Landroid/widget/LinearLayout;", listOf(constructor(TAB_VIEW, viewConstructor))),
        ).associateBy { it.type }
        return { all[it] }
    }

    private fun load(apk: java.io.File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }
}
