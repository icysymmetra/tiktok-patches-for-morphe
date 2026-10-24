/*
 * Copyright 2026 icysymmetra/tiktok-patches-for-morphe contributors
 * https://github.com/icysymmetra/tiktok-patches-for-morphe
 */
package app.morphe.patches.tiktok.interaction.resume

import app.morphe.util.addInstruction
import app.morphe.util.addInstructions
import app.morphe.util.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.extension.sharedExtensionPatch
import app.morphe.patches.tiktok.misc.settings.SettingsStatusLoadFingerprint
import app.morphe.patches.tiktok.misc.settings.settingsPatch
import app.morphe.patches.tiktok.shared.requireLocals
import app.morphe.util.RegisterLiveness
import app.morphe.patcher.util.getReference
import app.morphe.util.indexOfFirstInstructionOrThrow
import app.morphe.util.indexOfFirstInstructionReversedOrThrow
import app.morphe.patcher.util.numberOfParameterRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val EXTENSION_DESCRIPTOR =
    "Lapp/morphe/extension/tiktok/interaction/ResumeVideoAfterScrollPatch;"

/** What a call's answer is taken with. Nothing may be put between the call and one of these. */
private val MOVE_RESULTS = setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_WIDE, Opcode.MOVE_RESULT_OBJECT)

