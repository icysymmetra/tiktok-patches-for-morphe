/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.feedmute

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.interaction.blockauthor.PlayerPlayFingerprint
import app.morphe.patches.tiktok.interaction.blockauthor.awemeParameterRegister
import app.morphe.patches.tiktok.interaction.blockauthor.blockAuthorPatch
import app.morphe.patches.tiktok.interaction.blockauthor.resolveNativeFocus
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.inbox.MainActivityOnCreateFingerprint
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.guardAtEntry
import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.cloneMutable
import app.morphe.util.singleOrPatchException
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Mute feed videos"
private const val FEED_MUTE = "Lapp/morphe/extension/tiktok/playback/FeedMute;"
private const val CONTEXT = "Landroid/content/Context;"

/** How many of its own fields' types the session's focus helper is looked through. */
private const val FOCUS_HELPER_DEPTH = 2

/**
 * TikTok's members the extension's bridges call, all renamed by each build and read here off
 * TikTok's own code:
 * - the feed engine's player field and the player type that declares `getSourceId()`, from the
 *   first thing the engine's play() reads off itself and casts;
 * - TTVideoEngine's public setIsMute forwarder, the `(Z)V` that calls the impl logging it;
 * - the focus helper holding the player session's grant: the `()V` that asks the manager for it
 *   and the give-up the session itself makes on it, both called with the helper as p0.
 */
internal class FeedMuteMembers(
    val engine: String,
    val playerField: FieldReference,
    val player: String,
    val setMute: MethodReference,
    val session: String,
    val sessionAbandon: MethodReference,
    val sessionRequest: MethodReference,
)

private fun Method.invokes(target: Method): Boolean =
    implementation?.instructions?.any { instruction ->
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        called != null && called.definingClass == target.definingClass && called.name == target.name &&
            called.parameterTypes.map(CharSequence::toString) == target.parameterTypes.map(CharSequence::toString)
    } == true

private fun isInstance(method: Method) = !AccessFlags.STATIC.isSet(method.accessFlags)

/** Every method a body calls, in order. */
private fun Method.calls(): List<MethodReference> =
    implementation?.instructions?.mapNotNull {
        (it as? ReferenceInstruction)?.reference as? MethodReference
    }.orEmpty()

/** Whether [classDef] declares the no-arg method that asks the manager for the focus. */
private fun asksForFocus(classDef: ClassDef, focusRequest: Method) = classDef.methods.any {
    isInstance(it) && it.returnType == "V" && it.parameterTypes.isEmpty() && it.invokes(focusRequest)
}

/**
 * The class that declares the session's focus request: the session itself on builds that keep
 * the request beside its give-up, or a class it holds within [FOCUS_HELPER_DEPTH] field hops.
 * 47.6.15 moved the request off the player into its per-session audio-focus helper
 * (`LX/0ord;->LIZIZ:LX/0orc;` -> `LX/0orc;->LJIILIIL:LX/0orh;`), so the session's give-up and its
 * request no longer sit in one class; a level holding more than one asker is a build nothing
 * here can settle.
 */
private fun focusHelper(
    session: ClassDef,
    focusRequest: Method,
    classOf: (String) -> ClassDef?,
): ClassDef? {
    val seen = mutableSetOf(session.type)
    var level = listOf(session)
    repeat(FOCUS_HELPER_DEPTH + 1) {
        val asks = level.filter { asksForFocus(it, focusRequest) }
        if (asks.size > 1) {
            throw PatchException("$PATCH: ${asks.map { it.type }} all ask for the focus, so ${session.type}'s helper is ambiguous.")
        }
        asks.singleOrNull()?.let { return it }
        level = level.flatMap { classDef -> classDef.fields.mapNotNull { classOf(it.type) } }
            .filter { seen.add(it.type) }
        if (level.isEmpty()) return null
    }
    return null
}

