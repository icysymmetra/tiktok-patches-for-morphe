package app.morphe.patches.tiktok.interaction.speed

import app.morphe.Fixtures
import app.morphe.patcher.util.getReference
import app.morphe.util.literalReads
import app.morphe.util.readsAfter
import app.morphe.util.readsRegisterAsFloat
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every place the hold speed reaches on each declared TikTok build, held to what the patch assumes.
 *
 * The gesture's 2x is a literal: once in the press method, twice in the release method and
 * once in the lock banner's "already at that speed" check. Each is followed through the
 * method's branches and handlers to every instruction that reads it, and each of those must
 * read it as a float, which is what lets the patch hand it a float from the hold speed
 * without the verifier refusing the class. The press literal reaches the call that applies the
 * speed; one release literal reaches the selection boundary the pull-down lock persists
 * through, the other the speed-up telemetry.
 *
 * TikTok's own words for the hold come from three places, all found the way the patch finds
 * them: the lock banner's getString calls, the plain banner the receiver shows and hides, and
 * the lock toast the release method sets by string id, whose id register must be free after
 * each call because the reworded text goes back in it.
 */
class HoldSpeedAnchorsTest {
    private val component = "Lcom/ss/android/ugc/aweme/feed/longvideo/edgespeedup/EdgeSpeedupAssem;"
    private val aweme = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"

