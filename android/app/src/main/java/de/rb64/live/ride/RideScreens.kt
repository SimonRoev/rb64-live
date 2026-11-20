package de.rb64.live.ride

import de.rb64.live.whereText
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import de.rb64.live.Cloud
import de.rb64.live.ui.Dim
import de.rb64.live.ui.Pill
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbButton
import de.rb64.live.ui.RbCard
import de.rb64.live.ui.RbIcons
import de.rb64.live.ui.SectionHeader
import de.rb64.live.ui.TopBar
import de.rb64.live.ui.Ts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val HM = SimpleDateFormat("HH:mm", Locale.GERMANY)

/** Minuten zwischen "HH:mm" (Plan) und einer gemessenen Zeit. */
private fun deltaMin(planned: String?, measured: Long?): Int? = Analyzer.deltaMin(planned, measured)

/** Pille nur bei Abweichung (ab +3). */
@Composable
private fun DeltaPill(d: Int?) {
    when {
        d == null || d < 3 -> {}
        d >= 6 -> Pill("+$d", Rb.c.bad, Rb.c.badBg)
        else -> Pill("+$d", Rb.c.warn, Rb.c.warnBg)
    }
}

private val TAG_ICONS: Map<String, ImageVector> = mapOf(
    "signal" to RbIcons.signal, "voll" to RbIcons.crowd, "durchsage" to RbIcons.announce, "stoerung" to RbIcons.warning,
)
private val TAG_TEXT = linkedMapOf("signal" to "Vor Signal", "voll" to "Sehr voll", "durchsage" to "Durchsage", "stoerung" to "Störung")

