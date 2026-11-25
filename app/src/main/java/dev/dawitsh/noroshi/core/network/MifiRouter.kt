package dev.dawitsh.noroshi.core.network

import android.util.Log
import dev.dawitsh.noroshi.R
import dev.dawitsh.noroshi.core.codec.RouterDateTime
import dev.dawitsh.noroshi.core.codec.b64Encode
import dev.dawitsh.noroshi.core.codec.joinMsgIds
import dev.dawitsh.noroshi.core.codec.sha256Hex
import dev.dawitsh.noroshi.core.codec.ucs2Encode
import dev.dawitsh.noroshi.core.model.ActionKind
import dev.dawitsh.noroshi.core.model.ActionReply
import dev.dawitsh.noroshi.core.model.ActionResult
import dev.dawitsh.noroshi.core.model.AirtimeBalance
import dev.dawitsh.noroshi.core.model.ApnConfig
import dev.dawitsh.noroshi.core.model.ApnReport
import dev.dawitsh.noroshi.core.model.ApnStatus
import dev.dawitsh.noroshi.core.model.CellExtras
import dev.dawitsh.noroshi.core.model.Device
import dev.dawitsh.noroshi.core.model.DeviceReport
import dev.dawitsh.noroshi.core.model.GetRandomLogin
import dev.dawitsh.noroshi.core.model.Info
import dev.dawitsh.noroshi.core.model.Internet
import dev.dawitsh.noroshi.core.model.InternetReport
import dev.dawitsh.noroshi.core.model.LoginReply
import dev.dawitsh.noroshi.core.model.LoginResult
import dev.dawitsh.noroshi.core.model.Message
import dev.dawitsh.noroshi.core.model.MessageStatus
import dev.dawitsh.noroshi.core.model.Power
import dev.dawitsh.noroshi.core.model.ReportKind
import dev.dawitsh.noroshi.core.model.RouterException
import dev.dawitsh.noroshi.core.model.RouterType
import dev.dawitsh.noroshi.core.model.Show
import dev.dawitsh.noroshi.core.model.Signal
import dev.dawitsh.noroshi.core.model.SignalReport
import dev.dawitsh.noroshi.core.model.SimIccid
import dev.dawitsh.noroshi.core.model.SmsCapacity
import dev.dawitsh.noroshi.core.model.SmsInbox
import dev.dawitsh.noroshi.core.model.SmsParameters
import dev.dawitsh.noroshi.core.model.SmsSettings
import dev.dawitsh.noroshi.core.model.StationList
import dev.dawitsh.noroshi.core.model.SystemInfo
import dev.dawitsh.noroshi.core.model.TextShow
import dev.dawitsh.noroshi.core.model.UssdData
import dev.dawitsh.noroshi.core.model.UssdFlag
import dev.dawitsh.noroshi.core.model.UssdWriteFlag
import dev.dawitsh.noroshi.core.model.WanDetails
import dev.dawitsh.noroshi.core.model.Wifi
import dev.dawitsh.noroshi.core.model.res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.serializer
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Safaricom Kimem MiFi: ZTE-style JSON over /reqproc/proc_get and /reqproc/proc_post. */
class MifiRouter(
    private val http: OkHttpClient,
    host: String,
    private val username: String,
    private val password: String,
) : Router {
    override val type = RouterType.SafaricomMifi

    private val address = "http://${host.trim()}"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private suspend fun execute(request: Request): String = withContext(Dispatchers.IO) {
        try {
            http.newCall(request).execute().use { it.body.string() }
        } catch (e: IOException) {
            throw RouterException(res(R.string.err_request_failed, e.message ?: e.javaClass.simpleName), e)
        }
    }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T {
        val element = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw RouterException(res(R.string.err_non_json, text), e)
        }
        return try {
            json.decodeFromJsonElement(serializer, element)
        } catch (e: SerializationException) {
            throw RouterException(res(R.string.err_decode, text), e)
        } catch (e: IllegalArgumentException) {
            throw RouterException(res(R.string.err_decode, text), e)
        }
    }

    private suspend fun fetch(url: String): String = withRetry {
        execute(Request.Builder().url(url).header("User-Agent", "Noroshi Android").build())
    }

    private suspend fun getRaw(cmd: String, params: Map<String, String>): String {
        val url = "$address/reqproc/proc_get".toHttpUrlOrNull()!!.newBuilder()
            .addQueryParameter("isTest", "false")
            .addQueryParameter("cmd", cmd)
            .apply { params.forEach { (k, v) -> addQueryParameter(k, v) } }
            .build()
        return fetch(url.toString())
    }

    private suspend inline fun <reified T> get(cmd: String, params: Map<String, String> = emptyMap()): T =
        decode(serializer<T>(), getRaw(cmd, params))

    private suspend inline fun <reified T> getMulti(cmds: List<String>): T =
        decode(serializer<T>(), fetch("$address/reqproc/proc_get?cmd=${cmds.joinToString(",")}&multi_data=1&isTest=false"))

    private suspend fun postRaw(goformId: String, params: Map<String, String> = emptyMap()): String {
        val form = FormBody.Builder()
            .add("isTest", "false")
            .add("goformId", goformId)
            .apply { params.forEach { (k, v) -> add(k, v) } }
            .build()
        val request = Request.Builder()
            .url("$address/reqproc/proc_post")
            .header("User-Agent", "Noroshi Android")
            .header("Referer", address)
            .post(form)
            .build()
        return execute(request)
    }

    private suspend inline fun <reified T> post(goformId: String, params: Map<String, String> = emptyMap()): T =
        decode(serializer<T>(), withRetry { postRaw(goformId, params) })

    private suspend fun action(kind: ActionKind, goformId: String, params: Map<String, String>): ActionResult =
        ActionResult(kind, post<ActionReply>(goformId, params).result)

    override suspend fun login() {
        val nonce = get<GetRandomLogin>("get_random_login").randomLogin
        val reply = post<LoginReply>(
            "LOGIN", mapOf(
                "username" to b64Encode(username),
                "password" to b64Encode(sha256Hex(nonce + password)),
                "unique_login_credentials" to "1",
            )
        )
        when (val result = reply.result) {
            LoginResult.Success -> Unit
            LoginResult.InvalidCredentials -> throw RouterException(res(R.string.err_login_wrong))
            LoginResult.MalformedRequest -> throw RouterException(res(R.string.err_login_malformed))
            is LoginResult.Other -> throw RouterException(res(R.string.err_login_code, result.code))
        }
    }

    override suspend fun logout() {
        withRetry { postRaw("LOGOUT") }
    }

    override suspend fun reboot() {
        try {
            postRaw("REBOOT_DEVICE")
        } catch (e: RouterException) {
            Log.d(TAG, "reboot response not read: ${e.message}")
        }
    }

    override suspend fun report(kind: ReportKind): Show = when (kind) {
        ReportKind.Info -> info()
        ReportKind.System -> system()
        ReportKind.Signal -> signal()
        ReportKind.Internet -> internet()
        ReportKind.Apn -> apn()
        ReportKind.Device -> device()
        ReportKind.Wifi -> wifi()
        ReportKind.Devices -> devices()
        ReportKind.SmsInfo -> smsInfo()
        ReportKind.Syslog -> TextShow(res(R.string.kind_syslog), syslog())
        ReportKind.Airtime -> airtime()
        ReportKind.Power -> power()
    }

    suspend fun info(): Info = getMulti(Info.CMDS)
    suspend fun system(): SystemInfo = getMulti(SystemInfo.CMDS)
    suspend fun wifi(): Wifi = getMulti(Wifi.CMDS)
    suspend fun power(): Power = getMulti(Power.CMDS)
    suspend fun devices(): StationList = get(StationList.CMD)
    suspend fun airtime(): AirtimeBalance = get(AirtimeBalance.CMD)

    suspend fun signal(): SignalReport = SignalReport(get(Signal.CMD), getMulti(CellExtras.CMDS))

    suspend fun internet(): InternetReport = InternetReport(getMulti(Internet.CMDS), get(WanDetails.CMD))

    suspend fun apn(): ApnReport =
        ApnReport(get<ApnStatus>(ApnStatus.CMD).apnMode, get<ApnConfig>(ApnConfig.CMD).profile)

    suspend fun device(): DeviceReport = DeviceReport(get(Device.CMD), get<SimIccid>(SimIccid.CMD).ziccid)

    suspend fun syslog(): String = fetch("$address/data/syslog.html?uniquelogincredentials=1&isTest=false")

    override suspend fun smsInbox(): SmsInbox = get(SmsInbox.CMD, SmsInbox.PARAMS)

    override suspend fun sms(id: Int): Message =
        smsInbox().messages.firstOrNull { it.id == id } ?: throw RouterException(res(R.string.err_no_message, id))

    suspend fun smsInfo(): SmsSettings = SmsSettings(get(SmsCapacity.CMD), get(SmsParameters.CMD))

    override suspend fun sendSms(number: String, message: String): ActionResult = action(
        ActionKind.Send, "SEND_SMS", mapOf(
            "notCallback" to "true",
            "encode_type" to "UNICODE",
            "ID" to "-1",
            "Number" to number,
            "MessageBody" to ucs2Encode(message),
            "sms_time" to RouterDateTime.routerTimeNow(),
        )
    )

    override suspend fun deleteSms(ids: Iterable<Int>): ActionResult =
        action(ActionKind.Delete, "DELETE_SMS", mapOf("msg_id" to joinMsgIds(ids)))

    override suspend fun deleteAllSms(): ActionResult? {
        val messages = smsInbox().messages
        if (messages.isEmpty()) return null
        return deleteSms(messages.map { it.id })
    }

    override suspend fun markSms(ids: Iterable<Int>): ActionResult =
        action(ActionKind.Mark, "SET_MSG_READ", mapOf("msg_id" to joinMsgIds(ids), "tag" to "0"))

    override suspend fun markAllSms(): ActionResult? {
        val unread = smsInbox().messages.filter { it.tag is MessageStatus.Unread }.map { it.id }
        if (unread.isEmpty()) return null
        return markSms(unread)
    }

    override suspend fun ussdSend(code: String) {
        post<ActionReply>("USSD_PROCESS", mapOf("USSD_operator" to "ussd_send", "USSD_send_number" to code, "notCallback" to "true"))
    }

    override suspend fun ussdReply(reply: String) {
        post<ActionReply>("USSD_PROCESS", mapOf("USSD_operator" to "ussd_reply", "USSD_reply_number" to reply, "notCallback" to "true"))
    }

    override suspend fun ussdCancel() {
        try {
            postRaw("USSD_PROCESS", mapOf("USSD_operator" to "ussd_cancel"))
        } catch (e: RouterException) {
            Log.d(TAG, "ussd cancel failed: ${e.message}")
        }
    }

    override suspend fun ussdAwaitResponse(): String {
        val maxPolls = 30
        var polls = 0
        while (true) {
            delay(1_000)
            when (val flag = get<UssdWriteFlag>(UssdWriteFlag.CMD).flag) {
                UssdFlag.Ready -> break
                is UssdFlag.Failed -> throw RouterException(res(R.string.err_ussd_failed, flag.reason))
                UssdFlag.Pending -> {
                    polls += 1
                    if (polls == maxPolls) throw RouterException(res(R.string.err_ussd_timeout, maxPolls))
                }
            }
        }
        return get<UssdData>(UssdData.CMD).text.trim()
    }
}