/** The bridge body that calls [member] on the session's helper; a boolean give-up drops it. */
private fun sessionCall(members: FeedMuteMembers, member: MethodReference): String {
    val call = when (val parameters = member.parameterTypes.map(CharSequence::toString)) {
        emptyList<String>() -> "invoke-virtual { p0 }, ${members.session}->${member.name}()${member.returnType}"
        // 47.6.15's helper only asks; the give-up TikTok's own release makes on it is (Z), false.
        listOf("Z") -> """
            const/4 v0, 0x0
            invoke-virtual { p0, v0 }, ${members.session}->${member.name}(Z)${member.returnType}
        """
        else -> throw PatchException(
            "$PATCH: ${members.session}->${member.name} takes ${parameters.joinToString(", ", "(", ")")}, a shape the bridge can't call.",
        )
    }
    return """
        check-cast p0, ${members.session}
        $call
        return-void
    """
}

private fun requirePublic(what: String, flags: Int) {
    if (!AccessFlags.PUBLIC.isSet(flags)) {
        throw PatchException("$PATCH: $what isn't public in this build, and the extension calls it from outside.")
    }
}

/**
 * Reads the members off the four fingerprinted methods. [classOf] gives a class or null; the
 * patch reads the app with it and the fixture test the APKs, so both run this code.
 */
internal fun resolveFeedMuteMembers(
    enginePlay: Method,
    setIsMuteImpl: Method,
    sessionAbandon: Method,
    focusRequest: Method,
    classOf: (String) -> ClassDef?,
): FeedMuteMembers {
    val body = enginePlay.implementation ?: throw PatchException("$PATCH: the engine's play() has no body.")
    val instructions = body.instructions.toList()
    val self = body.registerCount - 1
    val read = instructions.indexOfFirst {
        it.opcode == Opcode.IGET_OBJECT && (it as TwoRegisterInstruction).registerB == self
    }
    if (read < 0) throw PatchException("$PATCH: ${enginePlay.definingClass}.play() reads no field of its own.")
    val field = (instructions[read] as ReferenceInstruction).reference as FieldReference
    val into = (instructions[read] as TwoRegisterInstruction).registerA
    val player = instructions.drop(read + 1).firstOrNull {
        it.opcode == Opcode.CHECK_CAST && (it as OneRegisterInstruction).registerA == into
    }?.let { ((it as ReferenceInstruction).reference as TypeReference).type }
        ?: throw PatchException("$PATCH: ${enginePlay.definingClass}.play() doesn't cast its ${field.name}.")
    val playerClass = classOf(player) ?: throw PatchException("$PATCH: $player isn't in this build.")
    val sourceId = playerClass.methods.singleOrNull {
        it.name == "getSourceId" && it.parameterTypes.isEmpty() && it.returnType == "Ljava/lang/String;"
    } ?: throw PatchException("$PATCH: $player, the engine's player, declares no getSourceId().")

    val engineBase = classOf(TT_VIDEO_ENGINE) ?: throw PatchException("$PATCH: $TT_VIDEO_ENGINE isn't in this build.")
    val forwarder = engineBase.methods.filter {
        isInstance(it) && it.returnType == "V" &&
            it.parameterTypes.map(CharSequence::toString) == listOf("Z") &&
            // 47.6.15 routes the public call through an interface the impl implements, so match
            // the same member name and shape rather than the impl class the call lands on.
            it.implementation?.instructions?.any { instruction ->
                val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
                called != null && called.name == setIsMuteImpl.name &&
                    called.parameterTypes.map(CharSequence::toString) == listOf("Z") &&
                    called.returnType == "V"
            } == true
    }.singleOrPatchException("$PATCH: TTVideoEngine's forwarder to ${setIsMuteImpl.definingClass}->${setIsMuteImpl.name}")
    val isMute = engineBase.methods.singleOrNull {
        it.name == "isMute" && it.parameterTypes.isEmpty() && it.returnType == "Z" && isInstance(it)
    } ?: throw PatchException("$PATCH: TTVideoEngine has no isMute().")

    val sessionAbandonClass = classOf(sessionAbandon.definingClass)
        ?: throw PatchException("$PATCH: ${sessionAbandon.definingClass} isn't in this build.")
    val session = focusHelper(sessionAbandonClass, focusRequest, classOf)
        ?: throw PatchException("$PATCH: ${sessionAbandonClass.type} and what it holds ask for no focus.")
    val request = session.methods.filter {
        isInstance(it) && it.returnType == "V" && it.parameterTypes.isEmpty() && it.invokes(focusRequest)
    }.singleOrPatchException("$PATCH: the player session's focus request in ${session.type}")
    // The give-up is the session's own on builds that hold both, and on 47.6.15 the call its
    // release makes on the helper it asked through.
    val abandon = if (session.type == sessionAbandonClass.type) sessionAbandon else {
        val given = sessionAbandon.calls().filter { it.definingClass == session.type && it.name != request.name }
            .singleOrPatchException("$PATCH: ${sessionAbandonClass.type}'s give-up on ${session.type}")
        session.methods.filter {
            isInstance(it) && it.name == given.name &&
                it.parameterTypes.map(CharSequence::toString) == given.parameterTypes.map(CharSequence::toString)
        }.singleOrPatchException("$PATCH: ${session.type}->${given.name}, the give-up the session makes")
    }

    val engine = classOf(enginePlay.definingClass)!!
    requirePublic("the engine ${engine.type}", engine.accessFlags)
    requirePublic("the player ${playerClass.type}", playerClass.accessFlags)
    requirePublic("${playerClass.type}->getSourceId()", sourceId.accessFlags)
    requirePublic("TTVideoEngine", engineBase.accessFlags)
    requirePublic("TTVideoEngine->${forwarder.name}(Z)", forwarder.accessFlags)
    requirePublic("TTVideoEngine->isMute()", isMute.accessFlags)
    requirePublic("the player session's focus helper ${session.type}", session.accessFlags)
    requirePublic("${session.type}->${abandon.name}", abandon.accessFlags)
    requirePublic("${session.type}->${request.name}()", request.accessFlags)
    val declared = generateSequence(engine) { classOf(it.superclass ?: return@generateSequence null) }
        .firstNotNullOfOrNull { owner -> owner.fields.singleOrNull { it.name == field.name && it.type == field.type } }
        ?: throw PatchException("$PATCH: nothing above ${engine.type} declares ${field.name}.")
    requirePublic("${field.definingClass}->${field.name}", declared.accessFlags)

    return FeedMuteMembers(
        engine = engine.type,
        playerField = field,
        player = player,
        setMute = forwarder,
        session = session.type,
        sessionAbandon = abandon,
        sessionRequest = request,
    )
}

