package app.kerkida.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kerkida.data.ThemeMode

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0B1F4B),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD9E3F8),
    onPrimaryContainer = Color(0xFF0B1F4B),
    secondary = Color(0xFF007A54),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC8F2E1),
    onSecondaryContainer = Color(0xFF00382A),
    tertiary = Color(0xFF3A5FA0),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDCE6F7),
    onTertiaryContainer = Color(0xFF0B1F4B),
    background = Color(0xFFF4F6FA),
    onBackground = Color(0xFF141B2D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF141B2D),
    surfaceVariant = Color(0xFFE6EAF2),
    onSurfaceVariant = Color(0xFF525C70),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8FAFD),
    surfaceContainer = Color(0xFFEEF1F7),
    surfaceContainerHigh = Color(0xFFE7EBF3),
    surfaceContainerHighest = Color(0xFFE0E5EE),
    outline = Color(0xFF7F889A),
    outlineVariant = Color(0xFFD5DAE5),
    error = Color(0xFFB3261E),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF5CE0B0),
    onPrimary = Color(0xFF00382A),
    primaryContainer = Color(0xFF1A3264),
    onPrimaryContainer = Color(0xFFD9E3F8),
    secondary = Color(0xFF3FD3A0),
    onSecondary = Color(0xFF00382A),
    secondaryContainer = Color(0xFF0B4D3B),
    onSecondaryContainer = Color(0xFFC8F2E1),
    tertiary = Color(0xFFA9C2F2),
    onTertiary = Color(0xFF0B1F4B),
    tertiaryContainer = Color(0xFF24417A),
    onTertiaryContainer = Color(0xFFDCE6F7),
    background = Color(0xFF0A1226),
    onBackground = Color(0xFFE6EAF4),
    surface = Color(0xFF111A33),
    onSurface = Color(0xFFE6EAF4),
    surfaceVariant = Color(0xFF222D4A),
    onSurfaceVariant = Color(0xFFAEB8CC),
    surfaceContainerLowest = Color(0xFF070E1E),
    surfaceContainerLow = Color(0xFF0E1730),
    surfaceContainer = Color(0xFF151F3A),
    surfaceContainerHigh = Color(0xFF1C2743),
    surfaceContainerHighest = Color(0xFF26324F),
    outline = Color(0xFF7A859C),
    outlineVariant = Color(0xFF2E3A58),
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC),
)

/** Colours Material does not name: match states and the demo warning. */
@Immutable
data class KerkidaColors(
    val live: Color,
    val onLive: Color,
    val win: Color,
    val draw: Color,
    val loss: Color,
    val demo: Color,
    val onDemo: Color,
    val brandBar: Color,
    val onBrandBar: Color,
    /** Brand green: selected states and favourites. */
    val accent: Color,
    /** The same green when it is used for text on the page background. */
    val accentText: Color,
    /** Yellow cards only. */
    val marigold: Color,
)

private val LightExtras = KerkidaColors(
    live = Color(0xFFA85A00),
    onLive = Color(0xFFFFFFFF),
    win = Color(0xFF007A54),
    draw = Color(0xFF9A8F78),
    loss = Color(0xFFC8452F),
    demo = Color(0xFF5B2A86),
    onDemo = Color(0xFFFFFFFF),
    brandBar = Color(0xFF0B1F4B),
    onBrandBar = Color(0xFFFFFFFF),
    accent = Color(0xFF00B37A),
    accentText = Color(0xFF00704C),
    marigold = Color(0xFFF2B134),
)

private val DarkExtras = KerkidaColors(
    live = Color(0xFFF2B84B),
    onLive = Color(0xFF2A1B00),
    win = Color(0xFF3FD3A0),
    draw = Color(0xFFB5AB95),
    loss = Color(0xFFFF8A73),
    demo = Color(0xFF4A2472),
    onDemo = Color(0xFFF3E8FF),
    brandBar = Color(0xFF0E1A3D),
    onBrandBar = Color(0xFFFFFFFF),
    accent = Color(0xFF00B37A),
    accentText = Color(0xFF3FD3A0),
    marigold = Color(0xFFF2B134),
)

val LocalKerkidaColors = staticCompositionLocalOf { LightExtras }

private val KerkidaTypography = Typography(
    headlineSmall = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 20.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 22.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 16.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 14.sp, fontWeight = FontWeight.SemiBold),
)

private val KerkidaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(26.dp),
)

@Composable
fun KerkidaTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(LocalKerkidaColors provides if (dark) DarkExtras else LightExtras) {
        MaterialTheme(
            colorScheme = if (dark) DarkScheme else LightScheme,
            typography = KerkidaTypography,
            shapes = KerkidaShapes,
            content = content,
        )
    }
}

object KerkidaTheme {
    val colors: KerkidaColors
        @Composable get() = LocalKerkidaColors.current
}
