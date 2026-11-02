/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.interaction.searchsuggestions

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.callThroughLocals
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patches.tiktok.shared.objectIn
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.util.getReference
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/search/SearchSuggestions;"
private const val SUGGEST_WORDS_VIEW_MODEL =
    "Lcom/ss/android/ugc/aweme/search/pages/middlepage/core/viewmodel/SuggestWordsViewModel;"
private const val SEARCH_MIDDLE_MONITOR =
    "Lcom/ss/android/ugc/aweme/search/performance/metrics/SearchMiddleMonitor;"

internal const val SEARCH_REWARDS_SERVICE =
    "Lcom/ss/android/ugc/aweme/search/df/api/incentivetask/spi/ISearchIncentiveTaskBridgeService;"

/**
 * The one door to TikTok's search rewards (issue #21): the points banner under the search box
 * and the coin counter floating over results, served in some regions. Every search page reaches
 * the service through a single static accessor and checks the answer for null before using it,
 * since the feature ships as an optional module, so null is the answer an account without
 * rewards already gets. The accessor's class is renamed on every build (0OHX, 0OS5, 0OID, 0HgS,
 * 0IgL); its return type is a real name and nothing else returns it from a static no-argument
 * method.
 */
internal fun isSearchRewardsAccessor(method: Method): Boolean =
    AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() &&
        method.returnType == SEARCH_REWARDS_SERVICE && method.implementation != null

private object SearchRewardsServiceFingerprint : Fingerprint(
    returnType = SEARCH_REWARDS_SERVICE,
    parameters = listOf(),
    custom = { method, _ -> isSearchRewardsAccessor(method) },
)

/** Whether the method calls the named method on the search page's own timing monitor. */
private fun Method.marks(monitorMethod: String) =
    implementation?.instructions?.any { instruction ->
        instruction.getReference<MethodReference>()?.let { reference ->
            reference.definingClass == SEARCH_MIDDLE_MONITOR && reference.name == monitorMethod
        } == true
    } == true

/**
 * Whether the search page preloads its suggested words at all. A real name on a real class, and
 * a config gate the app already expects to be false, so refusing here is a path it handles.
 */
private object IntermediatePreloadEnableFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/search/pages/middlepage/core/utils/IntermediatePreload;",
    name = "enable",
    parameters = listOf("Landroid/os/Bundle;"),
    returnType = "Z",
)

/**
 * The request for suggested words. Its name is obfuscated but it opens by starting the search
 * page's own timer, `SearchMiddleMonitor.recommendRequestStart`, which is a real name on a real
 * class and says exactly what the method is for. Nothing else on the view model calls it.
 */
private object SuggestWordsRequestFingerprint : Fingerprint(
    definingClass = SUGGEST_WORDS_VIEW_MODEL,
    returnType = "V",
    custom = { method, _ -> method.marks("recommendRequestStart") },
)

/**
 * The hand-off to the screen. Same idea: it opens with
 * `SearchMiddleMonitor.recommendPostValueStart` and then posts to the view model's live data.
 * This covers ViewModel responses; the separate Lynx cache and visibility inputs are handled
 * below because they can populate the page without publishing through this LiveData.
 */
private object SuggestWordsPublishFingerprint : Fingerprint(
    definingClass = SUGGEST_WORDS_VIEW_MODEL,
    returnType = "V",
    custom = { method, _ -> method.marks("recommendPostValueStart") },
)

/** The AB key the first-screen gate reads, which its own class holds in the clear. */
private const val FIRST_SCREEN_KEY = "search_lynx_recommend_first_screen"

/**
 * The gate the first-screen cache consults, both from its raw JSON reader and its TemplateData
 * one, independently of the history gate and SuggestWordsViewModel.
 *
 * <p>Its class was written here as `LX/0OiL;`, which is `LX/0Or3;` on 46.7.3 and `LX/0Ol1;` on
 * 46.8.3. Unlike most of TikTok's AB gates it reads its key in its own `<clinit>` rather than
 * behind a lambda, and exactly one `<clinit>` in the app holds that key on all three builds.
 */
private object LynxFirstScreenCacheFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = emptyList(),
    returnType = "Z",
    custom = { _, classDef ->
        classDef.methods.any { candidate ->
            candidate.name == "<clinit>" &&
                candidate.implementation?.instructions?.any {
                    it.getReference<StringReference>()?.string == FIRST_SCREEN_KEY
                } == true
        }
    },
)

