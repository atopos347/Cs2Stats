package com.cs2stats.app.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = PrimaryLight,
    onPrimary = OnPrimaryLight,
    primaryContainer = PrimaryContainerLight,
    onPrimaryContainer = OnPrimaryContainerLight,
    secondary = SecondaryLight,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    background = BackgroundLight,
    surface = SurfaceLight,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    outline = OutlineLight,
    tertiary = AccentOrange,
)

private val DarkColors = darkColorScheme(
    primary = PrimaryDark,
    onPrimary = OnPrimaryDark,
    primaryContainer = PrimaryContainerDark,
    onPrimaryContainer = OnPrimaryContainerDark,
    secondary = SecondaryDark,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    background = BackgroundDark,
    surface = SurfaceDark,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = OnSurfaceVariantDark,
    outline = OutlineDark,
    tertiary = AccentOrange,
)

/**
 * **OLED 暗色**：把中性面全部压成纯黑 —— 屏幕像素点直接关掉，省电、黑得彻底；
 * 容器只留一丝灰（0x08~0x1C），顶栏/导航栏/卡片才分得出层次。
 *
 * 只动中性色：主色、主容器、强调橙一律保持原样，
 * 所以 Android 12+ 的壁纸取色在两种暗色样式里都还在（只是底色不同）。
 *
 * [surfaceVariant] 同样不动 —— 它在本项目里是行背景与进度条轨道，
 * 压黑了这些元素在纯黑上就看不见了。
 */
fun ColorScheme.asOledDark(): ColorScheme = copy(
    background = OledBackground,
    surface = OledSurface,
    surfaceDim = OledBackground,
    surfaceBright = OledContainer,
    surfaceContainerLowest = OledBackground,
    surfaceContainerLow = OledContainerLow,
    surfaceContainer = OledContainer,
    surfaceContainerHigh = OledContainerHigh,
    surfaceContainerHighest = OledContainerHighest,
)

/**
 * Material 3 主题。
 *
 * - [darkTheme]：深浅色由调用方决定（默认跟系统），由 `AppSettings.isDark()` 支持
 *   **跟随系统 / 强制浅色 / 强制深色** 三档；
 * - [oledDark]：深色里再分两 —— `true` = OLED 纯黑底，`false` = 普通 LCD 深灰底；
 * - [dynamicColor]：Android 12+ 用壁纸取色定主色（两种暗色样式下都保留）。
 */
@Composable
fun Cs2StatsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    oledDark: Boolean = false,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColors
        else -> LightColors
    }
    val colorScheme = if (darkTheme && oledDark) base.asOledDark() else base

    // 状态栏/导航栏图标明暗跟随**应用**的明暗，而不是系统的 ——
    // 强制深色时系统仍是浅色的话，深底配深图标就看不见了。
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content,
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
