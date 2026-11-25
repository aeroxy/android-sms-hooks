package wtf.oops.aero.smshooks

import android.content.Context
import java.io.File

/**
 * SMS waiting to be delivered, one file per message. Kept outside WorkManager's input data, which is
 * limited to 10 KB, and written before the first send so the message survives the process being
 * killed mid-send.
 */
object Outbox {
    private fun file(context: Context, id: String) = File(File(context.filesDir, "outbox").apply { mkdirs() }, id)

    fun put(context: Context, id: String, content: String) = file(context, id).writeText(content)

    fun get(context: Context, id: String): String? = file(context, id).takeIf { it.exists() }?.readText()

    fun remove(context: Context, id: String) {
        file(context, id).delete()
    }
}
