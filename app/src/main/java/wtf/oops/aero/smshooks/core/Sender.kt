package wtf.oops.aero.smshooks.core

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** POSTs one message to send_msg and classifies how it went. */
class Sender(private val client: OkHttpClient) {

    /** [phoneOnline] is asked only after a send gets no HTTP answer, to tell 🛜 from 📵. */
    fun send(
        url: String,
        secret: String,
        content: String,
        timestamp: Long,
        phoneOnline: () -> Boolean,
    ): Outcome {
        val httpUrl = url.trim().toHttpUrlOrNull()
            ?: return Outcome(Verdict.BAD_URL, "not a valid URL", retry = false)
        val request = Request.Builder()
            .url(httpUrl)
            .post(body(content, secret, timestamp).toRequestBody(JSON))
            .build()
        return try {
            client.newCall(request).execute().use { response ->
                Classifier.fromHttp(response.code, response.peekBody(MAX_REPLY_BYTES).string())
            }
        } catch (e: IOException) {
            Classifier.fromError(e, phoneOnline())
        }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val MAX_REPLY_BYTES = 64L * 1024

        /** Exactly what send_msg_server reads; it ignores everything else. */
        fun body(content: String, secret: String, timestamp: Long): String = JSONObject()
            .put("content", content)
            .put("secret", secret)
            .put("timestamp", timestamp)
            .toString()

        // Redirects are not followed: the server's bare domain 302s to an unrelated page whose
        // 200 would otherwise look like success.
        private val base: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        fun client(timeoutSeconds: Long): OkHttpClient =
            base.newBuilder().callTimeout(timeoutSeconds, TimeUnit.SECONDS).build()
    }
}
