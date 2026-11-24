package de.rb64.live.ride

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sign
import kotlin.math.sin

/* ================================================================== Kippen (ruhig) */

/**
 * Neigung der Karte (x = links/rechts, y = vor/zurueck, je -1..1), ruhig gerechnet:
 * relativ zur Lage beim Oeffnen, Tiefpass, Totzone 3 Grad, Begrenzung auf 20 Grad, und die Ruhelage
 * wandert langsam mit - wer das Handy einfach haelt, sieht nach kurzer Zeit nichts mehr wackeln.
 */
@Composable
fun rememberTiltXY(): State<Offset> {
    // im Teilen-Bild fest in Ruhelage, ohne Sensor
    if (LocalShareMode.current != null) return remember { mutableStateOf(Offset.Zero) }
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(Offset.Zero) }
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(SensorManager::class.java)
        val sensor = sm?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        val rot = FloatArray(9); val ori = FloatArray(3)
        var base: FloatArray? = null
        var pitch = 0f; var roll = 0f
        val dead = 3f * PI.toFloat() / 180f
        val range = 20f * PI.toFloat() / 180f
        fun shape(v: Float): Float { val a = abs(v) - dead; return if (a <= 0f) 0f else (a / (range - dead)).coerceAtMost(1f) * sign(v) }
        val l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                SensorManager.getRotationMatrixFromVector(rot, e.values)
                SensorManager.getOrientation(rot, ori)
                val b = base ?: floatArrayOf(ori[1], ori[2]).also { base = it }
                // Ruhelage folgt sehr langsam, Messwert mit Tiefpass
                b[0] += (ori[1] - b[0]) * 0.004f; b[1] += (ori[2] - b[1]) * 0.004f
                pitch += ((ori[1] - b[0]) - pitch) * 0.1f
                roll += ((ori[2] - b[1]) - roll) * 0.1f
                val next = Offset(shape(roll), shape(pitch))
                if ((next - state.value).getDistance() > 0.015f) state.value = next
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_GAME)
        onDispose { sm?.unregisterListener(l) }
    }
    return state
}

/** Staerke der Neigung 0..1 (fuer Wasserzeichen und Glanz). */
fun Offset.strength() = hypot(x, y).coerceAtMost(1f)

/* ================================================================== Varianten */

private val PEN = Color(0xFF1E3A8A)

/** Papierfarbe je Variante (null = normal). */
fun variantPaper(t: Ticket): Color? = when (t.variant?.id) {
    "blueprint" -> Color(0xFFDCEAF7)
    "rain", "rain2" -> Color(0xFFF1F4F6)
    "night" -> Color(0xFFF0F1F7)
    else -> null
}

/** Kopfzeile je Variante (null = normal). */
fun variantHeader(t: Ticket): Brush? = when (t.variant?.id) {
    "night" -> Brush.horizontalGradient(listOf(Color(0xFF0F172A), Color(0xFF1E3A5F), Color(0xFF0B5563)))
    "gold" -> Brush.horizontalGradient(listOf(Color(0xFF0B5563), Color(0xFF0E4D57), Color(0xFF0B5563)))
    "blueprint" -> Brush.horizontalGradient(listOf(Color(0xFF1E4E8C), Color(0xFF2563A8)))
    "border" -> Brush.horizontalGradient(listOf(Color(0xFF0B5563), Color(0xFF0B5563), Color(0xFFEA580C)))
    else -> null
}

