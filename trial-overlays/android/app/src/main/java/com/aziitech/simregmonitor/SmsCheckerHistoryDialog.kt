package com.aziitech.simregmonitor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SmsCheckerHistoryDialog(
    store: SmsCheckerDailyHistoryStore,
    title: String,
    initialDeviceCode: String? = null,
    onDismiss: () -> Unit
) {
    val dates = remember(store) { store.availableDates() }
    var selectedDate by remember(store) { mutableStateOf(dates.firstOrNull().orEmpty()) }
    var dateMenuExpanded by remember { mutableStateOf(false) }
    var search by remember(initialDeviceCode) { mutableStateOf(initialDeviceCode.orEmpty()) }
    var deviceView by remember(initialDeviceCode) { mutableStateOf(!initialDeviceCode.isNullOrBlank()) }
    var statusFilter by remember { mutableStateOf("All") }
    var expandedKey by remember { mutableStateOf<String?>(null) }

    val dayEvents = remember(store, selectedDate) {
        if (selectedDate.isBlank()) emptyList() else store.recordsForDate(selectedDate)
    }
    val deviceEvents = remember(store, search) {
        if (search.isBlank()) emptyList() else store.recordsForCode(search.trim())
    }
    val baseEvents = if (deviceView) deviceEvents else dayEvents.groupBy { it.record.code }
        .values.mapNotNull { it.maxByOrNull { event -> event.completedAt } }
        .sortedByDescending { it.completedAt }
    val visible = baseEvents.filter { event ->
        val matchesSearch = search.isBlank() || event.record.code.contains(search.trim(), true)
        val matchesStatus = when (statusFilter) {
            "Success" -> event.record.status == AttemptStatus.SUCCESS
            "Not found" -> event.record.status == AttemptStatus.NOT_FOUND
            "Timeout" -> event.record.status == AttemptStatus.TIMEOUT
            "Failed" -> event.record.status == AttemptStatus.SEND_FAILED || event.record.status == AttemptStatus.UNRECOGNIZED
            else -> true
        }
        matchesSearch && matchesStatus
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text("SMS Checker history • ${dates.size} saved day(s)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("Back") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = !deviceView, onClick = { deviceView = false; statusFilter = "All" }, label = { Text("Whole day") })
                    FilterChip(selected = deviceView, onClick = { deviceView = true; statusFilter = "All" }, label = { Text("Per device") })
                }
                if (!deviceView) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        TextButton(onClick = { dateMenuExpanded = true }, enabled = dates.isNotEmpty()) {
                            Text(if (selectedDate.isBlank()) "Choose date" else selectedDate)
                        }
                        DropdownMenu(expanded = dateMenuExpanded, onDismissRequest = { dateMenuExpanded = false }) {
                            dates.forEach { date -> DropdownMenuItem(text = { Text(date) }, onClick = { selectedDate = date; dateMenuExpanded = false }) }
                        }
                        Text("${dayEvents.size} attempts", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelMedium)
                    }
                }
                OutlinedTextField(
                    value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(),
                    singleLine = true, label = { Text("Find device / partner code") },
                    trailingIcon = { if (search.isNotBlank()) TextButton(onClick = { search = "" }) { Text("Clear") } }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("All", "Success", "Not found", "Timeout", "Failed").forEach { filter ->
                        FilterChip(selected = statusFilter == filter, onClick = { statusFilter = filter }, label = { Text(filter, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))) {
                    Row(Modifier.fillMaxWidth().padding(9.dp)) {
                        Text("DEVICE / CODE", Modifier.weight(1.35f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
                        Text("TIME", Modifier.weight(0.8f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
                        Text("RESULT", Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold)
                    }
                }
                if (visible.isEmpty()) {
                    Surface(Modifier.fillMaxWidth().weight(1f), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Center) {
                            Text(
                                when {
                                    deviceView && search.isBlank() -> "Open this page from a device's History button, or enter a device ID."
                                    deviceView -> "No saved SMS Checker activity for this code in the retained period."
                                    selectedDate.isBlank() -> "No SMS Checker history has been saved yet."
                                    else -> "No matching SMS Checker activity for $selectedDate."
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        items(visible, key = { "${it.completedAt}-${it.record.code}-${it.record.attempt}" }) { event ->
                            val key = "${event.completedAt}-${event.record.code}-${event.record.attempt}"
                            SmsCheckerHistoryTableRow(event, expandedKey == key) { expandedKey = if (expandedKey == key) null else key }
                        }
                    }
                }
                Text("Showing ${visible.size} row(s) • Tap a row for attempt details • Current and previous month retained", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SmsCheckerHistoryTableRow(event: SmsCheckerHistoryEvent, expanded: Boolean, onClick: () -> Unit) {
    val record = event.record
    val result = record.result
    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(event.completedAt))
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = if (expanded) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f) else MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 9.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1.35f)) {
                    Text(record.code, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Text(event.activityDate, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(time, Modifier.weight(0.8f), style = MaterialTheme.typography.labelSmall)
                Text(record.status.name.replace('_', ' '), Modifier.weight(0.9f), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            if (expanded) {
                Text("Attempt ${record.attempt} • Sent ${record.repetitionsSent} time(s)", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                if (result != null) Text(
                    listOfNotNull(result.today?.let { "Today $it" }, result.mtd?.let { "MTD $it" }, result.active?.let { "Active $it" }, result.bar48?.let { "B48 $it" }, result.bar96?.let { "B96 $it" }, result.duplicates?.let { "Duplicates $it" }, result.monthlyTarget?.let { "Target $it" }).joinToString(" • "),
                    style = MaterialTheme.typography.bodySmall
                )
                record.error?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                Text("Tap to collapse", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
