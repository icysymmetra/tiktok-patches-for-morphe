/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every extension bridge a patch finds by name has exactly one method of that name to find.
 *
 * Several patches look a bridge up by name while patching and rewrite its body, and each needs
 * one match. An extension-only push runs the tests and the lint but, by design, not buildAndroid
 * or the fixture application, so a renamed, removed or overloaded bridge used to fail first in a
 * release receipt or in Morphe Manager. This reads the patch sources and the extension sources
 * and fails naming both, without the desktop CLI.
 *
 * A lookup the scan can't resolve to a name fails too: the first version of this scan missed
 * every bridge whose name came from a loop or whose class was spelled with a template, and
 * passed.
 */
class ExtensionBridgeLookupTest {
    private val repo = File("..").takeIf { File(it, "extensions").isDirectory } ?: File(".")
    private val patchSources = File(repo, "patches/src/main/kotlin")

    private data class Scan(val found: List<Triple<String, String, String>>, val unresolved: List<String>)

    private fun scanTree(): Scan {
        val found = mutableListOf<Triple<String, String, String>>()
        val unresolved = mutableListOf<String>()
        patchSources.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { file ->
            val scan = scan(file.name, file.readText())
            found += scan.found
            unresolved += scan.unresolved
        }
        return Scan(found, unresolved)
    }

