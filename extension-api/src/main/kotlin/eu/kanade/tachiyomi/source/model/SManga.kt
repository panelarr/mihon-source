// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

@Suppress("PropertyName", "ktlint:standard:property-naming")
interface SManga : Serializable {
    var url: String
    var title: String
    var artist: String?
    var author: String?
    var description: String?
    var genre: String?
    var status: Int
    var thumbnail_url: String?
    var update_strategy: UpdateStrategy
    var memo: JsonObject
    var initialized: Boolean

    fun getGenres(): List<String>? =
        genre
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotBlank() }
            ?.distinct()

    fun copyFrom(other: SManga) {
        if (other.author != null) author = other.author
        if (other.artist != null) artist = other.artist
        if (other.description != null) description = other.description
        if (other.genre != null) genre = other.genre
        if (other.thumbnail_url != null) thumbnail_url = other.thumbnail_url
        status = other.status
        update_strategy = other.update_strategy
        if (!initialized) initialized = other.initialized
    }

    fun copy(): SManga = create().also { copy ->
        copy.url = url
        copy.title = title
        copy.artist = artist
        copy.author = author
        copy.description = description
        copy.genre = genre
        copy.status = status
        copy.thumbnail_url = thumbnail_url
        copy.update_strategy = update_strategy
        copy.memo = memo
        copy.initialized = initialized
    }

    companion object {
        const val UNKNOWN = 0
        const val ONGOING = 1
        const val COMPLETED = 2
        const val LICENSED = 3
        const val PUBLISHING_FINISHED = 4
        const val CANCELLED = 5
        const val ON_HIATUS = 6

        fun create(): SManga = SMangaImpl()
    }
}

@Suppress("PropertyName", "ktlint:standard:property-naming")
class SMangaImpl : SManga {
    override lateinit var url: String
    override lateinit var title: String
    override var artist: String? = null
    override var author: String? = null
    override var description: String? = null
    override var genre: String? = null
    override var status: Int = 0
    override var thumbnail_url: String? = null
    override var update_strategy: UpdateStrategy = UpdateStrategy.ALWAYS_UPDATE
    override var memo: JsonObject = JsonObject(emptyMap())
    override var initialized: Boolean = false

    /** Whether [url] and [title] were set; extensions sometimes leave them out. */
    fun hasUrl(): Boolean = this::url.isInitialized

    fun hasTitle(): Boolean = this::title.isInitialized
}

enum class UpdateStrategy {
    ALWAYS_UPDATE,
    ONLY_FETCH_ONCE,
}
