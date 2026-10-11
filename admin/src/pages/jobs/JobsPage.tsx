// SPDX-License-Identifier: GPL-3.0-or-later

import { useQuery } from "@tanstack/react-query";
import { Link } from "@tanstack/react-router";
import type { Job, JobRunSummary } from "../../api/types";
import { Badge, Empty, ErrorBox, Loading, PageHeader } from "../../components/ui";
import { formatDate } from "../../lib/format";
import { defaultSelection, scheduleLabel, statusLabel, statusTone } from "../../lib/jobs";
import { jobsQuery } from "./queries";
import { RunJobButton } from "./RunJob";

export function RunStatusBadge({ run }: { run: JobRunSummary | undefined }) {
  if (!run?.status) return <span className="muted">Never ran</span>;
  return <Badge tone={statusTone(run.status)}>{statusLabel(run.status)}</Badge>;
}

export function JobsPage() {
  const jobs = useQuery(jobsQuery());
  const groups = new Map<string, Job[]>();
  for (const job of jobs.data ?? []) {
    const group = job.group || "Other";
    groups.set(group, [...(groups.get(group) ?? []), job]);
  }

  return (
    <>
      <PageHeader title="Jobs" />
      <p className="muted">
        Work the service does in the background. Schedules are set in the configuration; any job can also run now.
      </p>
      {jobs.isPending ? <Loading /> : null}
      <ErrorBox error={jobs.error} onRetry={() => jobs.refetch()} />
      {jobs.data?.length === 0 ? <Empty>No background jobs.</Empty> : null}
      {jobs.data && jobs.data.length > 0 ? (
        <table className="table">
          <thead>
            <tr>
              <th>Job</th>
              <th>Schedule</th>
              <th>Last run</th>
              <th>Next run</th>
              <th aria-label="Actions" />
            </tr>
          </thead>
          {[...groups].map(([group, members]) => (
            <tbody key={group}>
              <tr className="group-row">
                <th colSpan={5} scope="colgroup">
                  {group}
                </th>
              </tr>
              {members.map((job) => (
                <JobRow key={job.id} job={job} />
              ))}
            </tbody>
          ))}
        </table>
      ) : null}
    </>
  );
}

function JobRow({ job }: { job: Job }) {
  const jobId = job.id ?? "";
  const last = job.lastRun;
  return (
    <tr>
      <td>
        <Link to="/jobs/$jobId" params={{ jobId }}>
          {job.name || jobId}
        </Link>
        {job.description ? <div className="muted small">{job.description}</div> : null}
      </td>
      <td>
        {scheduleLabel(job)}
        {job.schedule?.source ? <div className="muted small">{job.schedule.source}</div> : null}
        {defaultSelection(job) ? <div className="small">Runs with {defaultSelection(job)}</div> : null}
      </td>
      <td>
        {job.running && job.currentRunId ? (
          <Link to="/job-runs/$runId" params={{ runId: job.currentRunId }}>
            <Badge tone="info">Running</Badge>
          </Link>
        ) : null}{" "}
        {last?.id ? (
          <>
            <Link
              to="/job-runs/$runId"
              params={{ runId: last.id }}
              aria-label={`Last run: ${statusLabel(last.status)}`}
            >
              <RunStatusBadge run={last} />
            </Link>{" "}
            <span className="muted small">{formatDate(last.finishedAt ?? last.startedAt)}</span>
            {last.message ? <div className="small">{last.message}</div> : null}
          </>
        ) : job.running ? null : (
          <RunStatusBadge run={undefined} />
        )}
      </td>
      <td>{job.nextRunAt ? formatDate(job.nextRunAt) : <span className="muted">—</span>}</td>
      <td className="row-actions">
        <RunJobButton job={job} className="small" />
      </td>
    </tr>
  );
}
