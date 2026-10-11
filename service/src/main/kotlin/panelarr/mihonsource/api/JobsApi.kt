// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.api

import com.google.protobuf.Any
import com.google.rpc.Code
import com.google.rpc.ErrorInfo
import io.grpc.StatusException
import io.grpc.protobuf.StatusProto
import panelarr.mihon.v1.CancelJobRunRequest
import panelarr.mihon.v1.CancelJobRunResponse
import panelarr.mihon.v1.GetJobRequest
import panelarr.mihon.v1.GetJobResponse
import panelarr.mihon.v1.GetJobRunRequest
import panelarr.mihon.v1.GetJobRunResponse
import panelarr.mihon.v1.Job
import panelarr.mihon.v1.JobAlreadyRunning
import panelarr.mihon.v1.JobRun
import panelarr.mihon.v1.JobRunStatus
import panelarr.mihon.v1.JobRunSummary
import panelarr.mihon.v1.JobTrigger
import panelarr.mihon.v1.ListJobRunsRequest
import panelarr.mihon.v1.ListJobRunsResponse
import panelarr.mihon.v1.ListJobsRequest
import panelarr.mihon.v1.ListJobsResponse
import panelarr.mihon.v1.MihonJobsServiceGrpcKt
import panelarr.mihon.v1.RunJobRequest
import panelarr.mihon.v1.RunJobResponse
import panelarr.mihon.v1.cancelJobRunResponse
import panelarr.mihon.v1.getJobResponse
import panelarr.mihon.v1.getJobRunResponse
import panelarr.mihon.v1.job
import panelarr.mihon.v1.jobParam
import panelarr.mihon.v1.jobProgress
import panelarr.mihon.v1.jobRun
import panelarr.mihon.v1.jobRunNote
import panelarr.mihon.v1.jobRunSummary
import panelarr.mihon.v1.listJobRunsResponse
import panelarr.mihon.v1.listJobsResponse
import panelarr.mihon.v1.runJobResponse
import panelarr.mihon.v1.schedule
import panelarr.mihonsource.auth.AuthInterceptor
import panelarr.mihonsource.jobs.BackgroundJob
import panelarr.mihonsource.jobs.JobAlreadyRunningException
import panelarr.mihonsource.jobs.JobRunner
import panelarr.mihonsource.jobs.RunRecord
import panelarr.mihonsource.jobs.RunStatus
import panelarr.mihonsource.jobs.RunTrigger
import panelarr.mihonsource.jobs.RunView
import java.time.Instant

