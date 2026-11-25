package wtf.oops.aero.smshooks

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import wtf.oops.aero.smshooks.core.Verdict

/**
 * Retries a send the receiver couldn't finish. The SMS itself is read from the [Outbox], and the URL
 * and secret at run time, so a fix made in the app applies to messages still waiting.
 */
class WebhookWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val context = applicationContext
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        // Nothing saved means it was already delivered or dropped.
        val content = Outbox.get(context, id) ?: return Result.success()

        val prefs = Prefs(context)
        if (!prefs.enabled || !prefs.configured) {
            EventLog.write(context, "RETRY dropped: forwarding is off or not set up")
            prefs.markSmsNotSent(id)
            Outbox.remove(context, id)
            return Result.failure()
        }

        val outcome = Forwarder.send(context, content, timestamp, Forwarder.WORKER_TIMEOUT_S)
        Forwarder.log(context, "retry#${runAttemptCount + 1}", outcome)
        prefs.saveSmsResult(id, outcome)
        if (outcome.retry) return Result.retry()
        Outbox.remove(context, id)
        return if (outcome.verdict == Verdict.OK) Result.success() else Result.failure()
    }

    companion object {
        const val KEY_ID = "SMS_ID"
        const val KEY_TIMESTAMP = "SMS_TIMESTAMP"
    }
}
