package dev.dawitsh.noroshi.core.network

import dev.dawitsh.noroshi.R
import dev.dawitsh.noroshi.core.codec.RouterDateTime
import dev.dawitsh.noroshi.core.codec.SmsLength
import dev.dawitsh.noroshi.core.codec.XmlNode
import dev.dawitsh.noroshi.core.codec.smsTimeNow
import dev.dawitsh.noroshi.core.model.ActionResult
import dev.dawitsh.noroshi.core.model.AsrSms
import dev.dawitsh.noroshi.core.model.AsrSmsCmdStatus
import dev.dawitsh.noroshi.core.model.AsrSmsInfo
import dev.dawitsh.noroshi.core.model.AsrSmsPage
import dev.dawitsh.noroshi.core.model.AsrSmsRequests
import dev.dawitsh.noroshi.core.model.AsrSmsStatus
import dev.dawitsh.noroshi.core.model.Message
import dev.dawitsh.noroshi.core.model.RouterException
import dev.dawitsh.noroshi.core.model.SmsBox
import dev.dawitsh.noroshi.core.model.SmsCmd
import dev.dawitsh.noroshi.core.model.SmsInbox
import dev.dawitsh.noroshi.core.model.res
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The Stellar's SMS, step for step as the panel's `SMS.js` does it, over the backend's own
 * `get` / `set` (so login, Digest and session loss stay in [AsrRouter]).
 *
 * The screens use whole-number ids while the router names messages like `LRCV12`, one series
 * per folder, so ids here are the folder's block ([BOX_SPAN] each) plus the number in the name.
 * A list of everything comes back merged, oldest first, the way the MiFi's inbox does.
 */
