/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.live

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.StringComparisonType
import app.morphe.patcher.string
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.util.findMutableMethodOf
import app.morphe.util.returnEarly
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.SwitchPayload
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val EXTENSION = "Lapp/morphe/extension/tiktok/live/LiveControls;"
private const val RECORD_EXTENSION = "Lapp/morphe/extension/tiktok/live/LiveRecordChip;"
private const val PANEL_THEME = "Lapp/morphe/extension/tiktok/live/LivePanelTheme;"
private const val QUALITY_LIST_COLORS = "Lapp/morphe/extension/tiktok/live/LiveQualityListColors;"

/** A LIVE room page, one fragment per room in the room pager, real-named on 47.1.4. */
internal const val LIVE_PLAY_FRAGMENT = "Lcom/bytedance/android/livesdk/chatroom/ui/LivePlayFragment;"

/** The room model a LIVE page holds, with the creator and the stream addresses, real-named on 47.1.4. */
internal const val LIVE_ROOM_TYPE = "Lcom/bytedance/android/livesdkapi/depend/model/live/Room;"

/**
 * A LIVE room page coming to the front (#37). The pager resumes a page as its room is shown, so the
 * hook hands the page to the recording button, which finds the room (and with it the creator and the
 * stream addresses) in the page's single Room field when the viewer taps Record. The page also
 * names the room's activity, which is where the button goes. The hook only notes the page.
 */
internal object LivePlayFragmentResumeFingerprint : Fingerprint(
    definingClass = LIVE_PLAY_FRAGMENT,
    name = "onResume",
    returnType = "V",
    parameters = listOf(),
)

/**
 * The one field of [fragment] that holds its room. The recording button reads the room from the
 * page by that type, so a page with none or two would leave it recording the wrong LIVE or none.
 */
internal fun liveRoomField(fragment: ClassDef): String {
    val rooms = fragment.fields.filter { it.type == LIVE_ROOM_TYPE }
    return rooms.singleOrNull()?.name
        ?: throw PatchException("LIVE controls: expected one Room field in ${fragment.type}, found ${rooms.map { it.name }}")
}

/** The feed LIVE preview's guide, real-named on 47.0.3, 47.1.3 and 47.1.4. */
internal const val LIVE_PREVIEW_GUIDE_VM = "Lcom/ss/android/ugc/aweme/feed/adapter/widget/guide/LivePreviewGuideEnterVM;"

/**
 * Starts the countdown that takes a LIVE preview in the feed into its room: it checks the feed
 * (homepage_hot for For You, homepage_live for the LIVE tab behind
 * live_preview_page_enable_auto_entering_guide, and it returns on homepage_follow), then either
 * hands a smart countdown to LivePreviewEnterRoomGuideManager or arms a fixed one of
 * live_preview_page_auto_entering_request_delay seconds. When that fires, BottomTipsWidget runs
 * its AutoEnterProgressBar for live_preview_page_auto_entering_guide_duration seconds, and the
 * bar's listener enters the room with "enter_room". Renamed on every build (O63 on 47.0.3, E83 on
 * 47.1.3 and 47.1.4); the only method that loads the enable key. It already returns early on
 * the Following feed and once a countdown has started, so returning at entry is a state TikTok
 * handles: no countdown, no bar, and the preview stays a preview until it's tapped.
 */
internal object LivePreviewAutoEnterFingerprint : Fingerprint(
    definingClass = LIVE_PREVIEW_GUIDE_VM,
    accessFlags = listOf(AccessFlags.PUBLIC, AccessFlags.FINAL),
    returnType = "V",
    parameters = listOf(),
    strings = listOf("live_preview_page_enable_auto_entering_guide", "homepage_follow").map { string(it, StringComparisonType.CONTAINS) },
)

/** TikTok's server setting for its newer LIVE quality picker, real-named on 47.1.4. */
internal const val LIVE_RESOLUTION_SELECTION_SETTING =
    "Lcom/bytedance/android/livesdk/livesetting/watchlive/LiveShowResolutionSelectionSetting;"

/**
 * Whether a room's menu offers the newer quality picker, LiveTalentDefinitionSelectionDialog.
 * The menu builder (X.0hG2.LIZ on 47.1.4) offers the older picker only when the stream itself asks
 * for it (LiveCoreSDKData.Options.showQualityButton), and the newer one only in a video room
 * whose stream doesn't, when this answers true. It answers the server's
 * enable_resolution_selection, false unless TikTok turns it on, which is why only some rooms
 * offer a quality at all (#57).
 */
internal object LiveResolutionSelectionFingerprint : Fingerprint(
    definingClass = LIVE_RESOLUTION_SELECTION_SETTING,
    name = "isEnable",
    returnType = "Z",
    parameters = listOf(),
)

/**
 * Whether a pick in that picker carries to the next room: when this answers true,
 * PullStreamService hands a video room the last quality picked instead of the stream's default.
 * It answers isEnable() and the server's persist_across_rooms.
 */
internal object LiveResolutionPersistFingerprint : Fingerprint(
    definingClass = LIVE_RESOLUTION_SELECTION_SETTING,
    name = "needPersistAcrossRooms",
    returnType = "Z",
    parameters = listOf(),
)

/** TikTok's server setting for the clear screen entry in a portrait LIVE room's menu, real-named on 47.1.4. */
internal const val LIVE_CLEAR_SCREEN_ENTRY_SETTING =
    "Lcom/bytedance/android/livesdk/setting/LiveClearScreenFeatureEntryCompletionSetting;"

/**
 * Whether a portrait LIVE room's menu offers clear screen, which hides the room's chat, gifts and
 * buttons and leaves the stream on its own (#57). It answers the server's
 * live_clear_screen_feature_entry_completion, false unless TikTok turns it on. The room's panel
 * builder (PortraitAudienceInteractionFragment.n00 on 47.1.4) adds the entry, labeled clear or
 * exit by IClearScreenService's state, only when this answers true, and the room's widget loader
 * reads it the same way. The entry turns clear screen on and off through that service.
 */
internal object LiveClearScreenEntryFingerprint : Fingerprint(
    definingClass = LIVE_CLEAR_SCREEN_ENTRY_SETTING,
    name = "getValue",
    returnType = "Z",
    parameters = listOf(),
)

/** The host side of the LIVE SDK's resources, real-named on 47.1.4. */
internal const val LIVE_HOST_RESOURCE = "Lcom/ss/android/ugc/aweme/live/livehostimpl/LiveHostResource;"

/**
 * Whether a LIVE panel takes the LIVE design system's dark theme (X.0Jz4.LIZ on 47.1.4, in the
 * theme provider the host hands the LIVE SDK). Every theme-aware LIVE fragment and dialog asks it
 * before picking the dark or the light overlay. It answers TikTok's dark mode and then the page
 * TikTok finds for the panel's context, and the LIVE room pages (LivePlayRootFragment,
 * BaseLiveSdkSceneFragment) and LIVE activities always say no, so the share menu, the fan club and
 * the first gift dialog opened light in a dark app (#159). The provider is the class holding a
 * LiveHostResource; this answer is the one whose first call is the provider's own dark mode check,
 * which tells it from the provider's other (Context)Z answer.
 */