@Suppress("unused")
val resumeVideoAfterScrollPatch = bytecodePatch(
    name = "Resume videos after scrolling",
    description = "When you scroll back to a video, it picks up where you left off instead of " +
        "starting over. On by default. Turn it off in Metra settings > App behavior.",
    default = true,
) {
    category("Interaction")
    dependsOn(settingsPatch, sharedExtensionPatch)

    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())

    execute {
        // Everything the writes below need is read off the host and checked before the first of
        // them, because the patcher does not take a failed patch's writes back out.
        val settingsStatus = SettingsStatusLoadFingerprint.method

        val continueGate = FeedProgressContinueGateFingerprint.method
        // v0 is scratch and the branch falls back into the host's own first instruction, so
        // the register has to be a local rather than one of the parameters.
        continueGate.requireLocals("Resume videos after scrolling", 1)

        val completed = FeedPlayCompletedFingerprint.method
        // v0 and v1 are written before the host's own first instruction runs.
        completed.requireLocals("Resume videos after scrolling", 2)

        // TikTok records a position for every feed it plays, but it only hands one back when the
        // feed's event type is in FeedPlayProgressContinueConfig.event_type_list. On 46.2.3 that
        // list is built in the config's own constructor and holds homepage_hot, others_homepage,
        // personal_homepage, landscape_mode, account_history and collection_video. The Following
        // and Friends tabs are not in it, which is why the switch appeared to do nothing there
        // while the position was being stored all along.
        val resumePosition = FeedProgressResumePositionFingerprint.method
        val eventTypeListIndex = resumePosition.indexOfFirstInstructionOrThrow {
            getReference<FieldReference>()?.name == "event_type_list"
        }
        val containsIndex = resumePosition.indexOfFirstInstructionOrThrow(eventTypeListIndex) {
            val reference = getReference<MethodReference>()
            reference?.definingClass == "Ljava/util/List;" && reference.name == "contains"
        }
        val containsResult = resumePosition.getInstruction(containsIndex + 1)
        if (containsResult.opcode != Opcode.MOVE_RESULT) {
            throw PatchException(
                "Resume video after scroll: the event type check does not keep its answer.",
            )
        }
        val eventTypeRegister = (containsResult as OneRegisterInstruction).registerA
        check(eventTypeRegister <= 15) {
            "Resume video after scroll: the event type answer is above v15, which the " +
                "override cannot name."
        }

        val progress = FeedPlayProgressFingerprint.method
        val cachePutIndex = progress.indexOfFirstInstructionReversedOrThrow {
            val reference = getReference<MethodReference>()
            reference?.definingClass == "Landroid/util/LruCache;" &&
                reference.name == "put" &&
                reference.parameterTypes.size == 2
        }
        val (cacheRegister, keyRegister, scratchRegister) =
            progressClearRegistersAt(progress, cachePutIndex)
        val continueInstruction = progress.getInstruction(cachePutIndex + 1)

        // Where the positions live, read off that same put because every name in it is
        // renamed per build.
        val store = progressStoreAt(progress, cachePutIndex) { classDefByOrNull(it) }

        settingsStatus.addInstruction(
            0,
            "invoke-static {}, " +
                "Lapp/morphe/extension/tiktok/settings/SettingsStatus;->enableResumeVideoAfterScroll()V",
        )

        continueGate.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $EXTENSION_DESCRIPTOR->shouldResumeVideoAfterScroll()Z
                move-result v0
                if-eqz v0, :continue_gate
                const/4 v0, 0x1
                invoke-static {v0}, Ljava/lang/Boolean;->valueOf(Z)Ljava/lang/Boolean;
                move-result-object v0
                return-object v0
            """,
            ExternalLabel("continue_gate", continueGate.getInstruction(0)),
        )

        // A video watched to the end starts from the top next time: its position leaves the
        // cache, and so does the record the store would otherwise answer from first.
        completed.addInstructionsWithLabels(
            0,
            """
                invoke-static {}, $EXTENSION_DESCRIPTOR->shouldResumeVideoAfterScroll()Z
                move-result v0
                if-eqz v0, :continue_completion
                sget-object v0, ${store.cache.smali()}
                ${store.cacheValueInvoke} {v0}, ${store.cacheValue.smali()}
                move-result-object v0
                check-cast v0, Landroid/util/LruCache;
                move-object/from16 v1, p1
                invoke-virtual {v0, v1}, Landroid/util/LruCache;->remove(Ljava/lang/Object;)Ljava/lang/Object;
                const/4 v0, 0x0
                sput-object v0, ${store.lastRecord.smali()}
                sput-object v0, ${store.lastAid.smali()}
            """,
            ExternalLabel("continue_completion", completed.getInstruction(0)),
        )

        resumePosition.addInstructions(
            containsIndex + 2,
            """
                invoke-static {v$eventTypeRegister}, $EXTENSION_DESCRIPTOR->allowResumeInThisFeed(Z)Z
                move-result v$eventTypeRegister
            """,
        )

        progress.addInstructionsWithLabels(
            cachePutIndex + 1,
            """
                invoke-static/range {p2 .. p5}, $EXTENSION_DESCRIPTOR->shouldClearCompletedProgress(JJ)Z
                move-result v$scratchRegister
                if-eqz v$scratchRegister, :continue_progress
                invoke-virtual {v$cacheRegister, v$keyRegister}, Landroid/util/LruCache;->remove(Ljava/lang/Object;)Ljava/lang/Object;
                const/4 v$scratchRegister, 0x0
                sput-object v$scratchRegister, ${store.lastRecord.smali()}
                sput-object v$scratchRegister, ${store.lastAid.smali()}
            """,
            ExternalLabel("continue_progress", continueInstruction),
        )
    }
}

/** The three registers the progress clear writes through, read off the host's own put. */
internal data class ProgressClearRegisters(val cache: Int, val key: Int, val flag: Int)

/**
 * The registers the clear may use at [cachePutIndex] + 1, and every reason it may not.
 *
 * <p>The cache, the key and a register to work in all come out of the put itself: it holds the
 * cache and the key this has to remove, and the value it was given is spent the moment it
 * returns. Naming them by number was right on 46.2.3 and says nothing about the next build.
 *
 * <p>"Spent the moment it returns" was a comment rather than a question put to the method. The
 * value register is an ordinary host register, and a build that reads it again after the put
 * would have had the flag written over the top of it, with nothing here to say so and the
 * damage showing up as wrong playback rather than as a failed patch. `GhostModeCallSites`
 * asks `RegisterLiveness` for exactly this before it writes its own flag mid-method, and the
 * working notes say every mid-method write needs the same answer.
 */
internal fun progressClearRegistersAt(progress: Method, cachePutIndex: Int): ProgressClearRegisters {
    // p2 to p5 are the two timestamps only when p0 is the receiver. A static callback of the
    // same signature would hand the clear the aid and half a timestamp.
    check(!AccessFlags.STATIC.isSet(progress.accessFlags)) {
        "Resume video after scroll: the progress callback is static, so p2 to p5 are not the " +
            "two timestamps the clear is given."
    }

    val instructions = progress.implementation?.instructions?.toList()
        ?: throw PatchException("Resume video after scroll: the progress method has no body.")
    val put = instructions.getOrNull(cachePutIndex) as? FiveRegisterInstruction
        ?: throw PatchException(
            "Resume video after scroll: the progress cache put is not a plain invoke.",
        )
    val registers = ProgressClearRegisters(put.registerC, put.registerD, put.registerE)
    check(maxOf(registers.cache, registers.key, registers.flag) <= 15) {
        "Resume video after scroll: the progress cache put reaches above v15, which the " +
            "removal cannot name."
    }

    // The value is only spent once the put returns if nothing takes the put's own answer,
    // and a line wedged between a call and its move-result is one the verifier refuses.
    val continueInstruction = instructions.getOrNull(cachePutIndex + 1)
        ?: throw PatchException(
            "Resume video after scroll: the progress cache put ends the method, so nothing " +
                "can go after it.",
        )
    if (continueInstruction.opcode in MOVE_RESULTS) {
        throw PatchException(
            "Resume video after scroll: the progress cache put keeps its answer, so nothing " +
                "can go straight after it.",
        )
    }

    // Asked of the whole method, branches and handlers included, rather than of the lines that
    // happen to follow the put.
    if (registers.flag in RegisterLiveness.of(progress).liveInto(cachePutIndex + 1)) {
        throw PatchException(
            "Resume video after scroll: v${registers.flag}, the value handed to the progress " +
                "cache put, is read again after it, so the clear cannot answer in it.",
        )
    }

    return registers
}
