package dev.dawitsh.noroshi.core.network

import java.security.MessageDigest
import kotlin.random.Random

/** The `WWW-Authenticate: Digest realm=.., nonce=.., qop=..` challenge that `GET /login.cgi` answers with. */
data class DigestChallenge(val realm: String, val nonce: String, val qop: String)

/**
 * HTTP Digest the way the ASR "Duster" panel does it (`js/base/utils.js`: doLogin and getAuthHeader).
 * Not RFC 7616 to the letter: the login request hashes the fixed uri `/cgi/protected.cgi`, every
 * later call hashes `/cgi/xml_action.cgi` whatever the real path is, and the user name and
 * password are URL-encoded (`encodeURIComponent`) before anything is hashed.
 */
object AsrDigest {
    const val REALM = "Highwmg"
    const val LOGIN_URI = "/cgi/protected.cgi"
    const val XML_URI = "/cgi/xml_action.cgi"

    /** The panel's `hex_md5`: lowercase hex, 8 bits per character. */
    fun md5Hex(text: String): String {
        val bytes = ByteArray(text.length) { (text[it].code and 0xFF).toByte() }
        return MessageDigest.getInstance("MD5").digest(bytes).toHexString()
    }

    /** JavaScript's `encodeURIComponent`, which the panel's login form applies to the user name and password. */
    fun encodeUriComponent(text: String): String = buildString {
        for (byte in text.encodeToByteArray()) {
            val c = (byte.toInt() and 0xFF).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c in "-_.!~*'()") append(c)
            else append('%').append("%02X".format(byte.toInt() and 0xFF))
        }
    }

    /** Reads the panel's challenge header; null when it is missing or not Digest. */
    fun parseChallenge(header: String?): DigestChallenge? {
        if (header == null) return null
        val trimmed = header.trim()
        if (!trimmed.startsWith("Digest", ignoreCase = true)) return null
        val fields = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^\s,]+))""").findAll(trimmed.substring(6))
            .associate { m -> m.groupValues[1].lowercase() to (m.groups[2]?.value ?: m.groupValues[3]) }
        val realm = fields["realm"] ?: return null
        val nonce = fields["nonce"] ?: return null
        return DigestChallenge(realm, nonce, fields["qop"] ?: "auth")
    }

    /** True for the challenge of an ASR / Duster panel, which is how a Stellar is recognised. */
    fun isAsrChallenge(header: String?): Boolean = parseChallenge(header)?.realm == REALM

    /** The panel's cnonce: the first 16 hex digits of md5(random + time). */
    fun newCnonce(random: Random = Random.Default, now: Long = System.currentTimeMillis()): String =
        md5Hex("${random.nextInt(100_001)}$now").substring(0, 16)

    /** `nc` as the panel writes it: uppercase hex, zero padded to 8 digits. */
    fun nonceCount(count: Int): String = count.toString(16).uppercase().padStart(8, '0')

    fun ha1(user: String, realm: String, password: String): String = md5Hex("$user:$realm:$password")

    fun response(ha1: String, challenge: DigestChallenge, nc: String, cnonce: String, method: String, uri: String): String =
        md5Hex("$ha1:${challenge.nonce}:$nc:$cnonce:${challenge.qop}:${md5Hex("$method:$uri")}")

    /** `response=` of `GET /login.cgi?Action=Digest&...`; [user] and [password] already URL-encoded. */
    fun loginResponse(user: String, password: String, challenge: DigestChallenge, cnonce: String): String =
        response(ha1(user, challenge.realm, password), challenge, nonceCount(1), cnonce, "GET", LOGIN_URI)

    /** The query of the login request, in the panel's order. */
    fun loginQuery(user: String, challenge: DigestChallenge, response: String, cnonce: String): String =
        "Action=Digest&username=$user&realm=${challenge.realm}&nonce=${challenge.nonce}" +
            "&response=$response&qop=${challenge.qop}&cnonce=$cnonce&temp=asr"

    /** The `Authorization` header of a call to `xml_action.cgi` (also sent with the login request, count 1). */
    fun authorization(user: String, password: String, challenge: DigestChallenge, method: String, count: Int, cnonce: String): String {
        val nc = nonceCount(count)
        val response = response(ha1(user, challenge.realm, password), challenge, nc, cnonce, method, XML_URI)
        return "Digest username=\"$user\", realm=\"${challenge.realm}\", nonce=\"${challenge.nonce}\", " +
            "uri=\"$XML_URI\", response=\"$response\", qop=${challenge.qop}, nc=$nc, cnonce=\"$cnonce\""
    }
}