/** Laufende Fahrt: Kopf mit Geschwindigkeit, Halte mit gemessenen Zeiten, Markierungen, Beenden. */
@Composable
fun RideScreen(s: RideState) {
    val ctx = LocalContext.current
    val deps = Analyzer.departures(s.events)
    val reached = s.events.mapNotNull { it.station }.toSet()
    val next = s.info.stops.firstOrNull { it !in reached && it != s.info.from }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(2_000); now = System.currentTimeMillis() } }
    val kmh = Analyzer.speedKmh(s.points, now)
    val live = androidx.compose.runtime.remember(now / 15_000) { RideService.liveRow(ctx, s.info) }
    val departed = RideService.isDeparted(s)
    fun timeAt(st: String) = live?.stops?.firstOrNull { it.name == st }?.time?.ifBlank { null } ?: s.info.dbExpected[st]

    Column(Modifier.fillMaxSize()) {
        TopBar(if (departed) "Fahrt läuft" else "Warte auf den Zug", below = {
            Row(Modifier.fillMaxWidth().padding(end = Dim.s8, bottom = Dim.s4), verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("${s.info.train} → ${s.info.to}", style = Ts.textStrong, color = Rb.c.onHeader)
                    Text(if (!departed) "Abfahrt ca. ${live?.depExpected ?: s.info.depPlanned}" +
                            (live?.whereText()?.let { " · $it" } ?: "")
                        else next?.let { n -> "nächster Halt $n" + (timeAt(n)?.let { " · ca. $it" } ?: "") } ?: "am Ziel",
                        style = Ts.small, color = Rb.c.onHeader.copy(alpha = 0.85f))
                }
                Text(kmh?.toString() ?: "–", style = Ts.big, color = Rb.c.onHeader)
                Text(" km/h", style = Ts.small, color = Rb.c.onHeader.copy(alpha = 0.85f), modifier = Modifier.padding(bottom = 6.dp))
            }
        })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Dim.s16), verticalArrangement = Arrangement.spacedBy(Dim.s8)) {
            // Halte: gemessen bzw. erwartet; dazwischen ungeplante Halte
            RbCard(padding = 0.dp) {
                s.info.stops.forEach { st ->
                    val dep = deps[st]
                    val d = deltaMin(s.info.planned[st] ?: s.info.dbExpected[st], dep)
                    val done = dep != null || st in reached
                    Row(Modifier.fillMaxWidth().heightIn(min = Dim.touch).padding(horizontal = Dim.s16), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(12.dp).clip(CircleShape).background(if (done) Rb.c.primary else Rb.c.surface)
                            .border(2.dp, if (done) Rb.c.primary else Rb.c.graphic, CircleShape))
                        Spacer(Modifier.width(Dim.s12))
                        Text(st, style = Ts.text, modifier = Modifier.weight(1f))
                        if (dep != null) {
                            Text("ab ${HM.format(Date(dep))}", style = Ts.textStrong)
                            Spacer(Modifier.width(Dim.s8)); DeltaPill(d)
                        } else Text("ca. ${timeAt(st) ?: "–"}", style = Ts.text, color = Rb.c.muted)
                    }
                    s.events.filter { it.station == null && it.seconds.let { sec -> sec == null || sec >= 20 } }
                        .filter { ev -> nearestBefore(s, ev) == st }
                        .forEach { ev ->
                            Row(Modifier.fillMaxWidth().padding(start = Dim.s16, end = Dim.s16, bottom = Dim.s8), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(12.dp).clip(RoundedCornerShape(4.dp)).background(Rb.c.warn))
                                Spacer(Modifier.width(Dim.s12))
                                Text("Halt auf freier Strecke", style = Ts.small, color = Rb.c.warn, modifier = Modifier.weight(1f))
                                Text(ev.seconds?.let { "${(it + 30) / 60} min" } ?: "steht", style = Ts.smallStrong, color = Rb.c.warn)
                            }
                        }
                }
            }

            SectionHeader("Markieren")
            TAG_TEXT.entries.chunked(2).forEach { pair ->
                Row(horizontalArrangement = Arrangement.spacedBy(Dim.s8)) {
                    pair.forEach { (kind, label) ->
                        val count = s.tags.count { it.kind == kind }
                        RbButton(label + if (count > 0) " ×$count" else "", {
                            ctx.startService(Intent(ctx, RideService::class.java).setAction(RideService.ACTION_TAG).putExtra("kind", kind))
                        }, Modifier.weight(1f), icon = TAG_ICONS[kind], primary = false)
                    }
                }
            }
            Spacer(Modifier.height(Dim.s8))
            RbButton("Fahrt beenden", { ctx.startService(Intent(ctx, RideService::class.java).setAction(RideService.ACTION_STOP)) },
                Modifier.fillMaxWidth(), icon = RbIcons.stop)
            Text("Am Ziel endet die Aufzeichnung von selbst. Gespeichert werden nur Punkte entlang der Strecke.",
                style = Ts.small, color = Rb.c.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Letzter Bahnhof (in Fahrtrichtung) vor einem Halt auf freier Strecke. */
private fun nearestBefore(s: RideState, ev: StopEvent): String? {
    val passed = s.events.filter { it.station != null && it.arr <= ev.arr }.lastOrNull()?.station
    return passed ?: s.info.from
}

/** Auswertung nach der Fahrt: Plan vs. Prognose vs. gemessen je Halt, ungeplante Halte, Markierungen. */
@Composable
fun SummaryScreen(s: RideState, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val deps = Analyzer.departures(s.events)
    val arrTo = s.events.lastOrNull { it.station == s.info.to }?.arr
    val dur = ((s.ended ?: s.started) - s.started) / 60_000
    var askDelete by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    // noch nicht bestaetigt hochgeladen?
    val uploadPending = de.rb64.live.Repo.prefs(ctx).getString("ride_upload_pending", null) == s.info.id

    if (askDelete) DeleteRideDialog(
        uploaded = !uploadPending,
        onLocal = { askDelete = false; RideStore.delete(ctx, s.info.id); onBack() },
        onEverywhere = { askDelete = false; Cloud.deleteRide(s.info.id); RideStore.delete(ctx, s.info.id); onBack() },
        onDismiss = { askDelete = false },
    )

    Column(Modifier.fillMaxSize()) {
        TopBar("Fahrt vom ${SimpleDateFormat("dd.MM.", Locale.GERMANY).format(Date(s.started))}", onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(Dim.s16), verticalArrangement = Arrangement.spacedBy(Dim.s8)) {
            RbCard {
                Text("${s.info.train} ${s.info.from} → ${s.info.to}", style = Ts.title)
                s.info.corrected?.let {
                    Text("Zug anhand der gemessenen Abfahrt erkannt (gewählt war $it)", style = Ts.small, color = Rb.c.primary)
                }
                Text("${SimpleDateFormat("dd.MM.", Locale.GERMANY).format(Date(s.started))} · $dur min · ${s.points.size} Messpunkte" +
                    (Analyzer.maxKmh(s.points)?.let { " · max. $it km/h" } ?: ""), style = Ts.small, color = Rb.c.muted)
                // Wo liegt die Fahrt? (fuer die Loesch-Entscheidung wichtig)
                Text(if (uploadPending) "Nur auf dem Handy · wird hochgeladen, sobald Netz da ist"
                    else "Auf dem Handy und anonym auf dem Server (für die Prognose)",
                    style = Ts.small, color = Rb.c.muted, modifier = Modifier.padding(top = Dim.s4))
            }
            SectionHeader("Wo wie schnell")
            RideMapCard(s)
            SectionHeader("Halte")
            RbCard(padding = 0.dp) {
                Row(Modifier.fillMaxWidth().padding(horizontal = Dim.s16, vertical = Dim.s8)) {
                    Text("", Modifier.weight(1f))
                    listOf("Plan", "Prognose", "gemessen").forEach { Text(it, style = Ts.small, color = Rb.c.muted, modifier = Modifier.width(72.dp)) }
                }
                s.info.stops.forEach { st ->
                    val measured = if (st == s.info.to) arrTo else deps[st]
                    val plan = s.info.planned[st]
                    val d = deltaMin(plan ?: s.info.dbExpected[st], measured)
                    Row(Modifier.fillMaxWidth().heightIn(min = Dim.touch).padding(horizontal = Dim.s16), verticalAlignment = Alignment.CenterVertically) {
                        Text(st + if (st == s.info.to) " an" else "", style = Ts.text, modifier = Modifier.weight(1f))
                        Text(plan ?: "", style = Ts.text, modifier = Modifier.width(72.dp))
                        Text(s.info.dbExpected[st] ?: "–", style = Ts.text, color = Rb.c.muted, modifier = Modifier.width(72.dp))
                        Text(measured?.let { HM.format(Date(it)) + (d?.takeIf { x -> x > 0 }?.let { x -> " +$x" } ?: "") } ?: "–",
                            style = Ts.textStrong, modifier = Modifier.width(72.dp),
                            color = when { (d ?: 0) >= 6 -> Rb.c.bad; (d ?: 0) >= 3 -> Rb.c.warn; else -> Rb.c.onSurface })
                    }
                }
            }
            val free = s.events.filter { it.station == null }
            if (free.isNotEmpty() || s.tags.isNotEmpty()) {
                SectionHeader("Unterwegs")
                RbCard {
                    free.forEach { ev ->
                        Text("${(ev.seconds ?: 0) / 60} min ${(ev.seconds ?: 0) % 60} s Halt auf freier Strecke nach ${nearestBefore(s, ev)} " +
                            "(${HM.format(Date(ev.arr))})", style = Ts.text, color = Rb.c.warn)
                    }
                    s.tags.forEach { t ->
                        Row(Modifier.heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                            TAG_ICONS[t.kind]?.let { Icon(it, null, tint = Rb.c.muted, modifier = Modifier.size(Dim.iconSmall)) }
                            Spacer(Modifier.width(Dim.s8))
                            Text("${TAG_TEXT[t.kind] ?: t.kind} · ${HM.format(Date(t.t))}", style = Ts.text, color = Rb.c.muted)
                        }
                    }
                }
            }
            Spacer(Modifier.height(Dim.s8))
            var feedback by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
            RbButton("Feedback zu dieser Fahrt", { feedback = true }, Modifier.fillMaxWidth(), primary = false)
            if (feedback) de.rb64.live.FeedbackDialog(
                context = "Fahrt ${s.info.train} ${s.info.from} → ${s.info.to} am ${SimpleDateFormat("dd.MM.", Locale.GERMANY).format(Date(s.started))} (${s.info.id.take(8)})",
            ) { feedback = false }
            RbButton("Fahrt löschen …", { askDelete = true }, Modifier.fillMaxWidth(),
                icon = RbIcons.delete, primary = false)
        }
    }
}

/* ------------------------------------------------------------------ Fahrten-Liste */

private val DAY = SimpleDateFormat("EE dd.MM.", Locale.GERMANY)

/** Alle gespeicherten Fahrten, neueste zuerst; oben eine kleine Bilanz. Antippen oeffnet die Auswertung. */
@Composable
fun RideListScreen(rides: List<RideMeta>, onOpen: (String) -> Unit, onGoDepartures: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    Column(Modifier.fillMaxSize()) {
        TopBar("Fahrten")
        androidx.compose.foundation.lazy.LazyColumn(Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(Dim.s16),
            verticalArrangement = Arrangement.spacedBy(Dim.s8)) {
            if (rides.isEmpty()) item {
                RbCard {
                    Text("Noch keine Fahrt aufgezeichnet", style = Ts.textStrong)
                    Spacer(Modifier.height(Dim.s4))
                    Text("Tippe bei einem Zug auf „Ich fahre mit“. Die App misst dann Halte, Tempo und Verspätung und hilft damit der Prognose.",
                        style = Ts.small, color = Rb.c.muted)
                    Spacer(Modifier.height(Dim.s12))
                    RbButton("Zu den Abfahrten", onGoDepartures, primary = false)
                }
                return@item
            }
            if (rides.size >= 2) item {
                val d = rides.mapNotNull { it.arrDelta }
                RbCard {
                    Row(horizontalArrangement = Arrangement.spacedBy(Dim.s16)) {
                        Stat("${rides.size}", "Fahrten")
                        Stat("%.0f km".format(rides.sumOf { it.km }), "gefahren")
                        if (d.isNotEmpty()) Stat("+${d.sorted()[d.size / 2]}", "meist am Ziel")
                        if (d.isNotEmpty()) Stat("${d.count { it < 3 } * 100 / d.size} %", "pünktlich")
                    }
                }
            }
            // Prognose-Check: wie lagen Prognose und DB bei deinen angehefteten Zuegen?
            val checks = de.rb64.live.PCheckStore.done(ctx)
            if (checks.isNotEmpty()) {
                item { SectionHeader("Prognose-Check") }
                items(checks.size) { k -> de.rb64.live.PCheckCard(checks[k], compact = true) }
            }
            if (rides.isNotEmpty()) item { SectionHeader("Aufgezeichnet") }
            items(rides.size) { k ->
                val m = rides[k]
                RbCard(onClick = { onOpen(m.id) }, padding = Dim.s12) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).padding(start = Dim.s4)) {
                            Text("${m.from} → ${m.to}", style = Ts.textStrong)
                            Text("${DAY.format(Date(m.started))} · ${HM.format(Date(m.started))} · ${m.train} · " +
                                "${(m.ended - m.started) / 60_000} min" + (if (m.km >= 0.5) " · %.1f km".format(m.km) else ""),
                                style = Ts.small, color = Rb.c.muted)
                        }
                        val d = m.arrDelta
                        when {
                            d == null -> {}
                            d >= 3 -> DeltaPill(d)
                            else -> Pill("pünktlich", Rb.c.ok, Rb.c.okBg)
                        }
                        Icon(RbIcons.arrowRight, null, tint = Rb.c.muted, modifier = Modifier.padding(start = Dim.s8).size(Dim.iconSmall))
                    }
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String) {
    Column {
        Text(value, style = Ts.title)
        Text(label, style = Ts.small, color = Rb.c.muted)
    }
}

/**
 * Loeschen mit klarer Wahl: nur die Kopie auf dem Handy oder ueberall.
 * Nach "nur auf dem Handy" gibt es in der App keinen Weg mehr, die Server-Kopie zu loeschen - das steht dabei.
 */
@Composable
private fun DeleteRideDialog(uploaded: Boolean, onLocal: () -> Unit, onEverywhere: () -> Unit, onDismiss: () -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Rb.c.surface,
        title = { Text("Fahrt löschen", style = Ts.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Dim.s12)) {
                DeleteOption("Überall löschen",
                    if (uploaded) "Vom Handy und vom Server. Die Messpunkte werden dort entfernt und nicht mehr für die Prognose genutzt."
                    else "Vom Handy; hochgeladen wird sie dann nicht mehr.",
                    danger = true, onClick = onEverywhere)
                if (uploaded) DeleteOption("Nur auf dem Handy",
                    "Verschwindet aus deiner Liste. Auf dem Server bleibt sie anonym und hilft weiter bei der Prognose – " +
                        "löschen kannst du sie dort danach nicht mehr.",
                    danger = false, onClick = onLocal)
            }
        },
        confirmButton = {},
        dismissButton = {
            Text("Abbrechen", style = Ts.textStrong, color = Rb.c.primary,
                modifier = Modifier.clip(RoundedCornerShape(Dim.rButton)).clickable(onClick = onDismiss).padding(Dim.s12))
        },
    )
}

@Composable
private fun DeleteOption(title: String, text: String, danger: Boolean, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.rButton))
        .background(if (danger) Rb.c.badBg else Rb.c.background)
        .clickable(onClick = onClick).padding(Dim.s12)) {
        Text(title, style = Ts.textStrong, color = if (danger) Rb.c.bad else Rb.c.onSurface)
        Text(text, style = Ts.small, color = Rb.c.onSurface)
    }
}
