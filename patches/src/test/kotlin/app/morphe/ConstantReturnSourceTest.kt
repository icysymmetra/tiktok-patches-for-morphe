/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No patch replaces a whole method with smali it wrote out by hand.
 *
 * <p>`returnEarly` checks the method's return type before it emits anything, and since the four
 * sites this gate was written for it checks that the frame holds the registers the value is
 * written into. Raw smali at index 0 checks neither: `MandatoryLoginService.shouldShowForcedLogin`
 * was taking `const/4 v0` and `return v0` on the strength of a fingerprint that matched on class
 * suffix and method name alone, so a build that boxed that answer would have shipped an integer
 * return on a method declaring an object and failed dex verification on the phone, which is a
 * long way from the patch that caused it.
 *
 * <p>Only the whole-method shape is refused: an insertion at index 0 whose entire body is a
 * constant and the return of that same register, or a bare `return-void`. A constant and a return
 * inside a guard, after an `if-eqz`, is ordinary patch code and is left alone.
 *
 * <p>This is a net, not a proof. It reads the shapes spelled out in the regexes below, and a
 * spelling nobody has written yet can walk past it: smali held in a local and handed in, or a
 * mnemonic the alternation does not list. It caught every site in the tree when it was written
 * and it is worth widening when it misses one, rather than being read as a guarantee that none
 * exists.
 */
class ConstantReturnSourceTest {
    /** Below this the scan has stopped finding the tree and the case proves nothing. */
    private val fewestCredibleSources = 90

    /** `addInstructions` and `addInstructionsWithLabels`, at index 0, with a block string. */
    private val tripleQuoted = Regex(
        "addInstructions(?:WithLabels)?\\s*\\(\\s*0\\s*,\\s*\"\"\"(.*?)\"\"\"",
        RegexOption.DOT_MATCHES_ALL,
    )

    /** The same, written on one line with escaped newlines in it. */
    private val singleQuoted = Regex(
        "addInstructions(?:WithLabels)?\\s*\\(\\s*0\\s*,\\s*\"((?:[^\"\\\\]|\\\\.)*)\"",
    )

    /** Every constant mnemonic a whole-method override could be written with. */
    private val constants =
        "const(?:/4|/16|/high16|-wide(?:/16|/32|/high16)?|-string(?:/jumbo)?|-class)?"

    /**
     * A constant written into a register and that same register handed straight back, or a bare
     * return-void, which is the whole-method override `returnEarly()` writes for a void method.
     * Parameter registers count: `const/4 p0, 0x0` and `return p0` is the same override.
     */
    private val wholeMethodOverride = Regex(
        "^(?:" + constants + "\\s+[vp](\\d+),[^\\n]*\\nreturn(?:-wide|-object)?\\s+[vp]\\1|return-void)$",
    )

    @Test
    fun noPatchWritesAConstantReturnByHand() {
        val sources = patchSources()
        assertTrue(
            "only ${sources.size} sources were found, so this case proves nothing",
            sources.size >= fewestCredibleSources,
        )

        val offenders = sources.flatMap { file ->
            handWrittenOverrides(file.readText()).map {
                file.path.replace('\\', '/').substringAfter("/app/morphe/") + ": " + it
            }
        }

        assertEquals(
            "these sites replace a whole method with smali written out by hand, which checks " +
                "neither the method's return type nor whether its frame holds the register the " +
                "value is written into. Call returnEarly(value) instead: " + offenders,
            emptyList<String>(),
            offenders,
        )
    }

