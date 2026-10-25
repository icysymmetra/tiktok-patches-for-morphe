package app.morphe.patches.tiktok.interaction.live

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
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * LIVE controls shows the exact viewer count by wrapping the widget's four calls to TikTok's
 * "K+/M+/B+" abbreviator. Each declared build has the widget under its real name with exactly
 * those four calls, each in the shape the hook writes around: `int-to-long`, the formatter on the
 * pair, `move-result-object`, then `Locale.ENGLISH` and `toUpperCase` on the result. The hook
 * adds a call reading the pair before the formatter and one reading and rewriting the result
 * register after it, so it needs no register of its own, and both calls use the range form so any
 * register number works. This test holds that the same four shapes exist on all three builds, and
 * that a programmed LIVE's three count widgets each format once through the same helper.
 */
class LiveViewerCountAnchorsTest {
    @Test
    fun `each declared build has the widget with four hookable viewer count calls`() {
        val shapes = mutableMapOf<String, List<Pair<Int, Int>>>()
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val widgets = mutableListOf<ClassDef>()
            for (entry in container.dexEntryNames) {
                container.getEntry(entry)!!.dexFile.classes.filterTo(widgets) { it.type == ONLINE_AUDIENCE_RANK_WIDGET }
            }
            assertEquals("${apk.name}: widget classes", 1, widgets.size)
            val widget = widgets.single()

            val sites = viewerCountSites(widget)
            assertEquals("${apk.name}: viewer count calls", VIEWER_COUNT_SITES, sites.size)

            val formatters = sites.map { site ->
                val call = site.method.implementation!!.instructions.toList()[site.formatIndex] as ReferenceInstruction
                (call.reference as MethodReference).let { "${it.definingClass}->${it.name}" }
            }.toSet()
            assertEquals("${apk.name}: one formatter behind all four: $formatters", 1, formatters.size)

            sites.forEach { site ->
                val instructions = site.method.implementation!!.instructions.toList()
                // Register safety: the inserted calls name the pair and the result register, with
                // the range form so any register number works, and nothing else.
                assertTrue(
                    "${apk.name}: ${site.method.name} frame holds v${site.pairLow + 1} and v${site.result}",
                    site.method.implementation!!.registerCount > maxOf(site.pairLow + 1, site.result),
                )
                // The original instructions keep owning the registers: int-to-long defines the
                // pair right before the formatter, move-result-object defines the result right
                // after it, and the added calls sit between those and nothing else.
                assertEquals(Opcode.INT_TO_LONG, instructions[site.formatIndex - 1].opcode)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, instructions[site.formatIndex + 1].opcode)
                assertEquals(Opcode.SGET_OBJECT, instructions[site.formatIndex + 2].opcode)
            }
            // Every static (J)String call to a non-JDK class in the widget is one of the four,
            // so no formatted count is left showing TikTok's rounded text.
            val all = widget.methods.flatMap { it.implementation?.instructions?.toList().orEmpty() }.count {
                val ref = (it as? ReferenceInstruction)?.reference as? MethodReference
                it.opcode == Opcode.INVOKE_STATIC && ref != null && !ref.definingClass.startsWith("Ljava/") &&
                    ref.returnType == "Ljava/lang/String;" && ref.parameterTypes.map(CharSequence::toString) == listOf("J")
            }
            assertEquals("${apk.name}: static (J)String calls in the widget", VIEWER_COUNT_SITES, all)

            shapes[apk.name] = sites.map { it.pairLow to it.result }.sortedBy { it.first * 100 + it.second }

