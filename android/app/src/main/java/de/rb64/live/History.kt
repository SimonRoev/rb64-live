package de.rb64.live

import android.content.Context
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.google.firebase.firestore.FirebaseFirestore
import de.rb64.live.ui.Dim
import de.rb64.live.ui.Rb
import de.rb64.live.ui.Ts
import org.json.JSONObject
import java.io.File
import java.util.Calendar
import java.util.Locale

/** Verlauf einer Abfahrt: Werte der letzten (bis zu) 7 gleichartigen Tage, aelteste zuerst. */
data class HistSeries(
    val station: String, val time: String, val weekday: Boolean,
    val days: List<String>,          // "Mo", "Di", ...
    val values: List<Int?>,          // Verspaetung in min, CANCELLED = Ausfall, null = keine Daten
) {
    val known: List<Int> get() = values.filterNotNull().filter { it != CANCELLED }
    val cancelled: Int get() = values.count { it == CANCELLED }
    val daysWithData: Int get() = values.count { it != null }
    val median: Int get() = known.sorted().let { if (it.isEmpty()) 0 else if (it.size % 2 == 1) it[it.size / 2] else (it[it.size / 2 - 1] + it[it.size / 2] + 1) / 2 }
    val art: String get() = if (weekday) "Werktage" else "Wochenendtage"

    companion object { const val CANCELLED = -1 }
}

/**
 * Laedt den Tagesverlauf (Firestore public/hist, von der VM einmal am Tag geschrieben) hoechstens
 * alle 6 Stunden und haelt ihn als Datei vor. Nicht im Push, damit dieser klein bleibt.
 */
object HistStore {
    private const val TAG = "RB64Hist"
    private const val FILE = "hist.json"
    private const val EVERY_MS = 6 * 3600_000L
    private var cache: JSONObject? = null
    /** Compose-Zaehler: steigt, wenn neue Daten da sind (loest Neuzeichnen aus). */
    var version by mutableIntStateOf(0)
        private set

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun data(ctx: Context): JSONObject? {
        cache?.let { return it }
        val f = file(ctx)
        if (!f.exists()) return null
        return runCatching { JSONObject(f.readText()) }.getOrNull()?.also { cache = it }
    }

    fun refreshIfDue(ctx: Context, force: Boolean = false) {
        val p = Repo.prefs(ctx)
        val last = p.getLong("hist_fetched", 0)
        if (!force && file(ctx).exists() && System.currentTimeMillis() - last < EVERY_MS) return
        val app = ctx.applicationContext
        Cloud.withUserPublic {
            FirebaseFirestore.getInstance().collection("public").document("hist").get()
                .addOnSuccessListener { d ->
                    val json = d.getString("json") ?: return@addOnSuccessListener
                    runCatching { JSONObject(json) }.onSuccess {
                        file(app).writeText(json)
                        cache = it
                        Repo.prefs(app).edit().putLong("hist_fetched", System.currentTimeMillis()).apply()
                        version++
                    }
                }
                .addOnFailureListener { Log.w(TAG, "Verlauf laden fehlgeschlagen", it) }
        }
    }

    /** Verlauf fuer die Abfahrt [row] am Einstieg der Strecke [rel]; null bei weniger als 3 Tagen mit Daten. */
    fun series(ctx: Context, rel: Rel, row: Row, weekday: Boolean): HistSeries? {
        @Suppress("UNUSED_VARIABLE") val v = version            // Compose: bei neuen Daten neu lesen
        if (row.bus) return null
        val from = Line.seqOf(rel.from) ?: return null
        val to = Line.seqOf(rel.to) ?: return null
        val kind = data(ctx)?.optJSONObject(if (weekday) "w" else "e") ?: return null
        val dir = if (to > from) "ES" else "MS"
        val arr = kind.optJSONObject("v")?.optJSONObject(from.toString())?.optJSONArray("$dir ${row.depPlanned}") ?: return null
        val dates = kind.optJSONArray("days") ?: return null
        val values = (0 until arr.length()).map { i ->
            when {
                arr.isNull(i) -> null
                arr.opt(i) == "x" -> HistSeries.CANCELLED
                else -> arr.optInt(i)
            }
        }
        val days = (0 until dates.length()).map { dayShort(dates.optString(it)) }
        val s = HistSeries(rel.from, row.depPlanned, weekday, days, values)
        return s.takeIf { it.daysWithData >= 3 && days.size == values.size }
    }

    private fun dayShort(iso: String): String = runCatching {
        val (y, m, d) = iso.split("-").map { it.toInt() }
        val c = Calendar.getInstance().apply { set(y, m - 1, d) }
        c.getDisplayName(Calendar.DAY_OF_WEEK, Calendar.SHORT, Locale.GERMANY)?.take(2) ?: ""
    }.getOrDefault("")
}

@Composable
private fun barColor(v: Int): Color = when {
    v >= 6 -> Rb.c.bad
    v >= 3 -> Rb.c.warn
    else -> Rb.c.graphic
}

