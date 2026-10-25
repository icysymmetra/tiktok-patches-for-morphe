package app.morphe.patches.tiktok.interaction.authorregion

import app.morphe.Fixtures
import app.morphe.patches.tiktok.misc.inbox.MainActivityOnCreateFingerprint
import app.morphe.takes
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Test

/** Both windows the country is shown in are hooked where they're created, on every declared host. */
class ShowAuthorRegionFixturesTest {
    @Test
    fun `the feed and the detail page each have one onCreate to install from`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val classes = container.dexEntryNames.asSequence()
                .flatMap { container.getEntry(it)!!.dexFile.classes.asSequence() }
                .toList()
            val expected = mapOf(
                MainActivityOnCreateFingerprint to "Lcom/ss/android/ugc/aweme/main/MainActivity;->onCreate",
                DetailActivityOnCreateFingerprint to "Lcom/ss/android/ugc/aweme/detail/ui/DetailActivity;->onCreate",
            )
            for ((fingerprint, method) in expected) {
                val taken = classes.flatMap { classDef ->
                    classDef.methods.filter { fingerprint.takes(it, classDef) }.map { "${classDef.type}->${it.name}" }
                }
                assertEquals(version, listOf(method), taken)
            }
        }
    }
}
