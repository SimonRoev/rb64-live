package de.rb64.live.ride

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.rb64.live.ui.Dim
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbCard
import de.rb64.live.ui.SectionHeader
import de.rb64.live.ui.TopBar
import de.rb64.live.ui.Ts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val HEFT = Color(0xFFFFFDF7)
private val MONO = TextStyle(fontFamily = FontFamily.Monospace)

/** Stempelfarbe und Symbol je Bahnhof. */
private val STAMP = mapOf(
    "Münster Hbf" to (Color(0xFF1D4ED8) to "uhr"), "Zentrum Nord" to (Color(0xFF0F766E) to "nord"),
    "Häger" to (Color(0xFF15803D) to "baum"), "Altenberge" to (Color(0xFF9D174D) to "kirche"),
    "Nordwalde" to (Color(0xFF0369A1) to "rad"), "Borghorst" to (Color(0xFF7C3AED) to "stift"),
    "Grottenkamp" to (Color(0xFF92400E) to "grotte"), "Burgsteinfurt" to (Color(0xFFB91C1C) to "schloss"),
    "Metelen Land" to (Color(0xFFA16207) to "aehre"), "Ochtrup" to (Color(0xFF0B5563) to "spule"),
    "Gronau" to (Color(0xFF9333EA) to "gitarre"), "Glanerbrug" to (Color(0xFFC2410C) to "grenze"),
    "De Eschmarke" to (Color(0xFF15803D) to "muehle"), "Enschede" to (Color(0xFF1E40AF) to "tor"),
)

/** Minimales Symbol im 24er-Raster, mit Strich gezeichnet. */
private fun DrawScope.stampIcon(kind: String, col: Color, box: Float, origin: Offset) {
    val k = box / 24f
    fun o(x: Float, y: Float) = Offset(origin.x + x * k, origin.y + y * k)
    val st = Stroke(1.8f * k, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(vararg p: Float) { val path = Path(); path.moveTo(o(p[0], p[1]).x, o(p[0], p[1]).y)
        var i = 2; while (i < p.size) { path.lineTo(o(p[i], p[i + 1]).x, o(p[i], p[i + 1]).y); i += 2 }; drawPath(path, col, style = st) }
    fun circle(x: Float, y: Float, r: Float) = drawCircle(col, r * k, o(x, y), style = st)
    when (kind) {
        "uhr" -> { circle(12f, 12f, 8f); line(12f, 7f, 12f, 12f, 15f, 14f) }
        "nord" -> { line(12f, 4f, 16f, 13f, 8f, 13f, 12f, 4f); line(12f, 13f, 12f, 20f) }
        "baum" -> { line(12f, 3f, 18f, 13f, 6f, 13f, 12f, 3f); line(12f, 13f, 12f, 20f) }
        "kirche" -> { line(12f, 2f, 12f, 6f); line(10f, 4f, 14f, 4f); line(8f, 20f, 8f, 11f, 12f, 6f, 16f, 11f, 16f, 20f, 8f, 20f) }
        "rad" -> { line(12f, 11f, 12f, 21f); line(12f, 11f, 6f, 6f); line(12f, 11f, 19f, 9f); line(12f, 11f, 11f, 3f) }
        "stift" -> { line(6f, 20f, 18f, 20f); line(8f, 20f, 8f, 10f, 16f, 10f, 16f, 20f); line(12f, 10f, 12f, 4f); line(10f, 6f, 14f, 6f) }
        "grotte" -> { line(4f, 19f, 5f, 12f, 8f, 8f, 12f, 6f, 16f, 8f, 19f, 12f, 20f, 19f); line(9f, 19f, 10f, 15f, 12f, 13f, 14f, 15f, 15f, 19f) }
        "schloss" -> { line(7f, 20f, 7f, 8f, 17f, 8f, 17f, 20f); line(7f, 8f, 7f, 5f, 9f, 5f, 9f, 7f, 11f, 7f, 11f, 5f, 13f, 5f, 13f, 7f, 15f, 7f, 15f, 5f, 17f, 5f, 17f, 8f); line(11f, 20f, 11f, 16f, 13f, 16f, 13f, 20f) }
        "aehre" -> { line(12f, 21f, 12f, 6f); listOf(9f, 13f, 17f).forEach { y -> line(9f, y - 2f, 12f, y, 15f, y - 2f) } }
        "spule" -> { line(8f, 4f, 16f, 4f); line(8f, 20f, 16f, 20f); line(9f, 4f, 15f, 20f); line(15f, 4f, 9f, 20f) }
        "gitarre" -> { circle(9f, 16f, 4f); line(11.5f, 13.5f, 19f, 6f); line(18f, 4f, 21f, 7f) }
        "grenze" -> { line(12f, 3f, 12f, 21f); line(12f, 5f, 19f, 5f, 17f, 8f, 19f, 11f, 12f, 11f) }
        "muehle" -> { line(9f, 21f, 10.5f, 12f, 13.5f, 12f, 15f, 21f); line(6f, 4f, 18f, 16f); line(18f, 4f, 6f, 16f) }
        else -> { line(5f, 21f, 5f, 9f, 12f, 4f, 19f, 9f, 19f, 21f); line(10f, 21f, 10f, 16f, 14f, 16f, 14f, 21f) }
    }
}

/** Ein Bahnhofsstempel (gesammelt, leer oder als Geisterstempel). */
@Composable
private fun StampCircle(name: String, rot: Float, ghost: Boolean = false, glow: Boolean = false) {
    val (col0, icon) = STAMP[name] ?: (Color(0xFF0B5563) to "tor")
    val col = col0.copy(alpha = if (ghost) 0.22f else 0.88f)
    Box(Modifier.size(84.dp).rotate(rot), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = size.minDimension / 2
            if (glow) drawCircle(col0.copy(alpha = 0.12f), r)
            drawCircle(col, r - 1.5.dp.toPx(), style = Stroke(2.5.dp.toPx()))
            drawCircle(col.copy(alpha = col.alpha * 0.7f), r - 6.dp.toPx(), style = Stroke(1.dp.toPx()))
            val b = 26.dp.toPx()
            stampIcon(icon, col, b, Offset(center.x - b / 2, center.y - b / 2 + 1.dp.toPx()))
        }
        Text(name.uppercase(), style = MONO.copy(fontSize = 6.5.sp, fontWeight = FontWeight.Bold, color = col, letterSpacing = 0.5.sp),
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 13.dp), maxLines = 1)
        Text("RB64", style = MONO.copy(fontSize = 7.sp, fontWeight = FontWeight.Bold, color = col),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 13.dp))
    }
}

