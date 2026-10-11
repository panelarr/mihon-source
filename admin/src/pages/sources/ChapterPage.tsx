// SPDX-License-Identifier: GPL-3.0-or-later

import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { AuthImage } from "../../components/AuthImage";
import { Empty, ErrorBox, Loading, PageHeader } from "../../components/ui";
import { pagesQuery, sourceQuery } from "./queries";

const route = getRouteApi("/_app/sources/$sourceId/chapter");

/** A preview of a chapter's pages, each loaded through the image endpoint. */
export function ChapterPage() {
  const { sourceId } = route.useParams();
  const { key, series } = route.useSearch();
  const source = useQuery(sourceQuery(sourceId));
  const pages = useQuery({ ...pagesQuery(sourceId, key), enabled: key !== "" });

  if (key === "") return <ErrorBox error="No chapter key in the address." />;

  return (
    <>
      <p className="crumbs">
        <Link to="/sources">Sources</Link> /{" "}
        <Link to="/sources/$sourceId" params={{ sourceId }}>
          {source.data?.name || sourceId}
        </Link>{" "}
        /{" "}
        {series ? (
          <>
            <Link to="/sources/$sourceId/series" params={{ sourceId }} search={{ key: series }}>
              Series
            </Link>{" "}
            /
          </>
        ) : null}
      </p>
      <PageHeader title="Chapter preview" />
      <p className="muted small">
        Key: <code className="mono">{key}</code>
      </p>
      {pages.isPending ? <Loading label="Loading pages…" /> : null}
      <ErrorBox error={pages.error} onRetry={() => pages.refetch()} />
      {pages.data?.length === 0 ? <Empty>This chapter has no pages.</Empty> : null}
      <ol className="pages">
        {pages.data?.map((page, index) => (
          <li key={`${page.index}-${index}`}>
            <span className="muted small">Page {(page.index ?? index) + 1}</span>
            <AuthImage
              sourceId={sourceId}
              imageRef={page.ref || page.imageUrl}
              alt={`Page ${(page.index ?? index) + 1}`}
              className="page-image"
            />
          </li>
        ))}
      </ol>
    </>
  );
}
