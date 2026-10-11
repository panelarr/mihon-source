// SPDX-License-Identifier: GPL-3.0-or-later

import type { Job, JobParam, JobRunStatus, JobRunSummary, JobTrigger } from "../api/types";
import type { Tone } from "../components/ui";
import { formatDuration } from "./duration";

export const runStatuses: readonly JobRunStatus[] = [
  "JOB_RUN_STATUS_RUNNING",
  "JOB_RUN_STATUS_SUCCEEDED",
  "JOB_RUN_STATUS_FAILED",
  "JOB_RUN_STATUS_CANCELLED",
  "JOB_RUN_STATUS_SKIPPED",
];

const statusLabels: Record<JobRunStatus, string> = {
  JOB_RUN_STATUS_UNSPECIFIED: "Unknown",
  JOB_RUN_STATUS_RUNNING: "Running",
  JOB_RUN_STATUS_SUCCEEDED: "Succeeded",
  JOB_RUN_STATUS_FAILED: "Failed",
  JOB_RUN_STATUS_CANCELLED: "Cancelled",
  JOB_RUN_STATUS_SKIPPED: "Skipped",
};

const statusTones: Record<JobRunStatus, Tone> = {
  JOB_RUN_STATUS_UNSPECIFIED: "neutral",
  JOB_RUN_STATUS_RUNNING: "info",
  JOB_RUN_STATUS_SUCCEEDED: "good",
  JOB_RUN_STATUS_FAILED: "bad",
  JOB_RUN_STATUS_CANCELLED: "warn",
  JOB_RUN_STATUS_SKIPPED: "neutral",
};

export function statusLabel(status: JobRunStatus | undefined): string {
  return statusLabels[status ?? "JOB_RUN_STATUS_UNSPECIFIED"];
}

export function statusTone(status: JobRunStatus | undefined): Tone {
  return statusTones[status ?? "JOB_RUN_STATUS_UNSPECIFIED"];
}

const triggerLabels: Record<JobTrigger, string> = {
  JOB_TRIGGER_UNSPECIFIED: "—",
  JOB_TRIGGER_SCHEDULE: "Schedule",
  JOB_TRIGGER_MANUAL: "Manual",
  JOB_TRIGGER_STARTUP: "Startup",
  JOB_TRIGGER_SYSTEM: "System",
};

export function triggerLabel(run: JobRunSummary): string {
  const label = triggerLabels[run.trigger ?? "JOB_TRIGGER_UNSPECIFIED"];
  return run.triggeredBy ? `${label} (${run.triggeredBy})` : label;
}

/** "Every 12h", "Cron: 0 3 * * *" or "Manual only". */
export function scheduleLabel(job: Job): string {
  const schedule = job.schedule;
  if (!schedule?.enabled) return "Manual only";
  if (schedule.interval) return `Every ${formatDuration(schedule.interval)}`;
  if (schedule.cron) return `Cron: ${schedule.cron}`;
  return "Manual only";
}

/**
 * What runs use when no params are given, scheduled runs included: the params' defaults,
 * e.g. "langs: en, fr · sample: 10". Empty when the job has none.
 */
export function defaultSelection(job: Job): string {
  return (job.params ?? [])
    .filter((param) => param.default)
    .map((param) => `${param.name}: ${(param.default ?? "").split(",").join(", ")}`)
    .join(" · ");
}

/** How long a run took, or has been running so far. */
export function runDuration(run: JobRunSummary, now: number = Date.now()): string {
  if (!run.startedAt) return "—";
  const start = new Date(run.startedAt).getTime();
  const end = run.finishedAt ? new Date(run.finishedAt).getTime() : now;
  if (Number.isNaN(start) || Number.isNaN(end)) return "—";
  const seconds = Math.max(0, Math.round((end - start) / 1000));
  if (seconds === 0) return end > start ? "<1s" : "0s";
  return formatDuration(`${seconds}s`);
}

/** "stores 2 · updates 5", in the order the job reported them. */
export function countsLabel(counts: Record<string, string> | undefined): string {
  return Object.entries(counts ?? {})
    .map(([name, value]) => `${name} ${value}`)
    .join(" · ");
}

/** The form value a param starts with: its default, or nothing. */
export function initialParamValue(param: JobParam): string {
  return param.default ?? "";
}

/** Form values to RunJob params: empty values are left out, so defaults apply. */
export function paramsToSend(values: Record<string, string>): Record<string, string> {
  return Object.fromEntries(
    Object.entries(values)
      .map(([name, value]) => [name, value.trim()] as const)
      .filter(([, value]) => value !== ""),
  );
}

/** "a, b" → ["a", "b"]. */
export function splitList(value: string): string[] {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter((item) => item !== "");
}
