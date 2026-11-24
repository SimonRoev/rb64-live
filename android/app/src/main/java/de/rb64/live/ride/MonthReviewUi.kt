package de.rb64.live.ride

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * Monatsrueckblick als animierte Story (Idee wie bei "Wrapped"):
 * - Folien laufen von selbst weiter (Fortschrittsbalken), Tippen rechts/links blaettert, Festhalten pausiert.
 * - Hintergrund atmet: Verlauf wandert, grosse Formen drehen sich langsam.
 * - Inhalte kommen gestaffelt von unten, Zahlen zaehlen hoch, jede Folie hat ein eigenes kleines Bild in Bewegung.
 * Versteckt: 3 s festhalten (Zahl spult zurueck), Kreuzungszahl 3x antippen, Lautsprecher auf der letzten Folie.
 */

private const val SLIDE_MS = 7_000
private const val PAGES = 7
private val WHITE = Color.White
private val MONO = TextStyle(fontFamily = FontFamily.Monospace)

/** Drei Farben je Folie (Verlauf). */
private val PALETTE = listOf(
    listOf(Color(0xFF0B5563), Color(0xFF0E7490), Color(0xFF14B8A6)),
    listOf(Color(0xFFF97316), Color(0xFFDB2777), Color(0xFF6D28D9)),
    listOf(Color(0xFF16A34A), Color(0xFF0D9488), Color(0xFF0B5563)),
    listOf(Color(0xFFF59E0B), Color(0xFFEA580C), Color(0xFFB91C1C)),
    listOf(Color(0xFF6366F1), Color(0xFF8B5CF6), Color(0xFFDB2777)),
    listOf(Color(0xFF0EA5E9), Color(0xFF6366F1), Color(0xFF312E81)),
    listOf(Color(0xFF0F172A), Color(0xFF1E3A5F), Color(0xFF0B5563)),
)

@Composable
fun MonthReviewScreen(p: MonthReviews.Pending, history: List<RideMeta>, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val data by produceState<MonthReviews.Data?>(null, p) { value = withContext(Dispatchers.IO) { MonthReviews.data(ctx, p, history) } }
    var page by remember { mutableIntStateOf(0) }
    var paused by remember { mutableStateOf(false) }
    var rewind by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    // Fortschritt der aktuellen Folie; laeuft weiter, wo er beim Pausieren stand
    val progress = remember(page) { Animatable(0f) }
    var sharing by remember { mutableStateOf(false) }
    LaunchedEffect(page, paused || sharing, data != null) {
        if (data == null || paused || sharing) return@LaunchedEffect
        progress.animateTo(1f, tween(((1f - progress.value) * SLIDE_MS).toInt(), easing = LinearEasing))
        if (page < PAGES - 1) page++
    }

    val pal = PALETTE[page % PALETTE.size]
    val c0 by animateColorAsState(pal[0], tween(700), label = "c0")
    val c1 by animateColorAsState(pal[1], tween(700), label = "c1")
    val c2 by animateColorAsState(pal[2], tween(700), label = "c2")
    val inf = rememberInfiniteTransition(label = "bg")
    val drift by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(9_000, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "drift")
    val spin by inf.animateFloat(0f, 360f, infiniteRepeatable(tween(40_000, easing = LinearEasing)), label = "spin")
    val breathe by inf.animateFloat(0.92f, 1.08f, infiniteRepeatable(tween(3_500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "breathe")

    Box(Modifier.fillMaxSize()
        .background(Brush.linearGradient(listOf(c0, c1, c2), start = Offset(0f, 2000f * drift), end = Offset(1400f, 2400f - 1200f * drift)))
        .pointerInput(Unit) {
            detectTapGestures(
                onTap = { o -> if (o.x < size.width / 3) page = (page - 1).coerceAtLeast(0) else if (page < PAGES - 1) page++ else onClose() },
                onLongPress = { },
                onPress = {
                    paused = true
                    val job = scope.launch { delay(3_000); rewind++ }
                    tryAwaitRelease(); job.cancel(); paused = false
                },
            )
        }) {
        // grosse, langsam drehende Formen im Hintergrund
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            rotate(spin, Offset(w * 0.85f, h * 0.22f)) { drawPath(star(Offset(w * 0.85f, h * 0.22f), w * 0.42f * breathe, 12, 0.72f), WHITE.copy(alpha = 0.10f)) }
            rotate(-spin * 0.7f, Offset(w * 0.1f, h * 0.85f)) { drawPath(star(Offset(w * 0.1f, h * 0.85f), w * 0.5f, 7, 0.55f), WHITE.copy(alpha = 0.07f)) }
            drawCircle(WHITE.copy(alpha = 0.06f), w * 0.22f * (2.1f - breathe), Offset(w * (0.3f + 0.4f * drift), h * 0.5f))
        }
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(PAGES) { i ->
                    Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(WHITE.copy(alpha = 0.35f))) {
                        val f = when { i < page -> 1f; i == page -> progress.value; else -> 0f }
                        Box(Modifier.fillMaxWidth(f).height(3.dp).background(WHITE))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Dein ${p.name} auf der RB64", style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = WHITE.copy(alpha = 0.9f)),
                    modifier = Modifier.weight(1f))
                Text("Teilen", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold, color = WHITE),
                    modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { if (data != null) sharing = true }.padding(horizontal = 10.dp, vertical = 6.dp))
                Icon(RbIcons.close, "Schließen", tint = WHITE, modifier = Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).clickable { onClose() }.padding(4.dp))
            }
            Spacer(Modifier.weight(0.25f))
            val d = data
            if (d == null) Text("Einen Moment …", style = TextStyle(fontSize = 18.sp, color = WHITE))
            else AnimatedContent(targetState = page, label = "slide", transitionSpec = {
                (fadeIn(tween(350)) + scaleIn(tween(450), initialScale = 0.9f)) togetherWith fadeOut(tween(200))
            }) { pg -> Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Slide(pg, d, rewind) } }
            Spacer(Modifier.weight(1f))
        }
    }
    if (sharing) data?.let { d -> ReviewShareSheet(d, page, onClose = { sharing = false }) }
}

