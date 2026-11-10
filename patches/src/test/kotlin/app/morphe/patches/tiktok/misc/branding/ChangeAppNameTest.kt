package app.morphe.patches.tiktok.misc.branding

import app.morphe.patcher.patch.PatchException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The launcher's name for the patched app. TikTok 47.x lists one launcher entry, an alias of
 * MainActivity with no label of its own, so the launcher shows the application's label; the
 * patch writes both, and leaves every other activity's label as it was.
 */
class ChangeAppNameTest {
    @Test
    fun `the application and every launcher entry take the name and nothing else changes`() {
        val manifest = manifest(
            """
            <activity android:name="com.ss.android.ugc.aweme.main.MainActivity" android:exported="true"/>
            <activity-alias android:name="com.ss.android.ugc.aweme.splash.SplashActivity"
                    android:targetActivity="com.ss.android.ugc.aweme.main.MainActivity" android:exported="true">
                <intent-filter>
                    <action android:name="android.intent.action.MAIN"/>
                    <category android:name="android.intent.category.LAUNCHER"/>
                </intent-filter>
            </activity-alias>
            <activity-alias android:name="com.ss.android.ugc.aweme.splash.SeasonalIcon" android:enabled="false"
                    android:label="@string/old" android:targetActivity="com.ss.android.ugc.aweme.main.MainActivity">
                <intent-filter>
                    <action android:name="android.intent.action.MAIN"/>
                    <category android:name="android.intent.category.LAUNCHER"/>
                </intent-filter>
            </activity-alias>
            <activity android:name="com.ss.android.ugc.aweme.deeplink.Links" android:label="MainActivity">
                <intent-filter>
                    <action android:name="android.intent.action.VIEW"/>
                    <category android:name="android.intent.category.LAUNCHER"/>
                </intent-filter>
                <intent-filter>
                    <action android:name="android.intent.action.MAIN"/>
                    <category android:name="android.intent.category.DEFAULT"/>
                </intent-filter>
            </activity>
            """,
        )

        assertEquals(2, renameApp(manifest, "Metra"))
        assertEquals("Metra", element(manifest, "application").getAttribute("android:label"))
        assertEquals("Metra", named(manifest, "com.ss.android.ugc.aweme.splash.SplashActivity").getAttribute("android:label"))
        // An alias that already had a label of its own gets the name too, or it would keep showing its own.
        assertEquals("Metra", named(manifest, "com.ss.android.ugc.aweme.splash.SeasonalIcon").getAttribute("android:label"))
        // MAIN and LAUNCHER in two different filters is not a launcher entry.
        assertEquals("MainActivity", named(manifest, "com.ss.android.ugc.aweme.deeplink.Links").getAttribute("android:label"))
        assertTrue(!named(manifest, "com.ss.android.ugc.aweme.main.MainActivity").hasAttribute("android:label"))
    }

    @Test
    fun `a manifest with no launcher entry is refused before the label is written`() {
        val manifest = manifest("""<activity android:name="com.example.Only"/>""")
        assertThrows(PatchException::class.java) { renameApp(manifest, "Metra") }
        assertEquals("@string/app_name", element(manifest, "application").getAttribute("android:label"))
    }

    @Test
    fun `the name is trimmed and held to what a label can be`() {
        assertEquals("TikTok Clean", checkedAppName("  TikTok Clean  "))
        assertEquals("x".repeat(MAX_APP_NAME), checkedAppName("x".repeat(MAX_APP_NAME)))
        for (refused in listOf(null, "", "   ", "@string/app_name", "?attr/name", "x".repeat(MAX_APP_NAME + 1))) {
            assertThrows("accepted: $refused", PatchException::class.java) { checkedAppName(refused) }
        }
    }

    private fun manifest(entries: String): Document {
        val xml = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.zhiliaoapp.musically">
                <application android:label="@string/app_name">
                    $entries
                </application>
            </manifest>
        """.trimIndent()
        // Not namespace aware, the way the patcher's document reads a decoded manifest: the
        // attribute is called android:label, prefix and all.
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    }

    private fun element(document: Document, tag: String): Element = document.getElementsByTagName(tag).item(0) as Element

    private fun named(document: Document, name: String): Element =
        listOf("activity", "activity-alias").flatMap { tag ->
            val nodes = document.getElementsByTagName(tag)
            (0 until nodes.length).map { nodes.item(it) as Element }
        }.single { it.getAttribute("android:name") == name }
}
