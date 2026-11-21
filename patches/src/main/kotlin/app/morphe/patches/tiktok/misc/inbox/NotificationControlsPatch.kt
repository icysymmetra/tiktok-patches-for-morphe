/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.fieldAccess
import app.morphe.patcher.patch.PatchException
import app.morphe.util.addInstruction
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.optimizer.InitPushTaskFingerprint
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.privacy.invokeSitesOf
import app.morphe.patches.tiktok.privacy.replaceSites
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.patcher.util.getReference
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/inbox/NotificationControls;"
private const val PUSH_SHUTOFF = "Lapp/morphe/extension/tiktok/inbox/PushShutoff;"
private const val POWER_MANAGER = "Landroid/os/PowerManager;"
private const val WAKE_LOCK = "Landroid/os/PowerManager\$WakeLock;"

/**
 * Every wake lock call Turn off push notifications stands in front of, by the framework's own
 * descriptor, each to a PushShutoff static taking the receiver first. PowerManager and WakeLock
 * are final, so no call names a subclass. 47.0.3 makes 46 of these calls (FCM, TikTok's early
 * push lock, the push handler lighting the screen, WorkManager, JobIntentService, app
 * measurement and LIVE). On 47.1.3 and 47.1.4 R8 moved every one of them into X.00kn's API
 * outlines (le, me, ne and oe), apart from app measurement's own acquire, so those builds have
 * five. newWakeLock is there for the tag, which is how the extension tells a kept lock from
 * the rest, and release because a skipped acquire would make a reference counted release throw.
 */
internal val WAKE_LOCK_CALLS = mapOf(
    "$POWER_MANAGER->newWakeLock(ILjava/lang/String;)$WAKE_LOCK" to
        "$PUSH_SHUTOFF->newWakeLock(${POWER_MANAGER}ILjava/lang/String;)$WAKE_LOCK",
    "$WAKE_LOCK->acquire()V" to "$PUSH_SHUTOFF->acquire($WAKE_LOCK)V",
    "$WAKE_LOCK->acquire(J)V" to "$PUSH_SHUTOFF->acquire(${WAKE_LOCK}J)V",
    "$WAKE_LOCK->release()V" to "$PUSH_SHUTOFF->release($WAKE_LOCK)V",
    "$WAKE_LOCK->release(I)V" to "$PUSH_SHUTOFF->release(${WAKE_LOCK}I)V",
)

/**
 * The one method on the push handler that hands a built notification to Android. The class
 * kept its name; the method did not, so it is found by the call it makes. Nothing else on
 * MessageShowHandler calls notify, and its parameters are checked below before the message
 * is read out of p1. Block suggested video notifications reroutes that call through its filter,
 * and either patch can run first, so the rerouted call counts too.
 */
internal object PushNotifyFingerprint : Fingerprint(
    definingClass = "Lcom/ss/android/ugc/awemepushlib/manager/MessageShowHandler;",
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.STATIC),
    returnType = "V",
    custom = { method, _ ->
        method.implementation?.instructions?.any { instruction ->
            instruction.getReference<MethodReference>()?.postsNotification() == true
        } == true
    },
)

/**
 * Whether a conversation offers the streak button, and whether it carries the streak
 * reminder message. Both names survived on several copies of the same Kotlin model, so
 * every one of them is taken.
 */
private object ShowStreakButtonFingerprint : Fingerprint(
    name = "getShowStreakButton",
    returnType = "Z",
    parameters = emptyList(),
)

private object StreakReminderFingerprint : Fingerprint(
    name = "getHasStreakReminderInlineMsg",
    returnType = "Z",
    parameters = emptyList(),
)

// In 47.6.15 the strategy adapters can read these flags without calling the model getter.
private val streakReminderOwners = listOf(
    "Lcom/ss/android/ugc/aweme/im/lightinteract/api/platform/strategybridge/LightInteractionMetaData;",
    "Lcom/ss/android/ugc/aweme/im/actionbar/serviceimpl/ActionBarMetaData;",
    "Lcom/ss/android/ugc/aweme/im/pluspanel/impl/serviceimpl/PlusPanelMetaData;",
)
private const val REMINDER_FIELD = "hasStreakReminderInlineMsg"

private fun filterMessageStreak(register: Int) =
    "invoke-static/range {v$register .. v$register}, $EXTENSION->filterMessageStreak(Z)Z\nmove-result v$register"

