package com.oilquiz.app.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// 将XML颜色转换为Compose Color
private val Primary = Color(0xFF6366F1)
private val PrimaryDark = Color(0xFF4F46E5)
private val PrimaryLight = Color(0xFF818CF8)
private val PrimaryContainer = Color(0xFFE0E7FF)
private val OnPrimary = Color(0xFFFFFFFF)
private val OnPrimaryContainer = Color(0xFF1E1B4B)

private val Secondary = Color(0xFF8B5CF6)
private val SecondaryDark = Color(0xFF7C3AED)
private val SecondaryLight = Color(0xFFA78BFA)
private val SecondaryContainer = Color(0xFFEDE9FE)
private val OnSecondary = Color(0xFFFFFFFF)
private val OnSecondaryContainer = Color(0xFF2E1065)

private val Tertiary = Color(0xFFF59E0B)
private val TertiaryContainer = Color(0xFFFEF3C7)
private val OnTertiary = Color(0xFFFFFFFF)
private val OnTertiaryContainer = Color(0xFF451A03)

private val Error = Color(0xFFEF4444)
private val ErrorContainer = Color(0xFFFEE2E2)
private val OnError = Color(0xFFFFFFFF)
private val OnErrorContainer = Color(0xFF450A0A)

private val Success = Color(0xFF10B981)
private val SuccessContainer = Color(0xFFD1FAE5)

private val Surface = Color(0xFFFFFFFF)
private val SurfaceDark = Color(0xFFF9FAFB)
private val SurfaceVariant = Color(0xFFF3F4F6)
private val OnSurface = Color(0xFF111827)
private val OnSurfaceVariant = Color(0xFF6B7280)

private val Background = Color(0xFFF9FAFB)
private val OnBackground = Color(0xFF111827)

private val Outline = Color(0xFFD1D5DB)
private val OutlineVariant = Color(0xFFE5E7EB)

private val TextPrimary = Color(0xFF111827)
private val TextSecondary = Color(0xFF6B7280)
private val TextTertiary = Color(0xFF9CA3AF)
private val TextLight = Color(0xFFFFFFFF)

private val LightColors = lightColorScheme(
    primary = Primary,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryContainer,
    onPrimaryContainer = OnPrimaryContainer,
    secondary = Secondary,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryContainer,
    onSecondaryContainer = OnSecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    error = Error,
    onError = OnError,
    errorContainer = ErrorContainer,
    onErrorContainer = OnErrorContainer,
    background = Background,
    onBackground = OnBackground,
    surface = Surface,
    onSurface = OnSurface,
    surfaceVariant = SurfaceVariant,
    onSurfaceVariant = OnSurfaceVariant,
    outline = Outline,
    outlineVariant = OutlineVariant,
)

private val DarkColors = darkColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimary,
    primaryContainer = PrimaryDark,
    onPrimaryContainer = PrimaryContainer,
    secondary = SecondaryLight,
    onSecondary = OnSecondary,
    secondaryContainer = SecondaryDark,
    onSecondaryContainer = SecondaryContainer,
    tertiary = Tertiary,
    onTertiary = OnTertiary,
    tertiaryContainer = TertiaryContainer,
    onTertiaryContainer = OnTertiaryContainer,
    error = Color(0xFFF87171),
    onError = OnError,
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = ErrorContainer,
    background = Color(0xFF111827),
    onBackground = Color(0xFFF9FAFB),
    surface = Color(0xFF1F2937),
    onSurface = Color(0xFFF9FAFB),
    surfaceVariant = Color(0xFF374151),
    onSurfaceVariant = Color(0xFF9CA3AF),
    outline = Color(0xFF4B5563),
    outlineVariant = Color(0xFF374151),
)

@Composable
fun SmartQuizTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = if (darkTheme) DarkColors else LightColors
    
    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}
