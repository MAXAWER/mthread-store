package app.mthread.store.install

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import app.mthread.store.Notifications
import app.mthread.store.StoreApp
import app.mthread.store.data.HistoryEntry
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Итог системной установки. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val appId = intent.getStringExtra(Installer.EXTRA_APP_ID) ?: return
        val name = intent.getStringExtra(Installer.EXTRA_APP_NAME) ?: appId
        val foreground = ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                } ?: return
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                // Из фона Android не даёт открыть окно — тогда просим нажать на уведомление.
                if (foreground) context.startActivity(confirm) else Notifications.confirmInstall(context, appId, name, confirm)
            }
            PackageInstaller.STATUS_SUCCESS -> {
                Notifications.cancelConfirm(context, appId)
                (context.applicationContext as StoreApp).settings.addHistory(
                    HistoryEntry(appId, name, intent.getStringExtra(Installer.EXTRA_VERSION).orEmpty(), System.currentTimeMillis())
                )
                if (!foreground) Notifications.installed(context, appId, name)
                InstallEvents.emit(InstallEvent(appId, success = true, message = null))
            }
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                val text = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> null // пользователь нажал «Отмена»
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Конфликт с установленной версией: возможно, она подписана другим ключом. Удалите старую и поставьте заново."
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Недостаточно места на телефоне"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Приложение не подходит для этого телефона"
                    else -> message ?: "Не удалось установить"
                }
                if (text != null && !foreground) Notifications.failed(context, appId, name, text)
                InstallEvents.emit(InstallEvent(appId, success = false, message = text))
            }
        }
    }
}

data class InstallEvent(val appId: String, val success: Boolean, val message: String?)

object InstallEvents {
    private val flow = MutableSharedFlow<InstallEvent>(extraBufferCapacity = 16)
    val events = flow.asSharedFlow()
    fun emit(event: InstallEvent) {
        flow.tryEmit(event)
    }
}
