// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.store

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.protobuf.ProtoBuf
import okhttp3.HttpUrl.Companion.toHttpUrl
import panelarr.mihonsource.net.Downloader
import java.io.ByteArrayInputStream
import java.util.zip.GZIPInputStream

/** A store as read from the network, normalized across the formats. */
data class StoreSnapshot(
    /** The URL the extension list came from; relative artifact URLs resolve against it. */
    val indexUrl: String,
    val name: String,
    val badgeLabel: String,
    val contact: String,
    /** Lowercase hex SHA-256 of the signing certificate, empty when the store announces none. */
    val signingKey: String,
    val legacy: Boolean,
    val extensions: List<StoreExtension>,
)

/** One extension a store offers, with absolute URLs. */
data class StoreExtension(
    val name: String,
    val pkgName: String,
    val versionName: String,
    val versionCode: Long,
    val libVersion: String,
    val contentWarning: IndexContentWarning,
    val apkUrl: String?,
    val jarUrl: String?,
    val iconUrl: String?,
    val sources: List<IndexSource>,
)

/**
 * Reads extension stores: `repo.json` (following its pointer to the index), a protobuf or
 * JSON index (gzipped or not), or a legacy `index.min.json`. Formats are told apart by
 * content, as the URL alone doesn't say.
 */