/** Farbe je Seltenheit (Sonderstempel, Sprueche). */
private fun rarityColors(r: Rarity): Pair<Color, Color> = when (r) {
    Rarity.COMMON -> Color(0xFFFEF3C7) to Color(0xFF92400E)
    Rarity.UNCOMMON -> Color(0xFFE3F1F3) to Color(0xFF0B5563)
    Rarity.RARE -> Color(0xFFEDE9FE) to Color(0xFF6D28D9)
    Rarity.LEGENDARY -> Color(0xFFFDE68A) to Color(0xFF713F12)
}

/**
 * Stempelheft: ein Stempel je Bahnhof, an dem du mitgefahren bist. Versteckt: leeres Feld lang druecken
 * (Geisterstempel), benachbarte Stempel nacheinander antippen (Perlenkette - nach 3 s ohne Beruehrung faehrt der Zug),
 * Glanerbrug lang druecken, "Heft 1" lang druecken (Rueckseite des Hefts).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StampBookScreen(rides: List<RideMeta>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val book by produceState<Tickets.Book?>(null, rides) { value = withContext(Dispatchers.IO) { Tickets.book(ctx, rides) } }
    val names = Stations.all.map { it.name }
    val counts = book?.stations ?: emptyMap()
    val got = names.filter { (counts[it] ?: 0) > 0 }
    // Perlenkette: angetippte, in der Linie benachbarte Stempel (Reihenfolge = Fahrtrichtung)
    var chain by remember { mutableStateOf(listOf<String>()) }
    var taps by remember { mutableIntStateOf(0) }
    val train = remember { Animatable(0f) }
    var running by remember { mutableStateOf(false) }
    var border by remember { mutableIntStateOf(0) }
    var backCover by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf<Special?>(null) }
    var showLines by remember { mutableStateOf(false) }
    val found = remember(book) { Extras.foundLines(ctx) }
    LaunchedEffect(taps) {
        if (taps == 0 || running) return@LaunchedEffect
        kotlinx.coroutines.delay(3_000)
        if (chain.size >= 2) running = true else chain = emptyList()
    }
    LaunchedEffect(running) {
        if (running) { train.snapTo(0f); train.animateTo(1f, tween(900 * chain.size, easing = LinearEasing)); running = false; chain = emptyList() }
    }
    fun tap(name: String) {
        if (running) return
        val last = chain.lastOrNull()
        val gi = got.indexOf(name)
        val step = if (last == null) 0 else gi - got.indexOf(last)
        val dir = if (chain.size >= 2) got.indexOf(chain[chain.size - 1]) - got.indexOf(chain[chain.size - 2]) else step
        // naechster gesammelter Stempel in der Linie, Richtung bleibt gleich; sonst neu anfangen
        chain = if (last != null && name !in chain && kotlin.math.abs(step) == 1 && step == dir) chain + name else listOf(name)
        taps++
    }
    val rots = listOf(-8f, 5f, -3f, 9f, -6f, 3f, -10f, 6f, -4f, 8f, -5f, 4f, -7f, 2f)

    Column(Modifier.fillMaxSize()) {
        TopBar("Stempelheft", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Dim.s16), verticalArrangement = Arrangement.spacedBy(Dim.s12)) {
            if (backCover) {
                // Rueckseite des Hefts - nur wer "Heft 1" lange drueckt, findet sie
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.rCard)).background(Color(0xFF0B5563))
                    .pointerInput(Unit) { detectTapGestures(onTap = { backCover = false }) }.padding(Dim.s16)) {
                    Text("RÜCKSEITE DES HEFTS", style = MONO.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.7f), letterSpacing = 1.5.sp))
                    Spacer(Modifier.height(Dim.s8))
                    Text("Fahrplanauszug", style = Ts.textStrong, color = Color.White)
                    Text("Grottenkamp ab 00:00 · Gleis – · Zug 0", style = MONO.copy(fontSize = 13.sp, color = Color.White))
                    Text("verkehrt nicht", style = MONO.copy(fontSize = 11.sp, color = Color(0xFFFDE68A)))
                }
            } else Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.rCard)).background(HEFT).padding(Dim.s16)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${got.size} von ${names.size} Bahnhöfen", style = Ts.textStrong, color = Color(0xFF111827), modifier = Modifier.weight(1f))
                    Text(if (got.size == names.size) "Streckenkenner" else "Heft 1", style = MONO.copy(fontSize = 11.sp, color = Color(0xFF6B7280)),
                        modifier = Modifier.pointerInput(Unit) { detectTapGestures(onLongPress = { backCover = true }) })
                }
                Spacer(Modifier.height(Dim.s8))
                Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Color(0xFFE5E7EB))) {
                    Box(Modifier.fillMaxWidth(got.size / names.size.toFloat()).height(6.dp).background(Color(0xFF0B5563)))
                }
                // Perlenkette: Platz ist immer reserviert (sonst verrutscht das Heft beim Antippen), sichtbar erst mit Stempeln
                Column(Modifier.height(50.dp)) { if (chain.isNotEmpty()) {
                    Canvas(Modifier.fillMaxWidth().height(34.dp).padding(top = 12.dp)) {
                        val n = chain.size.coerceAtLeast(2)
                        val y = size.height / 2
                        drawLine(Color(0xFF0B5563), Offset(0f, y), Offset(size.width, y), 3.dp.toPx(), StrokeCap.Round)
                        chain.forEachIndexed { i, s ->
                            drawCircle(STAMP[s]?.first ?: Color.Gray, 6.dp.toPx(), Offset(if (chain.size == 1) 0f else i / (n - 1f) * size.width, y))
                        }
                        if (running) {
                            val x = train.value * size.width
                            drawRoundRect(Color(0xFF0B5563), Offset(x - 18.dp.toPx(), y - 7.dp.toPx()), Size(36.dp.toPx(), 12.dp.toPx()), CornerRadius(5.dp.toPx()))
                            repeat(3) { k -> drawRect(Color.White, Offset(x - 14.dp.toPx() + k * 10.dp.toPx(), y - 4.dp.toPx()), Size(6.dp.toPx(), 4.dp.toPx())) }
                        }
                    }
                    Text(if (chain.size >= 2) "${chain.first()} → ${chain.last()}" else chain.first(),
                        style = MONO.copy(fontSize = 10.sp, color = Color(0xFF6B7280)))
                } }
            }

            if (book == null) Text("Stempel werden gezählt …", style = Ts.small, color = Rb.c.muted)
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.rCard)).background(HEFT).padding(vertical = Dim.s16),
                verticalArrangement = Arrangement.spacedBy(Dim.s12)) {
                names.chunked(3).forEachIndexed { row, three ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        three.forEachIndexed { col, name ->
                            val idx = row * 3 + col
                            val n = counts[name] ?: 0
                            var ghost by remember { mutableStateOf(false) }
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.pointerInput(name, n, got) {
                                detectTapGestures(
                                    onTap = { if (n > 0) tap(name) },
                                    onLongPress = { if (n > 0 && name == "Glanerbrug") border = (border + 1) % 3 },
                                    onPress = { if (n == 0) { ghost = true; tryAwaitRelease(); ghost = false } },
                                )
                            }) {
                                if (n > 0) StampCircle(name, rots[idx], glow = name in chain)
                                else Box(Modifier.size(84.dp), contentAlignment = Alignment.Center) {
                                    Canvas(Modifier.fillMaxSize()) {
                                        drawCircle(Color(0xFFCBD5E1), size.minDimension / 2 - 2.dp.toPx(),
                                            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                                    }
                                    if (ghost) StampCircle(name, rots[idx], ghost = true)
                                    else Text(name, style = Ts.small.copy(fontSize = 10.sp), color = Color(0xFF9CA3AF), textAlign = TextAlign.Center,
                                        modifier = Modifier.padding(10.dp))
                                }
                                Text(when {
                                    n == 0 -> " "
                                    // normal ×n wie ueberall; lang druecken wechselt die Aufschrift
                                    name == "Glanerbrug" -> listOf("×$n", "Instappen", "Sänk ju for träwelling")[border]
                                    else -> "×$n"
                                }, style = MONO.copy(fontSize = 9.sp, color = Color(0xFF6B7280), textAlign = TextAlign.Center, lineHeight = 11.sp),
                                    maxLines = 2, modifier = Modifier.width(96.dp))
                            }
                        }
                        repeat(3 - three.size) { Box(Modifier.size(84.dp)) }
                    }
                }
            }

            val have = book?.specials ?: emptySet()
            SectionHeader("Sonderstempel · ${have.size} von ${Extras.SPECIALS.size}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dim.s8), verticalArrangement = Arrangement.spacedBy(Dim.s8)) {
                Extras.SPECIALS.filter { it.key in have }.forEach { sp ->
                    val (bg, fg) = rarityColors(sp.rarity)
                    val label = if (sp.key == "knitter") "${sp.name} ×${book?.knitter ?: 0}" else sp.name
                    Text(label, style = Ts.smallBold, color = fg,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg)
                            .pointerInput(sp.key) { detectTapGestures(onTap = { hint = if (hint == sp) null else sp }) }
                            .padding(horizontal = 12.dp, vertical = 6.dp))
                }
                val missing = Extras.SPECIALS.size - have.size
                if (missing > 0) Text("? ? ?  noch $missing versteckt", style = Ts.smallBold, color = Color(0xFF9CA3AF),
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp))
            }
            hint?.let { Text("${it.name} (${it.rarity.label}): ${it.hint}", style = Ts.small, color = Rb.c.muted) }

            // Kartenvarianten: gefundene mit Anzahl, unbekannte nur als Fragezeichen
            val vars = book?.variants ?: emptyMap()
            val allVars = Extras.VARIANTS.distinctBy { it.name }
            SectionHeader("Varianten · ${vars.size + (if ((book?.doodles ?: 0) > 0) 1 else 0)} von ${allVars.size + 1}")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Dim.s8), verticalArrangement = Arrangement.spacedBy(Dim.s8)) {
                val doodles = book?.doodles ?: 0
                if (doodles > 0) {
                    val (bg, fg) = rarityColors(Rarity.UNCOMMON)
                    Text("Kritzelkarte ×$doodles", style = Ts.smallBold, color = fg,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp))
                }
                allVars.filter { it.name in vars }.forEach { v ->
                    val (bg, fg) = rarityColors(v.rarity)
                    Text("${v.name} ×${vars[v.name]}", style = Ts.smallBold, color = fg,
                        modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 12.dp, vertical = 6.dp))
                }
                val missing = allVars.count { it.name !in vars } + (if (doodles == 0) 1 else 0)
                if (missing > 0) Text("? ? ?  noch $missing", style = Ts.smallBold, color = Color(0xFF9CA3AF),
                    modifier = Modifier.clip(RoundedCornerShape(999.dp)).border(1.dp, Color(0xFFCBD5E1), RoundedCornerShape(999.dp))
                        .padding(horizontal = 12.dp, vertical = 6.dp))
            }

            // Sammelkarten mit Stufen: Treue (Fahrten) und Geduld (Verspaetungsminuten, je Fahrt hoechstens 60)
            SectionHeader("Karten")
            Row(horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
                Extras.PASSES.forEach { pass ->
                    val value = if (pass.id == "treue") book?.rides ?: 0 else book?.delayMinutes ?: 0
                    PassCard(pass, value, Modifier.weight(1f))
                }
            }

            // Spruchsammlung: wie viele Sprueche je Seltenheit schon auf Karten standen
            SectionHeader("Sprüche · ${found.size} von ${Extras.LINES.size}",
                action = if (found.isNotEmpty()) (if (showLines) "Weniger" else "Ansehen") else null,
                onAction = { showLines = !showLines })
            Text(Rarity.entries.joinToString(" · ") { r ->
                "${r.label} ${found.count { it.rarity == r }}/${Extras.LINES.count { it.rarity == r }}"
            }, style = Ts.small, color = Rb.c.muted)
            if (showLines) Column(verticalArrangement = Arrangement.spacedBy(Dim.s8)) {
                found.sortedByDescending { it.rarity }.forEach { l ->
                    val (bg, fg) = rarityColors(l.rarity)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(l.rarity.label, style = Ts.smallBold.copy(fontSize = 11.sp), color = fg,
                            modifier = Modifier.clip(RoundedCornerShape(999.dp)).background(bg).padding(horizontal = 8.dp, vertical = 2.dp))
                        Spacer(Modifier.width(Dim.s8))
                        Text("„${l.text}“", style = Ts.small, color = Rb.c.onSurface)
                    }
                }
            }
            Spacer(Modifier.height(Dim.s16))
        }
    }
}

/**
 * Sammelkarte mit Stufen 25/50/100 (Anspielung auf die BahnCard, eigene Gestaltung):
 * Farbe steigt von Grau ueber Silber zu Gold, darunter der Fortschritt zur naechsten Stufe.
 */
