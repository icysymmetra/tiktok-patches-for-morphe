/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.branding

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.registerForBuildDetails
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.findInstructionIndicesReversedOrThrow
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/misc/BesideStoreApp;"
private const val JSON = "Lorg/json/JSONObject;"
private const val STRING = "Ljava/lang/String;"
private const val WALLPAPER_DATA_PROVIDER = "Lcom/ss/android/ugc/aweme/livewallpaper/WallPaperDataProvider;"

internal const val JSON_PUT = "$JSON->put(Ljava/lang/String;Ljava/lang/Object;)$JSON"
internal const val GET_PACKAGE_NAME = "Landroid/content/Context;->getPackageName()Ljava/lang/String;"

/** The registration header field TikTok's servers read the app's identity from. */
internal const val PACKAGE_KEY = "package"

/** The package the store app installs as, named outright where TikTok's code means itself. */
internal const val STORE_PACKAGE = "com.zhiliaoapp.musically"

/** TikTok Asia's package, which every check for TikTok's own package names beside the store one. */
internal const val ASIA_PACKAGE = "com.ss.android.ugc.trill"

/** The authority of TikTok's live wallpaper data provider, written into its URIs outright. */
internal const val WALLPAPER_AUTHORITY = "com.zhiliaoapp.musically.wallpapercaller"

/** How many checks for TikTok's own package a declared build has: a media path resolver and two activity rules. */
internal const val OWN_PACKAGE_CHECKS = 3

/** Kotlin's `contains(CharSequence, CharSequence, ignoreCase)`, as the checks call it. */
private val CONTAINS_PARAMETERS = listOf("Ljava/lang/CharSequence;", "Ljava/lang/CharSequence;", "Z")

/** How far before the write its key's `const-string` may sit (two on every declared build). */
private const val KEY_LOOKBACK = 3

/** How far before the write the running package may be read (seven on every declared build). */
private const val VALUE_LOOKBACK = 10

/**
 * AppLog's package header loader, the bd_tracker PackageLoader that fills the header every
 * `device_register` request and log upload carries: `package` (the running package, or a
 * configured override TikTok never sets), `real_package_name` beside an override, then
 * `app_version`, `app_version_minor`, `version_code`, `update_version_code` and
 * `manifest_version_code`. X.07Yj.LIZ on 47.0.3, X.08Aj.LIZ on 47.1.3 and X.08An.LIZ on 47.1.4,
 * the only `(JSONObject)Z` loading all three strings below on each build.
 */
internal object RegistrationPackageHeaderFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf(JSON),
    strings = listOf("real_package_name", "app_version_minor", "manifest_version_code").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The template fill of TikTok's provider shell, the one manifest provider that hosts the
 * multiprocess settings, push, auth token and other providers under one roof: `${applicationId}`
 * becomes the running package, `${APP_ID}` TikTok's app id and `${FACEBOOK_APP_ID}` its Facebook
 * app id. Every authority and permission of the providers it hosts comes back through here on
 * its way into a ProviderInfo, so this is where a renamed copy's names are put right.
 * X.03bE.LJIIIIZZ on 47.0.3, X.03d9.LJIIIIZZ on 47.1.3 and X.03dD.LJIIIIZZ on 47.1.4.
 */
