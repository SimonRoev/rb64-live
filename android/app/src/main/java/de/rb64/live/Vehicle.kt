package de.rb64.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import de.rb64.live.ui.Dim
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbIcons
import de.rb64.live.ui.Ts
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Vom Server: Vorfahrt des Fahrzeugs (Zugnummer), geplante Wendezeit, gleiches Gleis = sichere Zuordnung. */
data class VehicleLink(val train: String, val turnMin: Int, val sure: Boolean)

/** Aufbereitet fuer die Anzeige: wo ist das Fahrzeug, das gleich als mein Zug faehrt? */
data class VehicleView(
    val inTrain: String, val origin: String, val turnStation: String, val turnMin: Int, val sure: Boolean,
    val state: State, val where: String?, val delay: Int,
    val inDep: String?, val inArrPlanned: String?, val inArrExpected: String?, val outDep: String?,
    /** Verspaetung, die die Wende nicht auffaengt (wie in der Prognose: 4 min Mindest-Wende). */
    val carry: Int,
) {
    enum class State { NOT_STARTED, RUNNING, ARRIVED, CANCELLED }
}

private val HM_V = SimpleDateFormat("HH:mm", Locale.GERMANY)
private const val MIN_TURN = 4

/**
 * Fahrzeug fuer den Zug [train] - nur solange dieser Zug noch nicht losgefahren ist und die Vorfahrt
 * in den Daten steckt. Bei langer Wende (>= 40 min, z. B. Burgsteinfurt) nur, wenn sie nicht reicht.
 */
fun Snapshot.vehicleFor(train: String, now: Long = System.currentTimeMillis()): VehicleView? {
    val out = trips.firstOrNull { it.train == train } ?: return null
    val link = out.link ?: return null
    if (out.pos >= 0) return null                                   // schon unterwegs: Halte-Streifen zeigt den Zug
    val inT = trips.firstOrNull { it.train == link.train } ?: return null
    if (inT.stops.size < 2 || out.stops.isEmpty()) return null
    fun epoch(min: Int?) = min?.let { (base + it) * 60_000L }
    val first = inT.stops.first(); val last = inT.stops.last(); val outFirst = out.stops.first()
    val outStart = epoch(outFirst.pdp) ?: return null
    if (outStart + (outFirst.ddp ?: 0) * 60_000L < now - 2 * 60_000L) return null
    val delay = (last.dar ?: inT.stops.lastOrNull { it.ddp != null }?.ddp ?: 0).coerceAtLeast(0)
    val arrPlanned = epoch(last.par)
    val arrived = inT.pos < 0 && arrPlanned != null && arrPlanned + delay * 60_000L <= now
    val state = when {
        last.cancelled || inT.stops.all { it.cancelled } -> VehicleView.State.CANCELLED
        arrived -> VehicleView.State.ARRIVED
        inT.pos >= 0 -> VehicleView.State.RUNNING
        else -> VehicleView.State.NOT_STARTED
    }
    val carry = (delay - (link.turnMin - MIN_TURN)).coerceAtLeast(0)
    if (link.turnMin >= 40 && carry == 0 && state != VehicleView.State.CANCELLED) return null
    val where = if (state == VehicleView.State.RUNNING) {
        val p = inT.pos
        if (abs(p - Math.round(p)) < 0.08) "in ${name(Math.round(p).toInt())}"
        else {
            val a = if (inT.toEnschede) floor(p).toInt() else ceil(p).toInt()
            val b = if (inT.toEnschede) ceil(p).toInt() else floor(p).toInt()
            "zwischen ${name(a)} und ${name(b)}"
        }
    } else null
    fun hm(e: Long?) = e?.let { HM_V.format(Date(it)) }
    return VehicleView(
        inTrain = link.train, origin = name(first.seq), turnStation = name(outFirst.seq),
        turnMin = link.turnMin, sure = link.sure, state = state, where = where, delay = delay,
        inDep = hm(epoch(first.pdp)), inArrPlanned = hm(arrPlanned),
        inArrExpected = hm(arrPlanned?.let { it + delay * 60_000L }), outDep = hm(outStart), carry = carry,
    )
}

/** Kurztext fuer die Hero-Karte: Titel und eine Zeile darunter. */
fun VehicleView.heroLines(): Pair<String, String> {
    val maybe = if (sure) "" else "vermutlich "
    val buffer = "$turnMin min Puffer in $turnStation " + if (carry > 0) "reichen nicht: ca. +$carry" else "reichen"
    return when (state) {
        VehicleView.State.CANCELLED -> "Vorherige Fahrt fällt aus" to "Ob und wann dein Zug fährt, ist unsicher"
        VehicleView.State.ARRIVED -> "Zug steht schon in $turnStation bereit" to
            (if (carry > 0) "kam mit +$delay aus $origin · ca. +$carry" else "kam aus $origin")
        VehicleView.State.NOT_STARTED -> "Zug kommt ${maybe}aus $origin" to
            "fährt dort ${inDep?.let { "um $it " } ?: ""}los · $turnMin min Puffer in $turnStation"
        VehicleView.State.RUNNING -> "Zug kommt ${maybe}aus $origin · " + (if (delay >= 1) "+$delay" else "pünktlich") to
            listOfNotNull(where?.replaceFirstChar { it.uppercase() }, buffer).joinToString(" · ")
    }
}