class AsrSmsClient(
    private val get: suspend (file: String) -> XmlNode,
    private val set: suspend (file: String, fields: List<Pair<String, String>>) -> XmlNode,
    private val pause: suspend (Long) -> Unit = { delay(it) },
    private val clock: () -> ZonedDateTime = ZonedDateTime::now,
) {
    suspend fun page(box: SmsBox, page: Int): AsrSmsPage {
        set(FILE, AsrSmsRequests.list(box, page))
        return AsrSmsPage.parse(get(FILE), box)
    }

    /** Every page of [box] in the router's order, [MAX_PAGES] at most. */
    suspend fun list(box: SmsBox): List<AsrSms> {
        val first = page(box, 1)
        val messages = first.messages.toMutableList()
        for (n in 2..minOf(first.totalPages, MAX_PAGES)) messages += page(box, n).messages
        return messages.distinctBy { it.index }
    }

    suspend fun info(): AsrSmsInfo = AsrSmsInfo.parse(get(FILE))

    /** Sends [text] to [contacts] and waits until the router says it went out. */
    suspend fun send(contacts: List<String>, text: String): ActionResult {
        if (contacts.isEmpty()) throw RouterException(res(R.string.err_sms_no_recipient))
        val length = SmsLength.of(text)
        if (length.tooLong) throw RouterException(res(R.string.err_sms_too_long, length.chars, length.encoding.maxChars))
        set(FILE, AsrSmsRequests.send(contacts, text, smsTimeNow(clock())))
        return await(SmsCmd.Send, SEND_POLLS)
    }

    suspend fun delete(box: SmsBox, indexes: List<String>): ActionResult {
        set(FILE, AsrSmsRequests.delete(box, indexes))
        return await(SmsCmd.Delete, POLLS)
    }

    /** The panel posts this and moves on; only a clear failure is an error here. */
    suspend fun markRead(box: SmsBox, index: String): ActionResult {
        set(FILE, AsrSmsRequests.markRead(box, index))
        val status = poll(SmsCmd.MarkRead, MARK_POLLS)
        if (status != null && !status.done) throw failed(SmsCmd.MarkRead, status)
        return ActionResult(SmsCmd.MarkRead.kind, SUCCESS)
    }

    // What the screens call, through Router.

    /** Every folder, merged and oldest first (the list screen turns it round). */
    suspend fun inbox(): SmsInbox = SmsInbox(
        all().sortedWith(compareBy<Numbered>({ it.sms.received ?: LocalDateTime.MIN }, { it.id })).map { it.toMessage() }
    )

    suspend fun message(id: Int): Message = find(id).toMessage()

    /** [number] may hold several recipients, split by commas, semicolons or spaces. */
    suspend fun sendSms(number: String, text: String): ActionResult =
        send(number.split(',', ';', ' ', '\n').map { it.trim() }.filter { it.isNotEmpty() }, text)

    suspend fun deleteSms(ids: Iterable<Int>): ActionResult {
        val wanted = ids.toSet()
        if (wanted.isEmpty()) throw RouterException(res(R.string.no_entries))
        var result: ActionResult? = null
        for ((box, boxIds) in wanted.groupBy(::boxOf)) {
            if (box == null) throw RouterException(res(R.string.err_no_message, boxIds.first()))
            val known = numbered(box).associateBy { it.id }
            val indexes = boxIds.map { id -> known[id]?.sms?.index ?: throw RouterException(res(R.string.err_no_message, id)) }
            result = delete(box, indexes)
        }
        return result!!
    }

    suspend fun deleteAllSms(): ActionResult? {
        var result: ActionResult? = null
        for (box in SmsBox.entries) {
            val indexes = list(box).map { it.index }
            if (indexes.isNotEmpty()) result = delete(box, indexes)
        }
        return result
    }

    suspend fun markSms(ids: Iterable<Int>): ActionResult {
        var result: ActionResult? = null
        for (id in ids.toSet()) {
            val found = find(id)
            result = markRead(found.sms.box, found.sms.index)
        }
        return result ?: throw RouterException(res(R.string.no_entries))
    }

    suspend fun markAllSms(): ActionResult? {
        var result: ActionResult? = null
        for (box in listOf(SmsBox.Inbox, SmsBox.Sim)) {
            for (sms in list(box).filter { it.status == AsrSmsStatus.Unread }) result = markRead(box, sms.index)
        }
        return result
    }

    private class Numbered(val id: Int, val sms: AsrSms) {
        fun toMessage() = Message(
            id = id,
            number = sms.number,
            content = sms.text,
            tag = sms.messageStatus(),
            date = RouterDateTime((sms.received ?: NO_DATE).atZone(ZoneId.systemDefault())),
            folder = res(sms.box.label),
        )
    }

    private suspend fun all(): List<Numbered> = SmsBox.entries.flatMap { numbered(it) }

    private suspend fun numbered(box: SmsBox): List<Numbered> = number(box, list(box))

    private suspend fun find(id: Int): Numbered {
        val box = boxOf(id) ?: throw RouterException(res(R.string.err_no_message, id))
        return numbered(box).firstOrNull { it.id == id } ?: throw RouterException(res(R.string.err_no_message, id))
    }

    private suspend fun await(cmd: SmsCmd, polls: Int): ActionResult {
        val status = poll(cmd, polls) ?: throw RouterException(res(R.string.err_sms_timeout, res(cmd.doing)))
        if (!status.done) throw failed(cmd, status)
        return ActionResult(cmd.kind, SUCCESS)
    }

    private fun failed(cmd: SmsCmd, status: AsrSmsCmdStatus) =
        RouterException(res(R.string.err_sms_failed, res(cmd.doing), status.result))

    /** Reads `sms_cmd_status_result` until [cmd] stops running; null if it never does. */
    private suspend fun poll(cmd: SmsCmd, polls: Int): AsrSmsCmdStatus? {
        repeat(polls) { attempt ->
            if (attempt > 0) pause(POLL_MS)
            val status = AsrSmsCmdStatus.parse(get(FILE))
            val ours = status.cmd.isEmpty() || status.cmd == cmd.code
            if (ours && !status.running) return status
        }
        return null
    }

    companion object {
        const val FILE = "message"
        const val MAX_PAGES = 30
        const val POLL_MS = 1_500L
        const val POLLS = 10
        const val SEND_POLLS = 40

        /** Mark read is fire and forget in the panel, so it gets only a quick look. */
        const val MARK_POLLS = 2

        /** Each folder's block of ids: inbox 1.., sent 100001.., SIM 200001.., drafts 300001.. */
        const val BOX_SPAN = 100_000

        /** A second name series inside one folder (say `SRCV` and `SSND` on the SIM) starts this far up. */
        const val SERIES_SPAN = 20_000

        /** Series past the fourth, and names without a usable number, get a slot in the folder's last part. */
        private const val SERIES_MAX = 4

        private const val SUCCESS = "success"
        private val NO_DATE: LocalDateTime = LocalDateTime.of(2000, 1, 1, 0, 0)

        fun boxOf(id: Int): SmsBox? = SmsBox.entries.getOrNull(id / BOX_SPAN)

        /**
         * Gives each message of one folder its id: the folder's block, plus [SERIES_SPAN] for each
         * name series before its own (alphabetically), plus the number in its name. Nothing is
         * left out: an odd name gets a slot from its hash in the block's last part.
         */
        private fun number(box: SmsBox, messages: List<AsrSms>): List<Numbered> {
            val base = box.ordinal * BOX_SPAN
            val series = messages.map { seriesOf(it.index) }.distinct().sorted()
            val taken = HashSet<Int>()
            return messages.map { sms ->
                val n = sms.index.filter(Char::isDigit).toIntOrNull()
                val rank = series.indexOf(seriesOf(sms.index))
                var id = if (n != null && n < SERIES_SPAN && rank < SERIES_MAX) base + rank * SERIES_SPAN + n else spare(base, sms.index, 0)
                var probe = 1
                while (!taken.add(id)) id = spare(base, sms.index, probe++)
                Numbered(id, sms)
            }
        }

        private fun spare(base: Int, index: String, probe: Int): Int {
            val room = BOX_SPAN - SERIES_MAX * SERIES_SPAN
            return base + SERIES_MAX * SERIES_SPAN + Math.floorMod(index.hashCode() + probe, room)
        }

        private fun seriesOf(index: String) = index.filterNot(Char::isDigit)

        internal fun idsFor(box: SmsBox, messages: List<AsrSms>): List<Pair<Int, String>> =
            number(box, messages).map { it.id to it.sms.index }
    }
}
