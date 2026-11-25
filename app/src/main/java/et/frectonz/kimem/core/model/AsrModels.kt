package et.frectonz.kimem.core.model

import et.frectonz.kimem.R
import et.frectonz.kimem.core.codec.XmlNode
import et.frectonz.kimem.core.codec.fmtSeconds
import et.frectonz.kimem.core.codec.humanSize
import et.frectonz.kimem.core.codec.ucs2Decode
import java.net.URLDecoder

/** `wan/sys_mode` of an ASR panel. */
enum class AsrSysMode(val code: Int, val label: String) {
    Gsm(3, "GSM"), Wcdma(5, "WCDMA"), TdScdma(15, "TD-SCDMA"), Lte(17, "LTE");

    companion object {
        fun parse(raw: String): AsrSysMode? = raw.trim().toIntOrNull()?.let { c -> entries.firstOrNull { it.code == c } }
    }
}

/** One PDP context (`wan/pdp_context_list/Item`): the data connection and its addresses. */
data class AsrPdp(
    val name: String,
    val state: String,
    val ipv4: String,
    val gateway: String,
    val netmask: String,
    val dns: List<String>,
    val ipv6: String,
    val globalIpv6: String,
    val connectedSeconds: Long?,
    val totalSeconds: Long?,
) {
    val connected: Boolean get() = state == "1"

    val stateLabel: UiText
        get() = when (state) {
            "1" -> res(R.string.ppp_connected)
            "2" -> res(R.string.asr_connecting)
            "0" -> res(R.string.ppp_disconnected)
            else -> orDash(state)
        }
}

/**
 * The panel's dashboard file, `file=status1`, read the way `js/panel/dashboard.js` reads it.
 * Every field is optional on the router's side, so missing ones come back empty.
 */
