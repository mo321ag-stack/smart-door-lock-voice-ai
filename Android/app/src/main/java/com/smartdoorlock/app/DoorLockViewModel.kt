package com.smartdoorlock.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.smartdoorlock.bluetooth.ConnectionState
import com.smartdoorlock.bluetooth.DeviceInfo
import com.smartdoorlock.bluetooth.McuCommand
import com.smartdoorlock.bluetooth.McuStatus
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Voice-authentication status shown on the main screen.
 * Stage 3: the ECAPA-TDNN model is not integrated yet, so the only honest
 * state is MODEL_NOT_INSTALLED. Stage 4 adds the real states.
 */
enum class VoiceAuthState { MODEL_NOT_INSTALLED }

class DoorLockViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as SmartDoorLockApp
    private val spp = app.spp
    private val discovery = app.discovery

    val connection = spp.state
    val doorStatus = spp.lastStatus
    val doorStatusTime = spp.lastStatusTime
    val trafficLog = spp.log

    val pairedDevices = discovery.paired
    val foundDevices = discovery.found
    val discovering = discovery.discovering
    val bluetoothOn = discovery.bluetoothOn
    val pairingMessage = discovery.pairingMessage
    val bluetoothSupported: Boolean get() = discovery.isSupported

    val voiceState = VoiceAuthState.MODEL_NOT_INSTALLED

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    val lastDeviceAddress: String? get() = app.lastDeviceAddress

    // ---- Bluetooth -----------------------------------------------------

    fun refreshPaired() {
        discovery.refreshPaired()
    }

    fun startDiscovery() {
        discovery.start()?.let { _messages.tryEmit(it.message) }
    }

    fun stopDiscovery() = discovery.stop()

    fun pair(device: DeviceInfo) {
        val err = discovery.pair(device.address)
        _messages.tryEmit(err?.message ?: "Pairing with ${device.name}... confirm the PIN in the Android dialog.")
    }

    fun clearPairingMessage() = discovery.clearPairingMessage()

    fun connect(device: DeviceInfo) {
        app.lastDeviceAddress = device.address
        spp.connect(device.address)
    }

    fun reconnectLast() {
        val address = app.lastDeviceAddress
        if (address == null) {
            _messages.tryEmit("No previous device. Choose the HC-05 in Bluetooth devices.")
        } else {
            spp.connect(address)
        }
    }

    fun disconnect() = spp.disconnect()

    // ---- Lock commands -------------------------------------------------

    fun lockDoor() {
        viewModelScope.launch {
            val reply = spp.sendAndAwait(McuCommand.LOCK)
            _messages.emit(
                when (reply) {
                    McuStatus.LOCKED -> "Door locked."
                    null -> "No response from the lock. Check the connection."
                    else -> "Lock replied: ${reply.name}"
                },
            )
        }
    }

    fun refreshStatus() {
        viewModelScope.launch {
            if (spp.sendAndAwait(McuCommand.STATUS) == null) {
                _messages.emit("No STATUS response from the lock.")
            }
        }
    }

    /**
     * DEBUG BUILDS ONLY: sends a raw protocol command to test the firmware
     * before the voice model exists. The physical keypad PIN is still required
     * by the ATmega32, so this cannot unlock the door on its own.
     */
    fun sendDebugCommand(command: McuCommand) {
        if (!BuildConfig.DEBUG) return
        viewModelScope.launch { spp.send(command) }
    }

    fun clearLog() = spp.clearLog()

    val isConnected: Boolean get() = connection.value is ConnectionState.Connected
}
