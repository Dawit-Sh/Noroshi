package dev.dawitsh.noroshi.ui.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import dev.dawitsh.noroshi.R

/**
 * From Android 17 an app that targets it may only reach devices on the local network (the
 * router) once it holds ACCESS_LOCAL_NETWORK, a run-time permission. Without it every call to
 * the router times out.
 */
object LocalNetwork {
    private const val ANDROID_17 = 37

    fun needsPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= ANDROID_17 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED
}

/** Asks for local network access where the app starts, while it is missing; shows nothing once allowed. */
@Composable
fun LocalNetworkBox(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var missing by remember { mutableStateOf(LocalNetwork.needsPermission(context)) }
    var denied by remember { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        missing = !granted
        denied = !granted
    }
    if (!missing) return

    MonoBox(modifier.fillMaxWidth()) {
        Text(stringResource(R.string.local_network_title).uppercase(), style = MaterialTheme.typography.labelLarge, color = ink())
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(if (denied) R.string.local_network_denied else R.string.local_network_body),
            style = MaterialTheme.typography.bodyMedium,
            color = ink(),
        )
        if (!denied) {
            Spacer(Modifier.height(12.dp))
            MonoButton(
                stringResource(R.string.local_network_allow),
                { launcher.launch(Manifest.permission.ACCESS_LOCAL_NETWORK) },
                Modifier.fillMaxWidth(),
                primary = true,
            )
        }
    }
}