data class AsrStatus(
    val deviceName: String,
    val modelName: String,
    val hardwareVersion: String,
    val firmware: String,
    val sysMode: String,
    val sysSubmode: String,
    val networkName: String,
    val roaming: Boolean,
    val roamingNetworkName: String,
    val rssi: Int?,
    val simStatus: String,
    val pinStatus: String,
    val wanLinkStatus: String,
    val wanConnStatus: String,
    val connType: String,
    val proto: String,
    val connectDisconnect: String,
    val imei: String,
    val iccid: String,
    val msisdn: String,
    val mcc: String,
    val mnc: String,
    val apn: AsrApn,
    val pdp: List<AsrPdp>,
    val rxBytes: Long?,
    val txBytes: Long?,
    val rxBytesAll: Long?,
    val txBytesAll: Long?,
    val lanIp: String,
    val lanMask: String,
    val lanMac: String,
    val uptimeSeconds: Long?,
    val wifiSsid: String,
    val wifiSecurity: String,
    val wifiEnabled: String,
    val wifiChannel: String,
    val dhcpServer: String,
    val connectedDevices: Int?,
    val newSms: Int?,
) {
    val mode: AsrSysMode? get() = AsrSysMode.parse(sysMode)

    /** The data connection: the PDP that is up, else the first one. */
    val activePdp: AsrPdp? get() = pdp.firstOrNull { it.connected } ?: pdp.firstOrNull()

    val simPresent: Boolean get() = simStatus == "0"

    /**
     * `cellular/rssi` turned into dBm the way the panel family does it: on LTE it is the RSRP
     * index (dBm = index - 140), on GSM / WCDMA a CSQ (dBm = 2 * CSQ - 113).
     */
    val signalDbm: Int?
        get() {
            val value = rssi ?: return null
            return when (mode) {
                AsrSysMode.Lte -> value - 140
                AsrSysMode.Gsm, AsrSysMode.Wcdma, AsrSysMode.TdScdma -> value * 2 - 113
                null -> null
            }
        }

    /** Signal bars, 0 to 4, with the dashboard's own thresholds. */
    val signalBars: Int?
        get() {
            val r = rssi ?: return null
            val steps = when (mode) {
                AsrSysMode.Lte -> intArrayOf(25, 34, 43, 55)
                AsrSysMode.Gsm -> intArrayOf(7, 14, 19, 24)
                AsrSysMode.Wcdma, AsrSysMode.TdScdma -> intArrayOf(17, 22, 27, 31)
                null -> return 0
            }
            return steps.count { r >= it }
        }

    /** Whether the cellular data link is up, from the same fields the dashboard looks at. */
    val online: Boolean
        get() {
            if (proto == "disabled" || connectDisconnect == "disabled" || wanLinkStatus.equals("disconnected", true)) return false
            return activePdp?.connected ?: wanConnStatus.equals("connected", true)
        }

    private fun simLabel(): UiText = when (simStatus) {
        "0" -> when (pinStatus) {
            "", "0" -> res(R.string.asr_sim_ready)
            "1" -> res(R.string.asr_sim_pin)
            "2" -> res(R.string.asr_sim_puk)
            "3" -> res(R.string.asr_sim_locked)
            else -> res(R.string.asr_sim_error)
        }

        "1" -> res(R.string.asr_sim_absent)
        "" -> res(R.string.dash)
        else -> res(R.string.asr_sim_error)
    }

    private fun networkLabel(): UiText {
        val m = mode ?: return res(R.string.asr_no_service)
        return plain(m.label)
    }

    private fun operatorLabel(): UiText {
        val name = if (roaming && roamingNetworkName.isNotEmpty()) roamingNetworkName else networkName
        val plmn = if (mcc.isNotEmpty() && mnc.isNotEmpty()) "$mcc$mnc" else ""
        return when {
            name.isEmpty() -> orDash(plmn)
            plmn.isEmpty() -> plain(name)
            else -> res(R.string.plmn_with_operator, name, plmn)
        }
    }

    private fun signalLabel(): UiText {
        val bars = signalBars ?: return res(R.string.dash)
        val dbm = signalDbm ?: return res(R.string.signal_bars_4, bars)
        return res(R.string.asr_bars_dbm, bars, dbm)
    }

    private fun internetLabel(): UiText = res(if (online) R.string.ppp_connected else R.string.ppp_disconnected)

    fun info(): Show = show(
        R.string.kind_info,
        R.string.info_phone_number to orDash(msisdn),
        R.string.asr_operator to operatorLabel(),
        R.string.info_network_type to networkLabel(),
        R.string.info_signal to signalLabel(),
        R.string.info_internet to internetLabel(),
        R.string.asr_sim to simLabel(),
        R.string.info_unread_sms to (newSms?.let { plain(it.toString()) } ?: res(R.string.dash)),
        R.string.info_connected_devices to (connectedDevices?.let { plain(it.toString()) } ?: res(R.string.dash)),
    )

    fun signal(): Show = show(
        R.string.kind_signal,
        R.string.asr_mode to networkLabel(),
        (if (mode == AsrSysMode.Lte) R.string.signal_rsrp else R.string.signal_rssi) to fmtDbm(signalDbm?.toDouble()),
        R.string.asr_signal_index to (rssi?.let { plain(it.toString()) } ?: res(R.string.dash)),
        R.string.asr_bars to (signalBars?.let { res(R.string.signal_bars_4, it) } ?: res(R.string.dash)),
        R.string.asr_operator to operatorLabel(),
        R.string.asr_roaming to yesNo(roaming),
    )

    fun internet(): Show {
        val p = activePdp
        return show(
            R.string.kind_internet,
            R.string.internet_status to internetLabel(),
            R.string.asr_connection to orDash(listOf(proto, connType).firstOrNull { it.isNotEmpty() } ?: ""),
            R.string.asr_pdp to (p?.let { res(R.string.plmn_with_operator, it.name.ifEmpty { "—" }, it.stateLabel) } ?: res(R.string.dash)),
            R.string.internet_wan_ip to orDash(p?.ipv4 ?: ""),
            R.string.internet_gateway to orDash(p?.gateway ?: ""),
            R.string.internet_netmask to orDash(p?.netmask ?: ""),
            R.string.internet_dns to fmtDns(p?.dns ?: emptyList()),
            R.string.asr_ipv6 to orDash(p?.globalIpv6?.ifEmpty { p.ipv6 } ?: ""),
            R.string.asr_connected_for to (p?.connectedSeconds?.let { plain(fmtSeconds(it)) } ?: res(R.string.dash)),
            R.string.asr_total_connected to (p?.totalSeconds?.let { plain(fmtSeconds(it)) } ?: res(R.string.dash)),
            R.string.asr_received to bytes(rxBytes),
            R.string.asr_sent to bytes(txBytes),
            R.string.asr_received_all to bytes(rxBytesAll),
            R.string.asr_sent_all to bytes(txBytesAll),
        )
    }

    fun apn(): Show = show(
        R.string.kind_apn,
        R.string.asr_auto_apn to yesNo(apn.auto),
        R.string.apn_apn to orDash(apn.apn),
        R.string.asr_lte_apn to orDash(apn.lteApn),
        R.string.apn_pdp_type to orDash(apn.ipType),
        R.string.apn_auth_mode to orDash(apn.authType),
        R.string.apn_username to orDash(apn.username),
        R.string.apn_password to orDash(apn.password),
    )

    fun device(): Show = show(
        R.string.kind_device,
        R.string.asr_model to orDash(modelName.ifEmpty { deviceName }),
        R.string.asr_hardware to orDash(hardwareVersion),
        R.string.device_firmware to orDash(firmware),
        R.string.device_imei to orDash(imei),
        R.string.device_iccid to orDash(iccid),
        R.string.asr_lan_ip to orDash(lanIp),
        R.string.internet_netmask to orDash(lanMask),
        R.string.asr_lan_mac to orDash(lanMac),
        R.string.device_uptime to (uptimeSeconds?.let { plain(fmtSeconds(it)) } ?: res(R.string.dash)),
    )

    fun wifi(): Show = show(
        R.string.kind_wifi,
        R.string.wifi_ssid to orDash(wifiSsid),
        R.string.asr_wifi_on to flagLabel(wifiEnabled),
        R.string.asr_security to orDash(wifiSecurity),
        R.string.wifi_channel to (if (wifiChannel == "0") res(R.string.asr_auto) else orDash(wifiChannel)),
        R.string.asr_dhcp_server to flagLabel(dhcpServer),
    )

    companion object {
        fun parse(root: XmlNode): AsrStatus {
            val sysinfo = root.find("sysinfo")
            val wan = root.find("wan")
            val cellular = wan?.find("cellular") ?: root.find("cellular")
            val lan = root.find("lan")
            val wlanSecurity = root.find("wlan_security")
            val wlanSettings = root.find("wlan_settings")
            val stats = root.find("WanStatistics")

            fun XmlNode?.t(name: String): String = this?.textOf(name) ?: ""

            val uptime = listOf("run_days", "run_hours", "run_minutes", "run_seconds").map { lan.t(it).toLongOrNull() }
            val uptimeSeconds = if (uptime.all { it == null }) null
            else uptime.zip(listOf(86_400L, 3_600L, 60L, 1L)).sumOf { (v, unit) -> (v ?: 0L) * unit }

            return AsrStatus(
                deviceName = sysinfo.t("device_name"),
                modelName = sysinfo.t("model_name"),
                hardwareVersion = sysinfo.t("hardware_version"),
                firmware = sysinfo.t("version_num"),
                sysMode = wan.t("sys_mode"),
                sysSubmode = wan.t("sys_submode"),
                networkName = wan.t("network_name"),
                roaming = wan.t("roaming") == "1",
                roamingNetworkName = wan.t("roaming_network_name"),
                rssi = cellular.t("rssi").trim().toIntOrNull(),
                simStatus = cellular.t("sim_status"),
                pinStatus = cellular.t("pin_status"),
                wanLinkStatus = wan.t("wan_link_status"),
                wanConnStatus = wan.t("wan_conn_status"),
                connType = wan.t("ConnType"),
                proto = wan.t("proto"),
                connectDisconnect = wan.t("connect_disconnect"),
                imei = wan.t("IMEI"),
                iccid = wan.t("ICCID"),
                msisdn = wan.t("MSISDN"),
                mcc = wan.t("mmc").ifEmpty { wan.t("mcc") },
                mnc = wan.t("mnc"),
                apn = AsrApn(
                    auto = wan.t("auto_apn") == "1",
                    apn = wan.t("apn"),
                    lteApn = wan.t("lte_apn"),
                    ipType = when (wan.t("iptype")) {
                        "0" -> "IPv4v6"
                        "1" -> "IPv4"
                        "2" -> "IPv6"
                        else -> ""
                    },
                    authType = wan.t("authtype4g").ifEmpty { wan.t("authtype2g3g") },
                    username = wan.t("username4g").ifEmpty { wan.t("username2g3g") },
                    password = wan.t("password4g").ifEmpty { wan.t("password2g3g") },
                ),
                pdp = (wan?.find("pdp_context_list")?.children ?: emptyList()).filter { it.name == "Item" }.map { item ->
                    AsrPdp(
                        name = item.at("rulename"),
                        state = item.at("success"),
                        ipv4 = item.at("ipv4"),
                        gateway = item.at("v4gateway"),
                        netmask = item.at("v4netmask"),
                        dns = listOf(item.at("v4dns1"), item.at("v4dns2")).filter { it.isNotEmpty() && it != "NA" },
                        ipv6 = item.at("ipv6").takeIf { it != "NA" } ?: "",
                        globalIpv6 = item.at("g_ipv6").takeIf { it != "NA" } ?: "",
                        connectedSeconds = item.at("curconntime").toLongOrNull()?.takeIf { it >= 0 },
                        totalSeconds = item.at("totalconntime").toLongOrNull()?.takeIf { it >= 0 },
                    )
                },
                rxBytes = stats.t("rx_byte").toLongOrNull(),
                txBytes = stats.t("tx_byte").toLongOrNull(),
                rxBytesAll = stats.t("rx_byte_all").toLongOrNull(),
                txBytesAll = stats.t("tx_byte_all").toLongOrNull(),
                lanIp = lan?.at("ip") ?: "",
                lanMask = lan?.at("mask") ?: "",
                lanMac = lan?.at("mac") ?: "",
                uptimeSeconds = uptimeSeconds,
                wifiSsid = decodeUni(wlanSecurity?.at("ssid") ?: ""),
                wifiSecurity = wlanSecurity?.at("mode")?.let { if (it == "Mixed") "WPA/WPA2" else it } ?: "",
                wifiEnabled = wlanSettings?.at("wlan_enable") ?: "",
                wifiChannel = wlanSettings?.at("channel") ?: "",
                dhcpServer = root.find("dhcp")?.at("status") ?: "",
                connectedDevices = root.find("device_management")?.at("nr_connected_dev")?.toIntOrNull(),
                newSms = root.find("message")?.at("new_sms_num")?.toIntOrNull(),
            )
        }
    }
}

