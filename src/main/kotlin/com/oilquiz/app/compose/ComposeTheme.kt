package com.oilquiz.app.compose

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.oilquiz.app.manager.ThemeManager
import com.oilquiz.app.theme.ThemePalette

/**
 * SmartQuiz Compose 主题（重构版）。
 *
 * 色板不硬编码：与 XML 体系统一，由 Google material-color-utilities HCT 算法
 * 根据 ThemeManager 的当前主题色（7 预设 / 自定义）+ 深浅模式动态生成——
 * Compose 页面中"自定义颜色"同样全量生效。
 *
 * 响应式：内部注册 ThemeManager 主题变更监听，切色/切模式时无需重建 Activity，
 * Compose 页面自动重组并刷新色板（适用于悬浮窗、独立 Compose 容器等场景）。
 *
 * @param darkTheme 显式指定深浅；null 时自动跟随 ThemeManager（浅/深/跟随系统）
 */
@Composable
fun SmartQuizTheme(
    darkTheme: Boolean? = null,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    val dark = darkTheme ?: ThemeManager.isDarkTheme(context)
    val themeColor = ThemeManager.getThemeColor(context)

    // 主题变化版本号：切色 / 切模式时 +1，触发下方 remember 重新计算
    var themeVersion by remember { mutableIntStateOf(0) }
    DisposableEffect(context) {
        val listener = ThemeManager.OnThemeChangedListener { _, _, _ ->
            themeVersion++
        }
        ThemeManager.registerThemeChangedListener(listener)
        onDispose { ThemeManager.unregisterThemeChangedListener(listener) }
    }

    // key 含主题色与版本号：主题变化后色板必然刷新，不复用旧缓存
    val palette = remember(context, dark, themeColor, themeVersion) {
        ThemeManager.getPalette(context, dark)
    }
    val colors = if (dark) darkColors(palette) else lightColors(palette)

    MaterialTheme(
        colorScheme = colors,
        content = content
    )
}

private fun Int.toColor(): Color = Color(this)

private fun lightColors(p: ThemePalette) = lightColorScheme(
    primary = p.primary.toColor(),
    onPrimary = p.onPrimary.toColor(),
    primaryContainer = p.primaryContainer.toColor(),
    onPrimaryContainer = p.onPrimaryContainer.toColor(),
    secondary = p.secondary.toColor(),
    onSecondary = p.onSecondary.toColor(),
    secondaryContainer = p.secondaryContainer.toColor(),
    onSecondaryContainer = p.onSecondaryContainer.toColor(),
    tertiary = p.tertiary.toColor(),
    onTertiary = p.onTertiary.toColor(),
    tertiaryContainer = p.tertiaryContainer.toColor(),
    onTertiaryContainer = p.onTertiaryContainer.toColor(),
    error = p.error.toColor(),
    onError = p.onError.toColor(),
    errorContainer = p.errorContainer.toColor(),
    onErrorContainer = p.onErrorContainer.toColor(),
    background = p.background.toColor(),
    onBackground = p.onBackground.toColor(),
    surface = p.surface.toColor(),
    onSurface = p.onSurface.toColor(),
    surfaceVariant = p.surfaceVariant.toColor(),
    onSurfaceVariant = p.onSurfaceVariant.toColor(),
    outline = p.outline.toColor(),
    outlineVariant = p.outlineVariant.toColor(),
)

private fun darkColors(p: ThemePalette) = darkColorScheme(
    primary = p.primary.toColor(),
    onPrimary = p.onPrimary.toColor(),
    primaryContainer = p.primaryContainer.toColor(),
    onPrimaryContainer = p.onPrimaryContainer.toColor(),
    secondary = p.secondary.toColor(),
    onSecondary = p.onSecondary.toColor(),
    secondaryContainer = p.secondaryContainer.toColor(),
    onSecondaryContainer = p.onSecondaryContainer.toColor(),
    tertiary = p.tertiary.toColor(),
    onTertiary = p.onTertiary.toColor(),
    tertiaryContainer = p.tertiaryContainer.toColor(),
    onTertiaryContainer = p.onTertiaryContainer.toColor(),
    error = p.error.toColor(),
    onError = p.onError.toColor(),
    errorContainer = p.errorContainer.toColor(),
    onErrorContainer = p.onErrorContainer.toColor(),
    background = p.background.toColor(),
    onBackground = p.onBackground.toColor(),
    surface = p.surface.toColor(),
    onSurface = p.onSurface.toColor(),
    surfaceVariant = p.surfaceVariant.toColor(),
    onSurfaceVariant = p.onSurfaceVariant.toColor(),
    outline = p.outline.toColor(),
    outlineVariant = p.outlineVariant.toColor(),
)
