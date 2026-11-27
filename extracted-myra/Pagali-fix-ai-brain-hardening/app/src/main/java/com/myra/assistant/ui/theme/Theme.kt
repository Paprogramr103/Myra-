package com.myra.assistant.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val MyraDarkColorScheme = darkColorScheme(
    primary = MyraCyan,
    onPrimary = MyraVoid,
    primaryContainer = MyraPrimeCard,
    onPrimaryContainer = MyraTextPrimary,
    secondary = MyraPurple,
    onSecondary = MyraTextPrimary,
    tertiary = MyraGreen,
    background = MyraVoid,
    onBackground = MyraTextPrimary,
    surface = MyraSurface,
    onSurface = MyraTextPrimary,
    surfaceVariant = MyraSurfaceElevated,
    onSurfaceVariant = MyraTextSecondary,
    outline = MyraBorderSubtle,
    error = MyraRed,
)

@Composable
fun MyraTheme(
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = MyraDarkColorScheme,
        typography = MyraTypography,
        content = content,
    )
}
