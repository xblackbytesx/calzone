package com.xblackbytesx.calzone.core

import com.xblackbytesx.calzone.model.Account
import com.xblackbytesx.calzone.model.Collections
import com.xblackbytesx.calzone.model.CzJson
import com.xblackbytesx.calzone.model.Notice
import com.xblackbytesx.calzone.model.ServerError
import com.xblackbytesx.calzone.model.SignedIn
import com.xblackbytesx.calzone.net.OkHttpTransport
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.KSerializer
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * The server's /sync/v1 golden files (calzone-server crates/server/tests/
 * golden, written by its golden_pg test) parse with the app's models, and
 * the REAL Rust core (host build, through JNA as on the phone) syncs against
 * them over the app's own OkHttp transport. A server change that breaks the
 * app fails here.
 */
class CoreContractTest {
    private val dir = System.getProperty("cz.golden")?.let(::File)
    private val lib = System.getProperty("uniffi.component.calzone_ffi.libraryOverride")?.let(::File)
    private val server = MockWebServer()
    private val seen = mutableListOf<String>()

    private fun text(name: String): String {
        assumeTrue("golden files missing (../calzone-server)", dir?.resolve("$name.json")?.isFile == true)
        return dir!!.resolve("$name.json").readText()
    }

    private fun <T> golden(name: String, s: KSerializer<T>): T = CzJson.decodeFromString(s, text(name))

    @Before fun start() = server.start()

    @After fun stop() = server.close()

    @Test
    fun theAppsModelsParseEveryAnswer() {
        val signed = golden("signed_in", SignedIn.serializer())
        assertTrue(signed.token.startsWith("czd_"))
        assertEquals("Mila", signed.account.displayName)
        assertEquals("Pixel 9", golden("me", Account.serializer()).deviceLabel)
        val family = golden("collections", Collections.serializer()).collections.single()
        assertEquals("Family", family.label)
        assertEquals("#d53a7b", family.shownColor)
        assertEquals("write", family.access)
        assertEquals("bad_state", golden("error", ServerError.serializer()).code)
        assertEquals(7L, golden("notice", Notice.serializer()).collections.single().state)
    }

    @Test
    fun theCoreSignsInAndSyncsOverOkHttp() {
        assumeTrue("host libcalzone_ffi.so missing (make core)", lib?.isFile == true)
        val bodies = mapOf(
            "POST /sync/v1/link" to text("signed_in"),
            "GET /sync/v1/collections" to text("collections"),
        )
        val changes = text("changes_full")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val key = "${request.method} ${request.url.encodedPath}"
                synchronized(seen) { seen += key + " " + (request.headers["Authorization"] ?: "-") }
                bodies[key]?.let { return MockResponse(body = it) }
                if (key.endsWith("/changes") && request.method == "GET") return MockResponse(body = changes)
                return MockResponse(code = 404, body = """{"error":"not found","code":"not_found"}""")
            }
        }
        val base = server.url("/").toString().trimEnd('/')
        val code = "czl_" + "a".repeat(43)
        val link = Core.link("$base/link/$code")
        assertEquals(Link(base, code), link)
        assertNull(Core.link("https://example.com/calendar"))

        val path = Files.createTempDirectory("cz").resolve("store.db").toString()
        val core = Core(path)
        val signed = core.signIn(OkHttpTransport(base, null), link!!, "JVM test")
        assertEquals("mila", core.account()?.name)
        assertEquals(base, core.server())

        val report = core.sync(OkHttpTransport(base, signed.token))
        assertEquals(1, report.collections)
        assertEquals(2, report.pulled)
        val family = core.collections().single()
        assertEquals(2L, family.state)
        val swim = core.objects(family.info.id).first { it.name == "swim.ics" }
        assertEquals("Swimming", swim.summary)
        assertNotNull(swim.starts)
        assertTrue("the token goes with every call after sign-in", seen.drop(1).all { it.endsWith("Bearer ${signed.token}") })
        assertTrue(seen.first().endsWith(" -"))

        // A quiet run is one request.
        val before = seen.size
        core.sync(OkHttpTransport(base, signed.token))
        assertEquals(before + 1, seen.size)

        // No answer and "sign in again" stay apart.
        try {
            core.sync(OkHttpTransport("http://127.0.0.1:1", signed.token))
            error("expected Offline")
        } catch (_: CoreError.Offline) {
        }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse(code = 401, body = """{"error":"x","code":"unauthorized"}""")
        }
        try {
            core.sync(OkHttpTransport(base, signed.token))
            error("expected SignedOut")
        } catch (_: CoreError.SignedOut) {
        }
        assertEquals("the store keeps its copy", 2, core.objects(family.info.id).size)
    }
}
