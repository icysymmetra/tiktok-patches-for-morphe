/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.language

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Keep the app language"
internal const val APP_LANGUAGE_SWITCH =
    "Lapp/morphe/extension/tiktok/misc/AppLanguage;->keepChosenLanguage(Landroid/content/Context;)Z"

/** TikTok's language service. Its service registry loads it by this name, so R8 keeps it. */
internal const val I18N_MANAGER_SERVICE = "Lcom/tiktok/ef/i18nimpl/service/I18nManagerServiceImpl;"
internal const val I18N_MANAGER_API = "Lcom/tiktok/ef/i18nmanagerapi/service/i18n/I18nManagerServiceApi;"
internal const val LANGUAGE_PREFERENCES = "key_language_sp_key"
internal const val APP_LANGUAGE = "Lapp/morphe/extension/tiktok/misc/AppLanguage;"

/** TikTok's reword table, its own strings by language. Native code loads it by this name. */
internal const val REWORD_MANAGER = "Lcom/ss/android/ugc/aweme/nxreword/manager/NxRewordManager;"
internal const val HOST_APPLICATION = "Lcom/ss/android/ugc/aweme/app/host/AwemeHostApplication;"
private const val CONTEXT = "Landroid/content/Context;"
private const val LOCALE = "Ljava/util/Locale;"
private const val EDITOR = "Landroid/content/SharedPreferences\$Editor;"

/**
 * TikTok's language reset (`LJJ` on 47.1.4, read from the fixture's dex): it clears the
 * [LANGUAGE_PREFERENCES] file, where the language picked in TikTok's settings is kept, then
 * applies the language that read gives back with nothing saved, the phone's. Only the start-up
 * language check calls it (`X.033C` on 47.1.4, through [I18N_MANAGER_API], in two places), and
 * only once a language was picked: when the phone's language differs from the one saved at the
 * last start and the reader never changed it in TikTok, or when a language TikTok newly supports
 * is the phone's. It runs inside the application's attachBaseContext.
 */
internal object AppLanguageResetFingerprint : Fingerprint(
    definingClass = I18N_MANAGER_SERVICE,
    returnType = "V",
    parameters = listOf(CONTEXT),
    custom = { method, _ -> method.isAppLanguageReset() },
)

/**
 * Whether [method] takes a context, reads [LANGUAGE_PREFERENCES] and clears a preferences file. It
 * must be an instance method, since the guard reads the context as p1.
 */
internal fun Method.isAppLanguageReset(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags)) return false
    if (returnType != "V" || parameterTypes.map(CharSequence::toString) != listOf(CONTEXT)) return false
    val instructions = implementation?.instructions ?: return false
    return instructions.any { it.getReference<StringReference>()?.string == LANGUAGE_PREFERENCES } &&
        instructions.any { instruction ->
            instruction.getReference<MethodReference>()?.let { it.definingClass == EDITOR && it.name == "clear" } == true
        }
}

/**
 * TikTok's read of the language picked in its settings (`X.039Q.LIZ` on 47.1.4): the saved locale
 * from [LANGUAGE_PREFERENCES], with its zh-Hans and region handling, or null when nothing was
 * picked and TikTok follows the phone.
 */
internal object PickedLocaleFingerprint : Fingerprint(
    returnType = LOCALE,
    parameters = listOf(CONTEXT),
    strings = listOf(LANGUAGE_PREFERENCES, "key_current_locale", "pref_language_key", "key_current_region").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

/**
 * TikTok's apply of that language to the app's resources (`X.039Q.LIZIZ`), which its start-up
 * runs as well, after the point the reword table can already have loaded.
 */
internal object ApplyPickedLocaleFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(CONTEXT),
    strings = listOf(LANGUAGE_PREFERENCES, "updateLocale error:").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.calls("Landroid/content/res/Configuration;", "setLocale")
    },
)

/** The reword table's switch to another language (`LJ`), the one TikTok's language page makes. */
internal object LoadStringsFingerprint : Fingerprint(
    definingClass = REWORD_MANAGER,
    returnType = "V",
    parameters = listOf(LOCALE),
    strings = listOf("switch_locale").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

/** The language the reword table is loaded for (`LIZIZ`), null before it loads. */
internal object StringsLocaleFingerprint : Fingerprint(
    definingClass = REWORD_MANAGER,
    returnType = LOCALE,
    parameters = listOf(),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) },
)

