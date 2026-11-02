package app.morphe.patches.tiktok.interaction.live

import app.morphe.Fixtures
import app.morphe.patcher.Fingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #57: LIVE controls keeps TikTok's last LIVE quality pick after TikTok closes. TikTok holds the
 * pick in one static string that only its picker's setter writes, and the patch saves it there.
 * Two starts read it, and the patch hands each the saved pick while TikTok's is still null. Each
 * declared build has exactly those three places, so no pick goes unsaved and no start misses
 * the saved one, and the player start still checks the stream has the pick before using it.
 */
class LiveQualityPickAnchorsTest {
    @Test
    fun `each declared build has one quality pick, one setter and the two starts the hook reads it in`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            fun taken(fingerprint: Fingerprint): Method {
                val found = classes.flatMap { cls -> cls.methods.filter { fingerprint.takes(it, cls) } }
                assertEquals("${apk.name}: ${found.map { "${it.definingClass}->${it.name}" }}", 1, found.size)
                return found.single()
            }
            val startQuality = taken(LiveStartQualityFingerprint)
            val playerStart = taken(LivePlayerStartFingerprint)
            assertEquals(PULL_STREAM_SERVICE, startQuality.definingClass)

            val service: ClassDef = classes.single { it.type == PULL_STREAM_SERVICE }
            val pick = liveQualityPick(service, startQuality)

            // Every read of the pick is one of the two hooked starts, once each, into a register
            // the hook's range call can name.
            val readers = mutableListOf<String>()
            val writers = mutableListOf<String>()
            for (cls in classes) for (method in cls.methods) {
                val instructions = method.implementation?.instructions?.toList() ?: continue
                for (instruction in instructions) {
                    val field = (instruction as? ReferenceInstruction)?.reference as? FieldReference ?: continue
                    if ("${field.definingClass}->${field.name}:${field.type}" != pick.field) continue
                    val at = "${cls.type}->${method.name}"
                    when (instruction.opcode) {
                        Opcode.SGET_OBJECT -> readers += at
                        Opcode.SPUT_OBJECT -> writers += at
                        else -> throw AssertionError("${apk.name}: ${instruction.opcode} of the pick in $at")
                    }
                }
            }
            assertEquals(
                "${apk.name}: readers of ${pick.field}",
                listOf(startQuality, playerStart).map { "${it.definingClass}->${it.name}" }.sorted(),
                readers.sorted(),
            )
            assertEquals("${apk.name}: writers of ${pick.field}", listOf("$PULL_STREAM_SERVICE->${pick.setter.name}"), writers)
            for (reader in listOf(startQuality, playerStart)) {
                val read = reader.readsOf(pick.field).single()
                val register = (reader.implementation!!.instructions.toList()[read] as OneRegisterInstruction).registerA
                assertTrue("${apk.name}: ${reader.name} reads into v$register", register < reader.implementation!!.registerCount)
            }

