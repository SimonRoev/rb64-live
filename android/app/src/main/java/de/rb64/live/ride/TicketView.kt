package de.rb64.live.ride

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbIcons
import de.rb64.live.ui.TextAction
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/* Papierfarben bleiben auch im dunklen Modus gleich - eine Fahrkarte ist aus Pappe. */
private val PAPER = Color(0xFFFBF8F1)
private val PAPER_1 = Color(0xFFFFFCF2)
private val PAPER_WORN = Color(0xFFF5EFE2)
private val PETROL = Color(0xFF0B5563)
private val PETROL_WORN = Color(0xFF2F6672)
private val INK = Color(0xFF111827)
private val GREY = Color(0xFF6B7280)
private val LINE = Color(0xFFE5E0D3)
private val RED = Color(0xFFB91C1C)
private val GREEN = Color(0xFF15803D)
private val BRASS = Color(0xFFB8925A)
private val GOLD = Color(0xFFE8C547)
private val AMBER_BG = Color(0xFFFEF3C7)
private val AMBER = Color(0xFF92400E)
private val COFFEE = Color(0xFF784A1E)

private val MONO = TextStyle(fontFamily = FontFamily.Monospace, color = INK)
private val SANS = TextStyle(color = INK, fontFeatureSettings = "tnum")
private val HM = SimpleDateFormat("HH:mm", Locale.GERMANY)
private val DMY = SimpleDateFormat("dd.MM.yyyy", Locale.GERMANY)
private val DM = SimpleDateFormat("dd.MM.", Locale.GERMANY)

private fun prefs(ctx: android.content.Context) = de.rb64.live.Repo.prefs(ctx)

/**
 * Fahrkarte mit Vorder- und Rueckseite. Alles Versteckte reagiert nur auf bewusstes Antippen,
 * Wischen, Druecken oder Kippen - keine Pop-ups, kein Ton.
 */
@Composable
fun TicketCard(t: Ticket, modifier: Modifier = Modifier) {
    // Drehen mit dem Finger: zur Seite ziehen dreht die Karte mit, beim Loslassen rastet sie auf einer Seite ein.
    var face by rememberSaveable(t.id) { mutableFloatStateOf(0f) }
    val angle = remember(t.id) { Animatable(face) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val skew = when (t.wear) { Wear.FOLD -> -1.2f; Wear.CRUMPLED -> -2f; else -> 0f }
    val norm = ((angle.value % 360f) + 360f) % 360f
    val front = norm <= 90f || norm >= 270f
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.widthIn(max = 380.dp).fillMaxWidth().height(480.dp)
            .pointerInput(t.id) {
                var start = 0f
                var total = 0f
                fun settle() {
                    val target = when {
                        total > size.width * 0.18f -> start + 180f
                        total < -size.width * 0.18f -> start - 180f
                        else -> start
                    }
                    face = target
                    scope.launch { angle.animateTo(target, tween(320)) }
                }
                detectHorizontalDragGestures(
                    onDragStart = { start = face; total = 0f },
                    onDragEnd = { settle() },
                    onDragCancel = { settle() },
                ) { ch, d ->
                    ch.consume()
                    total += d
                    scope.launch { angle.snapTo(start + (total / size.width * 180f).coerceIn(-180f, 180f)) }
                }
            }
            .graphicsLayer { rotationY = angle.value; rotationZ = skew; cameraDistance = 16f * density }) {
            if (front) TicketFront(t)
            else Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) { TicketBack(t) }
        }
        // bewusst kein Hinweis aufs Umdrehen - das sollen die Nutzer selbst entdecken
    }
}

/* ================================================================== Vorderseite */

@Composable
private fun TicketFront(t: Ticket) {
    val shape = RoundedCornerShape(18.dp)
    val tilt by rememberTiltXY()
    val bg = variantPaper(t) ?: when { t.wear != Wear.NONE -> PAPER_WORN; t.cls == TicketClass.FIRST -> PAPER_1; else -> PAPER }
    val ctx = LocalContext.current
    val punchKey = "tk_punch_${t.id}"
    var punch by remember { mutableFloatStateOf(prefs(ctx).getFloat(punchKey, -1f)) }
    var edgeY by remember { mutableFloatStateOf(-1f) }
    // Kerben und Zangenloch werden aus der Karte ausgestanzt: dahinter sieht man den echten Hintergrund
    var frame = Modifier.fillMaxSize().shadow(10.dp, shape).clip(shape)
        .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            if (edgeY < 0f) return@drawWithContent
            val notch = 9.dp.toPx()
            drawCircle(Color.Black, notch, Offset(0f, edgeY), blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
            drawCircle(Color.Black, notch, Offset(size.width, edgeY), blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
            if (punch >= 0f) {
                val cx = punch * size.width; val r = 6.5.dp.toPx()
                val hole = Path().apply {
                    moveTo(cx, edgeY - r); lineTo(cx + r * 0.85f, edgeY); lineTo(cx, edgeY + r); lineTo(cx - r * 0.85f, edgeY); close()
                }
                drawPath(hole, Color.Black, blendMode = androidx.compose.ui.graphics.BlendMode.Clear)
                // Tiefe: die obere Schnittkante wirft einen weichen Schatten ins Loch, unten faellt Licht auf die Kante
                clipPath(hole) {
                    drawRect(Brush.linearGradient(listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent),
                        start = Offset(cx - r, edgeY - r), end = Offset(cx + r * 0.2f, edgeY + r * 0.2f)))
                }
                drawLine(Color.White.copy(alpha = 0.55f), Offset(cx + 0.5.dp.toPx(), edgeY + r), Offset(cx + r * 0.85f, edgeY + 0.5.dp.toPx()), 0.8.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.55f), Offset(cx - 0.5.dp.toPx(), edgeY + r), Offset(cx - r * 0.85f, edgeY + 0.5.dp.toPx()), 0.8.dp.toPx())
            }
        }
        .background(bg)
    if (t.cls == TicketClass.FIRST) frame = frame.border(1.5.dp, BRASS, shape)
    if (t.cls == TicketClass.SPECIAL) frame = frame.border(2.dp, PETROL, shape)
    Box(frame) {
        Watermark(t, tilt.strength())
        Column(Modifier.fillMaxSize()) {
            if (t.cls == TicketClass.FIRST) Box(Modifier.fillMaxWidth().height(6.dp).background(GOLD))
            Header(t)
            FromTo(t)
            RouteStrip(t)
            TearEdge(t, punch, onPunch = { punch = it; prefs(ctx).edit().putFloat(punchKey, it).apply() }, onCenterY = { edgeY = it })
            Stats(t)
            Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 18.dp)) { ReservationSlip(t) }
            Bottom(t)
        }
        if (t.cls == TicketClass.SPECIAL) Column(Modifier.align(Alignment.CenterEnd).padding(end = 5.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            "SONDER".forEach { Text("$it", style = SANS.copy(fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = RED, lineHeight = 12.sp)) }
        }
        VariantOverlay(t, tilt)
        DoodleOverlay(t)
        WearOverlay(t)
    }
}