/** Ueberlagerung je Variante; tilt bewegt Glanz und Folie. */
@Composable
fun BoxScope.VariantOverlay(t: Ticket, tilt: Offset) {
    val id = t.variant?.id ?: return
    val rnd = remember(t.id) { java.util.Random((t.id + id).hashCode().toLong()) }
    val drops = remember(t.id) { List(26) { Triple(rnd.nextFloat(), rnd.nextFloat(), 3f + rnd.nextFloat() * 5f) } }
    val stars = remember(t.id) { List(14) { Pair(rnd.nextFloat(), rnd.nextFloat() * 0.07f + 0.005f) } }
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width; val h = size.height
        when (id) {
            "holo" -> {
                // Regenbogenband, das mit dem Kippen ueber die Karte wandert, plus weicher Glanzpunkt
                val cx = w * (0.5f + 0.45f * tilt.x); val cy = h * (0.45f + 0.3f * tilt.y)
                val band = listOf(Color(0x00FFFFFF), Color(0x33F472B6), Color(0x33FDE047), Color(0x3334D399), Color(0x3360A5FA), Color(0x33A78BFA), Color(0x00FFFFFF))
                drawRect(Brush.linearGradient(band, start = Offset(cx - w * 0.6f, cy - h * 0.4f), end = Offset(cx + w * 0.6f, cy + h * 0.4f)))
                drawCircle(Brush.radialGradient(listOf(Color(0x55FFFFFF), Color(0x00FFFFFF)), center = Offset(cx, cy), radius = w * 0.45f), w * 0.45f, Offset(cx, cy))
            }
            "gold" -> {
                val g = Brush.linearGradient(listOf(Color(0xFFB8860B), Color(0xFFFDE68A), Color(0xFFB8860B), Color(0xFFF5D06F)),
                    start = Offset(w * (0.2f * tilt.x), 0f), end = Offset(w * (1f + 0.2f * tilt.x), h))
                drawRoundRect(g, style = Stroke(5.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(18.dp.toPx()))
                drawRoundRect(g, topLeft = Offset(7.dp.toPx(), 7.dp.toPx()), size = Size(w - 14.dp.toPx(), h - 14.dp.toPx()),
                    style = Stroke(1.dp.toPx()), cornerRadius = androidx.compose.ui.geometry.CornerRadius(13.dp.toPx()), alpha = 0.7f)
                val cx = w * (0.5f + 0.5f * tilt.x)
                drawRect(Brush.linearGradient(listOf(Color(0x00FFFFFF), Color(0x26FFF7D6), Color(0x00FFFFFF)),
                    start = Offset(cx - w * 0.25f, 0f), end = Offset(cx + w * 0.25f, h)))
            }
            "blueprint" -> {
                val step = 18.dp.toPx(); val line = Color.White.copy(alpha = 0.55f)
                var x = 0f; while (x < w) { drawLine(line, Offset(x, 0f), Offset(x, h), 0.7.dp.toPx()); x += step }
                var y = 0f; while (y < h) { drawLine(line, Offset(0f, y), Offset(w, y), 0.7.dp.toPx()); y += step }
                // Massketten am Rand wie auf einer Zeichnung
                val m = Color(0xFF1E4E8C).copy(alpha = 0.5f)
                drawLine(m, Offset(12.dp.toPx(), h - 10.dp.toPx()), Offset(w - 12.dp.toPx(), h - 10.dp.toPx()), 1.dp.toPx())
                drawLine(m, Offset(12.dp.toPx(), h - 15.dp.toPx()), Offset(12.dp.toPx(), h - 5.dp.toPx()), 1.dp.toPx())
                drawLine(m, Offset(w - 12.dp.toPx(), h - 15.dp.toPx()), Offset(w - 12.dp.toPx(), h - 5.dp.toPx()), 1.dp.toPx())
            }
            "rain", "rain2" -> drops.forEach { (fx, fy, r) ->
                val c = Offset(fx * w, fy * h); val rad = r.dp.toPx()
                drawCircle(Color(0x1F3B82F6), rad, c)
                drawCircle(Color(0x332563EB), rad, c, style = Stroke(0.8.dp.toPx()))
                drawCircle(Color(0x99FFFFFF), rad * 0.3f, c + Offset(-rad * 0.35f, -rad * 0.35f))
            }
            "night" -> {
                stars.forEach { (fx, fy) -> drawCircle(Color.White.copy(alpha = 0.85f), 1.3.dp.toPx(), Offset(fx * w, fy * h + 4.dp.toPx())) }
                val mc = Offset(w - 70.dp.toPx(), 20.dp.toPx())
                drawCircle(Color(0xFFFDF4C8), 9.dp.toPx(), mc)
                drawCircle(Color(0xFF1E3A5F), 8.dp.toPx(), mc + Offset(4.dp.toPx(), -2.dp.toPx()))
            }
            "border" -> {
                val p = Path().apply { moveTo(w * 0.55f, 0f); lineTo(w, 0f); lineTo(w, h * 0.55f); close() }
                drawPath(p, Color(0x14EA580C))
                drawLine(Color(0x66EA580C), Offset(w * 0.55f, 0f), Offset(w, h * 0.55f), 1.5.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 5.dp.toPx())))
            }
        }
    }
    when (id) {
        "blueprint" -> Text("BLAUPAUSE · M 1:64", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 9.sp, fontWeight = FontWeight.Bold,
            color = Color(0xFF1E4E8C).copy(alpha = 0.7f)), modifier = Modifier.align(Alignment.BottomCenter).offset(y = (-14).dp))
        "border" -> Text("NL", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFFEA580C).copy(alpha = 0.6f)),
            modifier = Modifier.align(Alignment.CenterEnd).offset(x = (-22).dp, y = (-40).dp).rotate(12f))
    }
}

