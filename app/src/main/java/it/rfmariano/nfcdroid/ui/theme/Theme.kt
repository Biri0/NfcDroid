package it.rfmariano.nfcdroid.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Sand100,
    onPrimary = Ink,
    primaryContainer = Slate700,
    onPrimaryContainer = Foam,
    secondary = Teal300,
    onSecondary = Slate900,
    secondaryContainer = Slate800,
    onSecondaryContainer = Sand100,
    tertiary = Rust200,
    onTertiary = Slate900,
    tertiaryContainer = Color(0xFF6A4330),
    onTertiaryContainer = Foam,
    background = Slate900,
    onBackground = Sand100,
    surface = Slate800,
    onSurface = Sand100,
    surfaceVariant = Slate700,
    onSurfaceVariant = Mist,
    outline = Slate500,
    error = Rust200,
    onError = Slate900
)

private val LightColorScheme = lightColorScheme(
    primary = Slate900,
    onPrimary = Foam,
    primaryContainer = Sand200,
    onPrimaryContainer = Slate900,
    secondary = Teal600,
    onSecondary = Foam,
    secondaryContainer = Color(0xFFD9ECE7),
    onSecondaryContainer = Slate900,
    tertiary = Rust500,
    onTertiary = Foam,
    tertiaryContainer = Color(0xFFF4D8C7),
    onTertiaryContainer = Slate900,
    background = Foam,
    onBackground = Ink,
    surface = Color(0xFFFFFBFD),
    onSurface = Ink,
    surfaceVariant = Sand100,
    onSurfaceVariant = Slate700,
    outline = Sand300,
    error = Rust500,
    onError = Foam
)

@Composable
fun NfcDroidTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