/** Stern/Zahnrad-Form mit n Spitzen (r innen = r * inner). */
private fun star(c: Offset, r: Float, n: Int, inner: Float): Path = Path().apply {
    for (k in 0 until n * 2) {
        val a = (k * PI / n).toFloat()
        val rr = if (k % 2 == 0) r else r * inner
        val pt = Offset(c.x + rr * cos(a), c.y + rr * sin(a))
        if (k == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
    }
    close()
}

/* ------------------------------------------------------------------ Bausteine */

/** Gestaffelter Auftritt: von unten hereinfedern, index bestimmt die Verzoegerung. */
@Composable
private fun Enter(index: Int, content: @Composable () -> Unit) {
    val static = LocalShareMode.current != null           // im Teilen-Bild sofort im Endzustand
    val a = remember { Animatable(if (static) 1f else 0f) }
    LaunchedEffect(Unit) { if (!static) { delay(120L + index * 180L); a.animateTo(1f, spring(dampingRatio = 0.65f, stiffness = 180f)) } }
    Box(Modifier.graphicsLayer {
        alpha = a.value.coerceIn(0f, 1f)
        translationY = (1f - a.value) * 70.dp.toPx()
    }) { content() }
}

@Composable private fun Lead(s: String, i: Int = 0) = Enter(i) { Text(s, style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = WHITE)) }
@Composable private fun Sub(s: String, i: Int) = Enter(i) { Text(s, style = TextStyle(fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = WHITE, lineHeight = 30.sp)) }
@Composable private fun Body(s: String, i: Int, mono: Boolean = false) = Enter(i) {
    Text(s, style = (if (mono) MONO else TextStyle()).copy(fontSize = 16.sp, color = WHITE.copy(alpha = 0.92f), lineHeight = 22.sp))
}