@Composable
private fun PassCard(pass: Pass, value: Int, modifier: Modifier) {
    val level = pass.steps.count { value >= it }
    val next = pass.steps.firstOrNull { value < it }
    val bg = when (level) {
        0 -> listOf(Color(0xFFE5E7EB), Color(0xFFD1D5DB))
        1 -> listOf(Color(0xFFB91C1C), Color(0xFF7F1D1D))
        2 -> listOf(Color(0xFFE5E7EB), Color(0xFF9CA3AF), Color(0xFFF3F4F6))
        else -> listOf(Color(0xFFFDE68A), Color(0xFFB8860B), Color(0xFFFDE68A))
    }
    val fg = when (level) { 0 -> Color(0xFF6B7280); 1 -> Color.White; else -> Color(0xFF1F2937) }
    Column(modifier.clip(RoundedCornerShape(12.dp)).background(androidx.compose.ui.graphics.Brush.linearGradient(bg)).padding(12.dp)) {
        Text(pass.name.uppercase(), style = MONO.copy(fontSize = 10.sp, fontWeight = FontWeight.Bold, color = fg, letterSpacing = 1.sp))
        Text(if (level == 0) "–" else "${pass.steps[level - 1]}", style = Ts.big.copy(fontSize = 28.sp), color = fg)
        Text(if (next != null) "$value / $next ${pass.unit}" else "$value ${pass.unit} · voll", style = MONO.copy(fontSize = 9.sp, color = fg))
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(fg.copy(alpha = 0.25f))) {
            val prev = if (level == 0) 0 else pass.steps[level - 1]
            val frac = if (next == null) 1f else ((value - prev).toFloat() / (next - prev)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth(frac).height(4.dp).background(fg))
        }
    }
}
