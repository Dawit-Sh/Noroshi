package dev.dawitsh.noroshi

import dev.dawitsh.noroshi.core.codec.SmsEncoding
import dev.dawitsh.noroshi.core.codec.SmsLength
import dev.dawitsh.noroshi.core.codec.XmlNode
import dev.dawitsh.noroshi.core.codec.buildRgwXml
import dev.dawitsh.noroshi.core.codec.decodeAlphanumericSender
import dev.dawitsh.noroshi.core.codec.isGsm7
import dev.dawitsh.noroshi.core.codec.parseSmsTime
import dev.dawitsh.noroshi.core.codec.smsHexDecode
import dev.dawitsh.noroshi.core.codec.smsTimeNow
import dev.dawitsh.noroshi.core.codec.ucs2Encode
import dev.dawitsh.noroshi.core.model.AsrSmsCmdStatus
import dev.dawitsh.noroshi.core.model.AsrSmsInfo
import dev.dawitsh.noroshi.core.model.AsrSmsPage
import dev.dawitsh.noroshi.core.model.AsrSmsRequests
import dev.dawitsh.noroshi.core.model.AsrSmsStatus
import dev.dawitsh.noroshi.core.model.MessageStatus
import dev.dawitsh.noroshi.core.model.RouterException
import dev.dawitsh.noroshi.core.model.SmsBox
import dev.dawitsh.noroshi.core.model.smsSender
import dev.dawitsh.noroshi.core.network.AsrSmsClient
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

class AsrSmsTest {
    // Text

    @Test
    fun encodingMatchesThePanelsUniEncode() {
        assertEquals("00480069", ucs2Encode("Hi"))
        assertEquals("12201208", ucs2Encode("ሠለ"))
        assertEquals("d83dde00", ucs2Encode("😀"))
    }

    @Test
    fun decodingReadsEitherCaseAndNormalisesLineBreaks() {
        assertEquals("Hi", smsHexDecode("00480069"))
        assertEquals("ሠለ", smsHexDecode("12201208"))
        assertEquals("a\nb\nc", smsHexDecode("0061000d000a0062000d0063"))
        assertEquals("😀", smsHexDecode("D83DDE00"))
        assertEquals("H", smsHexDecode("004800"))
        assertEquals("�H", smsHexDecode("zz000048"))
        assertEquals("", smsHexDecode(""))
        // Always hex, even when it could pass for a number.
        assertEquals("ऑ∳", smsHexDecode("09112233"))
    }

    @Test
    fun roundTripKeepsEveryUtf16Unit() {
        val text = "Balance: 12.50 Birr — ቀሪ ሂሳብ 💰"
        assertEquals(text, smsHexDecode(ucs2Encode(text)))
    }

    @Test
    fun gsm7FollowsThePanel() {
        assertTrue(isGsm7("Hello, are you there? 100%"))
        assertTrue(isGsm7("Café €5 ΩΣ"))
        assertFalse(isGsm7("back`tick"))
        assertFalse(isGsm7("ሰላም"))
        assertFalse(isGsm7("niño"))
        assertEquals(SmsEncoding.Gsm7, SmsEncoding.of("plain"))
        assertEquals(SmsEncoding.Unicode, SmsEncoding.of("ሰላም"))
    }

    @Test
    fun lengthCountsPartsLikeThePanel() {
        assertEquals(1, SmsLength.of("a".repeat(160)).parts)
        assertEquals(2, SmsLength.of("a".repeat(161)).parts)
        assertEquals(5, SmsLength.of("a".repeat(765)).parts)
        assertTrue(SmsLength.of("a".repeat(766)).tooLong)
        assertEquals(1, SmsLength.of("ሀ".repeat(70)).parts)
        assertEquals(2, SmsLength.of("ሀ".repeat(71)).parts)
        assertTrue(SmsLength.of("ሀ".repeat(336)).tooLong)
        assertFalse(SmsLength.of("ሀ".repeat(335)).tooLong)
        assertEquals(0, SmsLength.of("").parts)
    }

