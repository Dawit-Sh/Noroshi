package dev.dawitsh.noroshi.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.dawitsh.noroshi.R
import dev.dawitsh.noroshi.core.data.DataReport
import dev.dawitsh.noroshi.core.data.DataUsageService
import dev.dawitsh.noroshi.core.data.NoticeCenter
import dev.dawitsh.noroshi.core.data.UsageRepository
import dev.dawitsh.noroshi.core.model.AlertSettings
import dev.dawitsh.noroshi.core.model.AsrDeviceUsage
import dev.dawitsh.noroshi.core.model.BundleUsage
import dev.dawitsh.noroshi.core.model.CountMode
import dev.dawitsh.noroshi.core.model.dataSize
import dev.dawitsh.noroshi.core.model.gbToBytes
import dev.dawitsh.noroshi.core.model.res
import dev.dawitsh.noroshi.core.work.UsageNotifier
import dev.dawitsh.noroshi.di.container
import dev.dawitsh.noroshi.ui.Load
import dev.dawitsh.noroshi.ui.Route
import dev.dawitsh.noroshi.ui.components.ErrorBox
import dev.dawitsh.noroshi.ui.components.KeyValueTable
import dev.dawitsh.noroshi.ui.components.LoadingBox
import dev.dawitsh.noroshi.ui.components.MonoBox
import dev.dawitsh.noroshi.ui.components.MonoButton
import dev.dawitsh.noroshi.ui.components.MonoIconButton
import dev.dawitsh.noroshi.ui.components.MonoSegmented
import dev.dawitsh.noroshi.ui.components.MonoTag
import dev.dawitsh.noroshi.ui.components.MonoTextField
import dev.dawitsh.noroshi.ui.components.MonoTopBar
import dev.dawitsh.noroshi.ui.components.SectionHeader
import dev.dawitsh.noroshi.ui.components.ink
import dev.dawitsh.noroshi.ui.components.paper
import dev.dawitsh.noroshi.ui.leafCrumbs
import dev.dawitsh.noroshi.ui.load
import dev.dawitsh.noroshi.ui.string
import dev.dawitsh.noroshi.ui.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.floor

class DataViewModel(
    private val service: DataUsageService,
    private val usage: UsageRepository,
    private val notices: NoticeCenter,
) : ViewModel() {
    private val state = MutableStateFlow<Load<DataReport>>(Load.Loading)
    val report: StateFlow<Load<DataReport>> = state.asStateFlow()

    private val correctingState = MutableStateFlow(false)
    val correcting: StateFlow<Boolean> = correctingState.asStateFlow()

    val alerts: StateFlow<AlertSettings> = usage.alertSettings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlertSettings())

    init {
        load()
        // A bundle written from the Set bundle screen shows here at once; the device list stays.
        viewModelScope.launch { service.changes.collect { show(it) } }
    }

    private fun show(fresh: DataReport) {
        val devices = (state.value as? Load.Ready)?.value?.devices
        state.value = Load.Ready(if (fresh.devices == null) fresh.copy(devices = devices) else fresh)
    }

    fun load() {
        viewModelScope.launch {
            state.value = Load.Loading
            state.value = load { service.read(withDevices = true) }
        }
    }

    fun correct(used: Long) {
        if (correctingState.value) return
        correctingState.value = true
        viewModelScope.launch {
            try {
                service.correctUsed(used)
                notices.post(res(R.string.data_corrected))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.post(e.uiText())
            } finally {
                correctingState.value = false
            }
        }
    }

    fun setAlerts(enabled: Boolean) {
        viewModelScope.launch { usage.saveAlertSettings(alerts.value.copy(enabled = enabled)) }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { DataViewModel(container.dataUsage, container.usageRepository, container.notices) }
        }
    }
}

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)
private val MONTH: DateTimeFormatter = DateTimeFormatter.ofPattern("MMM yyyy", Locale.US)
private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.US)

internal fun LocalDate.show(): String = format(DAY)
internal fun LocalTime.show(): String = format(TIME)

