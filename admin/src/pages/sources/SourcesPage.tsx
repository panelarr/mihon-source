// SPDX-License-Identifier: GPL-3.0-or-later

import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { useMemo } from "react";
import { AuthIcon } from "../../components/AuthIcon";
import { Empty, ErrorBox, Field, Loading, Mono, PageHeader } from "../../components/ui";
import { languageLabel } from "../../lib/format";
import { sourcesQuery } from "./queries";
import { CapabilityBadges, ContentRatingBadge } from "./SourceBadges";

const route = getRouteApi("/_app/sources");

export function SourcesPage() {
  const search = route.useSearch();
  const navigate = route.useNavigate();
  const sources = useQuery(sourcesQuery());

  const languages = useMemo(
    () => [...new Set((sources.data ?? []).map((source) => source.lang ?? ""))].filter(Boolean).sort(),
    [sources.data],
  );

  const visible = useMemo(() => {
    const query = (search.q ?? "").toLowerCase();
    return (sources.data ?? [])
      .filter((source) => !search.lang || source.lang === search.lang)
      .filter(
        (source) => !query || (source.name ?? "").toLowerCase().includes(query) || (source.id ?? "").includes(query),
      )
      .sort((a, b) => (a.name ?? "").localeCompare(b.name ?? "") || (a.lang ?? "").localeCompare(b.lang ?? ""));
  }, [sources.data, search.q, search.lang]);

  const setSearch = (patch: { q?: string; lang?: string }) =>
    navigate({ search: (prev) => ({ ...prev, ...patch }), replace: true });

  return (
    <>
      <PageHeader title="Sources" />
      <div className="toolbar">
        <Field label="Name or ID">
          <input
            type="search"
            value={search.q ?? ""}
            onChange={(event) => setSearch({ q: event.target.value || undefined })}
            placeholder="Filter sources"
          />
        </Field>
        <Field label="Language">
          <select value={search.lang ?? ""} onChange={(event) => setSearch({ lang: event.target.value || undefined })}>
            <option value="">All languages</option>
            {languages.map((lang) => (
              <option key={lang} value={lang}>
                {languageLabel(lang)}
              </option>
            ))}
          </select>
        </Field>
      </div>

      {sources.isPending ? <Loading /> : null}
      <ErrorBox error={sources.error} onRetry={() => sources.refetch()} />
      {sources.data && sources.data.length === 0 ? (
        <Empty>
          No sources yet. Sources come from extensions: <Link to="/extensions">install one</Link> or add a{" "}
          <Link to="/stores">store</Link>.
        </Empty>
      ) : null}
      {sources.data && sources.data.length > 0 && visible.length === 0 ? (
        <Empty>No source matches the filter.</Empty>
      ) : null}

      {visible.length > 0 ? (
        <table className="table">
          <thead>
            <tr>
              <th aria-label="Icon" />
              <th>Name</th>
              <th>Language</th>
              <th>ID</th>
              <th>Rating</th>
              <th>Capabilities</th>
            </tr>
          </thead>
          <tbody>
            {visible.map((source) => (
              <tr key={source.id}>
                <td className="icon-cell">
                  {/* The service serves icons at a path of its own; other URLs go through the image endpoint. */}
                  <AuthIcon
                    path={
                      source.iconUrl?.startsWith("/")
                        ? source.iconUrl
                        : source.iconUrl && source.id
                          ? `/v1/sources/${encodeURIComponent(source.id)}/image?ref=${encodeURIComponent(source.iconUrl)}`
                          : undefined
                    }
                    label={source.name ?? ""}
                  />
                </td>
                <td>
                  <Link to="/sources/$sourceId" params={{ sourceId: source.id ?? "" }}>
                    {source.name || source.id}
                  </Link>
                </td>
                <td>{languageLabel(source.lang)}</td>
                <td>
                  <Mono>{source.id}</Mono>
                </td>
                <td>
                  <ContentRatingBadge rating={source.contentRating} />
                </td>
                <td>
                  <CapabilityBadges source={source} />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </>
  );
}
