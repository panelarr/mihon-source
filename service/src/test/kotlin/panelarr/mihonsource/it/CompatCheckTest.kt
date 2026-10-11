// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.listExtensionsRequest
import panelarr.mihonsource.compat.check.CompatCheck
import panelarr.mihonsource.compat.check.Options
import panelarr.mihonsource.compat.check.Status
import panelarr.mihonsource.compat.check.summary
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.FixtureStore
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.mihonsource.fixtures.TestSigningKey
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The compatibility checker against a running service and the fixture store. */
class CompatCheckTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var site: FakeSite
    private lateinit var store: FixtureStore
    private lateinit var service: TestService
    private val key = TestSigningKey()

    @BeforeEach
    fun start() {
        site = FakeSite()
        System.setProperty("panelarr.testext.baseUrl", site.baseUrl)
        service = TestService(dir.resolve("data"))
        store = FixtureStore()
        val good = TestExtensions.signedJar(TestExtensions.lib16, key, dir.resolve("good.jar"))
        val broken =
            TestExtensions.signedJar(
                TestExtensions.withManifest(TestExtensions.lib14, dir.resolve("broken-unsigned.jar")) {
                    it.replace("panelarr.testext.lib14.Lib14Source", "panelarr.testext.lib14.Missing")
                },
                key,
                dir.resolve("broken.jar"),
            )
        store.publish(
            key,
            listOf(
                FixtureStore.Offer(TestExtensions.LIB16_PKG, "Lib16 Test", "1.6.2", 106002, "1.6", Files.readAllBytes(good), asJar = true),
                FixtureStore.Offer(
                    TestExtensions.LIB14_PKG,
                    "Lib14 Test",
                    "1.4.3",
                    104003,
                    "1.4",
                    Files.readAllBytes(broken),
                    asJar = true,
                ),
                FixtureStore.Offer("org.example.ancient", "Ancient", "1.2.1", 102001, "1.2", ByteArray(1), asJar = true),
            ),
        )
    }

    @AfterEach
    fun stop() {
        service.close()
        store.close()
        site.close()
        System.clearProperty("panelarr.testext.baseUrl")
    }

    private fun options() = Options(url = service.base, token = TestService.ADMIN, store = store.url("/store/repo.json"))

    @Test
    fun `load only by default, and nothing is left installed`() {
        val report = runBlocking { CompatCheck(options()).run() }
        val byPkg = report.extensions.associateBy { it.pkgName }
        assertEquals(Status.LOADED, byPkg.getValue(TestExtensions.LIB16_PKG).status)
        assertEquals(2, byPkg.getValue(TestExtensions.LIB16_PKG).sources.size)
        assertEquals(Status.LOAD_ERROR, byPkg.getValue(TestExtensions.LIB14_PKG).status)
        assertEquals("missing class", byPkg.getValue(TestExtensions.LIB14_PKG).problem)
        assertEquals(Status.UNSUPPORTED, byPkg.getValue("org.example.ancient").status)
        // Load only: no calls reached the site.
        assertEquals(0, site.requests.size)
        assertEquals(0, runBlocking { service.admin.listExtensions(listExtensionsRequest {}) }.extensionsCount)
        assertTrue("3 extension(s): 1 loaded, 2 not loaded" in summary(report))
    }

    @Test
    fun `checks run when asked, on a random pick`() {
        val report =
            runBlocking {
                CompatCheck(
                    options().copy(
                        selection = Options.Selection.Packages(listOf(TestExtensions.LIB16_PKG)),
                        checks = listOf("popular", "pages"),
                        keep = true,
                    ),
                ).run()
            }
        val extension = report.extensions.single()
        assertTrue(extension.sources.all { it.ok })
        assertEquals(
            listOf("popular", "chapters", "pages"),
            extension.sources
                .first()
                .steps
                .map { it.step },
        )
        // Kept on request.
        assertEquals(1, runBlocking { service.admin.listExtensions(listExtensionsRequest {}) }.extensionsCount)

        val random = runBlocking { CompatCheck(options().copy(selection = Options.Selection.Random(2, 7))).run() }
        assertEquals(2, random.extensions.size)
    }
}
