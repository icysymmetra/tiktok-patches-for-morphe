package app.morphe.patches.tiktok.privacy

import app.morphe.patcher.patch.PatchException
import java.io.StringReader
import java.io.StringWriter
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.xml.sax.InputSource

/**
 * Trust user certificates on the decoded shape of TikTok's network security config.
 *
 * <p>47.1.4 names `@xml/u` (0x7f150014, archive path res/au/o.xml), as 47.0.3 and 47.1.3 did, with
 * the same file: a base config trusting the system's certificates with their pins overridden,
 * debug overrides trusting the user's, and domain configs that set only cleartext. TIKTOK below
 * is that file cut to two of its 57 domains, read off the fixture with
 * `aapt2 dump xmltree --file res/au/o.xml`.
 */
class TrustUserCertificatesTest {
    @Test
    fun `TikTok's base config takes the user's certificates and nothing else moves`() {
        val config = parse(TIKTOK)

        assertEquals(1, trustUserCertificates(config))

        val root = config.documentElement
        val base = child(root, "base-config")
        assertEquals("true", base.getAttribute("cleartextTrafficPermitted"))
        assertEquals(
            listOf("system" to "true", "user" to "true"),
            certificates(child(base, "trust-anchors")),
        )
        assertEquals("false", child(base, "certificateTransparency").getAttribute("enabled"))
        // Debug overrides only reach a debuggable build, so they stay as TikTok wrote them.
        assertEquals(listOf("user" to ""), certificates(child(child(root, "debug-overrides"), "trust-anchors")))
        // The domain configs keep taking the base config's anchors, with their cleartext rules.
        val domains = descendants(root, "domain-config")
        assertEquals(listOf("true", "false"), domains.map { it.getAttribute("cleartextTrafficPermitted") })
        assertTrue(domains.all { it.getElementsByTagName("trust-anchors").length == 0 })
        assertEquals(listOf("zero-rating.tiktok.com", "tiktokcdn.com", "tiktokv.com"), descendants(root, "domain").map { it.textContent })

        // What the patcher writes back parses again to the same anchors.
        val again = parse(serialize(config))
        assertEquals(listOf("system" to "true", "user" to "true"), certificates(child(child(again.documentElement, "base-config"), "trust-anchors")))
    }

    @Test
    fun `a second run adds no second entry and a user entry gets its pins overridden`() {
        val config = parse(TIKTOK)
        trustUserCertificates(config)
        trustUserCertificates(config)
        assertEquals(listOf("system" to "true", "user" to "true"), baseAnchors(config))

        val plain = parse(
            """
            <network-security-config>
                <base-config>
                    <trust-anchors>
                        <certificates src="user" />
                        <certificates src="system" />
                    </trust-anchors>
                </base-config>
            </network-security-config>
            """.trimIndent(),
        )
        assertEquals(1, trustUserCertificates(plain))
        assertEquals(listOf("user" to "true", "system" to ""), baseAnchors(plain))
    }

    @Test
    fun `a domain config with its own anchors takes the user's certificates too, nested ones included`() {
        val config = parse(
            """
            <network-security-config>
                <base-config>
                    <trust-anchors>
                        <certificates src="system" />
                    </trust-anchors>
                </base-config>
                <domain-config>
                    <domain includeSubdomains="true">tiktokv.com</domain>
                    <trust-anchors>
                        <certificates src="@raw/c1" />
                    </trust-anchors>
                    <pin-set expiration="2030-01-01">
                        <pin digest="SHA-256">7HIpactkIAq2Y49orFOOQKurWxmmSFZhBCoQYcRhJ3Y=</pin>
                    </pin-set>
                    <domain-config>
                        <domain>api.tiktokv.com</domain>
                        <trust-anchors>
                            <certificates src="system" />
                        </trust-anchors>
                    </domain-config>
                </domain-config>
            </network-security-config>
            """.trimIndent(),
        )

        assertEquals(3, trustUserCertificates(config))

        val (outer, inner) = descendants(config.documentElement, "domain-config")
        assertEquals(listOf("@raw/c1" to "", "user" to "true"), certificates(child(outer, "trust-anchors")))
        assertEquals(listOf("system" to "", "user" to "true"), certificates(child(inner, "trust-anchors")))
        // The pins stay: overridePins lets a chain to the user's certificates past them.
        assertEquals(1, config.getElementsByTagName("pin").length)
    }

