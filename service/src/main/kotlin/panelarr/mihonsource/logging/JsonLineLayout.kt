// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.logging

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.spi.ThrowableProxyUtil
import ch.qos.logback.core.LayoutBase
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Instant

/**
 * One JSON object per log line: `time` (ISO-8601), `level`, `logger`, `thread`, `msg`,
 * plus `error` with the stack trace when there is one, and any MDC entries.
 */
class JsonLineLayout : LayoutBase<ILoggingEvent>() {
    override fun doLayout(event: ILoggingEvent): String {
        val json =
            buildJsonObject {
                put("time", JsonPrimitive(Instant.ofEpochMilli(event.timeStamp).toString()))
                put("level", JsonPrimitive(event.level.toString()))
                put("logger", JsonPrimitive(event.loggerName))
                put("thread", JsonPrimitive(event.threadName))
                put("msg", JsonPrimitive(event.formattedMessage))
                event.throwableProxy?.let { put("error", JsonPrimitive(ThrowableProxyUtil.asString(it))) }
                event.mdcPropertyMap?.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
            }
        return json.toString() + System.lineSeparator()
    }
}
