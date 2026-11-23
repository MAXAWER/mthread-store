package app.mthread.store.data

import android.content.Context
import app.mthread.store.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class Catalog(
    val schema: Int = 1,
    val updated: String? = null,
    val apps: List<CatalogApp> = emptyList(),
)

@Serializable
data class CatalogApp(
    val id: String,
    val name: String = "",
    val short: String = "",
    val description: String = "",
    val changelog: String = "",
    val category: String = "",
    val icon: String? = null,
    val screenshots: List<String> = emptyList(),
    val versionCode: Long = 0,
    val versionName: String = "",
    val size: Long = 0,
    val sha256: String = "",
    val encrypted: Boolean = false,
    val iv: String? = null,
    val url: String = "",
    val updated: String? = null,
) {
    val title get() = name.ifBlank { id }
}

/** Каталог из GitHub-репозитория с локальной копией на случай, если нет сети. */
class CatalogRepository(context: Context) {
    private val cacheFile = File(context.filesDir, "catalog.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val repo = BuildConfig.STORE_REPO

    val configured get() = repo.isNotBlank()

    fun mediaUrl(path: String?): String? =
        path?.let { "https://raw.githubusercontent.com/$repo/HEAD/$it" }

    fun cached(): Catalog? = runCatching { json.decodeFromString<Catalog>(cacheFile.readText()) }.getOrNull()

    suspend fun fetch(): Catalog = withContext(Dispatchers.IO) {
        // API отдаёт свежую версию сразу после публикации; raw — запасной путь, если упёрлись в лимит (60 запросов/час).
        val text = runCatching {
            get("https://api.github.com/repos/$repo/contents/catalog.json", "application/vnd.github.raw")
        }.recoverCatching {
            get("https://raw.githubusercontent.com/$repo/HEAD/catalog.json?t=${System.currentTimeMillis()}", null)
        }.getOrThrow()
        val catalog = json.decodeFromString<Catalog>(text)
        cacheFile.writeText(text)
        catalog
    }

    private fun get(url: String, accept: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.useCaches = false
        accept?.let { conn.setRequestProperty("Accept", it) }
        try {
            if (conn.responseCode != 200) throw IOException("HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }
}