@Composable
fun DataScreen(
    onNavigate: (Route) -> Unit,
    onCrumb: (Route) -> Unit,
    onBack: () -> Unit,
    vm: DataViewModel = viewModel(factory = DataViewModel.Factory),
) {
    val state by vm.report.collectAsStateWithLifecycle()
    val alerts by vm.alerts.collectAsStateWithLifecycle()
    val correcting by vm.correcting.collectAsStateWithLifecycle()
    var showCorrect by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        containerColor = paper(),
        topBar = {
            MonoTopBar(leafCrumbs(listOf("get", "data")), onCrumb = onCrumb, onBack = onBack) {
                MonoIconButton(Icons.Filled.Refresh, stringResource(R.string.refresh), onClick = vm::load, enabled = state !is Load.Loading)
            }
        },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            when (val current = state) {
                Load.Loading -> LoadingBox(stringResource(R.string.fetching, stringResource(R.string.data_title)))
                is Load.Error -> ErrorBox(current.message.string(), onRetry = vm::load)
                is Load.Ready -> DataView(
                    current.value,
                    alerts,
                    correcting = correcting,
                    onSetBundle = { onNavigate(Route.DataBundle) },
                    onCorrect = { showCorrect = true },
                    onAlerts = vm::setAlerts,
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    val bundle = (state as? Load.Ready)?.value?.bundle
    if (showCorrect && bundle != null) {
        CorrectDialog(
            bundle,
            onConfirm = { used ->
                showCorrect = false
                vm.correct(used)
            },
            onDismiss = { showCorrect = false },
        )
    }
}

@Composable
private fun DataView(
    report: DataReport,
    alerts: AlertSettings,
    correcting: Boolean,
    onSetBundle: () -> Unit,
    onCorrect: () -> Unit,
    onAlerts: (Boolean) -> Unit,
) {
    val today = report.readAt.toLocalDate()
    val traffic = report.traffic
    val bundle = report.bundle

    if (bundle != null) {
        BundleBox(bundle, traffic.mode)
        Spacer(Modifier.height(12.dp))
        KeyValueTable(stringResource(R.string.data_bundle), bundleRows(bundle, traffic.cutOff, today))
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MonoButton(
                stringResource(if (correcting) R.string.data_writing else R.string.data_correct),
                onCorrect,
                Modifier.weight(1f),
                enabled = !correcting,
            )
            MonoButton(stringResource(R.string.data_change_bundle), onSetBundle, Modifier.weight(1f))
        }
    } else {
        MonoBox(Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.data_no_bundle).uppercase(), style = MaterialTheme.typography.labelLarge, color = ink())
            Spacer(Modifier.height(8.dp))
            Text(
                stringResource(R.string.data_no_bundle_body, traffic.usedMonth?.let(::dataSize) ?: stringResource(R.string.dash)),
                style = MaterialTheme.typography.bodyMedium,
                color = ink(),
            )
            Spacer(Modifier.height(12.dp))
            MonoButton(stringResource(R.string.data_set_bundle), onSetBundle, Modifier.fillMaxWidth(), primary = true)
        }
    }

    SectionHeader(stringResource(R.string.data_alerts))
    AlertsBox(alerts, onAlerts)

    SectionHeader(stringResource(R.string.data_usage))
    val dash = stringResource(R.string.dash)
    val todayText = report.today?.let { used ->
        val from = report.todayFrom
        if (from != null && from.partial) stringResource(R.string.data_today_since, dataSize(used), from.takenAt.show())
        else dataSize(used)
    } ?: dash
    KeyValueTable(
        stringResource(R.string.data_usage),
        listOfNotNull(
            stringResource(R.string.data_today) to todayText,
            stringResource(R.string.data_this_month) to (traffic.usedMonth?.let(::dataSize) ?: dash),
            bundle?.let { stringResource(R.string.data_in_bundle) to dataSize(it.used) },
            stringResource(R.string.data_all_time) to (traffic.lifetime?.let(::dataSize) ?: dash),
            stringResource(R.string.data_read_at) to report.readAt.toLocalTime().show(),
        ),
    )

    if (traffic.history.isNotEmpty()) {
        Spacer(Modifier.height(12.dp))
        KeyValueTable(
            stringResource(R.string.data_months),
            traffic.history.sortedWith(compareByDescending<dev.dawitsh.noroshi.core.model.MonthUsage> { it.year }.thenByDescending { it.month })
                .map { YearMonth.of(it.year, it.month).format(MONTH) to dataSize(it.bytes) },
        )
    }

    val devices = report.devices
    if (devices != null) {
        SectionHeader(stringResource(R.string.data_devices))
        val shown = AsrDeviceUsage.shown(devices)
        if (shown.isEmpty()) {
            MonoBox(Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.data_no_devices), style = MaterialTheme.typography.bodyMedium, color = ink())
            }
        } else {
            KeyValueTable(
                stringResource(R.string.data_devices),
                shown.map { d ->
                    val name = d.name.ifEmpty { d.mac }
                    val label = if (d.connected) stringResource(R.string.data_device_online, name) else name
                    label to stringResource(
                        R.string.data_device_value,
                        d.month?.let(::dataSize) ?: dash,
                        d.last3Days?.let(::dataSize) ?: dash,
                    )
                },
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.data_devices_note), style = MaterialTheme.typography.bodySmall, color = ink())
        }
    }
}

