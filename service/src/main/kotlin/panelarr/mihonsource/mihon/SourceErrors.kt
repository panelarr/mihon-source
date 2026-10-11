// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.mihon

import com.google.protobuf.Any
import com.google.protobuf.Duration
import com.google.rpc.Code
import com.google.rpc.ErrorInfo
import com.google.rpc.RetryInfo
import eu.kanade.tachiyomi.network.HttpException
import io.grpc.StatusException
import io.grpc.protobuf.StatusProto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import org.slf4j.LoggerFactory
import panelarr.mihonsource.network.ChallengeException
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeoutException

/**
 * Turns what a source throws into a gRPC status with a google.rpc.ErrorInfo whose reason
 * is one of the contract's (CLOUDFLARE_CHALLENGE, RATE_LIMITED, SOURCE_DOWN, NOT_FOUND,
 * LOGIN_REQUIRED, UNSUPPORTED).
 */
object SourceErrors {
    const val DOMAIN = "source.panelarr"
    private val log = LoggerFactory.getLogger(SourceErrors::class.java)
    private const val DEFAULT_RETRY_SECONDS = 30L

    @Suppress("CyclomaticComplexMethod")
    fun toStatus(
        error: Throwable,
        sourceId: Long,
    ): StatusException {
        if (error is StatusException) return error
        if (error is CancellationException && error !is TimeoutCancellationException) throw error
        val cause = rootCause(error)
        return when {
            error is TimeoutCancellationException || cause is SocketTimeoutException || cause is TimeoutException -> {
                status(Code.UNAVAILABLE, "SOURCE_DOWN", "the source timed out")
            }

            cause is ChallengeException -> {
                status(Code.UNAVAILABLE, "CLOUDFLARE_CHALLENGE", cause.message ?: "blocked by a challenge")
            }

            cause is HttpException -> {
                http(cause.code)
            }

            cause is UnsupportedOperationException || cause is NotImplementedError -> {
                status(Code.UNIMPLEMENTED, "UNSUPPORTED", cause.message?.takeIf { it.isNotBlank() } ?: "the source doesn't support this")
            }

            cause is UnknownHostException || cause is IOException -> {
                status(Code.UNAVAILABLE, "SOURCE_DOWN", "couldn't reach the source: ${cause.message}")
            }

            cause is IllegalArgumentException -> {
                status(Code.INVALID_ARGUMENT, null, cause.message ?: "invalid request")
            }

            else -> {
                log.warn("Source {} failed", sourceId, error)
                status(Code.INTERNAL, null, "the source failed: ${cause.javaClass.simpleName}: ${cause.message}")
            }
        }
    }

    private fun http(code: Int): StatusException =
        when (code) {
            HTTP_NOT_FOUND, HTTP_GONE -> {
                status(Code.NOT_FOUND, "NOT_FOUND", "the source answered HTTP $code")
            }

            HTTP_TOO_MANY -> {
                status(
                    Code.RESOURCE_EXHAUSTED,
                    "RATE_LIMITED",
                    "the source is rate limiting (HTTP 429)",
                    DEFAULT_RETRY_SECONDS,
                )
            }

            HTTP_UNAUTHORIZED -> {
                status(Code.UNAUTHENTICATED, "LOGIN_REQUIRED", "the source needs a login (HTTP 401)")
            }

            HTTP_FORBIDDEN -> {
                status(Code.PERMISSION_DENIED, "LOGIN_REQUIRED", "the source refused the request (HTTP 403)")
            }

            in SERVER_ERRORS -> {
                status(Code.UNAVAILABLE, "SOURCE_DOWN", "the source answered HTTP $code")
            }

            else -> {
                status(Code.UNKNOWN, null, "the source answered HTTP $code")
            }
        }

    fun notFound(message: String): StatusException = status(Code.NOT_FOUND, "NOT_FOUND", message)

    fun unsupported(message: String): StatusException = status(Code.UNIMPLEMENTED, "UNSUPPORTED", message)

    private fun status(
        code: Code,
        reason: String?,
        message: String,
        retryAfterSeconds: Long? = null,
    ): StatusException {
        val builder =
            com.google.rpc.Status
                .newBuilder()
                .setCode(code.number)
                .setMessage(message)
        if (reason != null) {
            builder.addDetails(
                Any.pack(
                    ErrorInfo
                        .newBuilder()
                        .setReason(reason)
                        .setDomain(DOMAIN)
                        .build(),
                ),
            )
        }
        if (retryAfterSeconds != null) {
            builder.addDetails(Any.pack(RetryInfo.newBuilder().setRetryDelay(Duration.newBuilder().setSeconds(retryAfterSeconds)).build()))
        }
        return StatusProto.toStatusException(builder.build())
    }

    private fun rootCause(error: Throwable): Throwable {
        var current = error
        while (true) {
            if (current is HttpException || current is ChallengeException) return current
            val next = current.cause ?: return current
            if (next === current) return current
            current = next
        }
    }

    private const val HTTP_UNAUTHORIZED = 401
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_GONE = 410
    private const val HTTP_TOO_MANY = 429
    private val SERVER_ERRORS = 500..599
}
