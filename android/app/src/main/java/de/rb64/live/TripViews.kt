package de.rb64.live

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import de.rb64.live.ui.Dim
import de.rb64.live.ui.LocalVis
import de.rb64.live.ui.NeutralPill
import de.rb64.live.ui.Pill
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbButton
import de.rb64.live.ui.RbCard
import de.rb64.live.ui.RbIcons
import de.rb64.live.ui.Ts
import de.rb64.live.ui.relTime
import de.rb64.live.ui.relShort

/* ------------------------------------------------------------------ Status nach Regeln v2
 * Pille nur bei Abweichung (ab +3 oder Ausfall); nur dann ist die Zeit farbig.
 * Veraltete Daten: keine Statusfarben (nichts soll "sicher puenktlich" aussehen). */

@Composable
private fun delayColor(d: Int, stale: Boolean): Color? = when {
    stale -> null
    d >= 6 -> Rb.c.bad
    d >= 3 -> Rb.c.warn
    else -> null
}

@Composable
fun Row.depColor(stale: Boolean): Color = when {
    cancelled -> Rb.c.muted
    bus -> Rb.c.onSurface
    else -> delayColor(depDelay, stale) ?: Rb.c.onSurface
}

@Composable
fun Row.arrColor(stale: Boolean): Color = if (cancelled || bus) Rb.c.muted else delayColor(arrDelay, stale) ?: Rb.c.onSurface

@Composable
fun StatusPill(r: Row, stale: Boolean) {
    when {
        r.cancelled -> if (stale) NeutralPill("fällt aus") else Pill("fällt aus", Rb.c.bad, Rb.c.badBg)
        r.bus -> NeutralPill("Ersatzbus")
        r.depDelay >= 3 && stale -> NeutralPill("+${r.depDelay}")
        r.depDelay >= 6 -> Pill("+${r.depDelay}", Rb.c.bad, Rb.c.badBg)
        r.depDelay >= 3 -> Pill("+${r.depDelay}", Rb.c.warn, Rb.c.warnBg)
    }
}

private fun Row.hasPill() = cancelled || bus || depDelay >= 3

/** Fahrt als ein Satz fuer TalkBack: "7 Uhr 33, heute 7 Uhr 40, 7 Minuten später, wartet in Altenberge". */
fun Row.spoken(): String {
    fun t(hm: String) = hm.split(":").let { "${it[0].trimStart('0').ifEmpty { "0" }} Uhr ${it.getOrElse(1) { "" }}" }
    val parts = mutableListOf(t(depPlanned))
    when {
        cancelled -> parts += "fällt aus"
        depDelay >= 1 -> { parts += "heute ${t(depExpected)}"; parts += "$depDelay Minuten später" }
        else -> parts += "pünktlich"
    }
    if (!cancelled) parts += "Ankunft ${t(arrExpected)}"
    reason?.let { parts += it }
    return parts.joinToString(", ")
}

/** Text, der bei Aenderung sanft von oben einrutscht (z. B. neue Abfahrtszeit); ohne "Sanfte Bewegungen" sofort. */
@Composable
fun RollingText(text: String, style: androidx.compose.ui.text.TextStyle, color: Color, decoration: TextDecoration? = null) {
    if (!LocalVis.current.animations) { Text(text, style = style, color = color, textDecoration = decoration); return }
    androidx.compose.animation.AnimatedContent(targetState = text, label = "zeit", transitionSpec = {
        (androidx.compose.animation.slideInVertically { -it } + androidx.compose.animation.fadeIn()) togetherWith
            (androidx.compose.animation.slideOutVertically { it } + androidx.compose.animation.fadeOut())
    }) { t -> Text(t, style = style, color = color, textDecoration = decoration) }
}

/** Kleines Symbol: Echtzeit (Funkwellen) oder nur Fahrplan (Uhr). */
@Composable
fun SourceIcon(r: Row) {
    Icon(if (r.realtime) RbIcons.live else RbIcons.clock,
        if (r.realtime) "Echtzeit" else "nur Fahrplan", tint = Rb.c.muted, modifier = Modifier.size(Dim.iconSmall))
}

