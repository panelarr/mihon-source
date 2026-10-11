// SPDX-License-Identifier: GPL-3.0-or-later

import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { AuthImage } from "../../components/AuthImage";
import { Badge, Empty, ErrorBox, Loading, PageHeader } from "../../components/ui";
import { enumLabel, formatDate } from "../../lib/format";
import { chaptersQuery, seriesQuery, sourceQuery } from "./queries";

const route = getRouteApi("/_app/sources/$sourceId/series");

export function SeriesPage() {
  const { sourceId } = route.useParams();
  const { key } = route.useSearch();
  const source = useQuery(sourceQuery(sourceId));
  const series = useQuery({ ...seriesQuery(sourceId, key), enabled: key !== "" });
  const chapters = useQuery({ ...chaptersQuery(sourceId, key), enabled: key !== "" });

  const crumbs = (
    <p className="crumbs">
      <Link to="/sources">Sources</Link> /{" "}
      <Link to="/sources/$sourceId" params={{ sourceId }}>
        {source.data?.name || sourceId}
      </Link>{" "}
      /
    </p>
  );

  if (key === "") return <ErrorBox error="No series key in the address." />;

  return (
    <>
      {crumbs}
      {series.isPending ? <Loading /> : null}
      <ErrorBox error={series.error} onRetry={() => series.refetch()} />
      {series.data ? (
        <>
          <PageHeader title={series.data.title || key}>
            {series.data.url ? (
              <a href={series.data.url} target="_blank" rel="noreferrer noopener">
                Open on the website ↗
              </a>
            ) : null}
          </PageHeader>
          <div className="series-details">
            <AuthImage sourceId={sourceId} imageRef={series.data.coverUrl} alt="Cover" className="cover cover-large" />
            <dl className="details">
              {series.data.altTitles?.length ? (
                <>
                  <dt>Also known as</dt>
                  <dd>{series.data.altTitles.join(" · ")}</dd>
                </>
              ) : null}
              <dt>Status</dt>
              <dd>{enumLabel(series.data.status, "SERIES_STATUS_")}</dd>
              <dt>Authors</dt>
              <dd>{series.data.authors?.join(", ") || "—"}</dd>
              <dt>Artists</dt>
              <dd>{series.data.artists?.join(", ") || "—"}</dd>
              <dt>Genres</dt>
              <dd>
                <div className="badges">
                  {series.data.genres?.length
                    ? series.data.genres.map((genre) => <Badge key={genre}>{genre}</Badge>)
                    : "—"}
                </div>
              </dd>
              <dt>Key</dt>
              <dd>
                <code className="mono">{key}</code>
              </dd>
            </dl>
          </div>
          {series.data.description ? <p className="description">{series.data.description}</p> : null}
        </>
      ) : null}

      <h2>Chapters {chapters.data ? <span className="muted">({chapters.data.length})</span> : null}</h2>
      {chapters.isPending ? <Loading label="Loading chapters…" /> : null}
      <ErrorBox error={chapters.error} onRetry={() => chapters.refetch()} />
      {chapters.data?.length === 0 ? <Empty>No chapters.</Empty> : null}
      {chapters.data && chapters.data.length > 0 ? (
        <table className="table">
          <thead>
            <tr>
              <th>Name</th>
              <th>Number</th>
              <th>Volume</th>
              <th>Scanlator</th>
              <th>Uploaded</th>
              <th>Language</th>
            </tr>
          </thead>
          <tbody>
            {chapters.data.map((chapter, index) => (
              <tr key={`${chapter.key}-${index}`}>
                <td>
                  <Link
                    to="/sources/$sourceId/chapter"
                    params={{ sourceId }}
                    search={{ key: chapter.key ?? "", series: key }}
                  >
                    {chapter.name || chapter.key}
                  </Link>
                </td>
                <td>{chapter.number ?? "—"}</td>
                <td>{chapter.volume || "—"}</td>
                <td>{chapter.scanlator || "—"}</td>
                <td>{formatDate(chapter.uploadedAt)}</td>
                <td>{chapter.lang || "—"}</td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
    </>
  );
}