private fun plus(v: Int) = if (v >= 1) "+$v" else "±0"

/** Kurzfassung, z. B. "meist +10 · Spanne +6 bis +26 · 1× ausgefallen". */
fun HistSeries.summary(long: Boolean): String {
    val k = known
    val parts = mutableListOf(if (median >= 1) "meist +$median min" else "meist pünktlich")
    if (long && k.size >= 2 && k.max() > k.min()) parts += "zwischen ${plus(k.min())} und ${plus(k.max())} min"
    if (cancelled > 0) parts += "${cancelled}× ausgefallen" else if (long) parts += "nie ausgefallen"
    return parts.joinToString(" · ")
}

/** Detailblatt: "Wie puenktlich war diese Abfahrt?" mit Wert ueber jedem Balken, Medianlinie und "heute". */
@Composable
fun HistChart(s: HistSeries, today: Int?) {
    var open by rememberSaveable { mutableStateOf(true) }
    de.rb64.live.ui.Collapsible("Wie pünktlich war diese Abfahrt?", open, { open = !open },
        Modifier.fillMaxWidth().background(Rb.c.surface, RoundedCornerShape(Dim.rCard))
            .border(1.dp, Rb.c.line, RoundedCornerShape(Dim.rCard)).padding(horizontal = 14.dp, vertical = 4.dp)) {
        Text("${s.station} ${s.time} · Verspätung in Minuten, letzte ${s.daysWithData} ${s.art}", style = Ts.small, color = Rb.c.muted)
        if (!open) {
            Text(s.summary(true), style = Ts.small, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            return@Collapsible
        }
        val chartH = 112.dp
        val labelH = 18.dp
        // Skala 10..20 min: ein Ausreisser (z. B. +55) soll die anderen Tage nicht plattdruecken
        val maxV = ((s.known + listOfNotNull(today)).maxOrNull() ?: 10).coerceIn(10, 20)
        fun barH(v: Int): Dp = (chartH - labelH) * (v.coerceIn(0, maxV).toFloat() / maxV)
        val med = s.median
        val desc = s.days.zip(s.values).joinToString(", ") { (d, v) ->
            "$d " + when (v) { null -> "keine Daten"; HistSeries.CANCELLED -> "Ausfall"; else -> plus(v) }
        } + (today?.let { ", heute Prognose ${plus(it)}" } ?: "") + ". Median ${plus(med)}."
        Column(Modifier.fillMaxWidth().padding(top = Dim.s12).semantics { contentDescription = desc }) {
            Box(Modifier.fillMaxWidth().height(chartH)) {
                // Medianlinie (gestrichelt) + Grundlinie
                val lineColor = Rb.c.muted; val baseColor = Rb.c.line
                Canvas(Modifier.fillMaxSize()) {
                    val y = size.height - (barH(med)).toPx()
                    if (med >= 1) drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
                    drawLine(baseColor, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1.dp.toPx())
                }
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(Dim.s8), verticalAlignment = Alignment.Bottom) {
                    val top = s.known.maxOrNull()
                    s.values.forEach { v ->
                        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                            when (v) {
                                null -> Text("–", style = Ts.small, color = Rb.c.muted)
                                HistSeries.CANCELLED -> {
                                    Text("✕", style = Ts.smallStrong, color = Rb.c.bad)
                                    Box(Modifier.fillMaxWidth().height((chartH - labelH) * 0.5f)
                                        .border(1.5.dp, Rb.c.bad, RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)))
                                }
                                else -> {
                                    Text(plus(v), style = if (v == top && v >= 6) Ts.smallStrong else Ts.small,
                                        color = if (v == top && v >= 6) Rb.c.bad else Rb.c.muted)
                                    Box(Modifier.fillMaxWidth().height(barH(v).coerceAtLeast(2.dp))
                                        .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)).background(barColor(v).copy(alpha = .75f)))
                                }
                            }
                        }
                    }
                    if (today != null) Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(plus(today), style = Ts.smallStrong, color = Rb.c.primary)
                        val c = barColor(today)
                        Canvas(Modifier.fillMaxWidth().height(barH(today).coerceAtLeast(2.dp))) {
                            drawRect(c, style = Stroke(width = 2.dp.toPx(),
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 3.dp.toPx()))))
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
                s.days.forEach { Text(it, style = Ts.small, color = Rb.c.muted, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
                if (today != null) Text("heute", style = Ts.small.copy(fontWeight = FontWeight.SemiBold), color = Rb.c.primary,
                    textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            }
        }
        Text(s.summary(true).replaceFirstChar { it.uppercase() },
            style = Ts.text, modifier = Modifier.padding(top = Dim.s8).fillMaxWidth())
        Text("Balken: so war es an den letzten Tagen · gestrichelt: heute laut Prognose · Linie: der übliche Wert",
            style = Ts.small, color = Rb.c.muted, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
    }
}
