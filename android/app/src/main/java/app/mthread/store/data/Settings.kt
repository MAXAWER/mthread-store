package app.mthread.store.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class HistoryEntry(val id: String, val name: String, val version: String, val time: Long)

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    /** Сами ставить обновления (тихо — на Android 12+). */
    var autoUpdate: Boolean
        get() = prefs.getBoolean("auto_update", true)
        set(value) = prefs.edit().putBoolean("auto_update", value).apply()

    /** Автообновления только по Wi-Fi — тяжёлые APK не съедят мобильный трафик. */
    var wifiOnly: Boolean
        get() = prefs.getBoolean("wifi_only", true)
        set(value) = prefs.edit().putBoolean("wifi_only", value).apply()

    var chargingOnly: Boolean
        get() = prefs.getBoolean("charging_only", false)
        set(value) = prefs.edit().putBoolean("charging_only", value).apply()

    var notifyUpdates: Boolean
        get() = prefs.getBoolean("notify_updates", true)
        set(value) = prefs.edit().putBoolean("notify_updates", value).apply()

    var checkIntervalHours: Int
        get() = prefs.getInt("check_interval", 6)
        set(value) = prefs.edit().putInt("check_interval", value).apply()

    var lastCheck: Long
        get() = prefs.getLong("last_check", 0)
        set(value) = prefs.edit().putLong("last_check", value).apply()

    /** Приложения, которые не надо обновлять автоматически. */
    var excluded: Set<String>
        get() = prefs.getStringSet("excluded", emptySet()).orEmpty()
        set(value) = prefs.edit().putStringSet("excluded", value).apply()

    /** О каких обновлениях уже напоминали, чтобы не дёргать при каждой проверке. */
    var notifiedUpdates: String
        get() = prefs.getString("notified_updates", "") ?: ""
        set(value) = prefs.edit().putString("notified_updates", value).apply()

    val history: List<HistoryEntry>
        get() = runCatching { json.decodeFromString<List<HistoryEntry>>(prefs.getString("history", "[]")!!) }.getOrDefault(emptyList())

    fun addHistory(entry: HistoryEntry) {
        val list = (listOf(entry) + history).take(30)
        prefs.edit().putString("history", json.encodeToString(list)).apply()
    }

    companion object {
        val INTERVALS = listOf(1, 3, 6, 12, 24)
    }
}
