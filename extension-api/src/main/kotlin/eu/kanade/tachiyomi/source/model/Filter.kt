// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.source.model

/** Search filters. Constructors and their default arguments are part of the binary interface. */
sealed class Filter<T>(
    val name: String,
    var state: T,
) {
    open class Header(
        name: String,
    ) : Filter<Any>(name, 0)

    open class Separator(
        name: String = "",
    ) : Filter<Any>(name, 0)

    abstract class Select<V>(
        name: String,
        val values: Array<V>,
        state: Int = 0,
    ) : Filter<Int>(name, state)

    abstract class Text(
        name: String,
        state: String = "",
    ) : Filter<String>(name, state)

    abstract class CheckBox(
        name: String,
        state: Boolean = false,
    ) : Filter<Boolean>(name, state)

    abstract class TriState(
        name: String,
        state: Int = STATE_IGNORE,
    ) : Filter<Int>(name, state) {
        fun isIgnored() = state == STATE_IGNORE

        fun isIncluded() = state == STATE_INCLUDE

        fun isExcluded() = state == STATE_EXCLUDE

        companion object {
            const val STATE_IGNORE = 0
            const val STATE_INCLUDE = 1
            const val STATE_EXCLUDE = 2
        }
    }

    abstract class Group<V>(
        name: String,
        state: List<V>,
    ) : Filter<List<V>>(name, state)

    abstract class Sort(
        name: String,
        val values: Array<String>,
        state: Selection? = null,
    ) : Filter<Sort.Selection?>(name, state) {
        data class Selection(
            val index: Int,
            val ascending: Boolean,
        )
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Filter<*>) return false
        return name == other.name && state == other.state
    }

    override fun hashCode(): Int = 31 * name.hashCode() + (state?.hashCode() ?: 0)

    override fun toString(): String = "${javaClass.simpleName}(name=$name, state=$state)"
}

data class FilterList(
    val list: List<Filter<*>>,
) : List<Filter<*>> by list {
    constructor(vararg fs: Filter<*>) : this(if (fs.isNotEmpty()) fs.asList() else emptyList())

    // Filter lists carry mutable state, so two lists are never treated as equal.
    override fun equals(other: Any?): Boolean = false

    override fun hashCode(): Int = list.hashCode()
}
