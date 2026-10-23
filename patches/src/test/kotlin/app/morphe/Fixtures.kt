package app.morphe

import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.DexFileFactory
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.dexbacked.DexBackedDexFile
import com.android.tools.smali.dexlib2.iface.MultiDexContainer
import java.io.File
import java.io.OutputStream
import java.lang.ref.SoftReference
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue

/**
 * The vendor TikTok builds the fixture tests read, from the folder `HUSHFEED_FIXTURE_DIR` names.
 *
 * Unset, every fixture test skips and says which variable to set: the APKs are hundreds of
 * megabytes each and are not in the repository. Set, the folder has to hold what the test reads.
 * A variable pointing at a missing or empty folder is a broken setup, and skipping there would
 * read as a pass. There is no default folder: one used to be a path on the maintainer's machine,
 * which skipped quietly everywhere else and published that machine's layout.
 */
internal object Fixtures {
    const val VARIABLE = "HUSHFEED_FIXTURE_DIR"

    private data class CacheKey(val file: File, val api: Int?, val artVersion: Int?)
    private data class CacheEntry(
        val digest: ByteArray,
        val container: SoftReference<MultiDexContainer<out DexBackedDexFile>>,
    )
    private val cache = ConcurrentHashMap<CacheKey, CacheEntry>()
    private val decodes = AtomicInteger()

    /** The read-only DEX container used by fixture assertions, never a mutable Patcher context. */
    fun dexContainer(apk: File, opcodes: Opcodes? = Opcodes.getDefault()): MultiDexContainer<out DexBackedDexFile> {
        val file = apk.canonicalFile
        val digest = MessageDigest.getInstance("SHA-256")
        DigestInputStream(file.inputStream(), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
        val bytes = digest.digest()
        var result: MultiDexContainer<out DexBackedDexFile>? = null
        cache.compute(CacheKey(file, opcodes?.api, opcodes?.artVersion)) { _, previous ->
            val retained = previous?.container?.get()
            if (retained != null && previous.digest.contentEquals(bytes)) {
                result = retained
                previous
            } else {
                // One line per decode, so a run's report shows how often the cache held.
                System.err.println("[fixtures] decoding ${file.name} (decode ${decodes.incrementAndGet()} in this JVM)")
                val decoded = DexFileFactory.loadDexContainer(file, opcodes)
                // The pinned ZIP reader is lazy. Read every entry while this content is current.
                decoded.dexEntryNames.forEach { checkNotNull(decoded.getEntry(it)) }
                DigestInputStream(file.inputStream(), digest).use { it.transferTo(OutputStream.nullOutputStream()) }
                check(bytes.contentEquals(digest.digest())) { "Fixture changed while decoding: $file" }
                result = decoded
                CacheEntry(bytes, SoftReference(decoded))
            }
        }
        return checkNotNull(result)
    }

    /** The files in the fixture folder that [accept] takes, sorted by name. */
    fun files(accept: (File) -> Boolean): List<File> {
        val configured = System.getenv(VARIABLE)
        assumeTrue(
            "$VARIABLE is not set, so the TikTok fixture tests skip. Point it at the folder " +
                "that holds the vendor TikTok APKs to run them.",
            !configured.isNullOrBlank(),
        )
        val directory = File(configured!!)
        if (!directory.isDirectory) fail("$VARIABLE names $directory, which is not a folder.")
        val found = directory.listFiles()?.filter { it.isFile && accept(it) }?.sortedBy { it.name }.orEmpty()
        if (found.isEmpty()) fail("$VARIABLE names $directory, which holds none of the TikTok files this test reads.")
        return found
    }

    /** Every TikTok APK in the fixture folder. */
    fun apks(): List<File> = files { it.extension == "apk" }

    /** The builds the bundle declares, oldest first. */
    fun declaredVersions(): List<String> = AppCompatibilities.tiktok().single().targets
        .map { checkNotNull(it.version) { "a declared target without a version" } }
        .sortedWith { a, b -> compareVersions(parts(a), parts(b)) }

    /** The universal APK of [version] in the fixture folder, by the APKMirror or the short file name. */
    fun apkOf(version: String): File {
        val found = apks().filter { it.name.contains("_$version-") || it.name == "tiktok-$version.apk" }
        if (found.size != 1) fail("The fixture folder holds ${found.size} universal APKs of TikTok $version, not one: $found")
        return found.single()
    }

    /** One universal APK of every declared build, oldest first. A declared build without one fails. */
    fun declared(): List<File> = declaredVersions().map(::apkOf)

    /**
     * Runs [check] on each declared build's APK and names the build in a failure, since the
     * same assertion message reads alike on every build. Not inline, so a `return` in [check]
     * can't end the test before the later builds were looked at.
     */
    fun forEachDeclared(check: (File) -> Unit) {
        for (apk in declared()) {
            try {
                check(apk)
            } catch (failure: AssertionError) {
                throw AssertionError("${apk.name}: ${failure.message}", failure)
            }
        }
    }

    /** The build an APK in the fixture folder carries, read off its file name. */
    fun versionOf(apk: File): String = Regex("""\d+\.\d+\.\d+""").find(apk.name)?.value
        ?: error("no version in ${apk.name}")

    private fun parts(version: String) = version.split('.').map(String::toInt)

    private fun compareVersions(a: List<Int>, b: List<Int>): Int {
        for (i in 0 until maxOf(a.size, b.size)) {
            val difference = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (difference != 0) return difference
        }
        return 0
    }
}
