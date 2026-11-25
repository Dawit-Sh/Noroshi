package dev.dawitsh.noroshi

import dev.dawitsh.noroshi.core.codec.XmlNode
import dev.dawitsh.noroshi.core.codec.buildRgwXml
import dev.dawitsh.noroshi.core.model.AsrDevices
import dev.dawitsh.noroshi.core.model.AsrStatus
import dev.dawitsh.noroshi.core.model.AsrSysMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AsrXmlTest {
    @Test
    fun parsesAndFindsLikeJquery() {
        val xml = XmlNode.parse("""<?xml version="1.0" encoding="US-ASCII" ?><RGW><a><b><c>1</c></b></a><c>2</c></RGW>""")
        assertEquals("RGW", xml.name)
        assertEquals("1", xml.textOf("c"))
        assertEquals("1", xml.at("a/b/c"))
        assertEquals("", xml.at("a/x"))
        assertEquals(listOf("1", "2"), xml.findAll("c").map { it.text })
    }

    @Test
    fun loggedOutAnswer() {
        val xml = XmlNode.parse("""<?xml version="1.0" encoding="US-ASCII" ?><RGW><login_status>UNAUTHORIZED</login_status></RGW>""")
        assertEquals("UNAUTHORIZED", xml.child("login_status")?.text)
    }

    @Test
    fun buildsSetBodiesLikeThePanel() {
        val body = buildRgwXml(
            listOf(
                "RGW/message/flag/message_flag" to "SEND_SMS",
                "RGW/message/flag/sms_cmd" to "4",
                "RGW/message/send_save_message/contacts" to "+251900000000,",
                "RGW/message/send_save_message/content" to "00480069",
            )
        )
        assertEquals(
            "<?xml version=\"1.0\" encoding=\"US-ASCII\"?> <RGW><message><flag><message_flag>SEND_SMS</message_flag>" +
                "<sms_cmd>4</sms_cmd></flag><send_save_message><contacts>+251900000000,</contacts>" +
                "<content>00480069</content></send_save_message></message></RGW>",
            body,
        )
        assertEquals("<?xml version=\"1.0\" encoding=\"US-ASCII\"?> <RGW><a>&lt;&amp;&gt;</a></RGW>", buildRgwXml(listOf("RGW/a" to "<&>")))
    }

    /** Shaped after what js/panel/dashboard.js reads; the router's own answer is in StellarStatusTest. */
    private val wcdmaStatus = """
        <RGW>
          <sysinfo><device_name>MIFI</device_name><hardware_version>HW1</hardware_version><version_num>V1</version_num></sysinfo>
          <wan>
            <sys_mode>5</sys_mode><network_name>Safaricom</network_name><roaming>0</roaming>
            <cellular><rssi>30</rssi><sim_status>0</sim_status><pin_status>1</pin_status></cellular>
            <proto>cellular</proto><connect_disconnect>cellular</connect_disconnect><wan_link_status>Connected</wan_link_status>
            <pdp_context_list>
              <Item><rulename>first</rulename><success>0</success><ipv4></ipv4></Item>
              <Item><rulename>second</rulename><success>1</success><ipv4>10.0.0.2</ipv4><v4dns1>8.8.8.8</v4dns1><v4dns2>NA</v4dns2><curconntime>3725</curconntime></Item>
            </pdp_context_list>
          </wan>
          <lan><ip>192.168.100.1</ip><run_days>1</run_days><run_hours>2</run_hours><run_minutes>3</run_minutes><run_seconds>4</run_seconds></lan>
          <wlan_security><mode>Mixed</mode><ssid>0053007400650066</ssid></wlan_security>
          <device_management><nr_connected_dev>3</nr_connected_dev></device_management>
          <message><new_sms_num>2</new_sms_num></message>
        </RGW>
    """.trimIndent()

    @Test
    fun wcdmaSignalIsCsq() {
        val s = AsrStatus.parse(XmlNode.parse(wcdmaStatus))
        assertEquals(AsrSysMode.Wcdma, s.mode)
        assertEquals(30 * 2 - 113, s.signalDbm)
        assertEquals(3, s.signalBars)
    }

    @Test
    fun picksTheConnectedPdpAndDecodes() {
        val s = AsrStatus.parse(XmlNode.parse(wcdmaStatus))
        assertEquals("second", s.activePdp?.name)
        assertEquals(listOf("8.8.8.8"), s.activePdp?.dns)
        assertEquals(3725L, s.activePdp?.connectedSeconds)
        assertTrue(s.online)
        assertEquals("Stef", s.wifiSsid)
        assertEquals("WPA/WPA2", s.wifiSecurity)
        assertEquals(86_400L + 2 * 3_600 + 3 * 60 + 4, s.uptimeSeconds)
        assertEquals(3, s.connectedDevices)
        assertEquals(2, s.newSms)
        assertEquals("1", s.pinStatus)
    }

    @Test
    fun missingFieldsStayEmpty() {
        val s = AsrStatus.parse(XmlNode.parse("<RGW><wan><sys_mode>0</sys_mode></wan></RGW>"))
        assertNull(s.mode)
        assertNull(s.rssi)
        assertNull(s.signalDbm)
        assertNull(s.activePdp)
        assertNull(s.uptimeSeconds)
        assertFalse(s.online)
        // Every report still builds.
        listOf(s.info(), s.signal(), s.internet(), s.apn(), s.device(), s.wifi()).forEach { it.table() }
    }

    @Test
    fun connectionTime() {
        assertEquals("1h 2m 3s", AsrDevices.fmtConnTime("1,2,3"))
        assertEquals("38m 50s", AsrDevices.fmtConnTime("0 hours,38 mins, 50 secs"))
        assertEquals("odd", AsrDevices.fmtConnTime("odd"))
    }
}