/** Wasserzeichen: kaum zu sehen - flach halten und kippen macht es deutlicher (Datum der ersten Fahrt). */
@Composable
private fun BoxScope.Watermark(t: Ticket, tilt: Float) {
    val a by animateFloatAsState(tilt, androidx.compose.animation.core.spring(stiffness = 60f), label = "wm")
    Column(Modifier.align(Alignment.Center).offset(y = (-60).dp).rotate(-24f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("RB64", style = SANS.copy(fontSize = 110.sp, fontWeight = FontWeight.Black, color = PETROL.copy(alpha = 0.022f + 0.17f * a), letterSpacing = (-4).sp))
        Text("unterwegs seit ${t.firstRide?.let { DMY.format(Date(it)) } ?: "heute"}",
            style = MONO.copy(fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PETROL.copy(alpha = 0.55f * a)))
    }
}

@Composable
private fun Header(t: Ticket) {
    val ctx = LocalContext.current
    var memory by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(memory) { if (memory != null) { delay(4_000); memory = null } }
    val head = when { t.wear != Wear.NONE -> PETROL_WORN; else -> PETROL }
    val variantHead = variantHeader(t)
    val headMod = if (variantHead != null && t.cls != TicketClass.SPECIAL) Modifier.background(variantHead)
    else if (t.cls == TicketClass.SPECIAL)
        Modifier.background(Brush.linearGradient(listOf(PETROL, Color(0xFF0E6B7C), PETROL, Color(0xFF0E6B7C)), end = Offset(120f, 0f),
            tileMode = androidx.compose.ui.graphics.TileMode.Repeated))
    else Modifier.background(head)
    Box {
        // bei Eselsohr (oben rechts) rueckt die Klassen-Ziffer nach links
        val endPad = if (t.wear >= Wear.FOLD) 44.dp else 18.dp
        Row(Modifier.fillMaxWidth().then(headMod).padding(start = 18.dp, end = endPad, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            val misprint = t.variant?.id == "misprint"
            val title = when (t.cls) { TicketClass.SPECIAL -> "RB64 LIVE · SONDERFAHRT"; else -> if (misprint) "RB64 LIVE · FAHRKRATE" else "RB64 LIVE · FAHRKARTE" } +
                (if (t.ordinal == 100) " · JUBILÄUM" else "")
            val ts = SANS.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, color = Color.White)
            Box(Modifier.weight(1f)) {
                // Fehldruck: verrutschte zweite Farbe
                if (misprint) Text(title, style = ts.copy(color = Color(0xFFF43F5E).copy(alpha = 0.55f)), maxLines = 1,
                    modifier = Modifier.offset(x = 2.dp, y = 1.5.dp))
                Text(title, style = ts, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val digit = when (t.cls) { TicketClass.FIRST -> "1"; TicketClass.SPECIAL -> "✦"; else -> "2" }
            val badgeBg = if (t.cls == TicketClass.FIRST) Brush.linearGradient(listOf(Color(0xFFF3DC8A), BRASS)) else Brush.linearGradient(listOf(Color.White, Color.White))
            Text(digit, style = SANS.copy(fontSize = 13.sp, fontWeight = FontWeight.ExtraBold,
                color = if (t.cls == TicketClass.FIRST) Color(0xFF3B2A0E) else head),
                modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(badgeBg)
                    .pointerInput(t.id) { detectTapGestures(onLongPress = {
                        memory = t.seatSeen?.let { "Sie saßen schon einmal hier: Wagen ${t.wagen}, Platz ${t.platz}, am ${DM.format(Date(it))}" }
                            ?: "Wagen ${t.wagen}, Platz ${t.platz}: zum ersten Mal hier. Willkommen."
                    }) }
                    .padding(horizontal = 8.dp, vertical = 1.dp))
        }
        memory?.let { m ->
            Text(m, style = MONO.copy(fontSize = 10.5.sp, color = Color(0xFF7A1B1B)),
                modifier = Modifier.align(Alignment.BottomEnd).offset(y = 30.dp).padding(horizontal = 12.dp)
                    .clip(RoundedCornerShape(6.dp)).background(Color.White).border(1.dp, LINE, RoundedCornerShape(6.dp)).padding(8.dp))
        }
    }
}

/**
 * VON/NACH-Kopf in drei Zeilen (Bezeichnung, Namen mit Pfeil, Uhrzeiten), damit der Pfeil immer mittig
 * auf der Namenszeile sitzt - auch im Teilen-Bild ohne Uhrzeiten.
 * Die Schrift wird so lange verkleinert, bis beide Namen ganz hineinpassen (Burgsteinfurt, De Eschmarke).
 */
@Composable
private fun FromTo(t: Ticket) {
    val showTimes = LocalShareMode.current?.details != false
    val from = t.from.uppercase(); val to = t.to.uppercase()
    val tm = rememberTextMeasurer()
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 14.dp)) {
        val avail = constraints.maxWidth
        val gap = with(density) { 44.dp.roundToPx() }          // Platz fuer den Pfeil
        val size = remember(from, to, avail, density) {
            var s = 20f
            while (s > 11f) {
                val st = SANS.copy(fontSize = s.sp, fontWeight = FontWeight.ExtraBold)
                val w = tm.measure(from, st, maxLines = 1, softWrap = false).size.width +
                    tm.measure(to, st, maxLines = 1, softWrap = false).size.width
                if (w + gap <= avail) break
                s -= 0.5f
            }
            s
        }
        val name = SANS.copy(fontSize = size.sp, fontWeight = FontWeight.ExtraBold)
        val label = SANS.copy(fontSize = 10.sp, color = GREY, letterSpacing = 1.sp)
        val time = MONO.copy(fontSize = 15.sp, fontWeight = FontWeight.Bold, color = PETROL)
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth()) { Text("VON", style = label); Spacer(Modifier.weight(1f)); Text("NACH", style = label) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(from, style = name, maxLines = 1, softWrap = false)
                Text("→", style = SANS.copy(fontSize = 22.sp, color = PETROL, textAlign = TextAlign.Center), modifier = Modifier.weight(1f).offset(y = (-2).dp))
                Text(to, style = name, maxLines = 1, softWrap = false)
            }
            if (showTimes) Row(Modifier.fillMaxWidth()) { Text(t.dep, style = time); Spacer(Modifier.weight(1f)); Text(t.arr, style = time) }
        }
    }
}