/** Eine Zeile in der Hero-Karte (getoent, antippbar ueber die Karte). */
@Composable
fun VehicleLine(v: VehicleView) {
    val (title, sub) = v.heroLines()
    val warn = v.state == VehicleView.State.CANCELLED
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(RoundedCornerShape(Dim.rButton))
        .background(if (warn) Rb.c.warnBg else Rb.c.primaryTint).padding(horizontal = Dim.s8, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(if (warn) RbIcons.warning else RbIcons.route, null, tint = if (warn) Rb.c.warn else Rb.c.primary,
            modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = Ts.smallStrong, color = if (warn) Rb.c.warn else Rb.c.primary)
            Text(sub, style = Ts.small, color = Rb.c.onSurface)
        }
    }
}

/** Block im Detailblatt: Vorfahrt -> aktueller Ort -> Wende -> deine Fahrt, mit Einordnung. */
@Composable
fun VehicleBlock(v: VehicleView, myTrain: String, myFrom: String, myDep: String) {
    var open by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    val verdict = when {
        v.state == VehicleView.State.CANCELLED -> "Die vorherige Fahrt fällt aus – unklar, wann dein Zug kommt. Im Zweifel früher am Bahnsteig sein."
        v.carry > 0 -> "Die ${v.turnMin} min Puffer in ${v.turnStation} reichen nicht: dein Zug fährt dort ca. +${v.carry} ab."
        v.delay >= 1 -> "Die ${v.turnMin} min Puffer in ${v.turnStation} fangen die +${v.delay} auf – daher keine Verspätung für deinen Zug."
        else -> "Der Zug ist pünktlich unterwegs – daher keine Verspätung für deinen Zug."
    }
    de.rb64.live.ui.Collapsible("Woher kommt dein Zug?", open, { open = !open },
        Modifier.fillMaxWidth().border(1.dp, Rb.c.line, RoundedCornerShape(Dim.rCard)).padding(horizontal = 14.dp, vertical = 4.dp),
        badge = {
            Text(if (v.sure) "Schätzung" else "unsicher", style = Ts.small, color = Rb.c.muted,
                modifier = Modifier.border(1.dp, Rb.c.graphic, RoundedCornerShape(999.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        }) {
        if (!open) {
            Text("${v.heroLines().first} · $verdict", style = Ts.small, color = Rb.c.onSurface,
                modifier = Modifier.padding(bottom = 10.dp))
            return@Collapsible
        }
        Column(Modifier.padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val delayColor = when { v.delay >= 6 -> Rb.c.bad; v.delay >= 3 -> Rb.c.warn; else -> Rb.c.onSurface }
        ChainStep(dot = Rb.c.graphic, title = "ab ${v.origin}${v.inDep?.let { " $it" } ?: ""}", sub = "vorherige Fahrt (RB64 ${v.inTrain})")
        when (v.state) {
            VehicleView.State.RUNNING -> ChainStep(dot = delayColor, strong = true,
                title = "${v.where ?: "unterwegs"} · ${if (v.delay >= 1) "+${v.delay}" else "pünktlich"}", sub = null)
            VehicleView.State.NOT_STARTED -> ChainStep(dot = Rb.c.graphic, title = "noch nicht losgefahren", sub = null)
            VehicleView.State.ARRIVED -> ChainStep(dot = Rb.c.primary, strong = true, title = "ist in ${v.turnStation} angekommen", sub = "steht bereit")
            VehicleView.State.CANCELLED -> ChainStep(dot = Rb.c.bad, strong = true, title = "vorherige Fahrt fällt aus", sub = "dein Zug fährt ggf. mit einem anderen Fahrzeug")
        }
        ChainStep(dot = Rb.c.primary, square = true, title = "${v.turnMin} min Puffer in ${v.turnStation} (Wendezeit)",
            sub = listOfNotNull(v.inArrExpected?.let { "an $it" + if (v.delay >= 1) " (+${v.delay})" else "" }, v.outDep?.let { "ab $it" })
                .joinToString(" → ").ifBlank { null })
        ChainStep(dot = Rb.c.primary, filled = true, strong = true, title = "dein Zug $myTrain · ab $myFrom $myDep", sub = null, last = true)

        Text(verdict, style = Ts.small, color = Rb.c.onSurface,
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Rb.c.background).padding(8.dp))
        Text("Umlauf aus Fahrplan- und Echtzeitdaten geschätzt, kein offizieller Fahrzeugplan.", style = Ts.small, color = Rb.c.muted)
        }
    }
}

@Composable
private fun ChainStep(dot: androidx.compose.ui.graphics.Color, title: String, sub: String?,
                      strong: Boolean = false, square: Boolean = false, filled: Boolean = false, last: Boolean = false) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(20.dp).padding(top = 4.dp), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.size(12.dp).clip(if (square) RoundedCornerShape(3.dp) else CircleShape)
                .background(if (filled || strong) dot else Rb.c.surface)
                .border(2.dp, dot, if (square) RoundedCornerShape(3.dp) else CircleShape))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = if (strong) Ts.smallStrong else Ts.small, color = if (last) Rb.c.primary else Rb.c.onSurface)
            if (sub != null) Text(sub, style = Ts.small, color = Rb.c.muted)
        }
    }
    if (!last) Spacer(Modifier.heightIn(min = 0.dp))
}
