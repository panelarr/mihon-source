// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import eu.kanade.tachiyomi.source.model.FilterList
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.source.model.SManga
import io.grpc.StatusException
import java.time.Duration
import java.time.Instant

/** What a check can do. Each step feeds the next: a list gives a series, a series a chapter, and so on. */
enum class CheckStepKind { POPULAR, LATEST, SEARCH, DETAILS, CHAPTERS, PAGES, IMAGE }

data class StepOutcome(
    val step: CheckStepKind,
    val ok: Boolean,
    val skipped: Boolean = false,
    val reason: String? = null,
    val message: String? = null,
    val duration: Duration = Duration.ZERO,
    val detail: String? = null,
)

data class SourceCheckResult(
    val source: MihonSource,
    val steps: List<StepOutcome>,
    val checkedAt: Instant,
) {
    val ok: Boolean get() = steps.none { !it.ok && !it.skipped }

    /** The first failure, which explains the result. */
    val failure: StepOutcome? get() = steps.firstOrNull { !it.ok && !it.skipped }

    val latency: Duration get() = steps.fold(Duration.ZERO) { sum, step -> sum.plus(step.duration) }
}

/**
 * Walks a source the way a reader would: list → series → chapters → pages → image. Used
 * by on-demand checks, scheduled health checks and the compatibility checker. Calls go
 * through [MihonSources], so they use the source's network profile and rate limits.
 *
 * A WebView source's failures are reported as [NEEDS_WEBVIEW], with a hint on enabling the
 * browser, while no browser is configured. With one they keep their own reason: the source
 * is runnable, and a failure is a real one.
 */
class SourceProber(
    private val sources: MihonSources,
    private val webViewConfigured: Boolean = false,
) {
    suspend fun probe(
        source: MihonSource,
        requested: Set<CheckStepKind>,
        searchQuery: String? = null,
    ): SourceCheckResult {
        val steps = withPrerequisites(requested.ifEmpty { setOf(CheckStepKind.POPULAR) }, searchQuery)
        val walk = Walk(source, steps, searchQuery?.takeIf { it.isNotBlank() })
        walk.all()
        return SourceCheckResult(source, walk.results, Instant.now())
    }

    /** One check of one source; what each step finds is kept for the next. */
    private inner class Walk(
        private val source: MihonSource,
        private val steps: Set<CheckStepKind>,
        private val searchQuery: String?,
    ) {
        val results = mutableListOf<StepOutcome>()
        private var series: SManga? = null
        private var title: String? = null
        private var chapter: SChapter? = null
        private var pageRef: String? = null

        suspend fun all() {
            popular()
            latest()
            search()
            details()
            chapters()
            pages()
            image()
        }

        private suspend fun popular() =
            run(CheckStepKind.POPULAR, true) {
                val page = sources.call(source, "check_popular") { it.source.getPopularManga(1) }
                found(page.mangas)
                "${page.mangas.size} series"
            }

        private suspend fun latest() =
            run(CheckStepKind.LATEST, source.supportsLatest) {
                val page = sources.call(source, "check_latest") { it.source.getLatestUpdates(1) }
                found(page.mangas)
                "${page.mangas.size} series"
            }

        private suspend fun search() {
            val query = searchQuery ?: title
            run(CheckStepKind.SEARCH, query != null) {
                val page = sources.call(source, "check_search") { it.source.getSearchManga(1, query!!, FilterList()) }
                found(page.mangas)
                "${page.mangas.size} series for \"$query\""
            }
        }

        private suspend fun details() =
            run(CheckStepKind.DETAILS, series != null) {
                val manga = sources.call(source, "check_details") { it.source.getMangaUpdate(series!!, emptyList(), true, false) }.manga
                "\"${manga.title}\""
            }

        private suspend fun chapters() =
            run(CheckStepKind.CHAPTERS, series != null) {
                val list = sources.call(source, "check_chapters") { it.source.getMangaUpdate(series!!, emptyList(), false, true) }.chapters
                chapter = list.lastOrNull()
                "${list.size} chapters"
            }

        private suspend fun pages() =
            run(CheckStepKind.PAGES, chapter != null) {
                val list = sources.call(source, "check_pages") { it.source.getPageList(chapter!!) }
                pageRef = list.firstOrNull()?.let { Mapping.encodeRef(Mapping.PageRef(it.index, it.url, it.imageUrl)) }
                "${list.size} pages"
            }

        private suspend fun image() =
            run(CheckStepKind.IMAGE, pageRef != null) {
                sources.openImage(source, pageRef!!, operation = "check_image").use { response ->
                    val bytes = response.body.byteStream().use { it.readNBytes(IMAGE_PROBE_BYTES) }
                    "${response.body.contentType() ?: "unknown type"}, ${bytes.size} bytes read"
                }
            }

        private fun found(list: List<SManga>) {
            series = series ?: list.firstOrNull()
            title = title ?: list.firstOrNull()?.title
        }

        private suspend fun run(
            step: CheckStepKind,
            ready: Boolean,
            block: suspend () -> String,
        ) {
            if (step !in steps) return
            if (!ready) {
                results +=
                    StepOutcome(step, ok = false, skipped = true, message = "nothing to check: an earlier step found nothing or failed")
                return
            }
            val started = System.nanoTime()
            results +=
                try {
                    StepOutcome(step, ok = true, duration = since(started), detail = block())
                } catch (e: StatusException) {
                    val needsBrowser = source.needsWebView && !webViewConfigured
                    StepOutcome(
                        step,
                        ok = false,
                        reason = if (needsBrowser) NEEDS_WEBVIEW else SourceErrors.outcome(e),
                        message = if (needsBrowser) "$WEBVIEW_HINT (${e.status.description})" else e.status.description,
                        duration = since(started),
                    )
                }
        }
    }

    /** Adds the steps a requested step needs to run at all. */
    private fun withPrerequisites(
        requested: Set<CheckStepKind>,
        searchQuery: String?,
    ): Set<CheckStepKind> {
        val steps = requested.toMutableSet()
        if (CheckStepKind.IMAGE in steps) steps += CheckStepKind.PAGES
        if (CheckStepKind.PAGES in steps) steps += CheckStepKind.CHAPTERS
        val needsSeries = CheckStepKind.DETAILS in steps || CheckStepKind.CHAPTERS in steps
        val hasList = steps.any { it == CheckStepKind.POPULAR || it == CheckStepKind.LATEST || it == CheckStepKind.SEARCH }
        if (needsSeries && !hasList) steps += CheckStepKind.POPULAR
        if (CheckStepKind.SEARCH in steps && searchQuery.isNullOrBlank()) steps += CheckStepKind.POPULAR
        return steps
    }

    private fun since(started: Long): Duration = Duration.ofNanos(System.nanoTime() - started)

    companion object {
        /** The failure reason of a WebView source while no browser is configured. */
        const val NEEDS_WEBVIEW = "NEEDS_WEBVIEW"

        /** How to make a WebView source work, shown with [NEEDS_WEBVIEW]. */
        const val WEBVIEW_HINT =
            "this source needs a WebView: run the optional browser service (a headless Chromium) and set MIHON_WEBVIEW_URL to it"
        private const val IMAGE_PROBE_BYTES = 64 * 1024
    }
}