/**
 * The Lynx parameter helper, `LX/0Bav;` on 46.2.3 and `LX/09iY;` and `LX/09eO;` since.
 *
 * <p>Four statics together name it: the reader this hooks, the two that answer with a name and a
 * map, and one that writes a value into a gson object. That last signature is what makes the set
 * specific, and exactly one class in the app carries all four on each of the three builds.
 */
private val LYNX_PARAMETER_SHAPES = setOf(
    "Ljava/lang/String;[]",
    "Ljava/util/Map;[]",
    "V[Lcom/google/gson/n;, Ljava/lang/Object;, Ljava/lang/String;]",
)

private object LynxParameterFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = listOf("Ljava/lang/String;"),
    returnType = "Ljava/lang/Object;",
    custom = { _, classDef ->
        val statics = classDef.methods
            .filter { AccessFlags.STATIC.value and it.accessFlags != 0 }
            .mapTo(HashSet()) { "${it.returnType}${it.parameterTypes}" }
        statics.containsAll(LYNX_PARAMETER_SHAPES)
    },
)

/**
 * Whether the search page draws "You may like" itself instead of through the Lynx page the
 * hooks above cover. 47.1.4 turned it on for some accounts, and its own list has a Refresh
 * button that none of the Lynx hooks reach. The AB key defaults to false, so refusing keeps
 * TikTok on its stock path. The class is renamed per build (0HAN on 47.1.4, 04wz in PineDrama),
 * and one public static no-argument boolean reads the key in each.
 */
internal const val NATIVE_GUESS_KEY = "ttk_guess_use_native"

internal fun isNativeGuessSearchGate(method: Method): Boolean =
    AccessFlags.PUBLIC.isSet(method.accessFlags) && AccessFlags.STATIC.isSet(method.accessFlags) &&
        method.parameterTypes.isEmpty() && method.returnType == "Z" &&
        method.implementation?.instructions?.any {
            it.getReference<StringReference>()?.string == NATIVE_GUESS_KEY
        } == true

private object NativeGuessSearchFingerprint : Fingerprint(
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    parameters = emptyList(),
    returnType = "Z",
    strings = listOf(NATIVE_GUESS_KEY).map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> isNativeGuessSearchGate(method) },
)

private object SearchReentryFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/aweme/search/middle/DynamicSingleIntermediateFragmentNew;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    strings = listOf("show_suggest_search_words", "searchTransferEnter").map { string(it, StringComparisonType.CONTAINS) },
)

private const val SUGGEST_WORD = "Lcom/ss/android/ugc/aweme/discover/model/suggest/Word;"

/**
 * The observer that writes TikTok's trending word into the search box placeholder ("costco
 * finds") and stores it as what an empty submit searches for. It sits in a renamed class on each
 * build: an instance `onChanged` in `0EcI` on 47.4.3, and a static `onChanged$42` that PineDrama
 * merged into a shared observer class. What names it is its content: it is the one method in the
 * app that reads `Word.checkAndResetIsFromRefresh` and calls a `setSearchHint(String)`.
 */
internal fun isSearchHintObserver(method: Method): Boolean =
    method.returnType == "V" && method.implementation?.instructions?.let { instructions ->
        instructions.any {
            it.getReference<MethodReference>()?.let { reference ->
                reference.definingClass == SUGGEST_WORD && reference.name == "checkAndResetIsFromRefresh"
            } == true
        } && instructions.any {
            it.getReference<MethodReference>()?.let { reference ->
                reference.name == "setSearchHint" &&
                    reference.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
            } == true
        }
    } == true

private object SearchHintObserverFingerprint : Fingerprint(
    returnType = "V",
    custom = { method, _ -> isSearchHintObserver(method) },
)

private const val SEARCH_FRAGMENT = "Lcom/ss/android/ugc/aweme/search/middle/BaseDiscoverAndSearchFragmentNew;"

/**
 * Where the search page writes a word into the box placeholder and keeps it as the `Word` an
 * empty submit searches for. Every word reaches the box through here: the one the feed hands the
 * page as it opens (the observer above only fills the box when that is empty) and the ones the
 * page's own observers pass in. `iZ` on 47.4.3, `Yw` in PineDrama. It is the one method in the
 * fragment that takes one argument, builds a `Word` and sets the box hint.
 */
