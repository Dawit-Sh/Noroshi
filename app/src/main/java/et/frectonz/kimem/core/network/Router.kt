package et.frectonz.kimem.core.network

import android.util.Log
import et.frectonz.kimem.core.model.ActionResult
import et.frectonz.kimem.core.model.Message
import et.frectonz.kimem.core.model.ReportKind
import et.frectonz.kimem.core.model.RouterType
import et.frectonz.kimem.core.model.Show
import et.frectonz.kimem.core.model.SmsInbox
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal const val TAG = "Kimem"

suspend fun <T> withRetry(action: suspend () -> T): T {
    val maxAttempts = 3
    val baseDelayMs = 200L
    for (attempt in 1..maxAttempts) {
        try {
            return action()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (attempt == maxAttempts) throw e
            val wait = baseDelayMs shl (attempt - 1)
            Log.d(TAG, "Retrying in ${wait}ms (${e.message})")
            delay(wait)
        }
    }
    error("unreachable")
}

/**
 * What the screens talk to. Each router family has its own backend:
 * [MifiRouter] for the Safaricom Kimem MiFi (ZTE-style JSON) and [AsrRouter]
 * for ASR / Marvell "Duster" panels such as the Stellar 8400 CPE.
 *
 * A backend only has to answer what its [RouterType] lists in
 * [RouterType.reports] and [RouterType.features]; the menus hide the rest.
 */
interface Router {
    val type: RouterType

    suspend fun login()
    suspend fun logout()
    suspend fun reboot()

    suspend fun report(kind: ReportKind): Show

    suspend fun smsInbox(): SmsInbox
    suspend fun sms(id: Int): Message
    suspend fun sendSms(number: String, message: String): ActionResult
    suspend fun deleteSms(ids: Iterable<Int>): ActionResult
    suspend fun deleteAllSms(): ActionResult?
    suspend fun markSms(ids: Iterable<Int>): ActionResult
    suspend fun markAllSms(): ActionResult?

    suspend fun ussdSend(code: String)
    suspend fun ussdReply(reply: String)
    suspend fun ussdCancel()
    suspend fun ussdAwaitResponse(): String

    suspend fun <T> session(block: suspend Router.() -> T): T {
        login()
        try {
            return block()
        } finally {
            withContext(NonCancellable) {
                try {
                    logout()
                } catch (e: Exception) {
                    Log.d(TAG, "logout failed: ${e.message}")
                }
            }
        }
    }
}

class RouterClient(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    fun open(type: RouterType, host: String, username: String, password: String): Router = when (type) {
        RouterType.SafaricomMifi -> MifiRouter(http, host, username, password)
        RouterType.Stellar -> AsrRouter(http, host, username, password)
    }

    val detector: RouterDetector by lazy { RouterDetector(http) }
}
