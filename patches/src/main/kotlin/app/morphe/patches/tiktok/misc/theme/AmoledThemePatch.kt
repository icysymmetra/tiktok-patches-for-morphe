/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.patches.tiktok.misc.theme

import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.colorOption
import app.morphe.patcher.patch.resourcePatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patches.tiktok.misc.diagnostics.BUILD_DETAILS_ASSET
import app.morphe.patches.tiktok.misc.diagnostics.BuildChoice
import app.morphe.patches.tiktok.misc.diagnostics.BuildDetails
import app.morphe.patches.tiktok.misc.diagnostics.buildChoicePatch
import java.io.File
import org.w3c.dom.Document
import org.w3c.dom.Element

@Suppress("unused")
val amoledThemePatch = resourcePatch(
    name = "AMOLED dark theme",
    description = "Makes TikTok's dark mode backgrounds pure black, or a dark color you pick " +
        "in this patch's options. Black lets OLED screens switch those pixels off. Light mode " +
        "doesn't change. It's built in while patching, so only patching again without it undoes " +
        "it.",
    default = false,
) {
    category("Interface")
    compatibleWith(*AppCompatibilities.tiktok())
    dependsOn(buildChoicePatch(BuildChoice.AMOLED))
    val background by colorOption(
        key = "backgroundColor",
        default = "#000000",
        title = "Dark background color",
        description = "Pick a color, or type a solid color code like #000000. Black lets " +
            "OLED screens turn those pixels off completely.",
        values = mapOf("Black" to "#000000", "Mocha" to "#181825", "Dark gray" to "#121212"),
    )
    // The cards on TikTok's Compose settings pages come from a palette in code, not resources (#152).
    dependsOn(amoledComposeCardsPatch { background })
    execute {
        // The path-collision refusal examines the complete decoded resource namespace.
        // Preserve that safety check when this optional patch requests its resources.
        decode("res/**")
        val packageRoot = get("res").parentFile
        val decodedRoots = decodedPackageRoots(packageRoot)
        val collisions = renamedPathCollisions(
            entries = listApkEntries("res/"),
            decodedDirectories = decodedResourceDirectories(decodedRoots),
            aliasOf = { name -> get(name).relativeTo(packageRoot).invariantSeparatorsPath },
            isDecoded = { name -> decodedRoots.any { it.resolve(name).isFile } },
        )
        if (collisions.isNotEmpty()) throw PatchException(renamedPathsRefusal(collisions.size))
        val color = amoledColor(background)
        // The dark token block's background grays under this build's own names. Read before any
        // file is touched: a build whose palette was never read is refused with nothing changed.
        val backgrounds = darkBackgroundColors(packageMetadata.versionName)
        val surfaces = surfaceStyleItems(packageMetadata.versionName)
        val found = mutableSetOf<String>()
        val styleItemsFound = mutableSetOf<String>()
        val valuesDirectories = get("res").listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("values") }
        valuesDirectories.map { it.resolve("colors.xml") }.filter { it.exists() }.forEach { file ->
            document(file.relativeTo(get(".")).invariantSeparatorsPath).use { xml ->
                val colors = xml.getElementsByTagName("color")
                for (index in 0 until colors.length) {
                    val entry = colors.item(index) as Element
                    val name = entry.getAttribute("name")
                    if (name in backgrounds) {
                        entry.textContent = color
                        found.add(name)
                    }
                }
            }
        }
        // The sheets, the long-press panel and the Inbox Activity page never touch colors.xml.
        // They fill with theme attributes whose dark values are literals inside a <style>
        // (SURFACE_STYLE_ITEMS). Only a dark literal is rewritten, so a light style's white
        // stays white.
        valuesDirectories.map { it.resolve("styles.xml") }.filter { it.exists() }.forEach { file ->
            document(file.relativeTo(get(".")).invariantSeparatorsPath).use { xml ->
                styleItemsFound += rewriteDarkStyleItems(xml, surfaces, color)
            }
        }
        if (found != backgrounds) throw PatchException("Dark background palette is incomplete: $found")
        // The palette check above refuses every build whose names weren't read, so this is a
        // build whose surface names are known, and one missing means an identity is wrong.
        if (styleItemsFound != surfaces) {
            throw PatchException("Dark surface style items are incomplete: $styleItemsFound")
        }
        BuildDetails.amoled(get(BUILD_DETAILS_ASSET), color)
    }
}

