// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.content.SharedPreferences
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [SharedPreferences] stored as a JSON file. Each value keeps its type, because Android
 * code relies on getting back exactly the type it stored.
 *
 * File format: `{"key": {"type": "string|int|long|float|boolean|stringSet", "value": ...}}`.
 *
 * Saves are flushed to disk before the new file replaces the old one, so a crash or power
 * cut can't leave a file of zeros behind. A file that can't be read anyway is set aside
 * next to the original, the preferences start empty, and [onDamaged] is told.
 */
@Suppress("TooManyFunctions") // one override per SharedPreferences member
class JsonSharedPreferences(
    private val file: Path,
    private val onDamaged: (file: Path, keptAs: Path) -> Unit = { _, _ -> },
) : SharedPreferences {
    private val values: MutableMap<String, Any> = load()
    private val listeners = CopyOnWriteArrayList<SharedPreferences.OnSharedPreferenceChangeListener>()

    @Synchronized
    override fun getAll(): Map<String, *> = values.mapValues { (_, v) -> if (v is Set<*>) HashSet(v) else v }

    override fun getString(
        key: String,
        defValue: String?,
    ): String? = get(key) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(
        key: String,
        defValues: Set<String>?,
    ): Set<String>? = (get<Set<String>>(key))?.let { HashSet(it) } ?: defValues

    override fun getInt(
        key: String,
        defValue: Int,
    ): Int = get(key) ?: defValue

    override fun getLong(
        key: String,
        defValue: Long,
    ): Long = get(key) ?: defValue

    override fun getFloat(
        key: String,
        defValue: Float,
    ): Float = get(key) ?: defValue

    override fun getBoolean(
        key: String,
        defValue: Boolean,
    ): Boolean = get(key) ?: defValue

    @Synchronized
    override fun contains(key: String): Boolean = key in values

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners -= listener
    }

    /** Throws ClassCastException on a type mismatch, as Android does. */
    @Synchronized
    private inline fun <reified T> get(key: String): T? = values[key]?.let { it as T }

    private inner class Editor : SharedPreferences.Editor {
        private val changes = linkedMapOf<String, Any?>()
        private var clear = false

        override fun putString(
            key: String,
            value: String?,
        ) = put(key, value)

        override fun putStringSet(
            key: String,
            values: Set<String>?,
        ) = put(key, values?.let { HashSet(it) })

        override fun putInt(
            key: String,
            value: Int,
        ) = put(key, value)

        override fun putLong(
            key: String,
            value: Long,
        ) = put(key, value)

        override fun putFloat(
            key: String,
            value: Float,
        ) = put(key, value)

        override fun putBoolean(
            key: String,
            value: Boolean,
        ) = put(key, value)

        override fun remove(key: String) = put(key, null)

        override fun clear(): SharedPreferences.Editor {
            clear = true
            return this
        }

        private fun put(
            key: String,
            value: Any?,
        ): SharedPreferences.Editor {
            synchronized(this) { changes[key] = value }
            return this
        }

        override fun commit(): Boolean {
            val changed = apply(changes.toMap(), clear)
            notify(changed)
            return true
        }

        override fun apply() {
            commit()
        }
    }

    @Synchronized
    private fun apply(
        changes: Map<String, Any?>,
        clear: Boolean,
    ): List<String> {
        val changed = mutableListOf<String>()
        if (clear) {
            changed += values.keys
            values.clear()
        }
        changes.forEach { (key, value) ->
            if (value == null) {
                if (values.remove(key) != null) changed += key
            } else if (values[key] != value) {
                values[key] = value
                changed += key
            }
        }
        if (changed.isNotEmpty() || clear) save()
        return changed.distinct()
    }

    private fun notify(keys: List<String>) {
        keys.forEach { key -> listeners.forEach { it.onSharedPreferenceChanged(this, key) } }
    }

    private fun save() {
        val json =
            buildJsonObject {
                values.toSortedMap().forEach { (key, value) -> put(key, encode(value)) }
            }
        Files.createDirectories(file.parent)
        val tmp = file.resolveSibling(".${file.fileName}.tmp")
        FileChannel
            .open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
            .use { channel ->
                val buffer = ByteBuffer.wrap(JSON.encodeToString(JsonObject.serializer(), json).toByteArray())
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        syncDirectory(file.parent)
    }

    private fun load(): MutableMap<String, Any> {
        if (!Files.exists(file)) return linkedMapOf()
        val root =
            try {
                JSON.parseToJsonElement(Files.readString(file)).jsonObject
            } catch (_: IllegalArgumentException) {
                // Not JSON, or not an object (kotlinx's parse errors are IllegalArgumentExceptions).
                return setAside()
            } catch (_: CharacterCodingException) {
                return setAside()
            }
        return root.mapNotNullTo(mutableListOf()) { (key, element) -> decode(element)?.let { key to it } }.toMap(linkedMapOf())
    }

    private fun setAside(): MutableMap<String, Any> {
        val stamp =
            Instant
                .now()
                .truncatedTo(ChronoUnit.SECONDS)
                .toString()
                .replace(":", "")
        val aside = file.resolveSibling("${file.fileName}.corrupt-$stamp")
        Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING)
        onDamaged(file, aside)
        return linkedMapOf()
    }

    /** Makes the rename survive a crash too. Not every platform can open a directory for this. */
    private fun syncDirectory(dir: Path) {
        runCatching { FileChannel.open(dir, StandardOpenOption.READ).use { it.force(true) } }
    }

    companion object {
        private val JSON = Json { prettyPrint = true }

        fun encode(value: Any): JsonObject {
            val (type, json) =
                when (value) {
                    is String -> "string" to JsonPrimitive(value)
                    is Int -> "int" to JsonPrimitive(value)
                    is Long -> "long" to JsonPrimitive(value)
                    is Float -> "float" to JsonPrimitive(value)
                    is Boolean -> "boolean" to JsonPrimitive(value)
                    is Set<*> -> "stringSet" to JsonArray(value.map { JsonPrimitive(it as String) })
                    else -> error("unsupported preference type ${value.javaClass}")
                }
            return buildJsonObject {
                put("type", JsonPrimitive(type))
                put("value", json)
            }
        }

        fun decode(element: JsonElement): Any? {
            val obj = element as? JsonObject ?: return null
            val value = obj["value"] ?: return null
            return when (obj["type"]?.jsonPrimitive?.content) {
                "string" -> value.jsonPrimitive.content
                "int" -> value.jsonPrimitive.int
                "long" -> value.jsonPrimitive.long
                "float" -> value.jsonPrimitive.float
                "boolean" -> value.jsonPrimitive.booleanOrNull ?: value.jsonPrimitive.boolean
                "stringSet" -> value.jsonArray.map { it.jsonPrimitive.content }.toHashSet()
                else -> null
            }
        }
    }
}
