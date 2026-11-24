package de.rb64.live.ride

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.rb64.live.ui.RbIcons
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Monatsrueckblick als Story-Folien. Er erscheint ab dem 1. eines Monats fuer den Vormonat - aber nur, wenn man
 * im Vormonat mindestens [MIN_RIDES] Fahrten aufgezeichnet hat. Am 1. (bis zum 7. nachgeholt) kommt einmal eine stille Meldung.
 * Sichtbar bleibt er bis zum Ende des Monats, in dem er kam.
 */
object MonthReviews {
    const val MIN_RIDES = 8
    private val MONTH = SimpleDateFormat("MMMM", Locale.GERMANY)

    /** Beginn (00:00 am 1.) des Monats, der [offset] Monate vor [now] liegt. */
    fun monthStart(now: Long, offset: Int = 0): Long = Calendar.getInstance().apply {
        timeInMillis = now; set(Calendar.DAY_OF_MONTH, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); add(Calendar.MONTH, -offset)
    }.timeInMillis

    data class Pending(val from: Long, val to: Long, val name: String, val rides: List<RideMeta>)

    /** Rueckblick fuer den Vormonat, falls die Bedingungen passen (nur aus der Fahrtenliste, ohne Dateien zu laden). */
    fun pending(history: List<RideMeta>, now: Long = System.currentTimeMillis()): Pending? {
        val from = monthStart(now, 1); val to = monthStart(now, 0)
        val inMonth = history.filter { it.started in from until to }
        if (inMonth.size < MIN_RIDES) return null
        return Pending(from, to, MONTH.format(Date(from)), inMonth)
    }

    const val CHANNEL = "rb64_review"
    private const val NOTIF_ID = 6464

    fun createChannel(ctx: Context) {
        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(android.app.NotificationChannel(CHANNEL, "Monatsrückblick", android.app.NotificationManager.IMPORTANCE_DEFAULT).apply {
            setSound(null, null); enableVibration(false)
            description = "Einmal im Monat: dein Rückblick auf den Vormonat"
        })
    }

