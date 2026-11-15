package com.aziitech.devmonitor.gateway

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import com.aziitech.devmonitor.gateway.ui.AuroraBlue
import com.aziitech.devmonitor.gateway.ui.AuroraCyan
import com.aziitech.devmonitor.gateway.ui.AuroraEmerald
import com.aziitech.devmonitor.gateway.ui.AuroraFloatingWaitOverlay
import com.aziitech.devmonitor.gateway.ui.AuroraGlassCard
import com.aziitech.devmonitor.gateway.ui.AuroraHero
import com.aziitech.devmonitor.gateway.ui.AuroraMetricCard
import com.aziitech.devmonitor.gateway.ui.AuroraNavy
import com.aziitech.devmonitor.gateway.ui.AuroraNavy2
import com.aziitech.devmonitor.gateway.ui.AuroraSectionHeader
import com.aziitech.devmonitor.gateway.ui.AuroraStatusPill
import com.aziitech.devmonitor.gateway.ui.AuroraViolet
import com.aziitech.devmonitor.gateway.ui.DevMonitorButton
import com.aziitech.devmonitor.gateway.ui.DevMonitorNavGlyph
import com.aziitech.devmonitor.gateway.ui.DevMonitorOutlinedButton
import com.aziitech.devmonitor.gateway.ui.fieldPulseBackground
import kotlinx.coroutines.launch

private data class GatewayNavigationItem(val label: String, val glyph: String, val tab: Int)

