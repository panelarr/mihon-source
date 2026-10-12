// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.getExtensionRequest
import panelarr.mihon.v1.setExtensionLanguagesRequest
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.source.v1.getSourceRequest
import panelarr.source.v1.listSourcesRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** An extension's enabled languages decide which of its sources are listed. */
class ExtensionLanguagesTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var site: FakeSite
    private var service: TestService? = null

    @BeforeEach
    fun start() {
        site = FakeSite()
        System.setProperty("panelarr.testext.baseUrl", site.baseUrl)
    }

    @AfterEach
    fun stop() {
        service?.close()
        site.close()
        System.clearProperty("panelarr.testext.baseUrl")
    }

    private fun start(install: Boolean): TestService =
        TestService(dir.resolve("data")).also {
            service = it
            if (install) it.install(TestExtensions.lib16)
        }

    private fun TestService.listed(): Map<String, String> =
        runBlocking { sources.listSources(listSourcesRequest {}) }.sourcesList.associate { it.lang to it.id }

    private fun TestService.setLangs(vararg langs: String) =
        runBlocking {
            admin.setExtensionLanguages(
                setExtensionLanguagesRequest {
                    pkgName = TestExtensions.LIB16_PKG
                    enabledLangs += langs.toList()
                },
            )
        }.extension

    @Test
    fun `languages narrow the sources, and the setting survives a restart`() {
        val svc = start(install = true)
        val all = svc.listed()
        assertEquals(setOf("en", "fr"), all.keys)

        val ext = svc.setLangs("fr")
        assertEquals(listOf("fr"), ext.enabledLangsList)
        assertEquals(mapOf("en" to false, "fr" to true), ext.sourcesList.associate { it.lang to it.available })
        assertEquals(setOf("fr"), svc.listed().keys)
        val hidden =
            assertFailsWith<StatusException> { runBlocking { svc.sources.getSource(getSourceRequest { sourceId = all.getValue("en") }) } }
        assertEquals(Status.Code.NOT_FOUND, hidden.status.code)

        val bad = assertFailsWith<StatusException> { svc.setLangs("xx") }
        assertEquals(Status.Code.INVALID_ARGUMENT, bad.status.code)

        svc.close()
        service = null
        val again = start(install = false)
        assertEquals(setOf("fr"), again.listed().keys)
        assertEquals(
            listOf("fr"),
            runBlocking { again.admin.getExtension(getExtensionRequest { pkgName = TestExtensions.LIB16_PKG }) }.extension.enabledLangsList,
        )

        // None means all again.
        again.setLangs()
        assertEquals(setOf("en", "fr"), again.listed().keys)
    }
}
