package com.aziitech.simregmonitor

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class SmsDailyRow(val code: String, val latest: SmsCheckerHistoryEvent, val byDate: Map<String, SmsCheckerHistoryEvent>)

@Composable
fun SmsCheckerHistoryDialog(store: SmsCheckerDailyHistoryStore, title: String, initialDeviceCode: String? = null, onDismiss: () -> Unit) {
    val events = remember(store) { store.allDailySnapshots() }
    val dates = remember(events) { events.map { it.activityDate }.distinct().sorted() }
    val rows = remember(events) {
        events.groupBy { canonicalDeviceCode(it.record.code) }.map { (code, entries) ->
            SmsDailyRow(code, entries.maxByOrNull { it.completedAt }!!, entries.associateBy { it.activityDate })
        }.sortedBy { it.code }
    }
    var search by remember(initialDeviceCode) { mutableStateOf(initialDeviceCode.orEmpty()) }
    var status by remember { mutableStateOf("All") }
    var dateFilter by remember { mutableStateOf("All dates") }
    var dateMenu by remember { mutableStateOf(false) }
    val shownDates = if (dateFilter == "All dates") dates else listOf(dateFilter)
    val visible = rows.filter { row ->
        val s = row.latest.record.status
        row.code.contains(search.trim(), true) &&
            (dateFilter == "All dates" || row.byDate.containsKey(dateFilter)) &&
            when (status) {
                "Success" -> s == AttemptStatus.SUCCESS
                "Not found" -> s == AttemptStatus.NOT_FOUND
                "Failed" -> s in setOf(AttemptStatus.TIMEOUT, AttemptStatus.SEND_FAILED, AttemptStatus.UNRECOGNIZED)
                else -> true
            }
    }
    val widths = listOf(172.dp, 100.dp, 74.dp, 74.dp, 74.dp, 74.dp, 128.dp)
    val headers = listOf("DEVICE / TRADE CODE", "LATEST STATUS", "MTD", "ACTIVE", "BAR 48", "BAR 96", "LAST CHECK")

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text("Daily SMS Checker report • latest return saved per device per day • ${dates.size} date(s)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("Back") }
                }
                OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                    label = { Text("Search device ID / trade code") },
                    trailingIcon = { if (search.isNotBlank()) TextButton(onClick = { search = "" }) { Text("Clear") } })
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf("All", "Success", "Not found", "Failed").forEach {
                        FilterChip(selected = status == it, onClick = { status = it }, label = { Text(it, style = MaterialTheme.typography.labelSmall) })
                    }
                    TextButton(onClick = { dateMenu = true }) { Text(dateFilter) }
                    DropdownMenu(expanded = dateMenu, onDismissRequest = { dateMenu = false }) {
                        DropdownMenuItem(text = { Text("All dates") }, onClick = { dateFilter = "All dates"; dateMenu = false })
                        dates.asReversed().forEach { d -> DropdownMenuItem(text = { Text(d) }, onClick = { dateFilter = d; dateMenu = false }) }
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        headers.forEachIndexed { i, h -> SmsReportCell(h, widths[i], true) }
                        shownDates.forEach { SmsReportCell(it.takeLast(5), 76.dp, true) }
                    }
                }
                if (visible.isEmpty()) {
                    Surface(Modifier.fillMaxWidth().weight(1f), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .35f), shape = MaterialTheme.shapes.large) {
                        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.Center) {
                            Text(if (events.isEmpty()) "No SMS Checker daily results have been saved yet." else "No daily results match the selected search or filters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        items(visible, key = { it.code }) { row ->
                            val result = row.latest.record.result
                            val checked = SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(row.latest.completedAt))
                            Card(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(7.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    SmsReportCell(row.code, widths[0], bold = true)
                                    SmsReportCell(row.latest.record.status.name.replace('_', ' '), widths[1])
                                    SmsReportCell(result?.mtd?.toString() ?: "—", widths[2])
                                    SmsReportCell(result?.active?.toString() ?: "—", widths[3])
                                    SmsReportCell(result?.bar48?.toString() ?: "—", widths[4])
                                    SmsReportCell(result?.bar96?.toString() ?: "—", widths[5])
                                    SmsReportCell(checked, widths[6])
                                    shownDates.forEach { d -> SmsReportCell(row.byDate[d]?.record?.result?.today?.toString() ?: "—", 76.dp) }
                                }
                            }
                        }
                    }
                }
                Text("${visible.size} device(s) • Date columns contain the SMS return's Today value • A same-day recheck replaces that day's saved result • Current and previous month retained", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun SmsReportCell(value: String, width: Dp, header: Boolean = false, bold: Boolean = false) {
    Surface(Modifier.width(width), color = if (header) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface) {
        Text(value, Modifier.padding(horizontal = 6.dp, vertical = 9.dp), fontWeight = if (header || bold) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.labelSmall, maxLines = 2)
    }
}