internal object LiveDarkThemeFingerprint : Fingerprint(
    returnType = "Z",
    parameters = listOf("Landroid/content/Context;"),
    custom = { method, classDef -> isLiveDarkThemeAnswer(method, classDef) },
)

internal fun isLiveDarkThemeAnswer(method: Method, classDef: ClassDef): Boolean {
    if (classDef.fields.none { it.type == LIVE_HOST_RESOURCE }) return false
    val instructions = method.implementation?.instructions?.toList() ?: return false
    val first = instructions.firstOrNull() ?: return false
    val own = (first as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return first.opcode == Opcode.INVOKE_VIRTUAL && own.definingClass == classDef.type &&
        own.returnType == "Z" && own.parameterTypes.map(CharSequence::toString) == listOf("Landroid/content/Context;") &&
        instructions.any {
            it.opcode == Opcode.INVOKE_STATIC &&
                ((it as ReferenceInstruction).reference as MethodReference).definingClass == LIVE_HOST_RESOURCE
        }
}

/** The base of every themed LIVE dialog (LiveDialogFragment and the rest), real-named on 47.1.4. */
internal const val LIVE_THEMED_DIALOG = "Lcom/bytedance/android/live/design/resource/theme/LiveThemeAwareDialogFragment;"

/** The base of LIVE's sheet pages (LiveSheetFragment) and of the room's own fragments, real-named on 47.1.4. */
internal const val LIVE_THEMED_FRAGMENT = "Lcom/bytedance/android/live/design/resource/theme/LiveThemeAwareFragment;"

/** LIVE's sheet page, the fan club sheets and most newer LIVE panels, real-named on 47.1.4. */
internal const val LIVE_SHEET_FRAGMENT = "Lcom/bytedance/android/live/design/view/sheet/LiveSheetFragment;"

/**
 * The layout inflater a themed LIVE dialog or fragment hands its views (#159). In a dark app it
 * inflates through TikTok's light LIVE theme, with force, when the page says light, and through
 * its own unwrapped inflater when the page says dark, which leaves the room's light colors in
 * place: TikTok only adds the dark theme to the room's theme without force. Every return hands
 * its inflater to the extension, which wraps it in the panel's own (dark) overlay while Dark LIVE
 * panels is on.
 */
internal object LiveThemedDialogInflaterFingerprint : Fingerprint(
    definingClass = LIVE_THEMED_DIALOG,
    name = "onGetLayoutInflater",
    returnType = "Landroid/view/LayoutInflater;",
    parameters = listOf("Landroid/os/Bundle;"),
)

/** [LiveThemedDialogInflaterFingerprint] for LIVE's fragments; the extension only touches sheet pages. */
internal object LiveThemedFragmentInflaterFingerprint : Fingerprint(
    definingClass = LIVE_THEMED_FRAGMENT,
    name = "onGetLayoutInflater",
    returnType = "Landroid/view/LayoutInflater;",
    parameters = listOf("Landroid/os/Bundle;"),
)

/** The older LIVE quality picker, Share > Video quality in rooms whose stream asks for it, real-named on 47.1.4. */
internal const val LIVE_DEFINITION_DIALOG = "Lcom/bytedance/android/livesdk/definition/LiveDefinitionSelectionDialog;"

/** The quality list's view being set up, which the extension watches so its rows stay readable in dark mode. */
internal object LiveDefinitionListFingerprint : Fingerprint(
    definingClass = LIVE_DEFINITION_DIALOG,
    name = "onViewCreated",
    returnType = "V",
    parameters = listOf("Landroid/view/View;", "Landroid/os/Bundle;"),
)

/**
 * Every return-object in [method], last first, with the register it returns. A return that
 * isn't a plain v0..v15 register, or a method whose `this` sits above v15, can't take the
 * four-bit call the hook adds, so it's refused instead of written.
 */
internal fun inflaterReturns(method: Method): List<Pair<Int, Int>> {
    val body = method.implementation ?: throw PatchException("LIVE controls: ${method.definingClass} has no inflater body")
    if (body.registerCount - 2 > 15) throw PatchException("LIVE controls: ${method.definingClass}'s inflater keeps this above v15")
    val returns = body.instructions.withIndex()
        .filter { it.value.opcode == Opcode.RETURN_OBJECT }
        .map { it.index to (it.value as OneRegisterInstruction).registerA }
        .reversed()
    if (returns.isEmpty() || returns.any { it.second > 15 }) {
        throw PatchException("LIVE controls: ${method.definingClass}'s inflater returns don't fit the hook: $returns")
    }
    return returns
}

/** Hands every inflater [inflater] returns to the extension first, on each return's own label. */
internal fun hookPanelInflater(inflater: MutableMethod) {
    inflaterReturns(inflater).forEach { (index, register) ->
        inflater.addInstructionsAtControlFlowLabel(
            index,
            """
                invoke-static {p0, v$register}, $PANEL_THEME->darkLivePanel(Ljava/lang/Object;Landroid/view/LayoutInflater;)Landroid/view/LayoutInflater;
                move-result-object v$register
            """,
        )
    }
}

/** The LIVE room activity, real-named on 47.4.3. */
internal const val LIVE_PLAY_ACTIVITY = "Lcom/ss/android/ugc/aweme/live/LivePlayActivity;"

/**
 * The LIVE room activity's setTheme (#159). It ignores the theme it's handed and builds the
 * room's own: a base room theme, then styles for how the room was entered and its color scheme,
 * each applied with force and none of them dark. So TikTok's generic sheets over a room, Share
 * among them, open white in a dark app, which the LIVE panel overlay can't reach.
 */
internal object LivePlayActivityThemeFingerprint : Fingerprint(
    definingClass = LIVE_PLAY_ACTIVITY,
    name = "setTheme",
    returnType = "V",
    parameters = listOf("I"),
)

/** The base of TikTok's screens, real-named on 47.4.3. */
internal const val BASE_ACTIVITY = "Lcom/bytedance/ies/foundation/activity/BaseActivity;"

/**
 * TikTok's dark mode for its screens (X.060v.LIZ on 47.4.3): in dark mode, a screen that says it
 * supports dark mode gets TikTok's dark app theme through setTheme. The room says no. The style
 * is read from here at patch time.
 */
internal object AppDarkThemeFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(BASE_ACTIVITY),
    custom = { method, _ -> AccessFlags.STATIC.isSet(method.accessFlags) && appDarkTheme(method) != null },
)

/**
 * The style [method] hands setTheme after it asks the screen isDarkModeSupported, read from the
 * constant last written to setTheme's argument; null when [method] isn't TikTok's dark mode.
 */