internal object HostApplicationOnCreateFingerprint : Fingerprint(
    definingClass = HOST_APPLICATION,
    name = "onCreate",
    returnType = "V",
    parameters = listOf(),
)

internal fun Method.calls(definingClass: String, name: String, returnType: String? = null): Boolean =
    implementation?.instructions?.any { instruction ->
        instruction.getReference<MethodReference>()?.let {
            it.definingClass == definingClass && it.name == name && (returnType == null || it.returnType == returnType)
        } == true
    } == true

@Suppress("unused")
val keepAppLanguagePatch = bytecodePatch(
    // A literal, not PATCH: DefaultSelectionPolicyTest reads the declarations as text.
    name = "Keep the app language",
    description = "Lets you keep the language you picked in TikTok's own settings. When TikTok " +
        "starts and decides the phone's language changed, it drops that pick and follows the " +
        "phone, which can leave it in the wrong language after a reboot. Starts off. Turn it on " +
        "in Metra settings > App behavior.",
) {
    category("Settings")
    dependsOn(sharedExtensionPatch, settingsPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        installAppLanguageSwitch()
        installLanguageAtStart()
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAppLanguage()V",
        )
    }
}

/**
 * The patch's hook, without its settings row, so a test can put it on a build that carries no
 * extension. The switch gets the reset's own context, since the settings context and
 * ActivityThread's application don't exist yet when it runs. With the switch off the reset runs
 * as TikTok wrote it.
 */
context(patchContext: BytecodePatchContext)
internal fun installAppLanguageSwitch() {
    val reset = AppLanguageResetFingerprint.method
    // A second method of this shape would mean it no longer picks out the reset, and skipping
    // the wrong one is a failure rather than a coin toss.
    val shaped = patchContext.classDefBy(I18N_MANAGER_SERVICE).methods.filter { it.isAppLanguageReset() }
    if (shaped.size != 1 || shaped.single().name != reset.name) {
        throw PatchException(
            "$PATCH: expected one language reset in $I18N_MANAGER_SERVICE, found ${shaped.map { it.name }}",
        )
    }
    reset.guardAtEntry(PATCH, "invoke-static/range { p1 .. p1 }, $APP_LANGUAGE_SWITCH", "return-void")
}

/**
 * Puts the picked language back first thing in the application's onCreate (#61). Skipping the
 * reset kept the pick saved, but the reporter's TikTok still came up in English after a reboot;
 * their own build that also did this stayed Italian. The extension's stubs get TikTok's calls
 * here, so no obfuscated name is written into its sources, and the extension decides with the
 * switch whether any of them runs.
 */
context(patchContext: BytecodePatchContext)
internal fun installLanguageAtStart() {
    val picked = PickedLocaleFingerprint.method
    val apply = ApplyPickedLocaleFingerprint.method
    if (!apply.calls(picked.definingClass, picked.name, LOCALE)) {
        throw PatchException(
            "$PATCH: ${apply.definingClass}->${apply.name} no longer reads ${picked.definingClass}->${picked.name}",
        )
    }
    val loadStrings = LoadStringsFingerprint.method
    val stringsLocale = StringsLocaleFingerprint.method

    val extension = patchContext.mutableClassDefBy(APP_LANGUAGE)
    fun stub(name: String) = extension.methods.single { it.name == name }
    stub("pickedLocale").addInstructions(
        0,
        """
            invoke-static { p0 }, ${picked.definingClass}->${picked.name}($CONTEXT)$LOCALE
            move-result-object v0
            return-object v0
        """,
    )
    stub("applyPickedLocale").addInstruction(0, "invoke-static { p0 }, ${apply.definingClass}->${apply.name}($CONTEXT)V")
    stub("stringsLocale").addInstructions(
        0,
        """
            invoke-static { }, $REWORD_MANAGER->${stringsLocale.name}()$LOCALE
            move-result-object v0
            return-object v0
        """,
    )
    stub("loadStringsFor").addInstruction(0, "invoke-static { p0 }, $REWORD_MANAGER->${loadStrings.name}($LOCALE)V")

    HostApplicationOnCreateFingerprint.method.addInstruction(0, "invoke-static { p0 }, $APP_LANGUAGE->applyAtStart($CONTEXT)V")
}