data class GatewaySimOption(
    val subscriptionId: Int,
    val slotIndex: Int,
    val carrierName: String,
    val ready: Boolean
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayHomeScreen(
    signedIn: Boolean,
    status: String,
    controllerUrl: String,
    gatewayDisplayName: String,
    gatewayId: String?,
    busy: Boolean,
    onControllerUrlChange: (String) -> Unit,
    onGatewayDisplayNameChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onVerifyToken: () -> Unit,
    onTestController: () -> Unit,
    onRegisterGateway: () -> Unit,
    workerState: String,
    batteryOptimizationIgnored: Boolean,
    smsPermissionsGranted: Boolean,
    phoneStatePermissionGranted: Boolean,
    activeSimCount: Int,
    readySimCount: Int,
    availableGatewaySims: List<GatewaySimOption>,
    selectedSimSubscriptionId: Int?,
    onSelectGatewaySim: (Int) -> Unit,
    onRequestSmsReadiness: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onStartWorker: () -> Unit,
    onStopWorker: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val navLabels = remember { listOf("Overview", "Queue", "Gateway", "Account") }
    val drawerItems = remember {
        listOf(
            GatewayNavigationItem("Overview", "home", 0),
            GatewayNavigationItem("Processing queue", "devices", 1),
            GatewayNavigationItem("This Gateway", "agents", 2),
            GatewayNavigationItem("Account & security", "settings", 3)
        )
    }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        scrimColor = Color.Black.copy(alpha = .62f),
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = AuroraNavy2,
                drawerContentColor = Color.White
            ) {
                Column(
                    Modifier.fillMaxWidth().background(
                        Brush.linearGradient(listOf(AuroraNavy, Color(0xFF0B3560), Color(0xFF123D3A)))
                    ).padding(horizontal = 18.dp, vertical = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Surface(
                            modifier = Modifier.size(48.dp),
                            shape = RoundedCornerShape(15.dp),
                            color = Color.White.copy(alpha = .10f)
                        ) {
                            Image(
                                painter = painterResource(com.aziitech.devmonitor.gateway.R.drawable.devmonitor_icon),
                                contentDescription = "DevMonitor",
                                modifier = Modifier.padding(6.dp)
                            )
                        }
                        Column {
                            Text("DevMonitor", color = Color.White, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleLarge)
                            Text("GATEWAY EDITION", color = AuroraCyan, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelSmall, letterSpacing = 1.1.sp)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    AuroraStatusPill(if (signedIn) "ACCOUNT AUTHENTICATED" else "SIGN-IN REQUIRED", if (signedIn) AuroraEmerald else AuroraCyan)
                    Text("Gateway workspace", color = Color.White.copy(alpha = .65f), style = MaterialTheme.typography.bodySmall)
                }
                Column(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text("WORKSPACE", color = AuroraCyan, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.3.sp, modifier = Modifier.padding(start = 14.dp, bottom = 5.dp))
                    drawerItems.forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(item.label, fontWeight = if (selectedTab == item.tab) FontWeight.Bold else FontWeight.Medium) },
                            selected = selectedTab == item.tab,
                            onClick = {
                                selectedTab = item.tab
                                scope.launch { drawerState.close() }
                            },
                            icon = { DevMonitorNavGlyph(item.glyph, selectedTab == item.tab) },
                            colors = NavigationDrawerItemDefaults.colors(
                                selectedContainerColor = AuroraBlue.copy(alpha = .20f),
                                selectedIconColor = AuroraCyan,
                                selectedTextColor = Color.White,
                                unselectedContainerColor = Color.Transparent,
                                unselectedIconColor = Color(0xFF9FB0C7),
                                unselectedTextColor = Color(0xFFD5E0ED)
                            )
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("DEVMonitor 5.0", color = AuroraCyan, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Text("Dedicated Gateway phone", color = Color.White.copy(alpha = .62f), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    ) {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Surface(
                                modifier = Modifier.size(38.dp),
                                shape = RoundedCornerShape(12.dp),
                                color = Color.Transparent
                            ) {
                                Box(
                                    Modifier.background(Brush.linearGradient(listOf(AuroraBlue, AuroraCyan))),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Image(
                                        painter = painterResource(com.aziitech.devmonitor.gateway.R.drawable.devmonitor_icon),
                                        contentDescription = "DevMonitor",
                                        modifier = Modifier.size(30.dp)
                                    )
                                }
                            }
                            Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                                Text("DevMonitor", color = Color.White, fontWeight = FontWeight.ExtraBold)
                                Text("GATEWAY EDITION", color = AuroraCyan, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                        }
                    },
                    navigationIcon = {
                        TextButton(onClick = { scope.launch { drawerState.open() } }) {
                            Text("☰", color = Color.White, style = MaterialTheme.typography.titleLarge)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = AuroraNavy.copy(alpha = .98f),
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White
                    )
                )
            },
            bottomBar = {
                NavigationBar(containerColor = AuroraNavy, contentColor = Color.White, tonalElevation = 0.dp) {
                    navLabels.forEachIndexed { index, label ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = {
                                val glyph = when (index) {
                                    0 -> "home"
                                    1 -> "devices"
                                    2 -> "agents"
                                    else -> "settings"
                                }
                                DevMonitorNavGlyph(glyph, selectedTab == index)
                            },
                            label = { Text(label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.White,
                                selectedTextColor = Color.White,
                                indicatorColor = AuroraBlue.copy(alpha = .24f),
                                unselectedIconColor = Color(0xFF9FB0C7),
                                unselectedTextColor = Color(0xFF9FB0C7)
                            )
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                Modifier.fillMaxSize()
                    .background(fieldPulseBackground(dark = true))
                    .padding(padding)
            ) {
                AnimatedContent(
                    targetState = selectedTab,
                    label = "gateway-screen-transition",
                    transitionSpec = {
                        (fadeIn() + slideInHorizontally { it / 12 }) togetherWith
                            (fadeOut() + slideOutHorizontally { -it / 12 })
                    }
                ) { tab ->
                    Column(
                        Modifier.fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        when (tab) {
                            0 -> GatewayOverviewContent(
                                signedIn, status, controllerUrl, gatewayDisplayName, gatewayId, busy,
                                onControllerUrlChange, onGatewayDisplayNameChange, onSignIn,
                                onVerifyToken, onTestController, onRegisterGateway
                            )
                            1 -> GatewayQueueContent()
                            2 -> GatewayPhoneContent(workerState, gatewayId, controllerUrl, batteryOptimizationIgnored, smsPermissionsGranted, phoneStatePermissionGranted, activeSimCount, readySimCount, availableGatewaySims, selectedSimSubscriptionId, onSelectGatewaySim, onRequestSmsReadiness, onOpenBatterySettings, onStartWorker, onStopWorker)
                            else -> GatewayAccountContent(signedIn, gatewayId, busy, onVerifyToken)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
                if (busy) {
                    Box(
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 12.dp)
                    ) {
                        AuroraFloatingWaitOverlay(
                            title = "Working securely…",
                            message = "DevMonitor is completing your requested operation."
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GatewayOverviewContent(
    signedIn: Boolean,
    status: String,
    controllerUrl: String,
    gatewayDisplayName: String,
    gatewayId: String?,
    busy: Boolean,
    onControllerUrlChange: (String) -> Unit,
    onGatewayDisplayNameChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onVerifyToken: () -> Unit,
    onTestController: () -> Unit,
    onRegisterGateway: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AuroraHero(
            title = "Gateway service",
            value = if (signedIn) "Connected" else "Not connected",
            subtitle = if (signedIn) "Your Gateway identity is authenticated." else "Sign in to prepare this phone as a Gateway.",
            meta = "Dedicated SMS processing • Secure account ownership",
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            AuroraMetricCard(
                title = "AUTHENTICATION",
                value = if (signedIn) "Ready" else "Pending",
                subtitle = "Firebase account",
                accent = if (signedIn) AuroraEmerald else AuroraBlue,
                modifier = Modifier.weight(1f)
            )
            AuroraMetricCard(
                title = "WORKER",
                value = "Not started",
                subtitle = "SMS service not active",
                accent = AuroraCyan,
                modifier = Modifier.weight(1f)
            )
        }
        AuroraSectionHeader(
            eyebrow = "Gateway setup",
            title = "Secure your Gateway",
            subtitle = "Authenticate this dedicated phone before registering it with the controller."
        )
        AuroraGlassCard(Modifier.fillMaxWidth(), AuroraBlue) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Google account", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                AuroraStatusPill(if (signedIn) "SIGNED IN" else "NOT SIGNED IN", if (signedIn) AuroraEmerald else AuroraCyan)
            }
            Text(
                if (signedIn) "Firebase authentication is active. You can verify token retrieval without exposing the token."
                else "Use the Google account that owns this Gateway. Authentication is handled securely by Firebase.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
            DevMonitorButton(onClick = onSignIn, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                Text(if (signedIn) "Switch Google account" else "Sign in with Google")
            }
            DevMonitorOutlinedButton(onClick = onVerifyToken, modifier = Modifier.fillMaxWidth(), enabled = signedIn && !busy) {
                Text("Verify secure sign-in token")
            }
        }
        AuroraSectionHeader(
            eyebrow = "Controller connection",
            title = "Register this Gateway phone",
            subtitle = "Create this phone's separate worker identity using your signed-in Firebase account."
        )
        AuroraGlassCard(Modifier.fillMaxWidth(), AuroraCyan) {
            Text("Controller endpoint", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            androidx.compose.material3.OutlinedTextField(
                value = controllerUrl,
                onValueChange = onControllerUrlChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("HTTPS base URL") },
                placeholder = { Text("https://controller.example") },
                enabled = gatewayId == null && !busy
            )
            if (gatewayId != null) {
                Text("This endpoint is pinned to the registered Gateway identity. Changing it requires a controlled identity recovery.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            androidx.compose.material3.OutlinedTextField(
                value = gatewayDisplayName,
                onValueChange = onGatewayDisplayNameChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                label = { Text("Gateway phone name") },
                placeholder = { Text("e.g. Office Gateway 1") },
                enabled = gatewayId == null && !busy
            )
            DevMonitorButton(
                onClick = onRegisterGateway,
                modifier = Modifier.fillMaxWidth(),
                enabled = signedIn && gatewayId == null && controllerUrl.isNotBlank() && gatewayDisplayName.isNotBlank() && !busy
            ) {
                Text(if (gatewayId == null) "Register this Gateway phone" else "Gateway registered")
            }
            DevMonitorOutlinedButton(
                onClick = onTestController,
                modifier = Modifier.fillMaxWidth(),
                enabled = signedIn && controllerUrl.isNotBlank() && !busy
            ) {
                Text("Test authenticated connection")
            }
        }
        AuroraGlassCard(Modifier.fillMaxWidth(), AuroraEmerald) {
            Text("Current status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun GatewayQueueContent() {
    AuroraSectionHeader("Processing queue", "Gateway queue", "Queue visibility will appear here when the controller is connected.")
    AuroraHero("Assigned work", "0 jobs", "No jobs are currently assigned to this phone.", "Live queue is not active yet.")
    AuroraGlassCard(Modifier.fillMaxWidth(), AuroraBlue) {
        AuroraStatusPill("CONTROLLER NOT CONNECTED", AuroraCyan)
        Text("The central FIFO queue, job leases, retry state and visible queue position will be shown here after controller integration.", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun GatewayPhoneContent(
    workerState: String,
    gatewayId: String?,
    controllerUrl: String,
    batteryOptimizationIgnored: Boolean,
    smsPermissionsGranted: Boolean,
    phoneStatePermissionGranted: Boolean,
    activeSimCount: Int,
    readySimCount: Int,
    availableGatewaySims: List<GatewaySimOption>,
    selectedSimSubscriptionId: Int?,
    onSelectGatewaySim: (Int) -> Unit,
    onRequestSmsReadiness: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    onStartWorker: () -> Unit,
    onStopWorker: () -> Unit
) {
    val active = workerState == "RUNNING" || workerState == "PAUSED" || workerState == "STARTING"
    AuroraSectionHeader("Phone availability", "Gateway status", "A user-controlled foreground service keeps the controller heartbeat visible.")
    AuroraGlassCard(Modifier.fillMaxWidth(), AuroraEmerald) {
        AuroraStatusPill(
            when (workerState) {
                "RUNNING" -> "SERVICE ACTIVE"
                "PAUSED" -> "HEARTBEAT PAUSED"
                "STARTING" -> "STARTING"
                else -> "WORKER STOPPED"
            },
            if (workerState == "RUNNING") AuroraEmerald else AuroraCyan
        )
        Text("Service state: $workerState", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            "This stage sends controller health heartbeats only. SMS permission, SIM readiness, job claiming, SMS sending and SMS receiving are deliberately disabled.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DevMonitorButton(
            onClick = onStartWorker,
            modifier = Modifier.fillMaxWidth(),
            enabled = gatewayId != null && controllerUrl.startsWith("https://", ignoreCase = true) && !active
        ) { Text("Start Gateway heartbeat service") }
        DevMonitorOutlinedButton(
            onClick = onStopWorker,
            modifier = Modifier.fillMaxWidth(),
            enabled = active
        ) { Text("Stop Gateway service") }
        Text(
            "Pause, resume and stop controls are also available in the persistent notification while the service is active.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
    AuroraGlassCard(Modifier.fillMaxWidth(), AuroraCyan) {
        Text("SMS and SIM readiness", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AuroraStatusPill(
            when {
                !smsPermissionsGranted -> "SMS PERMISSIONS NEEDED"
                !phoneStatePermissionGranted -> "PHONE STATE PERMISSION NEEDED"
                activeSimCount == 0 -> "NO ACTIVE SIM DETECTED"
                readySimCount == 0 -> "SIM NOT READY"
                else -> "PERMISSIONS + SIM READY"
            },
            if (smsPermissionsGranted && phoneStatePermissionGranted && activeSimCount > 0 && readySimCount > 0) AuroraEmerald else AuroraCyan
        )
        Text("SMS send/receive permissions: ${if (smsPermissionsGranted) "granted" else "not granted"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Phone-state permission: ${if (phoneStatePermissionGranted) "granted" else "not granted"}", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Active SIM subscriptions: $activeSimCount • SIMs reporting READY: $readySimCount", color = MaterialTheme.colorScheme.onSurfaceVariant)
        var simMenuExpanded by remember { mutableStateOf(false) }
        val selectedSim = availableGatewaySims.firstOrNull { it.subscriptionId == selectedSimSubscriptionId }
        Text("SIM for Gateway SMS", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Box(Modifier.fillMaxWidth()) {
            DevMonitorOutlinedButton(
                onClick = { simMenuExpanded = true },
                modifier = Modifier.fillMaxWidth(),
                enabled = availableGatewaySims.any { it.ready }
            ) {
                Text(selectedSim?.let { "SIM ${it.slotIndex + 1} • ${it.carrierName}" } ?: "Choose a SIM")
            }
            androidx.compose.material3.DropdownMenu(
                expanded = simMenuExpanded,
                onDismissRequest = { simMenuExpanded = false }
            ) {
                availableGatewaySims.forEach { sim ->
                    androidx.compose.material3.DropdownMenuItem(
                        text = {
                            Text("SIM ${sim.slotIndex + 1} • ${sim.carrierName}${if (sim.ready) " • READY" else " • NOT READY"}")
                        },
                        onClick = {
                            simMenuExpanded = false
                            if (sim.ready) onSelectGatewaySim(sim.subscriptionId)
                        },
                        enabled = sim.ready
                    )
                }
            }
        }
        if (selectedSim == null) {
            Text("No SIM is selected. Choose a ready SIM before Gateway SMS work can be enabled. The app will not choose a line automatically.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            AuroraStatusPill("SELECTED • SIM ${selectedSim.slotIndex + 1}", if (selectedSim.ready) AuroraEmerald else AuroraCyan)
            Text("${selectedSim.carrierName} is selected. If this subscription disappears or changes, the selection is cleared and the Gateway must be paused for a new choice.", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        DevMonitorOutlinedButton(onClick = onRequestSmsReadiness, modifier = Modifier.fillMaxWidth()) {
            Text(if (smsPermissionsGranted && phoneStatePermissionGranted) "Refresh SMS and SIM readiness" else "Review SMS permissions")
        }
        Text(
            "Permissions are requested only after you tap this button. This step does not send or receive messages. Actual SMS transport, sender verification and transaction correlation are still disabled. Controller heartbeat readiness remains false until that transport is implemented and tested.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
    AuroraGlassCard(Modifier.fillMaxWidth(), AuroraBlue) {
        Text("Battery optimisation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        AuroraStatusPill(
            if (batteryOptimizationIgnored) "NOT RESTRICTED" else "BATTERY OPTIMISATION ON",
            if (batteryOptimizationIgnored) AuroraEmerald else AuroraCyan
        )
        Text(
            if (batteryOptimizationIgnored)
                "Android reports that this app is exempt from standard battery optimisation. Device-manufacturer background limits may still apply."
            else
                "Battery optimisation may delay background work or heartbeat delivery when the phone is idle. For a dedicated Gateway phone, review Android's battery settings and decide whether to exempt this app.",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        DevMonitorOutlinedButton(
            onClick = onOpenBatterySettings,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Open battery optimisation settings") }
        Text(
            "This opens Android's settings list; you make the choice yourself. It does not grant an exemption automatically or guarantee uninterrupted operation.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun GatewayAccountContent(signedIn: Boolean, gatewayId: String?, busy: Boolean, onVerifyToken: () -> Unit) {
    AuroraSectionHeader("Account", "Gateway ownership", "Your Gateway identity and security controls.")
    AuroraGlassCard(Modifier.fillMaxWidth(), AuroraViolet) {
        AuroraStatusPill(if (gatewayId != null) "REGISTERED" else if (signedIn) "AUTHENTICATED — NOT REGISTERED" else "SIGN-IN REQUIRED", if (gatewayId != null) AuroraEmerald else AuroraCyan)
        Text("Gateway ID", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        Text(gatewayId ?: "Not registered", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("The private controller credential is encrypted with Android Keystore and is never displayed. This phone will only receive jobs authorized for its registered identity.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        DevMonitorOutlinedButton(onClick = onVerifyToken, modifier = Modifier.fillMaxWidth(), enabled = signedIn && !busy) {
            Text("Verify account session")
        }
    }
}