data class AsrApn(
    val auto: Boolean,
    val apn: String,
    val lteApn: String,
    val ipType: String,
    val authType: String,
    val username: String,
    val password: String,
)

/** One row of `file=device_management_all` (the panel's Connected Devices page). */
data class AsrDevice(
    val name: String,
    val ip: String,
    val mac: String,
    val connType: String,
    val connected: Boolean,
    val connTime: String,
    val blocked: Boolean,
)

class AsrDevices(val devices: List<AsrDevice>) : Show {
    override fun table() = Report.Records(
        res(R.string.kind_devices),
        listOf(
            res(R.string.devices_hostname),
            res(R.string.devices_ip),
            res(R.string.devices_mac),
            res(R.string.devices_type),
            res(R.string.asr_online),
            res(R.string.asr_connected_for),
        ),
        devices.map {
            listOf(
                orDash(it.name),
                orDash(it.ip),
                plain(it.mac),
                orDash(it.connType),
                yesNo(it.connected),
                orDash(it.connTime),
            )
        },
    )

    companion object {
        fun parse(root: XmlNode): AsrDevices = AsrDevices(
            root.findAll("Item").filter { it.child("mac") != null }.map { item ->
                val name = decodeUri(item.at("name"))
                AsrDevice(
                    name = if (name == "unkown_marvell") "" else name,
                    ip = item.at("ip_address"),
                    mac = item.at("mac"),
                    connType = item.at("conn_type"),
                    connected = item.at("connected") == "1",
                    connTime = fmtConnTime(item.at("conn_time")),
                    blocked = item.at("blocked") == "1",
                )
            }
        )

        /**
         * `conn_time` is "hours,minutes,seconds"; this firmware writes "5 hours,15 mins, 18 secs".
         * The panel takes the leading number of each part (parseInt), and so does this.
         */
        fun fmtConnTime(raw: String): String {
            val parts = raw.split(',').map { Regex("""^\s*(\d+)""").find(it)?.groupValues?.get(1)?.toLongOrNull() }
            if (parts.size != 3 || parts.any { it == null }) return raw
            return fmtSeconds(parts[0]!! * 3_600 + parts[1]!! * 60 + parts[2]!!)
        }
    }
}

private fun show(title: Int, vararg pairs: Pair<Int, UiText>): Show = object : Show {
    override fun table() = Report.KeyValues(res(title), rows(*pairs))
}

private fun bytes(value: Long?): UiText = value?.let { plain(humanSize(it)) } ?: res(R.string.dash)

private fun flagLabel(raw: String): UiText = when (raw) {
    "1" -> res(R.string.enabled)
    "0" -> res(R.string.disabled)
    else -> orDash(raw)
}

/** The panel's `UniDecode`: 4 hex digits per character. Plain text is returned as it is. */
internal fun decodeUni(raw: String): String {
    val t = raw.trim()
    if (t.isEmpty() || t.length % 4 != 0 || !t.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return raw
    return try {
        ucs2Decode(t)
    } catch (_: IllegalArgumentException) {
        raw
    }
}

/** The panel's `decodeURIComponent` on device names. */
internal fun decodeUri(raw: String): String = try {
    URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8")
} catch (_: IllegalArgumentException) {
    raw
}
