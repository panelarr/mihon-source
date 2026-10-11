// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.slf4j.LoggerFactory
import panelarr.mihonsource.storage.JsonFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.io.path.isDirectory
import kotlin.io.path.listDirectoryEntries

/** A source provided by a loaded extension. */
interface HostedSource {
    /** Source id, unique across extensions. */
    val id: Long
    val name: String
    val lang: String
}

/** Creates the sources of an extension from its classloader. */
fun interface SourceInstantiator {
    fun instantiate(
        classLoader: ClassLoader,
        record: ExtensionRecord,
        jar: Path,
    ): List<HostedSource>
}

/** An installed extension and its load state. */
class InstalledExtension(
    val record: ExtensionRecord,
    /** Null when loading failed. */
    val sources: List<HostedSource>?,
    val loadError: String?,
    internal val classLoader: ExtensionClassLoader?,
) {
    val loaded: Boolean get() = sources != null
}

/** Called when sources appear or disappear, so dependent state (settings, clients) can follow. */
interface ExtensionListener {
    fun onSourcesAdded(sources: List<HostedSource>) {}

    fun onSourcesRemoved(sources: List<HostedSource>) {}
}

/**
 * Installs, loads and unloads extensions. Each extension lives in
 * `extensions/<package name>/` as `extension.jar` plus `extension.json`, and gets its own
 * classloader. A failure to load one extension is recorded on it and never affects the
 * others or the service.
 */
