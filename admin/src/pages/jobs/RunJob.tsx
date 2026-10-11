// SPDX-License-Identifier: GPL-3.0-or-later

import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Link, useNavigate } from "@tanstack/react-router";
import { type FormEvent, useState } from "react";
import { api, unwrap } from "../../api/client";
import { ApiError } from "../../api/errors";
import type { Job, JobParam } from "../../api/types";
import { Dialog } from "../../components/Dialog";
import { ErrorBox, Field } from "../../components/ui";
import { initialParamValue, paramsToSend, splitList } from "../../lib/jobs";

/** Starts a run and opens it. A run that's already going on is linked instead. */
function useRunJob(job: Job, onStarted?: () => void) {
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const jobId = job.id ?? "";
  return useMutation({
    mutationFn: (params: Record<string, string>) =>
      unwrap(api.POST("/v1/admin/jobs/{jobId}:run", { params: { path: { jobId } }, body: { params } })),
    onSuccess: async (response) => {
      onStarted?.();
      await queryClient.invalidateQueries({ queryKey: ["jobs"] });
      const runId = response.run?.id;
      if (runId) void navigate({ to: "/job-runs/$runId", params: { runId } });
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: ["jobs"] }),
  });
}

/** The run that blocked a start, from a 409 answer. */
function runningRunId(error: unknown): string | undefined {
  if (!(error instanceof ApiError) || error.status !== 409) return undefined;
  const id = error.details.currentRunId;
  return typeof id === "string" && id !== "" ? id : undefined;
}

function RunError({ error }: { error: unknown }) {
  const runId = runningRunId(error);
  if (runId) {
    return (
      <p className="notice" role="alert">
        This job is already running.{" "}
        <Link to="/job-runs/$runId" params={{ runId }}>
          Open the current run
        </Link>
        .
      </p>
    );
  }
  return <ErrorBox error={error} />;
}

/** "Run now": runs at once, or asks for the params first when the job has any. */
export function RunJobButton({ job, className }: { job: Job; className?: string }) {
  const [asking, setAsking] = useState(false);
  const run = useRunJob(job);
  const hasParams = (job.params?.length ?? 0) > 0;

  return (
    <>
      <button
        type="button"
        className={className}
        disabled={job.running || run.isPending}
        title={job.running ? "Already running" : undefined}
        onClick={() => (hasParams ? setAsking(true) : run.mutate({}))}
      >
        {run.isPending ? "Starting…" : "Run now"}
      </button>
      {!hasParams && run.error ? <RunError error={run.error} /> : null}
      {asking ? <RunJobDialog job={job} onClose={() => setAsking(false)} /> : null}
    </>
  );
}

function RunJobDialog({ job, onClose }: { job: Job; onClose: () => void }) {
  const params = job.params ?? [];
  const [values, setValues] = useState<Record<string, string>>(() =>
    Object.fromEntries(params.map((param) => [param.name ?? "", initialParamValue(param)])),
  );
  const run = useRunJob(job, onClose);

  const submit = (event: FormEvent) => {
    event.preventDefault();
    run.mutate(paramsToSend(values));
  };

  return (
    <Dialog title={`Run ${job.name || job.id}`} onClose={onClose}>
      <form className="form-grid" onSubmit={submit}>
        {params.map((param) => (
          <ParamField
            key={param.name}
            param={param}
            value={values[param.name ?? ""] ?? ""}
            onChange={(value) => setValues((current) => ({ ...current, [param.name ?? ""]: value }))}
          />
        ))}
        <RunError error={run.error} />
        <div className="actions">
          <button type="submit" className="primary" disabled={run.isPending}>
            {run.isPending ? "Starting…" : "Run now"}
          </button>
          <button type="button" onClick={onClose}>
            Cancel
          </button>
        </div>
      </form>
    </Dialog>
  );
}

function paramLabel(param: JobParam): string {
  const name = (param.name ?? "").replace(/_/g, " ");
  return param.required ? `${name} (required)` : name;
}

function ParamField({ param, value, onChange }: { param: JobParam; value: string; onChange: (value: string) => void }) {
  const choices = param.choices ?? [];
  const label = paramLabel(param);
  const hint = param.description;

  if (param.type === "bool") {
    return (
      <label className="filter-check field">
        <input type="checkbox" checked={value === "true"} onChange={(e) => onChange(e.target.checked ? "true" : "")} />
        <span>
          {label} {hint ? <span className="muted small">{hint}</span> : null}
        </span>
      </label>
    );
  }

  if (param.type === "string_list" && choices.length > 0) {
    const selected = new Set(splitList(value));
    const toggle = (choice: string, on: boolean) => {
      const next = choices.filter((c) => (c === choice ? on : selected.has(c)));
      onChange(next.join(","));
    };
    return (
      <fieldset className="field">
        <legend className="field-label">{label}</legend>
        <div className="checks">
          {choices.map((choice) => (
            <label key={choice} className="filter-check">
              <input
                type="checkbox"
                checked={selected.has(choice)}
                onChange={(e) => toggle(choice, e.target.checked)}
              />
              <span>{choice}</span>
            </label>
          ))}
        </div>
        {hint ? <span className="field-hint">{hint}</span> : null}
      </fieldset>
    );
  }

  if (choices.length > 0) {
    return (
      <Field label={label} hint={hint}>
        <select value={value} onChange={(e) => onChange(e.target.value)} required={param.required}>
          {param.required ? null : <option value="">—</option>}
          {choices.map((choice) => (
            <option key={choice} value={choice}>
              {choice}
            </option>
          ))}
        </select>
      </Field>
    );
  }

  return (
    <Field
      label={label}
      hint={param.type === "string_list" ? `${hint ?? ""} Separate values with commas.`.trim() : hint}
    >
      <input
        type={param.type === "int" ? "number" : "text"}
        step={param.type === "int" ? 1 : undefined}
        value={value}
        onChange={(e) => onChange(e.target.value)}
        required={param.required}
      />
    </Field>
  );
}

/** Cancels a running run. */
export function CancelRunButton({ runId, className }: { runId: string; className?: string }) {
  const queryClient = useQueryClient();
  const cancel = useMutation({
    mutationFn: () => unwrap(api.POST("/v1/admin/job-runs/{runId}:cancel", { params: { path: { runId } }, body: {} })),
    onSettled: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ["jobs"] }),
        queryClient.invalidateQueries({ queryKey: ["job-runs", runId] }),
      ]),
  });
  return (
    <>
      <button type="button" className={className} disabled={cancel.isPending} onClick={() => cancel.mutate()}>
        {cancel.isPending ? "Cancelling…" : "Cancel run"}
      </button>
      <ErrorBox error={cancel.error} />
    </>
  );
}
