package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.patches.tiktok.misc.navigation.writes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The seams the Feed filter takes the Ask Tako surfaces off the search results page through, held
 * to what the patch assumes on every retained fixture: the served tab list response keeps its name,
 * its two list getters keep theirs, each opens with exactly one read of the tabList field and
 * nothing else on the response reads that field, the tab info keeps the key and name getters the
 * extension reads, and the fragment that inflates the strip keeps the onViewCreated the pill is
 * hidden from. On each declared build the "People also ask Tako" card in the results keeps the
 * onViewCreated it is hidden from.
 */
class SearchTabAnchorsTest {
    private val tabList = "Lcom/ss/android/ugc/aweme/search/pages/result/common/tabs/core/model/SearchDynamicTabList;"
    private val tabInfo = "Lcom/ss/android/ugc/aweme/search/pages/result/common/tabs/core/model/SearchDynamicTabInfo;"
    private val containerFragment = "Lcom/ss/android/ugc/aweme/search/pages/core/ui/fragment/SearchContainerFragment;"
    private val takoSugCard = "Lcom/ss/android/ugc/aweme/search/pages/result/topsearch/tako/SearchTakoSugCardAssem;"

    @Test
    fun `every retained fixture serves the search tab list through the two getters the patch filters`() {
        val apks = Fixtures.apks()
        assertTrue("fixtures: ${apks.map { it.name }}", apks.containsAll(Fixtures.declared()))
        apks.forEach { apk ->
            val app = load(apk)
            val list = app[tabList] ?: error("${apk.name}: no $tabList")
            listOf("getTabList", "getSearchTabList").forEach { getter ->
                val methods = list.methods.filter {
                    it.name == getter && it.parameterTypes.isEmpty() && it.returnType == "Ljava/util/List;" && it.implementation != null
                }
                assertEquals("${apk.name}: $getter on the tab list response", 1, methods.size)
                val reads = methods.single().implementation!!.instructions.filter { it.isSearchTabListRead() }
                assertEquals("${apk.name}: reads of tabList in $getter", 1, reads.size)
                val register = (reads.single() as TwoRegisterInstruction).registerA
                assertTrue("${apk.name}: $getter reads the list into v$register, a low register", register < 16)
            }
            // A third reader on the response (a copy or a component of a data class) would hand
            // the strip the list past the filter.
            val readers = list.methods
                .filter { method -> method.implementation?.instructions?.any { it.isSearchTabListRead() } == true }
                .map { it.name }
                .sorted()
            assertEquals("${apk.name}: readers of tabList on the response", listOf("getSearchTabList", "getTabList"), readers)
            val info = app[tabInfo] ?: error("${apk.name}: no $tabInfo")
            listOf("getTabKey", "getTabName").forEach { getter ->
                assertEquals(
                    "${apk.name}: $getter on the tab info",
                    1,
                    info.methods.count { it.name == getter && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;" },
                )
            }
        }
    }

    @Test
    fun `every retained fixture builds the strip in the container fragment's onViewCreated`() {
        val apks = Fixtures.apks()
        assertTrue("fixtures: ${apks.map { it.name }}", apks.containsAll(Fixtures.declared()))
        apks.forEach { apk ->
            val app = load(apk)
            val fragment = app[containerFragment] ?: error("${apk.name}: no $containerFragment")
            val created = fragment.methods.filter {
                it.name == "onViewCreated" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;", "Landroid/os/Bundle;") &&
                    it.returnType == "V" &&
                    it.implementation != null
            }
            assertEquals("${apk.name}: onViewCreated(View, Bundle) on the search container fragment", 1, created.size)
            assertTrue(
                "${apk.name}: onViewCreated returns somewhere the pill hook can sit",
                created.single().implementation!!.instructions.any { it.opcode == Opcode.RETURN_VOID },
            )
        }
    }

    @Test
    fun `every declared build lays out the people also ask Tako card in an onViewCreated the hook can return through`() {
        Fixtures.forEachDeclared { apk ->
            val card = load(apk)[takoSugCard] ?: error("${apk.name}: no $takoSugCard")
            val created = card.methods.filter {
                it.name == "onViewCreated" &&
                    it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;") &&
                    it.returnType == "V" &&
                    it.implementation != null
            }
            assertEquals("${apk.name}: onViewCreated(View) on the Tako questions card", 1, created.size)
            val implementation = created.single().implementation!!
            assertTrue(
                "${apk.name}: onViewCreated returns somewhere the card hook can sit",
                implementation.instructions.any { it.opcode == Opcode.RETURN_VOID },
            )
            // The hook hands p1 to the extension at each return, so nothing may write over it.
            val view = implementation.registerCount - 1
            assertTrue(
                "${apk.name}: onViewCreated keeps the view in p1 (v$view) to its returns",
                implementation.instructions.none { it.writes(view) },
            )
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
