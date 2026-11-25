package com.llyytt.path.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.llyytt.path.R

/**
 * 行迹的视觉系统。
 *
 * 设计定位：夜间城市仪表盘 —— 底子极暗、信息极少、数字极大，只留一种橙色的光。
 * 明暗跟随系统（1B 决定），白天户外也能看清。
 *
 * 字体：Barlow Condensed（SIL OFL，可商用）只用于数字和英文标签；
 * 中文没有对应字形时自动回落到系统字体，不需要额外处理。
 */

/** 方案里规定的附加色，Material 的 ColorScheme 装不下。 */
@Immutable
data class XingjiPalette(
    /** 主强调：日落橙 */
    val accent: Color,
    /** 次强调：琥珀，用于时长这类次级数字 */
    val amber: Color,
    /** 第三级文字：小标签 */
    val textTertiary: Color,
    /** 记录页顶部的"天空"渐变 */
    val skyTop: Color,
    val skyBottom: Color,
    /** 按钮/数字的柔光 */
    val glow: Color,
    /** 面板与背景的层级差 */
    val panel: Color,
    val hairline: Color,
)

private val DarkPalette = XingjiPalette(
    accent = Color(0xFFFF6B1A),
    amber = Color(0xFFFFC24B),
    textTertiary = Color(0xFF5A6068),
    skyTop = Color(0xFF2A1206),
    skyBottom = Color(0xFF0B0D10),
    glow = Color(0x33FF6B1A),
    panel = Color(0xFF14171C),
    hairline = Color(0xFF262B32),
)

private val LightPalette = XingjiPalette(
    accent = Color(0xFFE85D0F),
    amber = Color(0xFFB8791A),
    textTertiary = Color(0xFF9A958C),
    skyTop = Color(0xFFFFF3E9),
    skyBottom = Color(0xFFFAF9F6),
    glow = Color(0x22E85D0F),
    panel = Color(0xFFFFFFFF),
    hairline = Color(0xFFE3E0D9),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFFFF6B1A),
    onPrimary = Color(0xFF12100E),
    secondary = Color(0xFFFFC24B),
    onSecondary = Color(0xFF12100E),
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFF5F5F7),
    surface = Color(0xFF14171C),
    onSurface = Color(0xFFF5F5F7),
    surfaceVariant = Color(0xFF1C2026),
    onSurfaceVariant = Color(0xFF8A8F98),
    outline = Color(0xFF262B32),
    error = Color(0xFFFF453A),
    onError = Color(0xFF12100E),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFFE85D0F),
    onPrimary = Color(0xFFFFFFFF),
    secondary = Color(0xFFB8791A),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFFAF9F6),
    onBackground = Color(0xFF14161A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF14161A),
    surfaceVariant = Color(0xFFF1EFEA),
    onSurfaceVariant = Color(0xFF6B7078),
    outline = Color(0xFFE3E0D9),
    error = Color(0xFFC62828),
    onError = Color(0xFFFFFFFF),
)

/** 数字和英文用的窄体字。中文缺字时系统自动回落。 */
val CondensedFamily = FontFamily(
    Font(R.font.barlow_condensed_black, FontWeight.Black),
    Font(R.font.barlow_condensed_bold, FontWeight.Bold),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_medium, FontWeight.Medium),
)

private val XingjiTypography = Typography(
    // 超大数字：记录页的距离
    displayLarge = TextStyle(
        fontFamily = CondensedFamily,
        fontWeight = FontWeight.Black,
        fontSize = 132.sp,
        lineHeight = 124.sp,
        letterSpacing = (-2).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = CondensedFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 84.sp,
        lineHeight = 80.sp,
        letterSpacing = (-1).sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = CondensedFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 56.sp,
        lineHeight = 56.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = CondensedFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 36.sp,
        lineHeight = 40.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = CondensedFamily,
        fontWeight = FontWeight.Bold,
        fontSize = 30.sp,
        letterSpacing = 1.sp,
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 17.sp,
    ),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 24.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    // 大写小标签：GTA HUD 的排版签名
    labelLarge = TextStyle(
        fontFamily = CondensedFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        letterSpacing = 2.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = CondensedFamily,
        fontWeight = FontWeight.SemiBold,
        fontSize = 12.sp,
        letterSpacing = 2.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = CondensedFamily,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        letterSpacing = 1.6.sp,
    ),
)

val LocalXingjiPalette = staticCompositionLocalOf { DarkPalette }

/** 取当前调色板的快捷入口。 */
object XingjiTheme {
    val palette: XingjiPalette
        @Composable get() = LocalXingjiPalette.current
}

@Composable
fun XingjiTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val palette = if (darkTheme) DarkPalette else LightPalette
    CompositionLocalProvider(LocalXingjiPalette provides palette) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkScheme else LightScheme,
            typography = XingjiTypography,
            content = content,
        )
    }
}
