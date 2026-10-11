// SPDX-License-Identifier: GPL-3.0-or-later

import { useQuery } from "@tanstack/react-query";
import { type FormEvent, useMemo, useState } from "react";
import { api, unwrap } from "../../api/client";
import type { FilterValue } from "../../api/types";
import { ErrorBox, Loading } from "../../components/ui";
import { type FieldValue, type FilterState, toFilterValues } from "../../lib/filters";
import { FiltersForm } from "./FiltersForm";
import { filtersQuery } from "./queries";
import { BrowseList } from "./SeriesGrid";

type Submitted = { query: string; filters: FilterValue[] };

export function SearchPanel({ sourceId }: { sourceId: string }) {
  const filters = useQuery(filtersQuery(sourceId));
  const [query, setQuery] = useState("");
  // Only changed filters are kept; the rest show their defaults.
  const [state, setState] = useState<FilterState>({});
  const [submitted, setSubmitted] = useState<Submitted | null>(null);

  const changes = useMemo(() => toFilterValues(filters.data ?? [], state), [filters.data, state]);

  const onChange = (key: string, value: FieldValue) => setState((prev) => ({ ...prev, [key]: value }));

  const submit = (event: FormEvent) => {
    event.preventDefault();
    setSubmitted({ query: query.trim(), filters: changes });
  };

  return (
    <div className="search-layout">
      <form className="search-form card" onSubmit={submit}>
        <label className="field">
          <span className="field-label">Query</span>
          <input type="search" value={query} onChange={(event) => setQuery(event.target.value)} />
        </label>
        {filters.isPending ? <Loading label="Loading filters…" /> : null}
        <ErrorBox error={filters.error} title="Filters" onRetry={() => filters.refetch()} />
        {filters.data && filters.data.length > 0 ? (
          <>
            <h3 className="small-heading">
              Filters {changes.length > 0 ? <span className="muted">({changes.length} changed)</span> : null}
            </h3>
            <FiltersForm filters={filters.data} state={state} onChange={onChange} />
          </>
        ) : null}
        <div className="actions">
          <button type="submit" className="primary">
            Search
          </button>
          <button type="button" onClick={() => setState({})} disabled={changes.length === 0}>
            Reset filters
          </button>
        </div>
      </form>

      <div className="search-results">
        {submitted ? (
          <BrowseList
            sourceId={sourceId}
            queryKey={["sources", sourceId, "search", submitted]}
            fetchPage={(page) =>
              unwrap(
                api.POST("/v1/sources/{sourceId}/search", {
                  params: { path: { sourceId } },
                  body: { page, query: submitted.query, filters: submitted.filters },
                }),
              )
            }
          />
        ) : (
          <p className="muted">Enter a query or set filters, then search.</p>
        )}
      </div>
    </div>
  );
}
