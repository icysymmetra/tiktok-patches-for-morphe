package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StoryBindContractTest {
    @Test
    fun `exactly two concrete story bind methods are accepted`() {
        val methods = listOf(method("bindCurrent"), method("bindAdjacent"))

        assertEquals(methods, requireStoryBindMethods(methods))
    }

    @Test
    fun `one or three story bind matches name the expected count`() {
        for (methods in listOf(
            listOf(method("only")),
            listOf(method("first"), method("second"), method("third")),
        )) {
            val error = assertThrows(PatchException::class.java) {
                requireStoryBindMethods(methods)
            }
            assertTrue(error.message.orEmpty().contains("expected exactly two"))
            assertTrue(error.message.orEmpty().contains("found ${methods.size}"))
        }
    }

    @Test
    fun `a bodyless story bind method is refused before injection`() {
        val error = assertThrows(PatchException::class.java) {
            requireStoryBindMethods(listOf(method("concrete"), method("abstract", body = false)))
        }

        assertTrue(error.message.orEmpty().contains("has no implementation"))
    }

    private fun method(name: String, body: Boolean = true) = MutableMethod(
        ImmutableMethod(
            "Lcom/ss/android/ugc/feed/platform/component/StoryImmersivePlayAreaComponent;",
            name,
            listOf("I", "Lcom/ss/android/ugc/aweme/feed/model/Aweme;")
                .map { ImmutableMethodParameter(it, null, null) },
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            if (body) {
                ImmutableMethodImplementation(
                    4,
                    listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                    null,
                    null,
                )
            } else {
                null
            },
        ),
    )
}