internal fun renamedPathsRefusal(collisions: Int, patch: String = "AMOLED dark theme") =
    "$patch: this TikTok APK was merged from a split bundle in a way that moved " +
        "its resource files into new folders, and $collisions of their paths clash with the " +
        "names the resource rebuild gives other files. The rebuild would drop files and " +
        "TikTok would crash at launch. Nothing was changed. Patch the .apkm in Morphe Manager, " +
        "which keeps TikTok's own paths, or patch the full APK from APKMirror."

/**
 * The decoded package directories: the one holding [packageRoot] and its siblings that have a
 * `res` directory. Every resource package decodes to its own directory, and a clash can be in
 * any of them.
 */
internal fun decodedPackageRoots(packageRoot: File): List<File> =
    (listOf(packageRoot) + packageRoot.parentFile?.listFiles().orEmpty()
        .filter { it.isDirectory && it.resolve("res").isDirectory })
        .distinctBy { it.absoluteFile.normalize() }

/** Every `res/<folder>` the decoder wrote, across [roots]. */
internal fun decodedResourceDirectories(roots: List<File>): Set<String> =
    roots.flatMapTo(mutableSetOf()) { root ->
        root.resolve("res").listFiles().orEmpty().filter { it.isDirectory }.map { "res/${it.name}" }
    }

/**
 * Input resource paths that Morphe's resource rebuild can drop: a path whose own file was
 * decoded under another name (the path map renamed it) while another resource's decoded file
 * sits at that same path.
 *
 * <p>The decoder writes every resource file to `res/<type>/<entry name>` and records the
 * original archive path beside it; the rebuild renames each file back. TikTok's own paths are
 * short folders (`res/b/cfz.xml`), so nothing clashes, and Morphe Manager's merge of an .apkm
 * keeps them. The desktop CLI's merge moves every file into a folder named for its type and
 * keeps the short name, so entry `ac`'s file becomes `res/drawable/a0.xml`, which is also where
 * entry `a0`'s file decodes. On APKMirror's 46.2.3 and 47.0.3 bundles that made 14,468 and
 * 14,925 such clashes, the rebuild lost 1,375 and 1,361 files, all clashing paths, and TikTok died
 * inflating its first feed layout on the S22 (2026-09-18). Manager's merge of the same bundles
 * and both universal APKs have none and lose nothing. A file kept at its own path (Play's
 * `res/xml/splits0.xml` in a universal APK) is not a clash: its alias is itself.
 */
internal fun renamedPathCollisions(
    entries: Iterable<String>,
    decodedDirectories: Set<String>,
    aliasOf: (String) -> String,
    isDecoded: (String) -> Boolean,
): List<String> = entries.filter { name ->
    // The folder check comes first and is free: a clash can only sit where the decoder writes,
    // and on a clean input none of the archive's folders is one of those.
    name.substringBeforeLast('/', "") in decodedDirectories && aliasOf(name) != name && isDecoded(name)
}

/**
 * The dark theme's background grays, per build: the opaque dark grays of the color token block
 * the dark app themes set, which the surfaces read. The names are each build's own and move:
 * 47.0.3 and 47.1.3 each added a color ahead of the block, so every gray moved one name along
 * (47.4.3 added two), and an older build's names there are an accent and see-through white
 * overlays, which the patch painted black until this was read off the fixture (AmoledPaletteTest
 * holds every fixture's block to its entry here). Only the declared build is listed, so a build
 * the bundle moves to needs its own entry read off its fixture. The block's pure black is a token
 * of its own and stays.
 */
internal val DARK_BACKGROUND_COLORS: Map<String, Set<String>> = linkedMapOf(
    "47.4.3" to setOf("a42", "a44", "a45", "a47", "a4e"),
)

