/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.searchsuggestions

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.addInstructions
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/search/SearchSuggestions;"
private const val PLUGGABLE_SPI = "Lcom/ss/android/ugc/aweme/framework/services/PluggableExtentionKt;"
private val FEATURE_CHECK_PARAMETERS = listOf("Landroidx/fragment/app/Fragment;", "Ljava/lang/String;", "Z")

/** The two downloadable features the search rewards services live in. */
internal val SEARCH_REWARDS_FEATURES = setOf("coin", "incentive")

private val SEARCH_REWARDS_PLACEHOLDER =
    Regex("^Lcom/ss/android/ugc/aweme/search/df/api/(cointask|incentivetask)/.+/dummyspi/Dummy\\w+;$")

/** The placeholders for the services that put the coin and the banner on the search pages. */
internal val SEARCH_REWARDS_VIEW_PLACEHOLDERS = setOf(
    "Lcom/ss/android/ugc/aweme/search/df/api/cointask/core/spi/dummyspi/DummySearchCoinTaskControlService;",
    "Lcom/ss/android/ugc/aweme/search/df/api/incentivetask/spi/dummyspi/DummySearchIncentiveTaskBridgeService;",
)

/** A search rewards service getter: the placeholder it falls back to and where its feature check's answer lands. */
internal data class SearchRewardsGetter(val placeholder: String, val loadedResultIndex: Int)

/**
 * TikTok downloads its search rewards code (the points banner, the coin and their tasks) only
 * where the program runs, and the base APK carries a real-named placeholder for each service.
 * Each service comes from a lazy getter of one shape: it asks whether the "coin" or "incentive"
 * feature is loaded and returns the placeholder if not, then asks pluggableSpi for the real
 * service and falls back to the placeholder again. The coin's service never passes through the
 * static accessor [isSearchRewardsAccessor] covers, which is why the coin stayed (#21). Returns
 * where the first answer lands, or null for any other method.
 */
internal fun searchRewardsGetter(method: Method): SearchRewardsGetter? {
    if (method.returnType != "Ljava/lang/Object;") return null
    val instructions = method.implementation?.instructions?.toList() ?: return null
    val placeholder = instructions.mapNotNull { instruction ->
        instruction.takeIf { it.opcode == Opcode.NEW_INSTANCE }?.getReference<TypeReference>()?.type
            ?.takeIf(SEARCH_REWARDS_PLACEHOLDER::matches)
    }.distinct().singleOrNull() ?: return null
    val asksForRealService = instructions.any { instruction ->
        instruction.getReference<MethodReference>()?.let {
            it.definingClass == PLUGGABLE_SPI && it.name.startsWith("pluggableSpi")
        } == true
    }
    if (!asksForRealService) return null

    val checkIndex = instructions.indexOfFirst { instruction ->
        instruction.opcode == Opcode.INVOKE_VIRTUAL && instruction.getReference<MethodReference>()?.let {
            it.returnType == "Z" && it.parameterTypes.map(CharSequence::toString) == FEATURE_CHECK_PARAMETERS
        } == true
    }
    if (checkIndex < 0 || instructions.getOrNull(checkIndex + 1)?.opcode != Opcode.MOVE_RESULT) return null
    // The feature's name is the check's String argument, loaded just before it.
    val keyRegister = (instructions[checkIndex] as FiveRegisterInstruction).registerE
    val key = instructions.subList(0, checkIndex).lastOrNull {
        it.opcode == Opcode.CONST_STRING && (it as OneRegisterInstruction).registerA == keyRegister
    }?.getReference<StringReference>()?.string
    if (key !in SEARCH_REWARDS_FEATURES) return null
    return SearchRewardsGetter(placeholder, checkIndex + 1)
}

/**
 * Every search rewards service getter, found through the classes that load a feature name. A
 * build where the coin's or the banner's getter can't be found fails here, before anything is
 * written.
 */
internal fun BytecodePatchContext.searchRewardsGetters(): List<Pair<MutableMethod, SearchRewardsGetter>> {
    val found = SEARCH_REWARDS_FEATURES
        .flatMap { feature -> classDefByStrings(feature) }
        .distinctBy { it.type }
        .flatMap { classDef ->
            classDef.methods.mapNotNull { method ->
                searchRewardsGetter(method)?.let { Triple(classDef, method, it) }
            }
        }
    val missing = SEARCH_REWARDS_VIEW_PLACEHOLDERS - found.map { it.third.placeholder }.toSet()
    if (missing.isNotEmpty()) {
        throw PatchException("Hide search suggestions: no rewards service getter falls back to ${missing.joinToString()}.")
    }
    return found.map { (classDef, method, getter) ->
        mutableClassDefBy(classDef).findMutableMethodOf(method) to getter
    }
}

/** Passes the getter's "is it loaded" answer through the switch, so hidden rewards read as not downloaded. */
internal fun MutableMethod.askBeforeLoadingSearchRewards(getter: SearchRewardsGetter) {
    val loaded = getInstruction<OneRegisterInstruction>(getter.loadedResultIndex).registerA
    addInstructions(
        getter.loadedResultIndex + 1,
        """
            invoke-static/range {v$loaded .. v$loaded}, $EXTENSION->filterRewardsLoaded(Z)Z
            move-result v$loaded
        """,
    )
}
