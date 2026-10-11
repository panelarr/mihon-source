// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.jobs

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.slf4j.LoggerFactory
import panelarr.mihonsource.storage.JsonFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant

@Serializable
enum class RunStatus { RUNNING, SUCCEEDED, FAILED, CANCELLED, SKIPPED }

@Serializable
enum class RunTrigger { SCHEDULE, MANUAL, STARTUP }

/** A run as kept on disk. Times are ISO-8601 instants. */
@Serializable
data class RunRecord(
    val id: String,
    val jobId: String,
    val status: RunStatus,
    val trigger: RunTrigger,
    val startedAt: String,
    val finishedAt: String? = null,
    val message: String = "",
    val counts: Map<String, Long> = emptyMap(),
    /** Who started a manual run. */
    val triggeredBy: String = "",
    val params: Map<String, String> = emptyMap(),
    val notes: List<NoteRecord> = emptyList(),
)

@Serializable
data class NoteRecord(
    val at: String,
    val level: JobNote.Level,
    val message: String,
    val ref: String = "",
)

/**
 * The runs of one job, newest first, in `jobs/<job-id>.json`. Only the newest [keep]
 * runs are kept. A run still marked running when the file is loaded was cut
 * short by a stop or crash, and is marked failed ("interrupted").
 */
class JobHistory(
    path: Path,
    private val keep: Int,
    now: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(JobHistory::class.java)
    private val file = JsonFile(path, ListSerializer(RunRecord.serializer()))
    private val runs: MutableList<RunRecord> = load(now).toMutableList()

    private fun load(now: () -> Instant): List<RunRecord> {
        val stored =
            runCatching { file.read() }
                .onFailure { e ->
                    // Keep the unreadable file for inspection and start a new history.
                    log.warn("Unreadable job history {} ({}); starting a new one", file.path, e.message)
                    Files.move(file.path, file.path.resolveSibling("${file.path.fileName}.bad"), StandardCopyOption.REPLACE_EXISTING)
                }.getOrNull()
                .orEmpty()
        val at = now().toString()
        val fixed =
            stored.map {
                if (it.status ==
                    RunStatus.RUNNING
                ) {
                    it.copy(status = RunStatus.FAILED, finishedAt = at, message = INTERRUPTED)
                } else {
                    it
                }
            }
        if (fixed != stored) file.write(fixed)
        return fixed
    }

    @Synchronized
    fun all(): List<RunRecord> = runs.toList()

    @Synchronized
    fun find(id: String): RunRecord? = runs.firstOrNull { it.id == id }

    /** The newest finished run. */
    @Synchronized
    fun lastFinished(): RunRecord? = runs.firstOrNull { it.status != RunStatus.RUNNING }

    @Synchronized
    fun add(run: RunRecord) {
        runs.add(0, run)
        save()
    }

    @Synchronized
    fun replace(run: RunRecord) {
        val index = runs.indexOfFirst { it.id == run.id }
        if (index >= 0) runs[index] = run else runs.add(0, run)
        save()
    }

    /** Drops all but the newest [keep] runs (a running one always stays) and writes the file. */
    private fun save() {
        var seen = 0
        runs.removeAll { ++seen > keep && it.status != RunStatus.RUNNING }
        file.write(runs)
    }

    companion object {
        const val INTERRUPTED = "interrupted"
    }
}
