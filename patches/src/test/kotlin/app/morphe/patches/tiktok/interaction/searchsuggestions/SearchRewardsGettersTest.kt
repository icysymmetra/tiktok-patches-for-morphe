package app.morphe.patches.tiktok.interaction.searchsuggestions

import app.morphe.Fixtures
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private const val COIN_TASK = "Lcom/ss/android/ugc/aweme/search/df/api/cointask/core/spi/dummyspi/"
private const val INCENTIVE_TASK = "Lcom/ss/android/ugc/aweme/search/df/api/incentivetask/spi/dummyspi/"
private const val COIN_CONTROL = "${COIN_TASK}DummySearchCoinTaskControlService;"

/** Issue #21: every search rewards service getter on every declared host, and the hook put in each. */
class SearchRewardsGettersTest {
    @Test
    fun `each declared host has one getter for each of the eight search rewards services`() {
        val expected = setOf(
            "${COIN_TASK}DummySearchBlankPageCoinTaskService;",
            "${COIN_TASK}DummySearchCoinHintService;",
            "${COIN_TASK}DummySearchCoinTaskBridgeMethodService;",
            COIN_CONTROL,
            "${COIN_TASK}DummySearchCoinViewModelBridgeService;",
            "${COIN_TASK}DummySearchEntranceGuideTaskService;",
            "${INCENTIVE_TASK}DummySearchIncentiveMultiDayService;",
            "${INCENTIVE_TASK}DummySearchIncentiveTaskBridgeService;",
        )
        assertTrue(expected.containsAll(SEARCH_REWARDS_VIEW_PLACEHOLDERS))
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val getters = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .flatMap { it.methods.asSequence() }
                .mapNotNull { method -> searchRewardsGetter(method)?.let { method to it } }
                .toList()

            assertEquals("$version placeholders", expected, getters.map { it.second.placeholder }.toSet())
            assertEquals("$version: one getter each ${getters.map { it.first.definingClass }}",
                expected.size, getters.size)
            for ((method, getter) in getters) {
                // The patch finds them through the classes that load a feature name.
                assertTrue("$version ${method.definingClass}: loads no feature name",
                    method.implementation!!.instructions.any {
                        it.getReference<StringReference>()?.string in SEARCH_REWARDS_FEATURES
                    })
                assertHooked(method, getter)
            }
        }
    }

    @Test
    fun `a getter shaped like the hosts' is found at its feature check`() {
        val getter = searchRewardsGetter(getter("coin", COIN_CONTROL))
        assertEquals(COIN_CONTROL, getter!!.placeholder)
        assertEquals(5, getter.loadedResultIndex)
        assertHooked(getter("coin", COIN_CONTROL), getter)
    }

    @Test
    fun `other features, other placeholders and getters that never ask for the service are left alone`() {
        assertNull("another feature's check", searchRewardsGetter(getter("ecommerce", COIN_CONTROL)))
        assertNull("not a search rewards placeholder",
            searchRewardsGetter(getter("coin", "Lcom/ss/android/ugc/aweme/dummyspi/DummySearchAdapter;")))
        assertNull("never asks pluggableSpi", searchRewardsGetter(getter("coin", COIN_CONTROL, asksForService = false)))
    }

    /** The answer goes through the switch, and TikTok's own branch on it follows unchanged. */
    private fun assertHooked(native: Method, getter: SearchRewardsGetter) {
        val mutable = MutableMethod(native)
        val before = mutable.implementation!!.instructions.toList()
        val loaded = (before[getter.loadedResultIndex] as OneRegisterInstruction).registerA
        assertEquals("${native.definingClass}: the answer is branched on next",
            Opcode.IF_NEZ, before[getter.loadedResultIndex + 1].opcode)
        assertEquals(loaded, (before[getter.loadedResultIndex + 1] as OneRegisterInstruction).registerA)
        assertEquals("${native.definingClass}: not loaded builds the placeholder",
            getter.placeholder, before[getter.loadedResultIndex + 2].getReference<TypeReference>()?.type)

        mutable.askBeforeLoadingSearchRewards(getter)
        val after = mutable.implementation!!.instructions.toList()
        val ask = after[getter.loadedResultIndex + 1]
        assertEquals(Opcode.INVOKE_STATIC_RANGE, ask.opcode)
        assertEquals(loaded, (ask as RegisterRangeInstruction).startRegister)
        assertEquals(1, ask.registerCount)
        val reference = ask.getReference<MethodReference>()!!
        assertEquals("Lapp/morphe/extension/tiktok/search/SearchSuggestions;->filterRewardsLoaded(Z)Z",
            "${reference.definingClass}->${reference.name}(${reference.parameterTypes.joinToString("")})${reference.returnType}")
        assertEquals(Opcode.MOVE_RESULT, after[getter.loadedResultIndex + 2].opcode)
        assertEquals(loaded, (after[getter.loadedResultIndex + 2] as OneRegisterInstruction).registerA)
        assertEquals(before.size + 2, after.size)
        before.forEachIndexed { index, instruction ->
            assertSame(instruction, after[if (index <= getter.loadedResultIndex) index else index + 2])
        }
    }

    private fun getter(feature: String, placeholder: String, asksForService: Boolean = true): Method {
        val mutable = MutableMethod(ImmutableMethod(
            "LX/0AO4;", "invoke", emptyList(), "Ljava/lang/Object;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
            null, null, ImmutableMethodImplementation(9, emptyList(), null, null),
        ))
        val real = if (asksForService) {
            """
                const-class v2, Lcom/ss/android/ugc/aweme/search/df/api/cointask/core/spi/ISearchCoinTaskControlService;
                const/4 v3, 0x0
                const/4 v4, 0x0
                const/4 v5, 0x0
                const/16 v6, 0xe
                const/4 v7, 0x0
                invoke-static/range {v2 .. v7}, Lcom/ss/android/ugc/aweme/framework/services/PluggableExtentionKt;->pluggableSpi${'$'}default(Ljava/lang/Class;ZZZILjava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
            """
        } else {
            "const/4 v0, 0x0"
        }
        mutable.addInstructionsWithLabels(
            0,
            """
                sget-object v2, LX/08FU;->LIZIZ:LX/08FU;
                const-string v1, "$feature"
                const/4 v3, 0x0
                const/4 v0, 0x0
                invoke-virtual {v2, v0, v1, v3}, LX/08FU;->LJFF(Landroidx/fragment/app/Fragment;Ljava/lang/String;Z)Z
                move-result v0
                if-nez v0, :real
                new-instance v0, $placeholder
                invoke-direct {v0}, $placeholder-><init>()V
                return-object v0
                :real
                $real
                return-object v0
            """,
        )
        return mutable
    }
}
