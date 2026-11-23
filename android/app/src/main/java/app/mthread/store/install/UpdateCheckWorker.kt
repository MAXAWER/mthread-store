package app.mthread.store.install

import android.content.Context
import android.os.Build
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mthread.store.Notifications
import app.mthread.store.StoreApp
import app.mthread.store.data.Catalog
import app.mthread.store.data.Installed
import java.util.concurrent.TimeUnit

/** По расписанию смотрит каталог: ставит обновления сам или напоминает о них. */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = applicationContext as StoreApp
        val catalog = runCatching { store.catalog.fetch() }.getOrElse { return Result.retry() }
        AutoUpdate.run(applicationContext, catalog)
        return Result.success()
    }

    companion object {
        private const val PERIODIC = "update-check"
        private const val NOW = "update-check-now"
        const val TAG = "update-check"

        /** Пересоздаёт расписание по текущим настройкам. */
        fun schedule(context: Context, replace: Boolean = false) {
            val settings = (context.applicationContext as StoreApp).settings
            val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(settings.checkIntervalHours.toLong(), TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC,
                if (replace) ExistingPeriodicWorkPolicy.UPDATE else ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        fun checkNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<UpdateCheckWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** Общая логика автообновлений — и для фоновой проверки, и для открытия Store. */
object AutoUpdate {
    fun run(context: Context, catalog: Catalog) {
        val settings = (context.applicationContext as StoreApp).settings
        settings.lastCheck = System.currentTimeMillis()
        val updates = catalog.apps.filter { Installed.hasUpdate(it, Installed.versionCode(context, it.id)) }

        // Без вопросов Android даёт обновлять только то, что поставил сам Store, и только с Android 12.
        val auto = if (settings.autoUpdate) {
            updates.filter {
                it.id !in settings.excluded &&
                    (Build.VERSION.SDK_INT >= 31 && Installed.installedByUs(context, it.id) || it.id == context.packageName)
            }
        } else {
            emptyList()
        }
        auto.forEach { InstallWorker.enqueue(context, it, wifiOnly = settings.wifiOnly, chargingOnly = settings.chargingOnly, auto = true) }

        val manual = updates - auto.toSet()
        val key = manual.joinToString(",") { "${it.id}:${it.versionCode}" }
        when {
            manual.isEmpty() || !settings.notifyUpdates -> Notifications.cancelUpdates(context)
            key != settings.notifiedUpdates -> Notifications.updatesAvailable(context, manual.map { it.title })
        }
        settings.notifiedUpdates = key
    }
}