private fun short(n: String) = when (n) {
    "Münster Hbf" -> "Münster"; "Zentrum Nord" -> "Zentr. N."; "Metelen Land" -> "Metelen"; "De Eschmarke" -> "Eschm."
    else -> if (n.length > 8) n.take(6) + "." else n
}

/** Schematische Strecke: gleiche Abstaende, Kreuzung als Marke. Bei der Ehrenrunde gespiegelt. */
@Composable
private fun RouteStrip(t: Ticket) {
    val tm = rememberTextMeasurer()
    val line = if (t.wear != Wear.NONE) PETROL_WORN else PETROL
    Column {
        Canvas(Modifier.fillMaxWidth().height(70.dp).padding(horizontal = 26.dp)) {
            val n = t.stops.size
            val y = 30.dp.toPx()
            fun x(k: Int): Float { val f = if (n <= 1) 0f else k / (n - 1f); return (if (t.mirrored) 1f - f else f) * size.width }
            drawLine(LINE, Offset(0f, y), Offset(size.width, y), 10.dp.toPx(), StrokeCap.Round)
            drawLine(line, Offset(0f, y), Offset(size.width, y), 6.dp.toPx(), StrokeCap.Round)
            t.stops.forEachIndexed { k, st ->
                val end = k == 0 || k == n - 1
                if (end) drawCircle(line, 8.dp.toPx(), Offset(x(k), y))
                else { drawCircle(PAPER, 5.dp.toPx(), Offset(x(k), y)); drawCircle(line, 5.dp.toPx(), Offset(x(k), y), style = Stroke(2.5.dp.toPx())) }
                val label = n <= 7 || end || st == t.crossing?.first
                if (label) {
                    val r = tm.measure(short(st), SANS.copy(fontSize = 10.sp, color = Color(0xFF4B5563)))
                    val lx = (x(k) - r.size.width / 2f).coerceIn(-20.dp.toPx(), size.width - r.size.width + 20.dp.toPx())
                    drawText(r, topLeft = Offset(lx, y + 14.dp.toPx()))
                }
                if (st == t.crossing?.first) {
                    val r = tm.measure("⇄ ${t.crossing.second} min", SANS.copy(fontSize = 9.5.sp, fontWeight = FontWeight.Bold, color = AMBER))
                    val w = r.size.width + 14.dp.toPx(); val h = 16.dp.toPx()
                    val left = (x(k) - w / 2).coerceIn(-20.dp.toPx(), size.width - w + 20.dp.toPx())
                    drawRoundRect(AMBER_BG, Offset(left, y - 26.dp.toPx()), Size(w, h), CornerRadius(h / 2))
                    drawText(r, topLeft = Offset(left + 7.dp.toPx(), y - 26.dp.toPx() + (h - r.size.height) / 2))
                }
            }
        }
        if (t.mirrored) Text("Heute in umgekehrter Wagenreihung.", style = SANS.copy(fontSize = 10.sp, fontStyle = FontStyle.Italic, color = GREY),
            modifier = Modifier.fillMaxWidth().padding(top = 0.dp), textAlign = TextAlign.Center)
    }
}

/**
 * Abriss-Kante: Doppeltipp knipst wie die Zange des Zugbegleiters genau ein Loch und druckt Datum und Uhrzeit dazu.
 * Loch und Kerben werden in [TicketFront] wirklich aus der Karte ausgestanzt; hier nur Perforation und Aufdruck.
 */
