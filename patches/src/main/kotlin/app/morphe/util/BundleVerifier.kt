/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 *
 * Built on icysymmetra/tiktok-patches-for-morphe (GPL-3.0).
 */
package app.morphe.util

import app.morphe.patcher.patch.loadPatchesFromJar
import app.morphe.patches.shared.compat.AppCompatibilities
import com.google.gson.JsonParser
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.jar.JarFile
import java.security.MessageDigest

/** Verifies the on-disk deliverable without running any task that can repair it. */
object BundleVerifier {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 4) { "Expected bundle, patch list, version and build checksum" }
        val bundle = File(args[0])
        require(bundle.isFile) { "Bundle not found: $bundle" }
        JarFile(bundle).use { jar ->
            for (name in listOf("classes.dex", "extensions/tiktok.mpe", "extensions/shared.mpe")) {
                val entry = requireNotNull(jar.getJarEntry(name)) { "Bundle is missing $name" }
                require(entry.size > 0) { "Bundle entry is empty: $name" }
                jar.getInputStream(entry).use { stream ->
                    val header = stream.readNBytes(100)
                    require(header.size == 100
                            && header.copyOfRange(0, 4).contentEquals(byteArrayOf(100, 101, 120, 10))) {
                        "Bundle entry is not a DEX file: $name"
                    }
                    val classDefinitions = ByteBuffer.wrap(header, 96, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .int
                    require(classDefinitions > 0) { "Bundle DEX has no classes: $name" }
                }
            }
            require(jar.manifest.mainAttributes.getValue("Version") == args[2]) {
                "Bundle version does not match ${args[2]}"
            }
            requireTargetDescription(jar.manifest.mainAttributes.getValue("Description"))
        }
        val expectedDigest = File(args[3]).readText().trim()
        val actualDigest = MessageDigest.getInstance("SHA-256").digest(bundle.readBytes())
            .joinToString("") { "%02x".format(it) }
        require(actualDigest == expectedDigest) {
            "Bundle differs from the Android build output; a DEX or JVM payload may be stale"
        }
        val metadata = File(args[1]).reader().use { JsonParser.parseReader(it).asJsonObject }
        require(metadata["version"].asString == "v${args[2]}") { "Patch list version is stale" }
        val listed = metadata.getAsJsonArray("patches").map { it.asJsonObject["name"].asString }
        val bundled = loadPatchesFromJar(setOf(bundle)).map { it.name }
        require(listed.isNotEmpty() && listed.size == listed.toSet().size) {
            "Patch list is empty or contains duplicate names"
        }
        requireCanonicalDependencies(metadata)
        require(bundled.size == listed.size && bundled.toSet() == listed.toSet()) {
            "Patch list mismatch: bundle has ${bundled.size}, metadata has ${listed.size}; " +
                "missing=${listed.toSet() - bundled.toSet()}, extra=${bundled.toSet() - listed.toSet()}"
        }
        println("Verified ${bundle.name}: ${bundled.size} patches and all three non-empty DEX payloads")
    }

    /** The delivered description must name every declared host as an exact version. */
    fun requireTargetDescription(description: String?) {
        val targets = AppCompatibilities.tiktok().flatMap { it.targets }
            .mapNotNull { it.version }.distinct()
        val missing = targets.filter { version ->
            !Regex("(?<![\\d.])${Regex.escape(version)}(?![\\d.])")
                .containsMatchIn(description.orEmpty())
        }
        require(targets.isNotEmpty() && missing.isEmpty()) {
            "Bundle description omits declared TikTok versions: $missing"
        }
    }

    /**
     * Every row's dependencies named once and in order.
     *
     * A dependency list is a set written down as a list, so a name appearing twice is a catalog
     * that has lost track of what it is recording: two rows said `["Settings", "BytecodePatch",
     * "BytecodePatch"]`, which reads as two dependencies and is one. Order matters for a
     * different reason, that an unsorted list makes an edit to one patch reorder rows it has
     * nothing to do with.
     *
     * The generator deduplicates and sorts. This is what stops a hand-edited or stale catalog
     * getting past on a day the generator did not run, and it is separate from main so it can be
     * put in front of a crafted catalog without building a bundle to go with it.
     */
    fun requireCanonicalDependencies(metadata: com.google.gson.JsonObject) {
        val patches = metadata["patches"]
        require(patches != null && patches.isJsonArray) { "Patch list has no patches array" }
        patches.asJsonArray.forEachIndexed { index, entry ->
            require(entry.isJsonObject) { "Patch list row $index is not an object: $entry" }
            val patch = entry.asJsonObject
            // A hand-edited catalog is what this exists for, so every shape it could be in has
            // to come back as a sentence rather than as a cast failure from inside Gson.
            val name = patch["name"]?.takeIf { it.isJsonPrimitive }?.asString ?: "(unnamed)"
            val declared = patch["dependencies"] ?: return@forEachIndexed
            require(declared.isJsonArray) {
                "Patch \"$name\" declares dependencies that are not a list: $declared"
            }
            val dependencies = declared.asJsonArray.map { dependency ->
                require(dependency.isJsonPrimitive && dependency.asJsonPrimitive.isString) {
                    "Patch \"$name\" names a dependency that is not a string: $dependency"
                }
                dependency.asString
            }
            require(dependencies.size == dependencies.toSet().size) {
                "Patch \"$name\" names a dependency twice: $dependencies"
            }
            require(dependencies == dependencies.sorted()) {
                "Patch \"$name\" lists its dependencies out of order: $dependencies"
            }
        }
    }
}
