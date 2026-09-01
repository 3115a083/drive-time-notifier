package de.drivetime.notifier.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import de.drivetime.notifier.data.AppAppearance
import de.drivetime.notifier.data.ColorPalette

data class PaletteSpec(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
    val heroStart: Color,
    val heroEnd: Color
)

fun paletteSpec(palette: ColorPalette): PaletteSpec = when (palette) {
    ColorPalette.PROTON -> PaletteSpec(
        Color(0xFF6D4AFF), Color(0xFF9A63FF), Color(0xFF3D7BFF),
        Color(0xFF5A37F2), Color(0xFF8D58F5)
    )
    ColorPalette.OCEAN -> PaletteSpec(
        Color(0xFF1267D6), Color(0xFF03A9C8), Color(0xFF4D6BFF),
        Color(0xFF095BC7), Color(0xFF00A7C4)
    )
    ColorPalette.FOREST -> PaletteSpec(
        Color(0xFF147D64), Color(0xFF53A451), Color(0xFF0E7C86),
        Color(0xFF0E6E59), Color(0xFF499747)
    )
    ColorPalette.AURORA -> PaletteSpec(
        Color(0xFF5C55E6), Color(0xFF00A89C), Color(0xFFB54BCD),
        Color(0xFF4A47CF), Color(0xFF00A18D)
    )
    ColorPalette.SUNSET -> PaletteSpec(
        Color(0xFFD85D45), Color(0xFFE58B3B), Color(0xFFB84B8A),
        Color(0xFFC9485A), Color(0xFFE68A3B)
    )
    ColorPalette.GRAPHITE -> PaletteSpec(
        Color(0xFF4B5565), Color(0xFF65758B), Color(0xFF766B94),
        Color(0xFF374151), Color(0xFF667085)
    )
}

@Composable
fun resolvedDarkMode(appearance: AppAppearance): Boolean = when (appearance) {
    AppAppearance.SYSTEM -> isSystemInDarkTheme()
    AppAppearance.LIGHT -> false
    AppAppearance.DARK -> true
}

@Composable
fun DriveTimeTheme(
    appearance: AppAppearance,
    palette: ColorPalette,
    content: @Composable () -> Unit
) {
    val dark = resolvedDarkMode(appearance)
    val p = paletteSpec(palette)

    val colors = if (dark) {
        darkColorScheme(
            primary = p.primary.copy(red = (p.primary.red + 0.18f).coerceAtMost(1f), green = (p.primary.green + 0.18f).coerceAtMost(1f), blue = (p.primary.blue + 0.18f).coerceAtMost(1f)),
            secondary = p.secondary,
            tertiary = p.tertiary,
            background = Color(0xFF111217),
            surface = Color(0xFF191A21),
            surfaceVariant = Color(0xFF23252E),
            outlineVariant = Color(0xFF343741)
        )
    } else {
        lightColorScheme(
            primary = p.primary,
            secondary = p.secondary,
            tertiary = p.tertiary,
            background = Color(0xFFF6F7FB),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF0F1F6),
            outlineVariant = Color(0xFFE3E5ED)
        )
    }

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            headlineSmall = MaterialTheme.typography.headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            headlineMedium = MaterialTheme.typography.headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            titleLarge = MaterialTheme.typography.titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        ),
        shapes = Shapes(
            extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(10.dp),
            small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp),
            extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(32.dp)
        ),
        content = content
    )
}
