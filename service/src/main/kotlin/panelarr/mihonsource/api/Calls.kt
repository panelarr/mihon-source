package panelarr.mihonsource.api

import com.google.protobuf.Timestamp
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.time.Instant

private val log = LoggerFactory.getLogger("panelarr.mihonsource.api")

/**
 * Runs the body of an RPC and turns the exceptions our code throws into gRPC statuses,
 * so service methods can use plain `require` and not-found exceptions.
 */
@Suppress("SwallowedException") // The message is carried into the status; the stack trace isn't useful to callers.
internal suspend fun <T> rpc(block: suspend () -> T): T =
    try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: StatusException) {
        throw e
    } catch (e: IllegalArgumentException) {
        throw Status.INVALID_ARGUMENT.withDescription(e.message).asException()
    } catch (e: NoSuchElementException) {
        throw Status.NOT_FOUND.withDescription(e.message).asException()
    } catch (e: IllegalStateException) {
        throw Status.FAILED_PRECONDITION.withDescription(e.message).asException()
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        log.error("Unexpected error in RPC", e)
        throw Status.INTERNAL.withDescription("internal error").asException()
    }

internal fun Instant.toTimestamp(): Timestamp =
    Timestamp
        .newBuilder()
        .setSeconds(epochSecond)
        .setNanos(nano)
        .build()
