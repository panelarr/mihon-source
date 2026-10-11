// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.source.model

import kotlinx.serialization.json.JsonObject
import java.io.Serializable

@Suppress("PropertyName", "ktlint:standard:property-naming")
interface SChapter : Serializable {
    var url: String
    var name: String
    var date_upload: Long
    var chapter_number: Float
    var scanlator: String?
    var memo: JsonObject

    fun copyFrom(other: SChapter) {
        name = other.name
        url = other.url
        date_upload = other.date_upload
        chapter_number = other.chapter_number
        scanlator = other.scanlator
        memo = other.memo
    }

    companion object {
        fun create(): SChapter = SChapterImpl()
    }
}

@Suppress("PropertyName", "ktlint:standard:property-naming")
class SChapterImpl : SChapter {
    override lateinit var url: String
    override lateinit var name: String
    override var date_upload: Long = 0
    override var chapter_number: Float = -1f
    override var scanlator: String? = null
    override var memo: JsonObject = JsonObject(emptyMap())

    fun hasUrl(): Boolean = this::url.isInitialized

    fun hasName(): Boolean = this::name.isInitialized
}
