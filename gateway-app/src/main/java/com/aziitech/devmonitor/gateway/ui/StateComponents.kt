package com.aziitech.devmonitor.gateway.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberInfiniteTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun DevMonitorLoadingState(
    title: String = "Loading…",
    message: String = "Preparing this screen. Please wait.",
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            CircularProgressIndicator()
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Aurora floating wait panel used for blocking work, with an indeterminate animated progress rail. */
@Composable
fun AuroraFloatingWaitOverlay(
    title: String,
    message: String,
    modifier: Modifier = Modifier
) {
    val reducedMotion = devMonitorReducedMotion()
    val transition = rememberInfiniteTransition(label = "aurora-wait")
    val pulse by transition.animateFloat(
        initialValue = if (reducedMotion) 1f else .88f,
        targetValue = if (reducedMotion) 1f else 1.12f,
        animationSpec = infiniteRepeatable(tween(950, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "wait-pulse"
    )
    val sweep by transition.animateFloat(
        initialValue = if (reducedMotion) .5f else -.35f,
        targetValue = if (reducedMotion) .5f else 1.35f,
        animationSpec = infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "wait-sweep"
    )
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = AuroraPanel.copy(alpha = .97f)),
        border = BorderStroke(1.dp, AuroraCyan.copy(alpha = .30f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 12.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(46.dp).graphicsLayer {
                        scaleX = pulse
                        scaleY = pulse
                    }.clip(CircleShape).background(AuroraBlue.copy(alpha = .18f)),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(Modifier.size(34.dp)) {
                        drawCircle(AuroraCyan.copy(alpha = .45f), style = Stroke(width = 1.4.dp.toPx()))
                        drawCircle(AuroraCyan, radius = 4.dp.toPx())
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                    Text(message, color = Color(0xFFB9C9DC), style = MaterialTheme.typography.bodySmall)
                }
                CircularProgressIndicator(
                    modifier = Modifier.size(22.dp),
                    color = AuroraCyan,
                    trackColor = AuroraCyan.copy(alpha = .16f),
                    strokeWidth = 2.dp
                )
            }
            Canvas(Modifier.fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50))) {
                drawRoundRect(
                    color = AuroraCyan.copy(alpha = .13f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                )
                val segmentWidth = size.width * .34f
                val left = ((sweep * (size.width + segmentWidth)) - segmentWidth).coerceIn(-segmentWidth, size.width)
                drawRoundRect(
                    brush = Brush.horizontalGradient(
                        listOf(Color.Transparent, AuroraBlue, AuroraCyan, Color.Transparent),
                        startX = left,
                        endX = left + segmentWidth
                    ),
                    topLeft = Offset(left, 0f),
                    size = androidx.compose.ui.geometry.Size(segmentWidth, size.height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                )
            }
        }
    }
}

@Composable
fun DevMonitorEmptyState(
    title: String,
    message: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("○  $title", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                DevMonitorOutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun DevMonitorErrorState(
    title: String = "Something went wrong",
    message: String,
    actionLabel: String? = "Try again",
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("!  $title", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onErrorContainer)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                DevMonitorOutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}

@Composable
fun DevMonitorOfflineState(
    title: String = "You're offline",
    message: String = "An internet connection is required for this operation. Your local DevMonitor data remains available.",
    actionLabel: String? = "Try again",
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("⌁  $title", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.ExtraBold)
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (actionLabel != null && onAction != null) {
                Spacer(Modifier.height(4.dp))
                DevMonitorOutlinedButton(onClick = onAction) { Text(actionLabel) }
            }
        }
    }
}
