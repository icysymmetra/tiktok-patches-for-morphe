package app.morphe.patches.shared.misc.extension

import java.io.File
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.net.URLConnection
import java.net.URLStreamHandler
import java.nio.file.Files
import java.nio.file.Paths
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class LocalBundleFileTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `the actual manifest reader preserves local class and resource jar paths`() {
        listOf("bundle.jar", "bundle+fixture.jar", "bundle fixture.jar", "bundle%+caf\u00e9.jar").forEach { name ->
            val fixture = bundle(name)
            assertEquals(fixture.file, localBundleFile(fixture.classUrl))
            assertEquals(fixture.file, localBundleFile(fixture.resourceUrl))
            assertEquals("0.66.0", fixture.manifestEntry("Version"))
            assertEquals("Unknown", fixture.manifestEntry("Missing"))
            JarFile(localBundleFile(fixture.resourceUrl)).use { jar ->
                assertEquals("resource sentinel", jar.getInputStream(jar.getJarEntry("payload.txt"))
                    .use { it.reader().readText() })
            }
            assertTrue("The manifest reader retained the JAR: $name", fixture.file.delete())
        }
    }

    @Test fun `an opened jar closes when the actual manifest read fails`() {
        val fixture = bundle("broken+manifest.jar")
        writeBundle(fixture.file, fixture.classes, "invalid manifest header\n\n")
        assertThrows(IOException::class.java) { fixture.manifestEntry("Version") }
        assertTrue("The failed manifest read retained the JAR", fixture.file.delete())
    }

    @Test fun `nonlocal and unpacked locations fail without opening a connection`() {
        var connections = 0
        val handler = object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection {
                connections++
                throw AssertionError("The local bundle resolver opened a URL connection")
            }
        }
        listOf(null, URL(null, "https://example.invalid/bundle.jar", handler),
            URL(null, "jar:https://example.invalid/bundle.jar!/owner.class", handler),
            URL(null, "jar:ftp://example.invalid/bundle.jar!/owner.class", handler),
            URL(null, "file:/unpacked/owner.class", handler)).forEach { location ->
            val failure = assertThrows(IllegalStateException::class.java) { localBundleFile(location) }
            assertTrue(failure.message.orEmpty().contains("local JAR"))
        }
        assertEquals(0, connections)
    }

    @Test fun `windows unc authorities map without inspecting or opening the share`() {
        val handler = object : URLStreamHandler() {
            override fun openConnection(url: URL): URLConnection =
                throw AssertionError("The UNC mapper opened a URL connection")
        }
        val resource = URL(null, "jar:file://server/share/bundle%2B%20%25caf%C3%A9.jar!/owner.class", handler)
        if (File.separatorChar == '\\') {
            assertEquals(File("\\\\server\\share\\bundle+ %caf\u00e9.jar"), localBundleFile(resource))
        } else {
            assertThrows(IllegalArgumentException::class.java) { localBundleFile(resource) }
        }
    }

    private fun bundle(name: String): Bundle {
        val owner = Class.forName(OWNER)
        val source = owner.getResource("SharedExtensionPatchKt.class")!!
        val directory = Paths.get(source.toURI()).parent
        val classes = Files.list(directory).use { files ->
            files.filter { it.fileName.toString().startsWith("SharedExtensionPatchKt") &&
                it.fileName.toString().endsWith(".class") }.toList()
                .associate { RESOURCE.substringBeforeLast('/') + "/" + it.fileName to Files.readAllBytes(it) }
        }
        val file = temporary.newFile(name)
        writeBundle(file, classes, "Manifest-Version: 1.0\r\nVersion: 0.66.0\r\n\r\n")
        val loader = BundleLoader(file.toURI().toURL())
        // Load every owner class and retain its actual resource URL, then close the loader's
        // own archive handle so deletion below independently checks the production reader.
        val reader = loader.use {
            classes.keys.forEach { resource ->
                it.loadClass(resource.removeSuffix(".class").replace('/', '.'))
                it.getResource(resource)
            }
            val loadedOwner = it.loadClass(OWNER)
            val classUrl = it.getResource("$RESOURCE.class")!!
            val resourceUrl = it.getResource("payload.txt")!!
            val method = loadedOwner.declaredMethods.single { candidate ->
                candidate.name.endsWith("\$getPatchesManifestEntry")
            }.apply { isAccessible = true }
            Bundle(file, classes, classUrl, resourceUrl) { key ->
                try { method.invoke(null, key) as String }
                catch (failure: InvocationTargetException) { throw failure.targetException }
            }
        }
        return reader
    }

    private fun writeBundle(file: File, classes: Map<String, ByteArray>, manifest: String) {
        JarOutputStream(file.outputStream()).use { output ->
            (mapOf("META-INF/MANIFEST.MF" to manifest.toByteArray(), "payload.txt" to
                "resource sentinel".toByteArray()) + classes).forEach { (name, bytes) ->
                output.putNextEntry(JarEntry(name)); output.write(bytes); output.closeEntry()
            }
        }
    }

    private class BundleLoader(url: URL) : URLClassLoader(arrayOf(url), LocalBundleFileTest::class.java.classLoader) {
        private val resources = mutableMapOf<String, URL>()
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            if (name != OWNER && !name.startsWith("$OWNER\$")) return super.loadClass(name, resolve)
            return synchronized(getClassLoadingLock(name)) {
                (findLoadedClass(name) ?: findClass(name)).also { if (resolve) resolveClass(it) }
            }
        }
        override fun getResource(name: String): URL? {
            if (!name.startsWith(RESOURCE)) return super.getResource(name)
            return resources[name] ?: findResource(name)?.also { resources[name] = it }
        }
    }

    private data class Bundle(val file: File, val classes: Map<String, ByteArray>, val classUrl: URL,
        val resourceUrl: URL, val manifestEntry: (String) -> String)

    private companion object {
        const val OWNER = "app.morphe.patches.shared.misc.extension.SharedExtensionPatchKt"
        const val RESOURCE = "app/morphe/patches/shared/misc/extension/SharedExtensionPatchKt"
    }
}