@Suppress("unused")
val feedMutePatch = bytecodePatch(
    name = "Mute feed videos",
    description = "Mutes feed videos without changing your phone's volume, so music from " +
        "another app keeps playing. You can also add a mute button on videos. Starts off. Turn it " +
        "on in Metra settings > App behavior.",
) {
    category("Playback")
    dependsOn(settingsPatch, sharedExtensionPatch, blockAuthorPatch)
    compatibleWith(*AppCompatibilities.tiktok())

    execute {
        SettingsStatusLoadFingerprint.method.addInstruction(
            0,
            "invoke-static {}, Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableFeedMute()V",
        )
        MainActivityOnCreateFingerprint.method.addInstruction(
            0,
            "invoke-static/range { p0 .. p0 }, $FEED_MUTE->install(Landroid/app/Activity;)V",
        )

        // Which video a PlayerController asks for, before the engine for it plays. The Aweme is
        // p1 on both declared builds; a build that moves it gets a patch error, not a guess.
        PlayerPlayFingerprint.method.apply {
            val aweme = awemeParameterRegister()
            if (aweme != 1) throw PatchException("$PATCH: the play method's Aweme is p$aweme, not p1.")
            addInstruction(
                0,
                "invoke-static/range { p0 .. p1 }, $FEED_MUTE->onControllerPlay(Ljava/lang/Object;Ljava/lang/Object;)V",
            )
        }

        val members = resolveFeedMuteMembers(
            FeedEnginePlayFingerprint.method,
            EngineSetIsMuteFingerprint.method,
            SimAudioSessionAbandonFingerprint.method,
            SimAudioFocusRequestFingerprint.method,
        ) { classDefByOrNull(it) }

        // Every start of a feed video goes through the engine's play(); start() is hooked as
        // well, since it costs one line and nothing on these engines calls it today.
        val engine = mutableClassDefBy(members.engine)
        engine.methods.filter {
            (it.name == "play" || it.name == "start") && it.parameterTypes.isEmpty() && it.returnType == "V"
        }.also { if (it.none { method -> method.name == "play" }) throw PatchException("$PATCH: the engine lost play().") }
            .forEach {
                it.addInstruction(0, "invoke-static/range { p0 .. p0 }, $FEED_MUTE->onEnginePlay(Ljava/lang/Object;)V")
            }

        // The player session's lasting grant and the page helper's transient one, turned down
        // while muted with a feed video in front.
        val session = mutableClassDefBy(members.session)
        session.methods.single { it.name == members.sessionRequest.name && it.parameterTypes.isEmpty() }
            .guardAtEntry(PATCH, "invoke-static/range { p0 .. p0 }, $FEED_MUTE->holdSessionFocus(Ljava/lang/Object;)Z", "return-void")
        val page = resolveNativeFocus()
        requirePublic("the page focus helper ${page.helper}", classDefBy(page.helper).accessFlags)
        requirePublic("${page.helper}->${page.request.name}(Context)", page.request.accessFlags)
        requirePublic("${page.helper}->${page.abandon.name}(Context)", page.abandon.accessFlags)
        page.request.guardAtEntry(PATCH, "invoke-static/range { p0 .. p0 }, $FEED_MUTE->holdPageFocus(Ljava/lang/Object;)Z", "return-void")

        installFeedMuteBridges(members, page.helper, page.request.name, page.abandon.name)
    }
}