/* ================================================================== Kritzeleien */

/** Ein Motiv = mehrere Striche (Punkte im Einheitsquadrat 0..1). */
private typealias Motif = List<List<Offset>>

private fun circlePts(cx: Float, cy: Float, r: Float, from: Float = 0f, to: Float = 360f, n: Int = 18) =
    List(n + 1) { k -> val a = (from + (to - from) * k / n) * PI.toFloat() / 180f; Offset(cx + r * cos(a), cy + r * sin(a)) }

private val MOTIFS: Map<String, Motif> = mapOf(
    "sun" to listOf(circlePts(0.5f, 0.5f, 0.2f)) + List(8) { k ->
        val a = k * 45f * PI.toFloat() / 180f
        listOf(Offset(0.5f + 0.28f * cos(a), 0.5f + 0.28f * sin(a)), Offset(0.5f + 0.42f * cos(a), 0.5f + 0.42f * sin(a)))
    } + listOf(circlePts(0.5f, 0.5f, 0.1f, 20f, 160f, 8)),
    "cloud" to listOf(circlePts(0.35f, 0.45f, 0.15f, 150f, 330f, 10) + circlePts(0.58f, 0.38f, 0.18f, 190f, 360f, 10) +
        circlePts(0.75f, 0.5f, 0.12f, 250f, 450f, 8), listOf(Offset(0.22f, 0.55f), Offset(0.85f, 0.55f)),
        listOf(Offset(0.35f, 0.68f), Offset(0.3f, 0.82f)), listOf(Offset(0.52f, 0.68f), Offset(0.47f, 0.85f)), listOf(Offset(0.69f, 0.68f), Offset(0.64f, 0.82f))),
    "snail" to listOf(List(30) { k -> val a = k * 0.55f; val r = 0.02f + k * 0.0065f; Offset(0.45f + r * cos(a), 0.5f + r * sin(a)) },
        listOf(Offset(0.2f, 0.72f), Offset(0.85f, 0.72f), Offset(0.9f, 0.6f), Offset(0.85f, 0.5f)),
        listOf(Offset(0.86f, 0.52f), Offset(0.8f, 0.3f)), listOf(Offset(0.88f, 0.52f), Offset(0.95f, 0.32f))),
    "smile" to listOf(circlePts(0.5f, 0.5f, 0.32f), listOf(Offset(0.4f, 0.4f), Offset(0.4f, 0.46f)),
        listOf(Offset(0.6f, 0.4f), Offset(0.6f, 0.46f)), circlePts(0.5f, 0.52f, 0.17f, 20f, 160f, 8)),
    "cross" to listOf(listOf(Offset(0.15f, 0.38f), Offset(0.8f, 0.38f)), listOf(Offset(0.68f, 0.28f), Offset(0.82f, 0.38f), Offset(0.68f, 0.48f)),
        listOf(Offset(0.85f, 0.62f), Offset(0.2f, 0.62f)), listOf(Offset(0.32f, 0.52f), Offset(0.18f, 0.62f), Offset(0.32f, 0.72f))),
    "tulip" to listOf(listOf(Offset(0.5f, 0.9f), Offset(0.5f, 0.45f)), listOf(Offset(0.5f, 0.75f), Offset(0.32f, 0.6f), Offset(0.5f, 0.68f)),
        listOf(Offset(0.32f, 0.45f), Offset(0.3f, 0.2f), Offset(0.42f, 0.32f), Offset(0.5f, 0.15f), Offset(0.58f, 0.32f), Offset(0.7f, 0.2f), Offset(0.68f, 0.45f), Offset(0.32f, 0.45f))),
    "moon" to listOf(circlePts(0.45f, 0.5f, 0.3f, 60f, 300f, 14) + circlePts(0.58f, 0.5f, 0.24f, 280f, 80f, 12).reversed(),
        listOf(Offset(0.82f, 0.18f), Offset(0.82f, 0.32f)), listOf(Offset(0.75f, 0.25f), Offset(0.89f, 0.25f))),
    "train" to listOf(listOf(Offset(0.1f, 0.7f), Offset(0.1f, 0.4f), Offset(0.75f, 0.4f), Offset(0.88f, 0.55f), Offset(0.88f, 0.7f), Offset(0.1f, 0.7f)),
        listOf(Offset(0.2f, 0.48f), Offset(0.35f, 0.48f), Offset(0.35f, 0.58f), Offset(0.2f, 0.58f), Offset(0.2f, 0.48f)),
        listOf(Offset(0.45f, 0.48f), Offset(0.6f, 0.48f), Offset(0.6f, 0.58f), Offset(0.45f, 0.58f), Offset(0.45f, 0.48f)),
        circlePts(0.28f, 0.76f, 0.06f), circlePts(0.7f, 0.76f, 0.06f), listOf(Offset(0.0f, 0.84f), Offset(1f, 0.84f))),
    "cow" to listOf(listOf(Offset(0.2f, 0.4f), Offset(0.7f, 0.4f), Offset(0.7f, 0.65f), Offset(0.2f, 0.65f), Offset(0.2f, 0.4f)),
        listOf(Offset(0.7f, 0.42f), Offset(0.88f, 0.35f), Offset(0.9f, 0.52f), Offset(0.72f, 0.55f)),
        listOf(Offset(0.28f, 0.65f), Offset(0.28f, 0.82f)), listOf(Offset(0.62f, 0.65f), Offset(0.62f, 0.82f)),
        circlePts(0.38f, 0.5f, 0.06f), circlePts(0.55f, 0.55f, 0.04f)),
    "heart" to listOf(circlePts(0.38f, 0.4f, 0.14f, 150f, 360f, 9) + circlePts(0.62f, 0.4f, 0.14f, 180f, 390f, 9) +
        listOf(Offset(0.5f, 0.8f), Offset(0.25f, 0.48f))),
    "ttt" to listOf(listOf(Offset(0.38f, 0.15f), Offset(0.36f, 0.85f)), listOf(Offset(0.64f, 0.15f), Offset(0.62f, 0.85f)),
        listOf(Offset(0.12f, 0.38f), Offset(0.88f, 0.36f)), listOf(Offset(0.12f, 0.62f), Offset(0.88f, 0.6f)),
        listOf(Offset(0.16f, 0.16f), Offset(0.3f, 0.32f)), listOf(Offset(0.3f, 0.16f), Offset(0.16f, 0.32f)), circlePts(0.5f, 0.5f, 0.07f)),
    "cup" to listOf(listOf(Offset(0.25f, 0.4f), Offset(0.3f, 0.82f), Offset(0.65f, 0.82f), Offset(0.7f, 0.4f), Offset(0.25f, 0.4f)),
        circlePts(0.76f, 0.58f, 0.09f, 270f, 450f, 8), listOf(Offset(0.4f, 0.32f), Offset(0.45f, 0.22f), Offset(0.4f, 0.12f)),
        listOf(Offset(0.55f, 0.32f), Offset(0.6f, 0.22f), Offset(0.55f, 0.12f))),
    "spiral" to listOf(List(34) { k -> val a = k * 0.5f; val r = 0.02f + k * 0.011f; Offset(0.5f + r * cos(a), 0.5f + r * sin(a)) }),
)

