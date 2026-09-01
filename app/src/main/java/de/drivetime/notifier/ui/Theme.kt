package de.drivetime.notifier.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Color(0xFF0F6CBD),
    secondary = Color(0xFF2F7D62),
    tertiary = Color(0xFF7A5AF8),
    surface = Color(0xFFF8FAFC),
    background = Color(0xFFF4F7FB)
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8CC8FF),
    secondary = Color(0xFF8FD8B7),
    tertiary = Color(0xFFC4B5FD)
)

@Composable
fun DriveTimeTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = if (Build.VERSION.SDK_INT >= 31) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else if (dark) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colors,
        typography = Typography(),
        shapes = Shapes(
            small = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
            medium = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
            large = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
        ),
        content = content
    )
}
