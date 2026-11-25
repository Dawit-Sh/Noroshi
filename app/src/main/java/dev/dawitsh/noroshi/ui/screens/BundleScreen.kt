package dev.dawitsh.noroshi.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import dev.dawitsh.noroshi.R
import dev.dawitsh.noroshi.core.data.DataUsageService
import dev.dawitsh.noroshi.core.data.NoticeCenter
import dev.dawitsh.noroshi.core.data.UsageRepository
import dev.dawitsh.noroshi.core.model.BundlePlan
import dev.dawitsh.noroshi.core.model.BundleProblem
import dev.dawitsh.noroshi.core.model.GB
import dev.dawitsh.noroshi.core.model.dataSize
import dev.dawitsh.noroshi.core.model.gbToBytes
import dev.dawitsh.noroshi.core.model.res
import dev.dawitsh.noroshi.di.container
import dev.dawitsh.noroshi.ui.Route
import dev.dawitsh.noroshi.ui.components.MonoBox
import dev.dawitsh.noroshi.ui.components.MonoButton
import dev.dawitsh.noroshi.ui.components.MonoDialog
import dev.dawitsh.noroshi.ui.components.MonoSegmented
import dev.dawitsh.noroshi.ui.components.MonoTextField
import dev.dawitsh.noroshi.ui.components.MonoTopBar
import dev.dawitsh.noroshi.ui.components.ink
import dev.dawitsh.noroshi.ui.components.paper
import dev.dawitsh.noroshi.ui.leafCrumbs
import dev.dawitsh.noroshi.ui.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The Set bundle form as typed. The validity in days and the end date follow each other:
 * changing one fills in the other. The amount is what Safaricom says is left or used.
 */
internal data class BundleForm(
    val sizeGb: String = "",
    val start: String = "",
    val days: String = "30",
    val end: String = "",
    val byLeft: Boolean = false,
    val amountGb: String = "0",
) {
    fun withStart(value: String) = copy(start = value).endFromDays()
    fun withDays(value: String) = copy(days = value).endFromDays()
    fun withEnd(value: String) = copy(end = value).daysFromEnd()

    private fun endFromDays(): BundleForm {
        val s = date(start) ?: return this
        val d = days.trim().toLongOrNull()?.takeIf { it > 0 } ?: return this
        return copy(end = s.plusDays(d).toString())
    }

    private fun daysFromEnd(): BundleForm {
        val s = date(start) ?: return this
        val e = date(end) ?: return this
        return copy(days = ChronoUnit.DAYS.between(s, e).toString())
    }

    val size: Long? get() = gb(sizeGb)?.takeIf { it > 0 }

    /** The bundle to write, or null while a field doesn't parse. */
    fun plan(): BundlePlan? {
        val size = size ?: return null
        val s = date(start) ?: return null
        val e = date(end) ?: return null
        val amount = gb(amountGb) ?: return null
        return BundlePlan(size, s, e, if (byLeft) size - amount else amount)
    }

    companion object {
        fun fresh(today: LocalDate, sizeBytes: Long? = null): BundleForm = BundleForm(
            sizeGb = sizeBytes?.let { gbText(it) } ?: "",
            start = today.toString(),
        ).endFromDays()

        private fun date(text: String): LocalDate? = try {
            LocalDate.parse(text.trim())
        } catch (_: DateTimeParseException) {
            null
        }

        private fun gb(text: String): Long? = text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it >= 0 }?.let(::gbToBytes)

        /** 500 GB -> "500", 1.5 GB -> "1.5". */
        fun gbText(bytes: Long): String {
            val gb = bytes.toDouble() / GB
            return if (gb == Math.floor(gb)) gb.toLong().toString() else String.format(Locale.US, "%.2f", gb).trimEnd('0').trimEnd('.')
        }
    }
}

