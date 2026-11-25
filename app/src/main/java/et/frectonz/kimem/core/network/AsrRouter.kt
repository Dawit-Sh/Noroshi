package et.frectonz.kimem.core.network

import android.util.Log
import et.frectonz.kimem.R
import et.frectonz.kimem.core.codec.XmlNode
import et.frectonz.kimem.core.codec.buildRgwXml
import et.frectonz.kimem.core.model.ActionResult
import et.frectonz.kimem.core.model.AsrDevices
import et.frectonz.kimem.core.model.AsrStatus
import et.frectonz.kimem.core.model.Message
import et.frectonz.kimem.core.model.ReportKind
import et.frectonz.kimem.core.model.RouterException
import et.frectonz.kimem.core.model.RouterType
import et.frectonz.kimem.core.model.Show
import et.frectonz.kimem.core.model.SmsInbox
import et.frectonz.kimem.core.model.res
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
 * ASR / Marvell "Duster" panels (the Stellar 8400 CPE): Digest login, then XML over
 * `xml_action.cgi`. Everything here follows the panel's own `js/base/utils.js` and
 * `js/base/ajax_calls.js`.
 */
class AsrRouter(
    private val http: OkHttpClient,
    host: String,
    username: String,
    private val password: String,
) : Router {
    override val type = RouterType.Stellar

    private val address = "http://${host.trim()}"

    // The panel's login form URL-encodes both before hashing or sending them.
    private val user = AsrDigest.encodeUriComponent(username)
    private val pass = AsrDigest.encodeUriComponent(password)

    private val lock = Mutex()
    private var challenge: DigestChallenge? = null
    private var count = 1

    private fun Request.Builder.common(): Request.Builder = this
        .header("User-Agent", "Kimem Android")
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

    override suspend fun login() = lock.withLock { loginLocked() }

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

    override suspend fun logout() {
        lock.withLock {
            if (challenge == null) return
            try {
                execute(Request.Builder().url("$address/xml_action.cgi?Action=logout").common().build()) { it.body.string() }
            } finally {
                challenge = null
            }
        }
    }

    /** One authorised call; the digest counter moves on every request, retries included. */
    private suspend fun call(method: String, query: String, body: String?): XmlNode = lock.withLock {
        for (attempt in 1..2) {
            val text = withRetry {
                val ch = challenge ?: run { loginLocked(); challenge!! }
                val auth = AsrDigest.authorization(user, pass, ch, method, count++, AsrDigest.newCnonce())
                val builder = Request.Builder().url("$address/xml_action.cgi?$query").common().header("Authorization", auth)
                if (body != null) builder.post(body.toRequestBody(XML)) else builder.get()
                execute(builder.build()) { it.body.string() }
            }
            val xml = parse(text)
            when (xml.child("login_status")?.text) {
                "UNAUTHORIZED", "KICKOFF" -> {
                    // The session timed out or another browser took it over: log in again, once.
                    if (attempt == 2) throw RouterException(res(R.string.err_asr_session_lost))
                    challenge = null
                    loginLocked()
                }

                else -> return@withLock xml
            }
        }
        error("unreachable")
    }

    /** `GET xml_action.cgi?method=get&module=duster&file=<file>` */
    suspend fun get(file: String): XmlNode = call("GET", "method=get&module=duster&file=$file", null)

    /**
     * `POST xml_action.cgi?method=set&module=duster&file=<file>` with an `<RGW>` body built from
     * slash paths, the way the panel's `postXML` does it. Used by the SMS and USSD calls.
     */
    suspend fun set(file: String, fields: List<Pair<String, String>>): XmlNode =
        call("POST", "method=set&module=duster&file=$file", buildRgwXml(fields))

    suspend fun status(): AsrStatus = AsrStatus.parse(get("status1"))

    suspend fun devices(): AsrDevices = AsrDevices.parse(get("device_management_all"))

    override suspend fun report(kind: ReportKind): Show = when (kind) {
        ReportKind.Info -> status().info()
        ReportKind.Signal -> status().signal()
        ReportKind.Internet -> status().internet()
        ReportKind.Apn -> status().apn()
        ReportKind.Device -> status().device()
        ReportKind.Wifi -> status().wifi()
        ReportKind.Devices -> devices()
        else -> unsupported()
    }

    /** The panel's reboot page only reads `file=reset`; the router drops the connection right after. */
    override suspend fun reboot() {
        try {
            get("reset")
        } catch (e: RouterException) {
            Log.d(TAG, "reboot response not read: ${e.message}")
        }
    }

    private fun unsupported(): Nothing = throw RouterException(res(R.string.err_not_supported, res(type.label)))

    override suspend fun smsInbox(): SmsInbox = unsupported()
    override suspend fun sms(id: Int): Message = unsupported()
    override suspend fun sendSms(number: String, message: String): ActionResult = unsupported()
    override suspend fun deleteSms(ids: Iterable<Int>): ActionResult = unsupported()
    override suspend fun deleteAllSms(): ActionResult? = unsupported()
    override suspend fun markSms(ids: Iterable<Int>): ActionResult = unsupported()
    override suspend fun markAllSms(): ActionResult? = unsupported()

    override suspend fun ussdSend(code: String) {
        unsupported()
    }

    override suspend fun ussdReply(reply: String) {
        unsupported()
    }

    override suspend fun ussdCancel() = Unit

    override suspend fun ussdAwaitResponse(): String = unsupported()

    private companion object {
        // What jQuery sends with the panel's string bodies.
        val XML = "application/x-www-form-urlencoded; charset=UTF-8".toMediaType()
    }
}
