package app.morphe.patches.tiktok.misc.navigation

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.getReference
import app.morphe.util.literalReads
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bottom tab icon's badge setters keep their names while the class is renamed on every
 * build. Each name is one method of that shape in the app, both sit on one class, and that class
 * carries the badge views the setters drive, so the hook lands on the tab icon and nowhere else.
 *
 * The hook answers inside those two setters, so it hides every badge only if nothing else shows
 * one. On 47.0.3 that holds two ways, both checked here: a badge view enters the tab icon only
 * through its two lazy creators, each of which hands the new view to its setter straight away,
 * and no instruction anywhere passes a badge view to a visibility write other than the setters,
 * following each badge value through its method to every read of it.
 */
class TabBadgeAnchorsTest {
    private val setters = mapOf("getCountDotView" to "setCountDotVisibility", "getRedDotVIew" to "setTabDotVisibility")

    @Test
    fun `each declared build has one badge setter of each name on one tab icon`() {
        Fixtures.forEachDeclared { apk ->
            val app = load(apk)
            val counts = ArrayList<Method>()
            val dots = ArrayList<Method>()
            app.values.forEach { classDef ->
                classDef.methods.forEach { method ->
                    if (method.returnType != "V" || method.parameterTypes != listOf("I")) return@forEach
                    when (method.name) {
                        "setCountDotVisibility" -> counts += method
                        "setTabDotVisibility" -> dots += method
                    }
                }
            }
            assertEquals("setCountDotVisibility(I)V owners: ${counts.map { it.definingClass }}", 1, counts.size)
            assertEquals("setTabDotVisibility(I)V owners: ${dots.map { it.definingClass }}", 1, dots.size)
            assertEquals("the two setters sit on different classes", counts.single().definingClass, dots.single().definingClass)

            val icon = app.getValue(counts.single().definingClass)
            assertEquals("the tab icon is not a FrameLayout", "Landroid/widget/FrameLayout;", icon.superclass)
            val names = icon.methods.map { it.name }
            assertTrue("$names", "getCountDotView" in names && "getRedDotVIew" in names && "setCountDotText" in names)
        }
    }

