// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.ContentWarning
import panelarr.mihon.v1.ExtensionStatus
import panelarr.mihon.v1.MihonStoresServiceGrpcKt.MihonStoresServiceCoroutineStub
import panelarr.mihon.v1.StoreFormat
import panelarr.mihon.v1.addStoreRequest
import panelarr.mihon.v1.installFromStoreRequest
import panelarr.mihon.v1.listAvailableRequest
import panelarr.mihon.v1.listStoresRequest
import panelarr.mihon.v1.refreshStoreRequest
import panelarr.mihon.v1.removeStoreRequest
import panelarr.mihonsource.extension.Signatures
import panelarr.mihonsource.fixtures.FixtureStore
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.mihonsource.fixtures.TestSigningKey
import panelarr.mihonsource.store.IndexContentWarning
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Extension stores against an in-repo fixture store. */
class StoresTest {
    @TempDir
    lateinit var dir: Path

    private lateinit var service: TestService
    private lateinit var store: FixtureStore
    private val key = TestSigningKey()
    private val api: MihonStoresServiceCoroutineStub get() = service.stub()

    @BeforeEach
    fun start() {
        service = TestService(dir.resolve("data"))
        store = FixtureStore()
    }

    @AfterEach
    fun stop() {
        service.close()
        store.close()
    }

    private fun lib16(
        version: String = "1.6.2",
        code: Long = 106002,
        signer: TestSigningKey = key,
    ): FixtureStore.Offer {
        val edited =
            TestExtensions.withManifest(TestExtensions.lib16, dir.resolve("lib16-$version-unsigned.jar")) {
                it
                    .replace(
                        "android:versionCode=\"106002\"",
                        "android:versionCode=\"$code\"",
                    ).replace("android:versionName=\"1.6.2\"", "android:versionName=\"$version\"")
            }
        val signed = TestExtensions.signedJar(edited, signer, dir.resolve("lib16-$version-${signer.hashCode()}.jar"))
        return FixtureStore.Offer(
            TestExtensions.LIB16_PKG,
            "Lib16 Test",
            version,
            code,
            "1.6",
            Files.readAllBytes(signed),
            asJar = true,
            IndexContentWarning.CONTENT_WARNING_MIXED,
        )
    }

    private fun add(
        path: String = "/store/repo.json",
        expected: String = "",
    ) = runBlocking {
        api.addStore(
            addStoreRequest {
                url = store.url(path)
                expectedSigningKeyFingerprint = expected
            },
        )
    }.store

    @Test
    fun `adding a store pins its key and lists what it offers`() {
        store.publish(key, listOf(lib16()))
        val added = add(expected = Signatures.fingerprint(key.certificate))
        assertEquals("Fixture Store", added.name)
        assertEquals(StoreFormat.STORE_FORMAT_EXTENSION_STORE, added.format)
        assertEquals(Signatures.fingerprint(key.certificate), added.signingKeyFingerprint)
        assertEquals(1, added.extensionCount)

        val offered = runBlocking { api.listAvailable(listAvailableRequest {}) }.extensionsList.single()
        assertEquals(TestExtensions.LIB16_PKG, offered.pkgName)
        assertTrue(offered.supported)
        assertTrue(offered.hasJar)
        assertEquals(false, offered.installed)
        assertEquals(ContentWarning.CONTENT_WARNING_MIXED, offered.contentWarning)

        val duplicate = assertFailsWith<StatusException> { add() }
        assertEquals(Status.Code.INVALID_ARGUMENT, duplicate.status.code)
    }

