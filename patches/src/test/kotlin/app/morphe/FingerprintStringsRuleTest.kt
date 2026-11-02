package app.morphe

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [takes] judges a fingerprint's strings using its Patcher 2.0 filters: each string the
 * method loads answers one wanted string at most. #169 shipped a fingerprint with two pieces of
 * one log literal, the old per-string check passed it on the fixture, and the real apply failed.
 */
class FingerprintStringsRuleTest {
    private val type = "LFixture;"

    private fun method(vararg loads: String): Method = ImmutableMethod(type, "run", emptyList(), "V",
        AccessFlags.PUBLIC.value, emptySet(), emptySet(),
        ImmutableMethodImplementation(1,
            loads.map { ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(it)) } +
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            emptyList(), emptyList()))

    private fun owner(method: Method) = ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;",
        emptyList(), null, emptySet(), emptyList(), listOf(method))

    private fun takes(strings: List<String>, method: Method): Boolean =
        object : Fingerprint(strings = strings.map { string(it, StringComparisonType.CONTAINS) }) {}
            .takes(method, owner(method))

    @Test
    fun `raw strings compare exactly while an explicit contains filter preserves partial anchors`() {
        val log = method("CommentLongPressFragment@922.loadLongPressItems$2")
        assertFalse(object : Fingerprint(strings = listOf(".loadLongPressItems")) {}.takes(log, owner(log)))
        assertTrue(object : Fingerprint(strings = listOf("CommentLongPressFragment@922.loadLongPressItems$2")) {}
            .takes(log, owner(log)))
        assertTrue(takes(listOf(".loadLongPressItems"), log))
    }

    @Test
    fun `two pieces of one literal don't both match it`() {
        val log = method("CommentLongPressFragment@922.loadLongPressItems$2")
        assertFalse(takes(listOf("CommentLongPressFragment@", ".loadLongPressItems"), log))
        assertTrue(takes(listOf(".loadLongPressItems"), log))
    }

    @Test
    fun `two loads answer two wanted strings in any order`() {
        val both = method("loadLongPressItems", "CommentLongPressFragment@922")
        assertTrue(takes(listOf("CommentLongPressFragment@", "loadLongPressItems"), both))
        assertFalse(takes(listOf("CommentLongPressFragment@", "loadLongPressItems", "report"), both))
    }
}
