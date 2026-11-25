package wtf.oops.aero.smshooks

import android.content.Context
import android.os.Build
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import wtf.oops.aero.smshooks.core.Message
import wtf.oops.aero.smshooks.core.Outcome
import wtf.oops.aero.smshooks.core.Sender
import java.util.concurrent.TimeUnit

/** Sends to send_msg with the settings typed in the app, and queues retries. */
object Forwarder {
    /** The receiver must finish well within Android's broadcast limit. */
    const val RECEIVER_TIMEOUT_S = 8L
    const val WORKER_TIMEOUT_S = 30L
    const val TEST_TIMEOUT_S = 20L

    fun send(context: Context, content: String, timestamp: Long, timeoutSeconds: Long): Outcome {
        val prefs = Prefs(context)
        return Sender(Sender.client(timeoutSeconds))
            .send(prefs.url, prefs.secret, content, timestamp) { Device.isOnline(context) }
    }

    /** The test message uses the same three lines as an SMS, naming the phone and the build. */
    fun testContent(): String = Message.content(
        sender = "SMS Hooks",
        receiver = "${Build.MANUFACTURER} ${Build.MODEL}",
        body = "测试消息 · v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})",
    )

    fun log(context: Context, via: String, outcome: Outcome) {
        val retry = if (outcome.retry) " (will retry)" else ""
        EventLog.write(context, "SEND via=$via result=${outcome.verdict} detail=${outcome.detail}$retry")
    }

    fun enqueueRetry(context: Context, smsId: String, content: String, timestamp: Long) {
        val request = OneTimeWorkRequestBuilder<WebhookWorker>()
            .setInputData(
                workDataOf(
                    WebhookWorker.KEY_ID to smsId,
                    WebhookWorker.KEY_CONTENT to content,
                    WebhookWorker.KEY_TIMESTAMP to timestamp,
                ),
            )
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }
}
