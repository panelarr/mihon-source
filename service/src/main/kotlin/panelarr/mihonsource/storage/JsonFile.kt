package panelarr.mihonsource.storage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * One JSON document on disk. Writes go to a temporary file that is then moved over the
 * old one, so a crash never leaves a half-written file behind.
 */
class JsonFile<T>(
    val path: Path,
    private val serializer: KSerializer<T>,
    /** Restrict the file to its owner, for files that hold hashes or secrets. */
    private val private: Boolean = false,
) {
    fun read(): T? {
        if (!Files.exists(path)) return null
        return json.decodeFromString(serializer, Files.readString(path))
    }

    fun write(value: T) {
        Files.createDirectories(path.parent)
        val tmp = path.resolveSibling(".${path.fileName}.tmp")
        Files.writeString(tmp, json.encodeToString(serializer, value))
        if (private) restrictToOwner(tmp)
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    fun delete() {
        Files.deleteIfExists(path)
    }

    private fun restrictToOwner(file: Path) {
        runCatching { Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------")) }
    }

    companion object {
        val json =
            Json {
                prettyPrint = true
                ignoreUnknownKeys = true
                encodeDefaults = true
            }
    }
}
