// SPDX-License-Identifier: GPL-3.0-or-later

import { useQuery } from "@tanstack/react-query";
import { getRouteApi, Link } from "@tanstack/react-router";
import { Badge, Empty, ErrorBox, Loading, Mono, PageHeader, type Tone } from "../../components/ui";
import { formatDate } from "../../lib/format";
import { runDuration, triggerLabel } from "../../lib/jobs";
import { RunStatusBadge } from "./JobsPage";
import { jobQuery, jobRunQuery } from "./queries";
import { CancelRunButton } from "./RunJob";

const route = getRouteApi("/_app/job-runs/$runId");

const noteTones: Record<string, Tone> = { error: "bad", warn: "warn", info: "neutral" };

export function JobRunPage() {
  const { runId } = route.useParams();
  const run = useQuery(jobRunQuery(runId));
  const summary = run.data?.summary;
  const jobId = summary?.jobId ?? "";
  const job = useQuery({ ...jobQuery(jobId), enabled: jobId !== "" });
  const running = summary?.status === "JOB_RUN_STATUS_RUNNING";
  const progress = run.data?.progress;
  const total = Number(progress?.total ?? 0);
  const done = Number(progress?.done ?? 0);
  const counts = Object.entries(summary?.counts ?? {});
  const params = Object.entries(run.data?.params ?? {});
  const notes = run.data?.notes ?? [];

  return (
    <>
      <p className="muted">
        <Link to="/jobs">Jobs</Link> /{" "}
        {jobId ? (
          <>
            <Link to="/jobs/$jobId" params={{ jobId }}>
              {job.data?.name || jobId}
            </Link>{" "}
            /
          </>
        ) : null}
      </p>
      <PageHeader title={`Run ${runId}`}>
        {running && job.data?.cancellable ? <CancelRunButton runId={runId} /> : null}
      </PageHeader>
      {run.isPending ? <Loading /> : null}
      <ErrorBox error={run.error} onRetry={() => run.refetch()} />
      {summary ? (
        <section className="card">
          <dl className="details compact">
            <dt>Status</dt>
            <dd>
              <RunStatusBadge run={summary} />
            </dd>
            <dt>Result</dt>
            <dd className="wrap">{summary.message || <span className="muted">—</span>}</dd>
            <dt>Trigger</dt>
            <dd>{triggerLabel(summary)}</dd>
            <dt>Started</dt>
            <dd>{formatDate(summary.startedAt)}</dd>
            <dt>Finished</dt>
            <dd>{running ? <span className="muted">Still running</span> : formatDate(summary.finishedAt)}</dd>
            <dt>Took</dt>
            <dd>{runDuration(summary)}</dd>
            {counts.length > 0 ? (
              <>
                <dt>Counts</dt>
                <dd>
                  <span className="badges">
                    {counts.map(([name, value]) => (
                      <Badge key={name}>
                        {name}: {value}
                      </Badge>
                    ))}
                  </span>
                </dd>
              </>
            ) : null}
            {params.length > 0 ? (
              <>
                <dt>Parameters</dt>
                <dd>
                  {params.map(([name, value]) => (
                    <div key={name}>
                      <Mono>{name}</Mono> = <Mono>{value}</Mono>
                    </div>
                  ))}
                </dd>
              </>
            ) : null}
          </dl>
          {running && progress ? (
            <div className="job-progress">
              <progress max={total || undefined} value={total ? done : undefined} aria-label="Progress" />
              <span className="small">
                {progress.phase}
                {total ? ` ${done} of ${total} ${progress.unit ?? ""}` : ""}
              </span>
            </div>
          ) : null}
        </section>
      ) : null}

      {summary ? (
        <>
          <h2>Notes</h2>
          {notes.length === 0 ? <Empty>No notes{running ? " yet" : ""}.</Empty> : null}
          {notes.length > 0 ? (
            <table className="table">
              <thead>
                <tr>
                  <th>Time</th>
                  <th>Level</th>
                  <th>Note</th>
                  <th>About</th>
                </tr>
              </thead>
              <tbody>
                {notes.map((note, index) => (
                  // Notes have no ID; they're only ever appended.
                  // biome-ignore lint/suspicious/noArrayIndexKey: see above.
                  <tr key={index}>
                    <td>{formatDate(note.at)}</td>
                    <td>
                      <Badge tone={noteTones[note.level ?? ""] ?? "neutral"}>{note.level || "info"}</Badge>
                    </td>
                    <td className="wrap">{note.message}</td>
                    <td>{note.ref ? <Mono>{note.ref}</Mono> : null}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          ) : null}
        </>
      ) : null}
    </>
  );
}
