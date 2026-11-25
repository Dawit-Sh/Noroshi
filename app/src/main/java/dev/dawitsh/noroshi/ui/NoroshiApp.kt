package dev.dawitsh.noroshi.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.dawitsh.noroshi.core.data.NoticeCenter
import dev.dawitsh.noroshi.ui.components.MonoSnackbar
import dev.dawitsh.noroshi.ui.components.paper
import dev.dawitsh.noroshi.ui.screens.MenuScreen
import dev.dawitsh.noroshi.ui.screens.ReportScreen
import dev.dawitsh.noroshi.ui.screens.SendSmsScreen
import dev.dawitsh.noroshi.ui.screens.SettingsScreen
import dev.dawitsh.noroshi.ui.screens.SmsInboxScreen
import dev.dawitsh.noroshi.ui.screens.SmsMessageScreen
import dev.dawitsh.noroshi.ui.screens.SmsSelectScreen
import dev.dawitsh.noroshi.ui.screens.SmsShowScreen
import dev.dawitsh.noroshi.ui.screens.UssdScreen

@Composable
fun NoroshiApp(notices: NoticeCenter) {
    val navController = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current

    LaunchedEffect(notices) {
        notices.notices.collect { snackbar.showSnackbar(it.resolve(context)) }
    }

    val navigate: (Route) -> Unit = { navController.navigate(it) }
    val back: () -> Unit = { navController.popBackStack() }
    val jump: (Route) -> Unit = { target ->
        if (!navController.popBackStack(target, inclusive = false)) navController.navigate(target)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(paper())
    ) {
        NavHost(navController = navController, startDestination = Route.Menu()) {
            composable<Route.Menu> { MenuScreen(onNavigate = navigate, onCrumb = jump, onBack = back) }
            composable<Route.Settings> { SettingsScreen(onCrumb = jump, onBack = back) }
            composable<Route.Report> { ReportScreen(onCrumb = jump, onBack = back) }
            composable<Route.SmsInbox> { SmsInboxScreen(onNavigate = navigate, onCrumb = jump, onBack = back) }
            composable<Route.SmsShow> { SmsShowScreen(onNavigate = navigate, onCrumb = jump, onBack = back) }
            composable<Route.SmsMessage> { SmsMessageScreen(onNavigate = navigate, onCrumb = jump, onBack = back) }
            composable<Route.SendSms> { SendSmsScreen(onCrumb = jump, onBack = back) }
            composable<Route.SmsSelect> { SmsSelectScreen(onCrumb = jump, onBack = back) }
            composable<Route.Ussd> { UssdScreen(onCrumb = jump, onBack = back) }
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp),
            snackbar = { MonoSnackbar(it) },
        )
    }
}
