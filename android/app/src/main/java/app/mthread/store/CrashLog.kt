package app.mthread.store

import android.content.Context
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Сохраняет текст падения, чтобы при следующем запуске показать его и дать скопировать. */
object CrashLog {
    private fun file(context: Context) = File(context.filesDir, "crash.txt")

    fun install(context: Context) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(context, error, "поток ${thread.name}") }
            previous?.uncaughtException(thread, error)
        }
    }

    fun write(context: Context, error: Throwable, where: String) {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        val text = buildString {
            appendLine("MThread Store ${info.versionName} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()) + " · " + where)
            appendLine()
            append(error.stackTraceToString())
        }
        file(context).writeText(text)
    }

    fun read(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }
}
