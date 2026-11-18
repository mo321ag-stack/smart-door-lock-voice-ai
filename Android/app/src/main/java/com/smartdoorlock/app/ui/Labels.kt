package com.smartdoorlock.app.ui

import androidx.compose.ui.graphics.Color
import com.smartdoorlock.app.VoiceAuthState
import com.smartdoorlock.app.ui.theme.StatusColors
import com.smartdoorlock.bluetooth.ConnectionState
import com.smartdoorlock.bluetooth.McuStatus

/** Short title + explanation + colour for each status, used by the cards. */
data class StatusLabel(val title: String, val detail: String, val color: Color)

fun ConnectionState.label(): StatusLabel = when (this) {
    ConnectionState.Disconnected ->
        StatusLabel("Not connected", "Select the paired HC-05 to connect.", StatusColors.Neutral)
    is ConnectionState.Connecting ->
        StatusLabel("Connecting…", "Opening RFCOMM to ${device.name}", StatusColors.Busy)
    is ConnectionState.Reconnecting ->
        StatusLabel("Reconnecting…", "Attempt $attempt of $maxAttempts", StatusColors.Busy)
    is ConnectionState.Connected -> {
        val w = warning
        when {
            mcuVerified ->
                StatusLabel("Connected", "${device.name}: lock controller is responding.", StatusColors.Good)
            w != null ->
                StatusLabel("Link open, no response", w.message, StatusColors.Warn)
            else ->
                StatusLabel("Link open", "Waiting for the lock controller to answer…", StatusColors.Busy)
        }
    }
    is ConnectionState.Failed ->
        StatusLabel("Connection error", error.message + (detail?.let { "\n($it)" } ?: ""), StatusColors.Bad)
}

fun McuStatus?.label(connected: Boolean): StatusLabel = when (this) {
    McuStatus.LOCKED -> StatusLabel("Locked", "The door is locked.", StatusColors.Good)
    McuStatus.WAITING_PIN -> StatusLabel("Waiting for PIN", "Enter the PIN on the door keypad, then press #.", StatusColors.Busy)
    McuStatus.UNLOCKED -> StatusLabel("Unlocked", "The door relocks automatically.", StatusColors.Warn)
    McuStatus.ACCESS_DENIED -> StatusLabel("Access denied", "Voice or PIN rejected, or the keypad is temporarily locked out.", StatusColors.Bad)
    null -> if (connected) {
        StatusLabel("Unknown", "Press Refresh status.", StatusColors.Neutral)
    } else {
        StatusLabel("Unknown", "Not connected. The lock stays locked unless the keypad PIN was already accepted.", StatusColors.Neutral)
    }
}

fun VoiceAuthState.label(): StatusLabel = when (this) {
    VoiceAuthState.MODEL_NOT_INSTALLED -> StatusLabel(
        "Not available yet",
        "The ECAPA-TDNN speaker-verification model is added in Stage 4.",
        StatusColors.Neutral,
    )
}