internal fun appDarkTheme(method: Method): Int? {
    val instructions = method.implementation?.instructions?.toList() ?: return null
    fun calls(at: Int, name: String, parameters: List<String>, returnType: String): Boolean {
        val call = (instructions[at] as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return instructions[at].opcode == Opcode.INVOKE_VIRTUAL && call.name == name && call.returnType == returnType &&
            call.parameterTypes.map(CharSequence::toString) == parameters
    }
    val asked = instructions.indices.firstOrNull { calls(it, "isDarkModeSupported", listOf(), "Z") } ?: return null
    val set = (asked + 1 until instructions.size).firstOrNull { calls(it, "setTheme", listOf("I"), "V") } ?: return null
    val argument = (instructions[set] as FiveRegisterInstruction).registerD
    val written = (set - 1 downTo asked + 1).map { instructions[it] }
        .firstOrNull { it is OneRegisterInstruction && it.registerA == argument && it.opcode.setsRegister() }
    return (written as? NarrowLiteralInstruction)?.narrowLiteral?.takeIf { it ushr 24 == 0x7f }
}

/**
 * Puts the room hook right after the room's last setTheme, at the branch join a skipped cold start
 * style lands on too. Every room theme setTheme hands over sits on TikTok's light app theme and
 * re-applies all of it, so the dark app theme has to come after the last of them. The room's color
 * scheme styles still apply after the hook, so its own accents stay.
 */
internal fun hookRoomTheme(setTheme: MutableMethod) {
    val instructions = setTheme.implementation?.instructions?.toList()
        ?: throw PatchException("LIVE controls: the room's setTheme has no body")
    val join = roomThemeJoin(instructions)
    if (join < 0) throw PatchException("LIVE controls: the room's setTheme never sets a room theme")
    setTheme.addInstructionsAtControlFlowLabel(
        join,
        "invoke-static/range {p0 .. p0}, $PANEL_THEME->darkLiveRoom(Landroid/app/Activity;)V",
    )
}

/**
 * Where the room's own themes are done: right after the last super setTheme the code falls
 * through from. One room theme sits at the method's end and jumps back up (on 47.4.3 the one for
 * a room entered without the smooth transition), so it reaches this point through the join.
 */
internal fun roomThemeJoin(instructions: List<Instruction>): Int {
    val falls = instructions.indices.filter { at ->
        val call = (instructions[at] as? ReferenceInstruction)?.reference as? MethodReference
        instructions[at].opcode == Opcode.INVOKE_SUPER && call?.name == "setTheme" &&
            call.parameterTypes.map(CharSequence::toString) == listOf("I") &&
            instructions.getOrNull(at + 1)?.opcode !in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
    }
    return falls.lastOrNull()?.plus(1) ?: -1
}

/**
 * Hands the quality list's view to the extension as the dialog sets it up. The method keeps the
 * view above v15 on 47.1.4, so the call takes the range form.
 */
internal fun hookQualityList(viewCreated: MutableMethod) {
    viewCreated.addInstruction(
        0,
        "invoke-static/range {p1 .. p1}, $QUALITY_LIST_COLORS->readableQualityList(Landroid/view/View;)V",
    )
}

/** The LIVE profile card's header cell, the bio among it, real-named on 47.4.3. */
internal const val LIVE_PROFILE_HEADER = "Lcom/bytedance/android/livesdk/profile/cell/HeaderCell;"

private const val COLOR_SPAN = "Landroid/text/style/ForegroundColorSpan;"

/**
 * The profile card's bio being shown (HeaderCell.LJIJJLI on 47.4.3, #159). A translated bio ends
 * in a "See original" link. Its ForegroundColorSpan gets the right color from the card's theme,
 * but the link also carries a replacement span (X.0Y3Y), which Android draws without color spans,
 * in the view's own text color: the light theme's dark one, on the dark card.
 */
internal object LiveProfileBioFingerprint : Fingerprint(
    definingClass = LIVE_PROFILE_HEADER,
    returnType = "V",
    parameters = listOf(),
    custom = { method, _ -> bioLinkColor(method) != null },
)

/** The link color call at [call], the register its result moves into, and the one holding the bio view. */
internal class BioLinkColor(val call: Int, val color: Int, val view: Int)

/**
 * In [method], the static (int, Context) color call feeding the bio link's ForegroundColorSpan, the
 * register its result moves into and the span takes, and the register holding the bio view: the
 * receiver of the setSuffixText the link text goes through, read from a field of the view's type
 * before the call and written by nothing up to setSuffixText, with no branch, switch case or
 * handler landing between the read and the instruction after the result, where one would skip
 * the hook or reach it with other registers. Null when [method] isn't the bio.
 */
internal fun bioLinkColor(method: Method): BioLinkColor? {
    val code = method.implementation?.instructions?.toList() ?: return null
    fun ref(at: Int) = (code[at] as? ReferenceInstruction)?.reference
    fun params(at: Int) = (ref(at) as? MethodReference)?.parameterTypes?.map(CharSequence::toString)
    fun writes(at: Int, register: Int): Boolean {
        val written = code[at] as? OneRegisterInstruction ?: return false
        return (code[at].opcode.setsRegister() && written.registerA == register) ||
            (code[at].opcode.setsWideRegister() && written.registerA + 1 == register)
    }
    val span = code.indices.firstOrNull {
        code[it].opcode == Opcode.NEW_INSTANCE && (ref(it) as? TypeReference)?.type == COLOR_SPAN
    } ?: return null
    val made = (span + 1 until code.size).firstOrNull {
        code[it].opcode == Opcode.INVOKE_DIRECT && (ref(it) as? MethodReference)?.definingClass == COLOR_SPAN
    } ?: return null
    val call = (made - 1 downTo span + 1).firstOrNull {
        code[it].opcode == Opcode.INVOKE_STATIC && (ref(it) as? MethodReference)?.returnType == "I" &&
            params(it) == listOf("I", "Landroid/content/Context;")
    } ?: return null
    val suffix = (made + 1 until code.size).firstOrNull {
        code[it].opcode == Opcode.INVOKE_VIRTUAL && (ref(it) as? MethodReference)?.name == "setSuffixText" &&
            params(it) == listOf("Ljava/lang/CharSequence;")
    } ?: return null
    if (code.getOrNull(call + 1)?.opcode != Opcode.MOVE_RESULT) return null
    val color = (code[call + 1] as OneRegisterInstruction).registerA
    val init = code[made] as? FiveRegisterInstruction ?: return null
    if (init.registerCount != 2 || init.registerD != color || (call + 2 until made).any { writes(it, color) }) return null
    val view = (code[suffix] as? FiveRegisterInstruction)?.registerC ?: return null
    val read = (call - 1 downTo 0).firstOrNull { writes(it, view) } ?: return null
    if (code[read].opcode != Opcode.IGET_OBJECT ||
        (ref(read) as? FieldReference)?.type != (ref(suffix) as MethodReference).definingClass
    ) return null
    if ((call + 1 until suffix).any { writes(it, view) }) return null
    val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    val byAddress = code.indices.associateBy { addresses[it] }
    // A switch's offset points at its payload, whose cases are offsets from the switch itself.
    val targets = code.indices.flatMap { at ->
        val target = (code[at] as? OffsetInstruction)?.let { addresses[at] + it.codeOffset } ?: return@flatMap emptyList()
        val payload = byAddress[target]?.let { code[it] as? SwitchPayload }
        payload?.switchElements?.map { addresses[at] + it.offset } ?: listOf(target)
    }.toSet() + method.implementation!!.tryBlocks.flatMap { block -> block.exceptionHandlers.map { it.handlerCodeAddress } }
    if ((read + 1..call + 2).any { addresses[it] in targets }) return null
    return BioLinkColor(call, color, view)
}

/**
 * Hands the bio link's color and the bio view to the extension right after the color is picked.
 * While Dark LIVE panels is on in dark mode, the extension makes it the view's text color, which
 * the link's replacement span draws with. The hook writes no register. The color and the view
 * are both named by non-range invokes (the span's init and setSuffixText), so they're v0..v15
 * and fit the hook's call.
 */
internal fun hookBioLink(bio: MutableMethod) {
    val link = bioLinkColor(bio) ?: throw PatchException("LIVE controls: the profile card's bio link color wasn't found")
    bio.addInstructions(
        link.call + 2,
        "invoke-static {v${link.color}, v${link.view}}, $PANEL_THEME->bioLinkColor(ILjava/lang/Object;)V",
    )
}

internal const val PULL_STREAM_SERVICE ="Lcom/bytedance/android/live/livepullstream/PullStreamService;"
private const val LIVE_MODE = "Lcom/bytedance/android/livesdkapi/depend/model/live/LiveMode;"

/**
 * The pull stream service's starting quality for a room that's created ahead (rs2 on 47.1.4):
 * when [LiveResolutionPersistFingerprint] answers true it hands a video room TikTok's last
 * quality pick, a static string that lives only until TikTok closes, and the stream's default
 * while that's still null.
 */
internal object LiveStartQualityFingerprint : Fingerprint(
    definingClass = PULL_STREAM_SERVICE,
    returnType = "Ljava/lang/String;",
    parameters = listOf("Ljava/lang/String;", LIVE_MODE, "Z"),
    custom = { method, _ -> method.calls(LIVE_RESOLUTION_SELECTION_SETTING, "needPersistAcrossRooms") },
)

/**
 * The LIVE player's start (X.0K9X.LJJJZ on 47.1.4). When [LiveResolutionPersistFingerprint]
 * answers true it lists the stream's quality keys and starts at the last pick only when the
 * stream has it, at the stream's default otherwise.
 */
internal object LivePlayerStartFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L", "Z", "Ljava/lang/Long;"),
    strings = listOf(" setDataSource()", "streamData is null when get video resolution").map { string(it, StringComparisonType.CONTAINS) },
)

