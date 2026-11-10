/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.cleardisplay

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.removeInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BUBBLE_EXTENSION = "Lapp/morphe/extension/tiktok/cleardisplay/FollowingStoriesBubble;"

/**
 * What the Following stories bubble hook needs, held to each declared build: the bubble's clear
 * mode handler hides it with one call taking two booleans, under a test of the event's own flag
 * whose other arm is the way out; the list model is still a single list model with listGetAll;
 * and the hook lands before the hide and at the start of the way out, keeping the branch into
 * the way out on its first new instruction and TikTok's own instructions in their order.
 */
class FollowingStoriesBubbleAnchorsTest {
    @Test
    fun `each declared build has the bubble's clear mode shape and the hook fits it`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = HashMap<String, ClassDef>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) classes.putIfAbsent(classDef.type, classDef)
            }
            checkBubbleList { classes[it] }
            val bubble = classes[SKYLIGHT_BUBBLE] ?: throw AssertionError("$version: $SKYLIGHT_BUBBLE is missing")
            val found = findBubbleClearMode(bubble)
            assertEquals("$version: the hide is the bubble's own show and hide",
                listOf("Z", "Z"), found.toggle.parameterTypes.map { it.toString() })

            val before = found.handler.implementation!!.instructions.toList()
            val test = (found.toggleIndex - 1 downTo 0).first { before[it].opcode == Opcode.IF_EQZ }
            assertEquals("$version: the enter test leads to the way out", found.exitIndex, branchTarget(found.handler, test))

            val hooked = MutableMethod(found.handler)
            hooked.hookBubbleClearMode(found)
            val after = hooked.implementation!!.instructions.toList()
            assertEquals("$version: three instructions on the way in, thirteen on the way out", before.size + 16, after.size)

            // The way in: the bubble's view goes to the extension right before TikTok hides it.
            val view = after[found.toggleIndex].getReference<MethodReference>()!!
            assertEquals(found.view.toString(), view.toString())
            val hiding = after[found.toggleIndex + 2].getReference<MethodReference>()!!
            assertEquals(BUBBLE_EXTENSION, hiding.definingClass)
            assertEquals("hiding", hiding.name)
            assertEquals(found.toggle.toString(), after[found.toggleIndex + 3].getReference<MethodReference>().toString())

            // The way out: the branch lands on its first new instruction, and a no skips to the nop.
            val out = found.exitIndex + 3
            assertEquals("$version: the way out starts at the hook", out, branchTarget(hooked, test))
            assertEquals(found.view.toString(), after[out].getReference<MethodReference>().toString())
            assertEquals("$version: a private list getter is called directly",
                if (AccessFlags.PRIVATE.isSet(found.list.accessFlags)) Opcode.INVOKE_DIRECT else Opcode.INVOKE_VIRTUAL,
                after[out + 2].opcode)
            assertEquals(Opcode.IF_EQZ, after[out + 4].opcode)
            assertEquals("$version: no list model asks with no list", out + 7, branchTarget(hooked, out + 4))
            assertEquals(POWER_LIST_ALL, after[out + 5].getReference<MethodReference>().toString())
            val ask = after[out + 7].getReference<MethodReference>()!!
            assertEquals(BUBBLE_EXTENSION, ask.definingClass)
            assertEquals("showAgain", ask.name)
            assertEquals(Opcode.IF_EQZ, after[out + 9].opcode)
            assertEquals(Opcode.NOP, after[out + 12].opcode)
            assertEquals("$version: a no skips the show", out + 12, branchTarget(hooked, out + 9))
            assertEquals(found.toggle.toString(), after[out + 11].getReference<MethodReference>().toString())

            val added = (found.toggleIndex until found.toggleIndex + 3) + (out until out + 13)
            assertEquals(
                "$version: TikTok's own instructions keep their order",
                before.map { it.opcode },
                after.filterIndexed { index, _ -> index !in added }.map { it.opcode },
            )
        }
    }

    @Test
    fun `a way in that runs on into the way out is refused`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val original = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .first { it.type == SKYLIGHT_BUBBLE }
            val found = findBubbleClearMode(original)
            val bubble = MutableClass(original)
            // Drop the goto that ends the way in, so its last instruction runs on into the way out.
            bubble.methods.single { it.name == found.handler.name && it.parameterTypes == found.handler.parameterTypes }
                .removeInstruction(found.exitIndex - 1)
            assertThrows(PatchException::class.java) { findBubbleClearMode(bubble) }
        }
    }

    @Test
    fun `a list model that isn't a single list model is refused`() {
        assertThrows(PatchException::class.java) { checkBubbleList { null } }
    }
}
