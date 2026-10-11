// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.store

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.protobuf.ProtoNumber

// The Mihon extension store formats. Field numbers and names follow the published index
// format; jarUrl (field 501) is an optional extension some stores add.

/** `repo.json`: points at the index and names the store's signing key. */
@Serializable
data class RepoJson(
    @SerialName("index_v2") val indexV2: String? = null,
    val meta: Meta? = null,
) {
    @Serializable
    data class Meta(
        val name: String = "",
        val shortName: String? = null,
        val website: String = "",
        val signingKeyFingerprint: String = "",
    )
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class StoreIndex(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val badgeLabel: String = "",
    @ProtoNumber(3) val signingKey: String = "",
    @ProtoNumber(4) val contact: Contact? = null,
    @ProtoNumber(101) val extensionList: ExtensionList? = null,
    @ProtoNumber(102) val extensionListUrl: String? = null,
) {
    @Serializable
    data class Contact(
        @ProtoNumber(1) val website: String = "",
        @ProtoNumber(2) val discord: String? = null,
    )
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ExtensionList(
    @ProtoNumber(1) val extensions: List<IndexExtension> = emptyList(),
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class IndexExtension(
    @ProtoNumber(1) val name: String = "",
    @ProtoNumber(2) val packageName: String = "",
    @ProtoNumber(3) val resources: Resources = Resources(),
    @ProtoNumber(4) val extensionLib: String = "",
    @ProtoNumber(5) @Serializable(with = FlexibleLong::class) val versionCode: Long = 0,
    @ProtoNumber(6) val versionName: String = "",
    @ProtoNumber(7) val contentWarning: IndexContentWarning = IndexContentWarning.CONTENT_WARNING_UNSPECIFIED,
    @ProtoNumber(8) val sources: List<IndexSource> = emptyList(),
) {
    @Serializable
    data class Resources(
        @ProtoNumber(1) val apkUrl: String = "",
        @ProtoNumber(2) val iconUrl: String = "",
        @ProtoNumber(501) val jarUrl: String = "",
    )
}

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class IndexSource(
    @ProtoNumber(1) @Serializable(with = FlexibleLong::class) val id: Long = 0,
    @ProtoNumber(2) val name: String = "",
    @ProtoNumber(3) val language: String = "",
    @ProtoNumber(4) val homeUrl: String = "",
    @ProtoNumber(5) val mirrorUrls: List<String> = emptyList(),
    @ProtoNumber(7) val message: String? = null,
)

@OptIn(ExperimentalSerializationApi::class)
@Serializable
enum class IndexContentWarning {
    @ProtoNumber(0)
    CONTENT_WARNING_UNSPECIFIED,

    @ProtoNumber(1)
    CONTENT_WARNING_SAFE,

    @ProtoNumber(2)
    CONTENT_WARNING_MIXED,

    @ProtoNumber(3)
    CONTENT_WARNING_NSFW,
}

/** Legacy `index.min.json` entry. */
@Serializable
data class LegacyExtension(
    val name: String,
    val pkg: String,
    val apk: String,
    val lang: String = "",
    @Serializable(with = FlexibleLong::class) val code: Long = 0,
    val version: String = "",
    val nsfw: Int = 0,
    val sources: List<LegacySource> = emptyList(),
) {
    @Serializable
    data class LegacySource(
        val name: String = "",
        val lang: String = "",
        @Serializable(with = FlexibleLong::class) val id: Long = 0,
        val baseUrl: String = "",
    )
}

/** int64 values arrive as JSON strings (protobuf JSON mapping) or as plain numbers. */
object FlexibleLong : KSerializer<Long> {
    override val descriptor = PrimitiveSerialDescriptor("FlexibleLong", PrimitiveKind.LONG)

    override fun serialize(
        encoder: Encoder,
        value: Long,
    ) = encoder.encodeLong(value)

    override fun deserialize(decoder: Decoder): Long {
        if (decoder !is JsonDecoder) return decoder.decodeLong()
        val primitive = decoder.decodeJsonElement().jsonPrimitive
        return if (primitive.isString) primitive.content.toLong() else primitive.long
    }
}
