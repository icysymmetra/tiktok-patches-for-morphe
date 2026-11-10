package app.morphe.patches.tiktok.interaction.downloads

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the sticker saver reads off the StickerItem the Downloads patch registers, held to each
 * declared build. Until 2026-09-26 it reached the sticker through R8 names (a StickerItem field
 * `LLILLIZIL` and a converter class `X.0UD5`), and on 47.0.3 and 47.1.3 neither was there, or
 * `X.0UD5` was some other class, without a word from any test. It reads TikTok's own member
 * names now, which R8 keeps for this model, and sorts stickers by the numbers TikTok's sticker
 * type enum hands out; this test is what says so on each new build.
 */
class StickerSourceFixturesTest {
    @Test
    fun `StickerItem keeps the members the sticker saver reads on every declared build`() {
        val models = setOf("StickerItem", "StickerBase", "StickerImage").map { "$MODEL$it;" }
        Fixtures.forEachDeclared { apk ->
            val classes = classesOf(apk) { it.type in models }.associateBy { it.type }
            fun fieldType(owner: String, name: String) =
                classes["$MODEL$owner;"]?.fields?.singleOrNull { it.name == name }?.type
            assertEquals("StickerItem.stickerBase", "${MODEL}StickerBase;", fieldType("StickerItem", "stickerBase"))
            assertEquals("StickerBase.image", "${MODEL}StickerImage;", fieldType("StickerBase", "image"))
            assertEquals("StickerBase.thumbnail", "${MODEL}StickerImage;", fieldType("StickerBase", "thumbnail"))
            assertEquals("StickerBase.stickerType", "Ljava/lang/Integer;", fieldType("StickerBase", "stickerType"))
            assertEquals("StickerImage.urlList", "Ljava/util/List;", fieldType("StickerImage", "urlList"))
            assertEquals("StickerImage.imageType", "Ljava/lang/String;", fieldType("StickerImage", "imageType"))

            val currentImage = classes.getValue("${MODEL}StickerItem;").methods.singleOrNull {
                it.name == "currentImage" && it.parameterTypes.isEmpty()
            }
            assertNotNull("StickerItem.currentImage()", currentImage)
            assertEquals("StickerItem.currentImage()", "${MODEL}StickerImage;", currentImage!!.returnType)
            // The saver calls it through Class.getMethod, which finds public methods only.
            assertTrue("StickerItem.currentImage() is public", AccessFlags.PUBLIC.isSet(currentImage.accessFlags))
        }
    }

    @Test
    fun `the sticker type numbers the saver sorts by are TikTok's own on every declared build`() {
        val saver = listOf("../$SAVER", SAVER).map(::File).firstOrNull { it.isFile }
        assertNotNull("could not find the saver from ${File(".").absolutePath}", saver)
        val named = Regex("""static final int TYPE_([A-Z_]+) = (\d+);""").findAll(saver!!.readText())
            .associate { it.groupValues[1] to it.groupValues[2].toInt() }
        assertEquals("the saver's sticker types", 9, named.size)

        Fixtures.forEachDeclared { apk ->
            val types = stickerTypes(classesOf(apk) { def ->
                def.superclass == "Ljava/lang/Enum;" &&
                    def.staticFields.map { it.name }.containsAll(listOf("STATIC", "ANIMATED", "GIPHY", "THIRD_PARTY_TENOR"))
            })
            for ((name, number) in named) {
                assertEquals("sticker type $name", types[name], number)
            }
        }
    }

    /**
     * The sticker type enum's constants and what its getType() answers for each, read off its
     * static initializer. The enum is the one whose constants are the sticker kinds; its own
     * name is R8's (X.0CO2 on 47.0.3, X.0Bon on 47.1.3).
     */
    private fun stickerTypes(enums: List<ClassDef>): Map<String, Int> {
        assertEquals("sticker type enums ${enums.map { it.type }}", 1, enums.size)
        val enumClass = enums.single()

        // The constructor takes (name, ordinal, type) and keeps the type in the field getType() reads.
        val constructor = enumClass.directMethods.single {
            it.name == "<init>" && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/String;", "I", "I")
        }
        val typeRegister = constructor.implementation!!.registerCount - 1
        val typeField = constructor.implementation!!.instructions.single {
            it.opcode == Opcode.IPUT && (it as TwoRegisterInstruction).registerA == typeRegister
        }.let { ((it as ReferenceInstruction).reference as FieldReference).name }
        val getType = enumClass.virtualMethods.single { it.name == "getType" && it.parameterTypes.isEmpty() }
        val read = getType.implementation!!.instructions.single { it.opcode == Opcode.IGET }
        assertEquals("getType() reads the constructor's type", typeField, ((read as ReferenceInstruction).reference as FieldReference).name)

        val constants = setOf(Opcode.CONST_4, Opcode.CONST_16, Opcode.CONST, Opcode.CONST_HIGH16)
        val literals = HashMap<Int, Int>()
        val strings = HashMap<Int, String>()
        val types = HashMap<String, Int>()
        val clinit = enumClass.directMethods.single { it.name == "<clinit>" }
        for (instruction in clinit.implementation!!.instructions) {
            when (instruction.opcode) {
                in constants ->
                    literals[(instruction as OneRegisterInstruction).registerA] =
                        (instruction as NarrowLiteralInstruction).narrowLiteral
                Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO ->
                    strings[(instruction as OneRegisterInstruction).registerA] =
                        ((instruction as ReferenceInstruction).reference as StringReference).string
                Opcode.INVOKE_DIRECT -> {
                    val reference = (instruction as ReferenceInstruction).reference as MethodReference
                    if (reference.definingClass != enumClass.type || reference.name != "<init>") continue
                    val call = instruction as FiveRegisterInstruction
                    val name = strings[call.registerD] ?: continue
                    types[name] = literals[call.registerF] ?: error("$name: its type is not a constant")
                }
                else -> {}
            }
        }
        return types
    }

    /** The APK's classes that [wanted] takes. Only those are kept: a whole 47.x build fills the test heap. */
    private fun classesOf(apk: File, wanted: (ClassDef) -> Boolean): List<ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        return container.dexEntryNames.asSequence()
            .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
            .filter(wanted)
            .toList()
    }

    private companion object {
        const val MODEL = "Lcom/ss/android/ugc/aweme/im/common/model/"
        const val SAVER = "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/download/StickerGallerySaver.java"
    }
}
