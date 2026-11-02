/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.Fixtures
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Compose half of AMOLED dark theme (#152), held to each declared build: the palette labels
 * its UIPageGrouped2 field in toString, exactly one palette builder starts that field at TikTok's
 * dark gray, every card color that builder writes is a dark gray packed by one static `(J)J`
 * (a shift left by 32, the Compose Color packing), and after the repaint each of those writes
 * stores the chosen color through that same conversion, with TikTok's own instructions in order.
 * The light palettes and the high contrast dark one are other builders and aren't touched.
 */
class AmoledComposeCardsTest {
    @Test
    fun `each declared build has one dark palette builder whose cards the patch repaints`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = mutableListOf<ClassDef>()
            for (entry in container.dexEntryNames) classes += container.getEntry(entry)!!.dexFile.classes

            val field = classes.composeCardField()
            assertNotNull("$version: no palette labels $PAGE_GROUPED_2_LABEL", field)
            field!!

            val builders = classes.flatMap { classDef ->
                classDef.methods.filter {
                    it.parameterTypes.isEmpty() && it.returnType == field.definingClass && it.cardWrites(field) != null
                }
            }
            assertTrue("$version: the light and dark palettes are separate builders, found ${builders.size}",
                builders.size >= 2)
            val dark = builders.filter { it.isDarkPaletteBuilder(field) }
            assertEquals("$version: dark palette builders: ${dark.map { "${it.definingClass}->${it.name}" }}", 1, dark.size)

            val builder = dark.single()
            val writes = builder.cardWrites(field)!!
            writes.forEach { write ->
                val literal = builder.cardLiteral(write)
                assertNotNull("$version: a card color at ${write.index} isn't a literal", literal)
                assertTrue("$version: ${literal!!.toString(16)} isn't a dark opaque gray",
                    isDarkOpaqueLiteral("#" + literal.toString(16)))
            }
            assertEquals("$version: TikTok's own dark card", DARK_CARD_GRAY, builder.cardLiteral(writes.first()))
            builders.filter { it !== builder }.forEach { other ->
                assertTrue("$version: ${other.name} starts its cards at TikTok's dark gray too",
                    other.cardLiteral(other.cardWrites(field)!!.first()) != DARK_CARD_GRAY)
            }
            assertShiftsBy32(version, classes, writes.first().conversion)

            val before = builder.implementation!!.instructions.toList()
            val painted = MutableMethod(builder)
            painted.paintCards(field, composeArgb("#000000"))
            val after = painted.implementation!!.instructions.toList()
            assertEquals("$version: three instructions per card color", before.size + 3 * writes.size, after.size)
            val added = mutableSetOf<Int>()
            after.forEachIndexed { index, instruction ->
                val target = instruction.getReference<FieldReference>()
                if (instruction.opcode != Opcode.IPUT_WIDE || target?.name != field.name ||
                    target.definingClass != field.definingClass
                ) {
                    return@forEachIndexed
                }
                val register = (instruction as OneRegisterInstruction).registerA
                val literal = after[index - 3]
                assertTrue("$version: the color isn't set right before the write", literal is WideLiteralInstruction)
                assertEquals(register, (literal as OneRegisterInstruction).registerA)
                assertEquals(0xff000000L, (literal as WideLiteralInstruction).wideLiteral)
                val call = after[index - 2]
                assertEquals(Opcode.INVOKE_STATIC_RANGE, call.opcode)
                assertEquals(register, (call as RegisterRangeInstruction).startRegister)
                assertEquals(2, call.registerCount)
                val conversion = call.getReference<MethodReference>()!!
                assertEquals(writes.first().conversion.definingClass, conversion.definingClass)
                assertEquals(writes.first().conversion.name, conversion.name)
                assertEquals(Opcode.MOVE_RESULT_WIDE, after[index - 1].opcode)
                assertEquals(register, (after[index - 1] as OneRegisterInstruction).registerA)
                added += index - 3
                added += index - 2
                added += index - 1
            }
            assertEquals("$version: every card color was repainted", 3 * writes.size, added.size)
            assertEquals(
                "$version: TikTok's own instructions keep their order",
                before.map { it.opcode },
                after.filterIndexed { index, _ -> index !in added }.map { it.opcode },
            )
        }
    }

    @Test
    fun `the chosen color goes in opaque`() {
        assertEquals(0xff000000L, composeArgb("#000000"))
        assertEquals(0xff181825L, composeArgb("#181825"))
        assertEquals(0xff121212L, composeArgb("#FF121212"))
        assertEquals("#000000", amoledColor("#000000"))
    }

    /** The conversion is Compose's Color(Long): the ARGB value shifted into the top 32 bits. */
    private fun assertShiftsBy32(version: String, classes: List<ClassDef>, conversion: MethodReference) {
        val method: Method? = classes.firstOrNull { it.type == conversion.definingClass }
            ?.methods?.firstOrNull { it.name == conversion.name && it.parameterTypes.map(Any::toString) == listOf("J") }
        assertNotNull("$version: ${conversion.definingClass}->${conversion.name} is missing", method)
        val instructions = method!!.implementation!!.instructions.toList()
        assertTrue("$version: the conversion doesn't shift a long",
            instructions.any { it.opcode == Opcode.SHL_LONG || it.opcode == Opcode.SHL_LONG_2ADDR })
        assertTrue("$version: the conversion doesn't shift by 32",
            instructions.any { it is NarrowLiteralInstruction && it.narrowLiteral == 32 })
    }
}
