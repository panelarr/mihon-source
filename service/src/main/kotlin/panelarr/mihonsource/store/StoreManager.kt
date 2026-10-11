// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.store

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import panelarr.mihonsource.extension.ExtensionDescriptor
import panelarr.mihonsource.extension.ExtensionException
import panelarr.mihonsource.extension.ExtensionInstaller
import panelarr.mihonsource.extension.ExtensionManager
import panelarr.mihonsource.extension.ExtensionRecord
import panelarr.mihonsource.extension.Expectations
import panelarr.mihonsource.extension.InstalledExtension
import panelarr.mihonsource.extension.LibVersion
import panelarr.mihonsource.extension.Signatures
import panelarr.mihonsource.storage.JsonFile
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** A store as kept on disk: its settings, pinned key and last good snapshot. */
@Serializable
data class StoreRecord(
    val id: String,
    val url: String,
    val name: String,
    val badgeLabel: String,
    val contact: String,
    /** Pinned when the store was added. Empty for stores that announce no key. */
    val signingKey: String,
    val legacy: Boolean,
    val addedAt: String,
    val refreshedAt: String? = null,
    val lastError: String? = null,
    val extensionCount: Int = 0,
)

/** An offered extension together with what's installed. */
data class AvailableExtension(
    val storeId: String,
    val extension: StoreExtension,
    val installed: InstalledExtension?,
) {
    val supported: Boolean get() = LibVersion.isSupported(extension.libVersion)
    val updateAvailable: Boolean
        get() {
            val record = installed?.record ?: return false
            val fromHere = (record.origin as? ExtensionRecord.Origin.Store)?.storeId == storeId
            return fromHere && extension.versionCode > record.versionCode
        }
}

/**
 * Extension stores: adding (which pins the store's signing key), refreshing, listing what
 * they offer and installing from them. No store is configured by default. State is kept
 * in `stores/stores.json`, plus one snapshot file per store.
 */
