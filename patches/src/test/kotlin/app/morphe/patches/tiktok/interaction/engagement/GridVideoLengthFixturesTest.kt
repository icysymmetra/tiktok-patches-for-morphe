package app.morphe.patches.tiktok.interaction.engagement

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import org.junit.Assert.assertTrue
import org.junit.Test

/** What Show video length on profile grids reads from a grid cell's item (#181). */
class GridVideoLengthFixturesTest {
    private val aweme = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
    private val video = "Lcom/ss/android/ugc/aweme/feed/model/Video;"

    @Test
    fun `a grid cell's video names its length in milliseconds on every retained fixture`() {
        for (apk in Fixtures.apks()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = mutableMapOf<String, ClassDef>()
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                    if (classDef.type == aweme || classDef.type == video) classes[classDef.type] = classDef
                }
            }
            fun ClassDef.hasPublicGetter(name: String, returns: String) = methods.any {
                it.name == name && it.parameterTypes.isEmpty() && it.returnType == returns &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
            }
            assertTrue("${apk.name}: Aweme.getVideo()", classes.getValue(aweme).hasPublicGetter("getVideo", video))
            assertTrue("${apk.name}: Video.getDuration()", classes.getValue(video).hasPublicGetter("getDuration", "I"))
        }
    }
}
