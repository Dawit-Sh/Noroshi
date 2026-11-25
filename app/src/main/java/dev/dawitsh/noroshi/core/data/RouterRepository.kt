package dev.dawitsh.noroshi.core.data

import dev.dawitsh.noroshi.core.network.Detected
import dev.dawitsh.noroshi.core.network.Router
import dev.dawitsh.noroshi.core.network.RouterClient
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first

class RouterRepository(
    private val settings: SettingsRepository,
    private val client: RouterClient,
) {
    private val inboxChangesFlow = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    val inboxChanges: SharedFlow<Unit> = inboxChangesFlow.asSharedFlow()

    suspend fun open(): Router {
        var current = settings.settings.first()
        if (!settings.typeKnown.first()) {
            // First run: find out which router this is before the first login.
            client.detector.find(current.router)?.let { found ->
                settings.saveDetected(found.type, found.host)
                current = current.copy(type = found.type, router = found.host)
            }
        }
        return client.open(current.type, current.router, current.username, current.password)
    }

    suspend fun detect(host: String): Detected? = client.detector.find(host)

    suspend fun <T> session(block: suspend Router.() -> T): T = open().session(block)

    fun notifyInboxChanged() {
        inboxChangesFlow.tryEmit(Unit)
    }
}
