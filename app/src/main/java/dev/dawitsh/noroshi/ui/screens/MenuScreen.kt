package dev.dawitsh.noroshi.ui.screens

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.toRoute
import dev.dawitsh.noroshi.R
import dev.dawitsh.noroshi.core.data.NoticeCenter
import dev.dawitsh.noroshi.core.data.RouterRepository
import dev.dawitsh.noroshi.core.data.SettingsRepository
import dev.dawitsh.noroshi.core.model.Feature
import dev.dawitsh.noroshi.core.model.ReportKind
import dev.dawitsh.noroshi.core.model.RouterType
import dev.dawitsh.noroshi.core.model.Settings
import dev.dawitsh.noroshi.core.model.res
import dev.dawitsh.noroshi.di.container
import dev.dawitsh.noroshi.ui.MenuAction
import dev.dawitsh.noroshi.ui.Node
import dev.dawitsh.noroshi.ui.Route
import dev.dawitsh.noroshi.ui.components.MonoButton
import dev.dawitsh.noroshi.ui.components.MonoDialog
import dev.dawitsh.noroshi.ui.components.MonoDivider
import dev.dawitsh.noroshi.ui.components.MonoIconButton
import dev.dawitsh.noroshi.ui.components.MonoListItem
import dev.dawitsh.noroshi.ui.components.MonoTag
import dev.dawitsh.noroshi.ui.components.MonoTopBar
import dev.dawitsh.noroshi.ui.components.SectionHeader
import dev.dawitsh.noroshi.ui.components.ink
import dev.dawitsh.noroshi.ui.components.paper
import dev.dawitsh.noroshi.ui.menuCrumbs
import dev.dawitsh.noroshi.ui.resolveMenu
import dev.dawitsh.noroshi.ui.uiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MenuUiState(val rebooting: Boolean = false, val marking: Boolean = false)

class MenuViewModel(
    savedStateHandle: SavedStateHandle,
    private val repository: RouterRepository,
    settingsRepository: SettingsRepository,
    private val notices: NoticeCenter,
) : ViewModel() {
    val path: List<String> = savedStateHandle.toRoute<Route.Menu>().path

    private val state = MutableStateFlow(MenuUiState())
    val uiState: StateFlow<MenuUiState> = state.asStateFlow()

    val settings: StateFlow<Settings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Settings())

    fun reboot() {
        if (state.value.rebooting) return
        state.value = state.value.copy(rebooting = true)
        viewModelScope.launch {
            try {
                val router = repository.open()
                router.login()
                router.reboot()
                notices.post(res(R.string.device_rebooting))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.post(e.uiText())
            } finally {
                state.value = state.value.copy(rebooting = false)
            }
        }
    }

    fun markAllRead() {
        if (state.value.marking) return
        state.value = state.value.copy(marking = true)
        viewModelScope.launch {
            try {
                val result = repository.session { markAllSms() }
                if (result == null) {
                    notices.post(res(R.string.no_unread))
                } else {
                    notices.post(result.notice())
                    repository.notifyInboxChanged()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                notices.post(e.uiText())
            } finally {
                state.value = state.value.copy(marking = false)
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                MenuViewModel(
                    createSavedStateHandle(),
                    container.routerRepository,
                    container.settingsRepository,
                    container.notices,
                )
            }
        }
    }
}