private val NOTES = listOf("war hier", "+3?", "Kaffee!", "noch 3 Halte", ":)", "Kuh gesehen", "Gleis 2!", "zu früh?!")

/** Welche Kritzeleien auf diese Karte kommen: erst passende zur Fahrt, dann zufaellige (2-4 Stueck). */
private fun motifsFor(t: Ticket): List<String> {
    val r = java.util.Random((t.id + "doodles").hashCode().toLong())
    val hour = Calendar.getInstance().apply { timeInMillis = t.started }.get(Calendar.HOUR_OF_DAY)
    val data = buildList {
        if (t.weather.isNotEmpty()) add("cloud") else if (hour in 7..17) add("sun")
        if ((t.delay ?: 0) >= 10) add("snail") else if (t.delay != null && t.delay <= 0) add("smile")
        if (t.crossing != null) add("cross")
        if (t.border) add("tulip")
        if (hour >= 21 || hour < 5) add("moon")
    }.shuffled(r).take(2)
    val extra = listOf("train", "cow", "heart", "ttt", "cup", "spiral").shuffled(r)
    return (data + extra).distinct().take(2 + r.nextInt(2))
}

/** Freie Stellen auf der Vorderseite (Anteil von Breite/Hoehe, Groesse in dp). */
private val SLOTS = listOf(Triple(0.74f, 0.67f, 44f), Triple(0.91f, 0.61f, 34f), Triple(0.90f, 0.74f, 32f))