    /**
     * What the four sites looked like before they were changed, and the two shapes that have to
     * go on being allowed. Without this the case above would pass just as happily against a scan
     * that matched nothing at all.
     */
    @Test
    fun theScanFindsTheShapeItIsLookingFor() {
        val quote = "\""
        val triple = quote + quote + quote
        val tripleQuotedSite = "        GoogleAuthAvailableFingerprint.method.addInstructions(\n" +
            "            0,\n" +
            "            " + triple + "\n" +
            "                const/4 v0, 0x0\n" +
            "                return v0\n" +
            "            " + triple + ",\n" +
            "        )\n"
        assertEquals(
            listOf("const/4 v0, 0x0; return v0"),
            handWrittenOverrides(tripleQuotedSite),
        )

        // The Feature Gate Lab site was one line with an escaped newline in it rather than a
        // block, so a scan that only read block strings would have missed a quarter of them.
        val oneLineSite = "        installed.addInstructions(0, " + quote +
            "const/4 v0, 0x1\\nreturn v0" + quote + ")\n"
        assertEquals(listOf("const/4 v0, 0x1; return v0"), handWrittenOverrides(oneLineSite))

        val guard = "        method.addInstructions(\n" +
            "            0,\n" +
            "            " + triple + "\n" +
            "                invoke-static {}, Lapp/morphe/Hook;->enabled()Z\n" +
            "                move-result v0\n" +
            "                if-eqz v0, :on\n" +
            "                const/4 v0, 0x0\n" +
            "                return v0\n" +
            "                :on\n" +
            "                nop\n" +
            "            " + triple + ",\n" +
            "        )\n"
        assertEquals(emptyList<String>(), handWrittenOverrides(guard))

        // The spellings a scan written around one example misses. Every one of these is the
        // same override, and none of them is what the four sites happened to be written as.
        val otherSpellings = mapOf(
            "with labels" to "        method.addInstructionsWithLabels(\n" +
                "            0,\n" +
                "            " + triple + "\n" +
                "                const/4 v0, 0x0\n" +
                "                return v0\n" +
                "            " + triple + ",\n" +
                "        )\n",
            "const/high16" to "        method.addInstructions(0, " + quote +
                "const/high16 v0, 0x3f800000\\nreturn v0" + quote + ")\n",
            "a parameter register" to "        method.addInstructions(0, " + quote +
                "const/4 p0, 0x0\\nreturn p0" + quote + ")\n",
            "a wide constant" to "        method.addInstructions(0, " + quote +
                "const-wide/16 v0, 0x1\\nreturn-wide v0" + quote + ")\n",
            "a comment in the middle" to "        method.addInstructions(0, " + quote +
                "const/4 v0, 0x0\\n# the gate is off\\nreturn v0" + quote + ")\n",
            "a bare return-void" to "        method.addInstructions(0, " + quote +
                "return-void" + quote + ")\n",
        )
        for ((what, site) in otherSpellings) {
            assertEquals("$what walked past the scan", 1, handWrittenOverrides(site).size)
        }

        // An insertion anywhere but the start of the method is not a whole-method override.
        val laterInsertion = "        method.addInstructions(index, " + quote +
            "const/4 v0, 0x0\\nreturn v0" + quote + ")\n"
        assertEquals(emptyList<String>(), handWrittenOverrides(laterInsertion))
    }

    /** Every index-0 insertion in [source] whose whole body is a constant and its return. */
    private fun handWrittenOverrides(source: String): List<String> =
        (tripleQuoted.findAll(source) + singleQuoted.findAll(source))
            .map { normalise(it.groupValues[1]) }
            .filter { wholeMethodOverride.containsMatchIn(it) }
            .map { it.replace("\n", "; ") }
            .toList()

    /**
     * The smali as the patcher sees it: escapes resolved, indentation, blank lines and smali's
     * own `#` comments gone, so a comment written between the constant and the return does not
     * take the site out of the scan.
     */
    private fun normalise(body: String): String = body
        .replace("\\n", "\n")
        .replace("\r\n", "\n")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .joinToString("\n")

    private fun patchSources(): List<File> {
        val root = listOf(File("src/main/kotlin"), File("patches/src/main/kotlin"))
            .firstOrNull { it.isDirectory }
        assertTrue("no patch source tree was found from ${File(".").absolutePath}", root != null)
        return root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }.toList()
    }
}
