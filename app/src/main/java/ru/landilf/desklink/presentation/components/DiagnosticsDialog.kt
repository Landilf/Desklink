package ru.landilf.desklink.presentation.components

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.landilf.desklink.domain.model.SshProfile
import ru.landilf.desklink.R
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket

@Composable
fun DiagnosticsDialog(
    profiles: List<SshProfile>,
    active: SshProfile?,
    connected: Boolean,
    dismiss: () -> Unit
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE) }
    var address by remember { mutableStateOf(prefs.getString("zerotier_ip", "10.84.43.69") ?: "") }
    var selectedId by remember { mutableStateOf(active?.id ?: profiles.firstOrNull()?.id) }
    val selected = profiles.firstOrNull { it.id == selectedId }
    var revision by remember { mutableIntStateOf(0) }
    var ipStatus by remember { mutableStateOf(CheckStatus.PENDING) }
    var vpnStatus by remember { mutableStateOf(CheckStatus.PENDING) }
    var portStatus by remember { mutableStateOf(CheckStatus.PENDING) }

    LaunchedEffect(revision, selected?.id) {
        val targetAddress = address.trim()
        ipStatus = CheckStatus.PENDING; vpnStatus = CheckStatus.PENDING; portStatus = CheckStatus.PENDING
        val result = withContext(Dispatchers.IO) {
            val ip = runCatching {
                NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp }
                    .flatMap { it.inetAddresses.toList() }.any { it.hostAddress == targetAddress }
            }.fold({ if (it && targetAddress.isNotBlank()) CheckStatus.YES else CheckStatus.NO }, { CheckStatus.ERROR })
            val vpn = runCatching {
                val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                manager.allNetworks.any { manager.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
            }.fold({ if (it) CheckStatus.YES else CheckStatus.NO }, { CheckStatus.ERROR })
            val port = if (selected == null) CheckStatus.PENDING else runCatching {
                Socket().use { it.connect(InetSocketAddress(selected.host, selected.port), 3000) }
                CheckStatus.YES
            }.getOrElse { CheckStatus.NO }
            Triple(ip, vpn, port)
        }
        ipStatus = result.first; vpnStatus = result.second; portStatus = result.third
    }

    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text(context.getString(R.string.diagnostics)) },
        text = {
            Column(Modifier
                .heightIn(max = 500.dp)
                .verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    address,
                    { address = it },
                    label = { Text(context.getString(R.string.phone_ip)) },
                    singleLine = true
                )
                DiagnosticStatus(context.getString(R.string.diagnostic_phone), ipStatus)
                DiagnosticStatus(context.getString(R.string.diagnostic_vpn), vpnStatus)
                Text(context.getString(R.string.profile_to_check), Modifier.padding(top = 12.dp))
                profiles.forEach { profile ->
                    TextButton(onClick = { selectedId = profile.id }) {
                        Text((if (selectedId == profile.id) "● " else "○ ") + "${profile.name} (${profile.host}:${profile.port})")
                    }
                }
                DiagnosticStatus(context.getString(R.string.diagnostic_ssh_port), portStatus)
                DiagnosticStatus(context.getString(R.string.diagnostic_ssh_session),
                    if (connected && active?.id == selectedId) CheckStatus.YES else CheckStatus.NO)
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                TextButton(onClick = dismiss) { Text(context.getString(R.string.close)) }
                TextButton(onClick = {
                    prefs.edit().putString("zerotier_ip", address.trim()).apply()
                    revision++
                }) { Text(context.getString(R.string.check)) }
            }
        })
}


@Composable
private fun DiagnosticStatus(label: String, status: CheckStatus) {
    val statusText = stringResource(when (status) {
        CheckStatus.YES -> R.string.diagnostic_ok
        CheckStatus.NO -> R.string.diagnostic_no
        CheckStatus.PENDING -> R.string.checking
        CheckStatus.ERROR -> R.string.check_failed
    })
    Row(verticalAlignment = Alignment.CenterVertically) {
        StatusIndicator(status, "$label: $statusText")
        Spacer(Modifier.width(8.dp))
        Text(label)
    }
}
