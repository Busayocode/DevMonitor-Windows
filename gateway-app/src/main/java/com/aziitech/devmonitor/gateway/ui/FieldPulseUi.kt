package com.aziitech.devmonitor.gateway.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import android.provider.Settings
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/*
 * DevMonitor Aurora design system.
 *
 * This file deliberately owns the reusable visual language used by the legacy
 * screens as well as the newer Compose surfaces.  The data/business layer is
 * untouched: screens can keep calling the existing components while the
 * presentation moves to the Aurora product design.
 */

val AuroraNavy = Color(0xFF071426)
val AuroraNavy2 = Color(0xFF0B1D33)
val AuroraPanel = Color(0xFF10243D)
val AuroraBlue = Color(0xFF147BFF)
val AuroraBlueBright = Color(0xFF3D9BFF)
val AuroraCyan = Color(0xFF16D9F5)
val AuroraViolet = Color(0xFF8B5CF6)
val AuroraGreen = Color(0xFF20D889)
val AuroraEmerald = Color(0xFF10B981)
val AuroraMint = Color(0xFF5EEAD4)
val AuroraGreenDeep = Color(0xFF087F5B)
val AuroraTextMuted = Color(0xFF7B8CA5)
val AuroraLightSurface = Color(0xFFF6FAFF)
val AuroraLightCard = Color(0xFFFFFFFF)
val AuroraLightBorder = Color(0xFFDDE8F5)

fun fieldPulseBackground(dark: Boolean): Brush = if (dark) {
    Brush.verticalGradient(
        listOf(Color(0xFF050D18), Color(0xFF08182A), Color(0xFF071A22), Color(0xFF06101E))
    )
} else {
    Brush.verticalGradient(
        listOf(Color(0xFFF7FBFF), Color(0xFFEEF7FF), Color(0xFFF0FCF8), Color(0xFFF8F5FF))
    )
}

@Composable
private fun devMonitorReducedMotion(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        }.getOrDefault(false)
    }
}

private fun auroraActionBrush(enabled: Boolean): Brush = if (enabled) {
    Brush.horizontalGradient(listOf(AuroraBlue, AuroraBlueBright))
} else {
    Brush.horizontalGradient(listOf(Color(0xFFD9E2ED), Color(0xFFE7EDF4)))
}

@Composable
fun DevMonitorButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(50),
    colors: ButtonColors = ButtonDefaults.buttonColors(
        containerColor = AuroraBlue,
        contentColor = Color.White,
        disabledContainerColor = Color(0xFFDCE5F0),
        disabledContentColor = Color(0xFF8B95A7)
    ),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 12.dp, vertical = 9.dp),
    gradient: List<Color> = listOf(AuroraBlue, AuroraCyan),
    content: @Composable RowScope.() -> Unit
) {
    val resolvedShape = shape
    val brush = if (enabled) Brush.horizontalGradient(gradient) else Brush.horizontalGradient(listOf(Color(0xFFDCE5F0), Color(0xFFE8EEF5)))
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(resolvedShape)
            .shadow(if (enabled) 7.dp else 0.dp, resolvedShape, clip = false)
            .background(brush, resolvedShape)
            .then(if (border != null) Modifier.border(border, resolvedShape) else Modifier)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = {
            CompositionLocalProvider(LocalContentColor provides if (enabled) Color.White else Color(0xFF8B95A7)) {
                content()
            }
        }
    )
}

@Composable
fun DevMonitorOutlinedButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(50),
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(
        containerColor = Color.Transparent,
        contentColor = AuroraBlue,
        disabledContainerColor = Color.Transparent,
        disabledContentColor = Color(0xFF8B95A7)
    ),
    elevation: ButtonElevation? = null,
    border: BorderStroke? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 13.dp),
    gradient: List<Color> = listOf(AuroraViolet, AuroraBlue),
    content: @Composable RowScope.() -> Unit
) {
    val resolvedShape = shape
    val outline = border ?: BorderStroke(1.5.dp, if (enabled) gradient.last().copy(alpha = .62f) else AuroraLightBorder)
    val fill = if (enabled) gradient.first().copy(alpha = .055f) else Color(0xFFF0F4F8).copy(alpha = .70f)
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .clip(resolvedShape)
            .background(fill, resolvedShape)
            .border(outline, resolvedShape)
            .clickable(
                enabled = enabled,
                role = Role.Button,
                onClick = onClick
            )
            .padding(contentPadding),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = {
            CompositionLocalProvider(LocalContentColor provides if (enabled) gradient.last() else Color(0xFF8B95A7)) {
                content()
            }
        }
    )
}