/** Grosse Zahl: zaehlt hoch und springt am Ende kurz auf. Nach 3 s Festhalten spult sie zurueck (rewind). */
@Composable
private fun Big(value: Int, unit: String = "", i: Int = 1, rewind: Int = 0) {
    val static = LocalShareMode.current != null
    val shown = remember { Animatable(if (static) value.toFloat() else 0f) }
    val pop = remember { Animatable(1f) }
    LaunchedEffect(value) {
        if (static) return@LaunchedEffect
        delay(250L + i * 180L)
        shown.animateTo(value.toFloat(), tween(1_400, easing = FastOutSlowInEasing))
        pop.animateTo(1.12f, tween(120)); pop.animateTo(1f, spring(dampingRatio = 0.4f))
    }
    LaunchedEffect(rewind) {
        if (rewind > 0) { shown.animateTo(0f, tween(1_200, easing = LinearEasing)); delay(600); shown.animateTo(value.toFloat(), tween(600)) }
    }
    Enter(i) {
        Text("${shown.value.toInt()}$unit", style = TextStyle(fontSize = 112.sp, fontWeight = FontWeight.Black, color = WHITE,
            letterSpacing = (-4).sp, lineHeight = 116.sp),
            modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f) })
    }
}

/** Fortschritt 0..1 einer Folien-Animation (einmalig). */
@Composable
private fun oneShot(ms: Int, delayMs: Long = 300): Float {
    val static = LocalShareMode.current != null
    val a = remember { Animatable(if (static) 1f else 0f) }
    LaunchedEffect(Unit) { if (!static) { delay(delayMs); a.animateTo(1f, tween(ms, easing = FastOutSlowInEasing)) } }
    return a.value
}

/* ------------------------------------------------------------------ Folien */

@Composable
private fun Slide(page: Int, d: MonthReviews.Data, rewind: Int) {
    when (page) {
        0 -> {
            Lead("Im ${d.name} warst du")
            Big(d.rides, rewind = rewind)
            Sub("Mal mit der RB64 unterwegs", 2)
            Enter(3) { TicketFan(d.rides) }
        }
        1 -> {
            Lead("Zusammen gefahren")
            Big(d.km.toInt(), " km", rewind = rewind)
            val target = MonthReviews.compare(d.km)
            target?.let { Sub(it.text, 2) }
            Enter(3) { TrainLine(target?.city ?: "Enschede", back = target?.text?.contains("zurück") == true) }
            Body("Das ist ${MonthReviews.rb64Times(d.km)}-mal die ganze RB64 – und ${d.minutes / 60} h ${d.minutes % 60} min im Zug.", 4)
        }
        2 -> {
            Lead("Pünktlich am Ziel")
            if (d.punctual != null) { Big(d.punctual, " %", rewind = rewind); Sub("deiner Fahrten unter 6 Minuten", 2) } else Sub("Keine Ankunft gemessen.", 1)
            Enter(3) { Bars(d.punctual ?: 0) }
        }
        3 -> {
            Lead("Kreuzungen")
            var taps by remember { mutableIntStateOf(0) }
            Box(Modifier.pointerInput(Unit) { detectTapGestures { taps++ } }) { Big(d.crossings, rewind = rewind) }
            Sub(d.longestCross?.let { "längste: ${it.second} min in ${it.first}" } ?: "Diesen Monat kein Gegenverkehr.", 2)
            Enter(3) { CrossingLoop() }
            if (taps >= 3) Body("„Gegenverkehr gewartet. Sie nicht.“", 4, mono = true)
        }
        4 -> {
            Lead("Deine Gewohnheit")
            Sub(d.favRoute ?: "Mal hier, mal da.", 1)
            // Lieblingszeit verraet ein Muster - im Teilen-Bild nur mit "Details zeigen"
            val fav = if (LocalShareMode.current?.details == false) null else d.favTime
            Enter(2) { HabitClock(fav) }
            Body(fav?.let { "Meistens $it – da war Verlass auf dich." } ?: "Kein fester Zug – Freigeist.", 3)
        }
        5 -> {
            Lead("Neu in deiner Sammlung")
            Big(d.newStations.size, if (d.newStations.size == 1) " Stempel" else " Stempel", rewind = rewind)
            Enter(2) { StampRain(d.newStations.ifEmpty { listOf("RB64") }) }
            d.rarest?.takeIf { it.rarity > Rarity.COMMON }?.let { Body("Seltenster Spruch (${it.rarity.label}): „${it.text}“", 3) }
            if (d.variants.isNotEmpty()) Body("Varianten: ${d.variants.joinToString(", ")}", 4)
            if (d.doodles > 0) Body("${d.doodles}× hat jemand auf deine Karte gekritzelt.", 5)
        }
        else -> {
            Lead("Das war dein ${d.name}")
            Sub("Nächster Rückblick am 1. ${d.nextName}.", 1)
            Enter(2) { Confetti() }
            var ticker by remember { mutableStateOf(false) }
            Enter(3) {
                Icon(RbIcons.announce, "Durchsage", tint = WHITE.copy(alpha = 0.85f),
                    modifier = Modifier.size(40.dp).clip(RoundedCornerShape(20.dp)).pointerInput(Unit) { detectTapGestures { ticker = true } }.padding(6.dp))
            }
            if (ticker) Ticker("${d.farewell} – Else (außer Dienst)")
        }
    }
}