@Composable
private fun TearEdge(t: Ticket, punch: Float, onPunch: (Float) -> Unit, onCenterY: (Float) -> Unit) {
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().height(20.dp)
        .onGloballyPositioned { c -> onCenterY(c.positionInParent().y + c.size.height / 2f) }
        .pointerInput(t.id) {
            detectTapGestures(onDoubleTap = { o -> if (punch < 0f) onPunch((o.x / size.width).coerceIn(0.1f, 0.55f)) })   // rechts bleibt Platz fuer den Aufdruck
        }) {
        Canvas(Modifier.fillMaxSize()) {
            val y = size.height / 2
            drawLine(Color(0xFFD6CFBD), Offset(16.dp.toPx(), y), Offset(size.width - 16.dp.toPx(), y), 2.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
        }
        if (punch >= 0f) Text(punchText(t, LocalShareMode.current?.details != false), style = MONO.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF4338CA).copy(alpha = 0.75f)),
            maxLines = 1, modifier = Modifier.offset(x = maxWidth * punch + 12.dp, y = 9.dp).rotate(-2f).wrapContentSize(unbounded = true))
    }
}

@Composable
private fun Stats(t: Ticket) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp)) {
        Stat("FAHRZEIT", "${t.minutes} min", Modifier.weight(1f))
        Stat("STRECKE", "%.0f km".format(t.km), Modifier.weight(1f))
        Stat("SPITZE", t.vmax?.let { "$it km/h" } ?: "–", Modifier.weight(1f))
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = SANS.copy(fontSize = 10.sp, color = GREY, letterSpacing = 1.sp))
        Text(value, style = SANS.copy(fontSize = 20.sp, fontWeight = FontWeight.ExtraBold))
    }
}

/** Platzreservierung. Nach rechts wischen zieht den Zettel ab - darunter steht ein Satz. */
@Composable
private fun BoxScope.ReservationSlip(t: Ticket) {
    if (LocalShareMode.current?.details == false) return      // verraet Wochentag und Uhrzeit
    val ctx = LocalContext.current
    val key = "tk_torn_${t.id}"
    var torn by remember { mutableStateOf(prefs(ctx).getBoolean(key, false)) }
    val x = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    Text("Sie haben Platz genommen.", style = MONO.copy(fontSize = 11.sp, fontStyle = FontStyle.Italic, color = GREY),
        modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
    if (torn) return
    val sunday = t.wagen == 7
    val bg = if (t.cls == TicketClass.FIRST) Color(0xFFFBF0C8) else Color.White
    val note = when {
        sunday -> "Wagen fehlt heute. Erstattung 1,00 € – in Stempeln."
        t.wear != Wear.NONE -> "Reservierung gilt trotzdem."
        t.cls == TicketClass.FIRST -> "Reservierung inklusive. Getränk nicht."
        else -> t.seatNote.ifBlank { "${short(t.from)} → ${short(t.to)}" }
    }
    Column(Modifier.align(Alignment.CenterStart)
        .offset { IntOffset(x.value.roundToInt(), 0) }
        .rotate(if (t.wear != Wear.NONE) -6f else -2.5f)
        .width(196.dp)
        .shadow(3.dp, RoundedCornerShape(3.dp)).clip(RoundedCornerShape(3.dp)).background(bg)
        .border(1.dp, Color(0xFFD1D5DB), RoundedCornerShape(3.dp))
        .pointerInput(t.id) {
            val limit = with(density) { 130.dp.toPx() }
            detectHorizontalDragGestures(
                onDragEnd = {
                    if (x.value > limit) { torn = true; prefs(ctx).edit().putBoolean(key, true).apply() }
                    else scope.launch { x.animateTo(0f) }
                },
            ) { ch, d -> ch.consume(); scope.launch { x.snapTo((x.value + d).coerceAtLeast(0f)) } }
        }
        .padding(start = 10.dp, end = 8.dp, top = 6.dp, bottom = 7.dp)) {
        Text("PLATZRESERVIERUNG", style = MONO.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = PETROL, letterSpacing = 1.sp))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column { Text("WAGEN", style = MONO.copy(fontSize = 7.5.sp, color = GREY))
                Text("${t.wagen}", style = MONO.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold,
                    textDecoration = if (sunday) TextDecoration.LineThrough else null, color = if (sunday) RED else INK)) }
            Column { Text("PLATZ", style = MONO.copy(fontSize = 7.5.sp, color = GREY))
                Text("${t.platz}", style = MONO.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold)) }
            Text(if (t.window) "FENSTER" else "GANG", style = MONO.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), modifier = Modifier.padding(bottom = 3.dp))
        }
        Text(note, style = MONO.copy(fontSize = 8.5.sp, color = if (sunday) RED else Color(0xFF4B5563)))
    }
}

@Composable
private fun Bottom(t: Ticket) {
    Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 14.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            val parts = buildList {
                t.depDelay?.let { add("ab ${sign(it)}") }
                t.crossing?.let { add("Kreuzung ${it.second} min") }
                t.delay?.let { add("an ${sign(it)}") }
            }
            if (parts.isNotEmpty()) Text(parts.joinToString(" · "), style = MONO.copy(fontSize = 10.5.sp, color = Color(0xFF4B5563)))
            Text("„${t.spruch}“", style = SANS.copy(fontSize = 12.sp, fontStyle = FontStyle.Italic, color = Color(0xFF374151), lineHeight = 16.sp))
            t.specialReason?.let { Text(it, style = SANS.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold, color = RED)) }
            if (t.ironed) Text("FRISCH GEBÜGELT", style = MONO.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = GREEN),
                modifier = Modifier.border(1.dp, GREEN, RoundedCornerShape(3.dp)).padding(horizontal = 4.dp))
            Text("FAHRT ${t.nthInMonth} IM ${t.monthName.uppercase()}" + (if (LocalShareMode.current?.details != false) " · ${t.train.uppercase()}" else ""), style = MONO.copy(fontSize = 9.5.sp, color = GREY),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            // seltene Sprueche/Varianten bekommen ein kleines Zeichen hinter der Nummer
            // Seltenheit steht bewusst nicht auf der Karte - sie zeigt sich nur im Stempelheft
            if (LocalShareMode.current == null) Text("Nr. ${t.serial}", style = MONO.copy(fontSize = 9.sp, color = Color(0xFF9CA3AF)), maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        EntwerterStamp(t)
    }
}

