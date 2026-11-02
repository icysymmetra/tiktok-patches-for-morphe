package app.morphe.patches.tiktok.interaction.seekbar

import app.morphe.Fixtures
import app.morphe.takes
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the seek bar on video stories rests on, held to each TikTok build the bundle declares (#72),
 * through the same fingerprints the patch uses.
 *
 * <p>The video story cell's root component has one onViewCreated(View) and one instance bind taking
 * a VideoItemParams, which its generic bind reaches. The story layout builds one RadiusLayout, a
 * FrameLayout, straight under its root: the frame the line goes in. PlayerController's progress
 * callback is an instance method taking the id, the position and the length. A seek goes through
 * PlayerController's public getPlayerManager(), whose type declares seek(F), and is checked against
 * its public mCurrentAid; the cell's story comes from VideoItemParams.getAweme(). The extension
 * names every one of these, so each is held here by the same name.
 */
class StorySeekbarAnchorsTest {
    @Test
    fun `the story cell's view and bind each resolve to one instance method`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val cell = build.byType[STORY_VIDEO_CELL]
            assertNotNull("$STORY_VIDEO_CELL is not in this build", cell)

            for ((label, fingerprint) in listOf("view" to StoryVideoCellViewFingerprint, "bind" to StoryVideoCellBindFingerprint)) {
                val taken = build.methods.filter { (classDef, method) -> fingerprint.takes(method, classDef) }.toList()
                assertEquals("the cell's $label: ${taken.map { "${it.first.type}->${it.second.name}" }}", 1, taken.size)
                assertFalse("the cell's $label is static", AccessFlags.STATIC.isSet(taken.single().second.accessFlags))
            }

            // The generic bind casts its argument and calls the typed one by the same name, so the
            // hooked method is the one every bind goes through.
            val bind = cell!!.methods.single { StoryVideoCellBindFingerprint.takes(it, cell) }
            val generic = cell.methods.filter { method ->
                method.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;") && method.returnType == "V" &&
                    method.calls().any { it.name == bind.name && it.parameterTypes.map(CharSequence::toString) == listOf(VIDEO_ITEM_PARAMS) }
            }
            assertEquals("generic binds that reach ${bind.name}", 1, generic.size)
        }
    }

    @Test
    fun `the story layout builds one RadiusLayout, a FrameLayout`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            assertTrue("$RADIUS_LAYOUT is not a FrameLayout", build.descends(RADIUS_LAYOUT, FRAME_LAYOUT))
            val inflater = build.byType[STORY_ITEM_VIDEO]
            assertNotNull("$STORY_ITEM_VIDEO is not in this build", inflater)
            val built = inflater!!.methods.sumOf { method ->
                method.implementation?.instructions?.count {
                    it.opcode == Opcode.NEW_INSTANCE && it.getReference<TypeReference>()?.type == RADIUS_LAYOUT
                } ?: 0
            }
            assertEquals("RadiusLayouts the story layout builds", 1, built)
        }
    }

    @Test
    fun `the progress callback, the player manager's seek and the current id are where the extension looks`() {
        Fixtures.forEachDeclared { apk ->
            val build = Build(apk)
            val taken = build.methods.filter { (classDef, method) -> PlayProgressFingerprint.takes(method, classDef) }.toList()
            assertEquals("progress callbacks", 1, taken.size)
            assertFalse("the progress callback is static", AccessFlags.STATIC.isSet(taken.single().second.accessFlags))

            val controller = build.byType[PLAYER_CONTROLLER]!!
            val getter = controller.methods.singleOrNull { it.name == "getPlayerManager" && it.parameterTypes.isEmpty() }
            assertNotNull("PlayerController has no getPlayerManager()", getter)
            assertTrue("getPlayerManager() is not public", AccessFlags.PUBLIC.isSet(getter!!.accessFlags))
            assertTrue(
                "${getter.returnType} declares no seek(F)V",
                build.declaresAnywhere(getter.returnType) { it.name == "seek" && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == listOf("F") },
            )

            val current = controller.fields.singleOrNull { it.name == "mCurrentAid" }
            assertNotNull("PlayerController has no mCurrentAid", current)
            assertEquals("Ljava/lang/String;", current!!.type)
            assertFalse("mCurrentAid is static", AccessFlags.STATIC.isSet(current.accessFlags))

            val params = build.byType[VIDEO_ITEM_PARAMS]!!
            assertTrue(
                "VideoItemParams has no getAweme()",
                params.methods.any { it.name == "getAweme" && it.parameterTypes.isEmpty() && it.returnType == AWEME },
            )
        }
    }

    @Test
    fun `the patch hooks the story cell and the progress callback`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val source = File(root, "app/morphe/patches/tiktok/interaction/seekbar/ShowSeekbarPatch.kt").readText()
        assertTrue("the cell's view is not hooked", source.contains("StoryVideoCellViewFingerprint.method.storyCellViewHook()"))
        assertTrue("the cell's bind is not hooked", source.contains("StoryVideoCellBindFingerprint.method.storyCellBindHook()"))
        assertTrue("the progress callback is not hooked", source.contains("PlayProgressFingerprint.method.storyProgressHook()"))
    }

    private fun Method.calls(): List<MethodReference> =
        implementation?.instructions?.mapNotNull { it.getReference<MethodReference>() }.orEmpty()

    /** One fixture's classes by type; methods are walked on each ask, never held (the dex has millions). */
    private class Build(apk: File) {
        val byType = HashMap<String, ClassDef>()

        init {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) byType.putIfAbsent(classDef.type, classDef)
            }
        }

        val methods: Sequence<Pair<ClassDef, Method>>
            get() = byType.values.asSequence().flatMap { classDef -> classDef.methods.asSequence().map { classDef to it } }

        /** Whether [type] is [ancestor] or extends it through classes the APK defines. */
        fun descends(type: String, ancestor: String): Boolean {
            var current: String? = type
            while (current != null) {
                if (current == ancestor) return true
                current = byType[current]?.superclass
            }
            return false
        }

        /** Whether [type], a class it extends or an interface it takes on declares a method [wanted] accepts. */
        fun declaresAnywhere(type: String, wanted: (Method) -> Boolean): Boolean {
            val seen = HashSet<String>()
            val queue = ArrayDeque(listOf(type))
            while (queue.isNotEmpty()) {
                val classDef = byType[queue.removeFirst()] ?: continue
                if (!seen.add(classDef.type)) continue
                if (classDef.methods.any(wanted)) return true
                classDef.superclass?.let(queue::add)
                queue.addAll(classDef.interfaces)
            }
            return false
        }
    }

    private companion object {
        const val PLAYER_CONTROLLER = "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;"
        const val AWEME = "Lcom/ss/android/ugc/aweme/feed/model/Aweme;"
        const val RADIUS_LAYOUT = "Lcom/bytedance/tux/widget/RadiusLayout;"
        const val FRAME_LAYOUT = "Landroid/widget/FrameLayout;"

        /** The class TikTok's layout compiler made from story_item_video, the video story cell's layout. */
        const val STORY_ITEM_VIDEO = "Lcom/by/andInflater/story_item_video;"
    }
}