/* ------------------------------------------------------------------ kleine Bilder je Folie */

/** Fahrkarten faechern sich auf - eine je Fahrt (hoechstens 14). */
@Composable
private fun TicketFan(n: Int) {
    val t = oneShot(1_300, 500)
    Canvas(Modifier.fillMaxWidth().height(150.dp)) {
        val k = n.coerceIn(1, 14)
        val pivot = Offset(size.width * 0.35f, size.height * 1.15f)
        for (i in 0 until k) {
            val local = ((t * (k + 3) - i) / 3f).coerceIn(0f, 1f)
            val angle = (-38f + 76f * i / (k - 1).coerceAtLeast(1)) * local
            rotate(angle, pivot) {
                val w = 56.dp.toPx(); val h = 96.dp.toPx()
                val tl = Offset(pivot.x - w / 2, pivot.y - h - 40.dp.toPx() * local)
                drawRoundRect(WHITE.copy(alpha = 0.92f * local), tl, Size(w, h), CornerRadius(6.dp.toPx()))
                drawRect(Color(0xFF0B5563).copy(alpha = local), tl, Size(w, 14.dp.toPx()))
                drawCircle(Color(0xFFB91C1C).copy(alpha = 0.6f * local), 9.dp.toPx(), tl + Offset(w * 0.62f, h * 0.7f), style = Stroke(1.5.dp.toPx()))
            }
        }
    }
}

/** Zug faehrt von Muenster bis zur Vergleichsstadt - bei "hin und zurueck" auch wieder heim. */
@Composable
private fun TrainLine(city: String, back: Boolean = false) {
    val raw = oneShot(if (back) 3_600 else 2_400, 600)
    val t = if (!back) raw else if (raw < 0.5f) raw * 2 else 2f - raw * 2
    val reached = if (back) raw >= 0.5f else raw > 0.98f
    Column {
        Canvas(Modifier.fillMaxWidth().height(40.dp)) {
            val y = size.height / 2
            drawLine(WHITE.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 4.dp.toPx(), StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 8.dp.toPx())))
            drawLine(WHITE, Offset(0f, y), Offset(size.width * (if (reached) 1f else t), y), 4.dp.toPx(), StrokeCap.Round)
            drawCircle(WHITE, 7.dp.toPx(), Offset(0f + 7.dp.toPx(), y))
            drawCircle(WHITE.copy(alpha = if (reached) 1f else 0.4f), 7.dp.toPx(), Offset(size.width - 7.dp.toPx(), y))
            // kleiner Zug
            val x = (size.width - 40.dp.toPx()) * t
            drawRoundRect(WHITE, Offset(x, y - 11.dp.toPx()), Size(40.dp.toPx(), 16.dp.toPx()), CornerRadius(6.dp.toPx()))
            repeat(3) { k -> drawRect(Color(0xFF6D28D9), Offset(x + 5.dp.toPx() + k * 11.dp.toPx(), y - 7.dp.toPx()), Size(7.dp.toPx(), 5.dp.toPx())) }
        }
        Row(Modifier.fillMaxWidth()) {
            Text("Münster", style = MONO.copy(fontSize = 12.sp, color = WHITE), modifier = Modifier.weight(1f))
            Text(city, style = MONO.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = WHITE.copy(alpha = if (reached) 1f else 0.5f)))
        }
    }
}

