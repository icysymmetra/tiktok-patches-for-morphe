/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.shortcuts

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.methodCall
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.MainActivityOnCreateFingerprint
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.util.cloneMutable
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/misc/LauncherShortcuts;"
private const val SHORTCUT_MANAGER = "Landroid/content/pm/ShortcutManager;"
private const val SHORTCUT_SERVICE =
    "Lcom/ss/android/ugc/aweme/launcher/service/shortcut/IShortcutService;"
private const val SERVICE_MANAGER =
    "Lcom/ss/android/ugc/aweme/framework/services/ServiceManager;"

/**
 * The two platform calls that put a list of shortcuts in front of the launcher.
 *
 * <p>`updateShortcuts` is deliberately not here. It changes entries that are already published
 * and adds none, so with the published list empty there is nothing for it to change. Neither is
 * `requestPinShortcut`, which is somebody choosing to put a shortcut on their own home screen.
 */
private val PUBLISHERS = setOf("setDynamicShortcuts", "addDynamicShortcuts")

internal fun publishesShortcuts(instruction: Instruction): Boolean {
    // Both forms. d8 emits the range form whenever a register the call needs sits above v15,
    // which is ordinary in a large synthesized method, and matching only the short form would
    // walk past such a call without hooking it and without saying so.
    if (instruction.opcode != Opcode.INVOKE_VIRTUAL &&
        instruction.opcode != Opcode.INVOKE_VIRTUAL_RANGE
    ) {
        return false
    }
    val reference = instruction.getReference<MethodReference>() ?: return false
    return reference.definingClass == SHORTCUT_MANAGER &&
        reference.name in PUBLISHERS &&
        reference.parameterTypes.singleOrNull() == "Ljava/util/List;"
}

/** The register holding the list argument of a publish call, in either invoke form. */
internal fun listRegisterOf(instruction: Instruction): Int = when (instruction) {
    // registerC is the ShortcutManager the call is made on, registerD its only argument.
    is Instruction35c -> instruction.registerD
    // The range form lays the same two out consecutively from the start register.
    is RegisterRangeInstruction -> instruction.startRegister + 1
    else -> throw PatchException(
        "Hide the launcher shortcuts: a publish call in an unexpected form, ${instruction.opcode}.",
    )
}

/**
 * Puts the list about to be published in front of the extension and publishes its answer.
 *
 * <p>It is the argument that goes through the extension, not the receiver: routing the receiver
 * would hand the extension the `ShortcutManager` and then try to publish a manager. The answer
 * goes back into the argument's own register, so the call itself is untouched and whatever the
 * method does with its result still works.
 *
 * <p>`move-result-object` addresses eight bits, so a register above 255 could not receive the
 * answer. Nothing in this app comes close, and a wrong answer there would be a corrupt method
 * rather than a missing feature, so it is checked rather than assumed.
 */
internal fun MutableMethod.routeShortcutListThroughExtension(index: Int) {
    val list = listRegisterOf(getInstruction(index))
    if (list > 255) {
        throw PatchException(
            "Hide the launcher shortcuts: the list is in v$list, which move-result-object " +
                "cannot address.",
        )
    }
    addInstructions(
        index,
        """
            invoke-static/range {v$list .. v$list}, $EXTENSION->publish(Ljava/util/List;)Ljava/util/List;
            move-result-object v$list
        """,
    )
}

/**
 * Anything handing the platform a list of shortcuts. The method is Android's, so it is named in
 * full whatever TikTok called the code around it, and taking every one of them means the switch
 * does not depend on which of them the app happens to use. On 46.2.3 the one that publishes the
 * long-press menu is the `setDynamicShortcuts` inside `TiktokShortcutManager`'s refresh, and the
 * `addDynamicShortcuts` is the fallback inside the support library's own push.
 */
internal object ShortcutPublishFingerprint : Fingerprint(
    // The filter repeats a reference the custom block requires, so the patcher reads only the
    // classes that make it (#54).
    filters = listOf(methodCall(definingClass = SHORTCUT_MANAGER)),
    custom = { method, _ ->
        method.implementation?.instructions?.any(::publishesShortcuts) == true
    },
)

