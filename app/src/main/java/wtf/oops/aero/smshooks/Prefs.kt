package wtf.oops.aero.smshooks

import android.content.Context
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import wtf.oops.aero.smshooks.core.Outcome
import wtf.oops.aero.smshooks.core.Verdict

/** Settings typed in the app, plus the last results the setup check shows. */
class Prefs(context: Context) {
    private val sp = PreferenceManager.getDefaultSharedPreferences(context)

    val enabled: Boolean get() = sp.getBoolean(KEY_ENABLED, true)
    val url: String get() = sp.getString(KEY_URL, null)?.trim().orEmpty()
    val secret: String get() = sp.getString(KEY_SECRET, null).orEmpty()
    val simLabels: List<String?> get() = listOf(sp.getString(KEY_SIM1, null), sp.getString(KEY_SIM2, null))
    val configured: Boolean get() = url.isNotEmpty() && secret.isNotEmpty()

    var miuiConfirmed: Boolean
        get() = sp.getBoolean(KEY_MIUI_CONFIRMED, false)
        set(value) = sp.edit { putBoolean(KEY_MIUI_CONFIRMED, value) }
    var huaweiConfirmed: Boolean
        get() = sp.getBoolean(KEY_HUAWEI_CONFIRMED, false)
        set(value) = sp.edit { putBoolean(KEY_HUAWEI_CONFIRMED, value) }

    /** Result of a send; [verdict] is null while it is pending, or when [notSent] (forwarding off). */
    data class Status(
        val time: Long,
        val verdict: Verdict?,
        val detail: String,
        val retrying: Boolean,
        val from: String = "",
        val receiver: String = "",
        val notSent: Boolean = false,
    )

    // ---- Test button ----
    fun saveTest(outcome: Outcome) = sp.edit {
        putLong("test_time", System.currentTimeMillis())
        putString("test_verdict", outcome.verdict.name)
        putString("test_detail", outcome.detail)
    }

    fun lastTest(): Status? {
        val time = sp.getLong("test_time", 0)
        if (time == 0L) return null
        return Status(time, verdictOf(sp.getString("test_verdict", null)), sp.getString("test_detail", "")!!, false)
    }

    // ---- Last SMS received ----
    fun saveSmsReceived(id: String, time: Long, from: String, receiver: String, notSent: Boolean) = sp.edit {
        putString("sms_id", id)
        putLong("sms_time", time)
        putString("sms_from", from)
        putString("sms_receiver", receiver)
        remove("sms_verdict")
        putString("sms_detail", "")
        putBoolean("sms_retrying", false)
        putBoolean("sms_not_sent", notSent)
    }

    /** Ignored when a newer SMS has arrived since, so a late retry can't overwrite its status. */
    fun saveSmsResult(id: String, outcome: Outcome) {
        if (sp.getString("sms_id", null) != id) return
        sp.edit {
            putString("sms_verdict", outcome.verdict.name)
            putString("sms_detail", outcome.detail)
            putBoolean("sms_retrying", outcome.retry)
        }
    }

    fun lastSms(): Status? {
        val time = sp.getLong("sms_time", 0)
        if (time == 0L) return null
        return Status(
            time = time,
            verdict = verdictOf(sp.getString("sms_verdict", null)),
            detail = sp.getString("sms_detail", "")!!,
            retrying = sp.getBoolean("sms_retrying", false),
            from = sp.getString("sms_from", "")!!,
            receiver = sp.getString("sms_receiver", "")!!,
            notSent = sp.getBoolean("sms_not_sent", false),
        )
    }

    private fun verdictOf(name: String?): Verdict? = Verdict.entries.firstOrNull { it.name == name }

    companion object {
        const val KEY_ENABLED = "webhookEnabled"
        const val KEY_URL = "webhookUrl"
        const val KEY_SECRET = "secret"
        const val KEY_SIM1 = "sim1Label"
        const val KEY_SIM2 = "sim2Label"
        private const val KEY_MIUI_CONFIRMED = "miuiConfirmed"
        private const val KEY_HUAWEI_CONFIRMED = "huaweiConfirmed"
    }
}
