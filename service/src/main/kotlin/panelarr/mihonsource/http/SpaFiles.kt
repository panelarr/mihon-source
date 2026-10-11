// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.http

import com.linecorp.armeria.common.HttpHeaderNames
import com.linecorp.armeria.common.HttpRequest
import com.linecorp.armeria.common.HttpResponse
import com.linecorp.armeria.server.HttpService
import com.linecorp.armeria.server.ServiceRequestContext
import com.linecorp.armeria.server.file.FileService
import com.linecorp.armeria.server.file.HttpFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Serves the admin SPA: its files, and index.html for any other path so client-side
 * routes work on reload. Responses carry a strict Content-Security-Policy: the SPA has
 * no inline scripts or styles and talks only to this origin.
 */
class SpaFiles private constructor(
    private val files: FileService,
    private val index: HttpFile,
) {
    fun service(): HttpService =
        HttpService { ctx: ServiceRequestContext, req: HttpRequest ->
            ctx.addAdditionalResponseHeader(HttpHeaderNames.CONTENT_SECURITY_POLICY, CSP)
            val cache = if (ctx.path().startsWith("/assets/")) IMMUTABLE else NO_CACHE
            ctx.addAdditionalResponseHeader(HttpHeaderNames.CACHE_CONTROL, cache)
            files.orElse(index.asService()).serve(ctx, req)
        }

    companion object {
        const val CSP =
            "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' blob: data:; " +
                "connect-src 'self'; font-src 'self'; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'"
        private const val IMMUTABLE = "public, max-age=31536000, immutable"
        private const val NO_CACHE = "no-cache"

        /** The SPA from a directory (development) or from `admin/` on the classpath (the built jar). */
        fun find(directory: Path?): SpaFiles? {
            if (directory != null && Files.isRegularFile(directory.resolve("index.html"))) {
                return SpaFiles(
                    FileService.builder(directory).build(),
                    HttpFile.of(directory.resolve("index.html")),
                )
            }
            val loader = SpaFiles::class.java.classLoader
            if (loader.getResource("admin/index.html") == null) return null
            return SpaFiles(
                FileService.builder(loader, "admin").build(),
                HttpFile.of(loader, "admin/index.html"),
            )
        }
    }
}
