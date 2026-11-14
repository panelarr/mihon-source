// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.webview

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.slf4j.LoggerFactory
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.Base64

internal fun jsonArrayOf(vararg items: JsonElement) = JsonArray(items.toList())

/** Headers as CDP's `[{name, value}]` list. */
internal fun headerArray(headers: Map<String, String>) =
    JsonArray(
        headers.map { (name, value) ->
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("value", JsonPrimitive(value))
            }
        },
    )

/** Answers a paused request (`Fetch.requestPaused`) with a response of our own. */
internal suspend fun CdpPage.fulfill(
    requestId: String,
    body: ByteArray,
    status: Int,
    phrase: String,
    headers: Map<String, String>,
) {
    send(
        "Fetch.fulfillRequest",
        buildJsonObject {
            put("requestId", JsonPrimitive(requestId))
            put("responseCode", JsonPrimitive(status))
            put("responsePhrase", JsonPrimitive(phrase))
            put("responseHeaders", headerArray(headers))
            put("body", JsonPrimitive(Base64.getEncoder().encodeToString(body)))
        },
    )
}

/** A request the browser paused for us (`Fetch.requestPaused`). */
internal data class PausedRequest(
    val id: String,
    val url: String,
    val method: String,
    val headers: Map<String, String>,
    val mainFrame: Boolean,
    val redirect: Boolean,
) {
    fun toWebResourceRequest(extraHeaders: Map<String, String>): WebResourceRequest =
        WebViewRequest(url, mainFrame, redirect, method, headers + extraHeaders)

    companion object {
        /** Null when the event lacks what's needed to answer it. */
        fun from(
            params: JsonObject,
            mainFrameId: String,
        ): PausedRequest? {
            val id = params.string("requestId") ?: return null
            val request = params["request"] as? JsonObject ?: return null
            val url = request.string("url") ?: return null
            val headers =
                (request["headers"] as? JsonObject)
                    ?.mapNotNull { (name, value) -> (value as? JsonPrimitive)?.contentOrNull?.let { name to it } }
                    ?.toMap()
                    .orEmpty()
            return PausedRequest(
                id = id,
                url = url + request.string("urlFragment").orEmpty(),
                method = request.string("method") ?: "GET",
                headers = headers,
                // The main frame of a page target has the target's id.
                mainFrame = params.string("resourceType") == "Document" && params.string("frameId") == mainFrameId,
                redirect = params["redirectedRequestId"] != null,
            )
        }
    }
}

internal class WebViewRequest(
    private val url: String,
    private val mainFrame: Boolean,
    private val redirect: Boolean = false,
    private val method: String = "GET",
    private val headers: Map<String, String> = emptyMap(),
) : WebResourceRequest {
    override fun getUrl(): Uri = Uri.parse(url)

    override fun isForMainFrame(): Boolean = mainFrame

    override fun isRedirect(): Boolean = redirect

    override fun hasGesture(): Boolean = false

    override fun getMethod(): String = method

    override fun getRequestHeaders(): Map<String, String> = headers
}

internal class WebViewError(
    private val code: Int,
    private val description: String,
) : WebResourceError() {
    override fun getErrorCode(): Int = code

    override fun getDescription(): CharSequence = description
}

/**
 * An object the extension exposed with `addJavascriptInterface`, as page JS sees it: a
 * global [name] whose methods send their arguments to a CDP binding. The binding is
 * one-way, so methods return `undefined` to the page whatever the Java method returns.
 */
