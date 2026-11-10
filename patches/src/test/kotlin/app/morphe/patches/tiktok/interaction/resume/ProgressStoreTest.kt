package app.morphe.patches.tiktok.interaction.resume

import app.morphe.appendFixtureInstructions
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.getReference
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Where the positions live is read off the host's own put, whatever the build calls it. */
class ProgressStoreTest {
    /** The names on one build: the holder, its Lazy and its record type. */
    private class Build(val holder: String, val lazy: String, val record: String)

    private val v4623 = Build("LX/0Lze;", "LX/01xP;", "LX/0LxV;")
    private val v4683 = Build("LX/0M7t;", "LX/019N;", "LX/0M5f;")

    @Test
    fun `the store is read off the put that ends the progress callback`() {
        val store = storeOf(v4623)
        assertEquals("LX/0Lze;->LIZLLL:LX/01xP;", store.cache.smali())
        assertEquals("LX/01xP;->getValue()Ljava/lang/Object;", store.cacheValue.smali())
        assertEquals("invoke-interface", store.cacheValueInvoke)
        assertEquals("LX/0Lze;->LJ:LX/0LxV;", store.lastRecord.smali())
        assertEquals("LX/0Lze;->LJFF:Ljava/lang/String;", store.lastAid.smali())
    }

    @Test
    fun `the names follow the build rather than being written down`() {
        // 46.8.3, where every one of the three classes has another name and LX/0Lze; is a
        // class with no fields at all.
        val store = storeOf(v4683)
        assertEquals("LX/0M7t;->LIZLLL:LX/019N;", store.cache.smali())
        assertEquals("LX/019N;->getValue()Ljava/lang/Object;", store.cacheValue.smali())
        assertEquals("LX/0M7t;->LJ:LX/0M5f;", store.lastRecord.smali())
        assertEquals("LX/0M7t;->LJFF:Ljava/lang/String;", store.lastAid.smali())
    }

    @Test
    fun `a stored value that is not the store's own static is refused`() {
        val build = v4623
        val progress = progress(build, recordSource = "LX/Elsewhere;->LJ:${build.record}")
        assertRefused("is not read out of a static of ${build.holder}") { storeOf(build, progress) }
    }

    @Test
    fun `a reader that writes two strings beside the record is refused`() {
        val build = v4623
        val reader = reader(build, extra = "sput-object v1, ${build.holder}->LJII:Ljava/lang/String;")
        assertRefused("write one String static beside the record, found 2") {
            storeOf(build, holder = holder(build, reader))
        }
    }

    @Test
    fun `a cache that is not an LruCache is refused`() {
        val build = v4623
        val progress = progress(build, cacheType = "Ljava/util/HashMap;")
        assertRefused("is cast to Ljava/util/HashMap;, not LruCache") { storeOf(build, progress) }
    }

    private fun storeOf(
        build: Build,
        progress: MutableMethod = progress(build),
        holder: ClassDef = holder(build, reader(build)),
    ): ProgressStore {
        val instructions = progress.implementation!!.instructions.toList()
        val putIndex = instructions.indexOfLast { it.getReference<MethodReference>()?.name == "put" }
        return progressStoreAt(progress, putIndex) { type -> if (type == holder.type) holder else null }
    }

    /** The tail of onPlayProgressChange on both builds, from the record being made to its put. */
    private fun progress(
        build: Build,
        recordSource: String = "${build.holder}->LJ:${build.record}",
        cacheType: String = "Landroid/util/LruCache;",
    ) = MutableMethod(
        ImmutableMethod(
            "Lcom/ss/android/ugc/aweme/feed/controller/PlayerController;",
            "onPlayProgressChange",
            listOf("Ljava/lang/String;", "J", "J").map { ImmutableMethodParameter(it, null, null) },
            "V",
            AccessFlags.PUBLIC.value,
            null,
            null,
            ImmutableMethodImplementation(37, emptyList(), null, null),
        ),
    ).apply {
        appendFixtureInstructions(
            """
                new-instance v14, ${build.record}
                const-wide/16 v0, 0x0
                const-wide/16 v5, 0x0
                invoke-direct {v14, v0, v1, v5, v6}, ${build.record}-><init>(JJ)V
                sput-object v14, ${build.holder}->LJ:${build.record}
                sget-object v5, ${build.holder}->LIZLLL:${build.lazy}
                invoke-interface {v5}, ${build.lazy}->getValue()Ljava/lang/Object;
                move-result-object v14
                check-cast v14, $cacheType
                invoke-virtual {v3}, Lcom/ss/android/ugc/aweme/feed/model/Aweme;->getAid()Ljava/lang/String;
                move-result-object v6
                sget-object v5, $recordSource
                invoke-virtual {v14, v6, v5}, Landroid/util/LruCache;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;
                return-void
            """,
        )
    }

    /** The holder's reader: the last record when the aid matches, else the cache's, remembered. */
    private fun reader(build: Build, extra: String = "") = MutableMethod(
        ImmutableMethod(
            build.holder,
            "LIZ",
            listOf(ImmutableMethodParameter("Ljava/lang/String;", null, null)),
            build.record,
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(2, emptyList(), null, null),
        ),
    ).apply {
        appendFixtureInstructions(
            """
                sget-object v0, ${build.holder}->LIZLLL:${build.lazy}
                invoke-interface {v0}, ${build.lazy}->getValue()Ljava/lang/Object;
                move-result-object v0
                check-cast v0, Landroid/util/LruCache;
                invoke-virtual {v0, v1}, Landroid/util/LruCache;->get(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v0
                check-cast v0, ${build.record}
                sput-object v0, ${build.holder}->LJ:${build.record}
                sput-object v1, ${build.holder}->LJFF:Ljava/lang/String;
                $extra
                return-object v0
            """,
        )
    }

    /** A second static on the holder that writes a String of its own, and is not the reader. */
    private fun pauser(build: Build) = MutableMethod(
        ImmutableMethod(
            build.holder,
            "LIZIZ",
            listOf(ImmutableMethodParameter("Ljava/lang/String;", null, null)),
            "V",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value,
            null,
            null,
            ImmutableMethodImplementation(1, emptyList(), null, null),
        ),
    ).apply {
        appendFixtureInstructions(
            """
                sput-object v0, ${build.holder}->LJIIIIZZ:Ljava/lang/String;
                return-void
            """,
        )
    }

    private fun holder(build: Build, reader: MutableMethod): ClassDef = ImmutableClassDef(
        build.holder,
        AccessFlags.PUBLIC.value or AccessFlags.FINAL.value,
        "Ljava/lang/Object;",
        null,
        null,
        null,
        null,
        listOf(reader, pauser(build)),
    )

    private fun assertRefused(expected: String, block: () -> Unit) {
        try {
            block()
            fail("expected a refusal naming \"$expected\"")
        } catch (refused: PatchException) {
            assertTrue(refused.message.orEmpty(), refused.message.orEmpty().contains(expected))
        }
    }
}
