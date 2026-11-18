package com.smartdoorlock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartdoorlock.app.BuildConfig
import com.smartdoorlock.app.DoorLockViewModel
import com.smartdoorlock.app.ui.theme.StatusColors
import com.smartdoorlock.bluetooth.ConnectionState
import com.smartdoorlock.bluetooth.McuCommand

@Composable
fun MainScreen(
    vm: DoorLockViewModel,
    modifier: Modifier = Modifier,
    onOpenBluetooth: () -> Unit,
    onOpenVoice: () -> Unit,
) {
    val connection by vm.connection.collectAsStateWithLifecycle()
    val door by vm.doorStatus.collectAsStateWithLifecycle()
    val doorTime by vm.doorStatusTime.collectAsStateWithLifecycle()
    val log by vm.trafficLog.collectAsStateWithLifecycle()

    val connected = connection is ConnectionState.Connected
    val busy = connection is ConnectionState.Connecting || connection is ConnectionState.Reconnecting

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Voice + keypad two-factor access",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        StatusCard("Bluetooth connection", connection.label())
        StatusCard("Voice authentication", vm.voiceState.label())
        StatusCard("Door", door.label(connected)) {
            doorTime?.let {
                if (door != null) {
                    Spacer(Modifier.height(4.dp))
                    Text("Last update ${formatTime(it)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        DeviceCard(connection)

        Button(
            onClick = onOpenVoice,
            enabled = connected,
            modifier = Modifier.fillMaxWidth().height(52.dp),
        ) { Text("Start Voice Verification") }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = vm::lockDoor,
                enabled = connected,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
            ) { Text("Lock Door") }
            OutlinedButton(onClick = vm::refreshStatus, enabled = connected, modifier = Modifier.weight(1f)) {
                Text("Refresh status")
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onOpenBluetooth, modifier = Modifier.weight(1f)) { Text("Bluetooth devices") }
            OutlinedButton(
                onClick = vm::disconnect,
                enabled = connected || busy,
                modifier = Modifier.weight(1f),
            ) { Text("Disconnect") }
        }

        if (connection is ConnectionState.Failed || connection == ConnectionState.Disconnected) {
            if (vm.lastDeviceAddress != null) {
                TextButton(onClick = vm::reconnectLast) { Text("Reconnect to last device") }
            }
        }

        if (BuildConfig.DEBUG) {
            DebugConsole(vm, connected, log)
        }
    }
}

@Composable
private fun DeviceCard(connection: ConnectionState) {
    val device = when (connection) {
        is ConnectionState.Connected -> connection.device
        is ConnectionState.Connecting -> connection.device
        is ConnectionState.Reconnecting -> connection.device
        is ConnectionState.Failed -> connection.device
        ConnectionState.Disconnected -> null
    }
    SectionCard {
        Text("DEVICE STATUS", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        if (device == null) {
            Text("No device selected.", style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(device.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(device.address, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(6.dp))
            CheckRow(device.bonded, "Paired with this phone")
            CheckRow(connection is ConnectionState.Connected, "RFCOMM (SPP) socket open")
            CheckRow(
                connection is ConnectionState.Connected && connection.mcuVerified,
                "ATmega32 answering the protocol",
            )
        }
    }
}

@Composable
private fun DebugConsole(vm: DoorLockViewModel, connected: Boolean, log: List<com.smartdoorlock.bluetooth.LogEntry>) {
    SectionCard {
        Text("PROTOCOL TEST CONSOLE (DEBUG BUILD ONLY)", style = MaterialTheme.typography.labelMedium, color = StatusColors.Warn)
        Spacer(Modifier.height(4.dp))
        Text(
            "For firmware testing before the voice model exists. These buttons send raw commands. " +
                "The ATmega32 still requires the keypad PIN, so they cannot unlock the door. " +
                "Not present in release builds.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            McuCommand.entries.forEach { cmd ->
                OutlinedButton(
                    onClick = { vm.sendDebugCommand(cmd) },
                    enabled = connected,
                    modifier = Modifier.weight(1f),
                    contentPadding = ButtonDefaults.TextButtonContentPadding,
                ) { Text(cmd.wire, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(8.dp))
        Row {
            Text("Traffic", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).padding(top = 12.dp))
            TextButton(onClick = vm::clearLog) { Text("Clear") }
        }
        TrafficLog(log)
    }
}
