// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat.check

import kotlinx.serialization.Serializable

/** The result of one run, written as JSON. */
@Serializable
data class Report(
    val startedAt: String,
    val finishedAt: String,
    val service: String,
    val checks: List<String>,
    val extensions: List<ExtensionReport>,
) {
    val loaded: Int get() = extensions.count { it.status == Status.LOADED }
    val failed: Int get() = extensions.count { it.status != Status.LOADED }
}

@Serializable
data class ExtensionReport(
    val pkgName: String,
    val name: String,
    val version: String,
    val libVersion: String,
    val storeId: String,
    val status: Status,
    /** Short category of a load failure, e.g. "missing class", "unsupported lib". */
    val problem: String? = null,
    val detail: String? = null,
    val sources: List<SourceReport> = emptyList(),
)

@Serializable
data class SourceReport(
    val id: String,
    val name: String,
    val lang: String,
    val ok: Boolean,
    val steps: List<StepReport>,
)

@Serializable
data class StepReport(
    val step: String,
    val ok: Boolean,
    val skipped: Boolean = false,
    val reason: String? = null,
    val message: String? = null,
    val detail: String? = null,
    val millis: Long = 0,
)

@Serializable
enum class Status { LOADED, LOAD_ERROR, INSTALL_ERROR, UNSUPPORTED }

/** Sorts a load error into a few categories that point at the cause. */
object Problems {
    fun classify(error: String): String =
        when {
            "NoClassDefFoundError" in error || "ClassNotFoundException" in error -> "missing class"
            "NoSuchMethodError" in error || "NoSuchFieldError" in error || "AbstractMethodError" in error -> "missing member"
            "WebView" in error -> "needs webview"
            "VerifyError" in error || "ClassFormatError" in error -> "bad bytecode"
            "extensions-lib" in error -> "unsupported lib"
            "signing fingerprint" in error || "signature" in error -> "signature"
            else -> "error"
        }
}
