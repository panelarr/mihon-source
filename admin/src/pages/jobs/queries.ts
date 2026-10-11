// SPDX-License-Identifier: GPL-3.0-or-later

import { infiniteQueryOptions, queryOptions } from "@tanstack/react-query";
import { api, unwrap } from "../../api/client";
import type { JobRunStatus } from "../../api/types";

/** How often pages refresh while a run is going on. */
export const ACTIVE_POLL_MS = 2000;

export const jobsQuery = () =>
  queryOptions({
    queryKey: ["jobs"],
    queryFn: async () => (await unwrap(api.GET("/v1/admin/jobs"))).jobs ?? [],
    refetchInterval: (query) => (query.state.data?.some((job) => job.running) ? ACTIVE_POLL_MS : false),
  });

export const jobQuery = (jobId: string) =>
  queryOptions({
    queryKey: ["jobs", jobId],
    queryFn: async () => (await unwrap(api.GET("/v1/admin/jobs/{jobId}", { params: { path: { jobId } } }))).job ?? {},
    refetchInterval: (query) => (query.state.data?.running ? ACTIVE_POLL_MS : false),
  });

export const RUNS_PAGE_SIZE = 20;

export const jobRunsQuery = (jobId: string, status: JobRunStatus | undefined) =>
  infiniteQueryOptions({
    queryKey: ["jobs", jobId, "runs", status ?? "all"],
    queryFn: ({ pageParam }) =>
      unwrap(
        api.GET("/v1/admin/jobs/{jobId}/runs", {
          params: {
            path: { jobId },
            query: {
              pageSize: RUNS_PAGE_SIZE,
              ...(pageParam ? { pageToken: pageParam } : {}),
              ...(status ? { status } : {}),
            },
          },
        }),
      ),
    initialPageParam: "",
    getNextPageParam: (last) => last.nextPageToken || undefined,
    refetchInterval: (query) =>
      query.state.data?.pages.some((page) => page.runs?.some((run) => run.status === "JOB_RUN_STATUS_RUNNING"))
        ? ACTIVE_POLL_MS
        : false,
  });

export const jobRunQuery = (runId: string) =>
  queryOptions({
    queryKey: ["job-runs", runId],
    queryFn: async () =>
      (await unwrap(api.GET("/v1/admin/job-runs/{runId}", { params: { path: { runId } } }))).run ?? {},
    refetchInterval: (query) =>
      query.state.data?.summary?.status === "JOB_RUN_STATUS_RUNNING" ? ACTIVE_POLL_MS : false,
  });
