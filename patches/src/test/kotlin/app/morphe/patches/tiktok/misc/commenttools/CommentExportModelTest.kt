package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What Export comments reads off TikTok's comment model, held to every declared build.
 *
 * The export adds no hook. It reads the comments the search box already collects, by field name,
 * so a build that renames one of these would export an empty column instead of failing. This says
 * each name is still there with the type the writer expects.
 */
class CommentExportModelTest {
    private val comment = "Lcom/ss/android/ugc/aweme/comment/model/Comment;"

    private val expected = mapOf(
        "cid" to "Ljava/lang/String;",
        "text" to "Ljava/lang/String;",
        "user" to "Lcom/ss/android/ugc/aweme/profile/model/User;",
        "createTime" to "I",
        "diggCount" to "I",
        "replyCount" to "I",
        "replyCommentTotal" to "J",
        "replyComments" to "Ljava/util/List;",
        "rootCommentId" to "Ljava/lang/String;",
        "stickPosition" to "I",
        "authorPin" to "Z",
        "isAuthorDigged" to "Z",
        "commentLanguage" to "Ljava/lang/String;",
        "imageList" to "Ljava/util/List;",
    )

    @Test
    fun `the comment model keeps every field the export reads on each declared build`() {
        Fixtures.forEachDeclared { apk ->
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val found = HashMap<String, String>()
            for (entry in container.dexEntryNames) {
                val classDef = container.getEntry(entry)!!.dexFile.classes.firstOrNull { it.type == comment } ?: continue
                classDef.instanceFields.forEach { found[it.name] = it.type }
                break
            }
            assertNotNull("no Comment class", found.takeIf { it.isNotEmpty() })
            expected.forEach { (name, type) -> assertEquals("Comment.$name", type, found[name]) }
        }
    }
}
