/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.interaction.pip

import app.morphe.Fixtures
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.apksig.internal.apk.AndroidBinXmlParser
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import javax.xml.parsers.DocumentBuilderFactory

private const val EXTENSION = "Lapp/morphe/extension/tiktok/playback/PictureInPicture;"
private const val COMPONENT_ACTIVITY = "Landroidx/activity/ComponentActivity;"
private const val ANDROID_NAME = 0x01010003
private const val ANDROID_CONFIG_CHANGES = 0x0101001f

/** screenLayout, screenSize and smallestScreenSize: what shrinking into the window changes. */
private const val WINDOW_SIZE_CHANGES = 0x100 or 0x400 or 0x800

/**
 * Picture-in-picture hooks the two windows a feed video plays in: leaving (onUserLeaveHint) and
 * the window opening or closing (onPictureInPictureModeChanged). Both are inherited, so the patch
 * walks each window's superclass chain to the declaration Android runs, hooks it once however
 * many windows share it, and lets the detail pager open the window in the manifest.
 */
class PictureInPictureAnchorsTest {
    @Test
    fun `each declared build hands leaving and the window change over first`() {
        Fixtures.forEachDeclared { apk ->
            val classes = classesOf(apk)
            val hooks = pictureInPictureHooks { classes[it] }
            PICTURE_IN_PICTURE_WINDOWS.forEach { window ->
                val chain = generateSequence(window) { classes[it]?.superclass }.toList()
                assertTrue("${apk.name}: no leave hook above $window: $chain",
                    hooks.leaveHints.any { it.definingClass in chain })
                assertTrue("${apk.name}: no window change hook above $window: $chain",
                    hooks.modeChanges.any { it.definingClass in chain })
            }
            // Read off all three builds: the windows share TikTok's renamed base activity's
            // onUserLeaveHint, and nothing below androidx overrides the window change.
            assertEquals("${apk.name}: leave hooks ${hooks.leaveHints.map { it.definingClass }}", 1, hooks.leaveHints.size)
            assertEquals("${apk.name}", listOf(COMPONENT_ACTIVITY), hooks.modeChanges.map { it.definingClass })

            hooks.leaveHints.forEach { method ->
                val mutable = MutableMethod(method)
                val body = mutable.implementation!!.instructions.map { it.opcode }
                mutable.handLeaveHintToPictureInPicture()
                assertHandsOverFirst(mutable, body, "onUserLeaveHint", listOf("Landroid/app/Activity;"), parameterRegisters = 1)
            }
            hooks.modeChanges.forEach { method ->
                val mutable = MutableMethod(method)
                val body = mutable.implementation!!.instructions.map { it.opcode }
                mutable.handWindowChangeToPictureInPicture()
                assertHandsOverFirst(mutable, body, "onModeChanged", listOf("Landroid/app/Activity;", "Z"), parameterRegisters = 3)
            }
        }
    }

    @Test
    fun `each declared build declares both windows and lets them change size without a rebuild`() {
        Fixtures.forEachDeclared { apk ->
            val activities = activitiesOf(apk)
            PICTURE_IN_PICTURE_WINDOWS.map { it.removePrefix("L").removeSuffix(";").replace('/', '.') }.forEach { name ->
                val configChanges = activities[name]
                assertTrue("${apk.name}: $name isn't in the manifest", configChanges != null)
                assertEquals("${apk.name}: $name rebuilds when its window shrinks (configChanges ${Integer.toHexString(configChanges!!)})",
                    WINDOW_SIZE_CHANGES, configChanges and WINDOW_SIZE_CHANGES)
            }
        }
    }

    @Test
    fun `the walk takes the nearest instance declaration with code`() {
        val classes = mapOf(
            classOf("LX/Feed;", "LX/Static;"),
            classOf("LX/Static;", "LX/Abstract;", leaveHint(static = true)),
            classOf("LX/Abstract;", "LX/Base;", leaveHint(implemented = false)),
            classOf("LX/Base;", "Landroid/app/Activity;", leaveHint()),
        )
        assertEquals("LX/Base;", firstDeclaration("LX/Feed;", { classes[it] }, Method::isUserLeaveHint)?.definingClass)

        assertNull(firstDeclaration("LX/Feed;", { classes[it] }, Method::isPictureInPictureModeChange))
        val looped = mapOf(classOf("LX/A;", "LX/B;"), classOf("LX/B;", "LX/A;"))
        assertNull(firstDeclaration("LX/A;", { looped[it] }, Method::isUserLeaveHint))
    }

    @Test
    fun `a declaration both windows share is hooked once, and a window without one stops the patch`() {
        val (feed, detail) = PICTURE_IN_PICTURE_WINDOWS
        val shared = mapOf(
            classOf(feed, "LX/Middle;"),
            classOf("LX/Middle;", "LX/Base;"),
            classOf(detail, "LX/Base;"),
            classOf("LX/Base;", COMPONENT_ACTIVITY, leaveHint()),
            classOf(COMPONENT_ACTIVITY, "Landroid/app/Activity;", windowChange()),
        )
        val hooks = pictureInPictureHooks { shared[it] }
        assertEquals(listOf("LX/Base;"), hooks.leaveHints.map { it.definingClass })
        assertEquals(listOf(COMPONENT_ACTIVITY), hooks.modeChanges.map { it.definingClass })

        val detailOnItsOwn = shared + classOf(detail, "Landroid/app/Activity;")
        assertThrows(PatchException::class.java) { pictureInPictureHooks { detailOnItsOwn[it] } }
    }

