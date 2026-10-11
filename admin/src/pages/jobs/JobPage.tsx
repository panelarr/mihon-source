// SPDX-License-Identifier: GPL-3.0-or-later

import { useInfiniteQuery, useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import type { JobRunStatus } from "../../api/types";
import { Empty, ErrorBox, Field, Loading, Mono, PageHeader } from "../../components/ui";
import { formatDate } from "../../lib/format";
import { countsLabel, runDuration, runStatuses, scheduleLabel, statusLabel, triggerLabel } from "../../lib/jobs";
import { RunStatusBadge } from "./JobsPage";
import { jobQuery, jobRunsQuery } from "./queries";
import { CancelRunButton, RunJobButton } from "./RunJob";

const route = getRouteApi("/_app/jobs/$jobId");

export function JobPage() {
  const { jobId } = route.useParams();
  const { status } = route.useSearch();
  const navigate = route.useNavigate();
  const job = useQuery(jobQuery(jobId));
  const runs = useInfiniteQuery(jobRunsQuery(jobId, status));
  const all = runs.data?.pages.flatMap((page) => page.runs ?? []) ?? [];

  return (
    <>
      <p className="muted">
        <Link to="/jobs">Jobs</Link> /
      </p>
      <PageHeader title={job.data?.name || jobId}>
        {job.data ? <RunJobButton job={job.data} /> : null}
        {job.data?.running && job.data.currentRunId ? <CancelRunButton runId={job.data.currentRunId} /> : null}
      </PageHeader>
      {job.isPending ? <Loading /> : null}
      <ErrorBox error={job.error} onRetry={() => job.refetch()} />
      {job.data ? (
        <section className="card">
          {job.data.description ? <p>{job.data.description}</p> : null}
          <dl className="details compact">
            <dt>ID</dt>
            <dd>
              <Mono>{job.data.id}</Mono>
            </dd>
            <dt>Schedule</dt>
            <dd>
              {scheduleLabel(job.data)}
              {job.data.schedule?.source ? (
                <span className="muted">
                  {" "}
                  (set by <Mono>{job.data.schedule.source}</Mono>)
                </span>
              ) : null}
            </dd>
            <dt>Next run</dt>
            <dd>{formatDate(job.data.nextRunAt)}</dd>
            <dt>Running</dt>
            <dd>
              {job.data.running && job.data.currentRunId ? (
                <Link to="/job-runs/$runId" params={{ runId: job.data.currentRunId }}>
                  Yes, open the run
                </Link>
              ) : (
                "No"
              )}
            </dd>
            {job.data.params?.length ? (
              <>
                <dt title="Scheduled runs use the defaults">Parameters</dt>
                <dd>
                  <ul className="plain-list">
                    {job.data.params.map((param) => (
                      <li key={param.name}>
                        <Mono>{param.name}</Mono>
                        {param.default ? (
                          <>
                            {" "}
                            = <Mono>{param.default}</Mono>
                          </>
                        ) : null}{" "}
                        <span className="muted small">{param.description}</span>
                      </li>
                    ))}
                  </ul>
                </dd>
              </>
            ) : null}
          </dl>
        </section>
      ) : null}

      <h2>Runs</h2>
      <div className="toolbar">
        <Field label="Status">
          <select
            value={status ?? ""}
            onChange={(event) =>
              void navigate({ search: { status: (event.target.value || undefined) as JobRunStatus | undefined } })
            }
          >
            <option value="">All</option>
            {runStatuses.map((value) => (
              <option key={value} value={value}>
                {statusLabel(value)}
              </option>
            ))}
          </select>
        </Field>
      </div>
      {runs.isPending ? <Loading /> : null}
      <ErrorBox error={runs.error} onRetry={() => runs.refetch()} />
      {runs.data && all.length === 0 ? <Empty>No runs{status ? " with this status" : " yet"}.</Empty> : null}
      {all.length > 0 ? (
        <table className="table">
          <thead>
            <tr>
              <th>Status</th>
              <th>Started</th>
              <th>Took</th>
              <th>Trigger</th>
              <th>Result</th>
            </tr>
          </thead>
          <tbody>
            {all.map((run) => (
              <tr key={run.id}>
                <td>
                  <Link to="/job-runs/$runId" params={{ runId: run.id ?? "" }}>
                    <RunStatusBadge run={run} />
                  </Link>
                </td>
                <td>{formatDate(run.startedAt)}</td>
                <td>{runDuration(run)}</td>
                <td>{triggerLabel(run)}</td>
                <td>
                  {run.message || <span className="muted">—</span>}
                  {countsLabel(run.counts) ? <div className="muted small">{countsLabel(run.counts)}</div> : null}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      ) : null}
      {runs.hasNextPage ? (
        <div className="actions">
          <button type="button" onClick={() => runs.fetchNextPage()} disabled={runs.isFetchingNextPage}>
            {runs.isFetchingNextPage ? "Loading…" : "Load more"}
          </button>
        </div>
      ) : null}
    </>
  );
}