/** Zwei wachsende Balken: du gegen den Fernverkehr (60,1 %). */
@Composable
private fun Bars(you: Int) {
    val t = oneShot(1_600, 700)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf("Du" to you / 100f, "Fernverkehr 2025" to 0.601f).forEach { (label, v) ->
            Column {
                Text(label, style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = WHITE.copy(alpha = 0.9f)))
                Box(Modifier.fillMaxWidth().height(22.dp).clip(RoundedCornerShape(11.dp)).background(WHITE.copy(alpha = 0.2f))) {
                    Box(Modifier.fillMaxWidth((v * t).coerceIn(0.02f, 1f)).height(22.dp).clip(RoundedCornerShape(11.dp))
                        .background(if (label == "Du") WHITE else WHITE.copy(alpha = 0.55f)))
                }
            }
        }
    }
}

/** Eingleisig mit Ausweichstelle: ein Zug wartet, der Gegenzug faehrt durch, dann geht es weiter (Endlosschleife). */
@Composable
private fun CrossingLoop() {
    val inf = rememberInfiniteTransition(label = "x")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(5_000, easing = LinearEasing)), label = "xt")
    Canvas(Modifier.fillMaxWidth().height(70.dp)) {
        val y = size.height * 0.62f; val w = size.width
        val l = w * 0.38f; val r = w * 0.62f; val up = 18.dp.toPx()
        val track = WHITE.copy(alpha = 0.6f)
        drawLine(track, Offset(0f, y), Offset(w, y), 3.dp.toPx())
        val loop = Path().apply { moveTo(l - 20.dp.toPx(), y); lineTo(l, y - up); lineTo(r, y - up); lineTo(r + 20.dp.toPx(), y) }
        drawPath(loop, track, style = Stroke(3.dp.toPx()))
        val tw = 34.dp.toPx(); val th = 13.dp.toPx()
        // Zug A (von links): faehrt in die Ausweiche, wartet, faehrt weiter
        val ax = when { t < 0.3f -> -tw + (l + tw) * (t / 0.3f); t < 0.6f -> l; else -> l + (w - l + tw) * ((t - 0.6f) / 0.4f) }
        val ay = if (t in 0.25f..0.65f) y - up else y
        drawRoundRect(WHITE, Offset(ax, ay - th), Size(tw, th), CornerRadius(5.dp.toPx()))
        // Gegenzug B (von rechts) faehrt auf dem Hauptgleis durch
        val bx = w - (w + tw * 2) * ((t - 0.25f) / 0.45f).coerceIn(0f, 1f)
        drawRoundRect(Color(0xFFFDE68A), Offset(bx, y - th), Size(tw, th), CornerRadius(5.dp.toPx()))
    }
}

