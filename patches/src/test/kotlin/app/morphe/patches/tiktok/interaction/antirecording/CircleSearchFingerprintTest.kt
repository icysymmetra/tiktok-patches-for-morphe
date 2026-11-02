package app.morphe.patches.tiktok.interaction.antirecording

import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CircleSearchFingerprintTest {
    private fun matches(key: String): Boolean {
        val method = ImmutableMethod("LFixture;", "invoke", emptyList(), "Ljava/lang/Object;",
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
            ImmutableMethodImplementation(1, listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(key)),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
            ), null, null))
        val owner = ImmutableClassDef("LFixture;", AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
            emptyList(), null, emptySet(), emptyList(), listOf(method))
        return CircleSearchBlockFingerprint.takes(method, owner)
    }

    @Test
    fun `the block experiment excludes the separate post-block tag experiment`() {
        assertTrue(matches("circle_search_block"))
        assertFalse(matches("show_tag_after_circle_search_block"))
    }
}
