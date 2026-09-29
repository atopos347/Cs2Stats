package com.cs2stats.app.ui.theme

import androidx.compose.ui.graphics.Color

// 品牌种子色：CS2 HUD 蓝 + 火焰橙点缀
val SeedBlue = Color(0xFF1E6BF0)
val AccentOrange = Color(0xFFFF6A3D)

// 浅色
val PrimaryLight = Color(0xFF1A5FD0)
val OnPrimaryLight = Color(0xFFFFFFFF)
val PrimaryContainerLight = Color(0xFFDCE6FF)
val OnPrimaryContainerLight = Color(0xFF001B3F)
val SecondaryLight = Color(0xFF555F71)
val SecondaryContainerLight = Color(0xFFD9E3F8)
val OnSecondaryContainerLight = Color(0xFF121C2B)
val BackgroundLight = Color(0xFFFDFBFF)
val SurfaceLight = Color(0xFFFDFBFF)
val SurfaceVariantLight = Color(0xFFE0E2EC)
val OnSurfaceVariantLight = Color(0xFF44474F)
val OutlineLight = Color(0xFF74777F)

// 深色
val PrimaryDark = Color(0xFFAFC6FF)
val OnPrimaryDark = Color(0xFF002F67)
val PrimaryContainerDark = Color(0xFF00458F)
val OnPrimaryContainerDark = Color(0xFFDCE6FF)
val SecondaryDark = Color(0xFFBDC7DC)
val SecondaryContainerDark = Color(0xFF3E4759)
val OnSecondaryContainerDark = Color(0xFFD9E3F8)
val BackgroundDark = Color(0xFF0F1419)
val SurfaceDark = Color(0xFF111418)
val SurfaceVariantDark = Color(0xFF44474F)
val OnSurfaceVariantDark = Color(0xFFC4C6D0)
val OutlineDark = Color(0xFF8E9099)

// OLED 暗色：画布全黑，容器只留一丝灰，卡片之间才分得出层次
// （surfaceVariant 不动 —— 进度条/行背景靠它才在纯黑上看得见）
val OledBackground = Color(0xFF000000)
val OledSurface = Color(0xFF000000)
val OledContainerLow = Color(0xFF080808)
val OledContainer = Color(0xFF0E0E0E)
val OledContainerHigh = Color(0xFF141414)
val OledContainerHighest = Color(0xFF1C1C1C)

// Rating 配色（HLTV 习惯：越高越红/紫，低分偏青）
val RatingHigh = Color(0xFF00C853)
val RatingMid = Color(0xFFFFB300)
val RatingLow = Color(0xFFF44336)
val RatingStar = Color(0xFFE040FB)
