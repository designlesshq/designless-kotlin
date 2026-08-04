package io.designless.serve

/**
 * Reading the change stream.
 *
 * The surface serves `text/event-stream` at `/r/<id>/events`, and this turns
 * those bytes into events. It does the parsing and nothing else: the
 * connection, the retries and the platform's idea of "the app came back"
 * belong to the caller, and a parser that also owns a socket cannot be tested
 * without one.
 */

/** One frame off the stream. */
public data class ServeEvent(
    /** The event name, or `message` when the frame did not carry one. */
    public val name: String,
    public val data: String,
) {
    private val json: Map<String, JsonValue>?
        get() = if (data.isEmpty()) null else Json.parseOrNull(data)?.asObject

    /**
     * The build hash a frame carries, when it carries one.
     *
     * Both the opening `hello` and a change frame name one. Comparing it to
     * the hash in hand is how a client tells "this brand changed" from "this
     * frame is about a brand I am already showing", which matters because a
     * reconnect replays the hello.
     *
     * Read under exactly the name the stream uses, and no others. Accepting
     * spellings the surface has never sent is a guess dressed up as tolerance:
     * it costs nothing until a field really is renamed, and then it hides the
     * break behind a fallback that was never real.
     */
    public val hash: String? get() = json?.get("hash")?.asString?.takeIf { it.isNotEmpty() }

    /** The published version a frame names. `"1.0.3"`. */
    public val semver: String? get() = json?.get("semver")?.asString?.takeIf { it.isNotEmpty() }

    /** The build number a frame names. */
    public val version: Int? get() = json?.get("version")?.asDouble?.toInt()
}

/**
 * Turns event-stream text into [ServeEvent]s.
 *
 * Feed it whatever arrives. The transport may split a frame across chunks or
 * deliver several at once, and neither is unusual, so state is kept between
 * calls and a frame cut in half is still one event.
 */
public class EventStreamParser {
    private val dataLines = mutableListOf<String>()
    private var name: String? = null

    /**
     * The tail of the last chunk, when it did not end on a line break.
     *
     * Without this a chunk ending mid-line is treated as a whole line:
     * `event: chan` then `ge\n` yields an event named `chan`. Nothing stops a
     * chunk boundary landing inside a field name.
     */
    private var partial = ""

    /**
     * How long the server asked to be left alone between reconnects, in
     * milliseconds, or null if it has not said. The surface sends `retry: 3000`.
     *
     * Worth honouring: a client reconnecting on its own schedule turns a brief
     * outage into a stampede.
     */
    public var retryMilliseconds: Int? = null
        private set

    /**
     * Parse [chunk] and return the frames it completed.
     *
     * A comment line (`: keep-alive`) completes nothing and returns nothing,
     * which is the point of it: it holds the connection open and carries no
     * change, so a client that treats every line as a change refetches the
     * brand every time the server says hello.
     */
    public fun add(chunk: String): List<ServeEvent> {
        val out = mutableListOf<ServeEvent>()

        val buffered = partial + chunk
        val endsOnBreak = buffered.endsWith("\n") || buffered.endsWith("\r")

        // Split on "\n" alone, NOT on a character class. Splitting on both
        // turns "\r\n" into two breaks, which inserts a phantom empty line
        // between every real one — and an empty line is the frame terminator,
        // so every CRLF frame would end after its first field. The trailing
        // "\r" is dropped per line below.
        val lines = buffered.split("\n").toMutableList()
        partial = if (endsOnBreak) "" else lines.removeAt(lines.lastIndex)

        for (raw in lines) {
            val line = raw.removeSuffix("\r")

            if (line.isEmpty()) {
                val body = dataLines.joinToString("\n")
                if (body.isNotEmpty() || name != null) {
                    out.add(ServeEvent(name ?: "message", body))
                }
                dataLines.clear()
                name = null
                continue
            }

            // Comments, including this surface's keep-alives. Redundant with
            // the dispatch below — a `:` line parses to an empty field name,
            // which falls through — and kept because the two are independent:
            // the dispatch ignores unknown fields as a courtesy, this refuses
            // comments as a rule.
            if (line.startsWith(":")) continue

            val colon = line.indexOf(':')
            val field = if (colon == -1) line else line.substring(0, colon)
            val value = if (colon == -1) "" else line.substring(colon + 1).removePrefix(" ")

            when (field) {
                "event" -> name = value
                "data" -> dataLines.add(value)
                "retry" -> value.toIntOrNull()?.let { retryMilliseconds = it }
                else -> Unit // `id` and anything unknown: ignored, not an error
            }
        }

        return out
    }
}
