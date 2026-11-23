package app.mthread.store.install

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.StatFs
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.mthread.store.Notifications
import app.mthread.store.StoreApp
import app.mthread.store.data.CatalogApp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.TimeUnit

/** Скачивает и ставит одно приложение. Работает в фоне с уведомлением — можно свернуть Store. */
class InstallWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val appId get() = inputData.getString(KEY_APP_ID).orEmpty()
    private var appName = ""

    override suspend fun getForegroundInfo() = foregroundInfo(null, null)

    override suspend fun doWork(): Result {
        val store = applicationContext as StoreApp
        val catalog = runCatching { store.catalog.fetch() }.getOrNull() ?: store.catalog.cached()
        val app = catalog?.apps?.firstOrNull { it.id == appId }
            ?: return fail("Приложение пропало из каталога")
        appName = app.title
        runCatching { setForeground(foregroundInfo(0, app.size)) }

        val dir = File(applicationContext.filesDir, "downloads").apply { mkdirs() }
        val file = File(dir, "${app.id}-${app.versionCode}.part")
        dir.listFiles()?.filter { it.name.startsWith("${app.id}-") && it != file }?.forEach { it.delete() }

        // Нужно место под файл и под копию, которую делает системный установщик.
        val need = (app.size - file.length()) + app.size + 50L * 1024 * 1024
        val free = StatFs(dir.path).availableBytes
        if (free < need) return fail("Недостаточно места: нужно ещё ${formatSize(need - free)}")

        return try {
            Downloader.download(app.url, file, app.size) { done, total -> report(PHASE_DOWNLOAD, done, total) }
            report(PHASE_INSTALL, 0, app.size)
            // Подписываемся на итог до commit, чтобы не пропустить быстрый ответ системы.
            val event = coroutineScope {
                val result = async(start = CoroutineStart.UNDISPATCHED) {
                    InstallEvents.events.first { it.appId == app.id }
                }
                Installer.install(applicationContext, app, file) { done, total -> report(PHASE_INSTALL, done, total) }
                file.delete()
                setProgress(workDataOf(KEY_PHASE to PHASE_CONFIRM))
                withTimeoutOrNull(TimeUnit.MINUTES.toMillis(15)) { result.await() }.also { result.cancel() }
            }
            when {
                event == null || event.success -> Result.success()
                else -> Result.failure(workDataOf(KEY_ERROR to event.message))
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            val corrupted = e.message?.startsWith("Файл повреждён") == true
            if (corrupted) file.delete()
            // Сетевые сбои повторяем (докачка продолжит с места обрыва), остальное — сразу ошибка.
            if (e is java.io.IOException && !corrupted && runAttemptCount < 3) Result.retry()
            else fail(e.message ?: e.javaClass.simpleName)
        }
    }

    private var lastPercent = -1

    private suspend fun report(phase: String, done: Long, total: Long) {
        val percent = if (total > 0) (done * 100 / total).toInt() else 0
        if (percent == lastPercent) return
        lastPercent = percent
        setProgress(workDataOf(KEY_PHASE to phase, KEY_DONE to done, KEY_TOTAL to total))
        if (phase == PHASE_DOWNLOAD) runCatching { setForeground(foregroundInfo(done, total)) }
    }

    private fun fail(message: String): Result {
        Notifications.failed(applicationContext, appId, appName.ifBlank { appId }, message)
        return Result.failure(workDataOf(KEY_ERROR to message))
    }

    private fun foregroundInfo(done: Long?, total: Long?): ForegroundInfo {
        val notification = Notifications.progress(applicationContext, appName.ifBlank { "Приложение" }, done, total, id)
        val notificationId = Notifications.progressId(appId)
        return if (Build.VERSION.SDK_INT >= 29) {
            ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    companion object {
        const val TAG = "install"
        const val TAG_AUTO = "auto"
        const val KEY_APP_ID = "app_id"
        const val KEY_PHASE = "phase"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_ERROR = "error"
        const val PHASE_DOWNLOAD = "download"
        const val PHASE_INSTALL = "install"
        const val PHASE_CONFIRM = "confirm"

        fun uniqueName(appId: String) = "install:$appId"
        fun appTag(appId: String) = "app:$appId"

        /** auto — фоновое автообновление: ждёт Wi-Fi/зарядку по настройкам и не торопит систему. */
        fun enqueue(
            context: Context,
            app: CatalogApp,
            wifiOnly: Boolean = false,
            chargingOnly: Boolean = false,
            auto: Boolean = false,
        ) {
            val request = OneTimeWorkRequestBuilder<InstallWorker>()
                .setInputData(workDataOf(KEY_APP_ID to app.id))
                .addTag(TAG)
                .addTag(appTag(app.id))
                .apply { if (auto) addTag(TAG_AUTO) }
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(if (wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                        .setRequiresCharging(chargingOnly)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .apply { if (!auto) setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST) }
                .build()
            // Автообновление не перебивает уже идущее; ручное «Скачать сейчас» заменяет ждущее Wi-Fi.
            val policy = if (auto) ExistingWorkPolicy.KEEP else ExistingWorkPolicy.REPLACE
            WorkManager.getInstance(context).enqueueUniqueWork(uniqueName(app.id), policy, request)
        }

        fun cancel(context: Context, appId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(uniqueName(appId))
        }
    }
}

fun formatSize(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.2f ГБ".format(bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "%.1f МБ".format(bytes / (1L shl 20).toDouble())
    else -> "%d КБ".format(bytes / 1024)
}
