/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.util

import com.google.gson.JsonParser
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The dependency list in the shipped catalog is a set, so it reads like one.
 *
 * <p>Two CAPTCHA rows used to say `["Settings", "BytecodePatch", "BytecodePatch"]`, which reads as
 * two dependencies and is one, and every row's order was whatever the traversal produced, so an
 * edit to one patch could reorder rows it had nothing to do with and make the catalog diff say
 * more than the change did. The generator deduplicates and sorts now; these cases are what says
 * the catalog in the tree actually went through it.
 */
class CatalogDependencyTest {
    private fun catalogPatches() = run {
        // The repository root first. The other way round, a run whose working directory is the
        // root resolves "../patches-list.json" to a sibling of the checkout, outside it.
        val catalog = File("patches-list.json").takeIf { it.isFile } ?: File("../patches-list.json")
        assertTrue("could not find the patch list from ${File(".").absolutePath}", catalog.isFile)
        JsonParser.parseString(catalog.readText()).asJsonObject.getAsJsonArray("patches")
            .map { it.asJsonObject }
    }

    @Test
    fun `no patch names a dependency twice`() {
        val offenders = catalogPatches().mapNotNull { patch ->
            val dependencies = patch.getAsJsonArray("dependencies")?.map { it.asString }.orEmpty()
            if (dependencies.size == dependencies.toSet().size) null
            else patch["name"].asString + " -> " + dependencies
        }
        assertEquals(
            "a dependency list is a set written down as a list. Regenerate the catalog with " +
                ":patches:generatePatchesList",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `every dependency list is sorted`() {
        val offenders = catalogPatches().mapNotNull { patch ->
            val dependencies = patch.getAsJsonArray("dependencies")?.map { it.asString }.orEmpty()
            if (dependencies == dependencies.sorted()) null
            else patch["name"].asString + " -> " + dependencies
        }
        assertEquals(
            "an unsorted list makes an unrelated edit look like a change to these rows. " +
                "Regenerate the catalog with :patches:generatePatchesList",
            emptyList<String>(),
            offenders,
        )
    }

    /** The verifier refuses a catalog the generator would never have written. */
    @Test
    fun `the verifier rejects a duplicate or unsorted dependency list`() {
        val duplicate = JsonParser.parseString(
            """{"patches":[{"name":"Hide CAPTCHA popups","dependencies":["BytecodePatch","BytecodePatch","Settings"]}]}"""
        ).asJsonObject
        val duplicateFailure = runCatching { BundleVerifier.requireCanonicalDependencies(duplicate) }
            .exceptionOrNull()
        assertTrue(
            "a catalog naming a dependency twice was accepted",
            duplicateFailure is IllegalArgumentException,
        )
        assertTrue(
            "the refusal did not name the patch or what was wrong: ${duplicateFailure?.message}",
            duplicateFailure!!.message!!.contains("Hide CAPTCHA popups") &&
                duplicateFailure.message!!.contains("twice"),
        )

        val unsorted = JsonParser.parseString(
            """{"patches":[{"name":"Comment tools","dependencies":["Settings","BytecodePatch"]}]}"""
        ).asJsonObject
        val unsortedFailure = runCatching { BundleVerifier.requireCanonicalDependencies(unsorted) }
            .exceptionOrNull()
        assertTrue(
            "a catalog listing dependencies out of order was accepted",
            unsortedFailure is IllegalArgumentException,
        )
        assertTrue(
            "the refusal did not say the order was wrong: ${unsortedFailure?.message}",
            unsortedFailure!!.message!!.contains("out of order"),
        )

        // And the shape the generator does write goes through, so the two refusals above are
        // about what they say rather than about the check refusing everything.
        val canonical = JsonParser.parseString(
            """{"patches":[{"name":"Comment tools","dependencies":["BytecodePatch","Settings"]},
               {"name":"No dependencies here"}]}"""
        ).asJsonObject
        BundleVerifier.requireCanonicalDependencies(canonical)
    }

    /**
     * A hand-edited catalog is what the verifier is for, so every shape one could be in comes
     * back as a sentence naming the row rather than as a cast failure from inside Gson.
     */
    @Test
    fun `a malformed catalog is refused by name rather than crashing`() {
        val cases = mapOf(
            "no patches array at all" to """{"version":"v0.32.0"}""",
            "a null dependency list" to """{"patches":[{"name":"A","dependencies":null}]}""",
            "a dependency list that is a string" to """{"patches":[{"name":"A","dependencies":"Settings"}]}""",
            "a dependency that is a number" to """{"patches":[{"name":"A","dependencies":[1]}]}""",
            "a dependency that is an object" to """{"patches":[{"name":"A","dependencies":[{}]}]}""",
            "a row that is not an object" to """{"patches":["Settings"]}""",
            "a row with a null name" to """{"patches":[{"name":null,"dependencies":["B","A"]}]}""",
        )
        for ((what, json) in cases) {
            val failure = runCatching {
                BundleVerifier.requireCanonicalDependencies(JsonParser.parseString(json).asJsonObject)
            }.exceptionOrNull()
            assertTrue(
                "$what was accepted or crashed: $failure",
                failure is IllegalArgumentException && !failure.message.isNullOrBlank(),
            )
        }
    }

    /**
     * Without this the two cases above would pass just as happily against a catalog whose rows
     * carry no dependencies at all, which is what a broken generator would produce.
     */
    @Test
    fun `the catalog still records dependencies to check`() {
        val patches = catalogPatches()
        assertTrue("the catalog has almost no patches in it: ${patches.size}", patches.size > 50)
        val withDependencies = patches.count {
            (it.getAsJsonArray("dependencies")?.size() ?: 0) > 0
        }
        assertTrue(
            "no row in the catalog records a dependency, so the cases above prove nothing",
            withDependencies > 20,
        )
    }
}
