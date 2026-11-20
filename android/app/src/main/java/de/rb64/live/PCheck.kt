package de.rb64.live

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import de.rb64.live.ui.Dim
import de.rb64.live.ui.Rb
import de.rb64.live.ui.RbIcons
import de.rb64.live.ui.Ts
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** Ein Stand vor der Abfahrt: eigene Prognose und DB-Angabe (min) zum Datenzeitpunkt [ts]. */
data class PSample(val ts: Long, val own: Int, val db: Int)

/**
 * Prognose-Check fuer eine Abfahrt (angehefteter Zug oder Mitfahrt): Verlauf beider Quellen bis zur Abfahrt
 * und die tatsaechliche Abfahrtsverspaetung. Nur lokal auf dem Handy gespeichert.
 */
data class PCheck(
    val id: String, val train: String, val relId: String, val from: String, val to: String,
    val depPlanned: String, val depEpochPlanned: Long,
    val samples: List<PSample>, val actual: Int? = null, val cancelled: Boolean = false, val done: Boolean = false,
    val dismissed: Boolean = false,
    /** true = "tatsaechlich" ist die per GPS gemessene Abfahrt (du bist mitgefahren), nicht die DB-Angabe. */
    val gps: Boolean = false,
) {
    val depActual: Long? get() = actual?.let { depEpochPlanned + it * 60_000L }

    /** Bewertungsfenster: die letzten 60 min vor der Abfahrt (wie in der Abstimmung vereinbart). */
    private fun window(): List<PSample> {
        val dep = depActual ?: return emptyList()
        return samples.filter { it.ts < dep }
    }

    /** Ab wann zeigte die Quelle die spaetere Verspaetung (+-2 min) und blieb dabei? null = nie. */
    fun rightSince(sel: (PSample) -> Int): Long? {
        val a = actual ?: return null
        val w = window().ifEmpty { return null }
        var since: Long? = null
        for (s in w) {
            if (abs(sel(s) - a) <= TOL) { if (since == null) since = s.ts } else since = null
        }
        val start = (depActual ?: 0) - 60 * 60_000L
        return since?.coerceAtLeast(start)
    }

    /** Letzter Wert vor der Abfahrt und seit wann er so stand. */
    fun lastValue(sel: (PSample) -> Int): Pair<Int, Long>? {
        val w = window().ifEmpty { return null }
        val v = sel(w.last())
        val since = w.reversed().takeWhile { sel(it) == v }.last().ts
        return v to since
    }

    companion object { const val TOL = 2 }
}

object PCheckStore {
    private const val KEY = "pchecks"
    private const val MAX = 30
    private const val BEFORE_MS = 90 * 60_000L      // Aufzeichnung ab 90 min vor der Abfahrt
    /** Compose-Zaehler fuer Neuzeichnen. */
    var version by mutableIntStateOf(0)
        private set

