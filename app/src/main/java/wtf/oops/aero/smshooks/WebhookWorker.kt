package wtf.oops.aero.smshooks

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters
import wtf.oops.aero.smshooks.core.Verdict

/**
 * Retries a send the receiver couldn't finish. It reads the URL and secret at run time, so a fix
 * made in the app applies to messages still waiting.
 */
class WebhookWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val content = inputData.getString(KEY_CONTENT) ?: return Result.failure()
        val timestamp = inputData.getLong(KEY_TIMESTAMP, System.currentTimeMillis())
        val id = inputData.getString(KEY_ID).orEmpty()
        val prefs = Prefs(applicationContext)
        if (!prefs.enabled || !prefs.configured) {
            EventLog.write(applicationContext, "RETRY dropped: forwarding is off or not set up")
            return Result.failure()
        }

        val outcome = Forwarder.send(applicationContext, content, timestamp, Forwarder.WORKER_TIMEOUT_S)
        Forwarder.log(applicationContext, "retry#${runAttemptCount + 1}", outcome)
        prefs.saveSmsResult(id, outcome)
        return when {
            outcome.verdict == Verdict.OK -> Result.success()
            outcome.retry -> Result.retry()
            else -> Result.failure()
        }
    }

    companion object {
        const val KEY_ID = "SMS_ID"
        const val KEY_CONTENT = "CONTENT"
        const val KEY_TIMESTAMP = "SMS_TIMESTAMP"
    }
}