class StoreReader(
    private val downloader: Downloader,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }

    @OptIn(ExperimentalSerializationApi::class)
    private val protobuf = ProtoBuf

    suspend fun read(url: String): StoreSnapshot {
        val validated = Downloader.validate(url)
        val bytes = fetch(validated)
        return when (bytes.firstOrNull()?.toInt()?.toChar()) {
            '[' -> {
                legacy(validated, bytes, repo = sibling(validated, "repo.json")?.let { runCatching { repoJson(fetch(it)) }.getOrNull() })
            }

            '{' -> {
                val obj =
                    runCatching {
                        json
                            .parseToJsonElement(
                                bytes.decodeToString(),
                            ).jsonObject
                    }.getOrElse { throw invalid(validated, it) }
                if ("meta" in obj ||
                    "index_v2" in obj
                ) {
                    fromRepo(validated, json.decodeFromJsonElement(RepoJson.serializer(), obj))
                } else {
                    index(validated, jsonIndex(obj))
                }
            }

            null -> {
                throw StoreException("$validated is empty")
            }

            else -> {
                index(validated, protobufIndex(validated, bytes))
            }
        }
    }

    private suspend fun fromRepo(
        url: String,
        repo: RepoJson,
    ): StoreSnapshot {
        val meta = repo.meta
        val indexUrl = repo.indexV2?.let { resolve(url, it) }
        if (indexUrl != null) {
            val index = indexAt(indexUrl)
            // The repo.json fingerprint is authoritative when present; it must agree with the index.
            val key = meta?.signingKeyFingerprint.orEmpty().lowercase()
            if (key.isNotEmpty() && index.signingKey.isNotEmpty() && key != index.signingKey) {
                throw StoreException("repo.json and the index announce different signing keys")
            }
            return index.copy(
                name = meta?.name?.ifBlank { null } ?: index.name,
                badgeLabel = meta?.shortName ?: meta?.name ?: index.badgeLabel,
                contact = meta?.website ?: index.contact,
                signingKey = key.ifEmpty { index.signingKey },
            )
        }
        val legacyUrl = sibling(url, "index.min.json") ?: throw StoreException("repo.json points at no index")
        return legacy(legacyUrl, fetch(legacyUrl), repo)
    }

    private suspend fun indexAt(url: String): StoreSnapshot {
        val bytes = fetch(url)
        return when (bytes.firstOrNull()?.toInt()?.toChar()) {
            '{' -> index(url, jsonIndex(json.parseToJsonElement(bytes.decodeToString()).jsonObject))
            else -> index(url, protobufIndex(url, bytes))
        }
    }

    private suspend fun index(
        url: String,
        index: StoreIndex,
    ): StoreSnapshot {
        val list =
            index.extensionList
                ?: index.extensionListUrl?.let { listUrl ->
                    val target = resolve(url, listUrl)
                    val bytes = fetch(target)
                    if (bytes.firstOrNull()?.toInt()?.toChar() == '{') {
                        json.decodeFromString(ExtensionList.serializer(), bytes.decodeToString())
                    } else {
                        @OptIn(ExperimentalSerializationApi::class)
                        protobuf.decodeFromByteArray(ExtensionList.serializer(), bytes)
                    }
                }
                ?: ExtensionList()
        return StoreSnapshot(
            indexUrl = url,
            name = index.name,
            badgeLabel = index.badgeLabel.ifEmpty { index.name },
            contact = index.contact?.website.orEmpty(),
            signingKey = index.signingKey.lowercase(),
            legacy = false,
            extensions =
                list.extensions.filter { it.packageName.isNotBlank() }.map { e ->
                    StoreExtension(
                        name = e.name.removePrefix("Tachiyomi: "),
                        pkgName = e.packageName,
                        versionName = e.versionName,
                        versionCode = e.versionCode,
                        libVersion = e.extensionLib.ifEmpty { e.versionName.substringBeforeLast('.') },
                        contentWarning = e.contentWarning,
                        apkUrl =
                            e.resources.apkUrl
                                .ifBlank { null }
                                ?.let { resolve(url, it) },
                        jarUrl =
                            e.resources.jarUrl
                                .ifBlank { null }
                                ?.let { resolve(url, it) },
                        iconUrl =
                            e.resources.iconUrl
                                .ifBlank { null }
                                ?.let { resolve(url, it) },
                        sources = e.sources,
                    )
                },
        )
    }

    private fun legacy(
        url: String,
        bytes: ByteArray,
        repo: RepoJson?,
    ): StoreSnapshot {
        val entries =
            runCatching { json.decodeFromString<List<LegacyExtension>>(bytes.decodeToString()) }.getOrElse { throw invalid(url, it) }
        val base = url.substringBeforeLast('/')
        return StoreSnapshot(
            indexUrl = url,
            name = repo?.meta?.name ?: url.toHttpUrl().host,
            badgeLabel = repo?.meta?.shortName ?: repo?.meta?.name ?: "",
            contact = repo?.meta?.website.orEmpty(),
            signingKey =
                repo
                    ?.meta
                    ?.signingKeyFingerprint
                    .orEmpty()
                    .lowercase(),
            legacy = true,
            extensions =
                entries.map { e ->
                    StoreExtension(
                        name = e.name.removePrefix("Tachiyomi: "),
                        pkgName = e.pkg,
                        versionName = e.version,
                        versionCode = e.code,
                        libVersion = e.version.substringBeforeLast('.'),
                        contentWarning =
                            if (e.nsfw ==
                                1
                            ) {
                                IndexContentWarning.CONTENT_WARNING_NSFW
                            } else {
                                IndexContentWarning.CONTENT_WARNING_SAFE
                            },
                        apkUrl = "$base/apk/${e.apk}",
                        jarUrl = null,
                        iconUrl = "$base/icon/${e.pkg}.png",
                        sources = e.sources.map { IndexSource(id = it.id, name = it.name, language = it.lang, homeUrl = it.baseUrl) },
                    )
                },
        )
    }

    private fun repoJson(bytes: ByteArray): RepoJson = json.decodeFromString(RepoJson.serializer(), bytes.decodeToString())

    private fun jsonIndex(obj: JsonObject): StoreIndex = json.decodeFromJsonElement(StoreIndex.serializer(), obj)

    @OptIn(ExperimentalSerializationApi::class)
    private fun protobufIndex(
        url: String,
        bytes: ByteArray,
    ): StoreIndex = runCatching { protobuf.decodeFromByteArray(StoreIndex.serializer(), bytes) }.getOrElse { throw invalid(url, it) }

    /** Downloads [url], un-gzipping when needed. */
    private suspend fun fetch(url: String): ByteArray {
        val raw = downloader.fetch(url, maxBytes)
        if (raw.size < 2 || raw[0] != GZIP_0 || raw[1] != GZIP_1) return raw
        return GZIPInputStream(ByteArrayInputStream(raw)).use { input ->
            val out = input.readNBytes((maxBytes * GZIP_RATIO_LIMIT).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            out
        }
    }

    private fun resolve(
        base: String,
        target: String,
    ): String =
        base
            .toHttpUrl()
            .resolve(target)
            ?.toString()
            ?.let(Downloader::validate)
            ?: throw StoreException("invalid URL '$target' in the store")

    private fun sibling(
        url: String,
        name: String,
    ): String? = url.toHttpUrl().resolve(name)?.toString()

    private fun invalid(
        url: String,
        cause: Throwable,
    ) = StoreException("$url isn't a valid extension store: ${cause.message}", cause)

    private companion object {
        const val DEFAULT_MAX_BYTES = 32L * 1024 * 1024
        const val GZIP_RATIO_LIMIT = 4
        const val GZIP_0 = 0x1f.toByte()
        const val GZIP_1 = 0x8b.toByte()
    }
}

class StoreException(
    message: String,
    cause: Throwable? = null,
) : IllegalArgumentException(message, cause)
