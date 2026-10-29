// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.storage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * One JSON document on disk. Writes go through [DurableFiles], so a crash leaves either
 * the old file or the new one, never a half-written or empty one.
 */
class JsonFile<T>(
    val path: Path,
    private val serializer: KSerializer<T>,
    /** Restrict the file to its owner, for files that hold hashes or secrets. */
    private val private: Boolean = false,
    private val clock: () -> Instant = Instant::now,
) {
    /**
     * The stored value, or null when there is none. A file that can't be parsed (damaged
     * by something outside the service) is set aside next to the original and treated as
     * missing, so one bad file can't keep the service from starting.
     */
    fun read(): T? {
        if (!Files.exists(path)) return null
        return try {
            json.decodeFromString(serializer, Files.readString(path))
        } catch (e: SerializationException) {
            setAside(e)
        } catch (e: CharacterCodingException) {
            setAside(e)
        }
    }

    fun write(value: T) {
        DurableFiles.write(path, json.encodeToString(serializer, value).toByteArray(), private)
    }

    fun delete() {
        Files.deleteIfExists(path)
    }

    private fun setAside(error: Exception): T? {
        val stamp = clock().truncatedTo(ChronoUnit.SECONDS).toString().replace(":", "")
        val aside = path.resolveSibling("${path.fileName}.corrupt-$stamp")
        Files.move(path, aside, StandardCopyOption.REPLACE_EXISTING)
        // Only the error's type: its message quotes the file, which may hold secrets.
        log.error("Unreadable {} ({}); kept it as {} and continuing without it", path, error.javaClass.simpleName, aside.fileName)
        return null
    }

    companion object {
        private val log = LoggerFactory.getLogger(JsonFile::class.java)

        val json =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}
