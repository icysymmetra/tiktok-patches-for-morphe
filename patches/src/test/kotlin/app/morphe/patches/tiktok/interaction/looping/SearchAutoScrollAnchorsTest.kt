package app.morphe.patches.tiktok.interaction.looping

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Auto-advance in search results rests on, held to each declared build: exactly one
 * no-argument boolean gate and one no-argument search service method answering a state load the exact
 * search_auto_scroll key, each reads it once through an int settings call, and nothing else
 * that matches those fingerprints does. The longer keys that begin the same way don't count.
 */
class SearchAutoScrollAnchorsTest {
    @Test
    fun `search reads its auto scroll flag once in the gate and once in the service`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val readers = classesOf(apk).flatMap { owner -> owner.methods.map { owner to it } }
                .filter { (_, method) -> method.parameterTypes.isEmpty() && method.loadsExactKey() }

            val gates = readers.filter { (_, method) -> method.returnType == "Z" }
            assertEquals("$version: gates ${gates.map { it.second.name }}", 1, gates.size)
            assertEquals("$version: reads in the gate", listOf(1), gates.map { searchFlagReads(it.second).size })

            val service = readers.filter { (_, method) -> method.returnType.startsWith("L") && method.answersAState() }
            assertEquals("$version: service readers ${service.map { "${it.first.type}->${it.second.name}" }}",
                listOf(SERVICE), service.map { it.first.type })
            assertEquals("$version: reads in the service", listOf(1), service.map { searchFlagReads(it.second).size })
        }
    }

    /**
     * The host restores search's remembered state from onViewCreated, onResume and
     * onPageResume, and the extension reads the state at each one before that runs.
     */
    @Test
    fun `the component has the page resume the restore watch hooks`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val component = classesOf(apk).single { it.type == COMPONENT }
            val pageResumes = component.methods.filter {
                it.name == "onPageResume" && it.parameterTypes.map(CharSequence::toString) == listOf("I")
            }
            assertEquals("$version: onPageResume(int)", 1, pageResumes.size)
        }
    }

    private companion object {
        const val KEY = "search_auto_scroll"
        // AbsSearchService held it until 47.4.3.
        const val SERVICE = "Lcom/ss/android/ugc/aweme/search/common/communicate/SearchInnerFlowDetailServiceImpl;"
        const val COMPONENT = "Lcom/ss/android/ugc/feed/platform/panel/autoscroll/AutoScrollComponent;"

        fun Method.loadsExactKey() = implementation?.instructions?.any {
            (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
                ((it as ReferenceInstruction).reference as? StringReference)?.string == KEY
        } == true

        fun Method.answersAState() = implementation?.instructions?.any {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)?.name == "AUTO_SCROLL_STATE_STOP"
        } == true

        fun classesOf(apk: java.io.File): List<ClassDef> {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            return container.dexEntryNames.flatMap { entry -> container.getEntry(entry)!!.dexFile.classes }
        }
    }
}