/** Uhr dreht sich auf die Lieblingszeit ("Mo 08:20"). */
@Composable
private fun HabitClock(fav: String?) {
    val hm = fav?.substringAfter(' ')?.split(":")?.mapNotNull { it.toIntOrNull() }
    val target = if (hm != null && hm.size == 2) hm[0] * 60 + hm[1] else 8 * 60
    val t = oneShot(2_000, 500)
    Canvas(Modifier.size(130.dp)) {
        val r = size.minDimension / 2; val c = center
        drawCircle(WHITE.copy(alpha = 0.15f), r)
        drawCircle(WHITE, r - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
        for (i in 0 until 12) { val a = i * 30f * PI.toFloat() / 180f
            drawLine(WHITE, c + Offset(sin(a), -cos(a)) * (r - 6.dp.toPx()), c + Offset(sin(a), -cos(a)) * (r - 14.dp.toPx()), 2.5.dp.toPx()) }
        val minutes = target * t + 720f * (1f - t) * 0f
        val minAngle = (minutes % 60f) * 6f + 720f * (1f - t)          // Minutenzeiger dreht zwei Extra-Runden
        val hourAngle = (minutes % 720f) / 2f
        fun hand(deg: Float, len: Float, w: Float) { val a = deg * PI.toFloat() / 180f
            drawLine(WHITE, c, c + Offset(sin(a), -cos(a)) * len, w, StrokeCap.Round) }
        hand(hourAngle, r * 0.5f, 6.dp.toPx())
        hand(minAngle, r * 0.78f, 3.5.dp.toPx())
        drawCircle(WHITE, 4.dp.toPx(), c)
    }
}

/** Neue Stempel fallen nacheinander aufs Papier. */
@Composable
private fun StampRain(names: List<String>) {
    val t = oneShot(1_600, 500)
    val shown = names.take(6)
    Box(Modifier.fillMaxWidth().height(110.dp)) {
        shown.forEachIndexed { i, n ->
            val local = ((t * (shown.size + 2) - i) / 2f).coerceIn(0f, 1f)
            val rot = listOf(-10f, 7f, -4f, 11f, -7f, 5f)[i]
            Box(Modifier.offset(x = (i % 3 * 104).dp, y = (i / 3 * 52).dp).size(96.dp, 48.dp)
                .graphicsLayer { scaleX = 2f - local; scaleY = 2f - local; alpha = local; rotationZ = rot }
                .clip(RoundedCornerShape(24.dp)).background(WHITE.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                Text(n.uppercase(), style = MONO.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = WHITE), maxLines = 1)
            }
        }
    }
}

/** Fahrkarten-Konfetti zum Abschied. */
@Composable
private fun Confetti() {
    val inf = rememberInfiniteTransition(label = "conf")
    val t by inf.animateFloat(0f, 1f, infiniteRepeatable(tween(6_000, easing = LinearEasing)), label = "ct")
    val seeds = remember { List(28) { k -> Triple((k * 37 % 100) / 100f, (k * 53 % 100) / 100f, if (k % 3 == 0) Color(0xFFFDE68A) else WHITE) } }
    Canvas(Modifier.fillMaxWidth().height(160.dp)) {
        seeds.forEachIndexed { k, (fx, phase, col) ->
            val y = ((t + phase) % 1f) * size.height
            val x = fx * size.width + sin((t + phase) * 6.28f * 2) * 14.dp.toPx()
            translate(x, y) { rotate((t + phase) * 720f * (if (k % 2 == 0) 1 else -1), Offset.Zero) {
                drawRect(col.copy(alpha = 0.85f), Offset(-6.dp.toPx(), -3.dp.toPx()), Size(12.dp.toPx(), 6.dp.toPx()))
            } }
        }
    }
}

/** Stille Durchsage: nur Laufschrift, kein Ton. */
@Composable
private fun Ticker(s: String) {
    val x = remember { Animatable(1f) }
    LaunchedEffect(s) { while (true) { x.snapTo(1f); x.animateTo(-1.6f, tween(9_000, easing = LinearEasing)) } }
    BoxWithConstraints(Modifier.fillMaxWidth().padding(top = 10.dp).clip(RoundedCornerShape(8.dp))
        .background(Color(0xFF111827)).padding(vertical = 8.dp)) {
        Text(s, maxLines = 1, softWrap = false, style = MONO.copy(fontSize = 15.sp, color = Color(0xFFFCD34D)),
            modifier = Modifier.offset(x = maxWidth * x.value).wrapContentWidth(unbounded = true))
    }
}


/* ------------------------------------------------------------------ Teilen */

private const val STORY_W = 1080
private const val STORY_H = 1920

/** Eigene Teilen-Folie: der Monat auf einen Blick ("Mein Oktober auf der RB64"). */
@Composable
private fun ReviewShareSummary(d: MonthReviews.Data, details: Boolean) {
    val pal = PALETTE[1]
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(pal[0], pal[1], pal[2]), start = Offset(0f, 0f), end = Offset(1200f, 2600f)))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawPath(star(Offset(w * 0.9f, h * 0.12f), w * 0.45f, 12, 0.72f), WHITE.copy(alpha = 0.10f))
            drawPath(star(Offset(w * 0.05f, h * 0.92f), w * 0.5f, 7, 0.55f), WHITE.copy(alpha = 0.07f))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Text("Mein ${d.name}", style = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Black, color = WHITE, letterSpacing = (-1).sp))
            Text("auf der RB64", style = TextStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = WHITE.copy(alpha = 0.9f)))
            Spacer(Modifier.height(10.dp))
            StatLine("${d.rides}", "Fahrten")
            StatLine("${d.km.toInt()} km", MonthReviews.compare(d.km)?.text ?: "unterwegs")
            StatLine("${d.minutes / 60} h ${d.minutes % 60} min", "im Zug")
            d.punctual?.let { StatLine("$it %", "unter 6 Minuten am Ziel (Fernverkehr 2025: 60,1 %)") }
            d.longestCross?.let { StatLine("${it.second} min", "längste Kreuzung, in ${it.first}") }
            if (details) d.favTime?.let { StatLine(it, "meistens unterwegs") }
            Spacer(Modifier.weight(1f))
            Text("RB64 Live", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = WHITE))
        }
    }
}

