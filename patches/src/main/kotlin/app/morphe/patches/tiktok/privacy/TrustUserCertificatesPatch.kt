/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.privacy

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.branding.decodedXmlFiles
import app.morphe.patches.tiktok.misc.branding.elements
import app.morphe.patches.tiktok.misc.branding.readXml
import app.morphe.patches.tiktok.misc.diagnostics.BUILD_DETAILS_ASSET
import app.morphe.patches.tiktok.misc.diagnostics.BuildDetails
import app.morphe.patches.tiktok.misc.diagnostics.buildDetailsPatch
import app.morphe.patches.tiktok.misc.theme.decodedPackageRoots
import app.morphe.patches.tiktok.misc.theme.decodedResourceDirectories
import app.morphe.patches.tiktok.misc.theme.renamedPathCollisions
import org.w3c.dom.Document
import org.w3c.dom.Element

private const val NETWORK_SECURITY_CONFIG = "android:networkSecurityConfig"
private const val TRUST_ANCHORS = "trust-anchors"
private const val CERTIFICATES = "certificates"

/**
 * TikTok's network security config (`@xml/u` on 47.0.3, 47.1.3 and 47.1.4, the same file on all
 * three) trusts the system's certificates with their pins overridden and leaves the user's to
 * debug builds. Its domain configs only turn off cleartext and name no trust anchors of their own,
 * so they take the base config's. TTNet checks a server's chain through Chromium's X509Util
 * (com.ttnet.org.chromium.net.q0), which asks Android's default trust manager, and that one
 * follows this file.
 */
@Suppress("unused")
val trustUserCertificatesPatch = resourcePatch(
    name = "Trust user certificates",
    description = "Lets TikTok trust security certificates you install yourself, so a tool " +
        "like mitmproxy can show what the app sends. The risk: anyone who gets a certificate onto " +
        "your phone can read TikTok's traffic too. Use it only on a test phone.",
    default = false,
) {
    category("Privacy")
    compatibleWith(*AppCompatibilities.tiktokAndPineDrama())
    dependsOn(buildDetailsPatch)

    execute {
        val packageRoot = get("res").parentFile
        val decodedRoots = decodedPackageRoots(packageRoot)
        val collisions = renamedPathCollisions(
            entries = listApkEntries("res/"),
            decodedDirectories = decodedResourceDirectories(decodedRoots),
            aliasOf = { name -> get(name).relativeTo(packageRoot).invariantSeparatorsPath },
            isDecoded = { name -> decodedRoots.any { it.resolve(name).isFile } },
        )
        if (collisions.isNotEmpty()) throw PatchException(renamedConfigPathsRefusal(collisions.size))

        val reference = networkSecurityConfigReference(readXml(get("AndroidManifest.xml")))
        val files = decodedXmlFiles(get("res"), reference)
        if (files.isEmpty()) {
            throw PatchException("Trust user certificates: TikTok's network security config $reference isn't in the app. Nothing was changed.")
        }
        // A dry run on a throwaway copy of each file first: a refusal leaves every file as it was.
        files.forEach { trustUserCertificates(readXml(it)) }
        for (file in files) {
            document(file.relativeTo(get(".")).invariantSeparatorsPath).use { trustUserCertificates(it) }
        }
        BuildDetails.applied(get(BUILD_DETAILS_ASSET), "trustUserCertificates")
    }
}

internal fun renamedConfigPathsRefusal(collisions: Int) =
    "Trust user certificates: this TikTok APK was merged from a split bundle in a way that moved " +
        "its resource files into new folders, and $collisions of their paths clash with the " +
        "names the resource rebuild gives other files. Nothing was changed. Patch the .apkm in " +
        "Morphe Manager, which keeps TikTok's own paths, or patch the full APK from APKMirror."

/** The config the manifest's application names (`@xml/u`), or a refusal when it names none. */
internal fun networkSecurityConfigReference(manifest: Document): String {
    val application = manifest.getElementsByTagName("application").item(0) as? Element
        ?: throw PatchException("Trust user certificates: TikTok's manifest has no application element.")
    return application.getAttribute(NETWORK_SECURITY_CONFIG).trim().ifEmpty {
        throw PatchException("Trust user certificates: TikTok's manifest names no network security config. Nothing was changed.")
    }
}

/**
 * Adds the user's certificates, with their pins overridden, to the trust anchors of the base
 * config and of every domain config that names its own, so a certificate authority installed in
 * Android's settings is trusted wherever the system's are. A base config without trust anchors
 * gets the system's, the platform's own default, beside the user's. Debug overrides only reach a
 * debuggable build and stay as they are. Returns how many sets of trust anchors take the user's
 * certificates now.
 */
internal fun trustUserCertificates(config: Document): Int {
    val root = config.documentElement
    if (root?.tagName != "network-security-config") {
        throw PatchException("Trust user certificates: TikTok's network security config isn't one. Nothing was changed.")
    }
    val base = root.child("base-config")
        ?: config.createElement("base-config").also { root.insertBefore(it, root.firstChild) }
    if (base.child(TRUST_ANCHORS) == null) {
        base.appendChild(config.createElement(TRUST_ANCHORS).apply { appendChild(config.certificates("system")) })
    }
    val anchorSets = (listOf(base) + root.getElementsByTagName("domain-config").elements())
        .flatMap { scope -> scope.childNodes.elements().filter { it.tagName == TRUST_ANCHORS } }
    for (anchors in anchorSets) {
        val user = anchors.childNodes.elements().firstOrNull { it.tagName == CERTIFICATES && it.getAttribute("src") == "user" }
            ?: config.certificates("user").also { anchors.appendChild(it) }
        user.setAttribute("overridePins", "true")
    }
    return anchorSets.size
}

private fun Document.certificates(source: String): Element = createElement(CERTIFICATES).apply { setAttribute("src", source) }

private fun Element.child(tag: String): Element? = childNodes.elements().firstOrNull { it.tagName == tag }
