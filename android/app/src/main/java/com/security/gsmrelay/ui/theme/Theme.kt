package com.security.gsmrelay.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val RelayColorScheme = lightColorScheme(
    primary = RelayPrimary,
    onPrimary = RelayOnPrimary,
    primaryContainer = RelayPrimaryContainer,
    onPrimaryContainer = RelayOnPrimaryContainer,
    secondary = RelaySecondary,
    onSecondary = RelayOnSecondary,
    secondaryContainer = RelaySecondaryContainer,
    onSecondaryContainer = RelayOnSecondaryContainer,
    tertiary = RelayTertiary,
    onTertiary = RelayOnTertiary,
    tertiaryContainer = RelayTertiaryContainer,
    onTertiaryContainer = RelayOnTertiaryContainer,
    background = RelayBackground,
    onBackground = RelayOnBackground,
    surface = RelaySurface,
    onSurface = RelayOnSurface,
    surfaceVariant = RelaySurfaceVariant,
    onSurfaceVariant = RelayOnSurfaceVariant,
    outline = RelayOutline
)

@Composable
fun GSMRelayTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = RelayColorScheme,
        typography = Typography,
        content = content
    )
}