private fun sign(d: Int) = when { d > 0 -> "+$d"; d < 0 -> "$d"; else -> "±0" }

/** Entwerter. Lang druecken: Plan/Ist "geprueft" - und bei +1 bis +5 der Zusatzstempel "laut Bahn puenktlich" (unter 6 min). */
@Composable
private fun EntwerterStamp(t: Ticket) {
    var checked by remember { mutableStateOf(false) }
    LaunchedEffect(checked) { if (checked) { delay(5_000); checked = false } }
    val d = t.delay
    val ok = d != null && d <= 0
    val col = (if (t.cls == TicketClass.SPECIAL || !ok) RED else GREEN).copy(alpha = 0.8f)
    val (top, mid, bot) = when {
        t.cls == TicketClass.SPECIAL -> Triple("SONDER", "✦", "ZUG")
        d == null -> Triple("OHNE", "?", "MESSUNG")
        d == 0 && t.cls == TicketClass.FIRST -> Triple("AM ZIEL", "±0", "AUF DIE MIN")
        d <= 0 -> Triple("AM ZIEL", "✓", "PÜNKTLICH")
        else -> Triple("AM ZIEL", "+$d", "MIN")
    }
    // feste Groesse: die Einblendungen duerfen ueberstehen, ohne den Text links zu verdraengen
    Box(Modifier.size(92.dp), contentAlignment = Alignment.Center) {
        // Doppelstempel: blasser zweiter Abdruck, leicht versetzt
        if (t.variant?.id == "double") Canvas(Modifier.size(92.dp).wrapContentSize(unbounded = true).offset(x = (-10).dp, y = 7.dp).rotate(-4f).size(92.dp)) {
            drawCircle(col.copy(alpha = 0.3f), size.minDimension / 2 - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
            drawCircle(col.copy(alpha = 0.18f), size.minDimension / 2 - 8.dp.toPx(), style = Stroke(1.dp.toPx()))
        }
        Box(Modifier.size(92.dp).rotate(-14f).pointerInput(t.id) { detectTapGestures(onLongPress = { checked = true }) }) {
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(col, size.minDimension / 2 - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
                drawCircle(col.copy(alpha = 0.45f), size.minDimension / 2 - 8.dp.toPx(), style = Stroke(1.dp.toPx()))
            }
            Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(top, style = MONO.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = col, letterSpacing = 1.sp))
                Text(mid, style = SANS.copy(fontSize = if (mid.length > 3) 18.sp else 26.sp, fontWeight = FontWeight.ExtraBold, color = col))
                Text(bot, style = MONO.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = col, letterSpacing = 1.sp))
            }
        }
        if (checked && d != null && d in 1..5) Text("LAUT BAHN\nPÜNKTLICH", textAlign = TextAlign.Center,
            style = MONO.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, color = GREEN, lineHeight = 11.sp),
            modifier = Modifier.wrapContentSize(unbounded = true).offset(x = (-18).dp, y = 30.dp).rotate(6f).background(Color.White.copy(alpha = 0.85f))
                .border(2.dp, GREEN, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp))
        if (checked) Text("Plan ${t.arrPlan ?: "–"} · Ist ${t.arr} · geprüft", style = MONO.copy(fontSize = 9.5.sp, color = Color(0xFF7A1B1B)),
            modifier = Modifier.wrapContentSize(unbounded = true).offset(x = (-60).dp, y = (-62).dp).background(Color.White).border(1.dp, LINE, RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 3.dp), maxLines = 1)
    }
}

/** Gebrauchsspuren: Kaffeerand, Knick und Eselsohr, zerknuellt (stabil je Fahrt). */
@Composable
private fun WearOverlay(t: Ticket) {
    if (t.wear == Wear.NONE) return
    val page = Rb.c.background
    Canvas(Modifier.fillMaxSize()) {
        val rnd = java.util.Random(t.id.hashCode().toLong())
        val w = size.width; val h = size.height
        // Kaffeerand
        val c = Offset(w * 0.24f, h * 0.70f); val r = 46.dp.toPx()
        drawCircle(COFFEE.copy(alpha = 0.07f), r, c)
        drawCircle(COFFEE.copy(alpha = 0.32f), r, c, style = Stroke(4.dp.toPx()))
        drawCircle(COFFEE.copy(alpha = 0.12f), r + 4.dp.toPx(), c, style = Stroke(2.dp.toPx()))
        if (t.wear >= Wear.FOLD) {
            fun fold(a: Offset, b: Offset) {
                drawLine(Color.Black.copy(alpha = 0.10f), a, b, 1.5.dp.toPx())
                drawLine(Color.White.copy(alpha = 0.55f), a + Offset(0f, 2.dp.toPx()), b + Offset(0f, 2.dp.toPx()), 1.5.dp.toPx())
            }
            fold(Offset(0f, h * 0.60f), Offset(w, h * 0.53f))
            // Eselsohr oben rechts
            val e = 34.dp.toPx()
            drawPath(Path().apply { moveTo(w - e, 0f); lineTo(w, 0f); lineTo(w, e); close() }, page)
            drawPath(Path().apply { moveTo(w - e, 0f); lineTo(w, e); lineTo(w - e, e); close() }, Color(0xFFE7DDC8))
            drawPath(Path().apply { moveTo(w - e, 0f); lineTo(w, e); lineTo(w - e, e); close() }, Color.Black.copy(alpha = 0.10f), style = Stroke(1.dp.toPx()))
            if (t.wear == Wear.CRUMPLED) {
                fold(Offset(w * 0.12f, 0f), Offset(w * 0.55f, h))
                fold(Offset(w, h * 0.18f), Offset(0f, h * 0.36f))
                repeat(70) {
                    val p = Offset(rnd.nextFloat() * w, rnd.nextFloat() * h)
                    val s = (18 + rnd.nextInt(40)).dp.toPx()
                    val tri = Path().apply {
                        moveTo(p.x, p.y); lineTo(p.x + (rnd.nextFloat() - 0.5f) * s * 2, p.y + (rnd.nextFloat() - 0.5f) * s * 2)
                        lineTo(p.x + (rnd.nextFloat() - 0.5f) * s * 2, p.y + (rnd.nextFloat() - 0.5f) * s * 2); close()
                    }
                    drawPath(tri, (if (rnd.nextBoolean()) Color.Black else Color.White).copy(alpha = 0.025f + rnd.nextFloat() * 0.04f))
                }
            }
        }
    }
}