@Suppress("unused")
val notificationControlsPatch = bytecodePatch(
    name = "Notification controls",
    description = "Lets you turn off new follower notifications and streak reminders, which " +
        "TikTok has no switch for, or turn off TikTok's notifications altogether. Starts off. " +
        "Turn it on in Metra settings > Interface.",
) {
    category("Inbox")
    // The notify filter carries the drawer half of Turn off push notifications.
    dependsOn(settingsPatch, sharedExtensionPatch, notificationFilterPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        PushNotifyFingerprint.method.apply {
            // The method is static, so p1 is the second parameter. That is the push message,
            // whose own type is obfuscated and cannot be named here, so the parameters around
            // it stand in for its name: a Context first and the built Notification fifth. p1
            // itself only has to be an object for the call to pass it as one.
            check(
                parameterTypes.size >= 5 &&
                    parameterTypes[0] == "Landroid/content/Context;" &&
                    parameterTypes[1].startsWith("L") &&
                    parameterTypes[4] == "Landroid/app/Notification;",
            ) {
                "Notification controls: the push handler no longer takes the message in p1."
            }
            guardAtEntry(
                "getShowStreakButton",
                "invoke-static/range { p1 .. p1 }, $EXTENSION->shouldDropPush(Ljava/lang/Object;)Z",
                "return-void",
            )
        }

        val reminderGetters = StreakReminderFingerprint.matchAll().filter { it.originalMethod.implementation != null }
        check(reminderGetters.isNotEmpty()) { "Notification controls: no streak reminder getter has a body." }
        val buttonGetters = ShowStreakButtonFingerprint.matchAllOrNull().orEmpty()
            .filter { it.originalMethod.implementation != null }
        for (match in reminderGetters + buttonGetters) {
            val method = match.method
            val returns = method.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN }
            returns.asReversed().forEach { (index, instruction) ->
                method.addInstructionsAtControlFlowLabel(index,
                    filterMessageStreak((instruction as OneRegisterInstruction).registerA))
            }
        }
        var reminderReads = 0
        for (owner in streakReminderOwners) {
            val reads = Fingerprint(filters = listOf(fieldAccess(
                definingClass = owner, name = REMINDER_FIELD, opcode = Opcode.IGET_BOOLEAN,
            ))).matchAllOrNull().orEmpty()
            for (match in reads) {
                // Keep the data classes' equality, hash and debugging output tied to the original value.
                if (match.originalClassDef.type == owner) continue
                val method = match.method
                method.implementation!!.instructions.withIndex().filter { (_, instruction) ->
                    instruction.opcode == Opcode.IGET_BOOLEAN && instruction.getReference<FieldReference>()?.let {
                        it.definingClass == owner && it.name == REMINDER_FIELD && it.type == "Z"
                    } == true
                }.asReversed().forEach { (index, instruction) ->
                    method.addInstructions(index + 1, filterMessageStreak((instruction as OneRegisterInstruction).registerA))
                    reminderReads++
                }
            }
        }
        check(buttonGetters.isNotEmpty() || reminderReads > 0) {
            "Notification controls: neither the legacy streak button nor the strategy flag reads were found."
        }

        // Turn off push notifications. TikTok's push setup is one startup task, so a guard at its
        // entry keeps the push service from starting for a launch with the switch on, and the
        // first launch after it goes off sets push up as usual. Limit background traffic's Skip
        // push setup option puts a return at the start of the same method at patch time, and
        // with both in, whichever comes first ends it.
        InitPushTaskFingerprint.method.guardAtEntry(
            "Notification controls",
            "invoke-static {}, $PUSH_SHUTOFF->skipPushSetup()Z",
            "return-void",
        )

        val wakeLockSites = invokeSitesOf(WAKE_LOCK_CALLS.keys)
        for (call in listOf("$POWER_MANAGER->newWakeLock(", "$WAKE_LOCK->acquire(", "$WAKE_LOCK->release(")) {
            if (wakeLockSites.none { it.target.startsWith(call) }) {
                throw PatchException("Notification controls: found no ${call.substringAfter("->")}) call outside the extension.")
            }
        }
        replaceSites(wakeLockSites, WAKE_LOCK_CALLS)
        println("[Notification controls] Took ${wakeLockSites.size} wake lock calls.")

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableNotificationControls()V",
        )
    }
}
