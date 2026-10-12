// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.fixtures

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.protobuf.ProtoBuf
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okio.Buffer
import panelarr.mihonsource.extension.Signatures
import panelarr.mihonsource.store.ExtensionList
import panelarr.mihonsource.store.IndexContentWarning
import panelarr.mihonsource.store.IndexExtension
import panelarr.mihonsource.store.IndexSource
import panelarr.mihonsource.store.LegacyExtension
import panelarr.mihonsource.store.RepoJson
import panelarr.mihonsource.store.StoreIndex
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPOutputStream

/**
 * An extension store served from memory, publishing what real stores publish: repo.json,
 * a gzipped protobuf index, the same index as JSON, a legacy index.min.json, and the
 * extension files themselves.
 */
@OptIn(ExperimentalSerializationApi::class)
class FixtureStore : AutoCloseable {
    private val server = MockWebServer()
    private val files = ConcurrentHashMap<String, ByteArray>()
    private val json = Json { encodeDefaults = false }

    /** Requests per path, to see what was downloaded. */
    val requests = ConcurrentHashMap<String, Int>()

    /** Milliseconds to wait before answering a path, e.g. to make a download slow. */
    @Volatile
    var delay: (String) -> Long = { 0 }

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.url.encodedPath
                    requests.merge(path, 1, Int::plus)
                    delay(path).takeIf { it > 0 }?.let { Thread.sleep(it) }
                    val bytes = files[path] ?: return MockResponse.Builder().code(404).build()
                    return MockResponse.Builder().body(Buffer().write(bytes)).build()
                }
            }
        server.start()
    }

    /** Writes everything published to [dir], so any web server can serve the store. */
    fun exportTo(dir: java.nio.file.Path) {
        files.forEach { (path, bytes) ->
            val target = dir.resolve(path.removePrefix("/"))
            java.nio.file.Files
                .createDirectories(target.parent)
            java.nio.file.Files
                .write(target, bytes)
        }
    }

    fun url(path: String): String = server.url(path).toString()

    fun put(
        path: String,
        bytes: ByteArray,
    ) {
        files[path] = bytes
    }

    data class Offer(
        val pkgName: String,
        val name: String,
        val versionName: String,
        val versionCode: Long,
        val libVersion: String,
        val file: ByteArray,
        val asJar: Boolean,
        val warning: IndexContentWarning = IndexContentWarning.CONTENT_WARNING_SAFE,
        /** The sources the index announces, as name and language; one English source by default. */
        val sources: List<Pair<String, String>> = listOf(name to "en"),
    )

    /** Publishes a full store under /store/ signed with [key] (its fingerprint in the index). */
    fun publish(
        key: TestSigningKey?,
        offers: List<Offer>,
        name: String = "Fixture Store",
    ) {
        val fingerprint = key?.let { Signatures.fingerprint(it.certificate) }.orEmpty()
        val extensions =
            offers.map { offer ->
                val path = "/store/files/${offer.pkgName}-${offer.versionName}.${if (offer.asJar) "jar" else "apk"}"
                put(path, offer.file)
                put("/store/icons/${offer.pkgName}.png", TestExtensions.png(32))
                IndexExtension(
                    name = offer.name,
                    packageName = offer.pkgName,
                    resources =
                        IndexExtension.Resources(
                            apkUrl = if (offer.asJar) "" else "files/${path.substringAfterLast('/')}",
                            jarUrl = if (offer.asJar) "files/${path.substringAfterLast('/')}" else "",
                            iconUrl = "icons/${offer.pkgName}.png",
                        ),
                    extensionLib = offer.libVersion,
                    versionCode = offer.versionCode,
                    versionName = offer.versionName,
                    contentWarning = offer.warning,
                    sources =
                        offer.sources.mapIndexed { i, (sourceName, lang) ->
                            IndexSource(id = i + 1L, name = sourceName, language = lang, homeUrl = "https://example.com")
                        },
                )
            }
        val index = StoreIndex(name = name, badgeLabel = "FX", signingKey = fingerprint, extensionList = ExtensionList(extensions))
        put("/store/index.pb", gzip(ProtoBuf.encodeToByteArray(StoreIndex.serializer(), index)))
        put("/store/index.json", json.encodeToString(StoreIndex.serializer(), index).toByteArray())
        put(
            "/store/repo.json",
            json
                .encodeToString(
                    RepoJson.serializer(),
                    RepoJson(
                        indexV2 = "index.pb",
                        meta =
                            RepoJson.Meta(
                                name = name,
                                shortName = "FX",
                                website = "https://example.com",
                                signingKeyFingerprint = fingerprint,
                            ),
                    ),
                ).toByteArray(),
        )
    }

    /** Publishes a legacy store under /legacy/ (APKs only, no signing key). */
    fun publishLegacy(offers: List<Offer>) {
        val entries =
            offers.map { offer ->
                val apk = "${offer.pkgName}-v${offer.versionName}.apk"
                put("/legacy/apk/$apk", offer.file)
                LegacyExtension(
                    name = "Tachiyomi: ${offer.name}",
                    pkg = offer.pkgName,
                    apk = apk,
                    lang = "en",
                    code = offer.versionCode,
                    version = offer.versionName,
                    nsfw = if (offer.warning == IndexContentWarning.CONTENT_WARNING_NSFW) 1 else 0,
                )
            }
        put("/legacy/index.min.json", Json.encodeToString(ListSerializer(LegacyExtension.serializer()), entries).toByteArray())
    }

    private fun gzip(bytes: ByteArray): ByteArray =
        ByteArrayOutputStream()
            .also { out ->
                GZIPOutputStream(out).use {
                    it.write(bytes)
                }
            }.toByteArray()

    override fun close() = server.close()
}