    @Test
    fun `a store announcing another key than expected is refused`() {
        store.publish(key, listOf(lib16()))
        val error = assertFailsWith<StatusException> { add(expected = "aa".repeat(32)) }
        assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code)
        assertEquals(0, runBlocking { api.listStores(listStoresRequest {}) }.storesCount)
    }

    @Test
    fun `install and update from a store`() {
        store.publish(key, listOf(lib16()))
        val storeId = add().id
        val installed =
            runBlocking {
                api.installFromStore(
                    installFromStoreRequest {
                        this.storeId = storeId
                        pkgName =
                            TestExtensions.LIB16_PKG
                    },
                )
            }.extension
        assertEquals(ExtensionStatus.EXTENSION_STATUS_LOADED, installed.status, installed.loadError)
        assertEquals(storeId, installed.origin.storeId)
        assertEquals(Signatures.fingerprint(key.certificate), installed.signingFingerprint)
        assertEquals(ContentWarning.CONTENT_WARNING_MIXED, installed.contentWarning)

        store.publish(key, listOf(lib16("1.6.3", 106003)))
        runBlocking { api.refreshStore(refreshStoreRequest { this.storeId = storeId }) }
        val update = runBlocking { api.listAvailable(listAvailableRequest { updatesOnly = true }) }.extensionsList.single()
        assertEquals("1.6.2", update.installedVersionName)
        assertTrue(update.updateAvailable)

        val updated =
            runBlocking {
                api.installFromStore(
                    installFromStoreRequest {
                        this.storeId = storeId
                        pkgName =
                            TestExtensions.LIB16_PKG
                    },
                )
            }.extension
        assertEquals("1.6.3", updated.versionName)
        assertEquals(0, runBlocking { api.listAvailable(listAvailableRequest { updatesOnly = true }) }.extensionsCount)
    }

    @Test
    fun `files signed by another key are refused`() {
        store.publish(key, listOf(lib16(signer = TestSigningKey("someone else"))))
        val storeId = add().id
        val error =
            assertFailsWith<StatusException> {
                runBlocking {
                    api.installFromStore(
                        installFromStoreRequest {
                            this.storeId = storeId
                            pkgName =
                                TestExtensions.LIB16_PKG
                        },
                    )
                }
            }
        assertEquals(Status.Code.INVALID_ARGUMENT, error.status.code)
        assertTrue("fingerprint mismatch" in error.status.description!!)
        assertEquals(null, service.app.extensions.find(TestExtensions.LIB16_PKG))
    }

    @Test
    fun `a changed store key fails the refresh and keeps the pin`() {
        store.publish(key, listOf(lib16()))
        val added = add()
        store.publish(TestSigningKey("rotated"), listOf(lib16()))
        val error = assertFailsWith<StatusException> { runBlocking { api.refreshStore(refreshStoreRequest { storeId = added.id }) } }
        assertTrue("signing key changed" in error.status.description!!)
        val kept = runBlocking { api.listStores(listStoresRequest {}) }.storesList.single()
        assertEquals(added.signingKeyFingerprint, kept.signingKeyFingerprint)
        assertTrue(kept.lastError.isNotEmpty())
    }

    @Test
    fun `the JSON index works too`() {
        store.publish(key, listOf(lib16()))
        val added = add("/store/index.json")
        assertEquals(1, added.extensionCount)
        assertEquals(Signatures.fingerprint(key.certificate), added.signingKeyFingerprint)
    }

    @Test
    fun `a legacy index is the fallback`() {
        val apk = Files.readAllBytes(TestExtensions.lib14Apk(key, dir.resolve("lib14.apk")))
        store.publishLegacy(listOf(FixtureStore.Offer(TestExtensions.LIB14_PKG, "Lib14 Test", "1.4.3", 104003, "1.4", apk, asJar = false)))
        val added = add("/legacy/index.min.json")
        assertEquals(StoreFormat.STORE_FORMAT_LEGACY_INDEX, added.format)
        assertEquals("", added.signingKeyFingerprint)
        val installed =
            runBlocking {
                api.installFromStore(
                    installFromStoreRequest {
                        storeId = added.id
                        pkgName = TestExtensions.LIB14_PKG
                    },
                )
            }.extension
        assertEquals(ExtensionStatus.EXTENSION_STATUS_LOADED, installed.status, installed.loadError)

        runBlocking { api.removeStore(removeStoreRequest { storeId = added.id }) }
        assertEquals(0, runBlocking { api.listStores(listStoresRequest {}) }.storesCount)
    }
}
