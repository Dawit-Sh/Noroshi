package et.frectonz.kimem

import et.frectonz.kimem.core.codec.XmlNode
import et.frectonz.kimem.core.model.AsrDevices
import et.frectonz.kimem.core.model.AsrStatus
import et.frectonz.kimem.core.model.AsrSysMode
import et.frectonz.kimem.core.model.Report
import et.frectonz.kimem.core.model.plain
import et.frectonz.kimem.core.model.res
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Real answers of a Stellar 8400 (reports itself as CPE210V3_EU, firmware CPE210V3_EU-V5.3.1,
 * read 2026-11-25) with the IMEI, ICCID, number, MACs, Wi-Fi name, device names and carrier
 * addresses replaced. Fixtures in src/test/resources/stellar.
 */
class StellarStatusTest {
    private fun fixture(name: String): XmlNode =
        XmlNode.parse(javaClass.getResourceAsStream("/stellar/$name")!!.bufferedReader().readText())

    private val status = AsrStatus.parse(fixture("status1.xml"))

    @Test
    fun identity() {
        assertEquals("LV01", status.modelName)
        assertEquals("CPE210V3_EU", status.hardwareVersion)
        assertEquals("CPE210V3_EU-V5.3.1", status.firmware)
        assertEquals("350000000000001", status.imei)
        assertEquals("89251000000000000001", status.iccid)
        assertEquals("+251700000001", status.msisdn)
    }

    @Test
    fun lteSignal() {
        assertEquals(AsrSysMode.Lte, status.mode)
        assertEquals(41, status.rssi)
        assertEquals(-99, status.signalDbm)
        // The dashboard's LTE steps: 34 <= 41 < 43 is two bars.
        assertEquals(2, status.signalBars)
        assertEquals("SAFARICOM", status.networkName)
        assertEquals("636", status.mcc)
        assertEquals("02", status.mnc)
        assertEquals(false, status.roaming)
        assertTrue(status.simPresent)
        assertEquals("0", status.pinStatus)
    }

    @Test
    fun dataConnection() {
        assertTrue(status.online)
        val pdp = status.activePdp!!
        assertEquals("PDN1", pdp.name)
        assertEquals("10.0.0.8", pdp.ipv4)
        assertEquals("10.0.0.1", pdp.gateway)
        assertEquals("255.0.0.0", pdp.netmask)
        assertEquals(listOf("10.0.0.4", "10.0.0.5"), pdp.dns)
        assertEquals("", pdp.ipv6)
        assertEquals(4604L, pdp.connectedSeconds)
        assertEquals(15_961_605L, pdp.totalSeconds)
        assertEquals(4_713_693_296L, status.rxBytes)
        assertEquals(668_119_505L, status.txBytes)
        assertEquals(2_293_889_908_600L, status.rxBytesAll)
        assertEquals(847_879_419_942L, status.txBytesAll)
    }

    @Test
    fun lanAndWifi() {
        assertEquals("192.168.100.1", status.lanIp)
        assertEquals("255.255.255.0", status.lanMask)
        assertEquals("02:00:00:00:00:02", status.lanMac)
        // run_days is empty on this firmware.
        assertEquals(17L * 3_600 + 14 * 60 + 52, status.uptimeSeconds)
        assertEquals("Home Wi-Fi", status.wifiSsid)
        assertEquals("WPA2-PSK", status.wifiSecurity)
        assertEquals("1", status.wifiEnabled)
        assertEquals("0", status.wifiChannel)
        assertEquals("1", status.dhcpServer)
        assertEquals(5, status.connectedDevices)
        assertEquals(0, status.newSms)
    }

    @Test
    fun apnIsAutomatic() {
        assertTrue(status.apn.auto)
        assertEquals("", status.apn.apn)
        // iptype comes back as 105544712 here, which is none of the panel's 0 / 1 / 2.
        assertEquals("", status.apn.ipType)
    }

    @Test
    fun reportsShowTheValues() {
        val info = status.info().table() as Report.KeyValues
        assertEquals(res(R.string.info_phone_number) to plain("+251700000001"), info.rows[0])
        assertEquals(res(R.string.asr_operator) to res(R.string.plmn_with_operator, "SAFARICOM", "63602"), info.rows[1])
        assertEquals(res(R.string.info_signal) to res(R.string.asr_bars_dbm, 2, -99), info.rows[3])
        assertEquals(res(R.string.info_internet) to res(R.string.ppp_connected), info.rows[4])

        val signal = status.signal().table() as Report.KeyValues
        assertEquals(res(R.string.signal_rsrp) to res(R.string.dbm, "-99"), signal.rows[1])

        val wifi = status.wifi().table() as Report.KeyValues
        assertEquals(res(R.string.wifi_channel) to res(R.string.asr_auto), wifi.rows[3])

        listOf(status.internet(), status.apn(), status.device()).forEach { it.table() }
    }

    @Test
    fun connectedDevices() {
        val devices = AsrDevices.parse(fixture("device_management_all.xml")).devices
        assertEquals(5, devices.size)
        val first = devices[0]
        assertEquals("nas", first.name)
        assertEquals("192.168.100.100", first.ip)
        assertEquals("02:00:00:00:00:11", first.mac)
        assertEquals("LAN", first.connType)
        assertTrue(first.connected)
        // "5 hours,15 mins, 18 secs" on the wire.
        assertEquals("5h 15m 18s", first.connTime)
        assertEquals(listOf("LAN", "WLAN", "WLAN", "WLAN", "WLAN"), devices.map { it.connType })
        assertEquals(5, (AsrDevices(devices).table() as Report.Records).rows.size)
    }
}
