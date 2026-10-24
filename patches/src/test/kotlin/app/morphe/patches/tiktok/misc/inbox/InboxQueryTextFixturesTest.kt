package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.*
import org.junit.Test

private const val BEAN = "Lcom/ss/android/ugc/aweme/notice/repo/list/bean/"
private const val TEMPLATE = "Lcom/ss/android/ugc/aweme/notice/repo/list/uitemplate/"

class InboxQueryTextFixturesTest {
    @Test fun `search text follows the typed native notice paths and structured text config fields`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            fun type(entry: String, name: String) = dex.getEntry(entry)!!.dexFile.classes.single { it.type == name }
            type("classes39.dex", "LX/1Mmc;").field("LL", "${BEAN}MusNotice;")
            assertEquals("${BEAN}BaseNotice;", type("classes12.dex", "${BEAN}MusNotice;").superclass)
            type("classes12.dex", "${BEAN}BaseNotice;").field("templateNotice", "${BEAN}GeneralTemplateNotice;")
            type("classes12.dex", "${BEAN}GeneralTemplateNotice;").field("uiTemplate", "${TEMPLATE}NoticeUITemplate;")
            val template = type("classes12.dex", "${TEMPLATE}NoticeUITemplate;")
            listOf("content", "event", "quoteContent").forEach { template.field(it, "Ljava/lang/String;") }
            listOf("contentTextLinkConfigs", "textPair").forEach { template.field(it, "Ljava/util/List;") }
            template.field("titleTemplate", "${TEMPLATE}TitleTemplate;")
            type("classes12.dex", "${TEMPLATE}TextLinkConfig;").field("text", "Ljava/lang/String;")
            val title = type("classes12.dex", "${TEMPLATE}TitleTemplate;")
            title.field("title", "Ljava/lang/String;")
            title.field("fromUsers", "Ljava/util/List;")
            val pair = type("classes12.dex", "${TEMPLATE}TextPair;")
            listOf("title", "content").forEach { pair.field(it, "${BEAN}NoticeTextStruct;") }
            val struct = type("classes9.dex", "${BEAN}NoticeTextStruct;")
            struct.field("content", "${BEAN}NoticeTextConfig;")
            struct.field("textConfigs", "Ljava/util/List;")
            type("classes36.dex", "${BEAN}NoticeTextConfig;").field("text", "Ljava/lang/String;")
            val formatter = type("classes39.dex", "LX/1MmM;")
            val body = formatter.methods.single { it.name == "LJII" }
            val reads = body.implementation!!.instructions.mapNotNull { it.getReference<FieldReference>() }
            assertTrue(reads.any { it.definingClass == template.type && it.name == "content" })
            assertTrue(reads.any { it.definingClass == template.type && it.name == "event" })
        }
    }

    @Test fun `native rich text uses single digit one-based text links and nickname remains a pure fallback`() {
        Fixtures.forEachDeclared { apk ->
            val dex = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val rich = dex.getEntry("classes39.dex")!!.dexFile.classes.single { it.type == "LX/1MmO;" }
                .methods.single { it.name == "LIZ" }
            val code = rich.implementation!!.instructions.toList()
            assertTrue(code.any { it.getReference<StringReference>()?.string == "\\[\\[text_link_(\\d)\\]\\]" })
            val decrement = code.indexOfFirst { it.opcode == Opcode.SUB_INT_2ADDR }
            val indexedLink = code.indexOfFirst { it.getReference<MethodReference>()?.let { method ->
                method.definingClass == "Lcom/bytedance/mt/protector/impl/collections/ListProtector;" && method.name == "get" } == true }
            assertTrue(decrement >= 0 && decrement < indexedLink)
            assertTrue(code.any { it.getReference<FieldReference>()?.let { field ->
                field.definingClass == "${TEMPLATE}TextLinkConfig;" && field.name == "text" } == true })
            val aliases = dex.getEntry("classes5.dex")!!.dexFile.classes.single { it.type == "LX/08z7;" }
            val name = aliases.methods.single { it.name == "LIZ" && it.parameterTypes.map(CharSequence::toString) ==
                listOf("Lcom/ss/android/ugc/aweme/profile/model/User;", "Z") }
            assertTrue(name.references().any { it.definingClass == "Lcom/ss/android/ugc/aweme/profile/model/User;" && it.name == "getNickname" })
            // The expansion ability is not a generic formatter: its base methods return null.
            val ability = dex.getEntry("classes39.dex")!!.dexFile.classes.single { it.type == "LX/1Mmd;" }
            listOf("LIZJ", "LJII").forEach { member ->
                val native = ability.methods.single { it.name == member }
                assertEquals(Opcode.RETURN_OBJECT, native.implementation!!.instructions.last().opcode)
                assertTrue(native.references().isEmpty())
            }
        }
    }

    private fun ClassDef.field(name: String, type: String) { assertEquals(type, fields.single { it.name == name }.type) }
    private fun Method.references(): List<MethodReference> = implementation!!.instructions.mapNotNull { it.getReference<MethodReference>() }
}
