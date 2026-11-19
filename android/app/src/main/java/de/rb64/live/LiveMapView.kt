package de.rb64.live

import android.content.Context
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import de.rb64.live.ui.Banner
import de.rb64.live.ui.Dim
import de.rb64.live.ui.ListCard
import de.rb64.live.ui.LocalVis
import de.rb64.live.ui.Pill
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbCard
import de.rb64.live.ui.RbIcons
import de.rb64.live.ui.SectionHeader
import de.rb64.live.ui.Ts
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/**
 * Streckenverlauf der RB64 (vereinfachte OSM-Geometrie, assets/rb64_line.json), projiziert in Meter
 * (x = Osten, y = Norden). Halte sind Eckpunkte der Linie; Positionen (seq mit Nachkommastellen)
 * werden entlang der Gleise zwischen den beiden Halten interpoliert.
 */
object LineGeo {
    data class P(val x: Double, val y: Double)

    private const val LAT0 = 52.08
    private var pts: List<P> = emptyList()
    private var cum: DoubleArray = DoubleArray(0)
    private var stIdx: Map<Int, Int> = emptyMap()

    fun load(ctx: Context) {
        if (pts.isNotEmpty()) return
        val o = JSONObject(ctx.assets.open("rb64_line.json").bufferedReader().use { it.readText() })
        val k = 111_320.0 * cos(Math.toRadians(LAT0))
        val a = o.getJSONArray("pts")
        pts = List(a.length()) { i -> a.getJSONArray(i).let { P(it.getDouble(1) * k, it.getDouble(0) * 110_540.0) } }
        cum = DoubleArray(pts.size).also { c -> for (i in 1 until pts.size) c[i] = c[i - 1] + hypot(pts[i].x - pts[i - 1].x, pts[i].y - pts[i - 1].y) }
        val s = o.getJSONArray("stations")
        stIdx = (0 until s.length()).associate { s.getJSONObject(it).let { j -> j.getInt("seq") to j.getInt("i") } }
    }

    /** Entfernung entlang der Gleise vom Anfang (Muenster Hbf) bis zum Halt bzw. zur Position seq. */
    fun distOf(seq: Int): Double = cum[stIdx[seq] ?: 0]

    fun distAt(seq: Double): Double {
        val s0 = floor(seq).toInt().coerceIn(1, 14)
        val s1 = ceil(seq).toInt().coerceIn(1, 14)
        return distOf(s0) + (seq - s0) * (distOf(s1) - distOf(s0))
    }

    /** Eckpunkte zwischen zwei Halten mit ihrer Entfernung entlang der Gleise. */
    fun vertices(lo: Int, hi: Int): List<Pair<Double, P>> =
        ((stIdx[lo] ?: 0)..(stIdx[hi] ?: pts.size - 1)).map { cum[it] to pts[it] }

    fun all(): List<P> = pts

    fun point(seq: Double): P {
        val d = distAt(seq)
        var i = 0
        while (i < pts.size - 2 && cum[i + 1] < d) i++
        val seg = cum[i + 1] - cum[i]
        val f = if (seg <= 0) 0.0 else ((d - cum[i]) / seg).coerceIn(0.0, 1.0)
        return P(pts[i].x + f * (pts[i + 1].x - pts[i].x), pts[i].y + f * (pts[i + 1].y - pts[i].y))
    }
}

/**
 * Live-Linie als senkrechtes Gleisband: Enschede-Richtung oben, Muenster unten.
 * Abstaende proportional zur Strecke (mind. 44 dp), der echte Verlauf nur als leichter seitlicher Versatz.
 * Dazu Minikarte mit der echten Geometrie, Kreuzungshinweise und "dein Zug".
 */
