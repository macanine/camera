package com.photoanswer.app

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object CameraColors {
    val Ink = Color(0xFF17201F)
    val Paper = Color(0xFFF7FAF8)
    val Surface = Color(0xFFFFFFFF)
    val Muted = Color(0xFF455A55)
    val Line = Color(0xFFC3CFCB)
    val Error = Color(0xFFBA1A1A)
}

internal val LightColors = lightColorScheme(
    primary = Color(0xFF006B5F), onPrimary = Color.White,
    primaryContainer = Color(0xFF9EF2DF), onPrimaryContainer = Color(0xFF002019),
    secondary = Color(0xFF4B635B), onSecondary = Color.White,
    secondaryContainer = Color(0xFFCEE9DD), onSecondaryContainer = Color(0xFF092019),
    tertiary = Color(0xFF795900), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDF98), onTertiaryContainer = Color(0xFF261900),
    background = CameraColors.Paper, onBackground = CameraColors.Ink,
    surface = CameraColors.Surface, onSurface = CameraColors.Ink,
    surfaceVariant = Color(0xFFDFE9E4), onSurfaceVariant = CameraColors.Muted,
    outline = Color(0xFF6F7975), outlineVariant = CameraColors.Line,
    error = CameraColors.Error, onError = Color.White,
    errorContainer = Color(0xFFFFDAD6), onErrorContainer = Color(0xFF410002)
)
internal val DarkColors = darkColorScheme(
    primary = Color(0xFF80D5C3), onPrimary = Color(0xFF00382F),
    primaryContainer = Color(0xFF005045), onPrimaryContainer = Color(0xFF9EF2DF),
    secondary = Color(0xFFB2CDC1), onSecondary = Color(0xFF1E352C),
    secondaryContainer = Color(0xFF354C42), onSecondaryContainer = Color(0xFFCEE9DD),
    tertiary = Color(0xFFF0C35A), onTertiary = Color(0xFF402D00),
    tertiaryContainer = Color(0xFF5D4300), onTertiaryContainer = Color(0xFFFFDF98),
    background = Color(0xFF101512), onBackground = Color(0xFFE0E8E2),
    surface = Color(0xFF151D18), onSurface = Color(0xFFE0E8E2),
    surfaceVariant = Color(0xFF3F4943), onSurfaceVariant = Color(0xFFBFCAC2),
    outline = Color(0xFF89948C), outlineVariant = Color(0xFF3F4943),
    error = Color(0xFFFFB4AB), onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A), onErrorContainer = Color(0xFFFFDAD6)
)

private val AppTypography = Typography(
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 32.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 24.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 18.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp)
)

@Composable
fun CameraTheme(dark: Boolean = isSystemInDarkTheme(), dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (dark) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, typography = AppTypography,
        shapes = Shapes(small = RoundedCornerShape(8.dp), medium = RoundedCornerShape(16.dp), large = RoundedCornerShape(24.dp)),
        content = content)
}