    fun all(ctx: Context): List<PCheck> = runCatching {
        val a = JSONArray(Repo.prefs(ctx).getString(KEY, "[]"))
        (0 until a.length()).map { fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun saveAll(ctx: Context, list: List<PCheck>) {
        val a = JSONArray(); list.takeLast(MAX).forEach { a.put(toJson(it)) }
        Repo.prefs(ctx).edit().putString(KEY, a.toString()).apply()
        version++
    }

    private fun idOf(train: String, depEpochPlanned: Long, relId: String) =
        "$train-${SimpleDateFormat("yyyyMMdd", Locale.GERMANY).format(Date(depEpochPlanned))}-$relId"

    /** Bei jedem neuen Datenstand: Verlauf fuer verfolgte Zuege ergaenzen, abgefahrene abschliessen. */
    fun record(ctx: Context, snap: Snapshot, now: Long = System.currentTimeMillis()) {
        val tracked = buildList {
            val pin = Repo.pinnedTrain(ctx); val pinRel = Repo.pinnedRelId(ctx)
            if (pin != null && pinRel != null) add(pin to pinRel)
            de.rb64.live.ride.RideStore.state.value?.info?.let { add(it.train to it.relId) }
        }
        val list = all(ctx).toMutableList()
        var changed = false
        fun rowFor(train: String, relId: String): Pair<Rel, Row>? {
            val rel = Repo.relById(snap, relId) ?: return null
            return rel.rows.firstOrNull { it.train == train }?.let { rel to it }
        }
        // neue / laufende Aufzeichnungen
        for ((train, relId) in tracked.distinct()) {
            val (rel, row) = rowFor(train, relId) ?: continue
            val depPlannedEpoch = row.depEpoch - row.depDelay * 60_000L
            val id = idOf(train, depPlannedEpoch, relId)
            val i = list.indexOfFirst { it.id == id }
            if (i < 0) {
                if (row.cancelled || row.bus || now < depPlannedEpoch - BEFORE_MS || now > row.depEpoch) continue
                list += PCheck(id, train, relId, rel.from, rel.to, row.depPlanned, depPlannedEpoch, emptyList())
                changed = true
            }
        }
        // alle offenen: Stand anhaengen bzw. abschliessen
        for ((i, c) in list.withIndex()) {
            if (c.done) continue
            val found = rowFor(c.train, c.relId)
            if (found == null) {
                if (now > c.depEpochPlanned + 3 * 3600_000L) { list[i] = c.copy(done = true); changed = true }
                continue
            }
            val row = found.second
            val db = row.dbDelay ?: row.depDelay
            when {
                row.cancelled -> { list[i] = c.copy(cancelled = true, done = true); changed = true }
                snap.fresh < row.depEpoch -> {                  // vor der Abfahrt: nur Aenderungen speichern
                    val last = c.samples.lastOrNull()
                    if (last == null || last.own != row.depDelay || last.db != db) {
                        list[i] = c.copy(samples = c.samples + PSample(snap.fresh, row.depDelay, db)); changed = true
                    }
                }
                // abgefahren (Zug hinter dem Einstieg, sonst sicherheitshalber 15 min nach Abfahrt):
                // die DB-Angabe ist jetzt die gemessene Abfahrt
                (row.marker > 0.02 && now > row.depEpoch + 60_000L) || now > row.depEpoch + 15 * 60_000L -> {
                    list[i] = c.copy(actual = db, done = true); changed = true
                }
            }
        }
        if (changed) saveAll(ctx, list)
    }

    /** Check fuer diese Abfahrt (falls aufgezeichnet und abgeschlossen). */
    fun forRow(ctx: Context, relId: String, row: Row): PCheck? {
        @Suppress("UNUSED_VARIABLE") val v = version
        val id = idOf(row.train, row.depEpoch - row.depDelay * 60_000L, relId)
        return all(ctx).firstOrNull { it.id == id && it.done && it.actual != null && it.samples.isNotEmpty() }?.withGps(ctx)
    }

    /** Bist du mitgefahren, zaehlt die per GPS gemessene Abfahrt am Einstieg statt der DB-Angabe. */
    private fun PCheck.withGps(ctx: Context): PCheck {
        val store = de.rb64.live.ride.RideStore
        store.init(ctx)
        val meta = store.history.value.firstOrNull {
            it.train == train && it.from == from && abs(it.started - depEpochPlanned) < 3 * 3600_000L
        } ?: return this
        val ride = store.load(ctx, meta.id) ?: return this
        val dep = de.rb64.live.ride.Analyzer.departures(ride.events)[from] ?: return this
        return copy(actual = Math.round((dep - depEpochPlanned) / 60_000.0).toInt(), gps = true)
    }

    /** Abgeschlossene Checks, neueste zuerst (Liste unter "Fahrten"). */
    fun done(ctx: Context, max: Int = 5): List<PCheck> {
        @Suppress("UNUSED_VARIABLE") val v = version
        return all(ctx).filter { it.done && it.actual != null && it.samples.isNotEmpty() }.takeLast(max).reversed()
            .map { it.withGps(ctx) }
    }

    /** Neuester abgeschlossener Check der letzten 2 h, nicht weggeklickt. */
    fun recent(ctx: Context, now: Long = System.currentTimeMillis()): PCheck? {
        @Suppress("UNUSED_VARIABLE") val v = version
        return all(ctx).lastOrNull {
            it.done && !it.dismissed && it.actual != null && it.samples.isNotEmpty() &&
                (it.depActual ?: 0) > now - 2 * 3600_000L
        }
    }

    fun dismiss(ctx: Context, id: String) = saveAll(ctx, all(ctx).map { if (it.id == id) it.copy(dismissed = true) else it })

    private fun toJson(c: PCheck) = JSONObject().apply {
        put("id", c.id); put("n", c.train); put("rel", c.relId); put("from", c.from); put("to", c.to)
        put("pdp", c.depPlanned); put("pep", c.depEpochPlanned)
        put("s", JSONArray().apply { c.samples.forEach { put(JSONArray().put(it.ts).put(it.own).put(it.db)) } })
        c.actual?.let { put("a", it) }
        put("cx", c.cancelled); put("done", c.done); put("dis", c.dismissed)
    }

    private fun fromJson(o: JSONObject): PCheck {
        val s = o.optJSONArray("s") ?: JSONArray()
        return PCheck(o.getString("id"), o.getString("n"), o.getString("rel"), o.optString("from"), o.optString("to"),
            o.optString("pdp"), o.optLong("pep"),
            (0 until s.length()).map { s.getJSONArray(it).let { a -> PSample(a.getLong(0), a.getInt(1), a.getInt(2)) } },
            if (o.has("a")) o.getInt("a") else null, o.optBoolean("cx"), o.optBoolean("done"), o.optBoolean("dis"))
    }
}

private val HM_P = SimpleDateFormat("HH:mm", Locale.GERMANY)
private fun hm(t: Long) = HM_P.format(Date(t))
private fun pl(v: Int) = if (v >= 1) "+$v" else "pünktlich"

/** Einordnung in einem Satz: wer lag wann richtig. */
fun PCheck.verdict(): String {
    val a = actual ?: return ""
    val own = rightSince { it.own }
    val db = rightSince { it.db }
    val ownLast = lastValue { it.own }?.first
    return when {
        own != null && db != null && abs(own - db) < 2 * 60_000L -> "Beide lagen ab etwa ${hm(minOf(own, db))} richtig."
        own != null && (db == null || own < db) ->
            if (db == null) "Unsere Prognose lag ab ${hm(own)} richtig, die DB bis zur Abfahrt nicht."
            else "Unsere Prognose lag ab ${hm(own)} richtig, die DB ab ${hm(db)}."
        db != null && (own == null || db < own) ->
            if (ownLast != null && ownLast - a >= 3) "Unsere Prognose war zu vorsichtig (${pl(ownLast)}), tatsächlich ${pl(a)}."
            else if (own == null) "Die DB lag ab ${hm(db)} richtig, unsere Prognose nicht."
            else "Die DB lag ab ${hm(db)} richtig, unsere Prognose ab ${hm(own)}."
        else -> "Beide lagen mehr als ${PCheck.TOL} min daneben."
    }
}

/** Karte "So lag die Prognose für deinen Zug" (nach der Abfahrt). */
@Composable
fun PCheckCard(c: PCheck, compact: Boolean = false, onDismiss: (() -> Unit)? = null) {
    val a = c.actual ?: return
    if (compact) {
        // kurze Fassung fuer die Abfahrten: eine Zahlenzeile + Einordnung, Details im Detailblatt
        Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.rCard)).background(Rb.c.surface)
            .padding(start = Dim.s16, top = Dim.s12, bottom = Dim.s12, end = Dim.s4), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("${c.from} ${c.depPlanned} → ${c.to}", style = Ts.smallStrong, color = Rb.c.primary)
                Text(listOfNotNull(c.lastValue { it.own }?.let { "Prognose ${pl(it.first)}" },
                    c.lastValue { it.db }?.let { "DB ${pl(it.first)}" }, "tatsächlich ${pl(a)}" + if (c.gps) " (GPS)" else "").joinToString(" · "),
                    style = Ts.textStrong)
                Text(c.verdict(), style = Ts.small, color = Rb.c.muted)
            }
            if (onDismiss != null) de.rb64.live.ui.IconBtn(RbIcons.close, "Ausblenden", onDismiss, tint = Rb.c.muted)
        }
        return
    }
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(Dim.rCard)).background(Rb.c.surface).padding(Dim.s16),
        verticalArrangement = Arrangement.spacedBy(Dim.s8)) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Prognose-Check", style = Ts.smallStrong, color = Rb.c.primary)
                Text("So lag die Prognose für deinen Zug", style = Ts.textStrong)
                Text("${c.train} · ${c.from} ${c.depPlanned} · abgefahren ${c.depActual?.let { hm(it) } ?: ""}",
                    style = Ts.small, color = Rb.c.muted)
            }
            if (onDismiss != null) de.rb64.live.ui.IconBtn(RbIcons.close, "Ausblenden", onDismiss, tint = Rb.c.muted)
        }
        @Composable
        fun line(label: String, v: Pair<Int, Long>?, strong: Boolean) {
            if (v == null) return
            Row(Modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$label · seit ${hm(v.second)}", style = Ts.text, modifier = Modifier.weight(1f))
                Text(pl(v.first), style = Ts.textStrong, color = if (strong) Rb.c.primary else Rb.c.muted)
            }
        }
        line("Unsere Prognose", c.lastValue { it.own }, true)
        line("DB", c.lastValue { it.db }, false)
        Row(Modifier.fillMaxWidth().heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (c.gps) "Tatsächlich abgefahren (per GPS gemessen)" else "Tatsächlich abgefahren", style = Ts.textStrong,
                modifier = Modifier.weight(1f))
            val (fg, bg) = when { a >= 6 -> Rb.c.bad to Rb.c.badBg; a >= 3 -> Rb.c.warn to Rb.c.warnBg; else -> Rb.c.ok to Rb.c.okBg }
            de.rb64.live.ui.Pill(pl(a), fg, bg)
        }
        Text(c.verdict(), style = Ts.small, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
            .background(Rb.c.background).padding(horizontal = 10.dp, vertical = 8.dp))
        Text("„Richtig“ heißt: höchstens ±${PCheck.TOL} min daneben und dabei geblieben, in den 60 min vor der Abfahrt. " +
            "Nur auf deinem Handy gespeichert.", style = Ts.small, color = Rb.c.muted)
    }
}
