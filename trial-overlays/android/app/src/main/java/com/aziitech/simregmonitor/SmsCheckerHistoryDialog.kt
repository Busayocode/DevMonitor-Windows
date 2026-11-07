package com.aziitech.simregmonitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Surface
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SmsCheckerHistoryDialog(
    store: SmsCheckerDailyHistoryStore,
    title: String,
    onDismiss: () -> Unit
) {
    val dates = remember(store) { store.availableDates() }
    var selectedDate by remember(store) { mutableStateOf(dates.firstOrNull().orEmpty()) }
    var dateMenuExpanded by remember { mutableStateOf(false) }
    var deviceCode by remember { mutableStateOf("") }
    var deviceView by remember { mutableStateOf(false) }
    val dayActivityEvents = remember(store, selectedDate) {
        if (selectedDate.isBlank()) emptyList() else store.recordsForDate(selectedDate)
    }
    // Whole-day mode shows the final result for each code; per-device mode keeps every recheck.
    val dayEvents = remember(dayActivityEvents) {
        dayActivityEvents.groupBy { it.record.code }
            .values.mapNotNull { events -> events.maxByOrNull { it.completedAt } }
            .sortedByDescending { it.completedAt }
    }
    val deviceEvents = remember(store, deviceCode) {
        if (deviceCode.isBlank()) emptyList() else store.recordsForCode(deviceCode.trim())
    }
    val visibleEvents = if (deviceView) deviceEvents else dayEvents

    // Present history as a full-screen destination rather than a compact alert dialog.
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = true
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(onClick = onDismiss) { Text("← Back") }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text(
                            "SMS Checker history • ${dates.size} saved day(s)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !deviceView, onClick = { deviceView = false }, label = { Text("Whole day") })
                    FilterChip(selected = deviceView, onClick = { deviceView = true }, label = { Text("Per device") })
                }
                if (!deviceView) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = { dateMenuExpanded = true }, enabled = dates.isNotEmpty()) {
                            Text(if (selectedDate.isBlank()) "Choose a date" else selectedDate)
                        }
                        DropdownMenu(expanded = dateMenuExpanded, onDismissRequest = { dateMenuExpanded = false }) {
                            dates.forEach { date ->
                                DropdownMenuItem(
                                    text = { Text(date) },
                                    onClick = { selectedDate = date; dateMenuExpanded = false }
                                )
                            }
                        }
                    }
                    if (dayEvents.isNotEmpty()) {
                        val distinctCodes = dayEvents.size
                        val successes = dayEvents.count { it.record.status == AttemptStatus.SUCCESS }
                        val notFound = dayEvents.count { it.record.status == AttemptStatus.NOT_FOUND }
                        val problems = dayEvents.count { it.record.status == AttemptStatus.TIMEOUT || it.record.status == AttemptStatus.SEND_FAILED }
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text("$distinctCodes code(s) checked • ${dayActivityEvents.size} completed check(s)", fontWeight = FontWeight.Bold)
                                Text(
                                    "Latest results — Success: $successes   Not found: $notFound   Timeout/send failure: $problems",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = deviceCode,
                        onValueChange = { deviceCode = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("Device ID or partner trade code") },
                        placeholder = { Text("Enter the exact code") }
                    )
                    if (deviceCode.isNotBlank()) {
                        Text("${deviceEvents.size} completed check(s) in the retained period", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    }
                }
                if (visibleEvents.isEmpty()) {
                    Surface(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                        shape = MaterialTheme.shapes.large
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize().padding(20.dp),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                when {
                                    deviceView && deviceCode.isBlank() -> "Enter a device ID or partner trade code to see its history."
                                    deviceView -> "No saved SMS Checker activity for this code in the retained period."
                                    selectedDate.isBlank() -> "No SMS Checker history has been saved yet."
                                    else -> "No SMS Checker activity was saved for $selectedDate."
                                },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(visibleEvents, key = { "${it.completedAt}-${it.record.code}-${it.record.attempt}" }) { event ->
                            SmsCheckerHistoryEventCard(event)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmsCheckerHistoryEventCard(event: SmsCheckerHistoryEvent) {
    val record = event.record
    val result = record.result
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
    ) {
        Column(Modifier.padding(11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(record.code, modifier = Modifier.weight(1f), fontWeight = FontWeight.ExtraBold)
                Text(record.status.name.replace('_', ' '), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
            Text("${event.activityDate} • ${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.completedAt))} • Attempt ${record.attempt}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (result != null) {
                Text(
                    listOfNotNull(
                        result.today?.let { "Today $it" },
                        result.mtd?.let { "MTD $it" },
                        result.active?.let { "Active $it" },
                        result.bar48?.let { "Bar48 $it" },
                        result.bar96?.let { "Bar96 $it" },
                        result.duplicates?.let { "Duplicates $it" },
                        result.monthlyTarget?.let { "Target $it" }
                    ).joinToString("   •   "),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            record.error?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
