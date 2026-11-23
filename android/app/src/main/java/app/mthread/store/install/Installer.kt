package app.mthread.store.install

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import app.mthread.store.BuildConfig
import app.mthread.store.data.CatalogApp
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Пишет APK (расшифровывая на лету) в системную сессию установки и запускает её. */
object Installer {
    const val EXTRA_APP_ID = "app_id"
    const val EXTRA_APP_NAME = "app_name"
    const val EXTRA_VERSION = "version"

    fun decryptor(ivHex: String): Cipher {
        val key = hex(BuildConfig.STORE_KEY)
        require(key.size == 32) { "В Store не зашит ключ — пересоберите его после настройки админки" }
        return Cipher.getInstance("AES/CTR/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(hex(ivHex)))
        }
    }

    suspend fun install(context: Context, app: CatalogApp, file: File, onProgress: suspend (Long, Long) -> Unit) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.id)
            setSize(file.length())
            setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_STORE)
        }
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            val cipher = if (app.encrypted) decryptor(app.iv ?: error("Нет IV для закрытого APK")) else null
            val digest = MessageDigest.getInstance("SHA-256")
            val total = file.length()
            session.openWrite("base.apk", 0, total).use { out ->
                file.inputStream().use { input ->
                    val buf = ByteArray(1024 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        val plain = cipher?.update(buf, 0, n) ?: buf.copyOf(n)
                        digest.update(plain)
                        out.write(plain)
                        done += n
                        currentCoroutineContext().ensureActive()
                        onProgress(done, total)
                    }
                    cipher?.doFinal()?.let { digest.update(it); out.write(it) }
                }
                session.fsync(out)
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (app.sha256.isNotBlank() && !actual.equals(app.sha256, ignoreCase = true)) {
                throw IOException("Файл повреждён или ключ Store не совпадает с ключом админки")
            }
            val intent = Intent(context, InstallResultReceiver::class.java)
                .putExtra(EXTRA_APP_ID, app.id)
                .putExtra(EXTRA_APP_NAME, app.title)
                .putExtra(EXTRA_VERSION, app.versionName)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, sessionId, intent, flags).intentSender)
            session.close()
        } catch (e: Throwable) {
            session.abandon()
            throw e
        }
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