@Composable
private fun BundleBox(bundle: BundleUsage, mode: CountMode?) {
    val percent = floor(bundle.percent).toInt()
    MonoBox(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.data_bundle).uppercase(), style = MaterialTheme.typography.labelLarge, color = ink(), modifier = Modifier.weight(1f))
            val modeLabel = when (mode) {
                CountMode.Month -> R.string.data_mode_month
                CountMode.Unlimited -> R.string.data_mode_unlimited
                else -> R.string.data_mode_period
            }
            MonoTag(stringResource(modeLabel))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$percent%", style = MaterialTheme.typography.displayMedium, color = ink())
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.data_used_word), style = MaterialTheme.typography.bodyMedium, color = ink(), modifier = Modifier.padding(bottom = 8.dp))
        }
        Spacer(Modifier.height(8.dp))
        UsageBar((bundle.percent / 100).toFloat())
        Spacer(Modifier.height(8.dp))
        Text(
            "${dataSize(bundle.used)} / ${dataSize(bundle.size)}",
            style = MaterialTheme.typography.bodySmall,
            color = ink(),
        )
    }
}

@Composable
private fun UsageBar(fraction: Float) {
    val ink = ink()
    Box(
        Modifier
            .fillMaxWidth()
            .height(16.dp)
            .border(1.dp, ink)
            .padding(2.dp)
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .background(ink)
        )
    }
}

@Composable
private fun bundleRows(bundle: BundleUsage, cutOff: Boolean, today: LocalDate): List<Pair<String, String>> {
    val dash = stringResource(R.string.dash)
    val days = bundle.daysLeft(today)
    val ends = when {
        bundle.end == null || days == null -> dash
        days < 0 -> stringResource(R.string.data_ended, bundle.end.show())
        days == 0L -> stringResource(R.string.data_ends_today)
        days == 1L -> stringResource(R.string.data_ends_tomorrow, bundle.end.show())
        else -> stringResource(R.string.data_ends_on, days.toInt(), bundle.end.show())
    }
    val pace = bundle.perDay(today)?.let { perDay ->
        val runsOut = bundle.runsOutOn(today)
        if (runsOut != null && bundle.end != null && runsOut.isBefore(bundle.end)) {
            stringResource(R.string.data_pace_value, dataSize(perDay), runsOut.show())
        } else {
            stringResource(R.string.data_pace_value_short, dataSize(perDay))
        }
    }
    return listOfNotNull(
        stringResource(R.string.data_used) to dataSize(bundle.used),
        stringResource(R.string.data_left) to dataSize(bundle.left),
        (bundle.used - bundle.size).takeIf { it > 0 }?.let { stringResource(R.string.data_over) to dataSize(it) },
        stringResource(R.string.data_size) to dataSize(bundle.size),
        if (bundle.start != null && bundle.end != null) {
            stringResource(R.string.data_period) to stringResource(R.string.data_period_value, bundle.start.show(), bundle.end.show())
        } else null,
        stringResource(R.string.data_ends) to ends,
        pace?.let { stringResource(R.string.data_pace) to it },
        stringResource(R.string.data_cut_off) to stringResource(if (cutOff) R.string.data_cut_off_on else R.string.data_cut_off_off),
    )
}

