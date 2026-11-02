package app.morphe.patches.tiktok.interaction.sharesheet

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * What adding apps to the Share via row rests on, held to each declared TikTok build.
 *
 * The extension builds TikTok's own server-named channel from a ShareChannelInfo it makes by
 * reflection, so this pins the patch's lookup of that channel, its package getter and its icon map,
 * and the model's public constructors and field names the extension uses. It also pins the two
 * strings the extension relies on: the More channel's key, and the suffix TikTok adds to a key to
 * look up its square icon. And it pins TikTok's two share mode lookups, which drop a channel its
 * server doesn't list unless the patch answers for the added ones first.
 */
class ShareTargetsAnchorsTest {
    @Test
    fun `the generic share channel and its members resolve on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = classesOf(apk)
            val byType = classes.associateBy { it.type }
            val members = shareTargetMembers(classes.filter { it.isGenericShareChannel() }) { byType[it] }
            assertEquals("$version: package getter", "Ljava/lang/String;", members.packageGetter.returnType)
            assertEquals("$version: icon map holder", members.iconValue.definingClass, members.iconField.type)

            val icon = members.channel.methods.single { it.returnType == "Landroid/graphics/drawable/Drawable;" }
            assertTrue("$version: the icon method no longer adds $SQUARE_SUFFIX", SQUARE_SUFFIX in stringsOf(icon))
        }
    }

    @Test
    fun `the share models keep the constructors and fields the extension uses on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val byType = classesOf(apk).associateBy { it.type }
            val info = byType[SHARE_CHANNEL_INFO]
            assertNotNull("$version: ShareChannelInfo is gone", info)
            val target = byType[TARGET_COMPONENT_INFO]
            assertNotNull("$version: TargetComponentInfo is gone", target)

            val infoInit = info!!.methods.singleOrNull { method ->
                method.name == "<init>" &&
                    method.parameterTypes.map(CharSequence::toString) == List(5) { "Ljava/lang/String;" } + TARGET_COMPONENT_INFO
            }
            assertNotNull("$version: ShareChannelInfo(key, package, label, icon, square icon, target) is gone", infoInit)
            assertTrue("$version: that constructor isn't public", AccessFlags.PUBLIC.isSet(infoInit!!.accessFlags))
            assertTrue("$version: ShareChannelInfo isn't public", AccessFlags.PUBLIC.isSet(info.accessFlags))
            assertEquals(
                "$version: the constructor's field order",
                listOf("channelKey", "packageName", "labelName", "iconRes", "squareIconRes", "targetComponentInfo"),
                infoInit.implementation!!.instructions.mapNotNull { instruction ->
                    ((instruction as? ReferenceInstruction)?.reference as?
                        com.android.tools.smali.dexlib2.iface.reference.FieldReference)?.name
                },
            )

            val targetInit = target!!.methods.singleOrNull { it.name == "<init>" && it.parameterTypes.isEmpty() }
            assertNotNull("$version: TargetComponentInfo() is gone", targetInit)
            assertTrue("$version: TargetComponentInfo() isn't public", AccessFlags.PUBLIC.isSet(targetInit!!.accessFlags))
        }
    }

    @Test
    fun `each build has one share mode lookup for videos and one for photo posts`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val methods = classesOf(apk).flatMap { it.methods }
            for ((field, parameters) in listOf(
                VIDEO_SHARE_MODE to listOf("Ljava/lang/String;", "Ljava/util/List;"),
                PHOTO_SHARE_MODE to listOf("Ljava/lang/String;"),
            )) {
                val found = methods.filter {
                    it.parameterTypes.map(CharSequence::toString) == parameters && it.isShareModeLookup(field)
                }
                assertEquals("$version: $field lookups", 1, found.size)
                val locals = found.single().implementation!!.registerCount - parameters.size
                assertTrue("$version: the $field lookup has no local for the added check", locals >= 1)
            }
            val anyArity = methods.count { it.isShareModeLookup(VIDEO_SHARE_MODE) || it.isShareModeLookup(PHOTO_SHARE_MODE) }
            assertEquals("$version: a share mode lookup with another signature the patch would miss", 2, anyArity)
        }
    }

    @Test
    fun `the share snapshot stores its row once after the server's check on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val snapshots = classesOf(apk).flatMap { it.methods }.filter { method ->
                method.name == "<init>" && method.parameterTypes.size == 1 &&
                    stringsOf(method).containsAll(listOf("click_to_respond_duration", "config_duration"))
            }
            assertEquals("$version: share snapshot constructors", 1, snapshots.size)
            val snapshot = snapshots.single()
            val store = snapshot.finishedChannelRowStore()
            assertTrue("$version: no store of the channel row after the server's check", store >= 0)
            val field = ((snapshot.implementation!!.instructions.toList()[store] as ReferenceInstruction).reference
                as com.android.tools.smali.dexlib2.iface.reference.FieldReference)
            val reads = snapshot.implementation!!.instructions.count { instruction ->
                val reference = (instruction as? ReferenceInstruction)?.reference
                instruction.opcode == com.android.tools.smali.dexlib2.Opcode.IGET_OBJECT &&
                    reference is com.android.tools.smali.dexlib2.iface.reference.FieldReference &&
                    reference.definingClass == field.definingClass && reference.name == field.name
            }
            assertTrue("$version: the stored row ${field.name} is never read back as the channel row", reads > 0)
        }
    }

    @Test
    fun `a channel keyed more exists on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val keyedMore = classesOf(apk).any { classDef ->
                classDef.methods.any { method ->
                    method.name == "key" && method.parameterTypes.isEmpty() &&
                        stringsOf(method) == listOf(MORE_KEY)
                }
            }
            assertTrue("$version: no share channel answers the key \"$MORE_KEY\"", keyedMore)
        }
    }

    private companion object {
        const val TARGET_COMPONENT_INFO = "Lcom/ss/android/ugc/aweme/share/base/model/TargetComponentInfo;"
        const val SQUARE_SUFFIX = "__square_icon"
        const val MORE_KEY = "more"

        fun classesOf(apk: File): List<ClassDef> {
            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            return container.dexEntryNames.flatMap { entry -> container.getEntry(entry)!!.dexFile.classes }
        }

        fun stringsOf(method: com.android.tools.smali.dexlib2.iface.Method): List<String> =
            method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? StringReference)?.string
            }
    }
}
