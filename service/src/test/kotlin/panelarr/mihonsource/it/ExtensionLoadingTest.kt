// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.ContentWarning
import panelarr.mihon.v1.ExtensionFormat
import panelarr.mihon.v1.ExtensionStatus
import panelarr.mihon.v1.getExtensionRequest
import panelarr.mihon.v1.installExtensionRequest
import panelarr.mihon.v1.listExtensionsRequest
import panelarr.mihon.v1.reloadExtensionRequest
import panelarr.mihon.v1.uninstallExtensionRequest
import panelarr.mihonsource.extension.Signatures
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.mihonsource.fixtures.TestSigningKey
import panelarr.source.v1.listSourcesRequest
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Installing, loading, reloading and removing extensions through the admin API. */
class ExtensionLoadingTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var service: TestService
    private val key = TestSigningKey()

    @BeforeEach
    fun start() {
        service = TestService(dir.resolve("data"))
    }

    @AfterEach
    fun stop() = service.close()

    @Test
    fun `a lib 1_6 jar installs and its factory sources load`() {
        val extension = service.install(TestExtensions.lib16)
        assertEquals(TestExtensions.LIB16_PKG, extension.pkgName)
        assertEquals("Lib16 Test", extension.name)
        assertEquals("1.6", extension.libVersion)
        assertEquals(ExtensionFormat.EXTENSION_FORMAT_JAR, extension.format)
        assertEquals(ContentWarning.CONTENT_WARNING_MIXED, extension.contentWarning)
        assertEquals(ExtensionStatus.EXTENSION_STATUS_LOADED, extension.status, extension.loadError)
        assertEquals(listOf("en", "fr"), extension.sourcesList.map { it.lang }.sorted())
        assertEquals("", extension.signingFingerprint)

        val sources = runBlocking { service.sources.listSources(listSourcesRequest {}) }.sourcesList
        assertEquals(2, sources.size)
        // Mihon's id scheme: MD5 of "name/lang/versionId".
        assertTrue(sources.all { it.id.toLong() > 0 })
    }

    @Test
    fun `an APK is converted, its signature reported and its assets kept`() {
        val apk = TestExtensions.lib14Apk(key, dir.resolve("lib14.apk"))
        val extension = service.install(apk)
        assertEquals(ExtensionFormat.EXTENSION_FORMAT_APK, extension.format)
        assertEquals(ExtensionStatus.EXTENSION_STATUS_LOADED, extension.status, extension.loadError)
        assertEquals(Signatures.fingerprint(key.certificate), extension.signingFingerprint)
        assertEquals("1.4", extension.libVersion)
        assertEquals(ContentWarning.CONTENT_WARNING_NSFW, extension.contentWarning)

        val source = service.app.extensions.sources().single()
        val greeting = (source as panelarr.mihonsource.mihon.MihonSource).source.javaClass.getMethod("getGreeting").invoke(source.source)
        assertEquals("hello from assets", greeting)
    }

    @Test
    fun `a signed jar reports its fingerprint and expectations are enforced`() {
        val signed = TestExtensions.signedJar(TestExtensions.lib16, key, dir.resolve("signed.jar"))
        val fingerprint = Signatures.fingerprint(key.certificate)
        val bytes = Files.readAllBytes(signed)

        val wrongFingerprint =
            assertFailsWith<StatusException> {
                runBlocking {
                    service.admin.installExtension(
                        installExtensionRequest {
                            data = com.google.protobuf.ByteString.copyFrom(bytes)
                            expectedSigningFingerprint = "00".repeat(32)
                        },
                    )
                }
            }
        assertEquals(Status.Code.INVALID_ARGUMENT, wrongFingerprint.status.code)
        assertTrue("fingerprint mismatch" in wrongFingerprint.status.description!!)

        val wrongHash =
            assertFailsWith<StatusException> {
                runBlocking {
                    service.admin.installExtension(
                        installExtensionRequest {
                            data = com.google.protobuf.ByteString.copyFrom(bytes)
                            expectedSha256 = "ab".repeat(32)
                        },
                    )
                }
            }
        assertTrue("SHA-256 mismatch" in wrongHash.status.description!!)

        val installed =
            runBlocking {
                service.admin.installExtension(
                    installExtensionRequest {
                        data = com.google.protobuf.ByteString.copyFrom(bytes)
                        expectedSigningFingerprint = fingerprint.uppercase().chunked(2).joinToString(":")
                        expectedSha256 = Signatures.sha256Hex(bytes)
                    },
                ).extension
            }
        assertEquals(fingerprint, installed.signingFingerprint)
        assertEquals(ExtensionStatus.EXTENSION_STATUS_LOADED, installed.status)
    }

    @Test
    fun `unsupported lib versions are rejected`() {
        val old = TestExtensions.withManifest(TestExtensions.lib16, dir.resolve("old.jar")) { it.replace("android:value=\"1.6\"", "android:value=\"1.3\"") }
        val error = assertFailsWith<StatusException> { service.install(old) }
        assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code)
        assertTrue("1.3 isn't supported" in error.status.description!!)
    }

    @Test
    fun `a broken extension is reported and doesn't affect the others`() {
        service.install(TestExtensions.lib16)
        val broken =
            TestExtensions.withManifest(TestExtensions.lib14, dir.resolve("broken.jar")) {
                it.replace("panelarr.testext.lib14.Lib14Source", "panelarr.testext.lib14.Missing")
            }
        val extension = service.install(broken)
        assertEquals(ExtensionStatus.EXTENSION_STATUS_ERROR, extension.status)
        assertTrue("not found" in extension.loadError)

        val all = runBlocking { service.admin.listExtensions(listExtensionsRequest {}) }.extensionsList
        assertEquals(2, all.size)
        assertEquals(2, runBlocking { service.sources.listSources(listSourcesRequest {}) }.sourcesCount)
    }

    @Test
    fun `reload, restart and uninstall`() {
        service.install(TestExtensions.lib16)
        val reloaded = runBlocking { service.admin.reloadExtension(reloadExtensionRequest { pkgName = TestExtensions.LIB16_PKG }) }.extension
        assertEquals(ExtensionStatus.EXTENSION_STATUS_LOADED, reloaded.status)

        // A new service on the same data loads what was installed.
        service.close()
        service = TestService(dir.resolve("data"))
        assertEquals(2, runBlocking { service.sources.listSources(listSourcesRequest {}) }.sourcesCount)

        runBlocking { service.admin.uninstallExtension(uninstallExtensionRequest { pkgName = TestExtensions.LIB16_PKG }) }
        assertEquals(0, runBlocking { service.sources.listSources(listSourcesRequest {}) }.sourcesCount)
        val error = assertFailsWith<StatusException> { runBlocking { service.admin.getExtension(getExtensionRequest { pkgName = TestExtensions.LIB16_PKG }) } }
        assertEquals(Status.Code.NOT_FOUND, error.status.code)
    }

    @Test
    fun `garbage is rejected`() {
        val junk = dir.resolve("junk.jar").also { Files.writeString(it, "not a zip") }
        val error = assertFailsWith<StatusException> { service.install(junk) }
        assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code)
    }
}
