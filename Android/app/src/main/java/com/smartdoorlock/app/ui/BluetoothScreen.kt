package com.smartdoorlock.app.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartdoorlock.app.DoorLockViewModel
import com.smartdoorlock.app.ui.theme.StatusColors
import com.smartdoorlock.bluetooth.BluetoothPermissions
import com.smartdoorlock.bluetooth.ConnectionState
import com.smartdoorlock.bluetooth.DeviceInfo

@Composable
fun BluetoothScreen(vm: DoorLockViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val connection by vm.connection.collectAsStateWithLifecycle()
    val paired by vm.pairedDevices.collectAsStateWithLifecycle()
    val found by vm.foundDevices.collectAsStateWithLifecycle()
    val discovering by vm.discovering.collectAsStateWithLifecycle()
    val bluetoothOn by vm.bluetoothOn.collectAsStateWithLifecycle()
    val pairingMessage by vm.pairingMessage.collectAsStateWithLifecycle()

    var missing by remember { mutableStateOf(BluetoothPermissions.missing(context)) }
    var askedOnce by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        askedOnce = true
        missing = BluetoothPermissions.missing(context)
        vm.refreshPaired()
    }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.refreshPaired()
    }

    LifecycleResumeEffect(Unit) {
        missing = BluetoothPermissions.missing(context)
        vm.refreshPaired()
        onPauseOrDispose { }
    }
    DisposableEffect(Unit) { onDispose { vm.stopDiscovery() } }
    LaunchedEffect(bluetoothOn) { vm.refreshPaired() }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!vm.bluetoothSupported) {
            StatusCard("Bluetooth", StatusLabel("Not supported", "This phone has no Bluetooth adapter.", StatusColors.Bad))
            return@Column
        }

        // 1. Permissions
        if (missing.isNotEmpty()) {
            val activity = context.findActivity()
            val permanentlyDenied = askedOnce && activity != null &&
                missing.none { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
            SectionCard {
                Text("Permission needed", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        "Allow \"Nearby devices\" so the app can list, pair and connect to the HC-05 over Bluetooth Classic. The app does not use your location."
                    } else {
                        "Android 11 and older require the Location permission to search for Bluetooth Classic devices. The app does not use your location."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                if (permanentlyDenied) {
                    Text("Permission was denied. Enable it in App settings.", color = StatusColors.Bad, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Button(onClick = { context.openAppSettings() }) { Text("Open app settings") }
                } else {
                    Button(onClick = { permissionLauncher.launch(BluetoothPermissions.allPermissions()) }) { Text("Grant permission") }
                }
            }
        }

        // 2. Bluetooth on/off
        if (!bluetoothOn) {
            SectionCard {
                Text("Bluetooth is off", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = StatusColors.Bad)
                Spacer(Modifier.height(8.dp))
                Button(
                    enabled = BluetoothPermissions.hasConnect(context),
                    onClick = { enableLauncher.launch(enableBluetoothIntent()) },
                ) { Text("Turn on Bluetooth") }
            }
        }

        // 3. Connection status, three separate levels
        StatusCard("Connection", connection.label()) {
            val dev = when (val c = connection) {
                is ConnectionState.Connected -> c.device
                is ConnectionState.Connecting -> c.device
                is ConnectionState.Reconnecting -> c.device
                is ConnectionState.Failed -> c.device
                ConnectionState.Disconnected -> null
            }
            Spacer(Modifier.height(6.dp))
            CheckRow(dev?.bonded == true, "Paired (bonded) device selected" + (dev?.let { ": ${it.name}" } ?: ""))
            CheckRow(connection is ConnectionState.Connected, "RFCOMM socket open")
            CheckRow((connection as? ConnectionState.Connected)?.mcuVerified == true, "Serial communication with ATmega32 confirmed")
            if (connection is ConnectionState.Connected || connection is ConnectionState.Connecting || connection is ConnectionState.Reconnecting) {
                TextButton(onClick = vm::disconnect) { Text("Disconnect") }
            }
        }

        // 4. Paired devices
        SectionCard {
            Text("Paired devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Pick the HC-05. Only paired devices can be connected.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            if (paired.isEmpty()) {
                Text(
                    if (missing.isNotEmpty() || !bluetoothOn) "Grant permission and turn on Bluetooth to see paired devices."
                    else "No paired Bluetooth Classic devices. Search below to pair the HC-05.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            paired.forEachIndexed { i, d ->
                if (i > 0) HorizontalDivider()
                val current = (connection as? ConnectionState.Connected)?.device?.address == d.address
                DeviceRow(d, actionLabel = if (current) "Connected" else "Connect", actionEnabled = !current) { vm.connect(d) }
            }
        }

        // 5. Discovery + pairing
        SectionCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Find new devices", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        "Bluetooth Classic discovery (~12 s). Pairing PIN is usually 1234 or 0000.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (discovering) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(onClick = vm::stopDiscovery) { Text("Stop") }
                } else {
                    OutlinedButton(onClick = vm::startDiscovery, enabled = bluetoothOn && missing.isEmpty()) { Text("Search") }
                }
            }
            pairingMessage?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            }
            val unpaired = found.filterNot { f -> paired.any { it.address == f.address } }
            if (unpaired.isNotEmpty()) Spacer(Modifier.height(8.dp))
            unpaired.forEachIndexed { i, d ->
                if (i > 0) HorizontalDivider()
                DeviceRow(d, actionLabel = if (d.bonded) "Paired" else "Pair", actionEnabled = !d.bonded) { vm.pair(d) }
            }
            if (!discovering && found.isEmpty()) {
                Text("No search results yet.", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun DeviceRow(device: DeviceInfo, actionLabel: String, actionEnabled: Boolean, onAction: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                device.name + if (device.looksLikeHc05) "  •  HC-05" else "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (device.looksLikeHc05) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(device.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Button(onClick = onAction, enabled = actionEnabled) { Text(actionLabel) }
    }
}

@SuppressLint("MissingPermission") // only launched when BLUETOOTH_CONNECT is granted
private fun enableBluetoothIntent() = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)

private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is Activity) return c
        c = c.baseContext
    }
    return null
}

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
