package com.fastwake.app.assistant

/**
 * Turns a streaming answer into speakable sentences, so text-to-speech can
 * start on the first sentence while the rest is still arriving.
 *
 * Pure Kotlin (no Android), unit-tested in SpokenTextTest.
 */
class SentenceChunker(
    /** Shortest piece worth speaking on its own; avoids splitting "Dr." etc. */
    private val minChars: Int = 24,
    private val emit: (String) -> Unit,
) {
    private val buf = StringBuilder()

    fun add(delta: String) {
        buf.append(delta)
        while (true) {
            val cut = boundary() ?: return
            val sentence = cleanForSpeech(buf.substring(0, cut))
            buf.delete(0, cut)
            if (sentence.isNotEmpty()) emit(sentence)
        }
    }

    /** Emit whatever is left at the end of the answer. */
    fun finish() {
        val rest = cleanForSpeech(buf.toString())
        buf.setLength(0)
        if (rest.isNotEmpty()) emit(rest)
    }

    /** End index of the first complete sentence at least [minChars] long, or null. */
    private fun boundary(): Int? {
        for (i in buf.indices) {
            val c = buf[i]
            val atEnd = i + 1 == buf.length
            if (c == '\n' && i + 1 >= minChars) return i + 1
            if ((c == '.' || c == '!' || c == '?') && !atEnd && buf[i + 1].isWhitespace() && i + 1 >= minChars) {
                // "3.5" or "e.g." aren't boundaries: need whitespace after, and
                // a lowercase continuation usually means an abbreviation.
                val next = buf.substring(i + 1).trimStart().firstOrNull() ?: return null
                if (next.isLowerCase()) continue
                return i + 1
            }
        }
        return null
    }
}

private val MD_LINK = Regex("""\[([^\]]+)]\((?:[^)]+)\)""")
private val URL = Regex("""https?://\S+""")
private val CITATION = Regex("""\[\d+(?:,\s*\d+)*]""")
private val HEADING = Regex("""(?m)^\s{0,3}#{1,6}\s*""")
private val BULLET = Regex("""(?m)^\s*(?:[-*•]|\d+\.)\s+""")
private val EMPHASIS = Regex("""(\*\*|__|\*|_|`+)""")
private val SPACES = Regex("""\s+""")

/** Strip formatting that sounds wrong when read aloud (markdown, links, citation marks). */
fun cleanForSpeech(text: String): String =
    text.replace(MD_LINK, "$1")
        .replace(URL, "")
        .replace(CITATION, "")
        .replace(HEADING, "")
        .replace(BULLET, "")
        .replace(EMPHASIS, "")
        .replace(SPACES, " ")
        .trim()