@Composable
fun LiveMapCard(map: LiveMap, lo: Int, hi: Int, crossings: List<Crossing>, myTrain: String?) {
    val ctx = LocalContext.current
    remember { LineGeo.load(ctx) }
    val c = Rb.c
    val vis = LocalVis.current
    val tm = rememberTextMeasurer()

    // Kreuzungen zuerst: das wichtigste Signal auf der eingleisigen Strecke
    crossings.forEach { x -> Banner("${x.train}: ${x.text}", kind = "warn") }

    // ---------------------------------------------------------------- Geometrie in dp
    val seqs = (lo..hi).toList()
    val n = seqs.size
    val dists = seqs.map { LineGeo.distOf(it) }
    val totalM = max(dists.last() - dists.first(), 1.0)
    val k = 60.0 * (n - 1) / totalM
    // streckentreu: proportional (mind. 44 dp); schematisch: gleiche Abstaende
    val gaps = (0 until n - 1).map { if (vis.mapTrue) max(44.0, (dists[it + 1] - dists[it]) * k) else 52.0 }
    val top = 48.0
    val yS = DoubleArray(n).also { y -> y[n - 1] = top; for (i in n - 2 downTo 0) y[i] = y[i + 1] + gaps[i] }
    val heightDp = yS[0] + 28.0
    fun yOfDist(d: Double): Double {
        if (d <= dists.first()) return yS[0]
        for (i in 0 until n - 1) if (d <= dists[i + 1]) {
            val f = (d - dists[i]) / max(dists[i + 1] - dists[i], 1.0)
            return yS[i] + f * (yS[i + 1] - yS[i])
        }
        return yS[n - 1]
    }
    // seitlicher Versatz: Abstand zur Sehne zwischen den Endpunkten, auf hoechstens 20 dp skaliert
    val verts = LineGeo.vertices(lo, hi)
    val a = verts.first().second; val b = verts.last().second
    val len = max(hypot(b.x - a.x, b.y - a.y), 1.0)
    val devs = verts.map { (_, p) -> ((b.x - a.x) * (p.y - a.y) - (b.y - a.y) * (p.x - a.x)) / len }
    val maxDev = devs.maxOf { abs(it) }
    val amp = if (vis.mapTrue && maxDev > 50) 20.0 else 0.0
    fun xOffAt(d: Double): Double {
        if (amp == 0.0) return 0.0
        var i = 0
        while (i < verts.size - 2 && verts[i + 1].first < d) i++
        val j = (i + 1).coerceAtMost(verts.size - 1)
        val d0 = verts[i].first; val d1 = verts[j].first
        val f = if (d1 <= d0) 0.0 else ((d - d0) / (d1 - d0)).coerceIn(0.0, 1.0)
        val dev = devs[i] + f * (devs[j] - devs[i])
        return -dev / maxDev * amp                           // links der Fahrtrichtung (Westen) = links
    }

    // Zugpositionen gleiten bei neuen Daten (abschaltbar)
    val anim = map.trains.associate { t ->
        t.train to key(t.train) {
            animateFloatAsState(t.seq.toFloat(), if (vis.animations) tween(700) else snap(), label = "zug").value.toDouble()
        }
    }

    RbCard(padding = 0.dp) {
        // ------------------------------------------------------------ Kopf: Abschnitt + Minikarte
        Row(Modifier.fillMaxWidth().padding(start = Dim.s16, end = Dim.s12, top = Dim.s12), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("${Line.name(lo)} – ${Line.name(hi)}", style = Ts.textStrong)
                Text("$n Halte · ${"%.0f".format(totalM / 1000)} km · ${map.trains.size} ${if (map.trains.size == 1) "Zug" else "Züge"}",
                    style = Ts.small, color = c.muted)
            }
            if (vis.mapMini) MiniMap(lo, hi, map, Modifier.size(88.dp))
        }

        val crossSeqs = crossings.map { it.seq }.toSet()
        Canvas(Modifier.fillMaxWidth().height(heightDp.dp).semantics {
            contentDescription = "Linie ${Line.name(lo)} bis ${Line.name(hi)}. " +
                map.trains.joinToString { "${it.train} nach ${if (it.toEnschede) "Enschede" else "Münster"}" + (if (it.delay >= 1) " plus ${it.delay}" else "") }
        }) {
            val px = density
            val lineX = 128.0
            fun pos(d: Double) = Offset(((lineX + xOffAt(d)) * px).toFloat(), (yOfDist(d) * px).toFloat())
            val colX = ((lineX + 36) * px).toFloat()
            val colW = (size.width - colX - 20 * px) / 2

            // Spaltenkoepfe: links Richtung Enschede (nach oben), rechts Richtung Muenster
            listOf(true, false).forEachIndexed { k2, up ->
                val x0 = colX + k2 * (colW + 8 * px)
                drawChevron(Offset(x0 + 6 * px, 18 * px), up, c.muted, 4.5f * px)
                val lay = tm.measure(Line.name(if (up) hi else lo), Ts.small.copy(color = c.muted),
                    constraints = Constraints(maxWidth = (colW - 16 * px).toInt().coerceAtLeast(1)), maxLines = 1)
                drawText(lay, topLeft = Offset(x0 + 16 * px, 18 * px - lay.size.height / 2f))
            }

            // Kreuzungshalte mit wartendem Zug: Band ueber die ganze Breite
            seqs.forEachIndexed { i, s ->
                if (s in crossSeqs) drawRoundRect(c.warnBg, Offset(8 * px, ((yS[i] - 20) * px).toFloat()),
                    Size(size.width - 16 * px, 40 * px), CornerRadius(8 * px))
            }

            // Gleisband: Rand + Linie
            val path = Path().apply {
                verts.forEachIndexed { i, (d, _) -> val o = pos(d); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            }
            drawPath(path, c.primary.copy(alpha = if (c.dark) 0.6f else 0.45f), style = Stroke(6 * px, cap = StrokeCap.Round, join = StrokeJoin.Round))

            // Halte + Namen links
            map.stations.forEach { st ->
                val s = st.seq.toInt()
                val o = pos(LineGeo.distOf(s))
                if (st.wait) {
                    val r = 6 * px
                    drawRoundRect(c.surface, Offset(o.x - r, o.y - r), Size(2 * r, 2 * r), CornerRadius(3 * px))
                    drawRoundRect(if (s in crossSeqs) c.warn else c.onSurface, Offset(o.x - r, o.y - r), Size(2 * r, 2 * r),
                        CornerRadius(3 * px), style = Stroke(2.5f * px))
                } else {
                    drawCircle(c.surface, 5 * px, o)
                    drawCircle(c.graphic, 5 * px, o, style = Stroke(2.5f * px))
                }
                val lay = tm.measure(st.name, (if (st.wait || s == lo || s == hi) Ts.smallStrong else Ts.small).copy(color = c.onSurface),
                    constraints = Constraints(maxWidth = ((lineX - 44) * px).toInt()), maxLines = 1)
                drawText(lay, topLeft = Offset(o.x - 18 * px - lay.size.width, o.y - lay.size.height / 2f))
            }

            // Zuege: Punkt auf dem Band + Kaertchen in der Spalte ihrer Richtung (von oben nach unten, ohne Ueberlappung)
            val lastBottom = floatArrayOf(-1e9f, -1e9f)
            map.trains.sortedBy { yOfDist(LineGeo.distAt(anim[it.train] ?: it.seq)) }.forEach { t ->
                val o = pos(LineGeo.distAt(anim[t.train] ?: t.seq))
                val col = when { t.bus -> c.graphic; t.delay >= 6 -> c.bad; t.delay >= 3 -> c.warn; else -> c.primary }
                val mine = t.train == myTrain
                if (mine) drawCircle(c.primary, 13 * px, o, style = Stroke(3 * px))
                drawCircle(c.surface, 10 * px, o)
                drawCircle(col, 7.5f * px, o)
                drawChevron(o, t.toEnschede, c.surface, 3 * px)

                val k2 = if (t.toEnschede) 0 else 1
                val x0 = colX + k2 * (colW + 8 * px)
                val h = 32 * px
                var y0 = o.y - h / 2
                if (y0 < lastBottom[k2] + 4 * px) y0 = lastBottom[k2] + 4 * px
                lastBottom[k2] = y0 + h
                drawLine(col.copy(alpha = 0.5f), Offset(o.x + 10 * px, o.y), Offset(x0, y0 + h / 2), 2 * px)
                drawRoundRect(if (mine) c.primaryTint else c.surface3, Offset(x0, y0), Size(colW, h), CornerRadius(8 * px))
                drawRoundRect(col, Offset(x0, y0), Size(colW, h), CornerRadius(8 * px), style = Stroke((if (mine) 2.5f else 2f) * px))
                val name = tm.measure(if (t.bus) "Bus" else t.train, Ts.smallStrong.copy(color = c.onSurface))
                drawText(name, topLeft = Offset(x0 + 8 * px, y0 + (h - name.size.height) / 2))
                if (t.delay >= 1) {
                    val dl = tm.measure("+${t.delay}", Ts.smallBold.copy(color = if (t.delay >= 3) col else c.muted))
                    drawText(dl, topLeft = Offset(x0 + colW - 8 * px - dl.size.width, y0 + (h - dl.size.height) / 2))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = Dim.s16, vertical = Dim.s8)) {
            Text(if (myTrain != null) "Ring = dein Zug · Quadrat = Kreuzungshalt" else "Quadrat = Kreuzungshalt",
                style = Ts.small, color = c.muted, modifier = Modifier.weight(1f))
            if (vis.mapMini) Text("© OpenStreetMap", style = Ts.small, color = c.muted)
        }
    }

    // ---------------------------------------------------------------- Liste: wo genau ist welcher Zug
    val trains = map.trains.sortedBy { it.seq }
    SectionHeader(if (trains.isEmpty()) "Gerade kein Zug auf dem Abschnitt" else "Züge auf dem Abschnitt")
    if (trains.isNotEmpty()) ListCard(trains) { t ->
        val s0 = floor(t.seq).toInt(); val s1 = ceil(t.seq).toInt()
        val where = t.at?.let { "in $it" } ?: run {
            val from = Line.name(if (t.toEnschede) s0 else s1); val to = Line.name(if (t.toEnschede) s1 else s0)
            "zwischen $from und $to"
        }
        Row(Modifier.fillMaxWidth().heightIn(min = Dim.row).padding(horizontal = Dim.s16, vertical = Dim.s8),
            verticalAlignment = Alignment.CenterVertically) {
            Icon(if (t.toEnschede) RbIcons.chevronUp else RbIcons.chevronDown, null, tint = c.muted, modifier = Modifier.size(Dim.icon))
            Spacer(Modifier.width(Dim.s12))
            Column(Modifier.weight(1f)) {
                Text("${if (t.bus) "Ersatzbus" else t.train} nach ${if (t.toEnschede) "Enschede" else "Münster"}" +
                    (if (t.train == myTrain) " · dein Zug" else ""), style = Ts.textStrong)
                Text(where, style = Ts.small, color = c.muted)
            }
            when {
                t.bus -> {}
                t.delay >= 6 -> Pill("+${t.delay}", c.bad, c.badBg)
                t.delay >= 3 -> Pill("+${t.delay}", c.warn, c.warnBg)
                t.delay >= 1 -> Text("+${t.delay}", style = Ts.small, color = c.muted)
                else -> Text("pünktlich", style = Ts.small, color = c.muted)
            }
        }
    }
}

/** Minikarte: ganze Linie genordet, Abschnitt hervorgehoben, Zuege als Punkte. */
@Composable
private fun MiniMap(lo: Int, hi: Int, map: LiveMap, modifier: Modifier) {
    val c = Rb.c
    Canvas(modifier.clip(RoundedCornerShape(Dim.rButton)).background(c.background)) {
        val all = LineGeo.all()
        val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }
        val minY = all.minOf { it.y }; val maxY = all.maxOf { it.y }
        val pad = 8.dp.toPx()
        val s = minOf((size.width - 2 * pad) / (maxX - minX), (size.height - 2 * pad) / (maxY - minY)).toFloat()
        val ox = (size.width - (maxX - minX).toFloat() * s) / 2
        val oy = (size.height - (maxY - minY).toFloat() * s) / 2
        fun pt(p: LineGeo.P) = Offset(ox + ((p.x - minX) * s).toFloat(), oy + ((maxY - p.y) * s).toFloat())
        fun path(ps: List<LineGeo.P>) = Path().apply { ps.forEachIndexed { i, p -> val o = pt(p); if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) } }
        drawPath(path(all), c.graphic.copy(alpha = 0.5f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path(LineGeo.vertices(lo, hi).map { it.second }), c.primary, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        map.trains.forEach { t ->
            val col = when { t.bus -> c.graphic; t.delay >= 6 -> c.bad; t.delay >= 3 -> c.warn; else -> c.primary }
            val o = pt(LineGeo.point(t.seq))
            drawCircle(c.surface, 3.5.dp.toPx(), o); drawCircle(col, 2.5.dp.toPx(), o)
        }
    }
}

/** Pfeil: nach oben = Richtung Enschede, nach unten = Richtung Muenster. */
private fun DrawScope.drawChevron(p: Offset, up: Boolean, color: Color, s: Float) {
    val dy = if (up) -1f else 1f
    val tip = Offset(p.x, p.y + dy * s)
    drawLine(color, Offset(p.x - s, p.y - dy * s / 2), tip, 2.dp.toPx(), cap = StrokeCap.Round)
    drawLine(color, Offset(p.x + s, p.y - dy * s / 2), tip, 2.dp.toPx(), cap = StrokeCap.Round)
}
