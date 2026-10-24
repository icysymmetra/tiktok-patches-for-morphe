/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.translation

import app.morphe.Fixtures
import app.morphe.ResourceIds
import app.morphe.patches.shared.compat.AppCompatibilities
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * The received-message menu row hands TikTok's menu a bare resource id for its icon, written in
 * the extension, so each declared host has to still give that id its languages icon.
 */
class ReceivedDmTranslationIconTest {
    @Test fun `the received menu row icon id names TikTok's languages icon on declared hosts`() {
        val repo = if (File("src/main/kotlin").isDirectory) File("..") else File(".")
        val source = File(repo, "extensions/tiktok/src/main/java/app/morphe/extension/tiktok/translation/ReceivedDmTranslationPreview.java")
        val declared = checkNotNull(Regex("""LANGUAGES_ICON = 0x([0-9a-f]{8});""").find(source.readText())) {
            "ReceivedDmTranslationPreview no longer declares LANGUAGES_ICON"
        }.groupValues[1].toLong(16).toInt()
        val app = checkNotNull(AppCompatibilities.tiktok().single().packageName)
        Fixtures.forEachDeclared { apk ->
            assertEquals(apk.name, listOf(declared), ResourceIds.read(apk, "raw").getValue(app)["icon_languages"])
        }
    }
}