/** `panelarr.mihon.v1.MihonJobsService`: background jobs and their runs. */
class JobsApi(
    private val runner: JobRunner,
) : MihonJobsServiceGrpcKt.MihonJobsServiceCoroutineImplBase() {
    override suspend fun listJobs(request: ListJobsRequest): ListJobsResponse =
        rpc { listJobsResponse { jobs += runner.jobs().map { it.toProto() } } }

    override suspend fun getJob(request: GetJobRequest): GetJobResponse =
        rpc { getJobResponse { job = runner.job(request.jobId).toProto() } }

    override suspend fun listJobRuns(request: ListJobRunsRequest): ListJobRunsResponse =
        rpc {
            val size = request.pageSize.takeIf { it > 0 }?.coerceAtMost(MAX_PAGE_SIZE) ?: DEFAULT_PAGE_SIZE
            val offset =
                request.pageToken.takeIf { it.isNotEmpty() }?.let {
                    it.toIntOrNull()?.takeIf { offset -> offset >= 0 } ?: throw IllegalArgumentException("invalid page_token")
                } ?: 0
            val status =
                when (request.status) {
                    JobRunStatus.JOB_RUN_STATUS_UNSPECIFIED, JobRunStatus.UNRECOGNIZED, null -> null
                    else -> RunStatus.valueOf(request.status.name.removePrefix(STATUS_PREFIX))
                }
            val (runs, more) = runner.runs(request.jobId, status, offset, size)
            listJobRunsResponse {
                this.runs += runs.map { it.record.toSummary() }
                if (more) nextPageToken = (offset + size).toString()
            }
        }

    override suspend fun getJobRun(request: GetJobRunRequest): GetJobRunResponse =
        rpc { getJobRunResponse { run = runner.run(request.runId).toProto() } }

    override suspend fun runJob(request: RunJobRequest): RunJobResponse =
        rpc {
            val by = AuthInterceptor.current()?.name.orEmpty()
            val record =
                try {
                    runner.start(request.jobId, RunTrigger.MANUAL, request.paramsMap, by)
                } catch (e: JobAlreadyRunningException) {
                    throw alreadyRunning(e)
                }
            runJobResponse { run = record.toSummary() }
        }

    override suspend fun cancelJobRun(request: CancelJobRunRequest): CancelJobRunResponse =
        rpc { cancelJobRunResponse { run = runner.cancel(request.runId).record.toSummary() } }

    private fun alreadyRunning(e: JobAlreadyRunningException): StatusException =
        StatusProto.toStatusException(
            com.google.rpc.Status
                .newBuilder()
                .setCode(Code.ALREADY_EXISTS_VALUE)
                .setMessage(e.message)
                .addDetails(
                    Any.pack(
                        ErrorInfo
                            .newBuilder()
                            .setReason("ALREADY_RUNNING")
                            .setDomain(DOMAIN)
                            .putMetadata("current_run_id", e.runId)
                            .build(),
                    ),
                ).addDetails(Any.pack(JobAlreadyRunning.newBuilder().setCurrentRunId(e.runId).build()))
                .build(),
        )

    private fun BackgroundJob.toProto(): Job {
        val job = this
        val current = runner.current(id)
        return job {
            id = job.id
            name = job.name
            description = job.description
            group = job.group
            cancellable = job.cancellable
            schedule =
                schedule {
                    job.schedule.interval?.takeIf { job.schedule.enabled }?.let { every ->
                        interval =
                            com.google.protobuf.Duration
                                .newBuilder()
                                .setSeconds(every.seconds)
                                .setNanos(every.nano)
                                .build()
                    }
                    enabled = job.schedule.enabled
                    source = job.schedule.source
                }
            params +=
                job.params.map { spec ->
                    jobParam {
                        name = spec.name
                        type = spec.type.wire
                        description = spec.description
                        required = spec.required
                        default = spec.default
                        choices += spec.choices
                    }
                }
            running = current != null
            current?.let { currentRunId = it.record.id }
            runner.lastFinished(job.id)?.let { lastRun = it.toSummary() }
            runner.nextRunAt(job.id)?.let { nextRunAt = it.toTimestamp() }
        }
    }

    private fun RunView.toProto(): JobRun {
        val view = this
        return jobRun {
            summary = view.record.toSummary()
            params.putAll(view.record.params)
            notes +=
                view.record.notes.map { note ->
                    jobRunNote {
                        at = Instant.parse(note.at).toTimestamp()
                        level = note.level.name.lowercase()
                        message = note.message
                        ref = note.ref
                    }
                }
            view.progress?.let { p ->
                progress =
                    jobProgress {
                        phase = p.phase
                        done = p.done
                        total = p.total
                        unit = p.unit
                    }
            }
        }
    }

    private fun RunRecord.toSummary(): JobRunSummary {
        val run = this
        return jobRunSummary {
            id = run.id
            jobId = run.jobId
            status = JobRunStatus.valueOf(STATUS_PREFIX + run.status.name)
            trigger = JobTrigger.valueOf("JOB_TRIGGER_" + run.trigger.name)
            startedAt = Instant.parse(run.startedAt).toTimestamp()
            run.finishedAt?.let { finishedAt = Instant.parse(it).toTimestamp() }
            message = run.message
            counts.putAll(run.counts)
            triggeredBy = run.triggeredBy
        }
    }

    private companion object {
        const val DEFAULT_PAGE_SIZE = 20
        const val MAX_PAGE_SIZE = 100
        const val STATUS_PREFIX = "JOB_RUN_STATUS_"
        const val DOMAIN = "jobs.panelarr"
    }
}
