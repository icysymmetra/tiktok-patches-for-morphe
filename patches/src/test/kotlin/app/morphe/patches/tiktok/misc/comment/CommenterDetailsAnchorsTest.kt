/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.comment

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.patches.tiktok.misc.translation.BaseCommentCellBindFingerprint
import app.morphe.takes
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Show commenter handle and region hooks, held to each declared build.
 *
 * <p>Each fingerprint takes one method, and the shape checks the patch makes before writing
 * anything pass on it: the name setter hands its parameter straight to the name view and falls
 * from that write straight into its return, where the name's color goes on, the reply
 * label has one write whose text register is free after it, and the time row has the two time
 * writes the cell's layouts use. None of the three is the cell's bind, the method Comment
 * translation and Comment tools hook, so the patches edit different methods and the order they
 * are applied in can't matter. A cell that overrides the name setter or the time row still runs
 * the cell's own, so the hooks reach the reply cells too.
 */
class CommenterDetailsAnchorsTest {
    @Test
    fun `the name, reply label and time hooks resolve on each declared build`() {
        Fixtures.forEachDeclared { apk ->
            val classes = HashMap<String, ClassDef>()
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            val version = Fixtures.versionOf(apk)
            fun taken(fingerprint: Fingerprint) = classes.values.flatMap { classDef ->
                classDef.methods.filter { fingerprint.takes(it, classDef) }
            }
            val cell = checkNotNull(classes[COMMENT_CELL]) { "$version: no $COMMENT_CELL" }

            val names = taken(CommentNameWriteFingerprint)
            assertEquals("$version: the name fingerprint takes ${names.map(::label)}", 1, names.size)
            val name = names.single()
            assertTrue("$version: the name setter has no local for the comment",
                name.implementation!!.registerCount - name.numberOfParameterRegisters >= 1)
            // Throws unless the cell has exactly one comment getter.
            cell.commentGetter()
            // The color hook reads TikTok's color off the view the setter just wrote, so the site's
            // view has to be the TextView the setter loaded from its own field.
            val colorSite = name.nameColorSite()
            val loaded = name.implementation!!.instructions.take(colorSite.writeIndex).filter { instruction ->
                instruction.opcode == Opcode.IGET_OBJECT &&
                    (instruction as TwoRegisterInstruction).registerA == colorSite.viewRegister
            }
            assertTrue("$version: the name write's view isn't one the setter read from the cell", loaded.isNotEmpty())
            assertFalse("$version: the comment would land on the name view", colorSite.commentRegister == colorSite.viewRegister)

            val replies = taken(CommentReplyLabelFingerprint)
            assertEquals("$version: the reply label fingerprint takes ${replies.map(::label)}", 1, replies.size)
            val reply = replies.single()
            val replySite = reply.replyLabelSite()
            val beforeWrite = reply.implementation!!.instructions.take(replySite.callIndex)
            assertTrue("$version: the reply label doesn't read the replied-to name before writing it",
                beforeWrite.any { it.calls(COMMENT_MODEL, "getReplyToNickName") })
            assertFalse("$version: the label's view and text share a register",
                replySite.viewRegister == replySite.textRegister)

            val times = taken(CommentTimeWriteFingerprint)
            assertEquals("$version: the time fingerprint takes ${times.map(::label)}", 1, times.size)
            val time = times.single()
            val timeSites = time.commentTimeSites()
            assertEquals("$version: time writes $timeSites", 2, timeSites.size)
            assertEquals("$version: two time writes share a setText", 2, timeSites.map { it.writeIndex }.toSet().size)

            val binds = taken(BaseCommentCellBindFingerprint)
            assertEquals("$version: the cell bind fingerprint takes ${binds.map(::label)}", 1, binds.size)
            val hooked = listOf(name, reply, time).map(::label)
            assertFalse("$version: a hook shares the cell bind with Comment translation and Comment tools",
                label(binds.single()) in hooked)
            assertEquals("$version: two hooks land in one method", 3, hooked.toSet().size)

            val cells = classes.values.filter { it.descendsFrom(COMMENT_CELL, classes) }
            assertTrue("$version: nothing extends the comment cell", cells.isNotEmpty())
            for (sub in cells) for (method in sub.methods) {
                val overridden = listOf(name, time).firstOrNull { it.signature() == method.signature() } ?: continue
                val instructions = method.implementation?.instructions?.toList().orEmpty()
                val superCall = instructions.indexOfFirst { instruction ->
                    (instruction.opcode == Opcode.INVOKE_SUPER || instruction.opcode == Opcode.INVOKE_SUPER_RANGE) &&
                        instruction.getReference<MethodReference>()?.let { it.signature() == overridden.signature() } == true
                }
                assertTrue("$version: ${label(method)} replaces the cell's own ${overridden.name}", superCall >= 0)
                assertFalse("$version: ${label(method)} writes text over the hooked view after the cell's own",
                    instructions.drop(superCall + 1).any { it.writesText() })
            }

            val nameCallers = callers(classes) { reference ->
                reference.signature() == name.signature() && (reference.definingClass == COMMENT_CELL ||
                    classes[reference.definingClass]?.descendsFrom(COMMENT_CELL, classes) == true)
            }
            assertTrue("$version: the name setter is called from $nameCallers",
                nameCallers.isNotEmpty() && nameCallers.all { it == COMMENT_CELL || classes[it]?.descendsFrom(COMMENT_CELL, classes) == true })
            val replyCallers = callers(classes) { it.definingClass == reply.definingClass && it.signature() == reply.signature() }
            assertTrue("$version: the reply label is bound from $replyCallers",
                replyCallers.isNotEmpty() && replyCallers.all { it.startsWith(COMMENT_LIST_PACKAGE) })
        }
    }

    private companion object {
        const val COMMENT_LIST_PACKAGE = "Lcom/ss/android/ugc/aweme/commentv2/commentlist/powercell/"

        fun label(method: Method) = "${method.definingClass}->${method.name}"

        /** Name and descriptor: what an override shares with the method it overrides. */
        fun MethodReference.signature() = name + parameterTypes.joinToString("", "(", ")") + returnType

        fun Instruction.calls(owner: String, name: String) =
            getReference<MethodReference>()?.let { it.definingClass == owner && it.name == name } == true

        /** A TextView text write, or the colored text helper the name and the label go through. */
        fun Instruction.writesText() = getReference<MethodReference>()?.let { reference ->
            val parameters = reference.parameterTypes.map(CharSequence::toString)
            (reference.name == "setText" && parameters == listOf("Ljava/lang/CharSequence;")) ||
                parameters == listOf("I", "I", "Landroid/widget/TextView;", "Ljava/lang/CharSequence;")
        } == true

        fun ClassDef.descendsFrom(ancestor: String, classes: Map<String, ClassDef>): Boolean {
            var current = superclass
            while (current != null) {
                if (current == ancestor) return true
                current = classes[current]?.superclass
            }
            return false
        }

        /** The classes holding a call that [wanted] accepts. */
        fun callers(classes: Map<String, ClassDef>, wanted: (MethodReference) -> Boolean): Set<String> {
            val found = sortedSetOf<String>()
            for (classDef in classes.values) for (method in classDef.methods) {
                val instructions = method.implementation?.instructions ?: continue
                if (instructions.any { instruction -> instruction.getReference<MethodReference>()?.let(wanted) == true }) {
                    found += classDef.type
                }
            }
            return found
        }
    }
}