@Suppress("unused")
val hideLauncherShortcutsPatch = bytecodePatch(
    name = "Hide the launcher shortcuts",
    description = "Empties the menu that pops up when you press and hold TikTok's icon on " +
        "your home screen. Tapping the icon still opens the app. Starts off. Turn it on in " +
        "Metra settings > App behavior.",
) {
    category("Interface")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        var patched = 0
        ShortcutPublishFingerprint.matchAll().forEach { match ->
            val method = match.method
            val implementation = method.implementation ?: return@forEach

            // Later calls first, because inserting ahead of one moves every index after it.
            val calls = implementation.instructions.withIndex()
                .filter { publishesShortcuts(it.value) }
                .map { it.index }
                .toList()

            calls.asReversed().forEach { index ->
                method.routeShortcutListThroughExtension(index)
                patched++
            }
        }

        if (patched == 0) {
            throw PatchException("Hide the launcher shortcuts: nothing publishes any.")
        }

        // The service keeps its name; its methods do not. The one wanted is the only one on it
        // taking a scene and a flag, so it is chosen by that shape rather than by a name that
        // means nothing here and would be something else in the next build. More than one of that
        // shape means the shape has stopped identifying it, which is a failure rather than a
        // coin toss between them.
        val service = mutableClassDefBy(SHORTCUT_SERVICE)
        if (!AccessFlags.INTERFACE.isSet(service.accessFlags)) {
            throw PatchException(
                "Hide the launcher shortcuts: $SHORTCUT_SERVICE is no longer an interface, so " +
                    "invoke-interface is the wrong call for it.",
            )
        }
        val candidates = service.methods.filter {
            it.returnType == "V" &&
                it.parameterTypes.toList() == listOf("Ljava/lang/String;", "Z") &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        }
        if (candidates.size != 1) {
            throw PatchException(
                "Hide the launcher shortcuts: expected one rebuild taking a scene and a flag on " +
                    "$SHORTCUT_SERVICE, found ${candidates.size}.",
            )
        }
        val rebuild = candidates.single()

        // The bridge below names these two by hand, and a hand-written name that no longer exists
        // assembles perfectly and fails only on a phone, quietly, as a switch that stops putting
        // anything back. Everything else in this patch fails loudly when a shape is missing.
        val serviceManager = mutableClassDefBy(SERVICE_MANAGER)
        for (wanted in listOf("get" to emptyList(), "getService" to listOf("Ljava/lang/Class;"))) {
            if (serviceManager.methods.none {
                    it.name == wanted.first && it.parameterTypes.toList() == wanted.second
                }
            ) {
                throw PatchException(
                    "Hide the launcher shortcuts: $SERVICE_MANAGER has no ${wanted.first}, so " +
                        "the rebuild bridge would call something that is not there.",
                )
            }
        }

        val extension = mutableClassDefBy(EXTENSION)
        val stub = extension.methods.single { it.name == "askHostToRebuild" }
        val filled = stub.cloneMutable(additionalRegisters = 3)
        extension.methods.remove(stub)
        extension.methods.add(filled)
        filled.addInstructions(
            0,
            """
                invoke-static {}, $SERVICE_MANAGER->get()$SERVICE_MANAGER
                move-result-object v0
                const-class v1, $SHORTCUT_SERVICE
                invoke-virtual {v0, v1}, $SERVICE_MANAGER->getService(Ljava/lang/Class;)Ljava/lang/Object;
                move-result-object v0
                check-cast v0, $SHORTCUT_SERVICE
                const-string v1, "hushfeed"
                const/4 v2, 0x1
                invoke-interface {v0, v1, v2}, $SHORTCUT_SERVICE->${rebuild.name}(Ljava/lang/String;Z)V
                return-void
            """,
        )

        // Every launch, because the switch can be changed while the app is not running and what is
        // published outlives the process.
        //
        // At the front. Anywhere else is a guess about instructions this patch has not read: an
        // invoke and the move-result that takes its answer have to stay adjacent, and inserting
        // between them makes a method no device will verify. Prepending cannot split anything.
        // The context this needs is already set by then either way, because the shared extension
        // patch makes its hooks in finalize, which runs after every execute block, and puts its
        // setContext at index 0 of this same method.
        MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $EXTENSION->apply(Landroid/content/Context;)V",
        )

        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLauncherShortcuts()V",
        )
    }
}
