/*
 * Forked from:
 * https://gitlab.com/ReVanced/revanced-patches/-/blob/main/patches/src/main/kotlin/app/revanced/patches/tiktok/interaction/cleardisplay/RememberClearDisplayPatch.kt
 */
package app.morphe.patches.tiktok.interaction.cleardisplay

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.misc.theme.declaredVersions
import app.morphe.patches.tiktok.interaction.blockauthor.PlayerProgressAidFingerprint
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.util.cloneMutable
import app.morphe.patcher.util.getReference
import app.morphe.util.implementationOrPatchException
import app.morphe.util.returnEarly
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/cleardisplay/RememberClearDisplayPatch;"

@Suppress("unused")
val rememberClearDisplayPatch = bytecodePatch(
    name = "Remember clear display",
    description = "Keeps clear display, TikTok's mode that hides the buttons over a video, on " +
        "as you swipe to the next video. It can also turn on by itself after a delay you pick, " +
        "which starts off in Metra settings > Interface. When clear display ends, the " +
        "Following stories bubble TikTok hid comes back too.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())
    execute {
        // Entering clear display by itself must not look to TikTok like the reader asked for
        // it, so the events its own code sends are stopped. Two of the three were written as bare
        // signatures, both had stopped matching anything on 46.2.3, and both call sites were
        // methodOrNull, so the patch skipped them and still reported Applied. They are found
        // inside the logging class now, which the one surviving signature identifies.
        val stateMethod = ClearModeLogStateFingerprint.method
        val loggerClass = mutableClassDefBy(stateMethod.definingClass)
        stateMethod.returnEarly()

        fun suppress(event: String, parameterPrefix: List<String>) {
            val matches = loggerClass.methods.filter { candidate ->
                candidate.returnType == "V" &&
                    AccessFlags.STATIC.isSet(candidate.accessFlags) &&
                    candidate.parameters.map { it.type }.take(parameterPrefix.size) == parameterPrefix
            }
            if (matches.isEmpty()) {
                throw PatchException(
                    "Remember clear display: ${stateMethod.definingClass} no longer logs the " +
                        "$event event, so entering clear display would be reported as a tap.",
                )
            }
            matches.forEach { it.returnEarly() }
        }

        suppress(
            "clear mode change",
            listOf(
                "Z",
                "Ljava/lang/String;",
                "Ljava/lang/String;",
                "Lcom/ss/android/ugc/aweme/feed/model/Aweme;",
                "Ljava/lang/String;",
                "J",
                "I",
            ),
        )
        suppress(
            "clear mode playtime",
            listOf("F", "I", "J", "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"),
        )
        val eventMethod = OnClearDisplayEventFingerprint.method
        val controllerMembers = resolveClearDisplayController(eventMethod) { classDefByOrNull(it) }
        hookClearDisplayCompletion(eventMethod, ClearDisplayCellFingerprint.method)
        val eventClass = eventMethod.parameters[0].type
        val frameMethod = OnRenderFirstFrameBodyFingerprint.method
        val awemeGetter = frameMethod.implementationOrPatchException("Remember clear display")
            .instructions.mapNotNull {
            it.getReference<MethodReference>()
        }.filter {
            it.definingClass == frameMethod.definingClass && it.parameterTypes.isEmpty() &&
                it.returnType == "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
        }.distinctBy { it.toString() }
            .singleOrPatchException("Remember clear display: first-frame Aweme getter")
        val extension = mutableClassDefBy(EXTENSION)
        val panelOriginal = extension.methods.filter { it.name == "onNativePanelApply" }
            .singleOrPatchException("Remember clear display: extension panel controller bridge")
        extension.methods.remove(panelOriginal)
        extension.methods.add(clearDisplayPanelBridge(panelOriginal, controllerMembers))
        val postOriginal = extension.methods.filter { it.name == "postClear" }
            .singleOrPatchException("Remember clear display: extension postClear bridge")
        val post = postOriginal.cloneMutable(additionalRegisters = 5)
        extension.methods.remove(postOriginal)
        extension.methods.add(post)
        post.addInstructions(0, """
            new-instance v0, $eventClass
            move/from16 v1, p0
            const/4 v2, 0x0
            const-string v3, ""
            const-string v4, "long_press"
            invoke-direct { v0, v1, v2, v3, v4 }, $eventClass-><init>(ZILjava/lang/String;Ljava/lang/String;)V
            invoke-static { v0, v1 }, $EXTENSION->beginNativeDispatch(Ljava/lang/Object;Z)V
            invoke-virtual { v0 }, $eventClass->post()Lcom/ss/android/ugc/governance/eventbus/IEvent;
            invoke-static {}, $EXTENSION->finishNativeDispatch()Z
            move-result v0
            return v0
        """)
        val readOriginal = extension.methods.filter { it.name == "readCurrentAweme" }
            .singleOrPatchException("Remember clear display: extension readCurrentAweme bridge")
        val read = readOriginal.cloneMutable(additionalRegisters = 1)
        extension.methods.remove(readOriginal)
        extension.methods.add(read)
        read.addInstructions(0, """
            check-cast p0, @OWNER@
            invoke-virtual/range { p0 .. p0 }, $awemeGetter
            move-result-object v0
            return-object v0
        """.replace("@OWNER@", frameMethod.definingClass))
        eventMethod.addInstruction(0,
            "invoke-static/range { p1 .. p1 }, $EXTENSION->rememberClearDisplayEvent(Ljava/lang/Object;)V")
        frameMethod.addInstruction(0,
            "invoke-static/range { p0 .. p0 }, $EXTENSION->onFirstFrame(Ljava/lang/Object;)V")
        PlayerProgressAidFingerprint.method.addInstruction(0,
            "invoke-static/range { p0 .. p1 }, $EXTENSION->onPlaybackProgress(Ljava/lang/Object;Ljava/lang/String;)V")
        // TikTok's Following stories bubble hides itself as clear display starts and stays gone
        // after it ends. Held to declared builds by FollowingStoriesBubbleAnchorsTest.
        try {
            val bubble = mutableClassDefByOrNull(SKYLIGHT_BUBBLE)
                ?: throw PatchException("Following stories bubble: $SKYLIGHT_BUBBLE is missing")
            checkBubbleList { classDefByOrNull(it) }
            val found = findBubbleClearMode(bubble)
            (found.handler as MutableMethod).hookBubbleClearMode(found)
        } catch (problem: Exception) {
            if (packageMetadata.versionName in declaredVersions()) throw problem
            println("[Remember clear display] Left out the Following stories bubble on " +
                "${packageMetadata.versionName}: ${problem.message}")
        }
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableAutomaticClearDisplay()V")
    }
}