/* ================================================================== Rueckseite */

@Composable
private fun TicketBack(t: Ticket) {
    val shape = RoundedCornerShape(18.dp)
    val bg = if (t.wear != Wear.NONE) PAPER_WORN else PAPER
    Box(Modifier.fillMaxSize().shadow(10.dp, shape).clip(shape).background(bg)) {
        Text("RB64", style = SANS.copy(fontSize = 110.sp, fontWeight = FontWeight.Black, color = PETROL.copy(alpha = 0.04f)),
            modifier = Modifier.align(Alignment.Center).rotate(-24f))
        Column(Modifier.fillMaxSize().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("RÜCKSEITE", style = SANS.copy(fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, color = PETROL), modifier = Modifier.weight(1f))
                if (LocalShareMode.current == null) Text("Nr. ${t.serial}", style = MONO.copy(fontSize = 10.sp, color = Color(0xFF9CA3AF)))
            }
            val rows = buildList {
                add("Fahrzeit" to "${t.minutes} min"); add("Strecke" to "%.0f km".format(t.km))
                t.vmax?.let { add("Spitze" to "$it km/h") }
                add("Halte" to "${(t.stops.size - 2).coerceAtLeast(0)}")
                t.crossing?.let { add("Kreuzung" to "${it.second} min") }
            }
            Column { rows.forEach { (k, v) -> Text(k.padEnd(14, '.') + v.padStart(9, '.'), style = MONO.copy(fontSize = 12.5.sp, lineHeight = 20.sp)) } }
            Canvas(Modifier.fillMaxWidth().height(2.dp)) {
                drawLine(Color(0xFFD6CFBD), Offset(0f, 1f), Offset(size.width, 1f), 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f)))
            }
            if (t.beileger != null) {
                Text("BEILEGER", style = SANS.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp, color = GREY))
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { BeilegerView(t, t.beileger) }
            }
            Spacer(Modifier.weight(1f))
            if (LocalShareMode.current?.details != false) Row(verticalAlignment = Alignment.CenterVertically) {
                StationClock(t)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text("ANKUNFT", style = SANS.copy(fontSize = 10.sp, color = GREY, letterSpacing = 1.sp))
                    Text(t.arr, style = MONO.copy(fontSize = 18.sp, fontWeight = FontWeight.Bold))
                }
            }
            Text(buildString { append("Beförderungsbedingungen § 64 Abs. ${t.ordinal % 9 + 1}: "); append(t.agb) },
                style = SANS.copy(fontSize = 10.sp, color = GREY, lineHeight = 14.sp))
        }
    }
}

/**
 * Bahnhofsuhr mit der Ankunftszeit. Lang druecken: der Sekundenzeiger laeuft 58 s, wartet oben 2 s,
 * dann springt der Minutenzeiger - wie bei den Bahnhofsuhren der DB (Start bei Sekunde 50, damit man es sieht).
 */
@Composable
private fun StationClock(t: Ticket) {
    val base = t.arrMeasured ?: t.started
    var pressedAt by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(0L) }
    LaunchedEffect(pressedAt) { while (pressedAt > 0) { now = System.currentTimeMillis(); delay(40) } }
    val c = Calendar.getInstance().apply { timeInMillis = base }
    val running = pressedAt > 0
    val elapsed = if (running) (now - pressedAt).coerceAtLeast(0) / 1000f + 50f else 0f   // Sekunden ab :50
    val sec = elapsed % 60f
    val minutes = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE) + (elapsed / 60f).toInt()
    val secAngle = if (!running) 0f else if (sec < 58f) sec / 58f * 360f else 0f
    Canvas(Modifier.size(60.dp).pointerInput(t.id) {
        detectTapGestures(onPress = {
            pressedAt = System.currentTimeMillis(); now = pressedAt
            tryAwaitRelease(); pressedAt = 0L
        })
    }) {
        val r = size.minDimension / 2
        val cen = center
        drawCircle(Color.White, r)
        drawCircle(INK, r - 1.dp.toPx(), style = Stroke(2.5.dp.toPx()))
        for (i in 0 until 60) {
            val a = i * 6f * PI.toFloat() / 180f
            val len = if (i % 5 == 0) 6.dp.toPx() else 2.5.dp.toPx()
            val o = r - 4.dp.toPx()
            drawLine(INK, cen + Offset(sin(a) * o, -cos(a) * o), cen + Offset(sin(a) * (o - len), -cos(a) * (o - len)),
                if (i % 5 == 0) 2.dp.toPx() else 0.8.dp.toPx())
        }
        fun hand(deg: Float, len: Float, w: Float, col: Color, back: Float = 0f) {
            val a = deg * PI.toFloat() / 180f
            drawLine(col, cen - Offset(sin(a) * back, -cos(a) * back), cen + Offset(sin(a) * len, -cos(a) * len), w, StrokeCap.Butt)
        }
        hand((minutes % 720) / 2f, r * 0.5f, 3.5.dp.toPx(), INK)
        hand((minutes % 60) * 6f, r * 0.75f, 2.5.dp.toPx(), INK)
        hand(secAngle, r * 0.78f, 1.2.dp.toPx(), RED, back = r * 0.2f)
        drawCircle(INK, 2.dp.toPx(), cen)
    }
}

