// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import android.content.SharedPreferences
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.ListPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceScreen
import androidx.preference.TwoStatePreference
import com.google.protobuf.ListValue
import com.google.protobuf.NullValue
import com.google.protobuf.Struct
import com.google.protobuf.Value
import eu.kanade.tachiyomi.source.ConfigurableSource
import panelarr.mihonsource.compat.AndroidHost

/**
 * A source's settings, as declared by its preference screen and stored in its
 * SharedPreferences ("source_<id>", like Mihon). Exposed as a JSON Schema plus values.
 */
class SourceSettings(
    private val source: MihonSource,
) {
    private val configurable = source.source as? ConfigurableSource
    private val prefs: SharedPreferences = AndroidHost.sharedPreferences("source_${source.id}")

    /** The preferences the source declares, in display order. Built fresh, as Android does. */
    private fun preferences(): List<Preference> {
        val configurable = configurable ?: return emptyList()
        val screen = PreferenceScreen(AndroidHost.application)
        configurable.setupPreferenceScreen(screen)
        return screen.preferences.filter { !it.key.isNullOrEmpty() }
    }

    fun schema(): Struct {
        val preferences = preferences().filter { it.isVisible }
        val properties = Struct.newBuilder()
        preferences.forEach { pref ->
            propertySchema(pref)?.let { schema ->
                // Each property also carries its position, the convention every
                // Panelarr form reads; the object-level list below stays for older readers.
                val position = Value.newBuilder().setNumberValue((properties.fieldsCount + 1).toDouble()).build()
                val ordered = schema.toBuilder().apply { structValueBuilder.putFields("x-order", position) }.build()
                properties.putFields(pref.key, ordered)
            }
        }
        return struct {
            putFields("\$schema", string("https://json-schema.org/draft/2020-12/schema"))
            putFields("type", string("object"))
            putFields("title", string(source.name))
            putFields("properties", Value.newBuilder().setStructValue(properties).build())
            // Struct fields are a map, so the display order travels separately.
            putFields("x-order", list(preferences.filter { properties.containsFields(it.key) }.map { string(it.key) }))
            putFields("additionalProperties", Value.newBuilder().setBoolValue(false).build())
        }
    }

    fun values(): Struct =
        struct {
            preferences().forEach { pref -> current(pref)?.let { putFields(pref.key, it) } }
        }

    /** Validates and stores [changes]; keys left out keep their values. Returns all values. */
    fun set(changes: Struct): Struct {
        val byKey = preferences().associateBy { it.key }
        val updates = mutableListOf<Pair<Preference, Any>>()
        changes.fieldsMap.forEach { (key, value) ->
            val pref = byKey[key] ?: throw IllegalArgumentException("unknown setting '$key'")
            require(pref.isEnabled) { "setting '$key' can't be changed" }
            val converted = convert(pref, value)
            // As on Android, the source may veto a change.
            require(pref.callChangeListener(converted)) { "the source rejected the value of '$key'" }
            updates += pref to converted
        }
        val editor = prefs.edit()
        updates.forEach { (pref, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(pref.key, value)
                is String -> editor.putString(pref.key, value)
                is Set<*> -> editor.putStringSet(pref.key, value.map { it.toString() }.toSet())
            }
        }
        editor.commit()
        return values()
    }

    private fun propertySchema(pref: Preference): Value? {
        val base =
            when (pref) {
                is TwoStatePreference -> {
                    struct { putFields("type", string("boolean")) }
                }

                is ListPreference -> {
                    struct {
                        putFields("type", string("string"))
                        putFields("enum", list(pref.entryValues.orEmpty().map { string(it.toString()) }))
                        putFields("x-enumNames", list(pref.entries.orEmpty().map { string(it.toString()) }))
                    }
                }

                is MultiSelectListPreference -> {
                    struct {
                        putFields("type", string("array"))
                        putFields("uniqueItems", Value.newBuilder().setBoolValue(true).build())
                        putFields(
                            "items",
                            Value
                                .newBuilder()
                                .setStructValue(
                                    struct {
                                        putFields("type", string("string"))
                                        putFields("enum", list(pref.entryValues.orEmpty().map { string(it.toString()) }))
                                        putFields("x-enumNames", list(pref.entries.orEmpty().map { string(it.toString()) }))
                                    },
                                ).build(),
                        )
                    }
                }

                is EditTextPreference -> {
                    struct { putFields("type", string("string")) }
                }

                else -> {
                    return null
                }
            }.toBuilder()
        pref.title?.let { base.putFields("title", string(it.toString())) }
        description(pref)?.let { base.putFields("description", string(it)) }
        defaultValue(pref)?.let { base.putFields("default", it) }
        if (!pref.isEnabled) base.putFields("readOnly", Value.newBuilder().setBoolValue(true).build())
        return Value.newBuilder().setStructValue(base).build()
    }

    private fun description(pref: Preference): String? {
        val summary = pref.summary?.toString()?.takeIf { it.isNotBlank() && !it.contains("%s") }
        val dialog = (pref as? DialogPreference)?.dialogMessage?.toString()?.takeIf { it.isNotBlank() }
        return listOfNotNull(summary, dialog).distinct().joinToString("\n").ifEmpty { null }
    }

    private fun defaultValue(pref: Preference): Value? =
        when (val value = pref.defaultValue) {
            null -> null
            is Boolean -> Value.newBuilder().setBoolValue(value).build()
            is Set<*> -> list(value.map { string(it.toString()) })
            else -> string(value.toString())
        }

    private fun current(pref: Preference): Value? {
        val key = pref.key
        return when (pref) {
            is TwoStatePreference -> {
                if (prefs.contains(key)) Value.newBuilder().setBoolValue(prefs.getBoolean(key, false)).build() else defaultValue(pref)
            }

            is MultiSelectListPreference -> {
                prefs.getStringSet(key, null)?.let { set -> list(set.sorted().map(::string)) } ?: defaultValue(pref)
            }

            is ListPreference, is EditTextPreference -> {
                prefs.getString(key, null)?.let(::string) ?: defaultValue(pref)
            }

            else -> {
                null
            }
        } ?: Value.newBuilder().setNullValue(NullValue.NULL_VALUE).build()
    }

    private fun convert(
        pref: Preference,
        value: Value,
    ): Any {
        val key = pref.key
        return when (pref) {
            is TwoStatePreference -> {
                require(value.hasBoolValue()) { "'$key' must be a boolean" }
                value.boolValue
            }

            is ListPreference -> {
                require(value.hasStringValue()) { "'$key' must be a string" }
                val allowed = pref.entryValues.orEmpty().map { it.toString() }
                require(value.stringValue in allowed) { "'$key' must be one of ${allowed.joinToString()}" }
                value.stringValue
            }

            is MultiSelectListPreference -> {
                require(
                    value.hasListValue() && value.listValue.valuesList.all { it.hasStringValue() },
                ) { "'$key' must be a list of strings" }
                val allowed =
                    pref.entryValues
                        .orEmpty()
                        .map { it.toString() }
                        .toSet()
                val chosen =
                    value.listValue.valuesList
                        .map { it.stringValue }
                        .toSet()
                require(allowed.containsAll(chosen)) { "'$key' only takes ${allowed.joinToString()}" }
                chosen
            }

            is EditTextPreference -> {
                require(value.hasStringValue()) { "'$key' must be a string" }
                value.stringValue
            }

            else -> {
                throw IllegalArgumentException("setting '$key' can't be changed")
            }
        }
    }

    private fun struct(block: Struct.Builder.() -> Unit): Struct = Struct.newBuilder().apply(block).build()

    private fun string(value: String): Value = Value.newBuilder().setStringValue(value).build()

    private fun list(values: List<Value>): Value = Value.newBuilder().setListValue(ListValue.newBuilder().addAllValues(values)).build()

    private fun Struct.Builder.putFields(
        key: String,
        value: Struct,
    ): Struct.Builder = putFields(key, Value.newBuilder().setStructValue(value).build())
}
