package io.designless.serve

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Reading the change stream, including the frames that mean nothing. */
class EventStreamTest {
    @Test
    fun `a complete frame parses`() {
        val out = EventStreamParser().add("event: hello\ndata: {\"hash\":\"abc123\"}\n\n")
        assertEquals(1, out.size)
        assertEquals("hello", out[0].name)
        assertEquals("abc123", out[0].hash)
    }

    @Test
    fun `a frame split across chunks is still one frame`() {
        // The transport decides where the boundaries fall, and a client that
        // assumes one read is one frame drops changes under load.
        val p = EventStreamParser()
        assertTrue(p.add("event: chan").isEmpty())
        assertTrue(p.add("ge\ndata: {\"sem").isEmpty())
        val out = p.add("ver\":\"1.0.4\"}\n\n")
        assertEquals(1, out.size)
        assertEquals("change", out[0].name)
        assertEquals("1.0.4", out[0].semver)
    }

    @Test
    fun `several frames in one chunk all come out`() {
        val out = EventStreamParser().add(
            "event: hello\ndata: {\"hash\":\"a\"}\n\n" +
                "event: change\ndata: {\"hash\":\"b\"}\n\n",
        )
        assertEquals(listOf("hello", "change"), out.map { it.name })
        assertEquals("b", out.last().hash)
    }

    @Test
    fun `a keep-alive comment completes nothing`() {
        // A client that treats every line as a change refetches the whole
        // brand every time the server holds the connection open.
        val p = EventStreamParser()
        assertTrue(p.add(": keep-alive\n\n").isEmpty())
        assertTrue(p.add(":\n").isEmpty())
    }

    @Test
    fun `a frame with no event name is a message`() {
        val out = EventStreamParser().add("data: plain\n\n")
        assertEquals("message", out[0].name)
        assertEquals("plain", out[0].data)
    }

    @Test
    fun `multi-line data is joined with newlines`() {
        assertEquals("one\ntwo", EventStreamParser().add("data: one\ndata: two\n\n")[0].data)
    }

    @Test
    fun `carriage returns are tolerated`() {
        val out = EventStreamParser().add("event: hello\r\ndata: {\"hash\":\"a\"}\r\n\r\n")
        assertEquals("a", out[0].hash)
    }

    @Test
    fun `an unknown field is ignored rather than treated as an error`() {
        assertEquals("change", EventStreamParser().add("id: 7\nevent: change\ndata: {}\n\n")[0].name)
    }

    @Test
    fun `the retry interval the server asked for is carried`() {
        val p = EventStreamParser()
        p.add("retry: 3000\n\n")
        assertEquals(3000, p.retryMilliseconds)
    }

    @Test
    fun `no retry line is null, not a guess`() {
        // A client inventing its own interval turns a brief outage into a
        // stampede. Null means "the server has not said".
        assertNull(EventStreamParser().retryMilliseconds)
    }

    @Test
    fun `a malformed retry keeps the last good value`() {
        val p = EventStreamParser()
        p.add("retry: 3000\n\n")
        p.add("retry: soon\n\n")
        assertEquals(3000, p.retryMilliseconds)
    }

    @Test
    fun `data that is not json carries nothing`() {
        val out = EventStreamParser().add("event: change\ndata: not json\n\n")
        assertNull(out[0].hash)
        assertNull(out[0].semver)
        assertNull(out[0].version)
    }

    @Test
    fun `a real hello frame parses field for field`() {
        // Copied from the wire, not invented: this is what
        // GET /r/_designless/events sends on connect.
        val p = EventStreamParser()
        val out = p.add(
            "retry: 3000\n\n" +
                "event: hello\n" +
                "data: {\"hash\":\"38475d3cbc90c923282b801016601d23a374b3f9bdc3c2133ac98833d4016316\"," +
                "\"semver\":\"1.0.3\",\"version\":4}\n\n",
        )
        assertEquals(3000, p.retryMilliseconds)
        assertEquals("hello", out.last().name)
        assertEquals(64, out.last().hash!!.length)
        assertEquals("1.0.3", out.last().semver)
        assertEquals(4, out.last().version)
    }
}