@Composable
fun MenuScreen(
    onNavigate: (Route) -> Unit,
    onCrumb: (Route) -> Unit,
    onBack: () -> Unit,
    vm: MenuViewModel = viewModel(factory = MenuViewModel.Factory),
) {
    val state by vm.uiState.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val path = vm.path
    val menu = resolveMenu(settings.type, path)
    val isRoot = path.isEmpty()
    var pendingAction by remember { mutableStateOf<MenuAction?>(null) }

    Scaffold(
        containerColor = paper(),
        topBar = {
            if (isRoot) {
                MonoTopBar(stringResource(R.string.app_title)) {
                    MonoIconButton(Icons.Filled.Settings, stringResource(R.string.settings), onClick = { onNavigate(Route.Settings) })
                }
            } else {
                MonoTopBar(menuCrumbs(path), onCrumb = onCrumb, onBack = onBack)
            }
        },
    ) { inner ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(inner),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        ) {
            item {
                Text(
                    menu?.let { stringResource(it.description) } ?: stringResource(R.string.unknown_command),
                    style = MaterialTheme.typography.bodySmall,
                    color = ink(),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            item {
                SectionHeader(
                    if (isRoot) stringResource(R.string.commands)
                    else stringResource(R.string.usage_prefix, path.joinToString(" "))
                )
            }

            item {
                val ink = ink()
                Column(
                    Modifier
                        .fillMaxWidth()
                        .border(1.dp, ink)
                ) {
                    menu?.children.orEmpty().forEachIndexed { index, node ->
                        if (index > 0) MonoDivider()
                        NodeRow(node) {
                            when (node) {
                                is Node.Menu -> onNavigate(Route.Menu(path + node.name))
                                is Node.Leaf -> onNavigate(node.target)
                                is Node.Action -> pendingAction = node.action
                            }
                        }
                    }
                }
            }

            if (isRoot) {
                item {
                    SectionHeader(stringResource(R.string.quick_actions))
                    QuickActions(settings.type, marking = state.marking, onNavigate = onNavigate, onMarkAllRead = vm::markAllRead)
                }
                item {
                    Spacer(Modifier.height(24.dp))
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                            ) { onNavigate(Route.Settings) }
                    ) {
                        MonoDivider()
                        Text(
                            stringResource(R.string.router_footer_type, stringResource(settings.type.label), settings.router, settings.username),
                            style = MaterialTheme.typography.bodySmall,
                            color = ink(),
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }

    when (pendingAction) {
        MenuAction.Reboot -> MonoDialog(
            title = stringResource(R.string.reboot_title),
            message = stringResource(R.string.reboot_message),
            confirmText = stringResource(R.string.reboot),
            onConfirm = {
                pendingAction = null
                vm.reboot()
            },
            onDismiss = { pendingAction = null },
        )

        null -> Unit
    }
}

@Composable
private fun NodeRow(node: Node, onClick: () -> Unit) {
    when (node) {
        is Node.Menu -> MonoListItem(
            title = "${node.name}/",
            subtitle = stringResource(node.description),
            onClick = onClick,
            trailing = { MonoTag("${node.children.size}") },
        )

        is Node.Leaf -> MonoListItem(
            title = if (node.usage.isEmpty()) node.name else "${node.name} ${node.usage}",
            subtitle = stringResource(node.description),
            onClick = onClick,
        )

        is Node.Action -> MonoListItem(
            title = node.name,
            subtitle = stringResource(node.description),
            onClick = onClick,
            trailing = { MonoTag(stringResource(R.string.action)) },
        )
    }
}

private class Quick(val label: Int, val primary: Boolean = false, val enabled: Boolean = true, val onClick: () -> Unit)

@Composable
private fun QuickActions(type: RouterType, marking: Boolean, onNavigate: (Route) -> Unit, onMarkAllRead: () -> Unit) {
    val sms = type.has(Feature.Sms)
    val ussd = type.has(Feature.Ussd)
    val buttons = listOfNotNull(
        Quick(R.string.quick_inbox, primary = true) { onNavigate(Route.SmsInbox) }.takeIf { sms },
        Quick(R.string.quick_balance, primary = true) { onNavigate(Route.Ussd("*704#")) }.takeIf { ussd },
        Quick(R.string.quick_devices, primary = !sms) { onNavigate(Route.Report(ReportKind.Devices)) }
            .takeIf { ReportKind.Devices in type.reports },
        Quick(R.string.quick_bundles) { onNavigate(Route.Ussd("*777*02#")) }.takeIf { ussd },
        Quick(R.string.quick_signal, primary = !sms) { onNavigate(Route.Report(ReportKind.Signal)) }
            .takeIf { !sms && ReportKind.Signal in type.reports },
        Quick(R.string.quick_send_sms) { onNavigate(Route.SendSms()) }.takeIf { sms },
        Quick(if (marking) R.string.marking else R.string.quick_mark_all_read, enabled = !marking) { onMarkAllRead() }.takeIf { sms },
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        buttons.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { b ->
                    MonoButton(
                        stringResource(b.label),
                        b.onClick,
                        Modifier.weight(1f),
                        primary = b.primary,
                        enabled = b.enabled,
                    )
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
