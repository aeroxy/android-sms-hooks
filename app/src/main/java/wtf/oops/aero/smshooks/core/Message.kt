package wtf.oops.aero.smshooks.core

/**
 * The text send_msg relays. The body comes first so a notification preview shows it; the last line
 * says which SIM received it and who sent it:
 *
 *     在干嘛？
 *     [SIM1 186…1234] 139****5271
 */
object Message {
    fun content(sender: String, receiver: String, body: String): String = "$body\n[$receiver] $sender"

    /**
     * The receiving SIM: "SIM1"/"SIM2" plus the label typed for that slot (e.g. its number), or
     * "SIM?" when the phone didn't say which slot received the SMS.
     */
    fun receiver(slotIndex: Int?, labels: List<String?>): String {
        if (slotIndex == null || slotIndex < 0) return "SIM?"
        val label = labels.getOrNull(slotIndex)?.trim()
        val slot = "SIM${slotIndex + 1}"
        return if (label.isNullOrEmpty()) slot else "$slot $label"
    }
}