/** Zeiten einer Fahrt: neue Zeit (farbig nur bei Pille), Plan klein durchgestrichen, Ankunft. */
@Composable
private fun TimesLine(r: Row, stale: Boolean, big: Boolean) {
    val strike = if (r.cancelled) TextDecoration.LineThrough else null
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
        RollingText(if (r.cancelled) r.depPlanned else r.depExpected, Ts.title, r.depColor(stale), strike)
        // Planzeit lesbar als Einordnung (nicht durchgestrichen)
        if (!r.cancelled && r.depDelay >= 1) Text("Plan ${r.depPlanned}", style = Ts.small, color = Rb.c.muted,
            modifier = Modifier.padding(bottom = 2.dp))
        Text("→ " + (if (r.cancelled) r.arrPlanned else r.arrExpected), style = if (big) Ts.title else Ts.text,
            color = if (big) r.arrColor(stale) else Rb.c.muted, textDecoration = strike)
        if (big && r.arrPlatform != null && !r.cancelled) Text("an Gl. ${r.arrPlatform}", style = Ts.small, color = Rb.c.muted,
            modifier = Modifier.padding(bottom = 2.dp))
        if (!r.cancelled) Box(Modifier.padding(bottom = 4.dp)) { SourceIcon(r) }
    }
}

