package com.aziitech.simregmonitor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.flow.collectLatest
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

private data class SmsDailyRow(val code: String, val byDate: Map<String, SmsCheckerHistoryEvent>)
private data class SmsHistoryColumn(val key: String, val label: String, val width: Dp)

@Composable
fun SmsCheckerHistoryDialog(
    store: SmsCheckerDailyHistoryStore,
    title: String,
    initialDeviceCode: String? = null,
    onDismiss: () -> Unit
) {
    var refreshNonce by remember { mutableIntStateOf(0) }
    val events = remember(store, refreshNonce) { store.allDailySnapshots() }
    val allDates = remember(events) { events.map { it.activityDate }.distinct().sorted() }
    val currentMonth = YearMonth.now().toString()
    val previousMonth = YearMonth.now().minusMonths(1).toString()

    var query by remember(initialDeviceCode) { mutableStateOf(initialDeviceCode.orEmpty()) }
    var statusFilter by remember { mutableStateOf("All") }
    var dateScope by remember { mutableStateOf("All retained") }
    var dateMenu by remember { mutableStateOf(false) }
    var rowView by remember { mutableStateOf("All") }
    var selectedCode by remember { mutableStateOf<String?>(null) }
    var tableZoom by remember { mutableFloatStateOf(1f) }
    var showViewOptions by remember { mutableStateOf(false) }
    var showSummary by remember { mutableStateOf(false) }
    var showHierarchy by remember { mutableStateOf(false) }
    var additionalRowsToShow by remember { mutableIntStateOf(0) }

    val visibleDates = remember(allDates, dateScope, currentMonth, previousMonth) {
        when (dateScope) {
            "This month" -> allDates.filter { it.startsWith(currentMonth) }
            "Previous month" -> allDates.filter { it.startsWith(previousMonth) }
            else -> allDates
        }
    }
    val rows = remember(events) {
        events.groupBy { canonicalDeviceCode(it.record.code) }
            .map { (code, entries) -> SmsDailyRow(code, entries.associateBy { it.activityDate }) }
            .sortedBy { it.code.uppercase(Locale.US) }
    }

    val fixedColumns = listOf(
        SmsHistoryColumn("DEVICE", "DEVICE / TRADE CODE", 178.dp),
        SmsHistoryColumn("STATUS", "LATEST STATUS", 116.dp),
        SmsHistoryColumn("TYPE", "TYPE", 86.dp),
        SmsHistoryColumn("LAST_CHECK", "LAST CHECK", 128.dp)
    )
    val summaryColumns = listOf(
        SmsHistoryColumn("TODAY_TOTAL", "TODAY TOTAL", 100.dp),
        SmsHistoryColumn("MTD", "MTD", 82.dp),
        SmsHistoryColumn("ACTIVE", "ACTIVE", 82.dp),
        SmsHistoryColumn("BAR48", "BAR 48", 82.dp),
        SmsHistoryColumn("BAR96", "BAR 96", 82.dp),
        SmsHistoryColumn("ATTEMPT", "ATTEMPT", 82.dp),
        SmsHistoryColumn("RESPONSES", "RESPONSES", 92.dp),
        SmsHistoryColumn("ERROR", "ERROR", 210.dp)
    )
    val dateColumns = visibleDates.map { date ->
        SmsHistoryColumn("DATE:$date", runCatching {
            LocalDate.parse(date).format(DateTimeFormatter.ofPattern("MMM dd", Locale.getDefault()))
        }.getOrDefault(date), 78.dp)
    }
    val allColumns = fixedColumns + dateColumns + summaryColumns
    val defaultVisibility = remember(allColumns) { allColumns.associate { it.key to true } }
    var columnVisibility by remember(allColumns) { mutableStateOf(defaultVisibility) }
    val visibleFixed = fixedColumns.filter { columnVisibility[it.key] != false }
    val visibleDaily = dateColumns.filter { columnVisibility[it.key] != false }
    val visibleSummary = summaryColumns.filter { columnVisibility[it.key] != false }

    fun latestInScope(row: SmsDailyRow): SmsCheckerHistoryEvent? =
        row.byDate.filterKeys { it in visibleDates }.values.maxByOrNull { it.completedAt }
    fun todayTotal(row: SmsDailyRow): Int =
        row.byDate.filterKeys { it in visibleDates }.values.sumOf { it.record.result?.today ?: 0 }

    val filteredRows = remember(rows, visibleDates, query, statusFilter, rowView) {
        rows.mapNotNull { row ->
            val scoped = row.byDate.filterKeys { it in visibleDates }.values
            val latest = scoped.maxByOrNull { it.completedAt } ?: return@mapNotNull null
            val total = scoped.sumOf { it.record.result?.today ?: 0 }
            val searchable = listOf(row.code, latest.record.status.name, latest.record.type.name,
                latest.record.error.orEmpty(), latest.record.result?.raw.orEmpty())
                .any { it.contains(query.trim(), ignoreCase = true) }
            val statusOk = when (statusFilter) {
                "Success" -> latest.record.status == AttemptStatus.SUCCESS
                "Not found" -> latest.record.status == AttemptStatus.NOT_FOUND
                "Failed" -> latest.record.status in setOf(AttemptStatus.TIMEOUT, AttemptStatus.SEND_FAILED, AttemptStatus.UNRECOGNIZED)
                else -> true
            }
            val activityOk = when (rowView) { "Activity" -> total > 0; "Zero" -> total == 0; else -> true }
            if (searchable && statusOk && activityOk) row else null
        }.sortedBy { it.code.uppercase(Locale.US) }
    }

    val listState = rememberLazyListState()
    val tableScroll = rememberScrollState()
    val showShortCode by remember { derivedStateOf { tableScroll.value > 8 } }
    var tableHeaderIndex by remember { mutableIntStateOf(-1) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "sms_history_table_header" }?.index }
            .collectLatest { if (it != null) tableHeaderIndex = it }
    }
    val renderedRows = filteredRows.take((100 + additionalRowsToShow).coerceAtMost(filteredRows.size))
    val successes = filteredRows.count { latestInScope(it)?.record?.status == AttemptStatus.SUCCESS }
    val notFound = filteredRows.count { latestInScope(it)?.record?.status == AttemptStatus.NOT_FOUND }
    val failed = filteredRows.count { latestInScope(it)?.record?.status in setOf(AttemptStatus.TIMEOUT, AttemptStatus.SEND_FAILED, AttemptStatus.UNRECOGNIZED) }
    val totalToday = filteredRows.sumOf(::todayTotal)
    val latestMtd = filteredRows.sumOf { latestInScope(it)?.record?.result?.mtd ?: 0 }
    val latestActive = filteredRows.sumOf { latestInScope(it)?.record?.result?.active ?: 0 }

    val tableGesture = Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            var previousDistance = 0f
            while (true) {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    val a = pressed[0].position
                    val b = pressed[1].position
                    val dx = b.x - a.x
                    val dy = b.y - a.y
                    val distance = sqrt(dx * dx + dy * dy)
                    if (previousDistance > 0f && distance > 0f) {
                        val scale = distance / previousDistance
                        if (scale.isFinite() && scale != 1f) tableZoom = (tableZoom * scale).coerceIn(0.6f, 2f)
                    }
                    previousDistance = distance
                    pressed.forEach { it.consume() }
                } else previousDistance = 0f
                if (event.changes.none { it.pressed }) break
            }
        }
    }

    if (showViewOptions) {
        AlertDialog(
            onDismissRequest = { showViewOptions = false },
            title = { Text("Customize history table") },
            text = {
                Column(Modifier.heightIn(max = 540.dp)) {
                    Column(Modifier.weight(1f, false).verticalScroll(rememberScrollState())) {
                        Text("Columns", fontWeight = FontWeight.Bold)
                        allColumns.forEach { column ->
                            Row(Modifier.fillMaxWidth().clickable {
                                columnVisibility = columnVisibility.toMutableMap().apply { put(column.key, !(this[column.key] ?: true)) }
                            }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(columnVisibility[column.key] != false, { checked ->
                                    columnVisibility = columnVisibility.toMutableMap().apply { put(column.key, checked) }
                                })
                                Text(column.label)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Rows", fontWeight = FontWeight.Bold)
                        listOf("All", "Activity", "Zero").forEach { option ->
                            Row(Modifier.fillMaxWidth().clickable { rowView = option }.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(rowView == option, { rowView = option })
                                Text(when (option) { "Activity" -> "Only rows with Today activity"; "Zero" -> "Only zero-activity rows"; else -> "All rows" })
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showViewOptions = false }) { Text("Done") } },
            dismissButton = { TextButton(onClick = {
                columnVisibility = defaultVisibility
                rowView = "All"
                showViewOptions = false
            }) { Text("Reset view") } }
        )
    }

    if (showSummary) {
        AlertDialog(
            onDismissRequest = { showSummary = false },
            title = { Text("SMS Checker history summary") },
            text = {
                Column(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Scope: $dateScope • ${filteredRows.size} device(s)", fontWeight = FontWeight.Bold)
                    Text("Success: $successes")
                    Text("Not found: $notFound")
                    Text("Failed / timed out: $failed")
                    Divider()
                    Text("Sum of saved daily Today values: $totalToday")
                    Text("Latest MTD values: $latestMtd")
                    Text("Latest Active values: $latestActive")
                    Text("Daily columns contain each day's saved Today value. MTD and Active are latest snapshots, not added across days.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { showSummary = false }) { Text("Done") } }
        )
    }

    if (showHierarchy) {
        AlertDialog(
            onDismissRequest = { showHierarchy = false },
            title = { Text("History breakdown") },
            text = {
                Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Scope: $dateScope • ${filteredRows.size} device(s)", style = MaterialTheme.typography.bodySmall)
                    Text("Latest status", fontWeight = FontWeight.Bold)
                    filteredRows.groupBy { latestInScope(it)?.record?.status?.name?.replace('_', ' ') ?: "Unknown" }
                        .entries.sortedByDescending { it.value.size }.forEach { (label, entries) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, Modifier.weight(1f)); Text("${entries.size} device(s)")
                            }
                        }
                    Text("Query type", fontWeight = FontWeight.Bold)
                    filteredRows.groupBy { latestInScope(it)?.record?.type?.name ?: "Unknown" }
                        .entries.sortedByDescending { it.value.size }.forEach { (label, entries) ->
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(label, Modifier.weight(1f)); Text("${entries.size} device(s)")
                            }
                        }
                }
            },
            confirmButton = { TextButton(onClick = { showHierarchy = false }) { Text("Done") } }
        )
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = true)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
                        Text("Two-month daily SMS report • one latest result per device per day • ${allDates.size} date(s)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onDismiss) { Text("Back") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = query, onValueChange = { query = it; additionalRowsToShow = 0 },
                        modifier = Modifier.weight(1f), singleLine = true,
                        label = { Text("Search device ID, trade code, status or error") },
                        trailingIcon = { if (query.isNotBlank()) TextButton(onClick = { query = ""; additionalRowsToShow = 0 }) { Text("Clear") } }
                    )
                    TextButton(onClick = { refreshNonce++ }) { Text("Refresh") }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
                    listOf("All", "Success", "Not found", "Failed").forEach { option ->
                        FilterChip(statusFilter == option, { statusFilter = option; additionalRowsToShow = 0 }, label = { Text(option, style = MaterialTheme.typography.labelSmall) })
                    }
                    Box {
                        TextButton(onClick = { dateMenu = true }) { Text(dateScope) }
                        DropdownMenu(dateMenu, { dateMenu = false }) {
                            listOf("All retained", "This month", "Previous month").forEach { option ->
                                DropdownMenuItem(text = { Text(option) }, onClick = { dateScope = option; dateMenu = false; additionalRowsToShow = 0 })
                            }
                        }
                    }
                }
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text("${filteredRows.size} devices", fontWeight = FontWeight.Bold)
                            Text("Success $successes • Not found $notFound • Failed $failed", style = MaterialTheme.typography.labelSmall)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text("Today $totalToday", fontWeight = FontWeight.Bold)
                            Text("MTD $latestMtd • Active $latestActive", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(onClick = { showSummary = true }, modifier = Modifier.weight(1f)) { Text("Summary") }
                    OutlinedButton(onClick = { showHierarchy = true }, modifier = Modifier.weight(1f)) { Text("Breakdown") }
                    OutlinedButton(onClick = { showViewOptions = true }, modifier = Modifier.weight(1f)) { Text("Customize View") }
                }
                if (selectedCode != null) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Selected: $selectedCode", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        TextButton(onClick = { selectedCode = null }) { Text("Clear selection") }
                    }
                }
                if (initialDeviceCode != null && query.isNotBlank()) {
                    TextButton(onClick = { query = ""; selectedCode = null }) { Text("Show all devices") }
                }

                Box(Modifier.fillMaxSize().then(tableGesture)) {
                    LazyColumn(
                        Modifier.fillMaxSize(), state = listState,
                        contentPadding = PaddingValues(bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        item(key = "sms_history_table_intro") {
                            Text("Excel-style history table. Device / trade code stays pinned while the rest scrolls horizontally. Pinch with two fingers to zoom; tap a row to select it.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        item(key = "sms_history_table_header") {
                            SmsHistoryTableHeader(visibleFixed, visibleDaily, visibleSummary, tableScroll, tableZoom)
                        }
                        items(renderedRows, key = { it.code }) { row ->
                            val dailyEvents = row.byDate.filterKeys { it in visibleDates }.values
                            val latest = dailyEvents.maxByOrNull { it.completedAt }
                            val focused = selectedCode == row.code
                            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                                visibleFixed.firstOrNull { it.key == "DEVICE" }?.let { deviceColumn ->
                                    SmsHistoryTableCell(if (showShortCode) canonicalDeviceCode(latest?.record?.code ?: row.code) else (latest?.record?.code ?: row.code), deviceColumn.width, focused, tableZoom, bold = true) { selectedCode = row.code }
                                }
                                Row(Modifier.weight(1f).horizontalScroll(tableScroll).clickable { selectedCode = row.code }) {
                                    visibleFixed.filterNot { it.key == "DEVICE" }.forEach { column ->
                                        val value = when (column.key) {
                                            "STATUS" -> latest?.record?.status?.name?.replace('_', ' ') ?: "—"
                                            "TYPE" -> latest?.record?.type?.name ?: "—"
                                            "LAST_CHECK" -> latest?.let { SimpleDateFormat("dd MMM HH:mm", Locale.getDefault()).format(Date(it.completedAt)) } ?: "—"
                                            else -> "—"
                                        }
                                        SmsHistoryTableCell(value, column.width, focused, tableZoom)
                                    }
                                    visibleDaily.forEach { column ->
                                        val date = column.key.removePrefix("DATE:")
                                        SmsHistoryTableCell(row.byDate[date]?.record?.result?.today?.toString() ?: "—", column.width, focused, tableZoom)
                                    }
                                    visibleSummary.forEach { column ->
                                        val result = latest?.record?.result
                                        val value = when (column.key) {
                                            "TODAY_TOTAL" -> dailyEvents.sumOf { it.record.result?.today ?: 0 }.toString()
                                            "MTD" -> result?.mtd?.toString() ?: "—"
                                            "ACTIVE" -> result?.active?.toString() ?: "—"
                                            "BAR48" -> result?.bar48?.toString() ?: "—"
                                            "BAR96" -> result?.bar96?.toString() ?: "—"
                                            "ATTEMPT" -> latest?.record?.attempt?.toString() ?: "—"
                                            "RESPONSES" -> latest?.record?.responseCount?.toString() ?: "—"
                                            "ERROR" -> latest?.record?.error?.takeIf { it.isNotBlank() } ?: "—"
                                            else -> "—"
                                        }
                                        SmsHistoryTableCell(value, column.width, focused, tableZoom)
                                    }
                                }
                            }
                        }
                        if (renderedRows.size < filteredRows.size) {
                            item(key = "sms_history_show_more") {
                                Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text("${filteredRows.size - renderedRows.size} more devices available", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Button(onClick = { additionalRowsToShow = (additionalRowsToShow + 100).coerceAtMost(filteredRows.size) }) { Text("Show more") }
                                }
                            }
                        }
                        item(key = "sms_history_footer") {
                            Text("${renderedRows.size} of ${filteredRows.size} device(s) • daily columns show each saved Today value • MTD / Active / bars show the latest return in the selected date scope • same-day rechecks replace that day's snapshot", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (tableHeaderIndex >= 0 && listState.firstVisibleItemIndex > tableHeaderIndex) {
                        Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(MaterialTheme.colorScheme.background)) {
                            SmsHistoryTableHeader(visibleFixed, visibleDaily, visibleSummary, tableScroll, tableZoom)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SmsHistoryTableHeader(
    fixed: List<SmsHistoryColumn>,
    daily: List<SmsHistoryColumn>,
    summary: List<SmsHistoryColumn>,
    tableScroll: androidx.compose.foundation.ScrollState,
    zoom: Float
) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            fixed.firstOrNull { it.key == "DEVICE" }?.let { SmsHistoryTableCell(it.label, it.width, false, zoom, header = true) }
            Row(Modifier.weight(1f).horizontalScroll(tableScroll), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                fixed.filterNot { it.key == "DEVICE" }.forEach { SmsHistoryTableCell(it.label, it.width, false, zoom, header = true) }
                daily.forEach { SmsHistoryTableCell(it.label, it.width, false, zoom, header = true) }
                summary.forEach { SmsHistoryTableCell(it.label, it.width, false, zoom, header = true) }
            }
        }
    }
}

@Composable
private fun SmsHistoryTableCell(
    value: String,
    width: Dp,
    focused: Boolean,
    zoom: Float,
    header: Boolean = false,
    bold: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    Surface(
        Modifier.width(width * zoom).heightIn(min = 44.dp * zoom).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = when {
            header -> MaterialTheme.colorScheme.surfaceVariant
            focused -> MaterialTheme.colorScheme.secondaryContainer
            else -> MaterialTheme.colorScheme.surface
        }
    ) {
        Text(value, Modifier.padding(horizontal = 7.dp, vertical = 8.dp),
            fontWeight = if (header || bold) FontWeight.Bold else FontWeight.Normal,
            style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