            // A programmed LIVE's three count widgets each format once through the same helper,
            // then wrap the text for its direction instead of upper-casing it.
            val formatter = formatters.single()
            PROGRAMMED_AUDIENCE_WIDGETS.forEach { type ->
                val programmed = mutableListOf<ClassDef>()
                for (entry in container.dexEntryNames) {
                    container.getEntry(entry)!!.dexFile.classes.filterTo(programmed) { it.type == type }
                }
                assertEquals("${apk.name}: $type classes", 1, programmed.size)
                val found = viewerCountSites(programmed.single(), formatter)
                assertEquals("${apk.name}: viewer count calls in $type", 1, found.size)
                val site = found.single()
                val instructions = site.method.implementation!!.instructions.toList()
                assertEquals(Opcode.INT_TO_LONG, instructions[site.formatIndex - 1].opcode)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, instructions[site.formatIndex + 1].opcode)
                assertEquals(Opcode.INVOKE_VIRTUAL, instructions[site.formatIndex + 2].opcode)
                assertTrue(site.method.implementation!!.registerCount > maxOf(site.pairLow + 1, site.result))
            }
        }
        assertEquals("the same register shapes on every declared build: $shapes", 1, shapes.values.toSet().size)
    }

    @Test
    fun `both shapes are hooked in place and anything else stops the patch`() {
        val upper = method(
            ImmutableInstruction12x(Opcode.INT_TO_LONG, 0, 4),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 1, 0, 0, 0, formatterRef),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
            ImmutableInstruction21c(Opcode.SGET_OBJECT, 3, ImmutableFieldReference("Ljava/util/Locale;", "ENGLISH", "Ljava/util/Locale;")),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 2, 3, 0, 0, 0,
                ImmutableMethodReference("Ljava/lang/String;", "toUpperCase", listOf("Ljava/util/Locale;"), "Ljava/lang/String;")),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        val wrapped = method(
            ImmutableInstruction12x(Opcode.INT_TO_LONG, 0, 4),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 1, 0, 0, 0, formatterRef),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 3, 0, 0, 0, 0,
                ImmutableMethodReference("LX/Bidi;", "wrap", listOf("Ljava/lang/String;"), "Ljava/lang/String;")),
            ImmutableInstruction10x(Opcode.RETURN_VOID),
        )
        listOf(upper to 2, wrapped to 0).forEach { (body, result) ->
            val site = viewerCountSites(classWith(body)).single()
            assertEquals(1, site.formatIndex)
            assertEquals(0, site.pairLow)
            assertEquals(result, site.result)
            assertEquals("LX/Fmt;->abbreviate", site.formatter)

            val mutable = MutableMethod(body)
            mutable.hookViewerCount(site)
            val hooked = mutable.implementation!!.instructions.toList()
            // Count before the formatter, text swap right after the move-result, TikTok's own code kept.
            assertEquals(Opcode.INVOKE_STATIC_RANGE, hooked[1].opcode)
            assertEquals("noteViewerCount", hooked[1].getReference<MethodReference>()!!.name)
            assertEquals(Opcode.INVOKE_STATIC, hooked[2].opcode)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, hooked[3].opcode)
            assertEquals(Opcode.INVOKE_STATIC_RANGE, hooked[4].opcode)
            assertEquals("viewerCountText", hooked[4].getReference<MethodReference>()!!.name)
            assertEquals(result, (hooked[4] as RegisterRangeInstruction).startRegister)
            assertEquals(Opcode.MOVE_RESULT_OBJECT, hooked[5].opcode)
            assertEquals(result, (hooked[5] as OneRegisterInstruction).registerA)
        }

        // A formatter call followed by anything else could show TikTok's text unnoticed.
        val stray = method(
            ImmutableInstruction12x(Opcode.INT_TO_LONG, 0, 4),
            ImmutableInstruction35c(Opcode.INVOKE_STATIC, 2, 0, 1, 0, 0, 0, formatterRef),
            ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
        )
        assertThrows(PatchException::class.java) { viewerCountSites(classWith(stray)) }
        // With a formatter named, other (J)String helpers are left alone.
        assertTrue(viewerCountSites(classWith(stray), "LX/Other;->abbreviate").isEmpty())
    }

    private val formatterRef = ImmutableMethodReference("LX/Fmt;", "abbreviate", listOf("J"), "Ljava/lang/String;")

    private fun method(vararg instructions: Instruction): Method = ImmutableMethod(
        "LX/Widget;", "W", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
        ImmutableMethodImplementation(6, instructions.toList(), null, null),
    )

    private fun classWith(method: Method): ClassDef = ImmutableClassDef(
        "LX/Widget;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), listOf(method),
    )
}