@Composable
private fun BeilegerView(t: Ticket, b: Beileger) {
    val ctx = LocalContext.current
    when (b) {
        is Beileger.Stammplatz -> Column(Modifier.rotate(-1f).width(210.dp).shadow(3.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp)).background(Brush.linearGradient(listOf(Color(0xFFF3DC8A), Color(0xFFC9A15E), Color(0xFFE8C77E))))
            .padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("STAMMPLATZ", style = SANS.copy(fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 2.sp, color = Color(0xFF3B2A0E)))
            Text(if (LocalShareMode.current?.details == false) "Stammgast" else b.label, style = SANS.copy(fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = Color(0xFF3B2A0E)))
            Text("Nicht reservierbar, nur verdient.", style = SANS.copy(fontSize = 9.5.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF3B2A0E)))
        }
        is Beileger.Fahrgastrechte -> {
            val key = "tk_fgr_${t.id}"
            var bits by remember { mutableIntStateOf(prefs(ctx).getInt(key, 1)) }
            Column(Modifier.rotate(1.2f).width(240.dp).shadow(3.dp, RoundedCornerShape(2.dp)).background(Color.White).padding(10.dp)) {
                Text("FAHRGASTRECHTE · KURZANTRAG", style = MONO.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold, color = PETROL, letterSpacing = 1.sp))
                listOf("Ich habe gewartet. (+${b.delay})", "Ich habe es gelassen gesehen.", "Ich habe den Gegenzug gegrüßt.").forEachIndexed { k, s ->
                    val on = bits and (1 shl k) != 0
                    Text((if (on) "☒ " else "☐ ") + s, style = MONO.copy(fontSize = 10.5.sp, lineHeight = 17.sp),
                        modifier = Modifier.fillMaxWidth().clickable { bits = bits xor (1 shl k); prefs(ctx).edit().putInt(key, bits).apply() }
                            .padding(vertical = 5.dp))
                }
                Text(when {
                    b.delay >= 120 -> "Anspruch: 50 % des Fahrpreises. Bitte wirklich beantragen."
                    b.delay >= 60 -> "Anspruch: 25 % des Fahrpreises. Bitte wirklich beantragen."
                    else -> "Anspruch ab 60 min (25 %). Ihnen fehlen noch ${60 - b.delay} min."
                }, style = MONO.copy(fontSize = 8.5.sp, color = GREY))
                // ab 60 min echter Anspruch: Antrag bei der DB (Frist 3 Monate nach der Fahrt)
                if (b.delay >= 60) {
                    val left = (t.started + 92L * 86_400_000L - System.currentTimeMillis()) / 86_400_000L
                    if (left > 0) Text("Bei der DB beantragen (noch $left Tage) →", style = MONO.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = PETROL),
                        modifier = Modifier.padding(top = 6.dp).clickable {
                            runCatching {
                                ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("https://www.bahn.de/service/fahrplaene/fahrgastrechte/entschaedigung"))
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            }
                        }.padding(vertical = 6.dp))
                    else Text("Frist von 3 Monaten ist abgelaufen.", style = MONO.copy(fontSize = 8.5.sp, color = GREY), modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
        is Beileger.Zugbindung -> Column(Modifier.rotate(-8f).border(3.dp, RED.copy(alpha = 0.85f), RoundedCornerShape(6.dp))
            .padding(3.dp).border(1.dp, RED.copy(alpha = 0.6f), RoundedCornerShape(4.dp)).padding(horizontal = 14.dp, vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Text("ZUGBINDUNG", style = MONO.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = RED, letterSpacing = 1.5.sp))
            Text("AUFGEHOBEN", style = MONO.copy(fontSize = 19.sp, fontWeight = FontWeight.Bold, color = RED))
            Text("Gedanklich jeden Zug nehmen erlaubt.", style = MONO.copy(fontSize = 8.5.sp, color = Color(0xFF7F1D1D)))
        }
        is Beileger.Kreuzung -> Column(Modifier.rotate(-2f).width(250.dp).shadow(3.dp, RoundedCornerShape(2.dp)).background(Color.White)) {
            Box(Modifier.fillMaxWidth().height(5.dp).background(Color(0xFFF59E0B)))
            Column(Modifier.padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text("KREUZUNGSAUFTRAG · ${b.station.uppercase()}", style = MONO.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp))
                Row { Text("${if (LocalShareMode.current?.details == false) "RB64" else t.train} → ", style = MONO.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold))
                    Text("⇄", style = MONO.copy(fontSize = 11.sp, color = Color(0xFFF59E0B)))
                    Text(" ← Gegenzug", style = MONO.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f))
                    Text("${b.minutes} min", style = MONO.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold)) }
                Text("Begegnung abgewartet. Der Gegenzug durfte zuerst wichtig sein.", style = MONO.copy(fontSize = 9.sp, color = Color(0xFF4B5563)))
            }
        }
        is Beileger.Gleiswechsel -> Column(Modifier.rotate(4f).width(200.dp).shadow(3.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp)).background(Color(0xFFFACC15)).padding(10.dp)) {
            Text("HEUTE ABWEICHEND", style = SANS.copy(fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 1.5.sp))
            Text(if (b.station == t.from) "ab ${b.station}" else "bis ${b.station}", style = SANS.copy(fontSize = 18.sp, fontWeight = FontWeight.ExtraBold))
            Text("Wir bitten um Ihr Verständnis und Ihre Beine.", style = SANS.copy(fontSize = 9.5.sp))
        }
        is Beileger.Bordbistro -> Column(Modifier.width(170.dp).shadow(3.dp, RoundedCornerShape(2.dp)).background(Color.White).padding(10.dp)) {
            Text("BORDBISTRO RB64", style = MONO.copy(fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            b.items.forEach { Row { Text("1× $it", style = MONO.copy(fontSize = 10.sp), modifier = Modifier.weight(1f)); Text("0,00", style = MONO.copy(fontSize = 10.sp)) }
                if (it == "Keks") Text("(rund, rot, seit 1955)", style = MONO.copy(fontSize = 8.sp, color = GREY)) }
            Box(Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(Color(0xFFD1D5DB)))
            Row { Text("SUMME EUR", style = MONO.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold), modifier = Modifier.weight(1f)); Text("0,00", style = MONO.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold)) }
            Text("Stammgast-Rabatt", style = MONO.copy(fontSize = 8.sp, color = GREY), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        is Beileger.Kontrolle -> Column(Modifier.rotate(2f).width(230.dp).shadow(3.dp, RoundedCornerShape(2.dp)).background(Color.White)
            .border(1.dp, Color(0xFFCBD5E1)).padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("FAHRSCHEINKONTROLLE", style = MONO.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = PETROL), modifier = Modifier.weight(1f))
                // Knipszangen-Raute
                Canvas(Modifier.size(12.dp)) {
                    val p = Path().apply { moveTo(size.width / 2, 0f); lineTo(size.width, size.height / 2); lineTo(size.width / 2, size.height); lineTo(0f, size.height / 2); close() }
                    drawPath(p, Color(0xFFE9EDF1)); drawPath(p, Color.Black.copy(alpha = 0.3f), style = Stroke(1.dp.toPx()))
                }
            }
            Text("Kontrolliert in ${b.station}.", style = MONO.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold))
            Text("Alles in Ordnung, schönen Tag noch.", style = MONO.copy(fontSize = 9.5.sp, color = Color(0xFF4B5563)))
        }
        is Beileger.Fundbuero -> Column(Modifier.rotate(-3f).width(220.dp).shadow(3.dp, RoundedCornerShape(2.dp)).background(Color(0xFFFEF9C3)).padding(10.dp)) {
            Text("FUNDBÜRO · NR. 0064", style = MONO.copy(fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, color = Color(0xFF422006)))
            Text("Gefunden: ${b.item}", style = MONO.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold, color = Color(0xFF422006)))
            Text(b.note, style = MONO.copy(fontSize = 9.5.sp, color = Color(0xFF422006)))
        }
    }
}

