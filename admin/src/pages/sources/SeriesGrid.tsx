// SPDX-License-Identifier: GPL-3.0-or-later

import { type InfiniteData, useInfiniteQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import type { BrowseResponse, Series } from "../../api/types";
import { AuthImage } from "../../components/AuthImage";
import { Empty, ErrorBox, Loading } from "../../components/ui";

/** A paged list of series (popular, latest or search results) with "Load more". */
export function BrowseList({
  sourceId,
  queryKey,
  fetchPage,
}: {
  sourceId: string;
  queryKey: readonly unknown[];
  fetchPage: (page: number) => Promise<BrowseResponse>;
}) {
  const query = useInfiniteQuery<BrowseResponse, Error, InfiniteData<BrowseResponse>, readonly unknown[], number>({
    queryKey,
    queryFn: ({ pageParam }) => fetchPage(pageParam),
    initialPageParam: 1,
    getNextPageParam: (last, all) => (last.hasNextPage ? all.length + 1 : undefined),
  });

  if (query.isPending) return <Loading />;
  // A failed next page keeps the pages already loaded.
  if (!query.data) return <ErrorBox error={query.error} onRetry={() => query.refetch()} />;

  const series = query.data.pages.flatMap((page) => page.series ?? []);
  return (
    <>
      {series.length === 0 ? <Empty>No series.</Empty> : <SeriesGrid sourceId={sourceId} series={series} />}
      <ErrorBox error={query.isFetchNextPageError ? query.error : null} title="Loading the next page failed" />
      <div className="pager">
        <span className="muted small">
          {series.length} series, {query.data.pages.length} page{query.data.pages.length === 1 ? "" : "s"}
        </span>
        {query.hasNextPage ? (
          <button type="button" onClick={() => query.fetchNextPage()} disabled={query.isFetchingNextPage}>
            {query.isFetchingNextPage ? "Loading…" : "Load more"}
          </button>
        ) : series.length > 0 ? (
          <span className="muted small">No more pages.</span>
        ) : null}
      </div>
    </>
  );
}

export function SeriesGrid({ sourceId, series }: { sourceId: string; series: Series[] }) {
  return (
    <ul className="series-grid">
      {series.map((item, index) => (
        // Keys are opaque and a source may repeat one across pages, so the index is part of the key.
        <li key={`${item.key}-${index}`}>
          <Link
            to="/sources/$sourceId/series"
            params={{ sourceId }}
            search={{ key: item.key ?? "" }}
            className="series-card"
          >
            <AuthImage sourceId={sourceId} imageRef={item.coverUrl} alt="" className="cover" />
            <span className="series-title">{item.title || item.key}</span>
          </Link>
        </li>
      ))}
    </ul>
  );
}
