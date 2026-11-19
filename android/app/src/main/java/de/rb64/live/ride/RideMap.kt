package de.rb64.live.ride

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.rb64.live.LineGeo
import de.rb64.live.ui.Dim
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbCard
import de.rb64.live.ui.Rb64Colors
import de.rb64.live.ui.Ts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

private val HMS = SimpleDateFormat("HH:mm", Locale.GERMANY)

/** Aufbereitete Fahrt: projizierte Punkte (Meter), Strecke bis hierhin, geglaettete Geschwindigkeit. */
private class Track(s: RideState) {
    // gleiche Projektion wie LineGeo (passt dann zur Gleislinie)
    private val kx = 111_320.0 * cos(Math.toRadians(52.08))
    private val ky = 110_540.0
    fun x(lon: Double) = lon * kx
    fun y(lat: Double) = lat * ky

    val pts = s.points.filter { it.acc <= 60f }
    val px = DoubleArray(pts.size) { x(pts[it].lon) }
    val py = DoubleArray(pts.size) { y(pts[it].lat) }
    val dist = DoubleArray(pts.size).also { d -> for (i in 1 until pts.size) d[i] = d[i - 1] + hypot(px[i] - px[i - 1], py[i] - py[i - 1]) }
    val kmh: FloatArray = run {
        val raw = FloatArray(pts.size) { i -> Analyzer.speedOf(pts.getOrNull(i - 1), pts[i]) * 3.6f }
        FloatArray(pts.size) { i -> (max(0, i - 1)..minOf(pts.size - 1, i + 1)).map { raw[it] }.average().toFloat() }
    }
    val total = dist.lastOrNull() ?: 0.0
    val maxKmh = kmh.maxOrNull() ?: 0f
    /** Standzeit nach der Abfahrt am Start (Halte unterwegs). */
    val stopMin = s.events.drop(1).sumOf { it.seconds ?: 0 } / 60

    /** Halte der Fahrt mit dem naechstgelegenen Messpunkt. */
    val stations: List<Pair<Station, Int>> = s.info.stops.mapNotNull { n ->
        val st = Stations.byName(n) ?: return@mapNotNull null
        if (pts.isEmpty()) return@mapNotNull null
        val sx = x(st.lon); val sy = y(st.lat)
        st to pts.indices.minBy { hypot(px[it] - sx, py[it] - sy) }
    }

    fun nearestIdx(t: Long): Int = if (pts.isEmpty()) 0 else pts.indices.minBy { kotlin.math.abs(pts[it].t - t) }
    fun idxAtDist(d: Double): Int = dist.indices.minByOrNull { kotlin.math.abs(dist[it] - d) } ?: 0

    /** Wo liegt Punkt i? "in Altenberge" bzw. "nach Altenberge". */
    fun whereText(i: Int): String {
        val near = stations.minByOrNull { kotlin.math.abs(dist[it.second] - dist[i]) }
        if (near != null && kotlin.math.abs(dist[near.second] - dist[i]) < 350) return "in ${near.first.name}"
        val before = stations.lastOrNull { it.second <= i }?.first?.name
        val after = stations.firstOrNull { it.second > i }?.first?.name
        return when {
            before != null && after != null -> "$before → $after"
            before != null -> "nach $before"
            after != null -> "vor $after"
            else -> ""
        }
    }
}

/** Mittleres Tempo: aufgehellte Grundfarbe (unterscheidet sich in der Helligkeit von "schnell"). */
private fun mid(c: Rb64Colors) = lerp(c.primary, if (c.dark) Color.Black else Color.White, 0.5f)

/** Farbe je Tempo: steht = rot, langsam = orange, mittel = helles Petrol, schnell (100+) = Grundfarbe. */
private fun speedColor(c: Rb64Colors, kmh: Float): Color = when {
    kmh < 5f -> c.bad
    kmh < 35f -> lerp(c.bad, c.warn, (kmh - 5f) / 30f)
    kmh < 70f -> lerp(c.warn, mid(c), (kmh - 35f) / 35f)
    kmh < 100f -> lerp(mid(c), c.primary, (kmh - 70f) / 30f)
    else -> c.primary
}

