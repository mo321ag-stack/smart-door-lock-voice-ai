package com.smartdoorlock.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.smartdoorlock.bluetooth.LogEntry
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp)) { content() }
    }
}

/** A labelled status row: small caption, coloured dot + title, explanation. */
@Composable
fun StatusCard(caption: String, label: StatusLabel, modifier: Modifier = Modifier, extra: (@Composable () -> Unit)? = null) {
    SectionCard(modifier) {
        Text(caption.uppercase(), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(label.color, CircleShape))
            Spacer(Modifier.width(10.dp))
            Text(label.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = label.color)
        }
        Spacer(Modifier.height(4.dp))
        Text(label.detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        extra?.invoke()
    }
}

/** One step of the pairing → RFCOMM → MCU checklist. */
@Composable
fun CheckRow(done: Boolean, text: String, pendingColor: Color = MaterialTheme.colorScheme.outline) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(if (done) "✓" else "○", color = if (done) com.smartdoorlock.app.ui.theme.StatusColors.Good else pendingColor, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(10.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

fun formatTime(millis: Long): String = timeFormat.format(Date(millis))

@Composable
fun TrafficLog(entries: List<LogEntry>, maxLines: Int = 30) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (entries.isEmpty()) {
            Text("No traffic yet.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        entries.takeLast(maxLines).reversed().forEach { e ->
            val (prefix, color) = when (e.kind) {
                LogEntry.Kind.TX -> "→" to MaterialTheme.colorScheme.primary
                LogEntry.Kind.RX -> "←" to com.smartdoorlock.app.ui.theme.StatusColors.Good
                LogEntry.Kind.INFO -> "·" to MaterialTheme.colorScheme.onSurfaceVariant
                LogEntry.Kind.ERROR -> "!" to com.smartdoorlock.app.ui.theme.StatusColors.Bad
            }
            Text(
                "${formatTime(e.timeMillis)} $prefix ${e.text}",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = color,
            )
        }
    }
}