    @Test
    fun `a config without a base config or its anchors gets the platform's system anchors beside the user's`() {
        val bare = parse(
            """
            <network-security-config>
                <domain-config cleartextTrafficPermitted="false">
                    <domain includeSubdomains="true">tiktokv.com</domain>
                </domain-config>
            </network-security-config>
            """.trimIndent(),
        )
        assertEquals(1, trustUserCertificates(bare))
        assertEquals("base-config", children(bare.documentElement, "*").first().tagName)
        assertEquals(listOf("system" to "", "user" to "true"), baseAnchors(bare))

        val noAnchors = parse(
            """
            <network-security-config>
                <base-config cleartextTrafficPermitted="false" />
            </network-security-config>
            """.trimIndent(),
        )
        assertEquals(1, trustUserCertificates(noAnchors))
        assertEquals(listOf("system" to "", "user" to "true"), baseAnchors(noAnchors))
    }

    @Test
    fun `a file that isn't a network security config is refused`() {
        val message = assertThrows(PatchException::class.java) {
            trustUserCertificates(parse("<resources />"))
        }.message.orEmpty()
        assertTrue(message, message.startsWith("Trust user certificates: ") && message.endsWith(" Nothing was changed."))
    }

    @Test
    fun `the manifest's reference is read, and a manifest naming no config is refused`() {
        val manifest = parse(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.zhiliaoapp.musically">
                <application android:icon="@mipmap/c" android:networkSecurityConfig="@xml/u" />
            </manifest>
            """.trimIndent(),
        )
        assertEquals("@xml/u", networkSecurityConfigReference(manifest))

        val none = parse(
            """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.zhiliaoapp.musically">
                <application android:icon="@mipmap/c" />
            </manifest>
            """.trimIndent(),
        )
        val message = assertThrows(PatchException::class.java) { networkSecurityConfigReference(none) }.message.orEmpty()
        assertTrue(message, message.endsWith("names no network security config. Nothing was changed."))
    }

    private fun baseAnchors(config: Document) = certificates(child(child(config.documentElement, "base-config"), "trust-anchors"))

    /** Each certificates entry as its source and its overridePins ("" when it has none). */
    private fun certificates(anchors: Element) =
        children(anchors, "certificates").map { it.getAttribute("src") to it.getAttribute("overridePins") }

    private fun child(parent: Element, tag: String): Element = children(parent, tag).single()

    /** The direct child elements named [tag], or all of them for "*". */
    private fun children(parent: Element, tag: String): List<Element> =
        (0 until parent.childNodes.length).mapNotNull { parent.childNodes.item(it) as? Element }
            .filter { tag == "*" || it.tagName == tag }

    /** Every element named [tag] under [parent], at any depth, in document order. */
    private fun descendants(parent: Element, tag: String): List<Element> {
        val nodes = parent.getElementsByTagName(tag)
        return (0 until nodes.length).mapNotNull { nodes.item(it) as? Element }
    }

    private fun parse(text: String): Document =
        DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(text)))

    private fun serialize(document: Document): String {
        val writer = StringWriter()
        TransformerFactory.newInstance().newTransformer().transform(DOMSource(document), StreamResult(writer))
        return writer.toString()
    }

    private companion object {
        val TIKTOK =
            """
            <?xml version="1.0" encoding="utf-8"?>
            <network-security-config>
                <base-config cleartextTrafficPermitted="true">
                    <trust-anchors>
                        <certificates overridePins="true" src="system" />
                    </trust-anchors>
                    <certificateTransparency enabled="false" />
                </base-config>
                <debug-overrides>
                    <trust-anchors>
                        <certificates src="user" />
                    </trust-anchors>
                </debug-overrides>
                <domain-config cleartextTrafficPermitted="true">
                    <domain includeSubdomains="false">zero-rating.tiktok.com</domain>
                </domain-config>
                <domain-config cleartextTrafficPermitted="false">
                    <domain includeSubdomains="true">tiktokcdn.com</domain>
                    <domain includeSubdomains="true">tiktokv.com</domain>
                </domain-config>
            </network-security-config>
            """.trimIndent()
    }
}
