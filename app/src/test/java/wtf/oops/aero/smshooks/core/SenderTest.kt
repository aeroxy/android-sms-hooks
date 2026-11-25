package wtf.oops.aero.smshooks.core

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.SocketEffect
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Each test replays an answer the real server setup gives (Caddy in front of send_msg_server),
 * and checks which result the app shows for it.
 */
class SenderTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    private fun send(online: Boolean = true, timeoutSeconds: Long = 5): Outcome =
        Sender(Sender.client(timeoutSeconds)).send(
            url = server.url("/wework/sendWeworkMsg").toString(),
            secret = "s3cret",
            content = "在干嘛？\n[SIM1] 13900005271",
            timestamp = 1_700_000_000_000L,
            phoneOnline = { online },
        )

    private fun reply(code: Int, body: String = "", vararg headers: Pair<String, String>) {
        val builder = MockResponse.Builder().code(code).body(body)
        headers.forEach { (name, value) -> builder.addHeader(name, value) }
        server.enqueue(builder.build())
    }

    private fun assertOutcome(expected: Verdict, retry: Boolean, actual: Outcome) {
        assertEquals(actual.toString(), expected, actual.verdict)
        assertEquals(actual.toString(), retry, actual.retry)
    }

    @Test
    fun `request carries exactly what send_msg reads`() {
        reply(200, OK_REPLY)
        send()
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/wework/sendWeworkMsg", request.url.encodedPath)
        assertNull("the old a: covid-mp header is obsolete", request.headers["a"])
        assertTrue(request.headers["Content-Type"]!!.startsWith("application/json"))
        val json = JSONObject(request.body!!.utf8())
        assertEquals(setOf("content", "secret", "timestamp"), json.keys().asSequence().toSet())
        assertEquals("在干嘛？\n[SIM1] 13900005271", json.getString("content"))
        assertEquals("s3cret", json.getString("secret"))
        assertEquals(1_700_000_000_000L, json.getLong("timestamp"))
    }

    @Test
    fun `send_msg reply with both channels ok is success`() {
        reply(200, OK_REPLY)
        assertOutcome(Verdict.OK, retry = false, send())
    }

    @Test
    fun `one failed channel is reported but not retried`() {
        reply(200, """{"telegram":"Error: HTTP request failed","wework":"Wework message sent successfully"}""")
        val outcome = send()
        assertOutcome(Verdict.CHANNEL_FAILED, retry = false, outcome)
        assertTrue(outcome.detail.startsWith("telegram: Error"))
    }

    @Test
    fun `both channels failing is retried`() {
        reply(200, """{"telegram":"Error: a","wework":"Error: b"}""")
        assertOutcome(Verdict.CHANNEL_FAILED, retry = true, send())
    }

    @Test
    fun `wrong secret is 400`() {
        reply(400, "Invalid request format")
        assertOutcome(Verdict.BAD_SECRET, retry = false, send())
    }

    @Test
    fun `body the server can't read is 422 and not retried`() {
        reply(422, "Failed to deserialize the JSON body into the target type: missing field `content`")
        assertOutcome(Verdict.SERVER_ERROR, retry = false, send())
    }

    @Test
    fun `method not allowed means wrong URL`() {
        reply(405)
        assertOutcome(Verdict.BAD_URL, retry = false, send())
    }

    @Test
    fun `5xx from a proxy is retried`() {
        reply(502, "Bad Gateway")
        assertOutcome(Verdict.SERVER_ERROR, retry = true, send())
    }

    @Test
    fun `empty 200 from Caddy on an unknown path is not success`() {
        reply(200)
        assertOutcome(Verdict.BAD_URL, retry = false, send())
    }

    @Test
    fun `200 html page is not success`() {
        reply(200, "<!doctype html><html></html>")
        assertOutcome(Verdict.BAD_URL, retry = false, send())
    }

    @Test
    fun `redirect is not followed`() {
        reply(302, "", "Location" to "https://example.com/elsewhere")
        assertOutcome(Verdict.BAD_URL, retry = false, send())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `dropped connection while online is network down`() {
        repeat(2) { server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()) }
        assertOutcome(Verdict.NETWORK_DOWN, retry = true, send(online = true))
    }

    @Test
    fun `dropped connection while offline is phone offline`() {
        repeat(2) { server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.CloseSocket()).build()) }
        assertOutcome(Verdict.PHONE_OFFLINE, retry = true, send(online = false))
    }

    @Test
    fun `server that never answers times out as network down`() {
        server.enqueue(MockResponse.Builder().onRequestStart(SocketEffect.Stall).build())
        assertOutcome(Verdict.NETWORK_DOWN, retry = true, send(timeoutSeconds = 1))
    }

    @Test
    fun `invalid URL is caught before sending`() {
        val outcome = Sender(Sender.client(5)).send("not a url", "s", "c", 0L) { true }
        assertOutcome(Verdict.BAD_URL, retry = false, outcome)
        assertEquals(0, server.requestCount)
    }

    private companion object {
        const val OK_REPLY =
            """{"telegram":"Telegram message sent successfully","wework":"Wework message sent successfully"}"""
    }
}
