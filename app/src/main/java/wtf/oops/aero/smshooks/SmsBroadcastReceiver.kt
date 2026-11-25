package wtf.oops.aero.smshooks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import wtf.oops.aero.smshooks.core.Message
import java.util.UUID
import kotlin.concurrent.thread

/**
 * Forwards each incoming SMS right away from the receiver itself: Android lets an SMS receiver run
 * for a while even under battery restrictions, while Chinese ROMs often defer scheduled jobs.
 * Only when that first try fails is the send handed to WorkManager to retry.
 */
class SmsBroadcastReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)?.filterNotNull().orEmpty()
        if (parts.isEmpty()) return

        val first = parts.first()
        val sender = first.originatingAddress ?: first.displayOriginatingAddress ?: "unknown"
        val body = parts.joinToString("") { it.messageBody.orEmpty() }
        val timestamp = first.timestampMillis
        val sim = Device.simInfo(intent)
        val prefs = Prefs(context)
        val receiver = Message.receiver(sim.slot, prefs.simLabels)
        val id = UUID.randomUUID().toString()

        EventLog.write(
            context,
            "RECEIVED from=$sender sim=$receiver slot=${sim.slot} subId=${sim.subId} extras=${sim.extrasKeys}",
        )

        if (!prefs.enabled || !prefs.configured) {
            prefs.saveSmsReceived(id, timestamp, sender, receiver, notSent = true)
            EventLog.write(context, "NOT SENT: forwarding is off or the server URL/secret is missing")
            return
        }
        prefs.saveSmsReceived(id, timestamp, sender, receiver, notSent = false)

        val content = Message.content(sender, receiver, body)
        val pending = goAsync()
        thread {
            try {
                val outcome = Forwarder.send(context, content, timestamp, Forwarder.RECEIVER_TIMEOUT_S)
                Forwarder.log(context, "receiver", outcome)
                prefs.saveSmsResult(id, outcome)
                if (outcome.retry) Forwarder.enqueueRetry(context, id, content, timestamp)
            } catch (t: Throwable) {
                EventLog.write(context, "ERROR in receiver: $t")
                Forwarder.enqueueRetry(context, id, content, timestamp)
            } finally {
                pending.finish()
            }
        }
    }
}