/**
 * TikTok's last LIVE quality pick (#57): the static [field] the two starts read, and the one
 * service method ([setter], vJ1 on 47.1.4) the picker writes it through.
 */
internal data class LiveQualityPick(val field: String, val setter: Method)

private fun FieldReference.key() = "$definingClass->$name:$type"

/** Every `sget-object` of [field] in this method. */
internal fun Method.readsOf(field: String): List<Int> {
    val instructions = implementation?.instructions?.toList().orEmpty()
    return instructions.indices.filter { index ->
        instructions[index].opcode == Opcode.SGET_OBJECT &&
            ((instructions[index] as ReferenceInstruction).reference as FieldReference).key() == field
    }
}

/**
 * The pick and its setter, from the start [quality] answer's single static string read. Throws
 * unless the service has exactly one method that only stores its argument there: the pick is
 * saved at that call, so a second writer would leave a pick unsaved.
 */
internal fun liveQualityPick(service: ClassDef, quality: Method): LiveQualityPick {
    val instructions = quality.implementation?.instructions?.toList().orEmpty()
    val fields = instructions.filter { it.opcode == Opcode.SGET_OBJECT }
        .map { (it as ReferenceInstruction).reference as FieldReference }
        .filter { it.type == "Ljava/lang/String;" }
        .map { it.key() }.distinct()
    val field = fields.singleOrNull()
        ?: throw PatchException("LIVE controls: expected one quality pick read in ${quality.name}, found $fields")
    val setters = service.methods.filter { method ->
        val body = method.implementation?.instructions?.toList().orEmpty()
        val store = body.firstOrNull()
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
            method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
            body.size == 2 && store?.opcode == Opcode.SPUT_OBJECT &&
            ((store as ReferenceInstruction).reference as FieldReference).key() == field &&
            (store as OneRegisterInstruction).registerA == method.implementation!!.registerCount - 1 &&
            body[1].opcode == Opcode.RETURN_VOID
    }
    val setter = setters.singleOrNull()
        ?: throw PatchException("LIVE controls: expected one quality pick setter in ${service.type}, found ${setters.map { it.name }}")
    return LiveQualityPick(field, setter)
}

/**
 * Where the player start's game and PC LIVE branch ends, and what its regular branch switches a
 * room's quality with (#57). Rooms that LiveMode.isWatchGameLiveOrPCLive answers true for set their
 * stream up in a branch of their own (when TikTok's game quality setting is on or the room's
 * options say so) whose last call, on a Pair, is followed by a goto past the pick read, so such a
 * room opens at the stream's default even with a pick. [end] is that goto.
 * After the pick read the regular branch stores the picked key in [quality] and calls
 * [setter] on [player] with [data] and that key.
 */
internal class GameRoomStart(
    val end: Int,
    val quality: FieldReference,
    val data: FieldReference,
    val player: FieldReference,
    val setter: MethodReference,
) {
    fun quality() = quality.key()
    fun data() = data.key()
    fun player() = player.key()
    fun setter() = "${setter.definingClass}->${setter.name}(${setter.parameterTypes.joinToString("")})${setter.returnType}"
}

internal fun gameRoomStart(start: Method, pickRead: Int): GameRoomStart {
    val instructions = start.implementation?.instructions?.toList().orEmpty()
    fun refuse(what: String): Nothing = throw PatchException("LIVE controls: the LIVE player start has $what")
    val store = (pickRead until instructions.size).firstOrNull { index ->
        val field = (instructions[index] as? ReferenceInstruction)?.reference as? FieldReference
        instructions[index].opcode == Opcode.IPUT_OBJECT && field?.definingClass == start.definingClass &&
            field.type == "Ljava/lang/String;"
    } ?: refuse("no store of the picked quality after the pick read")
    val switchAt = (store until instructions.size).firstOrNull { index ->
        val call = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference
        instructions[index].opcode == Opcode.INVOKE_INTERFACE && call?.returnType == "V" &&
            call.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "Ljava/lang/String;")
    } ?: refuse("no quality switch after the picked quality's store")
    val switch = instructions[switchAt] as FiveRegisterInstruction
    fun fieldInto(register: Int): FieldReference = (switchAt - 1 downTo store + 1).asSequence()
        .map { instructions[it] }
        .firstOrNull { it.opcode == Opcode.IGET_OBJECT && (it as TwoRegisterInstruction).registerA == register }
        ?.let { (it as ReferenceInstruction).reference as FieldReference }
        ?: refuse("no field read for v$register before its quality switch")
    val quality = fieldInto(switch.registerE)
    if (quality.key() != (((instructions[store] as ReferenceInstruction).reference as FieldReference).key())) {
        refuse("a quality switch that doesn't use the stored pick")
    }
    val end = (0 until pickRead).firstOrNull { index ->
        val call = (instructions[index] as? ReferenceInstruction)?.reference as? MethodReference
        instructions[index].opcode == Opcode.INVOKE_VIRTUAL && call?.definingClass == start.definingClass &&
            call.parameterTypes.map(CharSequence::toString) == listOf("Lkotlin/Pair;") &&
            instructions.getOrNull(index + 1)?.opcode in setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
    } ?: refuse("no game LIVE branch ending before the pick read")
    return GameRoomStart(
        end + 1,
        quality,
        fieldInto(switch.registerD),
        fieldInto(switch.registerC),
        (instructions[switchAt] as ReferenceInstruction).reference as MethodReference,
    )
}