/** Karte der Fahrt (Tempo als Farbe) und Tempo-Profil ueber die Strecke; Ziehen im Profil zeigt die Stelle auf der Karte. */
@Composable
fun RideMapCard(s: RideState) {
    val ctx = LocalContext.current
    LineGeo.load(ctx)
    val tr = remember(s.points.size) { Track(s) }
    if (tr.pts.size < 2) return
    var sel by remember { mutableStateOf<Int?>(null) }
    val c = Rb.c
    val tm = rememberTextMeasurer()

    RbCard(padding = Dim.s12) {
        // Anzeige: gewaehlte Stelle oder Kennzahlen
        val i = sel
        Box(Modifier.fillMaxWidth().height(44.dp).padding(horizontal = Dim.s4), contentAlignment = Alignment.CenterStart) {
            if (i != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
                Text("${tr.kmh[i].toInt()} km/h", style = Ts.title, color = if (tr.kmh[i] >= 35f) c.primary else speedColor(c, tr.kmh[i]))
                Column {
                    Text(tr.whereText(i), style = Ts.smallStrong, maxLines = 1)
                    Text("${HMS.format(Date(tr.pts[i].t))} · km ${"%.1f".format(tr.dist[i] / 1000)}", style = Ts.small, color = c.muted)
                }
            } else {
                val moving = tr.kmh.indices.filter { tr.kmh[it] >= 5f }
                val avg = if (moving.size > 1) moving.map { tr.kmh[it] }.average().toInt() else 0
                Text("${"%.1f".format(tr.total / 1000)} km · Ø $avg km/h in Fahrt · ${tr.stopMin} min gestanden",
                    style = Ts.smallStrong, color = c.onSurface)
            }
        }

        // Karte
        Canvas(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(Dim.rButton)).background(c.background)
            .pointerInput(tr) {
                detectTapGestures { o -> sel = nearestOnMap(tr, size.width.toFloat(), size.height.toFloat(), density, o) }
            }) {
            drawRideMap(tr, s, c, tm, sel)
        }
        Spacer(Modifier.height(Dim.s12))

        // Tempo-Profil
        Canvas(Modifier.fillMaxWidth().height(150.dp)
            .pointerInput(tr) {
                fun pick(x: Float) { sel = tr.idxAtDist(profileDist(tr, size.width.toFloat(), density, x)) }
                detectTapGestures { pick(it.x) }
            }
            .pointerInput(tr) {
                fun pick(x: Float) { sel = tr.idxAtDist(profileDist(tr, size.width.toFloat(), density, x)) }
                detectDragGestures(onDragStart = { pick(it.x) }) { ch, _ -> pick(ch.position.x) }
            }) {
            drawProfile(tr, s, c, tm, sel)
        }

        // Legende
        Row(Modifier.fillMaxWidth().padding(top = Dim.s8, start = Dim.s4), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
            Box(Modifier.width(72.dp).height(6.dp).clip(RoundedCornerShape(3.dp))
                .background(Brush.horizontalGradient(listOf(c.bad, c.warn, mid(c), c.primary))))
            Text("0 → 100+ km/h", style = Ts.small, color = c.muted)
            Spacer(Modifier.weight(1f))
            Text(if (sel == null) "Tippen oder ziehen" else "", style = Ts.small, color = c.muted)
        }
    }
}

/* ------------------------------------------------------------------ Karte */

private class MapFit(val minX: Double, val maxY: Double, val s: Double, val ox: Float, val oy: Float) {
    fun o(x: Double, y: Double) = Offset(ox + ((x - minX) * s).toFloat(), oy + ((maxY - y) * s).toFloat())
}

private fun mapFit(tr: Track, w: Float, h: Float, pad: Float): MapFit {
    var minX = tr.px.min(); var maxX = tr.px.max(); var minY = tr.py.min(); var maxY = tr.py.max()
    // etwas Rand, damit Namen Platz haben
    val mx = (maxX - minX) * 0.06 + 200; val my = (maxY - minY) * 0.06 + 200
    minX -= mx; maxX += mx; minY -= my; maxY += my
    val s = minOf((w - 2 * pad) / (maxX - minX), (h - 2 * pad) / (maxY - minY))
    val ox = (w - ((maxX - minX) * s).toFloat()) / 2
    val oy = (h - ((maxY - minY) * s).toFloat()) / 2
    return MapFit(minX, maxY, s, ox, oy)
}

