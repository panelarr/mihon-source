// SPDX-License-Identifier: GPL-3.0-or-later

// Search filters: turns the filters of GET /v1/sources/{id}/filters into form state, and
// form state back into the FilterValue list of a search. Only filters the user changed
// from their defaults are sent; the source applies its defaults to the rest.
import type { Filter, FilterValue, TriState } from "../api/types";

export type FilterKind = "header" | "separator" | "text" | "checkbox" | "triState" | "select" | "sort" | "group";

export type SetTriState = Exclude<TriState, "TRI_STATE_UNSPECIFIED">;
export type SortValue = { index: number; ascending: boolean } | null;

export type FieldValue =
  | { kind: "text"; value: string }
  | { kind: "checkbox"; value: boolean }
  | { kind: "triState"; value: SetTriState }
  | { kind: "select"; value: number }
  | { kind: "sort"; value: SortValue };

/** Form state: values by filter path (see pathKey). Missing entries mean "the default". */
export type FilterState = Record<string, FieldValue>;

const kinds: readonly FilterKind[] = ["header", "separator", "text", "checkbox", "triState", "select", "sort", "group"];

/** Which oneof member a filter carries. Proto3 JSON leaves out empty fields, not the member. */
export function filterKind(filter: Filter): FilterKind | null {
  for (const kind of kinds) {
    if (filter[kind] !== undefined && filter[kind] !== null) return kind;
  }
  return null;
}

export function pathKey(path: readonly number[]): string {
  return path.join(".");
}

function triState(value: TriState | undefined): SetTriState {
  return value === undefined || value === "TRI_STATE_UNSPECIFIED" ? "TRI_STATE_IGNORE" : value;
}

/** The default value of a filter as the source reported it, or null for filters without a value. */
export function defaultValue(filter: Filter): FieldValue | null {
  switch (filterKind(filter)) {
    case "text":
      return { kind: "text", value: filter.text?.value ?? "" };
    case "checkbox":
      return { kind: "checkbox", value: filter.checkbox?.value ?? false };
    case "triState":
      return { kind: "triState", value: triState(filter.triState?.value) };
    case "select":
      return { kind: "select", value: filter.select?.selected ?? 0 };
    case "sort": {
      const selection = filter.sort?.selection;
      return {
        kind: "sort",
        value: selection ? { index: selection.index ?? 0, ascending: selection.ascending ?? false } : null,
      };
    }
    default:
      return null;
  }
}

type Visit = (filter: Filter, path: number[]) => void;

/** Visits every filter, children of groups included, with its path. */
export function walkFilters(filters: readonly Filter[], visit: Visit, parent: number[] = []): void {
  filters.forEach((filter, index) => {
    const path = [...parent, index];
    visit(filter, path);
    if (filterKind(filter) === "group") walkFilters(filter.group?.filters ?? [], visit, path);
  });
}

export function defaultFilterState(filters: readonly Filter[]): FilterState {
  const state: FilterState = {};
  walkFilters(filters, (filter, path) => {
    const value = defaultValue(filter);
    if (value) state[pathKey(path)] = value;
  });
  return state;
}

function sameValue(a: FieldValue, b: FieldValue): boolean {
  if (a.kind !== b.kind) return false;
  if (a.kind === "sort" && b.kind === "sort") {
    if (a.value === null || b.value === null) return a.value === b.value;
    return a.value.index === b.value.index && a.value.ascending === b.value.ascending;
  }
  return a.value === b.value;
}

function toFilterValue(path: number[], value: FieldValue): FilterValue | null {
  switch (value.kind) {
    case "text":
      return { path, text: value.value };
    case "checkbox":
      return { path, checkbox: value.value };
    case "triState":
      return { path, triState: value.value };
    case "select":
      return { path, select: value.value };
    case "sort":
      // A sort can't be set back to "none": leaving it out keeps the source's default.
      return value.value ? { path, sort: { index: value.value.index, ascending: value.value.ascending } } : null;
  }
}

/** The FilterValues of a search: one per filter whose value differs from its default. */
export function toFilterValues(filters: readonly Filter[], state: FilterState): FilterValue[] {
  const values: FilterValue[] = [];
  walkFilters(filters, (filter, path) => {
    const initial = defaultValue(filter);
    const current = state[pathKey(path)];
    // State left over from different filters (e.g. after the source changed them) is ignored.
    if (!initial || !current || current.kind !== initial.kind || sameValue(initial, current)) return;
    const value = toFilterValue(path, current);
    if (value) values.push(value);
  });
  return values;
}

/** Cycles a tri-state checkbox: ignore → include → exclude → ignore. */
export function nextTriState(value: SetTriState): SetTriState {
  if (value === "TRI_STATE_IGNORE") return "TRI_STATE_INCLUDE";
  if (value === "TRI_STATE_INCLUDE") return "TRI_STATE_EXCLUDE";
  return "TRI_STATE_IGNORE";
}