    @Test
    fun sendTimeIsThePanelsFormat() {
        assertEquals("26,11,5,9,4,7,%2B3", smsTimeNow(ZonedDateTime.of(2026, 11, 5, 9, 4, 7, 0, ZoneOffset.ofHours(3))))
        assertEquals("05,1,2,23,0,0,-0", smsTimeNow(ZonedDateTime.of(2005, 1, 2, 23, 0, 0, 0, ZoneOffset.UTC)))
        assertEquals("26,1,1,0,0,0,%2B5.5", smsTimeNow(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHoursMinutes(5, 30))))
        assertEquals("26,1,1,0,0,0,-5", smsTimeNow(ZonedDateTime.of(2026, 1, 1, 0, 0, 0, 0, ZoneOffset.ofHours(-5))))
    }

    @Test
    fun receivedTimeParsesWithoutPadding() {
        assertEquals(LocalDateTime.of(2026, 11, 5, 9, 4, 7), parseSmsTime("26,11,5,9,4,7,+3"))
        assertEquals(LocalDateTime.of(2026, 1, 15, 21, 30, 0), parseSmsTime("26,01,15,21,30,00,%2B3"))
        assertNull(parseSmsTime("26,13,5,9,4,7,+3"))
        assertNull(parseSmsTime("garbage"))
        assertNull(parseSmsTime(""))
    }

    @Test
    fun alphanumericSendersAreReadBack() {
        assertEquals("RSCHS", decodeAlphanumericSender(";2=9>01935"))
        for (name in listOf("Safaricom", "M-PESA", "Ethiotel", "MPESA", "Bank Alert", "ABC", "telebirr", "Awash Bank", "M-Birr")) {
            assertEquals(name, decodeAlphanumericSender(mangle(name)))
            assertEquals(name, decodeAlphanumericSender(mangle(name, lead = 0xB)))
            assertEquals("" to name, smsSender(mangle(name)))
        }
        assertEquals("+251900000001", decodeAlphanumericSender("+251900000001"))
        assertEquals("0911223344", decodeAlphanumericSender("0911223344"))
        assertEquals("811", decodeAlphanumericSender("811"))
        // "CBE" packs into plain digits, so it stays as the firmware shows it.
        assertEquals("341611", decodeAlphanumericSender(mangle("CBE")))
    }

    @Test
    fun senderSplitsNameAndNumber() {
        assertEquals("" to "+251900000001", smsSender(";+251900000001"))
        assertEquals("" to "+251900000001", smsSender("+251900000001;"))
        assertEquals("Mom" to "0900000002", smsSender("Mom;0900000002"))
        assertEquals("" to "811", smsSender("811"))
    }

    // Requests

    private fun body(fields: List<Pair<String, String>>) = buildRgwXml(fields)

    @Test
    fun requestBodiesMatchWhatThePanelPosts() {
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"US-ASCII\"?> <RGW><message><flag><message_flag>GET_RCV_SMS_LOCAL</message_flag></flag>" +
                "<get_message><page_number>2</page_number></get_message></message></RGW>",
            body(AsrSmsRequests.list(SmsBox.Inbox, 2)),
        )
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"US-ASCII\"?> <RGW><message><flag><message_flag>SEND_SMS</message_flag><sms_cmd>4</sms_cmd></flag>" +
                "<send_save_message><contacts>0911000000,+251922000000</contacts><content>00480069</content>" +
                "<encode_type>GSM7_default</encode_type><sms_time>26,11,5,9,4,7,%2B3</sms_time></send_save_message></message></RGW>",
            body(AsrSmsRequests.send(listOf("0911000000", "+251922000000"), "Hi", "26,11,5,9,4,7,%2B3")),
        )
        assertTrue(body(AsrSmsRequests.send(listOf("1"), "ሰላም", "t")).contains("<encode_type>UNICODE</encode_type>"))
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"US-ASCII\"?> <RGW><message><flag><message_flag>DELETE_SMS</message_flag><sms_cmd>6</sms_cmd></flag>" +
                "<get_message><tags>12</tags><mem_store>1</mem_store></get_message>" +
                "<set_message><delete_message_id>LRCV1,LRCV7,</delete_message_id></set_message></message></RGW>",
            body(AsrSmsRequests.delete(SmsBox.Inbox, listOf("LRCV1", "LRCV7"))),
        )
        assertTrue(body(AsrSmsRequests.delete(SmsBox.Sim, listOf("S1"))).contains("<tags></tags><mem_store>0</mem_store>"))
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"US-ASCII\"?> <RGW><message><flag><message_flag>SET_MSG_READ</message_flag><sms_cmd>7</sms_cmd></flag>" +
                "<get_message><tags>12</tags><mem_store>1</mem_store></get_message>" +
                "<set_message><read_message_id>LRCV3</read_message_id></set_message></message></RGW>",
            body(AsrSmsRequests.markRead(SmsBox.Inbox, "LRCV3")),
        )
    }

    // Replies

    @Test
    fun pageParsesItems() {
        val page = AsrSmsPage.parse(XmlNode.parse(INBOX_REPLY), SmsBox.Inbox)
        assertEquals(2, page.totalPages)
        assertEquals(3, page.messages.size)

        val first = page.messages[0]
        assertEquals("LRCV12", first.index)
        assertEquals("+251900000001", first.number)
        assertEquals("", first.name)
        assertEquals("Hello\nthere", first.text)
        assertEquals(LocalDateTime.of(2026, 11, 25, 9, 4, 7), first.received)
        assertEquals(AsrSmsStatus.Unread, first.status)
        assertEquals(MessageStatus.Unread, first.messageStatus())

        assertEquals("Mom", page.messages[1].name)
        assertEquals("0900000002", page.messages[1].number)
        assertEquals(MessageStatus.Read, page.messages[1].messageStatus())
        assertEquals("Safaricom", page.messages[2].number)
    }

    @Test
    fun emptyPagesHaveNoMessages() {
        val page = AsrSmsPage.parse(XmlNode.parse("<RGW><message><message_list></message_list><total_number>0</total_number></message></RGW>"), SmsBox.Sim)
        assertTrue(page.messages.isEmpty())
        assertEquals(0, page.totalPages)
        assertTrue(AsrSmsPage.parse(XmlNode.parse("<RGW/>"), SmsBox.Sent).messages.isEmpty())
    }

    @Test
    fun infoReadsCountsAndSettings() {
        val info = AsrSmsInfo.parse(XmlNode.parse(INBOX_REPLY))
        assertEquals(100, info.capacity.inboxTotal)
        assertEquals(13, info.capacity.inboxUsed)
        assertEquals(30, info.capacity.simTotal)
        assertEquals(0, info.capacity.simUsed)
        assertEquals("+251000000000", info.center)
        assertFalse(info.statusReport)
        assertEquals("1", info.saveLocation)
        assertEquals("255", info.validity)
    }

    private fun fixture(name: String): XmlNode =
        XmlNode.parse(javaClass.getResourceAsStream("/stellar/$name")!!.bufferedReader().readText())

    /** The Stellar's real answer to the inbox list (2026-11-25): counts and settings, an empty list. */
    @Test
    fun realInboxAnswerParses() {
        val root = fixture("message_inbox_empty.xml")
        val page = AsrSmsPage.parse(root, SmsBox.Inbox)
        assertTrue(page.messages.isEmpty())
        assertEquals(1, page.totalPages)

        val info = AsrSmsInfo.parse(root)
        assertEquals(180, info.capacity.deviceTotal)
        assertEquals(100, info.capacity.inboxTotal)
        assertEquals(101, info.capacity.inboxUsed)
        assertEquals(50, info.capacity.sentTotal)
        assertEquals(0, info.capacity.sentUsed)
        assertEquals(30, info.capacity.draftsTotal)
        assertEquals(2, info.capacity.simTotal)
        assertEquals(0, info.capacity.simUsed)
        assertEquals("+251700060105", info.center)
        assertFalse(info.statusReport)
        assertEquals("1", info.saveLocation)
        assertEquals("255", info.validity)
        assertEquals("3", AsrSmsCmdStatus.parse(root).result)
    }

    /** A `get` before any list was picked: every count is empty, which must not throw. */
    @Test
    fun realPlainAnswerHasEmptyCounts() {
        val root = fixture("message_plain.xml")
        assertTrue(AsrSmsPage.parse(root, SmsBox.Inbox).messages.isEmpty())
        assertEquals(0, AsrSmsPage.parse(root, SmsBox.Inbox).totalPages)
        val info = AsrSmsInfo.parse(root)
        assertNull(info.capacity.inboxTotal)
        assertEquals("+251700060105", info.center)
    }

    /**
     * The Stellar's real SIM list with one message (2026-11-25, number and text replaced): the
     * sender comes as `;number`, the hex in upper case, the name in the SIM's own series.
     */
    @Test
    fun realSimMessageParses() {
        val page = AsrSmsPage.parse(fixture("message_sim_one.xml"), SmsBox.Sim)
        assertEquals(1, page.totalPages)
        val sms = page.messages.single()
        assertEquals("SRCV1", sms.index)
        assertEquals("", sms.name)
        assertEquals("+251900000032", sms.number)
        assertEquals("Test text from a phone ", sms.text)
        assertEquals(LocalDateTime.of(2026, 11, 25, 12, 36, 52), sms.received)
        assertEquals(MessageStatus.Unread, sms.messageStatus())
    }

    @Test
    fun realSimMessageGetsASimId() = runTest {
        val raw = javaClass.getResourceAsStream("/stellar/message_sim_one.xml")!!.bufferedReader().readText()
        val item = Regex("<Item[\\s\\S]*?</Item>").find(raw)!!.value
        val router = FakeRouter(mapOf(SmsBox.Sim to listOf(listOf(item))))
        val message = router.client().inbox().messages.single()
        assertEquals(200_001, message.id)
        assertEquals("+251900000032", message.number)
        assertEquals("Test text from a phone", router.client().message(200_001).content.trim())
    }

    /** The device inbox numbers its messages from 0 (`LRCV0` was a real one). */
    @Test
    fun inboxNamesStartAtZero() = runTest {
        val router = FakeRouter(mapOf(SmsBox.Inbox to listOf(listOf(item("LRCV0")))), statuses = listOf(status("6", "3")))
        assertEquals(listOf(0), router.client().inbox().messages.map { it.id })
        router.client().deleteSms(listOf(0))
        assertTrue(router.calls.any { it.second.contains("<delete_message_id>LRCV0,</delete_message_id>") })
    }

    @Test
    fun statusesMapToTheScreensTags() {
        assertEquals(AsrSmsStatus.SendFailed, AsrSmsStatus.parse("2"))
        assertEquals(AsrSmsStatus.Sent, AsrSmsStatus.parse("3"))
        assertEquals(AsrSmsStatus.Draft, AsrSmsStatus.parse("4"))
        assertEquals(AsrSmsStatus.Unknown, AsrSmsStatus.parse("x"))
    }

    // Client

    @Test
    fun listReadsEveryPage() = runTest {
        val router = FakeRouter(mapOf(SmsBox.Inbox to listOf(listOf(item("LRCV2")), listOf(item("LRCV1")))))
        val inbox = router.client().list(SmsBox.Inbox)
        assertEquals(listOf("LRCV2", "LRCV1"), inbox.map { it.index })
        assertEquals(listOf("set", "get", "set", "get"), router.calls.map { it.first })
    }

    @Test
    fun inboxMergesFoldersOldestFirstWithIds() = runTest {
        val router = FakeRouter(
            mapOf(
                SmsBox.Inbox to listOf(listOf(item("LRCV3", time = "26,11,25,9,0,0,+3"), item("LRCV1", time = "26,11,20,9,0,0,+3"))),
                SmsBox.Sent to listOf(listOf(item("LSND2", status = "3", time = "26,11,22,9,0,0,+3"))),
                SmsBox.Sim to listOf(listOf(item("SRCV1", time = "26,11,21,9,0,0,+3"), item("SSND1", status = "3", time = "26,11,23,9,0,0,+3"))),
            )
        )
        val inbox = router.client().inbox()
        assertEquals(listOf(1, 200001, 100002, 220001, 3), inbox.messages.map { it.id })
        assertEquals(MessageStatus.Sent, inbox.messages[2].tag)
        assertEquals(listOf(SmsBox.Inbox, SmsBox.Sim, SmsBox.Sent, SmsBox.Sim, SmsBox.Inbox).map { dev.dawitsh.noroshi.core.model.res(it.label) }, inbox.messages.map { it.folder })
    }

    @Test
    fun oddNamesStillGetTheirOwnIds() = runTest {
        val router = FakeRouter(mapOf(SmsBox.Inbox to listOf(listOf(item("LRCV1"), item("NODIGITS"), item("LRCV99999")))))
        val ids = router.client().inbox().messages.map { it.id }
        assertEquals(3, ids.toSet().size)
        assertTrue(ids.all { it in 1 until AsrSmsClient.BOX_SPAN })
    }

    @Test
    fun deleteFindsTheRoutersNamesAndWaits() = runTest {
        val router = FakeRouter(
            mapOf(SmsBox.Inbox to listOf(listOf(item("LRCV4"), item("LRCV5"))), SmsBox.Sent to listOf(listOf(item("LSND1", status = "3")))),
            statuses = listOf(status("6", "1"), status("6", "3"), status("6", "3")),
        )
        router.client().deleteSms(listOf(4, 100001))
        val deletes = router.calls.filter { it.second.contains("DELETE_SMS") }.map { it.second }
        assertEquals(2, deletes.size)
        assertTrue(deletes[0].contains("<tags>12</tags><mem_store>1</mem_store>") && deletes[0].contains("<delete_message_id>LRCV4,</delete_message_id>"))
        assertTrue(deletes[1].contains("<tags>2</tags>") && deletes[1].contains("<delete_message_id>LSND1,</delete_message_id>"))
    }

    @Test
    fun unknownIdsAreAnError() = runTest {
        val router = FakeRouter(mapOf(SmsBox.Inbox to listOf(listOf(item("LRCV4")))))
        assertTrue(runCatching { router.client().message(7) }.exceptionOrNull() is RouterException)
        assertTrue(runCatching { router.client().deleteSms(listOf(999_999)) }.exceptionOrNull() is RouterException)
        assertTrue(router.calls.none { it.second.contains("DELETE_SMS") })
    }

    @Test
    fun sendWaitsForItsOwnStatus() = runTest {
        val router = FakeRouter(emptyMap(), statuses = listOf(status("6", "3"), status("4", "1"), status("4", "3")))
        var pauses = 0
        router.client(pause = { pauses++ }).sendSms("0900000000, 0911000000", "Hi")
        val send = router.calls.first { it.second.contains("SEND_SMS") }.second
        assertTrue(send.contains("<contacts>0900000000,0911000000</contacts>"))
        assertTrue(send.contains("<sms_time>26,11,25,9,4,7,%2B3</sms_time>"))
        assertEquals(2, pauses)
    }

    @Test
    fun failuresAndTimeoutsAreErrors() = runTest {
        val failing = FakeRouter(emptyMap(), statuses = listOf(status("4", "2")))
        assertTrue(runCatching { failing.client().sendSms("1", "Hi") }.exceptionOrNull() is RouterException)

        val slow = FakeRouter(emptyMap(), statuses = listOf(status("4", "1")))
        assertTrue(runCatching { slow.client().sendSms("1", "Hi") }.exceptionOrNull() is RouterException)
        assertEquals(AsrSmsClient.SEND_POLLS, slow.calls.count { it.first == "get" })

        val empty = FakeRouter(emptyMap())
        assertTrue(runCatching { empty.client().sendSms(" , ", "Hi") }.exceptionOrNull() is RouterException)
        assertTrue(runCatching { empty.client().sendSms("1", "a".repeat(766)) }.exceptionOrNull() is RouterException)
        assertTrue(empty.calls.isEmpty())
    }

    @Test
    fun markAllOnlyTouchesUnread() = runTest {
        val router = FakeRouter(
            mapOf(SmsBox.Inbox to listOf(listOf(item("LRCV1", status = "0"), item("LRCV2", status = "1")))),
            statuses = listOf(status("7", "3")),
        )
        router.client().markAllSms()
        val marks = router.calls.filter { it.second.contains("SET_MSG_READ") }
        assertEquals(1, marks.size)
        assertTrue(marks[0].second.contains("<read_message_id>LRCV1</read_message_id>"))
        assertNull(FakeRouter(mapOf(SmsBox.Inbox to listOf(listOf(item("LRCV2", status = "1"))))).client().markAllSms())
    }

    /**
     * Answers like the router: a list `set` picks the folder and page the next `get` shows; after
     * any other `set`, `get`s walk through [statuses] (the last one repeats).
     */
    private class FakeRouter(
        private val folders: Map<SmsBox, List<List<String>>>,
        private val statuses: List<String> = emptyList(),
    ) {
        val calls = mutableListOf<Pair<String, String>>()
        private var listing: Pair<SmsBox, Int>? = null
        private var nextStatus = 0

        suspend fun get(file: String): XmlNode {
            calls += "get" to file
            val (box, page) = listing ?: return XmlNode.parse(statuses.getOrElse(nextStatus++) { statuses.lastOrNull() ?: "<RGW/>" })
            val pages = folders[box].orEmpty()
            val items = pages.getOrNull(page - 1).orEmpty().joinToString("")
            return XmlNode.parse("<RGW><message><total_number>${pages.size}</total_number><message_list>$items</message_list></message></RGW>")
        }

        suspend fun set(file: String, fields: List<Pair<String, String>>): XmlNode {
            val xml = buildRgwXml(fields)
            calls += "set" to xml
            val flag = fields.first { it.first.endsWith("message_flag") }.second
            listing = SmsBox.entries.firstOrNull { it.listFlag == flag }?.let { it to fields.first { f -> f.first.endsWith("page_number") }.second.toInt() }
            return XmlNode.parse("<RGW/>")
        }

        fun client(pause: suspend (Long) -> Unit = {}) = AsrSmsClient(
            get = ::get,
            set = ::set,
            pause = pause,
            clock = { ZonedDateTime.of(2026, 11, 25, 9, 4, 7, 0, ZoneOffset.ofHours(3)) },
        )
    }

    companion object {
        /** Packs [name] as GSM 7-bit, then writes it the way the firmware shows it, as "digits". */
        fun mangle(name: String, lead: Int? = null): String {
            val bits = mutableListOf<Int>()
            lead?.let { for (j in 0 until 4) bits += (it shr j) and 1 }
            for (c in name) for (j in 0 until 7) bits += (c.code shr j) and 1
            while (bits.size % 8 != 0) bits += 0
            return bits.chunked(4).joinToString("") { nibble -> ('0' + nibble.withIndex().sumOf { (j, b) -> b shl j }).toString() }
        }

        fun item(index: String, from: String = ";0900000000", text: String = "x", time: String = "26,1,1,0,0,0,+3", status: String = "1") =
            "<Item index=\"0\"><index>$index</index><from>${ucs2Encode(from)}</from><subject>${ucs2Encode(text)}</subject>" +
                "<received>$time</received><status>$status</status><message_type>0</message_type></Item>"

        fun status(cmd: String, result: String) =
            "<RGW><message><flag><message_flag>x</message_flag><sms_cmd>$cmd</sms_cmd><sms_cmd_status_result>$result</sms_cmd_status_result></flag></message></RGW>"

        val INBOX_REPLY = "<?xml version=\"1.0\" encoding=\"US-ASCII\"?><RGW><message>" +
            "<flag><message_flag>GET_RCV_SMS_LOCAL</message_flag><sms_cmd>0</sms_cmd><sms_cmd_status_result>3</sms_cmd_status_result></flag>" +
            "<sms_nv_total>200</sms_nv_total><sms_sim_total>30</sms_sim_total><sms_sim_num>0</sms_sim_num>" +
            "<sms_nv_rev_total>100</sms_nv_rev_total><sms_nv_rev_num>13</sms_nv_rev_num>" +
            "<sms_nv_send_total>50</sms_nv_send_total><sms_nv_send_num>2</sms_nv_send_num>" +
            "<sms_setting><sms_center>+251000000000</sms_center><status_report>0</status_report><save_location>1</save_location><save_time>255</save_time></sms_setting>" +
            "<total_number>2</total_number><message_list>" +
            item("LRCV12", ";+251900000001", "Hello\r\nthere", "26,11,25,9,4,7,+3", "0") +
            item("LRCV11", "Mom;0900000002", "Call me", "26,11,24,18,30,0,+3", "1") +
            item("LRCV10", mangle("Safaricom"), "Your balance is 10 Br.", "26,11,24,8,0,0,+3", "1") +
            "</message_list></message></RGW>"
    }
}
