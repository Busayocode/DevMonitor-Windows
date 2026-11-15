package com.aziitech.devmonitor.gateway

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.aziitech.devmonitor.gateway.ui.AuroraBlue
import com.aziitech.devmonitor.gateway.ui.AuroraCyan
import com.aziitech.devmonitor.gateway.ui.AuroraEmerald
import com.aziitech.devmonitor.gateway.ui.AuroraGlassCard
import com.aziitech.devmonitor.gateway.ui.AuroraHero
import com.aziitech.devmonitor.gateway.ui.AuroraMetricCard
import com.aziitech.devmonitor.gateway.ui.AuroraNavy
import com.aziitech.devmonitor.gateway.ui.AuroraSectionHeader
import com.aziitech.devmonitor.gateway.ui.AuroraStatusPill
import com.aziitech.devmonitor.gateway.ui.DevMonitorButton
import com.aziitech.devmonitor.gateway.ui.DevMonitorLoadingState
import com.aziitech.devmonitor.gateway.ui.DevMonitorNavGlyph
import com.aziitech.devmonitor.gateway.ui.DevMonitorOutlinedButton
import com.aziitech.devmonitor.gateway.ui.fieldPulseBackground

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GatewayHomeScreen(
    signedIn: Boolean,
    status: String,
    controllerUrl: String,
    busy: Boolean,
    onControllerUrlChange: (String) -> Unit,
    onSignIn: () -> Unit,
    onVerifyToken: () -> Unit,
    onTestController: () -> Unit
) {
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    val navLabels = remember { listOf("Overview", "Queue", "Gateway", "Account") }

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
                            androidx.compose.foundation.layout.Box(
                                Modifier.background(Brush.linearGradient(listOf(AuroraBlue, AuroraCyan))),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("✦", color = Color.White, fontWeight = FontWeight.ExtraBold)
                            }
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                            Text("DevMonitor", color = Color.White, fontWeight = FontWeight.ExtraBold)
                            Text("GATEWAY EDITION", color = AuroraCyan, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        }
                    }
                },
                navigationIcon = {
                    TextButton(onClick = { selectedTab = 0 }) {
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
        Column(
            Modifier
                .fillMaxSize()
                .background(fieldPulseBackground(dark = true))
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            when (selectedTab) {
                0 -> {
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
                        title = "Connect to the Gateway controller",
                        subtitle = "Controller registration will be enabled after the backend is deployed."
                    )
                    AuroraGlassCard(Modifier.fillMaxWidth(), AuroraCyan) {
                        Text("Controller endpoint", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        androidx.compose.material3.OutlinedTextField(
                            value = controllerUrl,
                            onValueChange = onControllerUrlChange,
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("HTTPS base URL") },
                            placeholder = { Text("https://controller.example") }
                        )
                        DevMonitorButton(
                            onClick = onTestController,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = signedIn && controllerUrl.isNotBlank() && !busy
                        ) {
                            Text("Test authenticated connection")
                        }
                    }
                    if (busy) DevMonitorLoadingState(
                        title = "Connecting securely…",
                        message = "DevMonitor is completing the requested authentication or controller check."
                    )
                    AuroraGlassCard(Modifier.fillMaxWidth(), AuroraEmerald) {
                        Text("Current status", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(status, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                1 -> {
                    AuroraSectionHeader("Processing queue", "Gateway queue", "Queue visibility will appear here when the controller is connected.")
                    AuroraHero("Assigned work", "0 jobs", "No jobs are currently assigned to this phone.", "Live queue is not active yet.")
                    AuroraGlassCard(Modifier.fillMaxWidth(), AuroraBlue) {
                        AuroraStatusPill("CONTROLLER NOT CONNECTED", AuroraCyan)
                        Text("The central FIFO queue, job leases, retry state and visible queue position will be shown here after controller integration.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                2 -> {
                    AuroraSectionHeader("Phone availability", "Gateway status", "Controls for the dedicated worker phone.")
                    AuroraGlassCard(Modifier.fillMaxWidth(), AuroraEmerald) {
                        AuroraStatusPill("WORKER NOT RUNNING", AuroraCyan)
                        Text("SMS permissions, SIM readiness, network status and the foreground worker service will be presented here. No SMS is sent by this prototype.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                else -> {
                    AuroraSectionHeader("Account", "Gateway ownership", "Your Gateway identity and security controls.")
                    AuroraGlassCard(Modifier.fillMaxWidth(), AuroraViolet) {
                        AuroraStatusPill(if (signedIn) "AUTHENTICATED" else "SIGN-IN REQUIRED", if (signedIn) AuroraEmerald else AuroraCyan)
                        Text("This phone will only receive jobs authorized for the signed-in account or explicitly enabled shared-pool participation.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        DevMonitorOutlinedButton(onClick = onVerifyToken, modifier = Modifier.fillMaxWidth(), enabled = signedIn && !busy) {
                            Text("Verify account session")
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
