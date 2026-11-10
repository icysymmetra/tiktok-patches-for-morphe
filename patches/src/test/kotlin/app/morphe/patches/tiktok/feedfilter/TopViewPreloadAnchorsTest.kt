package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seam the Feed filter empties the cold-start TopView through, held to what the patch
 * assumes on every retained fixture: FeedApi (a real name) carries two methods returning a
 * FeedItemList from one parameter, the thin request and the fetch with its post-processing,
 * and exactly one of them hands a List to the splash ad service (the TopView preload handoff)
 * by one interface call whose list register a short call can name. The methods, the parameter
 * type and the service's method name are R8's and differ per build, which is why the fetch is
 * pinned by the handoff it makes and the handoff by the service's real name and parameter.
 */
class TopViewPreloadAnchorsTest {
    private val feedApi = "Lcom/ss/android/ugc/aweme/feed/api/FeedApi;"
    private val feedItemList = "Lcom/ss/android/ugc/aweme/feed/model/FeedItemList;"

    @Test
    fun `every retained fixture has one feed fetch with one TopView preload handoff`() {
        val apks = Fixtures.apks()
        assertTrue("fixtures: ${apks.map { it.name }}", apks.containsAll(Fixtures.declared()))
        apks.forEach { apk ->
            val app = load(apk)
            val classDef = app[feedApi] ?: error("${apk.name}: no $feedApi")
            val fetches = classDef.methods.filter { method ->
                method.returnType == feedItemList && method.parameterTypes.size == 1 &&
                    method.implementation?.instructions?.any { it.isTopViewPreloadHandoff() } == true
            }
            assertEquals("${apk.name}: feed fetches making the handoff ${fetches.map { it.name }}", 1, fetches.size)
            val handoffs = fetches.single().implementation!!.instructions.filter { it.isTopViewPreloadHandoff() }
            assertEquals("${apk.name}: TopView preload handoffs in the fetch", 1, handoffs.size)
            val handoff = handoffs.single()
            val listRegister = when (handoff) {
                is FiveRegisterInstruction -> handoff.registerD
                is RegisterRangeInstruction -> handoff.startRegister + 1
                else -> error("${apk.name}: unexpected handoff shape ${handoff.opcode}")
            }
            assertTrue("${apk.name}: the list register v$listRegister fits a short call", listRegister < 16)
            // The read the route is counted at: once, into a register a short call can name.
            val reads = fetches.single().implementation!!.instructions.filter { it.isTopViewPreloadRead() }
            assertEquals("${apk.name}: reads of preloadAds in the fetch", 1, reads.size)
            val readRegister = (reads.single() as TwoRegisterInstruction).registerA
            assertTrue("${apk.name}: the preload list register v$readRegister fits a short call", readRegister < 16)
            // The emptied list is written back with an iput-object, whose registers are four bits.
            val holderRegister = (reads.single() as TwoRegisterInstruction).registerB
            assertTrue("${apk.name}: the FeedItemList register v$holderRegister fits an iput-object", holderRegister < 16)
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
}