    @Test
    fun `the manifest lets both windows open picture-in-picture and leaves the rest alone`() {
        val xml = manifest(
            """
            <activity android:name="com.ss.android.ugc.aweme.main.MainActivity" android:supportsPictureInPicture="true"/>
            <activity android:name=".detail.ui.DetailActivity" android:configChanges="0xff0"/>
            <activity android:name="com.ss.android.ugc.aweme.live.LivePlayActivity"/>
            """,
            packageName = "com.ss.android.ugc.aweme",
        )
        allowPictureInPicture(xml)
        val activities = (0 until xml.getElementsByTagName("activity").length).map {
            xml.getElementsByTagName("activity").item(it) as Element
        }
        assertEquals(listOf("true", "true", ""), activities.map { it.getAttribute("android:supportsPictureInPicture") })
        assertEquals("0xff0", activities[1].getAttribute("android:configChanges"))
    }

    @Test
    fun `a manifest without the detail pager stops the patch`() {
        val xml = manifest("""<activity android:name="com.ss.android.ugc.aweme.main.MainActivity"/>""")
        assertThrows(PatchException::class.java) { allowPictureInPicture(xml) }
    }

    /** The call is a prefix: TikTok's own handler, super call and listeners included, runs after it. */
    private fun assertHandsOverFirst(
        method: MutableMethod,
        originalBody: List<Opcode>,
        name: String,
        parameters: List<String>,
        parameterRegisters: Int,
    ) {
        val instructions = method.implementation!!.instructions.toList()
        assertEquals("the hook changed TikTok's own handler", originalBody, instructions.drop(1).map { it.opcode })
        val first = instructions.first()
        assertEquals(Opcode.INVOKE_STATIC_RANGE, first.opcode)
        val range = first as RegisterRangeInstruction
        assertEquals("the call takes the wrong registers", parameters.size, range.registerCount)
        assertEquals("the call does not start at p0",
            method.implementation!!.registerCount - parameterRegisters, range.startRegister)
        val target = first.getReference<MethodReference>()!!
        assertEquals(EXTENSION, target.definingClass)
        assertEquals(name, target.name)
        assertEquals(parameters, target.parameterTypes.map(CharSequence::toString))
    }

    private fun classesOf(apk: File): Map<String, ClassDef> {
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        val classes = HashMap<String, ClassDef>()
        container.dexEntryNames.forEach { entry ->
            container.getEntry(entry)!!.dexFile.classes.forEach { classes.putIfAbsent(it.type, it) }
        }
        return classes
    }

    /** Each declared activity's name and configChanges flags. */
    private fun activitiesOf(apk: File): Map<String, Int> {
        val bytes = ZipFile(apk).use { zip ->
            zip.getInputStream(zip.getEntry("AndroidManifest.xml")).use { it.readBytes() }
        }
        val parser = AndroidBinXmlParser(ByteBuffer.wrap(bytes))
        val found = mutableMapOf<String, Int>()
        while (parser.next() != AndroidBinXmlParser.EVENT_END_DOCUMENT) {
            if (parser.eventType != AndroidBinXmlParser.EVENT_START_ELEMENT || parser.name != "activity") continue
            var name: String? = null
            var configChanges = 0
            for (i in 0 until parser.attributeCount) {
                when (parser.getAttributeNameResourceId(i)) {
                    ANDROID_NAME -> name = parser.getAttributeStringValue(i)
                    ANDROID_CONFIG_CHANGES -> configChanges = parser.getAttributeIntValue(i)
                }
            }
            if (name != null) found[name] = configChanges
        }
        return found
    }

    private fun manifest(entries: String, packageName: String = "com.zhiliaoapp.musically"): Document {
        val xml = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android" package="$packageName">
                <application>
                    $entries
                </application>
            </manifest>
        """.trimIndent()
        // Not namespace aware, the way the patcher's document reads a decoded manifest.
        return DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.byteInputStream())
    }

    private fun classOf(type: String, superclass: String, vararg methods: (String) -> Method): Pair<String, ClassDef> =
        type to ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, superclass, null, null, null, emptyList(),
            methods.map { it(type) },
        )

    private fun leaveHint(static: Boolean = false, implemented: Boolean = true): (String) -> Method =
        method("onUserLeaveHint", emptyList(), static, implemented)

    private fun windowChange(): (String) -> Method =
        method("onPictureInPictureModeChanged", listOf("Z", "Landroid/content/res/Configuration;"), static = false, implemented = true)

    private fun method(name: String, parameters: List<String>, static: Boolean, implemented: Boolean): (String) -> Method = { owner ->
        ImmutableMethod(
            owner, name,
            parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
            AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0) or
                (if (implemented) 0 else AccessFlags.ABSTRACT.value),
            null, null,
            if (implemented) {
                ImmutableMethodImplementation(parameters.size + 1, listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)), null, null)
            } else {
                null
            },
        )
    }
}