class BundleViewModel(
    private val service: DataUsageService,
    usage: UsageRepository,
    private val notices: NoticeCenter,
) : ViewModel() {
    private val state = MutableStateFlow(BundleForm.fresh(LocalDate.now()))
    internal val form: StateFlow<BundleForm> = state.asStateFlow()

    private val writingState = MutableStateFlow(false)
    val writing: StateFlow<Boolean> = writingState.asStateFlow()

    init {
        // Start from the size of the last bundle seen, which is usually the one bought again.
        viewModelScope.launch {
            val last = usage.last.first() ?: return@launch
            if (state.value.sizeGb.isEmpty()) state.value = state.value.copy(sizeGb = BundleForm.gbText(last.size))
        }
    }

    internal fun update(transform: (BundleForm) -> BundleForm) {
        state.value = transform(state.value)
    }

    fun write(onDone: () -> Unit) {
        val plan = state.value.plan() ?: return
        if (plan.problem(LocalDate.now()) != null || writingState.value) return
        writingState.value = true
        viewModelScope.launch {
            try {
                service.setBundle(plan)
                notices.post(res(R.string.data_bundle_set))
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.post(e.uiText())
            } finally {
                writingState.value = false
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer { BundleViewModel(container.dataUsage, container.usageRepository, container.notices) }
        }
    }
}

@Composable
fun BundleScreen(onCrumb: (Route) -> Unit, onBack: () -> Unit, vm: BundleViewModel = viewModel(factory = BundleViewModel.Factory)) {
    val form by vm.form.collectAsStateWithLifecycle()
    val writing by vm.writing.collectAsStateWithLifecycle()
    var confirm by rememberSaveable { mutableStateOf(false) }

    val today = LocalDate.now()
    val plan = form.plan()
    val problem = plan?.problem(today)
    val preview = if (plan != null && problem == null) {
        stringResource(
            R.string.bundle_preview,
            plan.start.show(),
            plan.end.show(),
            ChronoUnit.DAYS.between(plan.start, plan.end).toInt(),
            dataSize(plan.size),
            dataSize(plan.used ?: 0),
        )
    } else null
    val problemText = when {
        form.size == null && form.sizeGb.isNotBlank() -> stringResource(R.string.bundle_problem_size)
        plan == null -> if (form.sizeGb.isBlank()) stringResource(R.string.bundle_problem_size) else stringResource(R.string.bundle_problem_dates)
        problem == BundleProblem.NoSize -> stringResource(R.string.bundle_problem_size)
        problem == BundleProblem.EndBeforeStart -> stringResource(R.string.bundle_problem_dates)
        problem == BundleProblem.TodayOutside -> stringResource(R.string.bundle_problem_today)
        problem == BundleProblem.UsedTooBig -> stringResource(R.string.bundle_problem_used)
        else -> null
    }

    Scaffold(
        containerColor = paper(),
        topBar = { MonoTopBar(leafCrumbs(listOf("post", "bundle")), onCrumb = onCrumb, onBack = onBack) },
    ) { inner ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp)
        ) {
            Text(stringResource(R.string.bundle_help), style = MaterialTheme.typography.bodySmall, color = ink())
            Spacer(Modifier.height(16.dp))
            MonoTextField(
                value = form.sizeGb,
                onValueChange = { v -> vm.update { it.copy(sizeGb = v) } },
                label = stringResource(R.string.bundle_size),
                placeholder = "500",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.height(16.dp))
            MonoTextField(
                value = form.start,
                onValueChange = { v -> vm.update { it.withStart(v) } },
                label = stringResource(R.string.bundle_start),
                placeholder = today.toString(),
            )
            Spacer(Modifier.height(16.dp))
            MonoTextField(
                value = form.days,
                onValueChange = { v -> vm.update { it.withDays(v) } },
                label = stringResource(R.string.bundle_days),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Spacer(Modifier.height(16.dp))
            MonoTextField(
                value = form.end,
                onValueChange = { v -> vm.update { it.withEnd(v) } },
                label = stringResource(R.string.bundle_end),
            )
            Spacer(Modifier.height(16.dp))
            MonoSegmented(
                listOf(stringResource(R.string.data_amount_used), stringResource(R.string.data_amount_left)),
                selected = if (form.byLeft) 1 else 0,
                onSelect = { index ->
                    vm.update {
                        val left = index == 1
                        // Switching fills in the matching figure for a fresh bundle: 0 used is all of it left.
                        val amount = when {
                            left && it.amountGb.trim() == "0" -> it.sizeGb
                            !left && it.amountGb.trim() == it.sizeGb.trim() -> "0"
                            else -> it.amountGb
                        }
                        it.copy(byLeft = left, amountGb = amount)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            MonoTextField(
                value = form.amountGb,
                onValueChange = { v -> vm.update { it.copy(amountGb = v) } },
                label = stringResource(
                    R.string.data_amount_gb,
                    stringResource(if (form.byLeft) R.string.data_amount_left else R.string.data_amount_used),
                ),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
            Spacer(Modifier.height(16.dp))
            MonoBox(Modifier.fillMaxWidth()) {
                Text(preview ?: problemText ?: "", style = MaterialTheme.typography.bodyMedium, color = ink())
            }
            Spacer(Modifier.height(16.dp))
            MonoButton(
                stringResource(if (writing) R.string.data_writing else R.string.data_write),
                { confirm = true },
                Modifier.fillMaxWidth(),
                primary = true,
                enabled = preview != null && !writing,
            )
            Spacer(Modifier.height(24.dp))
        }
    }

    if (confirm && preview != null) {
        MonoDialog(
            title = stringResource(R.string.bundle_confirm_title),
            message = preview,
            confirmText = stringResource(R.string.data_write),
            onConfirm = {
                confirm = false
                vm.write(onBack)
            },
            onDismiss = { confirm = false },
        )
    }
}
