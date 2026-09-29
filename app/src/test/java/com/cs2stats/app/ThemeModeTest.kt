package com.cs2stats.app

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.cs2stats.app.data.local.AppSettings
import com.cs2stats.app.ui.theme.asOledDark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 暗色模式两档（OLED 纯黑 / LCD 深灰）与三档主题（跟随系统 / 浅色 / 深色）。
 *
 * 锁死的东西：
 * - 「跟随系统」必须真的跟着 `isSystemInDarkTheme()` 走，强制档则无视系统；
 * - 不认识的存档取值要回落到默认档（宁可退化也不能把界面染成没配过的颜色）；
 * - OLED 档的画布必须是纯 #000000，同时主色与 surfaceVariant（行背景/进度轨道）不能被动过。
 */
class ThemeModeTest {

    // ---------- 主题模式 × 系统状态 ----------

    @Test
    fun `跟随系统按时切换`() {
        val s = AppSettings(themeMode = AppSettings.THEME_FOLLOW)
        assertTrue(s.isDark(systemDark = true))
        assertFalse(s.isDark(systemDark = false))
    }

    @Test
    fun `强制浅色无视系统深夜`() {
        val s = AppSettings(themeMode = AppSettings.THEME_LIGHT)
        assertFalse(s.isDark(systemDark = true))
        assertFalse(s.isDark(systemDark = false))
    }

    @Test
    fun `强制深色无视系统白天`() {
        val s = AppSettings(themeMode = AppSettings.THEME_DARK)
        assertTrue(s.isDark(systemDark = true))
        assertTrue(s.isDark(systemDark = false))
    }

    @Test
    fun `不认识的主题取值回落跟随系统`() {
        val s = AppSettings(themeMode = "weird-value")
        assertTrue(s.isDark(systemDark = true))
        assertFalse(s.isDark(systemDark = false))
    }

    @Test
    fun `默认值是跟随系统加 OLED 纯黑`() {
        val s = AppSettings()
        assertEquals(AppSettings.THEME_FOLLOW, s.themeMode)
        assertEquals(AppSettings.DARK_OLED, s.darkStyle)
        assertTrue(s.useOledDark)
    }

    @Test
    fun `只有 OLED 档才算纯黑`() {
        assertTrue(AppSettings(darkStyle = AppSettings.DARK_OLED).useOledDark)
        assertFalse(AppSettings(darkStyle = AppSettings.DARK_LCD).useOledDark)
        assertFalse(AppSettings(darkStyle = "broken").useOledDark)
    }

    // ---------- OLED 配色不变量 ----------

    @Test
    fun `OLED 档画布与容器全压到纯黑`() {
        val oled = darkColorScheme().asOledDark()
        assertEquals(Color.Black, oled.background)
        assertEquals(Color.Black, oled.surface)
        assertEquals(Color.Black, oled.surfaceDim)
        assertEquals(Color.Black, oled.surfaceContainerLowest)

        // 容器留一丝灰：看得见层次，但都是近黑（< #202020）
        listOf(
            oled.surfaceContainerLow,
            oled.surfaceContainer,
            oled.surfaceContainerHigh,
            oled.surfaceContainerHighest,
        ).forEach { c ->
            assertTrue("容器应近黑，实际=${c.red}", c.red < 0.13f)
            assertTrue("容器不该是纯白系", c.green < 0.13f && c.blue < 0.13f)
        }
    }

    @Test
    fun `LCD 深灰底与 OLED 纯黑底真的不同`() {
        val lcd = darkColorScheme()
        val oled = lcd.asOledDark()
        assertFalse(lcd.background == oled.background)
        // LCD 底明显不黑，这样两种样式才不是换个名字
        assertTrue(lcd.background.red > 0.05f)
        assertEquals(Color.Black, oled.background)
    }

    @Test
    fun `OLED 只改中性色，主色与行背景不动`() {
        val lcd = darkColorScheme()
        val oled = lcd.asOledDark()
        assertEquals(lcd.primary, oled.primary)
        assertEquals(lcd.onPrimary, oled.onPrimary)
        assertEquals(lcd.primaryContainer, oled.primaryContainer)
        assertEquals(lcd.tertiary, oled.tertiary)
        // 行背景/进度轨道：压黑了就看不见，必须原样保留
        assertEquals(lcd.surfaceVariant, oled.surfaceVariant)
        assertEquals(lcd.onSurfaceVariant, oled.onSurfaceVariant)
        assertEquals(lcd.outline, oled.outline)
    }

    @Test
    fun `OLED 变换是幂等的`() {
        val once = darkColorScheme().asOledDark()
        val twice = once.asOledDark()
        // ColorScheme 未必自己实现 equals，逐字段比
        assertEquals(once.background, twice.background)
        assertEquals(once.surface, twice.surface)
        assertEquals(once.surfaceContainerLow, twice.surfaceContainerLow)
        assertEquals(once.surfaceContainerHighest, twice.surfaceContainerHighest)
        assertEquals(once.surfaceVariant, twice.surfaceVariant)
        assertEquals(once.primary, twice.primary)
    }
}
