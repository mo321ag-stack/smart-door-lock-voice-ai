package com.smartdoorlock.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import java.util.UUID

object SppConstants {
    /** Standard Serial Port Profile UUID used by the HC-05. */
    val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    const val CONNECT_TIMEOUT_MS = 12_000L
    const val HANDSHAKE_TIMEOUT_MS = 3_000L
    const val RESPONSE_TIMEOUT_MS = 3_000L
    const val MAX_RECONNECT_ATTEMPTS = 3
    const val RECONNECT_BASE_DELAY_MS = 2_000L
    const val LOG_CAPACITY = 200
}

data class DeviceInfo(
    val name: String,
    val address: String,
    val bonded: Boolean,
    val isClassic: Boolean,
) {
    /** Heuristic for highlighting only; any paired SPP device can be used. */
    val looksLikeHc05: Boolean
        get() = name.contains("HC-05", ignoreCase = true) || name.contains("HC05", ignoreCase = true)
}

@SuppressLint("MissingPermission") // callers check BLUETOOTH_CONNECT first; SecurityException is caught
internal fun BluetoothDevice.toInfo(): DeviceInfo = DeviceInfo(
    name = runCatching { name }.getOrNull()?.takeIf { it.isNotBlank() } ?: "(unnamed device)",
    address = address,
    bonded = runCatching { bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
    isClassic = runCatching { type != BluetoothDevice.DEVICE_TYPE_LE }.getOrDefault(true),
)

/** User-facing error categories. */
enum class BtError(val message: String) {
    NOT_SUPPORTED("This phone has no Bluetooth adapter."),
    DISABLED("Bluetooth is turned off. Turn it on and try again."),
    PERMISSION_DENIED("Bluetooth permission was denied. Allow \"Nearby devices\" for this app in Android Settings."),
    NOT_PAIRED("This device is not paired. Pair the HC-05 first (default PIN is usually 1234 or 0000)."),
    DEVICE_UNAVAILABLE("The device is not available. Check that the HC-05 is powered and in range."),
    CONNECTION_FAILED("Could not open the RFCOMM (SPP) channel. Make sure the HC-05 is powered, in range, and not connected to another phone."),
    TIMEOUT("The connection attempt timed out."),
    SOCKET_CLOSED("The Bluetooth link was lost."),
    WRITE_FAILED("Sending the command failed because the link closed."),
    NO_MCU_RESPONSE("The Bluetooth link is open, but the ATmega32 did not answer STATUS. Check the HC-05 baud rate (9600), the TX/RX wiring and the firmware."),
    DISCOVERY_FAILED("Device discovery could not start. On Android 11 and older, Location must also be turned on."),
    PAIRING_FAILED("Pairing failed or was cancelled. Check the PIN (often 1234 or 0000)."),
}

sealed interface ConnectionState {
    data object Disconnected : ConnectionState

    data class Connecting(val device: DeviceInfo) : ConnectionState

    data class Reconnecting(val device: DeviceInfo?, val attempt: Int, val maxAttempts: Int) : ConnectionState

    /**
     * RFCOMM socket is open. [mcuVerified] becomes true only after a valid
     * protocol line has been received from the ATmega32 - that is the proof of
     * working serial communication, not just a Bluetooth connection.
     */
    data class Connected(
        val device: DeviceInfo,
        val mcuVerified: Boolean,
        val warning: BtError? = null,
    ) : ConnectionState

    data class Failed(val error: BtError, val detail: String?, val device: DeviceInfo?) : ConnectionState
}

data class LogEntry(val timeMillis: Long, val kind: Kind, val text: String) {
    enum class Kind { TX, RX, INFO, ERROR }
}
