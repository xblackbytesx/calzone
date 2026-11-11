package com.xblackbytesx.calzone.ui

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

/** The web app's color roles (calzone-server `static/app.css`), so both feel like one product. */
@Immutable
data class CzColors(
    val canvas: Color,
    val surface: Color,
    val sunken: Color,
    val text: Color,
    val quiet: Color,
    val line: Color,
    val accent: Color,
    val onAccent: Color,
    val good: Color,
    val caution: Color,
    val danger: Color,
)

val DarkCz = CzColors(
    canvas = Color(0xFF131317),
    surface = Color(0xFF1C1C22),
    sunken = Color(0xFF24242C),
    text = Color(0xFFECEBE7),
    quiet = Color(0xFFA3A09A),
    line = Color(0xFF33333C),
    accent = Color(0xFF5FD0C4),
    onAccent = Color(0xFF0E1513),
    good = Color(0xFF6FD8A3),
    caution = Color(0xFFF2C063),
    danger = Color(0xFFFF8A80),
)

/**
 * Black for OLED screens (decision 36): a true black canvas, so the screen
 * is off where nothing is shown; cards and sheets lifted just enough to
 * tell apart. Text, accent and the other roles are Dark's.
 */
val BlackCz = DarkCz.copy(
    canvas = Color(0xFF000000),
    surface = Color(0xFF111114),
    sunken = Color(0xFF1B1B20),
    line = Color(0xFF2A2A31),
)

val LightCz = CzColors(
    canvas = Color(0xFFF6F4EF),
    surface = Color(0xFFFFFFFF),
    sunken = Color(0xFFEFECE5),
    text = Color(0xFF1F1D1A),
    quiet = Color(0xFF625D55),
    line = Color(0xFFE2DED5),
    accent = Color(0xFF0F766E),
    onAccent = Color(0xFFFFFFFF),
    good = Color(0xFF176A3F),
    caution = Color(0xFF8A5A00),
    danger = Color(0xFFB3261E),
)

val LocalCz = staticCompositionLocalOf { DarkCz }

/** Shorthand inside composables: `Cz.colors.accent`. */
object Cz {
    val colors: CzColors @Composable get() = LocalCz.current
}

/** `#rrggbb` (or `#rrggbbaa`) from the server, or null when it is not a color. */
fun parseColor(hex: String?): Color? {
    val h = hex?.removePrefix("#") ?: return null
    if (h.length != 6 && h.length != 8) return null
    val v = h.take(6).toLongOrNull(16) ?: return null
    return Color(0xFF000000 or v)
}

@Composable
fun CzTheme(dark: Boolean = true, black: Boolean = false, content: @Composable () -> Unit) {
    val c = when {
        !dark -> LightCz
        black -> BlackCz
        else -> DarkCz
    }
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.canvas, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.sunken, onSurfaceVariant = c.quiet,
            surfaceContainer = c.surface, surfaceContainerHigh = c.sunken, outline = c.line, error = c.danger,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = c.onAccent, background = c.canvas, onBackground = c.text,
            surface = c.surface, onSurface = c.text, surfaceVariant = c.sunken, onSurfaceVariant = c.quiet,
            surfaceContainer = c.surface, surfaceContainerHigh = c.sunken, outline = c.line, error = c.danger,
        )
    }
    val base = Typography()
    val type = base.copy(
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    )
    MaterialTheme(colorScheme = scheme, typography = type) {
        // Without this, text outside a Surface is black on the dark canvas (quantifyme lesson).
        CompositionLocalProvider(LocalCz provides c, LocalContentColor provides c.text, content = content)
    }
}

/** A switch in the app's colors; off has a visible thumb and edge on the dark canvas too. */
@Composable
fun czSwitchColors(): androidx.compose.material3.SwitchColors {
    val c = Cz.colors
    return androidx.compose.material3.SwitchDefaults.colors(
        checkedTrackColor = c.accent,
        checkedThumbColor = c.onAccent,
        uncheckedTrackColor = c.sunken,
        uncheckedThumbColor = c.quiet,
        uncheckedBorderColor = c.quiet,
    )
}
