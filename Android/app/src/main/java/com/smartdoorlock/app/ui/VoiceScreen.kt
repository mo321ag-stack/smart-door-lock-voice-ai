package com.smartdoorlock.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.smartdoorlock.app.DoorLockViewModel

/**
 * Stage 3 version of the voice screen. Recording, enrollment and ECAPA-TDNN
 * verification are implemented in Stage 4. Until then this screen states
 * clearly that verification is unavailable: it never shows a fake result
 * and never sends VOICE_OK.
 */
@Composable
fun VoiceScreen(vm: DoorLockViewModel, modifier: Modifier = Modifier) {
    val connection by vm.connection.collectAsStateWithLifecycle()

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        StatusCard("Speaker verification", vm.voiceState.label())
        StatusCard("Bluetooth", connection.label())

        SectionCard {
            Text("How it will work", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            listOf(
                "1. Enroll: record several samples of the authorised speaker.",
                "2. Verify: record a new sample; the ECAPA-TDNN model extracts a speaker embedding.",
                "3. Compare it with the enrolled profile (cosine similarity vs. a calibrated threshold).",
                "4. Send VOICE_OK or VOICE_FAIL to the lock.",
                "5. On WAITING_PIN, type the PIN on the door keypad. The PIN never goes through the phone.",
            ).forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }

        SectionCard {
            Text("Required before Stage 4 can work", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "An ECAPA-TDNN ONNX model exported with the AI_Model scripts. No model is bundled, and the app will not report a verification result without one.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
