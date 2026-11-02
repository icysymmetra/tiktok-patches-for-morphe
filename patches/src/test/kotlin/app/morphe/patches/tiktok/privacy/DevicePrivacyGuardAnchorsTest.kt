package app.morphe.patches.tiktok.privacy

import app.morphe.Fixtures
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.Opcodes
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What "Device privacy guard" hides from TikTok, held to each declared build.
 *
 * The VPN and advertising-id switches rest on three framework reads: the modern VPN check
 * `NetworkCapabilities.hasTransport`, the advertising id read `AdvertisingIdClient$Info.getId`,
 * and the static interface walk `NetworkInterface.getNetworkInterfaces`. The patch swaps every
 * call site of each for its own, so this pins that each build still makes the call and in the
 * invoke form the replacement is emitted in. A build that stops making one fails here, loudly,
 * rather than shipping a switch that does nothing.
 */
class DevicePrivacyGuardAnchorsTest {
    private val networkCapabilities = "Landroid/net/NetworkCapabilities;"
    private val networkInterface = "Ljava/net/NetworkInterface;"
    private val adIdInfo = "Lcom/google/android/gms/ads/identifier/AdvertisingIdClient\$Info;"

    private val virtual = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)
    private val static = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)

    @Test
    fun `each declared build makes the VPN and advertising-id reads the guard answers`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var hasTransport = 0
            var getId = 0
            var getInterfaces = 0
            val wrongForm = mutableListOf<String>()

            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                    // The real app's own code, not an extension payload (fixtures carry none).
                    if (classDef.type.startsWith("Lapp/morphe/extension/")) return@forEach
                    classDef.methods.forEach { method ->
                        method.implementation?.instructions?.forEach { instruction ->
                            val ref = reference(instruction) ?: return@forEach
                            when {
                                ref.definingClass == networkCapabilities && ref.name == "hasTransport" &&
                                    ref.returnType == "Z" -> {
                                    hasTransport++
                                    if (instruction.opcode !in virtual) wrongForm += "hasTransport ${instruction.opcode}"
                                }
                                ref.definingClass == adIdInfo && ref.name == "getId" &&
                                    ref.returnType == "Ljava/lang/String;" -> {
                                    getId++
                                    if (instruction.opcode !in virtual) wrongForm += "getId ${instruction.opcode}"
                                }
                                ref.definingClass == networkInterface && ref.name == "getNetworkInterfaces" -> {
                                    getInterfaces++
                                    if (instruction.opcode !in static) wrongForm += "getNetworkInterfaces ${instruction.opcode}"
                                }
                            }
                        }
                    }
                }
            }

            assertTrue("$version: no NetworkCapabilities.hasTransport call site", hasTransport > 0)
            assertTrue("$version: no AdvertisingIdClient\$Info.getId call site", getId > 0)
            assertTrue("$version: no NetworkInterface.getNetworkInterfaces call site", getInterfaces > 0)
            assertTrue("$version: a read is in an invoke form the replacement can't match: $wrongForm",
                wrongForm.isEmpty())
        }
    }

    /**
     * The advertising id reads that never call Info.getId, found by the same shape functions the
     * patch hooks with. On every declared build two SDKs (AppsFlyer and one R8 renamed) each ask
     * Google's service for the id (code 1, then readString) and the limit flag (code 2, then
     * readInt) in a method of their own, and Info's fields are read where the patch answers them:
     * the limit flag 14 times (13 inlined isLimitAdTrackingEnabled reads and toString) and the id
     * twice (getId's body and toString). A build that adds a reader the shape misses, or moves a
     * read out of the shape, fails here rather than handing TikTok the real id or flag.
     */
    @Test
    fun `each declared build reads the advertising id and its limit flag where the guard answers them`() {
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            val serviceReads = mutableListOf<AdIdReadSite>()
            val fieldReads = mutableListOf<AdIdReadSite>()
            val readers = mutableListOf<String>()
            val tokenAndTransact = mutableListOf<String>()
            val problems = mutableListOf<String>()
            var infoShape: Boolean? = null

            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                    if (classDef.type.startsWith("Lapp/morphe/extension/")) return@forEach
                    if (classDef.type == adIdInfo) infoShape = isAdvertisingInfoShape(classDef)
                    classDef.methods.forEach { method ->
                        val body = method.implementation ?: return@forEach
                        val instructions = body.instructions.toList()
                        val where = "${classDef.type}->${method.name}"
                        val writesToken = instructions.any {
                            ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == AD_ID_SERVICE_TOKEN
                        }
                        val transacts = instructions.any {
                            reference(it)?.let { call -> call.definingClass == "Landroid/os/IBinder;" && call.name == "transact" } == true
                        }
                        if (writesToken && transacts) tokenAndTransact += where

                        val raw = rawAdvertisingIdReads(method)
                        if (raw.isNotEmpty()) readers += where
                        serviceReads += raw
                        raw.forEach { site ->
                            val (moveResult, read) = when (site.read) {
                                AdIdRead.ID -> Opcode.MOVE_RESULT_OBJECT to "readString"
                                AdIdRead.LIMIT_REPLY -> Opcode.MOVE_RESULT to "readInt"
                                AdIdRead.LIMIT_FIELD -> null to ""
                            }
                            val call = reference(instructions[site.index - 1])
                            if (instructions[site.index].opcode != moveResult ||
                                call?.definingClass != "Landroid/os/Parcel;" || call?.name != read
                            ) {
                                problems += "$where: ${site.read} at ${site.index} is not a $read result"
                            }
                        }

                        val fields = advertisingInfoFieldReads(method)
                        fieldReads += fields
                        fields.forEach { site ->
                            val iget = when (site.read) {
                                AdIdRead.LIMIT_FIELD -> Opcode.IGET_BOOLEAN
                                AdIdRead.ID -> Opcode.IGET_OBJECT
                                AdIdRead.LIMIT_REPLY -> null
                            }
                            if (instructions[site.index].opcode != iget) {
                                problems += "$where: ${site.read} at ${site.index} is ${instructions[site.index].opcode}"
                            }
                        }

                        // The answer moves back with move-result vAA, so the register fits eight bits.
                        (raw + fields).forEach { site ->
                            if (site.register !in 0 until minOf(body.registerCount, 256)) {
                                problems += "$where: v${site.register} can't take the answer back"
                            }
                        }
                    }
                }
            }

            assertEquals("$version: AdvertisingIdClient\$Info holds one String and one boolean field", true, infoShape)
            assertEquals("$version: every method that writes the service token and calls transact is a reader the shape reads",
                tokenAndTransact.sorted(), readers.sorted())
            assertEquals("$version: direct service readers $readers", 4, readers.size)
            assertEquals("$version: AppsFlyer's two readers", 2, readers.count { it.startsWith("Lcom/appsflyer/") })
            assertEquals("$version: id reads off the service reply", 2, serviceReads.count { it.read == AdIdRead.ID })
            assertEquals("$version: limit flag reads off the service reply", 2,
                serviceReads.count { it.read == AdIdRead.LIMIT_REPLY })
            assertEquals("$version: Info limit flag reads", 14, fieldReads.count { it.read == AdIdRead.LIMIT_FIELD })
            assertEquals("$version: Info id reads", 2, fieldReads.count { it.read == AdIdRead.ID })
            assertTrue("$version: $problems", problems.isEmpty())
        }
    }

    /**
     * A new device identity answers the Android ID at TikTok's Settings.Secure.getString and
     * Settings.System.getString calls. Each declared build makes the Secure read, every one of
     * both reads is the static call the replacement is emitted for, and nothing reads a String off
     * Settings.Secure another way (getStringForUser, say) where the phone's own id would slip by.
     */
    @Test
    fun `each declared build reads the Android ID only where a new identity answers it`() {
        val secure = "Landroid/provider/Settings\$Secure;"
        val system = "Landroid/provider/Settings\$System;"
        Fixtures.forEachDeclared { apk ->
            val version = Fixtures.versionOf(apk)
            var secureReads = 0
            val wrongForm = mutableListOf<String>()
            val otherReads = mutableListOf<String>()

            val container = Fixtures.dexContainer(apk, Opcodes.getDefault())
            container.dexEntryNames.forEach { entry ->
                container.getEntry(entry)!!.dexFile.classes.forEach { classDef ->
                    if (classDef.type.startsWith("Lapp/morphe/extension/")) return@forEach
                    classDef.methods.forEach { method ->
                        method.implementation?.instructions?.forEach { instruction ->
                            val ref = reference(instruction) ?: return@forEach
                            if (ref.definingClass != secure && ref.definingClass != system) return@forEach
                            if (ref.returnType != "Ljava/lang/String;") return@forEach
                            val replaced = ref.name == "getString" &&
                                ref.parameterTypes.map { it.toString() } ==
                                listOf("Landroid/content/ContentResolver;", "Ljava/lang/String;")
                            when {
                                !replaced && ref.definingClass == secure ->
                                    otherReads += "${classDef.type}->${method.name} calls ${ref.name}"
                                !replaced -> Unit
                                instruction.opcode !in static ->
                                    wrongForm += "${ref.definingClass} getString ${instruction.opcode}"
                                ref.definingClass == secure -> secureReads++
                            }
                        }
                    }
                }
            }

            assertTrue("$version: no Settings.Secure.getString call site", secureReads > 0)
            assertTrue("$version: a Settings read in a form the replacement can't match: $wrongForm",
                wrongForm.isEmpty())
            assertTrue("$version: Settings.Secure read some other way: $otherReads", otherReads.isEmpty())
        }
    }

    private fun reference(instruction: Instruction): MethodReference? =
        (instruction as? ReferenceInstruction)?.reference as? MethodReference
}
