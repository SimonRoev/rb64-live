package de.rb64.live.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * RB64 Regeln v2 – nur diese Werte (siehe Entwurf "D · Regeln v2").
 * Farbe hat eine Aufgabe: primary = bedienbar, status.* = Zustand eines Zugs. Rot nie als Knopf.
 */
data class Rb64Colors(
    val primary: Color, val onPrimary: Color, val primaryTint: Color,
    val header: Color, val onHeader: Color,
    val background: Color, val surface: Color, val surface2: Color, val surface3: Color,
    val onSurface: Color, val muted: Color, val line: Color, val graphic: Color,
    val ok: Color, val okBg: Color, val warn: Color, val warnBg: Color, val bad: Color, val badBg: Color,
    val dark: Boolean,
)

val LightRb = Rb64Colors(
    primary = Color(0xFF0B5563), onPrimary = Color.White, primaryTint = Color(0xFFE3F1F3),
    header = Color(0xFF0B5563), onHeader = Color.White,
    background = Color(0xFFF2F4F7), surface = Color.White, surface2 = Color.White, surface3 = Color.White,
    onSurface = Color(0xFF111827), muted = Color(0xFF4B5563), line = Color(0xFFE2E6EB), graphic = Color(0xFF6B7280),
    ok = Color(0xFF166534), okBg = Color(0xFFDCFCE7), warn = Color(0xFF92400E), warnBg = Color(0xFFFEF3C7),
    bad = Color(0xFFB91C1C), badBg = Color(0xFFFEE2E2), dark = false,
)

/** Dunkel: keine Schatten, Tiefe ueber hellere Flaechen (surface < surface2 < surface3). */
val DarkRb = Rb64Colors(
    primary = Color(0xFF63B5C2), onPrimary = Color(0xFF10161A), primaryTint = Color(0xFF183A40),
    header = Color(0xFF153A40), onHeader = Color(0xFFEDEFF1),
    background = Color(0xFF14191E), surface = Color(0xFF1D2329), surface2 = Color(0xFF242B31), surface3 = Color(0xFF2B3339),
    onSurface = Color(0xFFEBEEF0), muted = Color(0xFFAAB2BA), line = Color(0xFF323C44), graphic = Color(0xFF8C969F),
    ok = Color(0xFF86EFAC), okBg = Color(0xFF173B26), warn = Color(0xFFFCD34D), warnBg = Color(0xFF3D2E0A),
    bad = Color(0xFFFCA5A5), badBg = Color(0xFF451A1A), dark = true,
)

val LocalRb = staticCompositionLocalOf { LightRb }
val LocalVis = staticCompositionLocalOf { de.rb64.live.Visuals() }

/** Zugriff: Rb.c.primary usw. */
object Rb {
    val c: Rb64Colors @Composable get() = LocalRb.current
}

/** Abstaende 4/8/12/16/24, Ecken 8/12/16/24, Tippflaeche 48. */
object Dim {
    val s4 = 4.dp; val s8 = 8.dp; val s12 = 12.dp; val s16 = 16.dp; val s24 = 24.dp
    val rChip = 8.dp; val rButton = 12.dp; val rCard = 16.dp; val rSheet = 24.dp
    val touch = 48.dp; val row = 56.dp; val nav = 80.dp
    val icon = 24.dp; val iconSmall = 16.dp
}

/** Tiefe in dp fuer Modifier.shadow (E1 Karte, E2 Kopfleiste, E3 Blatt). */
object Elev { val e1 = 1.dp; val e2 = 3.dp; val e3 = 8.dp }

/** Schrift: 13 / 16 / 20 / 32, dazu 40 nur einmal je Bildschirm. Gewichte 400/600/700, Ziffern gleich breit. */
object Ts {
    private val base = TextStyle(fontFeatureSettings = "tnum")
    val hero = base.copy(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 44.sp, letterSpacing = (-1).sp)
    val big = base.copy(fontSize = 32.sp, fontWeight = FontWeight.Bold, lineHeight = 38.sp)
    val title = base.copy(fontSize = 20.sp, fontWeight = FontWeight.SemiBold, lineHeight = 26.sp)
    val titleBold = title.copy(fontWeight = FontWeight.Bold)
    val text = base.copy(fontSize = 16.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp)
    val textStrong = text.copy(fontWeight = FontWeight.SemiBold)
    val small = base.copy(fontSize = 13.sp, fontWeight = FontWeight.Normal, lineHeight = 18.sp)
    val smallStrong = small.copy(fontWeight = FontWeight.SemiBold)
    val smallBold = small.copy(fontWeight = FontWeight.Bold)
}

/** Ist das Farbschema dunkel? mode = "system" | "light" | "dark". */
@Composable
fun isDark(mode: String) = when (mode) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }

@Composable
fun Rb64Theme(mode: String = "system", vis: de.rb64.live.Visuals = de.rb64.live.Visuals(), content: @Composable () -> Unit) {
    val base = if (isDark(mode)) DarkRb else LightRb
    // ohne farbige Kopfleiste: helle Leiste mit normaler Schrift
    val c = if (vis.colorHeader) base else base.copy(header = base.surface, onHeader = base.onSurface)
    // Material-Bausteine (Switch, Slider, Menue, Blatt) bekommen dieselben Rollen
    val scheme = if (c.dark) darkColorScheme(
        primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primaryTint, onPrimaryContainer = c.onSurface,
        background = c.background, surface = c.surface, surfaceContainer = c.surface2, surfaceContainerHigh = c.surface3,
        surfaceContainerLow = c.surface, onSurface = c.onSurface, onSurfaceVariant = c.muted,
        outline = c.graphic, outlineVariant = c.line, secondaryContainer = c.primaryTint,
    ) else lightColorScheme(
        primary = c.primary, onPrimary = c.onPrimary, primaryContainer = c.primaryTint, onPrimaryContainer = c.onSurface,
        background = c.background, surface = c.surface, surfaceContainer = c.surface, surfaceContainerHigh = c.surface,
        surfaceContainerLow = c.surface, onSurface = c.onSurface, onSurfaceVariant = c.muted,
        outline = c.graphic, outlineVariant = c.line, secondaryContainer = c.primaryTint,
    )
    // Texte ohne eigene Farbe bekommen onSurface (sonst schwarz auf dunkel)
    CompositionLocalProvider(LocalRb provides c, LocalVis provides vis) {
        MaterialTheme(colorScheme = scheme) {
            CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.onSurface, content = content)
        }
    }
}