/** Kritzeleien in Kugelschreiber-Blau; sie zeichnen sich beim Ansehen einmal selbst. */
@Composable
fun BoxScope.DoodleOverlay(t: Ticket) {
    if (!t.doodle) return
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) { DoodleContent(t, maxWidth, maxHeight) }
}

@Composable
private fun BoxScope.DoodleContent(t: Ticket, w: androidx.compose.ui.unit.Dp, h: androidx.compose.ui.unit.Dp) {
    val names = remember(t.id) { motifsFor(t) }
    val note = remember(t.id) { NOTES[java.util.Random((t.id + "note").hashCode().toLong()).nextInt(NOTES.size)] }
    val static = LocalShareMode.current != null
    val progress = remember(t.id) { Animatable(if (static) 1f else 0f) }
    LaunchedEffect(t.id) { progress.animateTo(1f, tween(1_800, easing = FastOutSlowInEasing)) }
    val jitter = remember(t.id) { java.util.Random((t.id + "jitter").hashCode().toLong()) }
    val wobble = remember(t.id) { List(400) { Offset(jitter.nextFloat() - 0.5f, jitter.nextFloat() - 0.5f) } }
    Canvas(Modifier.fillMaxSize()) {
        val stroke = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        var wi = 0
        names.forEachIndexed { i, n ->
            val (fx, fy, sz) = SLOTS[i % SLOTS.size]
            val box = sz.dp.toPx()
            val origin = Offset(size.width * fx - box / 2, size.height * fy - box / 2)
            val strokes = MOTIFS[n] ?: return@forEachIndexed
            val total = strokes.sumOf { it.size }
            var budget = (progress.value * total).toInt()
            strokes.forEach { pts ->
                if (budget <= 1) return@forEach
                val path = Path()
                pts.take(budget).forEachIndexed { k, p ->
                    val wob = wobble[wi++ % wobble.size] * 1.5.dp.toPx()
                    val q = origin + Offset(p.x * box, p.y * box) + wob
                    if (k == 0) path.moveTo(q.x, q.y) else path.lineTo(q.x, q.y)
                }
                budget -= pts.size
                drawPath(path, PEN.copy(alpha = 0.72f), style = stroke)
            }
        }
    }
    if (progress.value > 0.6f) Text(note, style = TextStyle(fontFamily = FontFamily.Cursive, fontSize = 15.sp, color = PEN.copy(alpha = 0.75f)),
        modifier = Modifier.align(Alignment.TopStart).offset(x = w * 0.64f, y = h * 0.555f).rotate(-6f).wrapContentSize(unbounded = true))
}

/* ================================================================== Lochzange */

/** Entwerter-Aufdruck neben dem Loch: "ENTWERTET 24.11.26 08:27". */
fun punchText(t: Ticket, full: Boolean = true): String {
    val c = Calendar.getInstance().apply { timeInMillis = t.started }
    if (!full) return "ENTWERTET %02d/%02d".format(c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR) % 100)
    return "ENTWERTET %02d.%02d.%02d %s".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1, c.get(Calendar.YEAR) % 100, t.dep)
}
