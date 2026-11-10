/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.foldable

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction23x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

private const val FEED_ACTIVITY = "Lcom/ss/android/ugc/aweme/main/MainActivity;"
private const val EXTENSION = "Lapp/morphe/extension/tiktok/foldable/FoldableSplitView;"

/** The feed activity's configuration change reaches the split view first (issue #26). */
class FoldableSplitViewConfigurationTest {
    @Test
    fun `each declared build's feed activity hands its configuration change to the split view`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = HashMap<String, ClassDef>()
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
            }

            val method = configurationChangeOf(FEED_ACTIVITY) { classes[it] }
            assertNotNull("nothing between the feed activity and Activity takes configuration changes", method)
            val chain = generateSequence(FEED_ACTIVITY) { classes[it]?.superclass }.toList()
            assertTrue("${method!!.definingClass} is not above the feed activity", method.definingClass in chain)

            val mutable = MutableMethod(method)
            val body = mutable.implementation!!.instructions.map { it.opcode }
            mutable.handConfigurationChangesToSplitView()
            assertHandsOverFirst(mutable, body)
        }
    }

    @Test
    fun `the walk takes the nearest override that runs and skips what cannot take the change`() {
        val classes = mapOf(
            classOf("LX/Feed;", "LX/Static;"),
            classOf("LX/Static;", "LX/Abstract;", change(static = true)),
            classOf("LX/Abstract;", "LX/Base;", change(implemented = false)),
            classOf("LX/Base;", "Landroid/app/Activity;", change()),
        )
        val found = configurationChangeOf("LX/Feed;") { classes[it] }
        assertEquals("LX/Base;", found?.definingClass)

        val mutable = MutableMethod(found!!)
        val body = mutable.implementation!!.instructions.map { it.opcode }
        mutable.handConfigurationChangesToSplitView()
        assertHandsOverFirst(mutable, body)
    }

    @Test
    fun `a chain that leaves the app before any override finds nothing`() {
        val classes = mapOf(classOf("LX/Feed;", "Landroid/app/Activity;"))
        assertNull(configurationChangeOf("LX/Feed;") { classes[it] })
        // A damaged chain that loops ends too.
        val looped = mapOf(classOf("LX/A;", "LX/B;"), classOf("LX/B;", "LX/A;"))
        assertNull(configurationChangeOf("LX/A;") { looped[it] })
    }

    /** TikTok's server sends 1 there, and the feed then registers its container only on a tablet. */
    @Test
    fun `each declared build's feed hands its server value to the split view before registering its container`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val found = mutableListOf<Method>()
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                    classDef.methods.filterTo(found) { it.matchesFeedSplitContainer() }
                }
            }
            assertEquals("methods that look like the feed's split container builder", 1, found.size)

            val mutable = MutableMethod(found.single())
            val read = mutable.splitContainerInitRead()
            val value = mutable.implementation!!.instructions.elementAt(read).let { (it as OneRegisterInstruction).registerA }
            val body = mutable.implementation!!.instructions.map { it.opcode }
            mutable.registerSplitContainerOnWideWindows(read)

            val instructions = mutable.implementation!!.instructions.toList()
            assertEquals("the hook changed more than the read",
                body.take(read + 1) + listOf(Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT_WIDE) + body.drop(read + 1),
                instructions.map { it.opcode })
            val call = instructions[read + 1] as RegisterRangeInstruction
            assertEquals("the call does not take the value just read", value, call.startRegister)
            assertEquals(2, call.registerCount)
            val target = call.getReference<MethodReference>()!!
            assertEquals(EXTENSION, target.definingClass)
            assertEquals("splitContainerInit", target.name)
            assertEquals(listOf("J"), target.parameterTypes.map(CharSequence::toString))
            assertEquals("J", target.returnType)
            assertEquals("the answer does not replace the value TikTok compares", value,
                (instructions[read + 2] as OneRegisterInstruction).registerA)
        }
    }

    @Test
    fun `anything but one read of the server value ahead of the registration fails the patch`() {
        val good = feedBuilder(read(6) + minusOne(1) + compare(6, 1) + registration())
        assertEquals(0, good.splitContainerInitRead())

        val shapes = mapOf(
            "no read" to feedBuilder(registration()),
            "two reads" to feedBuilder(read(6) + minusOne(1) + compare(6, 1) + read(4) + minusOne(2) + compare(4, 2) + registration()),
            "a compare of other registers" to feedBuilder(read(6) + minusOne(1) + compare(4, 1) + registration()),
            "a compare with another value" to feedBuilder(read(6) + listOf(ImmutableInstruction21s(Opcode.CONST_WIDE_16, 1, 0)) + compare(6, 1) + registration()),
            "the read after the registration" to feedBuilder(registration() + read(6) + minusOne(1) + compare(6, 1)),
        )
        shapes.forEach { (shape, method) ->
            try {
                method.splitContainerInitRead()
                fail("$shape was taken for the server value's read")
            } catch (expected: PatchException) {
            }
        }
    }

    @Test
    fun `the patch hands the configuration change over`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/misc/foldable/FoldableSplitViewPatch.kt").readText()
        val execute = source.indexOf("execute {", source.indexOf("val foldableSplitViewPatch"))
        assertTrue("the patch has no execute block", execute >= 0)
        assertTrue("the patch no longer hands the feed activity's configuration change over",
            source.indexOf(".handConfigurationChangesToSplitView()", execute) > execute)
        assertTrue("the configuration change is no longer found before anything is written",
            source.indexOf("configurationChangeOf(FEED_ACTIVITY)", execute) in execute until
                source.indexOf(".addInstructions(", execute))
        assertTrue("the feed's server value is no longer found before anything is written",
            source.indexOf(".splitContainerInitRead()", execute) in execute until
                source.indexOf(".addInstructions(", execute))
        assertTrue("the patch no longer hands the feed's server value over",
            source.indexOf(".registerSplitContainerOnWideWindows(", execute) > execute)
    }

    /** What FeedSplitContainerFingerprint matches on, read straight off the dex. */
    private fun Method.matchesFeedSplitContainer(): Boolean =
        returnType == "Landroid/view/View;" &&
            parameterTypes.map(CharSequence::toString) ==
            listOf("Lcom/bytedance/ies/uikit/base/AbsFragment;", "Landroid/view/LayoutInflater;") &&
            implementation?.instructions?.any { it.getReference<StringReference>()?.string == "HOME_" } == true &&
            registersSplitContainer()

    private fun read(register: Int) = listOf(ImmutableInstruction21c(Opcode.SGET_WIDE, register,
        ImmutableFieldReference("LX/Server;", "LIZ", "J")))
    private fun minusOne(register: Int) = listOf(ImmutableInstruction21s(Opcode.CONST_WIDE_16, register, -1))
    private fun compare(value: Int, literal: Int) = listOf(ImmutableInstruction23x(Opcode.CMP_LONG, 0, value, literal))
    private fun registration() = listOf(ImmutableInstruction35c(Opcode.INVOKE_STATIC, 4, 0, 9, 5, 4, 0,
        ImmutableMethodReference(SPLIT_CONTAINER_MANAGER, "LIZ",
            listOf("Ljava/lang/String;", "Landroidx/fragment/app/Fragment;", "Landroid/view/View;", "Z"), "LX/Container;")))

    private fun feedBuilder(code: List<Instruction>): Method = ImmutableMethod(
        "LX/Feed;", "LIZ",
        listOf(ImmutableMethodParameter("Lcom/bytedance/ies/uikit/base/AbsFragment;", null, null),
            ImmutableMethodParameter("Landroid/view/LayoutInflater;", null, null)),
        "Landroid/view/View;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
        ImmutableMethodImplementation(11, code + ImmutableInstruction11x(Opcode.RETURN_OBJECT, 5), null, null),
    )

    /** The call is a prefix: TikTok's own handler, `super` call included, still runs after it. */
    private fun assertHandsOverFirst(method: MutableMethod, originalBody: List<Opcode>) {
        val instructions = method.implementation!!.instructions.toList()
        assertEquals("the hook changed TikTok's own handler", originalBody, instructions.drop(1).map { it.opcode })
        val first = instructions.first()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, first.opcode)
        val range = first as RegisterRangeInstruction
        assertEquals("the call does not take this and the configuration", 2, range.registerCount)
        assertEquals("the call does not start at p0", method.implementation!!.registerCount - 2, range.startRegister)
        val target = first.getReference<MethodReference>()!!
        assertEquals(EXTENSION, target.definingClass)
        assertEquals("onConfigurationChanged", target.name)
        assertEquals(listOf("Landroid/app/Activity;", "Landroid/content/res/Configuration;"),
            target.parameterTypes.map(CharSequence::toString))
    }

    private fun classOf(type: String, superclass: String, vararg methods: (String) -> Method): Pair<String, ClassDef> =
        type to ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, superclass, null, null, null, emptyList(),
            methods.map { it(type) },
        )

    private fun change(static: Boolean = false, implemented: Boolean = true): (String) -> Method = { owner ->
        ImmutableMethod(
            owner, "onConfigurationChanged",
            listOf(ImmutableMethodParameter("Landroid/content/res/Configuration;", null, null)), "V",
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0) or
                (if (implemented) 0 else AccessFlags.ABSTRACT.value),
            null, null,
            if (implemented) {
                ImmutableMethodImplementation(if (static) 1 else 2, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null)
            } else {
                null
            },
        )
    }
}
