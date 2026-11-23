package app.mthread.store

import android.app.Application
import app.mthread.store.data.CatalogRepository
import app.mthread.store.data.Settings
import app.mthread.store.install.UpdateCheckWorker

class StoreApp : Application() {
    val catalog by lazy { CatalogRepository(this) }
    val settings by lazy { Settings(this) }

    // Раньше onCreate: ContentProvider-ы библиотек (WorkManager и др.) стартуют до него, их сбои тоже ловим.
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(base)
        CrashLog.install(this)
    }

    override fun onCreate() {
        super.onCreate()
        // Сбой фоновых настроек не должен ронять запуск — запишем и продолжим.
        runCatching {
            Notifications.createChannels(this)
            UpdateCheckWorker.schedule(this)
        }.onFailure { CrashLog.write(this, it, "запуск") }
    }
}
