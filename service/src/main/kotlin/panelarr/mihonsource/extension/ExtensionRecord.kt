// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.extension

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What the service remembers about an installed extension (`extensions/<pkg>/extension.json`). */
@Serializable
data class ExtensionRecord(
    val pkgName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val libVersion: String,
    val format: Format,
    /** Fully qualified names of the classes that provide sources (Source or SourceFactory). */
    val classNames: List<String>,
    val nsfw: Boolean,
    val contentWarning: ContentWarning? = null,
    val signingFingerprint: String? = null,
    val sha256: String,
    val origin: Origin,
    val installedAt: String,
) {
    enum class Format { JAR, APK }

    enum class ContentWarning { SAFE, MIXED, NSFW }

    @Serializable
    sealed interface Origin {
        @Serializable
        @SerialName("url")
        data class Url(
            val url: String,
        ) : Origin

        @Serializable
        @SerialName("upload")
        data class Upload(
            val fileName: String,
        ) : Origin

        @Serializable
        @SerialName("store")
        data class Store(
            val storeId: String,
            val url: String,
        ) : Origin
    }
}

/** What an extension file says about itself, before it's installed. */
data class ExtensionDescriptor(
    val pkgName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val libVersion: String,
    val classNames: List<String>,
    val nsfw: Boolean,
    /** From the manifest (lib 1.6), when the extension declares it. */
    val contentWarning: ExtensionRecord.ContentWarning? = null,
)