internal object ProviderNameTemplateFingerprint : Fingerprint(
    returnType = STRING,
    parameters = listOf(STRING),
    strings = listOf("\${applicationId}", "\${APP_ID}", "\${FACEBOOK_APP_ID}").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * The live wallpaper data provider's static initializer, which loads [WALLPAPER_AUTHORITY] once
 * and builds every `content://` URI of the provider from that register.
 */
internal object WallpaperCallerUrisFingerprint : Fingerprint(
    definingClass = WALLPAPER_DATA_PROVIDER,
    name = "<clinit>",
    strings = listOf(WALLPAPER_AUTHORITY).map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * TikTok's checks for its own package: a content URI whose authority holds it is TikTok's own and
 * isn't copied in as another app's would be, and under Family Pairing an app-settings page or a
 * Play link may open when its URI names it. Each loads the store package and TikTok Asia's and
 * asks Kotlin's `contains` about each in turn. A list of TikTok's packages and an installed-app
 * check load the same two strings and aren't counted ([storePackageChecks]).
 */
internal object OwnPackageCheckFingerprint : Fingerprint(
    strings = listOf(STORE_PACKAGE, ASIA_PACKAGE).map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> storePackageChecks(method).isNotEmpty() },
)

/**
 * Where [method] writes the running package into the `package` field: the index of each
 * `JSONObject.put(String, Object)` on three registers whose key register was last set by a
 * `const-string` of [PACKAGE_KEY] just before, and whose value register was last set by the
 * `move-result-object` of a `Context.getPackageName()` a few instructions before. The write of
 * TikTok's configured override, also under `package`, takes its value from a field and isn't
 * counted, so a reshaped loader leaves the patch without its site and it stops.
 */
internal fun registrationPackageWrites(method: Method): List<Int> {
    val instructions = method.implementation?.instructions?.toList() ?: return emptyList()
    return instructions.indices.filter { index ->
        val call = instructions[index]
        if (call.opcode != Opcode.INVOKE_VIRTUAL || call.getReference<MethodReference>()?.toString() != JSON_PUT) {
            return@filter false
        }
        val invoke = call as? FiveRegisterInstruction ?: return@filter false
        if (invoke.registerCount != 3) return@filter false

        val keyLoad = lastWriteOf(instructions, invoke.registerD, index, KEY_LOOKBACK)?.let { instructions[it] }
        val loadsKey = (keyLoad?.opcode == Opcode.CONST_STRING || keyLoad?.opcode == Opcode.CONST_STRING_JUMBO) &&
            keyLoad?.getReference<StringReference>()?.string == PACKAGE_KEY

        val valueStored = lastWriteOf(instructions, invoke.registerE, index, VALUE_LOOKBACK)
        val readsRunningPackage = valueStored != null && valueStored > 0 &&
            instructions[valueStored].opcode == Opcode.MOVE_RESULT_OBJECT &&
            instructions[valueStored - 1].opcode == Opcode.INVOKE_VIRTUAL &&
            instructions[valueStored - 1].getReference<MethodReference>()?.toString() == GET_PACKAGE_NAME

        loadsKey && readsRunningPackage
    }
}

/** The index of each `const-string` of [string] in [method], jumbo or not. */
internal fun stringLoads(method: Method, string: String): List<Int> {
    val instructions = method.implementation?.instructions?.toList() ?: return emptyList()
    return instructions.indices.filter { index ->
        val instruction = instructions[index]
        (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO) &&
            instruction.getReference<StringReference>()?.string == string
    }
}

/**
 * Where [method] asks whether some text holds the store package: the index of each `const-string`
 * of [STORE_PACKAGE] that the very next instruction hands, as the text looked for, to a static
 * `(CharSequence, CharSequence, Z)Z`, Kotlin's `contains`. A load used any other way, in a list
 * of TikTok's packages or an installed-app check, isn't counted.
 */
internal fun storePackageChecks(method: Method): List<Int> {
    val instructions = method.implementation?.instructions?.toList() ?: return emptyList()
    return stringLoads(method, STORE_PACKAGE).filter { index ->
        val call = instructions.getOrNull(index + 1) as? FiveRegisterInstruction ?: return@filter false
        if (call.opcode != Opcode.INVOKE_STATIC || call.registerCount != 3) return@filter false
        val reference = call.getReference<MethodReference>() ?: return@filter false
        reference.returnType == "Z" && reference.parameterTypes.map { it.toString() } == CONTAINS_PARAMETERS &&
            call.registerD == (instructions[index] as OneRegisterInstruction).registerA
    }
}

/** The index of the last instruction within [lookback] before [before] that writes [register]. */
private fun lastWriteOf(instructions: List<Instruction>, register: Int, before: Int, lookback: Int): Int? =
    (before - 1 downTo maxOf(0, before - lookback)).firstOrNull {
        instructions[it].opcode.setsRegister() && (instructions[it] as? OneRegisterInstruction)?.registerA == register
    }

/**
 * Hands the string the `const-string` at [index] just loaded to the extension's [method], whose
 * answer takes its place in the same register. The load keeps its index and any label on it, so
 * a jump to it runs the swap too.
 */
private fun MutableMethod.swapAfterLoad(index: Int, method: String) {
    val register = getInstruction<OneRegisterInstruction>(index).registerA
    addInstructions(
        index + 1,
        """
            invoke-static/range { v$register .. v$register }, $EXTENSION->$method($STRING)$STRING
            move-result-object v$register
        """,
    )
}

@Suppress("unused")
val runBesideStoreAppPatch = bytecodePatch(
    name = "Run beside the store app",
    description = "Lets a renamed copy of TikTok, made with Morphe's Clone app patch, sign in " +
        "while the regular TikTok stays installed. Pick it together with Clone app. Google and " +
        "Facebook sign-in won't work in the copy, so use email or phone.",
    default = false,
) {
    category("Settings")
    dependsOn(sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        // Everything is found and checked before the first write: a patch that fails part way
        // keeps what it already wrote.
        val loader = RegistrationPackageHeaderFingerprint.method
        val writes = registrationPackageWrites(loader)
        if (writes.size != 1) {
            throw PatchException(
                "Run beside the store app: ${loader.definingClass}->${loader.name} writes the running " +
                    "package into the registration header ${writes.size} times, not once.",
            )
        }
        val index = writes.single()
        val put = loader.implementation!!.instructions.elementAt(index) as FiveRegisterInstruction
        if (maxOf(put.registerC, put.registerD, put.registerE) > 15) {
            throw PatchException("Run beside the store app: the header write's registers don't fit a static call.")
        }
        val template = ProviderNameTemplateFingerprint.method
        val templateReturns = template.findInstructionIndicesReversedOrThrow(Opcode.RETURN_OBJECT)

        // The wallpaper data and the own-package checks matter far less than signing in. On a
        // declared build RenamedCopyAnchorsTest holds them, so a miss there is a fault and fails
        // the patch. On any other build a miss leaves just that part out, with a note.
        val declaredBuild = packageMetadata.versionName in declaredVersions()
        fun leftOut(problem: String): Nothing? {
            if (declaredBuild) throw PatchException("Run beside the store app: $problem")
            println("[Run beside the store app] Left out on ${packageMetadata.versionName}: $problem")
            return null
        }
        // The wallpaper caller's authority, loaded once into the register every URI of the
        // provider is built from.
        val wallpaper = WallpaperCallerUrisFingerprint.methodOrNull
        val wallpaperLoads = wallpaper?.let { stringLoads(it, WALLPAPER_AUTHORITY) }.orEmpty()
        val wallpaperLoad = if (wallpaper != null && wallpaperLoads.size == 1) {
            wallpaper to wallpaperLoads.single()
        } else {
            leftOut(
                "${wallpaper?.let { "${it.definingClass}->${it.name}" } ?: "WallPaperDataProvider.<clinit>"} loads " +
                    "the wallpaper caller's authority ${wallpaperLoads.size} times, not once.",
            )
        }
        // TikTok's checks for its own package: the store package each loads becomes the running one.
        val checks = OwnPackageCheckFingerprint.matchAll()
        val ownPackageSites = if (checks.size == OWN_PACKAGE_CHECKS) {
            checks.map { it.method to storePackageChecks(it.method) }
        } else {
            leftOut(
                "${checks.size} checks for TikTok's own package, not $OWN_PACKAGE_CHECKS: " +
                    checks.map { "${it.originalClassDef.type}->${it.method.name}" },
            )
        }

        // Replaced in place with a static call on the same three registers: the header, the key
        // and the running package. Its result goes unread, as the original's did.
        loader.replaceInstruction(
            index,
            "invoke-static { v${put.registerC}, v${put.registerD}, v${put.registerE} }, " +
                "$EXTENSION->putPackage(${JSON}Ljava/lang/String;Ljava/lang/Object;)$JSON",
        )

        // The provider shell's template fill. Every authority and permission of the providers it
        // hosts comes back through here, the multiprocess settings one among them, and the
        // extension answers with the name the copy's manifest declares for it.
        templateReturns.forEach { at ->
            val register = template.getInstruction<OneRegisterInstruction>(at).registerA
            template.addInstructionsAtControlFlowLabel(
                at,
                """
                    invoke-static/range { v$register .. v$register }, $EXTENSION->declared($STRING)$STRING
                    move-result-object v$register
                """,
            )
        }

        wallpaperLoad?.let { (method, at) -> method.swapAfterLoad(at, "declared") }
        // The contains call after each load, on the same three registers, asks the extension
        // instead: the store package as before, or the copy's own name. Swapping the loaded name
        // alone would stop a link to the store app counting as TikTok's own, as it does stock.
        ownPackageSites?.forEach { (method, sites) ->
            sites.forEach { at ->
                val call = method.getInstruction<FiveRegisterInstruction>(at + 1)
                method.replaceInstruction(
                    at + 1,
                    "invoke-static { v${call.registerC}, v${call.registerD}, v${call.registerE} }, " +
                        "$EXTENSION->holdsOwnPackage(Ljava/lang/CharSequence;Ljava/lang/CharSequence;Z)Z",
                )
            }
        }
        registerForBuildDetails("enableRunBesideStoreApp")
    }
}
