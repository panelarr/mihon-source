// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat.check

import java.nio.file.Path

/** What to check. Built from command-line arguments by [Options.parse]. */
data class Options(
    val url: String = "http://localhost:8902",
    val token: String? = null,
    /** Add this store first, unless it's already added. */
    val store: String? = null,
    val selection: Selection = Selection.All,
    val lang: String? = null,
    val query: String? = null,
    /** Steps to run after loading. Empty means load only. */
    val checks: List<String> = emptyList(),
    val searchQuery: String? = null,
    val concurrency: Int = DEFAULT_CONCURRENCY,
    /** Keep the extensions this run installed. By default they're uninstalled again. */
    val keep: Boolean = false,
    val out: Path? = null,
) {
    sealed interface Selection {
        data object All : Selection

        data class Random(
            val count: Int,
            val seed: Long?,
        ) : Selection

        data class Packages(
            val names: List<String>,
        ) : Selection
    }

    companion object {
        const val DEFAULT_CONCURRENCY = 4
        val STEPS = listOf("popular", "latest", "search", "details", "chapters", "pages", "image")

        val USAGE = """Usage: compat-check [options]

Installs extensions from the stores of a running mihon-source, checks that they load,
optionally runs source checks, and reports what works.

  --url URL            the service (default http://localhost:8902)
  --token TOKEN        admin token (default: ${"$"}MIHON_ADMIN_TOKEN)
  --store URL          add this store first, if it isn't added yet
  --all                every offered extension (default)
  --random N           N random extensions (--seed S to repeat a pick)
  --pkg A,B            these package names
  --lang L             only extensions with a source in this language
  --query TEXT         only extensions whose name matches
  --checks LIST        steps to run after loading: none (default), all, or a list of
                       popular,latest,search,details,chapters,pages,image
  --search-query Q     query for the search step (default: the first popular title)
  --concurrency N      extensions handled at once (default 4)
  --keep               keep what this run installed (default: uninstall it again)
  --out FILE           report path (default build/compat-reports/<time>.json)
"""

        fun parse(
            args: List<String>,
            env: Map<String, String> = System.getenv(),
        ): Options {
            var options = Options(token = env["MIHON_ADMIN_TOKEN"])
            var seed: Long? = null
            val queue = ArrayDeque(args)

            fun value(flag: String): String = queue.removeFirstOrNull() ?: throw IllegalArgumentException("$flag needs a value")
            while (queue.isNotEmpty()) {
                when (val flag = queue.removeFirst()) {
                    "--url" -> options = options.copy(url = value(flag).trimEnd('/'))
                    "--token" -> options = options.copy(token = value(flag))
                    "--store" -> options = options.copy(store = value(flag))
                    "--all" -> options = options.copy(selection = Selection.All)
                    "--random" -> options = options.copy(selection = Selection.Random(positive(flag, value(flag)), null))
                    "--seed" -> seed = value(flag).toLongOrNull() ?: throw IllegalArgumentException("--seed needs a number")
                    "--pkg" -> options = options.copy(selection = Selection.Packages(list(value(flag))))
                    "--lang" -> options = options.copy(lang = value(flag))
                    "--query" -> options = options.copy(query = value(flag))
                    "--checks" -> options = options.copy(checks = checks(value(flag)))
                    "--search-query" -> options = options.copy(searchQuery = value(flag))
                    "--concurrency" -> options = options.copy(concurrency = positive(flag, value(flag)))
                    "--keep" -> options = options.copy(keep = true)
                    "--out" -> options = options.copy(out = Path.of(value(flag)))
                    "-h", "--help" -> throw HelpRequested()
                    else -> throw IllegalArgumentException("unknown option $flag")
                }
            }
            val selection = options.selection
            if (selection is Selection.Random) options = options.copy(selection = selection.copy(seed = seed))
            if (options.token.isNullOrBlank()) throw IllegalArgumentException("an admin token is needed (--token or MIHON_ADMIN_TOKEN)")
            return options
        }

        private fun list(value: String) = value.split(',').map { it.trim() }.filter { it.isNotEmpty() }

        private fun positive(
            flag: String,
            value: String,
        ): Int = value.toIntOrNull()?.takeIf { it > 0 } ?: throw IllegalArgumentException("$flag needs a positive number")

        private fun checks(value: String): List<String> =
            when (value.trim().lowercase()) {
                "none", "" -> {
                    emptyList()
                }

                "all" -> {
                    STEPS
                }

                else -> {
                    list(value.lowercase()).onEach { step ->
                        require(step in STEPS) { "unknown check '$step' (known: ${STEPS.joinToString()})" }
                    }
                }
            }
    }
}

class HelpRequested : RuntimeException()
