// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.it

import com.google.protobuf.Struct
import com.google.protobuf.Value
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import panelarr.mihon.v1.assignProfileRequest
import panelarr.mihon.v1.putProfileRequest
import panelarr.mihonsource.fixtures.FakeSite
import panelarr.mihonsource.fixtures.TestExtensions
import panelarr.source.v1.NetworkProfileOrigin
import panelarr.source.v1.getNetworkProfileRequest
import panelarr.source.v1.getSettingsRequest
import panelarr.source.v1.getSettingsSchemaRequest
import panelarr.source.v1.healthRequest
import panelarr.source.v1.listSourcesRequest
import panelarr.source.v1.networkProfile
import panelarr.source.v1.proxy
import panelarr.source.v1.setNetworkProfileRequest
import panelarr.source.v1.setSettingsRequest
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import panelarr.mihon.v1.networkProfile as adminProfile
import panelarr.mihon.v1.proxy as adminProxy

/** Health's instance ID, the profile a source uses, and secret settings. */
class ProfilesAndSecretsTest {
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

    private fun launch(install: Boolean = true): TestService =
        TestService(dir.resolve("data")).also {
            service = it
            if (install) it.install(TestExtensions.lib16)
        }

    private fun TestService.en() = runBlocking { sources.listSources(listSourcesRequest { lang = "en" }) }.sourcesList.single().id

    private fun TestService.profile(id: String) = runBlocking { sources.getNetworkProfile(getNetworkProfileRequest { sourceId = id }) }

    @Test
    fun `instance id is stable while running and new after a restart`() {
        val first = launch()
        val a = runBlocking { first.sources.health(healthRequest {}) }.instanceId
        assertTrue(a.isNotEmpty())
        assertEquals(a, runBlocking { first.sources.health(healthRequest {}) }.instanceId)
        first.close()
        service = null
        val b = runBlocking { launch(install = false).sources.health(healthRequest {}) }.instanceId
        assertNotEquals(a, b)
    }

    @Test
    fun `the profile a source uses, and where it comes from`() {
        val svc = launch()
        val en = svc.en()
        assertEquals(NetworkProfileOrigin.NETWORK_PROFILE_ORIGIN_NONE, svc.profile(en).origin)

        // The service's own assignment.
        runBlocking {
            svc.integrations.putProfile(
                putProfileRequest {
                    profile =
                        adminProfile {
                            id = "local"
                            name = "Local"
                            proxy = adminProxy { url = "http://proxy.example:3128" }
                        }
                },
            )
            svc.integrations.assignProfile(
                assignProfileRequest {
                    sourceId = en
                    profileId = "local"
                },
            )
        }
        val own = svc.profile(en)
        assertEquals(NetworkProfileOrigin.NETWORK_PROFILE_ORIGIN_PLUGIN, own.origin)
        assertEquals("http://proxy.example:3128", own.profile.proxy.url)

        // A pushed one wins; its password never comes back.
        runBlocking {
            svc.sources.setNetworkProfile(
                setNetworkProfileRequest {
                    sourceId = en
                    profile =
                        networkProfile {
                            name = "vpn"
                            proxy =
                                proxy {
                                    url = "socks5://gluetun.example:1080"
                                    username = "u"
                                    password = "secret"
                                }
                        }
                },
            )
        }
        val pushed = svc.profile(en)
        assertEquals(NetworkProfileOrigin.NETWORK_PROFILE_ORIGIN_PUSHED, pushed.origin)
        assertEquals("socks5://gluetun.example:1080", pushed.profile.proxy.url)
        assertEquals("u", pushed.profile.proxy.username)
        assertEquals("", pushed.profile.proxy.password)

        // Removing the pushed profile falls back to the service's own.
        runBlocking { svc.sources.setNetworkProfile(setNetworkProfileRequest { sourceId = en }) }
        assertEquals(NetworkProfileOrigin.NETWORK_PROFILE_ORIGIN_PLUGIN, svc.profile(en).origin)
    }

    @Test
    fun `password settings are written but never read back`() {
        val svc = launch()
        val en = svc.en()
        val schema = runBlocking { svc.sources.getSettingsSchema(getSettingsSchemaRequest { sourceId = en }) }.schema
        val token =
            schema.fieldsMap
                .getValue("properties")
                .structValue.fieldsMap
                .getValue("api_token")
                .structValue.fieldsMap
        assertTrue(token.getValue("writeOnly").boolValue)
        val baseUrl =
            schema.fieldsMap
                .getValue("properties")
                .structValue.fieldsMap
                .getValue("base_url")
                .structValue.fieldsMap
        assertTrue("writeOnly" !in baseUrl)

        fun set(vararg values: Pair<String, String>) =
            runBlocking {
                svc.sources.setSettings(
                    setSettingsRequest {
                        sourceId = en
                        this.values =
                            Struct
                                .newBuilder()
                                .apply {
                                    values.forEach { (k, v) ->
                                        putFields(k, Value.newBuilder().setStringValue(v).build())
                                    }
                                }.build()
                    },
                )
            }.values
        assertEquals("", set("api_token" to "s3cret").fieldsMap.getValue("api_token").stringValue)
        assertEquals(
            "",
            runBlocking {
                svc.sources.getSettings(getSettingsRequest { sourceId = en })
            }.values.fieldsMap.getValue("api_token").stringValue,
        )
        // Sending back what was read (empty) keeps the secret; the extension still has it.
        set("api_token" to "", "rejects_bad" to "fine")
        assertEquals("s3cret", readToken(en))
    }

    /** The stored value, as the extension sees it. */
    private fun readToken(sourceId: String): String? =
        panelarr.mihonsource.compat.AndroidHost
            .sharedPreferences("source_$sourceId")
            .getString("api_token", null)
}
