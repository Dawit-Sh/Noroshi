package et.frectonz.kimem.core.network

import et.frectonz.kimem.core.model.RouterType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** A router found on the network. */
data class Detected(val type: RouterType, val host: String)

/**
 * Works out which kind of router answers at an address, without logging in:
 * an ASR / Duster panel (Stellar) answers `GET /login.cgi` with a Digest challenge for the
 * realm `Highwmg`; the Safaricom MiFi hands out its login nonce at
 * `/reqproc/proc_get?cmd=get_random_login`.
 */
class RouterDetector(http: OkHttpClient) {
    private val quick = http.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(6, TimeUnit.SECONDS)
        .build()

    /** The answer to [url], or failure when nothing answered (wrong address, not on that network). */
    private suspend fun <T> probe(url: String, read: (okhttp3.Response) -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(quick.newCall(Request.Builder().url(url).header("User-Agent", "Kimem Android").build()).execute().use(read))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun detect(host: String): RouterType? {
        if (host.isBlank()) return null
        val address = "http://${host.trim()}"

        val challenge = probe("$address/login.cgi") { it.header("WWW-Authenticate") }
        if (challenge.isFailure) return null
        if (AsrDigest.isAsrChallenge(challenge.getOrNull())) return RouterType.Stellar

        val nonce = probe("$address/reqproc/proc_get?isTest=false&cmd=get_random_login") { r ->
            if (r.isSuccessful) r.body.string() else null
        }.getOrNull()
        if (nonce != null && nonce.contains("\"random_login\"")) return RouterType.SafaricomMifi

        return null
    }

    /** Tries [host] and each router type's default address at once; [host] wins when several answer. */
    suspend fun find(host: String): Detected? = coroutineScope {
        val candidates = (listOf(host.trim()) + RouterType.entries.map { it.defaultHost })
            .filter { it.isNotEmpty() }
            .distinct()
        candidates.map { candidate -> async { detect(candidate)?.let { Detected(it, candidate) } } }
            .awaitAll()
            .firstOrNull { it != null }
    }
}