/* ------------------------------------------------------------------ Naechster Zug (Hero) */
@Composable
fun HeroCard(
    r: Row, now: Long, stale: Boolean,
    rideHere: Boolean, canRide: Boolean,
    onOpen: () -> Unit, onRide: () -> Unit, onStopRide: () -> Unit,
    pinned: Boolean = false, rideDeparted: Boolean = false,
    vehicle: VehicleView? = null, onTogglePin: (() -> Unit)? = null,
) {
    // Fahrzeug noch unterwegs zur Wende: dann steht der Zug nicht schon am Einstieg
    @Suppress("NAME_SHADOWING") val r = if (vehicle != null && vehicle.state != VehicleView.State.ARRIVED && r.marker == 0.0) r.copy(marker = -1.0) else r
    RbCard(onClick = onOpen, modifier = Modifier.semantics(mergeDescendants = true) {
        contentDescription = (if (pinned) "Dein Zug, angeheftet, " else "Nächster Zug, ") + r.spoken()
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (pinned) Icon(RbIcons.pin, null, tint = Rb.c.primary, modifier = Modifier.size(Dim.iconSmall).padding(end = 0.dp))
            if (pinned) Spacer(Modifier.width(Dim.s4))
            // Phase: vor der Abfahrt / gleich einsteigen / du wartest (Aufzeichnung) / unterwegs
            val vis0 = LocalVis.current
            val base = if (pinned) "Dein Zug" else "Nächster Zug"
            val toDep = r.depEpoch - now
            val label = when {
                r.cancelled -> base
                rideHere && rideDeparted -> "Unterwegs · Ankunft in"
                rideHere -> "Du fährst mit · Zug kommt in"
                toDep < 2 * 60_000 -> base
                vis0.relTime -> "$base · Abfahrt in"
                else -> "$base · Abfahrt"
            }
            Text(label, style = Ts.smallStrong, color = if (pinned || rideHere) Rb.c.primary else Rb.c.muted,
                modifier = Modifier.weight(1f))
            // auf einen Blick: auch "pünktlich" zeigen (Echtzeit vorhanden), nicht nur Verspaetungen
            if (!r.hasPill() && r.realtime && !stale && r.depDelay <= 0) Pill("pünktlich", Rb.c.ok, Rb.c.okBg)
            else StatusPill(r, stale)
        }
        Spacer(Modifier.height(Dim.s4))
        val vis = LocalVis.current
        val boarding = !r.cancelled && !rideHere && r.depEpoch - now < 2 * 60_000
        if (boarding) {
            // gleich geht's los: Aufforderung + Gleis gross
            RollingText("Jetzt einsteigen", Ts.big, Rb.c.onSurface)
            r.platform?.let { Text("Gleis $it", style = Ts.title, color = Rb.c.onSurface) }
            Spacer(Modifier.height(Dim.s4))
            TimesLine(r, stale, big = true)
        } else if (vis.relTime || r.cancelled || rideHere) {
            val big = when {
                r.cancelled -> "fällt aus"
                rideHere && rideDeparted -> relShort(r.arrEpoch, now)
                else -> relShort(r.depEpoch, now)
            }
            RollingText(big, if (vis.bigTime) Ts.heroXL else Ts.hero, if (r.cancelled) Rb.c.bad else Rb.c.onSurface)
            Spacer(Modifier.height(Dim.s4))
            TimesLine(r, stale, big = true)
        } else {
            // ohne "in X min": Abfahrt gross (Plan klein daneben), darunter nur die Ankunft
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
                RollingText(r.depExpected, if (vis.bigTime) Ts.heroXL else Ts.hero, r.depColor(stale))
                if (r.depDelay >= 1) Text("Plan ${r.depPlanned}", style = Ts.text, color = Rb.c.muted,
                    modifier = Modifier.padding(bottom = 6.dp))
            }
            Text("→ ${r.arrExpected}" + (r.arrPlatform?.let { " · an Gl. $it" } ?: ""), style = Ts.title, color = r.arrColor(stale))
        }
        if (vis.stopStrip && !r.cancelled && !r.bus && r.stops.size >= 2) {
            Spacer(Modifier.height(Dim.s12))
            StopStrip(r, stale)
        }
        if (vehicle == null) r.before?.takeIf { !r.cancelled }?.let {
            Spacer(Modifier.height(Dim.s8))
            Text(r.whereText() ?: "", style = Ts.small, color = Rb.c.muted)
        }
        val why = if (r.cancelled) null else r.reason?.replaceFirstChar { it.uppercase() }
        if (why != null) {
            Spacer(Modifier.height(Dim.s8))
            Text(if (r.ownForecast && vis.source) "Vermutlich: $why" else why, style = Ts.text, color = Rb.c.onSurface)
        }
        // "Wo ist mein Zug?": Fahrzeug, das gleich als dieser Zug faehrt (nur vor dem Start)
        if (vehicle != null && !r.cancelled && !rideDeparted) {
            Spacer(Modifier.height(Dim.s8))
            VehicleLine(vehicle)
        }
        val src = if (vis.source) r.sourceText() else null
        val hist = if (vis.history) r.histShort() else null
        if (src != null || hist != null) {
            Spacer(Modifier.height(Dim.s8))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (src != null) {
                    Icon(RbIcons.trend, null, tint = Rb.c.primary, modifier = Modifier.size(Dim.iconSmall))
                    Spacer(Modifier.width(Dim.s4))
                    Text(src, style = Ts.smallStrong, color = Rb.c.primary, modifier = Modifier.weight(1f))
                } else Spacer(Modifier.weight(1f))
                if (hist != null) Text(hist, style = Ts.small, color = Rb.c.muted)
            }
        }
        // Hauptaktion: erst anheften (dann Sperrbildschirm/Live Update), angeheftet dann "Ich fahre mit"
        val canPin = onTogglePin != null && !pinned && !r.cancelled && r.arrEpoch > now
        if (rideHere || canRide || canPin) {
            Spacer(Modifier.height(Dim.s12))
            when {
                rideHere -> RbButton(if (rideDeparted) "Aufzeichnung läuft · beenden" else "Aufzeichnung abbrechen",
                    onStopRide, Modifier.fillMaxWidth(), icon = RbIcons.stop, primary = false)
                canPin -> RbButton("Als meinen Zug anheften", onTogglePin!!, Modifier.fillMaxWidth(), icon = RbIcons.pin)
                else -> RbButton("Ich fahre mit", onRide, Modifier.fillMaxWidth(), icon = RbIcons.play)
            }
        }
    }
}

