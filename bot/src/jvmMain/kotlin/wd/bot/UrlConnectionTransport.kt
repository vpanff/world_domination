package wd.bot

import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

actual fun defaultTransport(): HttpTransport = UrlConnectionTransport

/**
 * HTTP на стандартном HttpURLConnection: есть и на ПК, и на Android, без лишних библиотек.
 * Запрос идёт в отдельном потоке; отмена корутины рвёт соединение,
 * поэтому остановка бота не ждёт конца long polling.
 */
object UrlConnectionTransport : HttpTransport {
    private val pool = Executors.newCachedThreadPool { r -> Thread(r, "telegram-http").apply { isDaemon = true } }

    override suspend fun postJson(url: String, body: String, timeoutSeconds: Int): HttpResult =
        suspendCancellableCoroutine { cont ->
            val conn = URI(url).toURL().openConnection() as HttpURLConnection
            cont.invokeOnCancellation { runCatching { conn.disconnect() } }
            pool.execute {
                try {
                    conn.requestMethod = "POST"
                    conn.connectTimeout = 10_000
                    conn.readTimeout = timeoutSeconds * 1000
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    conn.outputStream.use { it.write(body.toByteArray()) }
                    val code = conn.responseCode
                    val stream = if (code >= 400) conn.errorStream else conn.inputStream
                    val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                    cont.resume(HttpResult(code, text))
                } catch (e: IOException) {
                    cont.resumeWithException(NetworkException(e))
                } catch (e: Exception) {
                    cont.resumeWithException(e)
                } finally {
                    conn.disconnect()
                }
            }
        }
}
