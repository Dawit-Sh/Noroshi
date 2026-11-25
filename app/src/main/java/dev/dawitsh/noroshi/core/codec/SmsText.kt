package dev.dawitsh.noroshi.core.codec

import java.time.DateTimeException
import java.time.LocalDateTime
import java.time.ZonedDateTime

/*
 * SMS text helpers for ASR "Duster" panels (the Stellar 8400), written from the panel's
 * `js/base/utils.js`: `UniDecode`, `IsGSM7Code` and `GetSmsTime`. Its `UniEncode` gives the same
 * hex as [ucs2Encode].
 */

/**
 * The panel's `UniDecode` for SMS fields, which are always hex: four digits per UTF-16 unit,
 * either case. A group that is not hex becomes U+FFFD and a short tail is dropped, so one odd
 * message can't break a whole list. Line breaks come out as `\n`.
 */
fun smsHexDecode(hex: String): String {
    val clean = hex.trim()
    val out = StringBuilder(clean.length / 4)
    var i = 0
    while (i + 4 <= clean.length) {
        out.append(clean.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: '�')
        i += 4
    }
    return out.toString().replace("\r\n", "\n").replace('\r', '\n')
}

private val GSM7_EXTRA = setOf(
    0x20AC, 0x0C, 0x0A, 0x0D, 0xA1, 0xA3, 0xA5, 0xA7, 0xBF, 0xC4, 0xC5, 0xC6, 0xC7, 0xC9, 0xD1, 0xD6, 0xD8, 0xDC, 0xDF,
    0xE0, 0xE4, 0xE5, 0xE6, 0xE8, 0xE9, 0xEC, 0xF2, 0xF6, 0xF8, 0xF9, 0xFC,
    0x3C6, 0x3A9, 0x3A8, 0x3A3, 0x3A0, 0x39E, 0x39B, 0x398, 0x394, 0x393,
)

/**
 * `IsGSM7Code`, character for character, so the router gets the same `encode_type` as from its
 * own page (it leaves out the backtick, and `ñ` because of a typo in the panel).
 */
fun isGsm7(text: String): Boolean = text.all { c ->
    val code = c.code
    code != 0x60 && (code in 0x20..0x7F || code in GSM7_EXTRA)
}

enum class SmsEncoding(val wire: String, val maxChars: Int, val singleChars: Int, val partChars: Int) {
    Gsm7("GSM7_default", 765, 160, 153),
    Unicode("UNICODE", 335, 70, 67);

    companion object {
        fun of(text: String): SmsEncoding = if (isGsm7(text)) Gsm7 else Unicode
    }
}

/** How a message will go out: its encoding, its length against the panel's limit, and its parts (5 at most). */
data class SmsLength(val encoding: SmsEncoding, val chars: Int) {
    val tooLong: Boolean get() = chars > encoding.maxChars
    val parts: Int
        get() = when {
            chars == 0 -> 0
            chars <= encoding.singleChars -> 1
            else -> (chars + encoding.partChars - 1) / encoding.partChars
        }

    companion object {
        fun of(text: String) = SmsLength(SmsEncoding.of(text), text.length)
    }
}

/** `GetSmsTime`: `yy,M,d,H,m,s,%2B3`. The plus goes in URL-encoded, as the panel sends it. */
fun smsTimeNow(now: ZonedDateTime = ZonedDateTime.now()): String {
    val offsetSeconds = now.offset.totalSeconds
    val hours = if (offsetSeconds % 3600 == 0) (offsetSeconds / 3600).toString() else (offsetSeconds / 3600.0).toString()
    val zone = if (offsetSeconds > 0) "%2B$hours" else if (offsetSeconds == 0) "-0" else hours
    val year = (now.year % 100).toString().padStart(2, '0')
    return listOf(year, now.monthValue, now.dayOfMonth, now.hour, now.minute, now.second, zone).joinToString(",")
}

/** `received` is `yy,M,d,H,m,s,tz` without zero padding; the zone is left out, as the panel does. */
fun parseSmsTime(raw: String): LocalDateTime? {
    val parts = raw.trim().split(',').map { it.trim() }
    if (parts.size < 6) return null
    val numbers = parts.take(6).map { it.toIntOrNull() ?: return null }
    val year = numbers[0].let { if (it < 100) 2000 + it else it }
    return try {
        LocalDateTime.of(year, numbers[1], numbers[2], numbers[3], numbers[4], numbers[5])
    } catch (_: DateTimeException) {
        null
    }
}

/**
 * The firmware writes an alphanumeric sender (say "Safaricom") as if it were BCD digits: every
 * semi-octet becomes `'0' + value`, so the name arrives as a run of `0`-`9` and `:;<=>?`. This
 * reads the semi-octets back as packed GSM 7-bit text at each bit offset (the firmware sometimes
 * puts a stray semi-octet first) and keeps the reading that looks most like a name: the fewest
 * switches between letters and digits, then the offsets that line up with a semi-octet. Real
 * numbers and short codes come back unchanged; so does a name whose packing has no semi-octet
 * above 9, since it can't be told from a number.
 */
fun decodeAlphanumericSender(sender: String): String {
    if (sender.length < 4 || sender.any { it !in '0'..'?' } || sender.none { it in ':'..'?' }) return sender
    val bits = ArrayList<Int>(sender.length * 4)
    for (c in sender) {
        val nibble = c - '0'
        for (j in 0 until 4) bits += (nibble shr j) and 1
    }
    var best: String? = null
    var bestRank = Int.MAX_VALUE
    for ((preference, skip) in SKIP_ORDER.withIndex()) {
        val septets = mutableListOf<Int>()
        var i = skip
        while (i + 7 <= bits.size) {
            var v = 0
            for (j in 0 until 7) v = v or (bits[i + j] shl j)
            septets += v
            i += 7
        }
        while (septets.isNotEmpty() && septets.last() == 0) septets.removeAt(septets.size - 1)
        if (septets.size < 3 || septets.first() == ' '.code || !septets.all(::plainNameChar)) continue
        val text = septets.joinToString("") { it.toChar().toString() }.trim()
        val rank = switches(text) * SKIP_ORDER.size + preference
        if (rank < bestRank) {
            best = text
            bestRank = rank
        }
    }
    return best ?: sender
}

private val SKIP_ORDER = listOf(0, 4, 1, 2, 3, 5, 6)

private fun switches(text: String): Int {
    fun kind(c: Char) = if (c.isLetter()) 0 else if (c.isDigit()) 1 else 2
    return text.zipWithNext().count { (a, b) -> kind(a) != kind(b) }
}

private fun plainNameChar(v: Int): Boolean =
    v in 'A'.code..'Z'.code || v in 'a'.code..'z'.code || v in '0'.code..'9'.code || v == ' '.code || v == '-'.code || v == '.'.code