/** Writes TikTok's members into FeedMute's bridge methods, whose own bodies serve the tests. */
private fun BytecodePatchContext.installFeedMuteBridges(
    members: FeedMuteMembers,
    pageHelper: String,
    pageRequest: String,
    pageAbandon: String,
) {
    val feedMute = mutableClassDefBy(FEED_MUTE)
    fun rewrite(name: String, locals: Int, body: String) {
        val original = feedMute.methods.filter { it.name == name }
            .singleOrPatchException("$PATCH: FeedMute's $name bridge")
        val bridge = original.cloneMutable(additionalRegisters = locals)
        feedMute.methods.remove(original)
        feedMute.methods.add(bridge)
        bridge.addInstructions(0, body)
    }
    val field = members.playerField
    rewrite("engineSourceId", 2, """
        check-cast p0, ${members.engine}
        iget-object v0, p0, ${field.definingClass}->${field.name}:${field.type}
        instance-of v1, v0, ${members.player}
        if-eqz v1, :none
        check-cast v0, ${members.player}
        invoke-virtual { v0 }, ${members.player}->getSourceId()Ljava/lang/String;
        move-result-object v0
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """)
    rewrite("engineIsMute", 1, """
        check-cast p0, $TT_VIDEO_ENGINE
        invoke-virtual { p0 }, $TT_VIDEO_ENGINE->isMute()Z
        move-result v0
        return v0
    """)
    rewrite("setEngineMute", 1, """
        check-cast p0, $TT_VIDEO_ENGINE
        invoke-virtual { p0, p1 }, $TT_VIDEO_ENGINE->${members.setMute.name}(Z)V
        return-void
    """)
    rewrite("abandonSessionFocus", 1, sessionCall(members, members.sessionAbandon))
    rewrite("requestSessionFocus", 1, sessionCall(members, members.sessionRequest))
    for ((name, member) in listOf("abandonPageFocus" to pageAbandon, "requestPageFocus" to pageRequest)) {
        rewrite(name, 1, """
            check-cast p0, $pageHelper
            invoke-virtual { p0, p1 }, $pageHelper->$member($CONTEXT)V
            return-void
        """)
    }
}
