// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import eu.kanade.tachiyomi.source.model.Filter
import eu.kanade.tachiyomi.source.model.FilterList
import panelarr.source.v1.FilterValue
import panelarr.source.v1.TriState
import panelarr.source.v1.checkboxFilter
import panelarr.source.v1.filter
import panelarr.source.v1.groupFilter
import panelarr.source.v1.headerFilter
import panelarr.source.v1.selectFilter
import panelarr.source.v1.separatorFilter
import panelarr.source.v1.sortFilter
import panelarr.source.v1.sortSelection
import panelarr.source.v1.textFilter
import panelarr.source.v1.triStateFilter
import panelarr.source.v1.Filter as ProtoFilter

/** Converts Mihon filter lists to `panelarr.source.v1` filters, and applies filter values back. */
object FilterMapping {
    fun filters(list: FilterList): List<ProtoFilter> = list.map(::filter)

    private fun filter(source: Filter<*>): ProtoFilter =
        filter {
            name = source.name
            when (source) {
                is Filter.Header -> {
                    header = headerFilter {}
                }

                is Filter.Separator -> {
                    separator = separatorFilter {}
                }

                is Filter.Text -> {
                    text = textFilter { value = source.state }
                }

                is Filter.CheckBox -> {
                    checkbox = checkboxFilter { value = source.state }
                }

                is Filter.TriState -> {
                    triState = triStateFilter { value = triState(source.state) }
                }

                is Filter.Select<*> -> {
                    select =
                        selectFilter {
                            options += source.values.map { it.toString() }
                            selected = source.state.coerceAtLeast(0)
                        }
                }

                is Filter.Sort -> {
                    sort =
                        sortFilter {
                            options += source.values.toList()
                            source.state?.let {
                                selection =
                                    sortSelection {
                                        index = it.index
                                        ascending = it.ascending
                                    }
                            }
                        }
                }

                is Filter.Group<*> -> {
                    group =
                        groupFilter {
                            filters += source.state.filterIsInstance<Filter<*>>().map(::filter)
                        }
                }
            }
        }

    private fun triState(value: Int): TriState =
        when (value) {
            Filter.TriState.STATE_INCLUDE -> TriState.TRI_STATE_INCLUDE
            Filter.TriState.STATE_EXCLUDE -> TriState.TRI_STATE_EXCLUDE
            else -> TriState.TRI_STATE_IGNORE
        }

    /** Applies [values] to a fresh [list] by position. Mismatched paths or types are errors. */
    fun applyFilters(
        list: FilterList,
        values: List<FilterValue>,
    ): FilterList {
        values.forEach { value -> apply(target(list, value.pathList), value) }
        return list
    }

    private fun target(
        list: FilterList,
        path: List<Int>,
    ): Filter<*> {
        require(path.size in 1..2) { "filter path must have one or two elements" }
        val where = path.joinToString("/")
        val top = list.getOrNull(path[0]) ?: throw IllegalArgumentException("no filter at $where")
        if (path.size == 1) return top
        val group = top as? Filter.Group<*> ?: throw IllegalArgumentException("filter ${path[0]} isn't a group")
        return group.state.getOrNull(path[1]) as? Filter<*> ?: throw IllegalArgumentException("no filter at $where")
    }

    private fun apply(
        target: Filter<*>,
        value: FilterValue,
    ) {
        fun mismatch(): Nothing = throw IllegalArgumentException("filter '${target.name}' doesn't take ${value.valueCase}")
        when (value.valueCase) {
            FilterValue.ValueCase.TEXT -> {
                (target as? Filter.Text ?: mismatch()).state = value.text
            }

            FilterValue.ValueCase.CHECKBOX -> {
                (target as? Filter.CheckBox ?: mismatch()).state = value.checkbox
            }

            FilterValue.ValueCase.TRI_STATE -> {
                (target as? Filter.TriState ?: mismatch()).state = triStateValue(value.triState)
            }

            FilterValue.ValueCase.SELECT -> {
                val select = target as? Filter.Select<*> ?: mismatch()
                require(value.select < select.values.size) { "option ${value.select} is out of range for '${select.name}'" }
                select.state = value.select
            }

            FilterValue.ValueCase.SORT -> {
                val sort = target as? Filter.Sort ?: mismatch()
                require(value.sort.index < sort.values.size) { "option ${value.sort.index} is out of range for '${sort.name}'" }
                sort.state = Filter.Sort.Selection(value.sort.index, value.sort.ascending)
            }

            else -> {
                throw IllegalArgumentException("filter value for '${target.name}' has no value")
            }
        }
    }

    private fun triStateValue(value: TriState): Int =
        when (value) {
            TriState.TRI_STATE_INCLUDE -> Filter.TriState.STATE_INCLUDE
            TriState.TRI_STATE_EXCLUDE -> Filter.TriState.STATE_EXCLUDE
            else -> Filter.TriState.STATE_IGNORE
        }
}
