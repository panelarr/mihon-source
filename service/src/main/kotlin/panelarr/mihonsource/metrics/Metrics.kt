// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.metrics

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Timer
import io.micrometer.core.instrument.binder.jvm.JvmGcMetrics
import io.micrometer.core.instrument.binder.jvm.JvmMemoryMetrics
import io.micrometer.core.instrument.binder.jvm.JvmThreadMetrics
import io.micrometer.core.instrument.binder.system.ProcessorMetrics
import io.micrometer.core.instrument.binder.system.UptimeMetrics
import io.micrometer.prometheusmetrics.PrometheusConfig
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry
import java.time.Duration

/**
 * The service's metrics, in Prometheus format. Labels are kept bounded on purpose: a
 * source id (one per installed source), an operation name and an outcome. No URLs,
 * queries, keys or credentials ever become labels.
 */
class Metrics {
    val registry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT)

    init {
        JvmMemoryMetrics().bindTo(registry)
        JvmGcMetrics().bindTo(registry)
        JvmThreadMetrics().bindTo(registry)
        ProcessorMetrics().bindTo(registry)
        UptimeMetrics().bindTo(registry)
    }

    /**
     * One call into a source: [operation] is e.g. "popular" or "pages", [outcome] is "ok",
     * an error reason (e.g. "SOURCE_DOWN") or a gRPC code name.
     */
    fun sourceCall(
        sourceId: Long,
        operation: String,
        outcome: String,
        duration: Duration,
    ) {
        Timer
            .builder("mihon_source_call")
            .description("Calls into sources")
            .tags("source", sourceId.toString(), "operation", operation, "outcome", outcome)
            .publishPercentileHistogram(false)
            .register(registry)
            .record(duration)
    }

    /** One challenge solve: [outcome] is "ok" or "error". */
    fun solve(
        solverId: String,
        outcome: String,
    ) {
        Counter
            .builder("mihon_solver_solves")
            .description("Challenge solves requested from solvers")
            .tags("solver", solverId, "outcome", outcome)
            .register(registry)
            .increment()
    }

    /** Gauges read at scrape time: extensions by status and sources. */
    fun extensionGauges(
        loaded: () -> Int,
        failed: () -> Int,
        sources: () -> Int,
    ) {
        // Strong references: the lambdas are only held by the gauges.
        Gauge
            .builder("mihon_extensions", loaded) { it().toDouble() }
            .tag("status", "loaded")
            .strongReference(true)
            .register(registry)
        Gauge
            .builder("mihon_extensions", failed) { it().toDouble() }
            .tag("status", "error")
            .strongReference(true)
            .register(registry)
        Gauge
            .builder("mihon_sources", sources) { it().toDouble() }
            .description("Loaded sources")
            .strongReference(true)
            .register(registry)
    }

    private val health = java.util.concurrent.ConcurrentHashMap<Long, java.util.concurrent.atomic.AtomicInteger>()

    /** The last health check of a source: 1 ok, 0 failing. */
    fun sourceHealth(
        sourceId: Long,
        ok: Boolean,
    ) {
        health
            .computeIfAbsent(sourceId) { id ->
                java.util.concurrent.atomic.AtomicInteger().also { value ->
                    Gauge
                        .builder("mihon_source_health", value) { it.get().toDouble() }
                        .description("Result of the last health check of a source: 1 ok, 0 failing")
                        .tag("source", id.toString())
                        .register(registry)
                }
            }.set(if (ok) 1 else 0)
    }

    /** A result cache lookup: [result] is hit, miss, shared or bypass. */
    fun cache(
        operation: String,
        result: String,
    ) {
        Counter
            .builder("mihon_cache_lookups")
            .description("Result cache lookups")
            .tags("operation", operation, "result", result)
            .register(registry)
            .increment()
    }

    /** A finished job run: [status] and [trigger] are lower-case names, e.g. "succeeded", "schedule". */
    fun jobRun(
        job: String,
        status: String,
        trigger: String,
        duration: Duration,
    ) {
        Counter
            .builder("mihon_job_runs")
            .description("Finished job runs")
            .tags("job", job, "status", status, "trigger", trigger)
            .register(registry)
            .increment()
        Timer
            .builder("mihon_job_duration")
            .description("How long job runs took")
            .tag("job", job)
            .register(registry)
            .record(duration)
    }

    /** Whether a job is running now: 1 or 0, read at scrape time. */
    fun jobRunning(
        job: String,
        running: () -> Int,
    ) {
        Gauge
            .builder("mihon_job_running", running) { it().toDouble() }
            .description("Whether the job is running: 1 or 0")
            .tag("job", job)
            .strongReference(true)
            .register(registry)
    }

    fun scrape(): String = registry.scrape()
}
