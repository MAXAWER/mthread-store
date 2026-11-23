package app.mthread.store

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.WorkManager
import app.mthread.store.install.formatSize
import app.mthread.store.ui.MainActivity
import java.util.UUID

object Notifications {
    private const val CH_PROGRESS = "progress"
    private const val CH_INSTALL = "install"
    private const val CH_UPDATES = "updates"
    private const val ID_UPDATES = 1

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannels(listOf(
            NotificationChannel(CH_PROGRESS, "Скачивание", NotificationManager.IMPORTANCE_LOW),
            NotificationChannel(CH_INSTALL, "Установка", NotificationManager.IMPORTANCE_HIGH),
            NotificationChannel(CH_UPDATES, "Обновления", NotificationManager.IMPORTANCE_DEFAULT),
        ))
    }

    fun progressId(appId: String) = 1000 + (appId.hashCode() and 0xFFFF)
    private fun resultId(appId: String) = 100_000 + (appId.hashCode() and 0xFFFF)

    fun progress(context: Context, name: String, done: Long?, total: Long?, workId: UUID): Notification {
        val cancel = WorkManager.getInstance(context).createCancelPendingIntent(workId)
        val builder = NotificationCompat.Builder(context, CH_PROGRESS)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(name)
            .setContentIntent(openApp(context))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, "Отмена", cancel)
        if (done != null && total != null && total > 0) {
            builder.setContentText("${formatSize(done)} из ${formatSize(total)}")
                .setProgress(1000, (done * 1000 / total).toInt(), false)
        } else {
            builder.setContentText("Подготовка…").setProgress(0, 0, true)
        }
        return builder.build()
    }

    fun confirmInstall(context: Context, appId: String, name: String, confirm: Intent) {
        val pi = PendingIntent.getActivity(context, appId.hashCode(), confirm,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        notify(context, resultId(appId), NotificationCompat.Builder(context, CH_INSTALL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("$name готово к установке")
            .setContentText("Нажмите, чтобы установить")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
    }

    fun cancelConfirm(context: Context, appId: String) = NotificationManagerCompat.from(context).cancel(resultId(appId))

    fun installed(context: Context, appId: String, name: String) {
        val launch = context.packageManager.getLaunchIntentForPackage(appId)
        val pi = launch?.let { PendingIntent.getActivity(context, appId.hashCode(), it, PendingIntent.FLAG_IMMUTABLE) }
        notify(context, resultId(appId), NotificationCompat.Builder(context, CH_INSTALL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("$name установлено")
            .setContentIntent(pi ?: openApp(context))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build())
    }

    fun failed(context: Context, appId: String, name: String, message: String) {
        notify(context, resultId(appId), NotificationCompat.Builder(context, CH_INSTALL)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle("$name: не получилось")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build())
    }

    fun updatesAvailable(context: Context, names: List<String>) {
        val title = if (names.size == 1) "Доступно обновление" else "Доступно обновлений: ${names.size}"
        notify(context, ID_UPDATES, NotificationCompat.Builder(context, CH_UPDATES)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(names.joinToString(", "))
            .setContentIntent(openApp(context))
            .setAutoCancel(true)
            .build())
    }

    fun cancelUpdates(context: Context) = NotificationManagerCompat.from(context).cancel(ID_UPDATES)

    private fun openApp(context: Context) = PendingIntent.getActivity(context, 0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE)

    @SuppressLint("MissingPermission")
    private fun notify(context: Context, id: Int, notification: Notification) {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, notification)
    }
}
