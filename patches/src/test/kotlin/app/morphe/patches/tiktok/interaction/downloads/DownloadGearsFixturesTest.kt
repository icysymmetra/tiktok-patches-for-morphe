package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where a chosen-quality download reads a video's renditions (QualitySelector.rawGears), held to
 * every retained fixture. Video.getRawBitRate returns the backing list whole on each build, and
 * that field is named one of the two names the fallback reads: bitRate on 46.2.3, bitRateList on
 * 47.0.3, where reading bitRate alone found nothing and every chosen quality fell back to
 * TikTok's own save. The separate audio renditions stay in bitRateAudio.
 */
class DownloadGearsFixturesTest {
    @Test
    fun `every fixture hands the whole rendition list to getRawBitRate`() {
        for (apk in Fixtures.apks()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val video = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.filter { it.type == VIDEO }
            }.first()
            val raw = video.methods.single { it.name == "getRawBitRate" && it.parameterTypes.isEmpty() }
            assertEquals("${apk.name}: getRawBitRate returns a List", LIST, raw.returnType)
            val instructions = raw.implementation!!.instructions.toList()
            assertEquals("${apk.name}: getRawBitRate is a plain field read", 2, instructions.size)
            val read = instructions[0]
            assertEquals("${apk.name}: the read", Opcode.IGET_OBJECT, read.opcode)
            val field = (read as ReferenceInstruction).reference as FieldReference
            assertTrue(
                "${apk.name}: a List field of Video named as rawGears reads it, got ${field.name}",
                field.definingClass == VIDEO && field.type == LIST && field.name in setOf("bitRate", "bitRateList"),
            )
            assertEquals("${apk.name}: returned as read", Opcode.RETURN_OBJECT, instructions[1].opcode)
            assertEquals(
                "${apk.name}: the value read is the value returned",
                (read as TwoRegisterInstruction).registerA,
                (instructions[1] as OneRegisterInstruction).registerA,
            )
            assertTrue(
                "${apk.name}: the audio renditions field",
                video.fields.any { it.name == "bitRateAudio" && it.type == LIST },
            )
            // QualitySelector.codec reads this and takes a missing one for H.264, so a rename
            // would bring ByteVC2 files back with every test green (refutation review 2026-09-23).
            val bitRate = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.filter { it.type == BIT_RATE }
            }.first()
            assertTrue(
                "${apk.name}: BitRate's int codec field isBytevc1",
                bitRate.fields.any { it.name == "isBytevc1" && it.type == "I" },
            )
            assertTrue(
                "${apk.name}: BitRate's isBytevc1() getter",
                bitRate.methods.any { it.name == "isBytevc1" && it.parameterTypes.isEmpty() && it.returnType == "I" },
            )
        }
    }

    /**
     * Where a story or a sound save falls back when a video has no download address
     * (VideoDownloads.sourceUrls ends at Video.getPlayAddr). On every build it returns one of the
     * two play addresses TikTok tags itself: the ByteVC1 one when it checks out, else the H.264
     * one, codec 1 and 0. ByteVC2, which other players can't open, only turns up in the
     * rendition list. The fear that this fallback could be ByteVC2 was acted on once (d05230ca)
     * and taken back when the fixtures said otherwise; this holds that answer to each of them.
     */
    @Test
    fun `the play address a save falls back to is TikTok's H264 or ByteVC1 one on every fixture`() {
        for (apk in Fixtures.apks()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val video = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.filter { it.type == VIDEO }
            }.first()
            fun getter(name: String) = video.methods.single {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == VIDEO_URL_MODEL
            }.implementation!!.instructions.toList()
            val h264 = getter("getPlayAddrH264")
            val bytevc1 = getter("getPlayAddrBytevc1")
            val play = getter("getPlayAddr")
            assertEquals("${apk.name}: getPlayAddrH264 tags its address", setOf(0), codecTags(h264))
            assertEquals("${apk.name}: getPlayAddrBytevc1 tags its address", setOf(1), codecTags(bytevc1))
            assertEquals("${apk.name}: getPlayAddr tags only H.264 and ByteVC1", setOf(0, 1), codecTags(play))
            val h264Field = returnedFields(h264).single()
            val bytevc1Field = returnedFields(bytevc1).single()
            assertEquals(
                "${apk.name}: getPlayAddr returns the ByteVC1 address or the H.264 one, nothing else",
                setOf(h264Field, bytevc1Field),
                returnedFields(play),
            )
        }
    }

    /** The literal each setCodecType(int) call in a getter passes, read back to its const. */
    private fun codecTags(instructions: List<Instruction>): Set<Int> =
        instructions.withIndex().filter { (_, instruction) ->
            val method = (instruction as? ReferenceInstruction)?.reference as? MethodReference
            method?.name == "setCodecType" && method.parameterTypes.map { it.toString() } == listOf("I")
        }.map { (index, instruction) ->
            val register = (instruction as FiveRegisterInstruction).registerD
            val write = instructions.subList(0, index).last {
                it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register
            }
            assertTrue("the codec is a literal, got ${write.opcode}", write is NarrowLiteralInstruction)
            (write as NarrowLiteralInstruction).narrowLiteral
        }.toSet()

    /** The Video field each return-object in a getter hands back, read back to its iget. */
    private fun returnedFields(instructions: List<Instruction>): Set<String> =
        instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_OBJECT }.map { (index, instruction) ->
            val register = (instruction as OneRegisterInstruction).registerA
            val read = instructions.subList(0, index).last {
                it.opcode.setsRegister() && (it as? OneRegisterInstruction)?.registerA == register
            }
            assertEquals("a field read is what is returned", Opcode.IGET_OBJECT, read.opcode)
            val field = (read as ReferenceInstruction).reference as FieldReference
            assertEquals("a field of Video", VIDEO, field.definingClass)
            field.name
        }.toSet()

    private companion object {
        const val VIDEO_URL_MODEL = "Lcom/ss/android/ugc/aweme/feed/model/VideoUrlModel;"
        const val VIDEO = "Lcom/ss/android/ugc/aweme/feed/model/Video;"
        const val BIT_RATE = "Lcom/ss/android/ugc/aweme/feed/model/BitRate;"
        const val LIST = "Ljava/util/List;"
    }
}
