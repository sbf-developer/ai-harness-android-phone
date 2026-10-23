package dev.pocketharness.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Light = lightColorScheme(
    primary = Color(0xFF216B52), onPrimary = Color.White,
    primaryContainer = Color(0xFFDDEDE5), onPrimaryContainer = Color(0xFF164734),
    secondary = Color(0xFF5B675F), background = Color(0xFFF8F8F4),
    surface = Color(0xFFF8F8F4), surfaceContainer = Color(0xFFEFEEE8),
    surfaceContainerLow = Color(0xFFF1F2ED), onSurface = Color(0xFF1D2722),
    onSurfaceVariant = Color(0xFF59635C), outline = Color(0xFFABB4AC), outlineVariant = Color(0xFFDCE1D9),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF9DD6B9), primaryContainer = Color(0xFF174D38),
    background = Color(0xFF111A15), surface = Color(0xFF111A15),
    surfaceContainer = Color(0xFF202B24), surfaceContainerLow = Color(0xFF18221C),
    onSurface = Color(0xFFE3EAE2), onSurfaceVariant = Color(0xFFB0BEB3),
)
private val Type = Typography(
    headlineLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 34.sp, lineHeight = 40.sp),
    headlineSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 11.sp, letterSpacing = 1.sp),
)
@Composable
fun HarnessTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
}