    @Test
    fun `each declared build shows a badge only through the two hooked setters`() {
        Fixtures.forEachDeclared { apk ->
            val app = load(apk)
            val icon = app.values.single { classDef -> classDef.methods.any { it.name == "setCountDotVisibility" } }
            // Each badge field, from the getter that returns it, with the setter that drives it.
            val setterOf = icon.methods.filter { it.name in setters }.associate { getter ->
                val field = getter.implementation!!.instructions.mapNotNull { it.getReference<FieldReference>() }
                    .single { it.definingClass == icon.type }
                field.name to setters.getValue(getter.name)
            }
            assertEquals("badge fields: $setterOf", 2, setterOf.size)

            val writers = mutableMapOf<String, Pair<Method, String>>()
            val strayWrites = mutableListOf<String>()
            val viewSetterCalls = mutableListOf<String>()
            app.values.asSequence().flatMap { it.methods.asSequence() }.forEach { method ->
                val instructions = method.implementation?.instructions?.toList() ?: return@forEach
                instructions.forEachIndexed { index, instruction ->
                    instruction.getReference<MethodReference>()?.let { call ->
                        if (call.definingClass == icon.type && call.name in setOf("setCountDotView", "setRedDotVIew")) {
                            viewSetterCalls += "${method.definingClass}->${method.name}"
                        }
                    }
                    if (instruction.opcode == Opcode.IPUT_OBJECT) {
                        val field = instruction.getReference<FieldReference>()
                        if (field?.definingClass == icon.type && field.name in setterOf &&
                            !(method.definingClass == icon.type && method.name in setOf("setCountDotView", "setRedDotVIew"))
                        ) {
                            writers["${method.definingClass}->${method.name}${method.parameterTypes}"] = method to setterOf.getValue(field.name)
                        }
                    }
                    val loadsBadge = when (instruction.opcode) {
                        Opcode.IGET_OBJECT -> instruction.getReference<FieldReference>()
                            ?.let { it.definingClass == icon.type && it.name in setterOf } == true
                        Opcode.MOVE_RESULT_OBJECT -> instructions.getOrNull(index - 1)?.getReference<MethodReference>()
                            ?.let { it.definingClass == icon.type && it.name in setters } == true
                        else -> false
                    }
                    if (!loadsBadge || (method.definingClass == icon.type && method.name in setters.values)) return@forEachIndexed
                    method.literalReads(index).forEach { read ->
                        val call = instructions[read].getReference<MethodReference>() ?: return@forEach
                        val writesVisibility = call.name == "setVisibility" ||
                            (instructions[read].opcode == Opcode.INVOKE_STATIC && app[call.definingClass]?.methods?.firstOrNull {
                                it.name == call.name && it.parameterTypes == call.parameterTypes
                            }?.implementation?.instructions?.any { it.getReference<MethodReference>()?.name == "setVisibility" } == true)
                        if (writesVisibility) strayWrites += "${method.definingClass}->${method.name} at $read: $call"
                    }
                }
            }
            assertEquals("a badge's visibility written outside the hooked setters", emptyList<String>(), strayWrites)
            assertEquals("the tab icon's own view setters are called", emptyList<String>(), viewSetterCalls)

            // A new badge view: the method that stores it calls its setter, or every caller of that
            // method does, before anything could draw it.
            assertEquals("badge creators: ${writers.keys}", 2, writers.size)
            writers.values.forEach { (writer, setter) ->
                val callsSetter = { method: Method ->
                    method.implementation?.instructions?.any { it.getReference<MethodReference>()?.let { call ->
                        call.definingClass == icon.type && call.name == setter
                    } == true } == true
                }
                if (callsSetter(writer)) return@forEach
                val callers = app.values.asSequence().flatMap { it.methods.asSequence() }.filter { method ->
                    method.implementation?.instructions?.any { it.getReference<MethodReference>()?.let { call ->
                        call.definingClass == writer.definingClass && call.name == writer.name &&
                            call.parameterTypes == writer.parameterTypes
                    } == true } == true
                }.toList()
                assertTrue("${writer.definingClass}->${writer.name} has no callers", callers.isNotEmpty())
                callers.forEach { caller ->
                    assertTrue(
                        "${caller.definingClass}->${caller.name} creates a badge view and never calls $setter",
                        callsSetter(caller),
                    )
                }
            }
        }
    }

    /**
     * #170: the bubble over Inbox that counts new likes, followers, comments and who's LIVE when
     * TikTok opens. The hook returns at the start of the one method the fingerprint takes, so it
     * holds only if that method is the bubble's whole show: it builds the bubble view and reports
     * it shown itself, and nothing else in the app builds one.
     */
    @Test
    fun `each declared build shows the inbox bubble from one method the hook returns from`() {
        Fixtures.forEachDeclared { apk ->
            val app = load(apk)
            val taken = app.values.flatMap { classDef ->
                classDef.methods.filter { InboxNoticeBubbleFingerprint.takes(it, classDef) }
            }
            assertEquals("methods the bubble fingerprint takes: ${taken.map { "${it.definingClass}->${it.name}" }}", 1, taken.size)
            val show = taken.single()
            val strings = { method: Method ->
                method.implementation?.instructions?.mapNotNull { it.getReference<StringReference>()?.string }.orEmpty()
            }
            assertTrue("the show doesn't build the bubble itself", "createBubbleView" in strings(show) && "onBubbleShow" in strings(show))
            assertTrue("no local register for the check", show.implementation!!.registerCount > 1)
            val builders = app.values.asSequence().flatMap { it.methods.asSequence() }
                .filter { "createBubbleView" in strings(it) }
                .map { "${it.definingClass}->${it.name}" }.toList()
            assertEquals("methods that build the bubble", listOf("${show.definingClass}->${show.name}"), builders)
        }
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
