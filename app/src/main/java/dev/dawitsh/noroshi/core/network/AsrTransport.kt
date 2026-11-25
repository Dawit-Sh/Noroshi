package dev.dawitsh.noroshi.core.network

import dev.dawitsh.noroshi.R
import dev.dawitsh.noroshi.core.codec.XmlNode
import dev.dawitsh.noroshi.core.codec.buildRgwXml
import dev.dawitsh.noroshi.core.model.RouterException
import dev.dawitsh.noroshi.core.model.res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Talking to an ASR / Marvell "Duster" panel (the Stellar 8400 CPE): the Digest login and the
 * XML calls to `xml_action.cgi`, exactly as the panel's `js/base/utils.js` and
 * `js/base/ajax_calls.js` do them. Knows nothing about what the files mean.
 *
 * - [get] / [getText]: `GET xml_action.cgi?method=get&module=duster&file=<file>`
 * - [set] / [setXml]: `POST xml_action.cgi?method=set&module=duster&file=<file>` with an `<RGW>` body
 *
 * Every call logs in first if needed. An answer with `<login_status>` UNAUTHORIZED or KICKOFF
 * (session timed out, or someone logged in elsewhere) makes it log in again once and retry;
 * a second refusal throws. Calls are serialised, since the digest counter must go up in order.
 */
class AsrTransport(
    private val http: OkHttpClient,
    host: String,
    username: String,
    private val password: String,
) {
    private val address = "http://${host.trim()}"

    // The panel's login form URL-encodes both before hashing or sending them.
    private val user = AsrDigest.encodeUriComponent(username)
    private val pass = AsrDigest.encodeUriComponent(password)

    private val lock = Mutex()
    private var challenge: DigestChallenge? = null
    private var count = 1

    val loggedIn: Boolean get() = challenge != null

    private fun Request.Builder.common(): Request.Builder = this
        .header("User-Agent", "Noroshi Android")
        .header("Expires", "-1")
        .header("Cache-Control", "no-store, no-cache, must-revalidate")
        .header("Pragma", "no-cache")

    private suspend fun <T> execute(request: Request, read: (okhttp3.Response) -> T): T = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use(read)
        } catch (e: IOException) {
            throw RouterException(res(R.string.err_request_failed, e.message ?: e.javaClass.simpleName), e)
        }
    }

    private fun parse(text: String): XmlNode = try {
        XmlNode.parse(text)
    } catch (e: Exception) {
        throw RouterException(res(R.string.err_non_xml, text), e)
    }

    suspend fun login() = lock.withLock { loginLocked() }

    private suspend fun loginLocked() {
        if (password.isEmpty()) throw RouterException(res(R.string.err_password_required))

        val header = withRetry {
            execute(Request.Builder().url("$address/login.cgi").common().build()) { it.header("WWW-Authenticate") }
        }
        val ch = AsrDigest.parseChallenge(header) ?: throw RouterException(res(R.string.err_asr_no_challenge))

        val cnonce = AsrDigest.newCnonce()
        val response = AsrDigest.loginResponse(user, pass, ch, cnonce)
        val url = "$address/login.cgi?" + AsrDigest.loginQuery(user, ch, response, cnonce)
        val body = withRetry {
            val request = Request.Builder().url(url).common()
                .header("Authorization", AsrDigest.authorization(user, pass, ch, "GET", 1, AsrDigest.newCnonce()))
                .build()
            execute(request) { it.body.string() }
        }
        // The panel's login_done(): success is a body containing "200 OK"; a wrong login answers empty.
        if (!body.contains("200 OK")) throw RouterException(res(R.string.err_login_wrong))

        challenge = ch
        count = 2
    }

    /** `GET xml_action.cgi?Action=logout`; does nothing when not logged in. */
    suspend fun logout() {
        lock.withLock {
            if (challenge == null) return
            try {
                execute(Request.Builder().url("$address/xml_action.cgi?Action=logout").common().build()) { it.body.string() }
            } finally {
                challenge = null
            }
        }
    }

    /** One authorised call; returns the raw answer and its parsed tree. */
    private suspend fun call(method: String, query: String, body: String?): Pair<String, XmlNode> = lock.withLock {
        for (attempt in 1..2) {
            val text = withRetry {
                val ch = challenge ?: run { loginLocked(); challenge!! }
                val auth = AsrDigest.authorization(user, pass, ch, method, count++, AsrDigest.newCnonce())
                val builder = Request.Builder().url("$address/xml_action.cgi?$query").common().header("Authorization", auth)
                if (body != null) builder.post(body.toRequestBody(FORM)) else builder.get()
                execute(builder.build()) { it.body.string() }
            }
            // Some set calls answer with an empty body; that is not a refusal.
            if (text.isBlank()) return@withLock text to XmlNode("RGW", "", emptyList())
            val xml = parse(text)
            when (xml.child("login_status")?.text) {
                "UNAUTHORIZED", "KICKOFF" -> {
                    if (attempt == 2) throw RouterException(res(R.string.err_asr_session_lost))
                    challenge = null
                    loginLocked()
                }

                else -> return@withLock text to xml
            }
        }
        error("unreachable")
    }

    private fun getQuery(file: String) = "method=get&module=duster&file=$file"
    private fun setQuery(file: String) = "method=set&module=duster&file=$file"

    /** Reads `file=<file>` and parses it. */
    suspend fun get(file: String): XmlNode = call("GET", getQuery(file), null).second

    /** Reads `file=<file>` and returns the raw XML text (already checked for UNAUTHORIZED / KICKOFF). */
    suspend fun getText(file: String): String = call("GET", getQuery(file), null).first

    /**
     * Writes to `file=<file>` with a body built from slash paths, the way the panel's
     * `putMapElement` + `createXML` do it: `listOf("RGW/ussd/action" to "1", "RGW/ussd/ussd_param" to "*704#")`.
     */
    suspend fun set(file: String, fields: List<Pair<String, String>>): XmlNode =
        call("POST", setQuery(file), buildRgwXml(fields)).second

    /** Writes a ready-made body (`<?xml ...?> <RGW>...</RGW>`) and returns the raw answer, possibly empty. */
    suspend fun setXml(file: String, xmlBody: String): String = call("POST", setQuery(file), xmlBody).first

    private companion object {
        // What jQuery sends with the panel's string bodies.
        val FORM = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
    }
}
