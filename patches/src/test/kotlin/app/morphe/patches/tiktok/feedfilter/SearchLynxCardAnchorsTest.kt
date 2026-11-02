package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Hide mini dramas finds TikTok's Short Drama block in search, held to the fixtures with the
 * patch's own fingerprints.
 *
 * <p>The block is a Lynx card TikTok builds from a streamed chunk's patches and slots into the Top
 * results adapter, so it never passes the result list the other search filters read. The adapter's
 * bind hands each such card to one of two holders, the ten-parameter one or DynamicViewHolder,
 * depending on the card's feed type and a server list (review #7 found the second one unhooked).
 * Both hooks pass (holder, fragment, patch), which holds while each bind is an instance method of
 * a RecyclerView view holder whose first two parameters are the fragment and the patch. The other
 * lists' Lynx cell keeps its real name and one bind.
 */
class SearchLynxCardAnchorsTest {
    private val fragment = "Landroidx/fragment/app/Fragment;"
    private val holderBinds = listOf(SearchLynxHolderBindFingerprint, SearchDynamicHolderBindFingerprint)

    @Test
    fun `each declared build's results adapter binds every Lynx card through a hooked holder`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val binds = holderBinds.map { fingerprint -> build.single(fingerprint) }
            for ((holder, bind) in binds) {
                assertFalse("${bind.name} is static, so p0 is not the holder", AccessFlags.STATIC.isSet(bind.accessFlags))
                assertTrue("${holder.type} is not a RecyclerView view holder", build.isViewHolder(holder.type))
                assertEquals("the fragment is not ${holder.type}'s first parameter", fragment, bind.parameterTypes[0].toString())
                assertEquals("the patch is not ${holder.type}'s second parameter", DYNAMIC_PATCH_DESCRIPTOR, bind.parameterTypes[1].toString())
            }
            build.single(SearchLynxCardBindFingerprint)

            // The results adapter's bind is the ten-parameter holder bind's one caller. Every view
            // holder method it calls with a DynamicPatch has to be one of the two hooked binds.
            val tenParameter = binds.first().second
            val adapters = build.methods.filter { (_, method) -> method.calls(tenParameter) }.toList()
            assertEquals("callers of the ten-parameter holder bind: ${adapters.map { it.second.name }}", 1, adapters.size)
            val hooked = binds.map { (_, bind) -> key(bind.definingClass, bind.name, bind.parameterTypes) }.toSet()
            val lynxCalls = adapters.single().second.implementation!!.instructions.mapNotNull { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.takeIf { reference ->
                    reference.parameterTypes.any { it.toString() == DYNAMIC_PATCH_DESCRIPTOR } && build.isViewHolder(reference.definingClass)
                }
            }
            assertTrue("the results adapter binds no Lynx card at all", lynxCalls.isNotEmpty())
            lynxCalls.forEach { call ->
                assertTrue(
                    "the results adapter binds a Lynx card through an unhooked holder: ${call.definingClass}->${call.name}",
                    key(call.definingClass, call.name, call.parameterTypes) in hooked,
                )
            }
            assertEquals("both holder binds are called by the adapter", hooked, lynxCalls.map { key(it.definingClass, it.name, it.parameterTypes) }.toSet())
        }
    }

    /** The patch hooks every bind this test holds, each at index 0 with the same (holder, fragment, patch) call. */
    @Test
    fun `the feed filter hooks both holder binds and the cell`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/feedfilter/FeedFilterPatch.kt").readText()
        for (fingerprint in holderBinds) {
            val name = fingerprint.javaClass.simpleName
            assertTrue("the feed filter does not hook $name", Regex("""lynxBind\($name\)\?\.addInstructions\(0, holderBound\)""").containsMatchIn(source))
        }
        assertTrue("the feed filter does not hook the Lynx cell", source.contains("lynxBind(SearchLynxCardBindFingerprint)?.addInstructions("))
        assertTrue("holderBound is not the (holder, fragment, patch) call",
            Regex("""val holderBound = "invoke-static/range \{p0 \.\. p2\}, .*->onHolderBound\(""").containsMatchIn(source))
    }

    /** A second method taking a fingerprint would leave the patcher's pick to class order. */
    @Test
    fun `no fixture gives a Lynx fingerprint two methods`() {
        for (apk in Fixtures.apks()) {
            val build = Build(apk)
            for (fingerprint in holderBinds + SearchLynxCardBindFingerprint) {
                val taken = build.methods.filter { (classDef, method) -> fingerprint.takes(method, classDef) }.take(2).count()
                assertTrue("${apk.name}: ${fingerprint.javaClass.simpleName} takes $taken methods", taken <= 1)
            }
        }
    }

    private fun key(owner: String, name: String, parameters: List<CharSequence>) =
        owner + "->" + name + parameters.joinToString(",", "(", ")")

    private fun Method.calls(target: Method): Boolean = implementation?.instructions?.any { instruction ->
        ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let {
            it.definingClass == target.definingClass && it.name == target.name &&
                it.parameterTypes.map(Any::toString) == target.parameterTypes.map(Any::toString)
        } == true
    } == true

    /** One fixture's classes by type; methods are walked on each ask, never held (the dex has millions). */
    private class Build(apk: File) {
        val byType = HashMap<String, ClassDef>()

        init {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
            }
        }

        val methods: Sequence<Pair<ClassDef, Method>>
            get() = byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }

        fun single(fingerprint: Fingerprint): Pair<ClassDef, Method> {
            val taken = methods.filter { (classDef, method) -> fingerprint.takes(method, classDef) }.toList()
            assertEquals("${fingerprint.javaClass.simpleName} takes: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
            return taken.single()
        }

        fun isViewHolder(type: String): Boolean {
            var current: String? = type
            var depth = 0
            while (current != null && depth++ < 20) {
                if (current == "Landroidx/recyclerview/widget/RecyclerView\$ViewHolder;") return true
                current = byType[current]?.superclass
            }
            return false
        }
    }
}
