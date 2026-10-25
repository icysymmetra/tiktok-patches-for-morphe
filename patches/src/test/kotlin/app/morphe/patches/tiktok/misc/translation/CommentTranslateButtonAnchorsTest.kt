/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What the translate button hooks (#85), held to each declared build: one class whose lazy value
 * reads [AUTO_TRANSLATION_KEY], with one enabled read and one answer to whether the header shows
 * the button on it, and one read and one write of the toggle's key on one class. The write tells
 * the extension what it is about to store before TikTok's first instruction, and every answer of
 * the read goes through the extension, with TikTok's own instructions left in their order.
 *
 * <p>That both gates ask the switch first and keep TikTok's code behind it is read back by
 * SwitchedReturnFixturesTest, with the real hook put on each build.
 */
class CommentTranslateButtonAnchorsTest {
    @Test
    fun `each declared build has the button's two gates and one toggle read and write`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val byType = HashMap<String, ClassDef>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    byType.putIfAbsent(classDef.type, classDef)
                }
            }
            val targets = findCommentTranslateButtonTargets(byType::get) { visit -> byType.values.forEach(visit) }

            assertEquals("$version: the two gates are on one class",
                targets.enabledRead.definingClass, targets.styleAnswer.definingClass)
            assertEquals("$version: the toggle's read and write are on one class",
                targets.stateRead.definingClass, targets.stateWrite.definingClass)
            assertNotEquals("$version: the gate and the toggle are different classes",
                targets.enabledRead.definingClass, targets.stateRead.definingClass)

            assertWriteReported(version, targets.stateWrite)
            assertReadPassedThrough(version, targets.stateRead)
        }
    }

    private fun assertWriteReported(version: String, write: Method) {
        val before = write.implementation!!.instructions.toList()
        val hooked = MutableMethod(write)
        hooked.reportToggleWrite()
        val after = hooked.implementation!!.instructions.toList()
        assertEquals("$version: one instruction added to the write", before.size + 1, after.size)

        val call = after[0]
        assertEquals("$version: the write is reported first", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        call as RegisterRangeInstruction
        val firstParameter = hooked.implementation!!.registerCount - 2
        assertEquals("$version: hands over the value and the source as TikTok got them",
            firstParameter, call.startRegister)
        assertEquals(2, call.registerCount)
        val target = call.getReference<MethodReference>()!!
        assertEquals(COMMENT_TRANSLATE_BUTTON, target.definingClass)
        assertEquals("onToggleWritten", target.name)
        assertEquals("V", target.returnType)
        assertEquals(listOf("Ljava/lang/Boolean;", "Ljava/lang/Object;"), target.parameterTypes.map { it.toString() })
        assertEquals("$version: TikTok's own write follows untouched",
            before.map { it.opcode }, after.drop(1).map { it.opcode })
    }

    private fun assertReadPassedThrough(version: String, read: Method) {
        val before = read.implementation!!.instructions.toList()
        val returns = before.count { it.opcode == Opcode.RETURN }
        assertTrue("$version: the toggle read returns nothing", returns > 0)
        val hooked = MutableMethod(read)
        hooked.sendToggleStateThroughExtension()
        val after = hooked.implementation!!.instructions.toList()
        assertEquals("$version: two instructions per return", before.size + 2 * returns, after.size)
        val added = mutableSetOf<Int>()
        after.forEachIndexed { index, instruction ->
            if (instruction.opcode != Opcode.RETURN) return@forEachIndexed
            val register = (instruction as OneRegisterInstruction).registerA
            val call = after[index - 2]
            assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
            assertEquals("$version: hands over the answer it returns",
                register, (call as RegisterRangeInstruction).startRegister)
            assertEquals(1, call.registerCount)
            val target = call.getReference<MethodReference>()!!
            assertEquals(COMMENT_TRANSLATE_BUTTON, target.definingClass)
            assertEquals("toggleOn", target.name)
            assertEquals("Z", target.returnType)
            assertEquals(listOf("Z"), target.parameterTypes.map { it.toString() })
            assertEquals(Opcode.MOVE_RESULT, after[index - 1].opcode)
            assertEquals("$version: the answer is moved back where it's returned from",
                register, (after[index - 1] as OneRegisterInstruction).registerA)
            added += index - 2
            added += index - 1
        }
        assertEquals(
            "$version: TikTok's own instructions keep their order",
            before.map { it.opcode },
            after.filterIndexed { index, _ -> index !in added }.map { it.opcode },
        )
    }

    @Test
    fun `the enabled read takes nothing and unwraps a Boolean from the lazy value`() {
        assertTrue(enabledRead().isAutoTranslationEnabledRead())
        assertFalse("unwraps a number", enabledRead(unwrap = NUMBER_UNWRAP).isAutoTranslationEnabledRead())
        assertFalse("reads no lazy value", enabledRead(readsLazy = false).isAutoTranslationEnabledRead())
        assertFalse("not static", enabledRead(static = false).isAutoTranslationEnabledRead())
        assertFalse("takes an argument", enabledRead(parameters = listOf("Z")).isAutoTranslationEnabledRead())
    }

    @Test
    fun `the style answer takes the author flag and asks the enabled read on its own class`() {
        val read = enabledRead()
        assertTrue(styleAnswer(read).isButtonStyleAnswer(read))
        assertFalse("asks some other gate", styleAnswer(read, asks = "LIZJ").isButtonStyleAnswer(read))
        assertFalse("asks a read on another class",
            styleAnswer(read, askOwner = "LX/0Other;").isButtonStyleAnswer(read))
        assertFalse("takes no flag", styleAnswer(read, parameters = emptyList()).isButtonStyleAnswer(read))
        assertFalse("takes something else", styleAnswer(read, parameters = listOf("I")).isButtonStyleAnswer(read))
    }

    @Test
    fun `only a static read and a static write of the key are the toggle`() {
        assertTrue(toggleRead().isToggleStateRead())
        assertFalse("another key", toggleRead(key = "key_comment_translation_on_by_click").isToggleStateRead())
        assertFalse("an instance method", toggleRead(static = false).isToggleStateRead())

        assertTrue(toggleWrite().isToggleStateWrite())
        assertFalse("another key", toggleWrite(key = "hide_caption").isToggleStateWrite())
        assertFalse("an instance method, like TikTok's change listener", toggleWrite(static = false).isToggleStateWrite())
        assertFalse("takes the value only",
            toggleWrite(parameters = listOf("Ljava/lang/Boolean;")).isToggleStateWrite())
        assertFalse("takes a primitive value",
            toggleWrite(parameters = listOf("Z", "Ljava/lang/Object;")).isToggleStateWrite())
        assertFalse("read and write shapes don't cross", toggleRead().isToggleStateWrite())
        assertFalse(toggleWrite().isToggleStateRead())
    }

    @Test
    fun `a second toggle read or a write on another class is refused`() {
        val gateType = "LX/0Gate;"
        val read = enabledRead(owner = gateType)
        val gate = classDef(gateType, gateInitializer(gateType), read, styleAnswer(read))
        fun search(vararg classes: ClassDef) {
            val byType = (listOf(gate) + classes).associateBy { it.type }
            findCommentTranslateButtonTargets(byType::get) { visit -> byType.values.forEach(visit) }
        }

        search(classDef(TOGGLE_OWNER, toggleRead(), toggleWrite()))
        refused("two reads") {
            search(classDef(TOGGLE_OWNER, toggleRead(), toggleRead(name = "LJI"), toggleWrite()))
        }
        refused("no write") { search(classDef(TOGGLE_OWNER, toggleRead())) }
        refused("the write elsewhere") {
            search(classDef(TOGGLE_OWNER, toggleRead()), classDef("LX/0Else;", toggleWrite(owner = "LX/0Else;")))
        }
        refused("two style answers") {
            val doubled = classDef(gateType, gateInitializer(gateType), read, styleAnswer(read),
                styleAnswer(read, name = "LIZLLL"))
            val byType = listOf(doubled, classDef(TOGGLE_OWNER, toggleRead(), toggleWrite())).associateBy { it.type }
            findCommentTranslateButtonTargets(byType::get) { visit -> byType.values.forEach(visit) }
        }
    }

    private fun refused(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what was accepted")
        } catch (_: PatchException) {
        }
    }

    private fun gateInitializer(owner: String) = method("<clinit>", emptyList(), "V", 1, owner = owner).apply {
        addInstructionsWithLabels(
            0,
            """
                const-string v0, "$AUTO_TRANSLATION_KEY"
                return-void
            """,
        )
    }

    private fun enabledRead(
        owner: String = GATE_OWNER,
        unwrap: String = BOOLEAN_UNWRAP,
        readsLazy: Boolean = true,
        static: Boolean = true,
        parameters: List<String> = emptyList(),
    ) = method("LIZIZ", parameters, "Z", 2, owner = owner, static = static).apply {
        val value = if (readsLazy) {
            "invoke-interface { v0 }, LX/01xP;->getValue()Ljava/lang/Object;\nmove-result-object v0"
        } else {
            "const/4 v0, 0x0"
        }
        addInstructionsWithLabels(
            0,
            """
                sget-object v0, $owner->LIZ:LX/01xP;
                $value
                $unwrap
                return v0
            """,
        )
    }

    private fun styleAnswer(
        read: Method,
        asks: String = read.name,
        askOwner: String = read.definingClass,
        parameters: List<String> = listOf("Z"),
        name: String = "LIZ",
    ) = method(name, parameters, "Z", 3, owner = read.definingClass).apply {
        addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $askOwner->$asks()Z
                move-result v0
                return v0
            """,
        )
    }

    private fun toggleRead(
        key: String = TOGGLE_STATE_KEY,
        static: Boolean = true,
        name: String = "LIZLLL",
    ) = method(name, emptyList(), "Z", 2, owner = TOGGLE_OWNER, static = static).apply {
        addInstructionsWithLabels(
            0,
            """
                const-string v0, "$key"
                const/4 v1, 0x0
                return v1
            """,
        )
    }

    private fun toggleWrite(
        key: String = TOGGLE_STATE_KEY,
        static: Boolean = true,
        parameters: List<String> = listOf("Ljava/lang/Boolean;", "LX/0lRs;"),
        owner: String = TOGGLE_OWNER,
    ) = method("LJIIL", parameters, "V", 4, owner = owner, static = static).apply {
        addInstructionsWithLabels(
            0,
            """
                const-string v0, "$key"
                return-void
            """,
        )
    }

    private fun method(
        name: String,
        parameters: List<String>,
        returnType: String,
        registers: Int,
        owner: String,
        static: Boolean = true,
    ) = MutableMethod(
        ImmutableMethod(
            owner,
            name,
            parameters.map { ImmutableMethodParameter(it, null, null) },
            returnType,
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0),
            null,
            null,
            ImmutableMethodImplementation(registers, emptyList(), null, null),
        ),
    )

    private fun classDef(type: String, vararg methods: Method): ClassDef = ImmutableClassDef(
        type,
        AccessFlags.PUBLIC.value,
        "Ljava/lang/Object;",
        null,
        null,
        null,
        null,
        methods.toList(),
    )

    private companion object {
        const val GATE_OWNER = "LX/0Gate;"
        const val TOGGLE_OWNER = "LX/0Toggle;"
        const val BOOLEAN_UNWRAP =
            "check-cast v0, Ljava/lang/Boolean;\ninvoke-virtual { v0 }, Ljava/lang/Boolean;->booleanValue()Z\nmove-result v0"
        const val NUMBER_UNWRAP =
            "check-cast v0, Ljava/lang/Number;\ninvoke-virtual { v0 }, Ljava/lang/Number;->intValue()I\nmove-result v0"
    }
}
