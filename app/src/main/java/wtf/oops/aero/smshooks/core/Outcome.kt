package wtf.oops.aero.smshooks.core

import org.json.JSONObject
import java.io.IOException

/** How a send ended. The app shows each as a short label, e.g. ✅ 成功 or 🛜 网络不通. */
enum class Verdict {
    /** send_msg replied and both of its channels (Telegram, WeWork) succeeded. */
    OK,

    /** send_msg replied, but its own Telegram and/or WeWork send failed. */
    CHANNEL_FAILED,

    /** Rejected as unauthorized: send_msg answers a wrong secret with 400. */
    BAD_SECRET,

    /** Something answered, but not send_msg: a redirect, 404/405, or a 200 without its reply. */
    BAD_URL,

    /** 5xx (e.g. a proxy that can't reach send_msg) or an unexpected status. */
    SERVER_ERROR,

    /** No HTTP answer at all, while the phone itself is online: blocked, or the server is down. */
    NETWORK_DOWN,

    /** No HTTP answer, and the phone itself has no working internet. */
    PHONE_OFFLINE,
}

/** [detail] is for whoever reads the log or a screenshot: an HTTP code or an error name. */
data class Outcome(val verdict: Verdict, val detail: String, val retry: Boolean)

object Classifier {
    private const val MAX_DETAIL = 200

    fun fromHttp(code: Int, body: String): Outcome = when {
        code in 200..299 -> fromReply(code, body)
        code in 300..399 -> Outcome(Verdict.BAD_URL, "HTTP $code (redirect)", retry = false)
        code == 400 || code == 401 || code == 403 -> Outcome(Verdict.BAD_SECRET, "HTTP $code", retry = false)
        code == 404 || code == 405 -> Outcome(Verdict.BAD_URL, "HTTP $code", retry = false)
        code >= 500 -> Outcome(Verdict.SERVER_ERROR, "HTTP $code", retry = true)
        else -> Outcome(Verdict.SERVER_ERROR, "HTTP $code", retry = false)
    }

    fun fromError(error: IOException, phoneOnline: Boolean): Outcome {
        val detail = (error.javaClass.simpleName + (error.message?.let { ": $it" } ?: "")).take(MAX_DETAIL)
        val verdict = if (phoneOnline) Verdict.NETWORK_DOWN else Verdict.PHONE_OFFLINE
        return Outcome(verdict, detail, retry = true)
    }

    /** send_msg always answers 200 `{"telegram": "...", "wework": "..."}`; a failed channel says "Error: ...". */
    private fun fromReply(code: Int, body: String): Outcome {
        val reply = try {
            JSONObject(body)
        } catch (e: Exception) {
            null
        }
        val channels = listOf("telegram", "wework").map { it to (reply?.opt(it) as? String) }
        if (channels.any { it.second == null }) {
            return Outcome(Verdict.BAD_URL, "HTTP $code without send_msg's reply", retry = false)
        }
        val failed = channels.filter { it.second!!.startsWith("Error") }
        if (failed.isEmpty()) return Outcome(Verdict.OK, "HTTP $code", retry = false)
        val detail = failed.joinToString("; ") { "${it.first}: ${it.second}" }.take(MAX_DETAIL)
        // Retrying when only one channel failed would send a duplicate through the one that worked.
        return Outcome(Verdict.CHANNEL_FAILED, detail, retry = failed.size == channels.size)
    }
}
