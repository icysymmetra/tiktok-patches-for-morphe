package app.morphe.patches.tiktok.interaction.gesture

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Swipe-left controls rests on, held to each TikTok build the bundle declares.
 *
 * TikTok's feed-to-profile pager has one base class, the only one that declares
 * setPagingMainValve. It declares a private getIsPageEnabled and the three touch methods that
 * ask it (dispatch, intercept, onTouchEvent), and every return of the check hands back v0 with
 * no branch landing on it, so a call placed before each return hears TikTok's own answer on
 * every path. The home pager is a two-class chain below it that reaches the base's methods
 * through super calls and overrides none of the four; the main activity's layout alone creates
 * its concrete class. A video opened from a profile, search or a link sits in a bare instance
 * of the base (inflated from XML), which a detail page casts to the base, so the hooks reach
 * that pager too.
 */
class SwipeLeftAnchorsTest {
    @Test
    fun `each declared build pages feed to profile through one base, asked in one place`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
            }

            val valves = classes.values.filter { classDef -> classDef.methods.any { it.name == "setPagingMainValve" } }
            assertEquals("classes declaring setPagingMainValve", 1, valves.size)
            val base = valves.single()
            val gate = base.methods.single { it.name == "getIsPageEnabled" && it.parameterTypes.isEmpty() && it.returnType == "Z" }
            TOUCH_METHODS.forEach { name ->
                assertTrue("$name asks getIsPageEnabled", touchMethod(base, name).calls(base.type, gate.name))
            }

            // Every return of the check hands back v0, and nothing jumps straight to a return.
            val instructions = gate.implementation!!.instructions.toList()
            val addresses = instructions.runningFold(0) { address, instruction -> address + instruction.codeUnits }
            val targets = instructions.withIndex()
                .filter { it.value is OffsetInstruction }
                .map { addresses[it.index] + (it.value as OffsetInstruction).codeOffset }
                .toSet()
            val returns = instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }
            assertTrue("the check returns", returns.isNotEmpty())
            returns.forEach { (index, instruction) ->
                assertEquals("return at $index hands back v0", 0, (instruction as OneRegisterInstruction).registerA)
                assertTrue("no branch lands on the return at $index", addresses[index] !in targets)
            }

            val middles = classes.values.filter { classDef -> classDef.methods.any { it.name == "getOnInterceptTouchEventListeners" } }
            assertEquals("classes declaring getOnInterceptTouchEventListeners", 1, middles.size)
            val middle = middles.single()
            assertEquals("the home pager's class extends the base", base.type, middle.superclass)
            listOf("dispatchTouchEvent", "onInterceptTouchEvent").forEach { name ->
                assertTrue(
                    "the home pager's $name hands the event to the base's",
                    touchMethod(middle, name).implementation!!.instructions.any { instruction ->
                        instruction.opcode == Opcode.INVOKE_SUPER &&
                            ((instruction as ReferenceInstruction).reference as MethodReference).let {
                                it.definingClass == base.type && it.name == name
                            }
                    },
                )
            }
            assertTrue(
                "the home pager's class leaves onTouchEvent and the check to the base",
                middle.methods.none { it.name == "onTouchEvent" || it.name == gate.name },
            )

            val subclasses = classes.values.filter { it.superclass == base.type || it.superclass == middle.type }
            assertEquals("the chain below the base: ${subclasses.map { it.type }}", 2, subclasses.size)
            val concrete = subclasses.single { it.superclass == middle.type }
            assertTrue(
                "the concrete pager overrides none of the hooks",
                concrete.methods.none { it.name == gate.name || it.name in TOUCH_METHODS },
            )
            val creators = classes.values.filter { classDef ->
                classDef.methods.any { method ->
                    method.implementation?.instructions?.any { instruction ->
                        instruction.opcode == Opcode.NEW_INSTANCE &&
                            ((instruction as ReferenceInstruction).reference as TypeReference).type == concrete.type
                    } == true
                }
            }.map { it.type }.toSet()
            assertEquals(
                "who creates the concrete pager",
                setOf("Lcom/by/andInflater/homepage_common_activity_main;"),
                creators,
            )

            // The second pager the base's hooks reach: the detail page casts its inflated view to it.
            val detailCasts = classes.values.filter { classDef ->
                classDef.type.contains("DetailFragment") && classDef.methods.any { method ->
                    method.implementation?.instructions?.any { instruction ->
                        instruction.opcode == Opcode.CHECK_CAST &&
                            ((instruction as ReferenceInstruction).reference as TypeReference).type == base.type
                    } == true
                }
            }
            assertTrue("a detail page casts its pager to the base", detailCasts.isNotEmpty())
        }
    }

    private fun touchMethod(classDef: ClassDef, name: String): Method =
        classDef.methods.single { it.name == name && it.parameterTypes.map { p -> p.toString() } == listOf(MOTION_EVENT) }

    private fun Method.calls(owner: String, name: String) = implementation!!.instructions.any { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
            it.definingClass == owner && it.name == name
        } == true
    }

    private companion object {
        const val MOTION_EVENT = "Landroid/view/MotionEvent;"
        val TOUCH_METHODS = listOf("dispatchTouchEvent", "onInterceptTouchEvent", "onTouchEvent")
    }
}