internal fun isSearchHintApplier(method: Method): Boolean =
    method.definingClass == SEARCH_FRAGMENT && method.returnType == "V" && method.parameterTypes.size == 1 &&
        method.implementation?.instructions?.let { instructions ->
            instructions.any {
                it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == SUGGEST_WORD
            } && instructions.any {
                it.getReference<MethodReference>()?.let { reference ->
                    reference.definingClass == "Landroid/widget/TextView;" && reference.name == "setHint"
                } == true
            }
        } == true

/**
 * TikTok's own placeholder ("Search"), which the page passes to the method above whenever it has
 * no word. `IX` on 47.4.3, `ev` in PineDrama: the fragment's one no-argument string method that
 * reads the shop's `defaultHintWord` before falling back to the string resource.
 */
internal fun isDefaultSearchHint(method: Method): Boolean =
    method.definingClass == SEARCH_FRAGMENT && method.returnType == "Ljava/lang/String;" &&
        method.parameterTypes.isEmpty() &&
        method.implementation?.instructions?.any {
            it.getReference<FieldReference>()?.name == "defaultHintWord"
        } == true

private object SearchHintApplierFingerprint : Fingerprint(
    definingClass = SEARCH_FRAGMENT,
    returnType = "V",
    custom = { method, _ -> isSearchHintApplier(method) },
)

private object DefaultSearchHintFingerprint : Fingerprint(
    definingClass = SEARCH_FRAGMENT,
    returnType = "Ljava/lang/String;",
    parameters = emptyList(),
    custom = { method, _ -> isDefaultSearchHint(method) },
)

/**
 * Stops the search page filling itself with things to search for. TikTok asks for suggested
 * words before you have typed anything and shows them as "You might be interested in" and
 * "Popular searches"; with this on the page does not ask and does not show. What you searched
 * for before is a different list and is left alone.
 */
@Suppress("unused")
val hideSearchSuggestionsPatch = bytecodePatch(
    name = "Hide search suggestions",
    description = "Hides the searches TikTok suggests before you type, and can hide the search rewards banner. Your own search history stays. Starts off. Turn it on in Metra settings > App behavior.",
) {
    category("Search")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        // Resolved before anything is written, so a build without it leaves the patch unapplied
        // rather than half applied.
        val rewards = SearchRewardsServiceFingerprint.method
        val rewardsGetters = searchRewardsGetters()
        val nativeGuess = NativeGuessSearchFingerprint.method
        val searchHint = SearchHintObserverFingerprint.method
        val hintApplier = SearchHintApplierFingerprint.method
        val defaultHint = DefaultSearchHintFingerprint.method
        val hintModel = hintApplier.parameterTypes.single().toString()
        if (classDefBy(hintModel).methods.none {
                it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;")
            }
        ) {
            throw PatchException("Hide search suggestions: the search hint $hintModel has no one-word constructor.")
        }
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableHideSearchSuggestions()V",
        )
        rewards.guardAtEntry(
            "Hide search suggestions",
            "invoke-static {}, $EXTENSION->shouldHideRewards()Z",
            """
                const/4 v0, 0x0
                return-object v0
            """,
        )
        // The accessor above reaches the banner's service only. The coin and the other rewards
        // services come straight from their getters, so those take the not-downloaded branch.
        rewardsGetters.forEach { (method, getter) -> method.askBeforeLoadingSearchRewards(getter) }

        IntermediatePreloadEnableFingerprint.method.guard("const/4 v0, 0x0\n                    return v0")
        nativeGuess.guard("const/4 v0, 0x0\n                    return v0")
        SuggestWordsRequestFingerprint.method.guard("return-void")
        SuggestWordsPublishFingerprint.method.guard("return-void")
        // The box keeps TikTok's own "Search" placeholder, and an empty submit has no word to run.
        searchHint.guard("return-void")
        hintApplier.keepDefaultSearchHint(defaultHint)
        LynxFirstScreenCacheFingerprint.method.hookFirstScreenCache()
        LynxParameterFingerprint.method.hookInitialLynxParameter()
        SearchReentryFingerprint.method.hookReentryVisibility()
    }
}

