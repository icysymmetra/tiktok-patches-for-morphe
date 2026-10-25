package app.morphe.patches.tiktok.interaction.blockauthor

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcodes
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The daily hold's player members, resolved by the patch's own code ([resolveNativePlaybackMembers])
 * on each declared build and held to what that build calls them. Builds disagree: the pause was
 * LIZ on 46.2.3, 46.8.3 and 46.9.3, and on 47.0.3 LIZ was a getter returning a number, which the
 * hold called by name and took for a pause while the video played on under its panel (2026-09-23).
 * The expected names were read off the fixture's pauseVideo and space-key toggle, and a new target
 * needs its own row.
 */
class NativePlaybackFixturesTest {
    @Test
    fun `the hold's current video, pause and resume resolve on every fixture`() {
        val expected = mapOf(
            // version to (current-video getter, player manager, pause, resume)
            "47.4.3" to listOf("LLJJIJI", "LX/037y;", "LIZIZ", "LJIILLIIL"),
        )
        assertEquals("a row for each declared build", Fixtures.declaredVersions().toSet(), expected.keys)
        Fixtures.forEachDeclared { apk ->
            val want = expected.getValue(Fixtures.versionOf(apk))
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.flatMap { container.getEntry(it)!!.dexFile.classes }
                .associateBy { it.type }
            val pauseVideo = classes.getValue(PLAYER_CONTROLLER).methods
                .single { it.name == "pauseVideo" && it.parameterTypes.isEmpty() }
            val toggle = classes.getValue(FEED_RECOMMEND_FRAGMENT).methods
                .single { it.name == "onDispatchKeyEvent" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/KeyEvent;") }
            val members = resolveNativePlaybackMembers(pauseVideo, toggle) { classes[it]?.methods }
            assertEquals(
                "${apk.name}: getter, manager, pause, resume",
                want,
                listOf(members.awemeGetter.reference.name, members.manager, members.pause.reference.name, members.resume.reference.name),
            )
            assertEquals("${apk.name}: the getter is PlayerController's own", PLAYER_CONTROLLER, members.awemeGetter.reference.definingClass)
            assertFalse("${apk.name}: the getter is a virtual call", members.awemeGetter.throughInterface)
            assertTrue("${apk.name}: the pause goes through the manager interface", members.pause.throughInterface)
            assertTrue("${apk.name}: the resume goes through the manager interface", members.resume.throughInterface)

            // #83: the guard on TikTok's own resume sits in the implementation's resume, so that
            // class has to be the manager TikTok's interface calls land on, and its resume needs a
            // local for the hold's answer.
            assertEquals("${apk.name}: the manager implementation", "LX/037z;", members.implementation)
            val implementation = classes.getValue(members.implementation)
            assertTrue("${apk.name}: ${members.implementation} implements ${members.manager}",
                members.manager in implementation.interfaces)
            val resume = implementation.methods.implementationResume(members)
            val locals = resume.implementation!!.registerCount - 1
            assertTrue("${apk.name}: ${members.implementation}.${resume.name} has no local register", locals >= 1)
        }
    }

    @Test
    fun `the switches' play guards ask the hold before TikTok's play and resume`() {
        val root = File("src/main/kotlin").takeIf { it.isDirectory } ?: File("patches/src/main/kotlin")
        val bridge = File(root, "app/morphe/patches/tiktok/interaction/blockauthor/NativePlaybackBridge.kt").readText()
        val patch = File(root, "app/morphe/patches/tiktok/interaction/blockauthor/BlockAuthorPatch.kt").readText()
        assertTrue("the bridge patch does not install the guards", patch.contains("installSwitchPlayGuards(nativePlayback)"))
        assertTrue("the play is not asked about with its controller and Aweme",
            bridge.contains("invoke-static/range { p0 .. p1 }, \$HOLD->refusePlay(Ljava/lang/Object;Ljava/lang/Object;)Z"))
        assertTrue("a refused play is not answered as casting answers one",
            bridge.contains("\"const-string v0, \\\"\\\"\\nreturn-object v0\""))
        assertTrue("the manager's resume is not asked about",
            bridge.contains("invoke-static/range { p0 .. p0 }, \$HOLD->refuseResume(Ljava/lang/Object;)Z"))
    }
}
