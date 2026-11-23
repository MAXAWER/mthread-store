package app.mthread.store.data

/**
 * Поиск по каталогу: без учёта регистра и ё/е, понимает текст, набранный не в той раскладке
 * («сдфгву» → «claude»), транслит и опечатки в одну букву. Возвращает приложения по релевантности.
 */
object Search {
    fun filter(apps: List<CatalogApp>, query: String): List<CatalogApp> {
        val words = normalize(query).split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return apps
        return apps.mapNotNull { app -> score(app, words)?.let { app to it } }
            .sortedWith(compareByDescending<Pair<CatalogApp, Int>> { it.second }.thenBy { it.first.title.lowercase() })
            .map { it.first }
    }

    /** null — не подходит; иначе чем больше, тем выше в выдаче. Каждое слово запроса должно найтись. */
    private fun score(app: CatalogApp, words: List<String>): Int? {
        val fields = listOf(
            normalize(app.title) to 100,
            normalize(app.short) to 40,
            normalize(app.category) to 30,
            normalize(app.id) to 20,
            normalize(app.description) to 10,
        )
        var total = 0
        for (word in words) {
            val variants = variants(word)
            total += fields.maxOf { (text, weight) -> variants.maxOf { match(text, it) } * weight }
                .takeIf { it > 0 } ?: return null
        }
        return total
    }

    /** 3 — начало поля, 2 — начало слова, 1 — внутри слова или опечатка, 0 — нет. */
    private fun match(text: String, word: String): Int {
        if (text.isEmpty()) return 0
        if (text.startsWith(word)) return 3
        val tokens = text.split(' ', '.', '-', '_', ',', '/')
        if (tokens.any { it.startsWith(word) }) return 2
        if (text.contains(word)) return 1
        if (word.length >= 4 && tokens.any { closeEnough(it.take(word.length + 1), word) }) return 1
        return 0
    }

    private fun variants(word: String): Set<String> = setOf(word, swapLayout(word), translit(word)).filter { it.isNotEmpty() }.toSet()

    fun normalize(s: String) = s.lowercase().replace('ё', 'е').replace(Regex("[^\\p{L}\\p{N}.\\-_ ]"), " ")
        .replace(Regex("\\s+"), " ").trim()

    private const val EN = "qwertyuiop[]asdfghjkl;'zxcvbnm,."
    private const val RU = "йцукенгшщзхъфывапролджэячсмитьбю"

    private fun swapLayout(s: String) = s.map { c ->
        val i = EN.indexOf(c)
        if (i >= 0) RU[i] else RU.indexOf(c).let { j -> if (j >= 0) EN[j] else c }
    }.joinToString("")

    private val translitMap = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ж' to "zh", 'з' to "z",
        'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p",
        'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "h", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu", 'я' to "ya",
    )

    private fun translit(s: String) = s.map { translitMap[it] ?: it.toString() }.joinToString("")

    /** Расстояние Левенштейна не больше 1 (для коротких слов — точное совпадение). */
    private fun closeEnough(a: String, b: String): Boolean {
        if (kotlin.math.abs(a.length - b.length) > 1) return false
        var i = 0
        var j = 0
        var edits = 0
        while (i < a.length && j < b.length) {
            if (a[i] == b[j]) { i++; j++; continue }
            if (++edits > 1) return false
            when {
                a.length > b.length -> i++
                a.length < b.length -> j++
                else -> { i++; j++ }
            }
        }
        return edits + (a.length - i) + (b.length - j) <= 1
    }
}