    @Test
    fun `each declared build carries every hold speed site the patch rewrites`() {
        Fixtures.forEachDeclared { apk ->
            val app = load(apk)
            val (press, release) = gestureMethods(app, apk)

            // Press: one literal, read only as a float, reaching the component's (F)V apply call.
            val pressLiterals = floatLiterals(press)
            assertEquals("2x literals on press", 1, pressLiterals.size)
            val pressReads = press.literalReads(pressLiterals.single().first).map { press.at(it) }
            assertTrue(
                "the press literal reaches the component's apply call: ${pressReads.map { it.describe() }}",
                pressReads.any { call ->
                    call.opcode == Opcode.INVOKE_VIRTUAL && call.getReference<MethodReference>()?.let {
                        it.definingClass == component && it.parameterTypes == listOf("F") && it.returnType == "V"
                    } == true
                },
            )

            // Release: two literals, one reaching the selection boundary, one the telemetry.
            val releaseLiterals = floatLiterals(release)
            assertEquals("2x literals on release", 2, releaseLiterals.size)
            val reached = releaseLiterals.map { (index, _) ->
                release.literalReads(index).mapNotNull { release.at(it).getReference<MethodReference>() }
            }
            assertTrue(
                "one release literal reaches the selection boundary (F, Aweme, String, String)V",
                reached.any { calls -> calls.any { it.parameterTypes == listOf("F", aweme, "Ljava/lang/String;", "Ljava/lang/String;") } },
            )
            assertTrue(
                "one release literal reaches the speed-up telemetry (F, Aweme, Float, String, String, HashMap)V",
                reached.any { calls ->
                    calls.any {
                        it.parameterTypes.firstOrNull() == "F" && it.parameterTypes.getOrNull(2) == "Ljava/lang/Float;"
                    }
                },
            )

            // The lock toast: the builder's text-by-id setter, three calls, each id register free after.
            val toastCalls = release.implementation!!.instructions.withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.INVOKE_VIRTUAL && instruction.getReference<MethodReference>()?.let { reference ->
                    reference.parameterTypes == listOf("I") && reference.returnType == "V" &&
                        app[reference.definingClass]?.methods?.any { method ->
                            method.name == reference.name && method.parameterTypes == listOf("I") &&
                                method.implementation?.instructions?.any(::isGetString) == true
                        } == true &&
                        app.getValue(reference.definingClass).methods.count {
                            it.parameterTypes == listOf("Ljava/lang/CharSequence;") && it.returnType == "V"
                        } == 1
                } == true
            }
            assertEquals("the lock's toasts set by id", 3, toastCalls.size)
            toastCalls.forEach { (index, instruction) ->
                val call = instruction as FiveRegisterInstruction
                assertTrue("short registers at $index", call.registerC < 16 && call.registerD < 16)
                assertEquals("the id register at $index is read again", emptyList<Int>(), release.readsAfter(index, call.registerD))
            }

            // The banners, from the receiver of the press method's event.
            // Lazily: every method of every class in one list does not fit the test's heap.
            val receivers = app.values.asSequence().flatMap { it.methods.asSequence() }.filter { method ->
                method.returnType == "V" && strings(method).containsAll(HOLD_BANNER_RECEIVER_STRINGS)
            }.toList()
            assertEquals("banner receivers: ${receivers.map { it.definingClass + "->" + it.name }}", 1, receivers.size)
            val receiverCalls = receivers.single().implementation!!.instructions.mapNotNull { instruction ->
                instruction.getReference<MethodReference>()?.let { instruction.opcode to it }
            }
            val lockCall = receiverCalls.filter { (opcode, reference) ->
                opcode == Opcode.INVOKE_INTERFACE && reference.parameterTypes == listOf("F", "Z") && reference.returnType == "V"
            }.map { it.second.toString() }.distinct()
            assertEquals("lock banner calls: $lockCall", 1, lockCall.size)
            val lockInterface = receiverCalls.first { it.second.toString() == lockCall.single() }.second
            val lockBanners = app.values.filter { lockInterface.definingClass in it.interfaces }
            assertEquals("lock banner implementations", 1, lockBanners.size)
            val lockShow = lockBanners.single().methods.single {
                it.name == lockInterface.name && it.parameterTypes == listOf("F", "Z")
            }
            assertEquals("2x literals in the lock banner", 1, floatLiterals(lockShow).size)
            // The four hold texts ("Pull down to lock 2x speed", "Release to lock 2x speed", "Pull down
            // for normal speed", "Release for normal speed"): read once each by the constructor to
            // size the banner for the widest, then again when shown. All of them are reworded, so
            // no other text may come through these calls.
            val textIds = lockBanners.single().methods.flatMap { method ->
                val instructions = method.implementation?.instructions?.toList().orEmpty()
                instructions.indices.filter {
                    isGetString(instructions[it]) && instructions.getOrNull(it + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
                }.map { at ->
                    val idRegister = (instructions[at] as FiveRegisterInstruction).registerD
                    (at - 1 downTo 0).map { instructions[it] }.first {
                        it is OneRegisterInstruction && it.registerA == idRegister && it.opcode.setsRegister()
                    }.let { (it as? NarrowLiteralInstruction)?.narrowLiteral }
                }
            }
            assertEquals("texts the lock banner reads with getString: $textIds", 12, textIds.size)
            assertEquals("distinct string ids among them: $textIds", 4, textIds.filterNotNull().distinct().size)
            assertTrue("every id is a constant: $textIds", textIds.none { it == null })
            val plain = receiverCalls.filter { (opcode, reference) ->
                opcode == Opcode.INVOKE_VIRTUAL && reference.parameterTypes.isEmpty() && reference.returnType == "V" &&
                    reference.definingClass != receivers.single().definingClass &&
                    app[reference.definingClass]?.superclass == "Landroid/widget/LinearLayout;"
            }.map { it.second.toString() }.distinct()
            assertEquals("the plain banner's show and hide: $plain", 2, plain.size)
            assertEquals("one plain banner", 1, plain.map { it.substringBefore("->") }.distinct().size)
        }
    }

