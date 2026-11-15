package com.aziitech.devmonitor.gateway.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

private val GatewayLightScheme = lightColorScheme(
    primary = Color(0xFF146EF5), onPrimary = Color.White,
    primaryContainer = Color(0xFFDCEAFF), onPrimaryContainer = Color(0xFF06224F),
    secondary = Color(0xFF7A42F4), onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDE2FF), onSecondaryContainer = Color(0xFF28104F),
    tertiary = Color(0xFF00A9C7), onTertiary = Color.White,
    background = AuroraLightSurface, surface = AuroraLightCard,
    surfaceVariant = Color(0xFFEAF2FB), error = Color(0xFFD92D4B),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FBFF),
    surfaceContainer = Color(0xFFF4F8FD),
    surfaceContainerHigh = Color(0xFFEEF4FB),
    surfaceContainerHighest = Color(0xFFE8F0F8)
)

private val GatewayDarkScheme = darkColorScheme(
    primary = Color(0xFF62B0FF), onPrimary = Color(0xFF002B55),
    primaryContainer = Color(0xFF0D4F88), onPrimaryContainer = Color(0xFFD9ECFF),
    secondary = Color(0xFFB69AFF), onSecondary = Color(0xFF241044),
    secondaryContainer = Color(0xFF432878), onSecondaryContainer = Color(0xFFEBDDFF),
    tertiary = Color(0xFF48D9E9), onTertiary = Color(0xFF00363D),
    background = Color(0xFF06101E), surface = Color(0xFF0B192B),
    surfaceVariant = Color(0xFF17283C), error = Color(0xFFFF7188),
    surfaceContainerLowest = Color(0xFF030B14),
    surfaceContainerLow = Color(0xFF091525),
    surfaceContainer = Color(0xFF0D1B2D),
    surfaceContainerHigh = Color(0xFF12233A),
    surfaceContainerHighest = Color(0xFF172A43)
)

@Composable
fun GatewayAuroraTheme(
    darkTheme: Boolean = true,
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) GatewayDarkScheme else GatewayLightScheme,
        shapes = MaterialTheme.shapes.copy(
            extraSmall = RoundedCornerShape(16.dp),
            small = RoundedCornerShape(16.dp),
            medium = RoundedCornerShape(18.dp),
            large = RoundedCornerShape(26.dp)
        ),
        content = content
    )
}