/** The alerts, and the notification permission asked for right here when it is missing. */
@Composable
private fun AlertsBox(alerts: AlertSettings, onAlerts: (Boolean) -> Unit) {
    val context = LocalContext.current
    var needsPermission by remember { mutableStateOf(UsageNotifier.needsPermission(context)) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        needsPermission = !granted
        denied = !granted
        if (granted) onAlerts(true)
    }
    val blocked = !needsPermission && !NotificationManagerCompat.from(context).areNotificationsEnabled()
    val percents = alerts.percents.joinToString(", ") { "$it%" }

    MonoBox(Modifier.fillMaxWidth()) {
        val text = when {
            !alerts.enabled -> stringResource(R.string.data_alerts_off)
            denied -> stringResource(R.string.data_alerts_denied)
            needsPermission -> stringResource(R.string.data_alerts_ask, percents)
            blocked -> stringResource(R.string.data_alerts_blocked)
            alerts.endingDays.isEmpty() -> stringResource(R.string.data_alerts_on_no_days, percents)
            else -> stringResource(
                R.string.data_alerts_on,
                percents,
                stringResource(R.string.data_days_list, alerts.endingDays.sortedDescending().joinToString(", ")),
            )
        }
        Text(text, style = MaterialTheme.typography.bodyMedium, color = ink())
        when {
            !alerts.enabled -> {
                Spacer(Modifier.height(12.dp))
                MonoButton(stringResource(R.string.data_alerts_turn_on), {
                    onAlerts(true)
                    if (needsPermission) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }, Modifier.fillMaxWidth())
            }

            needsPermission && !denied -> {
                Spacer(Modifier.height(12.dp))
                MonoButton(
                    stringResource(R.string.data_alerts_allow),
                    { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) },
                    Modifier.fillMaxWidth(),
                    primary = true,
                )
            }
        }
    }
}

/** Sets the bundle's used amount from what Safaricom says is left (or used). */
@Composable
private fun CorrectDialog(bundle: BundleUsage, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    var byLeft by rememberSaveable { mutableStateOf(true) }
    var amount by rememberSaveable { mutableStateOf("") }
    val gb = amount.trim().replace(',', '.').toDoubleOrNull()
    val entered = gb?.takeIf { it >= 0 }?.let(::gbToBytes)
    val used = entered?.let { if (byLeft) bundle.size - it else it }?.takeIf { it in 0..bundle.size }

    Dialog(onDismissRequest = onDismiss) {
        MonoBox(Modifier.fillMaxWidth().imePadding(), padding = PaddingValues(20.dp)) {
            Text(stringResource(R.string.data_correct_title).uppercase(), style = MaterialTheme.typography.titleMedium, color = ink())
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.data_correct_body), style = MaterialTheme.typography.bodyMedium, color = ink())
            Spacer(Modifier.height(12.dp))
            MonoSegmented(
                listOf(stringResource(R.string.data_amount_left), stringResource(R.string.data_amount_used)),
                selected = if (byLeft) 0 else 1,
                onSelect = { byLeft = it == 0 },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            MonoTextField(
                value = amount,
                onValueChange = { amount = it },
                label = stringResource(R.string.data_amount_gb, stringResource(if (byLeft) R.string.data_amount_left else R.string.data_amount_used)),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (used != null) stringResource(R.string.data_correct_preview, dataSize(used), dataSize(bundle.size))
                else if (amount.isNotBlank()) stringResource(R.string.err_data_amount) else "",
                style = MaterialTheme.typography.bodySmall,
                color = ink(),
            )
            Spacer(Modifier.height(20.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MonoButton(stringResource(R.string.cancel), onDismiss, Modifier.weight(1f))
                MonoButton(
                    stringResource(R.string.data_write),
                    { used?.let(onConfirm) },
                    Modifier.weight(1f),
                    primary = true,
                    enabled = used != null,
                )
            }
        }
    }
}
