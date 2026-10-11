// SPDX-License-Identifier: GPL-3.0-or-later

package eu.kanade.tachiyomi.source.model

/** A page of a series listing. A regular class since lib 1.6, with the old data-class members kept. */
class MangasPage(
    val mangas: List<SManga>,
    val hasNextPage: Boolean,
) {
    @Deprecated("Use the properties")
    operator fun component1(): List<SManga> = mangas

    @Deprecated("Use the properties")
    operator fun component2(): Boolean = hasNextPage

    @Deprecated("Create a new instance")
    fun copy(
        mangas: List<SManga> = this.mangas,
        hasNextPage: Boolean = this.hasNextPage,
    ): MangasPage = MangasPage(mangas, hasNextPage)

    override fun equals(other: Any?): Boolean = other is MangasPage && other.mangas == mangas && other.hasNextPage == hasNextPage

    override fun hashCode(): Int = 31 * mangas.hashCode() + hasNextPage.hashCode()

    override fun toString(): String = "MangasPage(mangas=$mangas, hasNextPage=$hasNextPage)"
}

class SMangaUpdate(
    val manga: SManga,
    val chapters: List<SChapter>,
)
