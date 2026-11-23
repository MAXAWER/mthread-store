package app.mthread.store.data

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/** Что из каталога уже стоит на телефоне. */
object Installed {
    fun versionCode(context: Context, packageName: String): Long? = try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    fun versions(context: Context, apps: List<CatalogApp>): Map<String, Long> =
        apps.mapNotNull { app -> versionCode(context, app.id)?.let { app.id to it } }.toMap()

    /** Тихо обновлять можно только то, что поставил сам Store. */
    fun installedByUs(context: Context, packageName: String): Boolean = try {
        val pm = context.packageManager
        val installer = if (Build.VERSION.SDK_INT >= 30) {
            pm.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION") pm.getInstallerPackageName(packageName)
        }
        installer == context.packageName
    } catch (_: Exception) {
        false
    }

    fun hasUpdate(app: CatalogApp, installed: Long?) = installed != null && app.versionCode > installed
}