internal class JsBridge private constructor(
    private val target: Any,
    val name: String,
    private val methods: List<Method>,
) {
    private val log = LoggerFactory.getLogger(JsBridge::class.java)

    /** The binding's global name: unlikely to clash with a site's own. */
    val binding: String = "__panelarrBridge_" + name.replace(Regex("[^A-Za-z0-9_$]"), "_")

    /** Defines the object in every new document, before the page's own scripts. */
    fun script(): String {
        val names = JsonArray(methods.map { it.name }.distinct().map(::JsonPrimitive))
        return """
            (function () {
              var send = globalThis[${JsonPrimitive(binding)}];
              if (typeof send !== 'function') return;
              var api = {};
              $names.forEach(function (m) {
                api[m] = function () { send(JSON.stringify({ m: m, a: Array.prototype.slice.call(arguments) })); };
              });
              Object.defineProperty(globalThis, ${JsonPrimitive(name)}, { value: api, configurable: true, writable: true });
            })();
            """.trimIndent()
    }

    /** Calls the method a binding payload names, with its arguments converted to the parameter types. */
    fun call(payload: String) {
        val message = runCatching { Json.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return
        val methodName = message.string("m") ?: return
        val args = (message["a"] as? JsonArray) ?: JsonArray(emptyList())
        val method = methods.firstOrNull { it.name == methodName && it.parameterCount == args.size } ?: return
        try {
            method.invoke(target, *method.parameterTypes.mapIndexed { i, type -> convert(args[i], type) }.toTypedArray())
        } catch (e: InvocationTargetException) {
            log.warn("The extension's JavaScript interface {}.{} failed: {}", name, methodName, e.targetException.javaClass.name)
        } catch (e: IllegalArgumentException) {
            log.warn("Couldn't call the JavaScript interface {}.{}: {}", name, methodName, e.message)
        } catch (e: IllegalAccessException) {
            log.warn("Couldn't call the JavaScript interface {}.{}: {}", name, methodName, e.message)
        }
    }

    companion object {
        fun of(
            target: Any,
            name: String,
        ): JsBridge {
            // Only methods marked @JavascriptInterface are exposed, as on Android since API 17.
            val exposed =
                target.javaClass.methods.filter { method ->
                    method.isAnnotationPresent(JavascriptInterface::class.java) ||
                        method.annotations.any { it.annotationClass.java.name == JavascriptInterface::class.java.name }
                }
            // Extensions often expose anonymous or private classes, which Android calls all the same.
            exposed.forEach { it.trySetAccessible() }
            return JsBridge(target, name, exposed)
        }

        @Suppress("CyclomaticComplexMethod")
        private fun convert(
            value: JsonElement,
            type: Class<*>,
        ): Any? {
            val primitive = value as? JsonPrimitive
            val number = primitive?.doubleOrNull ?: primitive?.booleanOrNull?.let { if (it) 1.0 else 0.0 } ?: 0.0
            return when (type) {
                String::class.java -> if (value is JsonNull) null else primitive?.contentOrNull ?: value.toString()
                Int::class.javaPrimitiveType, Int::class.javaObjectType -> number.toInt()
                Long::class.javaPrimitiveType, Long::class.javaObjectType -> number.toLong()
                Double::class.javaPrimitiveType, Double::class.javaObjectType -> number
                Float::class.javaPrimitiveType, Float::class.javaObjectType -> number.toFloat()
                Short::class.javaPrimitiveType, Short::class.javaObjectType -> number.toInt().toShort()
                Byte::class.javaPrimitiveType, Byte::class.javaObjectType -> number.toInt().toByte()
                Boolean::class.javaPrimitiveType, Boolean::class.javaObjectType -> primitive?.booleanOrNull ?: (number != 0.0)
                else -> null
            }
        }
    }
}

/**
 * Moves cookies between `android.webkit.CookieManager` and a page: before a load, the
 * manager's cookies for the URL go into the browser; after it, the page's cookies come back.
 * Values are never logged.
 */
internal object WebViewCookies {
    private val log = LoggerFactory.getLogger(WebViewCookies::class.java)

    /** Puts the manager's cookies for [url] into [page]'s context. */
    suspend fun push(
        page: CdpPage,
        url: String,
    ) {
        val cookies = forUrl(url)
        if (cookies.isEmpty()) return
        page.send("Network.setCookies", buildJsonObject { put("cookies", JsonArray(cookies)) })
    }

    /** Copies [page]'s cookies for [url] into the manager. */
    suspend fun pull(
        page: CdpPage,
        url: String,
    ) {
        try {
            store(url, page.send("Network.getCookies", buildJsonObject { put("urls", jsonArrayOf(JsonPrimitive(url))) }))
        } catch (e: CdpProtocolException) {
            log.debug("Couldn't read the page's cookies: {}", e.message)
        }
    }

    /** The manager's cookies for [url], as CDP `CookieParam`s. */
    fun forUrl(url: String): List<JsonObject> {
        val header = CookieManager.getInstance().getCookie(url) ?: return emptyList()
        return header.split(';').mapNotNull { pair ->
            val name = pair.substringBefore('=').trim()
            if (name.isEmpty() || '=' !in pair) return@mapNotNull null
            buildJsonObject {
                put("name", JsonPrimitive(name))
                put("value", JsonPrimitive(pair.substringAfter('=').trim()))
                put("url", JsonPrimitive(url))
            }
        }
    }

    /** Stores the cookies of a `Network.getCookies` answer in the manager. */
    fun store(
        url: String,
        result: JsonObject,
    ) {
        val cookies = result["cookies"]?.jsonArray ?: return
        val manager = CookieManager.getInstance()
        val now = System.currentTimeMillis() / MILLIS
        cookies.forEach { element ->
            val cookie = element as? JsonObject ?: return@forEach
            val name = cookie.string("name") ?: return@forEach
            val value = cookie.string("value") ?: return@forEach
            val text =
                buildString {
                    append(name).append('=').append(value)
                    cookie.string("path")?.let { append("; Path=").append(it) }
                    // A leading dot marks a domain cookie; without one it's for the host only.
                    cookie.string("domain")?.takeIf { it.startsWith(".") }?.let { append("; Domain=").append(it.removePrefix(".")) }
                    val session = (cookie["session"] as? JsonPrimitive)?.booleanOrNull ?: true
                    val expires = (cookie["expires"] as? JsonPrimitive)?.doubleOrNull
                    if (!session && expires != null && expires > 0) append("; Max-Age=").append((expires.toLong() - now).coerceAtLeast(0))
                    if ((cookie["secure"] as? JsonPrimitive)?.booleanOrNull == true) append("; Secure")
                    if ((cookie["httpOnly"] as? JsonPrimitive)?.booleanOrNull == true) append("; HttpOnly")
                }
            manager.setCookie(url, text)
        }
    }

    private const val MILLIS = 1000L
}
