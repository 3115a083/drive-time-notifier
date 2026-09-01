package de.drivetime.notifier.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
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
    ColorPalette.MATERIAL_YOU -> PaletteSpec(
        Color(0xFF506082), Color(0xFF687395), Color(0xFF70617E),
        Color(0xFF4B5D82), Color(0xFF66759D)
    )
    ColorPalette.VIOLET -> PaletteSpec(
        Color(0xFF6558C5), Color(0xFF8174D8), Color(0xFF9C6DB7),
        Color(0xFF5547B7), Color(0xFF7B69D0)
    )
    ColorPalette.OCEAN -> PaletteSpec(
        Color(0xFF1667B7), Color(0xFF168AA4), Color(0xFF3D67C6),
        Color(0xFF0E5EAA), Color(0xFF168C9C)
    )
    ColorPalette.FOREST -> PaletteSpec(
        Color(0xFF26745E), Color(0xFF5B854B), Color(0xFF2E7279),
        Color(0xFF1F6854), Color(0xFF58824B)
    )
    ColorPalette.SUNSET -> PaletteSpec(
        Color(0xFFC75C45), Color(0xFFD7833F), Color(0xFFA95179),
        Color(0xFFB94D42), Color(0xFFD68240)
    )
    ColorPalette.ROSE -> PaletteSpec(
        Color(0xFFB55474), Color(0xFFD16B7D), Color(0xFF8E5AA8),
        Color(0xFFA74868), Color(0xFFC8667A)
    )
    ColorPalette.GRAPHITE -> PaletteSpec(
        Color(0xFF4F5B6C), Color(0xFF6C7889), Color(0xFF71687C),
        Color(0xFF3D4756), Color(0xFF606C7C)
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
    val context = LocalContext.current
    val p = paletteSpec(palette)

    val colors = when {
        palette == ColorPalette.MATERIAL_YOU && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> darkColorScheme(
            primary = p.primary.copy(
                red = (p.primary.red + 0.16f).coerceAtMost(1f),
                green = (p.primary.green + 0.16f).coerceAtMost(1f),
                blue = (p.primary.blue + 0.16f).coerceAtMost(1f)
            ),
            secondary = p.secondary,
            tertiary = p.tertiary,
            background = Color(0xFF111318),
            surface = Color(0xFF191B21),
            surfaceVariant = Color(0xFF24272E),
            outlineVariant = Color(0xFF363A44)
        )

        else -> lightColorScheme(
            primary = p.primary,
            secondary = p.secondary,
            tertiary = p.tertiary,
            background = Color(0xFFF6F7F9),
            surface = Color(0xFFFFFFFF),
            surfaceVariant = Color(0xFFF0F2F5),
            outlineVariant = Color(0xFFE1E4E9)
        )
    }

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(
            headlineSmall = Typography().headlineSmall.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
            headlineMedium = Typography().headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
            titleLarge = Typography().titleLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
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
