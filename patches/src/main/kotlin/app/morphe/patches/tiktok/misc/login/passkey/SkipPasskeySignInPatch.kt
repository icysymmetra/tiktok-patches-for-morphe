/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.login.passkey

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.returnBooleanWhenOn
import app.morphe.util.addInstruction
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Skip passkey sign-in"
internal const val PASSKEY_SIGN_IN_SWITCH = "Lapp/morphe/extension/tiktok/misc/PasskeySignIn;->hidePasskeys()Z"

/**
 * TikTok's sign-in passkey service. Its service registry loads it by this name, so R8 keeps it,
 * and it implements [CREDENTIAL_MANAGER_INTERFACE], which is how the rest of the app reaches it.
 */
internal const val CREDENTIAL_MANAGER_SERVICE =
    "Lcom/ss/android/ugc/aweme/account/login/passkey/CredentialManagerService;"
internal const val CREDENTIAL_MANAGER_INTERFACE = "Lcom/ss/android/ugc/aweme/ICredentialManagerService;"
internal const val GOOGLE_API_AVAILABILITY = "Lcom/google/android/gms/common/GoogleApiAvailability;"

/**
 * The service's check of whether this phone can use passkeys at all (`LJJIJIIJIL` on 47.1.4,
 * read from the fixture's dex). In order it asks a server flag, that the account isn't one
 * TikTok restricts, Android 9 or newer, Google Play services 230815045 (23.08.15) or newer, and
 * that `GoogleApiAvailability` reports Play services available. It's the only method in the
 * class that asks GoogleApiAvailability, which keeps its own name as a library class, and it
 * isn't on the interface.
 *
 * <p>Two of the service's own gates call it, and nothing else does. `LJIIZILJ` (server flag,
 * Android 9, this check) is the one the rest of the app asks through the interface, and
 * `LIZJ` builds on it. `LJII` adds a store region list and decides the passkey upsell popup.
 * Each answers no as soon as this does, and every caller of theirs on 47.1.4 already has a
 * route for no:
 * - `LoginService.loginByMethodName`, passkey method: calls `downgradeToNormalLogin`, which runs
 *   the caller's normal login.
 * - `LoginService.doPasskeyLoginWithChallenge`, a saved account's passkey login: runs the
 *   fallback it was handed.
 * - The automatic prompt on the login and sign-up pages (`X/0li6`, chosen in its `LJII`, in
 *   sign-up's onCreate, in `X/0lgo`, `X/0lgp` and the login tab's runnable): takes `LJIIJ`,
 *   which offers Google One Tap only, with no passkey request.
 * - The other-ways lists (`X/0lpT`, `X/0lpS`): leave out the passkey row and keep phone,
 *   password and the rest.
 * - The saved-account list (`OneClickLoginService.LJJ`, which keeps the answer from when the
 *   service was built): leaves saved passkey logins out.
 * - The passkey popup, the after-login passkey hook, the passkey wizard deeplink, the web
 *   bridge's create-passkey handler and Manage account's passkey row: don't show or don't start.
 */
internal object PasskeyDeviceSupportFingerprint : Fingerprint(
    definingClass = CREDENTIAL_MANAGER_SERVICE,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "Z",
    parameters = listOf(),
    filters = listOf(methodCall(definingClass = GOOGLE_API_AVAILABILITY, name = "getInstance")),
)

/** Whether [method] takes nothing, answers yes or no and looks up Google Play services. */
internal fun isPasskeyDeviceSupportCheck(method: Method): Boolean =
    method.returnType == "Z" && method.parameterTypes.isEmpty() &&
        method.implementation?.instructions?.any { instruction ->
            instruction.opcode == Opcode.INVOKE_STATIC &&
                instruction.getReference<MethodReference>()?.let {
                    it.definingClass == GOOGLE_API_AVAILABILITY && it.name == "getInstance"
                } == true
        } == true

@Suppress("unused")
val skipPasskeySignInPatch = bytecodePatch(
    name = "Skip passkey sign-in",
    description = "Lets you sign in another way, like your password or a code by email or text, " +
        "when TikTok wants a passkey. Password managers won't hand a passkey to a patched app, " +
        "so that step can't finish here. With the switch on, TikTok treats your phone as one " +
        "without passkeys. Starts off. Turn it on in Metra settings > App behavior.",
) {
    category("Settings")
    dependsOn(sharedExtensionPatch, settingsPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        installPasskeySignInSwitch()
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enablePasskeySignIn()V",
        )
    }
}

/**
 * The patch's hook, without its settings row, so a test can put it on a build that carries no
 * extension. With the switch off the check runs as TikTok wrote it.
 */
context(patchContext: BytecodePatchContext)
internal fun installPasskeySignInSwitch() {
    val check = PasskeyDeviceSupportFingerprint.method
    // A second method of this shape would mean the shape no longer picks out the check, and
    // answering no from the wrong one is a failure rather than a coin toss.
    val shaped = patchContext.classDefBy(CREDENTIAL_MANAGER_SERVICE).methods.filter(::isPasskeyDeviceSupportCheck)
    if (shaped.size != 1 || shaped.single().name != check.name) {
        throw PatchException(
            "$PATCH: expected one passkey support check in $CREDENTIAL_MANAGER_SERVICE, " +
                "found ${shaped.map { it.name }}",
        )
    }
    check.returnBooleanWhenOn(PATCH, PASSKEY_SIGN_IN_SWITCH, false)
}