/* ================================================================== Teilen */

private val SHARE_BG = Color(0xFFE9EDF1)
private const val SHARE_W = 1080

/** Ein Teilen-Bild: Karte (Vorder- oder Rueckseite) auf hellem Grund, darunter klein "RB64 Live" und der Monat. */
@Composable
private fun TicketShareImage(t: Ticket, back: Boolean, details: Boolean) {
    val month = SimpleDateFormat("MMMM yyyy", Locale.GERMANY).format(Date(t.started))
    val day = SimpleDateFormat("d. MMMM yyyy", Locale.GERMANY).format(Date(t.started))
    SharedContent(details) {
        Column(Modifier.fillMaxSize().background(SHARE_BG).padding(24.dp)) {
            Box(Modifier.fillMaxWidth().weight(1f)) { if (back) TicketBack(t) else TicketFront(t) }
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("RB64 Live", style = SANS.copy(fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = PETROL), modifier = Modifier.weight(1f))
                Text(if (details) day else month, style = SANS.copy(fontSize = 13.sp, color = GREY))
            }
        }
    }
}

/** Teilen-Dialog fuer eine Fahrkarte: Vorderseite oder beide Seiten (als zwei Bilder). */
@Composable
fun TicketShareSheet(t: Ticket, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val density = LocalDensity.current
    var both by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf(false) }
    // Hoehe aus der Breite: Karte 380:480, dazu Rand und Fusszeile
    val hPx = with(density) {
        val w = SHARE_W.toDp() - 48.dp
        (w * (480f / 380f) + 48.dp + 34.dp).roundToPx()
    }
    val front = remember(details) { mutableStateOf<android.graphics.Bitmap?>(null) }
    val back = remember(details, both) { mutableStateOf<android.graphics.Bitmap?>(null) }
    ShareFrame(
        title = "Fahrkarte teilen",
        choices = listOf("Vorderseite", "Beide Seiten"), choice = if (both) 1 else 0, onChoice = { both = it == 1 },
        details = details, onDetails = { details = it },
        summary = if (details) "Du teilst die Karte mit Datum, Uhrzeiten, Zugnummer und Platz. Die Seriennummer bleibt weg."
        else "Du teilst Strecke, Fahrzeit, km, Spruch und Stempel mit dem Monat – ohne Datum, Uhrzeiten, Zugnummer und Platz.",
        ready = front.value != null && (!both || back.value != null),
        onShare = {
            val list = listOfNotNull(front.value, if (both) back.value else null)
            runCatching { Sharing.send(ctx, Sharing.save(ctx, list)) }
            onClose()
        },
        onClose = onClose,
    ) {
        key(details) { Capture(SHARE_W, hPx, if (both) 150.dp else 240.dp, onBitmap = { front.value = it }) { TicketShareImage(t, back = false, details = details) } }
        if (both) key(details, "back") { Capture(SHARE_W, hPx, 150.dp, onBitmap = { back.value = it }) { TicketShareImage(t, back = true, details = details) } }
    }
}