/* ------------------------------------------------------------------ Listenzeile (56) */
@Composable
fun TripRow(r: Row, stale: Boolean, pinned: Boolean = false, onClick: () -> Unit) {
    val past = r.past || r.depEpoch < System.currentTimeMillis() - 60_000
    Row(
        Modifier.fillMaxWidth().heightIn(min = Dim.row).clickable(onClick = onClick)
            .clearAndSetSemantics { contentDescription = (if (past) "abgefahren, " else "") + r.spoken() }
            .padding(horizontal = Dim.s16, vertical = Dim.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Fruehere Zuege zeigen ihre tatsaechliche Verspaetung (zum Einschaetzen), nur mit "abgefahren" markiert
        Column(Modifier.weight(1f)) {
            TimesLine(r, stale, big = false)
            val why = r.reason?.takeIf { !r.cancelled && r.hasPill() }
            val caption = listOfNotNull(if (pinned) "dein Zug" else null, if (past) "abgefahren" else null,
                why?.replaceFirstChar { it.uppercase() }).joinToString(" · ")
            if (caption.isNotEmpty()) Text(caption, style = if (pinned) Ts.smallStrong else Ts.small,
                color = if (pinned) Rb.c.primary else Rb.c.muted, maxLines = 1)
        }
        Spacer(Modifier.width(Dim.s8))
        when {
            r.hasPill() -> StatusPill(r, stale)
            past && r.depDelay >= 1 -> Text("+${r.depDelay}", style = Ts.smallStrong, color = Rb.c.muted)
            past -> Text("pünktlich", style = Ts.small, color = Rb.c.muted)
            else -> r.histShort()?.takeIf { LocalVis.current.history }?.let { Text(it, style = Ts.small, color = Rb.c.muted) }
        }
    }
}

/* ------------------------------------------------------------------ Halte-Streifen */
/** Index des Halts, an dem/vor dem der Zug gerade ist (aus marker 0..1), sonst -1. */
fun herePos(r: Row): Double = if (r.marker in 0.0..1.0 && r.stops.size > 1) r.marker * (r.stops.size - 1) else -1.0

/** Waagerechte Linie mit allen Halten; Zugposition, Kreuzungshalt als Quadrat.
 *  Beschriftet: Start, Ziel, naechster Halt und Kreuzungshalt. */
@Composable
fun StopStrip(r: Row, stale: Boolean) {
    val n = r.stops.size
    val pos = herePos(r)
    val train = if (stale) Rb.c.graphic else delayColor(r.depDelay, false) ?: Rb.c.primary
    val track = Rb.c.line
    val graphic = Rb.c.graphic
    val surface = Rb.c.surface
    val nextIdx = if (pos >= 0) kotlin.math.ceil(pos).toInt().coerceIn(0, n - 1) else 0
    BoxWithConstraints(Modifier.fillMaxWidth().height(40.dp)) {
        val w = maxWidth
        fun x(i: Double) = (w - 16.dp) * (i / (n - 1)).toFloat() + 8.dp
        Box(Modifier.offset(x = 8.dp, y = 6.dp).width(w - 16.dp).height(4.dp).clip(RoundedCornerShape(999.dp)).background(track))
        if (pos > 0) Box(Modifier.offset(x = 8.dp, y = 6.dp).width(x(pos) - 8.dp).height(4.dp).clip(RoundedCornerShape(999.dp)).background(train))
        r.stops.forEachIndexed { i, s ->
            val done = pos >= 0 && i < pos - 0.01
            if (s.hang) Box(Modifier.offset(x = x(i.toDouble()) - 6.dp, y = 2.dp).size(12.dp).clip(RoundedCornerShape(4.dp))
                .background(surface).border(2.dp, Rb.c.warn, RoundedCornerShape(4.dp)))
            else Box(Modifier.offset(x = x(i.toDouble()) - 6.dp, y = 2.dp).size(12.dp).clip(CircleShape)
                .background(if (done) train else surface).border(2.dp, if (done) train else graphic, CircleShape))
            val label = n <= 3 || i == 0 || i == n - 1 || i == nextIdx || s.hang
            if (label) {
                val align = when (i) { 0 -> 0.dp; n - 1 -> w - 96.dp; else -> x(i.toDouble()) - 48.dp }
                Text(s.name, style = Ts.small, maxLines = 1,
                    color = if (s.hang) Rb.c.warn else if (i == 0 || i == n - 1) Rb.c.onSurface else Rb.c.muted,
                    textAlign = when (i) { 0 -> TextAlign.Start; n - 1 -> TextAlign.End; else -> TextAlign.Center },
                    modifier = Modifier.offset(x = align.coerceIn(0.dp, w - 96.dp), y = 20.dp).width(96.dp))
            }
        }
        if (pos >= 0) Box(Modifier.offset(x = x(pos) - 8.dp, y = 0.dp).size(16.dp).clip(CircleShape).background(train)
            .border(3.dp, surface, CircleShape))
    }
}

/* ------------------------------------------------------------------ Detailblatt */
@Composable
fun TripSheet(
    rel: Rel, r: Row, now: Long, stale: Boolean, weekday: Boolean,
    rideHere: Boolean, canRide: Boolean, onRide: () -> Unit, onStopRide: () -> Unit,
    pinned: Boolean = false, onTogglePin: (() -> Unit)? = null,
    stand: String? = null, refreshing: Boolean = false, onRefresh: (() -> Unit)? = null,
    notifyOn: Boolean = false, onToggleNotify: (() -> Unit)? = null,
    vehicle: VehicleView? = null, histSeries: HistSeries? = null, pcheck: PCheck? = null,
    onShowMap: (() -> Unit)? = null,
) {
    // Fahrzeug noch unterwegs zur Wende: dann steht der Zug nicht schon am Einstieg
    @Suppress("NAME_SHADOWING") val r = if (vehicle != null && vehicle.state != VehicleView.State.ARRIVED && r.marker == 0.0) r.copy(marker = -1.0) else r
    de.rb64.live.ui.SheetBody(header = {
        Column(verticalArrangement = Arrangement.spacedBy(Dim.s4)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${if (r.bus) "Ersatzbus" else "RB64"} ${r.train} · ${rel.from} → ${rel.to}", style = Ts.small, color = Rb.c.muted)
                    if (stand != null) Text(stand, style = Ts.small, color = if (stale) Rb.c.warn else Rb.c.muted)
                }
                if (onRefresh != null) de.rb64.live.ui.IconBtn(RbIcons.refresh, "Aktualisieren", { if (!refreshing) onRefresh() },
                    tint = Rb.c.primary, busy = refreshing)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
                Text(if (r.cancelled) r.depPlanned else r.depExpected, style = Ts.big, color = r.depColor(stale),
                    textDecoration = if (r.cancelled) TextDecoration.LineThrough else null)
                if (!r.cancelled && r.depDelay >= 1) Text("Plan ${r.depPlanned}", style = Ts.text, color = Rb.c.muted)
                Spacer(Modifier.weight(1f))
                StatusPill(r, stale)
            }
            if (!r.cancelled) Text(
                listOfNotNull(relTime(r.depEpoch, now).takeIf { r.depEpoch > now - 60_000 },
                    "an ${rel.to} ${r.arrExpected}" + (r.arrPlatform?.let { ", Gl. $it" } ?: ""),
                    r.platform?.let { "ab Gl. $it" }).joinToString(" · "),
                style = Ts.text, color = Rb.c.muted)
            if (onShowMap != null && !r.cancelled) de.rb64.live.ui.TextAction("Auf der Strecke ansehen", RbIcons.route, onShowMap,
                Modifier.padding(top = 2.dp).offset(x = (-8).dp))
        }
    }) {
        // nach der Abfahrt: wie lagen Prognose und DB?
        if (pcheck != null) PCheckCard(pcheck)
        // Woher kommt die Zahl?
        val vis = LocalVis.current
        if (r.ownForecast && vis.source) Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.rButton)).background(Rb.c.primaryTint).padding(Dim.s12),
            horizontalArrangement = Arrangement.spacedBy(Dim.s8),
        ) {
            Icon(RbIcons.trend, null, tint = Rb.c.primary, modifier = Modifier.size(Dim.icon))
            Column(verticalArrangement = Arrangement.spacedBy(Dim.s4)) {
                Text(r.sourceText() ?: "", style = Ts.textStrong, color = Rb.c.primary)
                Text((r.reason?.let { "Vermutlich: ${it.replaceFirstChar { c -> c.uppercase() }}. " } ?: "") +
                    "Berechnet aus Umlauf und Kreuzungen – die DB meldet das oft erst später.", style = Ts.small, color = Rb.c.onSurface)
            }
        } else {
            r.reason?.takeIf { !r.cancelled }?.let { Text(it.replaceFirstChar { c -> c.uppercase() }, style = Ts.text) }
            if (!r.cancelled && !r.bus && r.dbDelay != null) Text("Unsere Prognose und die DB sagen dasselbe", style = Ts.small, color = Rb.c.muted)
        }
        if (!r.cancelled && vis.history) {
            if (histSeries != null) HistChart(histSeries, today = r.depDelay.coerceAtLeast(0))
            else r.histLong(weekday)?.let { Text(it, style = Ts.small, color = Rb.c.muted) }
        }
        if (stale) de.rb64.live.ui.Banner("Daten veraltet – Zeiten können nicht mehr stimmen")
        if (vehicle != null && !r.cancelled) VehicleBlock(vehicle, r.train, rel.from, r.depExpected, myDelay = r.depDelay)

        // Zuglauf (Zeilen 48)
        if (r.stops.isNotEmpty() && !r.cancelled) Column {
            if (r.before != null) Text(r.whereText() ?: "", style = Ts.small, color = Rb.c.muted,
                modifier = Modifier.padding(bottom = Dim.s8))
            val pos = herePos(r)
            val train = if (stale) Rb.c.graphic else delayColor(r.depDelay, false) ?: Rb.c.primary
            // Lange Zuglaeufe kompakt: Einstieg, Ziel, Zugposition und Kreuzungshalte; der Rest auf Antippen
            var allStops by androidx.compose.runtime.saveable.rememberSaveable(r.train) { mutableStateOf(r.stops.size <= 5) }
            val keep = if (allStops) r.stops.indices.toSet() else buildSet {
                add(0); add(r.stops.lastIndex)
                if (pos >= 0) add(kotlin.math.round(pos).toInt().coerceIn(0, r.stops.lastIndex))
                r.stops.forEachIndexed { i, s -> if (s.hang) add(i) }
            }
            r.stops.forEachIndexed { i, s ->
                if (i !in keep) {
                    if (i - 1 in keep) {   // erste ausgeblendete Zeile einer Luecke: Platzhalter
                        val n = (i until r.stops.size).takeWhile { it !in keep }.size
                        val done = pos >= 0 && i < pos - 0.01
                        Row(Modifier.fillMaxWidth().height(Dim.touch).clip(RoundedCornerShape(Dim.rButton))
                            .clickable { allStops = true }.padding(horizontal = Dim.s8),
                            verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.width(Dim.icon).height(Dim.touch), contentAlignment = Alignment.Center) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                    repeat(4) { Box(Modifier.size(4.dp).clip(CircleShape).background(if (done) train else Rb.c.graphic)) }
                                }
                            }
                            Spacer(Modifier.width(Dim.s12))
                            Text(if (n == 1) "1 Halt dazwischen" else "$n Halte dazwischen", style = Ts.small, color = Rb.c.primary,
                                modifier = Modifier.weight(1f))
                            Icon(RbIcons.chevronDown, "alle Halte zeigen", tint = Rb.c.primary, modifier = Modifier.size(Dim.icon))
                        }
                    }
                    return@forEachIndexed
                }
                val done = pos >= 0 && i < pos - 0.01
                val here = pos >= 0 && kotlin.math.abs(pos - i) < 0.5
                val end = i == 0 || i == r.stops.size - 1
                Row(
                    Modifier.fillMaxWidth().height(Dim.touch)
                        .then(if (i == 0) Modifier.clip(RoundedCornerShape(Dim.rButton)).background(Rb.c.background) else Modifier)
                        .padding(horizontal = Dim.s8),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.width(Dim.icon).height(Dim.touch), contentAlignment = Alignment.Center) {
                        if (i > 0) Box(Modifier.align(Alignment.TopCenter).width(4.dp).height(24.dp).background(if (done || here) train else Rb.c.line))
                        if (i < r.stops.size - 1) Box(Modifier.align(Alignment.BottomCenter).width(4.dp).height(24.dp).background(if (done) train else Rb.c.line))
                        when {
                            here -> Box(Modifier.size(16.dp).clip(CircleShape).background(train))
                            s.hang -> Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(Rb.c.surface).border(2.dp, Rb.c.warn, RoundedCornerShape(4.dp)))
                            else -> Box(Modifier.size(12.dp).clip(CircleShape).background(if (done) train else Rb.c.surface)
                                .border(2.dp, if (end) Rb.c.onSurface else Rb.c.graphic, CircleShape))
                        }
                    }
                    Spacer(Modifier.width(Dim.s12))
                    Column(Modifier.weight(1f)) {
                        Text(s.name + when (i) { 0 -> " · Einstieg"; r.stops.size - 1 -> " · Ziel"; else -> "" },
                            style = if (end || here) Ts.textStrong else Ts.text, color = if (done) Rb.c.muted else Rb.c.onSurface)
                        if (s.hang && !here) Text("wartet auf Gegenzug", style = Ts.small, color = Rb.c.warn)
                    }
                    if (here) Text("Zug hier", style = Ts.smallStrong, color = train)
                    else Text(s.time, style = if (end) Ts.textStrong else Ts.text, color = if (done) Rb.c.muted else Rb.c.onSurface)
                }
            }
            if (allStops && r.stops.size > 5) Text("Weniger Halte", style = Ts.small, color = Rb.c.primary,
                modifier = Modifier.clip(RoundedCornerShape(Dim.rButton)).clickable { allStops = false }
                    .heightIn(min = Dim.touch).padding(horizontal = Dim.s8, vertical = 14.dp))
        }

        // Hauptknopf: Anheften (dein Zug auf Sperrbildschirm/Live Update); angeheftet: "Ich fahre mit".
        // Alles andere als kleinere Textknoepfe darunter.
        val canPin = onTogglePin != null && !r.cancelled && r.arrEpoch > now
        when {
            rideHere -> RbButton("Aufzeichnung läuft · beenden", onStopRide, Modifier.fillMaxWidth(), icon = RbIcons.stop, primary = false)
            canPin && !pinned -> RbButton("Als meinen Zug anheften", onTogglePin!!, Modifier.fillMaxWidth(), icon = RbIcons.pin)
            canRide -> RbButton("Ich fahre mit", onRide, Modifier.fillMaxWidth(), icon = RbIcons.play)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Dim.s8, Alignment.CenterHorizontally)) {
            if (!rideHere && canRide && !pinned && canPin) de.rb64.live.ui.TextAction("Ich fahre mit", RbIcons.play, onRide)
            if (canPin && pinned) de.rb64.live.ui.TextAction("Nicht mehr anheften", RbIcons.pin, onTogglePin!!)
            if (onToggleNotify != null && !r.bus)
                de.rb64.live.ui.TextAction(if (notifyOn) "Nicht mehr melden" else "Bei Verspätung melden", RbIcons.announce, onToggleNotify)
        }
    }
}
