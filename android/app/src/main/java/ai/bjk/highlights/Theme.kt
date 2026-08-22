package ai.bjk.highlights

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Background = Color(0xFF0B0F0E)
val Surface = Color(0xFF161C1A)
val Emerald = Color(0xFF19C37D)
val TextGray = Color(0xFF9AA5A0)
val OnDark = Color(0xFFF2F5F3)

private val Scheme = darkColorScheme(
    primary = Emerald,
    onPrimary = Background,
    primaryContainer = Emerald,
    onPrimaryContainer = Background,
    secondary = Emerald,
    onSecondary = Background,
    secondaryContainer = Surface,
    onSecondaryContainer = OnDark,
    tertiary = Emerald,
    onTertiary = Background,
    tertiaryContainer = Surface,
    onTertiaryContainer = OnDark,
    background = Background,
    onBackground = OnDark,
    surface = Background,
    onSurface = OnDark,
    surfaceVariant = Surface,
    onSurfaceVariant = TextGray,
    surfaceContainer = Surface,
    surfaceContainerHigh = Surface,
    surfaceContainerHighest = Surface,
    surfaceContainerLow = Surface,
    surfaceContainerLowest = Background,
    inverseSurface = OnDark,
    inverseOnSurface = Background,
    inversePrimary = Emerald,
    outline = Color(0xFF2C3533),
    outlineVariant = Color(0xFF2C3533),
    error = Color(0xFFE5484D),
    onError = OnDark,
    errorContainer = Color(0xFF3A1D1E),
    onErrorContainer = OnDark,
    surfaceTint = Emerald,
    scrim = Color.Black,
)

@Composable
fun HighlightsTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Scheme, content = content)
}
