package app.mthread.store.install

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Скачивание с докачкой: оборвалась сеть — продолжим с того же байта. */
object Downloader {
    suspend fun download(url: String, dest: File, expectedSize: Long, onProgress: suspend (Long, Long) -> Unit) {
        if (dest.exists() && expectedSize > 0 && dest.length() == expectedSize) return
        if (dest.exists() && expectedSize > 0 && dest.length() > expectedSize) dest.delete()
        var attempt = 0
        while (true) {
            try {
                downloadOnce(url, dest, expectedSize, onProgress)
                return
            } catch (e: IOException) {
                if (++attempt >= 5) throw e
                kotlinx.coroutines.delay(2_000L * attempt)
            }
        }
    }

    private suspend fun downloadOnce(url: String, dest: File, expectedSize: Long, onProgress: suspend (Long, Long) -> Unit) {
        var offset = if (dest.exists()) dest.length() else 0L
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        try {
            val code = conn.responseCode
            when {
                code == 206 -> Unit
                code == 200 -> offset = 0 // сервер не умеет докачку — начинаем заново
                code == 416 && expectedSize > 0 && offset == expectedSize -> return
                else -> throw IOException("Сервер ответил $code")
            }
            val total = if (expectedSize > 0) expectedSize else offset + conn.contentLengthLong
            FileOutputStream(dest, offset > 0).use { out ->
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var done = offset
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (done - lastReport > 2 * 1024 * 1024) {
                            currentCoroutineContext().ensureActive()
                            onProgress(done, total)
                            lastReport = done
                        }
                    }
                    onProgress(done, total)
                }
            }
            if (expectedSize > 0 && dest.length() != expectedSize) {
                throw IOException("Скачано ${dest.length()} из $expectedSize байт")
            }
        } finally {
            conn.disconnect()
        }
    }
}
