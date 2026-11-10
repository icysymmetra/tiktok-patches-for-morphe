/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.patches.tiktok.misc.inbox

import app.morphe.Fixtures
import app.morphe.takes
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.ClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private const val SERVICE_MANAGER = "Lcom/ss/android/ugc/aweme/framework/services/ServiceManager;"
private const val IM_SERVICE = "Lcom/ss/android/ugc/aweme/im/service/IIMService;"
private const val IM_START_TASK = "Lcom/ss/android/ugc/aweme/im/service/provider/IMService\$IdleTask;"
private const val IM_SERVICE_IMPL = "Lcom/ss/android/ugc/aweme/im/service/provider/IMService;"
private const val IM_PROXY = "Lcom/ss/android/ugc/aweme/im/IMProxyImpl;"

/**
 * Keep a streak going sends through TikTok's notification quick reply and starts TikTok's
 * messaging by the names TikTok kept. The patch copies the quick reply's own sender lookup into
 * the extension, and the extension reaches the rest by reflection, where a rename would only
 * show on a phone as a message that never went. Both are held to each declared build here.
 */
class AutoStreakAnchorsTest {
    @Test
    fun `the quick reply's send and its sender lookup resolve on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val receiver = wanted(apk)[QUICK_REPLY_RECEIVER] ?: error("$version: no quick reply receiver")
            val sends = receiver.methods.filter { QuickReplySendFingerprint.takes(it, receiver) }
            assertEquals("$version: quick reply sends matched", 1, sends.size)

            val lookup = readyLookupIn(sends.single()).getOrElse {
                throw AssertionError("$version: ${it.message}")
            }
            assertEquals(IM_CORE_PROXY, lookup.core.returnType)
            assertEquals("TIKTOK_SOCIAL_IM", lookup.business.name)
            assertEquals(lookup.chat.returnType, lookup.sender.definingClass)

            // The extension builds one and calls it the way Android would.
            assertTrue("$version: no public no-argument constructor", receiver.methods.any {
                it.name == "<init>" && it.parameterTypes.isEmpty() && AccessFlags.PUBLIC.isSet(it.accessFlags)
            })
            assertTrue("$version: no onReceive", receiver.methods.any {
                it.name == "onReceive" &&
                    it.parameterTypes == listOf("Landroid/content/Context;", "Landroid/content/Intent;")
            })
        }
    }

    @Test
    fun `the classes that start messaging keep their names on each build`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val classes = wanted(apk)

            val manager = classes[SERVICE_MANAGER] ?: error("$version: no ServiceManager")
            assertTrue("$version: no ServiceManager.get()", manager.methods.any {
                it.name == "get" && it.parameterTypes.isEmpty() && AccessFlags.STATIC.isSet(it.accessFlags)
            })
            assertTrue("$version: no ServiceManager.getService(Class)", manager.methods.any {
                it.name == "getService" && it.parameterTypes == listOf("Ljava/lang/Class;")
            })
            val service = classes[IM_SERVICE] ?: error("$version: no IIMService")
            assertTrue("$version: IIMService is no longer an interface", AccessFlags.INTERFACE.isSet(service.accessFlags))

            val task = classes[IM_START_TASK] ?: error("$version: no IMService\$IdleTask")
            assertTrue("$version: the start task takes no delay", task.methods.any {
                it.name == "<init>" && it.parameterTypes == listOf("J") && AccessFlags.PUBLIC.isSet(it.accessFlags)
            })
            assertTrue("$version: the start task has no run(Context)", task.methods.any {
                it.name == "run" && it.parameterTypes == listOf("Landroid/content/Context;") &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags)
            })

            // A process an alarm started catches the host proxy IMProxyImpl hands the service and
            // passes it to initIM itself.
            val handOver = classes[IM_PROXY] ?: error("$version: no IMProxyImpl")
            assertTrue("$version: IMProxyImpl has no public no-argument constructor", handOver.methods.any {
                it.name == "<init>" && it.parameterTypes.isEmpty() && AccessFlags.PUBLIC.isSet(it.accessFlags)
            })
            assertEquals("$version: IMProxyImpl hand-overs", 1, handOver.methods.count {
                it.parameterTypes == listOf(IM_SERVICE) && !AccessFlags.STATIC.isSet(it.accessFlags)
            })
            val impl = classes[IM_SERVICE_IMPL] ?: error("$version: no IMService")
            val initialize = impl.methods.singleOrNull { it.name == "initialize" && it.parameterTypes.size == 1 }
                ?: error("$version: no IMService.initialize")
            assertTrue("$version: initIM doesn't take what initialize is handed", impl.methods.any {
                it.name == "initIM" && it.parameterTypes == initialize.parameterTypes &&
                    AccessFlags.PUBLIC.isSet(it.accessFlags)
            })
        }
    }

    private fun wanted(apk: java.io.File): Map<String, ClassDef> {
        val names = setOf(QUICK_REPLY_RECEIVER, SERVICE_MANAGER, IM_SERVICE, IM_START_TASK, IM_SERVICE_IMPL, IM_PROXY)
        val found = HashMap<String, ClassDef>()
        val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
        for (entry in container.dexEntryNames) {
            for (classDef in container.getEntry(entry)!!.dexFile.classes) {
                if (classDef.type in names) found.putIfAbsent(classDef.type, classDef)
            }
            if (found.size == names.size) break
        }
        return found
    }
}
