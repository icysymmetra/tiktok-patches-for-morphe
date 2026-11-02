package app.morphe.patches.tiktok.interaction.looping

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Story controls hooks, held to each declared build: one enum declares all five of the story
 * play mode constants, the real-named story pager reads a field of that enum (the mode the loop
 * switch swaps), and the pager has the one onPlayCompleted(String) the photo hold stands in front
 * of, with registers to spare below its parameters.
 */
class StoryControlsAnchorsTest {
    @Test
    fun `the story pager reads one play mode enum and has one play completed callback`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .filter { it.type == STORY_PAGER || isStoryPlayModeEnum(it) }
                .toList()
            val enums = classes.filter(::isStoryPlayModeEnum)
            assertEquals("$apk: play mode enums ${enums.map { it.type }}", 1, enums.size)
            val pager: ClassDef? = classes.firstOrNull { it.type == STORY_PAGER }
            assertNotNull("$apk: no $STORY_PAGER", pager)

            val reads = pager!!.methods.sumOf { method ->
                method.implementation?.instructions?.playModeReads(enums.single().type)?.size ?: 0
            }
            assertTrue("$apk: the pager reads its play mode $reads time(s)", reads >= 1)

            val completed = pager.playCompletedMethod()
            assertNotNull("$apk: no onPlayCompleted(String) on the pager", completed)
            val registers = completed!!.implementation!!.registerCount
            assertTrue("$apk: onPlayCompleted has $registers register(s), none free below p0 and p1", registers >= 3)
        }
    }
}
