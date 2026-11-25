package dev.dawitsh.noroshi.core.network

import android.util.Log
import dev.dawitsh.noroshi.R
import dev.dawitsh.noroshi.core.model.ActionResult
import dev.dawitsh.noroshi.core.model.AsrDeviceUsage
import dev.dawitsh.noroshi.core.model.AsrDevices
import dev.dawitsh.noroshi.core.model.AsrStatus
import dev.dawitsh.noroshi.core.model.AsrTraffic
import dev.dawitsh.noroshi.core.model.BundlePlan
import dev.dawitsh.noroshi.core.model.CUT_OFF_OFF
import dev.dawitsh.noroshi.core.model.CountMode
import dev.dawitsh.noroshi.core.model.DeviceUsage
import dev.dawitsh.noroshi.core.model.correction
import dev.dawitsh.noroshi.core.model.Message
import dev.dawitsh.noroshi.core.model.ReportKind
import dev.dawitsh.noroshi.core.model.RouterException
import dev.dawitsh.noroshi.core.model.RouterType
import dev.dawitsh.noroshi.core.model.Show
import dev.dawitsh.noroshi.core.model.SmsInbox
import dev.dawitsh.noroshi.core.model.res
import okhttp3.OkHttpClient

/**
 * ASR / Marvell "Duster" panels (the Stellar 8400 CPE). What the panel's files mean lives
 * here; how to reach them (Digest login, XML get / set) is [AsrTransport].
 */
class AsrRouter(
    http: OkHttpClient,
    host: String,
    username: String,
    password: String,
) : Router {
    override val type = RouterType.Stellar

    /** The login and the XML calls; SMS and USSD build on its `get` / `set`. */
    val transport = AsrTransport(http, host, username, password)

    private val sms = AsrSmsClient(transport::get, transport::set)

    override suspend fun login() = transport.login()

    override suspend fun logout() = transport.logout()

    suspend fun status(): AsrStatus = AsrStatus.parse(transport.get("status1"))

    suspend fun devices(): AsrDevices = AsrDevices.parse(transport.get("device_management_all"))

    /** The router's own data count and bundle settings (`file=statistics`). */
    suspend fun traffic(): AsrTraffic = AsrTraffic.parse(transport.get("statistics"))

    /** Per-device use (`file=device_management`); counts above [cap] are dropped as broken. */
    suspend fun deviceUsage(cap: Long?): List<DeviceUsage> = AsrDeviceUsage.parse(transport.get("device_management"), cap)

    /**
     * Makes the router count against [plan]: per period, its dates, its size, and the used amount,
     * each written the way the panel's buttons write it. The cut-off at the limit is never turned
     * on; if the firmware turns it on by itself while setting up, it is turned back off.
     */
    suspend fun setBundle(plan: BundlePlan): AsrTraffic {
        val before = traffic()
        for (fields in plan.writes()) transport.set(STATISTICS, fields)
        var after = traffic()
        if (after.cutOff && !before.cutOff) {
            transport.set(STATISTICS, CUT_OFF_OFF)
            after = traffic()
        }
        return after
    }

    /** Sets the used amount of the current count, for when the operator's figure differs. */
    suspend fun correctUsed(mode: CountMode, used: Long): AsrTraffic {
        transport.set(STATISTICS, correction(mode, used))
        return traffic()
    }

    override suspend fun report(kind: ReportKind): Show = when (kind) {
        ReportKind.Info -> status().info()
        ReportKind.Signal -> status().signal()
        ReportKind.Internet -> status().internet()
        ReportKind.Apn -> status().apn()
        ReportKind.Device -> status().device()
        ReportKind.Wifi -> status().wifi()
        ReportKind.Devices -> devices()
        ReportKind.SmsInfo -> sms.info()
        else -> unsupported()
    }

    /** The panel's reboot page only reads `file=reset`; the router drops the connection right after. */
    override suspend fun reboot() {
        try {
            transport.get("reset")
        } catch (e: RouterException) {
            Log.d(TAG, "reboot response not read: ${e.message}")
        }
    }

    private fun unsupported(): Nothing = throw RouterException(res(R.string.err_not_supported, res(type.label)))

    override suspend fun smsInbox(): SmsInbox = sms.inbox()
    override suspend fun sms(id: Int): Message = sms.message(id)
    override suspend fun sendSms(number: String, message: String): ActionResult = sms.sendSms(number, message)
    override suspend fun deleteSms(ids: Iterable<Int>): ActionResult = sms.deleteSms(ids)
    override suspend fun deleteAllSms(): ActionResult? = sms.deleteAllSms()
    override suspend fun markSms(ids: Iterable<Int>): ActionResult = sms.markSms(ids)
    override suspend fun markAllSms(): ActionResult? = sms.markAllSms()

    override suspend fun ussdSend(code: String) {
        unsupported()
    }

    override suspend fun ussdReply(reply: String) {
        unsupported()
    }

    override suspend fun ussdCancel() = Unit

    override suspend fun ussdAwaitResponse(): String = unsupported()

    private companion object {
        const val STATISTICS = "statistics"
    }
}
