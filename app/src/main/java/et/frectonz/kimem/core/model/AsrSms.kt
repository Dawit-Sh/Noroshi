package et.frectonz.kimem.core.model

import androidx.annotation.StringRes
import et.frectonz.kimem.R
import et.frectonz.kimem.core.codec.SmsEncoding
import et.frectonz.kimem.core.codec.XmlNode
import et.frectonz.kimem.core.codec.decodeAlphanumericSender
import et.frectonz.kimem.core.codec.parseSmsTime
import et.frectonz.kimem.core.codec.smsHexDecode
import et.frectonz.kimem.core.codec.smsTimeNow
import et.frectonz.kimem.core.codec.ucs2Encode
import java.time.LocalDateTime

/*
 * SMS on ASR "Duster" panels (`file=message`), written from the panel's `js/panel/SMS/SMS.js`.
 * Every call is a `set` with an `<RGW><message>` body; what the panel then shows comes from the
 * `get` that follows it.
 */

/** The panel's four SMS folders, with the `mem_store` and `tags` it sends for each. */
enum class SmsBox(@StringRes val label: Int, val listFlag: String, val memStore: String, val tags: String) {
    Inbox(R.string.sms_box_inbox, "GET_RCV_SMS_LOCAL", "1", "12"),
    Sent(R.string.sms_box_sent, "GET_SENT_SMS_LOCAL", "1", "2"),
    Sim(R.string.sms_box_sim, "GET_SIM_SMS", "0", ""),
    Drafts(R.string.sms_box_drafts, "GET_DRAFT_SMS", "2", "2"),
}

/** `flag/sms_cmd`: what the panel sends with each change and later reads back. */
enum class SmsCmd(val code: String, val flag: String, val kind: ActionKind, @StringRes val doing: Int) {
    Send("4", "SEND_SMS", ActionKind.Send, R.string.sms_doing_send),
    Delete("6", "DELETE_SMS", ActionKind.Delete, R.string.sms_doing_delete),
    MarkRead("7", "SET_MSG_READ", ActionKind.Mark, R.string.sms_doing_mark),
}

enum class AsrSmsStatus {
    Unread, Read, SendFailed, Sent, Draft, Unknown;

    companion object {
        fun parse(raw: String): AsrSmsStatus = when (raw.trim()) {
            "0" -> Unread
            "1" -> Read
            "2" -> SendFailed
            "3" -> Sent
            "4" -> Draft
            else -> Unknown
        }
    }
}

data class AsrSms(
    /** The router's own id, like `LRCV1`: what delete and mark read take. */
    val index: String,
    val box: SmsBox,
    /** The phonebook name the router puts before the number, if any. */
    val name: String,
    /** The sender, or for sent messages and drafts the recipients. */
    val number: String,
    val text: String,
    val received: LocalDateTime?,
    val status: AsrSmsStatus,
    val rawStatus: String,
) {
    fun messageStatus(): MessageStatus = when (status) {
        AsrSmsStatus.Unread -> MessageStatus.Unread
        AsrSmsStatus.Read -> MessageStatus.Read
        AsrSmsStatus.Sent -> MessageStatus.Sent
        AsrSmsStatus.SendFailed -> MessageStatus.Failed
        AsrSmsStatus.Draft -> MessageStatus.Draft
        AsrSmsStatus.Unknown -> MessageStatus.Unknown(rawStatus)
    }
}

/** Router-wide counts that come with every `get` of `file=message`: `*_total` is room, `*_num` is used. */
data class AsrSmsCapacity(
    val deviceTotal: Int?,
    val inboxTotal: Int?,
    val inboxUsed: Int?,
    val sentTotal: Int?,
    val sentUsed: Int?,
    val draftsTotal: Int?,
    val draftsUsed: Int?,
    val simTotal: Int?,
    val simUsed: Int?,
) {
    companion object {
        fun parse(root: XmlNode) = AsrSmsCapacity(
            deviceTotal = root.int("sms_nv_total"),
            inboxTotal = root.int("sms_nv_rev_total"),
            inboxUsed = root.int("sms_nv_rev_num"),
            sentTotal = root.int("sms_nv_send_total"),
            sentUsed = root.int("sms_nv_send_num"),
            draftsTotal = root.int("sms_nv_draftbox_total"),
            draftsUsed = root.int("sms_nv_draftbox_num"),
            simTotal = root.int("sms_sim_total"),
            simUsed = root.int("sms_sim_num"),
        )
    }
}

data class AsrSmsPage(
    val box: SmsBox,
    val messages: List<AsrSms>,
    /** `total_number` counts pages (ten messages each), not messages. */
    val totalPages: Int,
) {
    companion object {
        fun parse(root: XmlNode, box: SmsBox): AsrSmsPage {
            val items = root.find("message_list")?.findAll("Item").orEmpty()
            val messages = items.mapNotNull { item ->
                val index = item.textOf("index")
                if (index.isEmpty()) return@mapNotNull null
                val (name, number) = smsSender(smsHexDecode(item.textOf("from")))
                val rawStatus = item.textOf("status")
                AsrSms(
                    index = index,
                    box = box,
                    name = name,
                    number = number,
                    text = smsHexDecode(item.textOf("subject")),
                    received = parseSmsTime(item.textOf("received")),
                    status = AsrSmsStatus.parse(rawStatus),
                    rawStatus = rawStatus,
                )
            }
            val pages = root.textOf("total_number").filter(Char::isDigit).toIntOrNull() ?: if (messages.isEmpty()) 0 else 1
            return AsrSmsPage(box, messages, pages)
        }
    }
}

