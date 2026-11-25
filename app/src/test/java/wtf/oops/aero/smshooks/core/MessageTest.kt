package wtf.oops.aero.smshooks.core

import org.junit.Assert.assertEquals
import org.junit.Test

class MessageTest {

    @Test
    fun `body comes first so a notification preview shows it`() {
        assertEquals(
            "在干嘛？\n[SIM1 18612341234] 13900005271",
            Message.content(sender = "13900005271", receiver = "SIM1 18612341234", body = "在干嘛？"),
        )
    }

    @Test
    fun `receiver is the slot plus its label`() {
        val labels = listOf("18612341234", "  ")
        assertEquals("SIM1 18612341234", Message.receiver(0, labels))
        assertEquals("SIM2", Message.receiver(1, labels)) // blank label
        assertEquals("SIM3", Message.receiver(2, labels)) // no label for that slot
        assertEquals("SIM?", Message.receiver(null, labels))
        assertEquals("SIM?", Message.receiver(-1, labels))
    }
}