class ExtensionManager(
    private val dir: Path,
    private val instantiator: SourceInstantiator,
    private val transform: (String, ByteArray) -> ByteArray = { _, bytes -> bytes },
    private val host: HostClassLoader = HostClassLoader(ExtensionManager::class.java.classLoader),
    private val clock: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(ExtensionManager::class.java)
    private val extensions = ConcurrentHashMap<String, InstalledExtension>()
    private val mutex = Mutex()
    private val listeners = mutableListOf<ExtensionListener>()
    private val workDir: Path = dir.resolve(".work")
    val inspector = ExtensionInspector(workDir)

    fun addListener(listener: ExtensionListener) {
        listeners += listener
    }

    fun list(): List<InstalledExtension> = extensions.values.sortedBy { it.record.name.lowercase() }

    fun get(pkgName: String): InstalledExtension =
        extensions[pkgName] ?: throw NoSuchElementException("extension '$pkgName' isn't installed")

    fun find(pkgName: String): InstalledExtension? = extensions[pkgName]

    fun sources(): List<HostedSource> = extensions.values.flatMap { it.sources.orEmpty() }

    /** Loads every installed extension. Called once at start. */
    suspend fun loadAll() {
        Files.createDirectories(dir)
        cleanWorkDir()
        mutex.withLock {
            dir.listDirectoryEntries().filter { it.isDirectory() && !it.fileName.toString().startsWith(".") }.forEach { extDir ->
                val record =
                    runCatching { recordFile(extDir).read() }
                        .onFailure { log.warn("Skipping {}: unreadable extension.json ({})", extDir.fileName, it.message) }
                        .getOrNull() ?: return@forEach
                load(record)
            }
        }
        log.info("Loaded {} extension(s) with {} source(s)", extensions.size, sources().size)
    }

    /** A temporary file for an incoming extension file. Pass it to [install]. */
    fun newIncomingFile(): Path {
        Files.createDirectories(workDir)
        return Files.createTempFile(workDir, "incoming", ".bin")
    }

    /**
     * Installs the extension in [file] (consumed), replacing an installed version of the
     * same package.
     */
    suspend fun install(
        file: Path,
        origin: ExtensionRecord.Origin,
        expectations: Expectations = Expectations(),
        hint: ExtensionDescriptor? = null,
        contentWarning: ExtensionRecord.ContentWarning? = null,
    ): InstalledExtension {
        val prepared =
            try {
                inspector.prepare(file, expectations, hint)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Files.deleteIfExists(file)
                throw e
            }
        return mutex.withLock {
            try {
                val descriptor = prepared.descriptor
                val record =
                    ExtensionRecord(
                        pkgName = descriptor.pkgName,
                        name = descriptor.name,
                        versionName = descriptor.versionName,
                        versionCode = descriptor.versionCode,
                        libVersion = descriptor.libVersion,
                        format = prepared.format,
                        classNames = descriptor.classNames,
                        nsfw = descriptor.nsfw,
                        contentWarning = contentWarning ?: descriptor.contentWarning,
                        signingFingerprint = prepared.signingFingerprint,
                        sha256 = prepared.sha256,
                        origin = origin,
                        installedAt = clock().toString(),
                    )
                unload(record.pkgName)
                val extDir = Files.createDirectories(dir.resolve(record.pkgName))
                Files.move(prepared.jar, jarPath(extDir), StandardCopyOption.REPLACE_EXISTING)
                recordFile(extDir).write(record)
                log.info("Installed {} {} ({})", record.pkgName, record.versionName, record.format)
                load(record)
            } finally {
                Files.deleteIfExists(prepared.jar)
            }
        }
    }

    suspend fun uninstall(pkgName: String): List<HostedSource> =
        mutex.withLock {
            val removed = get(pkgName).sources.orEmpty()
            unload(pkgName)
            dir.resolve(PackageNames.require(pkgName)).toFile().deleteRecursively()
            log.info("Uninstalled {}", pkgName)
            removed
        }

    suspend fun reload(pkgName: String): InstalledExtension =
        mutex.withLock {
            val record = get(pkgName).record
            unload(pkgName)
            load(record)
        }

    fun close() {
        extensions.keys.toList().forEach(::unload)
    }

    private fun load(record: ExtensionRecord): InstalledExtension {
        val extDir = dir.resolve(record.pkgName)
        var classLoader: ExtensionClassLoader? = null
        val installed =
            try {
                classLoader = ExtensionClassLoader(jarPath(extDir), host, record.pkgName, transform)
                val sources = instantiator.instantiate(classLoader, record, jarPath(extDir))
                checkIds(record, sources)
                InstalledExtension(record, sources, null, classLoader)
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Throwable,
            ) {
                // Extensions are untrusted code: anything can go wrong, including linkage errors.
                if (e is VirtualMachineError && e !is OutOfMemoryError && e !is StackOverflowError) throw e
                classLoader?.close()
                val message = describe(e)
                log.warn("Failed to load {}: {}", record.pkgName, message)
                InstalledExtension(record, null, message, null)
            }
        extensions[record.pkgName] = installed
        installed.sources?.let { sources -> listeners.forEach { it.onSourcesAdded(sources) } }
        return installed
    }

    private fun checkIds(
        record: ExtensionRecord,
        sources: List<HostedSource>,
    ) {
        val taken =
            extensions
                .filterKeys { it != record.pkgName }
                .values
                .flatMap { it.sources.orEmpty() }
                .associateBy { it.id }
        sources.forEach { source ->
            taken[source.id]?.let { other ->
                throw ExtensionException("source id ${source.id} ('${source.name}') is already used by '${other.name}'")
            }
        }
        val duplicates = sources.groupBy { it.id }.filterValues { it.size > 1 }.keys
        if (duplicates.isNotEmpty()) throw ExtensionException("the extension has duplicate source ids: $duplicates")
    }

    private fun unload(pkgName: String) {
        val previous = extensions.remove(pkgName) ?: return
        previous.sources?.let { sources -> listeners.forEach { it.onSourcesRemoved(sources) } }
        previous.classLoader?.close()
    }

    private fun cleanWorkDir() {
        if (Files.exists(workDir)) workDir.toFile().deleteRecursively()
        Files.createDirectories(workDir)
    }

    private fun jarPath(extDir: Path) = extDir.resolve("extension.jar")

    private fun recordFile(extDir: Path) = JsonFile(extDir.resolve("extension.json"), ExtensionRecord.serializer())

    private fun describe(e: Throwable): String {
        val root = generateSequence(e) { it.cause }.last()
        val text = "${e.javaClass.simpleName}: ${e.message}"
        return if (root === e) text else "$text (caused by ${root.javaClass.simpleName}: ${root.message})"
    }
}
