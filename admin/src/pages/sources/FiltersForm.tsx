// SPDX-License-Identifier: GPL-3.0-or-later

import type { Filter } from "../../api/types";
import {
  defaultValue,
  type FieldValue,
  type FilterState,
  filterKind,
  pathKey,
  type SetTriState,
  type SortValue,
} from "../../lib/filters";

type Change = (key: string, value: FieldValue) => void;

/** The search filter form generated from GET /v1/sources/{id}/filters. */
export function FiltersForm({
  filters,
  state,
  onChange,
}: {
  filters: readonly Filter[];
  state: FilterState;
  onChange: Change;
}) {
  return (
    <div className="filters">
      {filters.map((filter, index) => (
        <FilterInput
          // Filters have no ids: their position is their identity.
          key={index}
          filter={filter}
          path={[index]}
          state={state}
          onChange={onChange}
        />
      ))}
    </div>
  );
}

const triStateLabels: Record<SetTriState, string> = {
  TRI_STATE_IGNORE: "Any",
  TRI_STATE_INCLUDE: "Include",
  TRI_STATE_EXCLUDE: "Exclude",
};

function FilterInput({
  filter,
  path,
  state,
  onChange,
}: {
  filter: Filter;
  path: number[];
  state: FilterState;
  onChange: Change;
}) {
  const key = pathKey(path);
  const value = state[key] ?? defaultValue(filter);
  const name = filter.name || `Filter ${key}`;
  const id = `filter-${key}`;

  switch (filterKind(filter)) {
    case "header":
      return <h4 className="filter-header">{filter.name}</h4>;
    case "separator":
      return <hr className="filter-separator" />;
    case "text":
      return (
        <label className="filter-row" htmlFor={id}>
          <span>{name}</span>
          <input
            id={id}
            type="text"
            value={value?.kind === "text" ? value.value : ""}
            onChange={(event) => onChange(key, { kind: "text", value: event.target.value })}
          />
        </label>
      );
    case "checkbox":
      return (
        <label className="filter-check" htmlFor={id}>
          <input
            id={id}
            type="checkbox"
            checked={value?.kind === "checkbox" && value.value}
            onChange={(event) => onChange(key, { kind: "checkbox", value: event.target.checked })}
          />
          <span>{name}</span>
        </label>
      );
    case "triState": {
      const current = value?.kind === "triState" ? value.value : "TRI_STATE_IGNORE";
      return (
        <label className={`filter-row tri tri-${current.toLowerCase()}`} htmlFor={id}>
          <span>{name}</span>
          <select
            id={id}
            value={current}
            onChange={(event) => onChange(key, { kind: "triState", value: event.target.value as SetTriState })}
          >
            {(Object.keys(triStateLabels) as SetTriState[]).map((option) => (
              <option key={option} value={option}>
                {triStateLabels[option]}
              </option>
            ))}
          </select>
        </label>
      );
    }
    case "select":
      return (
        <label className="filter-row" htmlFor={id}>
          <span>{name}</span>
          <select
            id={id}
            value={value?.kind === "select" ? value.value : 0}
            onChange={(event) => onChange(key, { kind: "select", value: Number(event.target.value) })}
          >
            {(filter.select?.options ?? []).map((option, index) => (
              <option key={index} value={index}>
                {option}
              </option>
            ))}
          </select>
        </label>
      );
    case "sort": {
      const sort: SortValue = value?.kind === "sort" ? value.value : null;
      const set = (next: SortValue) => onChange(key, { kind: "sort", value: next });
      return (
        <div className="filter-row">
          <label htmlFor={id}>{name}</label>
          <span className="inline">
            <select
              id={id}
              value={sort ? String(sort.index) : ""}
              onChange={(event) =>
                set(
                  event.target.value === ""
                    ? null
                    : { index: Number(event.target.value), ascending: sort?.ascending ?? false },
                )
              }
            >
              {sort === null ? <option value="">Default</option> : null}
              {(filter.sort?.options ?? []).map((option, index) => (
                <option key={index} value={index}>
                  {option}
                </option>
              ))}
            </select>
            <label className="filter-check">
              <input
                type="checkbox"
                disabled={sort === null}
                checked={sort?.ascending ?? false}
                onChange={(event) => sort && set({ index: sort.index, ascending: event.target.checked })}
              />
              <span>Ascending</span>
            </label>
          </span>
        </div>
      );
    }
    case "group":
      return (
        <details className="filter-group">
          <summary>{name}</summary>
          <div className="filter-group-body">
            {(filter.group?.filters ?? []).map((child, index) => (
              <FilterInput key={index} filter={child} path={[...path, index]} state={state} onChange={onChange} />
            ))}
          </div>
        </details>
      );
    default:
      return <p className="muted small">{name}: unsupported filter type</p>;
  }
}