/** This build's background grays, or a refusal: a name carried over from another build is a guess. */
internal fun darkBackgroundColors(versionName: String?): Set<String> =
    versionName?.let { DARK_BACKGROUND_COLORS[it] } ?: throw PatchException(unreadPaletteRefusal(versionName))

internal fun unreadPaletteRefusal(versionName: String?): String {
    val build = if (versionName.isNullOrBlank()) "this TikTok build" else "TikTok $versionName"
    val known = DARK_BACKGROUND_COLORS.keys.toList()
    val builds = if (known.size == 1) known.single() else known.dropLast(1).joinToString(", ") + " and " + known.last()
    return "AMOLED dark theme hasn't read the dark palette of $build, so nothing was changed. " +
        "TikTok renames its colors from one build to the next, and a name taken from another " +
        "build can paint an accent or an overlay black. The palette is known for TikTok $builds."
}

/** The builds the bundle declares. */
internal fun declaredVersions(): Set<String> =
    AppCompatibilities.tiktok().flatMap { it.targets }.mapNotNull { it.version }.toSet()

/**
 * The dark style literals behind TikTok's sheets and pages per build, named by TikTok's own token
 * table (AmoledSheetTokensTest). On 47.4.3:
 * - aic is UISheetFlat1's dark value (attr/a25), the fill of the comment panel, the share sheet
 *   and TikTok's other sheets, panels and modals.
 * - aii is UISheetGrouped1's (attr/a2a), the fill of the feed's long-press panel and the other
 *   sheets that group their rows into cards. The cards are UISheetGrouped2 and keep their gray.
 * - bx is BGPrimary, a literal of its own in the dark themes, which the Inbox Activity page, its
 *   rows and New followers fill with.
 * - agp is UIPageGrouped2's (attr/a0h), the cards of TikTok's own settings pages: a TuxCell off
 *   a sheet fills with it. The pages behind them are UIPageGrouped1, black in TikTok's own dark
 *   mode already, so those cards were the only gray left on Settings and privacy (#152). A
 *   TuxCell on a sheet (the profile menu panel) fills with UISheetGrouped2 instead and keeps its
 *   gray like the long-press panel's cards.
 * - agm (UIPageFlat3Elevated) and c3 were the comments and share sheets' own through 46.x, under
 *   other names, and are dark surfaces of their own on 47.x.
 * The token names move a place or two with every build, and on 46.x a name these use now was
 * another surface tier, which is why only a build whose names were read is patched.
 */
internal val SURFACE_STYLE_ITEMS: Map<String, Set<String>> = linkedMapOf(
    "47.4.3" to setOf("agm", "c3", "aic", "aii", "bx", "agp"),
)

/** This build's surface style items, or a refusal like the palette's. */
internal fun surfaceStyleItems(versionName: String?): Set<String> =
    versionName?.let { SURFACE_STYLE_ITEMS[it] } ?: throw PatchException(unreadPaletteRefusal(versionName))

/**
 * Sets every `<item name="...">` in the named set whose value is a dark opaque colour literal
 * to [color], and returns the names it changed. A value that is a reference, translucent or
 * light is left alone: those are the light theme's and the overlays', not the surface.
 */
internal fun rewriteDarkStyleItems(styles: Document, names: Set<String>, color: String): Set<String> {
    val changed = mutableSetOf<String>()
    val items = styles.getElementsByTagName("item")
    for (index in 0 until items.length) {
        val item = items.item(index) as Element
        val name = item.getAttribute("name")
        if (name !in names || !isDarkOpaqueLiteral(item.textContent)) continue
        item.textContent = color
        changed.add(name)
    }
    return changed
}

/** `#RRGGBB` or `#FFRRGGBB`, every channel under 0x40: a surface colour, not a text or an overlay. */
internal fun isDarkOpaqueLiteral(value: String): Boolean {
    val hex = value.trim().removePrefix("#")
    val rgb = when (hex.length) {
        6 -> hex
        8 -> if (hex.startsWith("ff", ignoreCase = true)) hex.substring(2) else return false
        else -> return false
    }
    if (!rgb.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return false
    return rgb.chunked(2).all { it.toInt(16) < 0x40 }
}