/**
 * Where the player start's regular branch leaves LIVE Studio rooms out of the quality pick (#57):
 * it compares the room's mode with LiveMode.LIVE_STUDIO and jumps past the pick read when they
 * match. LIVE Studio is TikTok's PC streaming app, and on the S22 the gaming rooms from the LIVE
 * button were LIVE Studio rooms whose game branch was off, so they opened at AUTO whatever was
 * picked. Returns the index of that LIVE_STUDIO read; the if-eq follows it.
 */
internal fun liveStudioSkip(start: Method, from: Int, pickRead: Int): Int {
    val instructions = start.implementation?.instructions?.toList().orEmpty()
    return (from until pickRead).singleOrNull { index ->
        val field = (instructions[index] as? ReferenceInstruction)?.reference as? FieldReference
        instructions[index].opcode == Opcode.SGET_OBJECT && field?.definingClass == LIVE_MODE &&
            field.name == "LIVE_STUDIO" && instructions.getOrNull(index + 1)?.opcode == Opcode.IF_EQ
    } ?: throw PatchException("LIVE controls: the LIVE player start no longer leaves LIVE Studio rooms out of the pick")
}

internal const val PUSH_STREAM_QUALITY ="Lcom/bytedance/android/live/broadcast/model/PushStreamInfo\$Quality;"
private const val AUDIENCE_SHARE_SCREEN_DEFINITION =
    "Lcom/bytedance/android/livesdk/dataChannel/AudienceShareScreenDefinition;"

/**
 * A pick in the older LIVE quality picker going through (#57). Share > Video quality opens
 * LiveDefinitionSelectionDialog when the stream asks for a quality button
 * (LiveCoreSDKData.Options.showQualityButton), and the newer picker only in rooms whose stream
 * doesn't. Its list adapter asks the player to switch, and the switch's callback posts this with
 * the result (X.14HP.LIZ on 47.1.4, traced as LiveDefinitionSelectionDialog's
 * handleAudienceItemSelect). When the switch went through it copies the picked row's sdk key into
 * a PushStreamInfo.Quality for the room's AudienceShareScreenDefinition. It never calls the pull
 * stream service's setter the newer picker writes, so its picks were never saved: picking
 * Original there on the S22 left the saved pick at hd.
 */
internal object LiveDefinitionPickAppliedFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(),
    strings = listOf("sdk_key").map { string(it, StringComparisonType.CONTAINS) },
    custom = { method, _ ->
        method.references().any {
            it is FieldReference && it.definingClass == PUSH_STREAM_QUALITY && it.name == "sdkKey"
        } && method.references().any { it.toString() == AUDIENCE_SHARE_SCREEN_DEFINITION }
    },
)

/**
 * Where the older picker's pick is known: the first store of the picked sdk key, at [index], from
 * [register]. Throws unless that store comes after the method's check of its own result flag, so
 * only a switch that went through is saved, and before anything writes the register again.
 */
internal data class DefinitionPickStore(val index: Int, val register: Int)

internal fun definitionPickStore(applied: Method): DefinitionPickStore {
    val instructions = applied.implementation?.instructions?.toList().orEmpty()
    val store = instructions.indexOfFirst {
        it.opcode == Opcode.IPUT_OBJECT &&
            ((it as ReferenceInstruction).reference as FieldReference).let { field ->
                field.definingClass == PUSH_STREAM_QUALITY && field.name == "sdkKey"
            }
    }
    if (store < 0) throw PatchException("LIVE controls: ${applied.definingClass}->${applied.name} stores no sdk key")
    // The result flag: a boolean field of the runnable itself, read and branched on before the store.
    val flag = instructions.take(store).indexOfFirst {
        it.opcode == Opcode.IGET_BOOLEAN &&
            ((it as ReferenceInstruction).reference as FieldReference).definingClass == applied.definingClass
    }
    val flagRegister = (instructions.getOrNull(flag) as? OneRegisterInstruction)?.registerA
    val checked = flag >= 0 && instructions.subList(flag + 1, store).any {
        it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA == flagRegister
    }
    if (!checked) {
        throw PatchException(
            "LIVE controls: ${applied.definingClass}->${applied.name} no longer checks its result before storing the pick",
        )
    }
    return DefinitionPickStore(store, (instructions[store] as TwoRegisterInstruction).registerA)
}

/** The For You LIVE preview's Service+ row ("Service+ | Ask me anything!"), real-named on 47.1.4. */
internal const val SERVICE_PLUS_ROW =
    "Lcom/ss/android/ugc/aweme/feed/adapter/widget/serviceplus/PreviewServicePlusBottomInfoWidget;"
internal const val LIVE_ROOM_STRUCT = "Lcom/ss/android/ugc/aweme/feed/model/live/LiveRoomStruct;"
internal const val SMB_LIVE_PREVIEW = "Lcom/ss/android/ugc/aweme/feed/model/live/SMBLivePreview;"
private const val BASE_WIDGET = "Lcom/bytedance/android/widget/Widget;"

/**
 * Where the Service+ row takes a LIVE (#94). The row's bind ([method], f0 on 47.1.4) reads the
 * room's smbLivePreview, keeps it in a field of its own ([store] is that iput-object, [preview]
 * the register it stores and [row] the row itself) and shows it with the next call. That show
 * returns without hiding the row when the room has no Service+ item, and only the row's [hide]
 * (V0, run as a preview leaves the screen) takes it away, so a preview bound to the next LIVE
 * without leaving first kept the last LIVE's row.
 */
internal data class ServicePlusBind(
    val method: Method,
    val store: Int,
    val preview: Int,
    val row: Int,
    val hide: Method,
)

private fun Method.references() = implementation?.instructions?.toList().orEmpty()
    .mapNotNull { (it as? ReferenceInstruction)?.reference }

private fun Method.calls(owner: String, name: String) = references()
    .any { it is MethodReference && it.definingClass == owner && it.name == name }

/**
 * The row's bind and hide, matched by shape. Throws when either isn't there exactly once, or the
 * bind no longer stores the room's Service+ preview right before a plain show and a return: the
 * hook writes the preview's register after the store, which is only safe when nothing reads it
 * again.
 */
