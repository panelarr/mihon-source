// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator
import ch.qos.logback.classic.spi.LoggingEvent
import ch.qos.logback.core.ConsoleAppender
import ch.qos.logback.core.encoder.LayoutWrappingEncoder
import ch.qos.logback.core.status.Status
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The service's logback.xml, loaded the way the service loads it, survives Logback upgrades. */
class LogbackConfigTest {
    private fun configure(format: String?): LoggerContext {
        val context = LoggerContext()
        format?.let { context.putProperty("MIHON_LOG_FORMAT", it) }
        val config = assertNotNull(javaClass.getResource("/logback.xml"))
        JoranConfigurator().apply { this.context = context }.doConfigure(config)
        val errors = context.statusManager.copyOfStatusList.filter { it.level >= Status.ERROR }
        assertTrue(errors.isEmpty(), "logback.xml reported problems: $errors")
        return context
    }

    private fun root(context: LoggerContext): Logger = context.getLogger(Logger.ROOT_LOGGER_NAME)

    @Test
    fun `logs JSON lines by default`() {
        val context = configure(format = null)
        val root = root(context)
        assertEquals(Level.INFO, root.level)
        assertNull(root.getAppender("text"))
        val appender = assertIs<ConsoleAppender<*>>(root.getAppender("json"))
        val encoder = assertIs<LayoutWrappingEncoder<*>>(appender.encoder)
        assertIs<JsonLineLayout>(encoder.layout)

        val event = LoggingEvent("test", context.getLogger("panelarr.test"), Level.INFO, "hello {}", null, arrayOf("world"))
        val line = (encoder.layout as JsonLineLayout).doLayout(event)
        val json = Json.parseToJsonElement(line).jsonObject
        assertEquals("hello world", json.getValue("msg").jsonPrimitive.content)
        assertEquals("INFO", json.getValue("level").jsonPrimitive.content)
        context.stop()
    }

    @Test
    fun `logs plain text with MIHON_LOG_FORMAT=text`() {
        val context = configure(format = "text")
        val root = root(context)
        assertNull(root.getAppender("json"))
        assertIs<ConsoleAppender<*>>(root.getAppender("text"))
        context.stop()
    }
}
