package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Friends tab filters read: its card, the Reposts list and the reposter on a video. */
class FriendsFeedFixturesTest {
    private val wanted = setOf(
        "Lcom/ss/android/ugc/aweme/feed/model/friends/FriendsFeed;",
        "Lcom/ss/android/ugc/aweme/repostfeed/model/RepostFeed;",
        "Lcom/ss/android/ugc/aweme/repostfeed/model/RepostFeedResponse;",
        "Lcom/ss/android/ugc/aweme/feed/model/AwemeBizExtKt;",
    )

    @Test
    fun `a repost reaches the Friends filters as the card's own video on every retained fixture`() {
        for (apk in Fixtures.apks()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = mutableMapOf<String, ClassDef>()
            var v3Classes = 0
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                    if (classDef.type in wanted) classes[classDef.type] = classDef
                    if (classDef.type.contains("FriendsV3")) v3Classes++
                }
            }
            assertEquals("${apk.name}: the V3 Friends feed is gone", 0, v3Classes)

            val aweme = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
            val card = classes.getValue("Lcom/ss/android/ugc/aweme/feed/model/friends/FriendsFeed;")
            val cardFields = card.fields.associate { it.name to it.type }
            assertEquals("${apk.name}: Friends card video", aweme, cardFields["aweme"])
            assertTrue("${apk.name}: Friends card LIVE room", "roomStruct" in cardFields)
            assertTrue("${apk.name}: no separate repost wrapper", "repostItem" !in cardFields)

            val repost = classes.getValue("Lcom/ss/android/ugc/aweme/repostfeed/model/RepostFeed;")
            assertEquals(
                "${apk.name}: Reposts entry video",
                aweme,
                repost.fields.single { it.name == "aweme" }.type,
            )

            val response = classes.getValue("Lcom/ss/android/ugc/aweme/repostfeed/model/RepostFeedResponse;")
            assertTrue(
                "${apk.name}: Reposts list field",
                response.fields.any { it.name == "repostFeedData" && it.type == "Ljava/util/List;" },
            )
            val getter = response.methods.single {
                it.name == "getRepostFeedData" && it.parameterTypes.isEmpty() &&
                    it.returnType == "Ljava/util/List;"
            }
            assertTrue("${apk.name}: Reposts getter has a body", getter.implementation != null)

            val ext = classes.getValue("Lcom/ss/android/ugc/aweme/feed/model/AwemeBizExtKt;")
            assertTrue(
                "${apk.name}: the reposter a video names",
                ext.methods.any {
                    it.name == "getRepostUser" &&
                        it.parameterTypes.map(CharSequence::toString) == listOf(aweme) &&
                        it.returnType == "Lcom/ss/android/ugc/aweme/profile/model/User;" &&
                        AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
                },
            )
        }
    }
}
