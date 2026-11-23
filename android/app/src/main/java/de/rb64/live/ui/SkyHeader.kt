package de.rb64.live.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Kopfzeile nach Tageszeit: Morgen mit warmem Schimmer, Abend violett, Nacht dunkel mit ein paar Sternen,
 * tagsueber das gewohnte Petrol. Grundlage sind Sonnenauf- und -untergang in Muenster (wandern uebers Jahr).
 * Unten rechts immer eine sehr flache Huegel-Linie (Muensterland) als Wiedererkennung.
 */
object Sky {
    private const val LAT = 51.96
    private const val LON = 7.63

    /** Sonnenauf- und -untergang (epoch ms) fuer den Tag von [now] - Sonnenstand-Gleichung (NOAA, vereinfacht). */
    fun sunTimes(now: Long): Pair<Long, Long> {
        val jdNow = now / 86_400_000.0 + 2440587.5
        val n = floor(jdNow - 2451545.0 + 0.0008 + 0.5)            // Tag seit J2000 (Mittag)
        val jStar = n - LON / 360.0
        val m = (357.5291 + 0.98560028 * jStar) % 360.0
        val mr = m * PI / 180
        val c = 1.9148 * sin(mr) + 0.02 * sin(2 * mr) + 0.0003 * sin(3 * mr)
        val lambda = ((m + c + 180 + 102.9372) % 360.0) * PI / 180
        val jTransit = 2451545.0 + jStar + 0.0053 * sin(mr) - 0.0069 * sin(2 * lambda)
        val decl = asin(sin(lambda) * sin(23.4397 * PI / 180))
        val phi = LAT * PI / 180
        val cosW = (sin(-0.833 * PI / 180) - sin(phi) * sin(decl)) / (cos(phi) * cos(decl))
        val w = acos(cosW.coerceIn(-1.0, 1.0)) * 180 / PI
        fun ms(j: Double) = ((j - 2440587.5) * 86_400_000.0).toLong()
        return ms(jTransit - w / 360) to ms(jTransit + w / 360)
    }

    /** Gewichte 0..1 fuer Morgen, Abend, Nacht zum Zeitpunkt [now]. */
    fun weights(now: Long): Triple<Float, Float, Float> {
        val (rise, set) = sunTimes(now)
        val min = 60_000.0
        fun tri(t: Long, center: Long, before: Double, after: Double): Float {
            val d = (t - center) / min
            return when {
                d < -before || d > after -> 0f
                d <= 0 -> (1 + d / before).toFloat()
                else -> (1 - d / after).toFloat()
            }
        }
        val morning = tri(now, rise, 45.0, 75.0)
        val evening = tri(now, set, 75.0, 45.0)
        val toNight = ((now - (set + 45 * min.toLong())) / (30 * min)).toFloat()          // nach der Daemmerung
        val fromNight = (((rise - 45 * min.toLong()) - now) / (30 * min)).toFloat()       // vor der Morgendaemmerung
        val night = max(toNight, fromNight).coerceIn(0f, 1f)
        return Triple(morning, evening, night)
    }
}

private val WARM = Color(0xFFB4572A)
private val VIOLET = Color(0xFF5B2C83)
private val NIGHT = Color(0xFF06222A)
private val STARS = listOf(0.62f to 0.18f, 0.70f to 0.42f, 0.78f to 0.22f, 0.56f to 0.34f, 0.86f to 0.60f, 0.48f to 0.12f,
    0.93f to 0.30f, 0.66f to 0.70f)

/** Hintergrund der Kopfzeile: [base] (Petrol) je nach Tageszeit getoent. Ohne Effekt einfach einfarbig. */
@Composable
fun Modifier.skyBackground(base: Color, enabled: Boolean, now: Long = System.currentTimeMillis()): Modifier {
    if (!enabled) return this.drawBehind { drawRect(base) }
    val (morning, evening, night) = Sky.weights(now)
    val top = lerp(base, NIGHT, night * 0.85f)
    val bottom = lerp(lerp(lerp(top, WARM, morning * 0.75f), VIOLET, evening * 0.7f), NIGHT, night * 0.5f)
    return this.drawBehind {
        drawRect(Brush.linearGradient(listOf(top, top, bottom), start = Offset(0f, 0f), end = Offset(size.width * 0.55f, size.height * 1.6f)))
        // Nacht: ein paar Sterne im rechten Teil
        if (night > 0.2f) STARS.forEachIndexed { i, (x, y) ->
            drawCircle(Color.White.copy(alpha = 0.55f * night * (0.6f + (i % 3) * 0.2f)), radius = (1.1f + (i % 2) * 0.6f) * density,
                center = Offset(size.width * x, size.height * y))
        }
        // flache Huegel-Linie unten rechts (Muensterland), tagsueber kaum sichtbar
        val h = size.height
        val w = size.width
        val hill = Path().apply {
            moveTo(w * 0.45f, h); quadraticTo(w * 0.62f, h - 22 * density, w * 0.78f, h - 12 * density)
            quadraticTo(w * 0.90f, h - 6 * density, w, h - 18 * density); lineTo(w, h); close()
        }
        drawPath(hill, Color.Black.copy(alpha = 0.06f + 0.10f * max(evening, night) + 0.04f * morning))
        if (morning > 0.15f) drawCircle(Color(0xFFFDBA74).copy(alpha = 0.35f * morning), radius = 26 * density,
            center = Offset(w * 0.92f, h + 8 * density))
        if (night > 0.4f) drawCircle(Color(0xFFFDE68A).copy(alpha = 0.7f * min(1f, night)), radius = 6 * density,
            center = Offset(w * 0.90f, h * 0.30f))
    }
}
