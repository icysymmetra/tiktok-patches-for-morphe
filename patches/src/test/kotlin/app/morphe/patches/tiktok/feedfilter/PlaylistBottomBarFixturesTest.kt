package app.morphe.patches.tiktok.feedfilter

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The series playlist bar is suppressed at its own availability predicate. */
class PlaylistBottomBarFixturesTest {
    @Test
    fun `playlist bar availability remains unique and guardable on every retained fixture`() {
        for (apk in Fixtures.apks()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val modelClasses = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.filter { classDef ->
                    classDef.type == "Lcom/ss/android/ugc/aweme/feed/model/Aweme;" ||
                        classDef.type == "Lcom/ss/android/ugc/aweme/feed/model/PlayListInfo;"
                }
            }
            val aweme = modelClasses.single { it.type.endsWith("/Aweme;") }
            val playlistInfo = modelClasses.single { it.type.endsWith("/PlayListInfo;") }
            assertTrue(
                "${apk.name}: Aweme playlist_info model",
                aweme.fields.any {
                    it.name == "playlist_info" &&
                        it.type == "Lcom/ss/android/ugc/aweme/feed/model/PlayListInfo;"
                } && aweme.methods.any {
                    it.name == "getPlaylist_info" &&
                        it.returnType == "Lcom/ss/android/ugc/aweme/feed/model/PlayListInfo;"
                },
            )
            assertTrue(
                "${apk.name}: PlayListInfo mix id contract",
                playlistInfo.methods.any {
                    it.name == "getMixId" && it.parameterTypes.isEmpty() &&
                        it.returnType == "Ljava/lang/String;"
                },
            )
            val matches = container.dexEntryNames.flatMap { entry ->
                container.getEntry(entry)!!.dexFile.classes.filter { classDef ->
                    classDef.type.endsWith("/InteractPlayListBottomBarAssem;")
                }.flatMap { classDef ->
                    classDef.methods.filter { method ->
                        method.returnType == "Z" && method.parameterTypes.map(CharSequence::toString) ==
                            listOf("Lcom/ss/android/ugc/aweme/feed/model/VideoItemParams;") &&
                            !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null
                    }
                }
            }
            assertEquals("${apk.name}: playlist bar availability predicate", 1, matches.size)
            assertTrue(
                "${apk.name}: playlist predicate needs one local for the setting guard",
                matches.single().implementation!!.registerCount >= 3,
            )
        }
    }
}