    /**
     * Einmal je Monat melden, dass der Rueckblick da ist: am 1. ab 9 Uhr, bis zum 7. nachgeholt (Handy aus).
     * Wird vom regelmaessigen Hintergrund-Abruf aufgerufen; ohne Benachrichtigungs-Erlaubnis passiert nichts.
     */
    fun notifyIfDue(ctx: Context, now: Long = System.currentTimeMillis()) {
        val c = Calendar.getInstance().apply { timeInMillis = now }
        if (c.get(Calendar.DAY_OF_MONTH) > 7 || c.get(Calendar.HOUR_OF_DAY) < 9) return
        if (!de.rb64.live.Notifier.permitted(ctx)) return
        val key = "review_notified_%04d%02d".format(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1)
        val prefs = de.rb64.live.Repo.prefs(ctx)
        if (prefs.getBoolean(key, false)) return
        RideStore.init(ctx)
        val p = pending(RideStore.history.value, now) ?: return
        prefs.edit().putBoolean(key, true).apply()
        val open = android.content.Intent(ctx, de.rb64.live.MainActivity::class.java).putExtra("review", true)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pi = android.app.PendingIntent.getActivity(ctx, NOTIF_ID, open,
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val km = p.rides.sumOf { it.km }.toInt()
        val n = androidx.core.app.NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(de.rb64.live.R.drawable.ic_notify)
            .setContentTitle("Dein ${p.name} auf der RB64")
            .setContentText("${p.rides.size} Fahrten, $km km – dein Rückblick ist da.")
            .setContentIntent(pi).setAutoCancel(true)
            .build()
        runCatching { androidx.core.app.NotificationManagerCompat.from(ctx).notify(NOTIF_ID, n) }
    }

    data class Data(
        val name: String, val nextName: String, val rides: Int, val km: Double, val minutes: Int,
        val punctual: Int?, val crossings: Int, val longestCross: Pair<String, Int>?, val favRoute: String?, val favTime: String?,
        val newStations: List<String>, val rarest: Line?, val variants: List<String>, val doodles: Int, val farewell: String,
    )

    /** Laenge der RB64 Muenster-Enschede (Wikipedia: 64 km Strecke). */
    const val RB64_KM = 64.0
    private const val MS_LAT = 51.9607
    private const val MS_LON = 7.6261

    /** Vergleichsziele mit Koordinaten - die Entfernung ist Luftlinie ab Muenster und wird ausgerechnet, nicht geschaetzt. */
    private val CITIES = listOf(
        "Osnabrück" to (52.2799 to 8.0472), "Dortmund" to (51.5136 to 7.4653), "Köln" to (50.9375 to 6.9603),
        "Amsterdam" to (52.3676 to 4.9041), "Hamburg" to (53.5511 to 9.9937), "Berlin" to (52.5200 to 13.4050),
        "Paris" to (48.8566 to 2.3522), "Prag" to (50.0755 to 14.4378), "Wien" to (48.2082 to 16.3738), "Rom" to (41.9028 to 12.4964),
    ).map { (city, ll) -> airKm(ll.first, ll.second) to city }.sortedBy { it.first }

    /** Luftlinie ab Muenster in km (Haversine). */
    private fun airKm(lat: Double, lon: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat - MS_LAT); val dLon = Math.toRadians(lon - MS_LON)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(MS_LAT)) * Math.cos(Math.toRadians(lat)) * Math.sin(dLon / 2).let { it * it }
        return 2 * r * Math.asin(Math.sqrt(h))
    }

    data class Compare(val text: String, val city: String)

    /**
     * Vergleich als Hin- und Rueckweg (2x Luftlinie): "so weit wie Muenster -> Koeln und zurueck".
     * Reicht es noch nicht fuer hin und zurueck nach Osnabrueck, gilt die einfache Strecke.
     */
    fun compare(km: Double): Compare? {
        if (km < 1) return null
        val round = CITIES.lastOrNull { it.first * 2 <= km }
        if (round == null) {
            val one = CITIES.lastOrNull { it.first <= km } ?: return Compare("ein gutes Stück der RB64", "Enschede")
            return Compare("so weit wie Münster → ${one.second} (Luftlinie)", one.second)
        }
        val (d, city) = round
        if (city == "Rom" && km >= d * 4) return Compare("so weit wie ${(km / (d * 2)).toInt()}× Münster → Rom und zurück (Luftlinie)", "Rom")
        return Compare("so weit wie Münster → $city und zurück (Luftlinie)", city)
    }

    /** "2,2-mal die ganze RB64" */
    fun rb64Times(km: Double) = "%.1f".format(java.util.Locale.GERMANY, km / RB64_KM)

    /** Alles fuer die Folien (laedt die Fahrten des Monats - im Hintergrund aufrufen). */
    fun data(ctx: Context, p: Pending, history: List<RideMeta>): Data {
        val tickets = p.rides.mapNotNull { m -> RideStore.load(ctx, m.id)?.let { Tickets.of(ctx, it, history) } }
        val measured = p.rides.mapNotNull { it.arrDelta }
        val earlier = history.filter { it.started < p.from }.flatMap { Tickets.lineBetween(it.from, it.to) }.toSet()
        val now = p.rides.flatMap { Tickets.lineBetween(it.from, it.to) }.toSet()
        val crosses = tickets.mapNotNull { t -> t.crossing }
        val rarestLine = tickets.maxByOrNull { it.spruchRarity }?.let { t -> Extras.LINES.firstOrNull { it.text == t.spruch } }
        val cal = Calendar.getInstance()
        val favTime = p.rides.groupingBy {
            cal.timeInMillis = it.started; "%s %02d:%02d".format(listOf("So", "Mo", "Di", "Mi", "Do", "Fr", "Sa")[cal.get(Calendar.DAY_OF_WEEK) - 1],
                cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE) / 10 * 10)
        }.eachCount().maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.key
        val next = SimpleDateFormat("MMMM", Locale.GERMANY).format(Date(p.to))
        return Data(
            name = p.name, nextName = SimpleDateFormat("MMMM", Locale.GERMANY).format(Date(monthStart(p.to, -1))),
            rides = p.rides.size, km = p.rides.sumOf { it.km }, minutes = p.rides.sumOf { it.minutes.toInt() },
            punctual = if (measured.isEmpty()) null else measured.count { it <= 5 } * 100 / measured.size,
            crossings = crosses.size, longestCross = crosses.maxByOrNull { it.second },
            favRoute = p.rides.groupingBy { "${it.from} → ${it.to}" }.eachCount().maxByOrNull { it.value }?.key,
            favTime = favTime, newStations = (now - earlier).toList(),
            rarest = rarestLine, variants = tickets.mapNotNull { it.variant?.name }.distinct(), doodles = tickets.count { it.doodle },
            farewell = "Wir erreichen den $next planmäßig.",
        )
    }
}