@Composable
fun DevMonitorLiveDot(modifier: Modifier = Modifier) {
    val reducedMotion = devMonitorReducedMotion()
    val transition = rememberInfiniteTransition(label = "live-dot")
    val scale by transition.animateFloat(
        initialValue = if (reducedMotion) 1f else .78f,
        targetValue = if (reducedMotion) 1f else 1.18f,
        animationSpec = infiniteRepeatable(tween(1050, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "live-scale"
    )
    Box(
        modifier
            .size(12.dp)
            .semantics { contentDescription = "Live status indicator" },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .size((12f * scale).dp)
                .background(AuroraCyan.copy(alpha = .18f), CircleShape)
        )
        Box(Modifier.size(7.dp).background(AuroraCyan, CircleShape))
    }
}

/** Main reusable Aurora surface. */
@Composable
fun AuroraGlassCard(
    modifier: Modifier = Modifier,
    accent: Color = AuroraBlue,
    content: @Composable ColumnScope.() -> Unit
) {
    val dark = MaterialTheme.colorScheme.background == Color(0xFF06101E)
    val surface = if (dark) AuroraPanel.copy(alpha = .82f) else AuroraLightCard.copy(alpha = .94f)
    Surface(
        modifier = modifier
            .shadow(2.dp, RoundedCornerShape(24.dp), clip = false),
        shape = RoundedCornerShape(24.dp),
        color = surface,
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
        border = BorderStroke(1.dp, accent.copy(alpha = if (dark) .20f else .12f))
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(
                            accent.copy(alpha = if (dark) .10f else .055f),
                            Color.Transparent,
                            if (accent == AuroraGreen || accent == AuroraEmerald) {
                                AuroraMint.copy(alpha = if (dark) .035f else .018f)
                            } else {
                                AuroraViolet.copy(alpha = if (dark) .055f else .025f)
                            }
                        )
                    )
                )
        ) {
            Column(
                Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                content = content
            )
        }
    }
}