internal fun servicePlusBind(row: ClassDef): ServicePlusBind {
    val instance = row.methods.filter {
        it.implementation != null && !AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.isEmpty() && it.returnType == "V"
    }
    val binds = instance.filter { method ->
        method.references().any { it is FieldReference && it.definingClass == LIVE_ROOM_STRUCT && it.name == "smbLivePreview" }
    }
    val bind = binds.singleOrNull()
        ?: throw PatchException("LIVE controls: expected one Service+ row bind, found ${binds.map { it.name }}")
    val instructions = bind.implementation!!.instructions.toList()
    val stores = instructions.indices.filter { index ->
        val field = (instructions[index] as? ReferenceInstruction)?.reference as? FieldReference
        instructions[index].opcode == Opcode.IPUT_OBJECT && field != null &&
            field.definingClass == row.type && field.type == SMB_LIVE_PREVIEW
    }
    val store = stores.singleOrNull()
        ?: throw PatchException("LIVE controls: expected one Service+ preview store in ${bind.name}, found ${stores.size}")
    val put = instructions[store] as TwoRegisterInstruction
    val self = bind.implementation!!.registerCount - 1
    val show = instructions.getOrNull(store + 1)
    val showCall = (show as? ReferenceInstruction)?.reference as? MethodReference
    val plainShow = show is FiveRegisterInstruction && showCall != null &&
        show.opcode == Opcode.INVOKE_VIRTUAL && showCall.definingClass == row.type &&
        showCall.parameterTypes.isEmpty() && showCall.returnType == "V" &&
        show.registerCount == 1 && show.registerC == self
    if (put.registerB != self || put.registerA == self || !plainShow ||
        instructions.getOrNull(store + 2)?.opcode != Opcode.RETURN_VOID
    ) {
        throw PatchException("LIVE controls: the Service+ row bind ${bind.name} has an unexpected shape")
    }
    // Called with invoke-virtual, so a private one wouldn't verify.
    val hides = instance.filter { method ->
        method !== bind && !AccessFlags.PRIVATE.isSet(method.accessFlags) &&
            method.calls("Landroid/os/Handler;", "removeCallbacksAndMessages") &&
            method.calls(BASE_WIDGET, "hide") && !method.calls(BASE_WIDGET, "show")
    }
    val hide = hides.singleOrNull()
        ?: throw PatchException("LIVE controls: expected one Service+ row hide, found ${hides.map { it.name }}")
    return ServicePlusBind(bind, store, put.registerA, self, hide)
}

/** The LIVE room's top-right viewer count, real-named on 47.0.3, 47.1.3 and 47.1.4. */
internal const val ONLINE_AUDIENCE_RANK_WIDGET = "Lcom/bytedance/android/livesdk/rank/impl/widget/OnlineAudienceRankWidget;"

/** How many places the widget turns the viewer count into text; the same on all three builds. */
internal const val VIEWER_COUNT_SITES = 4

/**
 * The viewer count in a programmed LIVE (a scheduled show with a lineup), collapsed, expanded and
 * in landscape; real-named on 47.0.3, 47.1.3 and 47.1.4. Each formats its count once, through the
 * same abbreviator as [ONLINE_AUDIENCE_RANK_WIDGET].
 */
internal val PROGRAMMED_AUDIENCE_WIDGETS = listOf(
    "Lcom/bytedance/android/livesdk/programmedlive/ui/ProgrammedLiveOnlineAudienceCollapseWidget;",
    "Lcom/bytedance/android/livesdk/programmedlive/ui/ProgrammedLiveOnlineAudienceExpandWidget;",
    "Lcom/bytedance/android/livesdk/programmedlive/ui/ProgrammedLiveOnlineAudienceLandscapeWidget;",
)

/**
 * One place a widget formats the viewer count: `int-to-long vLow, vCount`, a static (J)String
 * formatter called on the pair (vLow, vLow + 1) and `move-result-object vResult`. Then either
 * `sget-object Locale.ENGLISH` and `toUpperCase` on vResult (the room's top-right count) or a
 * (String)String wrap of vResult (the programmed widgets' text direction wrap). [formatIndex] is
 * the formatter call.
 */
internal data class ViewerCountSite(
    val method: Method,
    val formatIndex: Int,
    val pairLow: Int,
    val result: Int,
) {
    /** The formatter, as `class->name`. */
    val formatter: String
        get() = ((method.implementation!!.instructions.toList()[formatIndex] as ReferenceInstruction).reference as MethodReference)
            .let { "${it.definingClass}->${it.name}" }
}

/**
 * Every call in [widget] to a static (J)Ljava/lang/String; helper that isn't the JDK's, or only
 * those to [formatter] when given. That helper is the "K+", "M+" and "B+" abbreviator (X.0LE4.LIZJ
 * on 47.0.3, X.0LDD on 47.1.3, X.0LDH on 47.1.4), renamed per build, so the shape is matched, not
 * the name. Throws if any such call has a shape the hook can't take: a site the patch skipped would
 * keep showing the rounded count with nothing to say so.
 */
internal fun viewerCountSites(widget: ClassDef, formatter: String? = null): List<ViewerCountSite> {
    val sites = mutableListOf<ViewerCountSite>()
    for (method in widget.methods) {
        val instructions = method.implementation?.instructions?.toList() ?: continue
        instructions.forEachIndexed { index, instruction ->
            val call = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                ?: return@forEachIndexed
            if (instruction.opcode != Opcode.INVOKE_STATIC && instruction.opcode != Opcode.INVOKE_STATIC_RANGE) {
                return@forEachIndexed
            }
            if (call.returnType != "Ljava/lang/String;" ||
                call.parameterTypes.map(CharSequence::toString) != listOf("J")
            ) {
                return@forEachIndexed
            }
            if (call.definingClass.startsWith("Ljava/")) return@forEachIndexed
            if (formatter != null && "${call.definingClass}->${call.name}" != formatter) return@forEachIndexed
            val where = "${widget.type}->${method.name} at $index"
            val invoke = instruction as? FiveRegisterInstruction
                ?: throw PatchException("LIVE controls: viewer count call is not a plain invoke at $where")
            val low = invoke.registerC
            val widened = instructions.getOrNull(index - 1)
            val moved = instructions.getOrNull(index + 1)
            val result = (moved as? OneRegisterInstruction)?.registerA
            val shaped = invoke.registerCount == 2 && invoke.registerD == low + 1 &&
                widened?.opcode == Opcode.INT_TO_LONG &&
                (widened as TwoRegisterInstruction).registerA == low &&
                moved?.opcode == Opcode.MOVE_RESULT_OBJECT && result != null &&
                (upperCased(instructions, index + 2, result) || wrapped(instructions, index + 2, result))
            if (!shaped) throw PatchException("LIVE controls: viewer count call has an unexpected shape at $where")
            sites += ViewerCountSite(method, index, low, result!!)
        }
    }
    return sites
}