@Composable
private fun StatLine(value: String, label: String) = Column {
    Text(value, style = TextStyle(fontSize = 44.sp, fontWeight = FontWeight.Black, color = WHITE, letterSpacing = (-1.5).sp, lineHeight = 48.sp))
    Text(label, style = TextStyle(fontSize = 15.sp, color = WHITE.copy(alpha = 0.9f), lineHeight = 20.sp))
}

/** Die aktuelle Folie als Bild: ohne Fortschrittsbalken und X, Animationen im Endzustand. */
@Composable
private fun ReviewSlideImage(page: Int, d: MonthReviews.Data) {
    val pal = PALETTE[page % PALETTE.size]
    Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(pal[0], pal[1], pal[2]), start = Offset(0f, 0f), end = Offset(1200f, 2600f)))) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width; val h = size.height
            drawPath(star(Offset(w * 0.85f, h * 0.2f), w * 0.42f, 12, 0.72f), WHITE.copy(alpha = 0.10f))
        }
        Column(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Mein ${d.name} auf der RB64", style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = WHITE.copy(alpha = 0.9f)))
            Spacer(Modifier.weight(0.25f))
            Slide(page, d, 0)
            Spacer(Modifier.weight(1f))
            Text("RB64 Live", style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.ExtraBold, color = WHITE))
        }
    }
}

/** Teilen-Dialog fuer den Rueckblick: Zusammenfassung (Standard) oder die gerade offene Folie. */
@Composable
private fun ReviewShareSheet(d: MonthReviews.Data, page: Int, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var choice by remember { mutableIntStateOf(0) }
    var details by remember { mutableStateOf(false) }
    val bmp = remember(choice, details) { mutableStateOf<android.graphics.Bitmap?>(null) }
    ShareFrame(
        title = "Rückblick teilen",
        choices = listOf("Zusammenfassung", "Diese Folie"), choice = choice, onChoice = { choice = it },
        details = details, onDetails = { details = it },
        summary = if (details) "Du teilst die Zahlen deines Monats und deine Lieblingszeit."
        else "Du teilst die Zahlen deines Monats – ohne Uhrzeiten und ohne einzelne Fahrten.",
        ready = bmp.value != null,
        onShare = { bmp.value?.let { b -> runCatching { Sharing.send(ctx, Sharing.save(ctx, listOf(b))) } }; onClose() },
        onClose = onClose,
    ) {
        androidx.compose.runtime.key(choice, details) {
            Capture(STORY_W, STORY_H, 190.dp, onBitmap = { bmp.value = it }) {
                SharedContent(details) { if (choice == 0) ReviewShareSummary(d, details) else ReviewSlideImage(page, d) }
            }
        }
    }
}