            // The player start keeps the stream's own default unless the stream lists the pick.
            assertTrue(
                "${apk.name}: ${playerStart.name} no longer checks the stream has the pick",
                playerStart.implementation!!.instructions.any {
                    val call = (it as? ReferenceInstruction)?.reference as? MethodReference
                    call?.name == "contains" && call.definingClass.startsWith("Ljava/util/")
                },
            )
            assertTrue(
                "${apk.name}: ${playerStart.name} no longer asks needPersistAcrossRooms",
                playerStart.implementation!!.instructions.any {
                    val call = (it as? ReferenceInstruction)?.reference as? MethodReference
                    call?.definingClass == LIVE_RESOLUTION_SELECTION_SETTING && call.name == "needPersistAcrossRooms"
                },
            )
        }
    }

    /**
     * #57: the player start can set game and PC rooms up in a branch of its own (when TikTok's game
     * quality setting is on or the room's options say so) that jumps past the pick read, so the
     * patch asks for the pick at that branch's end and switches the room the way the regular branch
     * does: the same quality field, stream data and player call.
     */
    @Test
    fun `the player start's game branch ends in a jump past the pick read and its quality switch`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val playerStart = classes.flatMap { cls -> cls.methods.filter { LivePlayerStartFingerprint.takes(it, cls) } }.single()
            val startQuality = classes.flatMap { cls -> cls.methods.filter { LiveStartQualityFingerprint.takes(it, cls) } }.single()
            val pick = liveQualityPick(classes.single { it.type == PULL_STREAM_SERVICE }, startQuality)
            val read = playerStart.readsOf(pick.field).single()
            val game = gameRoomStart(playerStart, read)
            val instructions = playerStart.implementation!!.instructions.toList()

            // The branch is the game and PC one: the mode check comes before its end.
            val modeCheck = instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.name == "isWatchGameLiveOrPCLive"
            }
            assertTrue("${apk.name}: no game LIVE check before the branch's end", modeCheck in 0 until game.end)

            // Its goto lands past the regular branch's quality switch.
            val offsets = instructions.runningFold(0) { at, instruction -> at + instruction.codeUnits }
            val goto = instructions[game.end] as OffsetInstruction
            val target = offsets.indexOf(offsets[game.end] + goto.codeOffset)
            val switchAt = instructions.indexOfFirst {
                ((it as? ReferenceInstruction)?.reference as? MethodReference)?.let { call ->
                    "${call.definingClass}->${call.name}" == "${game.setter.definingClass}->${game.setter.name}"
                } == true
            }
            assertTrue("${apk.name}: the game branch's goto lands at $target, not past the switch at $switchAt", target > switchAt && switchAt > read)

            // The fields are the start's own, and the switch is the player field's.
            for (field in listOf(game.quality, game.data, game.player)) {
                assertEquals("${apk.name}: ${field.name}", playerStart.definingClass, field.definingClass)
            }
            assertEquals("${apk.name}: the quality and stream fields", "Ljava/lang/String;", game.data.type)
            assertEquals("${apk.name}: the switch's owner", game.player.type, game.setter.definingClass)
            assertTrue("${apk.name}: the start has ${playerStart.implementation!!.registerCount} registers", playerStart.implementation!!.registerCount > 3)
        }
    }

    /**
     * #57: on the S22 the gaming rooms from the LIVE button were LIVE Studio rooms that reached the
     * regular branch, and it compares the room's mode with LIVE_STUDIO and jumps past the pick read
     * on a match, so they opened at AUTO after a pick. The patch empties that comparison while
     * Offer LIVE quality is on. It has to be the room's own mode against LIVE_STUDIO, between the
     * game branch's end and the read, jumping past the read, with the audio check still after it.
     */
    @Test
    fun `the player start's regular branch skips the pick for LIVE Studio rooms with one comparison`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val playerStart = classes.flatMap { cls -> cls.methods.filter { LivePlayerStartFingerprint.takes(it, cls) } }.single()
            val startQuality = classes.flatMap { cls -> cls.methods.filter { LiveStartQualityFingerprint.takes(it, cls) } }.single()
            val pick = liveQualityPick(classes.single { it.type == PULL_STREAM_SERVICE }, startQuality)
            val read = playerStart.readsOf(pick.field).single()
            val game = gameRoomStart(playerStart, read)
            val studio = liveStudioSkip(playerStart, game.end, read)
            val instructions = playerStart.implementation!!.instructions.toList()

            val compare = instructions[studio + 1] as TwoRegisterInstruction
            val studioRegister = (instructions[studio] as OneRegisterInstruction).registerA
            assertTrue("${apk.name}: the comparison doesn't use the LIVE_STUDIO read", studioRegister in listOf(compare.registerA, compare.registerB))
            val modeRegister = if (compare.registerA == studioRegister) compare.registerB else compare.registerA
            val mode = (studio - 1 downTo game.end).asSequence().map { instructions[it] }.first {
                it is OneRegisterInstruction && it.registerA == modeRegister
            }
            assertEquals("${apk.name}: the compared mode", Opcode.IGET_OBJECT, mode.opcode)
            val modeField = (mode as ReferenceInstruction).reference as FieldReference
            assertEquals("${apk.name}: the compared mode's owner", playerStart.definingClass, modeField.definingClass)
            assertEquals("${apk.name}: the compared mode's type", "Lcom/bytedance/android/livesdkapi/depend/model/live/LiveMode;", modeField.type)

            val offsets = instructions.runningFold(0) { at, instruction -> at + instruction.codeUnits }
            val target = offsets.indexOf(offsets[studio + 1] + (instructions[studio + 1] as OffsetInstruction).codeOffset)
            assertTrue("${apk.name}: a LIVE Studio room jumps to $target, not past the pick read at $read", target > read)
            val audio = (studio + 2 until read).any {
                ((instructions[it] as? ReferenceInstruction)?.reference as? FieldReference)?.name == "AUDIO"
            }
            assertTrue("${apk.name}: audio rooms are no longer left out after the LIVE Studio check", audio)
        }
    }

    /**
     * On the S22 a pick from Share > Video quality never reached the setter above: that room's
     * stream asked for a quality button, so the menu offered the older picker,
     * LiveDefinitionSelectionDialog, whose switch callback stores the picked sdk key for the room
     * and never calls the setter. The patch saves the key right after that store, which has to sit
     * behind the callback's own check that the switch went through.
     */
    @Test
    fun `the older picker's applied pick is one store behind its result check on each declared build`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }.toList()
            val found = classes.flatMap { cls -> cls.methods.filter { LiveDefinitionPickAppliedFingerprint.takes(it, cls) } }
            assertEquals("${apk.name}: ${found.map { "${it.definingClass}->${it.name}" }}", 1, found.size)
            val applied = found.single()
            val runnable = classes.single { it.type == applied.definingClass }
            val traced = runnable.methods.single { it.name == "run" && it.parameterTypes.isEmpty() }
                .implementation!!.instructions.any {
                    val text = ((it as? ReferenceInstruction)?.reference as? StringReference)?.string.orEmpty()
                    text.startsWith("LiveDefinitionSelectionDialog\$DefinitionListAdapter") &&
                        text.contains("handleAudienceItemSelect")
                }
            assertTrue("${apk.name}: ${runnable.type} is no longer the older picker's pick going through", traced)

            val stored = definitionPickStore(applied)
            val instructions = applied.implementation!!.instructions.toList()
            val store = instructions[stored.index]
            assertEquals(Opcode.IPUT_OBJECT, store.opcode)
            assertEquals("${apk.name}: the store's field", "sdkKey", ((store as ReferenceInstruction).reference as FieldReference).name)
            assertTrue("${apk.name}: the key's register v${stored.register}", stored.register < applied.implementation!!.registerCount)
            // The picked row's key, read from the adapter's list just before the store.
            val read = instructions[stored.index - 1]
            assertEquals("${apk.name}: the stored key is a row's field", Opcode.IGET_OBJECT, read.opcode)
            assertEquals("${apk.name}: the read lands in the stored register", stored.register, (read as OneRegisterInstruction).registerA)
        }
    }
}
