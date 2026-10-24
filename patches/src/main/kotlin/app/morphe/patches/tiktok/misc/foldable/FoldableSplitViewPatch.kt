/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.foldable

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.patcher.util.getReference
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/foldable/FoldableSplitView;"
private const val FEED_ACTIVITY = "Lcom/ss/android/ugc/aweme/main/MainActivity;"
private object LiveCheck : Fingerprint(
    strings = listOf("isOptCommentSplit").map { string(it, StringComparisonType.CONTAINS) }, returnType = "Z",
    parameters = listOf("Landroid/app/Activity;", "Landroid/content/res/Configuration;"),
)
private object ContainerCheck : Fingerprint(
    strings = listOf("isOptSplitContainer").map { string(it, StringComparisonType.CONTAINS) }, returnType = "Z", parameters = emptyList(),
)

internal const val SPLIT_CONTAINER_MANAGER = "Lcom/ss/android/ugc/aweme/tablet/impl/business/split/split/SplitContainerManager;"

/**
 * The feed fragment's view builder, which registers the feed's split container under a "HOME_"
 * key. Its class is renamed on every build (06ws on 47.1.4, 06wG on 47.4.3, 0arO in PineDrama).
 */
internal object FeedSplitContainerFingerprint : Fingerprint(
    returnType = "Landroid/view/View;",
    parameters = listOf("Lcom/bytedance/ies/uikit/base/AbsFragment;", "Landroid/view/LayoutInflater;"),
    strings = listOf("HOME_").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ -> method.registersSplitContainer() },
)

internal fun Method.registersSplitContainer(): Boolean = implementation?.instructions?.any { instruction ->
    instruction.opcode == Opcode.INVOKE_STATIC && instruction.getReference<MethodReference>()?.let {
        it.definingClass == SPLIT_CONTAINER_MANAGER && it.parameterTypes.firstOrNull()?.toString() == "Ljava/lang/String;"
    } == true
} == true

/**
 * The read of the server value the feed compares with -1 before it registers its split
 * container: `sget-wide`, then `const-wide/16 -1`, then a `cmp-long` of the two. Anything other
 * than one such read, ahead of the registration, fails the patch.
 */
internal fun Method.splitContainerInitRead(): Int {
    val instructions = implementation?.instructions?.toList()
        ?: throw PatchException("Split view: $definingClass->$name has no implementation.")
    val reads = instructions.indices.filter { i ->
        val read = instructions[i]
        val minusOne = instructions.getOrNull(i + 1)
        val compare = instructions.getOrNull(i + 2)
        read.opcode == Opcode.SGET_WIDE &&
            minusOne?.opcode == Opcode.CONST_WIDE_16 && (minusOne as WideLiteralInstruction).wideLiteral == -1L &&
            compare?.opcode == Opcode.CMP_LONG && (compare as ThreeRegisterInstruction).let {
                val value = (read as OneRegisterInstruction).registerA
                val literal = (minusOne as OneRegisterInstruction).registerA
                setOf(it.registerB, it.registerC) == setOf(value, literal)
            }
    }
    val read = reads.singleOrNull() ?: throw PatchException(
        "Split view: the feed compares ${reads.size} server values with -1 before its split container, not one.",
    )
    val registration = instructions.indexOfFirst {
        it.opcode == Opcode.INVOKE_STATIC && it.getReference<MethodReference>()?.definingClass == SPLIT_CONTAINER_MANAGER
    }
    if (registration < read) throw PatchException("Split view: the feed registers no split container after it reads the server value.")
    return read
}

/**
 * Passes the server value through the extension right after it is read (#26). TikTok's server
 * sends 1, and the feed then registers its split container only on a tablet, so on a wide phone
 * window the comments opened over the video though both checks answered yes.
 */
internal fun MutableMethod.registerSplitContainerOnWideWindows(read: Int) {
    val value = getInstruction<OneRegisterInstruction>(read).registerA
    addInstructions(read + 1, """
        invoke-static/range { v$value .. v${value + 1} }, $EXTENSION->splitContainerInit(J)J
        move-result-wide v$value
    """)
}

/** An instance method that takes a configuration change itself, rather than a declaration. */
internal fun Method.isConfigurationChange(): Boolean =
    name == "onConfigurationChanged" &&
        returnType == "V" &&
        parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/res/Configuration;") &&
        !AccessFlags.STATIC.isSet(accessFlags) &&
        implementation != null

/**
 * The first class from [start] up that takes configuration changes itself, and its method, or
 * null when the walk leaves the app before finding one.
 */
internal fun configurationChangeOf(start: String, classDefByOrNull: (String) -> ClassDef?): Method? {
    val seen = mutableSetOf<String>()
    var type: String? = start
    while (type != null && seen.add(type)) {
        val classDef = classDefByOrNull(type) ?: return null
        classDef.methods.firstOrNull { it.isConfigurationChange() }?.let { return it }
        type = classDef.superclass
    }
    return null
}

/**
 * Hands the feed activity's configuration change to the extension first (issue #26). TikTok's
 * feed activity takes size changes itself, so an unfold reaches it as one of these rather than
 * as a new activity, and the comment containers it built for the folded width would stay.
 */
internal fun MutableMethod.handConfigurationChangesToSplitView() = addInstruction(
    0,
    "invoke-static/range { p0 .. p1 }, $EXTENSION->onConfigurationChanged(Landroid/app/Activity;Landroid/content/res/Configuration;)V",
)

@Suppress("unused")
val foldableSplitViewPatch = bytecodePatch(
    name = "Foldable split comment view",
    description = "On a folding phone or tablet, shows comments beside the video instead of over it when the screen is wide enough. Starts off. Turn it on in Metra settings > App behavior.",
) {
    category("Comments")
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())
    dependsOn(sharedExtensionPatch, settingsPatch)
    execute {
        // Found before anything is written.
        val configurationChange = configurationChangeOf(FEED_ACTIVITY) { classDefByOrNull(it) }
            ?: throw PatchException("Split view: nothing between the feed activity and Activity takes configuration changes.")
        val feedContainer = FeedSplitContainerFingerprint.method
        val serverRead = feedContainer.splitContainerInitRead()
        listOf(LiveCheck.method to true, ContainerCheck.method to false).forEach { (method, live) ->
            check(AccessFlags.STATIC.isSet(method.accessFlags)) {
                "Split view: ${method.name} is not static, so p0 is not its first argument."
            }
            method.requireLocals("Split view", 1)
            val call = if (live) "invoke-static/range { p0 .. p1 }, $EXTENSION->shouldForce(Landroid/app/Activity;Landroid/content/res/Configuration;)Z"
                else "invoke-static {}, $EXTENSION->shouldForceContainer()Z"
            method.addInstructions(0, """
                $call
                move-result v0
                if-eqz v0, :stock_split_check
                return v0
                :stock_split_check
                nop
            """)
        }
        feedContainer.registerSplitContainerOnWideWindows(serverRead)
        mutableClassDefBy(configurationChange.definingClass).methods
            .single { it.isConfigurationChange() }
            .handConfigurationChangesToSplitView()
        SettingsStatusLoadFingerprint.method.addInstruction(0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFoldableSplitView()V")
    }
}
