package dev.dawitsh.noroshi.core.model

import androidx.annotation.Keep
import androidx.annotation.StringRes
import dev.dawitsh.noroshi.R

@Keep
enum class ReportKind(@StringRes val title: Int, @StringRes val description: Int, val path: List<String>) {
    Info(R.string.kind_info, R.string.desc_info, listOf("get", "info")),
    System(R.string.kind_system, R.string.desc_system, listOf("get", "system")),
    Signal(R.string.kind_signal, R.string.desc_signal, listOf("get", "signal")),
    Internet(R.string.kind_internet, R.string.desc_internet, listOf("get", "internet")),
    Apn(R.string.kind_apn, R.string.desc_apn, listOf("get", "apn")),
    Device(R.string.kind_device, R.string.desc_device, listOf("get", "device")),
    Wifi(R.string.kind_wifi, R.string.desc_wifi, listOf("get", "wifi")),
    Devices(R.string.kind_devices, R.string.desc_devices, listOf("get", "devices")),
    SmsInfo(R.string.kind_sms_info, R.string.desc_sms_info, listOf("get", "sms", "info")),
    Syslog(R.string.kind_syslog, R.string.desc_syslog, listOf("get", "syslog")),
    Airtime(R.string.kind_airtime, R.string.desc_airtime, listOf("get", "airtime")),
    Power(R.string.kind_power, R.string.desc_power, listOf("get", "power")),
}

/** Things a router can do besides showing reports. */
enum class Feature { Sms, Ussd, Reboot }

/**
 * The router families the app can talk to. Each one lists what it can show, so the
 * menus only offer commands the router answers.
 */
enum class RouterType(
    @StringRes val label: Int,
    @StringRes val subtitle: Int,
    val defaultHost: String,
    val reports: Set<ReportKind>,
    val features: Set<Feature>,
) {
    SafaricomMifi(
        R.string.router_type_mifi,
        R.string.app_subtitle,
        "192.168.0.1",
        ReportKind.entries.toSet(),
        Feature.entries.toSet(),
    ),

    /** Stellar 8400 4G CPE and other ASR / Marvell "Duster" panels. */
    Stellar(
        R.string.router_type_stellar,
        R.string.app_subtitle_stellar,
        "192.168.100.1",
        setOf(
            ReportKind.Info,
            ReportKind.Signal,
            ReportKind.Internet,
            ReportKind.Apn,
            ReportKind.Device,
            ReportKind.Wifi,
            ReportKind.Devices,
            ReportKind.SmsInfo,
        ),
        setOf(Feature.Sms, Feature.Reboot),
    );

    fun has(feature: Feature): Boolean = feature in features

    companion object {
        fun fromName(name: String?): RouterType? = entries.firstOrNull { it.name == name }
    }
}
