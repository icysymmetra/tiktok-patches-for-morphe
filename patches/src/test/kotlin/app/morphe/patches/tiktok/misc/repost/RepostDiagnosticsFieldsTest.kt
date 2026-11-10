package app.morphe.patches.tiktok.misc.repost

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.MultiDexContainer
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Retrofit and response-model names the repost diagnostics read by reflection, held to each
 * declared TikTok build. The extension cannot link against them, so a rename would only show as
 * "unknown" in a report; here it fails first.
 *
 * What it reads, and where each name comes from on 47.1.4:
 * - the parsed response (the return type of the interceptor's parseResponse lancet): field LIZIZ
 *   is the body, LIZ() is the HTTP code and LIZJ() is the success flag;
 * - the raw response (the return type of the executeCall lancet): the HTTP code is the int field
 *   LIZIZ, and it has no LIZ() without arguments, which is why the request-stage line reads the
 *   field;
 * - the response models, which R8 leaves alone: status_code and error_code on BaseResponse,
 *   upvoteLists and repostList on the list responses, itemId on UpvoteStruct, getAid on Aweme;
 * - the HTTP failure the lancet casts to, whose getStatusCode() the error lines read.
 */
class RepostDiagnosticsFieldsTest {
    private val interceptor = "Lcom/bytedance/retrofit2/CallServerInterceptor;"
    private val baseResponse = "Lcom/ss/android/ugc/aweme/base/api/BaseResponse;"
    private val repostList = "Lcom/ss/android/ugc/aweme/upvote/model/RepostListResponse;"
    private val batchList = "Lcom/ss/android/ugc/aweme/upvote/model/UpvoteBatchListResponse;"
    private val upvoteStruct = "Lcom/ss/android/ugc/aweme/feed/model/upvote/UpvoteStruct;"
    private val aweme = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"

    private fun classesOf(container: MultiDexContainer<out DexFile>, types: Set<String>): Map<String, ClassDef> {
        val found = HashMap<String, ClassDef>()
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                if (classDef.type in types) found[classDef.type] = classDef
            }
            if (found.size == types.size) break
        }
        return found
    }

    private fun ClassDef.fieldType(name: String): String? =
        fields.singleOrNull { it.name == name }?.type

    private fun ClassDef.noArgMethod(name: String) =
        methods.singleOrNull { it.name == name && it.parameterTypes.isEmpty() }

    @Test
    fun `each declared build keeps the response names the repost diagnostics read`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val fixed = classesOf(
                container,
                setOf(interceptor, baseResponse, repostList, batchList, upvoteStruct, aweme),
            )
            for (type in listOf(interceptor, baseResponse, repostList, batchList, upvoteStruct, aweme)) {
                assertNotNull("$type is gone", fixed[type])
            }

            val lancets = fixed.getValue(interceptor).methods
            val execute = lancets.single { it.name.endsWith("NetworkUtilsLancet_executeCall") }
            val parse = lancets.single { it.name.endsWith("NetworkUtilsLancet_parseResponse") }
            val rawType = execute.returnType
            val parsedType = parse.returnType

            val derived = HashSet<String>().apply {
                add(rawType)
                add(parsedType)
                // The failure the execute lancet casts to and reads getStatusCode from.
                execute.implementation!!.instructions.forEach { instruction ->
                    val type = (instruction as? ReferenceInstruction)?.reference as? TypeReference
                    if (type != null && type.type.startsWith("L")) add(type.type)
                }
            }
            val classes = classesOf(container, derived)

            val parsed = checkNotNull(classes[parsedType]) { "the parsed response $parsedType is gone" }
            assertEquals("the parsed response's body field", "Ljava/lang/Object;", parsed.fieldType("LIZIZ"))
            val code = checkNotNull(parsed.noArgMethod("LIZ")) { "the parsed response lost LIZ()" }
            assertEquals("the parsed response's HTTP code", "I", code.returnType)
            assertTrue("LIZ() must be public for getMethod", AccessFlags.PUBLIC.isSet(code.accessFlags))
            val success = checkNotNull(parsed.noArgMethod("LIZJ")) { "the parsed response lost LIZJ()" }
            assertEquals("the parsed response's success flag", "Z", success.returnType)
            assertTrue("LIZJ() must be public for getMethod", AccessFlags.PUBLIC.isSet(success.accessFlags))

            val raw = checkNotNull(classes[rawType]) { "the raw response $rawType is gone" }
            assertEquals("the raw response's HTTP code field", "I", raw.fieldType("LIZIZ"))
            assertEquals(
                "the raw response gained an argument-free LIZ(), so its request-stage read can use it",
                null,
                raw.noArgMethod("LIZ"),
            )

            assertTrue(
                "no class the execute lancet casts to answers getStatusCode()",
                classes.values.any { it.noArgMethod("getStatusCode")?.returnType == "I" },
            )

            val base = fixed.getValue(baseResponse)
            assertEquals("I", base.fieldType("status_code"))
            assertEquals("I", base.fieldType("error_code"))
            for (type in listOf(repostList, batchList)) {
                assertEquals("$type extends BaseResponse", baseResponse, fixed.getValue(type).superclass)
                assertEquals("Ljava/util/List;", fixed.getValue(type).fieldType("upvoteLists"))
            }
            assertEquals("Ljava/util/List;", fixed.getValue(repostList).fieldType("repostList"))
            assertEquals("Ljava/lang/String;", fixed.getValue(upvoteStruct).fieldType("itemId"))
            val aid = fixed.getValue(aweme).noArgMethod("getAid")
            assertEquals("Ljava/lang/String;", aid?.returnType)
        }
    }
}
