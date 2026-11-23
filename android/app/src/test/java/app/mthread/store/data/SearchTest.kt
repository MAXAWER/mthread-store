package app.mthread.store.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SearchTest {
    private val apps = listOf(
        CatalogApp(id = "app.cladroid", name = "Claude Code", short = "Агент для кода", category = "Разработка"),
        CatalogApp(id = "app.maps", name = "Карты", short = "Офлайн-навигация", description = "Маршруты без интернета"),
        CatalogApp(id = "app.notes", name = "Заметки", short = "Быстрые записи"),
    )

    private fun ids(q: String) = Search.filter(apps, q).map { it.id }

    @Test fun emptyQueryKeepsAll() = assertEquals(3, ids("  ").size)
    @Test fun nameMatch() = assertEquals(listOf("app.cladroid"), ids("claude"))
    @Test fun caseAndWordsAnyOrder() = assertEquals(listOf("app.cladroid"), ids("CODE claude"))
    @Test fun wrongKeyboardLayout() = assertEquals(listOf("app.cladroid"), ids("сдфгву"))
    @Test fun russianLayoutTypedInEnglish() = assertEquals(listOf("app.maps"), ids("rfhns"))
    @Test fun typo() = assertEquals(listOf("app.notes"), ids("заметкм"))
    @Test fun description() = assertEquals(listOf("app.maps"), ids("маршруты"))
    @Test fun category() = assertEquals(listOf("app.cladroid"), ids("разработка"))
    @Test fun nameRanksAboveDescription() {
        val list = listOf(
            CatalogApp(id = "a", name = "Погода", description = "карты осадков"),
            CatalogApp(id = "b", name = "Карты"),
        )
        assertEquals(listOf("b", "a"), Search.filter(list, "карты").map { it.id })
    }
}