class StoreManager(
    private val dir: Path,
    private val reader: StoreReader,
    private val extensions: ExtensionManager,
    private val installer: ExtensionInstaller,
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(StoreManager::class.java)
    private val file = JsonFile(dir.resolve("stores.json"), ListSerializer(StoreRecord.serializer()))
    private val mutex = Mutex()

    @Volatile
    private var stores: List<StoreRecord> = file.read().orEmpty()
    private val snapshots = mutableMapOf<String, List<StoreExtension>>()

    fun list(): List<StoreRecord> = stores

    fun get(id: String): StoreRecord = stores.firstOrNull { it.id == id } ?: throw NoSuchElementException("store '$id' not found")

    suspend fun add(
        url: String,
        expectedFingerprint: String?,
    ): StoreRecord =
        mutex.withLock {
            val id = idFor(url)
            require(stores.none { it.id == id }) { "this store is already added" }
            val snapshot = reader.read(url)
            expectedFingerprint?.let { expected ->
                if (Signatures.normalize(expected) != snapshot.signingKey) {
                    throw StoreException("the store announces signing key '${snapshot.signingKey.ifEmpty { "none" }}', not the expected one")
                }
            }
            val now = clock().toString()
            val record =
                StoreRecord(
                    id = id,
                    url = url.trim(),
                    name = snapshot.name.ifBlank { url },
                    badgeLabel = snapshot.badgeLabel,
                    contact = snapshot.contact,
                    signingKey = snapshot.signingKey,
                    legacy = snapshot.legacy,
                    addedAt = now,
                    refreshedAt = now,
                    extensionCount = snapshot.extensions.size,
                )
            saveSnapshot(id, snapshot.extensions)
            stores = stores + record
            file.write(stores)
            log.info("Added store {} ({}, {} extensions)", record.name, record.id, snapshot.extensions.size)
            record
        }

    suspend fun remove(id: String) {
        mutex.withLock {
            get(id)
            stores = stores.filterNot { it.id == id }
            file.write(stores)
            snapshots.remove(id)
            Files.deleteIfExists(snapshotFile(id).path)
        }
    }

    /**
     * Reads the store again. A store that now announces a different signing key keeps its
     * old pin and data, and the refresh fails: the key changing is exactly what pinning is
     * meant to catch.
     */
    suspend fun refresh(id: String): StoreRecord =
        mutex.withLock {
            val current = get(id)
            val updated =
                try {
                    val snapshot = reader.read(current.url)
                    if (snapshot.signingKey != current.signingKey) {
                        throw StoreException(
                            "the store's signing key changed from '${current.signingKey.ifEmpty { "none" }}' to " +
                                "'${snapshot.signingKey.ifEmpty { "none" }}'; remove and add the store again if you trust the new key",
                        )
                    }
                    saveSnapshot(id, snapshot.extensions)
                    current.copy(
                        name = snapshot.name.ifBlank { current.name },
                        badgeLabel = snapshot.badgeLabel,
                        contact = snapshot.contact,
                        refreshedAt = clock().toString(),
                        lastError = null,
                        extensionCount = snapshot.extensions.size,
                    )
                } catch (
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    val failed = current.copy(lastError = e.message ?: e.javaClass.simpleName)
                    replace(failed)
                    throw if (e is StoreException) e else StoreException("refreshing the store failed: ${e.message}", e)
                }
            replace(updated)
            updated
        }

    fun available(
        storeId: String? = null,
        lang: String? = null,
        query: String? = null,
        updatesOnly: Boolean = false,
    ): List<AvailableExtension> {
        val selected = if (storeId.isNullOrEmpty()) stores else listOf(get(storeId))
        return selected
            .flatMap { store -> snapshot(store.id).map { AvailableExtension(store.id, it, extensions.find(it.pkgName)) } }
            .filter { lang.isNullOrEmpty() || it.extension.sources.any { s -> s.language == lang } }
            .filter { available ->
                query.isNullOrBlank() ||
                    available.extension.name.contains(query, ignoreCase = true) ||
                    available.extension.sources.any { it.name.contains(query, ignoreCase = true) }
            }.filter { !updatesOnly || it.updateAvailable }
            .sortedBy { it.extension.name.lowercase() }
    }

    /** Installs or updates [pkgName] from the store, verifying the pinned signing key. */
    suspend fun install(
        storeId: String,
        pkgName: String,
    ): InstalledExtension {
        val store = get(storeId)
        val offer = snapshot(storeId).firstOrNull { it.pkgName == pkgName } ?: throw NoSuchElementException("the store doesn't offer '$pkgName'")
        LibVersion.requireSupported(offer.libVersion)
        extensions.find(pkgName)?.let { installed ->
            if (installed.record.versionCode > offer.versionCode) {
                throw ExtensionException("version ${installed.record.versionName} is installed, the store only has ${offer.versionName}")
            }
        }
        val url = offer.jarUrl ?: offer.apkUrl ?: throw StoreException("the store lists no file for '$pkgName'")
        val installed =
            installer.installFromUrl(
                url = url,
                expectations = Expectations(signingFingerprint = store.signingKey.ifEmpty { null }),
                origin = ExtensionRecord.Origin.Store(storeId, url),
                hint =
                    ExtensionDescriptor(
                        pkgName = offer.pkgName,
                        name = offer.name,
                        versionName = offer.versionName,
                        versionCode = offer.versionCode,
                        libVersion = offer.libVersion,
                        classNames = emptyList(),
                        nsfw = offer.contentWarning == IndexContentWarning.CONTENT_WARNING_NSFW,
                    ),
                contentWarning = warning(offer.contentWarning),
            )
        if (installed.record.pkgName != pkgName) {
            installer.uninstall(installed.record.pkgName, deleteData = false)
            throw ExtensionException("the store's file contains '${installed.record.pkgName}', not '$pkgName'")
        }
        return installed
    }

    private fun warning(value: IndexContentWarning): ExtensionRecord.ContentWarning =
        when (value) {
            IndexContentWarning.CONTENT_WARNING_MIXED -> ExtensionRecord.ContentWarning.MIXED
            IndexContentWarning.CONTENT_WARNING_NSFW -> ExtensionRecord.ContentWarning.NSFW
            else -> ExtensionRecord.ContentWarning.SAFE
        }

    private fun replace(record: StoreRecord) {
        stores = stores.map { if (it.id == record.id) record else it }
        file.write(stores)
    }

    @Synchronized
    private fun snapshot(id: String): List<StoreExtension> = snapshots.getOrPut(id) { snapshotFile(id).read().orEmpty().map { it.toExtension() } }

    @Synchronized
    private fun saveSnapshot(
        id: String,
        list: List<StoreExtension>,
    ) {
        snapshots[id] = list
        snapshotFile(id).write(list.map(StoredExtension::of))
    }

    private fun snapshotFile(id: String) = JsonFile(dir.resolve("$id.json"), ListSerializer(StoredExtension.serializer()))

    @Serializable
    private data class StoredExtension(
        val name: String,
        val pkgName: String,
        val versionName: String,
        val versionCode: Long,
        val libVersion: String,
        val contentWarning: IndexContentWarning,
        val apkUrl: String? = null,
        val jarUrl: String? = null,
        val iconUrl: String? = null,
        val sources: List<IndexSource> = emptyList(),
    ) {
        fun toExtension() = StoreExtension(name, pkgName, versionName, versionCode, libVersion, contentWarning, apkUrl, jarUrl, iconUrl, sources)

        companion object {
            fun of(e: StoreExtension) =
                StoredExtension(e.name, e.pkgName, e.versionName, e.versionCode, e.libVersion, e.contentWarning, e.apkUrl, e.jarUrl, e.iconUrl, e.sources)
        }
    }

    companion object {
        /** Store ids are derived from the URL, so adding the same store twice is caught. */
        fun idFor(url: String): String = Signatures.sha256Hex(url.trim().trimEnd('/').lowercase().toByteArray()).take(ID_LENGTH)

        private const val ID_LENGTH = 12
    }
}
