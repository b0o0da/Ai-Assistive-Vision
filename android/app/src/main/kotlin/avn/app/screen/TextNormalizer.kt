package avn.app.screen

import avn.contracts.ScreenSnapshot

/**
 * WS17-05: turns a [ScreenSnapshot]'s node texts into short lines that read well aloud, in screen
 * reading order (the node order the accessibility tree gives, top to bottom).
 *
 * Rules, in order:
 * 1. Secure or deny-listed screens give nothing; password nodes are always skipped.
 * 2. Each node speaks its text, or its content description when it has no text.
 * 3. Invisible characters (zero-width, bidi marks) are removed; a few common emoji become words,
 *    other emoji are dropped; separators such as "·" and "•" become spaces; whitespace collapses.
 * 4. Icon and control labels that carry no content ("More options", "Navigate up", "Image") are dropped.
 * 5. An unread badge (a number in a node whose id says unread/badge) becomes "N unread".
 * 6. Short relative times ("3h", "5m", "2d") become "3 hours ago"; clock times stay as they are.
 * 7. Duplicates are dropped: a line equal to the line before it, or already said inside the line
 *    before it (a row's combined description followed by its own children).
 * Pure function, no logging: screen text is private and is never written anywhere.
 */
object TextNormalizer {
    /** Only the line just before is compared: two identical chat messages in a row are still dropped,
     * but the same short reply ("OK") sent again later is kept. */
    private const val DEDUPE_WINDOW = 1

    private val invisible = Regex("[\\u200B-\\u200F\\u202A-\\u202E\\u2066-\\u2069\\uFEFF]")
    private val separators = Regex("\\s*[·•|]\\s*")
    private val spaces = Regex("\\s+")
    private val spaceBeforePunctuation = Regex("\\s+([,.!?;:])")
    private val repeatedWord = Regex("\\b(\\p{L}+)(?: \\1\\b)+")
    private val relativeTime = Regex("^(\\d{1,3})\\s?(m|min|mins|h|hr|hrs|d|w)$", RegexOption.IGNORE_CASE)
    private val digitsOnly = Regex("^\\d{1,4}$")

    private val emojiWords = linkedMapOf(
        "❤️" to "heart",
        "❤" to "heart",
        "👍" to "thumbs up",
        "😂" to "laughing",
        "🙏" to "thank you",
        "😢" to "sad",
        "😮" to "wow",
        "🎉" to "celebration",
        "🔥" to "fire"
    )

    private val iconLabels = setOf(
        "more options", "navigate up", "back", "menu", "search", "image", "icon", "button",
        "unlabeled", "photo", "profile picture", "voice message button", "attach", "camera", "emoji"
    )

    fun normalize(snapshot: ScreenSnapshot): List<String> {
        if (snapshot.flags.secure || snapshot.flags.denyListed) return emptyList()
        val out = mutableListOf<String>()
        for (node in snapshot.nodes) {
            if (node.isPassword) continue
            val raw = node.text.ifBlank { node.contentDescription.orEmpty() }
            val line = clean(raw, node.viewId) ?: continue
            if (isDuplicate(line, out)) continue
            out += line
        }
        return out
    }

    /** The lines joined into one utterance. */
    fun toSpeech(lines: List<String>): String = buildString {
        lines.forEachIndexed { i, raw ->
            val line = raw.trimEnd('.', ' ')
            append(line)
            if (i < lines.lastIndex) append(if (line.endsWith('?') || line.endsWith('!')) " " else ". ")
        }
    }

    fun clean(raw: String, viewId: String? = null): String? {
        var s = invisible.replace(raw, "")
        for ((emoji, word) in emojiWords) s = s.replace(emoji, " $word ")
        s = stripOtherEmoji(s)
        s = separators.replace(s, " ")
        s = spaces.replace(s, " ")
        s = spaceBeforePunctuation.replace(s, "$1")
        s = repeatedWord.replace(s, "$1").trim().trim(',', ';').trim()
        if (s.isEmpty()) return null
        if (s.lowercase() in iconLabels) return null
        val id = viewId.orEmpty().lowercase()
        if (digitsOnly.matches(s) && ("unread" in id || "badge" in id)) return "$s unread"
        relativeTime.matchEntire(s)?.let { m ->
            return relative(m.groupValues[1].toInt(), m.groupValues[2].lowercase())
        }
        return s
    }

    private fun relative(n: Int, unit: String): String {
        val word = when (unit) {
            "m", "min", "mins" -> "minute"
            "h", "hr", "hrs" -> "hour"
            "d" -> "day"
            else -> "week"
        }
        return "$n $word${if (n == 1) "" else "s"} ago"
    }

    /** Drops characters outside the Basic Multilingual Plane (most emoji) and the BMP symbol blocks. */
    private fun stripOtherEmoji(s: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val cp = s.codePointAt(i)
            val isEmoji = cp >= 0x1F000 || cp in 0x2600..0x27BF || cp == 0xFE0F || cp in 0x2B00..0x2BFF
            if (!isEmoji) sb.appendCodePoint(cp)
            i += Character.charCount(cp)
        }
        return sb.toString()
    }

    private fun key(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim()

    private fun isDuplicate(line: String, kept: List<String>): Boolean {
        val k = key(line)
        if (k.isEmpty()) return true
        if (kept.takeLast(DEDUPE_WINDOW).any { key(it) == k }) return true
        val previous = kept.lastOrNull()?.let(::key) ?: return false
        return previous.length > k.length && Regex("(^| )${Regex.escape(k)}( |$)").containsMatchIn(previous)
    }
}
