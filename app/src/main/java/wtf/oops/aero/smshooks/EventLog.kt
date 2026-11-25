package wtf.oops.aero.smshooks

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The exportable log: what arrived and how each send went. It never records an SMS body or the
 * secret, and it keeps only the most recent entries.
 */
object EventLog {
    private const val FILE_NAME = "log"
    private const val MAX_BYTES = 256 * 1024

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    @Synchronized
    fun write(context: Context, line: String) {
        Log.i("SmsHooks", line)
        try {
            val f = file(context)
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            f.appendText("$stamp $line\n")
            if (f.length() > MAX_BYTES) f.writeText(f.readText().takeLast(MAX_BYTES / 2))
        } catch (e: Exception) {
            Log.w("SmsHooks", "could not write the log", e)
        }
    }
}
