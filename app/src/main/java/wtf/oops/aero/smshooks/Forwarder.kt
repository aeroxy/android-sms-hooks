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
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Sends to the server with the settings typed in the app, and manages queued retries. */
object Forwarder {
    /** With the enqueue wait below, the receiver stays under Android's ~10 s broadcast limit. */
    const val RECEIVER_TIMEOUT_S = 7L
    private const val ENQUEUE_WAIT_S = 2L
    const val WORKER_TIMEOUT_S = 30L
    const val TEST_TIMEOUT_S = 20L

    fun send(context: Context, content: String, timestamp: Long, timeoutSeconds: Long): Outcome {
        val prefs = Prefs(context)
        return Sender(Sender.client(timeoutSeconds))
            .send(prefs.url, prefs.secret, content, timestamp) { Device.isOnline(context) }
    }

    /** The test message uses the same format as an SMS, naming the phone and the build. */
    fun testContent(): String = Message.content(
        sender = "SMS Hooks",
        receiver = "${Build.MANUFACTURER} ${Build.MODEL}",
        body = "测试消息 · v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA})",
    )

    fun log(context: Context, via: String, outcome: Outcome) {
        val retry = if (outcome.retry) " (will retry)" else ""
        EventLog.write(context, "SEND via=$via result=${outcome.verdict} detail=${outcome.detail}$retry")
    }

    /**
     * Saves the SMS and schedules its retry *before* the first send, so it is not lost if the
     * process is killed mid-send. The retry waits longer than a first send can take, and is
     * cancelled once that send settles. Returns the retry's ID, or null if it couldn't be queued.
     */
    fun queue(context: Context, smsId: String, content: String, timestamp: Long): UUID? {
        try {
            Outbox.put(context, smsId, content)
        } catch (e: Exception) {
            EventLog.write(context, "ERROR could not save the SMS for a retry: $e")
            return null
        }
        val request = OneTimeWorkRequestBuilder<WebhookWorker>()
            .setInputData(workDataOf(WebhookWorker.KEY_ID to smsId, WebhookWorker.KEY_TIMESTAMP to timestamp))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(30, TimeUnit.SECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        return try {
            // Wait briefly for WorkManager to store the retry before the send starts.
            WorkManager.getInstance(context).enqueue(request).result.get(ENQUEUE_WAIT_S, TimeUnit.SECONDS)
            request.id
        } catch (e: TimeoutException) {
            // Slow, not failed: the enqueue still completes in the background, and a later cancel by
            // ID still applies, so keep the saved SMS.
            EventLog.write(context, "queueing the retry is slow; continuing")
            request.id
        } catch (e: Exception) {
            EventLog.write(context, "ERROR could not queue a retry: $e")
            Outbox.remove(context, smsId) // nothing would ever read it
            null
        }
    }

    /** The first send settled without needing a retry: drop the queued retry and the saved copy. */
    fun settle(context: Context, smsId: String, retryId: UUID?) {
        if (retryId != null) WorkManager.getInstance(context).cancelWorkById(retryId)
        Outbox.remove(context, smsId)
    }
}