/** `sget-object Locale.ENGLISH`, then `toUpperCase` on [result]. */
private fun upperCased(instructions: List<Instruction>, at: Int, result: Int): Boolean {
    val localeGet = instructions.getOrNull(at)
    val locale = (localeGet as? ReferenceInstruction)?.reference as? FieldReference
    val upper = instructions.getOrNull(at + 1)
    val upperCall = (upper as? ReferenceInstruction)?.reference as? MethodReference
    return localeGet?.opcode == Opcode.SGET_OBJECT &&
        locale?.definingClass == "Ljava/util/Locale;" && locale?.name == "ENGLISH" &&
        upper?.opcode == Opcode.INVOKE_VIRTUAL && upperCall?.name == "toUpperCase" &&
        (upper as? FiveRegisterInstruction)?.registerC == result
}

/** An instance (String)String call that takes [result] as its argument. */
private fun wrapped(instructions: List<Instruction>, at: Int, result: Int): Boolean {
    val wrap = instructions.getOrNull(at) as? FiveRegisterInstruction ?: return false
    val wrapCall = (wrap as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    return wrap.opcode == Opcode.INVOKE_VIRTUAL && wrapCall.returnType == "Ljava/lang/String;" &&
        wrapCall.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;") &&
        wrap.registerCount == 2 && wrap.registerD == result
}

@Suppress("unused")
val liveControlsPatch = bytecodePatch(
    name = "LIVE controls",
    description = "Stops a LIVE in the feed from opening by itself after a countdown. It can " +
        "show a LIVE's exact viewer count instead of a rounded one, offer a quality choice and " +
        "clear screen in more LIVE rooms, and keep your quality pick after TikTok restarts. " +
        "Starts off. Turn it on in Metra settings > App behavior. " +
        "It also keeps a LIVE in the feed from showing the Service+ row of the LIVE before it, " +
        "and can add a Record button to a LIVE room that saves what you watch from when you tap it.",
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        val guide = LivePreviewAutoEnterFingerprint.method
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableLiveControls()V",
        )
        guide.guardAtEntry(
            "LIVE controls",
            "invoke-static {}, $EXTENSION->skipAutoEnter()Z",
            "return-void",
        )
        // Both answer true while the switch is on; every other reader of the setting goes
        // through these two.
        listOf(LiveResolutionSelectionFingerprint, LiveResolutionPersistFingerprint).forEach {
            it.method.guardAtEntry(
                "LIVE controls",
                "invoke-static {}, $EXTENSION->offerQualityEverywhere()Z",
                "const/4 v0, 0x1\nreturn v0",
            )
        }
        // Answers true while its switch is on, so every LIVE room's menu offers clear screen.
        LiveClearScreenEntryFingerprint.method.guardAtEntry(
            "LIVE controls",
            "invoke-static {}, $EXTENSION->offerClearScreen()Z",
            "const/4 v0, 0x1\nreturn v0",
        )
        // Answers true while its switch is on and the panel's context is in dark mode, so LIVE
        // panels take TikTok's dark LIVE theme in rooms too (#159).
        LiveDarkThemeFingerprint.method.guardAtEntry(
            "LIVE controls",
            "invoke-static {p1}, $EXTENSION->darkLivePanels(Landroid/content/Context;)Z",
            "const/4 v0, 0x1\nreturn v0",
        )
        // That answer alone left the panels white (S25, 2026-10-14): TikTok adds the dark theme
        // to the room's light one without force. Each themed dialog's and sheet page's inflater
        // goes through the extension, which applies the panel's dark overlay with force while
        // the switch is on in dark mode. The hook sits on each return's own label, so a branch
        // onto the return runs it too.
        listOf(LiveThemedDialogInflaterFingerprint, LiveThemedFragmentInflaterFingerprint).forEach {
            hookPanelInflater(it.method)
        }
        // TikTok's generic sheets over a room (Share) take the room activity's own light theme,
        // which the overlay can't reach. The room's setTheme hands the activity to the extension
        // after its last room theme, and the extension puts TikTok's dark app theme on it
        // with force while the switch is on in dark mode. The style comes from TikTok's own dark
        // mode code, so it follows each build's resource ids.
        val darkTheme = appDarkTheme(AppDarkThemeFingerprint.method)
            ?: throw PatchException("LIVE controls: TikTok's dark app theme wasn't found")
        mutableClassDefBy(PANEL_THEME).methods.single {
            it.name == "darkAppTheme" && it.returnType == "I" && it.parameterTypes.isEmpty()
        }.returnEarly(darkTheme)
        hookRoomTheme(LivePlayActivityThemeFingerprint.method)
        // A translated bio's "See original" link read dark on the dark profile card (Login2,
        // 2026-10-18): its replacement span draws it in the bio view's own light-theme text
        // color, skipping the link color. The extension makes the link color the view's text
        // color while the switch is on in dark mode.
        hookBioLink(LiveProfileBioFingerprint.method)
        // The older quality list's rows can read white on white in dark mode, switch or no
        // switch; the extension gives poorly contrasted text a readable color there.
        hookQualityList(LiveDefinitionListFingerprint.method)

        // The room page coming to the front: where the Record button goes and which room it records (#37).
        liveRoomField(classDefByOrNull(LIVE_PLAY_FRAGMENT)
            ?: throw PatchException("LIVE controls: $LIVE_PLAY_FRAGMENT not found"))
        LivePlayFragmentResumeFingerprint.method.addInstruction(
            0,
            "invoke-static/range {p0 .. p0}, $RECORD_EXTENSION->noteRoomFragment(Ljava/lang/Object;)V",
        )

        // The formatter's result lands in the upper half of the long it was given, so the count
        // can't be read back after the call and the hook needs no spare register: the count is
        // handed over before the call and TikTok's text after it, on the main thread in one
        // straight line of code, and the extension answers with the exact number or TikTok's
        // text. Both added calls use only registers the original instructions just wrote: the
        // first reads the pair int-to-long filled, the second reads and rewrites the register
        // move-result-object filled. No frame grows.
        val widget = classDefByOrNull(ONLINE_AUDIENCE_RANK_WIDGET)
            ?: throw PatchException("LIVE controls: $ONLINE_AUDIENCE_RANK_WIDGET not found")
        val sites = viewerCountSites(widget)
        if (sites.size != VIEWER_COUNT_SITES) {
            throw PatchException("LIVE controls: expected $VIEWER_COUNT_SITES viewer count calls, found ${sites.size}")
        }
        val formatter = sites.map { it.formatter }.distinct().singleOrNull()
            ?: throw PatchException("LIVE controls: the viewer count calls use more than one formatter")
        hookViewerCounts(ONLINE_AUDIENCE_RANK_WIDGET, sites)

        // A programmed LIVE shows its count in its own widgets, through the same formatter.
        PROGRAMMED_AUDIENCE_WIDGETS.forEach { type ->
            val programmed = classDefByOrNull(type) ?: throw PatchException("LIVE controls: $type not found")
            val found = viewerCountSites(programmed, formatter)
            if (found.size != 1) {
                throw PatchException("LIVE controls: expected one viewer count call in $type, found ${found.size}")
            }
            hookViewerCounts(type, found)
        }

        // A LIVE preview bound to a LIVE with no Service+ item runs the row's own hide first
        // when it's still showing the last LIVE's row (#94). Matched and checked in full before
        // anything is written.
        val serviceRow = classDefByOrNull(SERVICE_PLUS_ROW)
            ?: throw PatchException("LIVE controls: $SERVICE_PLUS_ROW not found")
        val serviceBind = servicePlusBind(serviceRow)
        mutableClassDefBy(SERVICE_PLUS_ROW).findMutableMethodOf(serviceBind.method).apply {
            val at = serviceBind.store + 1
            addInstructionsWithLabels(
                at,
                """
                    invoke-static {v${serviceBind.row}, v${serviceBind.preview}}, $EXTENSION->hideStaleServicePlus(Ljava/lang/Object;Ljava/lang/Object;)Z
                    move-result v${serviceBind.preview}
                    if-eqz v${serviceBind.preview}, :morphe_keep_service_plus_row
                    invoke-virtual {v${serviceBind.row}}, $SERVICE_PLUS_ROW->${serviceBind.hide.name}()V
                """,
                ExternalLabel("morphe_keep_service_plus_row", getInstruction(at)),
            )
        }

        // A LIVE quality pick that outlives TikTok closing (#57): the picker's setter saves each
        // pick, and both starts that read TikTok's last pick get the saved one while TikTok's is
        // still null after a cold start. The player start only uses it when the stream has that
        // quality, the same check it makes of a pick from this session. All four places are found
        // and checked before anything is written.
        val startQuality = LiveStartQualityFingerprint.method
        val pick = liveQualityPick(mutableClassDefBy(PULL_STREAM_SERVICE), startQuality)
        val playerStart = LivePlayerStartFingerprint.method
        val playerPickRead = playerStart.readsOf(pick.field).singleOrNull()
            ?: throw PatchException("LIVE controls: expected one quality pick read in ${playerStart.name}")
        val gameRoom = gameRoomStart(playerStart, playerPickRead)
        val studioSkip = liveStudioSkip(playerStart, gameRoom.end, playerPickRead)
        val studioMode = (playerStart.getInstruction(studioSkip) as OneRegisterInstruction).registerA
        val readers = listOf(startQuality, playerStart).map { reader ->
            val reads = reader.readsOf(pick.field)
            val read = reads.singleOrNull()
                ?: throw PatchException("LIVE controls: expected one quality pick read in ${reader.name}, found ${reads.size}")
            Triple(reader, read, (reader.getInstruction(read) as OneRegisterInstruction).registerA)
        }
        readers.forEach { (reader, read, register) ->
            reader.addInstructions(
                read + 1,
                """
                    invoke-static/range {v$register .. v$register}, $EXTENSION->liveQualityPick(Ljava/lang/String;)Ljava/lang/String;
                    move-result-object v$register
                """,
            )
        }
        // LIVE Studio rooms skip the pick read in the regular branch. With Offer LIVE quality on the
        // mode they're compared with becomes null, so they read the pick and check the stream has
        // it like other rooms. Audio rooms stay left out. It sits between the game branch's end and
        // the pick read, so it goes in after the read's hook and before the game end's.
        playerStart.addInstructions(
            studioSkip + 1,
            """
                invoke-static {v$studioMode}, $EXTENSION->liveStudioPickSkip(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v$studioMode
            """,
        )
        // A game or PC LIVE room's start jumps past the pick read, so its end asks for the pick
        // and switches the room the way the regular branch does. It sits before the pick read, so
        // the hook above is already in. v0 to v2 are written again after the goto before any read.
        playerStart.addInstructionsWithLabels(
            gameRoom.end,
            """
                sget-object v0, ${pick.field}
                iget-object v1, p0, ${gameRoom.data()}
                iget-object v2, p0, ${gameRoom.quality()}
                invoke-static {v0, v1, v2}, $EXTENSION->gameRoomQuality(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
                move-result-object v0
                if-eqz v0, :morphe_game_room_keep
                iput-object v0, p0, ${gameRoom.quality()}
                iget-object v2, p0, ${gameRoom.player()}
                invoke-interface {v2, v1, v0}, ${gameRoom.setter()}
            """,
            ExternalLabel("morphe_game_room_keep", playerStart.getInstruction(gameRoom.end)),
        )
        // Rooms from the LIVE button logged neither start's pick read on the S22, so with debug on
        // every player start says whether it had stream data and which LIVE mode it was. Added last
        // so the indices above still hold; v0 and v1 are written before they're read.
        val liveMode = playerStart.implementation!!.instructions.firstNotNullOfOrNull {
            ((it as? ReferenceInstruction)?.reference as? FieldReference)
                ?.takeIf { field -> it.opcode == Opcode.IGET_OBJECT && field.type == LIVE_MODE && field.definingClass == playerStart.definingClass }
        } ?: throw PatchException("LIVE controls: the LIVE player start reads no LIVE mode")
        playerStart.addInstructions(
            0,
            """
                iget-object v0, p0, ${gameRoom.data()}
                iget-object v1, p0, ${liveMode.key()}
                invoke-static {v0, v1}, $EXTENSION->notePlayerStart(Ljava/lang/String;Ljava/lang/Object;)V
            """,
        )
        mutableClassDefBy(PULL_STREAM_SERVICE).findMutableMethodOf(pick.setter).addInstruction(
            0,
            "invoke-static/range {p1 .. p1}, $EXTENSION->noteLiveQualityPick(Ljava/lang/String;)V",
        )
        // The older picker, which Share > Video quality opens in rooms whose stream asks for a
        // quality button, never goes through that setter. Its pick is saved where it has gone
        // through, right after the picked key is stored (#57).
        val applied = LiveDefinitionPickAppliedFingerprint.method
        val stored = definitionPickStore(applied)
        applied.addInstruction(
            stored.index + 1,
            "invoke-static/range {v${stored.register} .. v${stored.register}}, " +
                "$EXTENSION->noteLiveQualityPick(Ljava/lang/String;)V",
        )
    }
}

/**
 * Hands each site's count to the extension before the formatter and swaps TikTok's text for the
 * extension's after it.
 */
private fun BytecodePatchContext.hookViewerCounts(type: String, sites: List<ViewerCountSite>) {
    val mutableWidget = mutableClassDefBy(type)
    for ((method, group) in sites.groupBy { it.method }) {
        val mutable = mutableWidget.findMutableMethodOf(method)
        // Last site first, so an earlier site's index is still right when it is edited.
        for (site in group.sortedByDescending { it.formatIndex }) {
            mutable.hookViewerCount(site)
        }
    }
}

/** The two calls around one site; both use only the registers the site's own instructions write. */
internal fun MutableMethod.hookViewerCount(site: ViewerCountSite) {
    addInstructions(
        site.formatIndex + 2,
        "invoke-static/range {v${site.result} .. v${site.result}}, $EXTENSION->viewerCountText(Ljava/lang/String;)" +
            "Ljava/lang/String;\nmove-result-object v${site.result}",
    )
    addInstruction(
        site.formatIndex,
        "invoke-static/range {v${site.pairLow} .. v${site.pairLow + 1}}, $EXTENSION->noteViewerCount(J)V",
    )
}