internal fun MutableMethod.hookFirstScreenCache() {
    implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }
        .map { it.index }.asReversed().forEach { index ->
            val register = getInstruction<OneRegisterInstruction>(index).registerA
            // Native branches target this return directly. Replacing it keeps their target
            // on the hook; inserting before it would leave those branches skipping the call.
            replaceInstruction(
                index,
                "invoke-static/range {v$register .. v$register}, $EXTENSION->filterCachedSuggestions(Z)Z",
            )
            addInstructions(index + 1, "move-result v$register\nreturn v$register")
        }
}

internal fun MutableMethod.hookInitialLynxParameter() {
    if (!AccessFlags.STATIC.isSet(accessFlags)) {
        throw PatchException("Hide search suggestions: the Lynx parameter reader is not static.")
    }
    implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index }.asReversed().forEach { index ->
            val register = getInstruction<OneRegisterInstruction>(index).registerA
            val call = callThroughLocals(
                "Hide search suggestions", "invoke-static",
                "$EXTENSION->filterLynxParameter(Ljava/lang/String;Ljava/lang/Object;)Ljava/lang/Object;",
                objectIn("p0"), objectIn("v$register"),
            )
            addInstructionsAtControlFlowLabel(index, "$call\nmove-result-object v$register")
        }
}

internal fun MutableMethod.hookReentryVisibility() {
    val instructions = implementation!!.instructions.toList()
    val keyIndex = instructions.indices.singleOrNull {
        instructions[it].getReference<StringReference>()?.string == "show_suggest_search_words"
    } ?: throw PatchException("Hide search suggestions: search reentry has no unique visibility key.")
    val keyRegister = (instructions[keyIndex] as OneRegisterInstruction).registerA
    val read = instructions.getOrNull(keyIndex + 1)
    val reference = read?.getReference<MethodReference>()
    val result = instructions.getOrNull(keyIndex + 2)
    // The settings reader was named here as LX/0BZD;->LJIIJJI, both of which R8 assigns. Its
    // signature is what identifies it at this call site: two ints, the key this method just
    // loaded, a boolean, and an int back.
    if (read !is FiveRegisterInstruction || read.registerCount != 5 || read.registerF != keyRegister ||
        reference == null || reference.returnType != "I" ||
        reference.parameterTypes.map(CharSequence::toString) !=
        listOf("I", "I", "Ljava/lang/String;", "Z") ||
        result?.opcode != Opcode.MOVE_RESULT
    ) {
        throw PatchException("Hide search suggestions: search reentry visibility reader changed.")
    }
    val register = (result as OneRegisterInstruction).registerA
    // This is in the live native frame. A one-register range call needs no scratch register.
    addInstructions(
        keyIndex + 3,
        "invoke-static/range {v$register .. v$register}, $EXTENSION->filterReentryVisibility(I)I\n" +
            "move-result v$register",
    )
}

/**
 * Swaps the word the page hands the box for TikTok's own placeholder, the same hint the page
 * builds itself when it has no word, so the empty submit has nothing of TikTok's to search either.
 */
internal fun MutableMethod.keepDefaultSearchHint(defaultHint: Method) {
    requireLocals("Hide search suggestions", 2)
    val hintModel = parameterTypes.single().toString()
    val readDefault = if (AccessFlags.PRIVATE.isSet(defaultHint.accessFlags)) "invoke-direct/range" else "invoke-virtual/range"
    addInstructionsWithLabels(
        0,
        """
            invoke-static {}, $EXTENSION->shouldHide()Z
            move-result v0
            if-eqz v0, :morphe_keep_search_hint
            $readDefault {p0 .. p0}, $SEARCH_FRAGMENT->${defaultHint.name}()Ljava/lang/String;
            move-result-object v1
            new-instance v0, $hintModel
            invoke-direct/range {v0 .. v1}, $hintModel-><init>(Ljava/lang/String;)V
            move-object/from16 p1, v0
        """,
        ExternalLabel("morphe_keep_search_hint", getInstruction(0)),
    )
}

/** Leaves the method before it does anything, when the extension says the feature is on. */
private fun MutableMethod.guard(returnInstruction: String) {
    if (implementation!!.registerCount - numberOfParameterRegisters < 1) {
        throw PatchException("Hide search suggestions: $name has no free local register.")
    }
    guardAtEntry(
        "enable",
        "invoke-static {}, $EXTENSION->shouldHide()Z",
        "$returnInstruction",
    )
}