    /** Bridges one patch source looks up by name, with the lookups it could not resolve. */
    private fun scan(fileName: String, text: String): Scan {
        val found = mutableListOf<Triple<String, String, String>>()
        val unresolved = mutableListOf<String>()
        val constants = Regex("""const val (\w+)\s*=\s*"([^"]*)"""").findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }

        fun descriptor(argument: String): String? {
            val literal = Regex("""^"(.*)"$""").find(argument.trim())?.groupValues?.get(1)
                ?: return constants[argument.trim()]
            var unknown = false
            val resolved = Regex("""\$\{(\w+)}|\$(\w+)""").replace(literal) { match ->
                val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
                constants[name] ?: "".also { unknown = true }
            }
            return if (unknown) null else resolved
        }

        val classLookup = """(?:mutableClassDefBy|mutableClassDefByOrNull|classDefBy|classDefByOrNull)"""
        val methodLookup = """\.methods\??\.(?:filter|single|first|singleOrNull|firstOrNull)\s*\{\s*it\.name == """
        // Handles, and the lookups made straight on a class lookup with no handle between.
        val receivers = mutableListOf<Pair<String, String>>()
        Regex("""val (\w+)\s*=\s*$classLookup\(([^()]*)\)""").findAll(text).forEach { match ->
            descriptor(match.groupValues[2])?.let { receivers += Regex.escape(match.groupValues[1]) to it }
        }
        Regex("""$classLookup\(([^()]*)\)(?=$methodLookup)""").findAll(text).forEach { match ->
            descriptor(match.groupValues[1])?.let { receivers += Regex.escape(match.value) to it }
        }

        receivers.filter { (_, type) -> type.startsWith("Lapp/morphe/extension/") }.forEach { (receiver, type) ->
            Regex("""\b""" + receiver + methodLookup + """("?)(\w+)\1""").findAll(text).forEach { lookup ->
                val name = lookup.groupValues[2]
                if (lookup.groupValues[1] == "\"") {
                    found += Triple(fileName, type, name)
                    return@forEach
                }
                // A name passed in: the lookup must sit in a local helper taking it, and every
                // call of that helper must hand it a literal or a loop over literals.
                val helper = Regex("""fun (\w+)\(\s*${Regex.escape(name)}: String""")
                    .findAll(text.substring(0, lookup.range.first)).lastOrNull()
                if (helper == null) {
                    unresolved += "$fileName looks up a bridge on $type by `$name`, which isn't a helper's parameter"
                    return@forEach
                }
                val helperName = helper.groupValues[1]
                Regex("""\b${Regex.escape(helperName)}\(\s*([^,)\s]+)""").findAll(text)
                    .filter { it.range.first != helper.range.first + 4 }
                    .forEach { call ->
                        val argument = call.groupValues[1]
                        val literal = Regex("""^"(\w+)"$""").find(argument)
                        if (literal != null) {
                            found += Triple(fileName, type, literal.groupValues[1])
                            return@forEach
                        }
                        // The nearest loop naming it before the call, whichever form it takes.
                        val before = text.substring(0, call.range.first)
                        val loop = listOfNotNull(
                            Regex("""for \(\(\s*${Regex.escape(argument)}\s*,[^)]*\)\s+in\s+listOf\(([^)]*)\)\)""")
                                .findAll(before).lastOrNull(),
                            Regex("""for \(\s*${Regex.escape(argument)}\s+in\s+listOf\(([^)]*)\)\)""")
                                .findAll(before).lastOrNull(),
                        ).maxByOrNull { it.range.first }
                        val names = loop?.let { Regex(""""(\w+)"""").findAll(it.groupValues[1]).map { m -> m.groupValues[1] }.toList() }
                        if (names.isNullOrEmpty()) {
                            unresolved += "$fileName calls $helperName($argument, ...) with a name the scan can't read"
                        } else {
                            names.forEach { found += Triple(fileName, type, it) }
                        }
                    }
            }
        }
        return Scan(found, unresolved)
    }

    /** Declarations of [name] in [source]: annotated, package-private and instance ones included. */
    private fun declarationsIn(source: String, name: String): Int {
        val keywords = setOf("return", "new", "throw", "else", "case", "assert", "yield", "await")
        return Regex("""(?m)^[ \t]*(?:@[\w.]+(?:\([^)]*\))?\s+)*([^=;(){}\n]*?)\b${Regex.escape(name)}\s*\(""")
            .findAll(source).count { match ->
                val head = match.groupValues[1].trim()
                val words = head.split(Regex("""\s+""")).filter { it.isNotEmpty() }
                // A declaration has a return type (and maybe modifiers) before the name; a call
                // has nothing, a keyword, or an expression.
                if (words.isEmpty() || words.any { it in keywords } || head.startsWith("/") || head.startsWith("*") ||
                    head.contains('.') && !head.matches(Regex("""[\w.<>\[\]?, ]+"""))) {
                    return@count false
                }
                // And a body, a throws clause or, native and abstract, a semicolon after its parameters.
                var depth = 0
                var index = match.range.last
                while (index < source.length) {
                    when (source[index]) {
                        '(' -> depth++
                        ')' -> if (--depth == 0) break
                    }
                    index++
                }
                val rest = source.substring(minOf(index + 1, source.length)).trimStart()
                rest.startsWith("{") || rest.startsWith("throws") ||
                    (rest.startsWith(";") && words.any { it == "native" || it == "abstract" })
            }
    }

    private fun declarations(type: String, name: String): Int {
        val path = type.removePrefix("L").removeSuffix(";") + ".java"
        val source = listOf("extensions/tiktok/src/main/java", "extensions/shared/library/src/main/java")
            .map { File(repo, "$it/$path") }.firstOrNull { it.isFile }
            ?: return -1
        return declarationsIn(source.readText(), name)
    }

    @Test
    fun everyBridgeLookedUpByNameHasExactlyOneDeclaration() {
        val scan = scanTree()
        assertEquals("lookups the scan can't resolve to a bridge name", emptyList<String>(), scan.unresolved)
        val problems = scan.found.distinct().mapNotNull { (patch, type, name) ->
            when (val count = declarations(type, name)) {
                1 -> null
                -1 -> "$patch looks up $name on $type, and no source for that class was found"
                0 -> "$patch looks up $name on $type, which declares no method of that name"
                else -> "$patch looks up $name on $type, which declares $count methods of that name"
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    /** The scan has to see the bridges it exists for, or a pattern change would pass it empty. */
    @Test
    fun theScanFindsTheKnownBridges() {
        val found = scanTree().found.map { (patch, _, name) -> "$patch:$name" }.toSet()
        val known = listOf(
            "FeedMutePatch.kt:engineSourceId", "FeedMutePatch.kt:setEngineMute", "FeedMutePatch.kt:engineIsMute",
            "FeedMutePatch.kt:abandonSessionFocus", "FeedMutePatch.kt:requestSessionFocus",
            "FeedMutePatch.kt:abandonPageFocus", "FeedMutePatch.kt:requestPageFocus",
            "NativePlaybackBridge.kt:currentAweme", "NativePlaybackBridge.kt:pauseNative",
            "NativePlaybackBridge.kt:resumeNative", "SubtitleToolsPatch.kt:rootOf",
            "PlaybackSpeedPatch.kt:onFirstFrame", "PlaybackSpeedPatch.kt:setNativeSpeed",
            "PlaybackSpeedPatch.kt:nativeAweme", "AutoAdvancePatch.kt:readState",
            "NotInterestedPatch.kt:createCall", "RememberClearDisplayPatch.kt:postClear",
            "RememberClearDisplayPatch.kt:readCurrentAweme", "HideLauncherShortcutsPatch.kt:askHostToRebuild",
            "BackgroundPlayPatch.kt:replay",
        )
        val missing = known.filterNot(found::contains)
        assertTrue("the bridge scan no longer sees: $missing (saw ${found.sorted()})", missing.isEmpty())
    }

    /** Every shape a lookup has been written in, put in front of the scan. */
    @Test
    fun theScanReadsEveryShapeALookupComesIn() {
        val source = """
            private const val HOLDER = "Lapp/morphe/extension/tiktok/Holder;"
            private const val PACKAGE = "Lapp/morphe/extension/tiktok/"
            private const val HOST = "Lcom/ss/android/Host;"
            fun BytecodePatchContext.go() {
                val holder = mutableClassDefBy(HOLDER)
                holder.methods.filter { it.name == "literal" }
                holder.methods.singleOrNull { it.name == "single" }
                val templated = mutableClassDefBy("${'$'}{PACKAGE}Templated;")
                templated.methods.single { it.name == "fromTemplate" }
                mutableClassDefBy(HOLDER).methods.first { it.name == "inline" }
                fun rewrite(name: String, body: String) {
                    val original = holder.methods.filter { it.name == name }
                }
                rewrite("viaHelper", "")
                for ((name, call) in listOf("paired" to a, "alsoPaired" to b)) {
                    rewrite(name, "")
                }
                for (name in listOf("plain", "alsoPlain")) {
                    rewrite(name, "")
                }
                val host = mutableClassDefBy(HOST)
                host.methods.filter { it.name == "notABridge" }
            }
        """.trimIndent()
        val scan = scan("Sample.kt", source)
        assertEquals(emptyList<String>(), scan.unresolved)
        assertEquals(
            setOf("literal", "single", "fromTemplate", "inline", "viaHelper", "paired", "alsoPaired", "plain", "alsoPlain"),
            scan.found.map { it.third }.toSet(),
        )
        assertTrue(scan.found.single { it.third == "fromTemplate" }.second == "Lapp/morphe/extension/tiktok/Templated;")

        val unreadable = scan("Unreadable.kt", """
            private const val HOLDER = "Lapp/morphe/extension/tiktok/Holder;"
            fun BytecodePatchContext.go(names: List<String>) {
                val holder = mutableClassDefBy(HOLDER)
                fun rewrite(name: String) { holder.methods.filter { it.name == name } }
                names.forEach { rewrite(it) }
                val other = computed()
                holder.methods.filter { it.name == other }
            }
        """.trimIndent())
        assertEquals("unreadable lookups were not reported: ${unreadable.unresolved}", 2, unreadable.unresolved.size)
    }

    /** Declarations in every form a bridge is written, and calls that must not count. */
    @Test
    fun declarationsAreCountedWhateverTheirModifiers() {
        val source = """
            class Bridge {
                public static void one(Object a) {
                }
                @Keep static int two(Object a) { return 0; }
                void three(Object a) throws Exception {
                }
                private static native void four();
                static void caller() {
                    one(null);
                    int x = two(null);
                    return three(null);
                    helper(one(null));
                }
                static void one(
                        String overload) {
                }
                // one(x) {
                /** Calls {@link #one(Object)}. two(x) { */
            }
        """.trimIndent()
        assertEquals(2, declarationsIn(source, "one"))
        assertEquals(1, declarationsIn(source, "two"))
        assertEquals(1, declarationsIn(source, "three"))
        assertEquals(1, declarationsIn(source, "four"))
        assertEquals(1, declarationsIn(source, "caller"))
    }

    /** Names only match after R8 while it keeps them, which is what these two rules do. */
    @Test
    fun r8KeepsTheExtensionsNames() {
        val rules = File(repo, "extensions/proguard-rules.pro").readText()
        assertTrue("extensions/proguard-rules.pro no longer says -dontobfuscate", "-dontobfuscate" in rules)
        assertTrue("extensions/proguard-rules.pro no longer keeps every app.morphe.extension class",
            Regex("""-keep class app\.morphe\.extension\.\*\* \{\s*\*;\s*}""").containsMatchIn(rules))
    }
}
