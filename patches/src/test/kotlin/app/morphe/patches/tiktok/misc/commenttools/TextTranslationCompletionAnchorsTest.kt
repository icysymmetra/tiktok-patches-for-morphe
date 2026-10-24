package app.morphe.patches.tiktok.misc.commenttools

import app.morphe.Fixtures
import app.morphe.patches.tiktok.misc.translation.carriesTranslationCompletionAnchor
import app.morphe.patches.tiktok.misc.translation.isTextTranslationCompletionCarrier
import com.android.tools.smali.dexlib2.Opcodes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where Comment tools judges a translation before TikTok applies it, held to every fixture.
 *
 * The keyword filter's completion hook goes on the completions that mark comments translated,
 * chosen by the patch's own predicate. TikTok's audio completion carries the same log anchor but
 * writes a voice comment's transcript, and hooking it hid a voice comment and then showed it again
 * on the next bind. So each fixture must have exactly one text completion the predicate takes,
 * and every other anchor carrier must be one it leaves out.
 */
class TextTranslationCompletionAnchorsTest {
    @Test
    fun `each fixture has one text completion for the keyword filter and leaves the audio one out`() {
        for (apk in Fixtures.apks()) {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            val anchors = mutableListOf<String>()
            val text = mutableListOf<String>()
            for (entry in container.dexEntryNames) {
                for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                    for (method in classDef.methods) {
                        if (!carriesTranslationCompletionAnchor(method)) continue
                        val name = "${classDef.type}->${method.name}"
                        anchors += name
                        if (isTextTranslationCompletionCarrier(classDef, method)) text += name
                    }
                }
            }
            assertEquals("${apk.name}: text completions the filter hooks, of anchor carriers $anchors", 1, text.size)
            assertTrue("${apk.name}: the text completion is one of the anchor carriers $anchors", anchors.containsAll(text))
        }
    }
}
