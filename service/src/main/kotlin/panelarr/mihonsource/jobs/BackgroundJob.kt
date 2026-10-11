// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.jobs

import java.time.Duration
import java.time.Instant

/**
 * Work the service does in the background, on a schedule or on request. [JobRunner] runs
 * it, at most once at a time, and keeps the history of its runs.
 */
interface BackgroundJob {
    /** Stable ID, used in the API and as the metrics label: `^[a-z0-9][a-z0-9._-]*$`. */
    val id: String
    val name: String
    val description: String

    /** A label the admin UI groups jobs by. */
    val group: String

    val schedule: JobSchedule

    /**
     * What a run accepts. Scheduled runs use the defaults, so the defaults are what the
     * schedule runs with.
     */
    val params: List<JobParamSpec> get() = emptyList()

    /** Whether a run stops when cancelled. Jobs check for cancellation between steps. */
    val cancellable: Boolean get() = true

    /**
     * Checks [params] beyond their declared types before a run starts, such as whether a
     * named store exists. Throws [IllegalArgumentException] or [NoSuchElementException].
     */
    fun validate(params: Map<String, String>) {}

    /** Adjusts checked params (defaults filled in) before a run, e.g. drops ones another param overrides. */
    fun normalize(params: Map<String, String>): Map<String, String> = params

    /** Does the work. An exception fails the run with its message. */
    suspend fun run(run: JobRunContext)
}

/** A job's schedule as configured. A null [interval] means it only runs on request. */
data class JobSchedule(
    val interval: Duration?,
    /** Where the schedule is configured, e.g. an environment variable. */
    val source: String,
) {
    val enabled: Boolean get() = interval != null && !interval.isZero && !interval.isNegative
}

data class JobParamSpec(
    val name: String,
    val type: Type,
    val description: String,
    val required: Boolean = false,
    val default: String = "",
    val choices: List<String> = emptyList(),
) {
    enum class Type(
        val wire: String,
    ) {
        BOOL("bool"),
        INT("int"),
        STRING("string"),

        /** Comma-separated over the API. */
        STRING_LIST("string_list"),
    }
}

/** What a running job reports: notes, counts, progress and how it ended. */
class JobRunContext(
    val params: Map<String, String>,
    private val maxNotes: Int = MAX_NOTES,
    private val now: () -> Instant = Instant::now,
) {
    private val notes = ArrayList<JobNote>()
    private var droppedNotes = 0
    private val counts = LinkedHashMap<String, Long>()

    @Volatile
    var progress: JobProgressInfo? = null
        private set

    /** The one-line outcome. Defaults to a summary of the counts. */
    @Volatile
    var message: String = ""

    @Volatile
    internal var outcome: Outcome = Outcome.DONE
        private set

    internal enum class Outcome { DONE, FAILED, SKIPPED }

    /** A list param, split on commas. */
    fun list(name: String): List<String> =
        params[name]
            ?.split(',')
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()

    fun note(
        level: JobNote.Level,
        message: String,
        ref: String = "",
    ) = synchronized(this) {
        if (notes.size < maxNotes) notes += JobNote(now(), level, message, ref) else droppedNotes++
    }

    fun increment(
        name: String,
        by: Long = 1,
    ) = synchronized(this) { counts.merge(name, by, Long::plus) }

    fun count(
        name: String,
        value: Long,
    ) = synchronized(this) { counts[name] = value }

    fun progress(
        phase: String,
        done: Long,
        total: Long,
        unit: String,
    ) {
        progress = JobProgressInfo(phase, done, total, unit)
    }

    /** Ends the run as failed (after the job returns) without throwing, e.g. after partial failures. */
    fun fail(message: String) {
        this.message = message
        outcome = Outcome.FAILED
    }

    /** Ends the run as skipped: it had nothing to do. */
    fun skip(message: String) {
        this.message = message
        outcome = Outcome.SKIPPED
    }

    fun notes(): List<JobNote> =
        synchronized(this) {
            if (droppedNotes == 0) {
                notes.toList()
            } else {
                notes + JobNote(now(), JobNote.Level.INFO, "$droppedNotes more notes were left out", "")
            }
        }

    fun counts(): Map<String, Long> = synchronized(this) { counts.toMap() }

    companion object {
        const val MAX_NOTES = 200
    }
}

data class JobNote(
    val at: Instant,
    val level: Level,
    val message: String,
    val ref: String,
) {
    enum class Level { INFO, WARN, ERROR }
}

data class JobProgressInfo(
    val phase: String,
    val done: Long,
    val total: Long,
    val unit: String,
)