/**
 * `from` is `name;number`, the name coming from the router's phonebook and usually empty. A
 * sender name the firmware turned into digits is read back first, since those digits can hold
 * a `;` of their own.
 */
fun smsSender(from: String): Pair<String, String> {
    val raw = from.trim()
    val decoded = decodeAlphanumericSender(raw)
    if (decoded != raw) return "" to decoded
    val cut = raw.indexOf(';')
    if (cut < 0) return "" to raw
    val name = raw.substring(0, cut).trim()
    val number = raw.substring(cut + 1).trim().trimEnd(';')
    return if (number.isEmpty()) "" to decodeAlphanumericSender(name) else name to decodeAlphanumericSender(number)
}

/** `sms_cmd_status_result`: 1 running, 2 failed, 3 done, 9 partly done; empty before anything ran. */
data class AsrSmsCmdStatus(val cmd: String, val result: String) {
    val done: Boolean get() = result == "3"
    val running: Boolean get() = result == "1" || result.isEmpty()

    companion object {
        fun parse(root: XmlNode) = AsrSmsCmdStatus(root.textOf("sms_cmd"), root.textOf("sms_cmd_status_result"))
    }
}

/** The `set` bodies, as `RGW/...` paths in the order the panel puts them. */
object AsrSmsRequests {
    fun list(box: SmsBox, page: Int): List<Pair<String, String>> = listOf(
        "RGW/message/flag/message_flag" to box.listFlag,
        "RGW/message/get_message/page_number" to page.toString(),
    )

    fun send(contacts: List<String>, text: String, time: String = smsTimeNow()): List<Pair<String, String>> = listOf(
        "RGW/message/flag/message_flag" to SmsCmd.Send.flag,
        "RGW/message/flag/sms_cmd" to SmsCmd.Send.code,
        "RGW/message/send_save_message/contacts" to contacts.joinToString(","),
        "RGW/message/send_save_message/content" to ucs2Encode(text),
        "RGW/message/send_save_message/encode_type" to SmsEncoding.of(text).wire,
        "RGW/message/send_save_message/sms_time" to time,
    )

    fun delete(box: SmsBox, indexes: List<String>): List<Pair<String, String>> = listOf(
        "RGW/message/flag/message_flag" to SmsCmd.Delete.flag,
        "RGW/message/flag/sms_cmd" to SmsCmd.Delete.code,
        "RGW/message/get_message/tags" to box.tags,
        "RGW/message/get_message/mem_store" to box.memStore,
        "RGW/message/set_message/delete_message_id" to indexes.joinToString("") { "$it," },
    )

    fun markRead(box: SmsBox, index: String): List<Pair<String, String>> = listOf(
        "RGW/message/flag/message_flag" to SmsCmd.MarkRead.flag,
        "RGW/message/flag/sms_cmd" to SmsCmd.MarkRead.code,
        "RGW/message/get_message/tags" to box.tags,
        "RGW/message/get_message/mem_store" to box.memStore,
        "RGW/message/set_message/read_message_id" to index,
    )
}

/** `get sms info` for the Stellar: room in each folder and the SMS settings page. */
class AsrSmsInfo(
    val capacity: AsrSmsCapacity,
    val center: String,
    val statusReport: Boolean,
    val saveLocation: String,
    val validity: String,
) : Show {
    private fun used(used: Int?, total: Int?): UiText =
        if (used == null && total == null) res(R.string.dash) else res(R.string.of, used?.toString() ?: "?", total?.toString() ?: "?")

    override fun table() = Report.KeyValues(
        res(R.string.kind_sms_info), rows(
            R.string.sms_box_inbox_title to used(capacity.inboxUsed, capacity.inboxTotal),
            R.string.sms_box_sent_title to used(capacity.sentUsed, capacity.sentTotal),
            R.string.sms_box_drafts_title to used(capacity.draftsUsed, capacity.draftsTotal),
            R.string.sms_box_sim_title to used(capacity.simUsed, capacity.simTotal),
            R.string.sms_smsc to orDash(center),
            R.string.sms_delivery_reports to yesNo(statusReport),
            R.string.sms_default_store to when (saveLocation) {
                "0" -> res(R.string.store_sim)
                "1" -> res(R.string.store_device)
                else -> orDash(saveLocation)
            },
            R.string.sms_validity to when (validity) {
                "143" -> res(R.string.sms_validity_12h)
                "167" -> res(R.string.sms_validity_day)
                "173" -> res(R.string.sms_validity_week)
                "255" -> res(R.string.sms_validity_max)
                else -> orDash(validity)
            },
        )
    )

    companion object {
        fun parse(root: XmlNode): AsrSmsInfo {
            val setting = root.find("sms_setting") ?: root
            return AsrSmsInfo(
                capacity = AsrSmsCapacity.parse(root),
                center = setting.textOf("sms_center"),
                statusReport = setting.textOf("status_report") == "1",
                saveLocation = setting.textOf("save_location"),
                validity = setting.textOf("save_time"),
            )
        }
    }
}

private fun XmlNode.int(name: String): Int? = textOf(name).toIntOrNull()