    @Test
    fun `each declared build's speed menu toast handler is the one the reword targets`() {
        Fixtures.forEachDeclared { apk ->
            val app = load(apk)
            val basePackage = "Lcom/ss/android/ugc/aweme/share/base/model/BaseSharePackage;"
            // The speed menu class: the one whose panel method takes a BaseSharePackage and carries
            // the menu's own gate string, the same anchor PlaybackSpeedMenuFingerprint resolves.
            val menu = app.values.filter { classDef ->
                classDef.methods.any {
                    it.parameterTypes == listOf(basePackage) && "is_highlight_fast_speed" in strings(it)
                }
            }
            assertEquals("speed menu classes: ${menu.map { it.type }}", 1, menu.size)
            val menuClass = menu.single()
            val floatFields = menuClass.fields.filter { it.type == "F" }
            assertEquals("one float speed field: ${floatFields.map { it.name }}", 1, floatFields.size)
            val speedField = floatFields.single()
            fun Method.readsSpeed() = implementation?.instructions?.any { instruction ->
                instruction.opcode == Opcode.IGET &&
                    instruction.getReference<com.android.tools.smali.dexlib2.iface.reference.FieldReference>()?.let {
                        it.definingClass == speedField.definingClass && it.name == speedField.name && it.type == "F"
                    } == true
            } == true
            // Of the three (View, BaseSharePackage)V methods the toast handler is the one with the
            // single format argument (one aput-object) that reads the speed field; the other two are
            // trivial delegators.
            val handlers = menuClass.methods.filter { method ->
                method.parameterTypes == listOf("Landroid/view/View;", basePackage) && method.returnType == "V" &&
                    method.implementation?.instructions?.count { it.opcode == Opcode.APUT_OBJECT } == 1 &&
                    method.readsSpeed()
            }
            assertEquals("speed menu toast handlers: ${handlers.map { it.name }}", 1, handlers.size)
            val handler = handlers.single()
            val instructions = handler.implementation!!.instructions.toList()
            val speedReads = instructions.filter { instruction ->
                instruction.opcode == Opcode.IGET &&
                    instruction.getReference<com.android.tools.smali.dexlib2.iface.reference.FieldReference>()?.name == speedField.name
            }
            assertEquals("one speed-field read in the handler", 1, speedReads.size)
            val speedRegister = (speedReads.single() as OneRegisterInstruction).registerA
            val aputs = instructions.filter { it.opcode == Opcode.APUT_OBJECT }
            assertEquals("one format argument in the handler", 1, aputs.size)
            val labelRegister = (aputs.single() as com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction).registerA
            assertTrue(
                "the label and speed registers fit a short call: v$labelRegister, v$speedRegister",
                labelRegister < 16 && speedRegister < 16,
            )
        }
    }

    @Test
    fun `every retained fixture has the gesture's literals read only as floats`() {
        val apks = Fixtures.apks()
        assertTrue("fixtures: ${apks.map { it.name }}", apks.containsAll(Fixtures.declared()))
        apks.forEach { apk ->
            val (press, release) = gestureMethods(load(apk), apk)
            assertTrue("${apk.name}: press has a 2x literal", floatLiterals(press).isNotEmpty())
            assertTrue("${apk.name}: release has a 2x literal", floatLiterals(release).isNotEmpty())
        }
    }

    private fun load(apk: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }

    private fun gestureMethods(app: Map<String, ClassDef>, apk: File): Pair<Method, Method> {
        val classDef = app[component] ?: error("${apk.name}: no $component")
        val presses = classDef.methods.filter { method ->
            method.returnType == "V" && method.parameterTypes == listOf("F", "F") &&
                strings(method).containsAll(EDGE_SPEEDUP_PRESS_STRINGS)
        }
        val releases = classDef.methods.filter { method ->
            method.returnType == "V" && method.parameterTypes == listOf("Z") &&
                strings(method).containsAll(EDGE_SPEEDUP_RELEASE_STRINGS)
        }
        assertEquals("${apk.name}: press methods ${presses.map { it.name }}", 1, presses.size)
        assertEquals("${apk.name}: release methods ${releases.map { it.name }}", 1, releases.size)
        return presses.single() to releases.single()
    }

    private fun strings(method: Method): Set<String> =
        method.implementation?.instructions?.mapNotNull { instruction ->
            ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
        }?.toSet().orEmpty()

    /** The 2.0f literals, each only ever read as a float: what the patch requires of each. */
    private fun floatLiterals(method: Method): List<Pair<Int, Int>> {
        val literals = method.implementation!!.instructions.withIndex().filter { (_, instruction) ->
            instruction.opcode == Opcode.CONST_HIGH16 &&
                (instruction as NarrowLiteralInstruction).narrowLiteral == 2.0f.toRawBits()
        }.map { (index, instruction) -> index to (instruction as OneRegisterInstruction).registerA }
        literals.forEach { (index, register) ->
            val reads = method.literalReads(index)
            assertTrue("${method.name}: the literal at $index is never read", reads.isNotEmpty())
            reads.forEach { read ->
                assertTrue(
                    "${method.name}: the literal at $index is read as something else at $read: ${method.at(read).describe()}",
                    method.readsRegisterAsFloat(read, register),
                )
            }
        }
        return literals
    }

    private fun Method.at(index: Int): Instruction = implementation!!.instructions.elementAt(index)

    private fun Instruction.describe(): String =
        opcode.name + ((this as? ReferenceInstruction)?.reference?.let { " $it" } ?: "")
}