private fun nearestOnMap(tr: Track, w: Float, h: Float, density: Float, o: Offset): Int {
    val f = mapFit(tr, w, h, 16 * density)
    return tr.pts.indices.minBy { val p = f.o(tr.px[it], tr.py[it]); hypot((p.x - o.x).toDouble(), (p.y - o.y).toDouble()) }
}

private fun DrawScope.drawRideMap(tr: Track, s: RideState, c: Rb64Colors, tm: TextMeasurer, sel: Int?) {
    val f = mapFit(tr, size.width, size.height, 16.dp.toPx())

    // Gleis (ganze Linie, dezent) als Orientierung
    val line = LineGeo.all()
    if (line.isNotEmpty()) {
        val p = Path().apply { line.forEachIndexed { i, q -> val o = f.o(q.x, q.y); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        drawPath(p, c.line, style = Stroke(9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // Fahrt: Abschnitte nach Tempo gefaerbt
    val w = 6.dp.toPx()
    for (i in 1 until tr.pts.size) {
        drawLine(speedColor(c, (tr.kmh[i] + tr.kmh[i - 1]) / 2), f.o(tr.px[i - 1], tr.py[i - 1]), f.o(tr.px[i], tr.py[i]), w, cap = StrokeCap.Round)
    }

    // Halte auf freier Strecke
    s.events.filter { it.station == null }.forEach { ev ->
        val o = f.o(tr.x(ev.lon), tr.y(ev.lat))
        drawCircle(c.surface, 8.dp.toPx(), o); drawCircle(c.bad, 8.dp.toPx(), o, style = Stroke(2.5.dp.toPx()))
        val sec = ev.seconds ?: 0
        label(tm, "${sec / 60}:${"%02d".format(sec % 60)}", o + Offset(11.dp.toPx(), -8.dp.toPx()), c.bad, bold = true)
    }
    // Markierungen
    s.tags.forEach { t ->
        val la = t.lat ?: return@forEach; val lo = t.lon ?: return@forEach
        val o = f.o(tr.x(lo), tr.y(la)); val r = 6.dp.toPx()
        val d = Path().apply { moveTo(o.x, o.y - r); lineTo(o.x + r, o.y); lineTo(o.x, o.y + r); lineTo(o.x - r, o.y); close() }
        drawPath(d, c.warn); drawPath(d, c.surface, style = Stroke(1.5.dp.toPx()))
    }

    // Bahnhoefe mit Namen (abwechselnd links/rechts, damit sie sich nicht ueberdecken)
    tr.stations.forEachIndexed { k, (st, _) ->
        val o = f.o(tr.x(st.lon), tr.y(st.lat))
        val end = k == 0 || k == tr.stations.size - 1
        drawCircle(c.surface, (if (end) 7 else 5).dp.toPx(), o)
        drawCircle(if (end) c.onSurface else c.graphic, (if (end) 7 else 5).dp.toPx(), o, style = Stroke(2.dp.toPx()))
        val lay = tm.measure(st.name, TextStyle(fontSize = 11.sp, fontWeight = if (end) FontWeight.Bold else FontWeight.Medium))
        val right = k % 2 == 0
        var x = if (right) o.x + 10.dp.toPx() else o.x - 10.dp.toPx() - lay.size.width
        x = x.coerceIn(2f, size.width - lay.size.width - 2f)
        val tl = Offset(x, o.y - lay.size.height / 2f)
        drawRoundRect(c.surface.copy(alpha = 0.85f), tl - Offset(3f, 1f), Size(lay.size.width + 6f, lay.size.height + 2f),
            androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        drawText(lay, color = c.onSurface, topLeft = tl)
    }

    // gewaehlte Stelle
    sel?.let { i ->
        val o = f.o(tr.px[i], tr.py[i])
        drawCircle(speedColor(c, tr.kmh[i]).copy(alpha = 0.25f), 16.dp.toPx(), o)
        drawCircle(c.surface, 8.dp.toPx(), o)
        drawCircle(speedColor(c, tr.kmh[i]), 6.dp.toPx(), o)
    }
}

private fun DrawScope.label(tm: TextMeasurer, t: String, at: Offset, color: Color, bold: Boolean = false) {
    val lay = tm.measure(t, TextStyle(fontSize = 11.sp, fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal))
    drawText(lay, color = color, topLeft = at)
}

/* ------------------------------------------------------------------ Tempo-Profil */

private const val PROFILE_LEFT_DP = 30f

private fun profileDist(tr: Track, w: Float, density: Float, x: Float): Double {
    val l = PROFILE_LEFT_DP * density
    return ((x - l) / (w - l)).coerceIn(0f, 1f) * tr.total
}

private fun DrawScope.drawProfile(tr: Track, s: RideState, c: Rb64Colors, tm: TextMeasurer, sel: Int?) {
    val left = PROFILE_LEFT_DP.dp.toPx()
    val bottom = size.height - 30.dp.toPx()       // Platz fuer Bahnhofsnamen
    val top = 6.dp.toPx()
    val vmax = max(60f, ((tr.maxKmh / 20f).toInt() + 1) * 20f)
    fun X(d: Double) = left + (d / tr.total).toFloat() * (size.width - left)
    fun Y(v: Float) = bottom - (v / vmax) * (bottom - top)

    // Raster
    var g = 0f
    while (g <= vmax) {
        drawLine(c.line, Offset(left, Y(g)), Offset(size.width, Y(g)), 1.dp.toPx())
        label(tm, g.toInt().toString(), Offset(0f, Y(g) - 7.dp.toPx()), c.muted)
        g += if (vmax > 100) 40f else 20f
    }

    // Halte auf freier Strecke als rote Baender
    s.events.filter { it.station == null }.forEach { ev ->
        val a = tr.nearestIdx(ev.arr); val b = tr.nearestIdx(ev.dep ?: ev.arr)
        val x0 = X(tr.dist[a]) - 3.dp.toPx(); val x1 = X(tr.dist[b]) + 3.dp.toPx()
        drawRect(c.bad.copy(alpha = 0.15f), Offset(x0, top), Size(max(4f, x1 - x0), bottom - top))
    }

    // Bahnhoefe: senkrechte Linien und Namen (zwei Reihen gegen Ueberlappung)
    tr.stations.forEachIndexed { k, (st, idx) ->
        val x = X(tr.dist[idx])
        drawLine(c.graphic.copy(alpha = 0.5f), Offset(x, top), Offset(x, bottom), 1.dp.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
        val lay = tm.measure(st.name, TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Medium))
        val lx = (x - lay.size.width / 2f).coerceIn(left - 6.dp.toPx(), size.width - lay.size.width)
        drawText(lay, color = c.muted, topLeft = Offset(lx, bottom + (if (k % 2 == 0) 2 else 15).dp.toPx()))
    }

    // Flaeche + Linie nach Tempo gefaerbt
    val area = Path().apply {
        moveTo(X(0.0), bottom)
        for (i in tr.pts.indices) lineTo(X(tr.dist[i]), Y(tr.kmh[i]))
        lineTo(X(tr.total), bottom); close()
    }
    drawPath(area, Brush.verticalGradient(listOf(c.primary.copy(alpha = 0.22f), c.primary.copy(alpha = 0.02f)), top, bottom))
    for (i in 1 until tr.pts.size) {
        drawLine(speedColor(c, (tr.kmh[i] + tr.kmh[i - 1]) / 2), Offset(X(tr.dist[i - 1]), Y(tr.kmh[i - 1])),
            Offset(X(tr.dist[i]), Y(tr.kmh[i])), 2.5.dp.toPx(), cap = StrokeCap.Round)
    }

    // Auswahl
    sel?.let { i ->
        val x = X(tr.dist[i]); val y = Y(tr.kmh[i])
        drawLine(c.onSurface.copy(alpha = 0.6f), Offset(x, top), Offset(x, bottom), 1.5.dp.toPx())
        drawCircle(c.surface, 6.dp.toPx(), Offset(x, y)); drawCircle(speedColor(c, tr.kmh[i]), 4.5.dp.toPx(), Offset(x, y))
    }
}