@Composable
fun AuroraHero(
    title: String,
    value: String,
    subtitle: String,
    meta: String,
    modifier: Modifier = Modifier
) {
    val reducedMotion = devMonitorReducedMotion()
    val transition = rememberInfiniteTransition(label = "hero-glow")
    val glow by transition.animateFloat(
        initialValue = if (reducedMotion) .24f else .18f,
        targetValue = if (reducedMotion) .24f else .36f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "hero-glow-alpha"
    )
    Surface(
        modifier = modifier.shadow(10.dp, RoundedCornerShape(30.dp), clip = false),
        shape = RoundedCornerShape(30.dp),
        color = AuroraNavy,
        shadowElevation = 0.dp
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        listOf(AuroraNavy, Color(0xFF0B3560), Color(0xFF123D3A), Color(0xFF2A1C55))
                    )
                )
                .padding(20.dp)
        ) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .size(170.dp)
                    .offset(x = 40.dp, y = (-72).dp)
                    .background(
                        Brush.radialGradient(listOf(AuroraCyan.copy(alpha = glow), Color.Transparent)),
                        CircleShape
                    )
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(92.dp)
                        .background(
                            Brush.radialGradient(listOf(AuroraCyan.copy(alpha = .25f), Color.Transparent)),
                            CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .size(70.dp)
                            .border(2.dp, AuroraCyan.copy(alpha = .55f), CircleShape)
                            .padding(8.dp)
                            .border(1.dp, AuroraBlue.copy(alpha = .50f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        DevMonitorLiveDot()
                    }
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DevMonitorLiveDot()
                        Spacer(Modifier.width(7.dp))
                        Text(
                            "LIVE MONITOR",
                            color = AuroraCyan,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.ExtraBold,
                            letterSpacing = 1.3.sp
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(title, color = Color.White.copy(alpha = .70f), style = MaterialTheme.typography.labelMedium)
                    Text(value, color = Color.White, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.ExtraBold)
                    Text(subtitle, color = Color.White.copy(alpha = .88f), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(meta, color = Color.White.copy(alpha = .56f), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
fun AuroraMetricCard(
    title: String,
    value: String,
    modifier: Modifier = Modifier,
    accent: Color = AuroraBlue,
    subtitle: String = ""
) {
    AuroraGlassCard(modifier, accent) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.height(6.dp))
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AuroraSectionHeader(
    eyebrow: String,
    title: String,
    subtitle: String? = null,
    accent: Color = AuroraBlue,
    modifier: Modifier = Modifier
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(7.dp).background(accent, CircleShape))
            Spacer(Modifier.width(7.dp))
            Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.2.sp)
        }
        Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun AuroraIconBadge(accent: Color = AuroraBlue, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier
            .size(42.dp)
            .background(accent.copy(alpha = .11f), CircleShape)
            .border(1.dp, accent.copy(alpha = .18f), CircleShape),
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun AuroraListRow(
    title: String,
    subtitle: String? = null,
    accent: Color = AuroraBlue,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = .72f), shape)
            .border(1.dp, accent.copy(alpha = .10f), shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .semantics(mergeDescendants = true) {
                if (onClick != null) contentDescription = listOf(title, subtitle).filterNotNull().joinToString(". ")
            }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AuroraIconBadge(accent) {
            Box(Modifier.size(8.dp).background(accent, CircleShape))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.Bold, maxLines = 1)
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

@Composable
fun AuroraStatusPill(text: String, accent: Color = AuroraGreen) {
    Row(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(accent.copy(alpha = .11f))
            .border(1.dp, accent.copy(alpha = .18f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(6.dp).background(accent, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun DevMonitorNavGlyph(kind: String, selected: Boolean, modifier: Modifier = Modifier) {
    val color = if (selected) AuroraBlueBright else Color(0xFF8FA1B8)
    val description = when (kind) {
        "home" -> "Dashboard"
        "devices" -> "Devices"
        "agents" -> "Agents"
        "payments" -> "Payments"
        "settings" -> "Settings"
        else -> "Navigation"
    }
    Canvas(modifier.size(25.dp).semantics { contentDescription = description }) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            "home" -> {
                val path = Path().apply {
                    moveTo(w * .15f, h * .48f); lineTo(w * .5f, h * .18f); lineTo(w * .85f, h * .48f)
                    lineTo(w * .85f, h * .84f); lineTo(w * .58f, h * .84f); lineTo(w * .58f, h * .60f)
                    lineTo(w * .42f, h * .60f); lineTo(w * .42f, h * .84f); lineTo(w * .15f, h * .84f); close()
                }
                drawPath(path, color, style = stroke)
            }
            "devices" -> {
                drawRoundRect(color, Offset(w * .22f, h * .08f), androidx.compose.ui.geometry.Size(w * .56f, h * .84f), CornerRadius(5.dp.toPx()), style = stroke)
                drawLine(color, Offset(w * .40f, h * .75f), Offset(w * .60f, h * .75f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
            "agents" -> {
                drawCircle(color, w * .13f, Offset(w * .5f, h * .30f), style = stroke)
                drawArc(color, 205f, 130f, false, Offset(w * .20f, h * .40f), androidx.compose.ui.geometry.Size(w * .60f, h * .48f), style = stroke)
            }
            "payments" -> {
                drawRoundRect(color, Offset(w * .16f, h * .23f), androidx.compose.ui.geometry.Size(w * .68f, h * .54f), CornerRadius(4.dp.toPx()), style = stroke)
                drawLine(color, Offset(w * .28f, h * .43f), Offset(w * .72f, h * .43f), strokeWidth = stroke.width)
                drawCircle(color, w * .05f, Offset(w * .35f, h * .62f))
            }
            else -> {
                drawCircle(color, w * .25f, Offset(w * .5f, h * .5f), style = stroke)
                for (i in 0 until 8) {
                    val a = i * Math.PI / 4
                    drawLine(
                        color,
                        Offset(w * .5f + cos(a).toFloat() * w * .34f, h * .5f + sin(a).toFloat() * h * .34f),
                        Offset(w * .5f + cos(a).toFloat() * w * .43f, h * .5f + sin(a).toFloat() * h * .43f),
                        strokeWidth = stroke.width,
                        cap = StrokeCap.Round
                    )
                }
            }
        }
    }
}
